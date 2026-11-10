import { describe, expect, it, vi } from 'vitest';
import type { ArquivoDeTexto } from './arquivo-de-texto';
import { identificadorDoAparelho } from './dispositivo';

function arquivoFalso(inicial: string | null = null, quebrado = false) {
  const estado = { texto: inicial, gravacoes: 0 };
  const arquivo: ArquivoDeTexto = {
    ler: () => (quebrado ? Promise.reject(new Error('sem acesso')) : Promise.resolve(estado.texto)),
    gravar: (t) => {
      if (quebrado) return Promise.reject(new Error('sem acesso'));
      estado.texto = t;
      estado.gravacoes += 1;
      return Promise.resolve();
    },
  };
  return { arquivo, estado };
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('identificador do aparelho (ADR-0004, item 4)', () => {
  it('na primeira abertura gera um UUID v4 aleatório e o guarda', async () => {
    const { arquivo, estado } = arquivoFalso();
    const id = await identificadorDoAparelho(arquivo);
    expect(id).toMatch(UUID_V4);
    expect(estado.texto).toBe(id);
    expect(estado.gravacoes).toBe(1);
  });

  it('nas aberturas seguintes reaproveita o mesmo e não grava de novo', async () => {
    const { arquivo, estado } = arquivoFalso();
    const gerar = vi.fn(() => 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    const primeiro = await identificadorDoAparelho(arquivo, gerar);
    const segundo = await identificadorDoAparelho(arquivo, gerar);
    expect(segundo).toBe(primeiro);
    expect(gerar).toHaveBeenCalledTimes(1);
    expect(estado.gravacoes).toBe(1);
  });

  it('dois aparelhos (duas instalações) têm identificadores diferentes', async () => {
    const a = await identificadorDoAparelho(arquivoFalso().arquivo);
    const b = await identificadorDoAparelho(arquivoFalso().arquivo);
    expect(a).not.toBe(b);
  });

  it('arquivo estragado ou com texto que não é UUID vale como primeira abertura', async () => {
    const { arquivo, estado } = arquivoFalso('meu-computador');
    const id = await identificadorDoAparelho(arquivo);
    expect(id).toMatch(UUID_V4);
    expect(estado.texto).toBe(id);
  });

  it('se não der para ler nem gravar, o aplicativo segue com um identificador novo', async () => {
    const { arquivo } = arquivoFalso(null, true);
    expect(await identificadorDoAparelho(arquivo)).toMatch(UUID_V4);
  });
});
