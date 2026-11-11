/**
 * Maletas na ponte: montar (rascunho), enviar e consultar. Montar é sempre online e só da dona; o
 * servidor calcula preço, totais e limite, a tela manda só vendedor, semana, praça, peça e quantidade.
 */
export const OPERACOES_DE_MALETAS = [
  'maletas.criar',
  'maletas.ver',
  'maletas.listar',
  'maletas.colocarPeca',
  'maletas.tirarPeca',
  'maletas.excluir',
  'maletas.enviar',
  'maletas.emAberto',
  'maletas.saldo',
  'maletas.devolver',
  'maletas.desfazerDevolucao',
  'maletas.cancelar',
] as const;
export type OperacaoDeMaletas = (typeof OPERACOES_DE_MALETAS)[number];

export const TIPOS_DE_MALETA = ['NOVA', 'REPOSICAO'] as const;
export type TipoDeMaleta = (typeof TIPOS_DE_MALETA)[number];

/** Limite que o servidor também confere (ele tem a palavra final). */
export const MAXIMO_DA_QUANTIDADE_NA_MALETA = 100_000;

export const SITUACOES_DA_MALETA = [
  'RASCUNHO',
  'ENVIADA',
  'CONFERIDA',
  'EM_ACERTO',
  'ACERTADA',
  'CANCELADA',
] as const;

/** Motivo de cancelamento e de desfazer devolução: o servidor aceita até 400 caracteres. */
export const MAXIMO_DO_MOTIVO_DA_MALETA = 400;
