import { TIPO_DE_APP } from '@corona/api-client-vendedor';
import type { TransporteDeFotosDoCliente } from './fotos-dos-clientes';

export interface OpcoesDoTransporteDeFotosDoCliente {
  baseUrl: string;
  versaoDoApp: string;
  fetch?: typeof globalThis.fetch;
  tempoLimiteMs?: number;
}

/**
 * As rotas `PUT` e `GET /api/vendedor/clientes/{id}/fotos/{tipo}` (tarefa 9.1). O corpo do envio é a imagem; o servidor
 * confere o SHA-256 contra o cadastro e repetir é inofensivo. 401 pede renovação do token; 426 é versão antiga.
 */
export function criarTransporteDeFotosDoCliente(
  o: OpcoesDoTransporteDeFotosDoCliente,
): TransporteDeFotosDoCliente {
  const buscar = o.fetch ?? globalThis.fetch;
  const base = o.baseUrl.replace(/\/+$/, '');
  const cabecalhos = (token: string): Record<string, string> => ({
    'X-App-Tipo': TIPO_DE_APP,
    'X-App-Versao': o.versaoDoApp,
    Authorization: `Bearer ${token}`,
  });
  return {
    async enviar(token, clienteId, tipo, bytes) {
      try {
        const r = await buscar(`${base}/api/vendedor/clientes/${clienteId}/fotos/${tipo}`, {
          method: 'PUT',
          headers: { ...cabecalhos(token), 'Content-Type': 'image/jpeg' },
          body: new Uint8Array(bytes),
          signal: AbortSignal.timeout(o.tempoLimiteMs ?? 60_000),
        });
        await r.body?.cancel().catch(() => undefined);
        if (r.status === 401) return { ok: false, falha: { tipo: 'recusada', status: 401 } };
        if (r.status === 426) return { ok: false, falha: { tipo: 'app-desatualizado', mensagem: '' } };
        return { ok: true, dados: { status: r.status } };
      } catch {
        return { ok: false, falha: { tipo: 'sem-conexao' } };
      }
    },
    async baixar(token, clienteId, tipo) {
      try {
        const r = await buscar(`${base}/api/vendedor/clientes/${clienteId}/fotos/${tipo}`, {
          headers: cabecalhos(token),
          signal: AbortSignal.timeout(o.tempoLimiteMs ?? 60_000),
        });
        if (r.status === 401) return { ok: false, falha: { tipo: 'recusada', status: 401 } };
        if (r.status === 426) return { ok: false, falha: { tipo: 'app-desatualizado', mensagem: '' } };
        if (r.status !== 200) {
          await r.body?.cancel().catch(() => undefined);
          return { ok: true, dados: { status: r.status } };
        }
        return { ok: true, dados: { status: 200, bytes: Buffer.from(await r.arrayBuffer()) } };
      } catch {
        return { ok: false, falha: { tipo: 'sem-conexao' } };
      }
    },
  };
}
