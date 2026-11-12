import type { BancoAberto } from '../banco/banco-local';

/** Chaves da tabela `estado_sync` (texto livre, uma linha por chave). */
export const CHAVES = Object.freeze({
  /** Cursor opaco do pull; ausente = carga inicial. */
  cursor: 'cursor',
  /** Hora do servidor (ISO, UTC) da última resposta válida do envio ou do recebimento. */
  horaServidor: 'hora_servidor',
  /** Hora do computador no mesmo instante em que chegou a `hora_servidor` (insumo do prazo offline, tarefa 5.8). */
  horaLocalDoContato: 'hora_local_do_contato',
  /** A maior hora do computador já vista (relógio voltado para trás, tarefa 5.8). */
  maiorHoraLocal: 'maior_hora_local',
  /** O bloco `parametros` da última resposta (JSON). */
  parametros: 'parametros',
  /** "Dados até": hora do servidor no começo do último recebimento completo. */
  dadosAte: 'dados_ate',
  /** Hora do servidor da última conferência por resumo que bateu (ou que recarregou o que faltava). */
  conferidoEm: 'conferido_em',
  /** "1" depois de uma carga inicial: a conferência roda no fim do próximo recebimento completo. */
  conferirDepoisDaCarga: 'conferir_depois_da_carga',
} as const);

export function lerEstado(db: BancoAberto, chave: string): string | null {
  const r = db.prepare('SELECT valor FROM estado_sync WHERE chave = ?').get(chave) as
    { valor: string | null } | undefined;
  return r?.valor ?? null;
}

export function gravarEstado(db: BancoAberto, chave: string, valor: string | null): void {
  if (valor === null) {
    db.prepare('DELETE FROM estado_sync WHERE chave = ?').run(chave);
    return;
  }
  db.prepare(
    'INSERT INTO estado_sync (chave, valor) VALUES (?, ?) ON CONFLICT(chave) DO UPDATE SET valor = excluded.valor',
  ).run(chave, valor);
}

/**
 * Guarda o contato com o servidor: a hora dele, a hora local do mesmo instante e a maior hora local já vista. A regra
 * do prazo offline e do relógio voltado é da tarefa 5.8; aqui só fica o insumo.
 */
export function registrarContato(db: BancoAberto, horaServidor: string, horaLocal: Date): void {
  if (Number.isNaN(Date.parse(horaServidor))) return;
  gravarEstado(db, CHAVES.horaServidor, horaServidor);
  gravarEstado(db, CHAVES.horaLocalDoContato, horaLocal.toISOString());
  registrarHoraLocal(db, horaLocal);
}

export function registrarHoraLocal(db: BancoAberto, horaLocal: Date): void {
  const maior = lerEstado(db, CHAVES.maiorHoraLocal);
  if (!maior || Date.parse(maior) < horaLocal.getTime()) {
    gravarEstado(db, CHAVES.maiorHoraLocal, horaLocal.toISOString());
  }
}
