/**
 * Por que uma operação do consignado ao cliente não foi gravada no computador (tarefa 9.2; ADR-0016, item 11). O computador
 * recusa só o que também não deixa na tela e o que o servidor também recusaria (mesmos códigos e mesmo texto, que o
 * glossário aprova). Em espanhol, a tela escolhe a frase pelo código (`erros-es.ts`).
 */
export type CodigoDoConsignado =
  | 'NOTA_NAO_ENCONTRADA'
  | 'NOTA_JA_ACERTADA'
  | 'SEMANA_DA_NOTA_FECHADA'
  | 'DEVOLUCAO_MAIOR_QUE_O_LEVADO'
  | 'COMISSAO_FORA_DO_LIMITE'
  | 'ACERTO_NAO_ENCONTRADO_NO_COMPUTADOR'
  | 'ACERTO_JA_ESTORNADO'
  | 'RECIBO_NAO_ENCONTRADO'
  | 'RECIBO_JA_ESTORNADO_DO_CONSIGNADO'
  | 'RECIBO_DO_ACERTO'
  | 'PAGAMENTO_INVALIDO'
  | 'MOTIVO_OBRIGATORIO'
  | 'PEDIDO_SEM_PECAS'
  | 'SEM_SEMANA_ABERTA'
  | 'PEDIDO_SEM_CLIENTE'
  | 'PEDIDO_SEM_MALETA'
  | 'DATA_DE_COBRANCA_INVALIDA'
  | 'PAGO_ACIMA_DO_DEVIDO'
  | 'CLIENTE_SEM_PENDENCIA'
  | 'CADASTRO_DO_CLIENTE_INCOMPLETO'
  | 'FOTOS_DO_CLIENTE_FALTAM'
  | 'FOTO_INVALIDA'
  | 'PECA_NAO_ESTA_NA_NOTA'
  | 'NUMERO_DA_SEMANA_INVALIDO'
  | 'ITEM_DO_PEDIDO_NAO_ENCONTRADO'
  | 'CLIENTE_DE_OUTRO_REPRESENTANTE';

export const MENSAGENS_DO_CONSIGNADO: Readonly<Record<CodigoDoConsignado, string>> = Object.freeze({
  NOTA_NAO_ENCONTRADA: 'Não encontramos esta nota. Atualize a tela e tente de novo.',
  NOTA_JA_ACERTADA: 'Esta nota já foi acertada. Para mudar, estorne o acerto e faça de novo.',
  SEMANA_DA_NOTA_FECHADA:
    'Esta semana já foi fechada. Abra uma semana nova para fazer outro pedido.',
  DEVOLUCAO_MAIOR_QUE_O_LEVADO:
    'A devolução passa do que o cliente levou nesta nota. Confira as quantidades devolvidas.',
  COMISSAO_FORA_DO_LIMITE:
    'A comissão precisa ficar entre 0% e 40%. Corrija o percentual e tente de novo.',
  ACERTO_NAO_ENCONTRADO_NO_COMPUTADOR:
    'Não encontramos este acerto neste computador. Atualize a tela e tente de novo.',
  ACERTO_JA_ESTORNADO: 'Este acerto já foi estornado. Faça um acerto novo, se precisar.',
  RECIBO_NAO_ENCONTRADO: 'Não encontramos este recibo. Atualize a tela e tente de novo.',
  RECIBO_JA_ESTORNADO_DO_CONSIGNADO:
    'Este recibo já foi estornado e não pode mudar. Lance um pagamento novo, se precisar.',
  RECIBO_DO_ACERTO: 'Este recibo é de um acerto. Para desfazer, estorne o acerto.',
  PAGAMENTO_INVALIDO:
    'Confira o pagamento: use dinheiro, Pix, débito ou crédito à vista, com valor maior que zero e no máximo duas casas.',
  MOTIVO_OBRIGATORIO: 'Conte o motivo do estorno para continuar.',
  PEDIDO_SEM_PECAS: 'O pedido está sem peças. Acrescente pelo menos uma peça.',
  SEM_SEMANA_ABERTA: 'Não há semana aberta. Toque em "Nova semana" para começar.',
  PEDIDO_SEM_CLIENTE: 'Escolha o cliente do pedido.',
  PEDIDO_SEM_MALETA: 'Escolha a maleta antes de ler as peças.',
  DATA_DE_COBRANCA_INVALIDA: 'Confira a data de cobrança.',
  PAGO_ACIMA_DO_DEVIDO: 'O valor pago passa do que o cliente deve. Confira os valores.',
  CLIENTE_SEM_PENDENCIA: 'Este cliente não deve nada.',
  CADASTRO_DO_CLIENTE_INCOMPLETO:
    'Preencha o nome, o endereço (rua, número, bairro, cidade e estado) e o telefone do cliente.',
  FOTOS_DO_CLIENTE_FALTAM:
    'Faltam fotos: tire a foto do cliente e a frente e o verso do documento.',
  FOTO_INVALIDA: 'Não foi possível usar esta foto. Tire outra ou escolha outro arquivo.',
  PECA_NAO_ESTA_NA_NOTA: 'Esta peça não está na nota do cliente.',
  NUMERO_DA_SEMANA_INVALIDO: 'Confira o número da semana.',
  ITEM_DO_PEDIDO_NAO_ENCONTRADO: 'Não encontramos esta peça no pedido. Atualize a tela.',
  CLIENTE_DE_OUTRO_REPRESENTANTE: 'Este cliente não é seu.',
});
