/** Formatos que a API de produtos devolve, lidos com cuidado. */
import type { TipoDeCodigo } from '../../../shared/produtos';
import { TIPOS_DE_CODIGO } from '../../../shared/produtos';

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');

export interface CodigoDoProduto {
  id: string;
  codigo: string;
  tipo: TipoDeCodigo;
  fornecedorId?: string;
  principal: boolean;
  ativo: boolean;
}

export interface FotoPrincipal {
  hash: string;
}

export interface Produto {
  id: string;
  version: number;
  nome: string;
  referencia: string;
  categoriaId: string;
  fornecedorId?: string;
  precoVenda: string;
  precoCusto: string | null;
  estoqueMinimo: number;
  estoqueAtual: number;
  ativo: boolean;
  codigos: CodigoDoProduto[];
  fotoPrincipal: FotoPrincipal | null;
}

export interface FotoDoProduto {
  id: string;
  hash: string;
  principal: boolean;
  ordem: number;
}

export interface PaginaDeProdutos {
  itens: Produto[];
  pagina: number;
  tamanho: number;
  total: number;
}

/** O valor em dinheiro chega como número ou texto; a tela só trabalha com texto "12.50". */
export function dinheiroEmTexto(v: unknown): string | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v.toFixed(2);
  if (typeof v === 'string' && /^\d+(\.\d+)?$/.test(v)) {
    const [i, c = ''] = v.split('.');
    return `${i}.${c.padEnd(2, '0').slice(0, 2)}`;
  }
  return null;
}

function lerCodigo(bruto: unknown): CodigoDoProduto | null {
  const o = objeto(bruto);
  const tipo = (TIPOS_DE_CODIGO as readonly unknown[]).includes(o.tipo)
    ? (o.tipo as TipoDeCodigo)
    : null;
  if (typeof o.id !== 'string' || typeof o.codigo !== 'string' || !tipo) return null;
  return {
    id: o.id,
    codigo: o.codigo,
    tipo,
    ...(typeof o.fornecedorId === 'string' ? { fornecedorId: o.fornecedorId } : {}),
    principal: o.principal === true,
    ativo: o.ativo !== false,
  };
}

export function lerProduto(bruto: unknown): Produto | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  const foto = objeto(o.fotoPrincipal);
  return {
    id: o.id,
    version: typeof o.version === 'number' ? o.version : 0,
    nome: texto(o.nome),
    referencia: texto(o.referencia),
    categoriaId: texto(o.categoriaId),
    ...(typeof o.fornecedorId === 'string' ? { fornecedorId: o.fornecedorId } : {}),
    precoVenda: dinheiroEmTexto(o.precoVenda) ?? '0.00',
    precoCusto: dinheiroEmTexto(o.precoCusto),
    estoqueMinimo: typeof o.estoqueMinimo === 'number' ? o.estoqueMinimo : 0,
    estoqueAtual: typeof o.estoqueAtual === 'number' ? o.estoqueAtual : 0,
    ativo: o.ativo !== false,
    codigos: (Array.isArray(o.codigos) ? o.codigos : [])
      .map(lerCodigo)
      .filter((c): c is CodigoDoProduto => c !== null),
    fotoPrincipal: typeof foto.hash === 'string' ? { hash: foto.hash } : null,
  };
}

export function lerPaginaDeProdutos(bruto: unknown): PaginaDeProdutos {
  const o = objeto(bruto);
  const itens = (Array.isArray(o.itens) ? o.itens : [])
    .map(lerProduto)
    .filter((p): p is Produto => p !== null);
  return {
    itens,
    pagina: typeof o.pagina === 'number' ? o.pagina : 0,
    tamanho: typeof o.tamanho === 'number' ? o.tamanho : 20,
    total: typeof o.total === 'number' ? o.total : itens.length,
  };
}

export function lerFotos(bruto: unknown): FotoDoProduto[] {
  return (Array.isArray(bruto) ? bruto : [])
    .map((f) => objeto(f))
    .filter((f) => typeof f.id === 'string' && typeof f.hash === 'string')
    .map((f) => ({
      id: f.id as string,
      hash: f.hash as string,
      principal: f.principal === true,
      ordem: typeof f.ordem === 'number' ? f.ordem : 0,
    }))
    .sort((a, b) => a.ordem - b.ordem);
}

export function codigoPrincipal(p: Produto): string {
  return (
    (p.codigos.find((c) => c.principal && c.ativo) ?? p.codigos.find((c) => c.ativo))?.codigo ?? ''
  );
}

/** Itens de uma lista da API que têm `id` e `nome` (categorias, fornecedores). */
export function lerOpcoes(bruto: unknown): { id: string; nome: string; ativo: boolean }[] {
  const itens = objeto(bruto).itens;
  return (Array.isArray(itens) ? itens : [])
    .map(objeto)
    .filter((o) => typeof o.id === 'string')
    .map((o) => ({
      id: o.id as string,
      nome: texto(o.nome) || texto(o.razaoSocial),
      ativo: o.ativo !== false,
    }));
}

export function lerProximoCodigo(bruto: unknown): string {
  return texto(objeto(bruto).codigo);
}
