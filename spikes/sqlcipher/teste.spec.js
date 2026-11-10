import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { gerarChave, abrir, migrar, chaveDoCofre } = require('./app/banco.cjs');

const M1 = { versao: 1, sql: 'CREATE TABLE cliente (id TEXT PRIMARY KEY, nome TEXT NOT NULL)' };
const M2 = {
  versao: 2,
  sql: 'CREATE TABLE venda (id TEXT PRIMARY KEY, total NUMERIC NOT NULL); CREATE INDEX ix_venda_total ON venda(total)',
};
let dir;
let arq;
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'spike-'));
  arq = path.join(dir, 'v.db');
});
afterEach(() => fs.rmSync(dir, { recursive: true, force: true }));

describe('criptografia', () => {
  it('lê com a chave certa, falha com a errada, e o disco não tem texto legível', () => {
    const chave = gerarChave();
    let db = abrir(arq, chave);
    migrar(db, [M1]);
    db.prepare('INSERT INTO cliente VALUES (?, ?)').run('1', 'Maria Aparecida Valor-Conhecido');
    db.pragma('wal_checkpoint(TRUNCATE)');
    db.close();
    db = abrir(arq, chave);
    expect(db.prepare('SELECT nome FROM cliente').get().nome).toBe(
      'Maria Aparecida Valor-Conhecido',
    );
    db.close();
    expect(() => abrir(arq, gerarChave())).toThrowError(/chave errada/);
    const bytes = fs.readFileSync(arq);
    expect(bytes.includes(Buffer.from('Valor-Conhecido'))).toBe(false);
    expect(bytes.subarray(0, 15).toString()).not.toBe('SQLite format 3');
  });

  it('parâmetros do cifrador', () => {
    const db = abrir(arq, gerarChave());
    const p = {};
    for (const n of [
      'cipher',
      'kdf_iter',
      'hmac_algorithm',
      'kdf_algorithm',
      'cipher_page_size',
      'page_size',
    ]) {
      try {
        p[n] = db.pragma(n, { simple: true });
      } catch {
        p[n] = '?';
      }
    }
    console.log('PARAMS', JSON.stringify(p), db.prepare('select sqlite_version() v').get().v);
    expect(p.cipher).toBe('sqlcipher');
    db.close();
  });
});

describe('migrações', () => {
  it('aplica 2 migrações, é idempotente e recusa migração editada', () => {
    const db = abrir(arq, gerarChave());
    expect(migrar(db, [M1])).toBe(1);
    expect(migrar(db, [M1, M2])).toBe(2);
    expect(migrar(db, [M1, M2])).toBe(2);
    expect(() => migrar(db, [{ ...M1, sql: M1.sql + ' ' }, M2])).toThrowError(/foi editada/);
    db.close();
  });
});

describe('desempenho e atomicidade', () => {
  it('10 mil linhas em transação, leitura com índice, WAL, reabertura', () => {
    const chave = gerarChave();
    let t = performance.now();
    let db = abrir(arq, chave);
    const tAbrir = performance.now() - t;
    migrar(db, [M1, M2]);
    const ins = db.prepare('INSERT INTO venda VALUES (?, ?)');
    t = performance.now();
    db.transaction(() => {
      for (let i = 0; i < 10000; i++) ins.run(`id-${i}`, i / 100);
    })();
    const tIns = performance.now() - t;
    t = performance.now();
    const ler = db.prepare('SELECT count(*) c FROM venda WHERE total BETWEEN ? AND ?');
    let n = 0;
    for (let i = 0; i < 1000; i++) n += ler.get(i / 10, i / 10 + 0.5).c;
    const tLer = performance.now() - t;
    expect(db.pragma('journal_mode', { simple: true })).toBe('wal');
    db.close();
    t = performance.now();
    db = abrir(arq, chave);
    const tReabrir = performance.now() - t;
    expect(db.prepare('SELECT count(*) c FROM venda').get().c).toBe(10000);
    db.close();
    console.log(
      'TEMPOS',
      JSON.stringify({
        abrir_novo_ms: +tAbrir.toFixed(1),
        insert10k_ms: +tIns.toFixed(1),
        ler1000_ms: +tLer.toFixed(1),
        reabrir_ms: +tReabrir.toFixed(1),
        n,
      }),
    );
  });

  it('processo morto no meio de uma transação não deixa nada pela metade', () => {
    const chave = gerarChave();
    const db = abrir(arq, chave);
    migrar(db, [M1]);
    db.prepare('INSERT INTO cliente VALUES (?, ?)').run('antes', 'ja gravado');
    db.close();
    const roteiro = `
      const { abrir } = require(${JSON.stringify(path.resolve('app/banco.cjs'))});
      const db = abrir(${JSON.stringify(arq)}, ${JSON.stringify(chave)});
      db.exec('BEGIN');
      const p = db.prepare('INSERT INTO cliente VALUES (?, ?)');
      for (let i = 0; i < 5000; i++) p.run('x' + i, 'n');
      process.kill(process.pid, 'SIGKILL');`;
    spawnSync(process.execPath, ['-e', roteiro]);
    const db2 = abrir(arq, chave);
    expect(db2.prepare('SELECT count(*) c FROM cliente').get().c).toBe(1);
    expect(db2.pragma('integrity_check', { simple: true })).toBe('ok');
    db2.close();
  });
});

describe('cofre da chave', () => {
  const cofre = {
    disponivel: () => true,
    cifrar: (t) => Buffer.from(t).reverse(),
    decifrar: (b) => Buffer.from(b).reverse().toString(),
  };
  it('gera na primeira vez, relê depois e recusa se o arquivo da chave sumir', async () => {
    const arqChave = path.join(dir, 'chave.bin');
    const c1 = await chaveDoCofre(arqChave, arq, cofre);
    expect(c1).toMatch(/^[0-9a-f]{64}$/);
    expect(fs.readFileSync(arqChave).toString()).not.toContain(c1);
    abrir(arq, c1).close();
    expect(await chaveDoCofre(arqChave, arq, cofre)).toBe(c1);
    fs.rmSync(arqChave);
    await expect(chaveDoCofre(arqChave, arq, cofre)).rejects.toThrowError(/sumiu/);
  });
  it('cofre indisponível', async () => {
    await expect(
      chaveDoCofre(path.join(dir, 'c'), arq, { ...cofre, disponivel: () => false }),
    ).rejects.toThrowError(/cofre/);
  });
});
