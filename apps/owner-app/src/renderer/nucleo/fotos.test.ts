import { describe, expect, it } from 'vitest';
import { conferirArquivoDeFoto, nomeParaEnviar } from './fotos';

const arq = (name: string, size = 100, type = '') => ({ name, size, type });

describe('foto escolhida, colada ou arrastada', () => {
  it('aceita JPG, PNG e WebP até 10 MB', () => {
    expect(conferirArquivoDeFoto(arq('a.JPG'))).toBeNull();
    expect(conferirArquivoDeFoto(arq('a.png'))).toBeNull();
    expect(conferirArquivoDeFoto(arq('a.webp', 10 * 1024 * 1024))).toBeNull();
  });
  it('recusa PDF, arquivo vazio e foto de mais de 10 MB', () => {
    expect(conferirArquivoDeFoto(arq('a.pdf'))).toContain('Esta não é uma foto');
    expect(conferirArquivoDeFoto(arq('a.jpg', 0))).toBe('Este arquivo está vazio. Escolha outro.');
    expect(conferirArquivoDeFoto(arq('a.jpg', 10 * 1024 * 1024 + 1))).toContain('10 MB');
  });
  it('foto colada sem extensão ganha a extensão do tipo', () => {
    expect(nomeParaEnviar(arq('image', 5, 'image/png'))).toBe('image.png');
    expect(conferirArquivoDeFoto(arq('image', 5, 'image/png'))).toBeNull();
    expect(conferirArquivoDeFoto(arq('image', 5, 'application/pdf'))).toContain(
      'Esta não é uma foto',
    );
  });
});
