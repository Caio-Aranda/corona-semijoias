import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PonteDoCorona } from '../../shared/ponte';
import {
  LIMITE_NA_MEMORIA,
  esquecerImagens,
  imagemPronta,
  preBuscarImagens,
  quantasNaMemoria,
  usarImagem,
} from './imagens';

const hash = (n: number) => n.toString(16).padStart(64, '0');

function ponteQueResponde() {
  const chamadas: string[] = [];
  const ponte = {
    chamarApi: vi.fn((p: { parametros?: Record<string, unknown> }) => {
      chamadas.push(`${String(p.parametros?.hash)}/${String(p.parametros?.tamanho)}`);
      return Promise.resolve({
        ok: true,
        dados: { tipo: 'image/jpeg', bytes: new Uint8Array([1, 2]) },
      });
    }),
  } as unknown as PonteDoCorona;
  return { ponte, chamadas };
}

let criadas = 0;
const revogadas: string[] = [];

beforeEach(() => {
  criadas = 0;
  revogadas.length = 0;
  URL.createObjectURL = () => `blob:foto-${++criadas}`;
  URL.revokeObjectURL = (u: string) => {
    revogadas.push(u);
  };
});
afterEach(() => esquecerImagens());

describe('fotos na memória da tela', () => {
  it('a mesma foto é pedida uma única vez, mesmo com vários usos', async () => {
    const { ponte, chamadas } = ponteQueResponde();
    const a = usarImagem(ponte, hash(1), 'miniatura');
    const b = usarImagem(ponte, hash(1), 'miniatura');
    expect(await a.promessa).toBe(await b.promessa);
    expect(chamadas).toEqual([`${hash(1)}/miniatura`]);
  });

  it('a miniatura que já chegou fica disponível na hora', async () => {
    const { ponte } = ponteQueResponde();
    expect(imagemPronta(hash(1), 'miniatura')).toBeNull();
    await usarImagem(ponte, hash(1), 'miniatura').promessa;
    expect(imagemPronta(hash(1), 'miniatura')).toMatch(/^blob:/);
  });

  it('pré-busca as miniaturas da página sem repetir hash e ignorando produto sem foto', async () => {
    const { ponte, chamadas } = ponteQueResponde();
    preBuscarImagens(ponte, [hash(1), hash(2), hash(1), null, undefined]);
    await vi.waitFor(() => expect(quantasNaMemoria()).toBe(2));
    expect(chamadas.sort()).toEqual([`${hash(1)}/miniatura`, `${hash(2)}/miniatura`]);
  });

  it('passou do limite: revoga as mais antigas que ninguém está mostrando, nunca as em uso', async () => {
    const { ponte } = ponteQueResponde();
    const emUso = usarImagem(ponte, hash(0), 'miniatura');
    const urlEmUso = await emUso.promessa;
    for (let i = 1; i <= LIMITE_NA_MEMORIA + 5; i += 1) {
      const u = usarImagem(ponte, hash(i), 'miniatura');
      await u.promessa;
      u.liberar();
    }
    expect(revogadas.length).toBeGreaterThanOrEqual(5);
    expect(revogadas).not.toContain(urlEmUso);
    expect(quantasNaMemoria()).toBeLessThanOrEqual(LIMITE_NA_MEMORIA);
    emUso.liberar();
  });

  it('esquecer revoga tudo', async () => {
    const { ponte } = ponteQueResponde();
    await usarImagem(ponte, hash(1), 'media').promessa;
    esquecerImagens();
    expect(revogadas).toHaveLength(1);
    expect(quantasNaMemoria()).toBe(0);
  });

  it('falha não fica guardada: a próxima tentativa pede de novo', async () => {
    const ponte = {
      chamarApi: vi
        .fn()
        .mockResolvedValueOnce({ ok: false, motivo: 'sem-conexao' })
        .mockResolvedValue({
          ok: true,
          dados: { tipo: 'image/jpeg', bytes: new Uint8Array([1]) },
        }),
    } as unknown as PonteDoCorona;
    expect(await usarImagem(ponte, hash(1), 'media').promessa).toBeNull();
    expect(await usarImagem(ponte, hash(1), 'media').promessa).toMatch(/^blob:/);
  });
});
