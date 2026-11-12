import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const compose = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'ambiente',
  'docker-compose.yml',
);

/**
 * Leitura do PostgreSQL das duas pontas, **só para conferir** (o oráculo e as contagens). Usa o `psql` de dentro do
 * contêiner, sem dependência nova. A consulta é envolvida num `json_agg`, então a resposta vem como JSON. Nunca grava:
 * a transação é somente leitura.
 */
export function consultar<T = Record<string, unknown>>(sql: string): Promise<T[]> {
  const envolvida = `BEGIN TRANSACTION READ ONLY; SELECT coalesce(json_agg(t), '[]'::json) FROM (${sql}) t; COMMIT;`;
  return new Promise((ok, erro) => {
    const p = spawn(
      'docker',
      [
        'compose',
        '-f',
        compose,
        'exec',
        '-T',
        'postgres',
        'psql',
        '-U',
        'sync_admin',
        '-d',
        'corona_sync',
        '-At',
        '-v',
        'ON_ERROR_STOP=1',
        '-c',
        envolvida,
      ],
      { stdio: ['ignore', 'pipe', 'pipe'] },
    );
    const saida: Buffer[] = [];
    const erros: Buffer[] = [];
    p.stdout.on('data', (b: Buffer) => saida.push(b));
    p.stderr.on('data', (b: Buffer) => erros.push(b));
    p.on('error', erro);
    p.on('close', (codigo) => {
      if (codigo !== 0) {
        erro(new Error(`psql falhou (${codigo}): ${Buffer.concat(erros).toString('utf8')}`));
        return;
      }
      // Saída: "BEGIN", o JSON e "COMMIT", uma por linha.
      const linhas = Buffer.concat(saida).toString('utf8').split(/\r?\n/);
      const json = linhas.find((l) => l.startsWith('['));
      if (!json) {
        erro(new Error(`psql não devolveu JSON: ${linhas.join(' | ')}`));
        return;
      }
      ok(JSON.parse(json) as T[]);
    });
  });
}

/** Uma consulta que devolve um número só. */
export async function contar(sql: string): Promise<number> {
  const [linha] = await consultar<{ n: number }>(`SELECT (${sql})::int AS n`);
  return linha?.n ?? 0;
}

/** Texto seguro dentro de aspas simples do SQL (só ids e textos do próprio teste). */
export function texto(valor: string): string {
  return `'${valor.replaceAll("'", "''")}'`;
}
