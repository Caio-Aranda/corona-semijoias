import { afterEach, describe, expect, it } from 'vitest';
import { abrirBancoLocal, type BancoAberto } from '../banco/banco-local';
import { CHAVE_DOS_EXEMPLOS, abrirSemMigrar, copiarExemplo } from '../banco/testes/exemplos';
import { aplicarLote } from './aplicador-do-pull';
import { numeroDaNota, numeroDoRecibo } from './consignado-comum';
import { proximaSequencia } from './operacoes-do-consignado';
import { criarBancoDeTeste, idDeTeste, type BancoDeTeste } from './testes/banco-de-teste';

/**
 * Numeração separada de notas e recibos (tarefa 9.8; ADR-0016, D2): cada tipo tem contador próprio na semana, o recibo
 * aparece com o prefixo "R ". Banco de verdade (SQLCipher, arquivo).
 */
const limpar: (() => void)[] = [];
afterEach(() => {
  for (const f of limpar.splice(0).reverse()) f();
});

const semana = idDeTeste(1);

function banco(): BancoDeTeste {
  const b = criarBancoDeTeste();
  limpar.push(() => b.fechar());
  return b;
}

function receberNumeros(db: BancoAberto, notasAte: number, recibosAte: number): void {
  const linhas = [
    {
      tabela: 'semanas_vendedor' as const,
      id: semana,
      version: 1,
      deletedAt: null,
      campos: { numero: 65, situacao: 'ABERTA', aberta_em: '2026-11-10T10:00:00.000Z' },
    },
  ];
  for (let i = 1; i <= notasAte; i += 1)
    linhas.push({
      tabela: 'notas_consignado' as never,
      id: idDeTeste(2),
      version: 1,
      deletedAt: null,
      campos: {
        semana_id: semana,
        sequencia: i,
        cliente_id: idDeTeste(3),
        situacao: 'EM_ABERTO',
      } as never,
    });
  for (let i = 1; i <= recibosAte; i += 1)
    linhas.push({
      tabela: 'recibos_consignado' as never,
      id: idDeTeste(4),
      version: 1,
      deletedAt: null,
      campos: { semana_id: semana, sequencia: i, cliente_id: idDeTeste(3), total: '1.00' } as never,
    });
  aplicarLote(db, linhas, { cursor: 'c-1' });
}

describe('número do recibo', () => {
  it('o recibo leva o prefixo R e a nota não', () => {
    expect(numeroDaNota(65, 1)).toBe('0000065-01');
    expect(numeroDoRecibo(65, 1)).toBe('R 0000065-01');
    expect(numeroDoRecibo(65, 53)).toBe('R 0000065-53');
  });
});

describe('contador separado de notas e recibos', () => {
  it('nota, recibo, nota, recibo: cada tipo conta o seu (01, R 01, 02, R 02)', () => {
    const b = banco();
    expect(proximaSequencia(b.db, semana, 'NOTA')).toBe(1);
    expect(proximaSequencia(b.db, semana, 'RECIBO')).toBe(1);
    expect(proximaSequencia(b.db, semana, 'NOTA')).toBe(2);
    expect(proximaSequencia(b.db, semana, 'RECIBO')).toBe(2);
  });

  it('troca de computador: espelho com notas até 32 e recibos até 53, sem contador: nota 33 e recibo R 54', () => {
    const b = banco();
    receberNumeros(b.db, 32, 53);
    expect(b.db.prepare('SELECT count(*) AS n FROM contadores_da_semana').get()).toEqual({ n: 0 });
    expect(proximaSequencia(b.db, semana, 'NOTA')).toBe(33);
    expect(proximaSequencia(b.db, semana, 'RECIBO')).toBe(54);
  });
});

describe('migração local 11 (contador do recibo)', () => {
  it('banco v10 com contador compartilhado em 33 e recibos até 31: nota 33 e recibo 32, nenhum repetido do mesmo tipo', () => {
    const c = copiarExemplo(10);
    limpar.push(c.apagar);
    const antes = abrirSemMigrar(c.caminho, 10);
    antes
      .prepare('INSERT INTO contadores_da_semana (semana_id, proxima_sequencia) VALUES (?, ?)')
      .run(semana, 33);
    for (let i = 1; i <= 31; i += 1)
      antes
        .prepare(
          `INSERT INTO recibos_consignado (id, version, origem_local, semana_id, sequencia, cliente_id, total)
           VALUES (?, 1, 'SERVIDOR', ?, ?, ?, '1.00')`,
        )
        .run(idDeTeste(5), semana, i, idDeTeste(3));
    antes.close();
    const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
    limpar.push(() => db.close());
    const colunas = db.prepare('PRAGMA table_info(contadores_da_semana)').all() as {
      name: string;
    }[];
    expect(colunas.map((x) => x.name)).toContain('proxima_sequencia_recibo');
    expect(proximaSequencia(db, semana, 'NOTA')).toBe(33);
    expect(proximaSequencia(db, semana, 'RECIBO')).toBe(32);
    expect(proximaSequencia(db, semana, 'NOTA')).toBe(34);
    expect(proximaSequencia(db, semana, 'RECIBO')).toBe(33);
  });
});
