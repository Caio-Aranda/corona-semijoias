import { describe, expect, it } from 'vitest';
import { enderecoDoApp, POLITICA_DE_CONTEUDO } from './seguranca';

describe('segurança da janela', () => {
  it('a política de conteúdo não libera script de fora, rede nem objetos', () => {
    expect(POLITICA_DE_CONTEUDO).toContain("default-src 'none'");
    expect(POLITICA_DE_CONTEUDO).toContain("script-src 'self'");
    expect(POLITICA_DE_CONTEUDO).toContain("connect-src 'none'");
    expect(POLITICA_DE_CONTEUDO).not.toMatch(/unsafe-|http:|https:|\*/);
  });

  it('em produção só o arquivo empacotado é endereço do aplicativo', () => {
    expect(enderecoDoApp('file:///C:/App/resources/app/out/renderer/index.html', undefined)).toBe(
      true,
    );
    expect(enderecoDoApp('https://outro.site/', undefined)).toBe(false);
    expect(enderecoDoApp('lixo', undefined)).toBe(false);
  });

  it('em desenvolvimento só o servidor de desenvolvimento é endereço do aplicativo', () => {
    const dev = 'http://localhost:5173';
    expect(enderecoDoApp('http://localhost:5173/', dev)).toBe(true);
    expect(enderecoDoApp('http://localhost:5174/', dev)).toBe(false);
    expect(enderecoDoApp('file:///C:/qualquer.html', dev)).toBe(false);
  });
});

describe('configuração do instalador da dona', () => {
  const pins = `${IMPRESSAO_A},${IMPRESSAO_B}`;

  it('aceita o servidor por IP com as duas impressões digitais', () => {
    expect(
      validarConfiguracaoDeProducao({
        MAIN_VITE_CORONA_API_URL: 'https://203.0.113.10/',
        MAIN_VITE_CORONA_API_PINS: pins,
      }),
    ).toEqual({ endereco: 'https://203.0.113.10', impressoes: [IMPRESSAO_A, IMPRESSAO_B] });
  });

  it.each([
    [undefined, pins],
    ['', pins],
    ['http://203.0.113.10', pins],
    ['https://203.0.113.10', undefined],
    ['https://203.0.113.10', ''],
    ['http://localhost:8080', pins],
    ['https://127.0.0.1:8443', pins],
  ])('recusa o endereço %s com as impressões %s', (url, impressoes) => {
    expect(() =>
      validarConfiguracaoDeProducao({
        MAIN_VITE_CORONA_API_URL: url,
        MAIN_VITE_CORONA_API_PINS: impressoes,
      }),
    ).toThrow(ErroDeConfiguracao);
  });
});
