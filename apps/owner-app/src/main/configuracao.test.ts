import { describe, expect, it } from 'vitest';
import { enderecoDoApp, POLITICA_DE_CONTEUDO } from './seguranca';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

describe('segurança da janela', () => {
  it('a política de conteúdo não libera script de fora, rede nem objetos', () => {
    expect(POLITICA_DE_CONTEUDO).toContain("default-src 'none'");
    expect(POLITICA_DE_CONTEUDO).toContain("script-src 'self'");
    expect(POLITICA_DE_CONTEUDO).toContain("connect-src 'none'");
    expect(POLITICA_DE_CONTEUDO).not.toMatch(/unsafe-|http:|https:|\*/);
  });

  it('em produção só o index.html empacotado é endereço do aplicativo', () => {
    const arquivo = resolve('pacote', 'out', 'renderer', 'index.html');
    const url = pathToFileURL(arquivo).href;
    expect(enderecoDoApp(url, undefined, arquivo)).toBe(true);
    expect(enderecoDoApp(`${url}#/vender`, undefined, arquivo)).toBe(true);
    expect(
      enderecoDoApp(pathToFileURL(resolve('pacote', 'out', 'outro.html')).href, undefined, arquivo),
    ).toBe(false);
    expect(enderecoDoApp(pathToFileURL(resolve('segredos.txt')).href, undefined, arquivo)).toBe(
      false,
    );
    expect(enderecoDoApp(url, undefined, undefined)).toBe(false);
    expect(enderecoDoApp('https://outro.site/', undefined, arquivo)).toBe(false);
    expect(enderecoDoApp('lixo', undefined, arquivo)).toBe(false);
  });

  it('em desenvolvimento só o servidor de desenvolvimento é endereço do aplicativo', () => {
    const dev = 'http://localhost:5173';
    const arquivo = resolve('pacote', 'out', 'renderer', 'index.html');
    expect(enderecoDoApp('http://localhost:5173/', dev, arquivo)).toBe(true);
    expect(enderecoDoApp('http://localhost:5174/', dev, arquivo)).toBe(false);
    expect(enderecoDoApp(pathToFileURL(arquivo).href, dev, arquivo)).toBe(false);
  });
});
