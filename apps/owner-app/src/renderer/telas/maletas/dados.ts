/** Formatos que a API de maletas devolve, lidos com cuidado (nada aqui confia no formato). */
import { centavosDeTexto } from '../../nucleo/moedas';
import { dinheiroEmTexto } from '../produtos/dados';

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');
const inteiro = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

export interface ItemDaMaleta {
  produtoId: string;
  nome: string;
  codigo: string;
  fotoHash: string | null;
  quantidade: number;
  precoUnitario: string;
  total: string;
}

export interface Maleta {
  id: string;
  version: number;
  vendedorId: string;
  vendedorNome: string;
  semanaId: string;
  pracaId: string;
  semanaNumero: number;
  pracaNome: string;
  tipo: string;
  status: string;
  totalPecas: number;
  totalValor: string;
  criadaEm: string;
  enviadaEm: string;
  itens: ItemDaMaleta[];
  avisos: string[];
}

export function lerMaleta(bruto: unknown): Maleta | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  const itens: ItemDaMaleta[] = [];
  for (const b of Array.isArray(o.itens) ? o.itens : []) {
    const i = objeto(b);
    const p = objeto(i.produto);
    const foto = objeto(p.fotoPrincipal);
    if (typeof p.id !== 'string') continue;
    itens.push({
      produtoId: p.id,
      nome: texto(p.nome),
      codigo: texto(p.codigo),
      fotoHash: typeof foto.hash === 'string' ? foto.hash : null,
      quantidade: inteiro(i.quantidade),
      precoUnitario: dinheiroEmTexto(i.precoUnitario) ?? '0.00',
      total: dinheiroEmTexto(i.total) ?? '0.00',
    });
  }
  return {
    id: o.id,
    version: inteiro(o.version),
    vendedorId: texto(o.vendedorId),
    vendedorNome: texto(o.vendedorNome),
    semanaId: texto(o.semanaId),
    pracaId: texto(o.pracaId),
    semanaNumero: inteiro(o.semanaNumero),
    pracaNome: texto(o.pracaNome),
    tipo: texto(o.tipo),
    status: texto(o.status),
    totalPecas: inteiro(o.totalPecas),
    totalValor: dinheiroEmTexto(o.totalValor) ?? '0.00',
    criadaEm: texto(o.criadaEm),
    enviadaEm: texto(o.enviadaEm),
    itens,
    avisos: (Array.isArray(o.avisos) ? o.avisos : []).filter(
      (a): a is string => typeof a === 'string',
    ),
  };
}

export function lerPaginaDeMaletas(bruto: unknown): Maleta[] {
  const itens = objeto(bruto).itens;
  return (Array.isArray(itens) ? itens : []).map(lerMaleta).filter((m): m is Maleta => m !== null);
}

export interface SituacaoDoLimite {
  /** Em centavos; null = "Sem limite" ou limite ainda não definido. Zero vale zero. */
  limite: bigint | null;
  /** A dona marcou "Sem limite" de propósito. */
  semLimite: boolean;
  /** Falso = limite ainda não definido: o servidor recusa a maleta até a dona definir. */
  definido: boolean;
  /** Valor que o vendedor já tem em maletas enviadas. */
  jaComEle: bigint;
}

/** Limite e valor em aberto do vendedor, como o servidor calculou (zero vale zero; "Sem limite" é explícito). */
export function lerSituacaoDoLimite(bruto: unknown): SituacaoDoLimite {
  const o = objeto(bruto);
  const limite = centavosDeTexto(dinheiroEmTexto(o.limite) ?? '');
  const semLimite = o.semLimite === true;
  return {
    limite: semLimite ? null : limite,
    semLimite,
    definido: semLimite || (o.limiteDefinido !== false && limite !== null),
    jaComEle: centavosDeTexto(dinheiroEmTexto(o.valorEmAberto) ?? '') ?? 0n,
  };
}

/** Quanto sobra do limite com esta maleta dentro (negativo = passou). Sem limite: null. */
export function sobraDoLimite(situacao: SituacaoDoLimite, estaMaleta: bigint): bigint | null {
  return situacao.limite === null ? null : situacao.limite - situacao.jaComEle - estaMaleta;
}

export interface OpcaoDeVendedor {
  id: string;
  nome: string;
  pracaId: string;
}

export function lerVendedoresParaEscolher(bruto: unknown): OpcaoDeVendedor[] {
  const itens = objeto(bruto).itens;
  return (Array.isArray(itens) ? itens : [])
    .map(objeto)
    .filter((v) => typeof v.id === 'string' && v.ativo !== false)
    .map((v) => ({ id: v.id as string, nome: texto(v.nome), pracaId: texto(v.pracaId) }));
}

export interface OpcaoDeSemana {
  id: string;
  numero: string;
  aberta: boolean;
}

export function lerSemanasParaEscolher(bruto: unknown): OpcaoDeSemana[] {
  const itens = objeto(bruto).itens;
  return (Array.isArray(itens) ? itens : [])
    .map(objeto)
    .filter((s) => typeof s.id === 'string')
    .map((s) => ({
      id: s.id as string,
      numero: typeof s.numero === 'number' || typeof s.numero === 'string' ? String(s.numero) : '',
      aberta: s.situacao !== 'FECHADA',
    }));
}

export interface LinhaDoSaldo {
  produtoId: string;
  nome: string;
  codigo: string;
  fotoHash: string | null;
  enviada: number;
  devolvida: number;
  saldo: number;
  precoUnitario: string;
  valorEmAberto: string;
}

export interface MovimentoDaMaleta {
  id: string;
  /** DEVOLUCAO_DONA, os estornos dela ou (nas fases seguintes) outros tipos. */
  tipo: string;
  produtoId: string;
  produtoNome: string;
  /** Sinal do servidor: devolução à dona é negativa. */
  quantidade: number;
  estornaId: string;
  desfeito: boolean;
  em: string;
}

export interface SaldoDaMaleta {
  status: string;
  motivoDoCancelamento: string;
  pecasEmAberto: number;
  valorEmAberto: string;
  linhas: LinhaDoSaldo[];
  movimentos: MovimentoDaMaleta[];
}

/** O que há na maleta, peça a peça, e os movimentos em ordem (devoluções e as desfeitas). */
export function lerSaldoDaMaleta(bruto: unknown): SaldoDaMaleta | null {
  const o = objeto(bruto);
  if (typeof o.status !== 'string') return null;
  const linhas: LinhaDoSaldo[] = [];
  for (const b of Array.isArray(o.itens) ? o.itens : []) {
    const l = objeto(b);
    const p = objeto(l.produto);
    if (typeof p.id !== 'string') continue;
    const foto = objeto(p.fotoPrincipal);
    linhas.push({
      produtoId: p.id,
      nome: texto(p.nome),
      codigo: texto(p.codigo),
      fotoHash: typeof foto.hash === 'string' ? foto.hash : null,
      enviada: inteiro(l.enviada),
      devolvida: inteiro(l.devolvida),
      saldo: inteiro(l.saldo),
      precoUnitario: dinheiroEmTexto(l.precoUnitario) ?? '0.00',
      valorEmAberto: dinheiroEmTexto(l.valorEmAberto) ?? '0.00',
    });
  }
  const movimentos: MovimentoDaMaleta[] = [];
  for (const b of Array.isArray(o.movimentos) ? o.movimentos : []) {
    const m = objeto(b);
    if (typeof m.id !== 'string') continue;
    movimentos.push({
      id: m.id,
      tipo: texto(m.tipo),
      produtoId: texto(m.produtoId),
      produtoNome: texto(m.produtoNome),
      quantidade: inteiro(m.quantidade),
      estornaId: texto(m.estornaId),
      desfeito: m.desfeito === true,
      em: texto(m.em),
    });
  }
  return {
    status: o.status,
    motivoDoCancelamento: texto(o.motivoDoCancelamento),
    pecasEmAberto: inteiro(o.pecasEmAberto),
    valorEmAberto: dinheiroEmTexto(o.valorEmAberto) ?? '0.00',
    linhas,
    movimentos,
  };
}

export interface LinhaDaConferencia {
  produtoId: string;
  produtoNome: string;
  esperado: number;
  contado: number;
  diferenca: number;
}

/** O que o vendedor contou ao conferir a maleta (Fase 4): o que devia haver, o que ele contou e a diferença. */
export interface ConferenciaDaMaleta {
  conferidaEm: string;
  divergencias: number;
  itens: LinhaDaConferencia[];
}

export function lerConferenciaDaMaleta(bruto: unknown): ConferenciaDaMaleta | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  const itens: LinhaDaConferencia[] = [];
  for (const b of Array.isArray(o.itens) ? o.itens : []) {
    const l = objeto(b);
    if (typeof l.produtoId !== 'string') continue;
    itens.push({
      produtoId: l.produtoId,
      produtoNome: texto(l.produtoNome),
      esperado: inteiro(l.esperado),
      contado: inteiro(l.contado),
      diferenca: inteiro(l.diferenca),
    });
  }
  return { conferidaEm: texto(o.conferidaEm), divergencias: inteiro(o.divergencias), itens };
}

export interface MaletaEmAberto {
  id: string;
  semanaNumero: number;
  tipo: string;
  enviadaEm: string;
  pecasEmAberto: number;
  valorEmAberto: string;
}

export interface EmAbertoDoVendedor {
  vendedorId: string;
  vendedorNome: string;
  /** Em centavos; null = "Sem limite" ou ainda não definido. */
  limite: bigint | null;
  semLimite: boolean;
  definido: boolean;
  valorEmAberto: bigint;
  /** Quanto sobra do limite; null quando não há limite. Pode ser negativo (passou). */
  sobra: bigint | null;
  maletas: MaletaEmAberto[];
}

export function lerEmAberto(bruto: unknown): EmAbertoDoVendedor {
  const o = objeto(bruto);
  const situacao = lerSituacaoDoLimite(o);
  const restante = centavosDeTexto(dinheiroEmTexto(o.limiteRestante) ?? '');
  const maletas: MaletaEmAberto[] = [];
  for (const b of Array.isArray(o.maletas) ? o.maletas : []) {
    const m = objeto(b);
    if (typeof m.id !== 'string') continue;
    maletas.push({
      id: m.id,
      semanaNumero: inteiro(m.semanaNumero),
      tipo: texto(m.tipo),
      enviadaEm: texto(m.enviadaEm),
      pecasEmAberto: inteiro(m.pecasEmAberto),
      valorEmAberto: dinheiroEmTexto(m.valorEmAberto) ?? '0.00',
    });
  }
  return {
    vendedorId: texto(o.vendedorId),
    vendedorNome: texto(o.vendedorNome),
    limite: situacao.limite,
    semLimite: situacao.semLimite,
    definido: situacao.definido,
    valorEmAberto: situacao.jaComEle,
    sobra: situacao.limite === null ? null : (restante ?? situacao.limite - situacao.jaComEle),
    maletas,
  };
}
