import type { DadosDeEntrada, DadosDeNovaSenha } from './ponte';
import {
  AGRUPAMENTOS_DE_VENDAS,
  CAMPOS_DO_CLIENTE_EDITAVEIS,
  type CampoDoClienteEditavel,
  ESTADOS_DA_PECA,
  FORMAS_DE_PAGAMENTO,
  FORMAS_DE_PAGAMENTO_DA_VENDA,
  MOTIVOS_DE_DEVOLUCAO,
  OPERACOES_DA_API,
  SITUACOES_DA_VENDA,
  TAMANHOS_DE_FOTO,
  type ClienteParaCadastrar,
  type ContagemDaPeca,
  type DevolucaoParaEnviar,
  type EstadoDaPeca,
  type FormaDePagamento,
  type FormaDePagamentoDaVenda,
  type MotivoDeDevolucao,
  type LinhaDaVendaParaEnviar,
  type PagamentoParaEnviar,
  type PecaQueVolta,
  type PedidoDeAutorizacaoParaEnviar,
  type PedidoDeChamada,
  type TrocaParaEnviar,
  type VendaParaEnviar,
} from './operacoes';

/** Tamanhos máximos aceitos na ponte. O servidor tem a palavra final sobre a senha (72 bytes). */
const MAX_USUARIO = 200;
const MAX_SENHA = 1000;

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const ehObjeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

/** Aceita só o formato esperado e devolve uma cópia limpa; qualquer outra coisa vira `null`. */
export function validarEntrada(v: unknown): DadosDeEntrada | null {
  if (!ehObjeto(v)) return null;
  const { usuario, senha } = v;
  if (!ehTexto(usuario) || !ehTexto(senha)) return null;
  if (usuario.length === 0 || usuario.length > MAX_USUARIO) return null;
  if (senha.length === 0 || senha.length > MAX_SENHA) return null;
  return { usuario, senha };
}

export function validarNovaSenha(v: unknown): DadosDeNovaSenha | null {
  if (!ehObjeto(v)) return null;
  const { senhaNova } = v;
  if (!ehTexto(senhaNova) || senhaNova.length === 0 || senhaNova.length > MAX_SENHA) return null;
  return { senhaNova };
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const HASH = /^[0-9a-f]{64}$/i;

const ehUuid = (v: unknown): v is string => ehTexto(v) && UUID.test(v);
const ehData = (v: unknown): v is string => ehTexto(v) && DATA.test(v);
const ehDinheiro = (v: unknown): v is string => ehTexto(v) && DINHEIRO.test(v);
const ehQuantidade = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 1 && v <= 100_000;
const textoAte = (v: unknown, max: number): v is string => ehTexto(v) && v.length <= max;

/** Campo opcional: ausente vale; presente tem de passar na conferência. */
function opcional<T>(
  o: Record<string, unknown>,
  campo: string,
  confere: (v: unknown) => v is T,
): { ok: true; valor?: T } | { ok: false } {
  const v = o[campo];
  if (v === undefined) return { ok: true };
  return confere(v) ? { ok: true, valor: v } : { ok: false };
}

function validarContagens(v: unknown): ContagemDaPeca[] | null {
  if (!Array.isArray(v) || v.length > 500) return null;
  const saida: ContagemDaPeca[] = [];
  for (const l of v as unknown[]) {
    if (!ehObjeto(l) || !ehUuid(l.produtoId)) return null;
    const c = l.contada;
    if (typeof c !== 'number' || !Number.isInteger(c) || c < 0 || c > 100_000) return null;
    saida.push({ produtoId: l.produtoId, contada: c });
  }
  return saida;
}

function validarLinhas(v: unknown): LinhaDaVendaParaEnviar[] | null {
  if (!Array.isArray(v) || v.length === 0 || v.length > 200) return null;
  const saida: LinhaDaVendaParaEnviar[] = [];
  for (const l of v as unknown[]) {
    if (!ehObjeto(l) || !ehUuid(l.produtoId) || !ehQuantidade(l.quantidade)) return null;
    const linha: LinhaDaVendaParaEnviar = { produtoId: l.produtoId, quantidade: l.quantidade };
    if (l.desconto !== undefined) {
      if (!ehDinheiro(l.desconto)) return null;
      linha.desconto = l.desconto;
    }
    saida.push(linha);
  }
  return saida;
}

/**
 * Os pagamentos da venda: a forma CREDITO (crédito do cliente, tarefa 6.8) sempre com o crédito, e o crédito só nela.
 * Com comCredito = false (a diferença da troca, dúvida V8), só as quatro formas de sempre.
 */
function validarPagamentos(v: unknown, comCredito = true): PagamentoParaEnviar[] | null {
  if (!Array.isArray(v) || v.length > 10) return null;
  const formas: readonly unknown[] = comCredito
    ? FORMAS_DE_PAGAMENTO_DA_VENDA
    : FORMAS_DE_PAGAMENTO;
  const saida: PagamentoParaEnviar[] = [];
  for (const p of v as unknown[]) {
    if (!ehObjeto(p) || !ehUuid(p.id) || !ehDinheiro(p.valor)) return null;
    if (!formas.includes(p.forma)) return null;
    const forma = p.forma as FormaDePagamentoDaVenda;
    if (forma === 'CREDITO' ? !ehUuid(p.creditoId) : p.creditoId !== undefined) return null;
    saida.push({
      id: p.id,
      valor: p.valor,
      forma,
      ...(forma === 'CREDITO' ? { creditoId: p.creditoId as string } : {}),
    });
  }
  return saida;
}

// --- devolução, troca e crédito (tarefa 6.8) ---

const LIMITE_DO_TEXTO = 400;
/** Nenhum valor vem da tela: o servidor calcula (o computador só mostra a prévia). */
const CAMPOS_DE_VALOR = [
  'valor',
  'valorDevolvido',
  'diferenca',
  'valorConferido',
  'totalConferido',
];
const semValor = (o: Record<string, unknown>): boolean => CAMPOS_DE_VALOR.every((c) => !(c in o));

const ehMotivo = (v: unknown): v is MotivoDeDevolucao =>
  (MOTIVOS_DE_DEVOLUCAO as readonly unknown[]).includes(v);
const ehEstado = (v: unknown): v is EstadoDaPeca =>
  (ESTADOS_DA_PECA as readonly unknown[]).includes(v);
const ehFormaDeReembolso = (v: unknown): v is FormaDePagamento =>
  (FORMAS_DE_PAGAMENTO as readonly unknown[]).includes(v);
const ehMotivoDoCancelamento = (v: unknown): v is string =>
  ehTexto(v) && v.trim() !== '' && v.length <= LIMITE_DO_TEXTO;

function validarPecasQueVoltam(v: unknown, vazioVale = false): PecaQueVolta[] | null {
  if (!Array.isArray(v) || v.length > 200 || (!vazioVale && v.length === 0)) return null;
  const saida: PecaQueVolta[] = [];
  for (const p of v as unknown[]) {
    if (!ehObjeto(p) || !semValor(p) || !ehUuid(p.produtoId) || !ehQuantidade(p.quantidade))
      return null;
    if (!ehEstado(p.estado)) return null;
    saida.push({ produtoId: p.produtoId, quantidade: p.quantidade, estado: p.estado });
  }
  return saida;
}

interface OQueVolta {
  id?: string;
  vendaId: string;
  motivo?: MotivoDeDevolucao;
  observacao?: string;
  itens: PecaQueVolta[];
}

/** Venda, motivo, observação e peças: o pedaço comum da devolução, do pedido e da parte que volta da troca. */
function validarOQueVolta(
  o: Record<string, unknown>,
  opcoes: { comId: boolean; motivoOpcional?: boolean; vazioVale?: boolean },
): OQueVolta | null {
  if (!semValor(o) || !ehUuid(o.vendaId) || (opcoes.comId && !ehUuid(o.id))) return null;
  if (!(opcoes.motivoOpcional && o.motivo === undefined) && !ehMotivo(o.motivo)) return null;
  if (o.observacao !== undefined && !textoAte(o.observacao, LIMITE_DO_TEXTO)) return null;
  const itens = validarPecasQueVoltam(o.itens, opcoes.vazioVale);
  if (!itens) return null;
  return {
    ...(opcoes.comId ? { id: o.id as string } : {}),
    vendaId: o.vendaId,
    ...(o.motivo !== undefined ? { motivo: o.motivo } : {}),
    ...(o.observacao !== undefined ? { observacao: o.observacao } : {}),
    itens,
  };
}

function validarDevolucao(v: unknown): DevolucaoParaEnviar | null {
  if (!ehObjeto(v) || !ehFormaDeReembolso(v.formaReembolso)) return null;
  const base = validarOQueVolta(v, { comId: true });
  if (!base?.id || !base.motivo) return null;
  return {
    id: base.id,
    vendaId: base.vendaId,
    motivo: base.motivo,
    ...(base.observacao !== undefined ? { observacao: base.observacao } : {}),
    formaReembolso: v.formaReembolso,
    itens: base.itens,
  };
}

function validarPedidoDeAutorizacao(v: unknown): PedidoDeAutorizacaoParaEnviar | null {
  if (!ehObjeto(v)) return null;
  const base = validarOQueVolta(v, { comId: true });
  if (!base?.id || !base.motivo) return null;
  return {
    id: base.id,
    vendaId: base.vendaId,
    motivo: base.motivo,
    ...(base.observacao !== undefined ? { observacao: base.observacao } : {}),
    itens: base.itens,
  };
}

function validarTroca(v: unknown): TrocaParaEnviar | null {
  if (!ehObjeto(v) || !semValor(v)) return null;
  if (!ehUuid(v.id) || !ehUuid(v.creditoId) || !ehUuid(v.pagamentoDoCreditoId)) return null;
  if (!ehObjeto(v.devolucao) || !ehObjeto(v.venda)) return null;
  const a = validarOQueVolta(v.devolucao, { comId: true });
  if (!a?.id || !a.motivo) return null;
  const b = v.venda;
  if (!semValor(b) || !ehUuid(b.id) || !ehUuid(b.remessaId)) return null;
  const itens = validarLinhas(b.itens);
  const pagamentos = validarPagamentos(b.pagamentos, false);
  if (!itens || !pagamentos) return null;
  return {
    id: v.id,
    creditoId: v.creditoId,
    pagamentoDoCreditoId: v.pagamentoDoCreditoId,
    devolucao: {
      id: a.id,
      vendaId: a.vendaId,
      motivo: a.motivo,
      ...(a.observacao !== undefined ? { observacao: a.observacao } : {}),
      itens: a.itens,
    },
    venda: { id: b.id, remessaId: b.remessaId, itens, pagamentos },
  };
}

function validarVenda(v: unknown): VendaParaEnviar | null {
  if (!ehObjeto(v) || !ehUuid(v.id) || !ehUuid(v.remessaId) || !ehUuid(v.clienteId)) return null;
  const itens = validarLinhas(v.itens);
  const pagamentos = validarPagamentos(v.pagamentos);
  if (!itens || !pagamentos) return null;
  const venda: VendaParaEnviar = {
    id: v.id,
    remessaId: v.remessaId,
    clienteId: v.clienteId,
    itens,
    pagamentos,
  };
  if (v.totalConferido !== undefined) {
    if (!ehDinheiro(v.totalConferido)) return null;
    venda.totalConferido = v.totalConferido;
  }
  return venda;
}

function validarCliente(v: unknown): ClienteParaCadastrar | null {
  if (!ehObjeto(v) || !ehUuid(v.id) || !ehTexto(v.nome) || v.nome.trim() === '') return null;
  if (v.nome.length > 200) return null;
  const cliente: ClienteParaCadastrar = { id: v.id, nome: v.nome };
  const campos = [
    ['cpf', 20],
    ['telefone', 30],
    ['whatsapp', 30],
    ['observacoes', 2000],
  ] as const;
  for (const [campo, max] of campos) {
    const x = v[campo];
    if (x === undefined) continue;
    if (!textoAte(x, max)) return null;
    cliente[campo] = x;
  }
  if (v.dataNascimento !== undefined) {
    if (!ehData(v.dataNascimento)) return null;
    cliente.dataNascimento = v.dataNascimento;
  }
  return cliente;
}

function validarCamposDoCliente(
  v: unknown,
): Partial<Record<CampoDoClienteEditavel, string>> | null {
  if (!ehObjeto(v)) return null;
  const saida: Partial<Record<CampoDoClienteEditavel, string>> = {};
  for (const [campo, valor] of Object.entries(v)) {
    if (!(CAMPOS_DO_CLIENTE_EDITAVEIS as readonly string[]).includes(campo)) return null;
    if (!ehTexto(valor)) return null;
    if (campo === 'dataNascimento' ? valor !== '' && !ehData(valor) : valor.length > 2000)
      return null;
    if (campo === 'nome' && valor.trim() === '') return null;
    saida[campo as CampoDoClienteEditavel] = valor;
  }
  return Object.keys(saida).length > 0 ? saida : null;
}

/** Período opcional: duas datas AAAA-MM-DD. */
function periodo(o: Record<string, unknown>): { de?: string; ate?: string } | null {
  const de = opcional(o, 'de', ehData);
  const ate = opcional(o, 'ate', ehData);
  if (!de.ok || !ate.ok) return null;
  return { ...(de.valor ? { de: de.valor } : {}), ...(ate.valor ? { ate: ate.valor } : {}) };
}

const ehSituacao = (x: unknown): x is 'CONCLUIDA' | 'ESTORNADA' =>
  (SITUACOES_DA_VENDA as readonly unknown[]).includes(x);
const ehAgrupamento = (x: unknown): x is (typeof AGRUPAMENTOS_DE_VENDAS)[number] =>
  (AGRUPAMENTOS_DE_VENDAS as readonly unknown[]).includes(x);
const ehBusca = (x: unknown): x is string => textoAte(x, 100);

/**
 * Aceita só o formato esperado de cada operação e devolve uma cópia limpa; qualquer outra coisa (tipo
 * errado, tamanho absurdo, operação desconhecida) vira `null`. O servidor continua tendo a palavra final.
 */
export function validarChamada(v: unknown): PedidoDeChamada | null {
  if (!ehObjeto(v)) return null;
  const { operacao } = v;
  if (!ehTexto(operacao) || !(OPERACOES_DA_API as readonly string[]).includes(operacao))
    return null;
  switch (operacao) {
    case 'eu':
    case 'maletas.listar':
    case 'cotacao.hoje':
    case 'relatorios.aReceber':
    case 'relatorios.creditos':
      return { operacao };
    case 'maletas.ver':
    case 'maletas.verConferencia':
    case 'vendas.ver':
    case 'clientes.ver':
      return ehUuid(v.id) ? { operacao, id: v.id } : null;
    case 'maletas.peca':
      return ehUuid(v.id) && textoAte(v.codigo, 100)
        ? { operacao, id: v.id, codigo: v.codigo }
        : null;
    case 'maletas.buscarPeca':
      return ehUuid(v.id) && textoAte(v.texto, 100) ? { operacao, id: v.id, texto: v.texto } : null;
    case 'maletas.conferir': {
      const itens = validarContagens(v.itens);
      if (!ehUuid(v.id) || !ehUuid(v.conferenciaId) || !itens) return null;
      return { operacao, id: v.id, conferenciaId: v.conferenciaId, itens };
    }
    case 'vendas.vender': {
      const venda = validarVenda(v.venda);
      return venda ? { operacao, venda } : null;
    }
    case 'vendas.listar': {
      const p = periodo(v);
      const cliente = opcional(v, 'clienteId', ehUuid);
      const situacao = opcional(v, 'status', ehSituacao);
      if (!p || !cliente.ok || !situacao.ok) return null;
      return {
        operacao,
        ...p,
        ...(cliente.valor ? { clienteId: cliente.valor } : {}),
        ...(situacao.valor ? { status: situacao.valor } : {}),
      };
    }
    case 'vendas.estornar': {
      if (!ehUuid(v.id) || !ehUuid(v.estornoId) || !ehTexto(v.motivo)) return null;
      if (v.motivo.trim() === '' || v.motivo.length > 400) return null;
      return { operacao, id: v.id, estornoId: v.estornoId, motivo: v.motivo };
    }
    case 'clientes.listar': {
      const busca = opcional(v, 'busca', ehBusca);
      return busca.ok ? { operacao, ...(busca.valor ? { busca: busca.valor } : {}) } : null;
    }
    case 'clientes.cadastrar': {
      const cliente = validarCliente(v.cliente);
      return cliente ? { operacao, cliente } : null;
    }
    case 'clientes.alterar': {
      const campos = validarCamposDoCliente(v.campos);
      return ehUuid(v.id) && campos ? { operacao, id: v.id, campos } : null;
    }
    case 'envios.naoAceitos':
      return { operacao };
    case 'envios.entendi':
      return ehUuid(v.id) ? { operacao, id: v.id } : null;
    case 'fotos.ver': {
      if (!ehTexto(v.hash) || !HASH.test(v.hash)) return null;
      if (!(TAMANHOS_DE_FOTO as readonly unknown[]).includes(v.tamanho)) return null;
      return { operacao, hash: v.hash, tamanho: v.tamanho as (typeof TAMANHOS_DE_FOTO)[number] };
    }
    case 'relatorios.vendas': {
      const p = periodo(v);
      const agrupar = opcional(v, 'agrupar', ehAgrupamento);
      if (!p || !agrupar.ok) return null;
      return { operacao, ...p, ...(agrupar.valor ? { agrupar: agrupar.valor } : {}) };
    }
    case 'relatorios.recebimentos':
    case 'relatorios.devolucoes': {
      const p = periodo(v);
      return p ? { operacao, ...p } : null;
    }
    // --- devolução, troca e crédito (tarefa 6.8) ---
    case 'devolucoes.vendasComAPeca':
      return textoAte(v.codigo, 100) ? { operacao, codigo: v.codigo } : null;
    case 'devolucoes.previa': {
      const base = validarOQueVolta(v, { comId: false, motivoOpcional: true, vazioVale: true });
      return base ? { operacao, ...base } : null;
    }
    case 'devolucoes.registrar': {
      const devolucao = validarDevolucao(v.devolucao);
      return devolucao ? { operacao, devolucao } : null;
    }
    case 'devolucoes.cancelar':
    case 'trocas.cancelar':
      return ehUuid(v.id) && ehUuid(v.cancelamentoId) && ehMotivoDoCancelamento(v.motivo)
        ? { operacao, id: v.id, cancelamentoId: v.cancelamentoId, motivo: v.motivo }
        : null;
    case 'devolucoes.listar': {
      const p = periodo(v);
      return p ? { operacao, ...p } : null;
    }
    case 'autorizacoes.pedir': {
      const pedido = validarPedidoDeAutorizacao(v.pedido);
      return pedido ? { operacao, pedido } : null;
    }
    case 'autorizacoes.listar':
      return { operacao };
    case 'trocas.previa': {
      if (!ehObjeto(v.devolucao)) return null;
      const devolucao = validarOQueVolta(v.devolucao, {
        comId: false,
        motivoOpcional: true,
        vazioVale: true,
      });
      const remessa = opcional(v, 'remessaId', ehUuid);
      const itens = Array.isArray(v.itens) && v.itens.length === 0 ? [] : validarLinhas(v.itens);
      if (!devolucao || !remessa.ok || !itens) return null;
      return {
        operacao,
        devolucao,
        ...(remessa.valor ? { remessaId: remessa.valor } : {}),
        itens,
      };
    }
    case 'trocas.registrar': {
      const troca = validarTroca(v.troca);
      return troca ? { operacao, troca } : null;
    }
    case 'creditos.doCliente':
      return ehUuid(v.clienteId) ? { operacao, clienteId: v.clienteId } : null;
    default:
      return null;
  }
}
