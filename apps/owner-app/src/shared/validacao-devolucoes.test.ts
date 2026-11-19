import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const OUTRO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';

describe('conferência dos dados de devoluções, trocas, créditos e garantia', () => {
  it('as listas aceitam só os filtros combinados', () => {
    expect(conferirParametros('devolucoes.autorizacoes', {})).toEqual({
      operacao: 'devolucoes.autorizacoes',
    });
    expect(
      conferirParametros('devolucoes.autorizacoes', { situacao: 'APROVADA', vendedorId: ID }),
    ).toEqual({ operacao: 'devolucoes.autorizacoes', situacao: 'APROVADA', vendedorId: ID });
    expect(conferirParametros('devolucoes.autorizacoes', { situacao: 'EM_REVISAO' })).toBeNull();
    expect(conferirParametros('devolucoes.autorizacoes', { vendedorId: 'x' })).toBeNull();
    expect(conferirParametros('devolucoes.autorizacoes', { outro: 1 })).toBeNull();

    expect(conferirParametros('devolucoes.listar', { situacao: 'EM_REVISAO' })).not.toBeNull();
    expect(conferirParametros('devolucoes.listar', { situacao: 'PEDIDA' })).toBeNull();
    expect(conferirParametros('trocas.listar', { situacao: 'ATIVA' })).not.toBeNull();
    expect(conferirParametros('trocas.listar', { situacao: 'ABERTO' })).toBeNull();
    expect(conferirParametros('creditos.listar', { situacao: 'UTILIZADO' })).not.toBeNull();
    expect(conferirParametros('creditos.listar', { situacao: 'ATIVA' })).toBeNull();
    expect(conferirParametros('creditos.listar', { clienteId: ID })).not.toBeNull();
  });

  it('situação vazia vale como não escolhida', () => {
    expect(conferirParametros('trocas.listar', { situacao: '' })).toEqual({
      operacao: 'trocas.listar',
    });
  });

  it('aprovar autorização, manter e ler a garantia pedem só o que precisam', () => {
    expect(conferirParametros('devolucoes.aprovarAutorizacao', { id: ID })).toEqual({
      operacao: 'devolucoes.aprovarAutorizacao',
      id: ID,
    });
    expect(conferirParametros('devolucoes.aprovarAutorizacao', { id: 'x' })).toBeNull();
    expect(conferirParametros('devolucoes.aprovarAutorizacao', { id: ID, a: 1 })).toBeNull();
    expect(conferirParametros('devolucoes.manter', { id: ID })).toEqual({
      operacao: 'devolucoes.manter',
      id: ID,
    });
    expect(
      conferirParametros('devolucoes.manter', { id: ID, observacao: 'Cliente antigo' }),
    ).toEqual({ operacao: 'devolucoes.manter', id: ID, observacao: 'Cliente antigo' });
    expect(conferirParametros('garantia.ler', {})).toEqual({ operacao: 'garantia.ler' });
    expect(conferirParametros('garantia.ler', { id: ID })).toBeNull();
  });

  it('negar, não aceitar e cancelar exigem motivo (até 400 letras)', () => {
    for (const op of ['devolucoes.negarAutorizacao', 'devolucoes.naoAceitar'] as const) {
      expect(conferirParametros(op, { id: ID, motivo: 'Fora do prazo' })).toMatchObject({ id: ID });
      expect(conferirParametros(op, { id: ID, motivo: '   ' })).toBeNull();
      expect(conferirParametros(op, { id: ID })).toBeNull();
      expect(conferirParametros(op, { id: ID, motivo: 'a'.repeat(401) })).toBeNull();
    }
    for (const op of ['devolucoes.cancelar', 'trocas.cancelar'] as const) {
      expect(
        conferirParametros(op, { id: ID, cancelamentoId: OUTRO, motivo: 'Foi engano' }),
      ).toEqual({ operacao: op, id: ID, cancelamentoId: OUTRO, motivo: 'Foi engano' });
      expect(conferirParametros(op, { id: ID, motivo: 'Foi engano' })).toBeNull();
      expect(conferirParametros(op, { id: ID, cancelamentoId: 'x', motivo: 'Foi' })).toBeNull();
      expect(conferirParametros(op, { id: ID, cancelamentoId: OUTRO, motivo: '' })).toBeNull();
    }
  });

  it('a busca da venda pede o código da peça', () => {
    expect(conferirParametros('devolucoes.vendasComAPeca', { codigo: ' AB-1 ' })).toEqual({
      operacao: 'devolucoes.vendasComAPeca',
      codigo: 'AB-1',
    });
    expect(conferirParametros('devolucoes.vendasComAPeca', { codigo: '' })).toBeNull();
    expect(conferirParametros('devolucoes.vendasComAPeca', { codigo: 'a'.repeat(101) })).toBeNull();
    expect(conferirParametros('devolucoes.vendasComAPeca', {})).toBeNull();
  });

  describe('registrar devolução', () => {
    const corpo = {
      id: ID,
      vendaId: OUTRO,
      motivo: 'ARREPENDIMENTO',
      formaReembolso: 'PIX',
      itens: [{ produtoId: ID, quantidade: 2, estado: 'BOA' }],
    };

    it('aceita o pedido completo e leva só o que a API espera', () => {
      expect(conferirParametros('devolucoes.registrar', { corpo })).toEqual({
        operacao: 'devolucoes.registrar',
        corpo,
      });
      expect(
        conferirParametros('devolucoes.registrar', {
          corpo: { ...corpo, motivo: 'OUTRO', observacao: ' Cor diferente ' },
        }),
      ).toMatchObject({ corpo: { observacao: 'Cor diferente' } });
    });

    it('recusa o que o servidor recusaria e qualquer campo a mais (nunca valor)', () => {
      const ruins: Record<string, unknown>[] = [
        { ...corpo, id: 'x' },
        { ...corpo, vendaId: undefined },
        { ...corpo, motivo: 'QUALQUER' },
        { ...corpo, formaReembolso: 'BOLETO' },
        { ...corpo, itens: [] },
        { ...corpo, itens: [{ produtoId: ID, quantidade: 0, estado: 'BOA' }] },
        { ...corpo, itens: [{ produtoId: ID, quantidade: 1.5, estado: 'BOA' }] },
        { ...corpo, itens: [{ produtoId: ID, quantidade: 1, estado: 'RUIM' }] },
        { ...corpo, itens: [{ produtoId: 'x', quantidade: 1, estado: 'BOA' }] },
        { ...corpo, itens: [{ produtoId: ID, quantidade: 1, estado: 'BOA', valor: '10.00' }] },
        { ...corpo, valorDevolvido: '10.00' },
        { ...corpo, observacao: 'a'.repeat(401) },
      ];
      for (const ruim of ruins) {
        expect(conferirParametros('devolucoes.registrar', { corpo: ruim })).toBeNull();
      }
      expect(conferirParametros('devolucoes.registrar', corpo)).toBeNull();
    });
  });

  it('transferir crédito pede o crédito, o id novo e o representante', () => {
    expect(
      conferirParametros('creditos.transferir', { id: ID, novoId: OUTRO, vendedorId: ID }),
    ).toEqual({ operacao: 'creditos.transferir', id: ID, novoId: OUTRO, vendedorId: ID });
    expect(conferirParametros('creditos.transferir', { id: ID, vendedorId: ID })).toBeNull();
    expect(
      conferirParametros('creditos.transferir', { id: ID, novoId: OUTRO, vendedorId: 'x' }),
    ).toBeNull();
  });

  describe('garantia e limites', () => {
    const ok = {
      prazoArrependimentoDias: 7,
      prazoDefeitoDias: 30,
      limiteReembolsoSemanal: null,
      valorAprovacaoReembolso: '500.00',
      percentualAlertaDevolucao: 20,
    };

    it('aceita os cinco valores, com dinheiro vazio (sem limite) ou em texto', () => {
      expect(conferirParametros('garantia.alterar', ok)).toEqual({
        operacao: 'garantia.alterar',
        ...ok,
      });
      expect(
        conferirParametros('garantia.alterar', { ...ok, limiteReembolsoSemanal: '1234.50' }),
      ).not.toBeNull();
    });

    it('recusa dias e porcentagem fora da faixa, dinheiro zero ou torto e campo a mais', () => {
      const ruins: Record<string, unknown>[] = [
        { ...ok, prazoArrependimentoDias: 0 },
        { ...ok, prazoArrependimentoDias: 366 },
        { ...ok, prazoDefeitoDias: 7.5 },
        { ...ok, prazoDefeitoDias: '30' },
        { ...ok, percentualAlertaDevolucao: 0 },
        { ...ok, percentualAlertaDevolucao: 101 },
        { ...ok, limiteReembolsoSemanal: '0.00' },
        { ...ok, limiteReembolsoSemanal: '-5.00' },
        { ...ok, valorAprovacaoReembolso: 'dez' },
        { ...ok, valorAprovacaoReembolso: 10 },
        { ...ok, extra: 1 },
      ];
      for (const ruim of ruins) {
        expect(conferirParametros('garantia.alterar', ruim)).toBeNull();
      }
      const faltando: Record<string, unknown> = { ...ok };
      delete faltando.prazoDefeitoDias;
      expect(conferirParametros('garantia.alterar', faltando)).toBeNull();
    });
  });
});
