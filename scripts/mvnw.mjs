// Roda o Maven wrapper dentro de backend/, no Windows (mvnw.cmd) e no Linux/macOS (./mvnw).
// Uso: node scripts/mvnw.mjs spring-boot:run
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const backend = join(dirname(fileURLToPath(import.meta.url)), '..', 'backend');
const windows = process.platform === 'win32';

const resultado = spawnSync(windows ? '.\\mvnw.cmd' : './mvnw', process.argv.slice(2), {
  cwd: backend,
  stdio: 'inherit',
  // O Node recusa abrir arquivos .cmd sem shell no Windows.
  shell: windows,
});

if (resultado.error) {
  console.error(`Não foi possível rodar o Maven: ${resultado.error.message}`);
  process.exit(1);
}
process.exit(resultado.status ?? 1);
