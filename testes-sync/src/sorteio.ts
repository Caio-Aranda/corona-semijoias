/**
 * Sorteio com semente (mulberry32): a mesma semente dá sempre a mesma sequência, para um teste que falhou poder ser
 * repetido. Cada teste imprime a semente que usou (ADR-0013, item 20).
 */
export type Sorteio = () => number;

export function criarSorteio(semente: number): Sorteio {
  let estado = semente >>> 0;
  return () => {
    estado = (estado + 0x6d2b79f5) >>> 0;
    let t = estado;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
