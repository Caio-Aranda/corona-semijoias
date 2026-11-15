import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FichaDoAcerto, FichaDoAcertoAgora } from '../../../apps/seller-app/src/shared/dados';
import { consultar, contar, texto } from '../banco-do-servidor';
import { Cenario, sementeDaRodada, Tela } from '../cenario';
import { registrarCotacaoDeHoje } from '../cotacao-de-teste';
import { vendedorComMaleta } from './apoio-da-fase-6';

/**
 * Cenário 23 da tarefa 8.10 (§7.4): o vendedor pede o acerto e conta as sobras pelo Corona Vendas, só com internet, e
 * a dona fecha. Preço fixo (R$ 100,00) para conferir à mão: maleta com 3 anéis, 1 vendido; sobram 2.
 * Líquido = 100,00 - 30% de comissão (abaixo da meta) = 70,00, que fica devendo em 1 parcela.
 * O pedido de acerto e as leituras vão direto à API (nada na outbox); a leitura repetida com o mesmo id não conta duas vezes.
 */
let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('pedir acerto e contar'));
});
afterAll(() => cenario?.desligar());

describe('duas pontas: o vendedor pede o acerto e conta as sobras, a dona fecha (caso 23)', () => {
  it('23. pedir, ler as peças (com repetição e desfazer), sem internet não conta, e a dona fecha', async () => {
    const [peca] = await cenario.pecas(1, { preco: '100.00' });
    const v = await vendedorComMaleta(cenario, 'Cecília', { [peca!.id]: 3 });
    const a = v.vendedor.aparelho;
    await Tela.vender(a, { maletaId: v.maletaId, clienteId: v.clienteId, produtoId: peca!.id });
    await a.sincronizar();

    // Pedir: direto na API, com o id do computador (reenviar o mesmo id não abre dois acertos).
    const acertoId = randomUUID();
    const outboxAntes = a.outbox().length;
    const pedido = await a.pedir<FichaDoAcertoAgora>({ operacao: 'acertos.pedir', id: acertoId });
    const repetido = await a.pedir<FichaDoAcertoAgora>({ operacao: 'acertos.pedir', id: acertoId });
    expect(pedido.id).toBe(acertoId);
    expect(repetido.id).toBe(acertoId);
    expect(await contar(`SELECT count(*) FROM corona.acertos WHERE id = ${texto(acertoId)}`)).toBe(
      1,
    );
    expect(
      await contar(
        `SELECT count(*) FROM corona.acertos WHERE id = ${texto(acertoId)} AND pedido_pelo_vendedor AND status = 'EM_ACERTO'`,
      ),
    ).toBe(1);

    // Ler: 1, a mesma leitura reenviada (não conta duas vezes), mais 1, mais 1 e desfazer 1 => 2 boas.
    const ler = (id: string, quantidade: number) =>
      a.pedir<FichaDoAcertoAgora>({
        operacao: 'acertos.ler',
        id: acertoId,
        leitura: { id, produtoId: peca!.id, quantidade, estadoPeca: 'BOA' },
      });
    const l1 = randomUUID();
    await ler(l1, 1);
    const reenviada = await ler(l1, 1);
    expect(reenviada.contagem.map((c) => [c.produto.id, c.estadoPeca, c.quantidade])).toEqual([
      [peca!.id, 'BOA', 1],
    ]);
    await ler(randomUUID(), 1);
    await ler(randomUUID(), 1);
    const depoisDeDesfazer = await ler(randomUUID(), -1);
    expect(depoisDeDesfazer.contagem.map((c) => [c.estadoPeca, c.quantidade])).toEqual([
      ['BOA', 2],
    ]);

    // Sem internet: a leitura falha em silêncio para a fila (nada é guardado) e nada chega ao servidor.
    a.semInternet();
    const semRede = await a.chamar({
      operacao: 'acertos.ler',
      id: acertoId,
      leitura: { id: randomUUID(), produtoId: peca!.id, quantidade: 1, estadoPeca: 'BOA' },
    });
    expect(semRede).toMatchObject({ ok: false, motivo: 'sem-conexao' });
    a.comInternet();
    expect(a.outbox().length).toBe(outboxAntes);
    expect(
      await contar(
        `SELECT coalesce(sum(quantidade), 0) FROM corona.leituras_acerto WHERE acerto_id = ${texto(acertoId)}`,
      ),
    ).toBe(2);
    const quemLeu = await consultar<{ pelo_vendedor: boolean }>(
      `SELECT DISTINCT pelo_vendedor FROM corona.leituras_acerto WHERE acerto_id = ${texto(acertoId)}`,
    );
    expect(quemLeu).toEqual([{ pelo_vendedor: true }]);

    await registrarCotacaoDeHoje();
    // A dona vê a contagem e fecha (só ela fecha): o servidor calcula tudo.
    const previa = await cenario.dona.chamar<{ liquido: string }>(
      'GET',
      `/api/dona/acertos/${acertoId}/fechamento`,
    );
    expect(Number(previa.liquido)).toBe(70);
    const vencimento = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);
    await cenario.dona.chamar('POST', `/api/dona/acertos/${acertoId}/fechar`, {
      id: randomUUID(),
      faltas: [],
      defeitos: [],
      cobrancas: [],
      pendenciasAntigas: [],
      liquidoMostrado: previa.liquido,
      pagamento: { restante: { parcelas: 1, primeiroVencimento: vencimento } },
    });

    // O computador recebe o acerto fechado: esperado 2, contado 2 boas, nenhuma falta.
    await a.sincronizar();
    const ficha = await a.pedir<FichaDoAcerto>({ operacao: 'acertos.ver', id: acertoId });
    expect(ficha.situacao).toBe('ACERTADO');
    expect(ficha.fechamento?.liquido).toBe('70.00');
    expect(
      ficha.fechamento?.itens.map((i) => [i.produto.id, i.esperado, i.contadoBoa, i.falta]),
    ).toEqual([[peca!.id, 2, 2, 0]]);

    // Fechado, o vendedor não conta mais nada.
    const depois = await a.chamar({
      operacao: 'acertos.ler',
      id: acertoId,
      leitura: { id: randomUUID(), produtoId: peca!.id, quantidade: 1, estadoPeca: 'BOA' },
    });
    expect(depois.ok).toBe(false);
    expect(a.estado().aguardando).toBe(0);

    await cenario.afirmarNoFim([a]);
  });
});
