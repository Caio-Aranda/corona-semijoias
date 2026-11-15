import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';

/**
 * A ponte das operações da tarefa 8.10: pedir o acerto e contar as sobras (só com internet). A tela manda só ids, a peça,
 * a quantidade (1 para bipar, negativa para desfazer) e o estado; nunca total, comissão nem nada calculado.
 */
const ID = '3f2b8c1e-5d4a-4b7e-9c1d-2a6f8e0b4c11';
const OUTRO = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const MAIS = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c';

const leitura = { id: OUTRO, produtoId: MAIS, quantidade: 1, estadoPeca: 'BOA' };

describe('as operações da tarefa 8.10 na ponte', () => {
  it('aceita pedir, ver agora e ler, no formato exato', () => {
    const certos = [
      { operacao: 'acertos.pedir', id: ID },
      { operacao: 'acertos.agora', id: ID },
      { operacao: 'acertos.ler', id: ID, leitura },
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, quantidade: -1 } },
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, estadoPeca: 'DEFEITO' } },
    ];
    for (const pedido of certos)
      expect(validarChamada(pedido), JSON.stringify(pedido)).toEqual(pedido);
  });

  it.each([
    ['pedir sem id', { operacao: 'acertos.pedir' }],
    ['pedir com id que não é UUID', { operacao: 'acertos.pedir', id: '123' }],
    ['ver agora com id ruim', { operacao: 'acertos.agora', id: 'x' }],
    ['ler sem a leitura', { operacao: 'acertos.ler', id: ID }],
    ['ler com acerto ruim', { operacao: 'acertos.ler', id: 'x', leitura }],
    [
      'ler com id da leitura ruim',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, id: 'x' } },
    ],
    [
      'ler com peça ruim',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, produtoId: 'x' } },
    ],
    [
      'ler com quantidade zero',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, quantidade: 0 } },
    ],
    [
      'ler com quantidade fracionada',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, quantidade: 1.5 } },
    ],
    [
      'ler com quantidade enorme',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, quantidade: 100_001 } },
    ],
    [
      'ler com quantidade em texto',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, quantidade: '1' } },
    ],
    [
      'ler com estado inventado',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, estadoPeca: 'NOVA' } },
    ],
    [
      'ler com campo a mais (custo)',
      { operacao: 'acertos.ler', id: ID, leitura: { ...leitura, custo: '1.00' } },
    ],
    ['ler com campo a mais no pedido', { operacao: 'acertos.ler', id: ID, leitura, total: '1.00' }],
    ['pedir com campo a mais', { operacao: 'acertos.pedir', id: ID, comissao: '10' }],
  ])('recusa %s', (_nome, pedido) => {
    expect(validarChamada(pedido)).toBeNull();
  });
});
