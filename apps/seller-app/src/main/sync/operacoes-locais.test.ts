import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ErroDoBanco, gravar } from '../banco/banco-local';
import { aplicarLote } from './aplicador-do-pull';
import { ErroDaOperacao, OperacoesLocais, saldoDaPeca } from './operacoes-locais';
import { desfazerOperacao, limparProvisoriasEnviadas } from './outbox';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { type MaletaDeTeste, receberMaleta } from './testes/dados-da-maleta';

/**
 * Operações do vendedor no computador (tarefa 5.6; ADR-0013, itens 2 e 7): cópia provisória e outbox na mesma
 * transação, JSON exato, saldo em prévia.
 */
let b: BancoDeTeste;
let m: MaletaDeTeste;
let ops: OperacoesLocais;
let gravacoes: number;
const AGORA = new Date('2026-11-12T13:00:00.000Z');

beforeEach(() => {
  b = criarBancoDeTeste();
  m = receberMaleta(b.db);
  gravacoes = 0;
  ops = new OperacoesLocais({
    banco: () => b.db,
    agora: () => AGORA,
    aoGravar: () => {
      gravacoes += 1;
    },
  });
});
afterEach(() => b.fechar());

const venda = (quantidade = 2, extra: Partial<{ desconto: string; pago: string }> = {}) => ({
  id: idDeTeste(5),
  remessaId: m.remessaId,
  clienteId: m.clienteId,
  itens: [{ produtoId: m.produtoA, quantidade, desconto: extra.desconto ?? '0.10' }],
  pagamentos: [{ id: idDeTeste(6), valor: extra.pago ?? '20.00', forma: 'PIX' as const }],
});

const outbox = () =>
  b.db
    .prepare('SELECT ordem, operacao_id, tipo, formato, dados, situacao FROM outbox ORDER BY ordem')
    .all() as {
    ordem: number;
    operacao_id: string;
    tipo: string;
    formato: number;
    dados: string;
    situacao: string;
  }[];

describe('venda sem internet', () => {
  it('grava a venda provisória, a baixa da maleta e a operação juntas, com o total em centavos', () => {
    const v = venda(3);
    const r = ops.vender(v);
    expect(r).toEqual({ operacaoId: v.id, total: '99.89' }); // 3 × 33,33 − 0,10
    expect(
      b.db.prepare('SELECT status, subtotal, desconto, total, origem_local FROM vendas').get(),
    ).toEqual({
      status: 'CONCLUIDA',
      subtotal: '99.99',
      desconto: '0.10',
      total: '99.89',
      origem_local: 'PROVISORIO',
    });
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(0);
    const [linha] = outbox();
    expect(linha).toMatchObject({
      operacao_id: v.id,
      tipo: 'VENDA',
      formato: 1,
      situacao: 'AGUARDANDO',
    });
    expect(JSON.parse(linha?.dados ?? '')).toEqual({
      id: v.id,
      remessaId: m.remessaId,
      clienteId: m.clienteId,
      itens: [{ produtoId: m.produtoA, quantidade: 3, desconto: '0.10' }],
      pagamentos: [{ id: v.pagamentos[0]?.id, valor: '20.00', forma: 'PIX' }],
      totalConferido: '99.89',
      vendidaEm: AGORA.toISOString(),
    });
    expect(contar(b.db, 'SELECT 1 FROM provisorias WHERE operacao_id = ?', v.id)).toBe(4);
    expect(gravacoes).toBe(1);
  });

  it('sem saldo, peça fora da maleta ou pago acima do total: nada é gravado', () => {
    expect(() => ops.vender(venda(4))).toThrow(ErroDaOperacao);
    expect(() =>
      ops.vender({ ...venda(1), itens: [{ produtoId: idDeTeste(), quantidade: 1 }] }),
    ).toThrow('Esta peça não consta na sua maleta.');
    expect(() => ops.vender(venda(1, { pago: '40.00' }))).toThrow('O valor pago passa do total');
    expect(outbox()).toHaveLength(0);
    expect(contar(b.db, 'SELECT 1 FROM vendas')).toBe(0);
    expect(gravacoes).toBe(0);
  });

  it('falha injetada na outbox: nem a venda provisória nem a operação ficam', () => {
    b.db.exec(
      "CREATE TEMP TRIGGER falha_na_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'falha injetada'); END;",
    );
    expect(() => ops.vender(venda(1))).toThrow('falha injetada');
    expect(contar(b.db, 'SELECT 1 FROM vendas')).toBe(0);
    expect(contar(b.db, "SELECT 1 FROM movimentos_remessa WHERE origem_local = 'PROVISORIO'")).toBe(
      0,
    );
    expect(contar(b.db, 'SELECT 1 FROM provisorias')).toBe(0);
    expect(outbox()).toHaveLength(0);
  });

  it('disco cheio: a transação inteira é desfeita, com o texto do glossário', () => {
    const paginas = Number(b.db.pragma('page_count', { simple: true }));
    b.db.pragma(`max_page_count = ${paginas}`);
    let erro: unknown;
    try {
      // Muitas vendas grandes até o disco "encher".
      for (let i = 0; i < 200; i++) {
        ops.cadastrarCliente({ id: idDeTeste(7), nome: 'x'.repeat(2000) });
      }
    } catch (e) {
      erro = e;
    }
    expect(erro).toBeInstanceOf(ErroDoBanco);
    expect((erro as ErroDoBanco).message).toBe(
      'O computador está sem espaço. Libere espaço e tente de novo.',
    );
    // Cada cliente gravado tem a sua operação; nenhum ficou pela metade.
    expect(contar(b.db, "SELECT 1 FROM clientes WHERE origem_local = 'PROVISORIO'")).toBe(
      outbox().length,
    );
  });

  it('a ordem da fila é a ordem em que as operações foram feitas', () => {
    const v1 = venda(1);
    const v2 = venda(1);
    ops.vender(v1);
    ops.cadastrarCliente({ id: idDeTeste(7), nome: 'Bia' });
    ops.vender(v2);
    const fila = outbox();
    expect(fila.map((f) => f.tipo)).toEqual(['VENDA', 'CLIENTE_NOVO', 'VENDA']);
    expect(fila.map((f) => f.ordem)).toEqual([...fila.map((f) => f.ordem)].sort((a, z) => a - z));
    expect(fila[0]?.operacao_id).toBe(v1.id);
    expect(fila[2]?.operacao_id).toBe(v2.id);
  });

  it('o JSON guardado é o que a operação gravou, byte a byte', () => {
    const v = venda(1);
    ops.vender(v);
    const guardado = outbox()[0]?.dados ?? '';
    // Mudar a cópia local depois não muda a operação guardada.
    b.db.prepare("UPDATE vendas SET total = '0.00'").run();
    expect(outbox()[0]?.dados).toBe(guardado);
  });
});

describe('estorno sem internet', () => {
  it('as peças voltam para a maleta e o pagamento volta como reembolso', () => {
    const v = venda(2);
    ops.vender(v);
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
    const e = { id: idDeTeste(8), vendaId: v.id, motivo: 'Cliente desistiu' };
    ops.estornar(e);
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(3);
    expect(
      b.db.prepare("SELECT valor FROM pagamentos_cliente WHERE tipo = 'REEMBOLSO'").get(),
    ).toEqual({ valor: '-20.00' });
    expect(JSON.parse(outbox()[1]?.dados ?? '')).toEqual({
      ...e,
      estornadoEm: AGORA.toISOString(),
    });
    expect(() => ops.estornar({ ...e, id: idDeTeste(8) })).toThrow('Esta venda já foi estornada.');
  });
});

describe('clientes sem internet', () => {
  it('CPF repetido é conferido no diretório guardado no computador', () => {
    let erro: unknown;
    try {
      ops.cadastrarCliente({ id: idDeTeste(7), nome: 'Outra Ana', cpf: '123.456.789-09' });
    } catch (e) {
      erro = e;
    }
    expect(erro).toBeInstanceOf(ErroDaOperacao);
    expect((erro as ErroDaOperacao).message).toBe('Este cliente já está cadastrado: Ana.');
    expect((erro as ErroDaOperacao).clienteExistente).toEqual({
      id: m.clienteId,
      nome: 'Ana',
      telefone: '11999990000',
    });
    expect(outbox()).toHaveLength(0);
  });

  it('a alteração guarda antes e depois de cada campo e a versão que o computador tinha', () => {
    const r = ops.alterarCliente(m.clienteId, { telefone: '11888880000', nome: 'Ana' });
    expect(r).not.toBeNull();
    expect(JSON.parse(outbox()[0]?.dados ?? '')).toEqual({
      clienteId: m.clienteId,
      versaoBase: 1,
      campos: { telefone: { antes: '11999990000', depois: '11888880000' } },
    });
    expect(b.db.prepare('SELECT telefone FROM clientes WHERE id = ?').get(m.clienteId)).toEqual({
      telefone: '11888880000',
    });
    expect(ops.alterarCliente(m.clienteId, { telefone: '11888880000' })).toBeNull();
  });
});

describe('conferência sem internet', () => {
  it('grava a conferência provisória com o esperado da maleta', () => {
    const c = {
      id: idDeTeste(9),
      remessaId: m.remessaId,
      itens: [
        { produtoId: m.produtoA, contada: 3 },
        { produtoId: m.produtoB, contada: 0 },
      ],
    };
    ops.conferir(c);
    expect(b.db.prepare('SELECT divergencias, origem_local FROM conferencias').get()).toEqual({
      divergencias: 1,
      origem_local: 'PROVISORIO',
    });
    expect(JSON.parse(outbox()[0]?.dados ?? '')).toEqual(c);
  });
});

describe('desfazer e limpar provisórias', () => {
  it('desfazer uma venda devolve o saldo, e repetir é inofensivo', () => {
    const v = venda(2);
    ops.vender(v);
    gravar(b.db, () => desfazerOperacao(b.db, v.id));
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(3);
    expect(contar(b.db, 'SELECT 1 FROM vendas')).toBe(0);
    gravar(b.db, () => desfazerOperacao(b.db, v.id));
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(3);
  });

  it('desfazer uma alteração de cliente volta o valor de antes', () => {
    const r = ops.alterarCliente(m.clienteId, { telefone: '11888880000' });
    gravar(b.db, () => desfazerOperacao(b.db, r?.operacaoId ?? ''));
    expect(b.db.prepare('SELECT telefone FROM clientes WHERE id = ?').get(m.clienteId)).toEqual({
      telefone: '11999990000',
    });
  });

  it('só as provisórias de operações ENVIADAS saem no fim do recebimento', () => {
    const enviada = venda(1);
    const aguardando = venda(1);
    ops.vender(enviada);
    ops.vender(aguardando);
    b.db.prepare("UPDATE outbox SET situacao = 'ENVIADO' WHERE operacao_id = ?").run(enviada.id);
    // A cópia do servidor da venda enviada chegou (mesmo id da venda, outros ids nos itens e movimentos).
    aplicarLote(
      b.db,
      [
        {
          tabela: 'vendas',
          id: enviada.id,
          version: 1,
          deletedAt: null,
          campos: { remessa_id: m.remessaId, status: 'CONCLUIDA', total: '33.23' },
        },
        {
          tabela: 'movimentos_remessa',
          id: idDeTeste(),
          version: 1,
          deletedAt: null,
          campos: {
            remessa_id: m.remessaId,
            produto_id: m.produtoA,
            tipo: 'VENDA',
            quantidade: -1,
            venda_id: enviada.id,
          },
        },
      ],
      { cursor: 'c2' },
    );
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(0); // contou a baixa duas vezes até limpar
    gravar(b.db, () => limparProvisoriasEnviadas(b.db));
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
    expect(b.db.prepare('SELECT origem_local FROM vendas WHERE id = ?').get(enviada.id)).toEqual({
      origem_local: 'SERVIDOR',
    });
    expect(contar(b.db, 'SELECT 1 FROM provisorias WHERE operacao_id = ?', aguardando.id)).toBe(4);
    expect(contar(b.db, 'SELECT 1 FROM provisorias WHERE operacao_id = ?', enviada.id)).toBe(0);
  });
});
