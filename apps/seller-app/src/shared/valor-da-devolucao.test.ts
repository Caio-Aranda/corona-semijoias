import { describe, expect, it } from 'vitest';
import { exigirCentavos as c, textoDeCentavos } from './dinheiro';
import { valorDaDevolucao } from './valor-da-devolucao';

/**
 * Prévia do valor de uma devolução no computador (tarefa 6.8): a mesma regra do `ValorDaDevolucao` do servidor
 * (ADR-0014, item 3), em centavos inteiros. Os casos são os do `ValorDaDevolucaoTest`. Escrito antes do código.
 */
const valor = (total: string, vendidas: number, ja: number, valorJa: string, q: number): string =>
  textoDeCentavos(valorDaDevolucao(c(total), vendidas, ja, c(valorJa), q));

describe('valor da devolução (prévia, a regra do servidor)', () => {
  it('três devoluções de uma unidade fecham no total, com a sobra na última', () => {
    expect(valor('10.00', 3, 0, '0.00', 1)).toBe('3.33');
    expect(valor('10.00', 3, 1, '3.33', 1)).toBe('3.33');
    expect(valor('10.00', 3, 2, '6.66', 1)).toBe('3.34');
    expect(valor('10.00', 3, 0, '0.00', 2)).toBe('6.66');
    expect(valor('10.00', 3, 0, '0.00', 3)).toBe('10.00');
  });

  it('com cancelamento no meio a soma ainda fecha no total da linha', () => {
    const primeira = valorDaDevolucao(c('10.01'), 3, 0, 0n, 1);
    const segunda = valorDaDevolucao(c('10.01'), 3, 1, primeira, 1);
    expect(textoDeCentavos(primeira)).toBe('3.34');
    expect(textoDeCentavos(segunda)).toBe('3.34');
    // A primeira é cancelada: fica só a segunda.
    const terceira = valorDaDevolucao(c('10.01'), 3, 1, segunda, 1);
    const quarta = valorDaDevolucao(c('10.01'), 3, 2, segunda + terceira, 1);
    expect(textoDeCentavos(segunda + terceira + quarta)).toBe('10.01');
    expect(textoDeCentavos(quarta)).toBe('3.33');
  });

  it('linha com centavos de menos para as unidades (0,18 em 12) usa a parte proporcional, nunca negativa', () => {
    expect(valor('0.18', 12, 0, '0.00', 5)).toBe('0.08');
    expect(valor('0.18', 12, 5, '0.08', 7)).toBe('0.10');
    let soma = 0n;
    for (let k = 0; k < 12; k++) {
      const uma = valorDaDevolucao(c('0.18'), 12, k, soma, 1);
      expect(uma >= 0n).toBe(true);
      soma += uma;
    }
    expect(textoDeCentavos(soma)).toBe('0.18');
  });

  it('linha de valor zero devolve zero', () => {
    expect(valor('0.00', 2, 0, '0.00', 1)).toBe('0.00');
    expect(valor('0.00', 2, 1, '0.00', 1)).toBe('0.00');
  });

  it('500 sequências aleatórias de devoluções parciais fecham no total, sem passar dele', () => {
    // Gerador simples e fixo (a mesma sequência sempre).
    let semente = 63;
    const proximo = (limite: number): number => {
      semente = (semente * 1103515245 + 12345) % 2147483648;
      return semente % limite;
    };
    for (let rodada = 0; rodada < 500; rodada++) {
      const n = 1 + proximo(15);
      const total = BigInt(proximo(100_000));
      let ja = 0;
      let valorJa = 0n;
      while (ja < n) {
        const q = 1 + proximo(n - ja);
        const parcial = valorDaDevolucao(total, n, ja, valorJa, q);
        expect(parcial >= 0n, `rodada ${rodada}`).toBe(true);
        ja += q;
        valorJa += parcial;
        expect(valorJa <= total, `rodada ${rodada}`).toBe(true);
      }
      expect(valorJa, `rodada ${rodada}`).toBe(total);
    }
  });

  it('recusa quantidade acima do vendido ou zero', () => {
    expect(() => valorDaDevolucao(c('10.00'), 3, 2, c('6.66'), 2)).toThrow();
    expect(() => valorDaDevolucao(c('10.00'), 3, 0, 0n, 0)).toThrow();
    expect(() => valorDaDevolucao(c('10.00'), 0, 0, 0n, 1)).toThrow();
  });

  it('só centavos inteiros: nenhum número quebrado entra na conta', () => {
    // A assinatura só aceita bigint para dinheiro; um number em dinheiro nem compila. Aqui, só a prova em execução.
    expect(typeof valorDaDevolucao(1000n, 3, 0, 0n, 1)).toBe('bigint');
  });
});
