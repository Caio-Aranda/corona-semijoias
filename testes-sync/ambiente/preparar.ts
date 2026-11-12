import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PORTA_DA_API, PORTA_DO_BANCO, URL_DA_API, VARIAVEIS_DA_API } from './ambiente';

const aqui = dirname(fileURLToPath(import.meta.url));
const raiz = resolve(aqui, '..', '..');
const windows = process.platform === 'win32';
export const PASTA_DE_RESULTADOS = join(aqui, '..', 'resultados');
/** A API roda nesta pasta: sem `.env` ao lado (o da raiz do projeto nunca é lido) e com os arquivos de teste dentro. */
const PASTA_DA_API = join(PASTA_DE_RESULTADOS, 'api');
const COMPOSE = ['compose', '-f', join(aqui, 'docker-compose.yml')];

function rodar(comando: string, args: string[], cwd = raiz): void {
  const r = spawnSync(comando, args, { cwd, stdio: 'inherit', shell: windows });
  if (r.status !== 0) throw new Error(`Falhou: ${comando} ${args.join(' ')}`);
}

async function portaLivre(porta: number): Promise<boolean> {
  try {
    await fetch(`http://127.0.0.1:${porta}/`, { signal: AbortSignal.timeout(1000) });
    return false;
  } catch (erro) {
    const causa = (erro as { cause?: { code?: string } }).cause;
    return causa?.code === 'ECONNREFUSED';
  }
}

async function esperarSaude(api: ChildProcess): Promise<void> {
  const limite = Date.now() + 180_000;
  while (Date.now() < limite) {
    if (api.exitCode !== null)
      throw new Error('A API de teste caiu ao subir. Veja testes-sync/resultados/api.log.');
    try {
      const r = await fetch(`${URL_DA_API}/actuator/health`);
      if (r.ok) return;
    } catch {
      // ainda subindo
    }
    await new Promise((ok) => setTimeout(ok, 1000));
  }
  throw new Error(
    'A API de teste não ficou de pé em 3 minutos. Veja testes-sync/resultados/api.log.',
  );
}

function acharJar(): string {
  const doAmbiente = process.env['CORONA_SYNC_JAR'];
  if (doAmbiente) {
    if (!existsSync(doAmbiente))
      throw new Error(`CORONA_SYNC_JAR aponta um arquivo que não existe: ${doAmbiente}`);
    return resolve(doAmbiente);
  }
  rodar('node', ['scripts/mvnw.mjs', '-B', '-q', '-DskipTests', 'package']);
  const jar = readdirSync(join(raiz, 'backend', 'target')).find(
    (f) => /^corona-api-.*\.jar$/.test(f) && !f.endsWith('.original'),
  );
  if (!jar) throw new Error('Não achei o jar da API em backend/target.');
  return join(raiz, 'backend', 'target', jar);
}

/**
 * Sobe o ambiente isolado das duas pontas (`globalSetup` do Vitest): PostgreSQL descartável (porta 5498, projeto
 * "corona-sync", sem volume) e a API de verdade (porta 8098). Devolve a função que desmonta tudo no fim.
 */
export default async function preparar(): Promise<() => void> {
  rmSync(PASTA_DE_RESULTADOS, { recursive: true, force: true });
  mkdirSync(PASTA_DA_API, { recursive: true });

  // Começa sempre de um banco vazio.
  spawnSync('docker', [...COMPOSE, 'down', '-v'], { stdio: 'ignore', shell: windows });
  for (const porta of [PORTA_DO_BANCO, PORTA_DA_API]) {
    if (!(await portaLivre(porta)))
      throw new Error(
        `A porta ${porta} já está em uso. Feche o que está nela (os testes de duas pontas usam 5498 e 8098).`,
      );
  }
  rodar('docker', [...COMPOSE, 'up', '-d', '--wait']);

  const jar = acharJar();
  const log = createWriteStream(join(PASTA_DE_RESULTADOS, 'api.log'));
  const api = spawn('java', ['-jar', jar], {
    cwd: PASTA_DA_API,
    env: { ...process.env, ...VARIAVEIS_DA_API },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  api.stdout?.pipe(log);
  api.stderr?.pipe(log);
  await esperarSaude(api);
  console.log(
    `\n[duas pontas] API de teste em ${URL_DA_API}, banco corona_sync na porta ${PORTA_DO_BANCO}.\n`,
  );

  return () => {
    if (windows && api.pid)
      spawnSync('taskkill', ['/T', '/F', '/PID', String(api.pid)], { stdio: 'ignore' });
    else api.kill('SIGTERM');
    spawnSync('docker', [...COMPOSE, 'down', '-v'], { stdio: 'ignore', shell: windows });
  };
}
