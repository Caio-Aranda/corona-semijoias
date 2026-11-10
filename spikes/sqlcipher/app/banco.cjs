// Banco local cifrado (spike da tarefa 18). Descartável: a Fase 5 reescreve dentro do app do vendedor.
const Database = require('better-sqlite3-multiple-ciphers');
const crypto = require('node:crypto');
const fs = require('node:fs');

/** Chave de 256 bits em hexadecimal (64 caracteres), gerada com o gerador seguro do sistema. */
function gerarChave() {
  return crypto.randomBytes(32).toString('hex');
}

/**
 * Abre (ou cria) o banco cifrado. A chave entra ANTES de qualquer leitura.
 * Cifrador padrão do SQLCipher 4 (AES-256-CBC, HMAC-SHA512, página de 4096).
 * Chave no formato bruto x'...' dispensa o PBKDF2 (a chave já é aleatória de 256 bits).
 */
function abrir(caminho, chaveHex) {
  const db = new Database(caminho);
  try {
    db.pragma("cipher='sqlcipher'");
    db.pragma('legacy=4');
    db.pragma(`key="x'${chaveHex}'"`);
    db.pragma('journal_mode = WAL');
    db.pragma('synchronous = FULL');
    db.pragma('foreign_keys = ON');
    // Força a leitura da primeira página: com chave errada falha aqui.
    db.prepare('SELECT count(*) AS n FROM sqlite_master').get();
  } catch (erro) {
    try {
      db.close();
    } catch {
      /* já fechado */
    }
    const e = new Error(
      'Não foi possível abrir o banco local: chave errada ou arquivo corrompido.',
    );
    e.cause = erro;
    e.codigo = 'BANCO_ILEGIVEL';
    throw e;
  }
  return db;
}

/**
 * Migrações versionadas (PRAGMA user_version + tabela _migracoes com o hash do SQL).
 * Migração já aplicada nunca é editada: o hash diferente derruba a abertura.
 */
function migrar(db, migracoes) {
  db.exec(
    'CREATE TABLE IF NOT EXISTS _migracoes (versao INTEGER PRIMARY KEY, hash TEXT NOT NULL, aplicada_em TEXT NOT NULL)',
  );
  const aplicadas = new Map(
    db
      .prepare('SELECT versao, hash FROM _migracoes')
      .all()
      .map((l) => [l.versao, l.hash]),
  );
  for (const m of migracoes) {
    const hash = crypto.createHash('sha256').update(m.sql).digest('hex');
    if (aplicadas.has(m.versao)) {
      if (aplicadas.get(m.versao) !== hash) {
        throw new Error(
          `A migração ${m.versao} já foi aplicada e foi editada. Crie uma nova migração.`,
        );
      }
      continue;
    }
    db.transaction(() => {
      db.exec(m.sql);
      db.prepare('INSERT INTO _migracoes VALUES (?, ?, ?)').run(
        m.versao,
        hash,
        new Date().toISOString(),
      );
      db.pragma(`user_version = ${m.versao}`);
    })();
  }
  return db.pragma('user_version', { simple: true });
}

/**
 * Cofre da chave: arquivo com a chave protegida pelo safeStorage (no Windows, DPAPI).
 * `cofre` = { disponivel(), cifrar(texto) => Buffer, decifrar(Buffer) => texto } (podem ser assíncronos).
 * Se o arquivo da chave sumir com o banco ainda lá, NÃO cria chave nova em silêncio: lança erro.
 */
async function chaveDoCofre(arquivoChave, arquivoBanco, cofre) {
  if (!(await cofre.disponivel())) {
    const e = new Error('O cofre do Windows não está disponível neste computador.');
    e.codigo = 'COFRE_INDISPONIVEL';
    throw e;
  }
  if (fs.existsSync(arquivoChave)) {
    try {
      return await cofre.decifrar(fs.readFileSync(arquivoChave));
    } catch {
      const e = new Error(
        'Não foi possível ler a chave do banco local (outro usuário do Windows ou cofre trocado).',
      );
      e.codigo = 'CHAVE_ILEGIVEL';
      throw e;
    }
  }
  if (fs.existsSync(arquivoBanco)) {
    const e = new Error('O arquivo da chave do banco local sumiu.');
    e.codigo = 'CHAVE_AUSENTE';
    throw e;
  }
  const chave = gerarChave();
  fs.writeFileSync(arquivoChave, await cofre.cifrar(chave));
  return chave;
}

module.exports = { gerarChave, abrir, migrar, chaveDoCofre };
