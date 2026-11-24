import { describe, expect, it } from 'vitest';
import {
  centavosDeTexto,
  dataParaTela,
  dividirArredondando,
  formatarDolar,
  formatarGuarani,
  formatarNaMoeda,
  formatarReais,
  lerReaisDigitados,
  lerValorNaMoeda,
  reaisDoValorNaMoeda,
  taxaDeTexto,
  tresMoedas,
  tresMoedasDoRecebimento,
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

// --- recebimento do representante digitado em Dólar ou Guarani (23/11/2026): os mesmos exemplos do ConversaoDeMoedaTest ---

describe('valor digitado na moeda do recebimento', () => {
  it.each([
    ['REAL', '1.234,56', '1234.56'],
    ['DOLAR', '100', '100.00'],
    ['DOLAR', 'US$ 0,01', '0.01'],
    ['GUARANI', '500.000', '500000'],
    ['GUARANI', '500000', '500000'],
    ['GUARANI', 'Gs. 1.379.397', '1379397'],
  ] as const)('%s: "%s" vira %s', (moeda, digitado, esperado) => {
    expect(lerValorNaMoeda(digitado, moeda)).toBe(esperado);
  });

  it.each([
    ['GUARANI', '500.000,50'],
    ['GUARANI', '500,5'],
    ['GUARANI', '0'],
    ['GUARANI', '1.234.567.890.123'],
    ['DOLAR', '1,005'],
    ['DOLAR', '0,00'],
    ['DOLAR', ''],
    ['REAL', 'abc'],
  ] as const)('%s: "%s" não é um valor', (moeda, digitado) => {
    expect(lerValorNaMoeda(digitado, moeda)).toBeNull();
  });
});

describe('quanto o valor digitado vale em reais (a mesma conta do servidor)', () => {
  it.each([
    ['DOLAR', '100.00', D_06_11, '550.13'],
    ['DOLAR', '224.41', D_06_11, '1234.55'],
    ['DOLAR', '0.01', D_06_11, '0.06'],
    // HALF_UP no meio exato: 0,065 vira 0,07.
    ['DOLAR', '0.01', { dolar: '6.50000000', guarani: '0.00100000' }, '0.07'],
    ['GUARANI', '500000', D_06_11, '447.50'],
    ['GUARANI', '1379397', D_06_11, '1234.56'],
    ['GUARANI', '500', D_06_11, '0.45'],
    ['GUARANI', '11', D_06_11, '0.01'],
    ['GUARANI', '5', D_06_11, '0.00'],
  ] as const)('%s %s → R$ %s', (moeda, valor, cotacao, esperado) => {
    expect(reaisDoValorNaMoeda(valor, moeda, cotacao)).toBe(esperado);
  });

  it('valor ou cotação fora do formato não converte', () => {
    expect(reaisDoValorNaMoeda('1.005', 'DOLAR', D_06_11)).toBeNull();
    expect(reaisDoValorNaMoeda('10.5', 'GUARANI', D_06_11)).toBeNull();
    expect(reaisDoValorNaMoeda('10', 'GUARANI', { dolar: '5', guarani: '0' })).toBeNull();
  });

  it('o valor máximo não perde dígitos (BigInt)', () => {
    expect(reaisDoValorNaMoeda('999999999999', 'GUARANI', D_06_11)).toBe('895000000.00');
  });
});

describe('as três moedas do recebimento', () => {
  it('a moeda recebida mostra o valor digitado, exato; as outras saem do Real', () => {
    expect(tresMoedasDoRecebimento('447.50', 'GUARANI', '500000', D_06_11)).toEqual({
      real: 'R$ 447,50',
      dolar: 'US$ 81,34',
      guarani: 'Gs. 500.000',
    });
    expect(tresMoedasDoRecebimento('27.51', 'DOLAR', '5.00', D_06_11)).toEqual({
      real: 'R$ 27,51',
      dolar: 'US$ 5,00',
      guarani: 'Gs. 30.737',
    });
    expect(tresMoedasDoRecebimento('10.00', 'REAL', null, D_06_11)).toEqual(
      tresMoedas('10.00', D_06_11),
    );
  });

  it('sem cotação, só o Real', () => {
    expect(tresMoedasDoRecebimento('10.00', 'REAL', null, null)).toEqual({ real: 'R$ 10,00' });
  });

  it('"Recebido em" na moeda digitada, com o sinal do estorno', () => {
    expect(formatarNaMoeda('500000', 'GUARANI')).toBe('Gs. 500.000');
    expect(formatarNaMoeda('-500000', 'GUARANI')).toBe('-Gs. 500.000');
    expect(formatarNaMoeda('100.00', 'DOLAR')).toBe('US$ 100,00');
    expect(formatarNaMoeda('100.5', 'DOLAR')).toBe('US$ 100,50');
    expect(formatarNaMoeda('12.34', 'REAL')).toBe('R$ 12,34');
    expect(formatarNaMoeda('abc', 'GUARANI')).toBeNull();
  });
});

describe('a cotação da dona: 1 real = Gs. N (guaranisPorReal)', () => {
  const DONA = { dolar: '5.80000000', guarani: '0.00074074', guaranisPorReal: '1350.00000000' };

  it('o recebimento em Guarani divide por Gs. por real (Gs. 50.000.000 → R$ 37.037,04)', () => {
    expect(reaisDoValorNaMoeda('50000000', 'GUARANI', DONA)).toBe('37037.04');
    expect(reaisDoValorNaMoeda('135000', 'GUARANI', DONA)).toBe('100.00');
    expect(reaisDoValorNaMoeda('1', 'GUARANI', DONA)).toBe('0.00');
    expect(reaisDoValorNaMoeda('500000', 'GUARANI', { ...DONA, guaranisPorReal: '1350.5' })).toBe(
      '370.23',
    );
  });

  it('sem guaranisPorReal (Banco Central), a fórmula antiga continua', () => {
    expect(reaisDoValorNaMoeda('500000', 'GUARANI', D_06_11)).toBe('447.50');
    expect(reaisDoValorNaMoeda('500000', 'GUARANI', { ...D_06_11, guaranisPorReal: null })).toBe(
      '447.50',
    );
  });

  it('a conversão de tela de R$ para Gs. e US$ segue a taxa de 8 casas', () => {
    expect(tresMoedas('100.00', DONA)).toEqual({
      real: 'R$ 100,00',
      dolar: 'US$ 17,24',
      guarani: 'Gs. 135.000',
    });
  });
});
