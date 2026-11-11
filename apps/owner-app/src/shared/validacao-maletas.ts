/** Conferência, no processo principal, dos dados das operações de maletas. */
import {
  MAXIMO_DA_QUANTIDADE_NA_MALETA,
  MAXIMO_DO_MOTIVO_DA_MALETA,
  SITUACOES_DA_MALETA,
  TIPOS_DE_MALETA,
  type OperacaoDeMaletas,
  type TipoDeMaleta,
} from './maletas';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const idValido = (v: unknown): v is string => typeof v === 'string' && UUID.test(v);
const inteiroNaoNegativo = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 0;

export type ParametrosDeMaleta =
  | {
      operacao: 'maletas.criar';
      corpo: {
        id: string;
        vendedorId: string;
        semanaId: string;
        pracaId?: string;
        tipo: TipoDeMaleta;
      };
    }
  | { operacao: 'maletas.ver' | 'maletas.enviar'; id: string }
  | {
      operacao: 'maletas.listar';
      lista: {
        vendedorId?: string;
        semanaId?: string;
        status?: string;
        pagina: number;
        tamanho: number;
      };
    }
  | {
      operacao: 'maletas.colocarPeca';
      id: string;
      produtoId: string;
      quantidade: number;
      version: number;
    }
  | { operacao: 'maletas.tirarPeca'; id: string; produtoId: string; version: number }
  | { operacao: 'maletas.excluir'; id: string; version: number }
  | { operacao: 'maletas.emAberto'; vendedorId: string }
  | { operacao: 'maletas.saldo'; id: string }
  | {
      operacao: 'maletas.devolver';
      id: string;
      itens: { id: string; produtoId: string; quantidade: number }[];
    }
  | {
      operacao: 'maletas.desfazerDevolucao';
      id: string;
      devolucaoId: string;
      estornoId: string;
      motivo: string;
    }
  | { operacao: 'maletas.cancelar'; id: string; motivo: string };

function motivoValido(v: unknown): v is string {
  return typeof v === 'string' && v.trim() !== '' && v.length <= MAXIMO_DO_MOTIVO_DA_MALETA;
}

export function conferirMaleta(
  operacao: OperacaoDeMaletas,
  p: Record<string, unknown>,
): ParametrosDeMaleta | null {
  const soCom = (...nomes: string[]) => Object.keys(p).every((k) => nomes.includes(k));
  switch (operacao) {
    case 'maletas.criar': {
      if (!soCom('id', 'vendedorId', 'semanaId', 'pracaId', 'tipo')) return null;
      if (!idValido(p.id) || !idValido(p.vendedorId) || !idValido(p.semanaId)) return null;
      if (!(TIPOS_DE_MALETA as readonly unknown[]).includes(p.tipo)) return null;
      const corpo: Extract<ParametrosDeMaleta, { operacao: 'maletas.criar' }>['corpo'] = {
        id: p.id,
        vendedorId: p.vendedorId,
        semanaId: p.semanaId,
        tipo: p.tipo as TipoDeMaleta,
      };
      if (p.pracaId !== undefined && p.pracaId !== '') {
        if (!idValido(p.pracaId)) return null;
        corpo.pracaId = p.pracaId;
      }
      return { operacao, corpo };
    }
    case 'maletas.ver':
    case 'maletas.saldo':
    case 'maletas.enviar':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'maletas.emAberto':
      return soCom('vendedorId') && idValido(p.vendedorId)
        ? { operacao, vendedorId: p.vendedorId }
        : null;
    case 'maletas.excluir':
      return soCom('id', 'version') && idValido(p.id) && inteiroNaoNegativo(p.version)
        ? { operacao, id: p.id, version: p.version }
        : null;
    case 'maletas.tirarPeca':
      return soCom('id', 'produtoId', 'version') &&
        idValido(p.id) &&
        idValido(p.produtoId) &&
        inteiroNaoNegativo(p.version)
        ? { operacao, id: p.id, produtoId: p.produtoId, version: p.version }
        : null;
    case 'maletas.colocarPeca': {
      if (!soCom('id', 'produtoId', 'quantidade', 'version')) return null;
      if (!idValido(p.id) || !idValido(p.produtoId) || !inteiroNaoNegativo(p.version)) return null;
      if (
        typeof p.quantidade !== 'number' ||
        !Number.isInteger(p.quantidade) ||
        p.quantidade < 1 ||
        p.quantidade > MAXIMO_DA_QUANTIDADE_NA_MALETA
      ) {
        return null;
      }
      return {
        operacao,
        id: p.id,
        produtoId: p.produtoId,
        quantidade: p.quantidade,
        version: p.version,
      };
    }
    case 'maletas.cancelar':
      return soCom('id', 'motivo') && idValido(p.id) && motivoValido(p.motivo)
        ? { operacao, id: p.id, motivo: p.motivo.trim() }
        : null;
    case 'maletas.desfazerDevolucao':
      return soCom('id', 'devolucaoId', 'estornoId', 'motivo') &&
        idValido(p.id) &&
        idValido(p.devolucaoId) &&
        idValido(p.estornoId) &&
        motivoValido(p.motivo)
        ? {
            operacao,
            id: p.id,
            devolucaoId: p.devolucaoId,
            estornoId: p.estornoId,
            motivo: p.motivo.trim(),
          }
        : null;
    case 'maletas.devolver': {
      if (!soCom('id', 'itens') || !idValido(p.id) || !Array.isArray(p.itens)) return null;
      if (p.itens.length < 1 || p.itens.length > 500) return null;
      const itens: { id: string; produtoId: string; quantidade: number }[] = [];
      for (const bruto of p.itens as unknown[]) {
        const i = (typeof bruto === 'object' && bruto !== null ? bruto : {}) as Record<
          string,
          unknown
        >;
        if (Object.keys(i).some((k) => !['id', 'produtoId', 'quantidade'].includes(k))) return null;
        if (!idValido(i.id) || !idValido(i.produtoId)) return null;
        const q = i.quantidade;
        if (
          typeof q !== 'number' ||
          !Number.isInteger(q) ||
          q < 1 ||
          q > MAXIMO_DA_QUANTIDADE_NA_MALETA
        ) {
          return null;
        }
        itens.push({ id: i.id, produtoId: i.produtoId, quantidade: q });
      }
      return { operacao, id: p.id, itens };
    }
    case 'maletas.listar': {
      if (!soCom('vendedorId', 'semanaId', 'status', 'pagina', 'tamanho')) return null;
      const pagina = p.pagina ?? 0;
      const tamanho = p.tamanho ?? 50;
      if (!inteiroNaoNegativo(pagina) || !inteiroNaoNegativo(tamanho)) return null;
      if (tamanho < 1 || tamanho > 200) return null;
      const lista: Extract<ParametrosDeMaleta, { operacao: 'maletas.listar' }>['lista'] = {
        pagina,
        tamanho,
      };
      for (const k of ['vendedorId', 'semanaId'] as const) {
        const v = p[k];
        if (v !== undefined && v !== '') {
          if (!idValido(v)) return null;
          lista[k] = v;
        }
      }
      if (p.status !== undefined && p.status !== '') {
        if (!(SITUACOES_DA_MALETA as readonly unknown[]).includes(p.status)) return null;
        lista.status = p.status as string;
      }
      return { operacao, lista };
    }
  }
}
