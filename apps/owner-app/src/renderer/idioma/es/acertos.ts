import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DE_ACERTOS } from '../../textos-acertos';

const pecas = (n: number) => (n === 1 ? '1 pieza' : `${n} piezas`);

export const ES_TEXTOS_DE_ACERTOS: NoMesmoFormato<typeof PT_TEXTOS_DE_ACERTOS> = {
  menu: {
    grupo: 'Acerto',
    acertos: 'Acertos',
    pendencias: 'Pendientes',
    comissao: 'Comisión y meta',
  },
  titulo: 'Acertos',
  fazerAcerto: 'Hacer acerto',
  pecas,
  situacoes: {
    EM_ACERTO: 'En acerto',
    ACERTADO: 'Acertado',
    DESISTIDO: 'Desistido',
  },
  lista: {
    comecar: 'Empezar acerto',
    legenda: 'Acertos de los vendedores',
    vazia: 'Ningún acerto por aquí. Use "Empezar acerto" para hacer el primero.',
    filtros: {
      vendedor: 'Vendedor',
      todosVendedores: 'Todos los vendedores',
      situacao: 'Estado',
      todasSituacoes: 'Todos los estados',
    },
    colunas: {
      vendedor: 'Vendedor',
      semana: 'Semana',
      maletas: 'Maletas',
      comecou: 'Empezó el',
      situacao: 'Estado',
      acoes: 'Qué hacer',
    },
    semana: (numero: number | string) => `Semana ${numero}`,
    abrir: 'Abrir',
    abrirDe: (vendedor: string, semana: number | string) =>
      `Abrir acerto de ${vendedor}, semana ${semana}`,
    daVendedora: 'Pedido por el vendedor',
  },
  comecar: {
    titulo: 'Empezar acerto',
    ajuda:
      'Elija el vendedor. El acerto cuenta lo que volvió en su maleta y cierra el dinero de la semana.',
    vendedor: 'Vendedor',
    escolhaVendedor: 'Elija el vendedor',
    vendedorAviso: 'Elija el vendedor.',
    maisOpcoes: 'Más opciones',
    menosOpcoes: 'Menos opciones',
    semana: 'Semana',
    semanaPadrao: 'La semana correcta (el sistema elige)',
    semanaAjuda: 'Solo elija una semana si el acerto es de otra semana.',
    nomeDaSemana: (numero: string) => `Semana ${numero}`,
    semMaleta:
      'Este vendedor no tiene maleta con él: el acerto va a tener solo ventas y devoluciones antiguas.',
    comecar: 'Empezar',
    comecando: 'Empezando...',
    voltar: 'Volver a la lista',
  },
  ficha: {
    voltar: 'Volver a la lista',
    titulo: (vendedor: string, semana: number | string) =>
      `Acerto de ${vendedor}, semana ${semana}`,
    comecouEm: (quando: string) => `Empezó el ${quando}.`,
    pedidoPeloVendedor: 'El vendedor pidió este acerto.',
    maletas: 'Maletas de este acerto',
    maleta: (numero: string) => (numero ? `Maleta ${numero}` : 'Maleta'),
    desistido: (motivo: string) => `Este acerto fue deshecho. Motivo: ${motivo}`,
    desistidoSemMotivo: 'Este acerto fue deshecho.',
    naoAbriu: 'No se pudo abrir el acerto. Intente de nuevo.',
  },
  contagem: {
    titulo: 'Conteo de las piezas',
    ajuda:
      'Lea cada pieza que volvió en la maleta. El sistema suma y compara con lo que salió. Lea de nuevo para sumar una más.',
    estado: 'Cómo está la pieza',
    boa: 'Pieza buena',
    defeito: 'Con defecto',
    quantidade: 'Cantidad',
    quantidadeAviso: 'Use una cantidad entera, de 1 en adelante.',
    leitor: {
      titulo: 'Leer una pieza',
      adicionar: 'Contar',
      ajuda: 'El lector ya presiona Enter solo. Leer la misma pieza de nuevo cuenta una más.',
    },
    legenda: 'Piezas contadas en este acerto',
    vazia: 'Todavía no hay piezas contadas. Lea la primera pieza arriba.',
    colunas: {
      peca: 'Pieza',
      estado: 'Cómo está',
      quantidade: 'Contadas',
      acoes: 'Qué hacer',
    },
    desfazer: 'Deshacer lectura',
    desfazerDe: (nome: string) => `Deshacer lectura de ${nome}`,
    vendaNova: (n: number) =>
      n === 1
        ? 'Llegó una venta nueva: revise el conteo. 1 venta llegó después de empezar el acerto.'
        : `Llegaron ventas nuevas: revise el conteo. ${n} ventas llegaron después de empezar el acerto.`,
    envios: (n: number) =>
      `La computadora del vendedor todavía tiene ${n === 1 ? '1 envío' : `${n} envíos`} para mandar. Pídale que se conecte a internet antes de cerrar.`,
    leituras: 'Últimas lecturas',
    leitura: (
      peca: string,
      qtd: number,
      estado: string,
      nome: string,
      onde: string,
      quando: string,
    ) =>
      `${peca}: ${qtd > 0 ? '+' : ''}${qtd} (${estado}), por ${nome || 'alguien'} ${onde}, el ${quando}`,
    noComputadorDoVendedor: 'en la computadora del vendedor',
    noComputadorDaLoja: 'en la computadora de la tienda',
    semFoto: 'Sin foto',
    conferir: 'Revisar el acerto',
    desistir: 'Desistir del acerto',
  },
  previa: {
    titulo: 'Revisión del acerto',
    ajuda:
      'Vea lo que volvió, decida qué hacer con cada diferencia y revise el dinero. Los números son del sistema: cuando usted decide, cambian al instante.',
    voltar: 'Volver al conteo',
    calculando: 'Calculando...',
    naoLeu: 'No se pudo leer el acerto. Intente de nuevo.',
    tentarDeNovo: 'Intentar de nuevo',
    motivoPendente: 'Escriba el motivo para que el sistema rehaga los números.',
    pecas: {
      titulo: 'Piezas',
      legenda: 'Piezas del acerto',
      colunas: {
        peca: 'Pieza',
        saiu: 'Salió en la maleta',
        boa: 'Volvió buena',
        defeito: 'Volvió con defecto',
        faltou: 'Faltó',
        valorFalta: 'Valor del faltante',
      },
      vazia: 'Ninguna pieza en este acerto.',
    },
    sobra: {
      titulo: 'Sobró pieza: revise las ventas o registre el ajuste.',
      ajuda:
        'Contaron más piezas de las que salieron en la maleta. Corrija la venta equivocada en la maleta o haga el ajuste del stock. Solo se puede cerrar el acerto después de eso.',
      linha: (nome: string, esperado: number, contado: number) =>
        `${nome}: eran ${esperado}, se contaron ${contado}.`,
    },
    revisao: {
      titulo: 'Hay una devolución esperando su revisión. Revísela antes de cerrar el acerto.',
      botao: 'Revisar devoluciones',
    },
    faltas: {
      titulo: 'Qué hacer con lo que faltó',
      ajuda:
        'Cada pieza que no volvió se puede cobrar ahora, cobrar después o ser asumida por la tienda.',
      decisao: (nome: string) => `Qué hacer con el faltante de ${nome}`,
      motivo: (nome: string) => `Motivo de que la tienda asuma: ${nome}`,
      motivoAviso: 'Diga el motivo de que la tienda asuma.',
      linha: (quantidade: number, valor: string, situacao: string) =>
        `Faltó ${quantidade === 1 ? '1 pieza' : `${quantidade} piezas`} (${valor}). ${situacao}`,
      situacoes: {
        COBRADA: 'Se va a cobrar ahora.',
        ABERTA: 'Queda para después.',
        ASSUMIDA_PELA_LOJA: 'La tienda asume.',
      },
      semFalta: 'Con los defectos aprobados, esta pieza ya no tiene faltante.',
    },
    opcoesDeCobranca: {
      COBRAR_NO_ACERTO: 'Cobrar ahora',
      COBRAR_DEPOIS: 'Cobrar después',
      LOJA_ASSUME: 'La tienda asume',
    },
    defeitos: {
      titulo: 'Piezas con defecto',
      ajuda: 'Diga cuántas piezas aprueba como defecto. Las otras vuelven como pieza buena.',
      contadas: (n: number) => `Contadas con defecto: ${n}`,
      aprovar: (nome: string) => `Aprobar como defecto: ${nome}`,
      aviso: (max: number) => `Use un número de 0 hasta ${max}.`,
    },
    naoAceitas: {
      titulo: 'Lo que no fue aceptado',
      ajuda:
        'Devoluciones y créditos que usted no aceptó. Elija si el vendedor paga ahora, después o si la tienda asume.',
      decisao: (tipo: string, valor: string) =>
        tipo === 'CREDITO_SEM_SALDO_NAO_ACEITO'
          ? `Qué hacer con el crédito sin saldo no aceptado de ${valor}`
          : `Qué hacer con la devolución no aceptada de ${valor}`,
      motivo: (valor: string) => `Motivo de que la tienda asuma: ${valor}`,
    },
    antigas: {
      titulo: 'Pendientes de acertos anteriores',
      ajuda: 'Piezas que ya faltaron antes. Puede cobrar ahora o dejar para otro acerto.',
      decisao: (nome: string) => `Qué hacer con la pendiente antigua de ${nome}`,
      opcoes: { COBRAR_NO_ACERTO: 'Cobrar ahora', MANTER: 'Dejar para después' },
      linha: (nome: string, quantidade: number, valor: string) =>
        `${nome}: ${quantidade === 1 ? '1 pieza' : `${quantidade} piezas`} (${valor})`,
      umaPeca: 'Una pieza',
    },
    comissao: {
      titulo: 'Comisión y meta',
      vendidoNaSemana: (vendido: string, meta: string) =>
        `Vendido en la semana: ${vendido} de ${meta} (meta)`,
      faltaParaMeta: (guarani: string, real: string) => `Falta para la meta: ${guarani} (${real}).`,
      bateu: (percentual: string) => `Alcanzó la meta: comisión de ${percentual}.`,
      naoBateu: (percentual: string) => `Todavía no alcanzó la meta: comisión de ${percentual}.`,
      legenda: 'Comisión por venta',
      colunas: {
        venda: 'Venta',
        base: 'Valor',
        percentual: 'Comisión',
        valor: 'Comisión en reales',
      },
      venda: (n: number) => `Venta ${n}`,
      devolucao: (n: number) => `Devolución (venta ${n})`,
      vazia: 'Ninguna venta en este acerto.',
    },
    totais: {
      titulo: 'Totales del acerto',
      vendido: 'Vendido',
      descontos: 'Descuentos',
      devolvido: 'Devuelto',
      vendidoLiquido: 'Vendido después de los descuentos',
      comissao: 'Comisión del vendedor',
      faltasCobradas: 'Faltantes cobrados',
      liquido: 'Valor del acerto',
      creditoUsado: 'Crédito del vendedor usado',
      aPagar: 'A pagar ahora',
    },
    cotacao: {
      frase: (data: string) =>
        `Valores en Dólar y Guaraní por la cotización del ${data}. Vale el valor en reales.`,
      sem: 'Sin cotización: los valores aparecen solo en reales.',
    },
  },
  pagamento: {
    titulo: 'Pago del acerto',
    ajuda:
      'Diga cuánto pagó el vendedor ahora. Lo que sobre se vuelve cuenta a cobrar del vendedor, en cuotas. Los números cambian a medida que escribe.',
    pagouAgora: 'Cuánto pagó el vendedor ahora',
    dinheiro: 'Efectivo',
    transferencia: 'Transferencia (PIX)',
    ajudaDoValor: '',
    boleto: {
      titulo: 'Boleto',
      valor: 'Boleto: valor',
      vencimento: 'Boleto: vencimiento',
      ajuda:
        'El boleto se vuelve una cuenta del vendedor. Solo entra en la caja cuando usted registre que fue pagado.',
      semVencimento: 'Elija el vencimiento del boleto.',
    },
    devendo: {
      titulo: 'Lo que quedó debiendo',
      ajuda: 'Lo que el vendedor no pagó ahora se vuelve cuenta a cobrar, en cuotas.',
      parcelas: 'En cuántas cuotas',
      umaParcela: '1 cuota',
      parcelasDe: (n: number) => `${n} cuotas`,
      primeira: 'Fecha de la primera cuota',
      semData: 'Elija la fecha de pago de lo que quedó debiendo.',
      parcela: (numero: number, valor: string, data: string) =>
        `Cuota ${numero}: ${valor}, vence el ${data}`,
    },
    antigas: {
      titulo: 'Cuentas antiguas del vendedor',
      ajuda:
        'Si el vendedor quiere, pague cuotas de acertos anteriores junto. Esto no entra en el valor de este acerto.',
      conta: (numero: number, total: number, vencimento: string, emAberto: string) =>
        `Cuota ${numero} de ${total}, vence el ${vencimento}: falta pagar ${emAberto}`,
      pagar: (numero: number, total: number) => `Pagar ahora la cuota ${numero} de ${total}`,
      como: (numero: number) => `Cómo pagó la cuota ${numero}`,
    },
    aFavor: {
      titulo: 'Acerto a favor del vendedor',
      deve: (valor: string) => `La tienda le debe ${valor} al vendedor.`,
      credito: 'Guardar como crédito para el próximo acerto',
      pagarAgora: 'Pagar ahora',
      como: 'Cómo paga la tienda',
    },
    resumo: {
      recebido: 'Cobrado ahora',
      boleto: 'Boleto',
      ficouDevendo: 'Quedó debiendo',
      creditoDisponivel: 'Crédito del vendedor disponible',
      pagoAoVendedor: 'Pagado al vendedor ahora',
    },
  },
  fechar: {
    botao: 'Cerrar acerto',
    titulo: 'Cerrar acerto',
    texto: (vendedor: string) =>
      `¿Cerrar el acerto de ${vendedor}? Después de cerrado, el acerto ya no cambia.`,
    voltar: 'Volver y revisar',
    fechando: 'Cerrando...',
    fechado: 'Acerto cerrado.',
    semConexao: 'Sin internet. El acerto no se cerró. Intente de nuevo.',
    desatualizado: 'Los números cambiaron desde que lo abrió. Revise el acerto de nuevo.',
    erros: {
      FECHAMENTO_DESATUALIZADO:
        'Los números cambiaron desde que lo abrió. Revise el acerto de nuevo.',
      PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO:
        'El valor pagado pasa lo que el vendedor debe. Revise los valores.',
      DATA_DO_RESTANTE_OBRIGATORIA: 'Elija la fecha de pago de lo que quedó debiendo.',
      SEMANA_FECHADA:
        'La semana de este acerto ya está cerrada, por eso el acerto no se puede cerrar. Hable con el soporte.',
      DATA_EM_SEMANA_FECHADA:
        'La semana de este acerto ya está cerrada, por eso el acerto no se puede cerrar. Hable con el soporte.',
    },
  },
  fechada: {
    titulo: 'Cierre del acerto',
    fechadoEm: (quando: string) => `Cerrado el ${quando}.`,
    pecas: {
      defeitoAprovado: 'Defecto aprobado',
    },
    pendencias: {
      legenda: 'Pendientes de este acerto',
      vazia: 'Ninguna pendiente en este acerto.',
      colunas: {
        peca: 'Pieza',
        tipo: 'Qué es',
        quantidade: 'Cantidad',
        valor: 'Valor',
        situacao: 'Estado',
      },
      tipos: {
        FALTA: 'Pieza que faltó',
        DEFEITO: 'Pieza con defecto',
        DEVOLUCAO_NAO_ACEITA: 'Devolución no aceptada',
        CREDITO_SEM_SALDO_NAO_ACEITO: 'Crédito sin saldo no aceptado',
      },
      situacoes: {
        ABERTA: 'Abierta',
        COBRADA: 'Cobrada',
        ASSUMIDA_PELA_LOJA: 'La tienda asumió',
        ENCONTRADA: 'Pieza encontrada',
        APROVADA: 'Defecto aprobado',
        RECUSADA: 'Defecto rechazado',
      },
      semPeca: 'Valor sin pieza',
    },
    totais: {
      recebido: 'Cobrado en el acerto',
      ficouDevendo: 'Quedó debiendo',
      pagoAoVendedor: 'Pagado al vendedor',
    },
    recebimentos: {
      legenda: 'Lo que el vendedor pagó',
      vazia: 'El vendedor no pagó nada en este acerto.',
      colunas: { forma: 'Forma', valor: 'Valor', quando: 'Cuándo', situacao: 'Estado' },
      formas: { DINHEIRO: 'Efectivo', PIX: 'Transferencia (PIX)', BOLETO: 'Boleto' },
      desfeito: 'Deshecho',
    },
    contas: {
      legenda: 'Cuentas que generó el acerto',
      vazia: 'Este acerto no generó cuentas a cobrar.',
      colunas: {
        parcela: 'Cuota',
        valor: 'Valor',
        vence: 'Vence el',
        forma: 'Forma prevista',
        situacao: 'Estado',
      },
      parcela: (numero: number, total: number) => `${numero} de ${total}`,
      situacoes: {
        ABERTA: 'Abierta',
        PARCIAL: 'Pagada en parte',
        PAGA: 'Pagada',
        CANCELADA: 'Cancelada',
      },
    },
    imprimir: 'Imprimir acerto',
    gerando: 'Preparando el acerto...',
    pronta: (nome: string) => `El acerto ${nome} fue preparado. Elija dónde guardarlo.`,
    erroDoPdf: 'No se pudo preparar el acerto ahora. Intente de nuevo.',
    naoLeu: 'No se pudo leer el cierre de este acerto. Intente de nuevo.',
  },
  pendencias: {
    titulo: 'Pendientes del acerto',
    ajuda: 'Piezas que faltaron y valores que quedaron abiertos en los acertos de los vendedores.',
    cobrar:
      'La pendiente abierta aparece en el próximo acerto del vendedor, y es allí donde se cobra. Aquí usted solo resuelve las que no se van a cobrar.',
    legenda: 'Pendientes de los vendedores',
    vazia: 'Ninguna pendiente por aquí.',
    filtros: {
      vendedor: 'Vendedor',
      todosVendedores: 'Todos los vendedores',
      situacao: 'Estado',
      todasSituacoes: 'Todos los estados',
    },
    colunas: {
      vendedor: 'Vendedor',
      peca: 'Pieza',
      tipo: 'Qué es',
      quantidade: 'Cantidad',
      valor: 'Valor',
      situacao: 'Estado',
      acoes: 'Qué hacer',
    },
    resolver: 'Resolver',
    resolverDe: (nome: string) => `Resolver pendiente de ${nome}`,
    dialogo: {
      titulo: 'Resolver pendiente',
      texto: 'Elija cómo resolver. Una vez resuelta, la pendiente no vuelve a cobrarse.',
      loja: 'La tienda asume',
      apareceu: 'La pieza apareció',
      motivo: 'Motivo',
      motivoAviso: 'Diga el motivo de resolver.',
      confirmar: 'Resolver pendiente',
      voltar: 'Volver',
    },
    feito: 'Pendiente resuelta.',
  },
  comissao: {
    titulo: 'Comisión y meta',
    ajuda:
      'La comisión del vendedor depende de cuánto vendió en la semana. Si pasa la meta, vale la comisión mayor.',
    meta: 'Meta de la semana, en Guaraníes',
    metaAviso: 'Escriba la meta en Guaraníes, solo con números.',
    abaixo: 'Comisión debajo de la meta, en %',
    naMeta: 'Comisión desde la meta, en %',
    percentualAviso: 'Use un número de 0 a 100.',
    ordemAviso: 'La comisión debajo de la meta no puede ser mayor que la comisión desde la meta.',
    valeParaOsProximos: 'Vale para los próximos acertos. Un acerto ya cerrado no cambia.',
    salvar: 'Guardar',
    salvando: 'Guardando...',
    salvo: 'Comisión y meta guardadas.',
    carregando: 'Cargando...',
  },
  desistir: {
    titulo: 'Desistir del acerto',
    texto:
      'Las maletas vuelven a quedar con el vendedor y no se guarda nada de dinero. Las lecturas del conteo quedan guardadas.',
    motivo: 'Motivo',
    motivoAviso: 'Diga el motivo de desistir.',
    confirmar: 'Desistir del acerto',
    voltar: 'Seguir con el acerto',
  },
  erros: {
    ACERTO_EM_ANDAMENTO: 'Este vendedor ya está en acerto. Abra su acerto en la lista.',
    ACERTO_DA_SEMANA_JA_EXISTE:
      'Este vendedor ya hizo el acerto de esta semana. Elija otra semana en "Más opciones".',
    SEMANA_FECHADA: 'Esta semana ya está cerrada. Elija una semana abierta.',
    PECA_NAO_ENCONTRADA: 'Esta pieza no es de este acerto. Revise el código.',
    LEITURA_DEIXA_CONTAGEM_NEGATIVA: 'No se puede quitar más de lo que se contó. Revise el conteo.',
    ACERTO_NAO_ESTA_EM_ANDAMENTO:
      'Este acerto ya terminó o fue deshecho. Vuelva a la lista y ábralo de nuevo.',
  },
};
