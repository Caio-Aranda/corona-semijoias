import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO } from '../../textos-relatorios-devolucao';

export const ES_TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO: NoMesmoFormato<
  typeof PT_TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO
> = {
  grupoDoMenu: 'Informes de devoluciones',
  cotacao: 'Dólar y Guaraní por la cotización del Banco Central del día de cada devolución.',
  cotacaoDoCredito:
    'Dólar y Guaraní por la cotización del Banco Central del día en que nació cada crédito.',
  cotacaoDoDinheiro:
    'Dólar y Guaraní por la cotización del Banco Central del día en que el dinero volvió al cliente.',
  cotacaoDoPedido: 'Dólar y Guaraní por la cotización del Banco Central del día de cada pedido.',
  cancelada: 'Cancelada',
  feita: 'Hecha',
  acimaDoLimite: 'Arriba del límite',
  devolucao: 'Devolución',
  devolucaoCancelada: 'Devolución cancelada',
  motivos: {
    ARREPENDIMENTO: 'Desistimiento',
    DEFEITO: 'Defecto',
    TAMANHO: 'No le quedó',
    OUTRO: 'Otro',
  } as Record<string, string>,
  tipos: { DEVOLUCAO: 'Devolución', TROCA: 'Cambio' } as Record<string, string>,
  situacoes: {
    ACEITA: 'Hecha',
    EM_REVISAO: 'En revisión',
    MANTIDA: 'Mantenida',
    NAO_ACEITA: 'No aceptada',
    CANCELADA: 'Cancelada',
  } as Record<string, string>,
  origens: {
    TROCA: 'Cambio',
    DEVOLUCAO: 'Devolución',
    TRANSFERENCIA: 'Pasado por la dueña',
  } as Record<string, string>,
  situacoesDoCredito: {
    ABERTO: 'Abierto',
    UTILIZADO: 'Usado',
    CANCELADO: 'Cancelado',
  } as Record<string, string>,
  formas: {
    DINHEIRO: 'Efectivo',
    PIX: 'Transferencia',
    CARTAO: 'Tarjeta',
    CHEQUE: 'Cheque',
  } as Record<string, string>,
  porQue: {
    FORA_DO_PRAZO: 'Pasó el plazo de la garantía',
    ACIMA_DO_VALOR: 'Pasó el valor',
  } as Record<string, string>,
  situacoesDoPedido: {
    PEDIDA: 'Esperando a usted',
    APROVADA: 'Aprobado',
    USADA: 'Usado',
    NEGADA: 'Negado',
    VENCIDA: 'Vencido',
  } as Record<string, string>,
  conferencia: {
    menu: 'Verificación de las devoluciones y cambios',
    titulo: 'Verificación de las devoluciones y cambios',
    ajuda:
      'El sistema verifica solo si las devoluciones, los cambios y los créditos coinciden con la maleta y con el dinero. Lo normal es que la lista de cada verificación quede vacía.',
    conferir: 'Verificar ahora',
    conferindo: 'Verificando...',
    tudoCerto:
      'Todo bien: las devoluciones, los cambios y los créditos coinciden con la maleta y con el dinero.',
    temDiferenca: 'Hay diferencias. Avise al soporte y no toque las ventas hasta resolver.',
    conferidos: (n: number) => `Verificados: ${n}`,
    semDiferenca: 'Nada fuera de lugar.',
    geradaEm: (quando: string) => `Verificado el ${quando}.`,
    erro: 'No se pudo verificar ahora. Intente de nuevo.',
    titulos: {
      maleta: 'Maleta con devoluciones y cambios',
      'devolvido-por-peca': 'Devuelto por pieza de la venta',
      creditos: 'Créditos y usos',
      'dinheiro-devolvido': 'Dinero devuelto',
      'contas-da-venda': 'Cuentas de la venta',
    } as Record<string, string>,
    explicacoes: {
      maleta:
        'Cada pieza que volvió a la maleta o salió en el cambio está registrada una vez, y el stock de la tienda más el de las maletas cierra.',
      'devolvido-por-peca':
        'En cada pieza vendida, lo que volvió nunca pasa de lo que se vendió.',
      creditos: 'Créditos generados menos usos es igual a lo que está abierto.',
      'dinheiro-devolvido': 'El dinero que volvió al cliente nunca pasa de lo que él pagó.',
      'contas-da-venda':
        'Lo que el cliente pagó, ya descontado lo que volvió, nunca pasa del total de la venta menos lo devuelto.',
    } as Record<string, string>,
  },
};
