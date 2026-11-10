import { describe, expect, it } from 'vitest';
import {
  DetectorDeLeitor,
  avaliarCodigo,
  chaveDoCodigo,
  conferirListaDeCodigos,
  digitoDoEan13Confere,
  normalizarCodigo,
  tipoSugerido,
} from './codigos';

describe('regras dos códigos da peça', () => {
  it('o código é texto: só tira os espaços das pontas e guarda os zeros', () => {
    expect(normalizarCodigo('  000123 ')).toBe('000123');
    expect(chaveDoCodigo(' a-0001 ')).toBe(chaveDoCodigo('A-0001'));
  });

  it('dígito verificador do EAN-13', () => {
    expect(digitoDoEan13Confere('7891234567895')).toBe(true);
    expect(digitoDoEan13Confere('7891234567890')).toBe(false);
    expect(digitoDoEan13Confere('789123456789')).toBe(false);
  });

  it('só o tipo EAN-13 confere o dígito; os outros aceitam qualquer formato', () => {
    expect(avaliarCodigo('7891234567890', 'LOJA')).toBeNull();
    expect(avaliarCodigo('A-12', 'FORNECEDOR')).toBeNull();
    expect(avaliarCodigo('7891234567895', 'EAN13')).toBeNull();
    const aviso = avaliarCodigo('7891234567890', 'EAN13');
    expect(aviso?.nivel).toBe('erro');
    expect(aviso?.mensagem).toContain('não confere');
  });

  it('EAN-13 com poucos números é leitura cortada: avisa para ler de novo', () => {
    const aviso = avaliarCodigo('78912345', 'EAN13');
    expect(aviso?.nivel).toBe('aviso');
    expect(aviso?.mensagem).toContain('Leia o código de novo');
  });

  it('código vazio não tem aviso e código longo demais é erro', () => {
    expect(avaliarCodigo('  ', 'EAN13')).toBeNull();
    expect(avaliarCodigo('x'.repeat(61), 'LOJA')?.nivel).toBe('erro');
  });

  it('um código que confere como EAN-13 sugere o tipo EAN-13', () => {
    expect(tipoSugerido('7891234567895', 'LOJA')).toBe('EAN13');
    expect(tipoSugerido('0012', 'LOJA')).toBe('LOJA');
  });

  it('lista da peça: pelo menos um, sem repetir, um só principal', () => {
    const c = (codigo: string, principal = false) => ({
      codigo,
      tipo: 'LOJA' as const,
      principal,
    });
    expect(conferirListaDeCodigos([c('')])).toEqual(['Informe pelo menos um código para a peça.']);
    expect(conferirListaDeCodigos([c('A1'), c('a1 ')])).toEqual([
      'O código a1 aparece mais de uma vez nesta peça.',
    ]);
    expect(conferirListaDeCodigos([c('1', true), c('2', true)])).toEqual([
      'Escolha apenas um código principal.',
    ]);
    expect(conferirListaDeCodigos([c('1', true), c('2')])).toEqual([]);
  });
});

describe('leitor de código x digitação', () => {
  const digitar = (intervalo: number, teclas: number) => {
    const d = new DetectorDeLeitor();
    for (let i = 0; i < teclas; i++) d.tecla(i * intervalo);
    return d;
  };
  it('teclas muito rápidas são leitor', () => {
    expect(digitar(10, 13).foiLeitor()).toBe(true);
  });
  it('digitação normal não é leitor', () => {
    expect(digitar(150, 13).foiLeitor()).toBe(false);
    expect(digitar(100, 8).foiLeitor()).toBe(false);
  });
  it('poucas teclas não bastam, mesmo rápidas', () => {
    expect(digitar(5, 2).foiLeitor()).toBe(false);
  });
  it('uma pausa grande começa uma nova leitura', () => {
    const d = new DetectorDeLeitor();
    for (let i = 0; i < 5; i++) d.tecla(i * 400);
    for (let i = 0; i < 8; i++) d.tecla(5000 + i * 10);
    expect(d.foiLeitor()).toBe(true);
    d.reiniciar();
    expect(d.foiLeitor()).toBe(false);
  });
});
