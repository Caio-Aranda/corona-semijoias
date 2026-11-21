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
  /**
   * Tabelas (JSON, lista) que ainda vêm de novo pela recarga dirigida (migração local 3, tarefa 6.7): sem apagar nada
   * antes, a versão igual também é substituída. Cada tabela sai da lista na transação da última página dela.
   */
  recargaDirigida: 'recarga_dirigida',
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
  // Uma resposta válida do servidor refaz o par (tarefa 5.8): a maior hora vista recomeça daqui, e é isso que libera um
  // computador cujo relógio foi mexido. Dentro do período sem internet ela só sobe (`registrarHoraLocal`).
  gravarEstado(db, CHAVES.maiorHoraLocal, horaLocal.toISOString());
}

/** A partir de quantos milissegundos de diferença o relógio do computador conta como "errado" (a mesma folga do prazo offline). */
export const FOLGA_DO_RELOGIO_MS = 5 * 60 * 1000;

/**
 * A diferença entre o relógio do servidor e o deste computador, guardada no último contato (ADR-0013, item 11):
 * `hora do servidor - hora local do mesmo instante`. Positiva = computador atrasado. `null` se nunca houve contato ou se a
 * diferença é menor que a folga (relógio certo para o que importa aqui).
 */
export function diferencaDoRelogioMs(db: BancoAberto): number | null {
  const servidor = lerEstado(db, CHAVES.horaServidor);
  const local = lerEstado(db, CHAVES.horaLocalDoContato);
  if (!servidor || !local) return null;
  const dif = Date.parse(servidor) - Date.parse(local);
  if (Number.isNaN(dif) || Math.abs(dif) < FOLGA_DO_RELOGIO_MS) return null;
  return dif;
}

export function registrarHoraLocal(db: BancoAberto, horaLocal: Date): void {
  const maior = lerEstado(db, CHAVES.maiorHoraLocal);
  if (!maior || Date.parse(maior) < horaLocal.getTime()) {
    gravarEstado(db, CHAVES.maiorHoraLocal, horaLocal.toISOString());
  }
}
