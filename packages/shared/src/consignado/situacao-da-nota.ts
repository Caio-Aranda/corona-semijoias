/**
 * A situação da nota (ADR-0016, item 10), sempre calculada dos fatos: sem acerto ativo é REMARCADO (se houve remarcação)
 * ou EM_ABERTO; com acerto ativo é PENDENTE enquanto o débito tiver saldo e FECHADO quando zerar.
 */
import { exigirCentavos } from './dinheiro-do-consignado';

export type SituacaoDaNota = 'EM_ABERTO' | 'PENDENTE' | 'FECHADO' | 'REMARCADO';

export function situacaoDaNota(
  temAcertoAtivo: boolean,
  foiRemarcada: boolean,
  saldoDoDebito: string | null,
): SituacaoDaNota {
  if (!temAcertoAtivo) return foiRemarcada ? 'REMARCADO' : 'EM_ABERTO';
  return saldoDoDebito !== null && exigirCentavos(saldoDoDebito) > 0n ? 'PENDENTE' : 'FECHADO';
}
