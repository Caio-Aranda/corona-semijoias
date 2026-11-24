/**
 * Contas de dinheiro do consignado em centavos inteiros (`bigint`), nunca ponto flutuante (CLAUDE.md). As mesmas regras
 * do servidor (`Dinheiro.java`): HALF_UP com duas casas.
 */
import { centavosDeTexto, dividirArredondando } from '../moedas';

/** 1230n → "12.30"; -5n → "-0.05". */
export function textoDeCentavos(centavos: bigint): string {
  const negativo = centavos < 0n;
  const abs = negativo ? -centavos : centavos;
  const casas = (abs % 100n).toString().padStart(2, '0');
  return `${negativo ? '-' : ''}${(abs / 100n).toString()}.${casas}`;
}

/** "12.3" → 1230n. Recusa texto que não é dinheiro (defeito de quem chamou). */
export function exigirCentavos(texto: string): bigint {
  const c = centavosDeTexto(texto.trim());
  if (c === null) throw new RangeError('Valor em dinheiro inválido.');
  return c;
}

/** "7.5" → 750n (centésimos de ponto percentual). Até duas casas; qualquer outra coisa → `null`. */
export function centesimosDePorcentagem(texto: string): bigint | null {
  const m = /^(-)?(\d{1,3})(?:\.(\d{1,2}))?$/.exec(texto.trim());
  if (!m) return null;
  const v = BigInt(m[2] ?? '0') * 100n + BigInt((m[3] ?? '').padEnd(2, '0') || '0');
  return m[1] ? -v : v;
}

/** valor × % ÷ 100, HALF_UP em centavos. `percentual` em centésimos de ponto percentual (30% = 3000n). */
export function aplicarPorcentagem(centavos: bigint, percentualEmCentesimos: bigint): bigint {
  return dividirArredondando(centavos * percentualEmCentesimos, 10_000n);
}

/** parte ÷ todo × 100, em centésimos de ponto percentual (43,02% = 4302n), HALF_UP; todo zero → 0. */
export function percentualDe(parte: bigint, todo: bigint): bigint {
  if (todo <= 0n) return 0n;
  return dividirArredondando(parte * 10_000n, todo);
}

/** 4302n → "43.02". */
export function textoDePorcentagem(centesimos: bigint): string {
  return textoDeCentavos(centesimos);
}
