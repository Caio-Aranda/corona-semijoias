import { createServer, request, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import {
  iniciarProxyDeRedeRuim,
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

function chamar(p: ProxyDeRedeRuim, corpo = '{"a":1}'): Promise<Resposta> {
  return new Promise((ok, erro) => {
    const r = request(
      {
        host: '127.0.0.1',
        port: p.porta,
        method: 'POST',
        path: '/x',
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
});
