/**
 * Prazo da garantia no computador (tarefa 6.8; ADR-0014, item 4): dias do calendário de Brasília entre a venda e a
 * devolução, como o `RegrasDaDevolucao` do servidor. O motivo `DEFEITO` usa o prazo do defeito; os outros, o do
 * arrependimento. É prévia: o servidor confere de novo pela hora corrigida e, se discordar, a devolução vai para a
 * revisão da dona (nunca some).
 */

export const MOTIVOS_DA_DEVOLUCAO = ['ARREPENDIMENTO', 'DEFEITO', 'TAMANHO', 'OUTRO'] as const;
export type MotivoDaDevolucao = (typeof MOTIVOS_DA_DEVOLUCAO)[number];

export interface PrazosDaGarantia {
  arrependimento: number;
  defeito: number;
}

/** Os padrões do servidor, para o banco que ainda não recebeu os prazos (dúvida V2 do plano da 6.8). */
export const PRAZOS_PADRAO: Readonly<PrazosDaGarantia> = Object.freeze({
  arrependimento: 7,
  defeito: 30,
});

const ehDias = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 1 && v <= 3650;

/** Os prazos do bloco `parametros` guardado no computador (tarefa 6.2); o que faltar ou vier torto vale o padrão. */
export function lerPrazos(texto: string | null | undefined): PrazosDaGarantia {
  let p: Record<string, unknown> = {};
  try {
    const lido: unknown = texto ? JSON.parse(texto) : {};
    if (typeof lido === 'object' && lido !== null) p = lido as Record<string, unknown>;
  } catch {
    /* fica o padrão */
  }
  const a = p['prazoArrependimentoDias'];
  const d = p['prazoDefeitoDias'];
  return {
    arrependimento: ehDias(a) ? a : PRAZOS_PADRAO.arrependimento,
    defeito: ehDias(d) ? d : PRAZOS_PADRAO.defeito,
  };
}

export function prazoDoMotivo(motivo: MotivoDaDevolucao, prazos: PrazosDaGarantia): number {
  return motivo === 'DEFEITO' ? prazos.defeito : prazos.arrependimento;
}

/** Dia civil (AAAA-MM-DD) em Brasília de um instante. */
export function diaEmBrasilia(instante: string | Date): string {
  const d = typeof instante === 'string' ? new Date(instante) : instante;
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(d);
}

const DIA_EM_MS = 86_400_000;

function comoNumero(dia: string): number {
  const [a, m, d] = dia.split('-').map(Number);
  return Date.UTC(a ?? 0, (m ?? 1) - 1, d ?? 1) / DIA_EM_MS;
}

/** Dias do calendário de Brasília entre dois dias AAAA-MM-DD. */
export function diasEntre(de: string, ate: string): number {
  return Math.round(comoNumero(ate) - comoNumero(de));
}

/** O dia AAAA-MM-DD somado de `dias`. */
export function somarDias(dia: string, dias: number): string {
  return new Date((comoNumero(dia) + dias) * DIA_EM_MS).toISOString().slice(0, 10);
}

/** O último dia (Brasília) em que a garantia ainda vale. */
export function garantiaAte(vendidaEm: string, prazo: number): string {
  return somarDias(diaEmBrasilia(vendidaEm), prazo);
}

/** `dia(devolução) − dia(venda) ≤ prazo do motivo`, os dois dias em Brasília. */
export function dentroDoPrazo(
  vendidaEm: string,
  quando: Date,
  motivo: MotivoDaDevolucao,
  prazos: PrazosDaGarantia,
): boolean {
  return (
    diasEntre(diaEmBrasilia(vendidaEm), diaEmBrasilia(quando)) <= prazoDoMotivo(motivo, prazos)
  );
}
