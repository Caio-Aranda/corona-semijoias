import { describe, expect, it } from 'vitest';
import { guaranisPorReal, reaisPorDolar } from './cotacao-na-tela';

describe('cotação do dia na tela', () => {
  it('mostra o dólar em reais com 2 casas', () => {
    expect(reaisPorDolar('5.00000000')).toBe('R$ 5,00');
    expect(reaisPorDolar('5.50130000')).toBe('R$ 5,50');
  });

  it('mostra quantos guaranis valem R$ 1, inteiro e com milhar', () => {
    expect(guaranisPorReal('0.00100000')).toBe('Gs. 1.000');
    expect(guaranisPorReal('0.00072500')).toBe('Gs. 1.379');
  });

  it('taxa inválida não mostra nada', () => {
    expect(reaisPorDolar('x')).toBe('');
    expect(guaranisPorReal('0')).toBe('');
  });
});
