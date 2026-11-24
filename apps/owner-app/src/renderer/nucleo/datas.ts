/**
 * O dia de hoje em Brasília ("2026-11-23"), no formato de data da API. A loja trabalha no horário de Brasília:
 * depois das 21h o dia em UTC já virou, e `toISOString().slice(0, 10)` dava a data de amanhã (revisão de 23/11/2026).
 */
export function hojeEmBrasilia(agora: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(agora);
}
