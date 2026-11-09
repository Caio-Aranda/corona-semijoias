import { createClient, createConfig, type Client } from './gerado/client';
import { ErroAppDesatualizado, ErroTipoDeAppInvalido } from './erros';

/** Tipo de app que este cliente declara em toda chamada (ADR-0004, item 10). */
export const TIPO_DE_APP = 'gestao';

export interface OpcoesDoCliente {
  /** Endereço da API, sem barra no fim. */
  baseUrl: string;
  /** Versão do aplicativo, três números (por exemplo "1.4.2"). Vai em X-App-Versao. */
  versaoDoApp: string;
  /** Só para testes. */
  fetch?: typeof globalThis.fetch;
}

interface CorpoDeErro {
  codigo?: unknown;
  detail?: unknown;
  versaoMinima?: unknown;
  versaoAtual?: unknown;
}

const texto = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);

/**
 * Cria o cliente da API do Corona Gestão. Toda chamada leva X-App-Tipo e X-App-Versao.
 * Respostas 426 (APP_DESATUALIZADO) e 400 (APP_TIPO_INVALIDO) viram erros tipados
 * (ErroAppDesatualizado, ErroTipoDeAppInvalido) com a mensagem que veio da API.
 * Passe o resultado em cada chamada: `eu({ client })`.
 */
export function criarClienteDaApi(opcoes: OpcoesDoCliente): Client {
  const cliente = createClient(
    createConfig({
      baseUrl: opcoes.baseUrl,
      headers: { 'X-App-Tipo': TIPO_DE_APP, 'X-App-Versao': opcoes.versaoDoApp },
      ...(opcoes.fetch ? { fetch: opcoes.fetch } : {}),
    }),
  );

  cliente.interceptors.response.use(async (resposta) => {
    if (resposta.status !== 426 && resposta.status !== 400) return resposta;
    const corpo = (await resposta
      .clone()
      .json()
      .catch(() => null)) as CorpoDeErro | null;
    const mensagem = texto(corpo?.detail) ?? '';
    if (resposta.status === 426 && corpo?.codigo === 'APP_DESATUALIZADO') {
      throw new ErroAppDesatualizado(mensagem, texto(corpo.versaoMinima), texto(corpo.versaoAtual));
    }
    if (resposta.status === 400 && corpo?.codigo === 'APP_TIPO_INVALIDO') {
      throw new ErroTipoDeAppInvalido(mensagem);
    }
    return resposta;
  });

  return cliente;
}
