import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { RecebiveisDoVendedor } from '../../../apps/seller-app/src/shared/dados';
import { consultar, contar, texto } from '../banco-do-servidor';
import { Cenario, sementeDaRodada, Tela } from '../cenario';
import { esperarForaDaVirada } from '../maquina-do-tempo';
import { vendedorComMaleta } from './apoio-da-fase-6';

/**
 * Cenários 21 e 22 da tarefa 7.11 (ADR-0012, item 7): venda a prazo com parcelas, recebimento do cliente e o estorno no mesmo
 * dia, tudo feito sem internet e enviado pela outbox; o servidor divide as parcelas e distribui o recebimento (o computador
 * nunca calcula parcela), e a cópia local das parcelas chega pelo recebimento. Preço fixo (R$ 100,00) para conferir à mão:
 * 100,00 em 3 parcelas = 33,33 + 33,33 + 33,34.
 */
let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('venda a prazo e recebimento'));
});
afterAll(() => cenario?.desligar());
// O estorno do recebimento só vale no mesmo dia de Brasília: perto da meia-noite espera a virada antes do caso.
beforeEach(() => esperarForaDaVirada());

const dentroDeDias = (dias: number): string =>
  new Date(Date.now() + dias * 86_400_000).toISOString().slice(0, 10);

const parcelasNoServidor = (vendaId: string) =>
  consultar<{ parcela: number; valor: string; valor_pago: string; status: string }>(
    `SELECT parcela, valor::text, valor_pago::text, status FROM corona.contas_receber
      WHERE venda_id = ${texto(vendaId)} AND devedor_tipo = 'CLIENTE' ORDER BY parcela`,
  );

describe('duas pontas: venda a prazo e recebimento (casos 21 e 22)', () => {
  it('21. venda a prazo em 3 parcelas, dois recebimentos e um estorno sem internet: o servidor divide e distribui, o computador recebe as parcelas', async () => {
    const [peca] = await cenario.pecas(1, { preco: '100.00' });
    const v = await vendedorComMaleta(cenario, 'Aurora', { [peca!.id]: 2 });
    const a = v.vendedor.aparelho;
    await a.sincronizar();

    a.semInternet();
    const vendaId = await Tela.venderAPrazo(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: peca!.id,
      parcelas: 3,
      primeiroVencimento: dentroDeDias(10),
    });
    const r50 = await Tela.receber(a, { vendaId, valor: '50.00', forma: 'PIX' });
    const r20 = await Tela.receber(a, { vendaId, valor: '20.00' });
    const estorno = await Tela.estornarRecebimento(a, r20, vendaId);

    // Sem internet: nenhuma parcela é inventada aqui; o que falta é a prévia (100,00 - 50,00).
    const antes = await a.pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' });
    expect(antes.dosClientes).toHaveLength(1);
    expect(antes.dosClientes[0]).toMatchObject({
      vendaId,
      aReceber: '50.00',
      aguardandoEnvio: true,
      parcelas: [],
    });
    expect(
      antes.dosClientes[0]?.recebimentos.map((r) => [r.id, r.estornado, r.podeEstornar]),
    ).toEqual([
      [r50, false, true],
      [r20, true, false],
    ]);

    a.comInternet();
    await a.sincronizar();
    const ordem = a.outbox().filter((o) => [vendaId, r50, r20, estorno].includes(o.operacao_id));
    expect(ordem.map((o) => [o.tipo, o.situacao])).toEqual([
      ['VENDA', 'ENVIADO'],
      ['RECEBIMENTO_CLIENTE', 'ENVIADO'],
      ['RECEBIMENTO_CLIENTE', 'ENVIADO'],
      ['ESTORNO_RECEBIMENTO_CLIENTE', 'ENVIADO'],
    ]);

    // O servidor dividiu (a diferença do centavo na última) e distribuiu os 50,00 pelas parcelas, da que vence primeiro.
    expect(await parcelasNoServidor(vendaId)).toEqual([
      { parcela: 1, valor: '33.33', valor_pago: '33.33', status: 'PAGA' },
      { parcela: 2, valor: '33.33', valor_pago: '16.67', status: 'PARCIAL' },
      { parcela: 3, valor: '33.34', valor_pago: '0.00', status: 'ABERTA' },
    ]);
    // Os recebimentos e o estorno existem uma vez cada, ligados à venda.
    const um = async (sql: string) => expect(await contar(sql)).toBe(1);
    await um(
      `SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ${texto(r50)} AND posterior AND forma = 'PIX'`,
    );
    await um(
      `SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ${texto(estorno)} AND estorna_id = ${texto(r20)} AND valor = -20`,
    );

    // A cópia local das parcelas chegou pelo recebimento, com os mesmos valores do servidor.
    const depois = await a.pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' });
    const venda = depois.dosClientes[0];
    expect(venda?.aguardandoEnvio).toBe(false);
    expect(venda?.aReceber).toBe('50.00');
    expect(venda?.parcelas.map((p) => [p.numero, p.valor, p.valorPago, p.situacao])).toEqual([
      [1, '33.33', '33.33', 'PAGA'],
      [2, '33.33', '16.67', 'PARCIAL'],
      [3, '33.34', '0.00', 'ABERTA'],
    ]);
    expect(venda?.recebimentos.map((r) => [r.id, r.estornado])).toEqual([
      [r50, false],
      [r20, true],
    ]);
    expect(a.estado().aguardando).toBe(0);

    await cenario.afirmarNoFim([a]);
  });

  it('22. rede ruim no envio do recebimento: corte depois de gravar e 502; o recebimento fica uma vez só e as parcelas batem', async () => {
    const [peca] = await cenario.pecas(1, { preco: '100.00' });
    const v = await vendedorComMaleta(cenario, 'Iracema', { [peca!.id]: 2 });
    const a = v.vendedor.aparelho;
    await a.sincronizar();

    a.semInternet();
    const vendaId = await Tela.venderAPrazo(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: peca!.id,
      parcelas: 2,
      primeiroVencimento: dentroDeDias(5),
      pagoAgora: '40.00',
    });
    const recebimento = await Tela.receber(a, { vendaId, valor: '30.00' });

    const pedidosAntes = a.proxy.historico().length;
    a.comInternet({
      sorteio: [],
      porCaminho: [
        { caminho: '/api/vendedor/sync/push', roteiro: ['CORTE_DEPOIS_DE_GRAVAR', 'ERRO_502'] },
        { caminho: '/api/vendedor/sync/pull', roteiro: ['CORTE_NO_MEIO_DA_RESPOSTA'] },
      ],
    });
    await a.sincronizar(120_000);
    const pushes = a.proxy
      .historico()
      .slice(pedidosAntes)
      .filter((p) => p.caminho.startsWith('/api/vendedor/sync/push'));
    expect(pushes.slice(0, 2).map((p) => p.falha)).toEqual(['CORTE_DEPOIS_DE_GRAVAR', 'ERRO_502']);
    expect(a.outbox().find((o) => o.operacao_id === recebimento)?.codigo_do_resultado).toBe(
      'JA_RECEBIDO',
    );

    // 100,00 − 40,00 pagos na hora = 60,00 em 2 parcelas de 30,00; os 30,00 recebidos quitam a primeira.
    expect(await parcelasNoServidor(vendaId)).toEqual([
      { parcela: 1, valor: '30.00', valor_pago: '30.00', status: 'PAGA' },
      { parcela: 2, valor: '30.00', valor_pago: '0.00', status: 'ABERTA' },
    ]);
    expect(
      await contar(
        `SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ${texto(recebimento)}`,
      ),
    ).toBe(1);
    expect(
      await contar(
        `SELECT count(*) FROM corona.baixas_receber WHERE pagamento_cliente_id = ${texto(recebimento)}`,
      ),
    ).toBe(1);
    const venda = (await a.pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }))
      .dosClientes[0];
    expect(venda?.aReceber).toBe('30.00');
    expect(a.estado().aguardando).toBe(0);

    await cenario.afirmarNoFim([a]);
  });
});
