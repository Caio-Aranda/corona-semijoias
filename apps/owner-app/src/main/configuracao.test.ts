import { describe, expect, it } from 'vitest';
import {
  enderecoDaApi,
  ENDERECO_PADRAO_DA_API,
  ErroDeConfiguracao,
  exigirImpressoesFora,
  impressoesDoServidor,
  validarConfiguracaoDeProducao,
  versaoDoApp,
} from './configuracao';
import { IMPRESSAO_A, IMPRESSAO_B } from './certificados-de-teste';
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

describe('impressões digitais do servidor', () => {
  it('lê a atual e a reserva, separadas por vírgula, sem repetir', () => {
    expect(impressoesDoServidor(` ${IMPRESSAO_A} , ${IMPRESSAO_B},${IMPRESSAO_A}`)).toEqual([
      IMPRESSAO_A,
      IMPRESSAO_B,
    ]);
    expect(impressoesDoServidor(undefined)).toEqual([]);
    expect(impressoesDoServidor('')).toEqual([]);
  });

  it.each(['sha1/abc', 'sha256/curto=', IMPRESSAO_A.replace('sha256/', '')])(
    'recusa %s',
    (valor) => {
      expect(() => impressoesDoServidor(valor)).toThrow(ErroDeConfiguracao);
    },
  );

  it('recusa mais de três', () => {
    expect(() =>
      impressoesDoServidor(
        `${IMPRESSAO_A},${IMPRESSAO_B},${IMPRESSAO_A.slice(0, -2)}A=,${IMPRESSAO_B.slice(0, -2)}A=`,
      ),
    ).toThrow(ErroDeConfiguracao);
  });

  it('https fora deste computador sem impressão digital não abre conexão', () => {
    expect(() => exigirImpressoesFora('https://203.0.113.10', [])).toThrow(ErroDeConfiguracao);
    expect(() => exigirImpressoesFora('https://203.0.113.10', [IMPRESSAO_A])).not.toThrow();
    expect(() => exigirImpressoesFora('http://localhost:8080', [])).not.toThrow();
    expect(() => exigirImpressoesFora('https://localhost:8443', [])).not.toThrow();
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
