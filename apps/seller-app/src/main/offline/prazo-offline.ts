import type { BancoAberto } from '../banco/banco-local';
import type { PrazoOffline } from '../../shared/prazo';
import { CHAVES, lerEstado, registrarHoraLocal } from '../sync/estado-sync';

export const DIA_MS = 86_400_000;
/** O aviso começa 3 dias antes do fim do prazo (ADR-0013, item 12; dúvida D3). */
export const AVISO_DIAS = 3;
/** Quanto o relógio pode voltar sem ser tratado como relógio mexido (os relógios se acertam sozinhos). */
export const TOLERANCIA_DO_RELOGIO_MS = 5 * 60_000;
/** Vale enquanto o servidor ainda não informou o prazo (`configuracoes.tempo_maximo_offline_dias`, padrão 10). */
export const PRAZO_PADRAO_DIAS = 10;

export interface EntradaDoPrazo {
  /** A hora do computador agora. */
  agora: Date;
  /** A hora do computador no instante do último contato válido com o servidor. */
  contatoLocal: Date | null;
  /** A maior hora do computador já vista. */
  maiorHoraVista: Date | null;
  prazoDias: number;
}

/**
 * A regra do prazo máximo sem internet (ADR-0013, item 12), pura e sem relógio próprio:
 * - dias sem contato = hora local agora menos a hora local do último contato (a hora do servidor nunca entra na conta
 *   local, e o relógio do computador nunca decide sozinho: só mede o que passou desde um contato);
 * - relógio **mais de 5 minutos antes** da maior hora já vista, ou antes do último contato, é prazo esgotado por causa
 *   do relógio; relógio adiantado só faz o prazo acabar mais cedo (o lado seguro);
 * - 10 dias cheios já é esgotado.
 */
export function avaliarPrazo(e: EntradaDoPrazo): PrazoOffline {
  const esgotado = (motivo: 'PRAZO' | 'RELOGIO'): PrazoOffline => ({
    situacao: 'ESGOTADO',
    diasRestantes: 0,
    motivo,
  });
  if (!e.contatoLocal) return esgotado('PRAZO');
  const agora = e.agora.getTime();
  const contato = e.contatoLocal.getTime();
  const maior = Math.max(e.maiorHoraVista?.getTime() ?? contato, contato);
  if (agora < maior - TOLERANCIA_DO_RELOGIO_MS || agora < contato - TOLERANCIA_DO_RELOGIO_MS) {
    return esgotado('RELOGIO');
  }
  const restanteMs = e.prazoDias * DIA_MS - (agora - contato);
  if (restanteMs <= 0) return esgotado('PRAZO');
  if (restanteMs > AVISO_DIAS * DIA_MS) return { situacao: 'EM_DIA', diasRestantes: 0, motivo: null };
  return { situacao: 'AVISO', diasRestantes: Math.ceil(restanteMs / DIA_MS), motivo: null };
}

export interface OpcoesDoControleDoPrazo {
  banco: () => BancoAberto | null;
  agora: () => Date;
}

/**
 * O prazo do computador, lido do banco local (`estado_sync`): o contato e a maior hora vista sobrevivem ao fechar o
 * aplicativo. Sem banco aberto não há como provar o prazo, então vale como esgotado.
 */
export class ControleDoPrazo {
  constructor(private readonly o: OpcoesDoControleDoPrazo) {}

  situacao(): PrazoOffline {
    const db = this.o.banco();
    if (!db) return { situacao: 'ESGOTADO', diasRestantes: 0, motivo: 'PRAZO' };
    return avaliarPrazo({
      agora: this.o.agora(),
      contatoLocal: data(lerEstado(db, CHAVES.horaLocalDoContato)),
      maiorHoraVista: data(lerEstado(db, CHAVES.maiorHoraLocal)),
      prazoDias: prazoDoServidor(lerEstado(db, CHAVES.parametros)),
    });
  }

  /** A cada minuto e a cada gravação: a maior hora local já vista só sobe. */
  batida(): void {
    const db = this.o.banco();
    if (db) registrarHoraLocal(db, this.o.agora());
  }
}

function data(texto: string | null): Date | null {
  if (!texto) return null;
  const d = new Date(texto);
  return Number.isNaN(d.getTime()) ? null : d;
}

function prazoDoServidor(parametros: string | null): number {
  if (!parametros) return PRAZO_PADRAO_DIAS;
  try {
    const dias = (JSON.parse(parametros) as { tempoMaximoOfflineDias?: unknown }).tempoMaximoOfflineDias;
    return typeof dias === 'number' && Number.isInteger(dias) && dias > 0 ? dias : PRAZO_PADRAO_DIAS;
  } catch {
    return PRAZO_PADRAO_DIAS;
  }
}
