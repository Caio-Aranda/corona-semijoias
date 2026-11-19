/**
 * Aplica um pagamento aos débitos do cliente do mais antigo para o mais novo (§23.8; ADR-0016, item 10). A mesma regra de
 * `AplicacaoDePagamento.java`. Quem chama passa os débitos JÁ ordenados; o que sobrar depois do último é o "a aplicar".
 */
import { exigirCentavos, textoDeCentavos } from './dinheiro-do-consignado';

export interface Debito {
  id: string;
  /** O que ainda deve, texto "100.00". */
  saldo: string;
}

export interface Aplicacao {
  debitoId: string;
  valor: string;
}

export interface ResultadoDaAplicacao {
  aplicacoes: Aplicacao[];
  sobra: string;
}

export function aplicarPagamento(
  debitosDoMaisAntigo: readonly Debito[],
  valor: string,
): ResultadoDaAplicacao {
  let restante = exigirCentavos(valor);
  if (restante <= 0n) throw new RangeError('O valor do pagamento precisa ser maior que zero.');
  const aplicacoes: Aplicacao[] = [];
  for (const d of debitosDoMaisAntigo) {
    if (restante === 0n) break;
    const saldo = exigirCentavos(d.saldo);
    if (saldo <= 0n) continue;
    const aplicado = restante < saldo ? restante : saldo;
    aplicacoes.push({ debitoId: d.id, valor: textoDeCentavos(aplicado) });
    restante -= aplicado;
  }
  return { aplicacoes, sobra: textoDeCentavos(restante) };
}
