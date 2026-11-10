import { describe, expect, it } from 'vitest';
import {
  centavosDeTexto,
  dataParaTela,
  dividirArredondando,
  formatarDolar,
  formatarGuarani,
  formatarReais,
  lerReaisDigitados,
  taxaDeTexto,
  tresMoedas,
} from './moedas';

// Taxas de venda reais do ADR-0006, item 14 (reais por unidade, 8 casas).
const D_30_10 = { dolar: '5.42120000', guarani: '0.00092960' };
const D_05_11 = { dolar: '5.46210000', guarani: '0.00088770' };
const D_06_11 = { dolar: '5.50130000', guarani: '0.00089500' };

describe('conversão pelos exemplos do ADR-0006 (item 14)', () => {
  it('R$ 1.234,56 em 06/11: US$ 224,41 e Gs. 1.379.397 (o Guarani arredonda para cima)', () => {
    expect(tresMoedas('1234.56', D_06_11)).toEqual({
      real: 'R$ 1.234,56',
      dolar: 'US$ 224,41',
      guarani: 'Gs. 1.379.397',
    });
  });

  it('R$ 1,00 em 06/11: US$ 0,18 e Gs. 1.117 (arredonda para baixo)', () => {
    const t = tresMoedas('1.00', D_06_11);
    expect(t?.dolar).toBe('US$ 0,18');
    expect(t?.guarani).toBe('Gs. 1.117');
  });

  it('R$ 0,01 em 06/11: US$ 0,00 e Gs. 11', () => {
    const t = tresMoedas('0.01', D_06_11);
    expect(t?.dolar).toBe('US$ 0,00');
    expect(t?.guarani).toBe('Gs. 11');
  });

  it('dois dias: cada valor pela cotação do seu dia, somando os convertidos', () => {
    const a = tresMoedas('49.90', D_05_11);
    const b = tresMoedas('100.00', D_06_11);
    expect(a?.dolar).toBe('US$ 9,14');
    expect(b?.dolar).toBe('US$ 18,18');
    expect(a?.guarani).toBe('Gs. 56.213');
    expect(b?.guarani).toBe('Gs. 111.732');
  });

  it('Finados: vale a cotação de 30/10 (US$ 18,45; Gs. 107.573)', () => {
    const t = tresMoedas('100.00', D_30_10);
    expect(t?.dolar).toBe('US$ 18,45');
    expect(t?.guarani).toBe('Gs. 107.573');
  });

  it('três vendas de R$ 10,05: cada uma US$ 1,83 e Gs. 11.229', () => {
    const t = tresMoedas('10.05', D_06_11);
    expect(t?.dolar).toBe('US$ 1,83');
    expect(t?.guarani).toBe('Gs. 11.229');
  });

  it('valor máximo de NUMERIC(14,2) não perde dígitos (só funciona com BigInt)', () => {
    const t = tresMoedas('999999999999.99', D_06_11);
    expect(t?.real).toBe('R$ 999.999.999.999,99');
    expect(t?.dolar).toBe('US$ 181.775.216.766,94');
    expect(t?.guarani).toBe('Gs. 1.117.318.435.754.179');
  });

  it('estorno: valor negativo com o sinal na frente', () => {
    const t = tresMoedas('-50.00', D_06_11);
    expect(t?.real).toBe('-R$ 50,00');
    expect(t?.dolar).toBe('-US$ 9,09');
    expect(t?.guarani).toBe('-Gs. 55.866');
  });

  it('sem cotação, mostra só o Real', () => {
    expect(tresMoedas('100.00', null)).toEqual({ real: 'R$ 100,00' });
  });

  it('cotação quebrada não vira número errado: mostra só o Real', () => {
    expect(tresMoedas('100.00', { dolar: 'abc', guarani: '0' })).toEqual({ real: 'R$ 100,00' });
  });

  it('valor que não é dinheiro vira nulo', () => {
    expect(tresMoedas('abc', D_06_11)).toBeNull();
    expect(tresMoedas('1.234', D_06_11)).toBeNull();
  });
});

describe('arredondamento', () => {
  it('HALF_UP: afasta do zero no meio exato', () => {
    expect(dividirArredondando(5n, 2n)).toBe(3n);
    expect(dividirArredondando(-5n, 2n)).toBe(-3n);
    expect(dividirArredondando(4n, 3n)).toBe(1n);
    expect(dividirArredondando(-4n, 3n)).toBe(-1n);
    expect(dividirArredondando(0n, 7n)).toBe(0n);
  });

  it('recusa divisor zero', () => {
    expect(() => dividirArredondando(1n, 0n)).toThrow();
  });
});

describe('leitura de textos', () => {
  it('centavos e taxas em inteiros', () => {
    expect(centavosDeTexto('1234.56')).toBe(123456n);
    expect(centavosDeTexto('1234.5')).toBe(123450n);
    expect(centavosDeTexto('-0.05')).toBe(-5n);
    expect(centavosDeTexto('1234.567')).toBeNull();
    expect(taxaDeTexto('5.50130000')).toBe(550130000n);
    expect(taxaDeTexto('0.00089500')).toBe(89500n);
    expect(taxaDeTexto('0')).toBeNull();
    expect(taxaDeTexto('1.123456789')).toBeNull();
  });
});

describe('formatos da parte 8 do glossário', () => {
  it('Real, Dólar e Guarani', () => {
    expect(formatarReais(123456n)).toBe('R$ 1.234,56');
    expect(formatarReais(5n)).toBe('R$ 0,05');
    expect(formatarDolar(-5000n)).toBe('-US$ 50,00');
    expect(formatarGuarani(1379397n)).toBe('Gs. 1.379.397');
    expect(formatarGuarani(0n)).toBe('Gs. 0');
  });

  it('data dd/mm/aaaa', () => {
    expect(dataParaTela('2026-11-06')).toBe('06/11/2026');
  });
});

describe('campo de dinheiro: o que a pessoa digita', () => {
  it.each([
    ['10', '10.00'],
    ['10,5', '10.50'],
    ['1.234,56', '1234.56'],
    ['0,05', '0.05'],
    ['R$ 99,90', '99.90'],
    ['1234,56', '1234.56'],
  ])('"%s" vira %s', (digitado, esperado) => {
    expect(lerReaisDigitados(digitado)).toBe(esperado);
  });

  it.each(['', 'abc', '10,555', '-5', '1,234.56', '12.34', '1e5', ',5'])(
    '"%s" não é um valor',
    (digitado) => {
      expect(lerReaisDigitados(digitado)).toBeNull();
    },
  );
});
