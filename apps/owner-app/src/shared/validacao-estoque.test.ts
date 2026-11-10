import { describe, expect, it } from 'vitest';
import { conferirEstoque } from './validacao-estoque';

const A = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const B = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';

describe('conferirEstoque', () => {
  it('lançar aceita só entrada, saída e perda, com quantidade inteira maior que zero', () => {
    const base = { id: A, produtoId: B, tipo: 'ENTRADA', quantidade: 3 };
    expect(conferirEstoque('estoque.lancar', base)?.corpo).toMatchObject({ quantidade: 3 });
    expect(conferirEstoque('estoque.lancar', { ...base, tipo: 'AJUSTE' })).toBeNull();
    expect(conferirEstoque('estoque.lancar', { ...base, tipo: 'REMESSA' })).toBeNull();
    for (const q of [0, -1, 1.5, '2', 2_000_000]) {
      expect(conferirEstoque('estoque.lancar', { ...base, quantidade: q })).toBeNull();
    }
    expect(conferirEstoque('estoque.lancar', { ...base, extra: 1 })).toBeNull();
    expect(conferirEstoque('estoque.lancar', { ...base, id: 'x' })).toBeNull();
  });

  it('ajustar exige motivo e uma só das duas formas', () => {
    const base = { id: A, produtoId: B, motivo: ' Contei de novo ' };
    expect(conferirEstoque('estoque.ajustar', { ...base, novaQuantidade: 0 })?.corpo).toMatchObject(
      { novaQuantidade: 0, motivo: 'Contei de novo' },
    );
    expect(conferirEstoque('estoque.ajustar', { ...base, diferenca: -2 })?.corpo).toMatchObject({
      diferenca: -2,
    });
    expect(conferirEstoque('estoque.ajustar', base)).toBeNull();
    expect(
      conferirEstoque('estoque.ajustar', { ...base, novaQuantidade: 1, diferenca: 1 }),
    ).toBeNull();
    expect(
      conferirEstoque('estoque.ajustar', { ...base, motivo: '  ', novaQuantidade: 1 }),
    ).toBeNull();
    expect(conferirEstoque('estoque.ajustar', { ...base, novaQuantidade: -1 })).toBeNull();
    expect(conferirEstoque('estoque.ajustar', { ...base, diferenca: 0 })).toBeNull();
  });

  it('desfazer pede o motivo e usa o identificador do pedido como id do corpo', () => {
    const r = conferirEstoque('estoque.desfazer', { id: A, pedidoId: B, motivo: 'Lancei errado' });
    expect(r).toEqual({
      operacao: 'estoque.desfazer',
      id: A,
      corpo: { id: B, motivo: 'Lancei errado' },
    });
    expect(conferirEstoque('estoque.desfazer', { id: A, pedidoId: B, motivo: '' })).toBeNull();
  });

  it('as listas aceitam só filtros conhecidos e páginas válidas', () => {
    expect(
      conferirEstoque('estoque.lancamentos', { tipo: 'AVARIA', de: '2026-11-01', pagina: 1 })
        ?.consulta,
    ).toEqual({ pagina: 1, tamanho: 20, tipo: 'AVARIA', de: '2026-11-01' });
    expect(conferirEstoque('estoque.lancamentos', { tipo: 'ESTORNO' })).toBeNull();
    expect(conferirEstoque('estoque.lancamentos', { de: '01/11/2026' })).toBeNull();
    expect(conferirEstoque('estoque.lancamentos', { tamanho: 500 })).toBeNull();
    expect(conferirEstoque('estoque.acabando', { categoriaId: 'x' })).toBeNull();
    expect(conferirEstoque('estoque.ficha', { id: A, pagina: 0, tamanho: 10 })?.id).toBe(A);
  });

  it('começar a contagem: categoria e fornecedor pedem o escopoId; todas não', () => {
    expect(conferirEstoque('contagens.comecar', { id: A, escopo: 'TODOS' })).not.toBeNull();
    expect(conferirEstoque('contagens.comecar', { id: A, escopo: 'CATEGORIA' })).toBeNull();
    expect(
      conferirEstoque('contagens.comecar', { id: A, escopo: 'FORNECEDOR', escopoId: B }),
    ).not.toBeNull();
    expect(
      conferirEstoque('contagens.comecar', { id: A, escopo: 'TODOS', escopoId: B }),
    ).toBeNull();
    expect(conferirEstoque('contagens.comecar', { id: A, escopo: 'OUTRO' })).toBeNull();
  });

  it('ler soma por código ou peça; corrigir define o contado (zero vale)', () => {
    const ler = conferirEstoque('contagens.ler', { id: A, leituraId: B, codigo: ' 0012 ' });
    expect(ler?.corpo).toEqual({ id: B, codigo: '0012' });
    expect(conferirEstoque('contagens.ler', { id: A, leituraId: B })).toBeNull();
    expect(
      conferirEstoque('contagens.ler', { id: A, leituraId: B, codigo: 'x', produtoId: A }),
    ).toBeNull();
    expect(
      conferirEstoque('contagens.ler', { id: A, leituraId: B, codigo: 'x', quantidade: 0 }),
    ).toBeNull();
    expect(
      conferirEstoque('contagens.corrigir', { id: A, leituraId: B, produtoId: A, contado: 0 })
        ?.corpo,
    ).toMatchObject({ contado: 0 });
    expect(
      conferirEstoque('contagens.corrigir', { id: A, leituraId: B, produtoId: A, contado: -1 }),
    ).toBeNull();
  });

  it('fechar manda sempre o zerarNaoLidos; cancelar tem motivo opcional', () => {
    expect(conferirEstoque('contagens.fechar', { id: A })?.corpo).toEqual({ zerarNaoLidos: false });
    expect(conferirEstoque('contagens.fechar', { id: A, zerarNaoLidos: true })?.corpo).toEqual({
      zerarNaoLidos: true,
    });
    expect(conferirEstoque('contagens.fechar', { id: A, zerarNaoLidos: 'sim' })).toBeNull();
    expect(conferirEstoque('contagens.cancelar', { id: A })?.corpo).toEqual({});
    expect(conferirEstoque('contagens.cancelar', { id: A, motivo: 'Errei' })?.corpo).toEqual({
      motivo: 'Errei',
    });
  });
});
