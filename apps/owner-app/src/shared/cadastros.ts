/**
 * Os seis cadastros básicos da dona e os campos que cada um aceita. É a lista que o processo
 * principal usa para conferir os dados antes de falar com a API; a tela usa os mesmos nomes.
 */
export const NOMES_DOS_CADASTROS = [
  'fornecedores',
  'categorias',
  'pracas',
  'semanas',
  'restricoes',
  'clientes',
] as const;
export type NomeDoCadastro = (typeof NOMES_DOS_CADASTROS)[number];

export const ACOES_DE_CADASTRO = [
  'listar',
  'criar',
  'editar',
  'excluir',
  'inativar',
  'reativar',
] as const;
export type AcaoDeCadastro = (typeof ACOES_DE_CADASTRO)[number];

export type OperacaoDeCadastro = `${NomeDoCadastro}.${AcaoDeCadastro}`;

/** Todos os cadastros têm ativo e inativo, menos as semanas (elas têm Aberta e Fechada). */
export const TEM_ATIVO: Record<NomeDoCadastro, boolean> = {
  fornecedores: true,
  categorias: true,
  pracas: true,
  semanas: false,
  restricoes: true,
  clientes: true,
};

export const OPERACOES_DE_CADASTRO: readonly OperacaoDeCadastro[] = NOMES_DOS_CADASTROS.flatMap(
  (n) =>
    ACOES_DE_CADASTRO.filter((a) => TEM_ATIVO[n] || (a !== 'inativar' && a !== 'reativar')).map(
      (a): OperacaoDeCadastro => `${n}.${a}`,
    ),
);

export type TipoDeCampoDaApi =
  | 'texto'
  | 'inteiro'
  | 'data'
  | 'booleano'
  | 'uuid'
  | 'situacao'
  /** Dinheiro em reais como texto com ponto ("1500.00"), nunca negativo. */
  | 'dinheiro'
  /** Porcentagem de 0 a 100 como texto com ponto ("12.50"). */
  | 'percentual'
  | 'sexo';

export interface CampoDaApi {
  nome: string;
  tipo: TipoDeCampoDaApi;
  /** Tamanho máximo do texto (o servidor confere de novo). */
  max?: number;
}

/** Endereço de fornecedor e de cliente: um objeto com estes campos de texto. */
export const CAMPOS_DO_ENDERECO: readonly CampoDaApi[] = [
  { nome: 'logradouro', tipo: 'texto', max: 200 },
  { nome: 'numero', tipo: 'texto', max: 20 },
  { nome: 'complemento', tipo: 'texto', max: 100 },
  { nome: 'bairro', tipo: 'texto', max: 100 },
  { nome: 'cidade', tipo: 'texto', max: 100 },
  { nome: 'uf', tipo: 'texto', max: 20 },
  { nome: 'cep', tipo: 'texto', max: 20 },
];

/** Campos de cada cadastro, além de `endereco` (objeto), `id` (só ao criar) e `version` (só ao editar). */
export const CAMPOS_DOS_CADASTROS: Record<NomeDoCadastro, readonly CampoDaApi[]> = {
  fornecedores: [
    { nome: 'razaoSocial', tipo: 'texto', max: 200 },
    { nome: 'cnpj', tipo: 'texto', max: 30 },
    { nome: 'contatoNome', tipo: 'texto', max: 100 },
    { nome: 'telefone', tipo: 'texto', max: 30 },
    { nome: 'email', tipo: 'texto', max: 200 },
  ],
  categorias: [{ nome: 'nome', tipo: 'texto', max: 100 }],
  pracas: [{ nome: 'nome', tipo: 'texto', max: 100 }],
  semanas: [
    { nome: 'numero', tipo: 'inteiro' },
    { nome: 'inicio', tipo: 'data' },
    { nome: 'fim', tipo: 'data' },
    { nome: 'situacao', tipo: 'situacao' },
  ],
  restricoes: [
    { nome: 'codigo', tipo: 'texto', max: 30 },
    { nome: 'descricao', tipo: 'texto', max: 200 },
    { nome: 'bloqueiaRemessa', tipo: 'booleano' },
  ],
  clientes: [
    { nome: 'nome', tipo: 'texto', max: 200 },
    { nome: 'cpf', tipo: 'texto', max: 20 },
    { nome: 'dataNascimento', tipo: 'data' },
    { nome: 'telefone', tipo: 'texto', max: 30 },
    { nome: 'whatsapp', tipo: 'texto', max: 30 },
    { nome: 'observacoes', tipo: 'texto', max: 2000 },
    { nome: 'vendedorResponsavelId', tipo: 'uuid' },
  ],
};

export const TEM_ENDERECO: Record<NomeDoCadastro, boolean> = {
  fornecedores: true,
  categorias: false,
  pracas: false,
  semanas: false,
  restricoes: false,
  clientes: true,
};

/** Só estes aceitam que o aplicativo escolha o identificador (UUID) ao criar. */
export const ACEITA_ID_NO_APP: Record<NomeDoCadastro, boolean> = {
  fornecedores: false,
  categorias: true,
  pracas: false,
  semanas: true,
  restricoes: false,
  clientes: true,
};
