import { X509Certificate, createHash } from 'node:crypto';

/**
 * Certificado fixado (ADR-0009, item 2). O servidor da Corona não tem domínio: o HTTPS usa um certificado
 * autoassinado e o aplicativo só confia na CHAVE PÚBLICA dele (impressão digital, "pin"), nunca em
 * "qualquer certificado". A exceção vale só para o endereço do servidor; todo outro endereço segue a
 * verificação normal do sistema. A verificação nunca é desligada de forma geral.
 */

/** Respostas do verificador do Chromium: 0 aceita; -2 recusa; -3 usa a verificação normal. */
export const VEREDITO = { aceitar: 0, recusar: -2, verificacaoNormal: -3 } as const;
type Veredito = (typeof VEREDITO)[keyof typeof VEREDITO];

export interface ServidorFixado {
  /** Endereço do servidor (https://IP). Só o nome da máquina conta. */
  endereco: string;
  /** Impressões digitais aceitas ("sha256/..."): a atual e, se houver, a reserva. */
  impressoes: readonly string[];
}

/** "sha256/<base64>" do SHA-256 da chave pública do certificado (PEM), ou null se não for um certificado. */
export function impressaoDaChave(pem: string): string | null {
  try {
    const chave = new X509Certificate(pem).publicKey.export({ type: 'spki', format: 'der' });
    return `sha256/${createHash('sha256').update(chave).digest('base64')}`;
  } catch {
    return null;
  }
}

/** Decide sobre o certificado apresentado por `hostname`. */
export function decidirCertificado(
  servidor: ServidorFixado,
  hostname: string,
  certificadoPem: string,
): Veredito {
  if (hostname.toLowerCase() !== new URL(servidor.endereco).hostname.toLowerCase()) {
    return VEREDITO.verificacaoNormal;
  }
  const impressao = impressaoDaChave(certificadoPem);
  return impressao !== null && servidor.impressoes.includes(impressao)
    ? VEREDITO.aceitar
    : VEREDITO.recusar;
}

interface PedidoDeVerificacao {
  hostname: string;
  certificate: { data: string };
}

/** O pedaço da sessão do Electron que interessa (facilita o teste). */
export interface SessaoComVerificador {
  setCertificateVerifyProc(
    proc: ((pedido: PedidoDeVerificacao, resposta: (codigo: number) => void) => void) | null,
  ): void;
}

/** Liga o certificado fixado na sessão: vale para as chamadas da API e para o baixar das atualizações. */
export function instalarCertificadoFixado(
  sessao: SessaoComVerificador,
  servidor: ServidorFixado,
): void {
  sessao.setCertificateVerifyProc((pedido, resposta) => {
    resposta(decidirCertificado(servidor, pedido.hostname, pedido.certificate.data));
  });
}
