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
