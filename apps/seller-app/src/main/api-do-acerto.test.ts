import { describe, expect, it } from 'vitest';
import type { PedidoDeChamada } from '../shared/operacoes';
import { criarApiDoVendas } from './api';
import { ehConsultaLocal } from './local/consultas-locais';

/**
 * Tarefa 8.10: pedir o acerto e contar as sobras vão direto à API do vendedor (sem internet, falham: nada é guardado
 * no computador nem vai à fila). Só usam as rotas da 7.4 que já existem.
 */
const ID = '3f2b8c1e-5d4a-4b7e-9c1d-2a6f8e0b4c11';
const OUTRO = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const base = { baseUrl: 'http://api.teste', versaoDoApp: '1.0.0', dispositivoId: ID };

const leitura = { id: OUTRO, produtoId: ID, quantidade: 1, estadoPeca: 'BOA' as const };
const casos: [PedidoDeChamada, string, unknown?][] = [
  [{ operacao: 'acertos.pedir', id: ID }, 'POST /api/vendedor/acertos', { id: ID }],
  [{ operacao: 'acertos.agora', id: ID }, `GET /api/vendedor/acertos/${ID}`],
  [
    { operacao: 'acertos.ler', id: ID, leitura },
    `POST /api/vendedor/acertos/${ID}/leituras`,
    leitura,
  ],
];

describe('pedir e contar o acerto pela API', () => {
  it.each(casos)('%j', async (pedido, esperado, corpo) => {
    const pedidos: Request[] = [];
    const fetch = (p: Request | string | URL): Promise<Response> => {
      pedidos.push(p as Request);
      return Promise.resolve(
        new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      );
    };
    const r = await criarApiDoVendas({ ...base, fetch }).chamar('token', pedido);
    expect(r.ok).toBe(true);
    const url = new URL(pedidos[0]?.url ?? '');
    expect(`${pedidos[0]?.method} ${url.pathname}${url.search}`).toBe(esperado);
    if (corpo !== undefined) expect(await pedidos[0]?.clone().json()).toEqual(corpo);
  });

  it('sem internet a falha é "sem-conexao", sem guardar nada', async () => {
    const fetch = () => Promise.reject(new TypeError('fetch failed'));
    const r = await criarApiDoVendas({ ...base, fetch }).chamar('token', casos[0]![0]);
    expect(r).toMatchObject({ ok: false, falha: { tipo: 'sem-conexao' } });
  });

  it('o erro do servidor volta em português, como veio', async () => {
    const fetch = () =>
      Promise.resolve(
        new Response(JSON.stringify({ detail: 'Termine de enviar as vendas antes.' }), {
          status: 409,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    const r = await criarApiDoVendas({ ...base, fetch }).chamar('token', casos[0]![0]);
    expect(r).toMatchObject({
      ok: false,
      falha: { tipo: 'recusada', status: 409, mensagem: 'Termine de enviar as vendas antes.' },
    });
  });

  it.each(casos.map((c) => [c[0].operacao, c[0]] as const))(
    '%s nunca é consulta local (não lê o banco do computador)',
    (_n, pedido) => {
      expect(ehConsultaLocal(pedido)).toBe(false);
    },
  );
});
