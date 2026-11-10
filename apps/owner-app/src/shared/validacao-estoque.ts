/** Conferência, no processo principal, dos dados das operações de estoque e de contagem. */
import {
  ESCOPOS_DA_CONTAGEM,
  FILTROS_DOS_ITENS,
  MAXIMO_DAS_OBSERVACOES_DA_CONTAGEM,
  MAXIMO_DA_QUANTIDADE_DO_ESTOQUE,
  MAXIMO_DO_MOTIVO_DO_ESTOQUE,
  SITUACOES_DA_CONTAGEM,
  TIPOS_DE_LANCAMENTO,
  TIPOS_DO_FILTRO,
  type OperacaoDeEstoque,
} from './estoque';

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
const idValido = (v: unknown): v is string => ehTexto(v) && UUID.test(v);
const inteiro = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const quantidadeValida = (v: unknown): v is number =>
  inteiro(v) && v >= 1 && v <= MAXIMO_DA_QUANTIDADE_DO_ESTOQUE;

export interface ParametrosDeEstoque {
  operacao: OperacaoDeEstoque;
  /** Produto, movimento ou contagem, conforme a operação. */
  id?: string;
  /** Filtros e página (vão na consulta). */
  consulta?: Record<string, string | number>;
  /** Corpo do pedido. */
  corpo?: Record<string, unknown>;
}

function motivoDe(v: unknown, obrigatorio: boolean): string | undefined | null {
  if (v === undefined || v === null || v === '') return obrigatorio ? null : undefined;
  if (!ehTexto(v) || v.length > MAXIMO_DO_MOTIVO_DO_ESTOQUE) return null;
  const limpo = v.trim();
  if (limpo === '') return obrigatorio ? null : undefined;
  return limpo;
}

/** Página e tamanho, mais os filtros que a operação aceita (texto, id, data ou lista fechada). */
function conferirConsulta(
  p: Record<string, unknown>,
  filtros: Record<string, 'id' | 'data' | 'texto' | readonly string[]>,
): Record<string, string | number> | null {
  const permitidos = ['pagina', 'tamanho', 'id', ...Object.keys(filtros)];
  if (Object.keys(p).some((k) => !permitidos.includes(k))) return null;
  const pagina = p.pagina ?? 0;
  const tamanho = p.tamanho ?? 20;
  if (!inteiro(pagina) || pagina < 0 || !inteiro(tamanho) || tamanho < 1 || tamanho > 100) {
    return null;
  }
  const consulta: Record<string, string | number> = { pagina, tamanho };
  for (const [nome, tipo] of Object.entries(filtros)) {
    const v = p[nome];
    if (v === undefined || v === '') continue;
    if (!ehTexto(v)) return null;
    if (tipo === 'id' && !idValido(v)) return null;
    if (tipo === 'data' && !DATA.test(v)) return null;
    if (tipo === 'texto' && v.length > 200) return null;
    if (Array.isArray(tipo) && !tipo.includes(v)) return null;
    consulta[nome] = v;
  }
  return consulta;
}

export function conferirEstoque(
  operacao: OperacaoDeEstoque,
  p: Record<string, unknown>,
): ParametrosDeEstoque | null {
  const soCom = (...nomes: string[]) => Object.keys(p).every((k) => nomes.includes(k));
  switch (operacao) {
    case 'estoque.ficha': {
      const consulta = conferirConsulta(p, {});
      return consulta && idValido(p.id) ? { operacao, id: p.id, consulta } : null;
    }
    case 'estoque.lancamentos': {
      const consulta = conferirConsulta(p, {
        produtoId: 'id',
        tipo: TIPOS_DO_FILTRO,
        usuarioId: 'id',
        de: 'data',
        ate: 'data',
      });
      if (!consulta || p.id !== undefined) return null;
      return { operacao, consulta };
    }
    case 'estoque.acabando': {
      const consulta = conferirConsulta(p, {
        categoriaId: 'id',
        fornecedorId: 'id',
        busca: 'texto',
      });
      if (!consulta || p.id !== undefined) return null;
      return { operacao, consulta };
    }
    case 'estoque.lancar': {
      if (!soCom('id', 'produtoId', 'tipo', 'quantidade', 'motivo')) return null;
      if (!idValido(p.id) || !idValido(p.produtoId)) return null;
      if (!(TIPOS_DE_LANCAMENTO as readonly unknown[]).includes(p.tipo)) return null;
      if (!quantidadeValida(p.quantidade)) return null;
      const motivo = motivoDe(p.motivo, false);
      if (motivo === null) return null;
      return {
        operacao,
        corpo: {
          id: p.id,
          produtoId: p.produtoId,
          tipo: p.tipo,
          quantidade: p.quantidade,
          ...(motivo ? { motivo } : {}),
        },
      };
    }
    case 'estoque.ajustar': {
      if (!soCom('id', 'produtoId', 'novaQuantidade', 'diferenca', 'motivo')) return null;
      if (!idValido(p.id) || !idValido(p.produtoId)) return null;
      const temNova = p.novaQuantidade !== undefined;
      const temDif = p.diferenca !== undefined;
      if (temNova === temDif) return null; // uma OU outra
      const motivo = motivoDe(p.motivo, true);
      if (!motivo) return null;
      const corpo: Record<string, unknown> = { id: p.id, produtoId: p.produtoId, motivo };
      if (temNova) {
        const n = p.novaQuantidade;
        if (!inteiro(n) || n < 0 || n > MAXIMO_DA_QUANTIDADE_DO_ESTOQUE) return null;
        corpo.novaQuantidade = n;
      } else {
        const d = p.diferenca;
        if (!inteiro(d) || d === 0 || Math.abs(d) > MAXIMO_DA_QUANTIDADE_DO_ESTOQUE) return null;
        corpo.diferenca = d;
      }
      return { operacao, corpo };
    }
    case 'estoque.desfazer': {
      if (!soCom('id', 'pedidoId', 'motivo') || !idValido(p.id) || !idValido(p.pedidoId)) {
        return null;
      }
      const motivo = motivoDe(p.motivo, true);
      if (!motivo) return null;
      return { operacao, id: p.id, corpo: { id: p.pedidoId, motivo } };
    }
    case 'contagens.listar': {
      const consulta = conferirConsulta(p, { situacao: SITUACOES_DA_CONTAGEM });
      if (!consulta || p.id !== undefined) return null;
      return { operacao, consulta };
    }
    case 'contagens.comecar': {
      if (!soCom('id', 'escopo', 'escopoId', 'observacoes') || !idValido(p.id)) return null;
      if (!(ESCOPOS_DA_CONTAGEM as readonly unknown[]).includes(p.escopo)) return null;
      const corpo: Record<string, unknown> = { id: p.id, escopo: p.escopo };
      if (p.escopo === 'TODOS') {
        if (p.escopoId !== undefined && p.escopoId !== '') return null;
      } else {
        if (!idValido(p.escopoId)) return null;
        corpo.escopoId = p.escopoId;
      }
      if (p.observacoes !== undefined && p.observacoes !== '') {
        if (!ehTexto(p.observacoes) || p.observacoes.length > MAXIMO_DAS_OBSERVACOES_DA_CONTAGEM) {
          return null;
        }
        corpo.observacoes = p.observacoes;
      }
      return { operacao, corpo };
    }
    case 'contagens.ver':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'contagens.itens': {
      const consulta = conferirConsulta(p, { filtro: FILTROS_DOS_ITENS, busca: 'texto' });
      return consulta && idValido(p.id) ? { operacao, id: p.id, consulta } : null;
    }
    case 'contagens.ler':
    case 'contagens.corrigir': {
      const ler = operacao === 'contagens.ler';
      if (!soCom('id', 'leituraId', 'codigo', 'produtoId', ler ? 'quantidade' : 'contado')) {
        return null;
      }
      if (!idValido(p.id) || !idValido(p.leituraId)) return null;
      const temCodigo = p.codigo !== undefined;
      const temProduto = p.produtoId !== undefined;
      if (temCodigo === temProduto) return null;
      const corpo: Record<string, unknown> = { id: p.leituraId };
      if (temCodigo) {
        if (!ehTexto(p.codigo) || p.codigo.trim() === '' || p.codigo.length > 60) return null;
        corpo.codigo = p.codigo.trim();
      } else {
        if (!idValido(p.produtoId)) return null;
        corpo.produtoId = p.produtoId;
      }
      if (ler) {
        if (p.quantidade !== undefined) {
          if (!quantidadeValida(p.quantidade)) return null;
          corpo.quantidade = p.quantidade;
        }
      } else {
        const c = p.contado;
        if (!inteiro(c) || c < 0 || c > MAXIMO_DA_QUANTIDADE_DO_ESTOQUE) return null;
        corpo.contado = c;
      }
      return { operacao, id: p.id, corpo };
    }
    case 'contagens.fechar':
      if (!soCom('id', 'zerarNaoLidos') || !idValido(p.id)) return null;
      if (p.zerarNaoLidos !== undefined && typeof p.zerarNaoLidos !== 'boolean') return null;
      return { operacao, id: p.id, corpo: { zerarNaoLidos: p.zerarNaoLidos === true } };
    case 'contagens.cancelar': {
      if (!soCom('id', 'motivo') || !idValido(p.id)) return null;
      const motivo = motivoDe(p.motivo, false);
      if (motivo === null) return null;
      return { operacao, id: p.id, corpo: motivo ? { motivo } : {} };
    }
  }
}
