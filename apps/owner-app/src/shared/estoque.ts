/**
 * Estoque e contagem na ponte. É a lista que o processo principal usa para conferir os dados antes
 * de falar com a API; a tela usa os mesmos nomes.
 */
export const OPERACOES_DE_ESTOQUE = [
  'estoque.ficha',
  'estoque.lancar',
  'estoque.ajustar',
  'estoque.desfazer',
  'estoque.lancamentos',
  'estoque.acabando',
  'contagens.listar',
  'contagens.comecar',
  'contagens.ver',
  'contagens.itens',
  'contagens.ler',
  'contagens.corrigir',
  'contagens.fechar',
  'contagens.cancelar',
] as const;
export type OperacaoDeEstoque = (typeof OPERACOES_DE_ESTOQUE)[number];

/** Tipos que a dona lança à mão (o ajuste tem tela própria). */
export const TIPOS_DE_LANCAMENTO = ['ENTRADA', 'SAIDA', 'AVARIA'] as const;
export type TipoDeLancamento = (typeof TIPOS_DE_LANCAMENTO)[number];

/** Tipos que aparecem no filtro das entradas e saídas. */
export const TIPOS_DO_FILTRO = ['ENTRADA', 'SAIDA', 'AVARIA', 'AJUSTE'] as const;

export const ESCOPOS_DA_CONTAGEM = ['TODOS', 'CATEGORIA', 'FORNECEDOR'] as const;
export type EscopoDaContagem = (typeof ESCOPOS_DA_CONTAGEM)[number];
export const SITUACOES_DA_CONTAGEM = ['EM_ANDAMENTO', 'FECHADO', 'CANCELADO'] as const;
export const FILTROS_DOS_ITENS = ['TODOS', 'DIVERGENTES', 'NAO_LIDOS', 'LIDOS'] as const;

/** Limites que o servidor também confere (ele tem a palavra final). */
export const MAXIMO_DA_QUANTIDADE_DO_ESTOQUE = 1_000_000;
export const MAXIMO_DO_MOTIVO_DO_ESTOQUE = 500;
export const MAXIMO_DAS_OBSERVACOES_DA_CONTAGEM = 1000;
