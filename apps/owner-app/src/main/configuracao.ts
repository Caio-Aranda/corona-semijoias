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

const FORMATO_DA_IMPRESSAO = /^sha256\/[A-Za-z0-9+/]{43}=$/;

/**
 * Impressões digitais do certificado do servidor (variável MAIN_VITE_CORONA_API_PINS): "sha256/..." separadas
 * por vírgula, a atual e, se houver, a reserva (ADR-0009, item 2). Vazio vale como nenhuma.
 */
export function impressoesDoServidor(valor: string | undefined): string[] {
  const lista = (valor ?? '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  if (lista.length > 3) {
    throw new ErroDeConfiguracao(
      'Há impressões digitais demais em MAIN_VITE_CORONA_API_PINS (no máximo 3).',
    );
  }
  for (const item of lista) {
    if (!FORMATO_DA_IMPRESSAO.test(item)) {
      throw new ErroDeConfiguracao(
        'Uma impressão digital em MAIN_VITE_CORONA_API_PINS não está no formato sha256/... (44 caracteres em Base64).',
      );
    }
  }
  return [...new Set(lista)];
}

/**
 * Fora deste computador, o HTTPS só vale com o certificado fixado: sem impressão digital o aplicativo
 * não abre conexão (nunca cai para "confiar em qualquer certificado").
 */
export function exigirImpressoesFora(endereco: string, impressoes: readonly string[]): void {
  const { protocol, hostname } = new URL(endereco);
  if (
    protocol === 'https:' &&
    !ENDERECOS_DESTE_COMPUTADOR.has(hostname) &&
    impressoes.length === 0
  ) {
    throw new ErroDeConfiguracao(
      'Falta a impressão digital do certificado do servidor (MAIN_VITE_CORONA_API_PINS). Gere o instalador de novo, conforme docs/entrega-corona-gestao.md.',
    );
  }
}

export interface ConfiguracaoDeProducao {
  endereco: string;
  impressoes: string[];
}

/**
 * Confere a configuração do INSTALADOR da dona antes de compilar (npm run owner:empacotar): endereço do
 * servidor (nunca este computador) e as impressões digitais do certificado.
 */
export function validarConfiguracaoDeProducao(
  env: {
    MAIN_VITE_CORONA_API_URL?: string | undefined;
    MAIN_VITE_CORONA_API_PINS?: string | undefined;
  },
  /** Só para testar o instalador neste computador (npm run owner:empacotar -- --para-teste). */
  opcoes: { permitirEsteComputador?: boolean } = {},
): ConfiguracaoDeProducao {
  if (!(env.MAIN_VITE_CORONA_API_URL ?? '').trim()) {
    throw new ErroDeConfiguracao(
      'Falta o endereço do servidor (MAIN_VITE_CORONA_API_URL), com https:// e o IP da VPS.',
    );
  }
  const endereco = enderecoDaApi(env.MAIN_VITE_CORONA_API_URL);
  if (
    !opcoes.permitirEsteComputador &&
    ENDERECOS_DESTE_COMPUTADOR.has(new URL(endereco).hostname)
  ) {
    throw new ErroDeConfiguracao(
      'O instalador da dona não pode apontar para este computador. Preencha MAIN_VITE_CORONA_API_URL com o endereço do servidor (https://IP).',
    );
  }
  const impressoes = impressoesDoServidor(env.MAIN_VITE_CORONA_API_PINS);
  exigirImpressoesFora(endereco, impressoes);
  return { endereco, impressoes };
}
