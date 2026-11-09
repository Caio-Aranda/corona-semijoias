// Comando único dos clientes da API (tarefa 13, ADR-0003 item 3).
//   npm run api:clientes           -> regrava os openapi.json (pelo backend) e o código gerado
//   npm run api:clientes -- --so-gerar -> só regera o código a partir dos openapi.json versionados (CI)
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';

function rodar(comando, args, opcoes = {}) {
  const r = spawnSync(comando, args, { cwd: raiz, stdio: 'inherit', shell: windows, ...opcoes });
  if (r.error || r.status !== 0) {
    console.error(`Falhou: ${comando} ${args.join(' ')}`);
    process.exit(r.status ?? 1);
  }
}

if (!process.argv.includes('--so-gerar')) {
  // Precisa do Docker aberto (o teste do backend sobe o PostgreSQL de teste).
  rodar('node', [
    'scripts/mvnw.mjs',
    '-q',
    'test',
    '-Dtest=ClientesDoOpenApiEmDiaTest',
    '-Dcorona.openapi.gravar=true',
  ]);
}
for (const pacote of ['api-client-dona', 'api-client-vendedor']) {
  rodar('npm', ['run', 'gerar', '--workspace', `@corona/${pacote}`]);
}
