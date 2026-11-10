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
  /** 1 a 12 (aniversariantes). */
  mes?: number;
  /** A chave de um total do rodapé: mostra só as linhas que formam esse total. */
  recorte?: string;
  pagina: number;
  tamanho: number;
}
