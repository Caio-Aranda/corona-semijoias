import { dividirArredondando, formatarGuarani, formatarReais, taxaDeTexto } from './moedas';

/** Quanto vale 1 US$ em reais, só para mostrar: "5.00000000" → "R$ 5,00". Taxa inválida → vazio. */
export function reaisPorDolar(taxa: string): string {
  const t = taxaDeTexto(taxa);
  return t === null ? '' : formatarReais(dividirArredondando(t, 10n ** 6n));
}

/** Quantos Gs. valem R$ 1, só para mostrar: "0.00100000" → "Gs. 1.000". Taxa inválida → vazio. */
export function guaranisPorReal(taxa: string): string {
  const t = taxaDeTexto(taxa);
  return t === null ? '' : formatarGuarani(dividirArredondando(10n ** 8n, t));
}
