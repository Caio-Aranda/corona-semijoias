import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createPackage } from '@electron/asar';
import {
  acharPacoteDoVendas,
  caminhosParaVarrer,
  PASTAS_COMPARTILHADAS,
  varrer,
} from './varrer-pacote.mjs';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'varrer-pacote.mjs');
const temporarias = [];
after(() => temporarias.forEach((p) => rmSync(p, { recursive: true, force: true })));

function pastaNova() {
  const pasta = mkdtempSync(join(tmpdir(), 'corona-barreira-teste-'));
  temporarias.push(pasta);
  return pasta;
}

/** Monta um repositório falso com um app.asar em apps/seller-app/release/<nome>/resources. */
async function repositorioCom(pacotes, conteudo = 'console.log("Corona Vendas");') {
  const raiz = pastaNova();
  for (const p of PASTAS_COMPARTILHADAS) mkdirSync(join(raiz, p), { recursive: true });
  const origem = join(raiz, 'origem');
  mkdirSync(origem);
  writeFileSync(join(origem, 'index.js'), conteudo);
  for (const [nome, quando] of pacotes) {
    const pasta = join(raiz, 'apps', 'seller-app', 'release', nome, 'resources');
    mkdirSync(pasta, { recursive: true });
    await createPackage(origem, join(pasta, 'app.asar'));
    if (quando) utimesSync(join(pasta, 'app.asar'), quando, quando);
  }
  return raiz;
}

test('sem argumentos, acha o app.asar do Windows e varre também as pastas compartilhadas', async () => {
  const raiz = await repositorioCom([['win-unpacked']]);
  const caminhos = caminhosParaVarrer([], raiz);
  assert.deepEqual(caminhos, [
    join(raiz, 'apps', 'seller-app', 'release', 'win-unpacked', 'resources', 'app.asar'),
    ...PASTAS_COMPARTILHADAS.map((p) => join(raiz, p)),
  ]);
});

test('acha o do Linux e, com os dois, fica com o mais novo', async () => {
  const raiz = await repositorioCom([
    ['win-unpacked', new Date('2026-01-01')],
    ['linux-unpacked', new Date('2026-02-01')],
  ]);
  assert.equal(
    acharPacoteDoVendas(raiz),
    join(raiz, 'apps', 'seller-app', 'release', 'linux-unpacked', 'resources', 'app.asar'),
  );
});

test('com argumentos, varre só o que foi passado', () => {
  assert.deepEqual(caminhosParaVarrer(['a.asar', 'pasta'], pastaNova()), ['a.asar', 'pasta']);
});

test('não achou o pacote: erro dizendo para rodar o seller:empacotar', () => {
  const raiz = pastaNova();
  assert.equal(acharPacoteDoVendas(raiz), null);
  assert.throws(() => caminhosParaVarrer([], raiz), /npm run seller:empacotar/);
});

test('o script sem pacote sai com erro e a mensagem simples', () => {
  const raiz = pastaNova();
  const r = spawnSync(process.execPath, [SCRIPT], {
    encoding: 'utf8',
    env: { ...process.env, CORONA_RAIZ_DO_REPOSITORIO: raiz },
  });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /npm run seller:empacotar/);
});

test('acha o marcador proibido dentro do app.asar', async () => {
  const raiz = await repositorioCom([['win-unpacked']], 'fetch("/api/dona/produtos");');
  const achados = varrer(caminhosParaVarrer([], raiz), ['/api/dona']);
  assert.equal(achados.length, 1);
  assert.equal(achados[0].marcador, '/api/dona');
  assert.match(achados[0].arquivo, /app\.asar!\/index\.js$/);
});

test('o script sem argumentos sai com erro quando o pacote leva código da dona', async () => {
  const raiz = await repositorioCom([['win-unpacked']], 'const precoCusto = 1;');
  const r = spawnSync(process.execPath, [SCRIPT], {
    encoding: 'utf8',
    env: { ...process.env, CORONA_RAIZ_DO_REPOSITORIO: raiz },
  });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /PROIBIDO: "precoCusto"/);
});

test('o script sem argumentos passa limpo com um pacote limpo', async () => {
  const raiz = await repositorioCom([['win-unpacked']]);
  const r = spawnSync(process.execPath, [SCRIPT], {
    encoding: 'utf8',
    env: { ...process.env, CORONA_RAIZ_DO_REPOSITORIO: raiz },
  });
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /Varredura limpa/);
});
