/**
 * Valores em Real, Dólar e Guarani na tela (ADR-0006, itens 6 e 9; glossário, partes 8 e 11).
 * Tudo em inteiros `BigInt`: dinheiro nunca passa por `number` fracionário nem por `parseFloat`.
 * Tudo é gravado em Real; o que sai daqui é só para mostrar.
 */

/** Cotação do dia como o servidor manda: reais por unidade, texto com ponto e até 8 casas. */
export interface CotacaoParaTela {
  dolar: string;
  guarani: string;
}

export interface TresMoedas {
  real: string;
  dolar: string;
  guarani: string;
}

const CASAS_DA_TAXA = 8;

/** Divide `n` por `d` (> 0) arredondando para longe do zero no meio exato (HALF_UP do Java). */
export function dividirArredondando(n: bigint, d: bigint): bigint {
  if (d <= 0n) throw new RangeError('O divisor precisa ser maior que zero.');
  return n >= 0n ? (2n * n + d) / (2n * d) : -((-2n * n + d) / (2n * d));
}

/** "1234.56" → 123456n (centavos). Aceita sinal e até 2 casas; qualquer outra coisa → `null`. */
export function centavosDeTexto(texto: string): bigint | null {
  const m = /^(-)?(\d{1,14})(?:\.(\d{1,2}))?$/.exec(texto);
  if (!m) return null;
  const inteiro = BigInt(m[2] ?? '0') * 100n;
  const frac = BigInt((m[3] ?? '').padEnd(2, '0') || '0');
  const total = inteiro + frac;
  return m[1] ? -total : total;
}

/** "5.50130000" → 550130000n (unidades de 10⁻⁸). Até 8 casas; zero ou formato errado → `null`. */
export function taxaDeTexto(texto: string): bigint | null {
  const m = /^(\d{1,10})(?:\.(\d{1,8}))?$/.exec(texto);
  if (!m) return null;
  const valor =
    BigInt(m[1] ?? '0') * 10n ** BigInt(CASAS_DA_TAXA) +
    BigInt((m[2] ?? '').padEnd(CASAS_DA_TAXA, '0') || '0');
  return valor > 0n ? valor : null;
}

/** US$ em centavos = arredondar(centavos × 10⁸ ÷ taxa). */
export function dolarEmCentavos(realEmCentavos: bigint, taxaDolar: bigint): bigint {
  return dividirArredondando(realEmCentavos * 10n ** BigInt(CASAS_DA_TAXA), taxaDolar);
}

/** Gs. inteiros = arredondar(centavos × 10⁶ ÷ taxa). */
export function guaraniInteiro(realEmCentavos: bigint, taxaGuarani: bigint): bigint {
  return dividirArredondando(realEmCentavos * 10n ** BigInt(CASAS_DA_TAXA - 2), taxaGuarani);
}

function comMilhares(n: bigint): string {
  return n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

function comSinal(negativo: boolean, corpo: string): string {
  return negativo ? `-${corpo}` : corpo;
}

function formatarComCentavos(simbolo: string, centavos: bigint): string {
  const negativo = centavos < 0n;
  const abs = negativo ? -centavos : centavos;
  const reais = abs / 100n;
  const resto = (abs % 100n).toString().padStart(2, '0');
  return comSinal(negativo, `${simbolo} ${comMilhares(reais)},${resto}`);
}

/** "R$ 1.234,56"; negativo com o sinal na frente: "-R$ 50,00". */
export function formatarReais(centavos: bigint): string {
  const negativo = centavos < 0n;
  return comSinal(negativo, formatarComCentavos('R$', negativo ? -centavos : centavos));
}

/** "US$ 1.234,56". */
export function formatarDolar(centavos: bigint): string {
  const negativo = centavos < 0n;
  return comSinal(negativo, formatarComCentavos('US$', negativo ? -centavos : centavos));
}

/** "Gs. 1.379.397", sem centavos. */
export function formatarGuarani(valor: bigint): string {
  const negativo = valor < 0n;
  return comSinal(negativo, `Gs. ${comMilhares(negativo ? -valor : valor)}`);
}

/**
 * Valor em reais (texto da API, "1234.56") nas três moedas, pela cotação dada.
 * Sem cotação (ou com cotação inválida), devolve só o Real.
 */
export function tresMoedas(
  valorEmReais: string,
  cotacao: CotacaoParaTela | null,
): { real: string; dolar?: string; guarani?: string } | null {
  const centavos = centavosDeTexto(valorEmReais);
  if (centavos === null) return null;
  const resultado: { real: string; dolar?: string; guarani?: string } = {
    real: formatarReais(centavos),
  };
  if (!cotacao) return resultado;
  const taxaDolar = taxaDeTexto(cotacao.dolar);
  const taxaGuarani = taxaDeTexto(cotacao.guarani);
  if (taxaDolar === null || taxaGuarani === null) return resultado;
  resultado.dolar = formatarDolar(dolarEmCentavos(centavos, taxaDolar));
  resultado.guarani = formatarGuarani(guaraniInteiro(centavos, taxaGuarani));
  return resultado;
}

/**
 * Lê o que a pessoa digitou no campo de dinheiro ("1.234,56", "10", "0,5") e devolve o texto
 * decimal com ponto que a API entende ("1234.56"). Vazio, sinal, letras ou mais de 2 casas → `null`.
 */
export function lerReaisDigitados(digitado: string): string | null {
  const limpo = digitado.trim().replace(/^R\$\s*/, '');
  const m = /^(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}))?$/.exec(limpo);
  if (!m) return null;
  const inteiro = (m[1] ?? '').replace(/\./g, '');
  if (inteiro.length > 12) return null;
  return `${inteiro}.${(m[2] ?? '').padEnd(2, '0') || '00'}`;
}

/** "2026-11-06" → "06/11/2026". */
export function dataParaTela(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : iso;
}
