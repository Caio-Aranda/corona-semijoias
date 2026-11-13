import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { semanaInicial } from './dona';

/**
 * O número da semana que a dona dos testes cria tem de caber no que a API aceita (issue #130): `semanaInicial` dependia
 * da hora, e durante 1/3 do tempo passava de 100000 e derrubava todo teste de duas pontas que monta maleta.
 */
const CONTROLLER = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'backend',
  'src',
  'main',
  'java',
  'br',
  'com',
  'coronasemijoias',
  'api',
  'cadastros',
  'SemanasDaDonaController.java',
);

/** Os limites do `numero` lidos do próprio código da API: se a API mudar, este teste acompanha. */
function limitesDaApi(): { minimo: number; maximo: number } {
  const fonte = readFileSync(CONTROLLER, 'utf8');
  const trecho = /@Min\(value = (\d+)[^)]*\)\s*@Max\(value = (\d+)[^)]*\)\s*Integer numero/.exec(
    fonte,
  );
  if (!trecho) throw new Error(`Não achei @Min e @Max do número da semana em ${CONTROLLER}`);
  return { minimo: Number(trecho[1]), maximo: Number(trecho[2]) };
}

describe('número da semana da dona dos testes', () => {
  it('a primeira semana de um arquivo cabe no limite da API em qualquer hora (todo segundo de um ciclo inteiro)', () => {
    const { minimo, maximo } = limitesDaApi();
    // A conta só depende do segundo do relógio e se repete; 200000 segundos seguidos passam por todos os restos.
    const base = Date.UTC(2026, 10, 13, 0, 0, 0);
    const fora: number[] = [];
    for (let s = 0; s < 200_000; s++) {
      const n = semanaInicial(base + s * 1000 + 999);
      if (n < minimo || n > maximo) fora.push(n);
    }
    expect(fora.slice(0, 5)).toEqual([]);
  });

  it('a hora da issue #130 (13/11/2026 06:07 UTC) dá uma semana que a API aceita', () => {
    const { minimo, maximo } = limitesDaApi();
    const n = semanaInicial(Date.UTC(2026, 10, 13, 6, 7, 0));
    expect(n).toBeGreaterThanOrEqual(minimo);
    expect(n).toBeLessThanOrEqual(maximo);
  });
});
