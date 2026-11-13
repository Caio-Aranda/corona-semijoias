/**
 * Prévia do valor de uma devolução de `quantidade` unidades de uma linha da venda (tarefa 6.8; ADR-0014, item 3): a
 * mesma regra do `ValorDaDevolucao` do servidor, em centavos inteiros (`bigint`), nunca ponto flutuante. Só para a tela
 * mostrar: o valor que vale é o que o servidor calcula (o computador nunca manda este valor).
 *
 * A unidade k da linha vale a parcela k do total dividido pela quantidade (HALF_UP, a sobra na última). A devolução vale
 * o acumulado depois dela menos o que já foi devolvido, entre zero e o que falta; a que completa a linha é exatamente o
 * que falta. Quando a divisão deixaria a última parcela com o sinal invertido (0,18 em 12), vale a parte proporcional.
 */
export function valorDaDevolucao(
  totalDaLinha: bigint,
  vendidas: number,
  jaDevolvidas: number,
  valorJaDevolvido: bigint,
  quantidade: number,
): bigint {
  if (
    !Number.isInteger(vendidas) ||
    !Number.isInteger(quantidade) ||
    !Number.isInteger(jaDevolvidas) ||
    vendidas < 1 ||
    quantidade < 1 ||
    jaDevolvidas < 0 ||
    jaDevolvidas + quantidade > vendidas
  ) {
    throw new Error(
      `Devolução de ${quantidade} unidade(s) com ${jaDevolvidas} já devolvida(s) numa linha de ${vendidas}.`,
    );
  }
  const falta = maior(totalDaLinha - valorJaDevolvido, 0n);
  const depois = jaDevolvidas + quantidade;
  if (depois === vendidas) return falta;
  const valor = acumulado(totalDaLinha, vendidas, depois) - valorJaDevolvido;
  return menor(maior(valor, 0n), falta);
}

/** Soma das `k` primeiras unidades da linha (k < vendidas). */
function acumulado(total: bigint, vendidas: number, k: number): bigint {
  const n = BigInt(vendidas);
  const parcela = dividirArredondando(total, n);
  const ultima = total - parcela * (n - 1n);
  // O `dividirEmParcelas` do servidor recusa quando a última parcela inverte o sinal: vale a parte proporcional.
  if (ultima !== 0n && sinal(ultima) !== sinal(total)) {
    return dividirArredondando(total * BigInt(k), n);
  }
  return parcela * BigInt(k);
}

/** `n / d` (d > 0), arredondado para longe do zero no meio exato (HALF_UP, como o servidor). */
export function dividirArredondando(n: bigint, d: bigint): bigint {
  return n >= 0n ? (2n * n + d) / (2n * d) : -((-2n * n + d) / (2n * d));
}

const sinal = (v: bigint): number => (v > 0n ? 1 : v < 0n ? -1 : 0);
const maior = (a: bigint, b: bigint): bigint => (a > b ? a : b);
const menor = (a: bigint, b: bigint): bigint => (a < b ? a : b);
