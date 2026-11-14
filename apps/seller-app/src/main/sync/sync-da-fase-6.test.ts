import { afterEach, describe, expect, it } from 'vitest';
import { abrirBancoLocal, type BancoAberto, gravar } from '../banco/banco-local';
import {
  MIGRACOES,
  TABELAS_DA_RECARGA_DIRIGIDA_V3,
  TABELAS_SINCRONIZAVEIS,
  VERSAO_DO_BANCO,
} from '../banco/migracoes';
import { abrirSemMigrar, CHAVE_DOS_EXEMPLOS, copiarExemplo } from '../banco/testes/exemplos';
import { aplicarLote, LoteRecusado } from './aplicador-do-pull';
import { CicloDeSincronizacao, tabelasDaRecargaDirigida } from './ciclo';
import { COLUNAS_DO_PULL } from './colunas';
import { CHAVES, gravarEstado, lerEstado } from './estado-sync';
import { desfazerOperacao } from './outbox';
import { criarTransporteDoSync } from './transporte-do-sync';
import { contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { ServidorFalso } from './testes/servidor-falso';

/**
 * O banco local e o motor do Corona Vendas com as tabelas da Fase 6 (tarefa 6.7; plano aprovado no PR #131, testes 20 a
 * 24 e o ponto 3 da aprovação): migração local 3, colunas, recarga dirigida sem apagar nada, provisórias das tabelas
 * novas. SQLCipher de verdade, arquivo de verdade, servidor falso de verdade (`node:http`).
 */

const NOVAS = [
  'devolucoes',
  'itens_devolucao',
  'autorizacoes_excecao',
  'itens_autorizacao',
  'trocas',
  'creditos_cliente',
  'usos_credito',
  'creditos_sem_saldo',
] as const;

/**
 * A marca da recarga de um banco da versão 2 que já tinha cursor, migrado pelo app atual: as onze da migração 3 e, depois,
 * as duas tabelas novas da migração 4 (a `pagamentos_cliente` já estava na marca).
 */
const MARCA_DO_BANCO_V2_COM_CURSOR = [
  ...TABELAS_DA_RECARGA_DIRIGIDA_V3,
  'contas_receber',
  'baixas_receber',
] as const;

const limpar: (() => void | Promise<void>)[] = [];
afterEach(async () => {
  for (const f of limpar.splice(0).reverse()) await f();
});

function abrirExemploMigrado(versao: number): { db: BancoAberto; caminho: string } {
  const c = copiarExemplo(versao);
  limpar.push(c.apagar);
  const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
  limpar.push(() => {
    db.close();
  });
  return { db, caminho: c.caminho };
}

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
    agora: () => new Date('2026-11-13T09:00:00.000Z'),
    esperar: () => Promise.resolve(),
  });
}

/** Uma linha de cada tabela nova, como o servidor manda (as colunas da lista). */
function linhasDasNovas(s: ServidorFalso, vendaId: string): Record<string, string> {
  const ids: Record<string, string> = {};
  for (const t of NOVAS) ids[t] = idDeTeste(9);
  s.mudar({
    tabela: 'devolucoes',
    id: ids['devolucoes']!,
    deletedAt: null,
    campos: {
      venda_id: vendaId,
      situacao: 'EM_REVISAO',
      valor_devolvido: '33.33',
      revisao_fora_do_prazo: true,
    },
  });
  s.mudar({
    tabela: 'itens_devolucao',
    id: ids['itens_devolucao']!,
    deletedAt: null,
    campos: {
      devolucao_id: ids['devolucoes'],
      quantidade: 1,
      valor: '33.33',
      estado_peca: 'BOA',
      destino_peca: 'MALETA',
    },
  });
  s.mudar({
    tabela: 'autorizacoes_excecao',
    id: ids['autorizacoes_excecao']!,
    deletedAt: null,
    campos: {
      venda_id: vendaId,
      situacao: 'APROVADA',
      valida_ate: '2026-11-20',
      valor_pedido: '33.33',
    },
  });
  s.mudar({
    tabela: 'itens_autorizacao',
    id: ids['itens_autorizacao']!,
    deletedAt: null,
    campos: { autorizacao_id: ids['autorizacoes_excecao'], quantidade: 1 },
  });
  s.mudar({
    tabela: 'trocas',
    id: ids['trocas']!,
    deletedAt: null,
    campos: { venda_origem_id: vendaId, situacao: 'ATIVA', diferenca: '-10.00' },
  });
  s.mudar({
    tabela: 'creditos_cliente',
    id: ids['creditos_cliente']!,
    deletedAt: null,
    campos: {
      cliente_id: idDeTeste(3),
      origem: 'TROCA',
      valor: '10.00',
      valor_utilizado: '0.00',
      situacao: 'ABERTO',
    },
  });
  s.mudar({
    tabela: 'usos_credito',
    id: ids['usos_credito']!,
    deletedAt: null,
    campos: { credito_id: ids['creditos_cliente'], tipo: 'USO', venda_id: vendaId, valor: '5.00' },
  });
  s.mudar({
    tabela: 'creditos_sem_saldo',
    id: ids['creditos_sem_saldo']!,
    deletedAt: null,
    campos: {
      venda_id: vendaId,
      pagamento_id: idDeTeste(8),
      credito_id: ids['creditos_cliente'],
      valor_pedido: '20.00',
      valor_usado: '5.00',
      situacao: 'EM_REVISAO',
    },
  });
  return ids;
}

// --- 20. migração local 3 ---

describe('migração local 3', () => {
  it('banco v2 com outbox e provisórias migra sem perder nada, com as tabelas e as colunas novas', () => {
    const c = copiarExemplo(2);
    limpar.push(c.apagar);
    const antes = abrirSemMigrar(c.caminho, 2);
    const contagem = (db: BancoAberto) =>
      Object.fromEntries(
        [
          'outbox',
          'provisorias',
          'vendas',
          'itens_venda',
          'movimentos_remessa',
          'pagamentos_cliente',
          'remessas',
          'clientes',
        ].map((t) => [t, contar(db, `SELECT 1 FROM ${t}`)]),
      );
    const contagemAntes = contagem(antes);
    const outboxAntes = antes.prepare('SELECT * FROM outbox ORDER BY ordem').all();
    expect(lerEstado(antes, CHAVES.cursor)).not.toBeNull();
    antes.close();

    const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
    limpar.push(() => {
      db.close();
    });
    expect(Number(db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);
    expect(VERSAO_DO_BANCO).toBe(4);
    expect(contagem(db)).toEqual(contagemAntes);
    expect(db.prepare('SELECT * FROM outbox ORDER BY ordem').all()).toEqual(outboxAntes);
    for (const t of NOVAS) expect(colunasDe(db, t), t).toContain('origem_local');
    expect(colunasDe(db, 'vendas')).toContain('troca_id');
    expect(colunasDe(db, 'pagamentos_cliente')).toEqual(
      expect.arrayContaining(['devolucao_id', 'estorna_id', 'credito_id']),
    );
    expect(colunasDe(db, 'movimentos_remessa')).toContain('devolucao_id');
    // Já tinha cursor: a marca da recarga dirigida nasce com as onze tabelas da 3 e as duas da 4.
    expect(tabelasDaRecargaDirigida(db)).toEqual([...MARCA_DO_BANCO_V2_COM_CURSOR]);
  });

  it('a marca da recarga só nasce em banco que já tinha cursor', () => {
    // Banco novo (as três migrações de uma vez): carga inicial, sem marca.
    const novo = criarBancoDeTeste();
    limpar.push(() => novo.fechar());
    expect(lerEstado(novo.db, CHAVES.recargaDirigida)).toBeNull();

    // Banco na versão 2 sem cursor (nunca recebeu nada): também sem marca.
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    b.db.close();
    const caminho = `${b.caminho}.v2`;
    const v2 = abrirBancoLocal({
      caminho,
      chave: b.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 2),
    });
    v2.close();
    const migrado = abrirBancoLocal({ caminho, chave: b.chave });
    limpar.push(() => {
      migrado.close();
    });
    expect(lerEstado(migrado, CHAVES.recargaDirigida)).toBeNull();
    expect(Number(migrado.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);

    // O exemplo da versão 1 tinha cursor: marca (as onze da 3 e as duas da 4).
    expect(tabelasDaRecargaDirigida(abrirExemploMigrado(1).db)).toHaveLength(13);
  });
});

// --- 21. colunas ---

describe('colunas das tabelas da Fase 6', () => {
  it('as 25 tabelas, nenhuma coluna de quem registrou nem que aponte para o crédito do outro vendedor', () => {
    expect(TABELAS_SINCRONIZAVEIS).toHaveLength(25);
    for (const t of NOVAS) expect(COLUNAS_DO_PULL[t], t).toBeDefined();
    for (const [t, colunas] of Object.entries(COLUNAS_DO_PULL)) {
      for (const c of colunas) {
        expect(c, `${t}.${c}`).not.toMatch(/_por$/);
        expect(c, `${t}.${c}`).not.toMatch(/credito_origem_id|credito_destino_id/);
      }
    }
    expect(COLUNAS_DO_PULL.vendas).toContain('troca_id');
    expect(COLUNAS_DO_PULL.pagamentos_cliente).toEqual(
      expect.arrayContaining(['devolucao_id', 'estorna_id', 'credito_id']),
    );
    expect(COLUNAS_DO_PULL.movimentos_remessa).toContain('devolucao_id');
    expect(COLUNAS_DO_PULL.devolucoes).toEqual(
      expect.arrayContaining(['valor_em_credito', 'cancelamento_id']),
    );
  });
});

// --- 22. aplicador ---

describe('aplicação das tabelas da Fase 6', () => {
  it('lote com as tabelas novas grava; credito_origem_id ou revisada_por recusa o lote inteiro', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const venda = idDeTeste(5);
    const credito = idDeTeste(6);
    aplicarLote(
      b.db,
      [
        {
          tabela: 'creditos_cliente',
          id: credito,
          version: 1,
          deletedAt: null,
          campos: { cliente_id: idDeTeste(3), valor: '10.00' },
        },
        {
          tabela: 'creditos_sem_saldo',
          id: idDeTeste(7),
          version: 1,
          deletedAt: null,
          campos: { venda_id: venda, credito_id: credito },
        },
      ],
      { cursor: 'c1' },
    );
    expect(contar(b.db, 'SELECT 1 FROM creditos_cliente')).toBe(1);
    expect(contar(b.db, 'SELECT 1 FROM creditos_sem_saldo')).toBe(1);
    for (const ruim of [
      {
        tabela: 'creditos_cliente',
        id: idDeTeste(6),
        version: 1,
        deletedAt: null,
        campos: { cliente_id: 'x', valor: '1.00', credito_origem_id: credito },
      },
      {
        tabela: 'devolucoes',
        id: idDeTeste(6),
        version: 1,
        deletedAt: null,
        campos: { venda_id: venda, revisada_por: credito },
      },
      {
        tabela: 'usos_credito',
        id: idDeTeste(6),
        version: 1,
        deletedAt: null,
        campos: { credito_id: credito, valor: '1.00', credito_destino_id: credito },
      },
    ]) {
      const boa = {
        tabela: 'trocas',
        id: idDeTeste(8),
        version: 1,
        deletedAt: null,
        campos: { venda_origem_id: venda },
      };
      expect(() => aplicarLote(b.db, [boa, ruim], { cursor: 'c2' })).toThrow(LoteRecusado);
      expect(contar(b.db, 'SELECT 1 FROM trocas WHERE id = ?', boa.id)).toBe(0);
      expect(lerEstado(b.db, CHAVES.cursor)).toBe('c1');
    }
  });

  it('substituirIgual troca a versão igual só quando pedido, e a provisória de outro id fica intocada', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const venda = idDeTeste(5);
    aplicarLote(
      b.db,
      [{ tabela: 'vendas', id: venda, version: 2, deletedAt: null, campos: { total: '10.00' } }],
      { cursor: 'c1' },
    );
    const provisoria = idDeTeste(6);
    gravar(b.db, () => {
      b.db
        .prepare(
          "INSERT INTO devolucoes (id, version, origem_local, venda_id) VALUES (?, 1, 'PROVISORIO', ?)",
        )
        .run(provisoria, venda);
      b.db
        .prepare(
          "INSERT INTO provisorias (tabela, linha_id, operacao_id) VALUES ('devolucoes', ?, 'op-1')",
        )
        .run(provisoria);
    });
    const comTroca = {
      tabela: 'vendas',
      id: venda,
      version: 2,
      deletedAt: null,
      campos: { total: '10.00', troca_id: idDeTeste(7) },
    };
    aplicarLote(b.db, [comTroca], { cursor: null });
    expect(b.db.prepare('SELECT troca_id FROM vendas WHERE id = ?').get(venda)).toEqual({
      troca_id: null,
    });
    aplicarLote(
      b.db,
      [
        comTroca,
        {
          tabela: 'devolucoes',
          id: idDeTeste(8),
          version: 1,
          deletedAt: null,
          campos: { venda_id: venda },
        },
      ],
      {
        cursor: null,
        substituirIgual: true,
      },
    );
    expect(b.db.prepare('SELECT troca_id FROM vendas WHERE id = ?').get(venda)).toEqual({
      troca_id: comTroca.campos.troca_id,
    });
    // Versão menor nunca substitui, nem na recarga dirigida.
    aplicarLote(
      b.db,
      [{ tabela: 'vendas', id: venda, version: 1, deletedAt: null, campos: { total: '1.00' } }],
      {
        cursor: null,
        substituirIgual: true,
      },
    );
    expect(b.db.prepare('SELECT total FROM vendas WHERE id = ?').get(venda)).toEqual({
      total: '10.00',
    });
    expect(
      b.db.prepare('SELECT origem_local FROM devolucoes WHERE id = ?').get(provisoria),
    ).toEqual({ origem_local: 'PROVISORIO' });
    expect(contar(b.db, 'SELECT 1 FROM provisorias WHERE linha_id = ?', provisoria)).toBe(1);
  });
});

// --- 23 e ponto 3 da aprovação: recarga dirigida ---

describe('recarga dirigida depois da migração local 3', () => {
  it('banco v2 com cursor recebe as tabelas e as colunas novas sem apagar nada, retoma depois de cair e não duplica', async () => {
    const { db } = abrirExemploMigrado(2);
    const { s, url } = await servidor();
    // O que o servidor já tinha mandado ao computador (mesma versão), agora com as colunas novas preenchidas.
    const remessas = db
      .prepare("SELECT id, version FROM remessas WHERE origem_local = 'SERVIDOR'")
      .all() as {
      id: string;
      version: number;
    }[];
    const vendaAntiga = idDeTeste(5);
    aplicarLote(
      db,
      [
        {
          tabela: 'vendas',
          id: vendaAntiga,
          version: 1,
          deletedAt: null,
          campos: { total: '33.33' },
        },
      ],
      { cursor: null },
    );
    const trocaDaVenda = idDeTeste(7);
    s.mudar({
      tabela: 'vendas',
      id: vendaAntiga,
      deletedAt: null,
      campos: { total: '33.33', troca_id: trocaDaVenda },
    });
    for (const r of remessas) {
      const local = db.prepare('SELECT * FROM remessas WHERE id = ?').get(r.id) as Record<
        string,
        unknown
      >;
      s.registro.push({
        tabela: 'remessas',
        id: r.id,
        version: r.version,
        deletedAt: null,
        campos: { status: local['status'] },
      });
    }
    for (const t of ['itens_remessa', 'clientes'] as const) {
      for (const l of db
        .prepare(`SELECT * FROM ${t} WHERE origem_local = 'SERVIDOR'`)
        .all() as Record<string, unknown>[]) {
        const campos = Object.fromEntries(COLUNAS_DO_PULL[t].map((c) => [c, l[c] ?? null]));
        s.registro.push({
          tabela: t,
          id: l['id'] as string,
          version: l['version'] as number,
          deletedAt: null,
          campos,
        });
      }
    }
    const ids = linhasDasNovas(s, vendaAntiga);
    // O computador está em dia com o registro do servidor: o pull normal não traz nada disso de novo.
    gravar(db, () => gravarEstado(db, CHAVES.cursor, `${s.epoca}:${s.registro.length}`));
    const servidorAntes = (t: string) =>
      contar(db, `SELECT 1 FROM ${t} WHERE origem_local = 'SERVIDOR'`);
    const remessasAntes = servidorAntes('remessas');
    const itensAntes = servidorAntes('itens_remessa');

    // Primeiro ciclo: a 2ª tabela cai. A 1ª já saiu da marca; nada foi apagado.
    s.cairNaTabela = { tabela: 'itens_devolucao', vezes: 1 };
    expect(await ciclo(db, url).executar()).toBe('falha');
    expect(tabelasDaRecargaDirigida(db)).toEqual(MARCA_DO_BANCO_V2_COM_CURSOR.slice(1));
    expect(contar(db, 'SELECT 1 FROM devolucoes WHERE id = ?', ids['devolucoes'])).toBe(1);
    expect(servidorAntes('remessas')).toBe(remessasAntes);
    expect(servidorAntes('itens_remessa')).toBe(itensAntes);
    expect(contar(db, 'SELECT 1 FROM vendas WHERE id = ?', vendaAntiga)).toBe(1);
    expect(db.prepare('SELECT troca_id FROM vendas WHERE id = ?').get(vendaAntiga)).toEqual({
      troca_id: null,
    });

    // Segundo ciclo: retoma da 2ª, termina, a marca some e a cópia antiga ganha a coluna nova.
    expect(await ciclo(db, url).executar()).toBe('ok');
    expect(lerEstado(db, CHAVES.recargaDirigida)).toBeNull();
    for (const t of NOVAS) expect(contar(db, `SELECT 1 FROM ${t} WHERE id = ?`, ids[t]), t).toBe(1);
    expect(db.prepare('SELECT troca_id FROM vendas WHERE id = ?').get(vendaAntiga)).toEqual({
      troca_id: trocaDaVenda,
    });
    expect(
      db
        .prepare('SELECT valida_ate FROM autorizacoes_excecao WHERE id = ?')
        .get(ids['autorizacoes_excecao']),
    ).toEqual({
      valida_ate: '2026-11-20',
    });
    expect(servidorAntes('remessas')).toBe(remessasAntes);
    expect(servidorAntes('itens_remessa')).toBe(itensAntes);
    // Sem duplicar: cada linha do servidor uma vez, as de antes continuam.
    const doServidor = new Set(
      [...s.estado().values()].filter((l) => l.tabela === 'vendas').map((l) => l.id),
    );
    expect(contar(db, "SELECT 1 FROM vendas WHERE origem_local = 'SERVIDOR'")).toBe(
      doServidor.size,
    );
    // A conferência das tabelas novas bateu: nenhum aviso de defeito.
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

  it('banco novo não faz a recarga dirigida', async () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const { s, url } = await servidor();
    linhasDasNovas(s, idDeTeste(5));
    expect(await ciclo(b.db, url).executar()).toBe('ok');
    expect(
      s.pedidosPara('/api/vendedor/sync/pull').filter((p) => p.query.get('tabela')),
    ).toHaveLength(0);
    for (const t of NOVAS) expect(contar(b.db, `SELECT 1 FROM ${t}`), t).toBe(1);
  });
});

// --- 24. provisórias das tabelas novas ---

describe('desfazer as provisórias das tabelas novas', () => {
  it('RECUSADO e CONFLITO desfazem devolução, itens, pagamentos e movimentos provisórios', async () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const { s, url } = await servidor();
    const recusada = idDeTeste(1);
    const emConflito = idDeTeste(1);
    for (const op of [recusada, emConflito]) {
      gravar(b.db, () => {
        b.db
          .prepare(
            "INSERT INTO outbox (operacao_id, tipo, formato, dados, criado_em_local) VALUES (?, 'DEVOLUCAO', 1, ?, '2026-11-13T08:00:00.000Z')",
          )
          .run(op, JSON.stringify({ id: op }));
        const linhas: [string, string][] = [
          ['devolucoes', op],
          ['itens_devolucao', idDeTeste(2)],
          ['pagamentos_cliente', idDeTeste(2)],
          ['movimentos_remessa', idDeTeste(2)],
        ];
        for (const [tabela, id] of linhas) {
          const extra: Record<string, string | number> =
            tabela === 'devolucoes'
              ? { venda_id: op }
              : tabela === 'itens_devolucao'
                ? { devolucao_id: op, quantidade: 1 }
                : tabela === 'pagamentos_cliente'
                  ? { venda_id: op, valor: '-10.00', devolucao_id: op }
                  : {
                      remessa_id: op,
                      produto_id: op,
                      tipo: 'DEVOLUCAO_CLIENTE',
                      quantidade: 1,
                      devolucao_id: op,
                    };
          const cols = Object.keys(extra);
          b.db
            .prepare(
              `INSERT INTO ${tabela} (id, version, origem_local, ${cols.join(', ')}) VALUES (?, 1, 'PROVISORIO', ${cols.map(() => '?').join(', ')})`,
            )
            .run(id, ...Object.values(extra));
          b.db
            .prepare('INSERT INTO provisorias (tabela, linha_id, operacao_id) VALUES (?, ?, ?)')
            .run(tabela, id, op);
        }
      });
    }
    s.resultadoPara = (op) =>
      op.operacaoId === recusada
        ? 'RECUSADO'
        : op.operacaoId === emConflito
          ? 'CONFLITO'
          : undefined;
    expect(await ciclo(b.db, url).executar()).toBe('ok');
    for (const t of ['devolucoes', 'itens_devolucao', 'pagamentos_cliente', 'movimentos_remessa']) {
      expect(contar(b.db, `SELECT 1 FROM ${t} WHERE origem_local = 'PROVISORIO'`), t).toBe(0);
    }
    expect(contar(b.db, 'SELECT 1 FROM provisorias')).toBe(0);
    // Desfazer de novo é inofensivo.
    gravar(b.db, () => desfazerOperacao(b.db, recusada));
    expect(
      contar(b.db, 'SELECT 1 FROM outbox WHERE situacao IN (?, ?)', 'RECUSADO', 'CONFLITO'),
    ).toBe(2);
  });
});
