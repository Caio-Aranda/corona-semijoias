import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_RELATORIOS } from '../../textos-relatorios';

export const ES_TEXTOS_RELATORIOS: NoMesmoFormato<typeof PT_TEXTOS_RELATORIOS> = {
  loja: 'Corona Semijoias',
  grupoDoMenu: 'Informes de registros',
  grupoDoEstoque: 'Informes de stock',
  grupoDasCompras: 'Informes de compras',
  rotuloDosFiltros: 'Filtros del informe',
  buscando: 'Buscando el informe...',
  filtros: {
    busca: 'Buscar',
    situacao: 'Estado',
    ativos: 'Solo activos',
    inativos: 'Solo inactivos',
    todos: 'Todos',
    categoria: 'Categoría',
    fornecedor: 'Proveedor',
    praca: 'Plaza',
    vendedor: 'Representante',
    restricao: 'Restricción',
    de: 'Desde',
    ate: 'Hasta',
    mes: 'Mes',
    usuario: 'Quién registró',
    qualquer: 'Todas',
    qualquerUm: 'Todos',
    limpar: 'Limpiar filtros',
    periodoInvertido: 'La fecha final no puede ser anterior a la fecha inicial.',
    meses: [
      'Enero',
      'Febrero',
      'Marzo',
      'Abril',
      'Mayo',
      'Junio',
      'Julio',
      'Agosto',
      'Septiembre',
      'Octubre',
      'Noviembre',
      'Diciembre',
    ],
  },
  totais: {
    titulo: 'Totales',
    verRegistros: 'Ver los registros',
    voltarAoRelatorio: 'Ver el informe completo',
    mostrando: (rotulo: string) => `Mostrando solo: ${rotulo}.`,
    pecas: (n: number) => (n === 1 ? '1 pieza' : `${n} piezas`),
    calculando: 'Calculando...',
  },
  exportar: {
    titulo: 'Exportar el informe completo',
    csv: 'Descargar en CSV',
    excel: 'Descargar en Excel',
    pdf: 'Descargar en PDF',
    preparando: 'Preparando el archivo...',
    pronto: (nome: string) => `El archivo ${nome} fue preparado. Elija dónde guardarlo.`,
    erro: 'No se pudo preparar el archivo ahora. Intente de nuevo.',
    semLinhas: 'No hay nada para exportar con estos filtros.',
    muitasLinhas:
      'Este informe tiene demasiadas líneas para exportar de una vez. Use los filtros para reducir e intente de nuevo.',
  },
  cotacao: {
    deHoje: 'En este informe, el Dólar y el Guaraní siguen la cotización de hoy.',
  },
  situacaoDoProduto: { ativo: 'Activo', inativo: 'Inactivo' },
  tiposDeCodigo: {
    LOJA: 'Código de la tienda',
    FORNECEDOR: 'Código del proveedor',
    EAN13: 'Código de barras',
  },
  tiposDeAniversariante: { VENDEDOR: 'Representante', CLIENTE: 'Cliente' },
  alteracoesSemImportacao:
    'Muestra lo que se registró y cambió, y quién lo hizo. El resultado de las importaciones en masa está en el informe "Resultado de las importaciones".',
  importacoesNota:
    'Muestra las importaciones hechas de ahora en adelante. En la planilla, el resultado es lo que el programa informó al terminar; si se cae internet en el medio, las últimas líneas pueden no aparecer aquí.',
  estoqueECompras: {
    origens: {
      MANUAL: 'Movimiento hecho a mano',
      AJUSTE_MANUAL: 'Ajuste del stock',
      COMPRA: 'Compra',
      INVENTARIO: 'Conteo del stock',
      MALETA: 'Maleta',
      ESTORNO: 'Deshacer movimiento',
    },
    desfeito: 'Deshecho',
    desfazOutro: 'Deshace otro movimiento',
    compraRegistrada: 'Registrada',
    compraDesfeita: 'Deshecha',
    situacoesDaContagem: {
      EM_ANDAMENTO: 'En curso',
      FECHADO: 'Cerrado',
      CANCELADO: 'Cancelado',
    },
    escopoDaContagem: (escopo: string, nome: string) =>
      escopo === 'CATEGORIA'
        ? `Categoría ${nome}`
        : escopo === 'FORNECEDOR'
          ? `Proveedor ${nome}`
          : 'Todas las piezas',
    acoes: {
      verAsPecas: 'Ver las piezas',
      soEstaContagem: 'Ver solo este conteo',
      historicoDeCusto: 'Ver el historial de costo',
      tituloDaCompra: (data: string, fornecedor: string) =>
        `Compra del ${data}${fornecedor ? ` — ${fornecedor}` : ''}`,
      tituloDaContagem: (inicio: string) => `Conteo del ${inicio}`,
      tituloDaPeca: (nome: string) => `Historial de costo: ${nome}`,
      voltar: 'Volver al informe',
      filtroFixo: (texto: string) => `Mostrando solo: ${texto}.`,
      coluna: 'Abrir',
    },
    dias: { padrao: (n: number) => `Sin salida hace ${n} días` },
    fotoNaoVaiNoArquivo:
      'La foto aparece en la pantalla; los archivos PDF, Excel y CSV llevan el nombre y el código de la pieza.',
  },
};
