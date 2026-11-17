import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { guardarIdioma, lerIdiomaGuardado } from './idioma-guardado';

let pasta = '';
beforeEach(() => {
  pasta = mkdtempSync(join(tmpdir(), 'corona-idioma-'));
});
afterEach(() => {
  rmSync(pasta, { recursive: true, force: true });
});

describe('o idioma guardado neste computador', () => {
  it('nunca escolheu: nada guardado', () => {
    expect(lerIdiomaGuardado(pasta)).toBeNull();
  });

  it('guarda e lê os dois idiomas', () => {
    guardarIdioma(pasta, 'pt');
    expect(lerIdiomaGuardado(pasta)).toBe('pt');
    guardarIdioma(pasta, 'es');
    expect(lerIdiomaGuardado(pasta)).toBe('es');
    expect(readFileSync(join(pasta, 'idioma.txt'), 'utf8')).toBe('es');
  });

  it('só aceita os dois idiomas; qualquer outra coisa é ignorada', () => {
    guardarIdioma(pasta, 'pt');
    guardarIdioma(pasta, 'fr');
    guardarIdioma(pasta, { idioma: 'es' });
    expect(lerIdiomaGuardado(pasta)).toBe('pt');
  });

  it('arquivo estragado volta ao padrão (nada guardado)', () => {
    writeFileSync(join(pasta, 'idioma.txt'), 'xyz', 'utf8');
    expect(lerIdiomaGuardado(pasta)).toBeNull();
  });

  it('pasta que não existe não derruba nada', () => {
    const naoExiste = join(pasta, 'a', 'b');
    expect(lerIdiomaGuardado(naoExiste)).toBeNull();
    guardarIdioma(naoExiste, 'pt');
    expect(lerIdiomaGuardado(naoExiste)).toBe('pt');
  });
});
