import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const css = readFileSync(join(process.cwd(), 'src', 'tema.css'), 'utf8');

function cor(nome: string): string {
  const m = new RegExp(`--${nome}:\\s*(#[0-9a-f]{6})`, 'i').exec(css);
  if (!m?.[1]) throw new Error(`Cor --${nome} não encontrada em tema.css`);
  return m[1];
}

function luminancia(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contraste(a: string, b: string): number {
  const [la, lb] = [luminancia(cor(a)), luminancia(cor(b))];
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

// Texto pequeno exige 4,5:1 (WCAG AA).
const paresDeTextoPequeno: [string, string][] = [
  ['cor-texto', 'cor-fundo'],
  ['cor-texto', 'cor-fundo-suave'],
  ['cor-texto', 'cor-champanhe'],
  ['cor-texto-suave', 'cor-fundo'],
  ['cor-texto-suave', 'cor-fundo-suave'],
  ['cor-texto', 'cor-ouro'],
  ['cor-sucesso-texto', 'cor-sucesso-fundo'],
  ['cor-erro-texto', 'cor-erro-fundo'],
  ['cor-aviso-texto', 'cor-aviso-fundo'],
  ['cor-botao-perigo-texto', 'cor-botao-perigo-fundo'],
];

describe('contraste dos tokens (AA)', () => {
  it.each(paresDeTextoPequeno)('%s sobre %s tem pelo menos 4,5:1', (texto, fundo) => {
    expect(contraste(texto, fundo)).toBeGreaterThanOrEqual(4.5);
  });

  it('o foco aparece sobre o fundo claro (pelo menos 3:1)', () => {
    expect(contraste('cor-foco', 'cor-fundo')).toBeGreaterThanOrEqual(3);
  });

  it('o dourado só serve para detalhes: não tem contraste de texto pequeno sobre bege', () => {
    expect(contraste('cor-ouro', 'cor-champanhe')).toBeLessThan(4.5);
  });
});
