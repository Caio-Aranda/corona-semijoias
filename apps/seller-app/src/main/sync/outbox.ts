import type { BancoAberto } from '../banco/banco-local';
import { aplicarCamposSeIguais, lerAlteracao } from './camada-de-pendentes';
import { ehTabelaSincronizavel } from './colunas';
import { CHAVES, lerEstado } from './estado-sync';

/** Os tipos de operação do formato 1 (tarefa 5.5). */
export type TipoDeOperacao =
  'VENDA' | 'ESTORNO_VENDA' | 'CLIENTE_NOVO' | 'CLIENTE_ALTERADO' | 'CONFERENCIA';

export type SituacaoNaOutbox = 'AGUARDANDO' | 'ENVIADO' | 'RECUSADO' | 'CONFLITO';

export interface LinhaDaOutbox {
  ordem: number;
  operacao_id: string;
  tipo: TipoDeOperacao;
  formato: number;
  dados: string;
  criado_em_local: string;
  ultimo_contato_servidor: string | null;
  situacao: SituacaoNaOutbox;
  tentativas: number;
}

/** Operações ENVIADO, e as recusas já "entendidas", saem da outbox depois de 30 dias (ADR-0013, item 2). */
export const DIAS_NA_OUTBOX = 30;

/**
 * Põe uma operação na fila. Chame SEMPRE dentro da mesma transação que gravou a cópia provisória: ou existem as duas,
 * ou nenhuma (ADR-0013, item 2). `dados` é o JSON exato desta hora; o reenvio manda sempre estes bytes.
 */
export function enfileirar(
  db: BancoAberto,
  op: { operacaoId: string; tipo: TipoDeOperacao; dados: string; agora: Date },
): void {
  db.prepare(
    `INSERT INTO outbox (operacao_id, tipo, formato, dados, criado_em_local, ultimo_contato_servidor)
     VALUES (?, ?, 1, ?, ?, ?)`,
  ).run(
    op.operacaoId,
    op.tipo,
    op.dados,
    op.agora.toISOString(),
    lerEstado(db, CHAVES.horaServidor),
  );
}

/** Marca uma linha recém-gravada como provisória da operação (para desfazer ou limpar depois). */
export function marcarProvisoria(
  db: BancoAberto,
  tabela: string,
  linhaId: string,
  operacaoId: string,
): void {
  db.prepare('INSERT INTO provisorias (tabela, linha_id, operacao_id) VALUES (?, ?, ?)').run(
    tabela,
    linhaId,
    operacaoId,
  );
}

/** As operações que aguardam envio, na ordem da fila. */
export function lerAguardando(db: BancoAberto, limite: number): LinhaDaOutbox[] {
  return db
    .prepare(
      `SELECT ordem, operacao_id, tipo, formato, dados, criado_em_local, ultimo_contato_servidor, situacao, tentativas
         FROM outbox WHERE situacao = 'AGUARDANDO' ORDER BY ordem LIMIT ?`,
    )
    .all(limite) as LinhaDaOutbox[];
}

/**
 * Desfaz o efeito local de uma operação que o servidor não aceitou (RECUSADO, ou CONFLITO: o servidor guardou outro
 * conteúdo e não o nosso). As linhas provisórias dela saem, e com elas o movimento da maleta: o saldo volta. Na
 * alteração de cliente, cada campo que ainda tem o valor novo volta para o de antes. Chamada na MESMA transação que muda a
 * situação na outbox. Repetir é inofensivo (não sobra nada para desfazer).
 */
export function desfazerOperacao(db: BancoAberto, operacaoId: string): void {
  removerProvisorias(db, operacaoId);
  const linha = db
    .prepare('SELECT tipo, dados FROM outbox WHERE operacao_id = ?')
    .get(operacaoId) as { tipo: string; dados: string } | undefined;
  if (linha?.tipo === 'CLIENTE_ALTERADO') {
    const a = lerAlteracao(linha.dados);
    if (a) aplicarCamposSeIguais(db, a.clienteId, a.campos);
  }
}

/**
 * Depois de um recebimento completo (`temMais = false`), a cópia do servidor de toda operação ENVIADA já chegou: as
 * linhas provisórias que sobraram dela (itens e movimentos, que no servidor têm outro id) saem. Uma provisória de
 * operação ainda AGUARDANDO nunca sai aqui.
 */
export function limparProvisoriasEnviadas(db: BancoAberto): number {
  const ops = db
    .prepare(
      `SELECT DISTINCT p.operacao_id AS id FROM provisorias p
         LEFT JOIN outbox o ON o.operacao_id = p.operacao_id
        WHERE o.situacao = 'ENVIADO' OR o.operacao_id IS NULL`,
    )
    .all() as { id: string }[];
  for (const op of ops) removerProvisorias(db, op.id);
  return ops.length;
}

/** Limpeza do próprio motor: ENVIADO e recusas "entendidas" há mais de 30 dias saem da outbox. */
export function limparOutboxAntiga(db: BancoAberto, agora: Date): number {
  const limite = new Date(agora.getTime() - DIAS_NA_OUTBOX * 86_400_000).toISOString();
  return db
    .prepare(
      `DELETE FROM outbox
        WHERE (situacao = 'ENVIADO' AND respondido_em IS NOT NULL AND respondido_em < ?)
           OR (situacao IN ('RECUSADO', 'CONFLITO') AND entendido_em IS NOT NULL AND entendido_em < ?)`,
    )
    .run(limite, limite).changes;
}

/** O vendedor tocou em "Entendi" numa recusa (a tela é da tarefa 5.7). */
export function marcarEntendido(db: BancoAberto, operacaoId: string, agora: Date): void {
  db.prepare(
    `UPDATE outbox SET entendido_em = ? WHERE operacao_id = ? AND situacao IN ('RECUSADO', 'CONFLITO')
       AND entendido_em IS NULL`,
  ).run(agora.toISOString(), operacaoId);
}

function removerProvisorias(db: BancoAberto, operacaoId: string): void {
  const linhas = db
    .prepare('SELECT tabela, linha_id FROM provisorias WHERE operacao_id = ?')
    .all(operacaoId) as { tabela: string; linha_id: string }[];
  for (const l of linhas) {
    if (!ehTabelaSincronizavel(l.tabela)) continue;
    // Só a cópia que ainda é provisória: a que o servidor já trocou (mesmo id) fica.
    db.prepare(`DELETE FROM ${l.tabela} WHERE id = ? AND origem_local = 'PROVISORIO'`).run(
      l.linha_id,
    );
  }
  db.prepare('DELETE FROM provisorias WHERE operacao_id = ?').run(operacaoId);
}
