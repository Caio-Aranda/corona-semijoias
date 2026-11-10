/** Conferência, no processo principal, dos dados das operações de compras. */
import {
  MAXIMO_DAS_OBSERVACOES,
  MAXIMO_DA_QUANTIDADE,
  MAXIMO_DE_ITENS_DA_COMPRA,
  MAXIMO_DO_MOTIVO,
  MAXIMO_DO_NUMERO_DA_NOTA,
  SITUACOES_DA_COMPRA,
  type OperacaoDeCompras,
} from './compras';

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const ehObjeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const idValido = (v: unknown): v is string => ehTexto(v) && UUID.test(v);

export interface ItemParaReceber {
  produtoId?: string;
  codigo?: string;
  quantidade: number;
  custoUnitario: string;
}

export interface ParametrosDeCompra {
  operacao: OperacaoDeCompras;
  id?: string;
  lista?: {
    de?: string;
    ate?: string;
    fornecedorId?: string;
    situacao?: (typeof SITUACOES_DA_COMPRA)[number];
    pagina: number;
    tamanho: number;
  };
  corpo?: {
    id: string;
    fornecedorId: string;
    dataCompra: string;
    numero?: string;
    observacoes?: string;
    itens: ItemParaReceber[];
  };
  /** Identificador do pedido de desfazer (torna o reenvio seguro). */
  pedidoId?: string;
  motivo?: string;
}

function conferirItem(v: unknown): ItemParaReceber | null {
  if (!ehObjeto(v)) return null;
  const permitidos = ['produtoId', 'codigo', 'quantidade', 'custoUnitario'];
  if (Object.keys(v).some((k) => !permitidos.includes(k))) return null;
  const temProduto = v.produtoId !== undefined;
  const temCodigo = v.codigo !== undefined;
  if (temProduto === temCodigo) return null; // um OU outro
  if (typeof v.quantidade !== 'number' || !Number.isInteger(v.quantidade)) return null;
  if (v.quantidade < 1 || v.quantidade > MAXIMO_DA_QUANTIDADE) return null;
  if (!ehTexto(v.custoUnitario) || !DINHEIRO.test(v.custoUnitario)) return null;
  const item: ItemParaReceber = { quantidade: v.quantidade, custoUnitario: v.custoUnitario };
  if (temProduto) {
    if (!idValido(v.produtoId)) return null;
    item.produtoId = v.produtoId;
  } else {
    if (!ehTexto(v.codigo) || v.codigo.trim() === '' || v.codigo.length > 60) return null;
    item.codigo = v.codigo;
  }
  return item;
}

export function conferirCompra(
  operacao: OperacaoDeCompras,
  p: Record<string, unknown>,
): ParametrosDeCompra | null {
  const soCom = (...nomes: string[]) => Object.keys(p).every((k) => nomes.includes(k));
  switch (operacao) {
    case 'compras.listar': {
      if (!soCom('de', 'ate', 'fornecedorId', 'situacao', 'pagina', 'tamanho')) return null;
      const pagina = p.pagina ?? 0;
      const tamanho = p.tamanho ?? 20;
      if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
      if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
      if (tamanho < 1 || tamanho > 100) return null;
      const lista: NonNullable<ParametrosDeCompra['lista']> = { pagina, tamanho };
      for (const k of ['de', 'ate'] as const) {
        const valor = p[k];
        if (valor !== undefined && valor !== '') {
          if (!ehTexto(valor) || !DATA.test(valor)) return null;
          lista[k] = valor;
        }
      }
      if (p.fornecedorId !== undefined && p.fornecedorId !== '') {
        if (!idValido(p.fornecedorId)) return null;
        lista.fornecedorId = p.fornecedorId;
      }
      if (p.situacao !== undefined && p.situacao !== '') {
        if (!(SITUACOES_DA_COMPRA as readonly unknown[]).includes(p.situacao)) return null;
        lista.situacao = p.situacao as (typeof SITUACOES_DA_COMPRA)[number];
      }
      return { operacao, lista };
    }
    case 'compras.ver':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'compras.desfazer': {
      if (!soCom('id', 'pedidoId', 'motivo') || !idValido(p.id) || !idValido(p.pedidoId)) {
        return null;
      }
      if (!ehTexto(p.motivo) || p.motivo.trim() === '' || p.motivo.length > MAXIMO_DO_MOTIVO) {
        return null;
      }
      return { operacao, id: p.id, pedidoId: p.pedidoId, motivo: p.motivo.trim() };
    }
    case 'compras.receber': {
      if (!soCom('id', 'fornecedorId', 'dataCompra', 'numero', 'observacoes', 'itens')) return null;
      if (!idValido(p.id) || !idValido(p.fornecedorId)) return null;
      if (!ehTexto(p.dataCompra) || !DATA.test(p.dataCompra)) return null;
      if (!Array.isArray(p.itens) || p.itens.length === 0) return null;
      if (p.itens.length > MAXIMO_DE_ITENS_DA_COMPRA) return null;
      const itens: ItemParaReceber[] = [];
      for (const bruto of p.itens) {
        const item = conferirItem(bruto);
        if (!item) return null;
        itens.push(item);
      }
      const corpo: NonNullable<ParametrosDeCompra['corpo']> = {
        id: p.id,
        fornecedorId: p.fornecedorId,
        dataCompra: p.dataCompra,
        itens,
      };
      if (p.numero !== undefined && p.numero !== '') {
        if (!ehTexto(p.numero) || p.numero.length > MAXIMO_DO_NUMERO_DA_NOTA) return null;
        corpo.numero = p.numero;
      }
      if (p.observacoes !== undefined && p.observacoes !== '') {
        if (!ehTexto(p.observacoes) || p.observacoes.length > MAXIMO_DAS_OBSERVACOES) return null;
        corpo.observacoes = p.observacoes;
      }
      return { operacao, corpo };
    }
  }
}
