/**
 * Por que uma devolução, um pedido de autorização, uma troca, um crédito ou um cancelamento não foi gravado no
 * computador (tarefa 6.8). Os textos são os do servidor (glossário, parte 6: "Devolução do cliente", "Autorização,
 * revisão e antifraude" e "Troca e crédito do cliente"): o computador recusa só o que o servidor também recusaria.
 */
export type CodigoDaFase6 =
  | 'VENDA_A_PRAZO'
  | 'MALETA_EM_ACERTO'
  | 'MALETA_NAO_CONFERIDA'
  | 'FORA_DO_PRAZO'
  | 'ACIMA_DO_VENDIDO'
  | 'MOTIVO_INVALIDO'
  | 'OBSERVACAO_OBRIGATORIA'
  | 'TEXTO_LONGO'
  | 'DEFEITO_COM_PECA_BOA'
  | 'ESTADO_INVALIDO'
  | 'QUANTIDADE_INVALIDA'
  | 'PECA_REPETIDA'
  | 'PECA_FORA_DA_VENDA'
  | 'FORMA_DO_REEMBOLSO'
  | 'SEM_PECAS'
  | 'SEM_PECAS_NOVAS'
  | 'AUTORIZACAO_NAO_APROVADA'
  | 'AUTORIZACAO_JA_USADA'
  | 'AUTORIZACAO_VENCIDA'
  | 'AUTORIZACAO_NAO_CONFERE'
  | 'AUTORIZACAO_DESNECESSARIA'
  | 'AUTORIZACAO_JA_PEDIDA'
  | 'VENDA_COM_DEVOLUCAO'
  | 'VENDA_DE_TROCA'
  | 'DEVOLUCAO_DE_TROCA'
  | 'DEVOLUCAO_NAO_ENCONTRADA'
  | 'TROCA_NAO_ENCONTRADA'
  | 'DEVOLUCAO_JA_CANCELADA'
  | 'TROCA_JA_CANCELADA'
  | 'CANCELAR_DEVOLUCAO_SO_DA_DONA'
  | 'CANCELAR_TROCA_SO_DA_DONA'
  | 'DEVOLUCAO_NAO_PODE_SER_CANCELADA'
  | 'TROCA_NAO_PODE_SER_CANCELADA'
  | 'PECA_DA_DEVOLUCAO_JA_VENDIDA'
  | 'CREDITO_DA_TROCA_JA_USADO'
  | 'CREDITO_DA_DEVOLUCAO_JA_USADO'
  | 'MOTIVO_DO_CANCELAMENTO_DA_DEVOLUCAO'
  | 'MOTIVO_DO_CANCELAMENTO_DA_TROCA'
  | 'CREDITO_NAO_ENCONTRADO'
  | 'CREDITO_DE_OUTRO_CLIENTE'
  | 'CREDITO_SEM_SALDO'
  | 'CREDITO_ACIMA_DO_SALDO'
  | 'CREDITO_REPETIDO'
  | 'CREDITO_SEM_ID'
  | 'CREDITO_EM_OUTRA_FORMA'
  | 'PAGO_SEM_DIFERENCA';

export const MENSAGENS_DA_FASE_6: Readonly<Record<CodigoDaFase6, string>> = Object.freeze({
  // Só para resposta de servidor antigo: desde a tarefa 6.6 a venda a prazo também se devolve (abate as parcelas).
  VENDA_A_PRAZO: 'Esta venda tem parcelas; a devolução dela chega em breve.',
  MALETA_EM_ACERTO:
    'O acerto desta maleta está acontecendo. Espere o acerto terminar e registre a devolução de novo.',
  MALETA_NAO_CONFERIDA:
    'Esta maleta ainda não foi conferida. Confira todas as peças da maleta antes de vender.',
  FORA_DO_PRAZO: 'Passou do prazo da garantia. Peça autorização à dona.',
  ACIMA_DO_VENDIDO: 'Esta quantidade passa do que o cliente comprou. Confira a quantidade.',
  MOTIVO_INVALIDO: 'Escolha o motivo da devolução: desistência, defeito, não serviu ou outro.',
  OBSERVACAO_OBRIGATORIA: 'Conte o motivo da devolução no campo de observação.',
  TEXTO_LONGO: 'Use no máximo 400 caracteres.',
  DEFEITO_COM_PECA_BOA: 'Com o motivo defeito, marque as peças como com defeito.',
  ESTADO_INVALIDO: 'Diga se a peça voltou boa ou com defeito.',
  QUANTIDADE_INVALIDA: 'Informe uma quantidade maior que zero.',
  PECA_REPETIDA:
    'A mesma peça aparece mais de uma vez com o mesmo estado. Junte as quantidades numa linha só.',
  PECA_FORA_DA_VENDA: 'Esta peça não consta nesta venda.',
  FORMA_DO_REEMBOLSO: 'Escolha como o dinheiro foi devolvido: dinheiro, Pix, cartão ou cheque.',
  SEM_PECAS: 'Informe pelo menos uma peça devolvida.',
  SEM_PECAS_NOVAS: 'Informe as peças que o cliente levou.',
  AUTORIZACAO_NAO_APROVADA:
    'Este pedido de autorização ainda não foi aprovado ou foi negado. Espere a resposta da dona.',
  AUTORIZACAO_JA_USADA:
    'Esta autorização já foi usada numa devolução. Peça outra autorização à dona.',
  AUTORIZACAO_VENCIDA: 'A autorização vale 7 dias e já venceu. Peça outra autorização à dona.',
  AUTORIZACAO_NAO_CONFERE:
    'Use a mesma venda, o mesmo motivo e no máximo as peças que a dona autorizou.',
  AUTORIZACAO_DESNECESSARIA: 'Esta devolução está no prazo. Registre a devolução direto.',
  AUTORIZACAO_JA_PEDIDA:
    'Já há um pedido de autorização para esta venda. Espere a resposta da dona ou use a autorização aprovada.',
  VENDA_COM_DEVOLUCAO: 'Esta venda já tem devolução. Cancele a devolução antes de estornar.',
  VENDA_DE_TROCA: 'Esta venda faz parte de uma troca. Use Cancelar troca.',
  DEVOLUCAO_DE_TROCA: 'Esta devolução faz parte de uma troca. Use Cancelar troca.',
  DEVOLUCAO_NAO_ENCONTRADA: 'Não encontramos esta devolução. Atualize a tela e tente de novo.',
  TROCA_NAO_ENCONTRADA: 'Não encontramos esta troca. Atualize a tela e tente de novo.',
  DEVOLUCAO_JA_CANCELADA: 'Esta devolução já foi cancelada e não pode mudar.',
  TROCA_JA_CANCELADA: 'Esta troca já foi cancelada e não pode mudar.',
  CANCELAR_DEVOLUCAO_SO_DA_DONA:
    'Só dá para cancelar a devolução no mesmo dia. Peça à dona para cancelar.',
  CANCELAR_TROCA_SO_DA_DONA: 'Só dá para cancelar a troca no mesmo dia. Peça à dona para cancelar.',
  DEVOLUCAO_NAO_PODE_SER_CANCELADA:
    'A dona já decidiu sobre esta devolução e ela não pode mais ser cancelada.',
  TROCA_NAO_PODE_SER_CANCELADA:
    'A dona já decidiu sobre esta troca e ela não pode mais ser cancelada.',
  PECA_DA_DEVOLUCAO_JA_VENDIDA:
    'A peça desta devolução já foi vendida de novo e a devolução não pode ser cancelada. Fale com a dona.',
  CREDITO_DA_TROCA_JA_USADO:
    'O crédito que sobrou desta troca já foi usado em outra compra e a troca não pode ser cancelada. Fale com a dona.',
  CREDITO_DA_DEVOLUCAO_JA_USADO:
    'O crédito desta devolução já foi usado em outra compra e a devolução não pode ser cancelada. Fale com a dona.',
  MOTIVO_DO_CANCELAMENTO_DA_DEVOLUCAO: 'Diga o motivo de cancelar a devolução.',
  MOTIVO_DO_CANCELAMENTO_DA_TROCA: 'Diga o motivo de cancelar a troca.',
  CREDITO_NAO_ENCONTRADO:
    'Não encontramos este crédito do cliente. Atualize a tela e tente de novo.',
  CREDITO_DE_OUTRO_CLIENTE:
    'Este crédito é de outro cliente. Use só o crédito do cliente desta venda.',
  CREDITO_SEM_SALDO:
    'Este crédito já foi todo usado ou foi cancelado. Escolha outra forma de pagamento.',
  CREDITO_ACIMA_DO_SALDO:
    'O valor passa do que sobra neste crédito. Diminua o valor e pague o resto de outra forma.',
  CREDITO_REPETIDO: 'O mesmo crédito aparece mais de uma vez. Use o crédito uma vez só.',
  CREDITO_SEM_ID: 'Escolha o crédito do cliente usado no pagamento.',
  CREDITO_EM_OUTRA_FORMA: 'O crédito só vale com a forma de pagamento crédito do cliente.',
  PAGO_SEM_DIFERENCA:
    'A peça nova vale menos que a devolvida: não há diferença a pagar. A sobra fica de crédito.',
});
