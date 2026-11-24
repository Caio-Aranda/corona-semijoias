/** Conferência, no processo principal, dos dados das operações de acertos (só a forma; o servidor tem a palavra final). */
import {
  ACOES_DA_PENDENCIA,
  DECISOES_DE_COBRANCA,
  DECISOES_DE_PENDENCIA_ANTIGA,
  DESTINOS_A_FAVOR_DO_VENDEDOR,
  ESTADOS_DA_PECA_NA_CONTAGEM,
  FORMAS_DO_PAGAMENTO,
  MAXIMO_DA_QUANTIDADE_NA_LEITURA,
  MAXIMO_DE_PARCELAS,
  MAXIMO_DO_MOTIVO_DO_ACERTO,
  SITUACOES_DA_PENDENCIA,
  SITUACOES_DO_ACERTO,
  type OperacaoDeAcertos,
  type PedidoDeFechamentoDaTela,
} from './acertos';
import { conferirValorDoRecebimento } from './valor-do-recebimento';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
/** Valor em Real como texto com ponto (o que a API aceita), maior que zero. */
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const LIQUIDO = /^-?\d{1,12}(\.\d{1,2})?$/;
const idValido = (v: unknown): v is string => typeof v === 'string' && UUID.test(v);
const inteiro = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const objeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const dentro = (lista: readonly string[], v: unknown): v is string =>
  typeof v === 'string' && lista.includes(v);
const soCom = (o: Record<string, unknown>, ...nomes: string[]) =>
  Object.keys(o).every((k) => nomes.includes(k));
const motivoValido = (v: unknown): v is string =>
  typeof v === 'string' && v.trim() !== '' && v.length <= MAXIMO_DO_MOTIVO_DO_ACERTO;
const dinheiroValido = (v: unknown): v is string =>
  typeof v === 'string' && DINHEIRO.test(v) && Number(v) > 0;

export type ParametrosDeAcerto =
  | {
      operacao: 'acertos.listar';
      lista: { vendedorId?: string; semanaId?: string; situacao?: string };
    }
  | { operacao: 'acertos.iniciar'; corpo: { id: string; vendedorId: string; semanaId?: string } }
  | { operacao: 'acertos.ver' | 'acertos.previa'; id: string }
  | {
      operacao: 'acertos.ler';
      id: string;
      corpo: { id: string; produtoId: string; estadoPeca: string; quantidade: number };
    }
  | { operacao: 'acertos.desistir'; id: string; corpo: { id: string; motivo: string } }
  | {
      operacao: 'acertos.previaComDecisoes' | 'acertos.fechar';
      id: string;
      pedido: PedidoDeFechamentoDaTela;
    }
  | { operacao: 'pendencias.listar'; lista: { vendedorId?: string; status?: string } }
  | {
      operacao: 'pendencias.resolver';
      id: string;
      corpo: { id: string; acao: string; motivo: string };
    }
  | { operacao: 'comissao.ler' }
  | {
      operacao: 'comissao.alterar';
      corpo: { metaGuarani: number; percentualAbaixoDaMeta: number; percentualNaMeta: number };
    };

function listaDe<T extends string>(
  p: Record<string, unknown>,
  ids: readonly T[],
  textos: Record<string, readonly string[]>,
): Record<string, string> | null {
  const saida: Record<string, string> = {};
  if (!soCom(p, ...ids, ...Object.keys(textos))) return null;
  for (const k of ids) {
    const v = p[k];
    if (v === undefined || v === '') continue;
    if (!idValido(v)) return null;
    saida[k] = v;
  }
  for (const [k, validos] of Object.entries(textos)) {
    const v = p[k];
    if (v === undefined || v === '') continue;
    if (!dentro(validos, v)) return null;
    saida[k] = v;
  }
  return saida;
}

/** Decisão de falta ou de "o que não foi aceito": "A loja assume" pede motivo. */
function conferirDecisaoDeCobranca(
  bruto: unknown,
  chave: 'produtoId' | 'origemId',
): { decisao: (typeof DECISOES_DE_COBRANCA)[number]; motivo?: string } | null {
  if (!objeto(bruto) || !soCom(bruto, chave, 'decisao', 'motivo')) return null;
  if (!idValido(bruto[chave]) || !dentro(DECISOES_DE_COBRANCA, bruto.decisao)) return null;
  const decisao = bruto.decisao as (typeof DECISOES_DE_COBRANCA)[number];
  if (decisao === 'LOJA_ASSUME') {
    if (!motivoValido(bruto.motivo)) return null;
    return { decisao, motivo: bruto.motivo.trim() };
  }
  if (bruto.motivo !== undefined && bruto.motivo !== '') return null;
  return { decisao };
}

function conferirPagamento(bruto: unknown): PedidoDeFechamentoDaTela['pagamento'] | null {
  if (!objeto(bruto)) return null;
  if (
    !soCom(
      bruto,
      'recebimentos',
      'boleto',
      'restante',
      'contasAntigas',
      'aFavorDoVendedor',
      'formaDoPagamento',
    )
  ) {
    return null;
  }
  const saida: NonNullable<PedidoDeFechamentoDaTela['pagamento']> = {};
  if (bruto.recebimentos !== undefined) {
    if (!Array.isArray(bruto.recebimentos) || bruto.recebimentos.length > 20) return null;
    saida.recebimentos = [];
    for (const r of bruto.recebimentos as unknown[]) {
      if (!objeto(r) || !soCom(r, 'id', 'forma', 'valor', 'moeda', 'valorNaMoeda')) return null;
      if (!idValido(r.id) || !dentro(FORMAS_DO_PAGAMENTO, r.forma)) return null;
      const valor = conferirValorDoRecebimento(r);
      if (!valor) return null;
      saida.recebimentos.push({
        id: r.id,
        forma: r.forma as (typeof FORMAS_DO_PAGAMENTO)[number],
        ...valor,
      });
    }
  }
  if (bruto.boleto !== undefined) {
    const b = bruto.boleto;
    if (!objeto(b) || !soCom(b, 'valor', 'vencimento')) return null;
    if (!dinheiroValido(b.valor) || typeof b.vencimento !== 'string' || !DATA.test(b.vencimento))
      return null;
    saida.boleto = { valor: b.valor, vencimento: b.vencimento };
  }
  if (bruto.restante !== undefined) {
    const r = bruto.restante;
    if (!objeto(r) || !soCom(r, 'parcelas', 'primeiroVencimento')) return null;
    if (!inteiro(r.parcelas) || r.parcelas < 1 || r.parcelas > MAXIMO_DE_PARCELAS) return null;
    if (typeof r.primeiroVencimento !== 'string' || !DATA.test(r.primeiroVencimento)) return null;
    saida.restante = { parcelas: r.parcelas, primeiroVencimento: r.primeiroVencimento };
  }
  if (bruto.contasAntigas !== undefined) {
    if (!Array.isArray(bruto.contasAntigas) || bruto.contasAntigas.length > 50) return null;
    saida.contasAntigas = [];
    for (const c of bruto.contasAntigas as unknown[]) {
      if (!objeto(c) || !soCom(c, 'id', 'contaId', 'forma', 'valor', 'moeda', 'valorNaMoeda'))
        return null;
      if (!idValido(c.id) || !idValido(c.contaId) || !dentro(FORMAS_DO_PAGAMENTO, c.forma)) {
        return null;
      }
      const valor = conferirValorDoRecebimento(c);
      if (!valor) return null;
      saida.contasAntigas.push({
        id: c.id,
        contaId: c.contaId,
        forma: c.forma as (typeof FORMAS_DO_PAGAMENTO)[number],
        ...valor,
      });
    }
  }
  if (bruto.aFavorDoVendedor !== undefined) {
    if (!dentro(DESTINOS_A_FAVOR_DO_VENDEDOR, bruto.aFavorDoVendedor)) return null;
    saida.aFavorDoVendedor =
      bruto.aFavorDoVendedor as (typeof DESTINOS_A_FAVOR_DO_VENDEDOR)[number];
  }
  if (bruto.formaDoPagamento !== undefined) {
    if (!dentro(FORMAS_DO_PAGAMENTO, bruto.formaDoPagamento)) return null;
    saida.formaDoPagamento = bruto.formaDoPagamento as (typeof FORMAS_DO_PAGAMENTO)[number];
  }
  return saida;
}

/** O pedido de prévia e de fechar: só as chaves conhecidas, nada de total, comissão nem percentual. */
function conferirPedidoDeFechamento(bruto: unknown): PedidoDeFechamentoDaTela | null {
  if (!objeto(bruto)) return null;
  if (
    !soCom(
      bruto,
      'id',
      'faltas',
      'defeitos',
      'cobrancas',
      'pendenciasAntigas',
      'liquidoMostrado',
      'cotacaoId',
      'pagamento',
    )
  ) {
    return null;
  }
  const saida: PedidoDeFechamentoDaTela = {
    faltas: [],
    defeitos: [],
    cobrancas: [],
    pendenciasAntigas: [],
  };
  if (bruto.id !== undefined) {
    if (!idValido(bruto.id)) return null;
    saida.id = bruto.id;
  }
  for (const [chave, destino] of [
    ['faltas', 'produtoId'],
    ['cobrancas', 'origemId'],
  ] as const) {
    const lista = bruto[chave] ?? [];
    if (!Array.isArray(lista) || lista.length > 500) return null;
    for (const item of lista as unknown[]) {
      const d = conferirDecisaoDeCobranca(item, destino);
      if (!d) return null;
      const id = (item as Record<string, string>)[destino] as string;
      if (chave === 'faltas') saida.faltas.push({ produtoId: id, ...d });
      else saida.cobrancas.push({ origemId: id, ...d });
    }
  }
  const defeitos = bruto.defeitos ?? [];
  if (!Array.isArray(defeitos) || defeitos.length > 500) return null;
  for (const d of defeitos as unknown[]) {
    if (!objeto(d) || !soCom(d, 'produtoId', 'aprovadas')) return null;
    if (!idValido(d.produtoId) || !inteiro(d.aprovadas) || d.aprovadas < 0) return null;
    if (d.aprovadas > MAXIMO_DA_QUANTIDADE_NA_LEITURA) return null;
    saida.defeitos.push({ produtoId: d.produtoId, aprovadas: d.aprovadas });
  }
  const antigas = bruto.pendenciasAntigas ?? [];
  if (!Array.isArray(antigas) || antigas.length > 500) return null;
  for (const a of antigas as unknown[]) {
    if (!objeto(a) || !soCom(a, 'pendenciaId', 'decisao')) return null;
    if (!idValido(a.pendenciaId) || !dentro(DECISOES_DE_PENDENCIA_ANTIGA, a.decisao)) return null;
    saida.pendenciasAntigas.push({
      pendenciaId: a.pendenciaId,
      decisao: a.decisao as 'COBRAR_NO_ACERTO' | 'MANTER',
    });
  }
  if (bruto.liquidoMostrado !== undefined) {
    if (typeof bruto.liquidoMostrado !== 'string' || !LIQUIDO.test(bruto.liquidoMostrado))
      return null;
    saida.liquidoMostrado = bruto.liquidoMostrado;
  }
  if (bruto.cotacaoId !== undefined) {
    if (!idValido(bruto.cotacaoId)) return null;
    saida.cotacaoId = bruto.cotacaoId;
  }
  if (bruto.pagamento !== undefined) {
    const pagamento = conferirPagamento(bruto.pagamento);
    if (!pagamento) return null;
    saida.pagamento = pagamento;
  }
  return saida;
}

export function conferirAcerto(
  operacao: OperacaoDeAcertos,
  p: Record<string, unknown>,
): ParametrosDeAcerto | null {
  switch (operacao) {
    case 'acertos.listar': {
      const lista = listaDe(p, ['vendedorId', 'semanaId'] as const, {
        situacao: SITUACOES_DO_ACERTO,
      });
      return lista ? { operacao, lista } : null;
    }
    case 'pendencias.listar': {
      const lista = listaDe(p, ['vendedorId'] as const, { status: SITUACOES_DA_PENDENCIA });
      return lista ? { operacao, lista } : null;
    }
    case 'acertos.iniciar': {
      if (!soCom(p, 'id', 'vendedorId', 'semanaId')) return null;
      if (!idValido(p.id) || !idValido(p.vendedorId)) return null;
      const corpo: { id: string; vendedorId: string; semanaId?: string } = {
        id: p.id,
        vendedorId: p.vendedorId,
      };
      if (p.semanaId !== undefined && p.semanaId !== '') {
        if (!idValido(p.semanaId)) return null;
        corpo.semanaId = p.semanaId;
      }
      return { operacao, corpo };
    }
    case 'acertos.ver':
    case 'acertos.previa':
      return soCom(p, 'id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'acertos.ler': {
      if (!soCom(p, 'id', 'leituraId', 'produtoId', 'estadoPeca', 'quantidade')) return null;
      if (!idValido(p.id) || !idValido(p.leituraId) || !idValido(p.produtoId)) return null;
      if (!dentro(ESTADOS_DA_PECA_NA_CONTAGEM, p.estadoPeca)) return null;
      const q = p.quantidade;
      if (!inteiro(q) || q === 0 || Math.abs(q) > MAXIMO_DA_QUANTIDADE_NA_LEITURA) return null;
      return {
        operacao,
        id: p.id,
        corpo: { id: p.leituraId, produtoId: p.produtoId, estadoPeca: p.estadoPeca, quantidade: q },
      };
    }
    case 'acertos.desistir':
      return soCom(p, 'id', 'comandoId', 'motivo') &&
        idValido(p.id) &&
        idValido(p.comandoId) &&
        motivoValido(p.motivo)
        ? { operacao, id: p.id, corpo: { id: p.comandoId, motivo: p.motivo.trim() } }
        : null;
    case 'pendencias.resolver':
      return soCom(p, 'id', 'comandoId', 'acao', 'motivo') &&
        idValido(p.id) &&
        idValido(p.comandoId) &&
        dentro(ACOES_DA_PENDENCIA, p.acao) &&
        motivoValido(p.motivo)
        ? {
            operacao,
            id: p.id,
            corpo: { id: p.comandoId, acao: p.acao, motivo: p.motivo.trim() },
          }
        : null;
    case 'acertos.previaComDecisoes':
    case 'acertos.fechar': {
      if (!soCom(p, 'id', 'pedido') || !idValido(p.id)) return null;
      const pedido = conferirPedidoDeFechamento(p.pedido);
      return pedido ? { operacao, id: p.id, pedido } : null;
    }
    case 'comissao.ler':
      return Object.keys(p).length === 0 ? { operacao } : null;
    case 'comissao.alterar': {
      if (!soCom(p, 'metaGuarani', 'percentualAbaixoDaMeta', 'percentualNaMeta')) return null;
      const { metaGuarani, percentualAbaixoDaMeta: abaixo, percentualNaMeta: naMeta } = p;
      if (!inteiro(metaGuarani) || metaGuarani < 0 || metaGuarani > 2_000_000_000) return null;
      if (!inteiro(abaixo) || !inteiro(naMeta)) return null;
      if (abaixo < 0 || abaixo > 100 || naMeta < 0 || naMeta > 100 || abaixo > naMeta) return null;
      return {
        operacao,
        corpo: { metaGuarani, percentualAbaixoDaMeta: abaixo, percentualNaMeta: naMeta },
      };
    }
  }
}
