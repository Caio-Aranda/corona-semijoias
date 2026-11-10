/** Conferência, no processo principal, dos dados dos relatórios de cadastro. */
import {
  NOMES_DOS_RELATORIOS,
  SITUACOES_DO_RELATORIO,
  type ConsultaDeRelatorio,
  type NomeDoRelatorio,
  type SituacaoDoRelatorio,
} from './relatorios';

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
const RECORTE = /^[a-z0-9-]{1,40}$/;
const VALOR = /^\d{1,12}(\.\d{1,2})?$/;
const SIM_NAO = ['SIM', 'NAO'];
const ESCOLHAS: [
  'comRestricao' | 'comProdutos' | 'comVendedor' | 'estoque' | 'foto' | 'tipo' | 'resultado',
  string[],
][] = [
  ['comRestricao', SIM_NAO],
  ['comProdutos', SIM_NAO],
  ['comVendedor', SIM_NAO],
  ['estoque', ['ZERADO', 'ABAIXO_DO_MINIMO', 'COM_ESTOQUE']],
  ['foto', ['COM', 'SEM']],
  ['tipo', ['PLANILHA', 'FOTOS']],
  ['resultado', ['CRIADO', 'ATUALIZADO', 'JA_EXISTIA', 'RECUSADO']],
];

export interface ParametrosDeRelatorio {
  operacao: 'relatorios.cadastros';
  relatorio: NomeDoRelatorio;
  consulta: ConsultaDeRelatorio;
}

const CAMPOS = [
  'relatorio',
  'busca',
  'categoriaId',
  'fornecedorId',
  'pracaId',
  'vendedorId',
  'restricaoId',
  'situacao',
  'de',
  'ate',
  'mes',
  'limiteDe',
  'limiteAte',
  'comRestricao',
  'estoque',
  'precoDe',
  'precoAte',
  'foto',
  'comProdutos',
  'comVendedor',
  'tipo',
  'resultado',
  'recorte',
  'pagina',
  'tamanho',
];

export function conferirRelatorio(p: Record<string, unknown>): ParametrosDeRelatorio | null {
  if (!Object.keys(p).every((k) => CAMPOS.includes(k))) return null;
  if (!(NOMES_DOS_RELATORIOS as readonly unknown[]).includes(p.relatorio)) return null;
  const pagina = p.pagina ?? 0;
  const tamanho = p.tamanho ?? 20;
  if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
  if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
  if (tamanho < 1 || tamanho > 100) return null;
  const consulta: ConsultaDeRelatorio = { pagina, tamanho };

  if (p.busca !== undefined && p.busca !== '') {
    if (!ehTexto(p.busca) || p.busca.length > 200) return null;
    consulta.busca = p.busca;
  }
  for (const k of [
    'categoriaId',
    'fornecedorId',
    'pracaId',
    'vendedorId',
    'restricaoId',
  ] as const) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !UUID.test(p[k])) return null;
      consulta[k] = p[k];
    }
  }
  if (p.situacao !== undefined && p.situacao !== '') {
    if (!(SITUACOES_DO_RELATORIO as readonly unknown[]).includes(p.situacao)) return null;
    consulta.situacao = p.situacao as SituacaoDoRelatorio;
  }
  for (const k of ['de', 'ate'] as const) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !DATA.test(p[k]) || Number.isNaN(Date.parse(p[k]))) return null;
      consulta[k] = p[k];
    }
  }
  for (const k of ['limiteDe', 'limiteAte', 'precoDe', 'precoAte'] as const) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !VALOR.test(p[k])) return null;
      consulta[k] = p[k];
    }
  }
  for (const [k, aceitos] of ESCOLHAS) {
    if (p[k] !== undefined && p[k] !== '') {
      if (!ehTexto(p[k]) || !aceitos.includes(p[k])) return null;
      consulta[k] = p[k];
    }
  }
  if (p.mes !== undefined && p.mes !== '') {
    if (typeof p.mes !== 'number' || !Number.isInteger(p.mes) || p.mes < 1 || p.mes > 12)
      return null;
    consulta.mes = p.mes;
  }
  if (p.recorte !== undefined && p.recorte !== '') {
    if (!ehTexto(p.recorte) || !RECORTE.test(p.recorte)) return null;
    consulta.recorte = p.recorte;
  }
  return { operacao: 'relatorios.cadastros', relatorio: p.relatorio as NomeDoRelatorio, consulta };
}
