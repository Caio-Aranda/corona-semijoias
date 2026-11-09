import { describe, expect, it } from 'vitest';
import {
  enderecoDaApi,
  ENDERECO_PADRAO_DA_API,
  ErroDeConfiguracao,
  versaoDoApp,
} from './configuracao';
import { enderecoDoApp, POLITICA_DE_CONTEUDO } from './seguranca';

describe('endereço da API', () => {
  it('sem configuração usa a API deste computador', () => {
    expect(enderecoDaApi(undefined)).toBe(ENDERECO_PADRAO_DA_API);
    expect(enderecoDaApi('  ')).toBe(ENDERECO_PADRAO_DA_API);
  });

  it('aceita https em qualquer lugar e http só neste computador, sem barra no fim', () => {
    expect(enderecoDaApi('https://api.corona.exemplo/')).toBe('https://api.corona.exemplo');
    expect(enderecoDaApi('http://127.0.0.1:8080')).toBe('http://127.0.0.1:8080');
  });

  it.each([
    'não é endereço',
    'ftp://api.exemplo',
    'http://api.corona.exemplo',
    'https://usuario:senha@api.exemplo',
  ])('recusa %s', (valor) => {
    expect(() => enderecoDaApi(valor)).toThrow(ErroDeConfiguracao);
  });
});

describe('versão do aplicativo', () => {
  it('aceita três números e recusa o resto', () => {
    expect(versaoDoApp('1.4.2')).toBe('1.4.2');
    expect(() => versaoDoApp('1.4')).toThrow(ErroDeConfiguracao);
    expect(() => versaoDoApp('1.4.2-beta')).toThrow(ErroDeConfiguracao);
  });
});

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
