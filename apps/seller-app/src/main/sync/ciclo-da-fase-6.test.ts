import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DevolucaoOuTroca, EnvioNaoAceito, Venda } from '../../shared/dados';
import type { PecaQueVolta } from '../../shared/operacoes';
import { ConsultasLocais } from '../local/consultas-locais';
import { CicloDeSincronizacao } from './ciclo';
import { OperacoesLocais, saldoDaPeca } from './operacoes-locais';
import { criarTransporteDoSync } from './transporte-do-sync';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { type LinhaDoServidor, ServidorFalso } from './testes/servidor-falso';

/**
 * A volta da internet com as operações da tarefa 6.8 (plano, teste 12): o servidor falso de verdade (`node:http`) e o
 * banco cifrado de verdade. As operações saem na ordem da fila com os bytes guardados; o `RECUSADO` e o `CONFLITO`
 * desfazem tudo o que a operação gravou; a cópia "para revisão da dona" e o crédito que já tinha acabado aparecem.
 */
let b: BancoDeTeste;
let s: ServidorFalso;
let url: string;
let ops: OperacoesLocais;
let consultas: ConsultasLocais;
let agora: Date;
const T0 = new Date('2026-11-12T13:00:00.000Z');
const PUSH = '/api/vendedor/sync/push';

const ciclo = () =>
  new CicloDeSincronizacao({
    banco: () => b.db,
    transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
    comToken: (fn) => fn('token-de-teste'),
    agora: () => agora,
    esperar: () => Promise.resolve(),
  });

interface Cenario {
  remessaId: string;
  produtoA: string;
  produtoC: string;
  clienteId: string;
  vendaAntiga: string;
}

/** A dona envia a maleta (já conferida), há um cliente e uma venda antiga do vendedor; o computador recebe tudo. */
async function cenario(): Promise<Cenario> {
  const remessaId = idDeTeste(1);
  const produtoA = idDeTeste(2);
  const produtoC = idDeTeste(2);
  const clienteId = idDeTeste(3);
  const vendaAntiga = idDeTeste(5);
  s.mudar({ tabela: 'remessas', id: remessaId, deletedAt: null, campos: { status: 'CONFERIDA' } });
  for (const [produto, qtd, preco] of [
    [produtoA, 3, '33.33'],
    [produtoC, 5, '50.00'],
  ] as const) {
    s.mudar({
      tabela: 'itens_remessa',
      id: idDeTeste(4),
      deletedAt: null,
      campos: {
        remessa_id: remessaId,
        produto_id: produto,
        qtd_enviada: qtd,
        preco_remessa: preco,
      },
    });
    s.mudar({ tabela: 'produtos', id: produto, deletedAt: null, campos: { nome: 'Peça' } });
  }
  s.mudar({ tabela: 'clientes', id: clienteId, deletedAt: null, campos: { nome: 'Ana' } });
  const vendidaEm = '2026-11-01T13:00:00.000Z';
  s.mudar({
    tabela: 'vendas',
    id: vendaAntiga,
    deletedAt: null,
    campos: {
      remessa_id: remessaId,
      cliente_id: clienteId,
      status: 'CONCLUIDA',
      total: '33.33',
      vendida_em: vendidaEm,
    },
  });
  s.mudar({
    tabela: 'itens_venda',
    id: idDeTeste(4),
    deletedAt: null,
    campos: {
      venda_id: vendaAntiga,
      produto_id: produtoA,
      quantidade: 1,
      preco_unitario: '33.33',
      desconto: '0.00',
    },
  });
  s.mudar({
    tabela: 'movimentos_remessa',
    id: idDeTeste(4),
    deletedAt: null,
    campos: {
      remessa_id: remessaId,
      produto_id: produtoA,
      tipo: 'VENDA',
      quantidade: -1,
      venda_id: vendaAntiga,
    },
  });
  s.mudar({
    tabela: 'pagamentos_cliente',
    id: idDeTeste(6),
    deletedAt: null,
    campos: {
      venda_id: vendaAntiga,
      tipo: 'PAGAMENTO',
      forma: 'PIX',
      valor: '33.33',
      pago_em: vendidaEm,
    },
  });
  expect(await ciclo().executar()).toBe('ok');
  return { remessaId, produtoA, produtoC, clienteId, vendaAntiga };
}

const pecas = (produtoId: string, quantidade: number): PecaQueVolta[] => [
  { produtoId, quantidade, estado: 'BOA' },
];

function vender(c: Cenario, quantidade: number): string {
  const id = idDeTeste(5);
  ops.vender({
    id,
    remessaId: c.remessaId,
    clienteId: c.clienteId,
    itens: [{ produtoId: c.produtoA, quantidade }],
    pagamentos: [{ id: idDeTeste(6), valor: (33.33 * quantidade).toFixed(2), forma: 'PIX' }],
  });
  return id;
}

function trocar(c: Cenario, vendaId: string) {
  const t = {
    id: idDeTeste(8),
    creditoId: idDeTeste(8),
    pagamentoDoCreditoId: idDeTeste(8),
    devolucao: {
      id: idDeTeste(8),
      vendaId,
      motivo: 'TAMANHO' as const,
      itens: pecas(c.produtoA, 1),
    },
    venda: {
      id: idDeTeste(5),
      remessaId: c.remessaId,
      itens: [{ produtoId: c.produtoC, quantidade: 1 }],
      pagamentos: [],
    },
  };
  ops.trocar(t);
  return t;
}

function pedir<T>(pedido: Parameters<ConsultasLocais['chamar']>[0]): T {
  const r = consultas.chamar(pedido);
  if (!r.ok) throw new Error(JSON.stringify(r.falha));
  return r.dados as T;
}

beforeEach(async () => {
  b = criarBancoDeTeste();
  s = new ServidorFalso();
  url = await s.iniciar();
  agora = T0;
  ops = new OperacoesLocais({ banco: () => b.db, agora: () => agora });
  consultas = new ConsultasLocais({ banco: () => b.db, operacoes: ops, agora: () => agora });
});
afterEach(async () => {
  await s.parar();
  b.fechar();
});

describe('volta da internet com devolução, troca e crédito (teste 12)', () => {
  it('as cinco operações novas saem na ordem da fila, com os bytes guardados', async () => {
    const c = await cenario();
    const v = vender(c, 2);
    const d = idDeTeste(8);
    ops.devolver({
      id: d,
      vendaId: v,
      motivo: 'ARREPENDIMENTO',
      formaReembolso: 'PIX',
      itens: pecas(c.produtoA, 1),
    });
    const t = trocar(c, v);
    const pedido = idDeTeste(8);
    ops.pedirAutorizacao({
      id: pedido,
      vendaId: c.vendaAntiga,
      motivo: 'ARREPENDIMENTO',
      itens: pecas(c.produtoA, 1),
    });
    ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d, motivo: 'Errei' });
    ops.cancelarTroca({ id: idDeTeste(8), trocaId: t.id, motivo: 'Desistiu' });
    const fila = b.db
      .prepare('SELECT operacao_id, tipo, dados FROM outbox ORDER BY ordem')
      .all() as {
      operacao_id: string;
      tipo: string;
      dados: string;
    }[];
    expect(fila.map((f) => f.tipo)).toEqual([
      'VENDA',
      'DEVOLUCAO',
      'TROCA',
      'PEDIDO_AUTORIZACAO',
      'CANCELAR_DEVOLUCAO',
      'CANCELAR_TROCA',
    ]);
    expect(await ciclo().executar()).toBe('ok');
    const corpo = s.pedidosPara(PUSH)[0]?.corpo ?? '';
    const enviadas = (
      JSON.parse(corpo) as { operacoes: { operacaoId: string; tipo: string; formato: number }[] }
    ).operacoes;
    expect(enviadas.map((o) => [o.operacaoId, o.tipo, o.formato])).toEqual(
      fila.map((f) => [f.operacao_id, f.tipo, 1]),
    );
    // Os bytes guardados vão como estão (nunca remontados).
    for (const f of fila) expect(corpo).toContain(f.dados);
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE situacao <> 'ENVIADO'")).toBe(0);
  });

  it('RECUSADO e CONFLITO desfazem tudo: saldo, crédito e "a receber" voltam, e o aviso diz o que foi', async () => {
    const c = await cenario();
    const v = vender(c, 2);
    const d = idDeTeste(8);
    ops.devolver({
      id: d,
      vendaId: v,
      motivo: 'ARREPENDIMENTO',
      formaReembolso: 'PIX',
      itens: pecas(c.produtoA, 1),
    });
    const t = trocar(c, v);
    expect(saldoDaPeca(b.db, c.remessaId, c.produtoA)).toBe(2);
    expect(saldoDaPeca(b.db, c.remessaId, c.produtoC)).toBe(4);
    s.resultadoPara = (op) =>
      op.operacaoId === d ? 'RECUSADO' : op.operacaoId === t.id ? 'CONFLITO' : undefined;
    expect(await ciclo().executar()).toBe('ok');
    expect(saldoDaPeca(b.db, c.remessaId, c.produtoA)).toBe(0);
    expect(saldoDaPeca(b.db, c.remessaId, c.produtoC)).toBe(5);
    expect(contar(b.db, 'SELECT 1 FROM creditos_cliente')).toBe(0);
    expect(contar(b.db, 'SELECT 1 FROM devolucoes')).toBe(0);
    expect(contar(b.db, 'SELECT 1 FROM vendas WHERE id = ?', t.venda.id)).toBe(0);
    expect(pedir<Venda>({ operacao: 'vendas.ver', id: v })).toMatchObject({
      devolvido: '0.00',
      pago: '66.66',
      aReceber: '0.00',
    });
    expect(
      pedir<EnvioNaoAceito[]>({ operacao: 'envios.naoAceitos' }).map((e) => e.descricao),
    ).toEqual(['Devolução de Ana', 'Troca de Ana']);
  });

  it('ACEITO com a cópia "para revisão da dona" e a venda com crédito que já tinha acabado aparecem', async () => {
    const c = await cenario();
    const v = vender(c, 2);
    const d = idDeTeste(8);
    const padrao = s.linhasDaOperacao;
    s.linhasDaOperacao = (op): LinhaDoServidor[] => {
      if (op.tipo !== 'DEVOLUCAO') return padrao(op);
      const item = [...s.estado().values()].find(
        (l) => l.tabela === 'itens_venda' && l.campos['venda_id'] === v,
      );
      return [
        {
          tabela: 'devolucoes',
          id: d,
          version: 1,
          deletedAt: null,
          campos: {
            venda_id: v,
            cliente_id: c.clienteId,
            remessa_id: c.remessaId,
            situacao: 'EM_REVISAO',
            destino: 'REEMBOLSO',
            valor_devolvido: '33.33',
            valor_reembolsado: '33.33',
            valor_em_credito: '0.00',
            devolvida_em: T0.toISOString(),
            revisao_acima_do_limite_semanal: true,
          },
        },
        {
          tabela: 'itens_devolucao',
          id: idDeTeste(4),
          version: 1,
          deletedAt: null,
          campos: {
            devolucao_id: d,
            item_venda_id: item?.id ?? '',
            quantidade: 1,
            valor: '33.33',
            estado_peca: 'BOA',
            destino_peca: 'MALETA',
          },
        },
        {
          tabela: 'movimentos_remessa',
          id: idDeTeste(4),
          version: 1,
          deletedAt: null,
          campos: {
            remessa_id: c.remessaId,
            produto_id: c.produtoA,
            tipo: 'DEVOLUCAO_CLIENTE',
            quantidade: 1,
            venda_id: v,
            devolucao_id: d,
          },
        },
        {
          tabela: 'pagamentos_cliente',
          id: idDeTeste(6),
          version: 1,
          deletedAt: null,
          campos: {
            venda_id: v,
            tipo: 'REEMBOLSO',
            forma: 'PIX',
            valor: '-33.33',
            devolucao_id: d,
            pago_em: T0.toISOString(),
          },
        },
        {
          tabela: 'creditos_sem_saldo',
          id: idDeTeste(9),
          version: 1,
          deletedAt: null,
          campos: {
            venda_id: v,
            situacao: 'EM_REVISAO',
            valor_pedido: '5.00',
            valor_usado: '0.00',
          },
        },
      ];
    };
    ops.devolver({
      id: d,
      vendaId: v,
      motivo: 'ARREPENDIMENTO',
      formaReembolso: 'PIX',
      itens: pecas(c.produtoA, 1),
    });
    expect(await ciclo().executar()).toBe('ok');
    expect(contar(b.db, "SELECT 1 FROM devolucoes WHERE origem_local = 'PROVISORIO'")).toBe(0);
    expect(saldoDaPeca(b.db, c.remessaId, c.produtoA)).toBe(1);
    expect(consultas.fase6.listar({}).map((l: DevolucaoOuTroca) => [l.id, l.situacao])).toEqual([
      [d, 'EM_REVISAO'],
    ]);
    expect(pedir<Venda>({ operacao: 'vendas.ver', id: v })).toMatchObject({
      devolvido: '33.33',
      aReceber: '0.00',
      creditoSemSaldo: true,
    });
  });
});
