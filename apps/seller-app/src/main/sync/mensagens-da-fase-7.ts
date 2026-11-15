/**
 * Por que um parcelamento, um recebimento do cliente ou o estorno dele não foi gravado no computador (tarefa 7.11; ADR-0012,
 * item 7). Os textos são os do servidor (glossário): o computador recusa só o que o servidor também recusaria, e nunca
 * calcula parcela.
 */
export type CodigoDaFase7 =
  | 'PARCELAMENTO_SEM_SALDO'
  | 'VENCIMENTO_NO_PASSADO'
  | 'VENCIMENTO_LONGE_DEMAIS'
  | 'RECEBIMENTO_ACIMA_DO_EM_ABERTO'
  | 'VENDA_SEM_VALOR_EM_ABERTO'
  | 'RECEBIMENTO_NAO_ENCONTRADO'
  | 'RECEBIMENTO_JA_ESTORNADO'
  | 'ESTORNO_DE_RECEBIMENTO_SO_DA_DONA'
  | 'PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA'
  | 'MOTIVO_DO_ESTORNO_DO_RECEBIMENTO';

export const MENSAGENS_DA_FASE_7: Readonly<Record<CodigoDaFase7, string>> = Object.freeze({
  PARCELAMENTO_SEM_SALDO: 'Esta venda já está paga. Não há parcelas.',
  VENCIMENTO_NO_PASSADO: 'O primeiro vencimento não pode ser antes do dia da venda.',
  VENCIMENTO_LONGE_DEMAIS:
    'O primeiro vencimento está longe demais. Escolha uma data até um ano depois da venda.',
  RECEBIMENTO_ACIMA_DO_EM_ABERTO: 'O valor passa do que falta pagar nesta venda. Confira o valor.',
  VENDA_SEM_VALOR_EM_ABERTO: 'Esta venda não tem nada a receber.',
  RECEBIMENTO_NAO_ENCONTRADO: 'Não encontramos este recebimento. Atualize a tela e tente de novo.',
  RECEBIMENTO_JA_ESTORNADO: 'Este recebimento já foi estornado e não pode mudar.',
  ESTORNO_DE_RECEBIMENTO_SO_DA_DONA:
    'Só dá para estornar o recebimento no mesmo dia. Peça à dona para estornar.',
  PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA:
    'Este pagamento foi feito na hora da venda. Para corrigir, estorne a venda.',
  MOTIVO_DO_ESTORNO_DO_RECEBIMENTO: 'Diga o motivo de estornar o recebimento.',
});
