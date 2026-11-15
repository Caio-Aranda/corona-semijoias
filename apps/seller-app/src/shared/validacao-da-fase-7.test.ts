import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';

/**
 * A ponte das operações da tarefa 7.11: parcelamento opcional na venda e na troca, recebimento do cliente (só Dinheiro e
 * Transferência, gravada como PIX), estorno do recebimento, e as consultas do acerto, da comissão e dos recebíveis. A tela
 * nunca manda parcela, comissão nem total calculado: só a quantidade de parcelas e o primeiro vencimento.
 */
const ID = '3f2b8c1e-5d4a-4b7e-9c1d-2a6f8e0b4c11';
const OUTRO = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const MAIS = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c';

const venda = {
  id: ID,
  remessaId: OUTRO,
  clienteId: MAIS,
  itens: [{ produtoId: OUTRO, quantidade: 1 }],
  pagamentos: [],
};
const troca = {
  id: ID,
  creditoId: OUTRO,
  pagamentoDoCreditoId: MAIS,
  devolucao: {
    id: MAIS,
    vendaId: OUTRO,
    motivo: 'TAMANHO',
    itens: [{ produtoId: OUTRO, quantidade: 1, estado: 'BOA' }],
  },
  venda: {
    id: OUTRO,
    remessaId: ID,
    itens: [{ produtoId: ID, quantidade: 1 }],
    pagamentos: [],
  },
};

describe('as operações da tarefa 7.11 na ponte', () => {
  it('aceita cada uma no formato certo e devolve uma cópia limpa', () => {
    const certos = [
      {
        operacao: 'vendas.vender',
        venda: { ...venda, parcelamento: { quantidade: 3, primeiroVencimento: '2026-12-15' } },
      },
      {
        operacao: 'trocas.registrar',
        troca: {
          ...troca,
          venda: {
            ...troca.venda,
            parcelamento: { quantidade: 2, primeiroVencimento: '2026-12-20' },
          },
        },
      },
      {
        operacao: 'recebimentos.receber',
        recebimento: { id: ID, vendaId: OUTRO, valor: '50.00', forma: 'DINHEIRO' },
      },
      {
        operacao: 'recebimentos.receber',
        recebimento: { id: ID, vendaId: OUTRO, valor: '50', forma: 'PIX' },
      },
      { operacao: 'recebimentos.estornar', id: ID, estornoId: OUTRO, motivo: 'Valor errado' },
      { operacao: 'recebiveis.ver' },
      { operacao: 'acertos.listar' },
      { operacao: 'acertos.ver', id: ID },
      { operacao: 'comissao.previa' },
    ];
    for (const pedido of certos)
      expect(validarChamada(pedido), JSON.stringify(pedido)).toEqual(pedido);
  });

  it.each([
    ['sem a quantidade', { primeiroVencimento: '2026-12-15' }],
    ['sem o vencimento', { quantidade: 3 }],
    ['com zero parcelas', { quantidade: 0, primeiroVencimento: '2026-12-15' }],
    ['com mais de 24 parcelas', { quantidade: 25, primeiroVencimento: '2026-12-15' }],
    ['com parcelas fracionadas', { quantidade: 2.5, primeiroVencimento: '2026-12-15' }],
    ['com data fora do formato', { quantidade: 3, primeiroVencimento: '15/12/2026' }],
    [
      'com valor de parcela vindo da tela',
      { quantidade: 3, primeiroVencimento: '2026-12-15', valor: '10.00' },
    ],
    ['com entrada', { quantidade: 3, primeiroVencimento: '2026-12-15', entrada: '10.00' }],
  ])('recusa o parcelamento %s', (_nome, parcelamento) => {
    expect(
      validarChamada({ operacao: 'vendas.vender', venda: { ...venda, parcelamento } }),
    ).toBeNull();
    expect(
      validarChamada({
        operacao: 'trocas.registrar',
        troca: { ...troca, venda: { ...troca.venda, parcelamento } },
      }),
    ).toBeNull();
  });

  it.each([
    ['CARTAO', 'cartão'],
    ['CHEQUE', 'cheque'],
    ['BOLETO', 'boleto'],
    ['CREDITO', 'crédito'],
  ])('recusa receber do cliente em %s (%s): só Dinheiro ou Transferência', (forma) => {
    expect(
      validarChamada({
        operacao: 'recebimentos.receber',
        recebimento: { id: ID, vendaId: OUTRO, valor: '50.00', forma },
      }),
    ).toBeNull();
  });

  it.each([
    ['valor zerado ou negativo', { valor: '-5.00' }],
    ['valor com três casas', { valor: '5.001' }],
    ['id que não é UUID', { id: '123' }],
    ['venda que não é UUID', { vendaId: 'abc' }],
    ['campo a mais', { parcela: 2 }],
  ])('recusa recebimento com %s', (_nome, troco) => {
    expect(
      validarChamada({
        operacao: 'recebimentos.receber',
        recebimento: { id: ID, vendaId: OUTRO, valor: '50.00', forma: 'DINHEIRO', ...troco },
      }),
    ).toBeNull();
  });

  it('recusa estorno sem motivo, com motivo enorme ou com ids ruins', () => {
    expect(
      validarChamada({ operacao: 'recebimentos.estornar', id: ID, estornoId: OUTRO, motivo: '  ' }),
    ).toBeNull();
    expect(
      validarChamada({
        operacao: 'recebimentos.estornar',
        id: ID,
        estornoId: OUTRO,
        motivo: 'x'.repeat(401),
      }),
    ).toBeNull();
    expect(
      validarChamada({
        operacao: 'recebimentos.estornar',
        id: 'a',
        estornoId: OUTRO,
        motivo: 'ok',
      }),
    ).toBeNull();
    expect(validarChamada({ operacao: 'acertos.ver', id: 'a' })).toBeNull();
  });
});
