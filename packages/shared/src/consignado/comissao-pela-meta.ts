/**
 * A comissão da revendedora decidida pela meta (ESPECIFICACAO §23.13). As MESMAS regras do servidor
 * (`ComissaoPelaMeta.java`), provadas pelo mesmo arquivo `packages/shared/exemplos/consignado-acerto.json`.
 *
 * O VD da nota é convertido para Guarani UMA vez, pela taxa de venda do Guarani da cotação do acerto (HALF_UP, inteiro).
 * VD em Guarani ≥ meta → percentual na meta; senão, o de baixo. Sem cotação → o de baixo. Meta e percentuais são
 * parâmetros (a dona os configura). No Corona Vendas é só PRÉVIA: o servidor decide e o que ele devolve vale.
 */
import { guaraniInteiro, taxaDeTexto } from '../moedas';
import { comissaoValida } from './calculo-do-acerto';
import { centesimosDePorcentagem, exigirCentavos } from './dinheiro-do-consignado';

export interface ParametrosDaMeta {
  /** Meta em Guarani, inteiro maior que zero: "700000". */
  metaEmGuarani: string;
  /** Comissão abaixo da meta, "30". */
  percentualAbaixo: string;
  /** Comissão na meta, "40". */
  percentualNaMeta: string;
}

export interface DecisaoDaComissao {
  /** O VD da nota em Guarani inteiro ("700000"); `null` quando não havia cotação. */
  vdEmGuarani: string | null;
  percentual: string;
  bateuAMeta: boolean;
}

/** Meta inteira > 0, percentuais de 0 a 40 com até duas casas, e o de baixo não passa do da meta. */
export function parametrosDaMetaValidos(p: ParametrosDaMeta): boolean {
  if (!/^\d{1,12}$/.test(p.metaEmGuarani) || BigInt(p.metaEmGuarani) <= 0n) return false;
  if (!comissaoValida(p.percentualAbaixo) || !comissaoValida(p.percentualNaMeta)) return false;
  const abaixo = centesimosDePorcentagem(p.percentualAbaixo);
  const naMeta = centesimosDePorcentagem(p.percentualNaMeta);
  return abaixo !== null && naMeta !== null && abaixo <= naMeta;
}

/**
 * @param valorVd o VD da nota em Real, "2310.00"
 * @param taxaGuarani reais por guarani da cotação do acerto ("0.00074074"); `null` sem cotação
 * @throws RangeError parâmetros inválidos
 */
export function decidirComissaoPelaMeta(
  valorVd: string,
  taxaGuarani: string | null,
  parametros: ParametrosDaMeta,
): DecisaoDaComissao {
  if (!parametrosDaMetaValidos(parametros))
    throw new RangeError('A meta e as comissões do consignado estão inválidas.');
  const taxa = taxaGuarani === null ? null : taxaDeTexto(taxaGuarani);
  if (taxa === null) {
    return { vdEmGuarani: null, percentual: parametros.percentualAbaixo, bateuAMeta: false };
  }
  const vd = guaraniInteiro(exigirCentavos(valorVd), taxa);
  const bateu = vd >= BigInt(parametros.metaEmGuarani);
  return {
    vdEmGuarani: vd.toString(),
    percentual: bateu ? parametros.percentualNaMeta : parametros.percentualAbaixo,
    bateuAMeta: bateu,
  };
}
