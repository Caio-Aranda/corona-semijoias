import { describe, expect, it } from 'vitest';
import { criarApiDoVendas } from './api';

function falso(respostas: { status: number; corpo: unknown }[] | { erro: Error }): {
  pedidos: Request[];
  fetch: typeof fetch;
} {
  const pedidos: Request[] = [];
  let i = 0;
  const fetchFalso = (pedido: Request | string | URL): Promise<Response> => {
    pedidos.push(pedido as Request);
    if ('erro' in respostas) return Promise.reject(respostas.erro);
    const r = respostas[Math.min(i++, respostas.length - 1)] ?? { status: 500, corpo: {} };
    return Promise.resolve(
      new Response(JSON.stringify(r.corpo), {
        status: r.status,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  };
  return { pedidos, fetch: fetchFalso };
}

const DISPOSITIVO = '3f2b8c1e-5d4a-4b7e-9c1d-2a6f8e0b4c11';
const base = { baseUrl: 'http://api.teste', versaoDoApp: '1.0.0', dispositivoId: DISPOSITIVO };

describe('API do Corona Vendas (processo principal)', () => {
  it('entrar manda os cabeçalhos de tipo e versão e devolve as credenciais', async () => {
    const { pedidos, fetch } = falso([
      {
        status: 200,
        corpo: {
          tokenDeAcesso: 'a',
          tokenDeRenovacao: 'r',
          validadeEmSegundos: 900,
          trocaDeSenhaObrigatoria: false,
        },
      },
    ]);
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.entrar('maria', 'senha');
    expect(r).toEqual({
      ok: true,
      dados: {
        tokenDeAcesso: 'a',
        tokenDeRenovacao: 'r',
        validadeEmSegundos: 900,
        trocaDeSenhaObrigatoria: false,
      },
    });
    expect(pedidos[0]?.headers.get('X-App-Tipo')).toBe('vendas');
    expect(pedidos[0]?.headers.get('X-App-Versao')).toBe('1.0.0');
    expect(pedidos[0]?.headers.get('Authorization')).toBeNull();
    expect(await pedidos[0]?.clone().json()).toEqual({
      login: 'maria',
      senha: 'senha',
      dispositivoId: DISPOSITIVO,
    });
  });

  it('chamadas com sessão levam o token no cabeçalho Authorization', async () => {
    const { pedidos, fetch } = falso([{ status: 200, corpo: { nome: 'Maria', login: 'maria' } }]);
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.eu('token-de-acesso');
    expect(pedidos[0]?.headers.get('Authorization')).toBe('Bearer token-de-acesso');
    expect(r).toEqual({ ok: true, dados: { nome: 'Maria' } });
  });

  it('erro da API: guarda a mensagem do servidor, que é o que a tela mostra', async () => {
    const { fetch } = falso([
      {
        status: 401,
        corpo: {
          codigo: 'LOGIN_INVALIDO',
          detail: 'Não foi possível entrar. Confira o que digitou.',
        },
      },
    ]);
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.entrar('maria', 'errada');
    expect(r).toEqual({
      ok: false,
      falha: {
        tipo: 'recusada',
        status: 401,
        codigo: 'LOGIN_INVALIDO',
        mensagem: 'Não foi possível entrar. Confira o que digitou.',
      },
    });
  });

  it('recusa com campos: guarda o motivo de cada campo para a tela mostrar', async () => {
    const { fetch } = falso([
      {
        status: 400,
        corpo: {
          codigo: 'DADOS_INVALIDOS',
          detail: 'Alguns dados não estão corretos.',
          campos: [
            { campo: 'data', mensagem: 'A data não pode ser no futuro.' },
            { campo: 'x', mensagem: 3 },
          ],
        },
      },
    ]);
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.entrar('maria', 'senha');
    expect(r).toEqual({
      ok: false,
      falha: {
        tipo: 'recusada',
        status: 400,
        codigo: 'DADOS_INVALIDOS',
        mensagem: 'Alguns dados não estão corretos.',
        campos: [{ campo: 'data', mensagem: 'A data não pode ser no futuro.' }],
      },
    });
  });

  it('aplicativo desatualizado (426) vira uma falha própria com a mensagem da API', async () => {
    const { fetch } = falso([
      {
        status: 426,
        corpo: { codigo: 'APP_DESATUALIZADO', detail: 'Seu aplicativo está desatualizado.' },
      },
    ]);
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.entrar('maria', 'senha');
    expect(r).toEqual({
      ok: false,
      falha: { tipo: 'app-desatualizado', mensagem: 'Seu aplicativo está desatualizado.' },
    });
  });

  it('sem internet: falha de conexão, sem mensagem inventada', async () => {
    const { fetch } = falso({ erro: new TypeError('fetch failed') });
    const api = criarApiDoVendas({ ...base, fetch });
    expect(await api.entrar('maria', 'senha')).toEqual({
      ok: false,
      falha: { tipo: 'sem-conexao' },
    });
  });

  it('resposta de login sem token de acesso não é aceita', async () => {
    const { fetch } = falso([{ status: 200, corpo: {} }]);
    const api = criarApiDoVendas({ ...base, fetch });
    expect(await api.entrar('maria', 'senha')).toEqual({
      ok: false,
      falha: { tipo: 'resposta-invalida' },
    });
  });

  it('renovar manda o mesmo identificador do aparelho no corpo', async () => {
    const { pedidos, fetch } = falso([
      {
        status: 200,
        corpo: { tokenDeAcesso: 'a2', tokenDeRenovacao: 'r2', validadeEmSegundos: 900 },
      },
    ]);
    const api = criarApiDoVendas({ ...base, fetch });
    await api.renovar('r1');
    expect(await pedidos[0]?.clone().json()).toEqual({
      tokenDeRenovacao: 'r1',
      dispositivoId: DISPOSITIVO,
    });
  });

  it('perfil errado (APP_INCOMPATIVEL): a tela recebe a mensagem que a API mandou', async () => {
    const mensagem =
      'Este é o Corona Vendas. Seu acesso é de dona da loja. Para entrar, abra o Corona Gestão.';
    const { fetch } = falso([
      { status: 403, corpo: { codigo: 'APP_INCOMPATIVEL', detail: mensagem } },
    ]);
    const api = criarApiDoVendas({ ...base, fetch });
    expect(await api.entrar('dona', 'senha')).toEqual({
      ok: false,
      falha: { tipo: 'recusada', status: 403, codigo: 'APP_INCOMPATIVEL', mensagem },
    });
  });

  it('guarda a hora do servidor e o prazo offline que vieram no login', async () => {
    const { fetch } = falso([
      {
        status: 200,
        corpo: {
          tokenDeAcesso: 'a',
          tokenDeRenovacao: 'r',
          validadeEmSegundos: 900,
          horaDoServidor: '2026-11-09T12:00:00Z',
          tempoMaximoOfflineDias: 10,
        },
      },
    ]);
    const api = criarApiDoVendas({ ...base, fetch });
    const r = await api.entrar('maria', 'senha');
    expect(r.ok && r.dados.horaDoServidor).toBe('2026-11-09T12:00:00Z');
    expect(r.ok && r.dados.tempoMaximoOfflineDias).toBe(10);
  });
});
