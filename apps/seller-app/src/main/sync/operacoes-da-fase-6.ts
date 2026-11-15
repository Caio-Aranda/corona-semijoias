import type { BancoAberto } from '../banco/banco-local';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import {
  dentroDoPrazo,
  diaEmBrasilia,
  garantiaAte,
  lerPrazos,
  prazoDoMotivo,
} from '../../shared/garantia';
import {
  ESTADOS_DA_PECA,
  FORMAS_DE_PAGAMENTO,
  MOTIVOS_DE_DEVOLUCAO,
  type CancelamentoParaEnviar,
  type DevolucaoParaEnviar,
  type EstadoDaPeca,
  type MotivoDeDevolucao,
  type PagamentoParaEnviar,
  type PecaQueVolta,
  type PedidoDeAutorizacaoParaEnviar,
  type TrocaParaEnviar,
} from '../../shared/operacoes';
import { valorDaDevolucao } from '../../shared/valor-da-devolucao';
import { CHAVES, lerEstado } from './estado-sync';
import type { CodigoDaFase6 } from './mensagens-da-fase-6';
import { ErroDaOperacao, inserir, limiteDeDesconto, saldoDaPeca } from './operacoes-locais';
import { conferirParcelamento } from './operacoes-da-fase-7';
import { enfileirar } from './outbox';
import { uuidV7 } from './uuid';

/**
 * Devolução, pedido de autorização, troca, crédito do cliente e os cancelamentos no computador (tarefa 6.8; ADR-0014,
 * itens 3 a 9, 11 e 14). Cada operação confere o que o servidor também recusaria (com o mesmo texto), grava as linhas
 * PROVISÓRIAS como o servidor gravaria (prévia) e põe na outbox o JSON exato do formato 1 da 6.7, montado uma vez e
 * nunca remontado. **O JSON nunca leva valor calculado aqui** (`valorConferido` e `totalConferido` vão nulos): o servidor
 * calcula tudo. Nunca há `UPDATE` em cópia do servidor: "cancelada aqui" e "usada aqui" vêm da outbox, e o `RECUSADO`
 * desfaz tudo pelas provisórias, como sempre.
 */

const LIMITE_DO_TEXTO = 400;
const MALETA_COM_O_VENDEDOR = new Set(['ENVIADA', 'CONFERIDA']);
const OUTBOX_EM_ANDAMENTO = "('AGUARDANDO', 'ENVIADO')";

const recusar = (codigo: CodigoDaFase6, detalhe?: string): never => {
  throw new ErroDaOperacao(codigo, undefined, detalhe);
};

const reais = (centavos: bigint): string => textoDeCentavos(centavos).replace('.', ',');

// --- quem é o vendedor deste computador e o que foi desfeito aqui ---

/** Os vendedores das maletas deste computador (defesa: o pull já só traz o que é dele). */
function vendedoresDoComputador(db: BancoAberto): Set<string> {
  return new Set(
    (
      db
        .prepare(
          'SELECT DISTINCT vendedor_id AS v FROM remessas WHERE vendedor_id IS NOT NULL AND deleted_at IS NULL',
        )
        .all() as { v: string }[]
    ).map((r) => r.v),
  );
}

/** A linha é deste vendedor (nula = provisória, feita aqui). */
export function ehDoVendedor(db: BancoAberto, vendedorId: string | null | undefined): boolean {
  return !vendedorId || vendedoresDoComputador(db).has(vendedorId);
}

/** SQL: a devolução `d` não está cancelada, nem no servidor nem aqui (cancelamento aguardando ou enviado). */
export const SQL_DEVOLUCAO_ATIVA = (d: string): string => `(${d}.deleted_at IS NULL
  AND coalesce(${d}.situacao, '') <> 'CANCELADA'
  AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.situacao IN ${OUTBOX_EM_ANDAMENTO} AND (
        (o.tipo = 'CANCELAR_DEVOLUCAO' AND json_extract(o.dados, '$.devolucaoId') = ${d}.id)
     OR (o.tipo = 'CANCELAR_TROCA' AND json_extract(o.dados, '$.trocaId') IN
           (SELECT t.id FROM trocas t WHERE t.devolucao_id = ${d}.id)))))`;

/** SQL: a troca `t` foi cancelada aqui (o cancelamento aguarda envio ou já foi). */
export const SQL_TROCA_CANCELADA_AQUI = (t: string): string => `EXISTS (SELECT 1 FROM outbox o
  WHERE o.situacao IN ${OUTBOX_EM_ANDAMENTO} AND o.tipo = 'CANCELAR_TROCA'
    AND json_extract(o.dados, '$.trocaId') = ${t}.id)`;

/** SQL: a venda `v` foi estornada aqui (estorno aguardando, ou a troca dela cancelada aqui). */
export const SQL_VENDA_ESTORNADA_AQUI = (v: string): string => `EXISTS (SELECT 1 FROM outbox o
  WHERE o.situacao IN ${OUTBOX_EM_ANDAMENTO} AND (
        (o.tipo = 'ESTORNO_VENDA' AND json_extract(o.dados, '$.vendaId') = ${v}.id)
     OR (o.tipo = 'CANCELAR_TROCA' AND json_extract(o.dados, '$.trocaId') IN
           (SELECT t.id FROM trocas t WHERE t.venda_nova_id = ${v}.id))))`;

const existe = (db: BancoAberto, sql: string, ...p: unknown[]): boolean =>
  (db.prepare(`SELECT EXISTS (${sql}) AS e`).get(...p) as { e: number }).e === 1;

export function devolucaoAtiva(db: BancoAberto, devolucaoId: string): boolean {
  return existe(
    db,
    `SELECT 1 FROM devolucoes d WHERE d.id = ? AND ${SQL_DEVOLUCAO_ATIVA('d')}`,
    devolucaoId,
  );
}

export function trocaCanceladaAqui(db: BancoAberto, trocaId: string): boolean {
  return existe(
    db,
    `SELECT 1 FROM trocas t WHERE t.id = ? AND ${SQL_TROCA_CANCELADA_AQUI('t')}`,
    trocaId,
  );
}

/** A venda tem devolução que não foi cancelada (o estorno local recusa, como o servidor). */
export function temDevolucaoAtiva(db: BancoAberto, vendaId: string): boolean {
  return existe(
    db,
    `SELECT 1 FROM devolucoes d WHERE d.venda_id = ? AND ${SQL_DEVOLUCAO_ATIVA('d')}`,
    vendaId,
  );
}

// --- crédito do cliente ---

interface CreditoLocal {
  id: string;
  cliente_id: string;
  vendedor_id: string | null;
  troca_id: string | null;
  devolucao_id: string | null;
  valor: string;
  situacao: string | null;
}

/** Saldo do crédito no computador: o valor menos os usos (do servidor e provisórios). É prévia. */
export function saldoDoCredito(db: BancoAberto, creditoId: string): bigint {
  const c = db
    .prepare('SELECT valor FROM creditos_cliente WHERE id = ? AND deleted_at IS NULL')
    .get(creditoId) as { valor: string } | undefined;
  if (!c) return 0n;
  const usos = db
    .prepare('SELECT valor FROM usos_credito WHERE credito_id = ? AND deleted_at IS NULL')
    .all(creditoId) as { valor: string }[];
  return usos.reduce((t, u) => t - exigirCentavos(u.valor), exigirCentavos(c.valor));
}

/** O crédito ainda vale: nem cancelado no servidor nem pela troca ou devolução cancelada aqui. */
export function creditoValido(db: BancoAberto, c: CreditoLocal): boolean {
  if (c.situacao === 'CANCELADO') return false;
  if (c.troca_id && trocaCanceladaAqui(db, c.troca_id)) return false;
  if (c.devolucao_id && !devolucaoAtiva(db, c.devolucao_id)) return false;
  return true;
}

/** Os créditos em aberto do cliente, deste vendedor, com o saldo do computador (para Vender e Meus clientes). */
export function creditosDoCliente(
  db: BancoAberto,
  clienteId: string,
): { id: string; saldo: bigint; origem: string | null }[] {
  const linhas = db
    .prepare(
      `SELECT id, cliente_id, vendedor_id, troca_id, devolucao_id, valor, situacao, origem
         FROM creditos_cliente WHERE cliente_id = ? AND deleted_at IS NULL ORDER BY gerado_em, id`,
    )
    .all(clienteId) as (CreditoLocal & { origem: string | null })[];
  return linhas
    .filter((c) => ehDoVendedor(db, c.vendedor_id) && creditoValido(db, c))
    .map((c) => ({ id: c.id, saldo: saldoDoCredito(db, c.id), origem: c.origem }))
    .filter((c) => c.saldo > 0n);
}

/**
 * Os pagamentos em crédito da venda (tarefa 6.8): forma `CREDITO` com o crédito, cada crédito uma vez, do mesmo
 * cliente, deste vendedor, válido e com saldo no computador. Os textos são os do servidor.
 */
export function conferirPagamentosEmCredito(
  db: BancoAberto,
  clienteId: string,
  pagamentos: readonly PagamentoParaEnviar[],
): void {
  const vistos = new Set<string>();
  for (const p of pagamentos) {
    if (p.forma !== 'CREDITO') {
      if (p.creditoId) recusar('CREDITO_EM_OUTRA_FORMA');
      continue;
    }
    if (!p.creditoId) recusar('CREDITO_SEM_ID');
    else if (vistos.has(p.creditoId)) recusar('CREDITO_REPETIDO');
    else vistos.add(p.creditoId);
  }
  for (const p of pagamentos) {
    if (p.forma !== 'CREDITO' || !p.creditoId) continue;
    const c = db
      .prepare(
        `SELECT id, cliente_id, vendedor_id, troca_id, devolucao_id, valor, situacao
           FROM creditos_cliente WHERE id = ? AND deleted_at IS NULL`,
      )
      .get(p.creditoId) as CreditoLocal | undefined;
    if (!c || !ehDoVendedor(db, c.vendedor_id)) return recusar('CREDITO_NAO_ENCONTRADO');
    if (c.cliente_id !== clienteId) recusar('CREDITO_DE_OUTRO_CLIENTE');
    const saldo = saldoDoCredito(db, c.id);
    if (!creditoValido(db, c) || saldo <= 0n) recusar('CREDITO_SEM_SALDO');
    if (exigirCentavos(p.valor) > saldo)
      recusar(
        'CREDITO_ACIMA_DO_SALDO',
        `Neste crédito sobram R$ ${reais(saldo)}. Diminua o valor e pague o resto de outra forma.`,
      );
  }
}

/** O uso do crédito do pagamento volta (estorno do uso, ligado ao reembolso em crédito), como o servidor. */
export function estornarUsoDoCredito(
  db: BancoAberto,
  agora: Date,
  operacaoId: string,
  pagamentoId: string,
  reembolsoId: string,
  quando: string,
): void {
  const uso = db
    .prepare(
      `SELECT id, credito_id, venda_id, valor FROM usos_credito
        WHERE pagamento_id = ? AND tipo = 'USO' AND deleted_at IS NULL
        ORDER BY (origem_local = 'SERVIDOR') DESC, id LIMIT 1`,
    )
    .get(pagamentoId) as
    { id: string; credito_id: string; venda_id: string | null; valor: string } | undefined;
  if (!uso) return;
  inserir(db, 'usos_credito', uuidV7(agora), operacaoId, {
    credito_id: uso.credito_id,
    tipo: 'ESTORNO',
    venda_id: uso.venda_id,
    pagamento_id: reembolsoId,
    valor: textoDeCentavos(-exigirCentavos(uso.valor)),
    estorna_id: uso.id,
    usado_em: quando,
  });
}

// --- as peças da venda e o que ainda pode voltar ---

export interface PecaDaVendaLocal {
  produtoId: string;
  nome: string;
  /** A linha da venda que vale agora (a do servidor, quando já chegou). */
  itemVendaId: string;
  vendidas: number;
  /** Preço × quantidade − desconto da linha, em centavos. */
  total: bigint;
  jaDevolvidas: number;
  valorJaDevolvido: bigint;
  podemVoltar: number;
}

/** As peças agrupadas pelo produto, em ordem estável (produto; boa antes de com defeito), como o servidor. */
function porProduto(pecas: readonly PecaQueVolta[]): Map<string, PecaQueVolta[]> {
  const ordenadas = [...pecas].sort(
    (a, b) =>
      a.produtoId.toLowerCase().localeCompare(b.produtoId.toLowerCase()) ||
      a.estado.localeCompare(b.estado),
  );
  const grupos = new Map<string, PecaQueVolta[]>();
  for (const p of ordenadas) grupos.set(p.produtoId, [...(grupos.get(p.produtoId) ?? []), p]);
  return grupos;
}

function nomeDoProduto(db: BancoAberto, produtoId: string): string {
  const r = db.prepare('SELECT nome FROM produtos WHERE id = ?').get(produtoId) as
    { nome: string | null } | undefined;
  return r?.nome ?? 'Peça';
}

/**
 * As peças da venda com o que já voltou e o que ainda pode voltar (decisão 6 do plano): a cópia do servidor
 * (`itens_devolucao` das devoluções não canceladas) e, para a devolução ou troca ainda sem cópia do servidor, as peças
 * do JSON da operação na outbox, pelo PRODUTO (o id da linha da venda muda quando a venda chega do servidor). A cópia
 * do servidor sempre ganha; nada conta em dobro.
 */
export function pecasDaVenda(db: BancoAberto, vendaId: string): Map<string, PecaDaVendaLocal> {
  let itens = db
    .prepare(
      `SELECT id, produto_id, quantidade, preco_unitario, desconto, origem_local FROM itens_venda
        WHERE venda_id = ? AND deleted_at IS NULL ORDER BY id`,
    )
    .all(vendaId) as {
    id: string;
    produto_id: string;
    quantidade: number;
    preco_unitario: string | null;
    desconto: string | null;
    origem_local: string;
  }[];
  // Quando a cópia do servidor já chegou, as linhas provisórias que ainda não saíram não contam.
  if (itens.some((i) => i.origem_local === 'SERVIDOR'))
    itens = itens.filter((i) => i.origem_local === 'SERVIDOR');
  const pecas = new Map<string, PecaDaVendaLocal>();
  for (const i of itens) {
    const total =
      exigirCentavos(i.preco_unitario ?? '0') * BigInt(i.quantidade) -
      exigirCentavos(i.desconto ?? '0');
    const p = pecas.get(i.produto_id);
    if (p) {
      p.vendidas += i.quantidade;
      p.total += total;
    } else
      pecas.set(i.produto_id, {
        produtoId: i.produto_id,
        nome: nomeDoProduto(db, i.produto_id),
        itemVendaId: i.id,
        vendidas: i.quantidade,
        total,
        jaDevolvidas: 0,
        valorJaDevolvido: 0n,
        podemVoltar: 0,
      });
  }
  // O que já voltou pela cópia do servidor.
  for (const r of db
    .prepare(
      `SELECT iv.produto_id, i.quantidade, i.valor FROM itens_devolucao i
         JOIN devolucoes d ON d.id = i.devolucao_id
         JOIN itens_venda iv ON iv.id = i.item_venda_id
        WHERE iv.venda_id = ? AND i.origem_local = 'SERVIDOR' AND i.deleted_at IS NULL
          AND ${SQL_DEVOLUCAO_ATIVA('d')}`,
    )
    .all(vendaId) as { produto_id: string; quantidade: number; valor: string | null }[]) {
    const p = pecas.get(r.produto_id);
    if (!p) continue;
    p.jaDevolvidas += r.quantidade;
    p.valorJaDevolvido += exigirCentavos(r.valor ?? '0');
  }
  // O que voltou por devolução ou troca ainda sem cópia do servidor, na ordem da fila.
  for (const op of db
    .prepare(
      `SELECT tipo, dados FROM outbox
        WHERE tipo IN ('DEVOLUCAO', 'TROCA') AND situacao IN ${OUTBOX_EM_ANDAMENTO} ORDER BY ordem`,
    )
    .all() as { tipo: string; dados: string }[]) {
    let dados: Record<string, unknown>;
    try {
      dados = JSON.parse(op.dados) as Record<string, unknown>;
    } catch {
      continue;
    }
    const dev = (op.tipo === 'TROCA' ? dados['devolucao'] : dados) as
      { id?: string; vendaId?: string; itens?: PecaQueVolta[] } | undefined;
    if (!dev?.id || dev.vendaId !== vendaId || !Array.isArray(dev.itens)) continue;
    const doServidor = existe(
      db,
      "SELECT 1 FROM devolucoes WHERE id = ? AND origem_local = 'SERVIDOR'",
      dev.id,
    );
    if (doServidor || !devolucaoAtiva(db, dev.id)) continue;
    for (const [produto, grupo] of porProduto(dev.itens)) {
      const p = pecas.get(produto);
      if (!p) continue;
      for (const g of grupo) {
        const q = Math.min(g.quantidade, p.vendidas - p.jaDevolvidas);
        if (q <= 0) continue;
        p.valorJaDevolvido += valorDaDevolucao(
          p.total,
          p.vendidas,
          p.jaDevolvidas,
          p.valorJaDevolvido,
          q,
        );
        p.jaDevolvidas += q;
      }
    }
  }
  for (const p of pecas.values()) p.podemVoltar = Math.max(0, p.vendidas - p.jaDevolvidas);
  return pecas;
}

// --- a venda e a prévia da devolução ---

interface VendaAchada {
  id: string;
  status: string | null;
  remessa_id: string;
  cliente_id: string | null;
  total: string | null;
  vendida_em: string;
  vendedor_id: string | null;
  troca_id: string | null;
  maleta: string | null;
  vendedor_da_maleta: string | null;
}

export function acharVenda(db: BancoAberto, vendaId: string): VendaAchada {
  const v = db
    .prepare(
      `SELECT v.id, v.status, v.remessa_id, v.cliente_id, v.total, v.vendida_em, v.vendedor_id, v.troca_id,
              r.status AS maleta, r.vendedor_id AS vendedor_da_maleta
         FROM vendas v LEFT JOIN remessas r ON r.id = v.remessa_id
        WHERE v.id = ? AND v.deleted_at IS NULL`,
    )
    .get(vendaId) as VendaAchada | undefined;
  if (!v || !ehDoVendedor(db, v.vendedor_id)) throw new ErroDaOperacao('VENDA_NAO_ENCONTRADA');
  return v;
}

/** A venda está estornada, no servidor ou aqui. */
export function vendaEstornada(db: BancoAberto, v: { id: string; status: string | null }): boolean {
  return (
    v.status === 'ESTORNADA' ||
    existe(db, `SELECT 1 FROM vendas v WHERE v.id = ? AND ${SQL_VENDA_ESTORNADA_AQUI('v')}`, v.id)
  );
}

function somaDe(db: BancoAberto, sql: string, ...p: unknown[]): bigint {
  return (db.prepare(sql).all(...p) as { valor: string }[]).reduce(
    (t, r) => t + exigirCentavos(r.valor),
    0n,
  );
}

interface PecaCalculada {
  produtoId: string;
  itemVendaId: string;
  quantidade: number;
  estado: EstadoDaPeca;
  valor: bigint;
}

export interface PreviaInterna {
  venda: VendaAchada;
  motivo: MotivoDeDevolucao;
  observacao: string | null;
  maletaComOVendedor: boolean;
  calculadas: PecaCalculada[];
  valor: bigint;
  /** O que a devolução abate do que o cliente ainda deve nas parcelas (venda a prazo; zero na venda à vista). */
  abatido: bigint;
  emCredito: bigint;
  emDinheiro: bigint;
  dentroDoPrazo: boolean;
  /** A autorização aprovada e válida que serve (dúvida V6: usada sozinha). */
  autorizacao: { id: string; validaAte: string } | null;
  /** Por que a autorização da venda não serve (a mais recente), quando nenhuma serve. */
  problemaDaAutorizacao: CodigoDaFase6 | null;
  desistenciaAte: string;
  defeitoAte: string;
}

function conferirTexto(
  observacao: string | undefined | null,
  motivo: MotivoDeDevolucao,
): string | null {
  const limpa = (observacao ?? '').trim();
  if (limpa.length > LIMITE_DO_TEXTO) recusar('TEXTO_LONGO');
  if (motivo === 'OUTRO' && limpa === '') recusar('OBSERVACAO_OBRIGATORIA');
  return limpa === '' ? null : limpa;
}

function conferirMotivo(motivo: unknown): MotivoDeDevolucao {
  if (!(MOTIVOS_DE_DEVOLUCAO as readonly unknown[]).includes(motivo)) recusar('MOTIVO_INVALIDO');
  return motivo as MotivoDeDevolucao;
}

function conferirPecas(itens: readonly PecaQueVolta[], motivo: MotivoDeDevolucao): void {
  if (itens.length === 0) recusar('SEM_PECAS');
  const vistas = new Set<string>();
  for (const p of itens) {
    if (!Number.isInteger(p.quantidade) || p.quantidade <= 0 || p.quantidade > 100_000)
      recusar('QUANTIDADE_INVALIDA');
    if (!(ESTADOS_DA_PECA as readonly unknown[]).includes(p.estado)) recusar('ESTADO_INVALIDO');
    if (motivo === 'DEFEITO' && p.estado !== 'DEFEITO') recusar('DEFEITO_COM_PECA_BOA');
    const chave = `${p.produtoId}/${p.estado}`;
    if (vistas.has(chave)) recusar('PECA_REPETIDA');
    vistas.add(chave);
  }
}

/** Autorizações usadas aqui: uma devolução ou troca aguardando (ou enviada) já leva o id. */
export function autorizacaoUsadaAqui(db: BancoAberto, autorizacaoId: string): boolean {
  return existe(
    db,
    `SELECT 1 FROM outbox WHERE situacao IN ${OUTBOX_EM_ANDAMENTO} AND (
        (tipo = 'DEVOLUCAO' AND json_extract(dados, '$.autorizacaoId') = ?)
     OR (tipo = 'TROCA' AND json_extract(dados, '$.devolucao.autorizacaoId') = ?))`,
    autorizacaoId,
    autorizacaoId,
  );
}

function problemaDaAutorizacao(
  db: BancoAberto,
  a: { id: string; situacao: string | null; valida_ate: string | null; motivo: string | null },
  motivo: MotivoDeDevolucao,
  itens: readonly PecaQueVolta[],
  hoje: string,
): CodigoDaFase6 | null {
  if (a.situacao === 'PEDIDA' || a.situacao === 'NEGADA') return 'AUTORIZACAO_NAO_APROVADA';
  if (a.situacao === 'USADA' || autorizacaoUsadaAqui(db, a.id)) return 'AUTORIZACAO_JA_USADA';
  if (a.situacao !== 'APROVADA' || !a.valida_ate || a.valida_ate.slice(0, 10) < hoje)
    return 'AUTORIZACAO_VENCIDA';
  if (a.motivo !== motivo) return 'AUTORIZACAO_NAO_CONFERE';
  const autorizadas = new Map<string, number>();
  for (const r of db
    .prepare(
      `SELECT iv.produto_id, a.quantidade FROM itens_autorizacao a
         JOIN itens_venda iv ON iv.id = a.item_venda_id
        WHERE a.autorizacao_id = ? AND a.deleted_at IS NULL`,
    )
    .all(a.id) as { produto_id: string; quantidade: number }[])
    autorizadas.set(r.produto_id, (autorizadas.get(r.produto_id) ?? 0) + r.quantidade);
  for (const [produto, grupo] of porProduto(itens)) {
    const pedidas = grupo.reduce((t, g) => t + g.quantidade, 0);
    if (pedidas > (autorizadas.get(produto) ?? 0)) return 'AUTORIZACAO_NAO_CONFERE';
  }
  return null;
}

/**
 * Confere o pedido e calcula a prévia da devolução, sem gravar nada (também a tela usa). Recusa o que o servidor
 * recusaria; o prazo e a autorização ficam na resposta para quem chama decidir.
 */
/**
 * O que o cliente ainda deve da venda, pela conta da C1 (total menos o devolvido menos o pago líquido, inclusive em
 * crédito): vale também para a venda a prazo que o computador ainda não enviou (sem parcela local) e não precisa de baixa
 * provisória. Nunca negativo.
 */
export function emAbertoDaVenda(db: BancoAberto, venda: VendaAchada): bigint {
  const devolvido = somaDe(
    db,
    `SELECT d.valor_devolvido AS valor FROM devolucoes d
      WHERE d.venda_id = ? AND d.deleted_at IS NULL AND ${SQL_DEVOLUCAO_ATIVA('d')}`,
    venda.id,
  );
  const liquido = somaDe(
    db,
    `SELECT valor FROM pagamentos_cliente
      WHERE venda_id = ? AND tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO', 'REEMBOLSO', 'ESTORNO_REEMBOLSO')
        AND deleted_at IS NULL`,
    venda.id,
  );
  const aberto = exigirCentavos(venda.total ?? '0') - devolvido - liquido;
  return aberto > 0n ? aberto : 0n;
}

export function preverDevolucao(
  db: BancoAberto,
  agora: Date,
  pedido: {
    vendaId: string;
    motivo: unknown;
    observacao?: string | null;
    itens: readonly PecaQueVolta[];
  },
  naTroca: boolean,
  conferirAntesDaVenda?: () => void,
  /** A tela pede a prévia antes de escolher as peças (prazo, autorização). */
  permitirSemPecas = false,
): PreviaInterna {
  const motivo = conferirMotivo(pedido.motivo);
  const observacao = conferirTexto(pedido.observacao, motivo);
  conferirAntesDaVenda?.();
  if (!(permitirSemPecas && pedido.itens.length === 0)) conferirPecas(pedido.itens, motivo);
  const venda = acharVenda(db, pedido.vendaId);
  if (vendaEstornada(db, venda)) throw new ErroDaOperacao('VENDA_JA_ESTORNADA');
  if (venda.maleta === 'EM_ACERTO') recusar('MALETA_EM_ACERTO');
  const maletaComOVendedor = MALETA_COM_O_VENDEDOR.has(venda.maleta ?? '');

  const linhas = pecasDaVenda(db, venda.id);
  for (const p of pedido.itens) if (!linhas.has(p.produtoId)) recusar('PECA_FORA_DA_VENDA');
  const calculadas: PecaCalculada[] = [];
  for (const [produto, grupo] of porProduto(pedido.itens)) {
    const linha = linhas.get(produto);
    if (!linha) return recusar('PECA_FORA_DA_VENDA');
    const pedidas = grupo.reduce((t, g) => t + g.quantidade, 0);
    if (pedidas > linha.podemVoltar)
      recusar(
        'ACIMA_DO_VENDIDO',
        linha.podemVoltar <= 0
          ? `Todas as peças de "${linha.nome}" desta venda já foram devolvidas.`
          : `Desta venda ainda podem voltar só ${linha.podemVoltar} peça(s) de "${linha.nome}". Confira a quantidade.`,
      );
    let ja = linha.jaDevolvidas;
    let valorJa = linha.valorJaDevolvido;
    for (const g of grupo) {
      const valor = valorDaDevolucao(linha.total, linha.vendidas, ja, valorJa, g.quantidade);
      calculadas.push({
        produtoId: produto,
        itemVendaId: linha.itemVendaId,
        quantidade: g.quantidade,
        estado: g.estado,
        valor,
      });
      ja += g.quantidade;
      valorJa += valor;
    }
  }
  const valor = calculadas.reduce((t, c) => t + c.valor, 0n);
  // A mesma divisão do servidor (ADR-0014, item 10): primeiro abate o que o cliente ainda deve; do resto, o crédito
  // líquido da venda volta primeiro como crédito (na troca, o resto todo) e só o que sobrar em dinheiro.
  const emAberto = emAbertoDaVenda(db, venda);
  const abatido = valor < emAberto ? valor : emAberto;
  const resto = valor - abatido;
  const creditoLiquido = somaDe(
    db,
    "SELECT valor FROM pagamentos_cliente WHERE venda_id = ? AND forma = 'CREDITO' AND deleted_at IS NULL",
    venda.id,
  );
  const emCredito = naTroca
    ? resto
    : resto < creditoLiquido
      ? resto
      : creditoLiquido > 0n
        ? creditoLiquido
        : 0n;

  const prazos = lerPrazos(lerEstado(db, CHAVES.parametros));
  const dentro = dentroDoPrazo(venda.vendida_em, agora, motivo, prazos);
  let autorizacao: PreviaInterna['autorizacao'] = null;
  let problema: CodigoDaFase6 | null = null;
  if (!dentro) {
    const hoje = diaEmBrasilia(agora);
    const lista = db
      .prepare(
        `SELECT id, situacao, valida_ate, motivo FROM autorizacoes_excecao
          WHERE venda_id = ? AND deleted_at IS NULL ORDER BY pedida_em DESC, id DESC`,
      )
      .all(venda.id) as {
      id: string;
      situacao: string | null;
      valida_ate: string | null;
      motivo: string | null;
    }[];
    for (const a of lista) {
      const p = problemaDaAutorizacao(db, a, motivo, pedido.itens, hoje);
      if (p === null) {
        autorizacao = { id: a.id, validaAte: (a.valida_ate ?? '').slice(0, 10) };
        break;
      }
      problema ??= p;
    }
  }
  return {
    venda,
    motivo,
    observacao,
    maletaComOVendedor,
    calculadas,
    valor,
    abatido,
    emCredito,
    emDinheiro: resto - emCredito,
    dentroDoPrazo: dentro,
    autorizacao,
    problemaDaAutorizacao: autorizacao ? null : problema,
    desistenciaAte: garantiaAte(venda.vendida_em, prazoDoMotivo('ARREPENDIMENTO', prazos)),
    defeitoAte: garantiaAte(venda.vendida_em, prazoDoMotivo('DEFEITO', prazos)),
  };
}

function exigirPrazo(p: PreviaInterna): void {
  if (!p.dentroDoPrazo && !p.autorizacao) recusar(p.problemaDaAutorizacao ?? 'FORA_DO_PRAZO');
}

const pecasDoJson = (itens: readonly PecaQueVolta[]) =>
  itens.map((i) => ({ produtoId: i.produtoId, quantidade: i.quantidade, estado: i.estado }));

/** As linhas provisórias da devolução (como o `ServicoDeDevolucoes` grava). */
function gravarDevolucao(
  db: BancoAberto,
  agora: Date,
  operacaoId: string,
  x: {
    id: string;
    p: PreviaInterna;
    quando: string;
    forma: string | null;
    troca?: { trocaId: string; creditoId: string };
  },
): void {
  const { p } = x;
  const v = p.venda;
  inserir(db, 'devolucoes', x.id, operacaoId, {
    venda_id: v.id,
    cliente_id: v.cliente_id,
    vendedor_id: v.vendedor_id ?? v.vendedor_da_maleta,
    remessa_id: v.remessa_id,
    motivo: p.motivo,
    observacao: p.observacao,
    destino: x.troca ? 'TROCA' : 'REEMBOLSO',
    dentro_garantia: p.dentroDoPrazo ? 1 : 0,
    situacao: 'ACEITA',
    autorizacao_id: p.autorizacao?.id ?? null,
    valor_devolvido: textoDeCentavos(p.valor),
    valor_reembolsado: textoDeCentavos(p.emDinheiro),
    valor_em_credito: textoDeCentavos(p.emCredito),
    valor_abatido: textoDeCentavos(p.abatido),
    forma_reembolso: x.forma,
    devolvida_em: x.quando,
  });
  for (const c of p.calculadas) {
    const destino = c.estado === 'BOA' && p.maletaComOVendedor ? 'MALETA' : 'LOJA';
    inserir(db, 'itens_devolucao', uuidV7(agora), operacaoId, {
      devolucao_id: x.id,
      item_venda_id: c.itemVendaId,
      quantidade: c.quantidade,
      valor: textoDeCentavos(c.valor),
      estado_peca: c.estado,
      destino_peca: destino,
    });
    if (destino === 'MALETA')
      inserir(db, 'movimentos_remessa', uuidV7(agora), operacaoId, {
        remessa_id: v.remessa_id,
        produto_id: c.produtoId,
        tipo: x.troca ? 'TROCA_ENTRADA' : 'DEVOLUCAO_CLIENTE',
        quantidade: c.quantidade,
        venda_id: v.id,
        devolucao_id: x.id,
      });
  }
  if (p.emCredito > 0n)
    // Na devolução de venda paga com crédito, o crédito que volta é do servidor: aqui só o reembolso (decisão 7).
    inserir(db, 'pagamentos_cliente', uuidV7(agora), operacaoId, {
      venda_id: v.id,
      tipo: 'REEMBOLSO',
      forma: 'CREDITO',
      valor: textoDeCentavos(-p.emCredito),
      pago_em: x.quando,
      devolucao_id: x.id,
      credito_id: x.troca?.creditoId ?? null,
    });
  if (p.emDinheiro > 0n)
    inserir(db, 'pagamentos_cliente', uuidV7(agora), operacaoId, {
      venda_id: v.id,
      tipo: 'REEMBOLSO',
      forma: x.forma,
      valor: textoDeCentavos(-p.emDinheiro),
      pago_em: x.quando,
      devolucao_id: x.id,
    });
}

// --- as operações ---

export function registrarDevolucao(
  db: BancoAberto,
  agora: Date,
  d: DevolucaoParaEnviar,
): { operacaoId: string } {
  const p = preverDevolucao(db, agora, d, false, () => {
    if (!(FORMAS_DE_PAGAMENTO as readonly unknown[]).includes(d.formaReembolso))
      recusar('FORMA_DO_REEMBOLSO');
  });
  exigirPrazo(p);
  const quando = agora.toISOString();
  const dados = JSON.stringify({
    id: d.id,
    vendaId: d.vendaId,
    motivo: p.motivo,
    observacao: p.observacao,
    formaReembolso: d.formaReembolso,
    itens: pecasDoJson(d.itens),
    // Só o servidor calcula o valor (reforço 2 da aprovação): o computador não manda a prévia.
    valorConferido: null,
    autorizacaoId: p.autorizacao?.id ?? null,
    devolvidaEm: quando,
  });
  gravarDevolucao(db, agora, d.id, { id: d.id, p, quando, forma: d.formaReembolso });
  enfileirar(db, { operacaoId: d.id, tipo: 'DEVOLUCAO', dados, agora });
  return { operacaoId: d.id };
}

export function registrarPedido(
  db: BancoAberto,
  agora: Date,
  pedido: PedidoDeAutorizacaoParaEnviar,
): { operacaoId: string } {
  const p = preverDevolucao(db, agora, pedido, false);
  const hoje = diaEmBrasilia(agora);
  if (
    existe(
      db,
      `SELECT 1 FROM autorizacoes_excecao WHERE venda_id = ? AND deleted_at IS NULL
          AND (situacao = 'PEDIDA' OR (situacao = 'APROVADA' AND substr(valida_ate, 1, 10) >= ?))`,
      pedido.vendaId,
      hoje,
    )
  )
    recusar('AUTORIZACAO_JA_PEDIDA');
  if (p.dentroDoPrazo) recusar('AUTORIZACAO_DESNECESSARIA');
  const quando = agora.toISOString();
  const dados = JSON.stringify({
    id: pedido.id,
    vendaId: pedido.vendaId,
    motivo: p.motivo,
    observacao: p.observacao,
    itens: pecasDoJson(pedido.itens),
    pedidaEm: quando,
  });
  inserir(db, 'autorizacoes_excecao', pedido.id, pedido.id, {
    venda_id: p.venda.id,
    vendedor_id: p.venda.vendedor_id ?? p.venda.vendedor_da_maleta,
    cliente_id: p.venda.cliente_id,
    motivo: p.motivo,
    observacao: p.observacao,
    tipo: 'FORA_DO_PRAZO',
    situacao: 'PEDIDA',
    valor_pedido: textoDeCentavos(p.valor),
    pedida_em: quando,
  });
  const porLinha = new Map<string, number>();
  for (const c of p.calculadas)
    porLinha.set(c.itemVendaId, (porLinha.get(c.itemVendaId) ?? 0) + c.quantidade);
  for (const [item, quantidade] of porLinha)
    inserir(db, 'itens_autorizacao', uuidV7(agora), pedido.id, {
      autorizacao_id: pedido.id,
      item_venda_id: item,
      quantidade,
    });
  enfileirar(db, { operacaoId: pedido.id, tipo: 'PEDIDO_AUTORIZACAO', dados, agora });
  return { operacaoId: pedido.id };
}

export function registrarTroca(
  db: BancoAberto,
  agora: Date,
  t: TrocaParaEnviar,
): { operacaoId: string } {
  const a = t.devolucao;
  const p = preverDevolucao(db, agora, a, true);
  exigirPrazo(p);

  // A venda nova B: da maleta conferida, com saldo (a peça boa de A que volta para a mesma maleta conta).
  const b = t.venda;
  const remessa = db
    .prepare('SELECT status, vendedor_id FROM remessas WHERE id = ? AND deleted_at IS NULL')
    .get(b.remessaId) as { status: string | null; vendedor_id: string | null } | undefined;
  if (!remessa || !ehDoVendedor(db, remessa.vendedor_id))
    throw new ErroDaOperacao('MALETA_NAO_ENCONTRADA');
  if (remessa.status === 'EM_ACERTO') recusar('MALETA_EM_ACERTO');
  if (remessa.status === 'ENVIADA') recusar('MALETA_NAO_CONFERIDA');
  if (remessa.status !== 'CONFERIDA') throw new ErroDaOperacao('MALETA_NAO_ENCONTRADA');
  if (b.itens.length === 0) recusar('SEM_PECAS_NOVAS');
  const voltam = new Map<string, number>();
  if (p.maletaComOVendedor && p.venda.remessa_id === b.remessaId)
    for (const c of p.calculadas)
      if (c.estado === 'BOA')
        voltam.set(c.produtoId, (voltam.get(c.produtoId) ?? 0) + c.quantidade);
  const porPeca = new Map<string, number>();
  for (const i of b.itens) porPeca.set(i.produtoId, (porPeca.get(i.produtoId) ?? 0) + i.quantidade);
  let subtotal = 0n;
  let desconto = 0n;
  const linhas = b.itens.map((item) => {
    const peca = db
      .prepare(
        `SELECT preco_remessa FROM itens_remessa
          WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL LIMIT 1`,
      )
      .get(b.remessaId, item.produtoId) as { preco_remessa: string } | undefined;
    if (!peca) throw new ErroDaOperacao('PECA_FORA_DA_MALETA');
    const saldo = saldoDaPeca(db, b.remessaId, item.produtoId) + (voltam.get(item.produtoId) ?? 0);
    if ((porPeca.get(item.produtoId) ?? 0) > saldo) throw new ErroDaOperacao('SEM_SALDO');
    const preco = exigirCentavos(peca.preco_remessa);
    const valor = preco * BigInt(item.quantidade);
    const d = exigirCentavos(item.desconto ?? '0');
    if (d < 0n || d > valor) throw new ErroDaOperacao('DESCONTO_MAIOR_QUE_A_PECA');
    subtotal += valor;
    desconto += d;
    return { item, preco, desconto: d };
  });
  const limite = limiteDeDesconto(db);
  if (limite !== null && desconto * 100n > subtotal * BigInt(limite))
    throw new ErroDaOperacao('DESCONTO_ACIMA_DO_LIMITE');
  const total = subtotal - desconto;
  const valorA = p.valor;
  // O crédito da troca é só o que passou do que A ainda devia (A a prazo abate as parcelas primeiro); A sem nada pago
  // não gera crédito.
  const creditoDeA = p.emCredito;
  const creditoUsado = creditoDeA < total ? creditoDeA : total;
  let pago = creditoUsado;
  for (const pg of b.pagamentos) {
    if (pg.forma === 'CREDITO' || pg.creditoId) recusar('CREDITO_EM_OUTRA_FORMA');
    const v = exigirCentavos(pg.valor);
    if (v <= 0n) throw new ErroDaOperacao('PAGAMENTO_SEM_VALOR');
    pago += v;
  }
  if (creditoDeA >= total && b.pagamentos.length > 0) recusar('PAGO_SEM_DIFERENCA');
  if (pago > total) throw new ErroDaOperacao('PAGO_PASSA_DO_TOTAL');
  // Parcelas (tarefa 7.11): só quando sobra a prazo; o computador não divide nada.
  const parcelamento = conferirParcelamento(b.parcelamento, total - pago, agora);

  const quando = agora.toISOString();
  const dados = JSON.stringify({
    id: t.id,
    creditoId: t.creditoId,
    pagamentoDoCreditoId: t.pagamentoDoCreditoId,
    devolucao: {
      id: a.id,
      vendaId: a.vendaId,
      motivo: p.motivo,
      observacao: p.observacao,
      itens: pecasDoJson(a.itens),
      autorizacaoId: p.autorizacao?.id ?? null,
    },
    venda: {
      id: b.id,
      remessaId: b.remessaId,
      itens: linhas.map((l) => ({
        produtoId: l.item.produtoId,
        quantidade: l.item.quantidade,
        desconto: textoDeCentavos(l.desconto),
      })),
      pagamentos: b.pagamentos.map((pg) => ({
        id: pg.id,
        valor: textoDeCentavos(exigirCentavos(pg.valor)),
        forma: pg.forma,
      })),
      // Só o servidor calcula o total (reforço 2 da aprovação).
      totalConferido: null,
      ...(parcelamento ? { parcelamento } : {}),
    },
    trocadaEm: quando,
  });

  gravarDevolucao(db, agora, t.id, {
    id: a.id,
    p,
    quando,
    forma: null,
    troca: { trocaId: t.id, creditoId: t.creditoId },
  });
  const vendedor = p.venda.vendedor_id ?? p.venda.vendedor_da_maleta ?? remessa.vendedor_id;
  if (creditoDeA > 0n)
    inserir(db, 'creditos_cliente', t.creditoId, t.id, {
      cliente_id: p.venda.cliente_id,
      vendedor_id: vendedor,
      origem: 'TROCA',
      troca_id: t.id,
      devolucao_id: a.id,
      valor: textoDeCentavos(creditoDeA),
      valor_utilizado: textoDeCentavos(creditoUsado),
      situacao: creditoUsado === creditoDeA ? 'UTILIZADO' : 'ABERTO',
      gerado_em: quando,
    });
  inserir(db, 'vendas', b.id, t.id, {
    cliente_id: p.venda.cliente_id,
    remessa_id: b.remessaId,
    status: 'CONCLUIDA',
    subtotal: textoDeCentavos(subtotal),
    desconto: textoDeCentavos(desconto),
    total: textoDeCentavos(total),
    vendida_em: quando,
    troca_id: t.id,
  });
  for (const l of linhas) {
    inserir(db, 'itens_venda', uuidV7(agora), t.id, {
      venda_id: b.id,
      produto_id: l.item.produtoId,
      quantidade: l.item.quantidade,
      preco_unitario: textoDeCentavos(l.preco),
      desconto: textoDeCentavos(l.desconto),
    });
    inserir(db, 'movimentos_remessa', uuidV7(agora), t.id, {
      remessa_id: b.remessaId,
      produto_id: l.item.produtoId,
      tipo: 'TROCA_SAIDA',
      quantidade: -l.item.quantidade,
      venda_id: b.id,
    });
  }
  if (creditoUsado > 0n) {
    inserir(db, 'pagamentos_cliente', t.pagamentoDoCreditoId, t.id, {
      venda_id: b.id,
      tipo: 'PAGAMENTO',
      forma: 'CREDITO',
      valor: textoDeCentavos(creditoUsado),
      pago_em: quando,
      credito_id: t.creditoId,
    });
    inserir(db, 'usos_credito', uuidV7(agora), t.id, {
      credito_id: t.creditoId,
      tipo: 'USO',
      venda_id: b.id,
      pagamento_id: t.pagamentoDoCreditoId,
      valor: textoDeCentavos(creditoUsado),
      usado_em: quando,
    });
  }
  for (const pg of b.pagamentos)
    inserir(db, 'pagamentos_cliente', pg.id, t.id, {
      venda_id: b.id,
      tipo: 'PAGAMENTO',
      forma: pg.forma,
      valor: textoDeCentavos(exigirCentavos(pg.valor)),
      pago_em: quando,
    });
  inserir(db, 'trocas', t.id, t.id, {
    venda_origem_id: a.vendaId,
    devolucao_id: a.id,
    venda_nova_id: b.id,
    cliente_id: p.venda.cliente_id,
    vendedor_id: vendedor,
    diferenca: textoDeCentavos(total - valorA),
    trocada_em: quando,
    situacao: 'ATIVA',
  });
  enfileirar(db, { operacaoId: t.id, tipo: 'TROCA', dados, agora });
  return { operacaoId: t.id };
}

// --- cancelamentos ---

function conferirMotivoDoCancelamento(motivo: string, codigo: CodigoDaFase6): string {
  const limpo = (motivo ?? '').trim();
  if (limpo === '') recusar(codigo);
  if (limpo.length > LIMITE_DO_TEXTO) recusar('TEXTO_LONGO');
  return limpo;
}

interface Movimento {
  id: string;
  remessa_id: string;
  produto_id: string;
  quantidade: number;
  tipo: string;
  venda_id: string | null;
}

/** Os movimentos ainda não desfeitos (nem por um estorno do servidor nem por um provisório). */
function movimentosVivos(db: BancoAberto, onde: string, ...p: unknown[]): Movimento[] {
  return db
    .prepare(
      `SELECT m.id, m.remessa_id, m.produto_id, m.quantidade, m.tipo, m.venda_id FROM movimentos_remessa m
        WHERE ${onde} AND m.estorna_id IS NULL AND m.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM movimentos_remessa e WHERE e.estorna_id = m.id AND e.deleted_at IS NULL)
        ORDER BY m.produto_id, m.id`,
    )
    .all(...p) as Movimento[];
}

function desfazerMovimentos(
  db: BancoAberto,
  agora: Date,
  operacaoId: string,
  movimentos: readonly Movimento[],
  devolucaoId: string | null,
): void {
  for (const m of movimentos)
    inserir(db, 'movimentos_remessa', uuidV7(agora), operacaoId, {
      remessa_id: m.remessa_id,
      produto_id: m.produto_id,
      tipo: m.tipo,
      quantidade: -m.quantidade,
      venda_id: m.venda_id,
      estorna_id: m.id,
      ...(devolucaoId ? { devolucao_id: devolucaoId } : {}),
    });
}

/** O dinheiro (e o crédito) que a devolução devolveu volta para a venda: `ESTORNO_REEMBOLSO` de cada reembolso. */
function desfazerReembolsos(
  db: BancoAberto,
  agora: Date,
  operacaoId: string,
  devolucaoId: string,
  quando: string,
): void {
  const reembolsos = db
    .prepare(
      `SELECT p.id, p.venda_id, p.forma, p.valor, p.credito_id FROM pagamentos_cliente p
        WHERE p.devolucao_id = ? AND p.tipo = 'REEMBOLSO' AND p.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM pagamentos_cliente e WHERE e.estorna_id = p.id AND e.deleted_at IS NULL)
        ORDER BY p.pago_em, p.id`,
    )
    .all(devolucaoId) as {
    id: string;
    venda_id: string;
    forma: string | null;
    valor: string;
    credito_id: string | null;
  }[];
  for (const r of reembolsos)
    inserir(db, 'pagamentos_cliente', uuidV7(agora), operacaoId, {
      venda_id: r.venda_id,
      tipo: 'ESTORNO_REEMBOLSO',
      forma: r.forma,
      valor: textoDeCentavos(-exigirCentavos(r.valor)),
      pago_em: quando,
      devolucao_id: devolucaoId,
      estorna_id: r.id,
      credito_id: r.credito_id,
    });
}

function mesmoDia(antes: string | null, agora: Date): boolean {
  return antes !== null && diaEmBrasilia(antes) === diaEmBrasilia(agora);
}

export function cancelarDevolucao(
  db: BancoAberto,
  agora: Date,
  c: CancelamentoParaEnviar & { devolucaoId: string },
): { operacaoId: string } {
  const motivo = conferirMotivoDoCancelamento(c.motivo, 'MOTIVO_DO_CANCELAMENTO_DA_DEVOLUCAO');
  const d = db
    .prepare(
      `SELECT id, vendedor_id, remessa_id, destino, situacao, devolvida_em FROM devolucoes
        WHERE id = ? AND deleted_at IS NULL`,
    )
    .get(c.devolucaoId) as
    | {
        id: string;
        vendedor_id: string | null;
        remessa_id: string | null;
        destino: string | null;
        situacao: string | null;
        devolvida_em: string | null;
      }
    | undefined;
  if (!d || !ehDoVendedor(db, d.vendedor_id)) return recusar('DEVOLUCAO_NAO_ENCONTRADA');
  if (d.destino === 'TROCA') recusar('DEVOLUCAO_DE_TROCA');
  if (!devolucaoAtiva(db, d.id)) recusar('DEVOLUCAO_JA_CANCELADA');
  if (d.situacao !== 'ACEITA' && d.situacao !== 'EM_REVISAO')
    recusar('DEVOLUCAO_NAO_PODE_SER_CANCELADA');
  if (!mesmoDia(d.devolvida_em, agora)) recusar('CANCELAR_DEVOLUCAO_SO_DA_DONA');
  const movimentos = movimentosVivos(db, 'm.devolucao_id = ?', d.id);
  if (movimentos.length > 0) {
    const maleta = db.prepare('SELECT status FROM remessas WHERE id = ?').get(d.remessa_id) as
      { status: string | null } | undefined;
    if (!MALETA_COM_O_VENDEDOR.has(maleta?.status ?? ''))
      recusar(
        'DEVOLUCAO_NAO_PODE_SER_CANCELADA',
        'A maleta desta devolução já não está com o vendedor. Fale com a dona.',
      );
    for (const m of movimentos)
      if (saldoDaPeca(db, m.remessa_id, m.produto_id) < m.quantidade)
        recusar('PECA_DA_DEVOLUCAO_JA_VENDIDA');
  }
  for (const credito of db
    .prepare('SELECT id FROM creditos_cliente WHERE devolucao_id = ? AND deleted_at IS NULL')
    .all(d.id) as { id: string }[])
    if (
      somaDe(
        db,
        'SELECT valor FROM usos_credito WHERE credito_id = ? AND deleted_at IS NULL',
        credito.id,
      ) !== 0n
    )
      recusar('CREDITO_DA_DEVOLUCAO_JA_USADO');

  const quando = agora.toISOString();
  const dados = JSON.stringify({ id: c.id, devolucaoId: d.id, motivo, canceladaEm: quando });
  desfazerMovimentos(db, agora, c.id, movimentos, d.id);
  desfazerReembolsos(db, agora, c.id, d.id, quando);
  enfileirar(db, { operacaoId: c.id, tipo: 'CANCELAR_DEVOLUCAO', dados, agora });
  return { operacaoId: c.id };
}

export function cancelarTroca(
  db: BancoAberto,
  agora: Date,
  c: CancelamentoParaEnviar & { trocaId: string },
): { operacaoId: string } {
  const motivo = conferirMotivoDoCancelamento(c.motivo, 'MOTIVO_DO_CANCELAMENTO_DA_TROCA');
  const t = db
    .prepare(
      `SELECT id, vendedor_id, venda_origem_id, venda_nova_id, devolucao_id, situacao, trocada_em FROM trocas
        WHERE id = ? AND deleted_at IS NULL`,
    )
    .get(c.trocaId) as
    | {
        id: string;
        vendedor_id: string | null;
        venda_origem_id: string;
        venda_nova_id: string;
        devolucao_id: string;
        situacao: string | null;
        trocada_em: string | null;
      }
    | undefined;
  if (!t || !ehDoVendedor(db, t.vendedor_id)) return recusar('TROCA_NAO_ENCONTRADA');
  if (t.situacao === 'CANCELADA' || trocaCanceladaAqui(db, t.id)) recusar('TROCA_JA_CANCELADA');
  if (!mesmoDia(t.trocada_em, agora)) recusar('CANCELAR_TROCA_SO_DA_DONA');
  const dev = db.prepare('SELECT situacao FROM devolucoes WHERE id = ?').get(t.devolucao_id) as
    { situacao: string | null } | undefined;
  if (dev && dev.situacao !== 'ACEITA' && dev.situacao !== 'EM_REVISAO')
    recusar('TROCA_NAO_PODE_SER_CANCELADA');
  if (temDevolucaoAtiva(db, t.venda_nova_id))
    recusar(
      'TROCA_NAO_PODE_SER_CANCELADA',
      'A peça que o cliente levou nesta troca já foi devolvida. Cancele essa devolução antes.',
    );
  const maletaDe = (vendaId: string): string | null =>
    (
      db.prepare('SELECT remessa_id FROM vendas WHERE id = ?').get(vendaId) as
        { remessa_id: string | null } | undefined
    )?.remessa_id ?? null;
  const statusDe = (remessaId: string | null): string =>
    (
      db.prepare('SELECT status FROM remessas WHERE id = ?').get(remessaId) as
        { status: string | null } | undefined
    )?.status ?? '';
  const maletaB = maletaDe(t.venda_nova_id);
  const voltaram = movimentosVivos(db, 'm.devolucao_id = ?', t.devolucao_id);
  const saidasDeB = movimentosVivos(
    db,
    "m.venda_id = ? AND m.tipo = 'TROCA_SAIDA'",
    t.venda_nova_id,
  );
  if (
    !MALETA_COM_O_VENDEDOR.has(statusDe(maletaB)) ||
    voltaram.some((m) => !MALETA_COM_O_VENDEDOR.has(statusDe(m.remessa_id)))
  )
    recusar(
      'TROCA_NAO_PODE_SER_CANCELADA',
      'Uma das maletas desta troca já não está com o vendedor. Fale com a dona.',
    );
  for (const m of voltaram) {
    // A mesma peça como B na mesma maleta volta com o desfazer de B antes de A sair.
    const deB = saidasDeB
      .filter((s) => s.remessa_id === m.remessa_id && s.produto_id === m.produto_id)
      .reduce((tot, s) => tot - s.quantidade, 0);
    if (saldoDaPeca(db, m.remessa_id, m.produto_id) + deB < m.quantidade)
      recusar(
        'TROCA_NAO_PODE_SER_CANCELADA',
        'A peça que voltou nesta troca já foi vendida de novo e a troca não pode ser cancelada. Fale com a dona.',
      );
  }
  for (const credito of db
    .prepare('SELECT id FROM creditos_cliente WHERE troca_id = ? AND deleted_at IS NULL')
    .all(t.id) as { id: string }[]) {
    const foraDaTroca = somaDe(
      db,
      `SELECT valor FROM usos_credito WHERE credito_id = ? AND deleted_at IS NULL
          AND (venda_id IS NULL OR venda_id <> ?)`,
      credito.id,
      t.venda_nova_id,
    );
    if (foraDaTroca !== 0n) recusar('CREDITO_DA_TROCA_JA_USADO');
  }

  const quando = agora.toISOString();
  const dados = JSON.stringify({ id: c.id, trocaId: t.id, motivo, canceladaEm: quando });
  // B volta à maleta e o que foi pago por ela volta (o crédito usado volta para o crédito).
  desfazerMovimentos(db, agora, c.id, saidasDeB, null);
  for (const pg of db
    .prepare(
      `SELECT id, forma, valor, credito_id FROM pagamentos_cliente
        WHERE venda_id = ? AND tipo = 'PAGAMENTO' AND deleted_at IS NULL ORDER BY pago_em, id`,
    )
    .all(t.venda_nova_id) as {
    id: string;
    forma: string;
    valor: string;
    credito_id: string | null;
  }[]) {
    const reembolso = uuidV7(agora);
    inserir(db, 'pagamentos_cliente', reembolso, c.id, {
      venda_id: t.venda_nova_id,
      tipo: 'REEMBOLSO',
      forma: pg.forma,
      valor: textoDeCentavos(-exigirCentavos(pg.valor)),
      pago_em: quando,
      credito_id: pg.credito_id,
    });
    if (pg.credito_id) estornarUsoDoCredito(db, agora, c.id, pg.id, reembolso, quando);
  }
  // A sai de novo da maleta e o reembolso em crédito da venda A é desfeito.
  desfazerMovimentos(db, agora, c.id, voltaram, t.devolucao_id);
  desfazerReembolsos(db, agora, c.id, t.devolucao_id, quando);
  enfileirar(db, { operacaoId: c.id, tipo: 'CANCELAR_TROCA', dados, agora });
  return { operacaoId: c.id };
}
