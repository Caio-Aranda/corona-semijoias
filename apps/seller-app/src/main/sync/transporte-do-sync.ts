import { TIPO_DE_APP } from '@corona/api-client-vendedor';
import type { Resultado } from '../tipos';

/** A resposta HTTP do servidor, já sem o token: status, corpo (JSON ou nada) e a espera pedida (`Retry-After`). */
export interface RespostaDoServidor {
  status: number;
  corpo: unknown;
  /** Segundos de `Retry-After` (429), quando vierem. */
  esperarSegundos?: number;
}

/**
 * As rotas do sync do vendedor (tarefas 5.4 e 5.5). Cada chamada recebe o token de quem chama (a guarda de tokens) e
 * devolve:
 * - `ok: true` com qualquer resposta do servidor, exceto 401 e 426;
 * - `ok: false` `recusada 401` (a guarda renova uma vez e repete), `app-desatualizado` (426) ou `sem-conexao`.
 */
export interface TransporteDoSync {
  /** A rota de saúde responde em até 5 s? É a medida de "com internet"; `net.isOnline()` é só uma dica. */
  saude(): Promise<boolean>;
  receber(
    token: string,
    pedido: { cursor: string | null; limite: number; tabela?: string },
  ): Promise<Resultado<RespostaDoServidor>>;
  /** `corpo` é o texto já montado: o `dados` de cada operação vai como estava guardado, byte a byte. */
  enviar(token: string, corpo: string): Promise<Resultado<RespostaDoServidor>>;
  conferir(token: string): Promise<Resultado<RespostaDoServidor>>;
  avisarDiferencas(token: string, tabelas: string[]): Promise<Resultado<RespostaDoServidor>>;
}

export interface OpcoesDoTransporte {
  baseUrl: string;
  versaoDoApp: string;
  /** A rede do Chromium no app (certificado fixado); nos testes, o `fetch` do Node. */
  fetch?: typeof globalThis.fetch;
  /** Tempo máximo de cada chamada de dados (padrão 60 s). */
  tempoLimiteMs?: number;
  /** Tempo máximo da rota de saúde (padrão 5 s, ADR-0013 item 19). */
  tempoDaSaudeMs?: number;
}

export function criarTransporteDoSync(o: OpcoesDoTransporte): TransporteDoSync {
  const buscar = o.fetch ?? globalThis.fetch;
  const base = o.baseUrl.replace(/\/+$/, '');
  const cabecalhos = (token?: string, json = false): Record<string, string> => ({
    'X-App-Tipo': TIPO_DE_APP,
    'X-App-Versao': o.versaoDoApp,
    Accept: 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(json ? { 'Content-Type': 'application/json' } : {}),
  });

  const chamar = async (
    caminho: string,
    inicio: RequestInit,
    tempo = o.tempoLimiteMs ?? 60_000,
  ): Promise<Resultado<RespostaDoServidor>> => {
    let resposta: Response;
    try {
      resposta = await buscar(`${base}${caminho}`, {
        ...inicio,
        signal: AbortSignal.timeout(tempo),
      });
    } catch {
      return { ok: false, falha: { tipo: 'sem-conexao' } };
    }
    let corpo: unknown;
    try {
      const texto = await resposta.text();
      corpo = texto ? (JSON.parse(texto) as unknown) : null;
    } catch {
      corpo = null;
    }
    if (resposta.status === 401) return { ok: false, falha: { tipo: 'recusada', status: 401 } };
    if (resposta.status === 426) {
      const detalhe = (corpo as { detail?: unknown } | null)?.detail;
      return {
        ok: false,
        falha: { tipo: 'app-desatualizado', mensagem: typeof detalhe === 'string' ? detalhe : '' },
      };
    }
    const r: RespostaDoServidor = { status: resposta.status, corpo };
    const espera = Number(resposta.headers.get('Retry-After'));
    if (Number.isFinite(espera) && espera > 0) r.esperarSegundos = espera;
    return { ok: true, dados: r };
  };

  return {
    async saude() {
      try {
        const r = await buscar(`${base}/actuator/health`, {
          headers: cabecalhos(),
          signal: AbortSignal.timeout(o.tempoDaSaudeMs ?? 5_000),
        });
        await r.body?.cancel().catch(() => undefined);
        return r.ok;
      } catch {
        return false;
      }
    },
    receber(token, pedido) {
      const q = new URLSearchParams();
      if (pedido.cursor) q.set('cursor', pedido.cursor);
      q.set('limite', String(pedido.limite));
      if (pedido.tabela) q.set('tabela', pedido.tabela);
      return chamar(`/api/vendedor/sync/pull?${q.toString()}`, { headers: cabecalhos(token) });
    },
    enviar(token, corpo) {
      return chamar('/api/vendedor/sync/push', {
        method: 'POST',
        headers: cabecalhos(token, true),
        body: corpo,
      });
    },
    conferir(token) {
      return chamar('/api/vendedor/sync/conferencia', { headers: cabecalhos(token) });
    },
    avisarDiferencas(token, tabelas) {
      return chamar('/api/vendedor/sync/conferencia/diferencas', {
        method: 'POST',
        headers: cabecalhos(token, true),
        body: JSON.stringify({ tabelas }),
      });
    },
  };
}
