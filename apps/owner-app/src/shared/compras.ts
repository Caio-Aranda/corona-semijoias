/**
 * Compras na ponte: receber, ver, listar e desfazer. A lista é a que o processo principal usa para
 * conferir os dados antes de falar com a API; a tela usa os mesmos nomes.
 */
export const OPERACOES_DE_COMPRAS = [
  'compras.listar',
  'compras.ver',
  'compras.receber',
  'compras.desfazer',
] as const;
export type OperacaoDeCompras = (typeof OPERACOES_DE_COMPRAS)[number];

/** Limites que o servidor também confere (ele tem a palavra final). */
export const MAXIMO_DE_ITENS_DA_COMPRA = 500;
export const MAXIMO_DA_QUANTIDADE = 1_000_000;
export const MAXIMO_DO_NUMERO_DA_NOTA = 60;
export const MAXIMO_DAS_OBSERVACOES = 1000;
export const MAXIMO_DO_MOTIVO = 500;

export const SITUACOES_DA_COMPRA = ['REGISTRADA', 'ESTORNADA'] as const;
