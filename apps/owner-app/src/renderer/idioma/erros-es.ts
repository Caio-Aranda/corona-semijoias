/**
 * Mensajes de error de la API en español, por código (`CodigoErro` del servidor).
 * Si el código no está aquí, la pantalla muestra el texto que mandó la API.
 * En portugués se usa siempre el texto de la API.
 */
const SOPORTE = 'Si sigue así, hable con el soporte del sistema.';
const ACTUALIZAR = 'Actualice el programa e intente de nuevo.';

export const ERROS_EM_ESPANHOL: Record<string, string> = {
  ERRO_INTERNO: `Algo salió mal. Intente de nuevo. ${SOPORTE}`,
  ROTA_NAO_ENCONTRADA: `No encontramos lo que pidió. ${ACTUALIZAR} ${SOPORTE}`,
  DADOS_INVALIDOS: 'Algunos datos no están correctos. Revise lo que completó e intente de nuevo.',
  PEDIDO_INVALIDO: `No se pudo entender el pedido. ${ACTUALIZAR} ${SOPORTE}`,
  SEM_PERMISSAO: `Usted no tiene acceso a esta parte del sistema. ${ACTUALIZAR} ${SOPORTE}`,
  LOGIN_INVALIDO:
    'No se pudo entrar. Usuario o contraseña incorrectos. Revise lo que escribió e intente de nuevo.',
  USUARIO_DESATIVADO:
    'Su acceso fue desactivado. Hable con la dueña de la tienda para volver a usar el sistema.',
  SESSAO_TERMINADA:
    'Su sesión terminó. Entre de nuevo con su usuario y su contraseña para continuar.',
  SENHA_TEMPORARIA_VENCIDA:
    'Su contraseña temporal venció. La contraseña que recibió solo vale por unos días, y ese plazo terminó.',
  TROCA_DE_SENHA_OBRIGATORIA:
    'Cree su contraseña nueva. La contraseña que recibió es temporal. Para continuar, cree una contraseña nueva que solo usted sepa.',
  SENHA_LONGA_DEMAIS: 'Esta contraseña es muy larga. Elija una más corta, de máximo 36 caracteres.',
  SENHA_FRACA:
    'La contraseña nueva todavía no sirve. Necesita tener al menos 8 caracteres, con letras y números.',
  SENHA_IGUAL_A_TEMPORARIA:
    'La contraseña nueva tiene que ser distinta de la temporal. Elija otra.',
  LOGIN_JA_USADO: 'Este usuario ya existe. Elija otro, por ejemplo agregando el apellido.',
  LOGIN_FORA_DO_FORMATO:
    'Este usuario no se puede usar. Use de 3 a 40 letras sin tilde, números o punto, sin espacios. Por ejemplo: maria.silva.',
  USUARIO_NAO_ENCONTRADO:
    'No encontramos a esta persona en el registro. Actualice la pantalla e intente de nuevo.',
  APP_TIPO_INVALIDO: `No se pudo entender el pedido. ${ACTUALIZAR} ${SOPORTE}`,
  APP_DESATUALIZADO: 'Su programa está desactualizado. Actualícelo para seguir usándolo.',
  APP_INCOMPATIVEL:
    'Este no es el programa de su acceso. Abra el otro programa de Corona Semijoias.',
  NAO_REDEFINIR_A_PROPRIA_SENHA:
    'Usted no puede cambiar su propia contraseña por aquí. Pídaselo a otra persona administradora o hable con el soporte del sistema.',
  NAO_DESATIVAR_A_SI_MESMA:
    'Usted no puede inactivar su propio acceso. Pídale a otra persona administradora que lo haga.',
  NAO_MUDAR_O_PROPRIO_ACESSO:
    'Usted no puede cambiar su propio tipo de acceso. Pídale a otra persona administradora que lo haga.',
  ULTIMA_ADMINISTRADORA:
    'Tiene que seguir existiendo al menos una persona administradora con acceso. Dé el acceso de administradora a otra persona antes de hacer este cambio.',
  CADASTRO_NAO_ENCONTRADO:
    'No encontramos este registro. Actualice la pantalla e intente de nuevo.',
  CADASTRO_JA_EXISTE: 'Este registro ya existe. Revise la lista o use otro nombre o código.',
  CADASTRO_ALTERADO:
    'Este registro fue cambiado por otra persona. Actualice la pantalla y haga el cambio de nuevo.',
  SEMANA_SOBREPOSTA:
    'Estas fechas ya forman parte de otra semana. Elija días que no estén en otra semana.',
  CADASTRO_EM_USO:
    'Este registro está en uso. Cambie la plaza o la restricción de los vendedores que la usan e intente de nuevo.',
  ARQUIVO_TIPO_NAO_ACEITO:
    'Este tipo de archivo no se puede enviar. Envíe una foto (JPG, PNG o WebP) o un archivo PDF.',
  CODIGO_JA_USADO:
    'Este código ya está en uso por otro producto. Use otro código o abra el producto que ya lo usa.',
  CADASTRO_EM_USO_POR_PRODUTOS:
    'Este registro está en uso por productos. Cambie la categoría o el proveedor de esos productos e intente de nuevo.',
  ARQUIVO_GRANDE_DEMAIS:
    'Este archivo es demasiado grande. Envíe un archivo de hasta 10 MB. Si es una foto, sáquela con menos calidad o reduzca el tamaño antes de enviar.',
  FOTO_TIPO_NAO_ACEITO:
    'El sistema no acepta esta foto. Envíe una foto en formato JPG, PNG o WebP.',
  FOTO_NAO_LIDA:
    'No se pudo abrir esta foto. El archivo puede estar dañado. Saque o guarde la foto de nuevo y envíela otra vez.',
  FOTO_GRANDE_DEMAIS_EM_PONTOS:
    'Esta foto es demasiado grande en tamaño de imagen. Reduzca la foto (hasta 25 millones de puntos, por ejemplo 5000 por 5000) y envíela de nuevo.',
  ESTOQUE_INSUFICIENTE:
    'No hay piezas suficientes en el stock. Revise la cantidad o reciba la compra antes.',
  LANCAMENTO_NAO_ENCONTRADO:
    'No encontramos este registro de movimiento. Actualice la pantalla e intente de nuevo.',
  LANCAMENTO_JA_DESFEITO: 'Este movimiento ya fue deshecho.',
  LANCAMENTO_NAO_PODE_SER_DESFEITO:
    'Este movimiento ya deshizo otro y no se puede deshacer. Si necesita, haga un movimiento nuevo.',
  FORMATO_NAO_ACEITO:
    'Este envío se hizo con una versión antigua del programa y no se pudo aceptar. La dueña ya fue avisada.',
  LANCAMENTO_JA_REGISTRADO_DIFERENTE:
    'Este movimiento ya fue registrado con otros datos. Actualice la pantalla, revise el historial y vuelva a registrarlo si necesita.',
  COMPRA_NAO_ENCONTRADA: 'No encontramos esta compra. Actualice la pantalla e intente de nuevo.',
  COMPRA_JA_REGISTRADA_DIFERENTE:
    'Esta compra ya fue registrada con otros datos. Revise la lista de compras antes de registrar de nuevo.',
  COMPRA_JA_DESFEITA: 'Esta compra ya fue deshecha.',
  COMPRA_NAO_PODE_SER_DESFEITA:
    'No se puede deshacer esta compra porque ya salieron piezas de ella.',
  REMESSA_NAO_ENCONTRADA: 'No encontramos esta maleta. Actualice la pantalla e intente de nuevo.',
  REMESSA_JA_REGISTRADA_DIFERENTE:
    'Esta maleta ya fue registrada con otros datos. Revise la lista de maletas antes de armar de nuevo.',
  REMESSA_NAO_EDITAVEL:
    'Esta maleta ya fue enviada y no se puede cambiar. Para corregir, haga una maleta nueva.',
  VENDEDOR_NAO_RECEBE_MALETA: 'Este vendedor no puede recibir maleta ahora.',
  LIMITE_DE_CONSIGNACAO_NAO_DEFINIDO: 'Defina el límite de este vendedor antes de armar la maleta.',
  LIMITE_DE_CONSIGNACAO_EXCEDIDO:
    'El valor de la maleta pasa el límite de consignación del vendedor.',
  PECA_FORA_DA_MALETA: 'Esta pieza está desactivada o fue eliminada y no puede ir a la maleta.',
  DEVOLUCAO_MAIOR_QUE_O_SALDO:
    'Hay menos piezas en la maleta de lo que se informó. Revise la cantidad.',
  MALETA_JA_MOVIMENTADA:
    'Esta maleta ya tuvo piezas devueltas o vendidas y no se puede cancelar. Deshaga las devoluciones antes, si fue un error.',
  PECA_ESTA_EM_MALETA:
    'Esta pieza todavía está con un vendedor en una maleta y no se puede eliminar. Reciba la devolución de las piezas antes.',
  DEVOLUCAO_NAO_PODE_SER_DESFEITA:
    'No se puede deshacer esta devolución porque las piezas ya salieron del stock de la tienda.',
  NOTA_SO_DEPOIS_DO_ENVIO:
    'La nota solo queda lista después de que se envíe la maleta. Envíe la maleta e intente de nuevo.',
  CONTAGEM_NAO_ENCONTRADA:
    'No encontramos este conteo del stock. Actualice la pantalla e intente de nuevo.',
  CONTAGEM_JA_REGISTRADA_DIFERENTE:
    'Este conteo ya fue iniciado con otros datos. Revise la lista de conteos antes de empezar de nuevo.',
  CONTAGEM_JA_EM_ANDAMENTO:
    'Ya hay un conteo en curso con piezas de esta selección. Cierre o cancele ese conteo antes de empezar otro.',
  CONTAGEM_ENCERRADA:
    'Este conteo ya fue cerrado y no se puede cambiar. Empiece un conteo nuevo si necesita.',
  LEITURA_JA_REGISTRADA_DIFERENTE:
    'Esta lectura ya fue registrada con otros datos. Actualice la pantalla y revise la cantidad contada.',
  CONTAGEM_NAO_PODE_FECHAR:
    'No se puede cerrar este conteo porque el stock de una pieza quedaría menor que cero.',
  VENDA_NAO_ENCONTRADA: 'No encontramos esta venta. Actualice la pantalla e intente de nuevo.',
  VENDEDOR_DESATIVADO: 'Este vendedor está desactivado. Hable con la dueña.',
  VENDA_JA_REGISTRADA_DIFERENTE:
    'Esta venta ya fue registrada con otros datos. Revise sus ventas antes de registrar de nuevo.',
  VENDA_SEM_SALDO: 'No hay piezas suficientes en su maleta para esta venta.',
  DESCONTO_ACIMA_DO_LIMITE: 'El descuento pasa el límite que definió la dueña. Baje el descuento.',
  VENDA_JA_ESTORNADA: 'Esta venta ya fue anulada y no se puede cambiar.',
  ESTORNO_SO_DA_DONA: 'Solo se puede anular la venta el mismo día. Pídale a la dueña que la anule.',
  MALETA_NAO_ESTA_COM_VOCE: 'Esta maleta no está disponible para vender. Actualice la pantalla.',
  MALETA_EM_OUTRO_APARELHO:
    'Esta maleta ya está en otra computadora. Pídale a la dueña que libere esta computadora.',
  PECA_NAO_ENCONTRADA: 'Esta pieza no consta en su maleta.',
  MALETA_JA_CONFERIDA:
    'Esta maleta ya fue controlada. Actualice la pantalla para ver el resultado.',
  CLIENTE_CPF_JA_CADASTRADO:
    'Este cliente ya está registrado. Elija el cliente que ya existe, en vez de registrarlo de nuevo.',
  CLIENTE_JA_REGISTRADO_DIFERENTE:
    'Este cliente ya fue registrado con otros datos. Revise la lista de clientes antes de registrar de nuevo.',
  MALETA_NAO_CONFERIDA:
    'Esta maleta todavía no fue controlada. Controle todas las piezas de la maleta antes de vender.',
  DEVOLUCAO_NAO_ENCONTRADA:
    'No encontramos esta devolución. Actualice la pantalla e intente de nuevo.',
  DEVOLUCAO_JA_REGISTRADA_DIFERENTE:
    'Esta devolución ya fue registrada con otros datos. Revise las devoluciones antes de registrar de nuevo.',
  DEVOLUCAO_ACIMA_DO_VENDIDO:
    'Esta cantidad pasa lo que el cliente compró en esta venta. Revise la cantidad.',
  GARANTIA_VENCIDA: 'Pasó el plazo de la garantía. Pida autorización a la dueña.',
  VENDA_A_PRAZO_SEM_DEVOLUCAO: 'Esta venta tiene cuotas; la devolución de ella llega pronto.',
  RECEBIMENTO_ACIMA_DO_EM_ABERTO:
    'El valor pasa lo que falta pagar de esta venta. Revise el valor.',
  VENDA_SEM_VALOR_EM_ABERTO: 'Esta venta no tiene nada para cobrar.',
  RECEBIMENTO_JA_REGISTRADO_DIFERENTE:
    'Este cobro ya fue registrado con otros datos. Revise los cobros antes de registrar de nuevo.',
  RECEBIMENTO_NAO_ENCONTRADO:
    'No encontramos este cobro. Actualice la pantalla e intente de nuevo.',
  RECEBIMENTO_JA_ESTORNADO: 'Este cobro ya fue anulado y no se puede cambiar.',
  CONTA_DO_VENDEDOR_NAO_ENCONTRADA:
    'No encontramos esta cuenta a cobrar. Actualice la pantalla e intente de nuevo.',
  VENDEDOR_SEM_CONTA_EM_ABERTO: 'Este vendedor no tiene cuenta abierta. No hay nada para cobrar.',
  RECEBIMENTO_DO_VENDEDOR_ACIMA_DO_EM_ABERTO:
    'El valor pasa lo que el vendedor debe. Revise el valor.',
  BOLETO_PAGO_PRECISA_SER_INTEIRO:
    'El boleto pagado tiene que ser del valor entero de la cuenta del boleto. Elija la cuenta del boleto y el valor total.',
  PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO:
    'El valor recibido pasa lo que el vendedor debe en este acerto. Revise los valores.',
  DATA_DO_RESTANTE_OBRIGATORIA:
    'Elija la fecha de pago de lo que quedó debiendo y en cuántas cuotas.',
  PAGAMENTO_AO_VENDEDOR_INVALIDO:
    'El pago al vendedor no coincide con el acerto. Pague el valor entero o déjelo de crédito para el próximo acerto.',
  ESTORNO_DE_RECEBIMENTO_SO_DA_DONA:
    'Solo se puede anular el cobro el mismo día. Pídale a la dueña que lo anule.',
  PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA:
    'Este pago se hizo en el momento de la venta. Para corregir, anule la venta.',
  VENDA_COM_DEVOLUCAO: 'Esta venta ya tiene devolución. Cancele la devolución antes de anular.',
  MALETA_EM_ACERTO: 'Esta maleta está en acerto. Espere a que la dueña termine el acerto.',
  ACERTO_NAO_ENCONTRADO: 'No encontramos este acerto. Actualice la lista e intente de nuevo.',
  ACERTO_JA_REGISTRADO_DIFERENTE:
    'Este acerto ya fue registrado con otros datos. Revise la lista de acertos.',
  ACERTO_EM_ANDAMENTO:
    'Este vendedor ya tiene un acerto en curso. Termine ese acerto antes de empezar otro.',
  ACERTO_NAO_ESTA_EM_ANDAMENTO:
    'Este acerto ya terminó o fue deshecho. Abra la lista de acertos para ver cómo quedó.',
  ACERTO_DA_SEMANA_JA_EXISTE: 'Este vendedor ya tiene acerto en esta semana. Elija otra semana.',
  SEMANA_FECHADA: 'Esta semana ya está cerrada. Elija una semana abierta.',
  SEMANA_NAO_ENCONTRADA: 'Registre la semana antes de iniciar el acerto.',
  SEM_MALETA_PARA_ACERTO: 'Usted no tiene maleta para acertar ahora. Hable con la dueña.',
  ENVIOS_AGUARDANDO:
    'Todavía hay ventas en esta computadora esperando ser enviadas. Conéctese a internet, espere que termine el envío e intente de nuevo.',
  VENDEDOR_EM_ACERTO: 'Este vendedor está en acerto. Termine el acerto antes de enviar una maleta.',
  LEITURA_DEIXA_CONTAGEM_NEGATIVA:
    'No se pueden quitar más piezas de las que se contaron. Revise el conteo.',
  DESISTENCIA_JA_REGISTRADA_DIFERENTE:
    'Este acerto ya fue deshecho por otro pedido. Actualice la lista de acertos.',
  SOBRA_NO_ACERTO:
    'Contaron más piezas de lo esperado. Revise las ventas y el conteo antes de cerrar el acerto.',
  FECHAMENTO_JA_REGISTRADO_DIFERENTE:
    'Este acerto ya fue cerrado con otras decisiones. Abra la lista de acertos para ver cómo quedó.',
  ACERTO_JA_FECHADO: 'Este acerto ya fue cerrado. Abra la lista de acertos para ver cómo quedó.',
  REVISAO_PENDENTE_NO_ACERTO:
    'Hay una devolución esperando su decisión. Acéptela o no antes de cerrar el acerto.',
  FECHAMENTO_DESATUALIZADO: 'Llegaron cosas nuevas a este acerto. Revise de nuevo antes de cerrar.',
  SEM_COTACAO_PARA_O_ACERTO: 'Busque la cotización del día antes de cerrar el acerto.',
  PENDENCIA_NAO_ENCONTRADA: 'No encontramos esta pendiente. Actualice la lista e intente de nuevo.',
  PENDENCIA_JA_RESOLVIDA: 'Esta pendiente ya fue resuelta. Actualice la lista.',
  PENDENCIA_RESOLUCAO_DIFERENTE:
    'Esta pendiente ya fue resuelta de otra manera. Actualice la lista.',
  CANCELAR_DEVOLUCAO_SO_DA_DONA:
    'Solo se puede cancelar la devolución el mismo día. Pídale a la dueña que la cancele.',
  DEVOLUCAO_JA_CANCELADA: 'Esta devolución ya fue cancelada y no se puede cambiar.',
  DEVOLUCAO_NAO_PODE_SER_CANCELADA:
    'La dueña ya decidió sobre esta devolución y ya no se puede cancelar.',
  PECA_DA_DEVOLUCAO_JA_VENDIDA:
    'La pieza de esta devolución ya se vendió de nuevo y la devolución no se puede cancelar. Hable con la dueña.',
  AUTORIZACAO_NAO_ENCONTRADA:
    'No encontramos este pedido de autorización. Actualice la pantalla e intente de nuevo.',
  AUTORIZACAO_JA_REGISTRADA_DIFERENTE:
    'Este pedido de autorización ya fue hecho con otros datos. Revise los pedidos antes de pedir de nuevo.',
  AUTORIZACAO_DESNECESSARIA:
    'Esta devolución está dentro del plazo. Registre la devolución directo.',
  AUTORIZACAO_JA_PEDIDA:
    'Ya hay un pedido de autorización para esta venta. Espere la respuesta de la dueña o use la autorización aprobada.',
  AUTORIZACAO_JA_DECIDIDA:
    'La dueña ya decidió este pedido de autorización y la decisión no cambia.',
  AUTORIZACAO_NAO_APROVADA:
    'Este pedido de autorización todavía no fue aprobado o fue negado. Espere la respuesta de la dueña.',
  AUTORIZACAO_JA_USADA:
    'Esta autorización ya se usó en una devolución. Pida otra autorización a la dueña.',
  AUTORIZACAO_VENCIDA:
    'La autorización vale 7 días y ya venció. Pida otra autorización a la dueña.',
  AUTORIZACAO_NAO_CONFERE:
    'Use la misma venta, el mismo motivo y como máximo las piezas que la dueña autorizó.',
  DEVOLUCAO_PRECISA_DE_AUTORIZACAO:
    'Esta devolución pasa el valor que la dueña aprueba antes. Pida autorización a la dueña.',
  DEVOLUCAO_NAO_ESTA_EM_REVISAO:
    'Solo la devolución enviada a revisión de la dueña se puede mantener o no aceptar.',
  DEVOLUCAO_JA_REVISADA: 'La dueña ya decidió sobre esta devolución y la decisión no cambia.',
  TROCA_NAO_ENCONTRADA: 'No encontramos este cambio. Actualice la pantalla e intente de nuevo.',
  TROCA_JA_REGISTRADA_DIFERENTE:
    'Este cambio ya fue registrado con otros datos. Revise los cambios antes de registrar de nuevo.',
  TROCA_NAO_CONFERE:
    'Los valores de este cambio no cerraron y no se guardó nada. Intente de nuevo; si sigue, hable con la dueña.',
  DEVOLUCAO_NAO_CONFERE:
    'Los valores de esta devolución no cerraron y no se guardó nada. Intente de nuevo; si sigue, hable con la dueña.',
  TROCA_JA_CANCELADA: 'Este cambio ya fue cancelado y no se puede cambiar.',
  TROCA_NAO_PODE_SER_CANCELADA: 'La dueña ya decidió sobre este cambio y ya no se puede cancelar.',
  CANCELAR_TROCA_SO_DA_DONA:
    'Solo se puede cancelar el cambio el mismo día. Pídale a la dueña que lo cancele.',
  CREDITO_DA_TROCA_JA_USADO:
    'El crédito que sobró de este cambio ya se usó en otra compra y el cambio no se puede cancelar. Hable con la dueña.',
  VENDA_DE_TROCA: 'Esta venta forma parte de un cambio. Use Cancelar cambio.',
  DEVOLUCAO_DE_TROCA: 'Esta devolución forma parte de un cambio. Use Cancelar cambio.',
  CREDITO_NAO_ENCONTRADO:
    'No encontramos este crédito del cliente. Actualice la pantalla e intente de nuevo.',
  CREDITO_DE_OUTRO_CLIENTE:
    'Este crédito es de otro cliente. Use solo el crédito del cliente de esta venta.',
  CREDITO_SEM_SALDO:
    'Este crédito ya se usó por completo o fue cancelado. Elija otra forma de pago.',
  CREDITO_ACIMA_DO_SALDO:
    'El valor pasa lo que queda en este crédito. Baje el valor y pague el resto de otra forma.',
  CREDITO_JA_USADO:
    'El crédito de esta devolución ya se usó en otra compra y la devolución no se puede cancelar. Hable con la dueña.',
  CREDITO_JA_TRANSFERIDO_DIFERENTE:
    'Esta transferencia de crédito ya se hizo con otros datos. Revise los créditos antes de transferir de nuevo.',
  CREDITO_SEM_SALDO_NAO_ENCONTRADO:
    'No encontramos esta venta con crédito para revisar. Actualice la pantalla e intente de nuevo.',
  CREDITO_SEM_SALDO_NAO_ESTA_PARA_REVISAO:
    'La dueña ya decidió sobre esta venta con crédito y la decisión no cambia.',
  APARELHO_NAO_LIBERADO:
    'Esta computadora todavía no fue habilitada por la dueña. Avise a la dueña para que habilite esta computadora.',
  APARELHO_SUBSTITUIDO:
    'La dueña cambió esta computadora por otra. Use la computadora nueva para vender.',
  APARELHO_BLOQUEADO: 'La dueña desconectó esta computadora de la tienda. Hable con la dueña.',
  APARELHO_DE_OUTRA_PESSOA:
    'Esta computadora ya la usa otro vendedor. Cada vendedor usa su propia computadora. Hable con la dueña.',
  APARELHO_NAO_PODE_SER_LIBERADO:
    'Esta computadora fue bloqueada o cambiada y no puede volver a vender. Pídale al vendedor que entre en la computadora que va a usar y habilite esa.',
  APARELHO_NAO_ENCONTRADO:
    'No encontramos esta computadora. Actualice la pantalla e intente de nuevo.',
  CONTAS_A_PAGAR_JA_GERADAS:
    'Las cuentas de esta compra ya fueron generadas. Para rehacer las cuotas, deshaga la compra y regístrela de nuevo.',
  COMPRA_SEM_VALOR_A_PAGAR:
    'Esta compra no tiene valor a pagar, entonces no hay cuentas para generar.',
  CONTA_A_PAGAR_NAO_ENCONTRADA:
    'No encontramos esta cuenta a pagar. Actualice la pantalla e intente de nuevo.',
  CONTA_A_PAGAR_CANCELADA:
    'Esta cuenta fue cancelada porque se deshizo la compra. No hay nada que pagar.',
  PAGAMENTO_A_FORNECEDOR_NAO_ENCONTRADO:
    'No encontramos este pago. Actualice la pantalla e intente de nuevo.',
  PAGAMENTO_A_FORNECEDOR_JA_REGISTRADO_DIFERENTE:
    'Este pago ya fue registrado con otros datos. Revise la cuenta antes de registrar de nuevo.',
  PAGAMENTO_A_FORNECEDOR_JA_ESTORNADO: 'Este pago ya fue anulado y no se puede cambiar.',
  PAGAMENTO_ACIMA_DO_SALDO: 'El valor pasa lo que falta pagar de esta cuenta.',
  FECHAMENTO_DA_SEMANA_JA_REGISTRADO_DIFERENTE:
    'Este cierre ya fue registrado para otra semana. Revise la lista de semanas.',
  DATA_EM_SEMANA_FECHADA: 'Esa semana ya fue cerrada. Registre con la fecha de hoy.',
  LANCAMENTO_DO_CAIXA_NAO_ENCONTRADO:
    'No encontramos este movimiento. Actualice la pantalla e intente de nuevo.',
  LANCAMENTO_DO_CAIXA_JA_REGISTRADO_DIFERENTE:
    'Este movimiento ya fue registrado con otros datos. Revise la caja antes de registrar de nuevo.',
  LANCAMENTO_JA_ESTORNADO: 'Este movimiento ya fue anulado y no se puede cambiar.',
  LANCAMENTO_DO_CAIXA_E_ESTORNO:
    'Este movimiento ya es una anulación y no se puede anular de nuevo. Haga un movimiento nuevo.',
  LANCAMENTO_AUTOMATICO:
    'Este movimiento vino de un pago. Deshaga el pago para que salga de la caja.',
  CATEGORIA_DO_CAIXA_NAO_ENCONTRADA:
    'No encontramos esta categoría. Actualice la pantalla e intente de nuevo.',
  CATEGORIA_DO_CAIXA_JA_EXISTE: 'Ya existe una categoría con este nombre. Use otro nombre.',
  CATEGORIA_DO_CAIXA_DO_SISTEMA: 'Esta categoría es del sistema y no se puede cambiar.',
  SEMANA_FECHADA_NAO_MUDA: 'Esta semana ya fue cerrada y no cambia más.',
  SEMANA_NAO_TERMINOU: 'Esta semana todavía no terminó. Cierre la semana después de su último día.',
  SEMANA_COM_ACERTO_EM_ANDAMENTO:
    'Hay un acerto en curso en esta semana. Termine o desista del acerto antes de cerrar la semana.',
  CONFERENCIA_EM_ANDAMENTO:
    'Ya hay una verificación en marcha. Espere un poco y actualice la pantalla.',
  CONFERENCIA_NAO_ENCONTRADA:
    'No encontramos esta verificación. Vuelva a la lista y abra una de las que aparecen.',
  CURSOR_INVALIDO:
    'Los datos de esta computadora se van a bajar de nuevo. Sus ventas guardadas siguen guardadas.',
  ENVIO_EM_ANDAMENTO: 'Sus ventas ya se están enviando. Espere un instante.',
  LOTE_GRANDE_DEMAIS:
    'Demasiadas ventas de una vez. Corona Ventas va a enviar en partes más chicas.',
  DEPENDE_DE_ENVIO_RECUSADO:
    'Esta venta depende de otro registro que no fue aceptado. La dueña ya fue avisada.',
  CLIENTE_DE_OUTRO_VENDEDOR:
    'Este cliente lo atiende otro vendedor. Solo quien atiende al cliente cambia su registro. Hable con la dueña.',
  ENVIO_NAO_ENCONTRADO: 'No encontramos este envío. Actualice la pantalla e intente de nuevo.',
  ENVIO_JA_RESOLVIDO: 'Este envío ya fue resuelto. Actualice la pantalla para ver cómo quedó.',
  ENVIO_SEM_DADOS:
    'Este envío es muy antiguo para repetirse. Los detalles completos se guardan por 90 días. Descártelo.',
};

/** Texto de ACESSO_BLOQUEADO: el servidor trae los minutos en su mensaje. */
export function erroDeBloqueio(mensagemDaApi: string | undefined): string {
  const minutos = /(\d+)/.exec(mensagemDaApi ?? '')?.[1];
  return minutos
    ? `Acceso bloqueado por unos minutos. Usted se equivocó varias veces con la contraseña. Por seguridad, espere ${minutos} minutos e intente de nuevo.`
    : 'Acceso bloqueado por unos minutos. Por seguridad, espere e intente de nuevo.';
}
