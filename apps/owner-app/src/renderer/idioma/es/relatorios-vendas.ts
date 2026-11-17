import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DE_RELATORIOS_DE_VENDAS } from '../../textos-relatorios-vendas';

export const ES_TEXTOS_DE_RELATORIOS_DE_VENDAS: NoMesmoFormato<
  typeof PT_TEXTOS_DE_RELATORIOS_DE_VENDAS
> = {
  grupoDoMenu: 'Informes de ventas',
  cotacao:
    'Dólar y Guaraní por la cotización del Banco Central del día de cada venta. Todo se guarda en Real.',
  liquido:
    'Vendido neto es lo que se vendió menos lo que el cliente devolvió o cambió. Las ventas canceladas quedan fuera de los totales.',
  curva:
    'Clase A: las piezas que suman hasta 80% del vendido neto. Clase B: hasta 95%. Clase C: el resto. Una pieza sin venta en el período no aparece. Dólar y Guaraní por la cotización del día de cada venta.',
  situacaoDaVenda: {
    CONCLUIDA: 'Hecha',
    ESTORNADA: 'Cancelada',
  },
  classes: {
    A: 'A',
    B: 'B',
    C: 'C',
  },
  semPraca: 'Sin plaza',
};
