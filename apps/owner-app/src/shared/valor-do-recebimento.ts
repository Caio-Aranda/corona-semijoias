/**
 * O valor do recebimento do representante como vai para a API (23/11/2026; §7.5): em Real, `valor`; em Dólar ou Guarani, só
 * `moeda` e `valorNaMoeda` (o servidor converte pela cotação e grava em Real; o app nunca manda o valor convertido).
 */
export const MOEDAS_DO_RECEBIMENTO = ['REAL', 'DOLAR', 'GUARANI'] as const;
export type MoedaDoRecebimentoNaApi = (typeof MOEDAS_DO_RECEBIMENTO)[number];

export type ValorDoRecebimento =
  { valor: string; moeda?: 'REAL' } | { moeda: 'DOLAR' | 'GUARANI'; valorNaMoeda: string };

/** Real e Dólar: texto com ponto e até 2 casas. Guarani: inteiro (sem centavos). Sempre maior que zero. */
const COM_CENTAVOS = /^\d{1,12}(\.\d{1,2})?$/;
const INTEIRO = /^\d{1,12}$/;
const positivo = (v: string) => /[1-9]/.test(v);

/**
 * Confere só a forma (o servidor tem a palavra final): a moeda da lista, o valor no formato dela e nunca os dois valores juntos.
 * Devolve só os campos conhecidos, ou `null`.
 */
export function conferirValorDoRecebimento(o: Record<string, unknown>): ValorDoRecebimento | null {
  const moeda = o.moeda === undefined ? 'REAL' : o.moeda;
  if (moeda === 'REAL') {
    if (o.valorNaMoeda !== undefined) return null;
    if (typeof o.valor !== 'string' || !COM_CENTAVOS.test(o.valor) || !positivo(o.valor))
      return null;
    return o.moeda === undefined ? { valor: o.valor } : { valor: o.valor, moeda: 'REAL' };
  }
  if (moeda !== 'DOLAR' && moeda !== 'GUARANI') return null;
  if (o.valor !== undefined) return null;
  const v = o.valorNaMoeda;
  const formato = moeda === 'GUARANI' ? INTEIRO : COM_CENTAVOS;
  if (typeof v !== 'string' || !formato.test(v) || !positivo(v)) return null;
  return { moeda, valorNaMoeda: v };
}
