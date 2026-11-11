import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  abrirBancoLocal,
  type BancoAberto,
  ErroDoBanco,
  espacoLivre,
  gravar,
  MENSAGENS_DO_BANCO,
  moverParaIlegivel,
} from './banco-local';
import {
  MIGRACOES,
  TABELAS_SINCRONIZAVEIS,
  type MigracaoLocal,
  VERSAO_DO_BANCO,
} from './migracoes';

/**
 * Banco local do Corona Vendas (tarefa 5.2; ADR-0005; ADR-0013, itens 15 e 16). SQLCipher de verdade
 * (better-sqlite3-multiple-ciphers), arquivos de verdade numa pasta temporária: nunca banco em memória.
 */

let pasta: string;
let caminho: string;
const abertos: BancoAberto[] = [];

beforeEach(() => {
  pasta = mkdtempSync(join(tmpdir(), 'corona-banco-'));
  caminho = join(pasta, 'banco.sqlite');
});

afterEach(() => {
  for (const db of abertos.splice(0)) {
    try {
      db.close();
    } catch {
      /* já fechado */
    }
  }
  rmSync(pasta, { recursive: true, force: true });
});

function abrir(chave: Uint8Array, migracoes?: readonly MigracaoLocal[]): BancoAberto {
  const db = abrirBancoLocal({ caminho, chave, ...(migracoes ? { migracoes } : {}) });
  abertos.push(db);
  return db;
}

function fechar(db: BancoAberto): void {
  db.close();
}

const chave = (): Buffer => randomBytes(32);
const hashDoArquivo = (c: string): string =>
  createHash('sha256').update(readFileSync(c)).digest('hex');
const codigoDe = (fn: () => unknown): string | undefined => {
  try {
    fn();
  } catch (e) {
    return e instanceof ErroDoBanco ? e.codigo : `outro: ${String(e)}`;
  }
  return undefined;
};

const M1: MigracaoLocal = {
  versao: 1,
  descricao: 'um',
  sql: 'CREATE TABLE t (id TEXT PRIMARY KEY, valor TEXT);',
};
const M2: MigracaoLocal = {
  versao: 2,
  descricao: 'dois',
  sql: 'ALTER TABLE t ADD COLUMN outra TEXT;',
};
const M3: MigracaoLocal = {
  versao: 3,
  descricao: 'três',
  sql: 'CREATE TABLE u (id TEXT PRIMARY KEY);',
};
const M4: MigracaoLocal = {
  versao: 4,
  descricao: 'quatro',
  sql: 'CREATE TABLE w (id TEXT PRIMARY KEY);',
};

describe('cifra e chave', () => {
  it('a chave certa lê, a errada falha na hora e o arquivo não mostra os dados', () => {
    const k = chave();
    const db = abrir(k);
    gravar(db, () =>
      db
        .prepare(
          "INSERT INTO categorias (id, version, nome) VALUES ('c1', 1, 'Valor-Conhecido-123')",
        )
        .run(),
    );
    db.pragma('wal_checkpoint(TRUNCATE)');
    fechar(db);

    const bytes = readFileSync(caminho);
    expect(bytes.subarray(0, 15).toString('latin1')).not.toBe('SQLite format 3');
    expect(bytes.includes(Buffer.from('Valor-Conhecido-123'))).toBe(false);
    expect(bytes.includes(Buffer.from(k.toString('hex')))).toBe(false);

    expect(codigoDe(() => abrir(chave()))).toBe('BANCO_ILEGIVEL');
    const deNovo = abrir(k);
    expect(deNovo.prepare("SELECT nome FROM categorias WHERE id = 'c1'").get()).toEqual({
      nome: 'Valor-Conhecido-123',
    });
  });

  it('usa SQLCipher 4, WAL e gravação completa', () => {
    const db = abrir(chave());
    expect(db.pragma('journal_mode', { simple: true })).toBe('wal');
    expect(db.pragma('synchronous', { simple: true })).toBe(2); // FULL
    expect(db.pragma('foreign_keys', { simple: true })).toBe(1);
    expect(Number(db.pragma('kdf_iter', { simple: true }))).toBe(256000);
    expect(Number(db.pragma('page_size', { simple: true }))).toBe(4096);
  });

  it('arquivo estragado (não é banco) é ilegível e nada é apagado', () => {
    writeFileSync(caminho, randomBytes(8192));
    const antes = hashDoArquivo(caminho);
    expect(codigoDe(() => abrir(chave()))).toBe('BANCO_ILEGIVEL');
    expect(hashDoArquivo(caminho)).toBe(antes);
  });

  it('o banco que não abre vai para .ilegivel, com o WAL, sem apagar', () => {
    const db = abrir(chave());
    fechar(db);
    writeFileSync(`${caminho}-wal`, 'x');
    const destino = moverParaIlegivel(caminho, new Date('2026-11-11T10:20:30Z'));
    expect(destino).toBe(`${caminho}.ilegivel-20261111T102030`);
    expect(existsSync(caminho)).toBe(false);
    expect(existsSync(destino)).toBe(true);
    expect(existsSync(`${destino}-wal`)).toBe(true);
  });
});

describe('migrações', () => {
  it('o esquema da versão 1 tem as cópias, a outbox e o estado do envio', () => {
    const db = abrir(chave());
    expect(db.pragma('user_version', { simple: true })).toBe(VERSAO_DO_BANCO);
    const tabelas = (
      db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all() as { name: string }[]
    ).map((t) => t.name);
    expect(tabelas).toEqual(
      expect.arrayContaining([...TABELAS_SINCRONIZAVEIS, 'outbox', 'estado_sync', '_migracoes']),
    );
    // Toda cópia tem os campos de controle do sync.
    for (const t of TABELAS_SINCRONIZAVEIS) {
      const colunas = (db.prepare(`PRAGMA table_info(${t})`).all() as { name: string }[]).map(
        (c) => c.name,
      );
      expect(colunas, t).toEqual(
        expect.arrayContaining(['id', 'version', 'deleted_at', 'origem_local']),
      );
      // Nada de custo nem de estoque da loja no computador do vendedor.
      expect(colunas.join(' ').toLowerCase(), t).not.toMatch(/custo|estoque|fornecedor/);
    }
  });

  it('dinheiro é texto: "12.30" volta igual, nunca número quebrado', () => {
    const db = abrir(chave());
    gravar(db, () =>
      db
        .prepare(
          "INSERT INTO itens_remessa (id, version, remessa_id, produto_id, qtd_enviada, preco_remessa) VALUES ('i1', 1, 'r', 'p', 1, '12.30')",
        )
        .run(),
    );
    expect(
      db.prepare('SELECT preco_remessa, typeof(preco_remessa) AS tipo FROM itens_remessa').get(),
    ).toEqual({
      preco_remessa: '12.30',
      tipo: 'text',
    });
  });

  it('reaplicar é inofensivo e migração já aplicada que mudou derruba a abertura', () => {
    const k = chave();
    fechar(abrir(k, [M1, M2]));
    fechar(abrir(k, [M1, M2]));
    const editada = { ...M2, sql: 'ALTER TABLE t ADD COLUMN outra_coisa TEXT;' };
    expect(codigoDe(() => abrir(k, [M1, editada]))).toBe('MIGRACAO_EDITADA');
  });

  it('faz uma cópia antes de migrar e guarda só as duas últimas', () => {
    const k = chave();
    const db = abrir(k, [M1]);
    gravar(db, () => db.prepare("INSERT INTO t VALUES ('a', 'guardado')").run());
    fechar(db);

    fechar(abrir(k, [M1, M2]));
    expect(existsSync(`${caminho}.antes-v1`)).toBe(true);
    fechar(abrir(k, [M1, M2, M3]));
    fechar(abrir(k, [M1, M2, M3, M4]));
    const copias = readdirSync(pasta)
      .filter((n) => n.includes('.antes-v'))
      .sort();
    expect(copias).toEqual(['banco.sqlite.antes-v2', 'banco.sqlite.antes-v3']);

    // A cópia é o arquivo cifrado inteiro: abre com a mesma chave e tem o dado de antes.
    rmSync(caminho);
    writeFileSync(caminho, readFileSync(`${caminho}.antes-v3`));
    const daCopia = abrir(k, [M1, M2, M3]);
    expect(daCopia.prepare("SELECT valor FROM t WHERE id = 'a'").get()).toEqual({
      valor: 'guardado',
    });
  });

  it('migração que falha volta para a cópia e nada se perde', () => {
    const k = chave();
    const db = abrir(k, [M1]);
    gravar(db, () => db.prepare("INSERT INTO t VALUES ('a', 'guardado')").run());
    fechar(db);
    const quebrada: MigracaoLocal = {
      versao: 2,
      descricao: 'quebrada',
      sql: 'ALTER TABLE nao_existe ADD COLUMN x TEXT;',
    };

    expect(codigoDe(() => abrir(k, [M1, quebrada]))).toBe('MIGRACAO_FALHOU');
    const voltou = abrir(k, [M1]);
    expect(voltou.pragma('user_version', { simple: true })).toBe(1);
    expect(voltou.prepare("SELECT valor FROM t WHERE id = 'a'").get()).toEqual({
      valor: 'guardado',
    });
  });

  it('banco de versão mais nova que o app não abre e não é mexido', () => {
    const k = chave();
    fechar(abrir(k, [M1, M2]));
    const antes = hashDoArquivo(caminho);
    expect(codigoDe(() => abrir(k, [M1]))).toBe('BANCO_MAIS_NOVO');
    expect(hashDoArquivo(caminho)).toBe(antes);
  });

  it('a lista de migrações do app está em ordem e sem versão repetida', () => {
    const versoes = MIGRACOES.map((m) => m.versao);
    expect(versoes).toEqual([...versoes].sort((a, b) => a - b));
    expect(new Set(versoes).size).toBe(versoes.length);
    expect(versoes[0]).toBe(1);
  });
});

describe('falhas do disco e do processo', () => {
  it('disco cheio desfaz a transação inteira e diz o texto do glossário', () => {
    const db = abrir(chave());
    gravar(db, () => db.prepare("INSERT INTO estado_sync VALUES ('antes', 'x')").run());
    const paginas = Number(db.pragma('page_count', { simple: true }));
    db.pragma(`max_page_count = ${paginas + 2}`);
    const grande = 'x'.repeat(3000);
    let erro: unknown;
    try {
      gravar(db, () => {
        db.prepare(
          "INSERT INTO outbox (operacao_id, tipo, formato, dados, criado_em_local) VALUES ('op-1', 'VENDA', 1, '{}', 'agora')",
        ).run();
        for (let i = 0; i < 200; i++)
          db.prepare('INSERT INTO estado_sync VALUES (?, ?)').run(`k${i}`, grande);
      });
    } catch (e) {
      erro = e;
    }
    expect(erro).toBeInstanceOf(ErroDoBanco);
    expect((erro as ErroDoBanco).codigo).toBe('DISCO_CHEIO');
    expect((erro as ErroDoBanco).message).toBe(
      'O computador está sem espaço. Libere espaço e tente de novo.',
    );
    // Nem a linha da outbox nem as outras ficaram: ou tudo, ou nada.
    expect(db.prepare('SELECT count(*) AS n FROM outbox').get()).toEqual({ n: 0 });
    expect(db.prepare('SELECT count(*) AS n FROM estado_sync').get()).toEqual({ n: 1 });
  });

  it('processo morto no meio de gravações e do checkpoint do WAL não corrompe e não deixa lote pela metade', async () => {
    const k = chave();
    fechar(abrir(k));
    const modulo = createRequire(import.meta.url).resolve('better-sqlite3-multiple-ciphers');
    const script = join(pasta, 'escritor.cjs');
    writeFileSync(
      script,
      `const Database = require(${JSON.stringify(modulo)});
const db = new Database(${JSON.stringify(caminho)});
db.pragma("cipher='sqlcipher'"); db.pragma('legacy=4'); db.pragma(\`key="x'${k.toString('hex')}'"\`);
db.pragma('journal_mode = WAL'); db.pragma('synchronous = FULL');
const ins = db.prepare('INSERT INTO estado_sync VALUES (?, ?)');
let n = 0;
process.stdout.write('pronto\\n');
for (;;) {
  db.transaction(() => { for (let i = 0; i < 50; i++) ins.run('k' + (n++), 'v'.repeat(200)); })();
  if (n % 500 === 0) db.pragma('wal_checkpoint(TRUNCATE)');
}`,
    );
    const filho = spawn(process.execPath, [script], { stdio: ['ignore', 'pipe', 'inherit'] });
    await new Promise<void>((resolver) => filho.stdout.once('data', () => resolver()));
    await new Promise((r) => setTimeout(r, 400));
    filho.kill('SIGKILL');
    await new Promise((r) => filho.once('exit', r));

    const db = abrir(k);
    expect(db.pragma('integrity_check', { simple: true })).toBe('ok');
    const { n } = db.prepare('SELECT count(*) AS n FROM estado_sync').get() as { n: number };
    expect(n).toBeGreaterThan(0);
    expect(n % 50).toBe(0); // cada lote de 50 entrou inteiro ou não entrou
  }, 30_000);

  it('mede o espaço livre do disco (aviso abaixo de 500 MB)', () => {
    expect(espacoLivre(pasta)).toBeGreaterThan(0);
    expect(MENSAGENS_DO_BANCO.BANCO_ILEGIVEL).toContain('Conecte à internet');
  });
});
