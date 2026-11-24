/** Conferência, no processo principal, dos dados das operações do financeiro (só a forma; o servidor tem a palavra final). */
import {
  FORMAS_DO_FINANCEIRO,
  MAXIMO_DA_DESCRICAO_DO_CAIXA,
  MAXIMO_DO_MOTIVO_DO_FINANCEIRO,
  MAXIMO_DO_NOME_DA_CATEGORIA,
  NIVEIS_DA_CONTA_A_RECEBER,
  SITUACOES_DA_CONTA_A_PAGAR,
  SITUACOES_DA_CONTA_A_RECEBER,
  TIPOS_DO_CAIXA,
  type OperacaoDeFinanceiro,
} from './financeiro';
import { conferirValorDoRecebimento, type ValorDoRecebimento } from './valor-do-recebimento';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
/** Valor em Real como texto com ponto (o que a API aceita), maior que zero. */
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const idValido = (v: unknown): v is string => typeof v === 'string' && UUID.test(v);
const dataValida = (v: unknown): v is string => typeof v === 'string' && DATA.test(v);
const dinheiroValido = (v: unknown): v is string =>
  typeof v === 'string' && DINHEIRO.test(v) && Number(v) > 0;
const dentro = (lista: readonly string[], v: unknown): v is string =>
  typeof v === 'string' && lista.includes(v);
const soCom = (o: Record<string, unknown>, ...nomes: string[]) =>
  Object.keys(o).every((k) => nomes.includes(k));
const textoAte = (v: unknown, max: number): v is string =>
  typeof v === 'string' && v.trim() !== '' && v.trim().length <= max;

export type ParametrosDeFinanceiro =
  | { operacao: 'caixa.extrato'; consulta: { de?: string; ate?: string } }
  | { operacao: 'caixa.saldo' | 'caixa.categorias' }
  | {
      operacao: 'caixa.lancar';
      corpo: {
        id: string;
        tipo: string;
        forma: string;
        categoriaId: string;
        data: string;
        descricao: string;
        valor: string;
      };
    }
  | {
      operacao: 'caixa.estornar' | 'contasAPagar.estornar';
      id: string;
      corpo: { id: string; motivo: string };
    }
  | { operacao: 'caixa.criarCategoria'; corpo: { id: string; nome: string; tipo: string } }
  | { operacao: 'caixa.renomearCategoria'; id: string; corpo: { nome: string } }
  | { operacao: 'caixa.inativarCategoria' | 'caixa.reativarCategoria'; id: string }
  | { operacao: 'contasAPagar.listar'; lista: Record<string, string | number> }
  | { operacao: 'contasAPagar.ver'; id: string }
  | {
      operacao: 'contasAPagar.pagar';
      id: string;
      corpo: { id: string; forma: string; valor: string; data?: string };
    }
  | { operacao: 'contasAReceber.listar' | 'recebimentos.listar'; lista: Record<string, string> }
  | {
      operacao: 'recebimentos.receber';
      corpo: {
        id: string;
        vendedorId: string;
        forma: string;
        contaId?: string;
        cotacaoData?: string;
      } & ValorDoRecebimento;
    }
  | {
      operacao: 'recebimentos.estornar';
      id: string;
      corpo: { id: string; motivo: string; vencimento?: string };
    }
  | { operacao: 'semanas.fechar'; id: string; corpo: { id: string } };

/** Lista de filtros: ids, datas e valores de uma lista fixa; o que vier de fora recusa o pedido todo. */
function filtros(
  p: Record<string, unknown>,
  ids: readonly string[],
  datas: readonly string[],
  listas: Record<string, readonly string[]>,
): Record<string, string> | null {
  if (!soCom(p, ...ids, ...datas, ...Object.keys(listas))) return null;
  const saida: Record<string, string> = {};
  for (const k of Object.keys(p)) {
    const v = p[k];
    if (v === undefined || v === '') continue;
    if (
      ids.includes(k)
        ? !idValido(v)
        : datas.includes(k)
          ? !dataValida(v)
          : !dentro(listas[k] ?? [], v)
    ) {
      return null;
    }
    saida[k] = v as string;
  }
  return saida;
}

export function conferirFinanceiro(
  operacao: OperacaoDeFinanceiro,
  p: Record<string, unknown>,
): ParametrosDeFinanceiro | null {
  switch (operacao) {
    case 'caixa.extrato': {
      const consulta = filtros(p, [], ['de', 'ate'], {});
      return consulta ? { operacao, consulta } : null;
    }
    case 'caixa.saldo':
    case 'caixa.categorias':
      return Object.keys(p).length === 0 ? { operacao } : null;
    case 'caixa.lancar': {
      if (!soCom(p, 'id', 'tipo', 'forma', 'categoriaId', 'data', 'descricao', 'valor'))
        return null;
      if (!idValido(p.id) || !idValido(p.categoriaId) || !dataValida(p.data)) return null;
      if (!dentro(TIPOS_DO_CAIXA, p.tipo) || !dentro(FORMAS_DO_FINANCEIRO, p.forma)) return null;
      if (!dinheiroValido(p.valor) || !textoAte(p.descricao, MAXIMO_DA_DESCRICAO_DO_CAIXA)) {
        return null;
      }
      return {
        operacao,
        corpo: {
          id: p.id,
          tipo: p.tipo,
          forma: p.forma,
          categoriaId: p.categoriaId,
          data: p.data,
          descricao: p.descricao.trim(),
          valor: p.valor,
        },
      };
    }
    case 'caixa.estornar':
    case 'contasAPagar.estornar': {
      const chave = operacao === 'caixa.estornar' ? 'id' : 'baixaId';
      if (!soCom(p, chave, 'comandoId', 'motivo')) return null;
      if (!idValido(p[chave]) || !idValido(p.comandoId)) return null;
      if (!textoAte(p.motivo, MAXIMO_DO_MOTIVO_DO_FINANCEIRO)) return null;
      return { operacao, id: p[chave], corpo: { id: p.comandoId, motivo: p.motivo.trim() } };
    }
    case 'caixa.criarCategoria': {
      if (!soCom(p, 'id', 'nome', 'tipo') || !idValido(p.id)) return null;
      if (!textoAte(p.nome, MAXIMO_DO_NOME_DA_CATEGORIA) || !dentro(TIPOS_DO_CAIXA, p.tipo)) {
        return null;
      }
      return { operacao, corpo: { id: p.id, nome: p.nome.trim(), tipo: p.tipo } };
    }
    case 'caixa.renomearCategoria':
      return soCom(p, 'id', 'nome') &&
        idValido(p.id) &&
        textoAte(p.nome, MAXIMO_DO_NOME_DA_CATEGORIA)
        ? { operacao, id: p.id, corpo: { nome: p.nome.trim() } }
        : null;
    case 'caixa.inativarCategoria':
    case 'caixa.reativarCategoria':
    case 'contasAPagar.ver':
      return soCom(p, 'id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'contasAPagar.listar': {
      const { pagina, tamanho, ...resto } = p;
      const lista = filtros(resto, ['fornecedorId'], ['vencimentoDe', 'vencimentoAte'], {
        situacao: SITUACOES_DA_CONTA_A_PAGAR,
      });
      if (!lista) return null;
      const saida: Record<string, string | number> = { ...lista };
      if (pagina !== undefined) {
        if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
        saida.pagina = pagina;
      }
      if (tamanho !== undefined) {
        if (
          typeof tamanho !== 'number' ||
          !Number.isInteger(tamanho) ||
          tamanho < 1 ||
          tamanho > 200
        ) {
          return null;
        }
        saida.tamanho = tamanho;
      }
      return { operacao, lista: saida };
    }
    case 'contasAPagar.pagar': {
      if (!soCom(p, 'id', 'comandoId', 'forma', 'valor', 'data')) return null;
      if (!idValido(p.id) || !idValido(p.comandoId)) return null;
      if (!dentro(FORMAS_DO_FINANCEIRO, p.forma) || !dinheiroValido(p.valor)) return null;
      const corpo: { id: string; forma: string; valor: string; data?: string } = {
        id: p.comandoId,
        forma: p.forma,
        valor: p.valor,
      };
      if (p.data !== undefined && p.data !== '') {
        if (!dataValida(p.data)) return null;
        corpo.data = p.data;
      }
      return { operacao, id: p.id, corpo };
    }
    case 'contasAReceber.listar': {
      const lista = filtros(p, ['vendedorId', 'clienteId'], [], {
        situacao: SITUACOES_DA_CONTA_A_RECEBER,
        nivel: NIVEIS_DA_CONTA_A_RECEBER,
      });
      return lista ? { operacao, lista } : null;
    }
    case 'recebimentos.listar': {
      const lista = filtros(p, ['vendedorId'], ['de', 'ate'], {});
      return lista ? { operacao, lista } : null;
    }
    case 'recebimentos.receber': {
      if (
        !soCom(
          p,
          'id',
          'vendedorId',
          'forma',
          'valor',
          'contaId',
          'moeda',
          'valorNaMoeda',
          'cotacaoData',
        )
      )
        return null;
      if (!idValido(p.id) || !idValido(p.vendedorId)) return null;
      if (!dentro(FORMAS_DO_FINANCEIRO, p.forma)) return null;
      const valor = conferirValorDoRecebimento(p);
      if (!valor) return null;
      const corpo: {
        id: string;
        vendedorId: string;
        forma: string;
        contaId?: string;
        cotacaoData?: string;
      } & ValorDoRecebimento = { id: p.id, vendedorId: p.vendedorId, forma: p.forma, ...valor };
      if (p.contaId !== undefined && p.contaId !== '') {
        if (!idValido(p.contaId)) return null;
        corpo.contaId = p.contaId;
      }
      if (p.cotacaoData !== undefined) {
        if (!dataValida(p.cotacaoData)) return null;
        corpo.cotacaoData = p.cotacaoData;
      }
      return { operacao, corpo };
    }
    case 'recebimentos.estornar': {
      if (!soCom(p, 'id', 'comandoId', 'motivo', 'vencimento')) return null;
      if (!idValido(p.id) || !idValido(p.comandoId)) return null;
      if (!textoAte(p.motivo, MAXIMO_DO_MOTIVO_DO_FINANCEIRO)) return null;
      const corpo: { id: string; motivo: string; vencimento?: string } = {
        id: p.comandoId,
        motivo: p.motivo.trim(),
      };
      if (p.vencimento !== undefined && p.vencimento !== '') {
        if (!dataValida(p.vencimento)) return null;
        corpo.vencimento = p.vencimento;
      }
      return { operacao, id: p.id, corpo };
    }
    case 'semanas.fechar':
      return soCom(p, 'id', 'comandoId') && idValido(p.id) && idValido(p.comandoId)
        ? { operacao, id: p.id, corpo: { id: p.comandoId } }
        : null;
  }
}
