/** Formatos que a API de compras devolve, lidos com cuidado (nada aqui confia no formato). */
import { dinheiroEmTexto } from '../produtos/dados';

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');
const inteiro = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

export interface ResumoDaCompra {
  id: string;
  fornecedorId: string;
  fornecedorNome: string;
  /** "2026-11-10". */
  dataCompra: string;
  numero: string;
  /** Dinheiro em texto com 2 casas, como o servidor calculou. */
  total: string;
  situacao: 'REGISTRADA' | 'ESTORNADA';
  itens: number;
  pecas: number;
}

export interface ItemDaCompra {
  id: string;
  produtoId: string;
  nome: string;
  codigo: string;
  fotoHash: string | null;
  quantidade: number;
  custoUnitario: string;
  total: string;
}

export interface CompraGravada {
  resumo: ResumoDaCompra;
  observacoes: string;
  usuarioNome: string;
  motivoDeDesfeita: string;
  desfeitaEm: string;
  itens: ItemDaCompra[];
}

export interface PaginaDeCompras {
  itens: ResumoDaCompra[];
  pagina: number;
  tamanho: number;
  total: number;
}

export function lerResumo(bruto: unknown): ResumoDaCompra | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  return {
    id: o.id,
    fornecedorId: texto(o.fornecedorId),
    fornecedorNome: texto(o.fornecedorNome),
    dataCompra: texto(o.dataCompra),
    numero: texto(o.numero),
    total: dinheiroEmTexto(o.total) ?? '0.00',
    situacao: o.situacao === 'ESTORNADA' ? 'ESTORNADA' : 'REGISTRADA',
    itens: inteiro(o.itens),
    pecas: inteiro(o.pecas),
  };
}

export function lerPaginaDeCompras(bruto: unknown): PaginaDeCompras {
  const o = objeto(bruto);
  const itens = (Array.isArray(o.itens) ? o.itens : [])
    .map(lerResumo)
    .filter((r): r is ResumoDaCompra => r !== null);
  return {
    itens,
    pagina: inteiro(o.pagina),
    tamanho: typeof o.tamanho === 'number' ? o.tamanho : 20,
    total: typeof o.total === 'number' ? o.total : itens.length,
  };
}

export function lerCompra(bruto: unknown): CompraGravada | null {
  const o = objeto(bruto);
  const resumo = lerResumo(o.compra);
  if (!resumo) return null;
  const itens: ItemDaCompra[] = [];
  for (const bruto of Array.isArray(o.itens) ? o.itens : []) {
    const i = objeto(bruto);
    const p = objeto(i.produto);
    const foto = objeto(p.fotoPrincipal);
    if (typeof i.id !== 'string') continue;
    itens.push({
      id: i.id,
      produtoId: texto(p.id),
      nome: texto(p.nome),
      codigo: texto(p.codigo),
      fotoHash: typeof foto.hash === 'string' ? foto.hash : null,
      quantidade: inteiro(i.quantidade),
      custoUnitario: dinheiroEmTexto(i.custoUnitario) ?? '0.00',
      total: dinheiroEmTexto(i.total) ?? '0.00',
    });
  }
  return {
    resumo,
    observacoes: texto(o.observacoes),
    usuarioNome: texto(o.usuarioNome),
    motivoDeDesfeita: texto(o.motivoDeDesfeita),
    desfeitaEm: texto(o.desfeitaEm),
    itens,
  };
}
