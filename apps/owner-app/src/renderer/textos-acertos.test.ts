import { describe, expect, it } from 'vitest';
import { TEXTOS_DE_ACERTOS } from './textos-acertos';

/** Todos os textos do objeto, chamando as funções com valores de exemplo. */
function todosOsTextos(v: unknown, saida: string[] = []): string[] {
  if (typeof v === 'string') saida.push(v);
  else if (typeof v === 'function') {
    const r = (v as (...a: unknown[]) => unknown)(
      'Ana',
      2,
      'boa',
      'Maria',
      'no computador',
      '01/01',
    );
    todosOsTextos(r, saida);
  } else if (typeof v === 'object' && v !== null) {
    for (const x of Object.values(v)) todosOsTextos(x, saida);
  }
  return saida;
}

describe('textos do acerto', () => {
  const textos = todosOsTextos(TEXTOS_DE_ACERTOS);

  it('não têm termo técnico nem palavra do bloco proibido do glossário', () => {
    const proibidas = [
      /\bstatus\b/i,
      /sincroniz/i,
      /\bsync\b/i,
      /payload/i,
      /\buuid\b/i,
      /\bid\b/i,
      /\berro\b/i,
      /\bremessa\b/i,
      /\bbip/i,
      /estorn/i,
      /cheque/i,
      /cart[aã]o/i,
      /\bentrada\b/i,
      /EM_ACERTO|ACERTADO|DESISTIDO|ABERTA|COBRAR_/,
    ];
    for (const t of textos) {
      for (const p of proibidas) expect(t, `"${t}" não pode casar ${p}`).not.toMatch(p);
    }
  });

  it('PIX só aparece como "Transferência (PIX)"', () => {
    for (const t of textos.filter((x) => /PIX/i.test(x))) {
      expect(t).toMatch(/Transferência \(PIX\)/);
      expect(t.replace(/Transferência \(PIX\)/g, '')).not.toMatch(/PIX/i);
    }
  });
});
