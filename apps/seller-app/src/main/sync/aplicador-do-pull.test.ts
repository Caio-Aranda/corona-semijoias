import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { aplicarLote, LoteRecusado } from './aplicador-do-pull';
import { CHAVES, lerEstado } from './estado-sync';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';

/**
 * Aplicação do pull (tarefa 5.6; ADR-0013, itens 3 e 7). SQLCipher de verdade, arquivo de verdade.
 */
let b: BancoDeTeste;
beforeEach(() => {
  b = criarBancoDeTeste();
});
afterEach(() => b.fechar());

const produto = (
  id: string,
  version: number,
  nome = 'Brinco',
  extra: Record<string, unknown> = {},
) => ({
  tabela: 'produtos',
  id,
  version,
  deletedAt: null,
  campos: { nome, preco_venda: '12.30', ativo: true, ...extra },
});

const lerProduto = (id: string) =>
  b.db.prepare('SELECT * FROM produtos WHERE id = ?').get(id) as
    Record<string, unknown> | undefined;

describe('aplicação de cada linha', () => {
  it('grava a linha nova como SERVIDOR, com dinheiro em texto e booleano em 0/1', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [produto(id, 1)], { cursor: 'c1' });
    expect(lerProduto(id)).toMatchObject({
      version: 1,
      nome: 'Brinco',
      preco_venda: '12.30',
      ativo: 1,
      origem_local: 'SERVIDOR',
      deleted_at: null,
    });
    expect(
      b.db.prepare('SELECT typeof(preco_venda) AS t FROM produtos WHERE id = ?').get(id),
    ).toEqual({ t: 'text' });
  });

  it('version maior troca; igual ou menor é ignorada; repetida e fora de ordem são inofensivas', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [produto(id, 2, 'v2')], { cursor: 'c1' });
    const r = aplicarLote(b.db, [produto(id, 1, 'v1'), produto(id, 2, 'v2-outra')], {
      cursor: 'c2',
    });
    expect(r).toMatchObject({ gravadas: 0, ignoradas: 2 });
    expect(lerProduto(id)?.['nome']).toBe('v2');
    aplicarLote(b.db, [produto(id, 3, 'v3'), produto(id, 3, 'v3'), produto(id, 2, 'v2')], {
      cursor: 'c3',
    });
    expect(lerProduto(id)).toMatchObject({ version: 3, nome: 'v3' });
  });

  it('lápide marca deleted_at e nunca apaga a linha', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [produto(id, 1)], { cursor: 'c1' });
    aplicarLote(b.db, [{ ...produto(id, 2), deletedAt: '2026-11-12T10:00:00Z' }], { cursor: 'c2' });
    expect(lerProduto(id)).toMatchObject({ version: 2, deleted_at: '2026-11-12T10:00:00Z' });
  });

  it('cópia provisória com o mesmo id: o servidor vence, qualquer version, e ela deixa de ser provisória', () => {
    const id = idDeTeste();
    const op = idDeTeste();
    b.db
      .prepare(
        "INSERT INTO clientes (id, version, nome, origem_local) VALUES (?, 1, 'Do vendedor', 'PROVISORIO')",
      )
      .run(id);
    b.db
      .prepare("INSERT INTO provisorias (tabela, linha_id, operacao_id) VALUES ('clientes', ?, ?)")
      .run(id, op);
    aplicarLote(
      b.db,
      [{ tabela: 'clientes', id, version: 1, deletedAt: null, campos: { nome: 'Do servidor' } }],
      { cursor: 'c1' },
    );
    expect(b.db.prepare('SELECT nome, origem_local FROM clientes WHERE id = ?').get(id)).toEqual({
      nome: 'Do servidor',
      origem_local: 'SERVIDOR',
    });
    expect(contar(b.db, 'SELECT 1 FROM provisorias')).toBe(0);
  });

  it('linha de OUTRO id nunca toca numa provisória cuja operação ainda aguarda envio', () => {
    const remessa = idDeTeste();
    const produtoId = idDeTeste();
    const op = idDeTeste();
    const provisorio = idDeTeste();
    b.db
      .prepare(
        "INSERT INTO outbox (operacao_id, tipo, formato, dados, criado_em_local) VALUES (?, 'VENDA', 1, '{}', 'agora')",
      )
      .run(op);
    b.db
      .prepare(
        "INSERT INTO movimentos_remessa (id, version, remessa_id, produto_id, tipo, quantidade, venda_id, origem_local) VALUES (?, 1, ?, ?, 'VENDA', -1, ?, 'PROVISORIO')",
      )
      .run(provisorio, remessa, produtoId, op);
    b.db
      .prepare(
        "INSERT INTO provisorias (tabela, linha_id, operacao_id) VALUES ('movimentos_remessa', ?, ?)",
      )
      .run(provisorio, op);

    // O servidor manda o movimento da mesma venda, com o id dele.
    aplicarLote(
      b.db,
      [
        {
          tabela: 'movimentos_remessa',
          id: idDeTeste(),
          version: 1,
          deletedAt: null,
          campos: {
            remessa_id: remessa,
            produto_id: produtoId,
            tipo: 'VENDA',
            quantidade: -1,
            venda_id: op,
          },
        },
      ],
      { cursor: 'c1' },
    );
    expect(
      b.db.prepare('SELECT origem_local FROM movimentos_remessa WHERE id = ?').get(provisorio),
    ).toEqual({ origem_local: 'PROVISORIO' });
    expect(contar(b.db, 'SELECT 1 FROM provisorias WHERE operacao_id = ?', op)).toBe(1);
  });
});

describe('o lote inteiro é recusado quando algo não serve', () => {
  const casos: [string, unknown][] = [
    ['tabela desconhecida', { tabela: 'usuarios', id: idDeTeste(), version: 1, campos: {} }],
    ['coluna fora da lista', { ...produto(idDeTeste(), 1), campos: { nome: 'x', margem: '1.00' } }],
    ['coluna de custo', { ...produto(idDeTeste(), 1), campos: { nome: 'x', preco_custo: '1.00' } }],
    [
      'custo em outra tabela',
      { tabela: 'itens_remessa', id: idDeTeste(), version: 1, campos: { custo: '1' } },
    ],
    [
      'dinheiro em número quebrado',
      { ...produto(idDeTeste(), 1), campos: { nome: 'x', preco_venda: 12.3 } },
    ],
    ['objeto dentro de campo', { ...produto(idDeTeste(), 1), campos: { nome: { a: 1 } } }],
    ['version zero', produto(idDeTeste(), 0)],
    ['id que não é UUID', { ...produto('abc', 1) }],
  ];
  for (const [nome, ruim] of casos) {
    it(nome, () => {
      const boa = idDeTeste();
      expect(() => aplicarLote(b.db, [produto(boa, 1), ruim], { cursor: 'c-novo' })).toThrow(
        LoteRecusado,
      );
      expect(lerProduto(boa)).toBeUndefined();
      expect(lerEstado(b.db, CHAVES.cursor)).toBeNull();
    });
  }
});

describe('atomicidade', () => {
  it('o cursor só muda junto com o lote', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [produto(id, 1)], { cursor: 'c1' });
    expect(lerEstado(b.db, CHAVES.cursor)).toBe('c1');
    aplicarLote(b.db, [produto(idDeTeste(), 1)], { cursor: null });
    expect(lerEstado(b.db, CHAVES.cursor)).toBe('c1');
  });

  it('falha no meio do lote não deixa nenhuma linha nem o cursor', () => {
    aplicarLote(b.db, [produto(idDeTeste(), 1)], { cursor: 'c1' });
    const ids = [idDeTeste(), idDeTeste(), idDeTeste()];
    expect(() =>
      aplicarLote(
        b.db,
        ids.map((i) => produto(i, 1)),
        {
          cursor: 'c2',
          depoisDeCadaLinha: (i) => {
            if (i === 1) throw new Error('queda no meio');
          },
        },
      ),
    ).toThrow('queda no meio');
    for (const i of ids) expect(lerProduto(i)).toBeUndefined();
    expect(lerEstado(b.db, CHAVES.cursor)).toBe('c1');
  });

  it('processo morto no meio da aplicação: cada lote entrou inteiro com o seu cursor, ou não entrou', async () => {
    b.db.close();
    const aqui = dirname(fileURLToPath(import.meta.url));
    const exigir = createRequire(import.meta.url);
    const script = join(dirname(b.caminho), 'aplicador-filho.cjs');
    writeFileSync(
      script,
      `const { createJiti } = require(${JSON.stringify(exigir.resolve('jiti'))});
const jiti = createJiti(${JSON.stringify(join(aqui, 'filho.cjs'))});
(async () => {
  const { abrirBancoLocal } = await jiti.import(${JSON.stringify(join(aqui, '../banco/banco-local.ts'))});
  const { aplicarLote } = await jiti.import(${JSON.stringify(join(aqui, 'aplicador-do-pull.ts'))});
  const db = abrirBancoLocal({ caminho: ${JSON.stringify(b.caminho)}, chave: Buffer.from(${JSON.stringify(b.chave.toString('hex'))}, 'hex') });
  let n = 0;
  process.stdout.write('pronto\\n');
  for (let lote = 1; ; lote++) {
    const linhas = [];
    for (let i = 0; i < 40; i++) {
      n++;
      const id = '00000000-0000-7000-8000-' + n.toString(16).padStart(12, '0');
      linhas.push({ tabela: 'produtos', id, version: 1, deletedAt: null, campos: { nome: 'p' + n, preco_venda: '1.00' } });
    }
    aplicarLote(db, linhas, { cursor: 'lote-' + lote });
    if (lote % 20 === 0) db.pragma('wal_checkpoint(TRUNCATE)');
  }
})();`,
    );
    const filho = spawn(process.execPath, [script], { stdio: ['ignore', 'pipe', 'inherit'] });
    await new Promise<void>((resolver) => filho.stdout.once('data', () => resolver()));
    await new Promise((r) => setTimeout(r, 500));
    filho.kill('SIGKILL');
    await new Promise((r) => filho.once('exit', r));

    const db = b.reabrir();
    expect(db.pragma('integrity_check', { simple: true })).toBe('ok');
    const n = contar(db, 'SELECT 1 FROM produtos');
    expect(n).toBeGreaterThan(0);
    expect(n % 40).toBe(0);
    expect(lerEstado(db, CHAVES.cursor)).toBe(`lote-${n / 40}`);
  }, 60_000);
});
