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
  /** Em centavos; null = vendedor sem limite cadastrado. */
  limite: bigint | null;
  /** Valor que o vendedor já tem em maletas enviadas. */
  jaComEle: bigint;
}

/** Limite e valor em aberto do vendedor, como o servidor calculou (limite 0 = sem limite cadastrado). */
export function lerSituacaoDoLimite(bruto: unknown): SituacaoDoLimite {
  const o = objeto(bruto);
  const limite = centavosDeTexto(dinheiroEmTexto(o.limite) ?? '');
  return {
    limite: limite === null || limite === 0n ? null : limite,
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
