import { TEXTOS_DE_AREAS_DE_RELATORIOS as T } from '../textos-areas-de-relatorios';
import { TEXTOS_DE_CONCILIACOES } from '../textos-conciliacoes';
import { TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO } from '../textos-relatorios-devolucao';
import type { NomeDoRelatorio } from '../../shared/relatorios';
import { ORDEM_DOS_RELATORIOS, RELATORIOS } from './relatorios';
import {
  ORDEM_DOS_RELATORIOS_DE_COMPRAS,
  ORDEM_DOS_RELATORIOS_DE_ESTOQUE,
} from './relatorios-estoque';
import { ORDEM_DOS_RELATORIOS_DE_DEVOLUCAO } from './relatorios-devolucao';
import { ORDEM_DOS_RELATORIOS_DE_MALETA } from './relatorios-maleta';
import { ORDEM_DOS_RELATORIOS_FINANCEIROS } from './relatorios-financeiros';
import {
  ORDEM_DOS_RELATORIOS_DE_VENDAS,
  RELATORIOS_DE_VENDAS_NO_ESTOQUE,
} from './relatorios-vendas';
import { ORDEM_DOS_RELATORIOS_DE_CONTROLE } from './relatorios-controle';

export const CHAVES_DAS_AREAS = [
  'cadastros',
  'estoque',
  'compras',
  'maletas',
  'devolucoes',
  'vendas',
  'financeiro',
  'controle',
  'conferencias',
] as const;
export type ChaveDeArea = (typeof CHAVES_DAS_AREAS)[number];

/** Rota de um relatório ou de uma conferência (o tipo `Rota` completo mora em rotas.ts). */
export type RotaDeCartao = `relatorios/${string}` | `conciliacoes/${string}`;

export interface CartaoDeRelatorio {
  rota: RotaDeCartao;
  titulo: string;
  explicacao: string;
}

type Item =
  | NomeDoRelatorio
  | 'devolucao-conferencia'
  | 'conciliacoes/dinheiro'
  | 'conciliacoes/conferencia-diaria';

const CONFERENCIAS_DO_ESTOQUE_E_DAS_DEVOLUCOES: Item[] = [
  'estoque-conferencia',
  'devolucao-conferencia',
  'conciliacoes/dinheiro',
  'conciliacoes/conferencia-diaria',
];

/** O que cada área lista, na ordem em que aparecia no menu. As conferências saíram das áreas de origem. */
const ITENS_DA_AREA: Record<ChaveDeArea, readonly Item[]> = {
  cadastros: ORDEM_DOS_RELATORIOS,
  estoque: [...ORDEM_DOS_RELATORIOS_DE_ESTOQUE, ...RELATORIOS_DE_VENDAS_NO_ESTOQUE].filter(
    (n) => n !== 'estoque-conferencia',
  ),
  compras: ORDEM_DOS_RELATORIOS_DE_COMPRAS,
  maletas: ORDEM_DOS_RELATORIOS_DE_MALETA,
  devolucoes: ORDEM_DOS_RELATORIOS_DE_DEVOLUCAO,
  vendas: ORDEM_DOS_RELATORIOS_DE_VENDAS,
  financeiro: ORDEM_DOS_RELATORIOS_FINANCEIROS,
  controle: ORDEM_DOS_RELATORIOS_DE_CONTROLE,
  conferencias: CONFERENCIAS_DO_ESTOQUE_E_DAS_DEVOLUCOES,
};

export const AREAS_DE_RELATORIOS = CHAVES_DAS_AREAS.map((chave) => ({ chave }));

export function rotaDaArea(chave: ChaveDeArea): `areas/${ChaveDeArea}` {
  return `areas/${chave}`;
}

export function rotuloDaArea(chave: ChaveDeArea): string {
  return T.areas[chave];
}

function rotaDoItem(item: Item): RotaDeCartao {
  return item.startsWith('conciliacoes/') ? (item as RotaDeCartao) : `relatorios/${item}`;
}

function textoDoItem(item: Item): { titulo: string; explicacao: string } {
  if (item === 'conciliacoes/dinheiro') {
    return { titulo: TEXTOS_DE_CONCILIACOES.menu, explicacao: TEXTOS_DE_CONCILIACOES.ajuda };
  }
  if (item === 'conciliacoes/conferencia-diaria') {
    const d = TEXTOS_DE_CONCILIACOES.diaria;
    return { titulo: d.menu, explicacao: d.ajuda };
  }
  if (item === 'devolucao-conferencia') {
    const c = TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO.conferencia;
    return { titulo: c.menu, explicacao: c.ajuda };
  }
  const def = RELATORIOS[item];
  return { titulo: def.titulo, explicacao: def.explicacao };
}

/** Os quatro relatórios financeiros do acerto antigo da §7.4 (ADR-0016, item 15): saem junto com o resto do acerto antigo. */
const RELATORIOS_DO_ACERTO_ANTIGO: readonly string[] = [
  'financeiro-acertos',
  'financeiro-pendencias',
  'financeiro-esperado-contado',
  'financeiro-comissoes',
];

export function ehRelatorioDoAcertoAntigo(rota: string): boolean {
  return (
    rota.startsWith('relatorios/') &&
    RELATORIOS_DO_ACERTO_ANTIGO.includes(rota.slice('relatorios/'.length))
  );
}

/** Os cartões da página de uma área, um por relatório (sem os do acerto antigo quando ele está escondido). */
export function cartoesDaArea(chave: ChaveDeArea, acertoVisivel = true): CartaoDeRelatorio[] {
  return ITENS_DA_AREA[chave]
    .filter((item) => acertoVisivel || !RELATORIOS_DO_ACERTO_ANTIGO.includes(item))
    .map((item) => ({ rota: rotaDoItem(item), ...textoDoItem(item) }));
}

/** A área a que a rota pertence (relatório ou conferência), ou nada se não é de relatório. */
export function areaDaRota(rota: string): ChaveDeArea | null {
  for (const chave of CHAVES_DAS_AREAS) {
    if (ITENS_DA_AREA[chave].some((i) => rotaDoItem(i) === rota)) return chave;
  }
  return null;
}

/** "areas/vendas" → "vendas"; qualquer outra coisa → nada. */
export function areaDaPagina(rota: string): ChaveDeArea | null {
  const chave = rota.startsWith('areas/') ? rota.slice('areas/'.length) : '';
  return (CHAVES_DAS_AREAS as readonly string[]).includes(chave) ? (chave as ChaveDeArea) : null;
}
