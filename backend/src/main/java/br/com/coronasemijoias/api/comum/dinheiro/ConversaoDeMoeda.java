package br.com.coronasemijoias.api.comum.dinheiro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Conversão de Real para Dólar e Guarani, só para mostrar na tela (ADR-0006, itens 6 e 9). Junto com
 * {@link Dinheiro}, é o único lugar do código com {@code RoundingMode}.
 *
 * <p>Uma divisão só, com o arredondamento no fim ({@code divide(taxa, escala, HALF_UP)} devolve o
 * quociente exato arredondado): nunca uma conta intermediária arredondada. O resultado <strong>nunca é
 * gravado</strong>: tudo é gravado em Real (ADR-0006, item 9). Totais em Dólar ou em Guarani são a soma
 * dos lançamentos já convertidos, cada um pela cotação do seu dia (item 10); quem soma é quem chama.
 *
 * <p>As taxas são as de venda (reais por unidade da moeda), como estão em {@code cotacoes}.
 */
public final class ConversaoDeMoeda {

    /** Casas do Dólar na tela. */
    public static final int ESCALA_DOLAR = 2;
    /** Casas do Guarani na tela (sem centavos). */
    public static final int ESCALA_GUARANI = 0;

    private static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_UP;

    private ConversaoDeMoeda() {
    }

    /** US$ = R$ ÷ taxa de venda do Dólar, com 2 casas. */
    public static BigDecimal paraDolar(BigDecimal reais, BigDecimal usdVenda) {
        return converter(reais, usdVenda, ESCALA_DOLAR);
    }

    /** Gs. = R$ ÷ taxa de venda do Guarani, com 0 casas. */
    public static BigDecimal paraGuarani(BigDecimal reais, BigDecimal pygVenda) {
        return converter(reais, pygVenda, ESCALA_GUARANI);
    }

    private static BigDecimal converter(BigDecimal reais, BigDecimal taxa, int escala) {
        Objects.requireNonNull(reais, "reais");
        Objects.requireNonNull(taxa, "taxa");
        if (reais.stripTrailingZeros().scale() > Dinheiro.ESCALA) {
            throw new IllegalArgumentException("O valor em reais " + reais.toPlainString()
                    + " tem mais de duas casas: arredonde antes de converter (ADR-0002).");
        }
        if (taxa.signum() <= 0) {
            throw new IllegalArgumentException("A taxa de câmbio precisa ser maior que zero, veio "
                    + taxa.toPlainString() + ".");
        }
        return reais.divide(taxa, escala, ARREDONDAMENTO);
    }
}
