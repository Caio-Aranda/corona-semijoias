import { describe, expect, it } from 'vitest';
import { diferencaDaTroca } from './diferenca-da-troca';

describe('diferença da troca (issue 208)', () => {
  it.each([
    ['à vista', 12000n, 10000n, 2000n],
    ['à vista, B mais barata', 7000n, 10000n, -3000n],
    ['a prazo, sem nada pago (crédito zero)', 12000n, 0n, 12000n],
    ['a prazo, pago 40,00', 7000n, 4000n, 3000n],
    ['a prazo, pago 40,00, B mais barata', 3000n, 4000n, -1000n],
  ])('%s', (_caso, totalDeB, creditoDeA, esperado) => {
    expect(diferencaDaTroca(totalDeB, creditoDeA)).toBe(esperado);
  });
});
