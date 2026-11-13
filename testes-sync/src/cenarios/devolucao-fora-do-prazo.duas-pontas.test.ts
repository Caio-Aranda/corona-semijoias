import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { MENSAGENS_DA_FASE_6 } from '../../../apps/seller-app/src/main/sync/mensagens-da-fase-6';
import type {
  AutorizacoesDoVendedor,
  DevolucaoOuTroca,
  PreviaDaDevolucao,
} from '../../../apps/seller-app/src/shared/dados';
import { consultar, contar, texto } from '../banco-do-servidor';
import { Cenario, mensagemDaRecusa, sementeDaRodada, Tela } from '../cenario';
import { DIA_MS, vendaEnvelhecida, vendedorComMaleta } from './apoio-da-fase-6';

/**
 * Cenários 14 a 16 do plano da 6.11 (ESPECIFICACAO §10; ADR-0014, itens 5 e 6): devolução fora do prazo sem internet, com o
 * relógio certo, atrasado e adiantado. A "máquina do tempo" envelhece a venda no servidor (a API corrige toda hora do
 * computador, então sem ela o servidor nunca acharia nada fora do prazo). Os prazos vêm da rota de garantia da dona.
 */
let cenario: Cenario;
let prazoArrependimento: number;
let prazoDefeito: number;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('devolução fora do prazo'));
  const g = await cenario.dona.garantia();
  prazoArrependimento = g.prazoArrependimentoDias;
  prazoDefeito = g.prazoDefeitoDias;
});
afterAll(() => cenario?.desligar());

const devolucaoNoServidor = (id: string) =>
  consultar<{
    situacao: string;
    hora_ajustada: boolean;
    autorizacao_id: string | null;
    valor_devolvido: string;
    valor_reembolsado: string;
  }>(
    `SELECT situacao, hora_ajustada, autorizacao_id::text, valor_devolvido::text, valor_reembolsado::text
       FROM corona.devolucoes WHERE id = ${texto(id)}`,
  ).then((l) => l[0]);

const autorizacaoNoServidor = (id: string) =>
  consultar<{ situacao: string; tipo: string; hora_ajustada: boolean; valida_ate: string | null }>(
    `SELECT situacao, tipo, hora_ajustada, valida_ate::text FROM corona.autorizacoes_excecao WHERE id = ${texto(id)}`,
  ).then((l) => l[0]);

const movimentosDaDevolucao = (id: string) =>
  contar(
    `SELECT coalesce(sum(quantidade), 0) FROM corona.movimentos_remessa
      WHERE devolucao_id = ${texto(id)} AND tipo = 'DEVOLUCAO_CLIENTE'`,
  );

const reembolsoDaDevolucao = (id: string) =>
  consultar<{ valor: string }>(
    `SELECT valor::text FROM corona.pagamentos_cliente WHERE devolucao_id = ${texto(id)} AND tipo = 'REEMBOLSO'`,
  );

describe('duas pontas: devolução fora do prazo (casos 14 a 16)', () => {
  it('14. fora do prazo sem internet, relógio certo: bloqueio, pedido, a dona aprova e nega, devolução com a autorização', async () => {
    const [peca] = await cenario.pecas(1, { preco: '80.00' });
    const v = await vendedorComMaleta(cenario, 'Dalva', { [peca!.id]: 4 });
    const a = v.vendedor.aparelho;
    const aprovada = await vendaEnvelhecida(v, {
      produtoId: peca!.id,
      dias: prazoArrependimento + 1,
    });
    const negada = await vendaEnvelhecida(v, {
      produtoId: peca!.id,
      dias: prazoArrependimento + 1,
    });
    const itens = [{ produtoId: peca!.id, quantidade: 1 }];

    // Sem internet: a prévia diz fora do prazo, a devolução é recusada com o texto do glossário e o pedido vai para a fila.
    a.semInternet();
    const previa = await a.pedir<PreviaDaDevolucao>({
      operacao: 'devolucoes.previa',
      vendaId: aprovada.vendaId,
      itens: itens.map((i) => ({ ...i, estado: 'BOA' as const })),
    });
    expect(previa.dentroDoPrazo).toBe(false);
    expect(previa.podePedirAutorizacao).toBe(true);
    const barrada = await Tela.tentarDevolver(a, { vendaId: aprovada.vendaId, itens });
    expect(mensagemDaRecusa(barrada.resultado)).toBe(MENSAGENS_DA_FASE_6.FORA_DO_PRAZO);
    const pedidoAprovado = await Tela.pedirAutorizacao(a, { vendaId: aprovada.vendaId, itens });
    const pedidoNegado = await Tela.pedirAutorizacao(a, { vendaId: negada.vendaId, itens });
    expect(a.outbox().filter((o) => o.tipo === 'PEDIDO_AUTORIZACAO')).toHaveLength(2);

    // Internet: os dois pedidos chegam esperando a dona, do tipo "fora do prazo".
    a.comInternet();
    await a.sincronizar();
    for (const id of [pedidoAprovado, pedidoNegado]) {
      const s = await autorizacaoNoServidor(id);
      expect(s).toMatchObject({ situacao: 'PEDIDA', tipo: 'FORA_DO_PRAZO', hora_ajustada: false });
    }

    // A dona aprova um e nega o outro; o recebimento traz as duas respostas.
    await cenario.dona.aprovarAutorizacao(pedidoAprovado);
    await cenario.dona.negarAutorizacao(pedidoNegado);
    await a.sincronizar();
    const lista = await a.pedir<AutorizacoesDoVendedor>({ operacao: 'autorizacoes.listar' });
    const doAprovado = lista.pedidos.find((p) => p.id === pedidoAprovado);
    const doNegado = lista.pedidos.find((p) => p.id === pedidoNegado);
    expect(doAprovado?.situacao).toBe('APROVADA');
    expect(doAprovado?.validaAte).toBeTruthy();
    expect(doNegado?.situacao).toBe('NEGADA');

    // Sem internet de novo: a devolução da venda aprovada passa; a da negada continua bloqueada; a segunda com a mesma
    // autorização é recusada (uma devolução só por autorização).
    a.semInternet();
    const devolucaoId = await Tela.devolver(a, { vendaId: aprovada.vendaId, itens });
    const negadaTenta = await Tela.tentarDevolver(a, { vendaId: negada.vendaId, itens });
    expect(negadaTenta.resultado.ok).toBe(false);
    const segunda = await Tela.tentarDevolver(a, { vendaId: aprovada.vendaId, itens });
    expect(segunda.resultado.ok).toBe(false);

    a.comInternet();
    await a.sincronizar();
    // No servidor: aceita (não vai para revisão), autorização consumida, peça boa de volta à maleta e o reembolso do que foi pago.
    expect(await devolucaoNoServidor(devolucaoId)).toMatchObject({
      situacao: 'ACEITA',
      autorizacao_id: pedidoAprovado,
      valor_devolvido: '80.00',
      valor_reembolsado: '80.00',
    });
    expect((await autorizacaoNoServidor(pedidoAprovado))?.situacao).toBe('USADA');
    expect((await autorizacaoNoServidor(pedidoNegado))?.situacao).toBe('NEGADA');
    expect(await movimentosDaDevolucao(devolucaoId)).toBe(1);
    expect((await reembolsoDaDevolucao(devolucaoId)).map((r) => r.valor)).toEqual(['-80.00']);
    expect(
      await contar(
        `SELECT count(*) FROM corona.devolucoes WHERE venda_id = ${texto(negada.vendaId)}`,
      ),
    ).toBe(0);

    await cenario.afirmarNoFim([a]);
  });

  it('15. relógio 1 dia atrasado no limite: o app acha dentro, o servidor manda para revisão e nunca recusa', async () => {
    const [peca, pecaComDefeito] = await cenario.pecas(2, { preco: '80.00' });
    // O relógio deste computador anda 1 dia atrasado desde antes do primeiro contato (depois de um contato, relógio que
    // volta é "relógio mexido" e bloqueia tudo): para o app, o dia da venda velha é o último dia do prazo.
    const v = await vendedorComMaleta(
      cenario,
      'Elisa',
      { [peca!.id]: 3, [pecaComDefeito!.id]: 3 },
      -DIA_MS,
    );
    const a = v.vendedor.aparelho;
    const desistencia = await vendaEnvelhecida(v, {
      produtoId: peca!.id,
      dias: prazoArrependimento + 1,
    });
    const defeito = await vendaEnvelhecida(v, {
      produtoId: pecaComDefeito!.id,
      dias: prazoDefeito + 1,
    });

    a.semInternet();
    const devolucaoId = await Tela.devolver(a, {
      vendaId: desistencia.vendaId,
      itens: [{ produtoId: peca!.id, quantidade: 1 }],
    });
    const defeitoId = await Tela.devolver(a, {
      vendaId: defeito.vendaId,
      motivo: 'DEFEITO',
      itens: [{ produtoId: pecaComDefeito!.id, quantidade: 1, estado: 'DEFEITO' }],
    });

    a.comInternet();
    await a.sincronizar();
    // Envio aceito (nunca recusado); a devolução nasce para revisão, com a hora ajustada.
    for (const id of [devolucaoId, defeitoId]) {
      expect(a.outbox().find((o) => o.operacao_id === id)?.situacao).toBe('ENVIADO');
      expect(await devolucaoNoServidor(id)).toMatchObject({
        situacao: 'EM_REVISAO',
        hora_ajustada: true,
      });
    }
    const linhas = await a.pedir<DevolucaoOuTroca[]>({ operacao: 'devolucoes.listar' });
    expect(linhas.find((l) => l.id === devolucaoId)?.situacao).toBe('EM_REVISAO');
    // A peça boa já voltou para a maleta; a com defeito vai para a loja (nenhum movimento na maleta, nas duas pontas).
    expect(await movimentosDaDevolucao(devolucaoId)).toBe(1);
    expect(await movimentosDaDevolucao(defeitoId)).toBe(0);
    expect(
      await contar(
        `SELECT count(*) FROM corona.itens_devolucao WHERE devolucao_id = ${texto(defeitoId)} AND destino_peca = 'LOJA'`,
      ),
    ).toBe(1);
    expect(
      a.ler<{ n: number }>(
        `SELECT count(*) AS n FROM movimentos_remessa WHERE devolucao_id = ? AND deleted_at IS NULL`,
        defeitoId,
      )[0]!.n,
    ).toBe(0);

    // A dona NÃO aceita a de desistência: nada muda na maleta nem no dinheiro; mantém a de defeito.
    await cenario.dona.naoAceitarDevolucao(devolucaoId);
    await cenario.dona.manterDevolucao(defeitoId);
    await a.sincronizar();
    expect((await devolucaoNoServidor(devolucaoId))?.situacao).toBe('NAO_ACEITA');
    expect((await devolucaoNoServidor(defeitoId))?.situacao).toBe('MANTIDA');
    expect(
      a.ler<{ situacao: string }>('SELECT situacao FROM devolucoes WHERE id = ?', devolucaoId)[0]
        ?.situacao,
    ).toBe('NAO_ACEITA');
    expect(await movimentosDaDevolucao(devolucaoId)).toBe(1);
    expect((await reembolsoDaDevolucao(devolucaoId)).map((r) => r.valor)).toEqual(['-80.00']);

    await cenario.afirmarNoFim([a]);
  });

  it('16. relógio 1 dia adiantado no limite: o app bloqueia, o pedido desnecessário é gravado e a devolução é aceita', async () => {
    const [peca] = await cenario.pecas(1, { preco: '80.00' });
    const v = await vendedorComMaleta(cenario, 'Fabiana', { [peca!.id]: 3 });
    const a = v.vendedor.aparelho;
    // Exatamente no último dia do prazo: dentro para o servidor.
    const venda = await vendaEnvelhecida(v, { produtoId: peca!.id, dias: prazoArrependimento });
    const itens = [{ produtoId: peca!.id, quantidade: 1 }];

    a.semInternet();
    a.relogio.deslocamentoMs = DIA_MS;
    const barrada = await Tela.tentarDevolver(a, { vendaId: venda.vendaId, itens });
    expect(mensagemDaRecusa(barrada.resultado)).toBe(MENSAGENS_DA_FASE_6.FORA_DO_PRAZO);
    const pedidoId = await Tela.pedirAutorizacao(a, { vendaId: venda.vendaId, itens });

    a.comInternet();
    await a.sincronizar();
    // O servidor grava o pedido "desnecessário" (fora do prazo, esperando a dona) e não o recusa.
    expect(a.outbox().find((o) => o.operacao_id === pedidoId)?.situacao).toBe('ENVIADO');
    expect(await autorizacaoNoServidor(pedidoId)).toMatchObject({
      situacao: 'PEDIDA',
      tipo: 'FORA_DO_PRAZO',
    });
    await cenario.dona.aprovarAutorizacao(pedidoId);
    await a.sincronizar();

    // O app, ainda adiantado, vê a autorização valendo e registra a devolução sem internet.
    a.semInternet();
    const devolucaoId = await Tela.devolver(a, { vendaId: venda.vendaId, itens });
    a.comInternet();
    await a.sincronizar();
    expect(a.outbox().find((o) => o.operacao_id === devolucaoId)?.situacao).toBe('ENVIADO');
    expect(await devolucaoNoServidor(devolucaoId)).toMatchObject({ situacao: 'ACEITA' });
    expect(await movimentosDaDevolucao(devolucaoId)).toBe(1);

    await cenario.afirmarNoFim([a]);
  });
});
