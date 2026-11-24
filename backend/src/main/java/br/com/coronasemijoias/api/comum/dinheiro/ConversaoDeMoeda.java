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

    /**
     * R$ = Gs. x taxa de venda do Guarani, com 2 casas (HALF_UP): o caminho inverso. Mostra "quanto falta, em Real" na prévia
     * da comissão (tarefa 7.5) e converte o recebimento do representante digitado em Guarani (23/11/2026): aí o resultado é o
     * valor em Real do lançamento, que é o gravado. O Guarani é inteiro (sem centavos).
     */
    public static BigDecimal guaraniParaReais(BigDecimal guarani, BigDecimal pygVenda) {
        Objects.requireNonNull(guarani, "guarani");
        Objects.requireNonNull(pygVenda, "pygVenda");
        if (guarani.stripTrailingZeros().scale() > ESCALA_GUARANI) {
            throw new IllegalArgumentException("O valor em Guarani " + guarani.toPlainString()
                    + " precisa ser inteiro (Guarani não tem centavos).");
        }
        if (pygVenda.signum() <= 0) {
            throw new IllegalArgumentException("A taxa de câmbio precisa ser maior que zero, veio "
                    + pygVenda.toPlainString() + ".");
        }
        return guarani.multiply(pygVenda).setScale(Dinheiro.ESCALA, ARREDONDAMENTO);
    }

    /**
     * R$ = Gs. ÷ guaranis por real, com 2 casas (HALF_UP): o recebimento digitado em Guarani quando a cotação é a da dona
     * (ADR-0006, item 15). Ela digita "1 real = Gs. 1.350"; dividir por esse número evita o erro de centavos que a taxa de
     * 8 casas (0,00074074) daria em valores grandes. Mesmas recusas de {@link #guaraniParaReais}.
     */
    public static BigDecimal guaraniParaReaisPorGuaranisPorReal(BigDecimal guarani, BigDecimal guaranisPorReal) {
        Objects.requireNonNull(guarani, "guarani");
        Objects.requireNonNull(guaranisPorReal, "guaranisPorReal");
        if (guarani.stripTrailingZeros().scale() > ESCALA_GUARANI) {
            throw new IllegalArgumentException("O valor em Guarani " + guarani.toPlainString()
                    + " precisa ser inteiro (Guarani não tem centavos).");
        }
        if (guaranisPorReal.signum() <= 0) {
            throw new IllegalArgumentException("A taxa de câmbio precisa ser maior que zero, veio "
                    + guaranisPorReal.toPlainString() + ".");
        }
        return guarani.divide(guaranisPorReal, Dinheiro.ESCALA, ARREDONDAMENTO);
    }

    /**
     * R$ = US$ x taxa de venda do Dólar, com 2 casas (HALF_UP), numa multiplicação só: o recebimento do representante digitado
     * em Dólar (23/11/2026). O resultado é o valor em Real do lançamento. O Dólar tem no máximo 2 casas.
     */
    public static BigDecimal dolarParaReais(BigDecimal dolar, BigDecimal usdVenda) {
        Objects.requireNonNull(dolar, "dolar");
        Objects.requireNonNull(usdVenda, "usdVenda");
        if (dolar.stripTrailingZeros().scale() > ESCALA_DOLAR) {
            throw new IllegalArgumentException("O valor em Dólar " + dolar.toPlainString() + " tem mais de duas casas.");
        }
        if (usdVenda.signum() <= 0) {
            throw new IllegalArgumentException("A taxa de câmbio precisa ser maior que zero, veio "
                    + usdVenda.toPlainString() + ".");
        }
        return dolar.multiply(usdVenda).setScale(Dinheiro.ESCALA, ARREDONDAMENTO);
    }

    /** Casas das taxas de cotação (NUMERIC(18,8)). */
    public static final int ESCALA_DA_TAXA = 8;

    /** A taxa do Guarani (reais por Guarani) da cotação da dona: 1 ÷ guaranis por real, 8 casas (ADR-0006, item 15). */
    public static BigDecimal taxaDoGuarani(BigDecimal guaranisPorReal) {
        Objects.requireNonNull(guaranisPorReal, "guaranisPorReal");
        if (guaranisPorReal.signum() <= 0) {
            throw new IllegalArgumentException("Os guaranis por real precisam ser maiores que zero, veio "
                    + guaranisPorReal.toPlainString() + ".");
        }
        return BigDecimal.ONE.divide(guaranisPorReal, ESCALA_DA_TAXA, ARREDONDAMENTO);
    }

    /** Guaranis por real de uma taxa de Guarani publicada (1 ÷ taxa), 2 casas: só para mostrar ao lado do que a dona digitou. */
    public static BigDecimal guaranisPorReal(BigDecimal pygVenda) {
        Objects.requireNonNull(pygVenda, "pygVenda");
        return BigDecimal.ONE.divide(pygVenda, Dinheiro.ESCALA, ARREDONDAMENTO);
    }

    /**
     * Guaranis por real com as 8 casas da tabela ({@code 1 ÷ taxa do Guarani}, HALF_UP): o "1 real = Gs. N" exato de uma cotação
     * do Banco Central, quando a dona muda só o Dólar e o Guarani fica o que valia (24/11/2026).
     */
    public static BigDecimal guaranisPorRealComOitoCasas(BigDecimal pygVenda) {
        Objects.requireNonNull(pygVenda, "pygVenda");
        return BigDecimal.ONE.divide(pygVenda, ESCALA_DA_TAXA, ARREDONDAMENTO);
    }

    /** Uma taxa digitada com as 8 casas da tabela de cotações (HALF_UP). */
    public static BigDecimal taxaComOitoCasas(BigDecimal taxa) {
        return Objects.requireNonNull(taxa, "taxa").setScale(ESCALA_DA_TAXA, ARREDONDAMENTO);
    }

    /** Um valor de cotação com as casas pedidas (HALF_UP), só para mostrar. */
    public static BigDecimal paraCasas(BigDecimal valor, int casas) {
        return Objects.requireNonNull(valor, "valor").setScale(casas, ARREDONDAMENTO);
    }

    /** |digitado − referência| ÷ referência, em %, 2 casas (aviso de diferença da cotação da dona; ADR-0006, item 15). */
    public static BigDecimal diferencaPorCento(BigDecimal digitado, BigDecimal referencia) {
        Objects.requireNonNull(digitado, "digitado");
        Objects.requireNonNull(referencia, "referencia");
        if (referencia.signum() <= 0) {
            throw new IllegalArgumentException("A referência precisa ser maior que zero, veio " + referencia.toPlainString() + ".");
        }
        return digitado.subtract(referencia).abs().multiply(new BigDecimal("100")).divide(referencia, Dinheiro.ESCALA, ARREDONDAMENTO);
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
