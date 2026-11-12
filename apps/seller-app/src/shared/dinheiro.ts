/**
 * Dinheiro do Corona Vendas em centavos inteiros (`bigint`), nunca ponto flutuante (CLAUDE.md; ADR-0013, item 7).
 * No banco local e na outbox o dinheiro é texto com duas casas ("12.30"), igual ao que o servidor manda.
 */

const DINHEIRO = /^(-)?(\d{1,12})(?:\.(\d{1,2}))?$/;

/** "12.3" → 1230n; "-0.05" → -5n. Qualquer outra coisa → `null`. */
export function centavosDe(texto: string): bigint | null {
  const m = DINHEIRO.exec(texto.trim());
  if (!m) return null;
  const inteiros = BigInt(m[2] ?? '0');
  const casas = BigInt((m[3] ?? '').padEnd(2, '0'));
  const valor = inteiros * 100n + casas;
  return m[1] ? -valor : valor;
}

/** Como {@link centavosDe}, mas recusa o texto inválido (defeito de quem chamou, nunca do vendedor). */
export function exigirCentavos(texto: string): bigint {
  const c = centavosDe(texto);
  if (c === null) throw new Error('Valor em dinheiro inválido.');
  return c;
}

/** 1230n → "12.30"; -5n → "-0.05". */
export function textoDeCentavos(centavos: bigint): string {
  const negativo = centavos < 0n;
  const abs = negativo ? -centavos : centavos;
  const inteiros = abs / 100n;
  const casas = (abs % 100n).toString().padStart(2, '0');
  return `${negativo ? '-' : ''}${inteiros.toString()}.${casas}`;
}
