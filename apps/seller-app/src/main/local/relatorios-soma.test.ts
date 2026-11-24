import { describe, expect, it } from 'vitest';
import { Soma } from './relatorios-do-consignado';

const taxas = { usd: 5_00000000n, pyg: 75000n };

describe('Soma convertida do relatório: nunca "US$ 0,00 · Gs. 0" falso', () => {
  it('zero COM cotação segue 0,00 em Dólar e Guarani (zero verdadeiro)', () => {
    const v = new Soma().somar(0n, taxas).valor();
    expect(v.real).toBe('0.00');
    expect(v.dolar).toBe('0.00');
    expect(v.guarani).toBe('0');
  });

  it('zero SEM cotação do dia, mesmo havendo cotação de outro dia, sai só em Real', () => {
    const v = new Soma(true).somar(0n, null).somar(0n, null).valor();
    expect(v).toEqual({ real: '0.00', dolar: null, guarani: null });
  });

  it('soma vazia: sem nenhuma cotação sai só em Real; havendo cotação, o zero é verdadeiro', () => {
    expect(new Soma(false).valor()).toEqual({ real: '0.00', dolar: null, guarani: null });
    expect(new Soma(true).valor()).toEqual({ real: '0.00', dolar: '0.00', guarani: '0' });
  });

  it('zero sem cotação junto de um valor com cotação não estraga a conversão', () => {
    const v = new Soma().somar(0n, null).somar(1000n, taxas).valor();
    expect(v.real).toBe('10.00');
    expect(v.dolar).not.toBeNull();
  });

  it('valor diferente de zero sem cotação tira Dólar e Guarani da soma', () => {
    const v = new Soma().somar(1000n, taxas).somar(500n, null).valor();
    expect(v.dolar).toBeNull();
    expect(v.guarani).toBeNull();
  });
});
