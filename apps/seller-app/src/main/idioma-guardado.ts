import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { IdiomaDaTela } from '../shared/ponte';

/** O arquivo da escolha de idioma, na pasta de dados do programa (neste computador, sem segredo nenhum). */
const ARQUIVO = 'idioma.txt';

const ehIdioma = (v: unknown): v is IdiomaDaTela => v === 'es' || v === 'pt';

/** O idioma que a pessoa escolheu antes; `null` se nunca escolheu ou se o arquivo não presta. */
export function lerIdiomaGuardado(pasta: string): IdiomaDaTela | null {
  try {
    const texto = readFileSync(join(pasta, ARQUIVO), 'utf8').trim();
    return ehIdioma(texto) ? texto : null;
  } catch {
    return null;
  }
}

/** Guarda o idioma. Valor que não seja um dos dois idiomas é ignorado; falha de disco não derruba a tela. */
export function guardarIdioma(pasta: string, idioma: unknown): void {
  if (!ehIdioma(idioma)) return;
  try {
    mkdirSync(pasta, { recursive: true });
    writeFileSync(join(pasta, ARQUIVO), idioma, 'utf8');
  } catch {
    // Sem espaço ou sem permissão: a escolha vale até fechar o programa.
  }
}
