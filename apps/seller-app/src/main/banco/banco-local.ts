import Database from 'better-sqlite3-multiple-ciphers';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, readdirSync, renameSync, rmSync, statfsSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { MIGRACOES, type MigracaoLocal } from './migracoes';

/** O banco aberto (better-sqlite3). Só o processo principal o vê; a tela nunca (ADR-0013, item 7). */
export type BancoAberto = Database.Database;

/**
 * Por que o banco local não abriu (ou a gravação falhou). Cada código tem um texto do glossário em
 * {@link MENSAGENS_DO_BANCO}; a tela nunca vê o erro técnico.
 */
export type CodigoDoBanco =
  /** A chave não abre o arquivo (chave errada, arquivo trocado ou estragado). */
  | 'BANCO_ILEGIVEL'
  /** Uma migração já aplicada mudou: defeito do aplicativo, nunca do vendedor. */
  | 'MIGRACAO_EDITADA'
  /** Os dados são de uma versão mais nova do aplicativo (voltaram a versão): não abre e não mexe. */
  | 'BANCO_MAIS_NOVO'
  /** A migração falhou no meio: o arquivo voltou para a cópia de antes. */
  | 'MIGRACAO_FALHOU'
  /** Sem espaço no disco: a transação inteira foi desfeita. */
  | 'DISCO_CHEIO';

/** Textos do glossário (parte "Banco local do Corona Vendas", tarefa 5.2). */
export const MENSAGENS_DO_BANCO: Readonly<Record<CodigoDoBanco, string>> = Object.freeze({
  BANCO_ILEGIVEL:
    'Não conseguimos abrir os dados deste computador. Conecte à internet para baixar tudo de novo. Se havia vendas ainda não enviadas, avise a dona.',
  MIGRACAO_EDITADA:
    'Não foi possível preparar os dados deste computador. Feche e abra o Corona Vendas de novo. Se continuar, avise a dona.',
  BANCO_MAIS_NOVO:
    'Este Corona Vendas é mais antigo que os dados deste computador. Atualize o Corona Vendas para continuar.',
  MIGRACAO_FALHOU:
    'Não foi possível preparar os dados deste computador. Feche e abra o Corona Vendas de novo. Se continuar, avise a dona.',
  DISCO_CHEIO: 'O computador está sem espaço. Libere espaço e tente de novo.',
});

/** Aviso ao abrir com menos de 500 MB livres (ADR-0013, item 15). */
export const AVISO_DE_POUCO_ESPACO =
  'O computador está com pouco espaço. Libere espaço para o Corona Vendas continuar guardando as vendas.';
export const ESPACO_MINIMO_EM_BYTES = 500 * 1024 * 1024;

export class ErroDoBanco extends Error {
  constructor(
    readonly codigo: CodigoDoBanco,
    opcoes?: { cause?: unknown },
  ) {
    super(MENSAGENS_DO_BANCO[codigo], opcoes);
    this.name = 'ErroDoBanco';
  }
}

export interface OpcoesDoBanco {
  /** Caminho do arquivo do banco (ex.: `<dados do app>/dados/banco.sqlite`). */
  caminho: string;
  /** Chave de 256 bits (32 bytes). */
  chave: Uint8Array;
  /** Para os testes; o padrão é a lista do aplicativo. */
  migracoes?: readonly MigracaoLocal[];
  /** Quantas cópias "antes da migração" guardar (padrão 2, ADR-0013 item 16). */
  copiasGuardadas?: number;
}

/**
 * Abre (ou cria) o banco local cifrado e o deixa na versão deste aplicativo (ADR-0005; ADR-0013, itens 15 e 16).
 *
 * - SQLCipher 4 (`cipher=sqlcipher`, `legacy=4`), chave bruta de 256 bits, WAL, `synchronous=FULL`, `foreign_keys=ON`.
 * - Chave errada ou arquivo estragado: {@link ErroDoBanco} `BANCO_ILEGIVEL`, sem mexer no arquivo.
 * - Banco de versão mais nova que o app: `BANCO_MAIS_NOVO`, sem mexer no arquivo.
 * - Migração já aplicada e editada: `MIGRACAO_EDITADA`.
 * - Antes de migrar um banco que já existia: cópia do arquivo (`<banco>.antes-vN`), guardando as duas últimas. Se a
 *   migração falhar, o arquivo volta para a cópia e a abertura falha com `MIGRACAO_FALHOU` (ou `DISCO_CHEIO`).
 */
export function abrirBancoLocal(opcoes: OpcoesDoBanco): BancoAberto {
  const migracoes = opcoes.migracoes ?? MIGRACOES;
  const versaoDoApp = migracoes.reduce((maior, m) => Math.max(maior, m.versao), 0);
  const existia = existsSync(opcoes.caminho);

  let db = abrirCifrado(opcoes.caminho, opcoes.chave);
  let versaoAtual: number;
  try {
    versaoAtual = Number(db.pragma('user_version', { simple: true }));
    if (versaoAtual > versaoDoApp) throw new ErroDoBanco('BANCO_MAIS_NOVO');
    conferirMigracoesAplicadas(db, migracoes);
  } catch (erro) {
    db.close();
    throw erro;
  }

  const pendentes = migracoes
    .filter((m) => m.versao > versaoAtual)
    .sort((a, b) => a.versao - b.versao);
  if (pendentes.length === 0) return db;

  let copia: string | null = null;
  if (existia && versaoAtual > 0) {
    // A cópia é do arquivo cifrado como está: o WAL vai para dentro do arquivo antes, e o banco fecha.
    db.pragma('wal_checkpoint(TRUNCATE)');
    db.close();
    copia = `${opcoes.caminho}.antes-v${versaoAtual}`;
    copyFileSync(opcoes.caminho, copia);
    guardarSoAsUltimasCopias(opcoes.caminho, opcoes.copiasGuardadas ?? 2);
    db = abrirCifrado(opcoes.caminho, opcoes.chave);
  }

  try {
    garantirTabelaDeMigracoes(db);
    for (const m of pendentes) {
      db.transaction(() => {
        db.exec(m.sql);
        db.prepare('INSERT INTO _migracoes (versao, hash, aplicada_em) VALUES (?, ?, ?)').run(
          m.versao,
          hashDe(m.sql),
          new Date().toISOString(),
        );
        db.pragma(`user_version = ${m.versao}`);
      })();
    }
    return db;
  } catch (erro) {
    try {
      db.close();
    } catch {
      /* já fechado */
    }
    if (copia) {
      // Volta para a cópia de antes da migração: nada do que havia se perde.
      apagarArquivosDoBanco(opcoes.caminho);
      copyFileSync(copia, opcoes.caminho);
    } else if (!existia) {
      // Banco novo que nem chegou a ficar pronto: não deixa arquivo pela metade.
      apagarArquivosDoBanco(opcoes.caminho);
    }
    throw new ErroDoBanco(ehDiscoCheio(erro) ? 'DISCO_CHEIO' : 'MIGRACAO_FALHOU', { cause: erro });
  }
}

/**
 * Grava numa transação só (ou tudo, ou nada) e traduz o disco cheio para o texto do glossário. Toda gravação do
 * aplicativo no banco local passa por aqui (a venda nunca fica pela metade, ADR-0013 item 15).
 */
export function gravar<T>(db: BancoAberto, fn: () => T): T {
  try {
    return db.transaction(fn)();
  } catch (erro) {
    if (ehDiscoCheio(erro)) throw new ErroDoBanco('DISCO_CHEIO', { cause: erro });
    throw erro;
  }
}

/**
 * Move o banco que não abriu (e o WAL) para `<banco>.ilegivel-<data>`, sem apagar nada (ADR-0005, "Riscos"): a dona
 * ainda pode querer o arquivo. O próximo banco nasce vazio e o computador baixa tudo de novo.
 */
export function moverParaIlegivel(caminho: string, agora: Date = new Date()): string {
  const carimbo = agora.toISOString().replace(/[-:]/g, '').replace(/\..*$/, '');
  const destino = `${caminho}.ilegivel-${carimbo}`;
  for (const sufixo of ['', '-wal', '-shm']) {
    if (existsSync(caminho + sufixo)) renameSync(caminho + sufixo, destino + sufixo);
  }
  return destino;
}

/** Apaga o banco, o WAL e o arquivo de memória compartilhada (uso: volta da cópia; apagar os dados, tarefa 5.8). */
export function apagarArquivosDoBanco(caminho: string): void {
  for (const sufixo of ['', '-wal', '-shm']) rmSync(caminho + sufixo, { force: true });
}

/** Espaço livre no disco da pasta, em bytes (para o aviso de pouco espaço ao abrir). */
export function espacoLivre(pasta: string): number {
  const s = statfsSync(pasta);
  return Number(s.bavail) * Number(s.bsize);
}

// --- internos ---

function abrirCifrado(caminho: string, chave: Uint8Array): BancoAberto {
  if (chave.length !== 32) throw new Error('A chave do banco local precisa ter 32 bytes.');
  const db = new Database(caminho);
  try {
    db.pragma("cipher='sqlcipher'");
    db.pragma('legacy=4');
    db.pragma(`key="x'${Buffer.from(chave).toString('hex')}'"`);
    // Força a leitura da primeira página: com a chave errada falha aqui, antes de qualquer outra coisa.
    db.prepare('SELECT count(*) AS n FROM sqlite_master').get();
    db.pragma('journal_mode = WAL');
    db.pragma('synchronous = FULL');
    db.pragma('foreign_keys = ON');
    return db;
  } catch (erro) {
    try {
      db.close();
    } catch {
      /* já fechado */
    }
    throw new ErroDoBanco('BANCO_ILEGIVEL', { cause: erro });
  }
}

function garantirTabelaDeMigracoes(db: BancoAberto): void {
  db.exec(
    'CREATE TABLE IF NOT EXISTS _migracoes (versao INTEGER PRIMARY KEY, hash TEXT NOT NULL, aplicada_em TEXT NOT NULL)',
  );
}

function conferirMigracoesAplicadas(db: BancoAberto, migracoes: readonly MigracaoLocal[]): void {
  const existe = db
    .prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '_migracoes'")
    .get();
  if (!existe) return;
  const porVersao = new Map(migracoes.map((m) => [m.versao, m]));
  const aplicadas = db.prepare('SELECT versao, hash FROM _migracoes').all() as {
    versao: number;
    hash: string;
  }[];
  for (const a of aplicadas) {
    const m = porVersao.get(a.versao);
    if (m && hashDe(m.sql) !== a.hash) throw new ErroDoBanco('MIGRACAO_EDITADA');
  }
}

function hashDe(sql: string): string {
  return createHash('sha256').update(sql, 'utf8').digest('hex');
}

/** Mantém só as N cópias "antes da migração" mais novas (pela versão no nome). */
function guardarSoAsUltimasCopias(caminho: string, quantas: number): void {
  const pasta = dirname(caminho);
  const prefixo = `${basename(caminho)}.antes-v`;
  const copias = readdirSync(pasta)
    .filter((n) => n.startsWith(prefixo) && /^\d+$/.test(n.slice(prefixo.length)))
    .map((n) => ({ nome: n, versao: Number(n.slice(prefixo.length)) }))
    .sort((a, b) => b.versao - a.versao);
  for (const velha of copias.slice(Math.max(quantas, 1)))
    rmSync(join(pasta, velha.nome), { force: true });
}

function ehDiscoCheio(erro: unknown): boolean {
  return (
    typeof erro === 'object' &&
    erro !== null &&
    'code' in erro &&
    (erro as { code?: unknown }).code === 'SQLITE_FULL'
  );
}
