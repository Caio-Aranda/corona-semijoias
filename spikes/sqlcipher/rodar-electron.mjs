// Roda o cenário dentro do Electron 45 real (precisa de janela de sessão do Windows; não roda no CI).
//   node rodar-electron.mjs               → Electron direto (ABI do Electron)
//   node rodar-electron.mjs --empacotar   → electron-builder --dir e roda o .exe empacotado (asar + asarUnpack)
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const raiz = path.resolve(aqui, '../..');
const saida = path.join(tmpdir(), 'spike-saida.json');
rmSync(saida, { force: true });
const env = { ...process.env, SPIKE_SAIDA: saida };
delete env.ELECTRON_RUN_AS_NODE;

let r;
if (process.argv.includes('--empacotar')) {
  const bld = spawnSync(
    process.execPath,
    [
      path.join(raiz, 'node_modules/electron-builder/cli.js'),
      '--dir',
      '--config',
      'electron-builder.yml',
    ],
    { cwd: aqui, stdio: 'inherit', env },
  );
  if (bld.status !== 0) process.exit(bld.status ?? 1);
  const exe = path.join(aqui, 'release/win-unpacked/Spike SQLCipher.exe');
  r = spawnSync(exe, [], { env, stdio: 'inherit' });
} else {
  r = spawnSync(path.join(raiz, 'node_modules/electron/dist/electron.exe'), [aqui], {
    env,
    stdio: 'inherit',
  });
}
console.log(existsSync(saida) ? readFileSync(saida, 'utf8') : 'sem saída');
process.exit(r.status ?? 1);
