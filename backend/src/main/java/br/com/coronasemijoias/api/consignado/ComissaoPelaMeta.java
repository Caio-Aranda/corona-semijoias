package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import java.math.BigDecimal;

/**
 * A comissão da revendedora decidida pela meta (ESPECIFICACAO §23.13; plano {@code docs/planos/acerto-45-dias.md}). Pura,
 * sem banco: o servidor decide, e o Corona Vendas usa a mesma conta ({@code packages/shared}), provada pelo mesmo arquivo de
 * exemplos.
 *
 * <p>O VD da nota (a preço da maleta, sem pendências anteriores) é convertido para Guarani <strong>uma vez</strong>, pela taxa
 * de venda do Guarani da cotação do acerto ({@link ConversaoDeMoeda#paraGuarani}, HALF_UP, inteiro). VD em Guarani maior ou
 * igual à meta: percentual "na meta"; senão, o "abaixo". Sem cotação: abaixo. A meta e os percentuais são parâmetros (a dona os
 * configura), nunca constantes aqui.
 */
public final class ComissaoPelaMeta {

    private ComissaoPelaMeta() {
    }

    /** Meta em Guarani (inteiro, maior que zero) e os dois percentuais (0 a 40; o de baixo não passa do da meta). */
    public record Parametros(BigDecimal metaEmGuarani, BigDecimal percentualAbaixo, BigDecimal percentualNaMeta) {

        public boolean valido() {
            return metaEmGuarani != null && metaEmGuarani.signum() > 0 && metaEmGuarani.stripTrailingZeros().scale() <= 0
                    && CalculoDoAcertoDaNota.comissaoValida(percentualAbaixo)
                    && CalculoDoAcertoDaNota.comissaoValida(percentualNaMeta)
                    && percentualAbaixo.compareTo(percentualNaMeta) <= 0;
        }
    }

    /**
     * @param vdEmGuarani o VD da nota em Guarani inteiro; {@code null} quando não havia cotação
     * @param percentual a comissão decidida
     * @param bateuAMeta VD em Guarani maior ou igual à meta (sem cotação, nunca)
     */
    public record Decisao(BigDecimal vdEmGuarani, BigDecimal percentual, boolean bateuAMeta) {
    }

    /**
     * @param valorVd o VD da nota em Real
     * @param taxaGuarani a taxa de venda do Guarani (reais por guarani) da cotação do acerto; {@code null} sem cotação
     * @throws IllegalArgumentException parâmetros inválidos (o servidor valida ao gravar a configuração; aqui é a última trava)
     */
    public static Decisao decidir(BigDecimal valorVd, BigDecimal taxaGuarani, Parametros parametros) {
        if (parametros == null || !parametros.valido()) {
            throw new IllegalArgumentException("A meta e as comissões do consignado estão inválidas.");
        }
        if (taxaGuarani == null) {
            return new Decisao(null, parametros.percentualAbaixo(), false);
        }
        BigDecimal vdEmGuarani = ConversaoDeMoeda.paraGuarani(valorVd, taxaGuarani);
        boolean bateu = vdEmGuarani.compareTo(parametros.metaEmGuarani()) >= 0;
        return new Decisao(vdEmGuarani, bateu ? parametros.percentualNaMeta() : parametros.percentualAbaixo(), bateu);
    }
}
