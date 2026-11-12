import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { TamanhoDeFoto } from '../../shared/operacoes';
import { aplicarLote } from '../sync/aplicador-do-pull';
import { criarBancoDeTeste, idDeTeste, type BancoDeTeste } from '../sync/testes/banco-de-teste';
import type { Resultado } from '../tipos';
import { CacheDeFotos } from './cache-de-fotos';
import { FilaDeFotos } from './fila-de-fotos';
import { ServicoDeFotos } from './servico-de-fotos';
import type { RespostaDaFoto, TransporteDeFotos } from './transporte-de-fotos';

const sha = (b: Buffer | string) => createHash('sha256').update(b).digest('hex');
const hashDe = (n: number | string) => sha(`foto-${n}`);
const TAMANHO_EM_BYTES: Record<TamanhoDeFoto, number> = {
  miniatura: 6_000,
  media: 40_000,
  grande: 300_000,
};
const conteudo = (hash: string, tamanho: TamanhoDeFoto) =>
  Buffer.alloc(TAMANHO_EM_BYTES[tamanho], `${hash}${tamanho}`);

/** Servidor de fotos de mentira: guarda a ordem dos pedidos e deixa o teste derrubar, estragar ou esconder fotos. */
class ServidorDeFotos implements TransporteDeFotos {
  pedidos: { hash: string; tamanho: TamanhoDeFoto }[] = [];
  semInternet = false;
  /** Cai a partir do pedido N+1. */
  cairDepoisDe: number | null = null;
  estragar = new Set<string>();
  naoExiste = new Set<string>();
  emAndamento = 0;
  maximoAoMesmoTempo = 0;
  concluidos = 0;

  async baixar(
    _token: string,
    hash: string,
    tamanho: TamanhoDeFoto,
  ): Promise<Resultado<RespostaDaFoto>> {
    this.pedidos.push({ hash, tamanho });
    if (this.semInternet) return { ok: false, falha: { tipo: 'sem-conexao' } };
    if (this.cairDepoisDe !== null && this.pedidos.length > this.cairDepoisDe) {
      return { ok: false, falha: { tipo: 'sem-conexao' } };
    }
    this.emAndamento++;
    this.maximoAoMesmoTempo = Math.max(this.maximoAoMesmoTempo, this.emAndamento);
    await new Promise((r) => setTimeout(r, 1));
    this.emAndamento--;
    if (this.naoExiste.has(hash)) return { ok: true, dados: { situacao: 'inexistente' } };
    this.concluidos++;
    const bytes = conteudo(hash, tamanho);
    const certo = sha(bytes);
    if (this.estragar.has(hash)) {
      const ruim = Buffer.from(bytes);
      ruim[0] = ruim[0]! ^ 0xff;
      return { ok: true, dados: { situacao: 'baixada', foto: { bytes: ruim, sha256: certo } } };
    }
    return { ok: true, dados: { situacao: 'baixada', foto: { bytes, sha256: certo } } };
  }
}

let banco: BancoDeTeste;
let pasta: string;
let cache: CacheDeFotos;
let servidor: ServidorDeFotos;
let fila: FilaDeFotos;
let mudancas: number;

const comToken = <T>(f: (t: string) => Promise<Resultado<T>>) => f('token');

function criarFila(): FilaDeFotos {
  return new FilaDeFotos({
    banco: () => banco.db,
    transporte: servidor,
    comToken,
    cache,
    aoMudar: () => {
      mudancas++;
    },
  });
}

/** Uma maleta aberta com uma peça por foto. */
function maletaComFotos(
  hashes: string[],
  status = 'ENVIADA',
): { remessaId: string; produtos: Record<string, string> } {
  const remessaId = idDeTeste(1);
  const linhas: unknown[] = [
    {
      tabela: 'remessas',
      id: remessaId,
      version: 1,
      deletedAt: null,
      campos: { status },
    },
  ];
  const produtos: Record<string, string> = {};
  for (const h of hashes) {
    const produtoId = idDeTeste(2);
    produtos[h] = produtoId;
    linhas.push(
      {
        tabela: 'itens_remessa',
        id: idDeTeste(4),
        version: 1,
        deletedAt: null,
        campos: {
          remessa_id: remessaId,
          produto_id: produtoId,
          qtd_enviada: 1,
          preco_remessa: '10.00',
        },
      },
      {
        tabela: 'produtos_fotos',
        id: idDeTeste(5),
        version: 1,
        deletedAt: null,
        campos: { produto_id: produtoId, arquivo_hash: h, principal: 1, ordem: 0 },
      },
    );
  }
  aplicarLote(banco.db, linhas, { cursor: null });
  return { remessaId, produtos };
}

beforeEach(() => {
  banco = criarBancoDeTeste();
  pasta = mkdtempSync(join(tmpdir(), 'corona-fotos-'));
  cache = new CacheDeFotos({ pasta: join(pasta, 'fotos') });
  servidor = new ServidorDeFotos();
  fila = criarFila();
  mudancas = 0;
});
afterEach(() => {
  banco.fechar();
  rmSync(pasta, { recursive: true, force: true });
});

describe('cache em disco', () => {
  it('grava em arquivo temporário e renomeia: no fim só existe o arquivo final', () => {
    const h = hashDe(1);
    const bytes = conteudo(h, 'miniatura');
    expect(cache.gravar(h, 'miniatura', bytes, sha(bytes))).toBe('gravada');
    expect(readdirSync(join(cache.pasta, h))).toEqual(['miniatura.jpg']);
    expect(cache.ler(h, 'miniatura')?.equals(bytes)).toBe(true);
  });

  it('SHA-256 que não bate: nada fica no disco', () => {
    const h = hashDe(2);
    expect(cache.gravar(h, 'media', conteudo(h, 'media'), sha('outra coisa'))).toBe('corrompida');
    expect(cache.tem(h, 'media')).toBe(false);
    expect(existsSync(join(cache.pasta, h))).toBe(false);
  });

  it('arquivo parcial de uma queda não vale e é limpo', () => {
    const h = hashDe(3);
    mkdirSync(join(cache.pasta, h), { recursive: true });
    writeFileSync(join(cache.pasta, h, 'media.jpg.ab12cd34.parcial'), 'meio');
    expect(cache.tem(h, 'media')).toBe(false);
    cache.limparParciais();
    expect(readdirSync(join(cache.pasta, h))).toEqual([]);
  });

  it('recusa hash que não seja SHA-256 (nunca sai da pasta de fotos)', () => {
    expect(cache.caminho('../../fora', 'media')).toBeNull();
    expect(cache.gravar('../../fora', 'media', Buffer.from('x'), null)).toBe('corrompida');
  });

  it('o grande passa do limite: sai o menos usado', async () => {
    const pequeno = new CacheDeFotos({
      pasta: join(pasta, 'pequeno'),
      limiteGrandeBytes: 700_000,
    });
    const [a, b, c] = [hashDe('a'), hashDe('b'), hashDe('c')] as [string, string, string];
    pequeno.gravar(a, 'grande', conteudo(a, 'grande'), null);
    await new Promise((r) => setTimeout(r, 20));
    pequeno.gravar(b, 'grande', conteudo(b, 'grande'), null);
    await new Promise((r) => setTimeout(r, 20));
    pequeno.ler(a, 'grande'); // a foi usada de novo: agora a menos usada é a b
    await new Promise((r) => setTimeout(r, 20));
    pequeno.gravar(c, 'grande', conteudo(c, 'grande'), null);
    expect(pequeno.tem(a, 'grande')).toBe(true);
    expect(pequeno.tem(b, 'grande')).toBe(false);
    expect(pequeno.tem(c, 'grande')).toBe(true);
    expect(pequeno.bytesDosGrandes()).toBeLessThanOrEqual(700_000);
  });
});

describe('fila de download', () => {
  it('baixa a miniatura de tudo e só depois a média, até 3 ao mesmo tempo', async () => {
    const hashes = [1, 2, 3, 4, 5, 6, 7].map(hashDe);
    maletaComFotos(hashes);
    await fila.rodar();
    const tamanhos = servidor.pedidos.map((p) => p.tamanho);
    expect(tamanhos.slice(0, 7)).toEqual(Array(7).fill('miniatura'));
    expect(tamanhos.slice(7)).toEqual(Array(7).fill('media'));
    expect(tamanhos).not.toContain('grande');
    expect(servidor.maximoAoMesmoTempo).toBeGreaterThan(1);
    expect(servidor.maximoAoMesmoTempo).toBeLessThanOrEqual(3);
    expect(fila.progresso()).toEqual({ baixadas: 7, total: 7 });
    for (const h of hashes) {
      expect(cache.tem(h, 'miniatura') && cache.tem(h, 'media')).toBe(true);
    }
  });

  it('só baixa fotos de maletas abertas (rascunho e acertada ficam de fora)', async () => {
    maletaComFotos([hashDe('aberta')]);
    maletaComFotos([hashDe('acertada')], 'ACERTADA');
    await fila.rodar();
    expect(new Set(servidor.pedidos.map((p) => p.hash))).toEqual(new Set([hashDe('aberta')]));
  });

  it('queda no meio do download: o que chegou fica, o resto recomeça no próximo ciclo', async () => {
    const hashes = [1, 2, 3, 4, 5].map(hashDe);
    maletaComFotos(hashes);
    servidor.cairDepoisDe = 3;
    await fila.rodar();
    expect(fila.progresso().total).toBe(5);
    expect(fila.progresso().baixadas).toBe(0); // só 3 miniaturas, nenhuma média
    expect(hashes.filter((h) => cache.tem(h, 'miniatura'))).toHaveLength(3);
    expect(cache.hashesNoDisco().flatMap((h) => readdirSync(join(cache.pasta, h)))).not.toEqual(
      expect.arrayContaining([expect.stringContaining('.parcial')]),
    );

    servidor.cairDepoisDe = null;
    servidor.pedidos = [];
    await fila.rodar();
    // Retomada: as 3 miniaturas que já estavam não são pedidas de novo.
    const miniaturasPedidas = servidor.pedidos.filter((p) => p.tamanho === 'miniatura');
    expect(miniaturasPedidas).toHaveLength(2);
    expect(fila.progresso()).toEqual({ baixadas: 5, total: 5 });
  });

  it('um arquivo parcial deixado por uma queda é apagado e baixado de novo', async () => {
    const h = hashDe('p');
    maletaComFotos([h]);
    mkdirSync(join(cache.pasta, h), { recursive: true });
    writeFileSync(join(cache.pasta, h, 'miniatura.jpg.0011aabb.parcial'), 'metade');
    await fila.rodar();
    expect(readdirSync(join(cache.pasta, h)).sort()).toEqual(['media.jpg', 'miniatura.jpg']);
  });

  it('arquivo corrompido: não vale, tenta de novo e nunca fica no disco', async () => {
    const ruim = hashDe('ruim');
    const boa = hashDe('boa');
    maletaComFotos([ruim, boa]);
    servidor.estragar.add(ruim);
    await fila.rodar();
    expect(cache.tem(ruim, 'miniatura')).toBe(false);
    expect(cache.tem(ruim, 'media')).toBe(false);
    expect(
      servidor.pedidos.filter((p) => p.hash === ruim && p.tamanho === 'miniatura'),
    ).toHaveLength(3);
    // A foto boa não é prejudicada.
    expect(cache.tem(boa, 'miniatura') && cache.tem(boa, 'media')).toBe(true);
    expect(fila.progresso()).toEqual({ baixadas: 1, total: 2 });

    // O servidor se corrige: no ciclo seguinte a foto chega.
    servidor.estragar.clear();
    await fila.rodar();
    expect(fila.progresso()).toEqual({ baixadas: 2, total: 2 });
  });

  it('foto que o servidor não tem (404) não trava as outras nem é pedida de novo', async () => {
    const sumiu = hashDe('sumiu');
    const ok = hashDe('ok');
    maletaComFotos([sumiu, ok]);
    servidor.naoExiste.add(sumiu);
    await fila.rodar();
    servidor.pedidos = [];
    await fila.rodar();
    expect(servidor.pedidos).toEqual([]);
    expect(cache.tem(ok, 'media')).toBe(true);
  });

  it('troca de foto sem internet: o metadado novo chega, a antiga some e a nova baixa quando voltar', async () => {
    const velha = hashDe('velha');
    const nova = hashDe('nova');
    const { produtos } = maletaComFotos([velha]);
    await fila.rodar();
    expect(cache.tem(velha, 'media')).toBe(true);

    // Sem internet, o pull que trouxe a troca já foi aplicado (a foto velha foi excluída, a nova chegou).
    servidor.semInternet = true;
    aplicarLote(
      banco.db,
      [
        {
          tabela: 'produtos_fotos',
          id: idDeTeste(5),
          version: 1,
          deletedAt: null,
          campos: { produto_id: produtos[velha], arquivo_hash: nova, principal: 1, ordem: 0 },
        },
      ],
      { cursor: null },
    );
    banco.db
      .prepare('UPDATE produtos_fotos SET deleted_at = ? WHERE arquivo_hash = ?')
      .run('2026-11-12T10:00:00Z', velha);
    await fila.rodar();
    expect(cache.tem(velha, 'media')).toBe(false); // a antiga saiu do disco
    expect(cache.tem(nova, 'media')).toBe(false); // a nova ainda não tem como chegar
    expect(fila.progresso()).toEqual({ baixadas: 0, total: 1 });

    servidor.semInternet = false;
    await fila.rodar();
    expect(cache.tem(nova, 'miniatura') && cache.tem(nova, 'media')).toBe(true);
    expect(fila.progresso()).toEqual({ baixadas: 1, total: 1 });
  });

  it('cancelamento ou acerto da maleta: miniatura e média saem do disco', async () => {
    const h = hashDe('maleta');
    const { remessaId } = maletaComFotos([h]);
    await fila.rodar();
    expect(cache.tem(h, 'media')).toBe(true);
    banco.db.prepare("UPDATE remessas SET status = 'CANCELADA' WHERE id = ?").run(remessaId);
    await fila.rodar();
    expect(cache.hashesNoDisco()).toEqual([]);
    expect(fila.progresso()).toEqual({ baixadas: 0, total: 0 });
  });

  it('a foto que uma maleta aberta ainda usa não sai quando outra maleta é cancelada', async () => {
    const h = hashDe('dividida');
    const a = maletaComFotos([h]);
    maletaComFotos([h]);
    await fila.rodar();
    banco.db.prepare("UPDATE remessas SET status = 'CANCELADA' WHERE id = ?").run(a.remessaId);
    await fila.rodar();
    expect(cache.tem(h, 'media')).toBe(true);
  });

  it('duas chamadas ao mesmo tempo viram uma passada e outra logo depois, nunca duas juntas', async () => {
    maletaComFotos([1, 2, 3, 4].map(hashDe));
    await Promise.all([fila.rodar(), fila.rodar(), fila.rodar()]);
    expect(servidor.pedidos).toHaveLength(8);
    expect(servidor.maximoAoMesmoTempo).toBeLessThanOrEqual(3);
  });

  it('avisa a tela cada vez que o número de fotos prontas muda', async () => {
    maletaComFotos([1, 2, 3].map(hashDe));
    await fila.rodar();
    expect(mudancas).toBeGreaterThanOrEqual(2);
  });
});

describe('fotos.ver', () => {
  it('com cópia no disco responde sem rede e em menos de 100 ms', async () => {
    const h = hashDe('ver');
    const bytes = conteudo(h, 'media');
    cache.gravar(h, 'media', bytes, null);
    const servico = new ServicoDeFotos({
      cache,
      transporte: servidor,
      comToken,
      comInternet: () => false,
    });
    const inicio = performance.now();
    const r = await servico.ver(h, 'media');
    const gasto = performance.now() - inicio;
    expect(r.ok && r.dados.dataUrl).toBe(`data:image/jpeg;base64,${bytes.toString('base64')}`);
    expect(gasto).toBeLessThan(100);
    expect(servidor.pedidos).toEqual([]);
  });

  it('venda sem foto: sem cópia e sem internet a resposta é uma falha comum, na hora', async () => {
    const servico = new ServicoDeFotos({
      cache,
      transporte: servidor,
      comToken,
      comInternet: () => false,
    });
    const inicio = performance.now();
    const r = await servico.ver(hashDe('nenhuma'), 'miniatura');
    expect(r.ok).toBe(false);
    expect(performance.now() - inicio).toBeLessThan(100);
    expect(servidor.pedidos).toEqual([]);
  });

  it('o grande é baixado sob demanda, com internet, e fica guardado', async () => {
    const h = hashDe('grande');
    const servico = new ServicoDeFotos({
      cache,
      transporte: servidor,
      comToken,
      comInternet: () => true,
    });
    const r = await servico.ver(h, 'grande');
    expect(r.ok).toBe(true);
    expect(cache.tem(h, 'grande')).toBe(true);
    servidor.semInternet = true;
    expect((await servico.ver(h, 'grande')).ok).toBe(true);
  });

  it('o grande que chegou estragado não é mostrado nem guardado', async () => {
    const h = hashDe('grande-ruim');
    servidor.estragar.add(h);
    const servico = new ServicoDeFotos({
      cache,
      transporte: servidor,
      comToken,
      comInternet: () => true,
    });
    expect((await servico.ver(h, 'grande')).ok).toBe(false);
    expect(cache.tem(h, 'grande')).toBe(false);
  });
});

describe('500 produtos com 5 fotos', () => {
  it('baixa tudo, mede o disco e o tempo e a leitura segue abaixo de 100 ms', async () => {
    const hashes = Array.from({ length: 2_500 }, (_, i) => hashDe(`g${i}`));
    maletaComFotos(hashes);
    const inicio = performance.now();
    await fila.rodar();
    const segundos = (performance.now() - inicio) / 1000;
    expect(fila.progresso()).toEqual({ baixadas: 2_500, total: 2_500 });
    const mb = cache.bytesTotais() / 1024 / 1024;
    // 2.500 fotos x (miniatura 6 KB + média 40 KB) = cerca de 112 MB
    expect(mb).toBeGreaterThan(100);
    expect(mb).toBeLessThan(130);

    const servico = new ServicoDeFotos({
      cache,
      transporte: servidor,
      comToken,
      comInternet: () => false,
    });
    const tempos: number[] = [];
    for (let i = 0; i < 300; i++) {
      const t = performance.now();
      const r = await servico.ver(hashes[(i * 7) % hashes.length]!, i % 2 ? 'media' : 'miniatura');
      tempos.push(performance.now() - t);
      expect(r.ok).toBe(true);
    }
    tempos.sort((a, b) => a - b);
    console.info(
      `[medida 5.9] 500 produtos x 5 fotos (miniatura 6 KB + média 40 KB por foto): ${mb.toFixed(1)} MB em disco, ` +
        `${segundos.toFixed(1)} s para baixar (servidor de mentira, 1 ms por foto), leitura: ` +
        `mediana ${tempos[150]!.toFixed(2)} ms, pior ${tempos[299]!.toFixed(2)} ms`,
    );
    expect(tempos[299]!).toBeLessThan(100);
  }, 120_000);
});
