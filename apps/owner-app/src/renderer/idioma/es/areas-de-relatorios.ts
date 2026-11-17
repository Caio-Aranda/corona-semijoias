import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DE_AREAS_DE_RELATORIOS } from '../../textos-areas-de-relatorios';

export const ES_TEXTOS_DE_AREAS_DE_RELATORIOS: NoMesmoFormato<
  typeof PT_TEXTOS_DE_AREAS_DE_RELATORIOS
> = {
  grupoDoMenu: 'Informes',
  areas: {
    cadastros: 'Registros',
    estoque: 'Stock',
    compras: 'Compras',
    maletas: 'Maletas',
    devolucoes: 'Devoluciones y cambios',
    vendas: 'Ventas',
    financeiro: 'Financiero',
    controle: 'Control',
    conferencias: 'Verificaciones',
  },
  tituloDaPagina: (area: string) => `Informes de ${area}`,
  ajudaDaPagina: 'Elija el informe que quiere ver.',
  voltar: (area: string) => `‹ Volver a Informes de ${area}`,
  rotuloDosCartoes: 'Informes de esta área',
};
