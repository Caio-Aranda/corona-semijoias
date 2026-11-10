/**
 * Vendedores na ponte: o cadastro, as abas da ficha, as referências e os documentos. É a lista que o
 * processo principal usa para conferir os dados antes de falar com a API; a tela usa os mesmos nomes.
 */
import type { CampoDaApi } from './cadastros';

export const OPERACOES_DE_VENDEDORES = [
  'vendedores.listar',
  'vendedores.ver',
  'vendedores.criar',
  'vendedores.editar',
  'vendedores.inativar',
  'vendedores.reativar',
  'vendedores.aba.ver',
  'vendedores.aba.gravar',
  'vendedores.referencias.listar',
  'vendedores.referencias.criar',
  'vendedores.referencias.editar',
  'vendedores.referencias.excluir',
  'vendedores.documentos.listar',
  'vendedores.documentos.enviar',
  'vendedores.documentos.baixar',
  'vendedores.documentos.excluir',
] as const;
export type OperacaoDeVendedores = (typeof OPERACOES_DE_VENDEDORES)[number];

export const SITUACOES_DA_LISTA = ['ATIVOS', 'INATIVOS', 'TODOS'] as const;

/** Campos do cadastro principal do vendedor (a aba "Cadastro"). */
export const CAMPOS_DO_VENDEDOR: readonly CampoDaApi[] = [
  { nome: 'nome', tipo: 'texto', max: 200 },
  { nome: 'dataNascimento', tipo: 'data' },
  { nome: 'sexo', tipo: 'sexo' },
  { nome: 'estrangeiro', tipo: 'booleano' },
  { nome: 'rgIe', tipo: 'texto', max: 30 },
  { nome: 'rgUf', tipo: 'texto', max: 20 },
  { nome: 'cpfCnpj', tipo: 'texto', max: 30 },
  { nome: 'naturalidade', tipo: 'texto', max: 100 },
  { nome: 'escolaridade', tipo: 'texto', max: 100 },
  { nome: 'telefone1', tipo: 'texto', max: 30 },
  { nome: 'telefone2', tipo: 'texto', max: 30 },
  { nome: 'whatsapp', tipo: 'texto', max: 30 },
  { nome: 'email', tipo: 'texto', max: 200 },
  { nome: 'facebook', tipo: 'texto', max: 200 },
  { nome: 'outrasMidias', tipo: 'texto', max: 300 },
  { nome: 'limiteConsignacao', tipo: 'dinheiro' },
  { nome: 'restricaoId', tipo: 'uuid' },
  { nome: 'comissaoPercentual', tipo: 'percentual' },
  { nome: 'pracaId', tipo: 'uuid' },
];

export const ABAS_DO_VENDEDOR = [
  'endereco',
  'contato',
  'trabalho',
  'conjuge',
  'mei',
  'observacoes',
] as const;
export type AbaDoVendedor = (typeof ABAS_DO_VENDEDOR)[number];

export const CAMPOS_DAS_ABAS: Record<AbaDoVendedor, readonly CampoDaApi[]> = {
  endereco: [
    { nome: 'logradouro', tipo: 'texto', max: 200 },
    { nome: 'numero', tipo: 'texto', max: 20 },
    { nome: 'complemento', tipo: 'texto', max: 100 },
    { nome: 'bairro', tipo: 'texto', max: 100 },
    { nome: 'cep', tipo: 'texto', max: 20 },
    { nome: 'cidade', tipo: 'texto', max: 100 },
    { nome: 'uf', tipo: 'texto', max: 20 },
    { nome: 'localReferencia', tipo: 'texto', max: 200 },
    { nome: 'residencia', tipo: 'texto', max: 100 },
    { nome: 'valorResidencia', tipo: 'dinheiro' },
  ],
  contato: [
    { nome: 'nomePai', tipo: 'texto', max: 200 },
    { nome: 'nomeMae', tipo: 'texto', max: 200 },
    { nome: 'contatoNome', tipo: 'texto', max: 200 },
    { nome: 'contatoParentesco', tipo: 'texto', max: 100 },
    { nome: 'contatoTelefone', tipo: 'texto', max: 30 },
  ],
  trabalho: [
    { nome: 'empresa', tipo: 'texto', max: 200 },
    { nome: 'funcao', tipo: 'texto', max: 100 },
    { nome: 'telefone', tipo: 'texto', max: 30 },
    { nome: 'endereco', tipo: 'texto', max: 300 },
    { nome: 'dataAdmissao', tipo: 'data' },
    { nome: 'renda', tipo: 'dinheiro' },
  ],
  conjuge: [
    { nome: 'nome', tipo: 'texto', max: 200 },
    { nome: 'cpf', tipo: 'texto', max: 20 },
    { nome: 'rg', tipo: 'texto', max: 30 },
    { nome: 'dataNascimento', tipo: 'data' },
    { nome: 'telefone', tipo: 'texto', max: 30 },
    { nome: 'profissao', tipo: 'texto', max: 100 },
    { nome: 'empresa', tipo: 'texto', max: 200 },
    { nome: 'renda', tipo: 'dinheiro' },
  ],
  mei: [
    { nome: 'cnpj', tipo: 'texto', max: 20 },
    { nome: 'razaoSocial', tipo: 'texto', max: 200 },
    { nome: 'nomeFantasia', tipo: 'texto', max: 200 },
    { nome: 'dataAbertura', tipo: 'data' },
    { nome: 'atividade', tipo: 'texto', max: 200 },
  ],
  observacoes: [{ nome: 'texto', tipo: 'texto', max: 4000 }],
};

export const CAMPOS_DA_REFERENCIA: readonly CampoDaApi[] = [
  { nome: 'nome', tipo: 'texto', max: 200 },
  { nome: 'parentesco', tipo: 'texto', max: 100 },
  { nome: 'telefone', tipo: 'texto', max: 30 },
  { nome: 'observacao', tipo: 'texto', max: 1000 },
];

/** Documentos: o que o servidor aceita (ele confere de novo pelo conteúdo do arquivo). */
export const TAMANHO_MAXIMO_DO_DOCUMENTO = 10 * 1024 * 1024;
export const EXTENSOES_DE_DOCUMENTO = ['pdf', 'jpg', 'jpeg', 'png', 'webp'] as const;
export const TAMANHO_MAXIMO_DO_NOME_DO_ARQUIVO = 255;

export function extensaoAceita(nome: string): boolean {
  const m = /\.([A-Za-z0-9]+)$/.exec(nome);
  return m !== null && (EXTENSOES_DE_DOCUMENTO as readonly string[]).includes(m[1]!.toLowerCase());
}

/** O que a tela manda ao enviar um documento; o arquivo vai como bytes, nunca como texto codificado. */
export interface ArquivoParaEnviar {
  nome: string;
  bytes: Uint8Array;
}

/** O que a ponte devolve ao baixar um documento. */
export interface ArquivoBaixado {
  nome: string;
  tipo: string;
  bytes: Uint8Array;
}
