/**
 * Mensagens de erro em ESPANHOL, pelo código do erro (`CodigoErro` da API e os códigos que o próprio computador
 * dá quando recusa uma gravação). Em português a tela mostra a mensagem que veio da API ou do computador, que já é
 * o texto do glossário. Código que não está aqui: a tela mostra a mensagem como veio (nada se perde).
 * Os textos seguem docs/glossario-interface.md (coluna em espanhol).
 */
export const ERROS_ES: Readonly<Record<string, string>> = Object.freeze({
  // --- Gerais e entrada ---
  ERRO_INTERNO:
    'Algo salió mal ahora. Intentá de nuevo. Si sigue igual, hablá con el soporte del sistema.',
  ROTA_NAO_ENCONTRADA:
    'No encontramos lo que pediste. Actualizá la aplicación e intentá de nuevo. Si sigue igual, hablá con el soporte del sistema.',
  DADOS_INVALIDOS:
    'Algunos datos no están correctos. Revisá lo que completaste e intentá de nuevo.',
  PEDIDO_INVALIDO:
    'No se pudo entender el pedido. Actualizá la aplicación e intentá de nuevo. Si sigue igual, hablá con el soporte del sistema.',
  SEM_PERMISSAO:
    'No tenés acceso a esta parte del sistema. Actualizá la aplicación e intentá de nuevo. Si sigue igual, hablá con el soporte del sistema.',
  LOGIN_INVALIDO:
    'No se pudo entrar. Usuario o contraseña incorrectos. Revisá lo que escribiste e intentá de nuevo.',
  USUARIO_DESATIVADO:
    'Tu acceso fue desactivado. Hablá con la dueña de la tienda para volver a usar el sistema.',
  SESSAO_TERMINADA:
    'Tu sesión terminó. Entrá de nuevo con tu usuario y tu contraseña para continuar.',
  ACESSO_BLOQUEADO:
    'Acceso bloqueado por unos minutos. Te equivocaste de contraseña varias veces. Por seguridad, esperá unos minutos e intentá de nuevo.',
  SENHA_TEMPORARIA_VENCIDA:
    'Tu contraseña temporal venció. La contraseña que recibiste vale solo por unos días, y ese plazo terminó. Pedile otra a la dueña.',
  TROCA_DE_SENHA_OBRIGATORIA:
    'Creá tu contraseña nueva. La contraseña que recibiste es temporal. Para continuar, creá una contraseña nueva que solo vos sepas.',
  SENHA_LONGA_DEMAIS:
    'Esta contraseña es demasiado larga. Elegí una más corta, de 36 caracteres como máximo.',
  SENHA_FRACA:
    'La contraseña nueva todavía no está bien. Tiene que tener por lo menos 8 caracteres, con letras y números.',
  SENHA_IGUAL_A_TEMPORARIA:
    'La contraseña nueva tiene que ser distinta de la temporal. Elegí otra.',
  APP_TIPO_INVALIDO:
    'No se pudo entender el pedido. Actualizá la aplicación e intentá de nuevo. Si sigue igual, hablá con el soporte del sistema.',
  APP_DESATUALIZADO: 'Tu aplicación está desactualizada. Actualizala para seguir usándola.',
  APP_INCOMPATIVEL:
    'Esta no es la aplicación de tu acceso. Abrí la otra aplicación de Corona Semijoias.',
  // --- Vendas, clientes e maleta (API) ---
  VENDA_NAO_ENCONTRADA: 'No encontramos esta venta. Actualizá la pantalla e intentá de nuevo.',
  VENDEDOR_DESATIVADO: 'Este vendedor está desactivado. Hablá con la dueña.',
  VENDA_JA_REGISTRADA_DIFERENTE:
    'Esta venta ya fue registrada con otros datos. Revisá tus ventas antes de registrar de nuevo.',
  VENDA_SEM_SALDO: 'No hay piezas suficientes en tu maleta para esta venta.',
  DESCONTO_ACIMA_DO_LIMITE: 'El descuento pasa del límite que definió la dueña. Bajá el descuento.',
  VENDA_JA_ESTORNADA: 'Esta venta ya fue anulada y no puede cambiar.',
  ESTORNO_SO_DA_DONA: 'Solo se puede anular la venta el mismo día. Pedile a la dueña que la anule.',
  MALETA_NAO_ESTA_COM_VOCE: 'Esta maleta no está disponible para vender. Actualizá la pantalla.',
  MALETA_EM_OUTRO_APARELHO:
    'Esta maleta ya está en otra computadora. Pedile a la dueña que libere esta computadora.',
  PECA_NAO_ENCONTRADA: 'Esta pieza no consta en tu maleta.',
  MALETA_JA_CONFERIDA:
    'Esta maleta ya fue controlada. Actualizá la pantalla para ver el resultado.',
  CLIENTE_CPF_JA_CADASTRADO:
    'Este cliente ya está registrado. Elegí el cliente que ya existe, en vez de registrarlo de nuevo.',
  CLIENTE_JA_REGISTRADO_DIFERENTE:
    'Este cliente ya fue registrado con otros datos. Revisá la lista de clientes antes de registrar de nuevo.',
  CLIENTE_DE_OUTRO_VENDEDOR:
    'Este cliente lo atiende otro vendedor. Solo quien atiende al cliente cambia su registro. Hablá con la dueña.',
  MALETA_NAO_CONFERIDA:
    'Esta maleta todavía no fue controlada. Controlá todas las piezas de la maleta antes de vender.',
  MALETA_EM_ACERTO: 'Esta maleta está en acerto. Esperá a que la dueña termine el acerto.',
  // --- Devolução, troca e crédito ---
  DEVOLUCAO_NAO_ENCONTRADA:
    'No encontramos esta devolución. Actualizá la pantalla e intentá de nuevo.',
  DEVOLUCAO_JA_REGISTRADA_DIFERENTE:
    'Esta devolución ya fue registrada con otros datos. Revisá las devoluciones antes de registrar de nuevo.',
  DEVOLUCAO_ACIMA_DO_VENDIDO:
    'Esta cantidad pasa de lo que el cliente compró en esta venta. Revisá la cantidad.',
  GARANTIA_VENCIDA: 'Pasó el plazo de la garantía. Pedile autorización a la dueña.',
  VENDA_A_PRAZO: 'Esta venta tiene cuotas; su devolución llega pronto.',
  VENDA_A_PRAZO_SEM_DEVOLUCAO: 'Esta venta tiene cuotas; su devolución llega pronto.',
  VENDA_COM_DEVOLUCAO: 'Esta venta ya tiene devolución. Cancelá la devolución antes de anular.',
  CANCELAR_DEVOLUCAO_SO_DA_DONA:
    'Solo se puede cancelar la devolución el mismo día. Pedile a la dueña que la cancele.',
  DEVOLUCAO_JA_CANCELADA: 'Esta devolución ya fue cancelada y no puede cambiar.',
  DEVOLUCAO_NAO_PODE_SER_CANCELADA:
    'La dueña ya decidió sobre esta devolución y ya no se puede cancelar.',
  PECA_DA_DEVOLUCAO_JA_VENDIDA:
    'La pieza de esta devolución ya se vendió de nuevo y la devolución no se puede cancelar. Hablá con la dueña.',
  AUTORIZACAO_NAO_ENCONTRADA:
    'No encontramos este pedido de autorización. Actualizá la pantalla e intentá de nuevo.',
  AUTORIZACAO_JA_REGISTRADA_DIFERENTE:
    'Este pedido de autorización ya fue hecho con otros datos. Revisá los pedidos antes de pedir de nuevo.',
  AUTORIZACAO_DESNECESSARIA:
    'Esta devolución está dentro del plazo. Registrá la devolución directamente.',
  AUTORIZACAO_JA_PEDIDA:
    'Ya hay un pedido de autorización para esta venta. Esperá la respuesta de la dueña o usá la autorización aprobada.',
  AUTORIZACAO_JA_DECIDIDA:
    'La dueña ya decidió este pedido de autorización y la decisión no cambia.',
  AUTORIZACAO_NAO_APROVADA:
    'Este pedido de autorización todavía no fue aprobado o fue negado. Esperá la respuesta de la dueña.',
  AUTORIZACAO_JA_USADA:
    'Esta autorización ya se usó en una devolución. Pedile otra autorización a la dueña.',
  AUTORIZACAO_VENCIDA:
    'La autorización vale 7 días y ya venció. Pedile otra autorización a la dueña.',
  AUTORIZACAO_NAO_CONFERE:
    'Usá la misma venta, el mismo motivo y como máximo las piezas que autorizó la dueña.',
  DEVOLUCAO_PRECISA_DE_AUTORIZACAO:
    'Esta devolución pasa del valor que la dueña aprueba antes. Pedile autorización a la dueña.',
  TROCA_NAO_ENCONTRADA: 'No encontramos este cambio. Actualizá la pantalla e intentá de nuevo.',
  TROCA_JA_REGISTRADA_DIFERENTE:
    'Este cambio ya fue registrado con otros datos. Revisá los cambios antes de registrar de nuevo.',
  TROCA_NAO_CONFERE:
    'Los valores de este cambio no cerraron y no se guardó nada. Intentá de nuevo; si sigue igual, hablá con la dueña.',
  DEVOLUCAO_NAO_CONFERE:
    'Los valores de esta devolución no cerraron y no se guardó nada. Intentá de nuevo; si sigue igual, hablá con la dueña.',
  TROCA_JA_CANCELADA: 'Este cambio ya fue cancelado y no puede cambiar.',
  TROCA_NAO_PODE_SER_CANCELADA: 'La dueña ya decidió sobre este cambio y ya no se puede cancelar.',
  CANCELAR_TROCA_SO_DA_DONA:
    'Solo se puede cancelar el cambio el mismo día. Pedile a la dueña que lo cancele.',
  CREDITO_DA_TROCA_JA_USADO:
    'El crédito que sobró de este cambio ya se usó en otra compra y el cambio no se puede cancelar. Hablá con la dueña.',
  CREDITO_DA_DEVOLUCAO_JA_USADO:
    'El crédito de esta devolución ya se usó en otra compra y la devolución no se puede cancelar. Hablá con la dueña.',
  VENDA_DE_TROCA: 'Esta venta es parte de un cambio. Usá Cancelar cambio.',
  DEVOLUCAO_DE_TROCA: 'Esta devolución es parte de un cambio. Usá Cancelar cambio.',
  CREDITO_NAO_ENCONTRADO:
    'No encontramos este crédito del cliente. Actualizá la pantalla e intentá de nuevo.',
  CREDITO_DE_OUTRO_CLIENTE:
    'Este crédito es de otro cliente. Usá solo el crédito del cliente de esta venta.',
  CREDITO_SEM_SALDO: 'Este crédito ya se usó todo o fue cancelado. Elegí otra forma de pago.',
  CREDITO_ACIMA_DO_SALDO:
    'El valor pasa de lo que queda en este crédito. Bajá el valor y pagá el resto de otra forma.',
  CREDITO_REPETIDO: 'El mismo crédito aparece más de una vez. Usá el crédito una sola vez.',
  CREDITO_SEM_ID: 'Elegí el crédito del cliente que se usó en el pago.',
  CREDITO_EM_OUTRA_FORMA: 'El crédito solo vale con la forma de pago crédito del cliente.',
  PAGO_SEM_DIFERENCA:
    'La pieza nueva vale menos que la devuelta: no hay diferencia a pagar. Lo que sobra queda de crédito.',
  // --- Cuotas y cobros ---
  RECEBIMENTO_ACIMA_DO_EM_ABERTO:
    'El valor pasa de lo que falta pagar en esta venta. Revisá el valor.',
  VENDA_SEM_VALOR_EM_ABERTO: 'Esta venta no tiene nada por cobrar.',
  RECEBIMENTO_JA_REGISTRADO_DIFERENTE:
    'Este cobro ya fue registrado con otros datos. Revisá los cobros antes de registrar de nuevo.',
  RECEBIMENTO_NAO_ENCONTRADO:
    'No encontramos este cobro. Actualizá la pantalla e intentá de nuevo.',
  RECEBIMENTO_JA_ESTORNADO: 'Este cobro ya fue anulado y no puede cambiar.',
  ESTORNO_DE_RECEBIMENTO_SO_DA_DONA:
    'Solo se puede anular el cobro el mismo día. Pedile a la dueña que lo anule.',
  PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA:
    'Este pago se hizo en el momento de la venta. Para corregirlo, anulá la venta.',
  PARCELAMENTO_SEM_SALDO: 'Esta venta ya está pagada. No hay cuotas.',
  VENCIMENTO_NO_PASSADO: 'El primer vencimiento no puede ser antes del día de la venta.',
  VENCIMENTO_LONGE_DEMAIS:
    'El primer vencimiento está demasiado lejos. Elegí una fecha hasta un año después de la venta.',
  MOTIVO_DO_ESTORNO_DO_RECEBIMENTO: 'Escribí el motivo de anular el cobro.',
  // --- Acerto ---
  ACERTO_NAO_ENCONTRADO: 'No encontramos este acerto. Actualizá la lista e intentá de nuevo.',
  ACERTO_NAO_ENCONTRADO_NO_COMPUTADOR:
    'No encontramos este acerto en esta computadora. Actualizá la pantalla e intentá de nuevo.',
  ACERTO_EM_ANDAMENTO: 'Ya tenés un acerto en marcha. Terminá ese acerto antes de empezar otro.',
  ACERTO_NAO_ESTA_EM_ANDAMENTO:
    'Este acerto ya terminó o fue deshecho. Abrí la lista de acertos para ver cómo quedó.',
  ACERTO_DA_SEMANA_JA_EXISTE: 'Ya tenés acerto en esta semana.',
  SEM_MALETA_PARA_ACERTO: 'No tenés maleta para hacer el acerto ahora. Hablá con la dueña.',
  ENVIOS_AGUARDANDO:
    'Todavía hay ventas en esta computadora esperando para ser enviadas. Conectate a internet, esperá a que termine el envío e intentá de nuevo.',
  ACERTO_JA_FECHADO: 'Este acerto ya fue cerrado. Abrí la lista de acertos para ver cómo quedó.',
  LEITURA_DEIXA_CONTAGEM_NEGATIVA:
    'No se pueden sacar más piezas de las que se contaron. Revisá el conteo.',
  // --- Computador e envio ---
  APARELHO_NAO_LIBERADO:
    'Esta computadora todavía no fue habilitada por la dueña. Avisale a la dueña para que la habilite.',
  APARELHO_SUBSTITUIDO:
    'La dueña cambió esta computadora por otra. Usá la computadora nueva para vender.',
  APARELHO_BLOQUEADO: 'La dueña desconectó esta computadora de la tienda. Hablá con la dueña.',
  APARELHO_DE_OUTRA_PESSOA:
    'Esta computadora ya la usa otro vendedor. Cada vendedor usa su propia computadora. Hablá con la dueña.',
  APARELHO_NAO_PODE_SER_LIBERADO:
    'Esta computadora fue bloqueada o cambiada y no puede volver a vender. Entrá en la computadora que te dio la dueña.',
  FORMATO_NAO_ACEITO:
    'Este envío se hizo con una versión vieja de la aplicación y no se pudo aceptar. La dueña ya fue avisada.',
  CURSOR_INVALIDO:
    'Los datos de esta computadora se van a bajar de nuevo. Tus ventas guardadas siguen guardadas.',
  ENVIO_EM_ANDAMENTO: 'Tus ventas ya se están enviando. Esperá un instante.',
  LOTE_GRANDE_DEMAIS:
    'Muchas ventas de una vez. El Corona Vendas va a enviar en partes más chicas.',
  DEPENDE_DE_ENVIO_RECUSADO:
    'Esta venta depende de otro registro que no fue aceptado. La dueña ya fue avisada.',
  // --- Só no computador (recusas locais) ---
  PECA_FORA_DA_MALETA: 'Esta pieza no consta en tu maleta.',
  SEM_SALDO: 'Ya no hay piezas de esta en tu maleta. Revisá la cantidad.',
  DESCONTO_MAIOR_QUE_A_PECA: 'El descuento es mayor que el valor de la pieza.',
  PAGO_PASSA_DO_TOTAL: 'El valor pagado pasa del total de la venta. Revisá los pagos.',
  PAGAMENTO_SEM_VALOR: 'El valor del pago tiene que ser mayor que cero.',
  CLIENTE_JA_CADASTRADO: 'Este cliente ya está registrado.',
  CLIENTE_NAO_ENCONTRADO:
    'No encontramos este cliente en esta computadora. Actualizá la pantalla e intentá de nuevo.',
  MALETA_NAO_ENCONTRADA:
    'No encontramos esta maleta en esta computadora. Actualizá la pantalla e intentá de nuevo.',
  PRAZO_ESGOTADO:
    'Esta computadora estuvo muchos días sin internet. Conectate a internet para hacer ventas nuevas.',
  RELOGIO_ERRADO:
    'La fecha de esta computadora parece estar mal. Revisá la fecha y la hora de la computadora y conectate a internet para continuar.',
  COMPUTADOR_DESLIGADO: 'Esta computadora ya no puede registrar ventas. Hablá con la dueña.',
  MALETA_SEM_CONFERENCIA: 'Esta maleta todavía no fue controlada.',
  CONSULTA_SO_COM_INTERNET: 'Esta consulta no se hace en la computadora.',
  SEM_BANCO_LOCAL:
    'Esta computadora todavía no está lista para guardar tus ventas. Conectate a internet y entrá de nuevo.',
  FORA_DO_PRAZO: 'Pasó el plazo de la garantía. Pedile autorización a la dueña.',
  ACIMA_DO_VENDIDO: 'Esta cantidad pasa de lo que el cliente compró. Revisá la cantidad.',
  MOTIVO_INVALIDO: 'Elegí el motivo de la devolución: desistimiento, defecto, no le quedó u otro.',
  OBSERVACAO_OBRIGATORIA: 'Contá el motivo de la devolución en el campo de observación.',
  TEXTO_LONGO: 'Usá como máximo 400 caracteres.',
  DEFEITO_COM_PECA_BOA: 'Con el motivo defecto, marcá las piezas como con defecto.',
  ESTADO_INVALIDO: 'Decí si la pieza volvió buena o con defecto.',
  QUANTIDADE_INVALIDA: 'Indicá una cantidad mayor que cero.',
  PECA_REPETIDA:
    'La misma pieza aparece más de una vez con el mismo estado. Juntá las cantidades en una sola línea.',
  PECA_FORA_DA_VENDA: 'Esta pieza no consta en esta venta.',
  FORMA_DO_REEMBOLSO:
    'Elegí cómo se devolvió el dinero: efectivo, transferencia, tarjeta o cheque.',
  SEM_PECAS: 'Indicá por lo menos una pieza devuelta.',
  SEM_PECAS_NOVAS: 'Indicá las piezas que se llevó el cliente.',
  MOTIVO_DO_CANCELAMENTO_DA_DEVOLUCAO: 'Escribí el motivo para cancelar la devolución.',
  MOTIVO_DO_CANCELAMENTO_DA_TROCA: 'Escribí el motivo para cancelar el cambio.',
  // --- Senha sem internet e banco local ---
  SENHA_ERRADA: 'Usuario o contraseña incorrectos. Revisá lo que escribiste e intentá de nuevo.',
  SENHA_BLOQUEADA:
    'La contraseña se escribió mal muchas veces. Conectate a internet y entrá de nuevo para continuar.',
  SEM_DADOS:
    'Para entrar al Corona Vendas por primera vez hace falta internet. Conectate e intentá de nuevo.',
  BANCO_ILEGIVEL:
    'No pudimos abrir los datos de esta computadora. Conectate a internet para bajar todo de nuevo. Si había ventas sin enviar, avisale a la dueña.',
  MIGRACAO_EDITADA:
    'No se pudieron preparar los datos de esta computadora. Cerrá y abrí el Corona Vendas de nuevo. Si sigue igual, avisale a la dueña.',
  BANCO_MAIS_NOVO:
    'Este Corona Vendas es más viejo que los datos de esta computadora. Actualizá el Corona Vendas para continuar.',
  MIGRACAO_FALHOU:
    'No se pudieron preparar los datos de esta computadora. Cerrá y abrí el Corona Vendas de nuevo. Si sigue igual, avisale a la dueña.',
  DISCO_CHEIO: 'La computadora no tiene espacio. Liberá espacio e intentá de nuevo.',
});

/**
 * A mensagem para a pessoa: em espanhol, pelo código (sem código conhecido, a mensagem como veio); em português,
 * a própria mensagem da API ou do computador. Nada de código técnico na tela.
 */
export function mensagemDoErro(
  idioma: 'es' | 'pt',
  codigo: string | undefined,
  mensagem: string | undefined,
  padrao: string,
): string {
  if (idioma === 'es' && codigo && Object.prototype.hasOwnProperty.call(ERROS_ES, codigo)) {
    return ERROS_ES[codigo] as string;
  }
  return mensagem ?? padrao;
}
