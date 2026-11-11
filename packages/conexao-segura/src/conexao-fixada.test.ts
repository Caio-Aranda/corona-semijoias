import { describe, expect, it, vi } from 'vitest';
import { CERTIFICADO_A, CERTIFICADO_B, IMPRESSAO_A, IMPRESSAO_B } from './certificados-de-teste';
import {
  decidirCertificado,
  impressaoDaChave,
  instalarCertificadoFixado,
  VEREDITO,
} from './conexao-fixada';

const SERVIDOR = { endereco: 'https://203.0.113.10', impressoes: [IMPRESSAO_A] };

describe('impressão digital da chave', () => {
  it('é o SHA-256 da chave pública, igual ao que o openssl mostra', () => {
    expect(impressaoDaChave(CERTIFICADO_A)).toBe(IMPRESSAO_A);
    expect(impressaoDaChave(CERTIFICADO_B)).toBe(IMPRESSAO_B);
  });

  it('um certificado que não é certificado não gera impressão', () => {
    expect(impressaoDaChave('lixo')).toBeNull();
  });
});

describe('decisão sobre o certificado', () => {
  it('aceita o servidor fixado com a chave fixada', () => {
    expect(decidirCertificado(SERVIDOR, '203.0.113.10', CERTIFICADO_A)).toBe(VEREDITO.aceitar);
  });

  it('recusa o servidor fixado com qualquer outra chave', () => {
    expect(decidirCertificado(SERVIDOR, '203.0.113.10', CERTIFICADO_B)).toBe(VEREDITO.recusar);
    expect(decidirCertificado(SERVIDOR, '203.0.113.10', 'lixo')).toBe(VEREDITO.recusar);
  });

  it('aceita a chave reserva quando ela também foi fixada', () => {
    const duas = { ...SERVIDOR, impressoes: [IMPRESSAO_A, IMPRESSAO_B] };
    expect(decidirCertificado(duas, '203.0.113.10', CERTIFICADO_B)).toBe(VEREDITO.aceitar);
  });

  it('para outro endereço nunca decide: segue a verificação normal do sistema', () => {
    // Mesmo com o certificado "certo": a exceção vale só para o servidor da Corona.
    expect(decidirCertificado(SERVIDOR, 'www4.bcb.gov.br', CERTIFICADO_A)).toBe(
      VEREDITO.verificacaoNormal,
    );
    expect(decidirCertificado(SERVIDOR, '203.0.113.11', CERTIFICADO_A)).toBe(
      VEREDITO.verificacaoNormal,
    );
  });

  it('sem nenhuma impressão fixada não aceita nada para o servidor', () => {
    const vazio = { endereco: 'https://203.0.113.10', impressoes: [] };
    expect(decidirCertificado(vazio, '203.0.113.10', CERTIFICADO_A)).toBe(VEREDITO.recusar);
  });
});

describe('instalação na sessão', () => {
  it('liga o verificador e responde pelo veredito, sem nunca aceitar tudo', () => {
    const instalar = vi.fn();
    instalarCertificadoFixado({ setCertificateVerifyProc: instalar }, SERVIDOR);
    expect(instalar).toHaveBeenCalledOnce();
    const verificador = instalar.mock.calls[0]![0] as (
      pedido: { hostname: string; certificate: { data: string } },
      resposta: (codigo: number) => void,
    ) => void;
    const respostas: number[] = [];
    verificador({ hostname: '203.0.113.10', certificate: { data: CERTIFICADO_A } }, (c) =>
      respostas.push(c),
    );
    verificador({ hostname: '203.0.113.10', certificate: { data: CERTIFICADO_B } }, (c) =>
      respostas.push(c),
    );
    verificador({ hostname: 'exemplo.com', certificate: { data: CERTIFICADO_A } }, (c) =>
      respostas.push(c),
    );
    expect(respostas).toEqual([0, -2, -3]);
  });
});
