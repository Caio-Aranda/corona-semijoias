import { afterEach, describe, expect, it } from 'vitest';
import { abrirBancoLocal, type BancoAberto, gravar } from '../banco/banco-local';
import {
  MIGRACOES,
  TABELAS_DA_RECARGA_DIRIGIDA_V4,
  TABELAS_DA_RECARGA_DIRIGIDA_V6,
  TABELAS_DA_RECARGA_DIRIGIDA_V7,
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
 * O banco local e o motor do Corona Vendas com as contas a receber do cliente (tarefa 7.3; plano aprovado no PR #162):
 * migração local 4, colunas, recarga dirigida das três tabelas sem apagar nada. SQLCipher de verdade, arquivo de verdade,
 * servidor falso de verdade. Só o motor e o banco: gravar parcelamento e recebimento na outbox e as telas são da 7.11.
 */

const V7_NOVAS = TABELAS_DA_RECARGA_DIRIGIDA_V7.filter((t) => t !== 'acertos');

const limpar: (() => void | Promise<void>)[] = [];
afterEach(async () => {
  for (const f of limpar.splice(0).reverse()) await f();
});

function abrirExemploMigrado(versao: number): BancoAberto {
  const c = copiarExemplo(versao);
  limpar.push(c.apagar);
  const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
  limpar.push(() => {
    db.close();
  });
  return db;
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
    agora: () => new Date('2026-11-14T09:00:00.000Z'),
    esperar: () => Promise.resolve(),
  });
}

describe('migração local 4', () => {
  it('banco v3 com outbox e provisórias migra para a 4 sem perder nada, com as tabelas e a coluna nova', () => {
    const c = copiarExemplo(3);
    limpar.push(c.apagar);
    const antes = abrirBancoLocal({
      caminho: c.caminho,
      chave: CHAVE_DOS_EXEMPLOS,
      migracoes: MIGRACOES.filter((m) => m.versao <= 3),
    });
    const contagem = (db: BancoAberto): Record<string, number> =>
      Object.fromEntries(
        [
          'vendas',
          'pagamentos_cliente',
          'itens_venda',
          'movimentos_remessa',
          'clientes',
          'provisorias',
        ].map((t) => [t, contar(db, `SELECT 1 FROM ${t}`)]),
      );
    const contagemAntes = contagem(antes);
    const outboxAntes = antes.prepare('SELECT * FROM outbox ORDER BY ordem').all();
    const pagamentosAntes = antes.prepare('SELECT * FROM pagamentos_cliente ORDER BY id').all();
    antes.close();

    const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
    limpar.push(() => {
      db.close();
    });
    expect(Number(db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);
    expect(VERSAO_DO_BANCO).toBe(7);
    expect(contagem(db)).toEqual(contagemAntes);
    expect(db.prepare('SELECT * FROM outbox ORDER BY ordem').all()).toEqual(outboxAntes);
    for (const t of ['contas_receber', 'baixas_receber'] as const) {
      expect(colunasDe(db, t), t).toEqual(
        expect.arrayContaining(['origem_local', ...COLUNAS_DO_PULL[t]]),
      );
      expect(contar(db, `SELECT 1 FROM ${t}`)).toBe(0);
    }
    expect(colunasDe(db, 'pagamentos_cliente')).toContain('posterior');
    // Os pagamentos de antes continuam iguais (a coluna nova começa vazia).
    expect(
      (
        db.prepare('SELECT * FROM pagamentos_cliente ORDER BY id').all() as Record<
          string,
          unknown
        >[]
      ).map(({ posterior, ...resto }) => {
        expect(posterior).toBeNull();
        return resto;
      }),
    ).toEqual(pagamentosAntes);
    // Já tinha cursor: a marca da recarga dirigida nasce com as tabelas da migração 4 e, depois, as da 6.
    expect(tabelasDaRecargaDirigida(db)).toEqual([
      ...TABELAS_DA_RECARGA_DIRIGIDA_V4,
      ...TABELAS_DA_RECARGA_DIRIGIDA_V6,
      ...V7_NOVAS,
    ]);
  });

  it('a marca da recarga só nasce em banco que já tinha cursor', () => {
    // Banco novo (as quatro migrações de uma vez): carga inicial, sem marca.
    const novo = criarBancoDeTeste();
    limpar.push(() => novo.fechar());
    expect(lerEstado(novo.db, CHAVES.recargaDirigida)).toBeNull();
    expect(Number(novo.db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);

    // Banco na versão 3 sem cursor (nunca recebeu nada): também sem marca, e sem linha vazia no estado.
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    b.db.close();
    const caminho = `${b.caminho}.v3`;
    const v3 = abrirBancoLocal({
      caminho,
      chave: b.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 3),
    });
    v3.close();
    const migrado = abrirBancoLocal({ caminho, chave: b.chave });
    limpar.push(() => {
      migrado.close();
    });
    expect(lerEstado(migrado, CHAVES.recargaDirigida)).toBeNull();
    expect(contar(migrado, "SELECT 1 FROM estado_sync WHERE chave = 'recarga_dirigida'")).toBe(0);
    expect(Number(migrado.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);

    // O mesmo banco da 3, agora com cursor antes da 4: marca com as três.
    const c = criarBancoDeTeste();
    limpar.push(() => c.fechar());
    c.db.close();
    const caminhoComCursor = `${c.caminho}.v3`;
    const comCursor = abrirBancoLocal({
      caminho: caminhoComCursor,
      chave: c.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 3),
    });
    gravar(comCursor, () => gravarEstado(comCursor, CHAVES.cursor, '1:5'));
    comCursor.close();
    const migradoComCursor = abrirBancoLocal({ caminho: caminhoComCursor, chave: c.chave });
    limpar.push(() => {
      migradoComCursor.close();
    });
    expect(tabelasDaRecargaDirigida(migradoComCursor)).toEqual([
      ...TABELAS_DA_RECARGA_DIRIGIDA_V4,
      ...TABELAS_DA_RECARGA_DIRIGIDA_V6,
      ...V7_NOVAS,
    ]);
  });

  it('a marca da 3 que ainda não terminou é somada à da 4, sem repetir tabela e na ordem', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    b.db.close();
    const caminho = `${b.caminho}.v3`;
    const v3 = abrirBancoLocal({
      caminho,
      chave: b.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 3),
    });
    gravar(v3, () => {
      gravarEstado(v3, CHAVES.cursor, '1:5');
      gravarEstado(v3, CHAVES.recargaDirigida, JSON.stringify(['trocas', 'pagamentos_cliente']));
    });
    v3.close();
    const migrado = abrirBancoLocal({ caminho, chave: b.chave });
    limpar.push(() => {
      migrado.close();
    });
    expect(tabelasDaRecargaDirigida(migrado)).toEqual([
      'trocas',
      'pagamentos_cliente',
      'contas_receber',
      'baixas_receber',
      'acertos',
      'leituras_acerto',
      ...V7_NOVAS,
    ]);
  });
});

describe('colunas das contas a receber', () => {
  it('as duas tabelas novas estão na lista, com as colunas do servidor e sem custo nem quem registrou', () => {
    expect(TABELAS_SINCRONIZAVEIS).toHaveLength(31);
    expect(TABELAS_SINCRONIZAVEIS).toEqual(
      expect.arrayContaining(['contas_receber', 'baixas_receber']),
    );
    expect(COLUNAS_DO_PULL.contas_receber).toEqual(
      expect.arrayContaining([
        'venda_id',
        'parcela',
        'vencimento',
        'valor',
        'valor_pago',
        'status',
      ]),
    );
    expect(COLUNAS_DO_PULL.baixas_receber).toEqual(
      expect.arrayContaining(['conta_id', 'tipo', 'valor', 'pagamento_cliente_id', 'estorna_id']),
    );
    expect(COLUNAS_DO_PULL.pagamentos_cliente).toContain('posterior');
    for (const t of ['contas_receber', 'baixas_receber'] as const) {
      for (const c of COLUNAS_DO_PULL[t]) {
        expect(c, `${t}.${c}`).not.toMatch(/custo|fornecedor|_por$/);
      }
    }
  });

  it('coluna fora da lista recusa o lote inteiro, sem gravar nada', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const venda = idDeTeste(5);
    const conta = idDeTeste(6);
    expect(() =>
      aplicarLote(
        b.db,
        [
          { tabela: 'vendas', id: venda, version: 1, deletedAt: null, campos: { total: '10.00' } },
          {
            tabela: 'contas_receber',
            id: conta,
            version: 1,
            deletedAt: null,
            campos: { venda_id: venda, valor: '10.00', custo: '1.00' },
          },
        ],
        { cursor: 'c1' },
      ),
    ).toThrow(LoteRecusado);
    expect(contar(b.db, 'SELECT 1 FROM vendas WHERE id = ?', venda)).toBe(0);
    expect(contar(b.db, 'SELECT 1 FROM contas_receber')).toBe(0);
    expect(lerEstado(b.db, CHAVES.cursor)).toBeNull();
  });

  it('lote com as parcelas, as baixas e o pagamento posterior grava com o dinheiro em texto', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const venda = idDeTeste(5);
    const conta = idDeTeste(6);
    const pagamento = idDeTeste(7);
    aplicarLote(
      b.db,
      [
        { tabela: 'vendas', id: venda, version: 1, deletedAt: null, campos: { total: '100.00' } },
        {
          tabela: 'contas_receber',
          id: conta,
          version: 2,
          deletedAt: null,
          campos: {
            venda_id: venda,
            parcela: 1,
            total_parcelas: 2,
            vencimento: '2026-12-01',
            valor: '50.00',
            valor_pago: '20.00',
            valor_cancelado: '0.00',
            status: 'PARCIAL',
          },
        },
        {
          tabela: 'pagamentos_cliente',
          id: pagamento,
          version: 1,
          deletedAt: null,
          campos: {
            venda_id: venda,
            tipo: 'PAGAMENTO',
            forma: 'PIX',
            valor: '20.00',
            posterior: true,
          },
        },
        {
          tabela: 'baixas_receber',
          id: idDeTeste(8),
          version: 1,
          deletedAt: null,
          campos: {
            conta_id: conta,
            tipo: 'PAGAMENTO',
            valor: '20.00',
            pagamento_cliente_id: pagamento,
          },
        },
      ],
      { cursor: 'c1' },
    );
    expect(
      b.db.prepare('SELECT valor, valor_pago, status, vencimento FROM contas_receber').get(),
    ).toEqual({ valor: '50.00', valor_pago: '20.00', status: 'PARCIAL', vencimento: '2026-12-01' });
    expect(
      b.db.prepare('SELECT posterior FROM pagamentos_cliente WHERE id = ?').get(pagamento),
    ).toEqual({
      posterior: 1,
    });
    expect(contar(b.db, 'SELECT 1 FROM baixas_receber')).toBe(1);
  });
});

describe('recarga dirigida depois da migração local 4', () => {
  it('banco v3 com cursor traz as três tabelas, retoma depois de cair, não apaga nem duplica', async () => {
    const db = abrirExemploMigrado(3);
    const { s, url } = await servidor();
    expect(tabelasDaRecargaDirigida(db)).toEqual([
      ...TABELAS_DA_RECARGA_DIRIGIDA_V4,
      ...TABELAS_DA_RECARGA_DIRIGIDA_V6,
      ...V7_NOVAS,
    ]);

    // O que já estava no banco (mesma versão no servidor): a cópia do pagamento não tem a coluna nova.
    const vendaAntiga = idDeTeste(5);
    const pagamentoAntigo = idDeTeste(6);
    aplicarLote(
      db,
      [
        {
          tabela: 'vendas',
          id: vendaAntiga,
          version: 1,
          deletedAt: null,
          campos: { total: '100.00' },
        },
        {
          tabela: 'pagamentos_cliente',
          id: pagamentoAntigo,
          version: 1,
          deletedAt: null,
          campos: { venda_id: vendaAntiga, tipo: 'PAGAMENTO', forma: 'PIX', valor: '20.00' },
        },
      ],
      { cursor: null },
    );
    const servidorAntes = (t: string) =>
      contar(db, `SELECT 1 FROM ${t} WHERE origem_local = 'SERVIDOR'`);
    const remessasAntes = servidorAntes('remessas');

    // O servidor, já com tudo.
    const conta = idDeTeste(7);
    const baixa = idDeTeste(8);
    s.mudar({
      tabela: 'vendas',
      id: vendaAntiga,
      deletedAt: null,
      campos: { total: '100.00' },
    });
    s.mudar({
      tabela: 'pagamentos_cliente',
      id: pagamentoAntigo,
      version: 1,
      deletedAt: null,
      campos: {
        venda_id: vendaAntiga,
        tipo: 'PAGAMENTO',
        forma: 'PIX',
        valor: '20.00',
        posterior: true,
      },
    });
    s.mudar({
      tabela: 'contas_receber',
      id: conta,
      deletedAt: null,
      campos: {
        venda_id: vendaAntiga,
        parcela: 1,
        total_parcelas: 1,
        vencimento: '2026-12-01',
        valor: '80.00',
        valor_pago: '0.00',
        valor_cancelado: '0.00',
        status: 'ABERTA',
      },
    });
    s.mudar({
      tabela: 'baixas_receber',
      id: baixa,
      deletedAt: null,
      campos: {
        conta_id: conta,
        tipo: 'PAGAMENTO',
        valor: '20.00',
        pagamento_cliente_id: pagamentoAntigo,
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
          campos: Object.fromEntries(COLUNAS_DO_PULL[t].map((c) => [c, l[c] ?? null])),
        });
      }
    }
    gravar(db, () => gravarEstado(db, CHAVES.cursor, `${s.epoca}:${s.registro.length}`));

    // Primeiro ciclo: a 2ª tabela da marca (baixas_receber) cai. A 1ª já saiu da marca; nada foi apagado.
    s.cairNaTabela = { tabela: 'baixas_receber', vezes: 1 };
    expect(await ciclo(db, url).executar()).toBe('falha');
    expect(tabelasDaRecargaDirigida(db)).toEqual([
      'baixas_receber',
      'pagamentos_cliente',
      ...TABELAS_DA_RECARGA_DIRIGIDA_V6,
      ...V7_NOVAS,
    ]);
    expect(contar(db, 'SELECT 1 FROM contas_receber WHERE id = ?', conta)).toBe(1);
    expect(contar(db, 'SELECT 1 FROM vendas WHERE id = ?', vendaAntiga)).toBe(1);
    expect(servidorAntes('remessas')).toBe(remessasAntes);
    expect(contar(db, 'SELECT 1 FROM pagamentos_cliente WHERE id = ?', pagamentoAntigo)).toBe(1);

    // Segundo ciclo: retoma, termina, a marca some e a cópia antiga do pagamento ganha a coluna nova.
    expect(await ciclo(db, url).executar()).toBe('ok');
    expect(lerEstado(db, CHAVES.recargaDirigida)).toBeNull();
    expect(contar(db, 'SELECT 1 FROM contas_receber WHERE id = ?', conta)).toBe(1);
    expect(contar(db, 'SELECT 1 FROM baixas_receber WHERE id = ?', baixa)).toBe(1);
    expect(
      db.prepare('SELECT posterior FROM pagamentos_cliente WHERE id = ?').get(pagamentoAntigo),
    ).toEqual({ posterior: 1 });
    expect(contar(db, 'SELECT 1 FROM vendas WHERE id = ?', vendaAntiga)).toBe(1);
    expect(servidorAntes('remessas')).toBe(remessasAntes);
    // Cada linha do servidor uma vez só.
    expect(contar(db, 'SELECT 1 FROM pagamentos_cliente WHERE id = ?', pagamentoAntigo)).toBe(1);
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

  it('banco novo faz a carga inicial e não faz recarga dirigida', async () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const { s, url } = await servidor();
    s.mudar({
      tabela: 'contas_receber',
      id: idDeTeste(7),
      deletedAt: null,
      campos: { venda_id: idDeTeste(5), valor: '10.00', status: 'ABERTA' },
    });
    expect(await ciclo(b.db, url).executar()).toBe('ok');
    expect(contar(b.db, 'SELECT 1 FROM contas_receber')).toBe(1);
    expect(
      s.pedidosPara('/api/vendedor/sync/pull').filter((p) => p.query.get('tabela')),
    ).toHaveLength(0);
  });
});
