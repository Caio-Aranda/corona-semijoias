/**
 * A conta do acerto da nota (ESPECIFICACAO §23.8; ADR-0016, item 8). As MESMAS fórmulas do servidor
 * (`CalculoDoAcertoDaNota.java`), provadas pelo mesmo arquivo `packages/shared/exemplos/consignado-acerto.json`.
 *
 * VD = DX − DV. A comissão incide UMA vez sobre o total do VD da nota (não por linha), HALF_UP com duas casas.
 * Total a receber = bruto + pendências anteriores; "ficou devendo" nunca é negativo. No Corona Vendas isto é só PRÉVIA:
 * o servidor recalcula tudo e o que ele devolve vale.
 */
import {
  aplicarPorcentagem,
  centesimosDePorcentagem,
  exigirCentavos,
  percentualDe,
  textoDeCentavos,
  textoDePorcentagem,
} from './dinheiro-do-consignado';

/** Comissão do cliente, em centésimos de ponto percentual (§23.1: de 0% a 40%). */
export const COMISSAO_MAXIMA_EM_CENTESIMOS = 4000n;

export interface LinhaDaNota {
  /** Quantidade levada (DX). */
  dx: number;
  /** Quantidade devolvida (DV). */
  dv: number;
  /** Preço da maleta, texto "200.00". */
  precoUnitario: string;
}

export interface ResultadoDoAcerto {
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: string;
  valorDv: string;
  valorVd: string;
  vendaPercentual: string;
  comissao: string;
  bruto: string;
  pendenciasAnteriores: string;
  totalAReceber: string;
  totalPago: string;
  ficouDevendo: string;
}

/** A comissão ("30", "7.50") está entre 0 e 40, com até duas casas? */
export function comissaoValida(percentual: string): boolean {
  const c = centesimosDePorcentagem(percentual);
  return c !== null && c >= 0n && c <= COMISSAO_MAXIMA_EM_CENTESIMOS;
}

/** @throws RangeError comissão fora de 0 a 40, DV maior que DX ou quantidade negativa. */
export function calcularAcerto(
  linhas: readonly LinhaDaNota[],
  comissaoPercentual: string,
  pendenciasAnteriores: string,
  totalPago: string,
): ResultadoDoAcerto {
  const pct = centesimosDePorcentagem(comissaoPercentual);
  if (pct === null || pct < 0n || pct > COMISSAO_MAXIMA_EM_CENTESIMOS)
    throw new RangeError('A comissão precisa estar entre 0% e 40%.');
  let itensDx = 0;
  let itensDv = 0;
  let valorDx = 0n;
  let valorDv = 0n;
  for (const l of linhas) {
    if (l.dx < 0 || l.dv < 0 || l.dv > l.dx)
      throw new RangeError('A devolução não pode passar do que foi levado.');
    const preco = exigirCentavos(l.precoUnitario);
    itensDx += l.dx;
    itensDv += l.dv;
    valorDx += preco * BigInt(l.dx);
    valorDv += preco * BigInt(l.dv);
  }
  const valorVd = valorDx - valorDv;
  const comissao = aplicarPorcentagem(valorVd, pct);
  const bruto = valorVd - comissao;
  const pendencias = exigirCentavos(pendenciasAnteriores);
  const totalAReceber = bruto + pendencias;
  const pago = exigirCentavos(totalPago);
  const devendo = totalAReceber - pago;
  return {
    itensDx,
    itensDv,
    itensVd: itensDx - itensDv,
    valorDx: textoDeCentavos(valorDx),
    valorDv: textoDeCentavos(valorDv),
    valorVd: textoDeCentavos(valorVd),
    vendaPercentual: textoDePorcentagem(percentualDe(valorVd, valorDx)),
    comissao: textoDeCentavos(comissao),
    bruto: textoDeCentavos(bruto),
    pendenciasAnteriores: textoDeCentavos(pendencias),
    totalAReceber: textoDeCentavos(totalAReceber),
    totalPago: textoDeCentavos(pago),
    ficouDevendo: textoDeCentavos(devendo < 0n ? 0n : devendo),
  };
}
