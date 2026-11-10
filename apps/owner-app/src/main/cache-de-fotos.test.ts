import { mkdtemp, readdir, rm, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { criarApiDoGestao } from './api';
import { CacheDeFotos, LimiteDeBuscas } from './cache-de-fotos';

const H1 = 'a'.repeat(64);
const H2 = 'b'.repeat(64);
const H3 = 'c'.repeat(64);
const jpeg = (n: number) => new Uint8Array(n).fill(7);

let pasta: string;

beforeEach(async () => {
  pasta = join(await mkdtemp(join(tmpdir(), 'corona-fotos-')), 'fotos');
});
afterEach(async () => {
  await rm(join(pasta, '..'), { recursive: true, force: true });
});

describe('cache de fotos em disco', () => {
  it('guarda e devolve a foto; o que não foi guardado volta vazio', async () => {
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1000 });
    expect(await cache.ler(H1, 'miniatura')).toBeNull();
    await cache.gravar(H1, 'miniatura', jpeg(10));
    const foto = await cache.ler(H1, 'miniatura');
    expect(foto?.bytes).toEqual(jpeg(10));
    expect(foto?.tipo).toBe('image/jpeg');
    expect(await cache.ler(H1, 'media')).toBeNull();
  });

  it('hash ou tamanho fora do formato nunca viram caminho de arquivo', async () => {
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1000 });
    await cache.gravar('../../fora', 'miniatura', jpeg(10));
    await cache.gravar(H1, '../x', jpeg(10));
    expect(await cache.ler('../../fora', 'miniatura')).toBeNull();
    await expect(readdir(pasta)).rejects.toThrow();
  });

  it('passou do limite: descarta o mais antigo e guarda o novo', async () => {
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 25 });
    await cache.gravar(H1, 'miniatura', jpeg(10));
    await utimes(join(pasta, `${H1}.miniatura`), new Date(2020, 1, 1), new Date(2020, 1, 1));
    await cache.gravar(H2, 'miniatura', jpeg(10));
    await utimes(join(pasta, `${H2}.miniatura`), new Date(2021, 1, 1), new Date(2021, 1, 1));
    await cache.gravar(H3, 'miniatura', jpeg(10));
    expect(await cache.ler(H1, 'miniatura')).toBeNull();
    expect(await cache.ler(H2, 'miniatura')).not.toBeNull();
    expect(await cache.ler(H3, 'miniatura')).not.toBeNull();
  });

  it('foto maior que o limite inteiro não é guardada', async () => {
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 5 });
    await cache.gravar(H1, 'grande', jpeg(10));
    expect(await cache.ler(H1, 'grande')).toBeNull();
  });

  it('limpar apaga tudo', async () => {
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1000 });
    await cache.gravar(H1, 'miniatura', jpeg(10));
    await cache.limpar();
    expect(await cache.ler(H1, 'miniatura')).toBeNull();
  });

  it('arquivo corrompido (vazio) é tratado como ausente', async () => {
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1000 });
    await cache.gravar(H1, 'miniatura', jpeg(10));
    await writeFile(join(pasta, `${H1}.miniatura`), new Uint8Array(0));
    expect(await cache.ler(H1, 'miniatura')).toBeNull();
  });
});

describe('limite de buscas ao mesmo tempo', () => {
  it('nunca passa de 4 em andamento e todas terminam', async () => {
    const limite = new LimiteDeBuscas(4);
    let agora = 0;
    let maximo = 0;
    const tarefa = async () => {
      agora += 1;
      maximo = Math.max(maximo, agora);
      await new Promise((r) => setTimeout(r, 5));
      agora -= 1;
      return 1;
    };
    const resultados = await Promise.all(Array.from({ length: 20 }, () => limite.executar(tarefa)));
    expect(resultados).toHaveLength(20);
    expect(maximo).toBe(4);
  });
});

describe('foto pela API com servidor de 150 ms', () => {
  function servidorLento() {
    const chamadas: string[] = [];
    const fetchLento = async (pedido: Request | string | URL): Promise<Response> => {
      chamadas.push((pedido as Request).url);
      await new Promise((r) => setTimeout(r, 150));
      return new Response(new Uint8Array([9, 8, 7]), {
        status: 200,
        headers: { 'Content-Type': 'image/jpeg' },
      });
    };
    return { chamadas, fetch: fetchLento };
  }
  const pedido = { operacao: 'produtos.fotos.imagem', hash: H1, tamanho: 'miniatura' } as const;

  it('a 2ª exibição da mesma foto não faz chamada de rede e é bem mais rápida', async () => {
    const { chamadas, fetch } = servidorLento();
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1_000_000 });
    const api = criarApiDoGestao({
      baseUrl: 'http://api.teste',
      versaoDoApp: '1.0.0',
      fetch,
      cacheDeFotos: cache,
    });

    const t1 = performance.now();
    const primeira = await api.executar('tk', pedido);
    const tempoPrimeira = performance.now() - t1;
    const t2 = performance.now();
    const segunda = await api.executar('tk', pedido);
    const tempoSegunda = performance.now() - t2;

    expect(primeira.ok && segunda.ok).toBe(true);
    expect(chamadas).toHaveLength(1);
    expect(tempoPrimeira).toBeGreaterThanOrEqual(140);
    expect(tempoSegunda).toBeLessThan(50);
    expect((segunda as { dados: { bytes: Uint8Array } }).dados.bytes).toEqual(
      new Uint8Array([9, 8, 7]),
    );
    console.info(
      `[fotos] 1ª exibição: ${tempoPrimeira.toFixed(0)} ms (servidor simulado de 150 ms); ` +
        `2ª exibição: ${tempoSegunda.toFixed(1)} ms, sem rede`,
    );
  });

  it('depois de limpar o cache (sair da sessão) a foto volta a ser buscada', async () => {
    const { chamadas, fetch } = servidorLento();
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1_000_000 });
    const api = criarApiDoGestao({
      baseUrl: 'http://api.teste',
      versaoDoApp: '1.0.0',
      fetch,
      cacheDeFotos: cache,
    });
    await api.executar('tk', pedido);
    await cache.limpar();
    await api.executar('tk', pedido);
    expect(chamadas).toHaveLength(2);
  });

  it('resposta com erro não vai para o cache', async () => {
    const chamadas: string[] = [];
    const fetchComErro = ((p: Request) => {
      chamadas.push(p.url);
      return Promise.resolve(
        new Response('{}', { status: 404, headers: { 'Content-Type': 'application/json' } }),
      );
    }) as unknown as typeof fetch;
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1_000_000 });
    const api = criarApiDoGestao({
      baseUrl: 'http://api.teste',
      versaoDoApp: '1.0.0',
      fetch: fetchComErro,
      cacheDeFotos: cache,
    });
    const r = await api.executar('tk', pedido);
    expect(r.ok).toBe(false);
    expect(await cache.ler(H1, 'miniatura')).toBeNull();
  });

  it('muitas fotos ao mesmo tempo: no máximo 4 chamadas em andamento', async () => {
    let agora = 0;
    let maximo = 0;
    const fetchContando = (async () => {
      agora += 1;
      maximo = Math.max(maximo, agora);
      await new Promise((r) => setTimeout(r, 20));
      agora -= 1;
      return new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: { 'Content-Type': 'image/jpeg' },
      });
    }) as unknown as typeof fetch;
    const cache = new CacheDeFotos({ pasta, limiteEmBytes: 1_000_000 });
    const api = criarApiDoGestao({
      baseUrl: 'http://api.teste',
      versaoDoApp: '1.0.0',
      fetch: fetchContando,
      cacheDeFotos: cache,
    });
    await Promise.all(
      Array.from({ length: 12 }, (_, i) =>
        api.executar('tk', { ...pedido, hash: i.toString(16).padStart(64, '0') }),
      ),
    );
    expect(maximo).toBe(4);
  });
});
