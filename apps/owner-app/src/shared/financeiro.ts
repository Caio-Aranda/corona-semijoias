/**
 * Financeiro na ponte (tarefa 7.10): caixa, categorias do caixa, contas a pagar, contas a receber, recebimento avulso do
 * vendedor e fechar semana. Só da dona. O servidor calcula tudo (saldo, parcelas, quanto cada recebimento baixa); a tela
 * manda só o que a pessoa digitou e um UUID por comando.
 */
export const OPERACOES_DE_FINANCEIRO = [
  'caixa.extrato',
  'caixa.saldo',
  'caixa.lancar',
  'caixa.estornar',
  'caixa.categorias',
  'caixa.criarCategoria',
  'caixa.renomearCategoria',
  'caixa.inativarCategoria',
  'caixa.reativarCategoria',
  'contasAPagar.listar',
  'contasAPagar.pagar',
  'contasAPagar.estornar',
  'contasAReceber.listar',
  'recebimentos.listar',
  'recebimentos.receber',
  'recebimentos.estornar',
  'semanas.fechar',
] as const;
export type OperacaoDeFinanceiro = (typeof OPERACOES_DE_FINANCEIRO)[number];

/** Só dinheiro, Transferência (PIX) e boleto. Nunca cheque, cartão nem entrada. */
export const FORMAS_DO_FINANCEIRO = ['DINHEIRO', 'PIX', 'BOLETO'] as const;
export type FormaDoFinanceiro = (typeof FORMAS_DO_FINANCEIRO)[number];
export const TIPOS_DO_CAIXA = ['ENTRADA', 'SAIDA'] as const;
export type TipoDoCaixa = (typeof TIPOS_DO_CAIXA)[number];
export const SITUACOES_DA_CONTA_A_PAGAR = [
  'ABERTA',
  'PARCIAL',
  'PAGA',
  'CANCELADA',
  'VENCIDA',
] as const;
export const SITUACOES_DA_CONTA_A_RECEBER = [
  'ABERTA',
  'PARCIAL',
  'PAGA',
  'CANCELADA',
  'EM_ABERTO',
  'VENCIDA',
] as const;
export const NIVEIS_DA_CONTA_A_RECEBER = ['CLIENTE', 'VENDEDOR'] as const;

export const MAXIMO_DA_DESCRICAO_DO_CAIXA = 200;
export const MAXIMO_DO_MOTIVO_DO_FINANCEIRO = 400;
export const MAXIMO_DO_NOME_DA_CATEGORIA = 80;
