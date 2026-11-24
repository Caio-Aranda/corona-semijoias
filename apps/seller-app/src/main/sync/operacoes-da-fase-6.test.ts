import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import type { DevolucaoParaEnviar, PecaQueVolta, TrocaParaEnviar } from '../../shared/operacoes';
import { aplicarLote } from './aplicador-do-pull';
import { pecasDaVenda, preverDevolucao, saldoDoCredito } from './operacoes-da-fase-6';
import { ErroDaOperacao, OperacoesLocais, saldoDaPeca } from './operacoes-locais';
import { limparProvisoriasEnviadas } from './outbox';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { type MaletaDeTeste, receberMaleta } from './testes/dados-da-maleta';
import { doServidor, mudarMaleta, plantarPeca, venderPago } from './testes/dados-da-fase-6';

/**
 * Devolução, pedido de autorização, troca, crédito na venda e os cancelamentos no computador (tarefa 6.8; plano,
 * testes 3 a 11): cópia provisória e operação na mesma transação, o JSON do formato 1 da 6.7 (sem nenhum valor
 * calculado pelo app), as conferências com os textos do servidor e o "ainda podem voltar" pelo produto. SQLCipher de
 * verdade. Escrito antes do código.
 */
let b: BancoDeTeste;
let m: MaletaDeTeste;
let ops: OperacoesLocais;
let agora: Date;
let bloquear: (() => void) | null;
/** 12/11/2026 10:00 em Brasília. */
const T0 = new Date('2026-11-12T13:00:00.000Z');
const diaSeguinte = (dias: number, hora = '15:00:00'): Date =>
  new Date(`2026-11-${String(12 + dias).padStart(2, '0')}T${hora}.000Z`);

beforeEach(() => {
  b = criarBancoDeTeste();
  m = receberMaleta(b.db);
  agora = T0;
  bloquear = null;
  ops = new OperacoesLocais({
    banco: () => b.db,
    agora: () => agora,
    podeGravar: () => bloquear?.(),
  });
});
afterEach(() => b.fechar());

const vender = (
  itens: { produtoId: string; quantidade: number; desconto?: string }[],
  pagamentos?: Parameters<typeof venderPago>[5],
  clienteId = m.clienteId,
  remessaId = m.remessaId,
) => venderPago(b.db, ops, remessaId, clienteId, itens, pagamentos);

const pecas = (...p: [string, number, ('BOA' | 'DEFEITO')?][]): PecaQueVolta[] =>
  p.map(([produtoId, quantidade, estado]) => ({ produtoId, quantidade, estado: estado ?? 'BOA' }));

const devolucao = (
  vendaId: string,
  itens: PecaQueVolta[],
  extra: Partial<DevolucaoParaEnviar> = {},
): DevolucaoParaEnviar => ({
  id: idDeTeste(8),
  vendaId,
  motivo: 'ARREPENDIMENTO',
  formaReembolso: 'PIX',
  itens,
  ...extra,
});

const erroDe = (fn: () => unknown): ErroDaOperacao => {
  try {
    fn();
  } catch (e) {
    if (e instanceof ErroDaOperacao) return e;
    throw e;
  }
  throw new Error('não recusou');
};

/** Tudo o que uma operação poderia gravar: depois de uma recusa, nada muda. */
const retrato = () =>
  [
    'outbox',
    'provisorias',
    'devolucoes',
    'itens_devolucao',
    'movimentos_remessa',
    'pagamentos_cliente',
    'autorizacoes_excecao',
    'itens_autorizacao',
    'trocas',
    'creditos_cliente',
    'usos_credito',
    'vendas',
    'itens_venda',
  ].map((t) => `${t}:${contar(b.db, `SELECT 1 FROM ${t}`)}`);

const dadosDa = (operacaoId: string): string =>
  (
    b.db.prepare('SELECT dados FROM outbox WHERE operacao_id = ?').get(operacaoId) as {
      dados: string;
    }
  ).dados;

const linha = (sql: string, ...p: unknown[]) =>
  b.db.prepare(sql).get(...p) as Record<string, unknown>;

const somaDosPagamentos = (vendaId: string): string =>
  textoDeCentavos(
    (
      b.db
        .prepare('SELECT valor FROM pagamentos_cliente WHERE venda_id = ? AND deleted_at IS NULL')
        .all(vendaId) as { valor: string }[]
    ).reduce((t, p) => t + exigirCentavos(p.valor), 0n),
  );

/** As chaves que a outbox pode levar: nenhuma é valor calculado no computador (reforço 2 da aprovação). */
function chavesDe(v: unknown, saida = new Set<string>()): Set<string> {
  if (Array.isArray(v)) v.forEach((x) => chavesDe(x, saida));
  else if (typeof v === 'object' && v !== null)
    for (const [k, x] of Object.entries(v)) {
      saida.add(k);
      chavesDe(x, saida);
    }
  return saida;
}

const itemDaVenda = (vendaId: string, produtoId: string): string =>
  (
    linha(
      'SELECT id FROM itens_venda WHERE venda_id = ? AND produto_id = ?',
      vendaId,
      produtoId,
    ) as {
      id: string;
    }
  ).id;

describe('devolução dentro do prazo (teste 3)', () => {
  it('grava a devolução provisória e a operação juntas, com o JSON exato do formato 1', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
    agora = diaSeguinte(1);
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    expect(ops.devolver(d)).toEqual({ operacaoId: d.id });

    expect(dadosDa(d.id)).toBe(
      `{"id":"${d.id}","vendaId":"${vendaId}","motivo":"ARREPENDIMENTO","observacao":null,` +
        `"formaReembolso":"PIX","itens":[{"produtoId":"${m.produtoA}","quantidade":1,"estado":"BOA"}],` +
        `"valorConferido":null,"autorizacaoId":null,"devolvidaEm":"2026-11-13T15:00:00.000Z"}`,
    );
    expect(linha('SELECT tipo, situacao FROM outbox WHERE operacao_id = ?', d.id)).toEqual({
      tipo: 'DEVOLUCAO',
      situacao: 'AGUARDANDO',
    });
    expect(
      linha(
        `SELECT destino, situacao, dentro_garantia, valor_devolvido, valor_reembolsado, valor_em_credito,
                forma_reembolso, origem_local FROM devolucoes WHERE id = ?`,
        d.id,
      ),
    ).toEqual({
      destino: 'REEMBOLSO',
      situacao: 'ACEITA',
      dentro_garantia: 1,
      valor_devolvido: '33.33',
      valor_reembolsado: '33.33',
      valor_em_credito: '0.00',
      forma_reembolso: 'PIX',
      origem_local: 'PROVISORIO',
    });
    expect(
      linha(
        'SELECT quantidade, valor, estado_peca, destino_peca FROM itens_devolucao WHERE devolucao_id = ?',
        d.id,
      ),
    ).toEqual({ quantidade: 1, valor: '33.33', estado_peca: 'BOA', destino_peca: 'MALETA' });
    // A peça boa de maleta com o vendedor volta: o saldo aumenta.
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(2);
    expect(
      linha(
        "SELECT tipo, forma, valor FROM pagamentos_cliente WHERE devolucao_id = ? AND tipo = 'REEMBOLSO'",
        d.id,
      ),
    ).toEqual({ tipo: 'REEMBOLSO', forma: 'PIX', valor: '-33.33' });
    expect(somaDosPagamentos(vendaId)).toBe('33.33');
    expect(pecasDaVenda(b.db, vendaId).get(m.produtoA)).toMatchObject({
      vendidas: 2,
      jaDevolvidas: 1,
      podemVoltar: 1,
    });
  });

  it('o JSON da outbox não leva nenhum valor calculado no computador (só venda, peças, estado, motivo e forma)', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const d = devolucao(vendaId, pecas([m.produtoA, 2]));
    ops.devolver(d);
    const dados = JSON.parse(dadosDa(d.id)) as Record<string, unknown>;
    expect([...chavesDe(dados)].sort()).toEqual(
      [
        'autorizacaoId',
        'devolvidaEm',
        'estado',
        'formaReembolso',
        'id',
        'itens',
        'motivo',
        'observacao',
        'produtoId',
        'quantidade',
        'valorConferido',
        'vendaId',
      ].sort(),
    );
    expect(dados['valorConferido']).toBeNull();
  });

  it('falha injetada na outbox: nada da devolução fica', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const antes = retrato();
    b.db.exec(
      "CREATE TEMP TRIGGER falha BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'falha injetada'); END;",
    );
    expect(() => ops.devolver(devolucao(vendaId, pecas([m.produtoA, 1])))).toThrow(
      'falha injetada',
    );
    expect(retrato()).toEqual(antes);
  });

  it('com defeito e de maleta já acertada a peça vai para a loja e o saldo não muda', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const v2 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    ops.devolver(
      devolucao(v1.vendaId, pecas([m.produtoA, 1, 'DEFEITO']), {
        motivo: 'DEFEITO',
      }),
    );
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
    mudarMaleta(b.db, m.remessaId, 'ACERTADA');
    const d2 = devolucao(v2.vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d2);
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
    expect(
      (
        b.db.prepare('SELECT destino_peca FROM itens_devolucao ORDER BY rowid').all() as {
          destino_peca: string;
        }[]
      ).map((r) => r.destino_peca),
    ).toEqual(['LOJA', 'LOJA']);
    expect(contar(b.db, "SELECT 1 FROM movimentos_remessa WHERE tipo = 'DEVOLUCAO_CLIENTE'")).toBe(
      0,
    );
  });

  it('venda paga com crédito: a parte em crédito volta primeiro, sem crédito usável no computador', () => {
    const credito = idDeTeste(9);
    doServidor(b.db, 'creditos_cliente', credito, {
      cliente_id: m.clienteId,
      origem: 'TROCA',
      valor: '50.00',
      valor_utilizado: '0.00',
      situacao: 'ABERTO',
    });
    const { vendaId } = vender(
      [{ produtoId: m.produtoA, quantidade: 1 }],
      [
        { valor: '20.00', forma: 'CREDITO', creditoId: credito },
        { valor: '13.33', forma: 'PIX' },
      ],
    );
    expect(saldoDoCredito(b.db, credito)).toBe(3000n);
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    expect(
      linha(
        'SELECT valor_devolvido, valor_reembolsado, valor_em_credito FROM devolucoes WHERE id = ?',
        d.id,
      ),
    ).toEqual({ valor_devolvido: '33.33', valor_reembolsado: '13.33', valor_em_credito: '20.00' });
    expect(
      b.db
        .prepare(
          'SELECT forma, valor, credito_id FROM pagamentos_cliente WHERE devolucao_id = ? ORDER BY forma',
        )
        .all(d.id),
    ).toEqual([
      { forma: 'CREDITO', valor: '-20.00', credito_id: null },
      { forma: 'PIX', valor: '-13.33', credito_id: null },
    ]);
    // O crédito que volta é do servidor: no computador, nenhum crédito novo aparece.
    expect(contar(b.db, 'SELECT 1 FROM creditos_cliente')).toBe(1);
    expect(saldoDoCredito(b.db, credito)).toBe(3000n);
  });
});

describe('fora do prazo e autorização (teste 4)', () => {
  const autorizacao = (
    vendaId: string,
    campos: Record<string, string | number | null>,
    quantidade = 2,
    produtoId = m.produtoA,
  ): string => {
    const id = idDeTeste(9);
    doServidor(b.db, 'autorizacoes_excecao', id, {
      venda_id: vendaId,
      motivo: 'ARREPENDIMENTO',
      tipo: 'FORA_DO_PRAZO',
      situacao: 'APROVADA',
      valida_ate: '2026-11-25',
      pedida_em: '2026-11-19T12:00:00.000Z',
      ...campos,
    });
    doServidor(b.db, 'itens_autorizacao', idDeTeste(9), {
      autorizacao_id: id,
      item_venda_id: itemDaVenda(vendaId, produtoId),
      quantidade,
    });
    return id;
  };

  it('no 8º dia, sem autorização, recusa e nada grava; com a aprovada e válida passa com o id dela', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    agora = diaSeguinte(8);
    const antes = retrato();
    expect(erroDe(() => ops.devolver(devolucao(vendaId, pecas([m.produtoA, 1])))).message).toBe(
      'Passou do prazo da garantia. Peça autorização à dona.',
    );
    expect(retrato()).toEqual(antes);

    const aut = autorizacao(vendaId, {});
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    expect(JSON.parse(dadosDa(d.id))).toMatchObject({ autorizacaoId: aut });
    expect(
      linha('SELECT dentro_garantia, autorizacao_id FROM devolucoes WHERE id = ?', d.id),
    ).toEqual({
      dentro_garantia: 0,
      autorizacao_id: aut,
    });
    // A autorização serve para uma devolução só: a seguinte (usada aqui, ainda aguardando envio) recusa.
    expect(erroDe(() => ops.devolver(devolucao(vendaId, pecas([m.produtoA, 1])))).message).toBe(
      'Esta autorização já foi usada numa devolução. Peça outra autorização à dona.',
    );
  });

  it.each([
    [
      { valida_ate: '2026-11-19' },
      2,
      'A autorização vale 7 dias e já venceu. Peça outra autorização à dona.',
    ],
    [
      { motivo: 'TAMANHO' },
      2,
      'Use a mesma venda, o mesmo motivo e no máximo as peças que a dona autorizou.',
    ],
    [{}, 1, 'Use a mesma venda, o mesmo motivo e no máximo as peças que a dona autorizou.'],
    [
      { situacao: 'NEGADA' },
      2,
      'Este pedido de autorização ainda não foi aprovado ou foi negado. Espere a resposta da dona.',
    ],
    [
      { situacao: 'PEDIDA' },
      2,
      'Este pedido de autorização ainda não foi aprovado ou foi negado. Espere a resposta da dona.',
    ],
    [
      { situacao: 'USADA' },
      2,
      'Esta autorização já foi usada numa devolução. Peça outra autorização à dona.',
    ],
  ] as const)(
    'autorização que não serve (%o, %i peças autorizadas): recusa com o texto',
    (campos, qtd, texto) => {
      const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
      agora = diaSeguinte(8);
      autorizacao(vendaId, { ...campos }, qtd);
      const antes = retrato();
      expect(erroDe(() => ops.devolver(devolucao(vendaId, pecas([m.produtoA, 2])))).message).toBe(
        texto,
      );
      expect(retrato()).toEqual(antes);
    },
  );

  it('a autorização de outra venda não serve: passou do prazo', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const v2 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    agora = diaSeguinte(8);
    autorizacao(v2.vendaId, {}, 1);
    expect(erroDe(() => ops.devolver(devolucao(v1.vendaId, pecas([m.produtoA, 1])))).message).toBe(
      'Passou do prazo da garantia. Peça autorização à dona.',
    );
  });

  it('defeito no 30º dia ainda passa sem autorização; no 31º não', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const v2 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    agora = new Date('2026-12-12T15:00:00.000Z');
    ops.devolver(devolucao(v1.vendaId, pecas([m.produtoA, 1, 'DEFEITO']), { motivo: 'DEFEITO' }));
    agora = new Date('2026-12-13T15:00:00.000Z');
    expect(
      erroDe(() =>
        ops.devolver(
          devolucao(v2.vendaId, pecas([m.produtoA, 1, 'DEFEITO']), { motivo: 'DEFEITO' }),
        ),
      ).message,
    ).toBe('Passou do prazo da garantia. Peça autorização à dona.');
  });
});

describe('pedido de autorização (teste 5)', () => {
  it('fora do prazo grava o pedido e a operação; no prazo e o segundo pedido recusam', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const pedido = {
      id: idDeTeste(8),
      vendaId,
      motivo: 'ARREPENDIMENTO' as const,
      itens: pecas([m.produtoA, 1]),
    };
    expect(erroDe(() => ops.pedirAutorizacao(pedido)).message).toBe(
      'Esta devolução está no prazo. Registre a devolução direto.',
    );
    agora = diaSeguinte(9);
    expect(ops.pedirAutorizacao(pedido)).toEqual({ operacaoId: pedido.id });
    expect(dadosDa(pedido.id)).toBe(
      `{"id":"${pedido.id}","vendaId":"${vendaId}","motivo":"ARREPENDIMENTO","observacao":null,` +
        `"itens":[{"produtoId":"${m.produtoA}","quantidade":1,"estado":"BOA"}],"pedidaEm":"2026-11-21T15:00:00.000Z"}`,
    );
    expect(
      linha(
        'SELECT situacao, tipo, valor_pedido, origem_local FROM autorizacoes_excecao WHERE id = ?',
        pedido.id,
      ),
    ).toEqual({
      situacao: 'PEDIDA',
      tipo: 'FORA_DO_PRAZO',
      valor_pedido: '33.33',
      origem_local: 'PROVISORIO',
    });
    expect(
      linha('SELECT quantidade FROM itens_autorizacao WHERE autorizacao_id = ?', pedido.id),
    ).toEqual({
      quantidade: 1,
    });
    expect(linha('SELECT tipo FROM outbox WHERE operacao_id = ?', pedido.id)).toEqual({
      tipo: 'PEDIDO_AUTORIZACAO',
    });
    expect(erroDe(() => ops.pedirAutorizacao({ ...pedido, id: idDeTeste(8) })).message).toBe(
      'Já há um pedido de autorização para esta venda. Espere a resposta da dona ou use a autorização aprovada.',
    );
  });
});

describe('troca (teste 6)', () => {
  let produtoC: string;
  beforeEach(() => {
    mudarMaleta(b.db, m.remessaId, 'CONFERIDA');
    produtoC = idDeTeste(2);
    plantarPeca(b.db, m.remessaId, produtoC, 5, '50.00');
  });

  const troca = (
    vendaId: string,
    voltam: PecaQueVolta[],
    levam: { produtoId: string; quantidade: number; desconto?: string }[],
    pagamentos: { valor: string; forma: 'DINHEIRO' | 'PIX' | 'CARTAO' | 'CHEQUE' }[] = [],
    extra: Partial<TrocaParaEnviar['devolucao']> = {},
  ): TrocaParaEnviar => ({
    id: idDeTeste(8),
    creditoId: idDeTeste(8),
    pagamentoDoCreditoId: idDeTeste(8),
    devolucao: {
      id: idDeTeste(8),
      vendaId,
      motivo: 'ARREPENDIMENTO',
      itens: voltam,
      ...extra,
    },
    venda: {
      id: idDeTeste(5),
      remessaId: m.remessaId,
      itens: levam,
      pagamentos: pagamentos.map((p) => ({ ...p, id: idDeTeste(6) })),
    },
  });

  it('B mais cara: a diferença paga à vista; tudo provisório numa transação e o JSON exato', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const t = troca(
      vendaId,
      pecas([m.produtoA, 1]),
      [{ produtoId: produtoC, quantidade: 1 }],
      [{ valor: '16.67', forma: 'DINHEIRO' }],
    );
    expect(ops.trocar(t)).toEqual({ operacaoId: t.id });
    const pg = t.venda.pagamentos[0]?.id ?? '';
    expect(dadosDa(t.id)).toBe(
      `{"id":"${t.id}","creditoId":"${t.creditoId}","pagamentoDoCreditoId":"${t.pagamentoDoCreditoId}",` +
        `"devolucao":{"id":"${t.devolucao.id}","vendaId":"${vendaId}","motivo":"ARREPENDIMENTO","observacao":null,` +
        `"itens":[{"produtoId":"${m.produtoA}","quantidade":1,"estado":"BOA"}],"autorizacaoId":null},` +
        `"venda":{"id":"${t.venda.id}","remessaId":"${m.remessaId}",` +
        `"itens":[{"produtoId":"${produtoC}","quantidade":1,"desconto":"0.00"}],` +
        `"pagamentos":[{"id":"${pg}","valor":"16.67","forma":"DINHEIRO"}],"totalConferido":null},` +
        `"trocadaEm":"2026-11-12T13:00:00.000Z"}`,
    );
    const dados = JSON.parse(dadosDa(t.id)) as { venda: { totalConferido: unknown } };
    expect(dados.venda.totalConferido).toBeNull();
    expect([...chavesDe(dados)]).not.toContain('valorConferido');
    expect(linha('SELECT tipo FROM outbox WHERE operacao_id = ?', t.id)).toEqual({ tipo: 'TROCA' });

    expect(
      linha(
        'SELECT destino, valor_devolvido, valor_reembolsado, valor_em_credito FROM devolucoes WHERE id = ?',
        t.devolucao.id,
      ),
    ).toEqual({
      destino: 'TROCA',
      valor_devolvido: '33.33',
      valor_reembolsado: '0.00',
      valor_em_credito: '33.33',
    });
    expect(
      linha(
        'SELECT origem, valor, troca_id, devolucao_id FROM creditos_cliente WHERE id = ?',
        t.creditoId,
      ),
    ).toEqual({
      origem: 'TROCA',
      valor: '33.33',
      troca_id: t.id,
      devolucao_id: t.devolucao.id,
    });
    expect(
      linha('SELECT total, troca_id, cliente_id FROM vendas WHERE id = ?', t.venda.id),
    ).toEqual({
      total: '50.00',
      troca_id: t.id,
      cliente_id: m.clienteId,
    });
    expect(
      b.db
        .prepare(
          'SELECT id, forma, valor, credito_id FROM pagamentos_cliente WHERE venda_id = ? ORDER BY forma',
        )
        .all(t.venda.id),
    ).toEqual([
      { id: t.pagamentoDoCreditoId, forma: 'CREDITO', valor: '33.33', credito_id: t.creditoId },
      { id: pg, forma: 'DINHEIRO', valor: '16.67', credito_id: null },
    ]);
    expect(linha('SELECT diferenca, situacao FROM trocas WHERE id = ?', t.id)).toEqual({
      diferenca: '16.67',
      situacao: 'ATIVA',
    });
    expect(linha("SELECT quantidade FROM movimentos_remessa WHERE tipo = 'TROCA_ENTRADA'")).toEqual(
      { quantidade: 1 },
    );
    expect(linha("SELECT quantidade FROM movimentos_remessa WHERE tipo = 'TROCA_SAIDA'")).toEqual({
      quantidade: -1,
    });
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(3);
    expect(saldoDaPeca(b.db, m.remessaId, produtoC)).toBe(4);
    expect(saldoDoCredito(b.db, t.creditoId)).toBe(0n);
    // Na venda A, o valor devolvido vira reembolso em crédito.
    expect(
      linha(
        "SELECT forma, valor FROM pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'",
        vendaId,
      ),
    ).toEqual({
      forma: 'CREDITO',
      valor: '-33.33',
    });
  });

  it('B mais cara a prazo: só o crédito paga; o resto fica a receber', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const t = troca(vendaId, pecas([m.produtoA, 1]), [{ produtoId: produtoC, quantidade: 1 }]);
    ops.trocar(t);
    expect(somaDosPagamentos(t.venda.id)).toBe('33.33');
  });

  it('B mais barata: a sobra fica de crédito; pagar algo da diferença recusa', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const antes = retrato();
    expect(
      erroDe(() =>
        ops.trocar(
          troca(
            vendaId,
            pecas([m.produtoA, 1]),
            [{ produtoId: m.produtoB, quantidade: 1 }],
            [{ valor: '1.00', forma: 'PIX' }],
          ),
        ),
      ).message,
    ).toBe(
      'A peça nova vale menos que a devolvida: não há diferença a pagar. A sobra fica de crédito.',
    );
    expect(retrato()).toEqual(antes);
    const t = troca(vendaId, pecas([m.produtoA, 1]), [{ produtoId: m.produtoB, quantidade: 1 }]);
    ops.trocar(t);
    expect(saldoDoCredito(b.db, t.creditoId)).toBe(2333n);
    expect(linha('SELECT diferenca FROM trocas WHERE id = ?', t.id)).toEqual({
      diferenca: '-23.33',
    });
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoB)).toBe(0);
  });

  it('igual: sem diferença; a peça que volta conta no saldo da mesma maleta', () => {
    const { vendaId } = vender([{ produtoId: produtoC, quantidade: 5 }]);
    expect(saldoDaPeca(b.db, m.remessaId, produtoC)).toBe(0);
    const t = troca(vendaId, pecas([produtoC, 1]), [{ produtoId: produtoC, quantidade: 1 }]);
    ops.trocar(t);
    expect(linha('SELECT diferenca FROM trocas WHERE id = ?', t.id)).toEqual({ diferenca: '0.00' });
    expect(saldoDaPeca(b.db, m.remessaId, produtoC)).toBe(0);
  });

  it('várias peças, e A com defeito vai para a loja', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const t = troca(vendaId, pecas([m.produtoA, 1, 'DEFEITO'], [m.produtoA, 1, 'BOA']), [
      { produtoId: m.produtoB, quantidade: 1 },
      { produtoId: produtoC, quantidade: 1 },
    ]);
    ops.trocar(t);
    expect(linha('SELECT valor_devolvido FROM devolucoes WHERE id = ?', t.devolucao.id)).toEqual({
      valor_devolvido: '66.66',
    });
    expect(saldoDoCredito(b.db, t.creditoId)).toBe(666n);
    expect(
      b.db
        .prepare('SELECT estado_peca, destino_peca FROM itens_devolucao ORDER BY estado_peca')
        .all(),
    ).toEqual([
      { estado_peca: 'BOA', destino_peca: 'MALETA' },
      { estado_peca: 'DEFEITO', destino_peca: 'LOJA' },
    ]);
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(2);
  });

  it('B sem saldo, maleta não conferida ou falha no meio: nada grava (tudo ou nada)', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const antes = retrato();
    expect(
      erroDe(() =>
        ops.trocar(
          troca(vendaId, pecas([m.produtoA, 1]), [{ produtoId: produtoC, quantidade: 6 }]),
        ),
      ).codigo,
    ).toBe('SEM_SALDO');
    b.db.exec(
      "CREATE TEMP TRIGGER falha BEFORE INSERT ON trocas BEGIN SELECT RAISE(ABORT, 'falha injetada'); END;",
    );
    expect(() =>
      ops.trocar(troca(vendaId, pecas([m.produtoA, 1]), [{ produtoId: produtoC, quantidade: 1 }])),
    ).toThrow('falha injetada');
    b.db.exec('DROP TRIGGER falha');
    mudarMaleta(b.db, m.remessaId, 'ENVIADA');
    expect(
      erroDe(() =>
        ops.trocar(
          troca(vendaId, pecas([m.produtoA, 1]), [{ produtoId: produtoC, quantidade: 1 }]),
        ),
      ).message,
    ).toBe(
      'Esta maleta ainda não foi conferida. Confira todas as peças da maleta antes de vender.',
    );
    expect(retrato()).toEqual(antes);
  });
});

describe('crédito na venda (teste 7)', () => {
  let produtoC: string;
  let credito: string;
  beforeEach(() => {
    mudarMaleta(b.db, m.remessaId, 'CONFERIDA');
    produtoC = idDeTeste(2);
    plantarPeca(b.db, m.remessaId, produtoC, 5, '50.00');
    // Uma troca com sobra de 23,33 de crédito, feita sem internet.
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    credito = idDeTeste(8);
    ops.trocar({
      id: idDeTeste(8),
      creditoId: credito,
      pagamentoDoCreditoId: idDeTeste(8),
      devolucao: { id: idDeTeste(8), vendaId, motivo: 'TAMANHO', itens: pecas([m.produtoA, 1]) },
      venda: {
        id: idDeTeste(5),
        remessaId: m.remessaId,
        itens: [{ produtoId: m.produtoB, quantidade: 1 }],
        pagamentos: [],
      },
    });
  });

  const comCredito = (valor: string, resto: string, id = credito) =>
    vender(
      [{ produtoId: produtoC, quantidade: 1 }],
      [
        { valor, forma: 'CREDITO', creditoId: id },
        { valor: resto, forma: 'PIX' },
      ],
    );

  it('o crédito da troca é usado em parte, de novo e até zerar, sem internet', () => {
    expect(saldoDoCredito(b.db, credito)).toBe(2333n);
    comCredito('10.00', '40.00');
    expect(saldoDoCredito(b.db, credito)).toBe(1333n);
    const { vendaId } = comCredito('13.33', '36.67');
    expect(saldoDoCredito(b.db, credito)).toBe(0n);
    expect(linha('SELECT tipo, valor FROM usos_credito WHERE venda_id = ?', vendaId)).toEqual({
      tipo: 'USO',
      valor: '13.33',
    });
    expect(erroDe(() => comCredito('1.00', '49.00')).message).toBe(
      'Este crédito já foi todo usado ou foi cancelado. Escolha outra forma de pagamento.',
    );
  });

  it('acima do saldo, de outro cliente, cancelado e repetido na mesma venda recusam no computador', () => {
    const bia = idDeTeste(3);
    doServidor(b.db, 'clientes', bia, { nome: 'Bia' });
    const cancelado = idDeTeste(9);
    doServidor(b.db, 'creditos_cliente', cancelado, {
      cliente_id: m.clienteId,
      origem: 'TROCA',
      valor: '50.00',
      situacao: 'CANCELADO',
    });
    const antes = retrato();
    expect(erroDe(() => comCredito('30.00', '20.00')).message).toBe(
      'Neste crédito sobram R$ 23,33. Diminua o valor e pague o resto de outra forma.',
    );
    expect(
      erroDe(() =>
        vender(
          [{ produtoId: produtoC, quantidade: 1 }],
          [{ valor: '5.00', forma: 'CREDITO', creditoId: credito }],
          bia,
        ),
      ).message,
    ).toBe('Este crédito é de outro cliente. Use só o crédito do cliente desta venda.');
    expect(
      erroDe(() =>
        vender(
          [{ produtoId: produtoC, quantidade: 1 }],
          [
            { valor: '5.00', forma: 'CREDITO', creditoId: credito },
            { valor: '5.00', forma: 'CREDITO', creditoId: credito },
          ],
        ),
      ).message,
    ).toBe('O mesmo crédito aparece mais de uma vez. Use o crédito uma vez só.');
    expect(erroDe(() => comCredito('5.00', '45.00', cancelado)).message).toBe(
      'Este crédito já foi todo usado ou foi cancelado. Escolha outra forma de pagamento.',
    );
    expect(erroDe(() => comCredito('5.00', '45.00', idDeTeste(9))).message).toBe(
      'Não encontramos este crédito do cliente. Atualize a tela e tente de novo.',
    );
    expect(retrato()).toEqual(antes);
  });

  it('o JSON da VENDA sem crédito é o de hoje (bytes fixos); com crédito, só o pagamento em crédito leva o creditoId', () => {
    const id = '00000000-0000-7000-8000-00000000aaa1';
    const pago = '00000000-0000-7000-8000-00000000aaa2';
    ops.vender({
      id,
      remessaId: m.remessaId,
      clienteId: m.clienteId,
      itens: [{ produtoId: produtoC, quantidade: 1 }],
      pagamentos: [{ id: pago, valor: '50.00', forma: 'PIX' }],
    });
    expect(dadosDa(id)).toBe(
      `{"id":"${id}","remessaId":"${m.remessaId}","clienteId":"${m.clienteId}",` +
        `"itens":[{"produtoId":"${produtoC}","quantidade":1,"desconto":"0.00"}],` +
        `"pagamentos":[{"id":"${pago}","valor":"50.00","forma":"PIX"}],"totalConferido":"50.00",` +
        `"vendidaEm":"2026-11-12T13:00:00.000Z"}`,
    );
    const { vendaId, pagamentoIds } = comCredito('10.00', '40.00');
    expect(JSON.parse(dadosDa(vendaId))).toMatchObject({
      pagamentos: [
        { id: pagamentoIds[0], valor: '10.00', forma: 'CREDITO', creditoId: credito },
        { id: pagamentoIds[1], valor: '40.00', forma: 'PIX' },
      ],
    });
    expect(
      Object.keys((JSON.parse(dadosDa(vendaId)) as { pagamentos: object[] }).pagamentos[1] ?? {}),
    ).toEqual(['id', 'valor', 'forma']);
    expect(
      linha('SELECT credito_id FROM pagamentos_cliente WHERE id = ?', pagamentoIds[0]),
    ).toEqual({
      credito_id: credito,
    });
  });
});

describe('cancelar devolução e cancelar troca (teste 8)', () => {
  it('no mesmo dia tudo volta; no dia seguinte, ou de novo, recusa', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    const c = { id: idDeTeste(8), devolucaoId: d.id, motivo: 'Errei a peça' };
    expect(ops.cancelarDevolucao(c)).toEqual({ operacaoId: c.id });
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
    expect(somaDosPagamentos(vendaId)).toBe('66.66');
    expect(pecasDaVenda(b.db, vendaId).get(m.produtoA)).toMatchObject({
      jaDevolvidas: 0,
      podemVoltar: 2,
    });
    expect(
      (b.db.prepare('SELECT tipo FROM outbox ORDER BY ordem').all() as { tipo: string }[]).map(
        (r) => r.tipo,
      ),
    ).toEqual(['VENDA', 'DEVOLUCAO', 'CANCELAR_DEVOLUCAO']);
    expect(dadosDa(c.id)).toBe(
      `{"id":"${c.id}","devolucaoId":"${d.id}","motivo":"Errei a peça","canceladaEm":"2026-11-12T13:00:00.000Z"}`,
    );
    expect(
      linha(
        "SELECT valor, estorna_id IS NOT NULL AS liga FROM pagamentos_cliente WHERE tipo = 'ESTORNO_REEMBOLSO'",
      ),
    ).toEqual({
      valor: '33.33',
      liga: 1,
    });
    expect(erroDe(() => ops.cancelarDevolucao({ ...c, id: idDeTeste(8) })).message).toBe(
      'Esta devolução já foi cancelada e não pode mudar.',
    );

    const d2 = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d2);
    agora = diaSeguinte(1);
    expect(
      erroDe(() => ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d2.id, motivo: 'x' }))
        .message,
    ).toBe('Só dá para cancelar a devolução no mesmo dia. Peça à dona para cancelar.');
    expect(
      erroDe(() => ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d2.id, motivo: '  ' }))
        .message,
    ).toBe('Diga o motivo de cancelar a devolução.');
  });

  describe('troca', () => {
    let produtoC: string;
    beforeEach(() => {
      mudarMaleta(b.db, m.remessaId, 'CONFERIDA');
      produtoC = idDeTeste(2);
      plantarPeca(b.db, m.remessaId, produtoC, 5, '50.00');
    });
    const trocar = (
      vendaId: string,
      levam: string,
      pagamentos: { valor: string; forma: 'PIX' }[] = [],
    ) => {
      const t: TrocaParaEnviar = {
        id: idDeTeste(8),
        creditoId: idDeTeste(8),
        pagamentoDoCreditoId: idDeTeste(8),
        devolucao: {
          id: idDeTeste(8),
          vendaId,
          motivo: 'ARREPENDIMENTO',
          itens: pecas([m.produtoA, 1]),
        },
        venda: {
          id: idDeTeste(5),
          remessaId: m.remessaId,
          itens: [{ produtoId: levam, quantidade: 1 }],
          pagamentos: pagamentos.map((p) => ({ ...p, id: idDeTeste(6) })),
        },
      };
      ops.trocar(t);
      return t;
    };

    it('cancelar troca no mesmo dia desfaz tudo: peças, pagamentos e o crédito deixa de valer', () => {
      const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
      const t = trocar(vendaId, produtoC, [{ valor: '16.67', forma: 'PIX' }]);
      const c = { id: idDeTeste(8), trocaId: t.id, motivo: 'O cliente desistiu' };
      expect(ops.cancelarTroca(c)).toEqual({ operacaoId: c.id });
      expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(2);
      expect(saldoDaPeca(b.db, m.remessaId, produtoC)).toBe(5);
      expect(somaDosPagamentos(t.venda.id)).toBe('0.00');
      expect(somaDosPagamentos(vendaId)).toBe('33.33');
      expect(saldoDoCredito(b.db, t.creditoId)).toBe(3333n);
      expect(linha("SELECT tipo, valor FROM usos_credito WHERE tipo = 'ESTORNO'")).toEqual({
        tipo: 'ESTORNO',
        valor: '-33.33',
      });
      expect(erroDe(() => comCreditoDe(t.creditoId)).message).toBe(
        'Este crédito já foi todo usado ou foi cancelado. Escolha outra forma de pagamento.',
      );
      expect(erroDe(() => ops.cancelarTroca({ ...c, id: idDeTeste(8) })).message).toBe(
        'Esta troca já foi cancelada e não pode mudar.',
      );
      expect(dadosDa(c.id)).toBe(
        `{"id":"${c.id}","trocaId":"${t.id}","motivo":"O cliente desistiu","canceladaEm":"2026-11-12T13:00:00.000Z"}`,
      );
    });

    const comCreditoDe = (credito: string) =>
      vender(
        [{ produtoId: produtoC, quantidade: 1 }],
        [
          { valor: '5.00', forma: 'CREDITO', creditoId: credito },
          { valor: '45.00', forma: 'PIX' },
        ],
      );

    it('troca com a sobra do crédito já usada, de outro dia ou pela devolução recusam', () => {
      const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
      const t = trocar(vendaId, m.produtoB);
      comCreditoDe(t.creditoId);
      expect(
        erroDe(() => ops.cancelarTroca({ id: idDeTeste(8), trocaId: t.id, motivo: 'x' })).message,
      ).toBe(
        'O crédito que sobrou desta troca já foi usado em outra compra e a troca não pode ser cancelada. Fale com a dona.',
      );
      expect(
        erroDe(() =>
          ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: t.devolucao.id, motivo: 'x' }),
        ).message,
      ).toBe('Esta devolução faz parte de uma troca. Use Cancelar troca.');
      const v2 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
      const t2 = trocar(v2.vendaId, produtoC);
      agora = diaSeguinte(1);
      expect(
        erroDe(() => ops.cancelarTroca({ id: idDeTeste(8), trocaId: t2.id, motivo: 'x' })).message,
      ).toBe('Só dá para cancelar a troca no mesmo dia. Peça à dona para cancelar.');
    });
  });
});

describe('estorno com devolução, troca e crédito (teste 9)', () => {
  it('venda com devolução e venda de troca recusam; venda paga com crédito devolve o saldo', () => {
    mudarMaleta(b.db, m.remessaId, 'CONFERIDA');
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    expect(erroDe(() => ops.estornar({ id: idDeTeste(8), vendaId, motivo: 'x' })).message).toBe(
      'Esta venda já tem devolução. Cancele a devolução antes de estornar.',
    );
    ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d.id, motivo: 'Errei' });
    ops.estornar({ id: idDeTeste(8), vendaId, motivo: 'Errei' });

    const v2 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const t: TrocaParaEnviar = {
      id: idDeTeste(8),
      creditoId: idDeTeste(8),
      pagamentoDoCreditoId: idDeTeste(8),
      devolucao: {
        id: idDeTeste(8),
        vendaId: v2.vendaId,
        motivo: 'TAMANHO',
        itens: pecas([m.produtoA, 1]),
      },
      venda: {
        id: idDeTeste(5),
        remessaId: m.remessaId,
        itens: [{ produtoId: m.produtoB, quantidade: 1 }],
        pagamentos: [],
      },
    };
    ops.trocar(t);
    expect(
      erroDe(() => ops.estornar({ id: idDeTeste(8), vendaId: t.venda.id, motivo: 'x' })).message,
    ).toBe('Esta venda faz parte de uma troca. Use Cancelar troca.');

    // A sobra (23,33) paga parte de outra venda; o estorno dessa venda devolve o saldo do crédito.
    const v3 = vender(
      [{ produtoId: m.produtoA, quantidade: 1 }],
      [
        { valor: '20.00', forma: 'CREDITO', creditoId: t.creditoId },
        { valor: '13.33', forma: 'PIX' },
      ],
    );
    expect(saldoDoCredito(b.db, t.creditoId)).toBe(333n);
    ops.estornar({ id: idDeTeste(8), vendaId: v3.vendaId, motivo: 'Desistiu' });
    expect(saldoDoCredito(b.db, t.creditoId)).toBe(2333n);
    expect(
      linha(
        "SELECT forma, valor, credito_id FROM pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO' AND forma = 'CREDITO'",
        v3.vendaId,
      ),
    ).toEqual({ forma: 'CREDITO', valor: '-20.00', credito_id: t.creditoId });
  });
});

describe('recusas no computador com o texto do servidor (teste 10)', () => {
  it.each([
    ['acima do que pode voltar', 'ACIMA'],
    ['Outro sem observação', 'OUTRO'],
    ['Defeito com peça boa', 'DEFEITO'],
    ['mesma peça repetida', 'REPETIDA'],
    ['peça fora da venda', 'FORA'],
    ['sem peças', 'VAZIA'],
  ] as const)('%s', (_nome, caso) => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const antes = retrato();
    const casos: Record<typeof caso, [DevolucaoParaEnviar, string]> = {
      ACIMA: [devolucao(vendaId, pecas([m.produtoA, 3])), 'ainda podem voltar só 2 peça(s) de'],
      OUTRO: [
        devolucao(vendaId, pecas([m.produtoA, 1]), { motivo: 'OUTRO', observacao: '   ' }),
        'Conte o motivo da devolução no campo de observação.',
      ],
      DEFEITO: [
        devolucao(vendaId, pecas([m.produtoA, 1, 'BOA']), { motivo: 'DEFEITO' }),
        'Com o motivo defeito, marque as peças como com defeito.',
      ],
      REPETIDA: [
        devolucao(vendaId, pecas([m.produtoA, 1], [m.produtoA, 1])),
        'A mesma peça aparece mais de uma vez com o mesmo estado. Junte as quantidades numa linha só.',
      ],
      FORA: [devolucao(vendaId, pecas([m.produtoB, 1])), 'Esta peça não consta nesta venda.'],
      VAZIA: [devolucao(vendaId, []), 'Informe pelo menos uma peça devolvida.'],
    };
    const [pedido, texto] = casos[caso];
    expect(erroDe(() => ops.devolver(pedido)).message).toContain(texto);
    expect(retrato()).toEqual(antes);
  });

  it('estornada, maleta em acerto e venda de outro vendedor recusam (a venda a prazo já não recusa)', () => {
    const estornada = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    ops.estornar({ id: idDeTeste(8), vendaId: estornada.vendaId, motivo: 'x' });
    expect(
      erroDe(() => ops.devolver(devolucao(estornada.vendaId, pecas([m.produtoA, 1])))).message,
    ).toBe('Esta venda já foi estornada.');
    const boa = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    mudarMaleta(b.db, m.remessaId, 'EM_ACERTO');
    expect(erroDe(() => ops.devolver(devolucao(boa.vendaId, pecas([m.produtoA, 1])))).message).toBe(
      'O acerto desta maleta está acontecendo. Espere o acerto terminar e registre a devolução de novo.',
    );
    // Venda de outro vendedor (defesa: nunca chega pelo envio, mas se estiver no banco, não aparece).
    mudarMaleta(b.db, m.remessaId, 'ENVIADA', {
      vendedor_id: '00000000-0000-7000-8000-0000000000e1',
    });
    const outra = idDeTeste(5);
    doServidor(b.db, 'vendas', outra, {
      vendedor_id: '00000000-0000-7000-8000-0000000000e2',
      cliente_id: m.clienteId,
      remessa_id: m.remessaId,
      status: 'CONCLUIDA',
      total: '33.33',
      vendida_em: T0.toISOString(),
    });
    expect(erroDe(() => ops.devolver(devolucao(outra, pecas([m.produtoA, 1])))).message).toBe(
      'Não encontramos esta venda neste computador. Atualize a tela e tente de novo.',
    );
  });

  it('prazo sem internet esgotado, relógio errado e computador desligado: nada grava', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const antes = retrato();
    for (const codigo of ['PRAZO_ESGOTADO', 'RELOGIO_ERRADO', 'COMPUTADOR_DESLIGADO'] as const) {
      bloquear = () => {
        throw new ErroDaOperacao(codigo);
      };
      expect(erroDe(() => ops.devolver(devolucao(vendaId, pecas([m.produtoA, 1])))).codigo).toBe(
        codigo,
      );
      expect(
        erroDe(() =>
          ops.pedirAutorizacao({
            id: idDeTeste(8),
            vendaId,
            motivo: 'ARREPENDIMENTO',
            itens: pecas([m.produtoA, 1]),
          }),
        ).codigo,
      ).toBe(codigo);
    }
    expect(retrato()).toEqual(antes);
  });
});

describe('"ainda podem voltar" não muda quando a venda chega do servidor (teste 11)', () => {
  it('conta a devolução aguardando pelo produto e não conta em dobro quando a cópia dela chega', () => {
    const { vendaId, total, pagamentoIds } = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    // O servidor aceita só a venda; o pull traz a cópia dela (itens com outro id) e as provisórias saem.
    b.db.prepare("UPDATE outbox SET situacao = 'ENVIADO' WHERE operacao_id = ?").run(vendaId);
    const itemDoServidor = idDeTeste(4);
    aplicarLote(
      b.db,
      [
        {
          tabela: 'vendas',
          id: vendaId,
          version: 1,
          deletedAt: null,
          campos: {
            cliente_id: m.clienteId,
            remessa_id: m.remessaId,
            status: 'CONCLUIDA',
            subtotal: total,
            desconto: '0.00',
            total,
            vendida_em: T0.toISOString(),
          },
        },
        {
          tabela: 'itens_venda',
          id: itemDoServidor,
          version: 1,
          deletedAt: null,
          campos: {
            venda_id: vendaId,
            produto_id: m.produtoA,
            quantidade: 2,
            preco_unitario: '33.33',
            desconto: '0.00',
          },
        },
        {
          tabela: 'movimentos_remessa',
          id: idDeTeste(4),
          version: 1,
          deletedAt: null,
          campos: {
            remessa_id: m.remessaId,
            produto_id: m.produtoA,
            tipo: 'VENDA',
            quantidade: -2,
            venda_id: vendaId,
          },
        },
        {
          tabela: 'pagamentos_cliente',
          id: pagamentoIds[0] ?? '',
          version: 1,
          deletedAt: null,
          campos: {
            venda_id: vendaId,
            tipo: 'PAGAMENTO',
            forma: 'PIX',
            valor: total,
            pago_em: T0.toISOString(),
          },
        },
      ],
      { cursor: 'c-1' },
    );
    limparProvisoriasEnviadas(b.db);
    expect(contar(b.db, 'SELECT 1 FROM itens_venda WHERE venda_id = ?', vendaId)).toBe(1);
    expect(pecasDaVenda(b.db, vendaId).get(m.produtoA)).toMatchObject({
      vendidas: 2,
      jaDevolvidas: 1,
      podemVoltar: 1,
    });
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(2);

    // A cópia da devolução chega: conta uma vez só.
    b.db.prepare("UPDATE outbox SET situacao = 'ENVIADO' WHERE operacao_id = ?").run(d.id);
    aplicarLote(
      b.db,
      [
        {
          tabela: 'devolucoes',
          id: d.id,
          version: 1,
          deletedAt: null,
          campos: {
            venda_id: vendaId,
            situacao: 'ACEITA',
            destino: 'REEMBOLSO',
            valor_devolvido: '33.33',
            devolvida_em: T0.toISOString(),
          },
        },
        {
          tabela: 'itens_devolucao',
          id: idDeTeste(4),
          version: 1,
          deletedAt: null,
          campos: {
            devolucao_id: d.id,
            item_venda_id: itemDoServidor,
            quantidade: 1,
            valor: '33.33',
            estado_peca: 'BOA',
            destino_peca: 'MALETA',
          },
        },
      ],
      { cursor: 'c-2' },
    );
    expect(pecasDaVenda(b.db, vendaId).get(m.produtoA)).toMatchObject({
      jaDevolvidas: 1,
      podemVoltar: 1,
    });
    limparProvisoriasEnviadas(b.db);
    expect(pecasDaVenda(b.db, vendaId).get(m.produtoA)).toMatchObject({
      jaDevolvidas: 1,
      podemVoltar: 1,
    });
  });
});

describe('devolução e troca de venda a prazo (tarefa 6.6)', () => {
  const dividido = (id: string) =>
    linha(
      'SELECT valor_devolvido, valor_abatido, valor_reembolsado, valor_em_credito FROM devolucoes WHERE id = ?',
      id,
    );

  it('sem pagamento: abate todo o valor e nenhum dinheiro volta', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }], []);
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    expect(dividido(d.id)).toEqual({
      valor_devolvido: '33.33',
      valor_abatido: '33.33',
      valor_reembolsado: '0.00',
      valor_em_credito: '0.00',
    });
    expect(contar(b.db, "SELECT 1 FROM pagamentos_cliente WHERE tipo = 'REEMBOLSO'")).toBe(0);
    // O abatimento não vai no JSON: só o servidor calcula (reforço 2 da aprovação).
    expect(dadosDa(d.id)).not.toContain('abatido');
  });

  it('paga em parte (10,00): abate o que falta (23,33) e devolve só o que foi pago', () => {
    const { vendaId } = vender(
      [{ produtoId: m.produtoA, quantidade: 1 }],
      [{ valor: '10.00', forma: 'PIX' }],
    );
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    expect(dividido(d.id)).toEqual({
      valor_devolvido: '33.33',
      valor_abatido: '23.33',
      valor_reembolsado: '10.00',
      valor_em_credito: '0.00',
    });
  });

  it('duas peças a prazo: a devolução de uma abate só o valor dela', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 2 }], []);
    // Duas peças a prazo (66,66): a devolução de uma abate 33,33 e nada mais.
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    expect(dividido(d.id)).toMatchObject({ valor_abatido: '33.33', valor_em_credito: '0.00' });
  });

  it('cancelar a devolução devolve a dívida: a prévia abate de novo', () => {
    const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }], []);
    const d = devolucao(vendaId, pecas([m.produtoA, 1]));
    ops.devolver(d);
    // Com a devolução ativa, já não há o que abater.
    expect(
      preverDevolucao(
        b.db,
        new Date('2026-11-12T13:00:00.000Z'),
        { vendaId, motivo: 'ARREPENDIMENTO', itens: [] },
        false,
        undefined,
        true,
      ).abatido,
    ).toBe(0n);
    ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d.id, motivo: 'engano' });
    const previa = preverDevolucao(
      b.db,
      new Date('2026-11-12T13:00:00.000Z'),
      { vendaId, motivo: 'ARREPENDIMENTO', itens: pecas([m.produtoA, 1]) },
      false,
    );
    expect(previa).toMatchObject({ abatido: 3333n, emDinheiro: 0n, emCredito: 0n });
  });

  describe('troca com A a prazo', () => {
    let produtoC: string;
    beforeEach(() => {
      mudarMaleta(b.db, m.remessaId, 'CONFERIDA');
      produtoC = idDeTeste(2);
      plantarPeca(b.db, m.remessaId, produtoC, 5, '50.00');
    });
    const trocar = (
      vendaId: string,
      pagamentos: { valor: string; forma: 'PIX' }[] = [],
    ): TrocaParaEnviar => {
      const t: TrocaParaEnviar = {
        id: idDeTeste(8),
        creditoId: idDeTeste(8),
        pagamentoDoCreditoId: idDeTeste(8),
        devolucao: {
          id: idDeTeste(8),
          vendaId,
          motivo: 'ARREPENDIMENTO',
          itens: pecas([m.produtoA, 1]),
        },
        venda: {
          id: idDeTeste(5),
          remessaId: m.remessaId,
          itens: [{ produtoId: produtoC, quantidade: 1 }],
          pagamentos: pagamentos.map((p) => ({ ...p, id: idDeTeste(6) })),
        },
      };
      ops.trocar(t);
      return t;
    };

    it('A sem nada pago: abate, não gera crédito e o cliente paga todo o B', () => {
      const { vendaId } = vender([{ produtoId: m.produtoA, quantidade: 1 }], []);
      const t = trocar(vendaId, [{ valor: '50.00', forma: 'PIX' }]);
      expect(dividido(t.devolucao.id)).toMatchObject({
        valor_abatido: '33.33',
        valor_em_credito: '0.00',
        valor_reembolsado: '0.00',
      });
      expect(contar(b.db, 'SELECT 1 FROM creditos_cliente')).toBe(0);
      expect(contar(b.db, "SELECT 1 FROM usos_credito WHERE tipo = 'USO'")).toBe(0);
      expect(somaDosPagamentos(t.venda.id)).toBe('50.00');
      // Só o crédito de A paga B (issue 208): sem crédito, a diferença é o total de B.
      expect(linha('SELECT diferenca FROM trocas WHERE id = ?', t.id)).toEqual({
        diferenca: '50.00',
      });
    });

    it('A paga em parte (10,00): o crédito é só o que passou do que faltava', () => {
      const { vendaId } = vender(
        [{ produtoId: m.produtoA, quantidade: 1 }],
        [{ valor: '10.00', forma: 'PIX' }],
      );
      const t = trocar(vendaId, [{ valor: '40.00', forma: 'PIX' }]);
      expect(dividido(t.devolucao.id)).toMatchObject({
        valor_abatido: '23.33',
        valor_em_credito: '10.00',
      });
      expect(somaDosPagamentos(t.venda.id)).toBe('50.00');
      expect(linha('SELECT diferenca FROM trocas WHERE id = ?', t.id)).toEqual({
        diferenca: '40.00',
      });
    });
  });
});
