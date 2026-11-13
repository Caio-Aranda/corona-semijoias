import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { esperarAte } from './esperar';

/**
 * A "máquina do tempo" das duas pontas (tarefa 6.11, plano E1). A API corrige e limita toda hora que vem do computador:
 * a venda do teste é sempre "de hoje" para o servidor, e ele nunca acharia nada fora do prazo de devolução. Aqui a gente
 * **envelhece uma venda já gravada** no banco descartável: só `vendas.vendida_em`, numa transação que desliga apenas o
 * gatilho de conteúdo da venda (DDL do PostgreSQL é transacional: ninguém vê o gatilho desligado, e um erro desfaz tudo).
 * Os outros gatilhos continuam ligados, então a versão e o `sync_seq` sobem e a data nova chega ao computador pelo
 * recebimento, como na vida real.
 *
 * É o único ponto do `testes-sync` que grava no banco do servidor, e só a data da venda. Nunca toca o banco de
 * desenvolvimento nem o código da API.
 */

const DIA_MS = 86_400_000;
const BRASILIA_MS = 3 * 3_600_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const compose = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'ambiente',
  'docker-compose.yml',
);

/** O meio-dia de Brasília (15:00 UTC) de `dias` dias atrás, contando o "hoje" de Brasília em `agoraMs`. */
export function meioDiaDeBrasiliaHa(dias: number, agoraMs: number): number {
  const hojeEmBrasilia = new Date(agoraMs - BRASILIA_MS);
  return Date.UTC(
    hojeEmBrasilia.getUTCFullYear(),
    hojeEmBrasilia.getUTCMonth(),
    hojeEmBrasilia.getUTCDate() - dias,
    15,
    0,
    0,
  );
}

/** Quanto falta (ms) para a próxima meia-noite de Brasília (03:00 UTC). Exatamente 03:00 conta como o dia novo. */
export function faltaParaAViradaDeBrasilia(agoraMs: number): number {
  const proxima = Math.floor((agoraMs - BRASILIA_MS) / DIA_MS) * DIA_MS + DIA_MS + BRASILIA_MS;
  return proxima - agoraMs;
}

/** O SQL da transação que envelhece a venda (só o id, que tem de ser UUID, e a data entram no texto). */
export function sqlDeEnvelhecerVenda(vendaId: string, novaData: Date): string {
  if (!UUID.test(vendaId))
    throw new Error(`Id de venda inválido para a máquina do tempo: ${vendaId}`);
  return [
    'BEGIN;',
    'ALTER TABLE corona.vendas DISABLE TRIGGER vendas_conteudo_imutavel;',
    `DO $$ BEGIN UPDATE corona.vendas SET vendida_em = '${novaData.toISOString()}' WHERE id = '${vendaId}'; IF NOT FOUND THEN RAISE EXCEPTION 'venda não encontrada'; END IF; END $$;`,
    'ALTER TABLE corona.vendas ENABLE TRIGGER vendas_conteudo_imutavel;',
    'COMMIT;',
  ].join('\n');
}

function rodarSql(sql: string): Promise<void> {
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
        '-v',
        'ON_ERROR_STOP=1',
        '-q',
      ],
      { stdio: ['pipe', 'pipe', 'pipe'] },
    );
    const erros: Buffer[] = [];
    p.stderr.on('data', (b: Buffer) => erros.push(b));
    p.on('error', erro);
    p.on('close', (codigo) =>
      codigo === 0
        ? ok()
        : erro(new Error(`psql falhou (${codigo}): ${Buffer.concat(erros).toString('utf8')}`)),
    );
    p.stdin.end(sql);
  });
}

/** Perto da meia-noite de Brasília o "hoje" muda no meio do caso: espera a virada passar (dúvida E2). */
export async function esperarForaDaVirada(margemMs = 10 * 60_000): Promise<void> {
  if (faltaParaAViradaDeBrasilia(Date.now()) >= margemMs) return;
  const espera = faltaParaAViradaDeBrasilia(Date.now()) + 5_000;
  console.log(
    `Máquina do tempo: perto da virada do dia em Brasília, esperando ${Math.ceil(espera / 1000)} s.`,
  );
  const fim = Date.now() + espera;
  await esperarAte(() => Date.now() >= fim, {
    limiteMs: espera + 30_000,
    intervaloMs: 1_000,
    descricao: 'a virada do dia em Brasília',
  });
}

/**
 * Deixa a venda com `dias` dias de idade (meio-dia de Brasília). Espera a virada do dia se estiver perto e devolve a data
 * nova. A venda tem de já estar no servidor.
 */
export async function envelhecerVenda(vendaId: string, dias: number): Promise<Date> {
  await esperarForaDaVirada();
  const data = new Date(meioDiaDeBrasiliaHa(dias, Date.now()));
  await rodarSql(sqlDeEnvelhecerVenda(vendaId, data));
  return data;
}
