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

/**
 * Achados levados à orquestradora (comentário no PR da tarefa 5.12) que não são vazamento de dado nem de código: enquanto
 * ela decide, ficam como conhecidos, cada um preso a um código de erro exato; a varredura continua valendo para todo o
 * resto. Hoje, um só: a mensagem do 403 `APARELHO_NAO_LIBERADO` diz ao
 * vendedor "Peça à dona para liberar este computador no Corona Gestão." (o nome do app da dona, não dado nem código).
 */
export const ACHADOS_CONHECIDOS: ReadonlyArray<{ marcador: string; codigo: string }> = [
  { marcador: 'Corona Gestão', codigo: 'APARELHO_NAO_LIBERADO' },
];

function codigoDoErro(corpo: string): string | null {
  try {
    const json = JSON.parse(corpo) as { codigo?: unknown };
    return typeof json.codigo === 'string' ? json.codigo : null;
  } catch {
    return null;
  }
}

/** Os marcadores achados numa resposta de texto (fotos e outros binários não são texto e ficam de fora). */
export function marcadoresNaResposta(r: RespostaVista): string[] {
  if (r.tipo && !/json|text/i.test(r.tipo)) return [];
  const texto = r.corpo.toString('utf8');
  const corpo = texto.toLowerCase();
  const codigo = r.status >= 400 ? codigoDoErro(texto) : null;
  return MARCADORES.filter(
    (m) =>
      corpo.includes(m.toLowerCase()) &&
      !ACHADOS_CONHECIDOS.some((a) => a.marcador === m && a.codigo === codigo),
  ).map((m) => `"${m}" na resposta de ${r.metodo} ${r.caminho.split('?')[0] ?? ''} (${r.status})`);
}
