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
  // Estoque e compras (tarefa 2.8). O último é a lista de peças de uma compra, aberta a partir da compra.
  'estoque-posicao',
  'estoque-movimentos',
  'estoque-minimo',
  'estoque-parados',
  'estoque-contagens',
  'estoque-conferencia',
  'compras-por-compra',
  'compras-por-fornecedor',
  'compras-custos',
  'compras-pecas',
  // Maletas (tarefa 3.7): os cinco relatórios da 3.4.
  'maleta-remessas',
  'maleta-reposicoes',
  'maleta-saldo',
  'maleta-mercadoria',
  'maleta-em-aberto',
  // Devolução e troca (tarefa 6.10): os sete relatórios da dona (a conferência tem tela própria).
  'devolucao-por-motivo',
  'devolucao-por-vendedor',
  'devolucao-defeitos',
  'devolucao-trocas',
  'devolucao-creditos',
  'devolucao-reembolsos',
  'devolucao-autorizacoes',
  // Financeiro (tarefa 7.12): os nove relatórios financeiros da dona.
  'financeiro-acertos',
  'financeiro-pendencias',
  'financeiro-esperado-contado',
  'financeiro-comissoes',
  'financeiro-a-receber',
  'financeiro-a-pagar',
  'financeiro-inadimplencia',
  'financeiro-caixa',
  'financeiro-recebimentos',
] as const;
export type NomeDoRelatorio = (typeof NOMES_DOS_RELATORIOS)[number];
export type NomeDoRelatorioDeEstoqueOuCompras = Extract<
  NomeDoRelatorio,
  `estoque-${string}` | `compras-${string}`
>;
export type NomeDoRelatorioDeMaleta = Extract<NomeDoRelatorio, `maleta-${string}`>;
export type NomeDoRelatorioDeDevolucao = Extract<NomeDoRelatorio, `devolucao-${string}`>;
export type NomeDoRelatorioFinanceiro = Extract<NomeDoRelatorio, `financeiro-${string}`>;
export type NomeDoRelatorioDeCadastro = Exclude<
  NomeDoRelatorio,
  | NomeDoRelatorioDeEstoqueOuCompras
  | NomeDoRelatorioDeMaleta
  | NomeDoRelatorioDeDevolucao
  | NomeDoRelatorioFinanceiro
>;

export const SITUACOES_DO_RELATORIO = [
  'ATIVOS',
  'INATIVOS',
  'TODOS',
  // Compras
  'REGISTRADAS',
  'ESTORNADAS',
  'TODAS',
  // Contagens do estoque
  'EM_ANDAMENTO',
  'FECHADO',
  'CANCELADO',
  // Maletas
  'CANCELADAS',
  'COM_SALDO',
  'ZERADAS',
  'ATRASADAS',
  'NO_PRAZO',
  // Devolução e troca (tarefa 6.10)
  'ACEITA',
  'EM_REVISAO',
  'MANTIDA',
  'NAO_ACEITA',
  'REGISTRADOS',
  'ABERTOS',
  'UTILIZADOS',
  'CANCELADOS',
  'PEDIDA',
  'APROVADA',
  'USADA',
  'NEGADA',
  'VENCIDA',
  // Financeiro (tarefa 7.12)
  'ABERTA',
  'PARCIAL',
  'PAGA',
  'CANCELADA',
  'COBRADA',
  'ASSUMIDA_PELA_LOJA',
  'ENCONTRADA',
  'RECUSADA',
] as const;
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
  /** Estoque e compras (tarefa 2.8). */
  produtoId?: string;
  usuarioId?: string;
  /** Origem do lançamento no estoque (entradas e saídas). */
  origem?: string;
  /** Peças sem saída há N dias (peças paradas). */
  dias?: number;
  /** Uma contagem do estoque só. */
  inventarioId?: string;
  /** SIM ou NAO (contagens: só com diferença). */
  divergencia?: string;
  /** Uma compra só (lista das peças da compra). */
  compraId?: string;
  /** Maletas (tarefa 3.7): semana, uma maleta só e o prazo (em dias) para a maleta contar como atrasada. */
  semanaId?: string;
  remessaId?: string;
  diasDeAtraso?: number;
  /** Devolução e troca (tarefa 6.10): motivo da devolução e forma do dinheiro devolvido. */
  motivo?: string;
  forma?: string;
  /** Financeiro (tarefa 7.12): quem deve (CLIENTE ou VENDEDOR) e quando foi o recebimento (ACERTO ou AVULSO). */
  devedor?: string;
  momento?: string;
  /** A chave de um total do rodapé: mostra só as linhas que formam esse total. */
  recorte?: string;
  pagina: number;
  tamanho: number;
}
