package br.com.coronasemijoias.api.comum.dinheiro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Único utilitário de dinheiro do sistema (ADR-0002, item 2) e o único lugar do código com
 * {@code RoundingMode.HALF_UP} (um teste de guarda reprova o uso fora daqui).
 *
 * <p>Regra: HALF_UP com escala 2; <strong>cada valor calculado é arredondado no instante em que é
 * calculado</strong>, e totais são a soma de valores já arredondados. Por isso as operações que
 * recebem dinheiro recusam valores com mais de duas casas: quem chega aqui com três casas está
 * somando um valor intermediário sem arredondar.
 *
 * <p>Porcentagens vão de 0 a 100 com até duas casas ({@code NUMERIC(5,2)}): {@code 7.50} é 7,5%.
 */
public final class Dinheiro {

    /** Escala de todo valor em dinheiro ({@code NUMERIC(14,2)}). */
    public static final int ESCALA = 2;

    private static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(ESCALA);
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private Dinheiro() {
    }

    /** Arredonda para duas casas, HALF_UP (1,005 vira 1,01; -1,005 vira -1,01). */
    public static BigDecimal arredondar(BigDecimal valor) {
        Objects.requireNonNull(valor, "valor");
        return valor.setScale(ESCALA, ARREDONDAMENTO);
    }

    /** Soma valores já arredondados. A soma de nada é 0,00. */
    public static BigDecimal somar(Collection<BigDecimal> valores) {
        BigDecimal total = ZERO;
        for (BigDecimal valor : valores) {
            total = total.add(jaArredondado(valor, "valor"));
        }
        return total.setScale(ESCALA);
    }

    public static BigDecimal subtrair(BigDecimal valor, BigDecimal menos) {
        return jaArredondado(valor, "valor").subtract(jaArredondado(menos, "valor subtraído")).setScale(ESCALA);
    }

    /** Preço da linha: quantidade inteira vezes o preço unitário. Já tem duas casas, não arredonda. */
    public static BigDecimal precoDaLinha(BigDecimal precoUnitario, int quantidade) {
        return jaArredondado(precoUnitario, "preço unitário").multiply(BigDecimal.valueOf(quantidade)).setScale(ESCALA);
    }

    /** Valor vezes a porcentagem, arredondado uma vez (33,33 x 7,50% = 2,49975, que vira 2,50). */
    public static BigDecimal aplicarPorcentagem(BigDecimal valor, BigDecimal porcentagem) {
        BigDecimal exato = jaArredondado(valor, "valor").multiply(porcentagemValida(porcentagem)).divide(CEM);
        return arredondar(exato);
    }

    /**
     * Divide o total em {@code n} parcelas: as n-1 primeiras são o total dividido por n,
     * arredondado; a última é o total menos a soma das anteriores, para fechar exato.
     *
     * <p>Recusa quando essa regra deixaria a última parcela com o sinal invertido (total pequeno
     * demais para tantas parcelas, por exemplo 0,18 em 12 vezes). Valor mínimo de parcela é regra
     * de negócio de quem chama.
     */
    public static List<BigDecimal> dividirEmParcelas(BigDecimal total, int n) {
        jaArredondado(total, "total");
        if (n < 1) {
            throw new IllegalArgumentException("O número de parcelas precisa ser pelo menos 1, veio " + n + ".");
        }
        BigDecimal parcela = total.divide(BigDecimal.valueOf(n), ESCALA, ARREDONDAMENTO);
        List<BigDecimal> parcelas = new ArrayList<>(n);
        for (int i = 1; i < n; i++) {
            parcelas.add(parcela);
        }
        BigDecimal ultima = total.subtract(parcela.multiply(BigDecimal.valueOf(n - 1L))).setScale(ESCALA);
        if (ultima.signum() != 0 && ultima.signum() != total.signum()) {
            throw new IllegalArgumentException("O total " + total.toPlainString() + " é pequeno demais para "
                    + n + " parcelas: a última ficaria " + ultima.toPlainString() + ".");
        }
        parcelas.add(ultima);
        return List.copyOf(parcelas);
    }

    /**
     * Estornos proporcionais de um valor (por exemplo, a comissão de uma venda) conforme a base é
     * devolvida aos poucos. Cada estorno é {@code valorOriginal x devolvido / baseOriginal},
     * arredondado; o estorno da devolução que completa a base inteira leva o resto, para a soma
     * dos estornos fechar exata com o valor original.
     *
     * @param basesDevolvidas todas as devoluções, na ordem em que aconteceram (a última é a nova)
     * @return um estorno por devolução, na mesma ordem
     */
    public static List<BigDecimal> estornosProporcionais(
            BigDecimal valorOriginal, BigDecimal baseOriginal, List<BigDecimal> basesDevolvidas) {
        jaArredondado(valorOriginal, "valor original");
        if (jaArredondado(baseOriginal, "base original").signum() <= 0) {
            throw new IllegalArgumentException("A base do estorno precisa ser maior que zero.");
        }
        List<BigDecimal> estornos = new ArrayList<>(basesDevolvidas.size());
        BigDecimal devolvido = ZERO;
        BigDecimal estornado = ZERO;
        for (BigDecimal base : basesDevolvidas) {
            if (jaArredondado(base, "valor devolvido").signum() <= 0) {
                throw new IllegalArgumentException("Cada valor devolvido precisa ser maior que zero.");
            }
            devolvido = devolvido.add(base);
            int comparacao = devolvido.compareTo(baseOriginal);
            if (comparacao > 0) {
                throw new IllegalArgumentException("As devoluções (" + devolvido.toPlainString()
                        + ") passam da base original (" + baseOriginal.toPlainString() + ").");
            }
            BigDecimal estorno = comparacao == 0
                    ? valorOriginal.subtract(estornado).setScale(ESCALA)
                    : valorOriginal.multiply(base).divide(baseOriginal, ESCALA, ARREDONDAMENTO);
            estornos.add(estorno);
            estornado = estornado.add(estorno);
        }
        return List.copyOf(estornos);
    }

    /** Caso (a) do ADR-0002: a comissão de cada venda é arredondada, e a do acerto é a soma delas. */
    public static BigDecimal comissaoPorVenda(Collection<BigDecimal> liquidosDasVendas, BigDecimal porcentagem) {
        BigDecimal total = ZERO;
        for (BigDecimal liquido : liquidosDasVendas) {
            total = total.add(aplicarPorcentagem(liquido, porcentagem));
        }
        return total;
    }

    /** Caso (b) do ADR-0002: a porcentagem incide uma vez sobre a soma dos líquidos do acerto. */
    public static BigDecimal comissaoSobreTotal(Collection<BigDecimal> liquidosDasVendas, BigDecimal porcentagem) {
        return aplicarPorcentagem(somar(liquidosDasVendas), porcentagem);
    }

    private static BigDecimal jaArredondado(BigDecimal valor, String nome) {
        Objects.requireNonNull(valor, nome);
        if (valor.stripTrailingZeros().scale() > ESCALA) {
            throw new IllegalArgumentException("O " + nome + " " + valor.toPlainString()
                    + " tem mais de duas casas: arredonde no momento do cálculo (ADR-0002).");
        }
        return valor;
    }

    private static BigDecimal porcentagemValida(BigDecimal porcentagem) {
        Objects.requireNonNull(porcentagem, "porcentagem");
        if (porcentagem.stripTrailingZeros().scale() > ESCALA
                || porcentagem.signum() < 0
                || porcentagem.compareTo(CEM) > 0) {
            throw new IllegalArgumentException("A porcentagem precisa estar entre 0 e 100, com até duas casas; veio "
                    + porcentagem.toPlainString() + ".");
        }
        return porcentagem;
    }
}
