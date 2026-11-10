import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Caminho do arquivo único com a lista de marcadores proibidos (ADR-0003, item 4). */
export const ARQUIVO_DE_MARCADORES = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'marcadores-proibidos.json',
);

/**
 * Lê a lista de marcadores. Aceita outro arquivo só para os testes.
 * @returns {{ marcadores: string[], importacoesProibidas: string[] }}
 */
export function lerMarcadores(arquivo = ARQUIVO_DE_MARCADORES) {
  const bruto = JSON.parse(readFileSync(arquivo, 'utf8'));
  const marcadores = bruto.marcadores;
  const importacoes = bruto.importacoesProibidas;
  const texto = (lista) =>
    Array.isArray(lista) && lista.length > 0 && lista.every((m) => typeof m === 'string' && m);
  if (!texto(marcadores) || !texto(importacoes)) {
    throw new Error(
      `${arquivo}: "marcadores" e "importacoesProibidas" precisam ser listas de texto.`,
    );
  }
  const fora = importacoes.filter((m) => !marcadores.includes(m));
  if (fora.length > 0) {
    throw new Error(
      `${arquivo}: toda importação proibida também precisa estar em "marcadores": ${fora.join(', ')}.`,
    );
  }
  return { marcadores, importacoesProibidas: importacoes };
}
