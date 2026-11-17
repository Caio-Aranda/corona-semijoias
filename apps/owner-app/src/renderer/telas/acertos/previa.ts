/** A prévia do fechamento: tudo vem pronto da API; a tela só lê (conferindo a forma) e formata. */
import { inteiro, lerProdutoDoAcerto, lista, objeto, texto, type ProdutoDoAcerto } from './dados';

const DINHEIRO = /^-?\d{1,12}(\.\d{1,2})?$/;
/** Valor em Real como texto da API; qualquer outra coisa é `null` (a tela nunca inventa um número). */
export const dinheiro = (v: unknown): string | null =>
  typeof v === 'string' && DINHEIRO.test(v) ? v : null;

export interface PecaDaPrevia {
  produto: ProdutoDoAcerto;
  remessaId: string;
  esperado: number;
  contadoBoa: number;
  contadoDefeito: number;
  falta: number;
  valorDaFalta: string;
}

export interface FaltaDaPrevia {
  produto: ProdutoDoAcerto;
  quantidade: number;
  valor: string;
  status: string;
}

export interface NaoAceitaDaPrevia {
  tipo: string;
  origemTipo: string;
  origemId: string;
  valor: string;
}

export interface AntigaDaPrevia {
  id: string;
  tipo: string;
  produtoId: string;
  quantidade: number;
  valor: string;
}

export interface LinhaDeComissao {
  vendaId: string;
  tipo: string;
  base: string;
  percentual: string;
  valor: string;
}

export interface ParcelaDaPrevia {
  numero: number;
  vencimento: string;
  valor: string;
}

export interface ContaAbertaDoVendedor {
  id: string;
  origem: string;
  numero: number;
  totalDeParcelas: number;
  vencimento: string;
  valor: string;
  emAberto: string;
}

/** Tudo que a prévia mostra. Os números vêm prontos da API; a tela só os formata. */
export interface PreviaDoFechamento {
  itens: PecaDaPrevia[];
  sobras: { produtoId: string; esperado: number; contado: number }[];
  faltas: FaltaDaPrevia[];
  defeitos: { produto: ProdutoDoAcerto; contadoDefeito: number }[];
  eventos: { tipo: string; valor: string }[];
  revisoesPendentes: { tipo: string; id: string }[];
  naoAceitas: NaoAceitaDaPrevia[];
  pendenciasAntigas: AntigaDaPrevia[];
  comissoes: LinhaDeComissao[];
  metaGuarani: number;
  vendidoParaMetaGuarani: number;
  /** Quanto falta para a meta, como a API calculou (Guarani inteiro e o Real equivalente); `null` se a API não mandou. */
  faltaParaMetaGuarani: number | null;
  faltaParaMetaReais: string | null;
  percentualComissao: string;
  vendidoBruto: string;
  descontos: string;
  devolvido: string;
  vendidoLiquido: string;
  comissao: string;
  faltasCobradas: string;
  liquido: string;
  creditoDisponivel: string;
  creditoUsado: string;
  aPagarNoAcerto: string;
  recebidoNoAcerto: string;
  boleto: string;
  ficouDevendo: string;
  pagoAoVendedor: string;
  restanteSemData: boolean;
  parcelasDoRestante: ParcelaDaPrevia[];
  contasAbertasDoVendedor: ContaAbertaDoVendedor[];
  cotacaoId: string;
  cotacaoData: string;
  podeFechar: boolean;
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
  'creditoDisponivel',
  'creditoUsado',
  'aPagarNoAcerto',
  'recebidoNoAcerto',
  'boleto',
  'ficouDevendo',
  'pagoAoVendedor',
] as const;

/**
 * Lê a prévia conferindo a forma. Resposta fora da forma (campo que falta, valor que não é dinheiro) vira `null`:
 * a tela mostra "Não deu para ler o acerto" e nunca um número inventado.
 */
export function lerPrevia(bruto: unknown): PreviaDoFechamento | null {
  const o = objeto(bruto);
  const valores: Record<string, string> = {};
  for (const k of CAMPOS_EM_REAIS) {
    const v = dinheiro(o[k]);
    if (v === null) return null;
    valores[k] = v;
  }
  const meta = numero(o.metaGuarani);
  const vendidoMeta = numero(o.vendidoParaMetaGuarani);
  if (meta === null || vendidoMeta === null) return null;
  if (typeof o.percentualComissao !== 'string') return null;
  if (typeof o.podeFechar !== 'boolean' || typeof o.restanteSemData !== 'boolean') return null;
  for (const k of ['itens', 'sobras', 'faltas', 'defeitos', 'comissoes']) {
    if (!Array.isArray(o[k])) return null;
  }

  const itens: PecaDaPrevia[] = [];
  for (const b of lista(o.itens)) {
    const i = objeto(b);
    const produto = lerProdutoDoAcerto(i.produto);
    const valor = dinheiro(i.valorDaFalta);
    if (!produto || valor === null) return null;
    itens.push({
      produto,
      remessaId: texto(i.remessaId),
      esperado: inteiro(i.esperado),
      contadoBoa: inteiro(i.contadoBoa),
      contadoDefeito: inteiro(i.contadoDefeito),
      falta: inteiro(i.falta),
      valorDaFalta: valor,
    });
  }
  const faltas: FaltaDaPrevia[] = [];
  for (const b of lista(o.faltas)) {
    const f = objeto(b);
    const produto = lerProdutoDoAcerto(f.produto);
    const valor = dinheiro(f.valor);
    if (!produto || valor === null) return null;
    faltas.push({ produto, quantidade: inteiro(f.quantidade), valor, status: texto(f.status) });
  }
  const defeitos: PreviaDoFechamento['defeitos'] = [];
  for (const b of lista(o.defeitos)) {
    const d = objeto(b);
    const produto = lerProdutoDoAcerto(d.produto);
    if (!produto) return null;
    defeitos.push({ produto, contadoDefeito: inteiro(d.contadoDefeito) });
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
  const naoAceitas: NaoAceitaDaPrevia[] = [];
  for (const b of lista(o.naoAceitas)) {
    const n = objeto(b);
    const valor = dinheiro(n.valor);
    if (typeof n.origemId !== 'string' || valor === null) return null;
    naoAceitas.push({
      tipo: texto(n.tipo),
      origemTipo: texto(n.origemTipo),
      origemId: n.origemId,
      valor,
    });
  }
  const pendenciasAntigas: AntigaDaPrevia[] = [];
  for (const b of lista(o.pendenciasAntigas)) {
    const a = objeto(b);
    const valor = dinheiro(a.valor);
    if (typeof a.id !== 'string' || valor === null) return null;
    pendenciasAntigas.push({
      id: a.id,
      tipo: texto(a.tipo),
      produtoId: texto(a.produtoId),
      quantidade: inteiro(a.quantidade),
      valor,
    });
  }
  const parcelasDoRestante: ParcelaDaPrevia[] = [];
  for (const b of lista(o.parcelasDoRestante)) {
    const p = objeto(b);
    const valor = dinheiro(p.valor);
    if (valor === null) return null;
    parcelasDoRestante.push({ numero: inteiro(p.numero), vencimento: texto(p.vencimento), valor });
  }
  const contasAbertasDoVendedor: ContaAbertaDoVendedor[] = [];
  for (const b of lista(o.contasAbertasDoVendedor)) {
    const c = objeto(b);
    const valor = dinheiro(c.valor);
    const emAberto = dinheiro(c.emAberto);
    if (typeof c.id !== 'string' || valor === null || emAberto === null) return null;
    contasAbertasDoVendedor.push({
      id: c.id,
      origem: texto(c.origem),
      numero: inteiro(c.numero),
      totalDeParcelas: inteiro(c.totalDeParcelas),
      vencimento: texto(c.vencimento),
      valor,
      emAberto,
    });
  }
  const eventos: PreviaDoFechamento['eventos'] = [];
  for (const b of lista(o.eventos)) {
    const e = objeto(b);
    const valor = dinheiro(e.valor);
    if (valor === null) return null;
    eventos.push({ tipo: texto(e.tipo), valor });
  }
  // O que falta para a meta é só um aviso a mais: se vier faltando ou fora da forma, a prévia abre sem a linha.
  const falta = numero(o.faltaParaMetaGuarani);
  const faltaEmReais = dinheiro(o.faltaParaMetaReais);
  const sabeOQueFalta =
    falta !== null && Number.isInteger(falta) && falta >= 0 && faltaEmReais !== null;
  return {
    itens,
    sobras: lista(o.sobras).map((b) => {
      const s = objeto(b);
      return {
        produtoId: texto(s.produtoId),
        esperado: inteiro(s.esperado),
        contado: inteiro(s.contado),
      };
    }),
    faltas,
    defeitos,
    eventos,
    revisoesPendentes: lista(o.revisoesPendentes).map((b) => {
      const r = objeto(b);
      return { tipo: texto(r.tipo), id: texto(r.id) };
    }),
    naoAceitas,
    pendenciasAntigas,
    comissoes,
    metaGuarani: meta,
    vendidoParaMetaGuarani: vendidoMeta,
    faltaParaMetaGuarani: sabeOQueFalta ? falta : null,
    faltaParaMetaReais: sabeOQueFalta ? faltaEmReais : null,
    percentualComissao: o.percentualComissao,
    vendidoBruto: valores.vendidoBruto!,
    descontos: valores.descontos!,
    devolvido: valores.devolvido!,
    vendidoLiquido: valores.vendidoLiquido!,
    comissao: valores.comissao!,
    faltasCobradas: valores.faltasCobradas!,
    liquido: valores.liquido!,
    creditoDisponivel: valores.creditoDisponivel!,
    creditoUsado: valores.creditoUsado!,
    aPagarNoAcerto: valores.aPagarNoAcerto!,
    recebidoNoAcerto: valores.recebidoNoAcerto!,
    boleto: valores.boleto!,
    ficouDevendo: valores.ficouDevendo!,
    pagoAoVendedor: valores.pagoAoVendedor!,
    restanteSemData: o.restanteSemData,
    parcelasDoRestante,
    contasAbertasDoVendedor,
    cotacaoId: texto(o.cotacaoId),
    cotacaoData: texto(o.cotacaoData),
    podeFechar: o.podeFechar,
  };
}

// --- As decisões da dona (o que a tela guarda e manda; nada de dinheiro calculado) ---

export type DecisaoDeCobranca = 'COBRAR_NO_ACERTO' | 'COBRAR_DEPOIS' | 'LOJA_ASSUME';

export interface Decisoes {
  faltas: { produto: ProdutoDoAcerto; decisao: DecisaoDeCobranca; motivo: string }[];
  defeitos: { produto: ProdutoDoAcerto; contados: number; aprovadas: number }[];
  cobrancas: { origemId: string; decisao: DecisaoDeCobranca; motivo: string }[];
  pendenciasAntigas: { pendenciaId: string; decisao: 'COBRAR_NO_ACERTO' | 'MANTER' }[];
}

/**
 * As decisões começam como as do servidor (cobrar as faltas agora, nenhum defeito aprovado) e cobrem exatamente o que a prévia
 * sem decisões lista: o servidor só aceita fechar se as decisões forem essas. Só vale a primeira prévia (a sem decisões).
 */
export function decisoesIniciais(p: PreviaDoFechamento): Decisoes {
  const vistos = new Set<string>();
  const faltas: Decisoes['faltas'] = [];
  for (const i of p.itens) {
    if (i.falta > 0 && !vistos.has(i.produto.id)) {
      vistos.add(i.produto.id);
      faltas.push({ produto: i.produto, decisao: 'COBRAR_NO_ACERTO', motivo: '' });
    }
  }
  return {
    faltas,
    defeitos: p.defeitos.map((d) => ({
      produto: d.produto,
      contados: d.contadoDefeito,
      aprovadas: 0,
    })),
    cobrancas: p.naoAceitas.map((n) => ({
      origemId: n.origemId,
      decisao: 'COBRAR_NO_ACERTO',
      motivo: '',
    })),
    pendenciasAntigas: p.pendenciasAntigas.map((a) => ({ pendenciaId: a.id, decisao: 'MANTER' })),
  };
}

/** "Loja assume" sem motivo ainda não pode ir para o servidor. */
export function decisoesCompletas(d: Decisoes): boolean {
  return [...d.faltas, ...d.cobrancas].every(
    (x) => x.decisao !== 'LOJA_ASSUME' || x.motivo.trim() !== '',
  );
}

/** O pedido de prévia e de fechar: só as decisões; nenhum total, comissão ou percentual. */
export function pedidoDeDecisoes(d: Decisoes): Record<string, unknown> {
  const comMotivo = (x: { decisao: DecisaoDeCobranca; motivo: string }) =>
    x.decisao === 'LOJA_ASSUME' ? { motivo: x.motivo.trim() } : {};
  return {
    faltas: d.faltas.map((x) => ({ produtoId: x.produto.id, decisao: x.decisao, ...comMotivo(x) })),
    defeitos: d.defeitos.map((x) => ({ produtoId: x.produto.id, aprovadas: x.aprovadas })),
    cobrancas: d.cobrancas.map((x) => ({
      origemId: x.origemId,
      decisao: x.decisao,
      ...comMotivo(x),
    })),
    pendenciasAntigas: d.pendenciasAntigas,
  };
}
