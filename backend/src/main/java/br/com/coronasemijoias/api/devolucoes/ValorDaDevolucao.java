package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Valor de uma devolução de {@code q} unidades de uma linha da venda (ADR-0014, item 3). Cálculo puro, sem banco.
 *
 * <p>A unidade número k da linha vale a parcela k de {@link Dinheiro#dividirEmParcelas} (o total da linha dividido pela
 * quantidade, com a sobra de centavos na última). O valor de uma devolução é o acumulado depois dela menos o que já foi
 * devolvido (só as devoluções não canceladas), sempre entre zero e o que falta; a que completa a linha é exatamente o
 * que falta, então a linha fecha no centavo mesmo com cancelamentos no meio. Quando {@code dividirEmParcelas} recusa
 * (linha com centavos de menos para tantas unidades, como 0,18 em 12), o acumulado é a parte proporcional arredondada
 * (dúvida Q2 do plano).
 */
public final class ValorDaDevolucao {

    private ValorDaDevolucao() {
    }

    /**
     * @param totalDaLinha     preço unitário x quantidade - desconto da linha da venda
     * @param vendidas         quantidade vendida na linha
     * @param jaDevolvidas     unidades já devolvidas (devoluções não canceladas)
     * @param valorJaDevolvido valor já devolvido (devoluções não canceladas)
     * @param quantidade       unidades desta devolução
     */
    public static BigDecimal calcular(BigDecimal totalDaLinha, int vendidas, int jaDevolvidas, BigDecimal valorJaDevolvido,
            int quantidade) {
        Objects.requireNonNull(totalDaLinha, "totalDaLinha");
        Objects.requireNonNull(valorJaDevolvido, "valorJaDevolvido");
        if (vendidas < 1 || quantidade < 1 || jaDevolvidas < 0 || (long) jaDevolvidas + quantidade > vendidas) {
            throw new IllegalArgumentException("Devolução de " + quantidade + " unidade(s) com " + jaDevolvidas
                    + " já devolvida(s) numa linha de " + vendidas + ".");
        }
        BigDecimal total = Dinheiro.somar(List.of(totalDaLinha));
        BigDecimal ja = Dinheiro.somar(List.of(valorJaDevolvido));
        BigDecimal falta = Dinheiro.subtrair(total, ja).max(BigDecimal.ZERO.setScale(Dinheiro.ESCALA));
        int depois = jaDevolvidas + quantidade;
        if (depois == vendidas) {
            return falta;
        }
        BigDecimal valor = Dinheiro.subtrair(acumulado(total, vendidas, depois), ja);
        return valor.max(BigDecimal.ZERO.setScale(Dinheiro.ESCALA)).min(falta);
    }

    /** Soma das {@code k} primeiras unidades da linha. */
    private static BigDecimal acumulado(BigDecimal total, int vendidas, int k) {
        try {
            return Dinheiro.somar(Dinheiro.dividirEmParcelas(total, vendidas).subList(0, k));
        } catch (IllegalArgumentException centavosDeMenos) {
            return Dinheiro.parteProporcional(total, k, vendidas);
        }
    }
}
