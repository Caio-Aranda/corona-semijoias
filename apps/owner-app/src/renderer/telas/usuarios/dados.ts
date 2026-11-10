/** Formatos que a API de usuários devolve, lidos com cuidado: o que não bate vira "sem item". */

export interface Usuario {
  id: string;
  version: number;
  nome: string;
  login: string;
  perfil: string;
  vendedorId: string | null;
  ativo: boolean;
}

export interface PaginaDeUsuarios {
  itens: Usuario[];
  pagina: number;
  tamanho: number;
  total: number;
}

export interface VendedorParaEscolha {
  id: string;
  nome: string;
  codigo: number | null;
}

export interface SenhaCriada {
  nome: string;
  senha: string;
  /** dd/mm/aaaa */
  validaAte: string | null;
}

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : {};

export function lerUsuario(bruto: unknown): Usuario | null {
  const u = objeto(bruto);
  if (typeof u.id !== 'string' || typeof u.nome !== 'string' || typeof u.login !== 'string') {
    return null;
  }
  return {
    id: u.id,
    version: typeof u.version === 'number' ? u.version : 0,
    nome: u.nome,
    login: u.login,
    perfil: typeof u.perfil === 'string' ? u.perfil : '',
    vendedorId: typeof u.vendedorId === 'string' ? u.vendedorId : null,
    ativo: u.ativo !== false,
  };
}

export function lerPaginaDeUsuarios(bruto: unknown): PaginaDeUsuarios {
  const p = objeto(bruto);
  const itens = (Array.isArray(p.itens) ? p.itens : [])
    .map(lerUsuario)
    .filter((u): u is Usuario => u !== null);
  return {
    itens,
    pagina: typeof p.pagina === 'number' ? p.pagina : 0,
    tamanho: typeof p.tamanho === 'number' && p.tamanho > 0 ? p.tamanho : 20,
    total: typeof p.total === 'number' ? p.total : itens.length,
  };
}

export function lerVendedores(bruto: unknown): VendedorParaEscolha[] {
  const p = objeto(bruto);
  return (Array.isArray(p.itens) ? p.itens : [])
    .map((v): VendedorParaEscolha | null => {
      const o = objeto(v);
      if (typeof o.id !== 'string' || typeof o.nome !== 'string') return null;
      return { id: o.id, nome: o.nome, codigo: typeof o.codigo === 'number' ? o.codigo : null };
    })
    .filter((v): v is VendedorParaEscolha => v !== null);
}

/** "2026-11-17T12:00:00Z" → "17/11/2026" (data de Brasília). */
export function dataDeBrasilia(instante: string): string | null {
  const d = new Date(instante);
  if (Number.isNaN(d.getTime())) return null;
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: 'America/Sao_Paulo',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(d);
}

export function lerSenhaCriada(bruto: unknown, nomePadrao: string): SenhaCriada | null {
  const o = objeto(bruto);
  if (typeof o.senhaTemporaria !== 'string' || o.senhaTemporaria === '') return null;
  return {
    nome: typeof o.nome === 'string' && o.nome ? o.nome : nomePadrao,
    senha: o.senhaTemporaria,
    validaAte:
      typeof o.senhaTemporariaVenceEm === 'string'
        ? dataDeBrasilia(o.senhaTemporariaVenceEm)
        : null,
  };
}
