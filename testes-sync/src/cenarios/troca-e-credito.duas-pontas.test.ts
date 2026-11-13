import { beforeEach, afterAll, beforeAll, describe, expect, it } from 'vitest';
import { MENSAGENS_DA_FASE_6 } from '../../../apps/seller-app/src/main/sync/mensagens-da-fase-6';
import { MENSAGENS_DAS_OPERACOES } from '../../../apps/seller-app/src/main/sync/operacoes-locais';
import type { CreditosDoCliente, EnvioNaoAceito } from '../../../apps/seller-app/src/shared/dados';
import { consultar, contar, texto } from '../banco-do-servidor';
import { Cenario, mensagemDaRecusa, sementeDaRodada, Tela } from '../cenario';
import { esperarForaDaVirada } from '../maquina-do-tempo';
import { saldoLocal, saldoNoServidor, vendedorComMaleta } from './apoio-da-fase-6';

/**
 * Cenários 17 a 20 do plano da 6.11 (ADR-0014, itens 8, 9 e 11): troca sem internet com a peça nova vendida no mesmo lote,
 * crédito gerado e usado sem internet, cancelar a troca depois de enviada e rede ruim no meio do envio da troca. Os casos 18b
 * e 19b (dúvida E3) são a dona mexendo no mesmo dinheiro enquanto o computador está sem internet. Preços fixos (A a 80,00 e B
 * a 50,00: sobram 30,00 de crédito) para os valores serem conferidos à mão.
 */
let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('troca e crédito'));
});
afterAll(() => cenario?.desligar());
// Cancelar troca só vale no mesmo dia de Brasília: perto da meia-noite espera a virada antes do caso.
beforeEach(() => esperarForaDaVirada());

async function duasPecas(): Promise<{ a: string; b: string }> {
  const [a] = await cenario.pecas(1, { preco: '80.00' });
  const [b] = await cenario.pecas(1, { preco: '50.00' });
  return { a: a!.id, b: b!.id };
}

const trocaNoServidor = (id: string) =>
  consultar<{ situacao: string; cancelamento_id: string | null; diferenca: string }>(
    `SELECT situacao, cancelamento_id::text, diferenca::text FROM corona.trocas WHERE id = ${texto(id)}`,
  ).then((l) => l[0]);

const creditoNoServidor = (id: string) =>
  consultar<{ situacao: string; valor: string; valor_utilizado: string; vendedor_id: string }>(
    `SELECT situacao, valor::text, valor_utilizado::text, vendedor_id::text FROM corona.creditos_cliente WHERE id = ${texto(id)}`,
  ).then((l) => l[0]);

const naoAceitos = (a: { pedir: <T>(p: { operacao: 'envios.naoAceitos' }) => Promise<T> }) =>
  a.pedir<EnvioNaoAceito[]>({ operacao: 'envios.naoAceitos' });

describe('duas pontas: troca e crédito (casos 17 a 20)', () => {
  it('17. troca sem internet com a peça nova vendida no mesmo lote: tudo aceito na ordem e saldo zero exato', async () => {
    const { a: A, b: B } = await duasPecas();
    const v = await vendedorComMaleta(cenario, 'Gisele', { [A]: 2, [B]: 2 });
    const a = v.vendedor.aparelho;
    const clienteY = await Tela.cliente(a, 'Cliente Y da Gisele');
    await a.sincronizar();

    // Sem internet, um lote só: venda de A ao cliente X, troca de A por B dessa venda (ainda não enviada: dependência),
    // venda da última B ao cliente Y; uma quarta operação com B é recusada (saldo zero na maleta).
    a.semInternet();
    const vendaA = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: A,
    });
    const troca = await Tela.trocar(a, {
      vendaId: vendaA,
      devolve: [{ produtoId: A, quantidade: 1 }],
      maletaId: v.maletaId,
      leva: [{ produtoId: B, quantidade: 1 }],
    });
    const vendaB = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: clienteY,
      produtoId: B,
    });
    expect(saldoLocal(a, v.maletaId, B)).toBe(0);
    const quarta = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: clienteY,
      produtoId: B,
    }).then(
      () => 'aceitou',
      (e: unknown) => String(e),
    );
    expect(quarta).toContain(MENSAGENS_DAS_OPERACOES.SEM_SALDO);

    // Internet: o lote vai num envio e tudo é aceito, na ordem.
    a.comInternet();
    await a.sincronizar();
    const ordem = a.outbox().filter((o) => [vendaA, troca.id, vendaB].includes(o.operacao_id));
    expect(ordem.map((o) => o.operacao_id)).toEqual([vendaA, troca.id, vendaB]);
    expect(ordem.map((o) => o.situacao)).toEqual(['ENVIADO', 'ENVIADO', 'ENVIADO']);

    // No servidor: B com saldo 0, A com 2 (venda −1 e TROCA_ENTRADA +1), três vendas (a da troca com a troca ligada),
    // TROCA_SAIDA de B e o crédito da peça devolvida (80,00) aberto, com 50,00 já usados na venda nova da troca e 30,00 de
    // saldo. Os mesmos números no computador.
    expect(await saldoNoServidor(v.maletaId, B)).toBe(0);
    expect(await saldoNoServidor(v.maletaId, A)).toBe(2);
    expect(saldoLocal(a, v.maletaId, B)).toBe(0);
    expect(saldoLocal(a, v.maletaId, A)).toBe(2);
    expect(
      await contar(
        `SELECT count(*) FROM corona.vendas WHERE vendedor_id = ${texto(v.vendedor.vendedorId)}`,
      ),
    ).toBe(3);
    expect(await trocaNoServidor(troca.id)).toMatchObject({
      situacao: 'ATIVA',
      diferenca: '-30.00',
    });
    expect(
      await contar(
        `SELECT count(*) FROM corona.movimentos_remessa WHERE venda_id = ${texto(troca.vendaNovaId)} AND tipo = 'TROCA_SAIDA'`,
      ),
    ).toBe(1);
    expect(
      await contar(
        `SELECT count(*) FROM corona.movimentos_remessa WHERE devolucao_id = ${texto(troca.devolucaoId)} AND tipo = 'TROCA_ENTRADA'`,
      ),
    ).toBe(1);
    expect(await creditoNoServidor(troca.creditoId)).toMatchObject({
      situacao: 'ABERTO',
      valor: '80.00',
      valor_utilizado: '50.00',
    });
    const creditos = await a.pedir<CreditosDoCliente>({
      operacao: 'creditos.doCliente',
      clienteId: v.clienteId,
    });
    expect(creditos.total).toBe('30.00');

    await cenario.afirmarNoFim([a]);
  });

  it('18. crédito gerado e usado sem internet: fecha a conta, nenhum crédito sem saldo e nada chega a outro vendedor', async () => {
    const { a: A, b: B } = await duasPecas();
    const v = await vendedorComMaleta(cenario, 'Heloisa', { [A]: 3, [B]: 3 });
    const a = v.vendedor.aparelho;
    const outro = await vendedorComMaleta(cenario, 'Irene', { [A]: 1 });
    const vendaA = await (async () => {
      const id = await Tela.vender(a, {
        maletaId: v.maletaId,
        clienteId: v.clienteId,
        produtoId: A,
      });
      await a.sincronizar();
      return id;
    })();

    a.semInternet();
    const troca = await Tela.trocar(a, {
      vendaId: vendaA,
      devolve: [{ produtoId: A, quantidade: 1 }],
      maletaId: v.maletaId,
      leva: [{ produtoId: B, quantidade: 1 }],
    });
    // Venda de 50,00 ao mesmo cliente com 30,00 de crédito (o saldo que o computador mostra) e 20,00 em dinheiro.
    const comCredito = await Tela.venderComCredito(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: B,
      creditoId: troca.creditoId,
    });
    // Um segundo uso do mesmo crédito é recusado no app (saldo zero).
    const segundoUso = await Tela.tentarVenderComCredito(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: B,
      creditoId: troca.creditoId,
      valorDoCredito: '10.00',
    });
    expect(segundoUso.resultado.ok).toBe(false);

    a.comInternet();
    await a.sincronizar();
    for (const id of [troca.id, comCredito]) {
      expect(a.outbox().find((o) => o.operacao_id === id)?.situacao).toBe('ENVIADO');
    }
    expect(await creditoNoServidor(troca.creditoId)).toMatchObject({
      situacao: 'UTILIZADO',
      valor_utilizado: '80.00',
    });
    expect(
      await consultar<{ valor: string }>(
        `SELECT valor::text FROM corona.usos_credito WHERE credito_id = ${texto(troca.creditoId)} AND tipo = 'USO' ORDER BY usado_em`,
      ),
    ).toEqual([{ valor: '50.00' }, { valor: '30.00' }]);
    expect(
      await contar(
        `SELECT count(*) FROM corona.creditos_sem_saldo WHERE venda_id = ${texto(comCredito)}`,
      ),
    ).toBe(0);
    // A conta de sempre da venda que usou o crédito: 30,00 em crédito e 20,00 em dinheiro, 50,00 no total.
    expect(
      await consultar<{ forma: string; valor: string }>(
        `SELECT forma, valor::text FROM corona.pagamentos_cliente WHERE venda_id = ${texto(comCredito)} ORDER BY forma`,
      ),
    ).toEqual([
      { forma: 'CREDITO', valor: '30.00' },
      { forma: 'DINHEIRO', valor: '20.00' },
    ]);

    // O outro vendedor, com o computador ligado, não recebe nada do crédito nem da troca.
    await outro.vendedor.aparelho.sincronizar();
    expect(
      outro.vendedor.aparelho.ler<{ n: number }>('SELECT count(*) AS n FROM creditos_cliente')[0]!
        .n,
    ).toBe(0);
    expect(
      outro.vendedor.aparelho.ler<{ n: number }>('SELECT count(*) AS n FROM trocas')[0]!.n,
    ).toBe(0);

    await cenario.afirmarNoFim([a, outro.vendedor.aparelho]);
  });

  it('18b. a dona devolve pela loja antes da troca chegar: troca e venda com o crédito são recusadas e o app desfaz', async () => {
    const { a: A, b: B } = await duasPecas();
    const v = await vendedorComMaleta(cenario, 'Joana', { [A]: 3, [B]: 3 });
    const a = v.vendedor.aparelho;
    const vendaA = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: A,
    });
    await a.sincronizar();

    // O computador fica sem internet; a dona devolve a peça inteira pela loja sem o vendedor saber.
    a.semInternet();
    await cenario.dona.devolverPelaLoja({
      vendaId: vendaA,
      itens: [{ produtoId: A, quantidade: 1, estado: 'BOA' }],
    });
    const troca = await Tela.trocar(a, {
      vendaId: vendaA,
      devolve: [{ produtoId: A, quantidade: 1 }],
      maletaId: v.maletaId,
      leva: [{ produtoId: B, quantidade: 1 }],
    });
    const comCredito = await Tela.venderComCredito(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: B,
      creditoId: troca.creditoId,
    });

    a.comInternet();
    await a.sincronizar();
    // A troca é recusada (acima do vendido: o impossível) e a venda com o crédito dela vem recusada por depender dela.
    const saidas = a.outbox().filter((o) => [troca.id, comCredito].includes(o.operacao_id));
    expect(saidas.map((o) => o.situacao)).toEqual(['RECUSADO', 'RECUSADO']);
    expect(saidas[1]?.codigo_do_resultado).toBe('DEPENDE_DE_ENVIO_RECUSADO');
    expect(await trocaNoServidor(troca.id)).toBeUndefined();
    expect(await creditoNoServidor(troca.creditoId)).toBeUndefined();
    expect(
      await contar(
        `SELECT count(*) FROM corona.vendas WHERE id IN (${texto(troca.vendaNovaId)}, ${texto(comCredito)})`,
      ),
    ).toBe(0);
    // O app desfaz as provisórias (saldo, crédito) e mostra os dois em "envios não aceitos".
    expect(
      a.ler<{ n: number }>(
        'SELECT count(*) AS n FROM creditos_cliente WHERE id = ?',
        troca.creditoId,
      )[0]!.n,
    ).toBe(0);
    expect(saldoLocal(a, v.maletaId, B)).toBe(3);
    const lista = await naoAceitos(a);
    expect(lista.map((l) => l.id)).toEqual(expect.arrayContaining([troca.id, comCredito]));
    // A dona os vê nos envios com problema.
    const problemas = (await cenario.dona.enviosComProblema()).map((e) => e.id);
    expect(problemas).toEqual(expect.arrayContaining([troca.id, comCredito]));

    await cenario.afirmarNoFim([a]);
  });

  it('19. cancelar a troca depois de enviada: registros de sinal oposto no servidor e no computador; com o crédito usado, o app recusa', async () => {
    const { a: A, b: B } = await duasPecas();
    const v = await vendedorComMaleta(cenario, 'Karina', { [A]: 3, [B]: 3 });
    const a = v.vendedor.aparelho;
    const vendaA = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: A,
    });
    const troca = await Tela.trocar(a, {
      vendaId: vendaA,
      devolve: [{ produtoId: A, quantidade: 1 }],
      maletaId: v.maletaId,
      leva: [{ produtoId: B, quantidade: 1 }],
    });
    await a.sincronizar();

    // No mesmo dia, sem internet: Cancelar troca.
    a.semInternet();
    const cancelamento = await Tela.cancelarTroca(a, troca.id);
    a.comInternet();
    await a.sincronizar();
    expect(a.outbox().find((o) => o.operacao_id === cancelamento)?.situacao).toBe('ENVIADO');
    expect(await trocaNoServidor(troca.id)).toMatchObject({
      situacao: 'CANCELADA',
      cancelamento_id: cancelamento,
    });
    // Nada apagado: a troca ganha movimentos de sinal oposto (entrada +1 e a saída dela de volta), o crédito é cancelado e a
    // venda nova da troca é estornada.
    expect(
      await consultar<{ n: number; soma: number }>(
        `SELECT count(*)::int AS n, coalesce(sum(quantidade), 0)::int AS soma FROM corona.movimentos_remessa
          WHERE devolucao_id = ${texto(troca.devolucaoId)}`,
      ),
    ).toEqual([{ n: 2, soma: 0 }]);
    expect(
      await consultar<{ n: number; soma: number }>(
        `SELECT count(*)::int AS n, coalesce(sum(quantidade), 0)::int AS soma FROM corona.movimentos_remessa
          WHERE venda_id = ${texto(troca.vendaNovaId)}`,
      ),
    ).toEqual([{ n: 2, soma: 0 }]);
    expect((await creditoNoServidor(troca.creditoId))?.situacao).toBe('CANCELADO');
    expect(
      (
        await consultar<{ status: string }>(
          `SELECT status FROM corona.vendas WHERE id = ${texto(troca.vendaNovaId)}`,
        )
      )[0]?.status,
    ).toBe('ESTORNADA');
    expect(saldoLocal(a, v.maletaId, A)).toBe(2);
    expect(await saldoNoServidor(v.maletaId, A)).toBe(2);
    expect(await saldoNoServidor(v.maletaId, B)).toBe(3);

    // Variação: com o crédito já usado, o app recusa o cancelamento com o texto do servidor.
    const vendaA2 = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: A,
    });
    const troca2 = await Tela.trocar(a, {
      vendaId: vendaA2,
      devolve: [{ produtoId: A, quantidade: 1 }],
      maletaId: v.maletaId,
      leva: [{ produtoId: B, quantidade: 1 }],
    });
    await Tela.venderComCredito(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: B,
      creditoId: troca2.creditoId,
    });
    await a.sincronizar();
    const recusa = await Tela.tentarCancelarTroca(a, troca2.id);
    expect(mensagemDaRecusa(recusa.resultado)).toBe(MENSAGENS_DA_FASE_6.CREDITO_DA_TROCA_JA_USADO);

    await cenario.afirmarNoFim([a]);
  });

  it('19b. a dona transfere o crédito antes de o cancelamento chegar: o cancelamento é recusado e o outro vendedor recebe o crédito', async () => {
    const { a: A, b: B } = await duasPecas();
    const v = await vendedorComMaleta(cenario, 'Lia', { [A]: 3, [B]: 3 });
    const a = v.vendedor.aparelho;
    const outro = await vendedorComMaleta(cenario, 'Marta', { [A]: 1 });
    const vendaA = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: A,
    });
    const troca = await Tela.trocar(a, {
      vendaId: vendaA,
      devolve: [{ produtoId: A, quantidade: 1 }],
      maletaId: v.maletaId,
      leva: [{ produtoId: B, quantidade: 1 }],
    });
    await a.sincronizar();

    // Sem internet o vendedor cancela a troca; nesse meio-tempo a dona transfere o crédito para outro vendedor.
    a.semInternet();
    const cancelamento = await Tela.cancelarTroca(a, troca.id);
    const novo = await cenario.dona.transferirCredito(troca.creditoId, outro.vendedor.vendedorId);

    a.comInternet();
    await a.sincronizar();
    expect(a.outbox().find((o) => o.operacao_id === cancelamento)?.situacao).toBe('RECUSADO');
    expect(await trocaNoServidor(troca.id)).toMatchObject({
      situacao: 'ATIVA',
      cancelamento_id: null,
    });
    expect((await creditoNoServidor(troca.creditoId))?.situacao).toBe('UTILIZADO');
    const lista = await naoAceitos(a);
    expect(lista.map((l) => l.id)).toContain(cancelamento);
    // O outro vendedor recebe o crédito novo; o primeiro não recebe o id dele.
    await outro.vendedor.aparelho.sincronizar();
    expect(
      outro.vendedor.aparelho
        .ler<{ id: string }>('SELECT id FROM creditos_cliente')
        .map((c) => c.id),
    ).toContain(novo.id);
    expect(a.ler<{ id: string }>('SELECT id FROM creditos_cliente').map((c) => c.id)).not.toContain(
      novo.id,
    );

    await cenario.afirmarNoFim([a, outro.vendedor.aparelho]);
  });

  it('20. rede ruim no meio do envio da troca: corte depois de gravar, 502 e corte no recebimento; exatamente um de cada', async () => {
    const { a: A, b: B } = await duasPecas();
    const v = await vendedorComMaleta(cenario, 'Nilda', { [A]: 3, [B]: 3 });
    const a = v.vendedor.aparelho;
    const vendaA = await Tela.vender(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: A,
    });
    await a.sincronizar();

    a.semInternet();
    const troca = await Tela.trocar(a, {
      vendaId: vendaA,
      devolve: [{ produtoId: A, quantidade: 1 }],
      maletaId: v.maletaId,
      leva: [{ produtoId: B, quantidade: 1 }],
    });
    const comCredito = await Tela.venderComCredito(a, {
      maletaId: v.maletaId,
      clienteId: v.clienteId,
      produtoId: B,
      creditoId: troca.creditoId,
    });

    const pedidosAntes = a.proxy.historico().length;
    // O 1º envio corta depois de o servidor gravar, o 2º leva 502 e o 1º recebimento corta no meio da resposta.
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
    // O reenvio volta "já recebido" e há exatamente um de cada no servidor.
    expect(a.outbox().find((o) => o.operacao_id === troca.id)?.codigo_do_resultado).toBe(
      'JA_RECEBIDO',
    );
    const um = async (sql: string) => expect(await contar(sql)).toBe(1);
    await um(`SELECT count(*) FROM corona.trocas WHERE id = ${texto(troca.id)}`);
    await um(`SELECT count(*) FROM corona.devolucoes WHERE id = ${texto(troca.devolucaoId)}`);
    await um(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(troca.vendaNovaId)}`);
    await um(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(comCredito)}`);
    await um(`SELECT count(*) FROM corona.creditos_cliente WHERE id = ${texto(troca.creditoId)}`);
    // Um uso do crédito na venda nova da troca e um na venda que usa o saldo, nunca dois na mesma venda.
    for (const venda of [troca.vendaNovaId, comCredito])
      await um(
        `SELECT count(*) FROM corona.usos_credito WHERE venda_id = ${texto(venda)} AND tipo = 'USO'`,
      );
    await um(
      `SELECT count(*) FROM corona.movimentos_remessa WHERE devolucao_id = ${texto(troca.devolucaoId)} AND tipo = 'TROCA_ENTRADA'`,
    );
    await um(
      `SELECT count(*) FROM corona.movimentos_remessa WHERE venda_id = ${texto(troca.vendaNovaId)} AND tipo = 'TROCA_SAIDA'`,
    );
    await um(
      `SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ${texto(comCredito)} AND forma = 'CREDITO'`,
    );
    expect(a.estado().aguardando).toBe(0);

    await cenario.afirmarNoFim([a]);
  });
});
