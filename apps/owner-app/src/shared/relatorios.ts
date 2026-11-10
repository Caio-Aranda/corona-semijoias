/**
 * Relatórios de cadastro na ponte (tarefa 1.12). Uma só operação; o nome do relatório vai nos dados.
 * É a lista que o processo principal usa para conferir antes de falar com a API.
 */
export const OPERACOES_DE_RELATORIOS = ['relatorios.cadastros'] as const;
export type OperacaoDeRelatorios = (typeof OPERACOES_DE_RELATORIOS)[number];

export const NOMES_DOS_RELATORIOS = [
  'vendedores',
  'clientes',
  'fornecedores',
  'produtos',
  'sem-foto',
  'sem-codigo-de-barras',
  'codigos-em-conflito',
  'aniversariantes',
  'alteracoes-de-produtos',
  'resultado-das-importacoes',
] as const;
export type NomeDoRelatorio = (typeof NOMES_DOS_RELATORIOS)[number];

export const SITUACOES_DO_RELATORIO = ['ATIVOS', 'INATIVOS', 'TODOS'] as const;
export type SituacaoDoRelatorio = (typeof SITUACOES_DO_RELATORIO)[number];

/** Filtros que a tela pode mandar (cada relatório usa só os seus; o servidor ignora os outros). */
export interface ConsultaDeRelatorio {
  busca?: string;
  categoriaId?: string;
  fornecedorId?: string;
  pracaId?: string;
  vendedorId?: string;
  restricaoId?: string;
  situacao?: SituacaoDoRelatorio;
  /** AAAA-MM-DD, inclusive. */
  de?: string;
  ate?: string;
  /** Faixa do limite de consignação, em reais (vendedores). */
  limiteDe?: string;
  limiteAte?: string;
  /** SIM ou NAO (vendedores). */
  comRestricao?: string;
  /** ZERADO, ABAIXO_DO_MINIMO ou COM_ESTOQUE (produtos). */
  estoque?: string;
  /** Faixa do preço de venda, em reais (produtos). */
  precoDe?: string;
  precoAte?: string;
  /** COM ou SEM (produtos). */
  foto?: string;
  /** SIM ou NAO (fornecedores: tem produtos ativos). */
  comProdutos?: string;
  /** SIM ou NAO (clientes: tem vendedor responsável). */
  comVendedor?: string;
  /** PLANILHA ou FOTOS (resultado das importações). */
  tipo?: string;
  /** CRIADO, ATUALIZADO, JA_EXISTIA ou RECUSADO (resultado das importações). */
  resultado?: string;
  /** TODOS, VENDEDORES ou CLIENTES (aniversariantes). */
  quem?: string;
  /** 1 a 12 (aniversariantes). */
  mes?: number;
  /** A chave de um total do rodapé: mostra só as linhas que formam esse total. */
  recorte?: string;
  pagina: number;
  tamanho: number;
}
