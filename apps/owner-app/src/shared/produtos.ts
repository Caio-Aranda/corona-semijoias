/**
 * Produtos na ponte: lista, ficha, códigos, fotos e importação de fotos. É a lista que o processo
 * principal usa para conferir os dados antes de falar com a API; a tela usa os mesmos nomes.
 */
export const OPERACOES_DE_PRODUTOS = [
  'produtos.listar',
  'produtos.ver',
  'produtos.porCodigo',
  'produtos.criar',
  'produtos.editar',
  'produtos.estoqueMinimo',
  'produtos.codigos.gravar',
  'produtos.codigoProximo',
  'produtos.inativar',
  'produtos.reativar',
  'produtos.fotos.listar',
  'produtos.fotos.enviar',
  'produtos.fotos.principal',
  'produtos.fotos.excluir',
  'produtos.fotos.imagem',
  'produtos.importacao.previa',
  'produtos.importacao.confirmar',
  'importacoes.registrar',
] as const;
export type OperacaoDeProdutos = (typeof OPERACOES_DE_PRODUTOS)[number];

export const TIPOS_DE_CODIGO = ['LOJA', 'FORNECEDOR', 'EAN13'] as const;
export type TipoDeCodigo = (typeof TIPOS_DE_CODIGO)[number];

export const TAMANHOS_DE_FOTO = ['miniatura', 'media', 'grande'] as const;
export type TamanhoDeFoto = (typeof TAMANHOS_DE_FOTO)[number];

/** Limites da foto (o servidor confere de novo, pelo conteúdo do arquivo). */
export const TAMANHO_MAXIMO_DA_FOTO = 10 * 1024 * 1024;
export const EXTENSOES_DE_FOTO = ['jpg', 'jpeg', 'png', 'webp'] as const;
export const FOTOS_POR_PECA = 5;
/** A importação manda no máximo 10 fotos por pedido (limite de partes do servidor). */
export const FOTOS_POR_LOTE = 10;
export const MAXIMO_DE_NOMES_NA_PREVIA = 5000;

/** Tamanho máximo de um código (igual ao do servidor). */
export const TAMANHO_MAXIMO_DO_CODIGO = 60;

export function extensaoDeFotoAceita(nome: string): boolean {
  const m = /\.([A-Za-z0-9]+)$/.exec(nome);
  return m !== null && (EXTENSOES_DE_FOTO as readonly string[]).includes(m[1]!.toLowerCase());
}

/** Um código da peça, no formato que a API aceita. */
export interface CodigoParaGravar {
  id?: string;
  codigo: string;
  tipo: TipoDeCodigo;
  fornecedorId?: string;
  principal?: boolean;
  ativo?: boolean;
}
