/**
 * Painel da semana (tarefa 8.8): lê o que a API da 8.7 devolve e monta o que a tela e os arquivos mostram.
 * A tela só mostra: nenhuma conta de dinheiro é feita aqui (Real gravado; Dólar e Guarani vêm prontos, pela
 * cotação do dia de cada lançamento). O que não tem o formato esperado vira vazio, sem derrubar a tela.
 */
import type { NomeDoRelatorio } from '../../shared/relatorios';
import { TEXTOS_DO_PAINEL as T } from '../textos-painel';
import type { PedidoDeExportacao } from './exportacao/grade';
import { centavosDeTexto, formatarDolar, formatarGuarani, formatarReais } from './moedas';

export interface Moedas {
  real: string | null;
  dolar: string | null;
  guarani: string | null;
}

export interface Origem {
  rota: string;
  filtros: Record<string, string>;
}

export interface LinhaDoPainel {
  acertoId: string | null;
  vendedorId: string;
  vendedor: string;
  pracaId: string | null;
  praca: string;
  situacao: string;
  previa: boolean;
  semCotacao: boolean;
  dataDaCotacao: string | null;
  percentualComissao: string | null;
  enviadoPecas: number;
  devolvidoPecas: number;
  vendidasPecas: number;
  emFaltaPecas: number;
  enviado: Moedas | null;
  devolvido: Moedas | null;
  vendidoLiquido: Moedas | null;
  comissao: Moedas | null;
  liquido: Moedas | null;
  maletas: { id: string; numero: string; tipo: string; situacao: string }[];
}

export interface ItemDoCartao {
  rotulo: string;
  quantidade?: number;
  valor?: Moedas | null;
}

export interface CartaoDoPainel {
  chave: string;
  titulo: string;
  itens: ItemDoCartao[];
  /** De onde saiu o número: abre o relatório com os registros. */
  origens: { rotulo: string; origem: Origem }[];
  semCotacao?: boolean;
}

export interface PainelLido {
  semana: { id: string; numero: number; inicio: string; fim: string; situacao: string } | null;
  linhas: LinhaDoPainel[];
  cartoes: CartaoDoPainel[];
  total: { enviado: Moedas | null; vendido: Moedas | null; liquido: Moedas | null };
  previaIncluiSemanasAnteriores: boolean;
  avisoDaPrevia: string | null;
}

const texto = (v: unknown): string =>
  typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '';
const numero = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};

/** Normaliza um decimal da API ("570000", "12.5") para texto com 2 casas; qualquer outra coisa vira nulo. */
function decimal(v: unknown): string | null {
  const c = centavosDeTexto(texto(v));
  if (c === null) return null;
  const negativo = c < 0n;
  const abs = negativo ? -c : c;
  return `${negativo ? '-' : ''}${abs / 100n}.${(abs % 100n).toString().padStart(2, '0')}`;
}

export function lerMoedas(bruto: unknown): Moedas | null {
  const o = objeto(bruto);
  const real = decimal(o.emReais);
  if (real === null) return null;
  return { real, dolar: decimal(o.emDolar), guarani: decimal(o.emGuarani) };
}

function lerOrigem(bruto: unknown): Origem | null {
  const o = objeto(bruto);
  if (typeof o.rota !== 'string' || o.rota === '') return null;
  const filtros: Record<string, string> = {};
  for (const [k, v] of Object.entries(objeto(o.filtros))) {
    if (typeof v === 'string') filtros[k] = v;
  }
  return { rota: o.rota, filtros };
}

/** "R$ 430,00" a partir do texto decimal. */
export function reaisNaTela(real: string | null): string {
  const c = real === null ? null : centavosDeTexto(real);
  return c === null ? '' : formatarReais(c);
}

export function dolarNaTela(dolar: string | null): string {
  const c = dolar === null ? null : centavosDeTexto(dolar);
  return c === null ? '' : formatarDolar(c);
}

/** O Guarani é inteiro: a API manda "570000.00"; tira os centavos. */
export function guaraniNaTela(guarani: string | null): string {
  const c = guarani === null ? null : centavosDeTexto(guarani);
  return c === null ? '' : formatarGuarani(c / 100n);
}

function lerLinha(bruto: unknown): LinhaDoPainel | null {
  const o = objeto(bruto);
  if (typeof o.vendedorId !== 'string') return null;
  return {
    acertoId: typeof o.acertoId === 'string' ? o.acertoId : null,
    vendedorId: o.vendedorId,
    vendedor: texto(o.vendedor),
    pracaId: typeof o.pracaId === 'string' ? o.pracaId : null,
    praca: texto(o.praca),
    situacao: texto(o.situacao),
    previa: o.previa === true,
    semCotacao: o.semCotacao === true,
    dataDaCotacao: typeof o.dataDaCotacao === 'string' ? o.dataDaCotacao : null,
    percentualComissao: typeof o.percentualComissao === 'string' ? o.percentualComissao : null,
    enviadoPecas: numero(o.enviadoPecas),
    devolvidoPecas: numero(o.devolvidoPecas),
    vendidasPecas: numero(o.vendidasPecas),
    emFaltaPecas: numero(o.emFaltaPecas),
    enviado: lerMoedas(o.enviado),
    devolvido: lerMoedas(o.devolvido),
    vendidoLiquido: lerMoedas(o.vendidoLiquido),
    comissao: lerMoedas(o.comissao),
    liquido: lerMoedas(o.liquido),
    maletas: (Array.isArray(o.maletas) ? o.maletas : []).flatMap((m) => {
      const x = objeto(m);
      return typeof x.id === 'string'
        ? [{ id: x.id, numero: texto(x.numero), tipo: texto(x.tipo), situacao: texto(x.situacao) }]
        : [];
    }),
  };
}

function cartao(
  chave: string,
  titulo: string,
  itens: ItemDoCartao[],
  origens: { rotulo: string; origem: Origem | null }[],
  extra: Partial<CartaoDoPainel> = {},
): CartaoDoPainel {
  return {
    chave,
    titulo,
    itens,
    origens: origens.flatMap((x) => (x.origem ? [{ rotulo: x.rotulo, origem: x.origem }] : [])),
    ...extra,
  };
}

const I = T.itens;
const R = T.registros;

function cartoesDosTotais(bruto: unknown): CartaoDoPainel[] {
  const t = objeto(bruto);
  const maletas = (chave: string, titulo: string): CartaoDoPainel => {
    const b = objeto(t[chave]);
    return cartao(
      chave,
      titulo,
      [
        { rotulo: I.maletas, quantidade: numero(b.quantidade) },
        { rotulo: I.pecas, quantidade: numero(b.pecas) },
        { rotulo: I.valor, valor: lerMoedas(b.valor) },
      ],
      [{ rotulo: R.maletas, origem: lerOrigem(b.origem) }],
    );
  };
  const a = objeto(t.acerto);
  const p = objeto(t.previa);
  const v = objeto(t.vendas);
  const tr = objeto(t.trocas);
  const rc = objeto(t.recebidoNoAcerto);
  const tt = objeto(t.total);
  return [
    maletas('novasMaletas', T.cartoes.novasMaletas),
    maletas('reposicoes', T.cartoes.reposicoes),
    cartao(
      'acerto',
      T.cartoes.acerto,
      [
        { rotulo: I.acertos, quantidade: numero(a.quantidade) },
        { rotulo: I.vendido, valor: lerMoedas(a.vendido) },
        { rotulo: I.descontos, valor: lerMoedas(a.descontos) },
        { rotulo: I.devolvidoPeloCliente, valor: lerMoedas(a.devolvidoPeloCliente) },
        { rotulo: I.devolvidoALoja, valor: lerMoedas(a.devolvidoALoja) },
        { rotulo: I.comissao, valor: lerMoedas(a.comissao) },
        { rotulo: I.faltasCobradas, valor: lerMoedas(a.faltasCobradas) },
        { rotulo: I.valorDoAcerto, valor: lerMoedas(a.valorDoAcerto) },
        { rotulo: I.recebido, valor: lerMoedas(a.recebido) },
        { rotulo: I.ficouDevendo, valor: lerMoedas(a.ficouDevendo) },
        { rotulo: I.pendenciasEmAberto, valor: lerMoedas(a.pendenciasEmAberto) },
        { rotulo: I.pendenciasPagas, valor: lerMoedas(a.pendenciasPagas) },
      ],
      [
        { rotulo: R.acertos, origem: lerOrigem(a.origem) },
        { rotulo: R.pendencias, origem: lerOrigem(a.origemDasPendencias) },
      ],
    ),
    cartao(
      'previa',
      T.cartoes.previa,
      [
        { rotulo: I.vendedores, quantidade: numero(p.linhas) },
        { rotulo: I.vendido, valor: lerMoedas(p.vendido) },
        { rotulo: I.liquidoPrevisto, valor: lerMoedas(p.liquidoPrevisto) },
        { rotulo: I.comissaoPrevista, valor: lerMoedas(p.comissaoPrevista) },
      ],
      [],
      { semCotacao: p.semCotacao === true },
    ),
    cartao(
      'vendas',
      T.cartoes.vendas,
      [
        { rotulo: I.vendas, quantidade: numero(v.quantidade) },
        { rotulo: I.vendido, valor: lerMoedas(v.vendido) },
        { rotulo: I.descontos, valor: lerMoedas(v.descontos) },
        { rotulo: I.devolvido, valor: lerMoedas(v.devolvido) },
        { rotulo: I.liquido, valor: lerMoedas(v.liquido) },
      ],
      [{ rotulo: R.vendas, origem: lerOrigem(v.origem) }],
    ),
    cartao(
      'trocas',
      T.cartoes.trocas,
      [
        { rotulo: I.trocas, quantidade: numero(tr.trocas) },
        { rotulo: I.valorDasTrocas, valor: lerMoedas(tr.valorDasTrocas) },
        { rotulo: I.creditosGerados, valor: lerMoedas(tr.creditosGerados) },
        { rotulo: I.creditosUsados, valor: lerMoedas(tr.creditosUsados) },
        { rotulo: I.creditosEmAberto, valor: lerMoedas(tr.creditosEmAberto) },
        { rotulo: I.diferencaAPagar, valor: lerMoedas(tr.diferencaAPagar) },
        { rotulo: I.ficouDeCredito, valor: lerMoedas(tr.ficouDeCredito) },
      ],
      [
        { rotulo: R.trocas, origem: lerOrigem(tr.origemDasTrocas) },
        { rotulo: R.creditos, origem: lerOrigem(tr.origemDosCreditos) },
      ],
    ),
    cartao(
      'recebido',
      T.cartoes.recebido,
      [
        { rotulo: I.dinheiro, valor: lerMoedas(rc.dinheiro) },
        { rotulo: I.transferencia, valor: lerMoedas(rc.transferencia) },
        { rotulo: I.boleto, valor: lerMoedas(rc.boleto) },
        { rotulo: I.total, valor: lerMoedas(rc.total) },
      ],
      [{ rotulo: R.recebimentos, origem: lerOrigem(rc.origem) }],
    ),
    cartao(
      'total',
      T.cartoes.total,
      [
        { rotulo: I.maletas, quantidade: numero(tt.maletas) },
        { rotulo: I.pecas, quantidade: numero(tt.pecas) },
        { rotulo: I.vendas, quantidade: numero(tt.vendas) },
        { rotulo: I.enviado, valor: lerMoedas(tt.enviado) },
        { rotulo: I.vendido, valor: lerMoedas(tt.vendido) },
        { rotulo: I.descontos, valor: lerMoedas(tt.descontos) },
        { rotulo: I.liquido, valor: lerMoedas(tt.liquido) },
      ],
      [],
    ),
  ];
}

export function lerPainel(bruto: unknown): PainelLido {
  const o = objeto(bruto);
  const s = objeto(o.semana);
  const totais = objeto(o.totais);
  const tt = objeto(totais.total);
  return {
    semana:
      typeof s.id === 'string'
        ? {
            id: s.id,
            numero: numero(s.numero),
            inicio: texto(s.inicio),
            fim: texto(s.fim),
            situacao: texto(s.situacao),
          }
        : null,
    linhas: (Array.isArray(o.pedidos) ? o.pedidos : []).flatMap((l) => {
      const x = lerLinha(l);
      return x ? [x] : [];
    }),
    cartoes: cartoesDosTotais(totais),
    total: {
      enviado: lerMoedas(tt.enviado),
      vendido: lerMoedas(tt.vendido),
      liquido: lerMoedas(tt.liquido),
    },
    previaIncluiSemanasAnteriores: o.previaIncluiSemanasAnteriores === true,
    avisoDaPrevia: typeof o.avisoDaPrevia === 'string' && o.avisoDaPrevia ? o.avisoDaPrevia : null,
  };
}

/** Relatório do app que mostra os registros de cada rota da API (o fim do caminho). */
const RELATORIO_DA_ROTA: Record<string, NomeDoRelatorio> = {
  'financeiro/acertos': 'financeiro-acertos',
  'financeiro/pendencias': 'financeiro-pendencias',
  'financeiro/recebimentos-por-forma': 'financeiro-recebimentos',
  'maleta/remessas': 'maleta-remessas',
  'vendas/vendas': 'vendas-lista',
  'devolucoes/trocas': 'devolucao-trocas',
  'devolucoes/creditos': 'devolucao-creditos',
};

export interface RegistrosParaAbrir {
  relatorio: NomeDoRelatorio;
  /** Só os filtros que a tela de relatório entende: semana, vendedor, praça e período. */
  filtros: Partial<Record<'semanaId' | 'vendedorId' | 'pracaId' | 'de' | 'ate', string>>;
}

/** Para onde o clique num total leva: o relatório de onde o número saiu, já com os mesmos filtros. */
export function registrosDaOrigem(origem: Origem): RegistrosParaAbrir | null {
  const m = /^\/api\/dona\/relatorios\/(.+)$/.exec(origem.rota);
  const relatorio = m ? RELATORIO_DA_ROTA[m[1]!] : undefined;
  if (!relatorio) return null;
  const filtros: RegistrosParaAbrir['filtros'] = {};
  for (const chave of ['semanaId', 'vendedorId', 'pracaId', 'de', 'ate'] as const) {
    const v = origem.filtros[chave];
    if (v) filtros[chave] = v;
  }
  return { relatorio, filtros };
}

/** Linhas do painel como o motor de exportação espera: Dólar e Guarani já vêm da API, por linha. */
export function pedidoDeExportacao(
  painel: PainelLido,
  contexto: { filtrosEmPalavras: string[]; geradoEm: string; geradoPor: string },
): PedidoDeExportacao {
  const campos = ['enviado', 'devolvido', 'vendidoLiquido', 'comissao', 'liquido'] as const;
  const linhas = painel.linhas.map((l) => {
    const x: Record<string, unknown> = {
      vendedor: l.vendedor,
      praca: l.praca,
      situacao: T.situacoes[l.situacao] ?? l.situacao,
      previa: l.previa ? 'Sim' : 'Não',
      enviadoPecas: l.enviadoPecas,
      devolvidoPecas: l.devolvidoPecas,
      vendidasPecas: l.vendidasPecas,
      dataDaCotacao: l.dataDaCotacao ?? '',
    };
    for (const c of campos) {
      const m = l[c];
      x[c] = m?.real ?? '';
      x[`${c}Dolar`] = m?.dolar ?? '';
      x[`${c}Guarani`] = m?.guarani ?? '';
    }
    return x;
  });
  const dinheiro = (chave: string, titulo: string) => ({
    chave,
    titulo,
    tipo: 'dinheiro' as const,
    convertido: { dolar: `${chave}Dolar`, guarani: `${chave}Guarani`, data: 'dataDaCotacao' },
  });
  const t = painel.total;
  const totais = [
    { chave: 'enviado', rotulo: 'Total enviado', valor: t.enviado },
    { chave: 'vendidoLiquido', rotulo: 'Total vendido líquido', valor: t.vendido },
    { chave: 'liquido', rotulo: 'Total líquido', valor: t.liquido },
  ];
  return {
    loja: T.loja,
    titulo: T.tituloDoArquivo,
    filtros: contexto.filtrosEmPalavras,
    geradoEm: contexto.geradoEm,
    geradoPor: contexto.geradoPor,
    linhaDaCotacao: 'Dólar e Guarani pela cotação do dia de cada lançamento.',
    dataDaCotacao: null,
    cotacao: null,
    colunas: [
      { chave: 'vendedor', titulo: T.colunas.vendedor, tipo: 'texto' },
      { chave: 'praca', titulo: T.colunas.praca, tipo: 'texto' },
      { chave: 'situacao', titulo: T.colunas.situacao, tipo: 'texto' },
      { chave: 'previa', titulo: T.previa, tipo: 'texto' },
      { chave: 'enviadoPecas', titulo: 'Peças enviadas', tipo: 'inteiro' },
      dinheiro('enviado', T.colunas.enviado),
      { chave: 'devolvidoPecas', titulo: 'Peças devolvidas', tipo: 'inteiro' },
      dinheiro('devolvido', T.colunas.devolvido),
      { chave: 'vendidasPecas', titulo: 'Peças vendidas', tipo: 'inteiro' },
      dinheiro('vendidoLiquido', 'Vendido líquido'),
      dinheiro('comissao', T.colunas.comissao),
      dinheiro('liquido', T.colunas.liquido),
      { chave: 'dataDaCotacao', titulo: 'Data da cotação', tipo: 'data' },
    ],
    linhas,
    totais: totais.map((x) => ({
      chave: x.chave,
      rotulo: x.rotulo,
      quantidade: painel.linhas.length,
      valorEmReais: x.valor?.real ?? null,
    })),
    totaisDeValor: { enviado: 'enviado', vendidoLiquido: 'vendidoLiquido', liquido: 'liquido' },
    totaisConvertidos: Object.fromEntries(
      totais.flatMap((x) =>
        x.valor && x.valor.dolar !== null && x.valor.guarani !== null
          ? [[x.chave, { dolar: x.valor.dolar, guarani: x.valor.guarani }]]
          : [],
      ),
    ),
  };
}
