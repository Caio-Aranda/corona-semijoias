/** Endereço da API usado quando nada foi configurado (a API de desenvolvimento). */
export const ENDERECO_PADRAO_DA_API = 'http://localhost:8080';

const ENDERECOS_DESTE_COMPUTADOR = new Set(['localhost', '127.0.0.1', '[::1]']);

export class ErroDeConfiguracao extends Error {}

/**
 * Confere o endereço da API (variável MAIN_VITE_CORONA_API_URL). Só aceita http ou https, sem
 * usuário e senha no endereço, e só aceita http (sem cifra) quando a API roda neste computador.
 * Devolve o endereço sem barra no fim.
 */
export function enderecoDaApi(valor: string | undefined): string {
  const bruto = (valor ?? '').trim() || ENDERECO_PADRAO_DA_API;
  let url: URL;
  try {
    url = new URL(bruto);
  } catch {
    throw new ErroDeConfiguracao(
      'O endereço da API (MAIN_VITE_CORONA_API_URL) não é válido. Veja o arquivo .env.example do aplicativo.',
    );
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ErroDeConfiguracao('O endereço da API precisa começar com http:// ou https://.');
  }
  if (url.username || url.password) {
    throw new ErroDeConfiguracao('O endereço da API não pode ter usuário nem senha.');
  }
  if (url.protocol === 'http:' && !ENDERECOS_DESTE_COMPUTADOR.has(url.hostname)) {
    throw new ErroDeConfiguracao(
      'Fora deste computador, o endereço da API precisa começar com https:// para proteger a senha.',
    );
  }
  return url.origin;
}

/** A versão do aplicativo manda no cabeçalho X-App-Versao: três números (por exemplo 1.4.2). */
export function versaoDoApp(valor: string): string {
  if (!/^\d+\.\d+\.\d+$/.test(valor)) {
    throw new ErroDeConfiguracao('A versão do aplicativo precisa ter três números, como 1.4.2.');
  }
  return valor;
}
