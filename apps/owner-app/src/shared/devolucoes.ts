/**
 * Devoluções, trocas, créditos dos clientes e garantia na ponte (tarefa 6.9). É a lista que o processo principal usa
 * para conferir os dados antes de falar com a API; a tela usa os mesmos nomes. Nenhum valor em dinheiro de devolução
 * passa por aqui: a tela manda venda, peças, quantidades, estado, motivo e forma do reembolso, e o servidor calcula.
 */

export const OPERACOES_DE_DEVOLUCOES = [
  'devolucoes.autorizacoes',
  'devolucoes.aprovarAutorizacao',
  'devolucoes.negarAutorizacao',
  'devolucoes.listar',
  'devolucoes.manter',
  'devolucoes.naoAceitar',
  'devolucoes.cancelar',
  'devolucoes.vendasComAPeca',
  'devolucoes.registrar',
  'trocas.listar',
  'trocas.cancelar',
  'creditos.listar',
  'creditos.transferir',
  'garantia.ler',
  'garantia.alterar',
] as const;
export type OperacaoDeDevolucoes = (typeof OPERACOES_DE_DEVOLUCOES)[number];

export const SITUACOES_DA_AUTORIZACAO = [
  'PEDIDA',
  'APROVADA',
  'NEGADA',
  'USADA',
  'VENCIDA',
] as const;
export const SITUACOES_DA_DEVOLUCAO = [
  'ACEITA',
  'EM_REVISAO',
  'MANTIDA',
  'NAO_ACEITA',
  'CANCELADA',
] as const;
export const SITUACOES_DA_TROCA = ['ATIVA', 'CANCELADA'] as const;
export const SITUACOES_DO_CREDITO = ['ABERTO', 'UTILIZADO', 'CANCELADO'] as const;
export const MOTIVOS_DA_DEVOLUCAO = ['ARREPENDIMENTO', 'DEFEITO', 'TAMANHO', 'OUTRO'] as const;
export const ESTADOS_DA_PECA = ['BOA', 'DEFEITO'] as const;
export const FORMAS_DO_REEMBOLSO = ['DINHEIRO', 'PIX', 'CARTAO', 'CHEQUE'] as const;

/** Motivos, observações e códigos: o servidor aceita até 400 caracteres (e confere de novo). */
export const MAXIMO_DO_TEXTO_DA_DEVOLUCAO = 400;
export const MAXIMO_DO_CODIGO_DA_PECA = 100;
export const MAXIMO_DE_PECAS_NA_DEVOLUCAO = 50;
export const MAXIMO_DA_QUANTIDADE_DEVOLVIDA = 100_000;

export interface CorpoDaDevolucao {
  id: string;
  vendaId: string;
  motivo: (typeof MOTIVOS_DA_DEVOLUCAO)[number];
  observacao?: string;
  formaReembolso: (typeof FORMAS_DO_REEMBOLSO)[number];
  itens: { produtoId: string; quantidade: number; estado: (typeof ESTADOS_DA_PECA)[number] }[];
}

export interface CorpoDaGarantia {
  prazoArrependimentoDias: number;
  prazoDefeitoDias: number;
  /** Dinheiro em texto ("1234.50"); `null` = sem limite. */
  limiteReembolsoSemanal: string | null;
  valorAprovacaoReembolso: string | null;
  percentualAlertaDevolucao: number;
}

export type ParametrosDeDevolucoes =
  | {
      operacao: 'devolucoes.autorizacoes' | 'devolucoes.listar';
      situacao?: string;
      vendedorId?: string;
    }
  | { operacao: 'trocas.listar'; situacao?: string; vendedorId?: string }
  | { operacao: 'creditos.listar'; situacao?: string; vendedorId?: string; clienteId?: string }
  | { operacao: 'devolucoes.aprovarAutorizacao'; id: string }
  | {
      operacao: 'devolucoes.negarAutorizacao' | 'devolucoes.naoAceitar';
      id: string;
      motivo: string;
    }
  | { operacao: 'devolucoes.manter'; id: string; observacao?: string }
  | {
      operacao: 'devolucoes.cancelar' | 'trocas.cancelar';
      id: string;
      cancelamentoId: string;
      motivo: string;
    }
  | { operacao: 'devolucoes.vendasComAPeca'; codigo: string }
  | { operacao: 'devolucoes.registrar'; corpo: CorpoDaDevolucao }
  | { operacao: 'creditos.transferir'; id: string; novoId: string; vendedorId: string }
  | { operacao: 'garantia.ler' }
  | ({ operacao: 'garantia.alterar' } & CorpoDaGarantia);

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const idValido = (v: unknown): v is string => typeof v === 'string' && UUID.test(v);
const ehObjeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const ehUm = <T extends readonly string[]>(lista: T, v: unknown): v is T[number] =>
  typeof v === 'string' && (lista as readonly string[]).includes(v);

function motivoValido(v: unknown): v is string {
  return typeof v === 'string' && v.trim() !== '' && v.length <= MAXIMO_DO_TEXTO_DA_DEVOLUCAO;
}

function diasValidos(v: unknown): v is number {
  return typeof v === 'number' && Number.isInteger(v) && v >= 1 && v <= 365;
}

/** Dinheiro maior que zero em texto, ou `null` (sem limite). `undefined` = recusado. */
function dinheiroOuVazio(v: unknown): string | null | undefined {
  if (v === null) return null;
  if (typeof v !== 'string' || !DINHEIRO.test(v)) return undefined;
  return Number(v) > 0 ? v : undefined;
}

function conferirCorpo(bruto: unknown): CorpoDaDevolucao | null {
  if (!ehObjeto(bruto)) return null;
  const chaves = Object.keys(bruto);
  const permitidas = ['id', 'vendaId', 'motivo', 'observacao', 'formaReembolso', 'itens'];
  if (!chaves.every((k) => permitidas.includes(k))) return null;
  if (!idValido(bruto.id) || !idValido(bruto.vendaId)) return null;
  if (!ehUm(MOTIVOS_DA_DEVOLUCAO, bruto.motivo)) return null;
  if (!ehUm(FORMAS_DO_REEMBOLSO, bruto.formaReembolso)) return null;
  let observacao: string | undefined;
  if (bruto.observacao !== undefined && bruto.observacao !== '') {
    if (typeof bruto.observacao !== 'string') return null;
    observacao = bruto.observacao.trim();
    if (observacao.length > MAXIMO_DO_TEXTO_DA_DEVOLUCAO) return null;
    if (observacao === '') observacao = undefined;
  }
  const itensBrutos = bruto.itens;
  if (
    !Array.isArray(itensBrutos) ||
    itensBrutos.length < 1 ||
    itensBrutos.length > MAXIMO_DE_PECAS_NA_DEVOLUCAO
  ) {
    return null;
  }
  const itens: CorpoDaDevolucao['itens'] = [];
  for (const i of itensBrutos as unknown[]) {
    if (
      !ehObjeto(i) ||
      !Object.keys(i).every((k) => ['produtoId', 'quantidade', 'estado'].includes(k))
    ) {
      return null;
    }
    const { produtoId, quantidade, estado } = i;
    if (!idValido(produtoId)) return null;
    if (
      typeof quantidade !== 'number' ||
      !Number.isInteger(quantidade) ||
      quantidade < 1 ||
      quantidade > MAXIMO_DA_QUANTIDADE_DEVOLVIDA
    ) {
      return null;
    }
    if (!ehUm(ESTADOS_DA_PECA, estado)) return null;
    itens.push({ produtoId, quantidade, estado });
  }
  return {
    id: bruto.id,
    vendaId: bruto.vendaId,
    motivo: bruto.motivo,
    ...(observacao ? { observacao } : {}),
    formaReembolso: bruto.formaReembolso,
    itens,
  };
}

/** Os filtros das listas: situação (da lista dada) e vendedor, todos opcionais. */
function conferirFiltros(
  operacao: 'devolucoes.autorizacoes' | 'devolucoes.listar' | 'trocas.listar' | 'creditos.listar',
  situacoes: readonly string[],
  p: Record<string, unknown>,
  extras: readonly string[] = [],
): ParametrosDeDevolucoes | null {
  const permitidas = ['situacao', 'vendedorId', ...extras];
  if (!Object.keys(p).every((k) => permitidas.includes(k))) return null;
  const resultado: Record<string, unknown> = { operacao };
  if (p.situacao !== undefined && p.situacao !== '') {
    if (!ehUm(situacoes, p.situacao)) return null;
    resultado.situacao = p.situacao;
  }
  for (const campo of ['vendedorId', ...extras]) {
    const v = p[campo];
    if (v === undefined || v === '') continue;
    if (!idValido(v)) return null;
    resultado[campo] = v;
  }
  return resultado as ParametrosDeDevolucoes;
}

export function conferirDevolucoes(
  operacao: OperacaoDeDevolucoes,
  p: Record<string, unknown>,
): ParametrosDeDevolucoes | null {
  const chaves = Object.keys(p);
  const soCom = (...nomes: string[]) => chaves.every((k) => nomes.includes(k));
  switch (operacao) {
    case 'devolucoes.autorizacoes':
      return conferirFiltros(operacao, SITUACOES_DA_AUTORIZACAO, p);
    case 'devolucoes.listar':
      return conferirFiltros(operacao, SITUACOES_DA_DEVOLUCAO, p);
    case 'trocas.listar':
      return conferirFiltros(operacao, SITUACOES_DA_TROCA, p);
    case 'creditos.listar':
      return conferirFiltros(operacao, SITUACOES_DO_CREDITO, p, ['clienteId']);
    case 'devolucoes.aprovarAutorizacao':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'devolucoes.negarAutorizacao':
    case 'devolucoes.naoAceitar':
      return soCom('id', 'motivo') && idValido(p.id) && motivoValido(p.motivo)
        ? { operacao, id: p.id, motivo: p.motivo.trim() }
        : null;
    case 'devolucoes.manter': {
      if (!soCom('id', 'observacao') || !idValido(p.id)) return null;
      if (p.observacao === undefined || p.observacao === '') return { operacao, id: p.id };
      if (!motivoValido(p.observacao)) return null;
      return { operacao, id: p.id, observacao: p.observacao.trim() };
    }
    case 'devolucoes.cancelar':
    case 'trocas.cancelar':
      return soCom('id', 'cancelamentoId', 'motivo') &&
        idValido(p.id) &&
        idValido(p.cancelamentoId) &&
        motivoValido(p.motivo)
        ? { operacao, id: p.id, cancelamentoId: p.cancelamentoId, motivo: p.motivo.trim() }
        : null;
    case 'devolucoes.vendasComAPeca': {
      if (!soCom('codigo') || typeof p.codigo !== 'string') return null;
      const codigo = p.codigo.trim();
      return codigo !== '' && codigo.length <= MAXIMO_DO_CODIGO_DA_PECA
        ? { operacao, codigo }
        : null;
    }
    case 'devolucoes.registrar': {
      if (!soCom('corpo')) return null;
      const corpo = conferirCorpo(p.corpo);
      return corpo ? { operacao, corpo } : null;
    }
    case 'creditos.transferir':
      return soCom('id', 'novoId', 'vendedorId') &&
        idValido(p.id) &&
        idValido(p.novoId) &&
        idValido(p.vendedorId)
        ? { operacao, id: p.id, novoId: p.novoId, vendedorId: p.vendedorId }
        : null;
    case 'garantia.ler':
      return chaves.length === 0 ? { operacao } : null;
    case 'garantia.alterar': {
      if (
        !soCom(
          'prazoArrependimentoDias',
          'prazoDefeitoDias',
          'limiteReembolsoSemanal',
          'valorAprovacaoReembolso',
          'percentualAlertaDevolucao',
        )
      ) {
        return null;
      }
      const { prazoArrependimentoDias, prazoDefeitoDias, percentualAlertaDevolucao } = p;
      if (!diasValidos(prazoArrependimentoDias) || !diasValidos(prazoDefeitoDias)) return null;
      if (
        typeof percentualAlertaDevolucao !== 'number' ||
        !Number.isInteger(percentualAlertaDevolucao) ||
        percentualAlertaDevolucao < 1 ||
        percentualAlertaDevolucao > 100
      ) {
        return null;
      }
      const limite = dinheiroOuVazio(p.limiteReembolsoSemanal);
      const aprovacao = dinheiroOuVazio(p.valorAprovacaoReembolso);
      if (limite === undefined || aprovacao === undefined) return null;
      return {
        operacao,
        prazoArrependimentoDias,
        prazoDefeitoDias,
        limiteReembolsoSemanal: limite,
        valorAprovacaoReembolso: aprovacao,
        percentualAlertaDevolucao,
      };
    }
  }
}
