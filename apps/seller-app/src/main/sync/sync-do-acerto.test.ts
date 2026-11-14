import { afterEach, describe, expect, it } from 'vitest';
import { abrirBancoLocal, type BancoAberto, gravar } from '../banco/banco-local';
import {
  MIGRACOES,
  TABELAS_DA_RECARGA_DIRIGIDA_V4,
  TABELAS_DA_RECARGA_DIRIGIDA_V6,
  TABELAS_SINCRONIZAVEIS,
  VERSAO_DO_BANCO,
} from '../banco/migracoes';
import { CHAVE_DOS_EXEMPLOS, copiarExemplo } from '../banco/testes/exemplos';
import { aplicarLote, LoteRecusado } from './aplicador-do-pull';
import { CicloDeSincronizacao, tabelasDaRecargaDirigida } from './ciclo';
import { COLUNAS_DO_PULL } from './colunas';
import { CHAVES, gravarEstado, lerEstado } from './estado-sync';
import { criarTransporteDoSync } from './transporte-do-sync';
import { contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { ServidorFalso } from './testes/servidor-falso';

/**
 * O banco local e o motor do Corona Vendas com o acerto, parte 1 (tarefa 7.4; plano aprovado no PR #165): migração local 6
 * (`acertos` e `leituras_acerto`), colunas do pull e recarga dirigida das duas tabelas, sem apagar nada. SQLCipher de
 * verdade, arquivo de verdade, servidor falso de verdade. Só o motor e o banco: pedir o acerto e bipar são online (§7.4) e
 * as telas são da 7.11.
 */

const limpar: (() => void | Promise<void>)[] = [];
afterEach(async () => {
  for (const f of limpar.splice(0).reverse()) await f();
});

function colunasDe(db: BancoAberto, tabela: string): string[] {
  return (db.prepare(`PRAGMA table_info(${tabela})`).all() as { name: string }[]).map(
    (c) => c.name,
  );
}

async function servidor(): Promise<{ s: ServidorFalso; url: string }> {
  const s = new ServidorFalso();
  const url = await s.iniciar();
  limpar.push(() => s.parar());
  return { s, url };
}

function ciclo(db: BancoAberto, url: string): CicloDeSincronizacao {
  return new CicloDeSincronizacao({
    banco: () => db,
    transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
    comToken: (fn) => fn('token-de-teste'),
    agora: () => new Date('2026-11-14T12:00:00.000Z'),
    esperar: () => Promise.resolve(),
  });
}

describe('migração local 6', () => {
  it('banco v5 migra para a 6 sem perder nada, com as duas tabelas e as colunas da lista', () => {
    const c = copiarExemplo(5);
    limpar.push(c.apagar);
    const antes = abrirBancoLocal({
      caminho: c.caminho,
      chave: CHAVE_DOS_EXEMPLOS,
      migracoes: MIGRACOES.filter((m) => m.versao <= 5),
    });
    const contagem = (db: BancoAberto): Record<string, number> =>
      Object.fromEntries(
        ['vendas', 'contas_receber', 'remessas', 'clientes', 'provisorias'].map((t) => [
          t,
          contar(db, `SELECT 1 FROM ${t}`),
        ]),
      );
    const contagemAntes = contagem(antes);
    const outboxAntes = antes.prepare('SELECT * FROM outbox ORDER BY ordem').all();
    antes.close();

    const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
    limpar.push(() => {
      db.close();
    });
    expect(Number(db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);
    expect(VERSAO_DO_BANCO).toBe(6);
    expect(contagem(db)).toEqual(contagemAntes);
    expect(db.prepare('SELECT * FROM outbox ORDER BY ordem').all()).toEqual(outboxAntes);
    for (const t of ['acertos', 'leituras_acerto'] as const) {
      expect(colunasDe(db, t), t).toEqual(
        expect.arrayContaining(['origem_local', ...COLUNAS_DO_PULL[t]]),
      );
      expect(contar(db, `SELECT 1 FROM ${t}`)).toBe(0);
    }
  });

  it('a marca da recarga só nasce em banco que já tinha cursor, e soma à que ainda não terminou', () => {
    // Banco novo (as seis migrações de uma vez): carga inicial, sem marca.
    const novo = criarBancoDeTeste();
    limpar.push(() => novo.fechar());
    expect(lerEstado(novo.db, CHAVES.recargaDirigida)).toBeNull();
    expect(Number(novo.db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);

    // Banco na versão 5 sem cursor: também sem marca.
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    b.db.close();
    const semCursor = `${b.caminho}.v5`;
    abrirBancoLocal({
      caminho: semCursor,
      chave: b.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 5),
    }).close();
    const migrado = abrirBancoLocal({ caminho: semCursor, chave: b.chave });
    limpar.push(() => {
      migrado.close();
    });
    expect(lerEstado(migrado, CHAVES.recargaDirigida)).toBeNull();

    // Banco na versão 5 com cursor: marca só com as duas tabelas novas.
    const c = criarBancoDeTeste();
    limpar.push(() => c.fechar());
    c.db.close();
    const comCursor = `${c.caminho}.v5`;
    const v5 = abrirBancoLocal({
      caminho: comCursor,
      chave: c.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 5),
    });
    gravar(v5, () => gravarEstado(v5, CHAVES.cursor, '1:5'));
    v5.close();
    const m5 = abrirBancoLocal({ caminho: comCursor, chave: c.chave });
    limpar.push(() => {
      m5.close();
    });
    expect(tabelasDaRecargaDirigida(m5)).toEqual([...TABELAS_DA_RECARGA_DIRIGIDA_V6]);

    // Banco na versão 5 com cursor e uma marca antiga ainda de pé: as antigas primeiro, sem repetir.
    const d = criarBancoDeTeste();
    limpar.push(() => d.fechar());
    d.db.close();
    const comMarca = `${d.caminho}.v5`;
    const v5b = abrirBancoLocal({
      caminho: comMarca,
      chave: d.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 5),
    });
    gravar(v5b, () => {
      gravarEstado(v5b, CHAVES.cursor, '1:5');
      gravarEstado(v5b, CHAVES.recargaDirigida, JSON.stringify(['contas_receber', 'acertos']));
    });
    v5b.close();
    const m5b = abrirBancoLocal({ caminho: comMarca, chave: d.chave });
    limpar.push(() => {
      m5b.close();
    });
    expect(tabelasDaRecargaDirigida(m5b)).toEqual(['contas_receber', 'acertos', 'leituras_acerto']);
  });
});

describe('colunas do acerto no pull', () => {
  it('as duas tabelas novas estão na lista, com as colunas do servidor e sem custo nem quem fez', () => {
    expect(TABELAS_SINCRONIZAVEIS).toHaveLength(27);
    expect(TABELAS_SINCRONIZAVEIS).toEqual(expect.arrayContaining(['acertos', 'leituras_acerto']));
    expect(COLUNAS_DO_PULL.acertos).toEqual([
      'vendedor_id',
      'semana_id',
      'status',
      'pedido_pelo_vendedor',
      'iniciado_em',
      'desistido_em',
      'motivo_desistencia',
    ]);
    expect(COLUNAS_DO_PULL.leituras_acerto).toEqual([
      'acerto_id',
      'produto_id',
      'quantidade',
      'estado_peca',
      'lida_em',
      'pelo_vendedor',
      'dispositivo_id',
    ]);
    for (const t of ['acertos', 'leituras_acerto'] as const) {
      for (const c of COLUNAS_DO_PULL[t]) {
        expect(c, `${t}.${c}`).not.toMatch(/custo|fornecedor|_por$/);
      }
    }
    // A ligação maleta -> acerto nunca vai ao aplicativo.
    expect(COLUNAS_DO_PULL.remessas).not.toContain('acerto_id');
    expect(COLUNAS_DO_PULL.remessas).not.toContain('status_antes_do_acerto');
  });

  it('lote com o acerto e a leitura grava, com o booleano como 0/1', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const acerto = idDeTeste(5);
    aplicarLote(
      b.db,
      [
        {
          tabela: 'acertos',
          id: acerto,
          version: 1,
          deletedAt: null,
          campos: {
            vendedor_id: idDeTeste(1),
            semana_id: idDeTeste(2),
            status: 'EM_ACERTO',
            pedido_pelo_vendedor: false,
            iniciado_em: '2026-11-14T12:00:00Z',
          },
        },
        {
          tabela: 'leituras_acerto',
          id: idDeTeste(6),
          version: 1,
          deletedAt: null,
          campos: {
            acerto_id: acerto,
            produto_id: idDeTeste(3),
            quantidade: 2,
            estado_peca: 'BOA',
            lida_em: '2026-11-14T12:01:00Z',
            pelo_vendedor: true,
            dispositivo_id: idDeTeste(4),
          },
        },
      ],
      { cursor: 'c1' },
    );
    expect(b.db.prepare('SELECT status, pedido_pelo_vendedor FROM acertos').get()).toEqual({
      status: 'EM_ACERTO',
      pedido_pelo_vendedor: 0,
    });
    expect(b.db.prepare('SELECT quantidade, pelo_vendedor FROM leituras_acerto').get()).toEqual({
      quantidade: 2,
      pelo_vendedor: 1,
    });
  });

  it('coluna fora da lista recusa o lote inteiro, sem gravar nada', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    for (const coluna of ['iniciado_por', 'lida_por', 'desistido_por', 'acerto_id']) {
      expect(() =>
        aplicarLote(
          b.db,
          [
            {
              tabela: 'acertos',
              id: idDeTeste(5),
              version: 1,
              deletedAt: null,
              campos: { status: 'EM_ACERTO', [coluna]: idDeTeste(9) },
            },
            {
              tabela: 'leituras_acerto',
              id: idDeTeste(6),
              version: 1,
              deletedAt: null,
              campos: { acerto_id: idDeTeste(5), quantidade: 1 },
            },
          ],
          { cursor: 'c1' },
        ),
      ).toThrow(LoteRecusado);
    }
    expect(contar(b.db, 'SELECT 1 FROM acertos')).toBe(0);
    expect(contar(b.db, 'SELECT 1 FROM leituras_acerto')).toBe(0);
    expect(lerEstado(b.db, CHAVES.cursor)).toBeNull();
  });
});

describe('recarga dirigida depois da migração local 6', () => {
  it('banco v5 com cursor traz as duas tabelas, retoma depois de cair, não apaga nem duplica', async () => {
    const c = copiarExemplo(5);
    limpar.push(c.apagar);
    const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
    limpar.push(() => {
      db.close();
    });
    const { s, url } = await servidor();
    expect(tabelasDaRecargaDirigida(db)).toEqual([...TABELAS_DA_RECARGA_DIRIGIDA_V6]);
    const remessasAntes = contar(db, "SELECT 1 FROM remessas WHERE origem_local = 'SERVIDOR'");

    const acerto = idDeTeste(7);
    const leitura = idDeTeste(8);
    s.mudar({
      tabela: 'acertos',
      id: acerto,
      deletedAt: null,
      campos: {
        vendedor_id: idDeTeste(1),
        semana_id: idDeTeste(2),
        status: 'EM_ACERTO',
        pedido_pelo_vendedor: false,
        iniciado_em: '2026-11-14T12:00:00Z',
      },
    });
    s.mudar({
      tabela: 'leituras_acerto',
      id: leitura,
      deletedAt: null,
      campos: {
        acerto_id: acerto,
        produto_id: idDeTeste(3),
        quantidade: 3,
        estado_peca: 'BOA',
        pelo_vendedor: false,
      },
    });
    // O computador está em dia com o servidor nas demais tabelas (a conferência por resumo não acha diferença).
    for (const t of ['remessas', 'itens_remessa', 'clientes', 'vendas'] as const) {
      for (const l of db
        .prepare(`SELECT * FROM ${t} WHERE origem_local = 'SERVIDOR'`)
        .all() as Record<string, unknown>[]) {
        if (s.estado().has(`${t}:${l['id'] as string}`)) continue;
        s.registro.push({
          tabela: t,
          id: l['id'] as string,
          version: l['version'] as number,
          deletedAt: null,
          campos: Object.fromEntries(COLUNAS_DO_PULL[t].map((col) => [col, l[col] ?? null])),
        });
      }
    }
    gravar(db, () => gravarEstado(db, CHAVES.cursor, `${s.epoca}:${s.registro.length}`));

    // Primeiro ciclo: a 2ª tabela da marca cai. A 1ª já saiu da marca; nada foi apagado.
    s.cairNaTabela = { tabela: 'leituras_acerto', vezes: 1 };
    expect(await ciclo(db, url).executar()).toBe('falha');
    expect(tabelasDaRecargaDirigida(db)).toEqual(['leituras_acerto']);
    expect(contar(db, 'SELECT 1 FROM acertos WHERE id = ?', acerto)).toBe(1);
    expect(contar(db, "SELECT 1 FROM remessas WHERE origem_local = 'SERVIDOR'")).toBe(
      remessasAntes,
    );

    // Segundo ciclo: retoma, termina e a marca some.
    expect(await ciclo(db, url).executar()).toBe('ok');
    expect(lerEstado(db, CHAVES.recargaDirigida)).toBeNull();
    expect(contar(db, 'SELECT 1 FROM leituras_acerto WHERE id = ?', leitura)).toBe(1);
    expect(contar(db, 'SELECT 1 FROM acertos WHERE id = ?', acerto)).toBe(1);
    expect(contar(db, "SELECT 1 FROM remessas WHERE origem_local = 'SERVIDOR'")).toBe(
      remessasAntes,
    );
    expect(s.pedidosPara('/api/vendedor/sync/conferencia/diferencas')).toHaveLength(0);

    // Um ciclo a mais não recarrega nada.
    const antes = s
      .pedidosPara('/api/vendedor/sync/pull')
      .filter((p) => p.query.get('tabela')).length;
    expect(await ciclo(db, url).executar()).toBe('ok');
    expect(
      s.pedidosPara('/api/vendedor/sync/pull').filter((p) => p.query.get('tabela')),
    ).toHaveLength(antes);
  });

  it('banco novo faz a carga inicial e não faz recarga dirigida', async () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const { s, url } = await servidor();
    s.mudar({
      tabela: 'acertos',
      id: idDeTeste(7),
      deletedAt: null,
      campos: { vendedor_id: idDeTeste(1), status: 'EM_ACERTO' },
    });
    expect(await ciclo(b.db, url).executar()).toBe('ok');
    expect(contar(b.db, 'SELECT 1 FROM acertos')).toBe(1);
    expect(
      s.pedidosPara('/api/vendedor/sync/pull').filter((p) => p.query.get('tabela')),
    ).toHaveLength(0);
    expect(TABELAS_DA_RECARGA_DIRIGIDA_V4).toHaveLength(3);
  });
});
