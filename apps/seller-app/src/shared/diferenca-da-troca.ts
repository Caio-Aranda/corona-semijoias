/**
 * A diferença de uma troca (ADR-0014, item 8; issue 208): total da venda nova (B) menos o valor EM CRÉDITO da devolução de
 * A. O que A abate nas próprias parcelas, quando era a prazo, não chega a B: só o crédito paga B. Positiva: o cliente paga
 * a diferença; negativa: a sobra fica de crédito. É a mesma conta do `ServicoDeTrocas` do servidor, em centavos inteiros
 * (`bigint`); o valor que vale é o que o servidor grava.
 */
export function diferencaDaTroca(totalDeB: bigint, creditoDeA: bigint): bigint {
  return totalDeB - creditoDeA;
}
