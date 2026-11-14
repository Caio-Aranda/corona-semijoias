import { afterEach, describe, expect, it } from 'vitest';
import { abrirBancoLocal, type BancoAberto, gravar } from '../banco/banco-local';
import {
  MIGRACOES,
  TABELAS_DA_RECARGA_DIRIGIDA_V8,
  TABELAS_SINCRONIZAVEIS,
  VERSAO_DO_BANCO,
} from '../banco/migracoes';
import { CHAVE_DOS_EXEMPLOS, copiarExemplo } from '../banco/testes/exemplos';
import { aplicarLote, LoteRecusado } from './aplicador-do-pull';
import { tabelasDaRecargaDirigida } from './ciclo';
import { COLUNAS_DO_PULL } from './colunas';
import { CHAVES, gravarEstado, lerEstado } from './estado-sync';
import { contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';

/**
 * O banco local e o motor do Corona Vendas com o dinheiro do vendedor (tarefa 7.6; plano aprovado no PR #167): migração local 8
 * (contas do nível 2 sem venda, forma prevista, baixa pelo recebimento, pago e crédito do acerto e `recebimentos_vendedor`),
 * colunas do pull e recarga dirigida, sem apagar nada. SQLCipher de verdade, arquivo de verdade. Só o motor e o banco: o vendedor
 * não registra recebimento (tudo é da dona) e as telas são da 7.11.
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

describe('migração local 8', () => {
  it('banco v7 migra para a 8 sem perder nada, com as colunas novas e a tabela dos recebimentos', () => {
    const c = copiarExemplo(7);
    limpar.push(c.apagar);
    const antes = abrirBancoLocal({
      caminho: c.caminho,
      chave: CHAVE_DOS_EXEMPLOS,
      migracoes: MIGRACOES.filter((m) => m.versao <= 7),
    });
    const contagem = (db: BancoAberto): Record<string, number> =>
      Object.fromEntries(
        ['vendas', 'contas_receber', 'baixas_receber', 'acertos', 'remessas', 'provisorias'].map(
          (t) => [t, contar(db, `SELECT 1 FROM ${t}`)],
        ),
      );
    const contagemAntes = contagem(antes);
    const contasAntes = antes.prepare('SELECT * FROM contas_receber ORDER BY id').all();
    const outboxAntes = antes.prepare('SELECT * FROM outbox ORDER BY ordem').all();
    antes.close();

    const db = abrirBancoLocal({ caminho: c.caminho, chave: CHAVE_DOS_EXEMPLOS });
    limpar.push(() => {
      db.close();
    });
    expect(Number(db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);
    expect(VERSAO_DO_BANCO).toBe(8);
    expect(contagem(db)).toEqual(contagemAntes);
    expect(db.prepare('SELECT * FROM outbox ORDER BY ordem').all()).toEqual(outboxAntes);
    // As linhas das contas continuam as mesmas (a cópia foi refeita com as mesmas linhas), mais a coluna nova vazia.
    const contasDepois = (
      db.prepare('SELECT * FROM contas_receber ORDER BY id').all() as Record<string, unknown>[]
    ).map(({ forma_prevista, ...resto }) => {
      expect(forma_prevista).toBeNull();
      return resto;
    });
    expect(contasDepois).toEqual(contasAntes);
    for (const t of [
      'contas_receber',
      'baixas_receber',
      'acertos',
      'recebimentos_vendedor',
    ] as const) {
      expect(colunasDe(db, t), t).toEqual(
        expect.arrayContaining(['origem_local', ...COLUNAS_DO_PULL[t]]),
      );
    }
    expect(contar(db, 'SELECT 1 FROM recebimentos_vendedor')).toBe(0);
    // A conta do nível 2 não tem venda: a cópia aceita.
    gravar(db, () => {
      db.prepare(
        `INSERT INTO contas_receber (id, version, devedor_tipo, vendedor_id, origem_tipo, origem_id, parcela,
           total_parcelas, vencimento, valor, valor_pago, valor_cancelado, status, forma_prevista)
         VALUES (?, 1, 'VENDEDOR', ?, 'ACERTO', ?, 1, 1, '2026-12-14', '15.00', '0.00', '0.00', 'ABERTA', 'BOLETO')`,
      ).run(idDeTeste(70), idDeTeste(1), idDeTeste(2));
    });
    expect(contar(db, 'SELECT 1 FROM contas_receber WHERE venda_id IS NULL')).toBe(1);
  });

  it('a marca da recarga só nasce em banco que já tinha cursor, e soma à que ainda não terminou', () => {
    // Banco novo (as oito migrações de uma vez): carga inicial, sem marca.
    const novo = criarBancoDeTeste();
    limpar.push(() => novo.fechar());
    expect(lerEstado(novo.db, CHAVES.recargaDirigida)).toBeNull();
    expect(Number(novo.db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);

    // Banco na versão 7 sem cursor: também sem marca.
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    b.db.close();
    const semCursor = `${b.caminho}.v7`;
    abrirBancoLocal({
      caminho: semCursor,
      chave: b.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 7),
    }).close();
    const migrado = abrirBancoLocal({ caminho: semCursor, chave: b.chave });
    limpar.push(() => {
      migrado.close();
    });
    expect(lerEstado(migrado, CHAVES.recargaDirigida)).toBeNull();

    // Banco na versão 7 com cursor: marca com as quatro tabelas da 8.
    const c = criarBancoDeTeste();
    limpar.push(() => c.fechar());
    c.db.close();
    const comCursor = `${c.caminho}.v7`;
    const v7 = abrirBancoLocal({
      caminho: comCursor,
      chave: c.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 7),
    });
    gravar(v7, () => gravarEstado(v7, CHAVES.cursor, '1:5'));
    v7.close();
    const m7 = abrirBancoLocal({ caminho: comCursor, chave: c.chave });
    limpar.push(() => {
      m7.close();
    });
    expect(tabelasDaRecargaDirigida(m7)).toEqual([...TABELAS_DA_RECARGA_DIRIGIDA_V8]);

    // Com uma marca antiga ainda de pé: as antigas primeiro, sem repetir.
    const d = criarBancoDeTeste();
    limpar.push(() => d.fechar());
    d.db.close();
    const comMarca = `${d.caminho}.v7`;
    const v7b = abrirBancoLocal({
      caminho: comMarca,
      chave: d.chave,
      migracoes: MIGRACOES.filter((m) => m.versao <= 7),
    });
    gravar(v7b, () => {
      gravarEstado(v7b, CHAVES.cursor, '1:5');
      gravarEstado(v7b, CHAVES.recargaDirigida, JSON.stringify(['comissoes', 'contas_receber']));
    });
    v7b.close();
    const m7b = abrirBancoLocal({ caminho: comMarca, chave: d.chave });
    limpar.push(() => {
      m7b.close();
    });
    expect(tabelasDaRecargaDirigida(m7b)).toEqual([
      'comissoes',
      'contas_receber',
      'baixas_receber',
      'recebimentos_vendedor',
      'acertos',
    ]);
  });
});

describe('colunas do dinheiro do vendedor no pull', () => {
  it('a tabela nova está na lista, só com as colunas do servidor, e o caixa nunca vem', () => {
    expect(TABELAS_SINCRONIZAVEIS).toHaveLength(32);
    expect(TABELAS_SINCRONIZAVEIS).toContain('recebimentos_vendedor');
    expect(COLUNAS_DO_PULL.recebimentos_vendedor).toEqual([
      'vendedor_id',
      'momento',
      'acerto_id',
      'aplicado_em',
      'forma',
      'valor',
      'recebido_em',
      'estorna_id',
    ]);
    expect(COLUNAS_DO_PULL.contas_receber).toContain('forma_prevista');
    expect(COLUNAS_DO_PULL.baixas_receber).toContain('recebimento_vendedor_id');
    expect(COLUNAS_DO_PULL.acertos).toEqual(
      expect.arrayContaining(['pago_ao_vendedor', 'credito_usado', 'credito_do_acerto_id']),
    );
    // Nem quem registrou, nem o motivo, nem o resumo do pedido, nem nada de caixa.
    for (const [t, colunas] of Object.entries(COLUNAS_DO_PULL)) {
      for (const c of colunas) {
        expect(c, `${t}.${c}`).not.toMatch(/caixa|registrado_por|conteudo|custo/);
      }
    }
    expect(COLUNAS_DO_PULL.recebimentos_vendedor).not.toContain('motivo');
    expect(Object.keys(COLUNAS_DO_PULL)).not.toContain('lancamentos_caixa');
    expect(Object.keys(COLUNAS_DO_PULL)).not.toContain('categorias_caixa');
  });

  it('lote com o recebimento, a conta do nível 2 e a baixa grava', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    const conta = idDeTeste(11);
    const recebimento = idDeTeste(12);
    aplicarLote(
      b.db,
      [
        {
          tabela: 'contas_receber',
          id: conta,
          version: 1,
          deletedAt: null,
          campos: {
            devedor_tipo: 'VENDEDOR',
            vendedor_id: idDeTeste(1),
            origem_tipo: 'ACERTO',
            origem_id: idDeTeste(2),
            parcela: 1,
            total_parcelas: 1,
            vencimento: '2026-12-14',
            valor: '20.00',
            valor_pago: '20.00',
            valor_cancelado: '0.00',
            status: 'PAGA',
            forma_prevista: 'BOLETO',
          },
        },
        {
          tabela: 'recebimentos_vendedor',
          id: recebimento,
          version: 1,
          deletedAt: null,
          campos: {
            vendedor_id: idDeTeste(1),
            momento: 'AVULSO',
            aplicado_em: 'CONTAS',
            forma: 'BOLETO',
            valor: '20.00',
            recebido_em: '2026-11-14T12:00:00Z',
          },
        },
        {
          tabela: 'baixas_receber',
          id: idDeTeste(13),
          version: 1,
          deletedAt: null,
          campos: {
            conta_id: conta,
            tipo: 'PAGAMENTO',
            valor: '20.00',
            baixada_em: '2026-11-14T12:00:00Z',
            recebimento_vendedor_id: recebimento,
          },
        },
      ],
      { cursor: 'c1' },
    );
    expect(
      b.db.prepare('SELECT venda_id, forma_prevista, status FROM contas_receber').get(),
    ).toEqual({ venda_id: null, forma_prevista: 'BOLETO', status: 'PAGA' });
    expect(b.db.prepare('SELECT forma, valor FROM recebimentos_vendedor').get()).toEqual({
      forma: 'BOLETO',
      valor: '20.00',
    });
    expect(b.db.prepare('SELECT recebimento_vendedor_id FROM baixas_receber').get()).toEqual({
      recebimento_vendedor_id: recebimento,
    });
  });

  it('coluna fora da lista recusa o lote inteiro, sem gravar nada', () => {
    const b = criarBancoDeTeste();
    limpar.push(() => b.fechar());
    for (const coluna of ['registrado_por', 'motivo', 'conteudo', 'categoria_id']) {
      expect(() =>
        aplicarLote(
          b.db,
          [
            {
              tabela: 'recebimentos_vendedor',
              id: idDeTeste(12),
              version: 1,
              deletedAt: null,
              campos: { forma: 'DINHEIRO', valor: '1.00', [coluna]: idDeTeste(9) },
            },
          ],
          { cursor: 'c1' },
        ),
      ).toThrow(LoteRecusado);
    }
    expect(contar(b.db, 'SELECT 1 FROM recebimentos_vendedor')).toBe(0);
    expect(lerEstado(b.db, CHAVES.cursor)).toBeNull();
  });
});
