import { describe, expect, it } from 'vitest';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamarTodasAsPaginas } from './chamadas';

/**
 * Listas de escolha com mais de 100 itens (revisão de 23/11, achado B3): o ajudante pede a página 0 com 100 itens, como antes,
 * e continua pedindo enquanto faltar item.
 */
function ponteCom(total: number, falharNaPagina?: number) {
  const pedidos: Record<string, unknown>[] = [];
  const ponte = {
    chamarApi: (p: { operacao: string; parametros?: Record<string, unknown> }) => {
      const parametros = p.parametros ?? {};
      pedidos.push(parametros);
      const pagina = Number(parametros.pagina);
      const tamanho = Number(parametros.tamanho);
      if (pagina === falharNaPagina) return Promise.resolve({ ok: false, motivo: 'sem-conexao' });
      const inicio = pagina * tamanho;
      const itens = Array.from(
        { length: Math.max(0, Math.min(tamanho, total - inicio)) },
        (_, i) => ({
          id: `f${inicio + i}`,
        }),
      );
      return Promise.resolve({ ok: true, dados: { itens, total, pagina, tamanho } });
    },
  } as unknown as PonteDoCorona;
  return { ponte, pedidos };
}

describe('chamarTodasAsPaginas', () => {
  it('com até 100 itens, um pedido só, igual ao de antes', async () => {
    const { ponte, pedidos } = ponteCom(37);
    const r = await chamarTodasAsPaginas<{ itens: unknown[] }>(ponte, 'fornecedores.listar', {
      situacao: 'ATIVOS',
    });
    expect(r.ok && r.dados.itens).toHaveLength(37);
    expect(pedidos).toEqual([{ situacao: 'ATIVOS', pagina: 0, tamanho: 100 }]);
  });

  it('com 250 itens, pede as três páginas e junta tudo (o 101º fornecedor aparece)', async () => {
    const { ponte, pedidos } = ponteCom(250);
    const r = await chamarTodasAsPaginas<{ itens: { id: string }[]; total: number }>(
      ponte,
      'fornecedores.listar',
    );
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.dados.itens).toHaveLength(250);
    expect(r.dados.itens[100]?.id).toBe('f100');
    expect(r.dados.total).toBe(250);
    expect(pedidos.map((p) => p.pagina)).toEqual([0, 1, 2]);
  });

  it('se uma página falha, devolve o erro (a tela mostra e tenta de novo)', async () => {
    const { ponte } = ponteCom(250, 1);
    const r = await chamarTodasAsPaginas(ponte, 'categorias.listar');
    expect(r.ok).toBe(false);
  });
});
