/** O fechamento gravado de um acerto fechado, lido com cuidado. Só leitura: nada aqui calcula dinheiro. */
import type { CotacaoParaTela } from '../../nucleo/moedas';
import { inteiro, lerProdutoDoAcerto, lista, objeto, texto, type ProdutoDoAcerto } from './dados';
import { dinheiro, type LinhaDeComissao } from './previa';

export interface ItemFechado {
  produto: ProdutoDoAcerto;
  esperado: number;
  contadoBoa: number;
  contadoDefeito: number;
  falta: number;
  defeitoAprovado: number;
}

export interface PendenciaFechada {
  tipo: string;
  produto: ProdutoDoAcerto | null;
  quantidade: number;
  valor: string;
  status: string;
}

export interface RecebimentoFechado {
  forma: string;
  valor: string;
  estornado: boolean;
  recebidoEm: string;
}

export interface ContaFechada {
  numero: number;
  totalDeParcelas: number;
  valor: string;
  vencimento: string;
  formaPrevista: string;
  status: string;
}

export interface FechamentoGravado {
  fechadoEm: string;
  cotacaoData: string;
  /** A cotação gravada no acerto (nunca a do dia); nula se o acerto não a trouxe. */
  cotacao: CotacaoParaTela | null;
  metaGuarani: number;
  vendidoParaMetaGuarani: number;
  percentualComissao: string;
  vendidoBruto: string;
  descontos: string;
  devolvido: string;
  vendidoLiquido: string;
  comissao: string;
  faltasCobradas: string;
  liquido: string;
  creditoUsado: string;
  recebidoNoAcerto: string;
  ficouDevendo: string;
  pagoAoVendedor: string;
  itens: ItemFechado[];
  comissoes: LinhaDeComissao[];
  pendencias: PendenciaFechada[];
  recebimentos: RecebimentoFechado[];
  contas: ContaFechada[];
}

const numero = (v: unknown): number | null =>
  typeof v === 'number' && Number.isFinite(v) ? v : null;

const CAMPOS_EM_REAIS = [
  'vendidoBruto',
  'descontos',
  'devolvido',
  'vendidoLiquido',
  'comissao',
  'faltasCobradas',
  'liquido',
  'creditoUsado',
  'recebidoNoAcerto',
  'ficouDevendo',
  'pagoAoVendedor',
] as const;

/** Lê o fechamento da ficha; fora da forma vira `null` (a tela mostra um aviso simples e nunca um número inventado). */
export function lerFechamento(bruto: unknown): FechamentoGravado | null {
  const o = objeto(bruto);
  const valores: Record<string, string> = {};
  for (const k of CAMPOS_EM_REAIS) {
    const v = dinheiro(o[k]);
    if (v === null) return null;
    valores[k] = v;
  }
  const meta = numero(o.metaGuarani);
  const vendidoMeta = numero(o.vendidoParaMetaGuarani);
  if (meta === null || vendidoMeta === null || typeof o.percentualComissao !== 'string') {
    return null;
  }
  const itens: ItemFechado[] = [];
  for (const b of lista(o.itens)) {
    const i = objeto(b);
    const produto = lerProdutoDoAcerto(i.produto);
    if (!produto) return null;
    itens.push({
      produto,
      esperado: inteiro(i.esperado),
      contadoBoa: inteiro(i.contadoBoa),
      contadoDefeito: inteiro(i.contadoDefeito),
      falta: inteiro(i.falta),
      defeitoAprovado: inteiro(i.defeitoAprovado),
    });
  }
  const comissoes: LinhaDeComissao[] = [];
  for (const b of lista(o.comissoes)) {
    const c = objeto(b);
    const base = dinheiro(c.base);
    const valor = dinheiro(c.valor);
    if (base === null || valor === null) return null;
    comissoes.push({
      vendaId: texto(c.vendaId),
      tipo: texto(c.tipo),
      base,
      percentual: texto(c.percentual),
      valor,
    });
  }
  const pendencias: PendenciaFechada[] = [];
  for (const b of lista(o.pendencias)) {
    const p = objeto(b);
    const valor = dinheiro(p.valor);
    if (valor === null) return null;
    pendencias.push({
      tipo: texto(p.tipo),
      produto: lerProdutoDoAcerto(p.produto),
      quantidade: inteiro(p.quantidade),
      valor,
      status: texto(p.status),
    });
  }
  const recebimentos: RecebimentoFechado[] = [];
  for (const b of lista(o.recebimentos)) {
    const r = objeto(b);
    const valor = dinheiro(r.valor);
    if (valor === null) return null;
    recebimentos.push({
      forma: texto(r.forma),
      valor,
      estornado: r.estornado === true,
      recebidoEm: texto(r.recebidoEm),
    });
  }
  const contas: ContaFechada[] = [];
  for (const b of lista(o.contas)) {
    const c = objeto(b);
    const valor = dinheiro(c.valor);
    if (valor === null) return null;
    contas.push({
      numero: inteiro(c.numero),
      totalDeParcelas: inteiro(c.totalDeParcelas),
      valor,
      vencimento: texto(c.vencimento),
      formaPrevista: texto(c.formaPrevista),
      status: texto(c.status),
    });
  }
  return {
    fechadoEm: texto(o.fechadoEm),
    cotacaoData: texto(o.cotacaoData),
    cotacao:
      texto(o.usdVenda) !== '' && texto(o.pygVenda) !== ''
        ? { dolar: texto(o.usdVenda), guarani: texto(o.pygVenda) }
        : null,
    metaGuarani: meta,
    vendidoParaMetaGuarani: vendidoMeta,
    percentualComissao: o.percentualComissao,
    vendidoBruto: valores.vendidoBruto!,
    descontos: valores.descontos!,
    devolvido: valores.devolvido!,
    vendidoLiquido: valores.vendidoLiquido!,
    comissao: valores.comissao!,
    faltasCobradas: valores.faltasCobradas!,
    liquido: valores.liquido!,
    creditoUsado: valores.creditoUsado!,
    recebidoNoAcerto: valores.recebidoNoAcerto!,
    ficouDevendo: valores.ficouDevendo!,
    pagoAoVendedor: valores.pagoAoVendedor!,
    itens,
    comissoes,
    pendencias,
    recebimentos,
    contas,
  };
}
