import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { RespostaVista } from './proxy-de-rede-ruim';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/**
 * O que nunca pode chegar ao computador do vendedor: os marcadores da barreira (ADR-0003, item 4; a lista mora em
 * `scripts/marcadores-proibidos.json`) mais o custo e o fornecedor em qualquer grafia (ADR-0013, item 4).
 */
export const MARCADORES: readonly string[] = (() => {
  const arquivo = JSON.parse(
    readFileSync(join(raiz, 'scripts', 'marcadores-proibidos.json'), 'utf8'),
  ) as { marcadores: string[] };
  return [...arquivo.marcadores, 'custo', 'fornecedor'];
})();

/** Os marcadores achados numa resposta de texto (fotos e outros binários não são texto e ficam de fora). */
export function marcadoresNaResposta(r: RespostaVista): string[] {
  if (r.tipo && !/json|text/i.test(r.tipo)) return [];
  const corpo = r.corpo.toString('utf8').toLowerCase();
  return MARCADORES.filter((m) => corpo.includes(m.toLowerCase())).map(
    (m) => `"${m}" na resposta de ${r.metodo} ${r.caminho.split('?')[0] ?? ''} (${r.status})`,
  );
}
