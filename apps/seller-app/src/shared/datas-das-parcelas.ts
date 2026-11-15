/** Datas das parcelas (tarefa 7.11): só calendário, nenhum dinheiro. Dia civil AAAA-MM-DD em Brasília. */

/** Soma meses a uma data AAAA-MM-DD sem passar do fim do mês (31/01 + 1 mês = 28/02). */
export function maisMeses(dia: string, meses: number): string {
  const [a = 0, m = 1, d = 1] = dia.split('-').map(Number);
  const total = a * 12 + (m - 1) + meses;
  const ano = Math.floor(total / 12);
  const mes = (total % 12) + 1;
  const ultimo = new Date(Date.UTC(ano, mes, 0)).getUTCDate();
  return `${ano}-${String(mes).padStart(2, '0')}-${String(Math.min(d, ultimo)).padStart(2, '0')}`;
}

/** O primeiro vencimento pode ser de hoje até um ano depois (a mesma regra do servidor). */
export const MESES_ATE_O_PRIMEIRO_VENCIMENTO = 12;
