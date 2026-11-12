import { TIPO_DE_APP } from '@corona/api-client-vendedor';
import type { TamanhoDeFoto } from '../../shared/operacoes';
import type { Resultado } from '../tipos';

/** O que o servidor devolveu para um tamanho de foto. */
export interface FotoBaixada {
  bytes: Buffer;
  /** O SHA-256 daquele tamanho, do cabeçalho; `null` se o servidor não mandou. */
  sha256: string | null;
}

export type RespostaDaFoto =
  | { situacao: 'baixada'; foto: FotoBaixada }
  /** 404: a foto não existe mais ou não é de uma peça das maletas deste vendedor. */
  | { situacao: 'inexistente' }
  /** Outro status (5xx, 429): tentar de novo mais tarde. */
  | { situacao: 'indisponivel' };

export interface TransporteDeFotos {
  baixar(token: string, hash: string, tamanho: TamanhoDeFoto): Promise<Resultado<RespostaDaFoto>>;
}

export const CABECALHO_DO_SHA256 = 'X-Conteudo-Sha256';

export interface OpcoesDoTransporteDeFotos {
  baseUrl: string;
  versaoDoApp: string;
  fetch?: typeof globalThis.fetch;
  tempoLimiteMs?: number;
}

/** A rota de fotos do vendedor (`/api/vendedor/fotos/{hash}/{tamanho}`, tarefa 4.3), separada do envio e do recebimento. */
export function criarTransporteDeFotos(o: OpcoesDoTransporteDeFotos): TransporteDeFotos {
  const buscar = o.fetch ?? globalThis.fetch;
  const base = o.baseUrl.replace(/\/+$/, '');
  return {
    async baixar(token, hash, tamanho) {
      try {
        const r = await buscar(`${base}/api/vendedor/fotos/${hash}/${tamanho}`, {
          headers: {
            'X-App-Tipo': TIPO_DE_APP,
            'X-App-Versao': o.versaoDoApp,
            Authorization: `Bearer ${token}`,
          },
          signal: AbortSignal.timeout(o.tempoLimiteMs ?? 60_000),
        });
        if (r.status === 401) return { ok: false, falha: { tipo: 'recusada', status: 401 } };
        if (r.status === 426) {
          return { ok: false, falha: { tipo: 'app-desatualizado', mensagem: '' } };
        }
        if (r.status === 404) {
          await r.body?.cancel().catch(() => undefined);
          return { ok: true, dados: { situacao: 'inexistente' } };
        }
        if (r.status !== 200) {
          await r.body?.cancel().catch(() => undefined);
          return { ok: true, dados: { situacao: 'indisponivel' } };
        }
        // Se a conexão cair no meio do corpo, isto lança: nada é entregue pela metade.
        const bytes = Buffer.from(await r.arrayBuffer());
        return {
          ok: true,
          dados: {
            situacao: 'baixada',
            foto: { bytes, sha256: r.headers.get(CABECALHO_DO_SHA256) },
          },
        };
      } catch {
        return { ok: false, falha: { tipo: 'sem-conexao' } };
      }
    },
  };
}
