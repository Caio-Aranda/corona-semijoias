import { describe, expect, it } from 'vitest';
import { TEXTOS_DE_FINANCEIRO } from './textos-financeiro';

/** Todos os textos do objeto, chamando as funções com valores de exemplo. */
function todosOsTextos(v: unknown, saida: string[] = []): string[] {
  if (typeof v === 'string') saida.push(v);
  else if (typeof v === 'function') {
    todosOsTextos((v as (...a: unknown[]) => unknown)('Ana', '2', '01/01'), saida);
  } else if (typeof v === 'object' && v !== null) {
    for (const x of Object.values(v)) todosOsTextos(x, saida);
  }
  return saida;
}

describe('textos do financeiro', () => {
  const textos = todosOsTextos(TEXTOS_DE_FINANCEIRO);

  it('não têm termo técnico, código da API, cheque nem cartão', () => {
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
      /cheque/i,
      /cart[aã]o/i,
      /ENTRADA|SAIDA|ABERTA|PARCIAL|VENCIDA|CANCELADA|DINHEIRO|BOLETO/,
    ];
    for (const t of textos) {
      for (const p of proibidas) expect(t, `"${t}" não pode casar ${p}`).not.toMatch(p);
    }
  });

  it('as formas são só Dinheiro, Transferência (PIX) e Boleto', () => {
    expect(Object.values(TEXTOS_DE_FINANCEIRO.formas)).toEqual([
      'Dinheiro',
      'Transferência (PIX)',
      'Boleto',
    ]);
  });

  it('PIX só aparece como "Transferência (PIX)"', () => {
    for (const t of textos.filter((x) => /PIX/i.test(x))) {
      expect(t).toMatch(/Transferência \(PIX\)/);
      expect(t.replace(/Transferência \(PIX\)/g, '')).not.toMatch(/PIX/i);
    }
  });
});
