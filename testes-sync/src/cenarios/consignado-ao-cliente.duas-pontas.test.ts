import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type {
  ClienteCompleto,
  DetalheDaNota,
  PainelDaSemana,
} from '../../../apps/seller-app/src/shared/dados-do-consignado';
import { consultar, contar, texto } from '../banco-do-servidor';
import { Cenario, sementeDaRodada } from '../cenario';
import { esperarAte } from '../esperar';

/**
 * Cenário do consignado ao cliente (tarefa 9.2; ADR-0016): o representante trabalha SEM internet (semana, cliente com as
 * três fotos, pedido, acerto com pagamento parcial, Lançar pagamento e estorno), a internet volta com a rede cortando depois
 * de o servidor gravar e com um 502, e no fim o computador e o servidor dizem a mesma coisa (nota, pendência, recibos,
 * saldo da maleta e as fotos criptografadas que subiram). Peça de R$ 100,00 para conferir à mão: 5 levadas, 1 devolvida,
 * VD 400, comissão 30% = 120, bruto 280, pago 100, ficou devendo 180.
 */
let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('consignado ao cliente'));
});
afterAll(() => cenario?.desligar());

const PUSH = '/api/vendedor/sync/push';

/** Um JPEG mínimo (o servidor e o app só conferem o começo do arquivo); o sufixo deixa cada foto com o seu hash. */
const JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFAEBAAAAAAAAAAAAAAAAAAAAAP/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/AL+AB//Z',
  'base64',
);
const foto = (sufixo: string): string =>
  `data:image/jpeg;base64,${Buffer.concat([JPEG, Buffer.from(sufixo)]).toString('base64')}`;

describe('duas pontas: o consignado ao cliente sem internet e a volta com rede ruim', () => {
  it('24. semana, cliente com fotos, pedido, acerto com pendência, pagamento e estorno sem internet; corte e 502 na volta; tudo igual dos dois lados', async () => {
    const [peca] = await cenario.pecas(1, { preco: '100.00' });
    const rosa = await cenario.vendedor('Rosa');
    const a = rosa.aparelho;
    const maletaId = await cenario.dona.maleta(rosa.vendedorId, { [peca!.id]: 10 });
    await a.sincronizar();

    a.semInternet();
    const semanaId = randomUUID();
    await a.pedir({ operacao: 'consignado.semanaNova', id: semanaId, numero: 65 });

    // Cliente com as três fotos: o cadastro leva só os hashes e sai pela outbox no formato 2.
    const hashes: string[] = [];
    for (const f of ['cliente', 'frente', 'verso'])
      hashes.push(
        (await a.pedir<{ hash: string }>({ operacao: 'consignado.fotoGuardar', dataUrl: foto(f) }))
          .hash,
      );
    const clienteId = randomUUID();
    await a.pedir({
      operacao: 'consignado.clienteCadastrar',
      cliente: {
        id: clienteId,
        nome: 'Vendedora Ana',
        telefone: '67 99999-0000',
        enderecoLogradouro: 'Rua das Flores',
        enderecoNumero: '10',
        enderecoBairro: 'Centro',
        enderecoCidade: 'Ponta Porã',
        enderecoUf: 'MS',
        documento: 'CI-1234567',
        documentoTipo: 'CI',
        comissaoPercentual: '30',
        fotoHash: hashes[0]!,
        fotoDocumentoFrenteHash: hashes[1]!,
        fotoDocumentoVersoHash: hashes[2]!,
      },
    });

    // Novo Pedido: 5 peças da maleta.
    await a.pedir({ operacao: 'consignado.pedidoDefinir', clienteId, remessaId: maletaId });
    for (let i = 0; i < 5; i += 1)
      await a.pedir({ operacao: 'consignado.pedidoBipar', codigo: peca!.codigo });
    const notaId = randomUUID();
    const finalizado = await a.pedir<{ numeroDaNota: string }>({
      operacao: 'consignado.pedidoFinalizar',
      id: notaId,
    });
    expect(finalizado.numeroDaNota).toBe('0000065-01');

    // Acerto: 1 volta, 4 vendidas (VD 400), comissão 30% (120), bruto 280; pagou 100 e ficou devendo 180.
    const acerto = await a.pedir<{ nota: DetalheDaNota }>({
      operacao: 'consignado.acertoSalvar',
      acerto: {
        id: randomUUID(),
        reciboId: randomUUID(),
        notaId,
        comissaoPercentual: '30',
        devolvidos: [{ produtoId: peca!.id, remessaId: maletaId, quantidade: 1 }],
        formas: [{ forma: 'DINHEIRO', valor: '100.00' }],
      },
    });
    expect(acerto.nota.situacao).toBe('PENDENTE');
    expect(acerto.nota.acerto?.ficouDevendo).toBe('180.00');
    expect(acerto.nota.saldoDaNota).toBe('180.00');

    // Lançar pagamento de 50 (pendência 130) e estornar (volta a 180).
    const reciboLancado = randomUUID();
    const pago = await a.pedir<{ cliente: ClienteCompleto }>({
      operacao: 'consignado.pagamento',
      pagamento: { id: reciboLancado, clienteId, formas: [{ forma: 'PIX', valor: '50.00' }] },
    });
    expect(pago.cliente.pendencia).toBe('130.00');
    const estornado = await a.pedir<{ cliente: ClienteCompleto }>({
      operacao: 'consignado.estornarRecibo',
      id: randomUUID(),
      reciboId: reciboLancado,
      motivo: 'Pix lançado por engano',
    });
    expect(estornado.cliente.pendencia).toBe('180.00');
    // Tudo isto sem internet: nada saiu, e nenhuma operação foi recusada pelo computador.
    expect(a.outbox().every((o) => o.situacao === 'AGUARDANDO')).toBe(true);
    expect(a.outbox().map((o) => [o.tipo, o.formato])).toEqual([
      ['SEMANA_NOVA', 1],
      ['CLIENTE_NOVO', 2],
      ['NOTA_CONSIGNADO', 1],
      ['ACERTO_NOTA', 1],
      ['PAGAMENTO_CONSIGNADO', 1],
      ['ESTORNO_RECIBO', 1],
    ]);

    // A internet volta, mas o 1º envio corta depois de o servidor gravar e o 2º responde 502.
    a.comInternet({
      sorteio: [],
      porCaminho: [{ caminho: PUSH, roteiro: ['CORTE_DEPOIS_DE_GRAVAR', 'ERRO_502'] }],
    });
    await a.sincronizar();
    await esperarAte(
      () =>
        a.ler<{ n: number }>(
          'SELECT count(*) AS n FROM fila_fotos_cliente WHERE enviada_em IS NULL',
        )[0]!.n === 0,
      { limiteMs: 60_000, intervaloMs: 100, descricao: () => 'as três fotos do cliente subirem' },
    );
    expect(a.outbox().every((o) => o.situacao === 'ENVIADO')).toBe(true);

    // O servidor tem tudo uma vez só, com as contas dele (recalculadas) iguais às do computador.
    const v = texto(rosa.vendedorId);
    expect(
      await contar(
        `SELECT count(*) FROM corona.semanas_vendedor WHERE id = ${texto(semanaId)} AND numero = 65`,
      ),
    ).toBe(1);
    expect(
      await contar(
        `SELECT count(*) FROM corona.clientes WHERE id = ${texto(clienteId)} AND vendedor_responsavel_id = ${v} AND comissao_percentual = 30`,
      ),
    ).toBe(1);
    expect(
      await contar(
        `SELECT count(*) FROM corona.fotos_de_cliente WHERE cliente_id = ${texto(clienteId)}`,
      ),
    ).toBe(3);
    expect(
      await contar(
        `SELECT count(*) FROM corona.notas_consignado WHERE id = ${texto(notaId)} AND sequencia = 1`,
      ),
    ).toBe(1);
    const [acertoNoServidor] = await consultar<Record<string, string>>(
      `SELECT valor_vd::text, comissao::text, bruto::text, total_pago::text, ficou_devendo::text FROM corona.acertos_nota WHERE nota_id = ${texto(notaId)}`,
    );
    expect(acertoNoServidor).toMatchObject({
      valor_vd: '400.00',
      comissao: '120.00',
      bruto: '280.00',
      total_pago: '100.00',
      ficou_devendo: '180.00',
    });
    expect(
      await contar(`SELECT count(*) FROM corona.recibos_consignado WHERE vendedor_id = ${v}`),
    ).toBe(2);
    expect(
      await contar(
        `SELECT count(*) FROM corona.recibos_consignado WHERE vendedor_id = ${v} AND estornado_em IS NOT NULL`,
      ),
    ).toBe(1);

    // Dos dois lados a mesma pendência, a mesma situação e o mesmo saldo da maleta.
    const local = await a.pedir<ClienteCompleto>({ operacao: 'consignado.cliente', id: clienteId });
    expect(local.pendencia).toBe('180.00');
    expect(local.fotosEsperandoEnvio).toBe(0);
    const painel = await a.pedir<PainelDaSemana>({ operacao: 'consignado.painel' });
    expect(painel.pedidos.map((p) => [p.numero, p.situacao, p.valorLiquido])).toEqual([
      ['0000065-01', 'PENDENTE', '280.00'],
    ]);
    const saldoNoServidor = await contar(`
      SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
               WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
        FROM corona.itens_remessa i
       WHERE i.remessa_id = ${texto(maletaId)} AND i.produto_id = ${texto(peca!.id)} AND i.deleted_at IS NULL`);
    expect(saldoNoServidor).toBe(10 - 5 + 1);
    expect(
      a.ler<{ saldo: number }>(
        `SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM movimentos_remessa m
                 WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.deleted_at IS NULL), 0) AS saldo
           FROM itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL`,
        maletaId,
        peca!.id,
      )[0]!.saldo,
    ).toBe(6);

    await cenario.afirmarNoFim([a]);
  });

  it('25. nota e recibo com a mesma sequência no ciclo real, com o relógio do computador atrasado e sem internet; igual dos dois lados depois do pull', async () => {
    const [peca] = await cenario.pecas(1, { preco: '100.00' });
    const ana = await cenario.vendedor('Ana', { deslocamentoMs: -86_400_000 });
    const a = ana.aparelho;
    const maletaId = await cenario.dona.maleta(ana.vendedorId, { [peca!.id]: 10 });
    await a.sincronizar();
    a.semInternet();
    await a.pedir({ operacao: 'consignado.semanaNova', id: randomUUID(), numero: 70 });
    const hashes: string[] = [];
    for (const f of ['c2', 'f2', 'v2'])
      hashes.push(
        (await a.pedir<{ hash: string }>({ operacao: 'consignado.fotoGuardar', dataUrl: foto(f) }))
          .hash,
      );
    const clienteId = randomUUID();
    await a.pedir({
      operacao: 'consignado.clienteCadastrar',
      cliente: {
        id: clienteId,
        nome: 'Cliente da Ana',
        telefone: '67 99999-1111',
        enderecoLogradouro: 'Rua Um',
        enderecoNumero: '1',
        enderecoBairro: 'Centro',
        enderecoCidade: 'Ponta Porã',
        enderecoUf: 'MS',
        documento: 'CI-7654321',
        documentoTipo: 'CI',
        comissaoPercentual: '30',
        fotoHash: hashes[0]!,
        fotoDocumentoFrenteHash: hashes[1]!,
        fotoDocumentoVersoHash: hashes[2]!,
      },
    });
    // O pedido sai com o relógio do computador 1 dia atrasado (a nota é de ontem).
    const notas: string[] = [];
    for (let k = 0; k < 2; k += 1) {
      await a.pedir({ operacao: 'consignado.pedidoDefinir', clienteId, remessaId: maletaId });
      await a.pedir({ operacao: 'consignado.pedidoBipar', codigo: peca!.codigo });
      const id = randomUUID();
      await a.pedir({ operacao: 'consignado.pedidoFinalizar', id });
      notas.push(id);
    }
    // O relógio volta ao certo; o acerto da 1ª nota paga 40 (recibo R 01 com a mesma sequência da nota 01).
    a.relogio.deslocamentoMs = 0;
    const acerto = await a.pedir<{ nota: DetalheDaNota }>({
      operacao: 'consignado.acertoSalvar',
      acerto: {
        id: randomUUID(),
        reciboId: randomUUID(),
        notaId: notas[0]!,
        comissaoPercentual: '30',
        devolvidos: [],
        formas: [{ forma: 'DINHEIRO', valor: '40.00' }],
      },
    });
    expect(acerto.nota.acerto?.recibo?.numero).toMatch(/^R \d{7}-01$/);
    // Lançar pagamento: recibo 02, enquanto a nota 02 continua sendo nota (contadores separados).
    await a.pedir({
      operacao: 'consignado.pagamento',
      pagamento: { id: randomUUID(), clienteId, formas: [{ forma: 'PIX', valor: '10.00' }] },
    });
    a.comInternet({
      sorteio: [],
      porCaminho: [{ caminho: PUSH, roteiro: ['CORTE_DEPOIS_DE_GRAVAR'] }],
    });
    await a.sincronizar();
    expect(a.outbox().every((o) => o.situacao === 'ENVIADO')).toBe(true);

    const v = texto(ana.vendedorId);
    for (const tipo of ['NOTA', 'RECIBO'])
      expect(
        await contar(
          `SELECT count(*) FROM corona.numeros_da_semana WHERE vendedor_id = ${v} AND tipo = '${tipo}' AND sequencia IN (1, 2)`,
        ),
      ).toBe(2);
    // Hora do acerto: a Dt. Acerto de hoje é o próprio lançamento (registrado_em = acertado_em), nas duas pontas.
    const [servidor] = await consultar<Record<string, string>>(
      `SELECT (registrado_em = acertado_em)::text AS igual FROM corona.acertos_nota WHERE nota_id = ${texto(notas[0]!)}`,
    );
    expect(servidor).toMatchObject({ igual: 'true' });
    const [local] = a.ler<{ acertado_em: string; registrado_em: string }>(
      'SELECT acertado_em, registrado_em FROM acertos_nota WHERE nota_id = ?',
      notas[0]!,
    );
    expect(local!.registrado_em).toBe(local!.acertado_em);
    const cliente = await a.pedir<ClienteCompleto>({
      operacao: 'consignado.cliente',
      id: clienteId,
    });
    // 1ª nota: bruto 70 (100 menos 30%), pagou 40 + 10 = 50 → 20; a 2ª nota ainda não foi acertada.
    expect(cliente.pendencia).toBe('20.00');
    await cenario.afirmarNoFim([a]);
  });
});
