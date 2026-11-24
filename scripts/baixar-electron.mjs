// Baixa o Electron depois do `npm ci` / `npm install` (postinstall da raiz).
// Desde o Electron 45.4, o pacote `electron` não tem mais script de instalação: o programa só é baixado por
// `install-electron`. Sem ele, `electron-builder` falha em `node_modules/electron/dist` ("electronDist does not exist").
// Respeita ELECTRON_SKIP_BINARY_DOWNLOAD=1 (o CI usa) e não faz nada se o programa já está lá.
import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';

if (process.env.ELECTRON_SKIP_BINARY_DOWNLOAD === '1') {
  console.log('Electron: download pulado (ELECTRON_SKIP_BINARY_DOWNLOAD=1).');
  process.exit(0);
}

const require = createRequire(import.meta.url);
let pasta;
try {
  pasta = dirname(require.resolve('electron/package.json'));
} catch {
  // Instalação sem o Electron (por exemplo, só o backend): nada a fazer.
  process.exit(0);
}

if (existsSync(join(pasta, 'dist')) && existsSync(join(pasta, 'path.txt'))) {
  process.exit(0);
}

console.log('Electron: baixando o programa (uma vez só)...');
const r = spawnSync(process.execPath, [join(pasta, 'install.js')], { stdio: 'inherit' });
if (r.status !== 0) {
  console.error(
    'Não deu para baixar o Electron. Confira a internet e rode de novo: node node_modules/electron/install.js',
  );
  process.exit(r.status ?? 1);
}
