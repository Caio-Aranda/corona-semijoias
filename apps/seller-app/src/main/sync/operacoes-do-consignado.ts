import { diaEmBrasilia } from '../../shared/garantia';
import {
  aplicarPagamento,
  calcularAcerto,
  centesimosDePorcentagem,
  comissaoValida,
} from '@corona/shared/consignado';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import {
  FORMAS_DO_CONSIGNADO,
  type AcertoParaEnviar,
  type ClienteDoConsignadoParaCadastrar,
  type FormaDoConsignado,
  type PagamentoDoConsignadoParaEnviar,
  type ReposicaoParaEnviar,
  type TrocaNaNotaParaEnviar,
} from '../../shared/operacoes-do-consignado';
import { type BancoAberto } from '../banco/banco-local';
import type { CodigoDoConsignado } from './mensagens-do-consignado';
import {
  acertoAtivoDaNota,
  acharNota,
  dataDeCobranca,
  debitosDoCliente,
  diaMaisDias,
  linhasDaNota,
  pendenciaDoCliente,
  semanaAtual,
  SQL_ACERTO_ATIVO,
  SQL_RECIBO_ATIVO,
} from './consignado-comum';
import { ErroDaOperacao, inserir, saldoDaPeca } from './operacoes-locais';
import { enfileirar } from './outbox';
import { uuidV7 } from './uuid';

/**
 * As gravações do consignado ao cliente no computador (tarefa 9.2; ADR-0016). Cada uma confere o que a tela já não deixa e
 * o que o servidor também recusaria, grava as linhas PROVISÓRIAS (o espelho do que o servidor vai gravar) e põe na outbox o
 * JSON exato do formato 1 (formato 2 no cadastro do cliente), montado uma vez e nunca remontado. O servidor recalcula
 * tudo: o que o computador calcula aqui (`@corona/shared/consignado`, as MESMAS fórmulas) é só prévia.
 */

const recusar = (codigo: CodigoDoConsignado | 'PECA_FORA_DA_MALETA' | 'SEM_SALDO'): never => {
  throw new ErroDaOperacao(codigo);
};

const DIAS_PARA_COBRANCA = 45;
const MAXIMO_DO_MOTIVO = 400;

const dinheiro = textoDeCentavos;

/** Quanto sobra de uma peça numa maleta, descontando o que já está no rascunho. */
function disponivelNaMaleta(db: BancoAberto, remessaId: string, produtoId: string): number {
  return saldoDaPeca(db, remessaId, produtoId);
}

function precoNaMaleta(db: BancoAberto, remessaId: string, produtoId: string): bigint | null {
  const r = db
    .prepare(
      `SELECT preco_remessa FROM itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL LIMIT 1`,
    )
    .get(remessaId, produtoId) as { preco_remessa: string } | undefined;
  return r ? exigirCentavos(r.preco_remessa) : null;
}

function exigirClienteDoRepresentante(db: BancoAberto, clienteId: string): void {
  const c = db
    .prepare('SELECT id FROM clientes WHERE id = ? AND deleted_at IS NULL')
    .get(clienteId);
  if (!c) throw new ErroDaOperacao('CLIENTE_NAO_ENCONTRADO');
}

// --- semana e contador ---

/**
 * O próximo número `semana-sequência` do tipo: nota e recibo têm contador próprio na mesma semana (ADR-0016, item 3 e D2).
 * Nota: o maior entre o contador das notas e a maior nota do espelho mais um. Recibo: o maior entre o contador dos recibos
 * e o maior recibo do espelho mais um (na troca de computador o espelho vem do servidor, sem contador).
 */
export function proximaSequencia(
  db: BancoAberto,
  semanaId: string,
  tipo: 'NOTA' | 'RECIBO',
): number {
  const tabela = tipo === 'NOTA' ? 'notas_consignado' : 'recibos_consignado';
  const coluna = tipo === 'NOTA' ? 'proxima_sequencia' : 'proxima_sequencia_recibo';
  const noEspelho = db
    .prepare(`SELECT max(sequencia) AS m FROM ${tabela} WHERE semana_id = ?`)
    .get(semanaId) as { m: number | null };
  const contador = db
    .prepare(`SELECT ${coluna} AS p FROM contadores_da_semana WHERE semana_id = ?`)
    .get(semanaId) as { p: number | null } | undefined;
  const proxima = Math.max(contador?.p ?? 1, (noEspelho.m ?? 0) + 1);
  if (tipo === 'NOTA')
    db.prepare(
      `INSERT INTO contadores_da_semana (semana_id, proxima_sequencia) VALUES (?, ?)
       ON CONFLICT (semana_id) DO UPDATE SET proxima_sequencia = excluded.proxima_sequencia`,
    ).run(semanaId, proxima + 1);
  else
    db.prepare(
      `INSERT INTO contadores_da_semana (semana_id, proxima_sequencia, proxima_sequencia_recibo) VALUES (?, 1, ?)
       ON CONFLICT (semana_id) DO UPDATE SET proxima_sequencia_recibo = excluded.proxima_sequencia_recibo`,
    ).run(semanaId, proxima + 1);
  return proxima;
}

/**
 * Nova semana (`SEMANA_NOVA`). O número é o maior que o computador conhece mais um; na primeira vez a pessoa pode digitar o
 * número de onde continua (dúvida D1). Abrir a nova fecha a anterior: aqui a anterior passa a "fechada" só por não ser mais
 * a de maior número (nada se muda no lugar).
 */
export function abrirSemana(
  db: BancoAberto,
  agora: Date,
  p: { id: string; numero?: number },
): { operacaoId: string; semanaId: string; numero: number } {
  const maior = (
    db.prepare('SELECT max(numero) AS m FROM semanas_vendedor WHERE deleted_at IS NULL').get() as {
      m: number | null;
    }
  ).m;
  const numero = p.numero ?? (maior ?? 0) + 1;
  if (
    !Number.isInteger(numero) ||
    numero < 1 ||
    numero > 9_999_999 ||
    (maior !== null && numero <= maior)
  )
    recusar('NUMERO_DA_SEMANA_INVALIDO');
  const abertaEm = agora.toISOString();
  inserir(db, 'semanas_vendedor', p.id, p.id, {
    numero,
    aberta_em: abertaEm,
    situacao: 'ABERTA',
  });
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'SEMANA_NOVA',
    dados: JSON.stringify({ id: p.id, numero, abertaEm }),
    agora,
  });
  return { operacaoId: p.id, semanaId: p.id, numero };
}

// --- rascunho do Novo Pedido (só no computador) ---

export interface ItemDoRascunho {
  id: string;
  produtoId: string;
  remessaId: string;
  quantidade: number;
  precoUnitario: string;
}

export interface RascunhoDoPedido {
  id: string;
  clienteId: string | null;
  remessaId: string | null;
  dataCobranca: string;
  itens: ItemDoRascunho[];
}

/** O Novo Pedido em montagem; nasce se ainda não há (um de cada vez; sobrevive a fechar o programa). */
export function rascunhoDoPedido(db: BancoAberto, agora: Date): RascunhoDoPedido {
  let r = db.prepare('SELECT * FROM rascunhos_nota ORDER BY criado_em LIMIT 1').get() as
    | {
        id: string;
        cliente_id: string | null;
        remessa_id: string | null;
        data_cobranca: string | null;
      }
    | undefined;
  if (!r) {
    const id = uuidV7(agora);
    const dataCobranca = diaMaisDias(agora, DIAS_PARA_COBRANCA);
    db.prepare(
      `INSERT INTO rascunhos_nota (id, cliente_id, remessa_id, data_cobranca, criado_em, atualizado_em)
       VALUES (?, NULL, NULL, ?, ?, ?)`,
    ).run(id, dataCobranca, agora.toISOString(), agora.toISOString());
    r = { id, cliente_id: null, remessa_id: null, data_cobranca: dataCobranca };
  }
  const itens = db
    .prepare(
      `SELECT id, produto_id, remessa_id, quantidade, preco_unitario FROM itens_rascunho_nota
        WHERE rascunho_id = ? ORDER BY ordem`,
    )
    .all(r.id) as {
    id: string;
    produto_id: string;
    remessa_id: string;
    quantidade: number;
    preco_unitario: string;
  }[];
  return {
    id: r.id,
    clienteId: r.cliente_id,
    remessaId: r.remessa_id,
    dataCobranca: r.data_cobranca ?? diaMaisDias(agora, DIAS_PARA_COBRANCA),
    itens: itens.map((i) => ({
      id: i.id,
      produtoId: i.produto_id,
      remessaId: i.remessa_id,
      quantidade: i.quantidade,
      precoUnitario: i.preco_unitario,
    })),
  };
}

const DIA = /^\d{4}-\d{2}-\d{2}$/;

export function dataValida(texto: string): boolean {
  if (!DIA.test(texto)) return false;
  const d = new Date(`${texto}T12:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === texto;
}

export function definirRascunho(
  db: BancoAberto,
  agora: Date,
  p: { clienteId?: string | null; remessaId?: string | null; dataCobranca?: string },
): RascunhoDoPedido {
  const r = rascunhoDoPedido(db, agora);
  if (p.clienteId !== undefined && p.clienteId !== null)
    exigirClienteDoRepresentante(db, p.clienteId);
  if (p.dataCobranca !== undefined && !dataValida(p.dataCobranca))
    recusar('DATA_DE_COBRANCA_INVALIDA');
  db.prepare(
    `UPDATE rascunhos_nota SET cliente_id = ?, remessa_id = ?, data_cobranca = ?, atualizado_em = ? WHERE id = ?`,
  ).run(
    p.clienteId === undefined ? r.clienteId : p.clienteId,
    p.remessaId === undefined ? r.remessaId : p.remessaId,
    p.dataCobranca ?? r.dataCobranca,
    agora.toISOString(),
    r.id,
  );
  return rascunhoDoPedido(db, agora);
}

export type ResultadoDoBipe =
  | { resultado: 'ADICIONADA'; produtoId: string; rascunho: RascunhoDoPedido }
  | { resultado: 'SEM_CODIGO_NA_TELA' }
  | { resultado: 'FORA_DA_MALETA' }
  | { resultado: 'EM_OUTRA_MALETA'; remessaId: string; produtoId: string }
  | { resultado: 'SEM_SALDO'; disponivel: number; produtoId: string };

/** O produto de um código lido (qualquer código da peça, sem diferenciar maiúsculas nem espaços nas pontas). */
export function produtosDoCodigo(db: BancoAberto, codigo: string): string[] {
  const lido = codigo.trim().toLowerCase();
  if (lido === '') return [];
  return (
    db
      .prepare(
        `SELECT DISTINCT produto_id FROM produto_codigos
          WHERE lower(trim(codigo)) = ? AND deleted_at IS NULL AND ativo IS NOT 0`,
      )
      .all(lido) as { produto_id: string }[]
  ).map((x) => x.produto_id);
}

/** As maletas com peças do representante (enviadas e conferidas), para escolher e para dizer "está na maleta X". */
export function maletasEmUso(db: BancoAberto): string[] {
  return (
    db
      .prepare(
        `SELECT id FROM remessas WHERE deleted_at IS NULL AND status IN ('ENVIADA', 'CONFERIDA') ORDER BY enviada_em, id`,
      )
      .all() as { id: string }[]
  ).map((r) => r.id);
}

export function noRascunho(r: RascunhoDoPedido, remessaId: string, produtoId: string): number {
  return r.itens
    .filter((i) => i.remessaId === remessaId && i.produtoId === produtoId)
    .reduce((s, i) => s + i.quantidade, 0);
}

/**
 * Bipar uma peça no Novo Pedido: procura o código NA MALETA ESCOLHIDA; linha nova ou +1 na mesma linha (mesmo código e
 * mesma maleta). Nada é adicionado quando o código não está na maleta (diz em qual maleta está, se estiver em outra) ou
 * quando passa do disponível (§23.6).
 */
export function biparNoRascunho(db: BancoAberto, agora: Date, codigo: string): ResultadoDoBipe {
  const r = rascunhoDoPedido(db, agora);
  if (!r.remessaId) return recusar('PEDIDO_SEM_MALETA');
  const produtos = produtosDoCodigo(db, codigo);
  if (produtos.length === 0) return { resultado: 'FORA_DA_MALETA' };
  const naMaleta = produtos.find((p) => precoNaMaleta(db, r.remessaId as string, p) !== null);
  if (!naMaleta) {
    for (const outra of maletasEmUso(db)) {
      if (outra === r.remessaId) continue;
      const p = produtos.find((x) => precoNaMaleta(db, outra, x) !== null);
      if (p) return { resultado: 'EM_OUTRA_MALETA', remessaId: outra, produtoId: p };
    }
    return { resultado: 'FORA_DA_MALETA' };
  }
  const disponivel = disponivelNaMaleta(db, r.remessaId, naMaleta);
  if (noRascunho(r, r.remessaId, naMaleta) + 1 > disponivel)
    return { resultado: 'SEM_SALDO', disponivel, produtoId: naMaleta };
  const existente = r.itens.find((i) => i.remessaId === r.remessaId && i.produtoId === naMaleta);
  if (existente) {
    db.prepare('UPDATE itens_rascunho_nota SET quantidade = quantidade + 1 WHERE id = ?').run(
      existente.id,
    );
  } else {
    const ordem = (
      db
        .prepare(
          'SELECT coalesce(max(ordem), 0) + 1 AS o FROM itens_rascunho_nota WHERE rascunho_id = ?',
        )
        .get(r.id) as { o: number }
    ).o;
    const preco = precoNaMaleta(db, r.remessaId, naMaleta) ?? 0n;
    db.prepare(
      `INSERT INTO itens_rascunho_nota (id, rascunho_id, produto_id, remessa_id, quantidade, preco_unitario, ordem)
       VALUES (?, ?, ?, ?, 1, ?, ?)`,
    ).run(uuidV7(agora), r.id, naMaleta, r.remessaId, dinheiro(preco), ordem);
  }
  db.prepare('UPDATE rascunhos_nota SET atualizado_em = ? WHERE id = ?').run(
    agora.toISOString(),
    r.id,
  );
  return { resultado: 'ADICIONADA', produtoId: naMaleta, rascunho: rascunhoDoPedido(db, agora) };
}

/** Diminuir a quantidade de uma linha, ou removê-la (quantidade 0). Aumentar passa pelo bipe, que confere o saldo. */
export function ajustarItemDoRascunho(
  db: BancoAberto,
  agora: Date,
  itemId: string,
  quantidade: number,
): RascunhoDoPedido {
  const r = rascunhoDoPedido(db, agora);
  const item = r.itens.find((i) => i.id === itemId);
  if (!item) return recusar('ITEM_DO_PEDIDO_NAO_ENCONTRADO');
  if (!Number.isInteger(quantidade) || quantidade < 0)
    return recusar('ITEM_DO_PEDIDO_NAO_ENCONTRADO');
  if (quantidade === 0) db.prepare('DELETE FROM itens_rascunho_nota WHERE id = ?').run(itemId);
  else if (quantidade <= item.quantidade)
    db.prepare('UPDATE itens_rascunho_nota SET quantidade = ? WHERE id = ?').run(
      quantidade,
      itemId,
    );
  return rascunhoDoPedido(db, agora);
}

export function descartarRascunho(db: BancoAberto): void {
  db.prepare('DELETE FROM itens_rascunho_nota').run();
  db.prepare('DELETE FROM rascunhos_nota').run();
}

// --- nota (Finalizar), Reposição, Troca e Remarcar ---

interface LinhaParaGravar {
  produtoId: string;
  remessaId: string;
  quantidade: number;
  preco: bigint;
}

function gravarItensDaSaida(
  db: BancoAberto,
  agora: Date,
  operacaoId: string,
  notaId: string,
  origem: 'SAIDA' | 'REPOSICAO',
  reposicaoId: string | null,
  linhas: readonly LinhaParaGravar[],
): {
  id: string;
  produtoId: string;
  remessaId: string;
  quantidade: number;
  precoUnitario: string;
  movimentoId: string;
}[] {
  return linhas.map((l) => {
    const id = uuidV7(agora);
    const movimentoId = uuidV7(agora);
    inserir(db, 'itens_nota_consignado', id, operacaoId, {
      nota_id: notaId,
      produto_id: l.produtoId,
      remessa_id: l.remessaId,
      origem,
      quantidade: l.quantidade,
      preco_unitario: dinheiro(l.preco),
      reposicao_id: reposicaoId,
      troca_id: null,
    });
    inserir(db, 'movimentos_remessa', movimentoId, operacaoId, {
      remessa_id: l.remessaId,
      produto_id: l.produtoId,
      tipo: 'CONSIGNADO_SAIDA',
      quantidade: -l.quantidade,
      nota_id: notaId,
    });
    return {
      id,
      produtoId: l.produtoId,
      remessaId: l.remessaId,
      quantidade: l.quantidade,
      precoUnitario: dinheiro(l.preco),
      movimentoId,
    };
  });
}

/** Confere cada linha: a peça está na maleta e a soma por linha não passa do disponível. Devolve as linhas já com o preço. */
function conferirLinhasDaSaida(
  db: BancoAberto,
  itens: readonly { produtoId: string; remessaId: string; quantidade: number }[],
): LinhaParaGravar[] {
  if (itens.length === 0) recusar('PEDIDO_SEM_PECAS');
  const porLinha = new Map<string, LinhaParaGravar>();
  for (const i of itens) {
    if (!Number.isInteger(i.quantidade) || i.quantidade < 1) recusar('PEDIDO_SEM_PECAS');
    const preco = precoNaMaleta(db, i.remessaId, i.produtoId);
    if (preco === null) recusar('PECA_FORA_DA_MALETA');
    const chave = `${i.produtoId}|${i.remessaId}`;
    const atual = porLinha.get(chave);
    if (atual) atual.quantidade += i.quantidade;
    else porLinha.set(chave, { ...i, preco: preco as bigint });
  }
  const linhas = [...porLinha.values()];
  for (const l of linhas)
    if (l.quantidade > disponivelNaMaleta(db, l.remessaId, l.produtoId)) recusar('SEM_SALDO');
  return linhas;
}

function totalDx(linhas: readonly LinhaParaGravar[]): bigint {
  return linhas.reduce((s, l) => s + l.preco * BigInt(l.quantidade), 0n);
}

function gravarNovaNota(
  db: BancoAberto,
  agora: Date,
  p: {
    id: string;
    clienteId: string;
    dataCobranca: string;
    notaOrigemId: string | null;
    linhas: readonly LinhaParaGravar[];
  },
): { notaId: string; numero: { semana: number; sequencia: number } } {
  const semana = semanaAtual(db);
  if (!semana) return recusar('SEM_SEMANA_ABERTA');
  const sequencia = proximaSequencia(db, semana.id, 'NOTA');
  const saidaEm = agora.toISOString();
  inserir(db, 'notas_consignado', p.id, p.id, {
    semana_id: semana.id,
    sequencia,
    cliente_id: p.clienteId,
    saida_em: saidaEm,
    data_cobranca: p.dataCobranca,
    nota_origem_id: p.notaOrigemId,
    situacao: 'EM_ABERTO',
  });
  const itens = gravarItensDaSaida(db, agora, p.id, p.id, 'SAIDA', null, p.linhas);
  const dados = JSON.stringify({
    id: p.id,
    semanaId: semana.id,
    sequencia,
    clienteId: p.clienteId,
    notaOrigemId: p.notaOrigemId,
    dataCobranca: p.dataCobranca,
    saidaEm,
    itens,
    totalDxVisto: dinheiro(totalDx(p.linhas)),
  });
  enfileirar(db, { operacaoId: p.id, tipo: 'NOTA_CONSIGNADO', dados, agora });
  return { notaId: p.id, numero: { semana: semana.numero, sequencia } };
}

/** Finalizar o Novo Pedido: a nota nasce completa (imutável) e as peças passam a "com o cliente". */
export function finalizarPedido(
  db: BancoAberto,
  agora: Date,
  p: { id: string },
): { operacaoId: string; notaId: string; numeroDaNota: string } {
  const r = rascunhoDoPedido(db, agora);
  if (!r.clienteId) return recusar('PEDIDO_SEM_CLIENTE');
  if (r.itens.length === 0) return recusar('PEDIDO_SEM_PECAS');
  if (!dataValida(r.dataCobranca)) return recusar('DATA_DE_COBRANCA_INVALIDA');
  exigirClienteDoRepresentante(db, r.clienteId);
  const linhas = conferirLinhasDaSaida(db, r.itens);
  const { numero } = gravarNovaNota(db, agora, {
    id: p.id,
    clienteId: r.clienteId,
    dataCobranca: r.dataCobranca,
    notaOrigemId: null,
    linhas,
  });
  descartarRascunho(db);
  return {
    operacaoId: p.id,
    notaId: p.id,
    numeroDaNota: `${String(numero.semana).padStart(7, '0')}-${String(numero.sequencia).padStart(2, '0')}`,
  };
}

/**
 * Reposição (§23.4): nota da semana atual recebe as peças (`REPOSICAO_NOTA`, a nota de saída sai de novo); nota de semana
 * anterior gera uma nota NOVA na semana atual só com as peças novas e a mesma Dt. Cobrança (`NOTA_CONSIGNADO`).
 */
export function registrarReposicao(
  db: BancoAberto,
  agora: Date,
  p: ReposicaoParaEnviar,
): { operacaoId: string; notaId: string; novaNota: boolean } {
  const nota = acharNota(db, p.notaId);
  if (!nota) return recusar('NOTA_NAO_ENCONTRADA');
  if (acertoAtivoDaNota(db, nota.id)) return recusar('NOTA_JA_ACERTADA');
  const semana = semanaAtual(db);
  if (!semana) return recusar('SEM_SEMANA_ABERTA');
  const linhas = conferirLinhasDaSaida(db, p.itens);
  if (nota.semana_id !== semana.id) {
    gravarNovaNota(db, agora, {
      id: p.id,
      clienteId: nota.cliente_id,
      dataCobranca: dataDeCobranca(db, nota) ?? diaMaisDias(agora, DIAS_PARA_COBRANCA),
      notaOrigemId: nota.id,
      linhas,
    });
    return { operacaoId: p.id, notaId: p.id, novaNota: true };
  }
  const feitaEm = agora.toISOString();
  inserir(db, 'reposicoes_nota', p.id, p.id, { nota_id: nota.id, feita_em: feitaEm });
  const itens = gravarItensDaSaida(db, agora, p.id, nota.id, 'REPOSICAO', p.id, linhas);
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'REPOSICAO_NOTA',
    dados: JSON.stringify({ id: p.id, notaId: nota.id, feitaEm, itens }),
    agora,
  });
  return { operacaoId: p.id, notaId: nota.id, novaNota: false };
}

/** Troca sem acerto: a peça trocada volta à maleta de onde saiu e a nova entra na nota (uma operação só). */
export function registrarTrocaNaNota(
  db: BancoAberto,
  agora: Date,
  p: TrocaNaNotaParaEnviar,
): { operacaoId: string } {
  const nota = acharNota(db, p.notaId);
  if (!nota) return recusar('NOTA_NAO_ENCONTRADA');
  if (acertoAtivoDaNota(db, nota.id)) return recusar('NOTA_JA_ACERTADA');
  const linha = linhasDaNota(db, nota.id).find(
    (l) => l.produtoId === p.volta.produtoId && l.remessaId === p.volta.remessaId,
  );
  if (!linha) return recusar('PECA_NAO_ESTA_NA_NOTA');
  const precoEntra = precoNaMaleta(db, p.entra.remessaId, p.entra.produtoId);
  if (precoEntra === null) return recusar('PECA_FORA_DA_MALETA');
  if (disponivelNaMaleta(db, p.entra.remessaId, p.entra.produtoId) < 1) return recusar('SEM_SALDO');
  const trocadaEm = agora.toISOString();
  inserir(db, 'trocas_nota', p.id, p.id, { nota_id: nota.id, trocada_em: trocadaEm });
  const voltaItem = uuidV7(agora);
  const voltaMov = uuidV7(agora);
  const entraItem = uuidV7(agora);
  const entraMov = uuidV7(agora);
  inserir(db, 'itens_nota_consignado', voltaItem, p.id, {
    nota_id: nota.id,
    produto_id: p.volta.produtoId,
    remessa_id: p.volta.remessaId,
    origem: 'TROCA_VOLTA',
    quantidade: -1,
    preco_unitario: dinheiro(linha.preco),
    reposicao_id: null,
    troca_id: p.id,
  });
  inserir(db, 'movimentos_remessa', voltaMov, p.id, {
    remessa_id: p.volta.remessaId,
    produto_id: p.volta.produtoId,
    tipo: 'CONSIGNADO_RETORNO',
    quantidade: 1,
    nota_id: nota.id,
  });
  inserir(db, 'itens_nota_consignado', entraItem, p.id, {
    nota_id: nota.id,
    produto_id: p.entra.produtoId,
    remessa_id: p.entra.remessaId,
    origem: 'TROCA_ENTRADA',
    quantidade: 1,
    preco_unitario: dinheiro(precoEntra),
    reposicao_id: null,
    troca_id: p.id,
  });
  inserir(db, 'movimentos_remessa', entraMov, p.id, {
    remessa_id: p.entra.remessaId,
    produto_id: p.entra.produtoId,
    tipo: 'CONSIGNADO_SAIDA',
    quantidade: -1,
    nota_id: nota.id,
  });
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'TROCA_NA_NOTA',
    dados: JSON.stringify({
      id: p.id,
      notaId: nota.id,
      trocadaEm,
      volta: {
        itemId: voltaItem,
        produtoId: p.volta.produtoId,
        remessaId: p.volta.remessaId,
        movimentoId: voltaMov,
      },
      entra: {
        itemId: entraItem,
        produtoId: p.entra.produtoId,
        remessaId: p.entra.remessaId,
        precoUnitario: dinheiro(precoEntra),
        movimentoId: entraMov,
      },
    }),
    agora,
  });
  return { operacaoId: p.id };
}

/** Cliente não veio: muda a Dt. Cobrança e a nota passa a Remarcado (só nota sem acerto valendo). */
export function remarcarNota(
  db: BancoAberto,
  agora: Date,
  p: { id: string; notaId: string; dataCobranca: string },
): { operacaoId: string } {
  const nota = acharNota(db, p.notaId);
  if (!nota) return recusar('NOTA_NAO_ENCONTRADA');
  if (acertoAtivoDaNota(db, nota.id)) return recusar('NOTA_JA_ACERTADA');
  if (!dataValida(p.dataCobranca)) return recusar('DATA_DE_COBRANCA_INVALIDA');
  const anterior = dataDeCobranca(db, nota) ?? p.dataCobranca;
  const remarcadaEm = agora.toISOString();
  inserir(db, 'remarcacoes_nota', p.id, p.id, {
    nota_id: nota.id,
    data_cobranca_anterior: anterior,
    data_cobranca_nova: p.dataCobranca,
    remarcada_em: remarcadaEm,
  });
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'REMARCAR_NOTA',
    dados: JSON.stringify({ id: p.id, notaId: nota.id, dataCobranca: p.dataCobranca, remarcadaEm }),
    agora,
  });
  return { operacaoId: p.id };
}

// --- acerto e pagamento ---

const CASAS = /^\d{1,12}(\.\d{1,2})?$/;

/** As formas pagas, conferidas: só as quatro da §23.8, cada uma maior que zero e com no máximo duas casas. */
export function conferirFormas(
  formas: readonly { forma: string; valor: string }[],
): { forma: FormaDoConsignado; valor: bigint }[] {
  const lista = formas.map((f) => {
    if (
      !(FORMAS_DO_CONSIGNADO as readonly string[]).includes(f.forma) ||
      !CASAS.test(f.valor.trim())
    )
      return recusar('PAGAMENTO_INVALIDO');
    const valor = exigirCentavos(f.valor);
    if (valor <= 0n) return recusar('PAGAMENTO_INVALIDO');
    return { forma: f.forma as FormaDoConsignado, valor };
  });
  const vistas = new Set<string>();
  for (const f of lista) {
    if (vistas.has(f.forma)) return recusar('PAGAMENTO_INVALIDO');
    vistas.add(f.forma);
  }
  return lista;
}

const somaDas = (formas: readonly { valor: bigint }[]): bigint =>
  formas.reduce((s, f) => s + f.valor, 0n);

/** A comissão ("30", "7.5") no formato do servidor, com duas casas: "30.00". */
export function comissaoComDuasCasas(texto: string): string {
  const c = centesimosDePorcentagem(texto);
  if (c === null || !comissaoValida(texto)) return recusar('COMISSAO_FORA_DO_LIMITE');
  return textoDeCentavos(c);
}

export interface PreviaDoAcerto {
  linhas: {
    produtoId: string;
    remessaId: string;
    dx: number;
    dv: number;
    vd: number;
    precoUnitario: string;
  }[];
  calculo: ReturnType<typeof calcularAcerto>;
  comissaoPercentual: string;
  debitos: { acertoId: string; numeroDaNota: string; acertadoEm: string; saldo: string }[];
}

/** A conta do acerto antes de salvar (só leitura): as mesmas fórmulas do servidor. */
export function previaDoAcerto(
  db: BancoAberto,
  p: Pick<AcertoParaEnviar, 'notaId' | 'devolvidos' | 'comissaoPercentual' | 'formas' | 'data'>,
): PreviaDoAcerto {
  const nota = acharNota(db, p.notaId);
  if (!nota) return recusar('NOTA_NAO_ENCONTRADA');
  if (acertoAtivoDaNota(db, nota.id)) return recusar('NOTA_JA_ACERTADA');
  const comissao = comissaoComDuasCasas(p.comissaoPercentual);
  const linhas = linhasDaNota(db, nota.id);
  const devolvidoPorLinha = new Map<string, number>();
  for (const d of p.devolvidos) {
    const chave = `${d.produtoId}|${d.remessaId}`;
    if (!linhas.some((l) => `${l.produtoId}|${l.remessaId}` === chave))
      return recusar('DEVOLUCAO_MAIOR_QUE_O_LEVADO');
    if (!Number.isInteger(d.quantidade) || d.quantidade < 0)
      return recusar('DEVOLUCAO_MAIOR_QUE_O_LEVADO');
    devolvidoPorLinha.set(chave, (devolvidoPorLinha.get(chave) ?? 0) + d.quantidade);
  }
  const partes = linhas.map((l) => {
    const dv = devolvidoPorLinha.get(`${l.produtoId}|${l.remessaId}`) ?? 0;
    if (dv > l.dx) return recusar('DEVOLUCAO_MAIOR_QUE_O_LEVADO');
    return { l, dv };
  });
  const debitos = debitosDoCliente(db, nota.cliente_id).filter((d) => d.saldo > 0n);
  const pendencias = debitos.reduce((s, d) => s + d.saldo, 0n);
  const pago = somaDas(conferirFormas(p.formas));
  const calculo = calcularAcerto(
    partes.map(({ l, dv }) => ({ dx: l.dx, dv, precoUnitario: dinheiro(l.preco) })),
    comissao,
    dinheiro(pendencias),
    dinheiro(pago),
  );
  return {
    linhas: partes.map(({ l, dv }) => ({
      produtoId: l.produtoId,
      remessaId: l.remessaId,
      dx: l.dx,
      dv,
      vd: l.dx - dv,
      precoUnitario: dinheiro(l.preco),
    })),
    calculo,
    comissaoPercentual: comissao,
    debitos: debitos.map((d) => ({
      acertoId: d.acertoId,
      numeroDaNota: d.numeroDaNota,
      acertadoEm: d.acertadoEm,
      saldo: dinheiro(d.saldo),
    })),
  };
}

/** Grava o recibo (e as formas) como o servidor gravaria: provisório, com o número `semana-sequência` da semana de agora. */
function gravarRecibo(
  db: BancoAberto,
  agora: Date,
  operacaoId: string,
  r: {
    id: string;
    clienteId: string;
    semanaId: string;
    sequencia: number;
    acertoId: string | null;
    formas: readonly { forma: FormaDoConsignado; valor: bigint }[];
    /** Quando o pagamento vale (a data escolhida na tela); sem isso, agora. */
    pagoEm?: string;
  },
): void {
  const pagoEm = r.pagoEm ?? agora.toISOString();
  inserir(db, 'recibos_consignado', r.id, operacaoId, {
    cliente_id: r.clienteId,
    semana_id: r.semanaId,
    sequencia: r.sequencia,
    acerto_id: r.acertoId,
    pago_em: pagoEm,
    total: dinheiro(somaDas(r.formas)),
  });
  for (const f of r.formas)
    inserir(db, 'formas_recibo_consignado', uuidV7(agora), operacaoId, {
      recibo_id: r.id,
      forma: f.forma,
      valor: dinheiro(f.valor),
    });
}

/** Salvar o acerto: devolvidas voltam à maleta de onde saíram, o resto é vendido, o pago quita do mais antigo ao mais novo. */
export function registrarAcerto(
  db: BancoAberto,
  agora: Date,
  p: AcertoParaEnviar,
): { operacaoId: string; reciboId: string | null; situacao: string } {
  const previa = previaDoAcerto(db, p);
  const nota = acharNota(db, p.notaId) as NonNullable<ReturnType<typeof acharNota>>;
  const semana = semanaAtual(db);
  if (!semana) return recusar('SEM_SEMANA_ABERTA');
  const formas = conferirFormas(p.formas);
  const pago = somaDas(formas);
  const totalAReceber = exigirCentavos(previa.calculo.totalAReceber);
  if (pago > totalAReceber) return recusar('PAGO_ACIMA_DO_DEVIDO');

  // A Data do acerto (§23.8): hoje ou um dia que já passou, nunca antes do dia da saída da nota; o momento do lançamento
  // (`registrado_em`) é a chave da ordem dos débitos.
  const acertadoEm = momentoDoLancamento(agora, p.data, 'DATA_DO_ACERTO_INVALIDA');
  if (p.data !== undefined && nota.saida_em && p.data < diaEmBrasilia(nota.saida_em))
    return recusar('DATA_DO_ACERTO_ANTES_DA_SAIDA');
  const registradoEm = agora.toISOString();
  const bruto = exigirCentavos(previa.calculo.bruto);
  const c = previa.calculo;
  inserir(db, 'acertos_nota', p.id, p.id, {
    nota_id: nota.id,
    semana_id: semana.id,
    acertado_em: acertadoEm,
    registrado_em: registradoEm,
    comissao_percentual: previa.comissaoPercentual,
    itens_dx: c.itensDx,
    itens_dv: c.itensDv,
    itens_vd: c.itensVd,
    valor_dx: c.valorDx,
    valor_dv: c.valorDv,
    valor_vd: c.valorVd,
    comissao: c.comissao,
    bruto: c.bruto,
    pendencias_anteriores: c.pendenciasAnteriores,
    total_a_receber: c.totalAReceber,
    total_pago: c.totalPago,
    ficou_devendo: c.ficouDevendo,
    recibo_id: pago > 0n ? p.reciboId : null,
  });
  const devolvidos: {
    itemAcertoId: string;
    produtoId: string;
    remessaId: string;
    quantidade: number;
    movimentoId: string;
  }[] = [];
  for (const l of previa.linhas) {
    const itemAcertoId = uuidV7(agora);
    inserir(db, 'itens_acerto_nota', itemAcertoId, p.id, {
      acerto_id: p.id,
      produto_id: l.produtoId,
      remessa_id: l.remessaId,
      qtd_dx: l.dx,
      qtd_dv: l.dv,
      preco_unitario: l.precoUnitario,
    });
    if (l.dv > 0) {
      const movimentoId = uuidV7(agora);
      inserir(db, 'movimentos_remessa', movimentoId, p.id, {
        remessa_id: l.remessaId,
        produto_id: l.produtoId,
        tipo: 'CONSIGNADO_RETORNO',
        quantidade: l.dv,
        nota_id: nota.id,
        acerto_nota_id: p.id,
      });
      devolvidos.push({
        itemAcertoId,
        produtoId: l.produtoId,
        remessaId: l.remessaId,
        quantidade: l.dv,
        movimentoId,
      });
    }
  }
  let recibo:
    { id: string; sequencia: number; formas: { forma: string; valor: string }[] } | undefined;
  if (pago > 0n) {
    const sequencia = proximaSequencia(db, semana.id, 'RECIBO');
    gravarRecibo(db, agora, p.id, {
      id: p.reciboId,
      clienteId: nota.cliente_id,
      semanaId: semana.id,
      sequencia,
      acertoId: p.id,
      formas,
      pagoEm: acertadoEm,
    });
    recibo = {
      id: p.reciboId,
      sequencia,
      formas: formas.map((f) => ({ forma: f.forma, valor: dinheiro(f.valor) })),
    };
    // Do mais antigo para o mais novo: as pendências anteriores primeiro, depois o bruto desta nota (§23.8).
    const debitos = debitosDoCliente(db, nota.cliente_id)
      .filter((d) => d.saldo > 0n)
      .map((d) => ({ id: d.acertoId, saldo: dinheiro(d.saldo) }));
    const { aplicacoes } = aplicarPagamento(
      [...debitos, { id: p.id, saldo: dinheiro(bruto) }],
      dinheiro(pago),
    );
    for (const a of aplicacoes)
      inserir(db, 'aplicacoes_recibo', uuidV7(agora), p.id, {
        recibo_id: p.reciboId,
        acerto_id: a.debitoId,
        valor: a.valor,
      });
  }
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'ACERTO_NOTA',
    dados: JSON.stringify({
      id: p.id,
      notaId: nota.id,
      semanaId: semana.id,
      acertadoEm: registradoEm,
      comissaoPercentual: previa.comissaoPercentual,
      devolvidos,
      ...(recibo ? { recibo } : {}),
      // Hoje é o mesmo que sem data: a operação sai como as de antes (mesmo resumo no servidor).
      ...(p.data && p.data !== diaEmBrasilia(agora) ? { dataDoAcerto: p.data } : {}),
      visto: {
        valorVd: c.valorVd,
        comissao: c.comissao,
        bruto: c.bruto,
        pendenciasAnteriores: c.pendenciasAnteriores,
        totalAReceber: c.totalAReceber,
        totalPago: c.totalPago,
        ficouDevendo: c.ficouDevendo,
      },
    }),
    agora,
  });
  // A situação da nota é a do débito dela: quitado → Fechado; faltou → Pendente (§23.4).
  const meu = debitosDoCliente(db, nota.cliente_id).find((d) => d.acertoId === p.id);
  const situacao = meu && meu.saldo > 0n ? 'PENDENTE' : 'FECHADO';
  return { operacaoId: p.id, reciboId: recibo ? p.reciboId : null, situacao };
}

/**
 * O instante que vale para a data escolhida (pagamento ou acerto): sem data, agora; a data de hoje, agora; um dia que já passou,
 * meio-dia desse dia em Brasília (a mesma regra do servidor, que confere de novo). Depois de hoje é recusado.
 */
function momentoDoLancamento(
  agora: Date,
  data: string | undefined,
  codigoDeRecusa: 'DATA_DO_PAGAMENTO_INVALIDA' | 'DATA_DO_ACERTO_INVALIDA',
): string {
  if (data === undefined) return agora.toISOString();
  if (!dataValida(data)) return recusar(codigoDeRecusa);
  const hoje = diaEmBrasilia(agora);
  if (data > hoje) return recusar(codigoDeRecusa);
  if (data === hoje) return agora.toISOString();
  return new Date(`${data}T12:00:00-03:00`).toISOString();
}

/** Lançar pagamento no cadastro do cliente: abate a pendência mais antiga primeiro; não passa do total devido. */
export function registrarPagamento(
  db: BancoAberto,
  agora: Date,
  p: PagamentoDoConsignadoParaEnviar,
): { operacaoId: string; sequencia: number } {
  exigirClienteDoRepresentante(db, p.clienteId);
  const semana = semanaAtual(db);
  if (!semana) return recusar('SEM_SEMANA_ABERTA');
  const formas = conferirFormas(p.formas);
  if (formas.length === 0) return recusar('PAGAMENTO_INVALIDO');
  const pago = somaDas(formas);
  const debitos = debitosDoCliente(db, p.clienteId).filter((d) => d.saldo > 0n);
  const pendencia = debitos.reduce((s, d) => s + d.saldo, 0n);
  if (pendencia === 0n) return recusar('CLIENTE_SEM_PENDENCIA');
  if (pago > pendencia) return recusar('PAGO_ACIMA_DO_DEVIDO');
  const sequencia = proximaSequencia(db, semana.id, 'RECIBO');
  const pagoEm = momentoDoLancamento(agora, p.data, 'DATA_DO_PAGAMENTO_INVALIDA');
  gravarRecibo(db, agora, p.id, {
    id: p.id,
    clienteId: p.clienteId,
    semanaId: semana.id,
    sequencia,
    acertoId: null,
    formas,
    pagoEm,
  });
  const { aplicacoes } = aplicarPagamento(
    debitos.map((d) => ({ id: d.acertoId, saldo: dinheiro(d.saldo) })),
    dinheiro(pago),
  );
  for (const a of aplicacoes)
    inserir(db, 'aplicacoes_recibo', uuidV7(agora), p.id, {
      recibo_id: p.id,
      acerto_id: a.debitoId,
      valor: a.valor,
    });
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'PAGAMENTO_CONSIGNADO',
    dados: JSON.stringify({
      id: p.id,
      clienteId: p.clienteId,
      semanaId: semana.id,
      sequencia,
      pagoEm: agora.toISOString(),
      formas: formas.map((f) => ({ forma: f.forma, valor: dinheiro(f.valor) })),
      pendenciaVista: dinheiro(pendencia),
      ...(p.data ? { dataDoPagamento: p.data } : {}),
    }),
    agora,
  });
  return { operacaoId: p.id, sequencia };
}

function motivoLimpo(motivo: string): string {
  const m = motivo.trim();
  if (m === '') return recusar('MOTIVO_OBRIGATORIO');
  return m.slice(0, MAXIMO_DO_MOTIVO);
}

/** Estorna as aplicações que ainda valem de um recibo, ou do débito de um acerto (aplicação negativa que anula cada uma). */
function estornarAplicacoes(
  db: BancoAberto,
  agora: Date,
  operacaoId: string,
  filtro: { coluna: 'recibo_id' | 'acerto_id'; id: string },
): void {
  const linhas = db
    .prepare(
      `SELECT id, recibo_id, acerto_id, valor FROM aplicacoes_recibo
        WHERE ${filtro.coluna} = ? AND deleted_at IS NULL AND estorna_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM aplicacoes_recibo e WHERE e.estorna_id = aplicacoes_recibo.id AND e.deleted_at IS NULL)`,
    )
    .all(filtro.id) as { id: string; recibo_id: string; acerto_id: string; valor: string }[];
  for (const l of linhas)
    inserir(db, 'aplicacoes_recibo', uuidV7(agora), operacaoId, {
      recibo_id: l.recibo_id,
      acerto_id: l.acerto_id,
      valor: dinheiro(-exigirCentavos(l.valor)),
      estorna_id: l.id,
    });
}

/** Estornar o acerto: desfaz os retornos de maleta, o recibo e as aplicações dele; a nota volta a Em aberto ou Remarcado. */
export function estornarAcerto(
  db: BancoAberto,
  agora: Date,
  p: { id: string; acertoId: string; motivo: string },
): { operacaoId: string } {
  const motivo = motivoLimpo(p.motivo);
  const acerto = db
    .prepare('SELECT id, nota_id, recibo_id FROM acertos_nota WHERE id = ? AND deleted_at IS NULL')
    .get(p.acertoId) as { id: string; nota_id: string; recibo_id: string | null } | undefined;
  if (!acerto) return recusar('ACERTO_NAO_ENCONTRADO_NO_COMPUTADOR');
  const ativo = db
    .prepare(`SELECT 1 FROM acertos_nota a WHERE a.id = ? AND ${SQL_ACERTO_ATIVO('a')}`)
    .get(p.acertoId);
  if (!ativo) return recusar('ACERTO_JA_ESTORNADO');
  const retornos = db
    .prepare(
      `SELECT id, remessa_id, produto_id, quantidade FROM movimentos_remessa
        WHERE acerto_nota_id = ? AND estorna_id IS NULL AND deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM movimentos_remessa e WHERE e.estorna_id = movimentos_remessa.id AND e.deleted_at IS NULL)`,
    )
    .all(p.acertoId) as {
    id: string;
    remessa_id: string;
    produto_id: string;
    quantidade: number;
  }[];
  for (const m of retornos)
    inserir(db, 'movimentos_remessa', uuidV7(agora), p.id, {
      remessa_id: m.remessa_id,
      produto_id: m.produto_id,
      tipo: 'CONSIGNADO_RETORNO',
      quantidade: -m.quantidade,
      nota_id: acerto.nota_id,
      acerto_nota_id: p.acertoId,
      estorna_id: m.id,
    });
  if (acerto.recibo_id)
    estornarAplicacoes(db, agora, p.id, { coluna: 'recibo_id', id: acerto.recibo_id });
  // As aplicações de OUTROS recibos neste débito também voltam (o valor fica "a aplicar", que o servidor reaproveita).
  estornarAplicacoes(db, agora, p.id, { coluna: 'acerto_id', id: p.acertoId });
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'ESTORNO_ACERTO_NOTA',
    dados: JSON.stringify({
      id: p.id,
      acertoId: p.acertoId,
      motivo,
      estornadoEm: agora.toISOString(),
    }),
    agora,
  });
  return { operacaoId: p.id };
}

/** Estornar um pagamento lançado (não o recibo de um acerto): a pendência reabre e as notas tocadas voltam a Pendente. */
export function estornarRecibo(
  db: BancoAberto,
  agora: Date,
  p: { id: string; reciboId: string; motivo: string },
): { operacaoId: string } {
  const motivo = motivoLimpo(p.motivo);
  const recibo = db
    .prepare('SELECT id, acerto_id FROM recibos_consignado WHERE id = ? AND deleted_at IS NULL')
    .get(p.reciboId) as { id: string; acerto_id: string | null } | undefined;
  if (!recibo) return recusar('RECIBO_NAO_ENCONTRADO');
  if (recibo.acerto_id) return recusar('RECIBO_DO_ACERTO');
  const ativo = db
    .prepare(`SELECT 1 FROM recibos_consignado r WHERE r.id = ? AND ${SQL_RECIBO_ATIVO('r')}`)
    .get(p.reciboId);
  if (!ativo) return recusar('RECIBO_JA_ESTORNADO_DO_CONSIGNADO');
  estornarAplicacoes(db, agora, p.id, { coluna: 'recibo_id', id: p.reciboId });
  enfileirar(db, {
    operacaoId: p.id,
    tipo: 'ESTORNO_RECIBO',
    dados: JSON.stringify({
      id: p.id,
      reciboId: p.reciboId,
      motivo,
      estornadoEm: agora.toISOString(),
    }),
    agora,
  });
  return { operacaoId: p.id };
}

// --- cliente (cadastro com fotos, formato 2) ---

const HASH = /^[0-9a-f]{64}$/;

const limpo = (v: string | undefined | null): string | null => {
  const t = (v ?? '').trim();
  return t === '' ? null : t;
};

/**
 * Cliente novo (formato 2, ADR-0016, item 4): nome, endereço e telefone obrigatórios; as três fotos obrigatórias na tela
 * (o servidor não as exige, a foto pode ainda não ter subido). Comissão de 0% a 40% (padrão 30%). As fotos já foram
 * guardadas (criptografadas) e entram na fila de envio, que nunca trava nada.
 */
export function cadastrarClienteDoConsignado(
  db: BancoAberto,
  agora: Date,
  c: ClienteDoConsignadoParaCadastrar,
): { operacaoId: string } {
  const nome = limpo(c.nome);
  const logradouro = limpo(c.enderecoLogradouro);
  const numero = limpo(c.enderecoNumero);
  const bairro = limpo(c.enderecoBairro);
  const cidade = limpo(c.enderecoCidade);
  const uf = limpo(c.enderecoUf);
  const telefone = limpo(c.telefone);
  const whatsapp = limpo(c.whatsapp);
  if (!nome || !logradouro || !numero || !bairro || !cidade || !uf || !(telefone ?? whatsapp))
    return recusar('CADASTRO_DO_CLIENTE_INCOMPLETO');
  if (uf.length > 30) return recusar('CADASTRO_DO_CLIENTE_INCOMPLETO');
  const fotos = [c.fotoHash, c.fotoDocumentoFrenteHash, c.fotoDocumentoVersoHash];
  if (fotos.some((h) => !h || !HASH.test(h))) return recusar('FOTOS_DO_CLIENTE_FALTAM');
  const comissao = comissaoComDuasCasas(c.comissaoPercentual ?? '30');
  const indicadoPorNome = c.indicadoPorClienteId ? null : limpo(c.indicadoPorNome);
  const documento = limpo(c.documento);
  const tipoDoDocumento = documento ? (limpo(c.documentoTipo) ?? 'OUTRO') : null;
  const dados = JSON.stringify({
    id: c.id,
    nome,
    documento,
    documentoTipo: tipoDoDocumento,
    dataNascimento: limpo(c.dataNascimento),
    telefone,
    whatsapp,
    enderecoLogradouro: logradouro,
    enderecoNumero: numero,
    enderecoComplemento: limpo(c.enderecoComplemento),
    enderecoBairro: bairro,
    enderecoCidade: cidade,
    enderecoUf: uf,
    enderecoCep: limpo(c.enderecoCep),
    indicadoPorClienteId: c.indicadoPorClienteId ?? null,
    indicadoPorNome,
    comoConheceu: limpo(c.comoConheceu),
    comissaoPercentual: comissao,
    observacoes: limpo(c.observacoes),
    fotoHash: c.fotoHash,
    fotoDocumentoFrenteHash: c.fotoDocumentoFrenteHash,
    fotoDocumentoVersoHash: c.fotoDocumentoVersoHash,
  });
  inserir(db, 'clientes', c.id, c.id, {
    nome,
    documento,
    documento_tipo: tipoDoDocumento,
    data_nascimento: limpo(c.dataNascimento),
    telefone,
    whatsapp,
    endereco_logradouro: logradouro,
    endereco_numero: numero,
    endereco_complemento: limpo(c.enderecoComplemento),
    endereco_bairro: bairro,
    endereco_cidade: cidade,
    endereco_uf: uf,
    endereco_cep: limpo(c.enderecoCep),
    indicado_por_cliente_id: c.indicadoPorClienteId ?? null,
    indicado_por_nome: indicadoPorNome,
    como_conheceu: limpo(c.comoConheceu),
    comissao_percentual: comissao,
    observacoes: limpo(c.observacoes),
    foto_hash: c.fotoHash ?? null,
    foto_documento_frente_hash: c.fotoDocumentoFrenteHash ?? null,
    foto_documento_verso_hash: c.fotoDocumentoVersoHash ?? null,
    ativo: 1,
  });
  enfileirar(db, { operacaoId: c.id, tipo: 'CLIENTE_NOVO', dados, agora, formato: 2 });
  colocarFotoNaFila(db, c.id, 'cliente', c.fotoHash);
  colocarFotoNaFila(db, c.id, 'documento-frente', c.fotoDocumentoFrenteHash);
  colocarFotoNaFila(db, c.id, 'documento-verso', c.fotoDocumentoVersoHash);
  return { operacaoId: c.id };
}

/** A foto entra na fila de envio (uma por cliente e tipo); trocar a foto recomeça o envio daquele tipo. */
export function colocarFotoNaFila(
  db: BancoAberto,
  clienteId: string,
  tipo: 'cliente' | 'documento-frente' | 'documento-verso',
  hash: string,
): void {
  db.prepare(
    `INSERT INTO fila_fotos_cliente (cliente_id, tipo, hash, caminho, tentativas, enviada_em)
     VALUES (?, ?, ?, ?, 0, NULL)
     ON CONFLICT (cliente_id, tipo) DO UPDATE SET hash = excluded.hash, caminho = excluded.caminho,
       tentativas = 0, enviada_em = NULL`,
  ).run(clienteId, tipo, hash, `${hash}.bin`);
}

/** Verdadeiro se a nota passou todas as conferências de "pode mexer" (só para as telas habilitarem os botões). */
export function notaAceitaMudanca(db: BancoAberto, notaId: string): boolean {
  return acharNota(db, notaId) !== null && acertoAtivoDaNota(db, notaId) === null;
}

export { pendenciaDoCliente };
