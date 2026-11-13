import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';

/**
 * A ponte das operações da tarefa 6.8 (plano, teste 14): ids UUID, listas fechadas (motivo, estado, forma), inteiros
 * positivos, texto até 400 letras e NENHUM campo de valor vindo da tela na devolução, no pedido e na parte que volta da
 * troca (só o desconto e o pago da diferença, como em Vender). Escrito antes do código.
 */
const ID = '3f2b8c1e-5d4a-4b7e-9c1d-2a6f8e0b4c11';
const OUTRO = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const MAIS = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c';

const pecas = [{ produtoId: OUTRO, quantidade: 1, estado: 'BOA' }];
const devolucao = {
  id: ID,
  vendaId: OUTRO,
  motivo: 'ARREPENDIMENTO',
  formaReembolso: 'PIX',
  itens: pecas,
};
const troca = {
  id: ID,
  creditoId: OUTRO,
  pagamentoDoCreditoId: MAIS,
  devolucao: { id: MAIS, vendaId: OUTRO, motivo: 'TAMANHO', itens: pecas },
  venda: {
    id: OUTRO,
    remessaId: ID,
    itens: [{ produtoId: ID, quantidade: 1, desconto: '1.00' }],
    pagamentos: [{ id: ID, valor: '10.00', forma: 'DINHEIRO' }],
  },
};

describe('as operações da devolução, troca e crédito na ponte', () => {
  it('aceita cada uma no formato certo e devolve uma cópia limpa', () => {
    const certos = [
      { operacao: 'devolucoes.vendasComAPeca', codigo: 'ANEL-1' },
      {
        operacao: 'devolucoes.previa',
        vendaId: ID,
        motivo: 'DEFEITO',
        itens: [{ ...pecas[0], estado: 'DEFEITO' }],
      },
      { operacao: 'devolucoes.previa', vendaId: ID, itens: [] },
      { operacao: 'devolucoes.registrar', devolucao },
      {
        operacao: 'devolucoes.registrar',
        devolucao: { ...devolucao, motivo: 'OUTRO', observacao: 'Não gostou da cor' },
      },
      { operacao: 'devolucoes.cancelar', id: ID, cancelamentoId: OUTRO, motivo: 'Errei' },
      { operacao: 'devolucoes.listar', de: '2026-11-01', ate: '2026-11-30' },
      {
        operacao: 'autorizacoes.pedir',
        pedido: { id: ID, vendaId: OUTRO, motivo: 'ARREPENDIMENTO', itens: pecas },
      },
      { operacao: 'autorizacoes.listar' },
      {
        operacao: 'trocas.previa',
        devolucao: { vendaId: OUTRO, motivo: 'TAMANHO', itens: pecas },
        remessaId: ID,
        itens: [{ produtoId: ID, quantidade: 1 }],
      },
      { operacao: 'trocas.registrar', troca },
      { operacao: 'trocas.cancelar', id: ID, cancelamentoId: OUTRO, motivo: 'Desistiu' },
      { operacao: 'creditos.doCliente', clienteId: ID },
      {
        operacao: 'vendas.vender',
        venda: {
          id: ID,
          remessaId: OUTRO,
          clienteId: ID,
          itens: [{ produtoId: OUTRO, quantidade: 1 }],
          pagamentos: [
            { id: OUTRO, valor: '10.00', forma: 'CREDITO', creditoId: MAIS },
            { id: MAIS, valor: '5.00', forma: 'PIX' },
          ],
        },
      },
    ];
    for (const pedido of certos)
      expect(validarChamada(pedido), JSON.stringify(pedido)).toEqual(pedido);
  });

  it.each([['valor'], ['valorDevolvido'], ['diferenca'], ['valorConferido'], ['totalConferido']])(
    'recusa o campo de valor "%s" vindo da tela',
    (campo) => {
      expect(
        validarChamada({
          operacao: 'devolucoes.registrar',
          devolucao: { ...devolucao, [campo]: '10.00' },
        }),
      ).toBeNull();
      expect(
        validarChamada({
          operacao: 'autorizacoes.pedir',
          pedido: {
            id: ID,
            vendaId: OUTRO,
            motivo: 'ARREPENDIMENTO',
            itens: pecas,
            [campo]: '1.00',
          },
        }),
      ).toBeNull();
      expect(
        validarChamada({
          operacao: 'trocas.registrar',
          troca: { ...troca, devolucao: { ...troca.devolucao, [campo]: '1.00' } },
        }),
      ).toBeNull();
      expect(
        validarChamada({ operacao: 'trocas.registrar', troca: { ...troca, [campo]: '1.00' } }),
      ).toBeNull();
      expect(
        validarChamada({
          operacao: 'devolucoes.registrar',
          devolucao: { ...devolucao, itens: [{ ...pecas[0], [campo]: '1.00' }] },
        }),
      ).toBeNull();
    },
  );

  it('recusa id malformado, lista fora do lugar, quantidade zero ou quebrada e texto longo', () => {
    const errados = [
      { operacao: 'devolucoes.registrar', devolucao: { ...devolucao, id: 'abc' } },
      { operacao: 'devolucoes.registrar', devolucao: { ...devolucao, motivo: 'GOSTOU' } },
      { operacao: 'devolucoes.registrar', devolucao: { ...devolucao, formaReembolso: 'CREDITO' } },
      {
        operacao: 'devolucoes.registrar',
        devolucao: { ...devolucao, itens: [{ ...pecas[0], estado: 'RUIM' }] },
      },
      {
        operacao: 'devolucoes.registrar',
        devolucao: { ...devolucao, itens: [{ ...pecas[0], quantidade: 0 }] },
      },
      {
        operacao: 'devolucoes.registrar',
        devolucao: { ...devolucao, itens: [{ ...pecas[0], quantidade: 1.5 }] },
      },
      {
        operacao: 'devolucoes.registrar',
        devolucao: { ...devolucao, observacao: 'x'.repeat(401) },
      },
      { operacao: 'devolucoes.cancelar', id: ID, cancelamentoId: OUTRO, motivo: '   ' },
      { operacao: 'devolucoes.cancelar', id: ID, cancelamentoId: 'x', motivo: 'Errei' },
      { operacao: 'trocas.cancelar', id: ID, cancelamentoId: OUTRO, motivo: 'x'.repeat(401) },
      { operacao: 'devolucoes.vendasComAPeca', codigo: 'x'.repeat(101) },
      { operacao: 'devolucoes.listar', de: '01/11/2026' },
      { operacao: 'creditos.doCliente', clienteId: 'ana' },
      // A troca paga a diferença à vista ou a prazo, nunca com outro crédito (dúvida V8).
      {
        operacao: 'trocas.registrar',
        troca: {
          ...troca,
          venda: {
            ...troca.venda,
            pagamentos: [{ id: ID, valor: '1.00', forma: 'CREDITO', creditoId: OUTRO }],
          },
        },
      },
      // O crédito só com a forma crédito, e a forma crédito sempre com o crédito.
      {
        operacao: 'vendas.vender',
        venda: {
          id: ID,
          remessaId: OUTRO,
          clienteId: ID,
          itens: [{ produtoId: OUTRO, quantidade: 1 }],
          pagamentos: [{ id: OUTRO, valor: '1.00', forma: 'CREDITO' }],
        },
      },
      {
        operacao: 'vendas.vender',
        venda: {
          id: ID,
          remessaId: OUTRO,
          clienteId: ID,
          itens: [{ produtoId: OUTRO, quantidade: 1 }],
          pagamentos: [{ id: OUTRO, valor: '1.00', forma: 'PIX', creditoId: MAIS }],
        },
      },
    ];
    for (const pedido of errados)
      expect(validarChamada(pedido), JSON.stringify(pedido).slice(0, 120)).toBeNull();
  });

  it('descarta campo a mais que não é valor (a ponte não repassa o que não conhece)', () => {
    expect(
      validarChamada({
        operacao: 'devolucoes.registrar',
        devolucao: { ...devolucao, vendedorId: ID },
      }),
    ).toEqual({ operacao: 'devolucoes.registrar', devolucao });
  });
});
