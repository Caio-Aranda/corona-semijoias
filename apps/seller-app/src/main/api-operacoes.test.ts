import { describe, expect, it } from 'vitest';
import type { PedidoDeChamada } from '../shared/operacoes';
import { criarApiDoVendas, MENSAGEM_SEM_BANCO } from './api';

const ID = '3f2b8c1e-5d4a-4b7e-9c1d-2a6f8e0b4c11';
const OUTRO = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const HASH = 'a'.repeat(64);
const base = { baseUrl: 'http://api.teste', versaoDoApp: '1.0.0', dispositivoId: ID };

function falso(corpo: unknown, tipo = 'application/json', status = 200) {
  const pedidos: Request[] = [];
  const fetchFalso = (pedido: Request | string | URL): Promise<Response> => {
    pedidos.push(pedido as Request);
    const resposta =
      corpo instanceof Uint8Array
        ? new Response(corpo.slice().buffer, { status, headers: { 'Content-Type': tipo } })
        : new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': tipo } });
    return Promise.resolve(resposta);
  };
  return { pedidos, fetch: fetchFalso };
}

describe('cada operação da tela vira a chamada certa à API do vendedor', () => {
  const casos: [PedidoDeChamada, string, unknown?][] = [
    [{ operacao: 'maletas.listar' }, 'GET /api/vendedor/maletas'],
    [{ operacao: 'maletas.ver', id: ID }, `GET /api/vendedor/maletas/${ID}`],
    [
      { operacao: 'maletas.peca', id: ID, codigo: 'A 1' },
      `GET /api/vendedor/maletas/${ID}/pecas?codigo=A%201`,
    ],
    [
      {
        operacao: 'maletas.conferir',
        id: ID,
        conferenciaId: OUTRO,
        itens: [{ produtoId: ID, contada: 2 }],
      },
      `POST /api/vendedor/maletas/${ID}/conferencia`,
      { id: OUTRO, itens: [{ produtoId: ID, contada: 2 }] },
    ],
    [{ operacao: 'maletas.verConferencia', id: ID }, `GET /api/vendedor/maletas/${ID}/conferencia`],
    [
      {
        operacao: 'vendas.vender',
        venda: {
          id: ID,
          remessaId: OUTRO,
          clienteId: ID,
          itens: [{ produtoId: OUTRO, quantidade: 1 }],
          pagamentos: [],
        },
      },
      'POST /api/vendedor/vendas',
      {
        id: ID,
        remessaId: OUTRO,
        clienteId: ID,
        itens: [{ produtoId: OUTRO, quantidade: 1 }],
        pagamentos: [],
      },
    ],
    [
      { operacao: 'vendas.listar', de: '2026-11-01', status: 'ESTORNADA' },
      'GET /api/vendedor/vendas?de=2026-11-01&status=ESTORNADA',
    ],
    [
      { operacao: 'vendas.listar', pagina: 2, tamanho: 50 },
      'GET /api/vendedor/vendas?pagina=2&tamanho=50',
    ],
    [{ operacao: 'vendas.ver', id: ID }, `GET /api/vendedor/vendas/${ID}`],
    [
      { operacao: 'vendas.estornar', id: ID, estornoId: OUTRO, motivo: 'Engano' },
      `POST /api/vendedor/vendas/${ID}/estorno`,
      { id: OUTRO, motivo: 'Engano' },
    ],
    [{ operacao: 'clientes.listar', busca: 'maria' }, 'GET /api/vendedor/clientes?busca=maria'],
    [{ operacao: 'clientes.ver', id: ID }, `GET /api/vendedor/clientes/${ID}`],
    [
      { operacao: 'clientes.cadastrar', cliente: { id: ID, nome: 'Maria' } },
      'POST /api/vendedor/clientes',
      { id: ID, nome: 'Maria' },
    ],
    [{ operacao: 'cotacao.hoje' }, 'GET /api/vendedor/cotacao'],
    [
      { operacao: 'relatorios.vendas', agrupar: 'peca', de: '2026-11-01' },
      'GET /api/vendedor/relatorios/vendas?de=2026-11-01&agrupar=peca',
    ],
    [{ operacao: 'relatorios.recebimentos' }, 'GET /api/vendedor/relatorios/recebimentos'],
    [{ operacao: 'relatorios.aReceber' }, 'GET /api/vendedor/relatorios/a-receber'],
  ];

  it.each(casos)('%j', async (pedido, esperado, corpo) => {
    const { pedidos, fetch } = falso({});
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.chamar('token-de-acesso', pedido);
    expect(r.ok).toBe(true);
    const enviado = pedidos[0];
    const url = new URL(enviado?.url ?? '');
    expect(`${enviado?.method} ${url.pathname}${url.search}`).toBe(esperado);
    expect(enviado?.headers.get('Authorization')).toBe('Bearer token-de-acesso');
    expect(enviado?.headers.get('X-App-Tipo')).toBe('vendas');
    if (corpo !== undefined) expect(await enviado?.clone().json()).toEqual(corpo);
  });

  it('a foto volta como endereço de dados e o token nunca sai do processo principal', async () => {
    const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
    const { pedidos, fetch } = falso(bytes, 'image/jpeg');
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.chamar('token-de-acesso', {
      operacao: 'fotos.ver',
      hash: HASH,
      tamanho: 'grande',
    });
    expect(r).toEqual({ ok: true, dados: { dataUrl: 'data:image/jpeg;base64,/9j/2Q==' } });
    expect(new URL(pedidos[0]?.url ?? '').pathname).toBe(`/api/vendedor/fotos/${HASH}/grande`);
    expect(pedidos[0]?.headers.get('Authorization')).toBe('Bearer token-de-acesso');
  });

  it('a mensagem da API (por exemplo, a recusa de uma venda) chega como veio', async () => {
    const mensagem =
      'Na sua maleta há só 1 peça(s) de "Anel" e você informou 2. Confira a quantidade.';
    const { fetch } = falso(
      { codigo: 'VENDA_SEM_SALDO', detail: mensagem },
      'application/json',
      409,
    );
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.chamar('t', {
      operacao: 'vendas.vender',
      venda: {
        id: ID,
        remessaId: OUTRO,
        clienteId: ID,
        itens: [{ produtoId: OUTRO, quantidade: 2 }],
        pagamentos: [],
      },
    });
    expect(r).toEqual({
      ok: false,
      falha: { tipo: 'recusada', status: 409, codigo: 'VENDA_SEM_SALDO', mensagem },
    });
  });
});

describe('devolução, troca e crédito só existem no banco do computador (tarefa 6.8)', () => {
  const pecas = [{ produtoId: OUTRO, quantidade: 1, estado: 'BOA' as const }];
  const casos: PedidoDeChamada[] = [
    { operacao: 'devolucoes.vendasComAPeca', codigo: 'A-1' },
    { operacao: 'devolucoes.previa', vendaId: ID, itens: pecas },
    {
      operacao: 'devolucoes.registrar',
      devolucao: {
        id: ID,
        vendaId: OUTRO,
        motivo: 'ARREPENDIMENTO',
        formaReembolso: 'PIX',
        itens: pecas,
      },
    },
    { operacao: 'devolucoes.cancelar', id: ID, cancelamentoId: OUTRO, motivo: 'Errei' },
    { operacao: 'devolucoes.listar' },
    {
      operacao: 'autorizacoes.pedir',
      pedido: { id: ID, vendaId: OUTRO, motivo: 'ARREPENDIMENTO', itens: pecas },
    },
    { operacao: 'autorizacoes.listar' },
    {
      operacao: 'trocas.previa',
      devolucao: { vendaId: OUTRO, itens: pecas },
      itens: [{ produtoId: ID, quantidade: 1 }],
    },
    {
      operacao: 'trocas.registrar',
      troca: {
        id: ID,
        creditoId: OUTRO,
        pagamentoDoCreditoId: ID,
        devolucao: { id: OUTRO, vendaId: ID, motivo: 'TAMANHO', itens: pecas },
        venda: {
          id: ID,
          remessaId: OUTRO,
          itens: [{ produtoId: ID, quantidade: 1 }],
          pagamentos: [],
        },
      },
    },
    { operacao: 'trocas.cancelar', id: ID, cancelamentoId: OUTRO, motivo: 'Desistiu' },
    { operacao: 'creditos.doCliente', clienteId: ID },
  ];

  it.each(casos.map((c) => [c.operacao, c] as const))('%s', async (_nome, pedido) => {
    const { pedidos, fetch } = falso({});
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.chamar('token-de-acesso', pedido);
    expect(r).toEqual({
      ok: false,
      falha: {
        tipo: 'recusada',
        status: 503,
        mensagem: MENSAGEM_SEM_BANCO,
        codigo: 'SEM_BANCO_LOCAL',
      },
    });
    expect(pedidos).toHaveLength(0);
  });
});
