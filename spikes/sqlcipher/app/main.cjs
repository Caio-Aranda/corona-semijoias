// Roda DENTRO do Electron (processo principal): cofre real (safeStorage) + banco cifrado.
const { app, safeStorage } = require('electron');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { abrir, migrar, chaveDoCofre } = require('./banco.cjs');

app.whenReady().then(async () => {
  const r = {
    electron: process.versions.electron,
    node: process.versions.node,
    abi: process.versions.modules,
    empacotado: app.isPackaged,
  };
  try {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'spike-el-'));
    const banco = path.join(dir, 'v.db');
    const arqChave = path.join(dir, 'chave.bin');
    const cofre = {
      disponivel: () => safeStorage.isAsyncEncryptionAvailable(),
      cifrar: async (t) => safeStorage.encryptStringAsync(t),
      decifrar: async (b) => (await safeStorage.decryptStringAsync(b)).result,
    };
    r.cofreDisponivel = await cofre.disponivel();
    const chave = await chaveDoCofre(arqChave, banco, cofre);
    r.chaveNoDiscoEmClaro = fs.readFileSync(arqChave).includes(Buffer.from(chave));
    let db = abrir(banco, chave);
    r.versao = migrar(db, [
      { versao: 1, sql: 'CREATE TABLE cliente (id TEXT PRIMARY KEY, nome TEXT NOT NULL)' },
      { versao: 2, sql: 'CREATE TABLE venda (id TEXT PRIMARY KEY, total NUMERIC NOT NULL)' },
    ]);
    db.prepare('INSERT INTO cliente VALUES (?, ?)').run('1', 'Valor-Conhecido-Electron');
    db.pragma('wal_checkpoint(TRUNCATE)');
    db.close();
    db = abrir(banco, await chaveDoCofre(arqChave, banco, cofre));
    r.leitura = db.prepare('SELECT nome FROM cliente').get().nome;
    db.close();
    r.textoLegivelNoDisco = fs.readFileSync(banco).includes(Buffer.from('Valor-Conhecido'));
    try {
      abrir(banco, '0'.repeat(64));
      r.chaveErrada = 'ABRIU (ruim)';
    } catch (e) {
      r.chaveErrada = e.message;
    }
    fs.rmSync(dir, { recursive: true, force: true });
    r.ok =
      r.leitura === 'Valor-Conhecido-Electron' && !r.textoLegivelNoDisco && !r.chaveNoDiscoEmClaro;
  } catch (e) {
    r.ok = false;
    r.erro = String(e && e.stack);
  }
  fs.writeFileSync(
    process.env.SPIKE_SAIDA || path.join(os.tmpdir(), 'spike-saida.json'),
    JSON.stringify(r, null, 2),
  );
  app.exit(r.ok ? 0 : 1);
});
