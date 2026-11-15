import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { TrocaParaEnviar } from '../../shared/operacoes';
import { ErroDaOperacao, OperacoesLocais } from './operacoes-locais';
import { desfazerOperacao } from './outbox';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { type MaletaDeTeste, receberMaleta } from './testes/dados-da-maleta';
import { mudarMaleta, plantarPeca, venderPago } from './testes/dados-da-fase-6';

/**
 * Parcelamento na venda e na troca, recebimento do cliente e estorno do recebimento no computador (tarefa 7.11; ADR-0012,
 * item 7). O computador NUNCA calcula parcela: grava só a quantidade e o primeiro vencimento no JSON exato da outbox e
 * confere o que o servidor também recusaria. SQLCipher de verdade. Escrito antes do código.
 */
let b: BancoDeTeste;
let m: MaletaDeTeste;
let ops: OperacoesLocais;
let agora: Date;
let bloquear: (() => void) | null;
/** 12/11/2026 10:00 em Brasília. */
const T0 = new Date('2026-11-12T13:00:00.000Z');
const PARCELAS = { quantidade: 3, primeiroVencimento: '2026-12-12' };

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

const dadosDa = (id: string): string =>
  (b.db.prepare('SELECT dados FROM outbox WHERE operacao_id = ?').get(id) as { dados: string })
    .dados;
const tipos = (): string[] =>
  (b.db.prepare('SELECT tipo FROM outbox ORDER BY ordem').all() as { tipo: string }[]).map(
    (l) => l.tipo,
  );
const codigoDe = (f: () => unknown): string | undefined => {
  try {
    f();
  } catch (e) {
    return e instanceof ErroDaOperacao ? e.codigo : 'OUTRO';
  }
  return undefined;
};

/** Venda de 1 peça A (R$ 33,33) a prazo, com o parcelamento (ou não). */
function vendaAPrazo(parcelamento: typeof PARCELAS | null = PARCELAS, pago?: string) {
  const id = idDeTeste(5);
  const pagamentos = pago ? [{ id: idDeTeste(6), valor: pago, forma: 'DINHEIRO' as const }] : [];
  ops.vender({
    id,
    remessaId: m.remessaId,
    clienteId: m.clienteId,
    itens: [{ produtoId: m.produtoA, quantidade: 1 }],
    pagamentos,
    ...(parcelamento ? { parcelamento } : {}),
  });
  return id;
}

describe('venda a prazo com parcelas', () => {
  it('grava só a quantidade e o primeiro vencimento no JSON, sem parcela, sem total calculado de parcela', () => {
    const id = vendaAPrazo(PARCELAS, '10.00');
    const dados = JSON.parse(dadosDa(id)) as Record<string, unknown>;
    expect(dados['parcelamento']).toEqual(PARCELAS);
    expect(Object.keys(dados)).toEqual([
      'id',
      'remessaId',
      'clienteId',
      'itens',
      'pagamentos',
      'totalConferido',
      'vendidaEm',
      'parcelamento',
    ]);
    // Nenhuma parcela é calculada aqui: a cópia local das parcelas só chega do servidor.
    expect(contar(b.db, 'SELECT 1 FROM contas_receber')).toBe(0);
    expect(contar(b.db, 'SELECT 1 FROM baixas_receber')).toBe(0);
  });

  it('a venda sem parcelamento continua com os mesmos bytes de antes (sem a chave)', () => {
    const id = vendaAPrazo(null, '10.00');
    expect(dadosDa(id)).not.toContain('parcelamento');
  });

  it('recusa parcelas quando nada fica a prazo e não grava nada', () => {
    const id = idDeTeste(5);
    const antes = contar(b.db, 'SELECT 1 FROM outbox');
    const codigo = codigoDe(() =>
      ops.vender({
        id,
        remessaId: m.remessaId,
        clienteId: m.clienteId,
        itens: [{ produtoId: m.produtoA, quantidade: 1 }],
        pagamentos: [{ id: idDeTeste(6), valor: '33.33', forma: 'PIX' }],
        parcelamento: PARCELAS,
      }),
    );
    expect(codigo).toBe('PARCELAMENTO_SEM_SALDO');
    expect(contar(b.db, 'SELECT 1 FROM outbox')).toBe(antes);
    expect(contar(b.db, 'SELECT 1 FROM vendas')).toBe(0);
  });

  it('recusa o primeiro vencimento antes de hoje e o que passa de um ano', () => {
    expect(codigoDe(() => vendaAPrazo({ quantidade: 2, primeiroVencimento: '2026-11-11' }))).toBe(
      'VENCIMENTO_NO_PASSADO',
    );
    expect(codigoDe(() => vendaAPrazo({ quantidade: 2, primeiroVencimento: '2027-11-13' }))).toBe(
      'VENCIMENTO_LONGE_DEMAIS',
    );
    // Hoje e daqui a exatamente um ano valem.
    expect(codigoDe(() => vendaAPrazo({ quantidade: 2, primeiroVencimento: '2026-11-12' }))).toBe(
      undefined,
    );
  });

  it('o dia de hoje é o de Brasília (23h do dia anterior em UTC já é o dia seguinte lá)', () => {
    agora = new Date('2026-11-13T02:00:00.000Z'); // 12/11 23:00 em Brasília
    expect(codigoDe(() => vendaAPrazo({ quantidade: 1, primeiroVencimento: '2026-11-12' }))).toBe(
      undefined,
    );
  });
});

describe('troca com parcelas na venda nova', () => {
  const produtoC = idDeTeste(2);
  beforeEach(() => {
    mudarMaleta(b.db, m.remessaId, 'CONFERIDA');
    plantarPeca(b.db, m.remessaId, produtoC, 5, '50.00');
  });

  const troca = (
    vendaId: string,
    pagamentos: { valor: string; forma: 'DINHEIRO' | 'PIX' }[],
    parcelamento?: typeof PARCELAS,
  ): TrocaParaEnviar => ({
    id: idDeTeste(8),
    creditoId: idDeTeste(8),
    pagamentoDoCreditoId: idDeTeste(8),
    devolucao: {
      id: idDeTeste(8),
      vendaId,
      motivo: 'ARREPENDIMENTO',
      itens: [{ produtoId: m.produtoA, quantidade: 1, estado: 'BOA' }],
    },
    venda: {
      id: idDeTeste(5),
      remessaId: m.remessaId,
      itens: [{ produtoId: produtoC, quantidade: 1 }],
      pagamentos: pagamentos.map((p) => ({ ...p, id: idDeTeste(6) })),
      ...(parcelamento ? { parcelamento } : {}),
    },
  });
  const vendaPaga = () =>
    venderPago(b.db, ops, m.remessaId, m.clienteId, [{ produtoId: m.produtoA, quantidade: 1 }])
      .vendaId;

  it('a diferença a prazo leva o parcelamento no JSON da venda nova; nada é calculado aqui', () => {
    const t = troca(vendaPaga(), [{ valor: '5.00', forma: 'DINHEIRO' }], PARCELAS);
    ops.trocar(t);
    const venda = (JSON.parse(dadosDa(t.id)) as { venda: Record<string, unknown> }).venda;
    expect(venda['parcelamento']).toEqual(PARCELAS);
    expect(venda['totalConferido']).toBeNull();
    expect(contar(b.db, 'SELECT 1 FROM contas_receber')).toBe(0);
  });

  it('sem parcelamento o JSON da venda nova não ganha a chave', () => {
    const t = troca(vendaPaga(), [{ valor: '16.67', forma: 'DINHEIRO' }]);
    ops.trocar(t);
    expect(dadosDa(t.id)).not.toContain('parcelamento');
  });

  it('recusa parcelas quando a diferença já foi toda paga', () => {
    const t = troca(vendaPaga(), [{ valor: '16.67', forma: 'DINHEIRO' }], PARCELAS);
    expect(codigoDe(() => ops.trocar(t))).toBe('PARCELAMENTO_SEM_SALDO');
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE tipo = 'TROCA'")).toBe(0);
  });
});

describe('receber do cliente depois da venda', () => {
  const receber = (vendaId: string, valor: string, forma: 'DINHEIRO' | 'PIX' = 'DINHEIRO') => {
    const id = idDeTeste(9);
    ops.receberDoCliente({ id, vendaId, valor, forma });
    return id;
  };

  it('grava o pagamento provisório (posterior) e a operação juntos, com o JSON exato', () => {
    const vendaId = vendaAPrazo(PARCELAS);
    const id = receber(vendaId, '12.34', 'PIX');
    expect(
      b.db
        .prepare(
          'SELECT venda_id, tipo, forma, valor, pago_em, posterior, origem_local FROM pagamentos_cliente WHERE id = ?',
        )
        .get(id),
    ).toEqual({
      venda_id: vendaId,
      tipo: 'PAGAMENTO',
      forma: 'PIX',
      valor: '12.34',
      pago_em: '2026-11-12T13:00:00.000Z',
      posterior: 1,
      origem_local: 'PROVISORIO',
    });
    expect(dadosDa(id)).toBe(
      `{"id":"${id}","vendaId":"${vendaId}","valor":"12.34","forma":"PIX","recebidoEm":"2026-11-12T13:00:00.000Z"}`,
    );
    expect(tipos()).toEqual(['VENDA', 'RECEBIMENTO_CLIENTE']);
    // Nenhuma baixa de parcela é inventada aqui.
    expect(contar(b.db, 'SELECT 1 FROM baixas_receber')).toBe(0);
  });

  it('recusa valor acima do que falta, venda já paga e venda inexistente, sem gravar nada', () => {
    const vendaId = vendaAPrazo(PARCELAS, '10.00'); // falta 23,33
    expect(codigoDe(() => receber(vendaId, '23.34'))).toBe('RECEBIMENTO_ACIMA_DO_EM_ABERTO');
    receber(vendaId, '23.33');
    expect(codigoDe(() => receber(vendaId, '0.01'))).toBe('VENDA_SEM_VALOR_EM_ABERTO');
    expect(codigoDe(() => receber(idDeTeste(5), '1.00'))).toBe('VENDA_NAO_ENCONTRADA');
    expect(tipos()).toEqual(['VENDA', 'RECEBIMENTO_CLIENTE']);
  });

  it('o texto de "acima do que falta" diz quanto falta, em reais', () => {
    const vendaId = vendaAPrazo(PARCELAS, '10.00');
    try {
      receber(vendaId, '30.00');
      expect.unreachable();
    } catch (e) {
      expect((e as Error).message).toContain('23,33');
    }
  });

  it('venda estornada não recebe', () => {
    const vendaId = vendaAPrazo(PARCELAS);
    ops.estornar({ id: idDeTeste(7), vendaId, motivo: 'Errei' });
    expect(codigoDe(() => receber(vendaId, '1.00'))).toBe('VENDA_JA_ESTORNADA');
  });

  it('com o prazo sem internet esgotado nada grava', () => {
    const vendaId = vendaAPrazo(PARCELAS);
    bloquear = () => {
      throw new ErroDaOperacao('PRAZO_ESGOTADO');
    };
    expect(codigoDe(() => receber(vendaId, '1.00'))).toBe('PRAZO_ESGOTADO');
    expect(contar(b.db, 'SELECT 1 FROM pagamentos_cliente WHERE posterior = 1')).toBe(0);
  });

  it('o servidor recusar desfaz o recebimento provisório', () => {
    const vendaId = vendaAPrazo(PARCELAS);
    const id = receber(vendaId, '5.00');
    desfazerOperacao(b.db, id);
    expect(contar(b.db, 'SELECT 1 FROM pagamentos_cliente WHERE posterior = 1')).toBe(0);
  });
});

describe('estornar um recebimento no mesmo dia', () => {
  const venda = () => vendaAPrazo(PARCELAS, '10.00');
  const receber = (vendaId: string, valor = '5.00') => {
    const id = idDeTeste(9);
    ops.receberDoCliente({ id, vendaId, valor, forma: 'DINHEIRO' });
    return id;
  };
  const estornar = (id: string, motivo = 'Valor errado') => {
    const estornoId = idDeTeste(10);
    ops.estornarRecebimento({ id: estornoId, recebimentoId: id, motivo });
    return estornoId;
  };

  it('grava o estorno negativo ligado ao recebimento e a operação, com o JSON exato', () => {
    const vendaId = venda();
    const id = receber(vendaId);
    agora = new Date('2026-11-12T20:00:00.000Z'); // ainda o mesmo dia em Brasília
    const estornoId = estornar(id);
    expect(
      b.db
        .prepare(
          'SELECT venda_id, tipo, forma, valor, posterior, estorna_id, origem_local FROM pagamentos_cliente WHERE id = ?',
        )
        .get(estornoId),
    ).toEqual({
      venda_id: vendaId,
      tipo: 'ESTORNO_PAGAMENTO',
      forma: 'DINHEIRO',
      valor: '-5.00',
      posterior: 1,
      estorna_id: id,
      origem_local: 'PROVISORIO',
    });
    expect(dadosDa(estornoId)).toBe(
      `{"id":"${estornoId}","recebimentoId":"${id}","motivo":"Valor errado","estornadoEm":"2026-11-12T20:00:00.000Z"}`,
    );
    expect(tipos()).toEqual(['VENDA', 'RECEBIMENTO_CLIENTE', 'ESTORNO_RECEBIMENTO_CLIENTE']);
  });

  it('o mesmo dia vale pelo relógio de Brasília, não pelo de Greenwich', () => {
    const vendaId = venda();
    agora = new Date('2026-11-13T01:30:00.000Z'); // 12/11 22:30 em Brasília
    const id = receber(vendaId);
    agora = new Date('2026-11-13T02:30:00.000Z'); // 12/11 23:30 em Brasília
    expect(codigoDe(() => estornar(id))).toBeUndefined();
  });

  it('no dia seguinte só a dona estorna', () => {
    const id = receber(venda());
    agora = new Date('2026-11-13T13:00:00.000Z');
    expect(codigoDe(() => estornar(id))).toBe('ESTORNO_DE_RECEBIMENTO_SO_DA_DONA');
  });

  it('não estorna duas vezes, nem sem motivo, nem o que não existe', () => {
    const id = receber(venda());
    expect(codigoDe(() => estornar(id, '   '))).toBe('MOTIVO_DO_ESTORNO_DO_RECEBIMENTO');
    estornar(id);
    expect(codigoDe(() => estornar(id))).toBe('RECEBIMENTO_JA_ESTORNADO');
    expect(codigoDe(() => estornar(idDeTeste(9)))).toBe('RECEBIMENTO_NAO_ENCONTRADO');
  });

  it('pagamento feito na hora da venda só se corrige estornando a venda', () => {
    const vendaId = venda();
    const naHora = (
      b.db
        .prepare('SELECT id FROM pagamentos_cliente WHERE venda_id = ? AND posterior IS NULL')
        .get(vendaId) as { id: string }
    ).id;
    expect(codigoDe(() => estornar(naHora))).toBe('PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA');
  });

  it('depois do estorno o cliente volta a dever e pode pagar de novo', () => {
    const vendaId = venda(); // falta 23,33
    const id = receber(vendaId, '23.33');
    expect(codigoDe(() => receber(vendaId, '1.00'))).toBe('VENDA_SEM_VALOR_EM_ABERTO');
    estornar(id);
    expect(codigoDe(() => receber(vendaId, '23.33'))).toBeUndefined();
  });

  it('o servidor recusar o estorno desfaz só o estorno provisório', () => {
    const id = receber(venda());
    const estornoId = estornar(id);
    desfazerOperacao(b.db, estornoId);
    expect(contar(b.db, "SELECT 1 FROM pagamentos_cliente WHERE tipo = 'ESTORNO_PAGAMENTO'")).toBe(
      0,
    );
    expect(contar(b.db, 'SELECT 1 FROM pagamentos_cliente WHERE posterior = 1')).toBe(1);
  });
});
