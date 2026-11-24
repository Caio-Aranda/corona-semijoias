import { describe, expect, it } from 'vitest';
import {
  dataEHoraDaCotacao,
  dolarDigitado,
  dolarParaCampo,
  guaraniDigitado,
  guaraniParaCampo,
  guaranisPorRealDaTaxa,
} from './cotacao-da-loja';

describe('cotação da loja: o que a dona digita e o que a tela mostra', () => {
  it('passa o que ela digita para o que o sistema recebe', () => {
    expect(dolarDigitado('5,80')).toBe('5.80');
    expect(dolarDigitado('abc')).toBeNull();
    expect(guaraniDigitado('1.350')).toBe('1350');
    expect(guaraniDigitado('1.350,5')).toBe('1350.5');
    expect(guaraniDigitado('')).toBeNull();
  });

  it('mostra a cotação do jeito que se digita', () => {
    expect(dolarParaCampo('5.80000000')).toBe('5,80');
    expect(dolarParaCampo('5.50130000')).toBe('5,5013');
    expect(guaraniParaCampo('1350.00000000')).toBe('1.350');
    expect(guaraniParaCampo('1117.32000000')).toBe('1.117,32');
    expect(guaranisPorRealDaTaxa('0.00089500')).toBe('1117');
  });

  it('separa o dia e a hora da data da cotação (a da dona vem com a hora)', () => {
    expect(dataEHoraDaCotacao('2026-11-23T10:15')).toEqual({ dia: '23/11/2026', hora: '10:15' });
    expect(dataEHoraDaCotacao('2026-11-06')).toEqual({ dia: '06/11/2026', hora: null });
  });
});
