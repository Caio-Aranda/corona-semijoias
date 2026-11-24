/**
 * Acertos na ponte (tarefa 7.9): iniciar, contar, ver a prévia, fechar com o pagamento, desistir, resolver
 * pendências e a configuração da comissão. Só da dona. O servidor calcula tudo (comissão, meta, totais, faltas,
 * parcelas, crédito); a tela manda só o que a pessoa decidiu ou digitou e um UUID por comando.
 */
import type { ValorDoRecebimento } from './valor-do-recebimento';
export const OPERACOES_DE_ACERTOS = [
  'acertos.listar',
  'acertos.iniciar',
  'acertos.ver',
  'acertos.ler',
  'acertos.desistir',
  'acertos.previa',
  'acertos.previaComDecisoes',
  'acertos.fechar',
  'pendencias.listar',
  'pendencias.resolver',
  'comissao.ler',
  'comissao.alterar',
] as const;
export type OperacaoDeAcertos = (typeof OPERACOES_DE_ACERTOS)[number];

export const SITUACOES_DO_ACERTO = ['EM_ACERTO', 'ACERTADO', 'DESISTIDO'] as const;
export const SITUACOES_DA_PENDENCIA = [
  'ABERTA',
  'COBRADA',
  'ASSUMIDA_PELA_LOJA',
  'ENCONTRADA',
  'APROVADA',
  'RECUSADA',
] as const;
export const ESTADOS_DA_PECA_NA_CONTAGEM = ['BOA', 'DEFEITO'] as const;
export const ACOES_DA_PENDENCIA = ['LOJA_ASSUME', 'ENCONTRADA'] as const;

/** Como a pessoa decide uma falta ou o que não foi aceito (o servidor confere de novo). */
export const DECISOES_DE_COBRANCA = ['COBRAR_NO_ACERTO', 'COBRAR_DEPOIS', 'LOJA_ASSUME'] as const;
export type DecisaoDeCobrancaDaTela = (typeof DECISOES_DE_COBRANCA)[number];
export const DECISOES_DE_PENDENCIA_ANTIGA = ['COBRAR_NO_ACERTO', 'MANTER'] as const;
/** Só dinheiro e Transferência (PIX) entram como pagamento; o boleto tem campo próprio. Nunca cheque, cartão nem entrada. */
export const FORMAS_DO_PAGAMENTO = ['DINHEIRO', 'PIX'] as const;
export type FormaDoPagamento = (typeof FORMAS_DO_PAGAMENTO)[number];
export const DESTINOS_A_FAVOR_DO_VENDEDOR = ['CREDITO', 'PAGAR_NA_HORA'] as const;
export type DestinoDoAFavor = (typeof DESTINOS_A_FAVOR_DO_VENDEDOR)[number];

export const MAXIMO_DA_QUANTIDADE_NA_LEITURA = 100_000;
export const MAXIMO_DE_PARCELAS = 24;
export const MAXIMO_DO_MOTIVO_DO_ACERTO = 400;

/** O que a tela manda para a prévia e para fechar: só decisões e o que a pessoa digitou. */
export interface PedidoDeFechamentoDaTela {
  /** Id do fechamento (UUID da tela). Só o fechar usa; a prévia o ignora. */
  id?: string;
  faltas: { produtoId: string; decisao: DecisaoDeCobrancaDaTela; motivo?: string }[];
  defeitos: { produtoId: string; aprovadas: number }[];
  cobrancas: { origemId: string; decisao: DecisaoDeCobrancaDaTela; motivo?: string }[];
  pendenciasAntigas: { pendenciaId: string; decisao: 'COBRAR_NO_ACERTO' | 'MANTER' }[];
  /** O líquido da última prévia, só para o servidor conferir. A tela não o calcula. */
  liquidoMostrado?: string;
  /** A cotação da última prévia: se a dona lançar outra antes de fechar com recebimento em Dólar ou Guarani, o sistema recusa. */
  cotacaoId?: string;
  pagamento?: PagamentoDaTela;
}

export interface PagamentoDaTela {
  /** Em Real, `valor`; em Dólar ou Guarani, só `moeda` e `valorNaMoeda` (o sistema converte pela cotação do acerto). */
  recebimentos?: ({ id: string; forma: FormaDoPagamento } & ValorDoRecebimento)[];
  boleto?: { valor: string; vencimento: string };
  restante?: { parcelas: number; primeiroVencimento: string };
  contasAntigas?: { id: string; contaId: string; forma: FormaDoPagamento; valor: string }[];
  aFavorDoVendedor?: DestinoDoAFavor;
  formaDoPagamento?: FormaDoPagamento;
}
