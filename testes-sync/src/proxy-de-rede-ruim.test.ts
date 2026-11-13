import { createServer, request, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import {
  iniciarProxyDeRedeRuim,
  PLANO_BOM,
  PLANO_SEM_INTERNET,
  type FalhaDaRede,
  type ProxyDeRedeRuim,
} from './proxy-de-rede-ruim';
import { criarSorteio } from './sorteio';

interface Resposta {
  status: number;
  corpo: string;
}

const abertos: Array<{ close: () => Promise<void> | void }> = [];
afterEach(async () => {
  while (abertos.length) await abertos.pop()!.close();
});

/** Servidor de verdade que conta quantas vezes gravou. */
async function servidorFalso(): Promise<{ porta: number; gravou: () => number }> {
  let gravou = 0;
  const s: Server = createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      gravou += 1;
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ n: gravou }));
    });
  });
  await new Promise<void>((ok) => s.listen(0, '127.0.0.1', ok));
  abertos.push({ close: () => new Promise<void>((ok) => s.close(() => ok())) });
  return { porta: (s.address() as AddressInfo).port, gravou: () => gravou };
}

async function proxy(
  porta: number,
  o: Parameters<typeof iniciarProxyDeRedeRuim>[0]['plano'],
  semente = 1,
) {
  const p = await iniciarProxyDeRedeRuim({
    destino: `http://127.0.0.1:${porta}`,
    semente,
    plano: o,
  });
  abertos.push(p);
  return p;
}

function chamar(p: ProxyDeRedeRuim, corpo = '{"a":1}', caminho = '/x'): Promise<Resposta> {
  return new Promise((ok, erro) => {
    const r = request(
      {
        host: '127.0.0.1',
        port: p.porta,
        method: 'POST',
        path: caminho,
        agent: false,
        headers: { 'content-type': 'application/json' },
      },
      (res) => {
        const partes: Buffer[] = [];
        res.on('data', (c: Buffer) => partes.push(c));
        res.on('end', () =>
          ok({ status: res.statusCode ?? 0, corpo: Buffer.concat(partes).toString('utf8') }),
        );
        res.on('aborted', () => erro(new Error('cortada')));
        res.on('error', erro);
      },
    );
    r.on('error', erro);
    r.end(corpo);
  });
}

describe('sorteio com semente', () => {
  it('a mesma semente dá a mesma sequência e outra semente dá outra', () => {
    const a = criarSorteio(42);
    const b = criarSorteio(42);
    const c = criarSorteio(43);
    const sa = Array.from({ length: 8 }, () => a());
    expect(Array.from({ length: 8 }, () => b())).toEqual(sa);
    expect(Array.from({ length: 8 }, () => c())).not.toEqual(sa);
    expect(sa.every((v) => v >= 0 && v < 1)).toBe(true);
  });
});

describe('proxy de rede ruim', () => {
  it('sem falha nenhuma, repassa o pedido e a resposta', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [] });
    const r = await chamar(p);
    expect(r.status).toBe(200);
    expect(JSON.parse(r.corpo)).toEqual({ n: 1 });
    expect(s.gravou()).toBe(1);
  });

  it('queda ANTES de encaminhar: o servidor não recebe nada', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [{ falha: 'QUEDA_ANTES', peso: 1 }] });
    await expect(chamar(p)).rejects.toBeDefined();
    expect(s.gravou()).toBe(0);
  });

  it('corte DEPOIS de o servidor gravar e ANTES da resposta: o servidor gravou, o app não soube', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [{ falha: 'CORTE_DEPOIS_DE_GRAVAR', peso: 1 }] });
    await expect(chamar(p)).rejects.toBeDefined();
    expect(s.gravou()).toBe(1);
  });

  it('502 devolve o erro sem encaminhar', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [{ falha: 'ERRO_502', peso: 1 }] });
    const r = await chamar(p);
    expect(r.status).toBe(502);
    expect(s.gravou()).toBe(0);
  });

  it('atraso segura a resposta pelo menos o tempo pedido', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [], atrasoMs: { minimo: 150, maximo: 150 } });
    const t0 = Date.now();
    const r = await chamar(p);
    expect(r.status).toBe(200);
    expect(Date.now() - t0).toBeGreaterThanOrEqual(140);
  });

  it('banda baixa demora mais para respostas maiores', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [], bytesPorSegundo: 200 });
    const t0 = Date.now();
    await chamar(p, JSON.stringify({ texto: 'x'.repeat(100) }));
    expect(Date.now() - t0).toBeGreaterThanOrEqual(300);
  });

  it('com a mesma semente a sequência de falhas é a mesma (e fica registrada)', async () => {
    const plano = {
      sorteio: [
        { falha: 'QUEDA_ANTES' as FalhaDaRede, peso: 1 },
        { falha: 'ERRO_502' as FalhaDaRede, peso: 1 },
        { falha: 'CORTE_DEPOIS_DE_GRAVAR' as FalhaDaRede, peso: 1 },
      ],
      chanceDeFalha: 0.7,
    };
    const rodar = async (semente: number) => {
      const s = await servidorFalso();
      const p = await proxy(s.porta, plano, semente);
      for (let i = 0; i < 12; i += 1) await chamar(p).catch(() => undefined);
      return p.historico().map((h) => h.falha ?? 'OK');
    };
    const um = await rodar(7);
    const dois = await rodar(7);
    const tres = await rodar(8);
    expect(dois).toEqual(um);
    expect(tres).not.toEqual(um);
    expect(um.some((f) => f !== 'OK')).toBe(true);
  });

  it('roteiro fixo: a falha N-ésima acontece exatamente ali, e a semente é impressa', async () => {
    const s = await servidorFalso();
    const p = await proxy(
      s.porta,
      { sorteio: [], roteiro: [undefined, 'CORTE_DEPOIS_DE_GRAVAR', undefined] },
      99,
    );
    expect((await chamar(p)).status).toBe(200);
    await expect(chamar(p)).rejects.toBeDefined();
    expect((await chamar(p)).status).toBe(200);
    expect(s.gravou()).toBe(3);
    expect(p.semente).toBe(99);
  });

  it('roteiro por caminho: conta só os pedidos daquele caminho, e o plano novo recomeça a conta', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, {
      sorteio: [],
      porCaminho: [{ caminho: '/api/push', roteiro: [undefined, 'CORTE_DEPOIS_DE_GRAVAR'] }],
    });
    expect((await chamar(p, '{}', '/api/saude')).status).toBe(200);
    expect((await chamar(p, '{}', '/api/push?x=1')).status).toBe(200);
    expect((await chamar(p, '{}', '/api/pull')).status).toBe(200);
    await expect(chamar(p, '{}', '/api/push')).rejects.toBeDefined();
    expect((await chamar(p, '{}', '/api/push')).status).toBe(200);
    expect(s.gravou()).toBe(5);
    p.trocarPlano({
      sorteio: [],
      porCaminho: [{ caminho: /^\/api\/pu/, roteiro: ['ERRO_502'] }],
    });
    expect((await chamar(p, '{}', '/api/pull')).status).toBe(502);
    expect((await chamar(p, '{}', '/api/push')).status).toBe(200);
  });

  it('corte no meio da resposta: o servidor respondeu, só metade chegou', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [], roteiro: ['CORTE_NO_MEIO_DA_RESPOSTA'] });
    await expect(chamar(p)).rejects.toBeDefined();
    expect(s.gravou()).toBe(1);
    expect(p.historico()[0]).toMatchObject({
      servidorRespondeu: true,
      falha: 'CORTE_NO_MEIO_DA_RESPOSTA',
    });
    expect((await chamar(p)).status).toBe(200);
  });

  it('sem internet e rede boa como planos prontos', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, PLANO_SEM_INTERNET);
    await expect(chamar(p)).rejects.toBeDefined();
    p.trocarPlano(PLANO_BOM);
    expect((await chamar(p)).status).toBe(200);
    expect(s.gravou()).toBe(1);
  });

  it('o observador vê toda resposta do servidor, inclusive a que a rede cortou', async () => {
    const s = await servidorFalso();
    const vistas: string[] = [];
    const p = await iniciarProxyDeRedeRuim({
      destino: `http://127.0.0.1:${s.porta}`,
      semente: 3,
      plano: { sorteio: [], roteiro: [undefined, 'CORTE_DEPOIS_DE_GRAVAR', 'QUEDA_ANTES'] },
      aoResponder: (r) => vistas.push(`${r.status} ${r.corpo.toString('utf8')}`),
    });
    abertos.push(p);
    await chamar(p);
    await chamar(p).catch(() => undefined);
    await chamar(p).catch(() => undefined);
    expect(vistas).toEqual(['200 {"n":1}', '200 {"n":2}']);
  });

  it('o histórico traz a hora de cada pedido, em ordem (para ler a falha depois, issue #130)', async () => {
    const s = await servidorFalso();
    const p = await proxy(s.porta, { sorteio: [], roteiro: [undefined, 'ERRO_502', undefined] });
    const antes = new Date().toISOString();
    for (let i = 0; i < 3; i += 1) {
      await chamar(p);
      await new Promise((ok) => setTimeout(ok, 5));
    }
    const depois = new Date().toISOString();
    const horas = p.historico().map((h) => String((h as { em?: unknown }).em));
    expect(horas).toHaveLength(3);
    for (const em of horas) {
      expect(em).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
      expect(em >= antes && em <= depois).toBe(true);
    }
    expect([...horas].sort()).toEqual(horas);
  });

  it('troca cabeçalhos no caminho até o servidor', async () => {
    let versao: string | undefined;
    const servidor: Server = createServer((req, res) => {
      versao = req.headers['x-app-versao'] as string | undefined;
      req.resume();
      req.on('end', () => res.end('{}'));
    });
    await new Promise<void>((ok) => servidor.listen(0, '127.0.0.1', ok));
    abertos.push({ close: () => new Promise<void>((ok) => servidor.close(() => ok())) });
    const p = await proxy((servidor.address() as AddressInfo).port, {
      sorteio: [],
      cabecalhos: { 'x-app-versao': '0.9.0' },
    });
    await chamar(p);
    expect(versao).toBe('0.9.0');
  });
});
