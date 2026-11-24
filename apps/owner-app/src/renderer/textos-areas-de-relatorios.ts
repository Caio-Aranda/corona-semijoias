import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_AREAS_DE_RELATORIOS } from './idioma/es/areas-de-relatorios';
/**
 * Menu "Relatórios" por área (issue 216): um item por área e, dentro, uma página de cartões, um por relatório.
 * Só navegação: os relatórios não mudam por dentro.
 */
export const PT_TEXTOS_DE_AREAS_DE_RELATORIOS = {
  grupoDoMenu: 'Relatórios',
  areas: {
    cadastros: 'Cadastros',
    estoque: 'Estoque',
    compras: 'Compras',
    maletas: 'Maletas',
    devolucoes: 'Devoluções e trocas',
    vendas: 'Vendas',
    financeiro: 'Financeiro',
    controle: 'Controle',
    conferencias: 'Conferências',
  },
  tituloDaPagina: (area: string) => `Relatórios de ${area}`,
  ajudaDaPagina: 'Escolha o relatório que você quer ver.',
  voltar: (area: string) => `‹ Voltar para Relatórios de ${area}`,
  rotuloDosCartoes: 'Relatórios desta área',
} as const;

export const TEXTOS_DE_AREAS_DE_RELATORIOS = textosVivos(
  'areas-de-relatorios',
  PT_TEXTOS_DE_AREAS_DE_RELATORIOS,
  ES_TEXTOS_DE_AREAS_DE_RELATORIOS,
);
