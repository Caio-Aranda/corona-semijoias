import type { OperacaoDaApi } from './ponte';

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const ehObjeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DECIMAL = /^-?\d{1,14}(\.\d{1,2})?$/;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
const PERFIS = ['ADMIN', 'VENDEDOR'];
/** Tamanho máximo dos textos; o servidor confere de novo o que é regra de negócio. */
const MAX_TEXTO = 200;

export interface ParametrosDeUsuarioNovo {
  nome: string;
  login: string;
  perfil: string;
  vendedorId?: string;
}

export interface ParametrosDeUsuarioEditado {
  id: string;
  nome: string;
  perfil: string;
  vendedorId?: string;
  version: number;
}

export interface ParametrosDaLista {
  busca?: string;
  perfil?: string;
  ativo?: boolean;
  pagina: number;
  tamanho: number;
}

export type ParametrosConferidos =
  | { operacao: 'eu' | 'cotacao.hoje' | 'avisos.listar' | 'vendedores.escolha' }
  | { operacao: 'usuarios.listar'; lista: ParametrosDaLista }
  | { operacao: 'usuarios.criar'; novo: ParametrosDeUsuarioNovo }
  | { operacao: 'usuarios.editar'; editado: ParametrosDeUsuarioEditado }
  | { operacao: 'usuarios.desativar' | 'usuarios.reativar' | 'usuarios.redefinirSenha'; id: string }
  | { operacao: 'cotacao.converter'; valorEmReais: string; data?: string };

function textoOpcional(v: unknown): string | undefined | null {
  if (v === undefined || v === '') return undefined;
  return ehTexto(v) && v.length <= MAX_TEXTO ? v : null;
}

function vendedorOpcional(v: unknown): string | undefined | null {
  if (v === undefined || v === null || v === '') return undefined;
  return ehTexto(v) && UUID.test(v) ? v : null;
}

/** Confere os dados de cada operação; o que não bate vira `null` e a chamada é recusada. */
export function conferirParametros(
  operacao: OperacaoDaApi,
  bruto: unknown,
): ParametrosConferidos | null {
  const p = bruto === undefined ? {} : bruto;
  if (!ehObjeto(p)) return null;
  switch (operacao) {
    case 'eu':
    case 'cotacao.hoje':
    case 'avisos.listar':
    case 'vendedores.escolha':
      return { operacao };
    case 'usuarios.listar': {
      const busca = textoOpcional(p.busca);
      const perfil = textoOpcional(p.perfil);
      if (busca === null || perfil === null) return null;
      if (perfil !== undefined && !PERFIS.includes(perfil)) return null;
      if (p.ativo !== undefined && typeof p.ativo !== 'boolean') return null;
      const pagina = p.pagina ?? 0;
      const tamanho = p.tamanho ?? 20;
      if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
      if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
      if (tamanho < 1 || tamanho > 100) return null;
      const lista: ParametrosDaLista = { pagina, tamanho };
      if (busca) lista.busca = busca;
      if (perfil) lista.perfil = perfil;
      if (typeof p.ativo === 'boolean') lista.ativo = p.ativo;
      return { operacao, lista };
    }
    case 'usuarios.criar': {
      if (!ehTexto(p.nome) || !ehTexto(p.login) || !ehTexto(p.perfil)) return null;
      if (p.nome.length > MAX_TEXTO || p.login.length > MAX_TEXTO) return null;
      if (!PERFIS.includes(p.perfil)) return null;
      const vendedorId = vendedorOpcional(p.vendedorId);
      if (vendedorId === null) return null;
      const novo: ParametrosDeUsuarioNovo = { nome: p.nome, login: p.login, perfil: p.perfil };
      if (vendedorId) novo.vendedorId = vendedorId;
      return { operacao, novo };
    }
    case 'usuarios.editar': {
      if (!ehTexto(p.id) || !UUID.test(p.id)) return null;
      if (!ehTexto(p.nome) || p.nome.length > MAX_TEXTO || !ehTexto(p.perfil)) return null;
      if (!PERFIS.includes(p.perfil)) return null;
      if (typeof p.version !== 'number' || !Number.isInteger(p.version) || p.version < 0)
        return null;
      const vendedorId = vendedorOpcional(p.vendedorId);
      if (vendedorId === null) return null;
      const editado: ParametrosDeUsuarioEditado = {
        id: p.id,
        nome: p.nome,
        perfil: p.perfil,
        version: p.version,
      };
      if (vendedorId) editado.vendedorId = vendedorId;
      return { operacao, editado };
    }
    case 'usuarios.desativar':
    case 'usuarios.reativar':
    case 'usuarios.redefinirSenha':
      return ehTexto(p.id) && UUID.test(p.id) ? { operacao, id: p.id } : null;
    case 'cotacao.converter': {
      if (!ehTexto(p.valorEmReais) || !DECIMAL.test(p.valorEmReais)) return null;
      if (p.data !== undefined && (!ehTexto(p.data) || !DATA.test(p.data))) return null;
      return p.data === undefined
        ? { operacao, valorEmReais: p.valorEmReais }
        : { operacao, valorEmReais: p.valorEmReais, data: p.data };
    }
  }
}
