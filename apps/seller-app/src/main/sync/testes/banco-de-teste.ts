import { randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { abrirBancoLocal, type BancoAberto } from '../../banco/banco-local';

/**
 * Banco local de verdade (SQLCipher, arquivo numa pasta temporária) para os testes do motor. Nunca banco em memória:
 * a atomicidade e o disco cheio só valem no arquivo.
 */
export interface BancoDeTeste {
  db: BancoAberto;
  caminho: string;
  chave: Buffer;
  reabrir(): BancoAberto;
  fechar(): void;
}

export function criarBancoDeTeste(): BancoDeTeste {
  const pasta = mkdtempSync(join(tmpdir(), 'corona-sync-'));
  const caminho = join(pasta, 'banco.sqlite');
  const chave = randomBytes(32);
  const abertos: BancoAberto[] = [];
  const abrir = (): BancoAberto => {
    const db = abrirBancoLocal({ caminho, chave });
    abertos.push(db);
    return db;
  };
  const b: BancoDeTeste = {
    db: abrir(),
    caminho,
    chave,
    reabrir() {
      try {
        b.db.close();
      } catch {
        /* já fechado */
      }
      b.db = abrir();
      return b.db;
    },
    fechar() {
      for (const db of abertos.splice(0)) {
        try {
          db.close();
        } catch {
          /* já fechado */
        }
      }
      rmSync(pasta, { recursive: true, force: true });
    },
  };
  return b;
}

let contador = 0;
/** Um UUID de teste previsível e em ordem (o servidor ordena pelo texto do id). */
export function idDeTeste(prefixo = 0): string {
  contador += 1;
  const n = contador.toString(16).padStart(12, '0');
  return `0000000${prefixo.toString(16)}-0000-7000-8000-${n}`;
}

export function contar(db: BancoAberto, sql: string, ...parametros: unknown[]): number {
  return (db.prepare(`SELECT count(*) AS n FROM (${sql})`).get(...parametros) as { n: number }).n;
}
