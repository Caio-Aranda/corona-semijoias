import type { ArquivoDeTexto } from './arquivo-de-texto';
import type { Credenciais } from './tipos';

/** O que o login e a renovação dizem sobre o prazo offline (ADR-0004, item 5). */
export interface UltimoContato {
  /** Hora do servidor (UTC, texto ISO) na última resposta válida. Nunca o relógio do computador. */
  horaDoServidor: string;
  /** Prazo máximo sem internet, em dias, como o servidor mandou. */
  tempoMaximoOfflineDias: number;
}

function lerContato(texto: string | null): UltimoContato | null {
  if (!texto) return null;
  try {
    const v = JSON.parse(texto) as Partial<UltimoContato>;
    if (
      typeof v.horaDoServidor === 'string' &&
      !Number.isNaN(Date.parse(v.horaDoServidor)) &&
      typeof v.tempoMaximoOfflineDias === 'number' &&
      Number.isInteger(v.tempoMaximoOfflineDias) &&
      v.tempoMaximoOfflineDias > 0
    ) {
      return { horaDoServidor: v.horaDoServidor, tempoMaximoOfflineDias: v.tempoMaximoOfflineDias };
    }
  } catch {
    /* arquivo estragado: vale como se não existisse */
  }
  return null;
}

/**
 * Guarda o "último contato com o servidor" e o prazo offline que ele informou. Fase 0 só guarda:
 * a regra que bloqueia vendas novas depois do prazo é da Fase 5. Não é segredo, então vai em texto.
 */
export class RegistroDeContato {
  constructor(private readonly arquivo: ArquivoDeTexto) {}

  async ler(): Promise<UltimoContato | null> {
    return lerContato(await this.arquivo.ler().catch(() => null));
  }

  /** Chamado a cada resposta válida de login, renovação ou troca de senha. */
  async registrar(
    c: Pick<Credenciais, 'horaDoServidor' | 'tempoMaximoOfflineDias'>,
  ): Promise<void> {
    if (!c.horaDoServidor || Number.isNaN(Date.parse(c.horaDoServidor))) return;
    const anterior = await this.ler();
    const dias = c.tempoMaximoOfflineDias ?? anterior?.tempoMaximoOfflineDias;
    if (dias === undefined) return;
    const novo = lerContato(
      JSON.stringify({ horaDoServidor: c.horaDoServidor, tempoMaximoOfflineDias: dias }),
    );
    if (!novo) return;
    await this.arquivo.gravar(JSON.stringify(novo)).catch(() => undefined);
  }
}
