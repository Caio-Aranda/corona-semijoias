import { pathToFileURL } from 'node:url';

/** Política de conteúdo da tela (CSP): só o que o próprio aplicativo empacotou, nada de rede. */
export const POLITICA_DE_CONTEUDO = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: blob:",
  "font-src 'self' data:",
  "connect-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "object-src 'none'",
].join('; ');

/**
 * Diz se o endereço pertence à tela do próprio aplicativo: o servidor de desenvolvimento
 * (quando houver) ou o arquivo empacotado. Serve para barrar navegação e conferir quem chama a ponte.
 */
export function enderecoDoApp(
  url: string,
  enderecoDeDesenvolvimento: string | undefined,
  arquivoDoApp: string | undefined,
): boolean {
  let alvo: URL;
  try {
    alvo = new URL(url);
  } catch {
    return false;
  }
  if (enderecoDeDesenvolvimento) {
    try {
      return alvo.origin === new URL(enderecoDeDesenvolvimento).origin;
    } catch {
      return false;
    }
  }
  // Só o index.html empacotado (auditoria B1): outro arquivo local não é a tela do aplicativo.
  if (alvo.protocol !== 'file:' || arquivoDoApp === undefined) return false;
  return alvo.pathname === pathToFileURL(arquivoDoApp).pathname;
}
