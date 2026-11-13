import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { NUMERO_DA_SEMANA, numeroDaSemana, semanaInicial } from './dona';

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

  it('os limites usados pela dona dos testes são os da API', () => {
    expect(NUMERO_DA_SEMANA).toEqual(limitesDaApi());
  });

  it('a primeira semana deixa folga de 10000 semanas até o limite, para o contador do arquivo', () => {
    const { maximo } = limitesDaApi();
    let maior = 0;
    for (let s = 0; s < 200_000; s++) maior = Math.max(maior, semanaInicial(s * 1000));
    expect(maior).toBeLessThanOrEqual(maximo - 10_000);
  });

  it('as semanas seguintes também cabem no limite, por maior que fique o contador', () => {
    const { minimo, maximo } = limitesDaApi();
    const fora: string[] = [];
    for (const inicial of [minimo, 2, 45_000, 89_999, 90_000]) {
      for (let contador = 0; contador < 250_000; contador += 7) {
        const n = numeroDaSemana(inicial, contador);
        if (!Number.isInteger(n) || n < minimo || n > maximo)
          fora.push(`${inicial}+${contador}=${n}`);
      }
      // Sem pular números: logo depois do início, uma semana por vez.
      expect(numeroDaSemana(inicial, 1)).toBe(inicial + 1);
    }
    expect(fora.slice(0, 5)).toEqual([]);
  });
});
