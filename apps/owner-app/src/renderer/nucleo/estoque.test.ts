import { describe, expect, it } from 'vitest';
import {
  conferirAjuste,
  conferirMinimo,
  conferirQuantidade,
  estaAcabando,
  podeDesfazer,
  precisaEscolherONaoLido,
  quantidadeComSinal,
  quantosAjustes,
  rotuloDoTipo,
} from './estoque';

describe('rótulos e sinais', () => {
  it('os tipos aparecem em palavras da loja, nunca em código', () => {
    expect(rotuloDoTipo('ENTRADA')).toBe('Entrada');
    expect(rotuloDoTipo('SAIDA')).toBe('Saída');
    expect(rotuloDoTipo('AVARIA')).toBe('Perda');
    expect(rotuloDoTipo('AJUSTE')).toBe('Ajuste');
    expect(rotuloDoTipo('REMESSA')).toBe('Maleta');
    expect(rotuloDoTipo('DEVOLUCAO')).toBe('Devolução da maleta');
    expect(rotuloDoTipo('QUALQUER')).not.toMatch(/[A-Z]{4}/);
  });

  it('a quantidade leva sinal: mais para entrar, menos para sair', () => {
    expect(quantidadeComSinal(3)).toBe('+3');
    expect(quantidadeComSinal(-2)).toBe('-2');
    expect(quantidadeComSinal(0)).toBe('0');
  });

  it('só se desfaz o que ainda vale', () => {
    expect(podeDesfazer({ desfazDeOutro: false, desfeito: false })).toBe(true);
    expect(podeDesfazer({ desfazDeOutro: false, desfeito: true })).toBe(false);
    expect(podeDesfazer({ desfazDeOutro: true, desfeito: false })).toBe(false);
  });

  it('está acabando quando há mínimo e o estoque chegou nele ou ficou abaixo', () => {
    expect(estaAcabando(2, 2)).toBe(true);
    expect(estaAcabando(0, 2)).toBe(true);
    expect(estaAcabando(3, 2)).toBe(false);
    expect(estaAcabando(0, 0)).toBe(false);
  });
});

describe('validações do lançamento e do ajuste', () => {
  it('a quantidade é inteira e maior que zero', () => {
    expect(conferirQuantidade(' 4 ')).toEqual({ ok: true, valor: 4 });
    for (const t of ['', '0', '-1', '1,5', 'a', '1.5']) {
      expect(conferirQuantidade(t).ok, t).toBe(false);
    }
    expect(conferirQuantidade('99999999').ok).toBe(false);
  });

  it('"contei" aceita zero, recusa o mesmo número do estoque e o que não é número', () => {
    expect(conferirAjuste('CONTEI', '0', 5)).toEqual({ ok: true, valor: { novaQuantidade: 0 } });
    expect(conferirAjuste('CONTEI', '7', 5)).toEqual({ ok: true, valor: { novaQuantidade: 7 } });
    expect(conferirAjuste('CONTEI', '5', 5).ok).toBe(false);
    expect(conferirAjuste('CONTEI', '-1', 5).ok).toBe(false);
    expect(conferirAjuste('CONTEI', '', 5).ok).toBe(false);
  });

  it('a diferença tem sinal, não é zero e não leva o estoque abaixo de zero', () => {
    expect(conferirAjuste('DIFERENCA', '+3', 5)).toEqual({ ok: true, valor: { diferenca: 3 } });
    expect(conferirAjuste('DIFERENCA', '-5', 5)).toEqual({ ok: true, valor: { diferenca: -5 } });
    expect(conferirAjuste('DIFERENCA', '0', 5).ok).toBe(false);
    const passa = conferirAjuste('DIFERENCA', '-6', 5);
    expect(passa.ok).toBe(false);
    if (!passa.ok) expect(passa.erro).toContain('há só 5 no estoque');
  });

  it('estoque mínimo: número inteiro, zero vale, negativo não', () => {
    expect(conferirMinimo('0')).toEqual({ ok: true, valor: 0 });
    expect(conferirMinimo(' 12 ')).toEqual({ ok: true, valor: 12 });
    for (const t of ['', '-1', '2,5', 'x']) expect(conferirMinimo(t).ok, t).toBe(false);
  });
});

describe('fechar a contagem', () => {
  it('com peças não lidas, é preciso escolher; sem elas, não', () => {
    expect(precisaEscolherONaoLido({ comDiferenca: 2, naoLidos: 3 }, null)).toBe(true);
    expect(precisaEscolherONaoLido({ comDiferenca: 2, naoLidos: 3 }, false)).toBe(false);
    expect(precisaEscolherONaoLido({ comDiferenca: 2, naoLidos: 3 }, true)).toBe(false);
    expect(precisaEscolherONaoLido({ comDiferenca: 2, naoLidos: 0 }, null)).toBe(false);
  });

  it('as peças não lidas só entram nos ajustes se virarem zero', () => {
    expect(quantosAjustes({ comDiferenca: 2, naoLidos: 3 }, false)).toBe(2);
    expect(quantosAjustes({ comDiferenca: 2, naoLidos: 3 }, true)).toBe(5);
  });
});
