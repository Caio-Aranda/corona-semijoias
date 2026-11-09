import { describe, expect, it } from 'vitest';
import {
  criarClienteDaApi,
  ehAppDesatualizado,
  ehTipoDeAppInvalido,
  eu,
  ErroAppDesatualizado,
  ErroTipoDeAppInvalido,
} from '../src';

function falso(status: number, corpo: unknown) {
  const pedidos: Request[] = [];
  const fetchFalso = (pedido: Request | string | URL): Promise<Response> => {
    pedidos.push(pedido as Request);
    return Promise.resolve(
      new Response(JSON.stringify(corpo), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  };
  return { pedidos, fetch: fetchFalso };
}

describe('cliente da API (gestao)', () => {
  it('manda X-App-Tipo e X-App-Versao em toda chamada', async () => {
    const { pedidos, fetch } = falso(200, {});
    const client = criarClienteDaApi({ baseUrl: 'http://api.teste', versaoDoApp: '1.4.2', fetch });
    await eu({ client });
    await eu({ client });
    expect(pedidos).toHaveLength(2);
    for (const p of pedidos) {
      expect(p.headers.get('X-App-Tipo')).toBe('gestao');
      expect(p.headers.get('X-App-Versao')).toBe('1.4.2');
      expect(p.url).toBe('http://api.teste/api/auth/eu');
    }
  });

  it('426 vira ErroAppDesatualizado com a mensagem da API', async () => {
    const { fetch } = falso(426, {
      codigo: 'APP_DESATUALIZADO',
      detail: 'Texto que veio da API.',
      versaoMinima: '1.2.0',
      versaoAtual: '1.1.9',
    });
    const client = criarClienteDaApi({ baseUrl: 'http://api.teste', versaoDoApp: '1.1.9', fetch });
    const { error } = await eu({ client });
    expect(ehAppDesatualizado(error)).toBe(true);
    expect(error).toBeInstanceOf(ErroAppDesatualizado);
    const erro = error as ErroAppDesatualizado;
    expect(erro.message).toBe('Texto que veio da API.');
    expect(erro.status).toBe(426);
    expect(erro.versaoMinima).toBe('1.2.0');
    expect(erro.versaoAtual).toBe('1.1.9');
  });

  it('400 APP_TIPO_INVALIDO vira ErroTipoDeAppInvalido', async () => {
    const { fetch } = falso(400, { codigo: 'APP_TIPO_INVALIDO', detail: 'Mensagem da API.' });
    const client = criarClienteDaApi({ baseUrl: 'http://api.teste', versaoDoApp: '1.0.0', fetch });
    const { error } = await eu({ client });
    expect(ehTipoDeAppInvalido(error)).toBe(true);
    expect(error).toBeInstanceOf(ErroTipoDeAppInvalido);
    expect((error as ErroTipoDeAppInvalido).message).toBe('Mensagem da API.');
  });

  it('outro 400 continua sendo o erro comum da API', async () => {
    const { fetch } = falso(400, { codigo: 'DADOS_INVALIDOS', detail: 'x' });
    const client = criarClienteDaApi({ baseUrl: 'http://api.teste', versaoDoApp: '1.0.0', fetch });
    const { error } = await eu({ client });
    expect(ehTipoDeAppInvalido(error)).toBe(false);
    expect(ehAppDesatualizado(error)).toBe(false);
    expect((error as { codigo: string }).codigo).toBe('DADOS_INVALIDOS');
  });
});
