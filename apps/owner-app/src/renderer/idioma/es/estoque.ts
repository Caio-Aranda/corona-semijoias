import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DE_ESTOQUE } from '../../textos-estoque';

const pecas = (n: number): string => (n === 1 ? '1 pieza' : `${n} piezas`);

export const ES_TEXTOS_DE_ESTOQUE: NoMesmoFormato<typeof PT_TEXTOS_DE_ESTOQUE> = {
  menu: {
    estoque: 'Stock',
    entradasESaidas: 'Entradas y salidas',
    acabando: 'Se está acabando',
    contagem: 'Conteo del stock',
  },
  comuns: {
    foto: 'Foto',
    peca: 'Pieza',
    codigo: 'Código',
    buscando: 'Buscando...',
    voltar: 'Volver',
    abrir: 'Abrir',
    todos: 'Todos',
    todas: 'Todas',
    limpar: 'Limpiar filtros',
    buscar: 'Buscar',
    pecas,
    semFotoNaoTrava: 'Sin foto',
  },
  posicao: {
    titulo: 'Stock',
    legenda: 'Piezas y cantidades en el stock',
    filtros: {
      busca: 'Nombre, referencia o código',
      categoria: 'Categoría',
      fornecedor: 'Proveedor',
      soAcabando: 'Solo los que se están acabando',
    },
    colunas: {
      noEstoque: 'En el stock',
      minimo: 'Stock mínimo',
      situacao: 'Estado',
      acoes: 'Qué hacer',
    },
    situacao: { acabando: 'Se está acabando', emDia: 'Al día' },
    abrirDe: (nome: string) => `Abrir ${nome}`,
    vazio: 'Todavía no hay piezas registradas.',
    semResultado: 'Ninguna pieza encontrada con esos filtros.',
    itens: ['pieza', 'piezas'] as const,
  },
  acabando: {
    titulo: 'Se está acabando',
    ajuda: 'Piezas que llegaron al stock mínimo o quedaron con menos. Haga la reposición.',
    legenda: 'Piezas que se están acabando',
    colunas: { faltam: 'Cuánto falta' },
    vazio: 'Ningún producto se está acabando.',
    semResultado: 'Ninguna pieza encontrada con esos filtros.',
  },
  ficha: {
    carregando: 'Abriendo la pieza...',
    naoEncontrada: 'No encontramos esta pieza. Vuelva y abra la lista de nuevo.',
    noEstoque: 'En el stock',
    minimo: 'Stock mínimo',
    custo: 'Costo de cada pieza',
    custoAjuda: 'Solo usted ve el costo.',
    acabando: 'Se está acabando',
    entrada: 'Entrada',
    saida: 'Salida',
    perda: 'Pérdida',
    ajustar: 'Ajustar el stock',
    mudarMinimo: 'Cambiar el stock mínimo',
    historico: 'Entradas y salidas de esta pieza',
    foiAtualizado: 'Listo. Se actualizó el stock de esta pieza.',
    minimoAtualizado: 'Listo. Se actualizó el stock mínimo.',
  },
  minimo: {
    titulo: 'Cambiar el stock mínimo',
    texto:
      'Cuando el stock llegue a ese número, la pieza aparece en "Se está acabando". Use cero para no recibir aviso.',
    campo: 'Stock mínimo',
    salvar: 'Guardar',
    cancelar: 'Cancelar',
  },
  historico: {
    legenda: 'Entradas y salidas',
    colunas: {
      data: 'Fecha',
      tipo: 'Tipo',
      quantidade: 'Cantidad',
      saldoDepois: 'Quedó con',
      quem: 'Quién',
      motivo: 'Motivo',
      situacao: 'Estado',
      acoes: 'Qué hacer',
    },
    vazio: 'Todavía no hay entradas ni salidas.',
    semResultado: 'Ninguna entrada o salida con esos filtros.',
    itens: ['movimiento', 'movimientos'] as const,
    desfeito: 'Deshecho',
    desfazDe: (descricao: string) => `Deshace ${descricao}`,
    desfazUmAnterior: 'Deshace un movimiento anterior',
    desfazer: 'Deshacer',
    desfazerDe: (tipo: string, quantidade: string, peca: string) =>
      `Deshacer movimiento: ${tipo} ${quantidade} de ${peca}`,
  },
  desfazer: {
    titulo: '¿Deshacer este movimiento?',
    texto:
      'El sistema crea un movimiento nuevo que cancela este. El movimiento antiguo sigue en el historial.',
    motivo: 'Motivo',
    confirmar: 'Deshacer movimiento',
    voltar: 'Volver',
  },
  lancar: {
    entrada: {
      titulo: 'Entrada al stock',
      texto: 'Use cuando entraron piezas que no vinieron de una compra.',
      confirmar: 'Registrar entrada',
    },
    saida: {
      titulo: 'Salida del stock',
      texto: 'Use cuando salieron piezas del stock.',
      confirmar: 'Registrar salida',
    },
    perda: {
      titulo: 'Pérdida de piezas',
      texto: 'Use para una pieza con defecto o extraviada.',
      confirmar: 'Registrar pérdida',
    },
    quantidade: 'Cantidad',
    motivo: 'Motivo (si quiere)',
    noEstoqueAgora: (n: number) => `Ahora hay ${pecas(n)} en el stock.`,
    cancelar: 'Cancelar',
  },
  ajuste: {
    titulo: 'Ajustar el stock',
    texto: 'Corrija la cantidad y diga el motivo. El movimiento antiguo nunca se borra.',
    forma: '¿Cómo quiere ajustar?',
    contei: 'Yo conté',
    diferenca: 'Quiero decir la diferencia',
    campoContei: '¿Cuántas piezas contó?',
    campoDiferenca: 'Diferencia (3 de más, -3 de menos)',
    motivo: 'Motivo del ajuste',
    confirmar: 'Ajustar el stock',
    cancelar: 'Cancelar',
  },
  lancamentos: {
    titulo: 'Entradas y salidas',
    ajuda: 'Todo lo que entró y salió del stock, de todas las piezas.',
    filtros: {
      de: 'Desde',
      ate: 'Hasta',
      tipo: 'Tipo',
      usuario: 'Quién registró',
      peca: 'Pieza (código)',
      pecaAjuda: 'Escriba el código de la pieza y presione Enter.',
      tirarPeca: 'Quitar la pieza',
      filtrandoPor: (nome: string) => `Solo de la pieza: ${nome}.`,
      pecaNaoAchada: (codigo: string) => `No encontramos ninguna pieza con el código ${codigo}.`,
      dataInvertida: 'La fecha final tiene que ser posterior a la inicial.',
    },
    colunas: { peca: 'Pieza' },
  },
  contagem: {
    titulo: 'Conteo del stock',
    ajuda:
      'Verifique lo que hay en la tienda leyendo el código de cada pieza. El sistema muestra lo que falta o sobra.',
    comecarTitulo: 'Empezar el conteo',
    explicacao:
      'Al empezar, el sistema anota cuántas piezas esperaba de cada producto. Las entradas y salidas hechas después siguen valiendo; al final, solo se ajusta la diferencia.',
    escopo: '¿Qué va a contar?',
    escopos: {
      TODOS: 'Todas las piezas',
      CATEGORIA: 'Una categoría',
      FORNECEDOR: 'Un proveedor',
    },
    escolhaCategoria: 'Elija la categoría',
    escolhaFornecedor: 'Elija el proveedor',
    escolhaAviso: 'Elija lo que va a contar.',
    observacoes: 'Observaciones (si quiere)',
    comecar: 'Empezar el conteo',
    comecando: 'Empezando el conteo...',
    anteriores: 'Conteos',
    legenda: 'Conteos del stock',
    colunas: {
      inicio: 'Empezó el',
      fim: 'Terminó el',
      o_que: 'Qué se contó',
      lidas: 'Piezas leídas',
      situacao: 'Estado',
      acoes: 'Qué hacer',
    },
    situacoes: {
      EM_ANDAMENTO: 'En curso',
      FECHADO: 'Cerrado',
      CANCELADO: 'Cancelado',
    },
    escopoDaLinha: (escopo: string, nome: string) =>
      escopo === 'CATEGORIA'
        ? `Categoría ${nome}`
        : escopo === 'FORNECEDOR'
          ? `Proveedor ${nome}`
          : 'Todas las piezas',
    lidasDe: (lidas: number, total: number) => `${lidas} de ${total}`,
    continuar: 'Seguir contando',
    ver: 'Ver el conteo',
    vazio: 'Todavía no hay conteos.',
    itens: ['conteo', 'conteos'] as const,
    nova: 'Empezar un conteo nuevo',
  },
  contando: {
    titulo: 'Contando el stock',
    leitor: {
      campo: 'Código de la pieza',
      ajuda: 'El lector ya presiona Enter solo. Cada lectura suma 1 pieza contada.',
      ler: 'Leer la pieza',
      lendo: 'Leyendo la pieza...',
      jaRegistrada: 'Esta lectura ya estaba registrada. No se sumó nada de nuevo.',
      somou: (nome: string, n: number) => `${nome}: ahora son ${pecas(n)} contadas.`,
    },
    peca: {
      titulo: 'Última pieza leída',
      esperado: 'Esperado',
      contado: 'Contado',
      diferenca: 'Diferencia',
      corrigir: 'Cantidad contada',
      corrigirAjuda: 'Si contó de otra manera, escriba la cantidad correcta. Reemplaza la lectura.',
      definir: 'Definir la cantidad',
      quantidadeInvalida: 'Escriba la cantidad con un número entero. Puede ser cero.',
      jaRegistrada: 'Esta lectura ya estaba registrada. No se sumó nada de nuevo.',
      definida: (nome: string, n: number) => `${nome}: la cantidad contada ahora es ${n}.`,
    },
    resumo: {
      lidas: (lidos: number, total: number) => `${lidos} de ${total} productos leídos`,
      faltam: (n: number) => `Faltan ${pecas(n)}`,
      sobram: (n: number) => `Sobran ${pecas(n)}`,
      comDiferenca: (n: number) =>
        n === 1 ? '1 producto con diferencia' : `${n} productos con diferencia`,
    },
    lista: {
      titulo: 'Esperado y contado',
      legenda: 'Piezas del conteo: esperado y contado',
      mostrar: 'Mostrar',
      filtros: {
        DIVERGENTES: 'Solo las que tienen diferencia',
        NAO_LIDOS: 'Las que todavía no leí',
        LIDOS: 'Las que ya leí',
        TODOS: 'Todas las piezas',
      },
      busca: 'Buscar en la lista',
      colunas: {
        esperado: 'Esperado',
        contado: 'Contado',
        diferenca: 'Diferencia',
        acoes: 'Qué hacer',
      },
      naoLido: 'No leído',
      corrigir: 'Corregir',
      corrigirDe: (nome: string) => `Corregir la cantidad de ${nome}`,
      vazio: {
        DIVERGENTES: 'Ninguna pieza con diferencia hasta ahora.',
        NAO_LIDOS: 'Todas las piezas ya fueron leídas.',
        LIDOS: 'Ninguna pieza fue leída todavía.',
        TODOS: 'No hay piezas en este conteo.',
      },
      itens: ['pieza', 'piezas'] as const,
    },
    fechar: 'Cerrar el conteo',
    cancelar: 'Cancelar el conteo',
    voltarLista: 'Volver a los conteos',
    carregando: 'Abriendo el conteo...',
    naoEncontrada:
      'No encontramos este conteo del stock. Actualice la pantalla e intente de nuevo.',
  },
  fechar: {
    titulo: '¿Cerrar el conteo?',
    texto: (n: number) =>
      `El stock se ajustará ${n === 1 ? 'en 1 producto' : `en los ${n} productos`} con diferencia. Después de cerrado, el conteo no cambia; para corregir, haga un ajuste.`,
    semAjustes: 'Ningún producto tiene diferencia. El stock no va a cambiar.',
    resumoDosAjustes: 'Lo que va a cambiar en el stock',
    legenda: 'Ajustes que se van a hacer',
    naoLidos: (n: number) =>
      n === 1 ? '1 producto no fue leído.' : `${n} productos no fueron leídos.`,
    naoLidosPergunta: '¿Deben quedar con cero piezas o quedan como están?',
    zero: 'Quedan con cero piezas',
    comoEstao: 'Quedan como están',
    escolhaAviso: 'Elija qué hacer con los productos que no fueron leídos.',
    confirmar: 'Cerrar el conteo',
    continuar: 'Seguir contando',
    carregando: 'Verificando los ajustes...',
    naoAjustadas: (nomes: string[]) =>
      `Sin ajuste, porque la pieza fue eliminada durante el conteo: ${nomes.join(', ')}. La diferencia quedó registrada.`,
    fechada: (n: number) =>
      n === 0
        ? 'Conteo cerrado. El stock no necesitó ajuste.'
        : `Conteo cerrado. El stock se ajustó ${n === 1 ? 'en 1 producto' : `en ${n} productos`}.`,
  },
  cancelarContagem: {
    titulo: '¿Cancelar este conteo?',
    texto: 'No cambia nada en el stock. El conteo queda en la lista como cancelado.',
    motivo: 'Motivo (si quiere)',
    confirmar: 'Cancelar el conteo',
    voltar: 'Volver',
    cancelada: 'Conteo cancelado. El stock no cambió.',
  },
  encerrada: {
    fechada: (quando: string, quem: string) =>
      `Conteo cerrado el ${quando}${quem ? ` por ${quem}` : ''}.`,
    cancelada: (quando: string) => `Conteo cancelado el ${quando}.`,
    motivo: (m: string) => `Motivo: ${m}`,
    ajustes: (n: number) =>
      n === 0
        ? 'No se hizo ningún ajuste.'
        : `${n === 1 ? '1 ajuste' : `${n} ajustes`} en el stock.`,
    zerou: 'Las piezas que no fueron leídas quedaron con cero.',
    nZerou: 'Las piezas que no fueron leídas quedaron como estaban.',
  },
  aviso: {
    verLista: 'Ver la lista',
  },
};
