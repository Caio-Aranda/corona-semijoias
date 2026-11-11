import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { mkdirSync, readdirSync, rmSync, createWriteStream } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PORTA_DA_API, URL_DA_API, VARIAVEIS_DA_API } from './ambiente';

const aqui = dirname(fileURLToPath(import.meta.url));
const raiz = resolve(aqui, '..');
const windows = process.platform === 'win32';
export const PASTA_DE_RESULTADOS = join(aqui, 'resultados');

function rodar(
  comando: string,
  args: string[],
  opcoes: { cwd?: string; env?: NodeJS.ProcessEnv } = {},
): void {
  const r = spawnSync(comando, args, {
    cwd: opcoes.cwd ?? raiz,
    env: opcoes.env ?? process.env,
    stdio: 'inherit',
    shell: windows,
  });
  if (r.status !== 0) throw new Error(`Falhou: ${comando} ${args.join(' ')}`);
}

async function esperarSaude(api: ChildProcess): Promise<void> {
  const limite = Date.now() + 180_000;
  while (Date.now() < limite) {
    if (api.exitCode !== null)
      throw new Error('A API de teste caiu ao subir. Veja testes-e2e/resultados/api.log.');
    try {
      const r = await fetch(`${URL_DA_API}/actuator/health`);
      if (r.ok) return;
    } catch {
      // ainda subindo
    }
    await new Promise((ok) => setTimeout(ok, 1500));
  }
  throw new Error(
    'A API de teste não ficou de pé em 3 minutos. Veja testes-e2e/resultados/api.log.',
  );
}

/**
 * Sobe o ambiente isolado do e2e: PostgreSQL descartável (porta 5499, projeto "corona-e2e", sem volume), a API
 * (porta 8099) e o Corona Gestão compilado apontando para ela. Devolve a função que desmonta tudo no fim.
 */
export default async function preparar(): Promise<() => void> {
  rmSync(PASTA_DE_RESULTADOS, { recursive: true, force: true });
  mkdirSync(PASTA_DE_RESULTADOS, { recursive: true });
  const compose = ['compose', '-f', join(aqui, 'docker-compose.yml')];

  // Começa sempre de um banco vazio.
  spawnSync('docker', [...compose, 'down', '-v'], { stdio: 'ignore', shell: windows });
  rodar('docker', [...compose, 'up', '-d', '--wait']);

  rodar('node', ['scripts/mvnw.mjs', '-B', '-q', '-DskipTests', 'package']);
  const jar = readdirSync(join(raiz, 'backend', 'target')).find(
    (f) => /^corona-api-.*\.jar$/.test(f) && !f.endsWith('.original'),
  );
  if (!jar) throw new Error('Não achei o jar da API em backend/target.');

  const log = createWriteStream(join(PASTA_DE_RESULTADOS, 'api.log'));
  const api = spawn(
    'java',
    ['-jar', join(raiz, 'backend', 'target', jar), '--spring.profiles.active=dev'],
    {
      cwd: join(raiz, 'backend'),
      env: { ...process.env, ...VARIAVEIS_DA_API },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  api.stdout?.pipe(log);
  api.stderr?.pipe(log);
  await esperarSaude(api);

  // O app é compilado com o endereço da API de teste (o Vite embute MAIN_VITE_*).
  rodar('npm', ['run', 'build', '-w', '@corona/owner-app'], {
    env: { ...process.env, MAIN_VITE_CORONA_API_URL: URL_DA_API },
  });
  console.log(
    `\n[e2e] API de teste em ${URL_DA_API} (porta ${PORTA_DA_API}), banco corona_e2e na porta 5499.\n`,
  );

  return () => {
    if (windows && api.pid)
      spawnSync('taskkill', ['/T', '/F', '/PID', String(api.pid)], { stdio: 'ignore' });
    else api.kill('SIGTERM');
    spawnSync('docker', [...compose, 'down', '-v'], { stdio: 'ignore', shell: windows });
    rmSync(join(raiz, 'backend', 'dados', 'e2e-arquivos'), { recursive: true, force: true });
  };
}
