/**
 * O pagamento do acerto como a tela o guarda: só o que a dona digitou ou escolheu, cada linha com o seu UUID.
 * Nenhum valor é somado nem conferido aqui: quem calcula (recebido, devendo, parcelas, crédito) é o sistema.
 */
import type { DestinoDoAFavor, FormaDoPagamento, PagamentoDaTela } from '../../../shared/acertos';
import { centavosDeTexto, lerReaisDigitados } from '../../nucleo/moedas';
import type { PreviaDoFechamento } from './previa';

export interface PagamentoEmEdicao {
  recebimentos: { id: string; forma: FormaDoPagamento; digitado: string }[];
  boleto: { digitado: string; vencimento: string };
  parcelas: number;
  primeiroVencimento: string;
  contasAntigas: Record<string, { id: string; forma: FormaDoPagamento; digitado: string }>;
  aFavor: DestinoDoAFavor;
  formaDoPagamento: FormaDoPagamento;
}

export function pagamentoInicial(): PagamentoEmEdicao {
  return {
    recebimentos: [
      { id: crypto.randomUUID(), forma: 'DINHEIRO', digitado: '' },
      { id: crypto.randomUUID(), forma: 'PIX', digitado: '' },
    ],
    boleto: { digitado: '', vencimento: '' },
    parcelas: 1,
    primeiroVencimento: '',
    contasAntigas: {},
    aFavor: 'CREDITO',
    formaDoPagamento: 'DINHEIRO',
  };
}

const DATA = /^\d{4}-\d{2}-\d{2}$/;

/** O valor digitado como texto da API, ou `null` se está vazio, é zero ou não é um valor em reais. */
export function valorDigitado(digitado: string): string | null {
  if (digitado.trim() === '') return null;
  const v = lerReaisDigitados(digitado);
  return v !== null && /[1-9]/.test(v) ? v : null;
}

const invalido = (digitado: string) => digitado.trim() !== '' && valorDigitado(digitado) === null;

/** Tem algum valor que não dá para mandar ainda (texto estranho ou boleto sem vencimento)? */
export function pagamentoIncompleto(p: PagamentoEmEdicao): boolean {
  if (p.recebimentos.some((r) => invalido(r.digitado))) return true;
  if (invalido(p.boleto.digitado)) return true;
  if (valorDigitado(p.boleto.digitado) !== null && !DATA.test(p.boleto.vencimento)) return true;
  return Object.values(p.contasAntigas).some((c) => invalido(c.digitado));
}

/** O que vai para o sistema. `aFavor` só vai quando o sistema disse que o acerto ficou a favor do vendedor. */
export function pagamentoParaPedido(
  p: PagamentoEmEdicao,
  previa: PreviaDoFechamento | null,
): PagamentoDaTela {
  const saida: PagamentoDaTela = {};
  const recebimentos = p.recebimentos.flatMap((r) => {
    const valor = valorDigitado(r.digitado);
    return valor ? [{ id: r.id, forma: r.forma, valor }] : [];
  });
  if (recebimentos.length > 0) saida.recebimentos = recebimentos;
  const valorDoBoleto = valorDigitado(p.boleto.digitado);
  if (valorDoBoleto && DATA.test(p.boleto.vencimento)) {
    saida.boleto = { valor: valorDoBoleto, vencimento: p.boleto.vencimento };
  }
  if (DATA.test(p.primeiroVencimento)) {
    saida.restante = { parcelas: p.parcelas, primeiroVencimento: p.primeiroVencimento };
  }
  const contas = Object.entries(p.contasAntigas).flatMap(([contaId, c]) => {
    const valor = valorDigitado(c.digitado);
    return valor ? [{ id: c.id, contaId, forma: c.forma, valor }] : [];
  });
  if (contas.length > 0) saida.contasAntigas = contas;
  if (previa && ehNegativo(previa.aPagarNoAcerto)) {
    saida.aFavorDoVendedor = p.aFavor;
    if (p.aFavor === 'PAGAR_NA_HORA') saida.formaDoPagamento = p.formaDoPagamento;
  }
  return saida;
}

/** O valor que o sistema mandou é negativo? (Só olha o sinal.) */
export function ehNegativo(valor: string): boolean {
  const c = centavosDeTexto(valor);
  return c !== null && c < 0n;
}

/** O valor que o sistema mandou é maior que zero? (Só olha o sinal.) */
export function ehPositivo(valor: string): boolean {
  const c = centavosDeTexto(valor);
  return c !== null && c > 0n;
}
