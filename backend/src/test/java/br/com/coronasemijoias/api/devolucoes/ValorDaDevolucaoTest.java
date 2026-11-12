package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Valor de uma devolução (tarefa 6.3; ADR-0014, item 3): a unidade número k de uma linha vale sempre o mesmo, devolvida
 * de uma vez ou aos poucos; a soma de todas as unidades fecha no total da linha, mesmo com cancelamentos no meio; nenhum
 * valor fica negativo. Cálculo puro, sem banco. Escrito antes do código.
 */
class ValorDaDevolucaoTest {

    private static BigDecimal v(String texto) {
        return new BigDecimal(texto);
    }

    private static BigDecimal valor(String total, int vendidas, int jaDevolvidas, String valorJaDevolvido, int quantidade) {
        return ValorDaDevolucao.calcular(v(total), vendidas, jaDevolvidas, v(valorJaDevolvido), quantidade);
    }

    @Test
    void tresDevolucoesDeUmaUnidadeFechamNoTotalComASobraNaUltima() {
        assertThat(valor("10.00", 3, 0, "0.00", 1)).isEqualByComparingTo("3.33");
        assertThat(valor("10.00", 3, 1, "3.33", 1)).isEqualByComparingTo("3.33");
        assertThat(valor("10.00", 3, 2, "6.66", 1)).isEqualByComparingTo("3.34");
        // De uma vez, a mesma conta.
        assertThat(valor("10.00", 3, 0, "0.00", 2)).isEqualByComparingTo("6.66");
        assertThat(valor("10.00", 3, 0, "0.00", 3)).isEqualByComparingTo("10.00");
        assertThat(valor("10.00", 3, 0, "0.00", 1).scale()).isEqualTo(2);
    }

    @Test
    void comCancelamentoNoMeioASomaAindaFechaNoTotalDaLinha() {
        // Linha de 3 com desconto que não divide exato: 4,00 x 3 - 1,99 = 10,01 (parcelas 3,34 + 3,34 + 3,33).
        BigDecimal primeira = valor("10.01", 3, 0, "0.00", 1);
        BigDecimal segunda = valor("10.01", 3, 1, primeira.toPlainString(), 1);
        assertThat(primeira).isEqualByComparingTo("3.34");
        assertThat(segunda).isEqualByComparingTo("3.34");
        // A primeira é cancelada: fica só a segunda (1 unidade, 3,34). Depois, uma parcial e a última.
        BigDecimal terceira = valor("10.01", 3, 1, segunda.toPlainString(), 1);
        BigDecimal quarta = valor("10.01", 3, 2, segunda.add(terceira).toPlainString(), 1);
        assertThat(segunda.add(terceira).add(quarta)).isEqualByComparingTo("10.01");
        assertThat(quarta).isEqualByComparingTo("3.33");
    }

    @Test
    void linhaComCentavosDemaisParaAsUnidadesNaoDaErroNemValorNegativo() {
        // 12 peças por 0,18: dividirEmParcelas recusa (a última ficaria negativa). Vale o acumulado arredondado (dúvida Q2).
        assertThat(valor("0.18", 12, 0, "0.00", 5)).isEqualByComparingTo("0.08");
        assertThat(valor("0.18", 12, 5, "0.08", 7)).isEqualByComparingTo("0.10");
        BigDecimal soma = BigDecimal.ZERO;
        for (int k = 0; k < 12; k++) {
            BigDecimal uma = valor("0.18", 12, k, soma.toPlainString(), 1);
            assertThat(uma.signum()).isGreaterThanOrEqualTo(0);
            soma = soma.add(uma);
        }
        assertThat(soma).isEqualByComparingTo("0.18");
    }

    @Test
    void linhaDeValorZeroDevolveZero() {
        assertThat(valor("0.00", 2, 0, "0.00", 1)).isEqualByComparingTo("0.00");
        assertThat(valor("0.00", 2, 1, "0.00", 1)).isEqualByComparingTo("0.00");
    }

    @Test
    void qualquerSequenciaDeDevolucoesFechaNoTotalSemPassarDele() {
        Random r = new Random(63);
        for (int rodada = 0; rodada < 500; rodada++) {
            int n = 1 + r.nextInt(15);
            BigDecimal total = BigDecimal.valueOf(r.nextInt(100_000), 2);
            int ja = 0;
            BigDecimal valorJa = BigDecimal.ZERO.setScale(2);
            List<BigDecimal> parciais = new ArrayList<>();
            while (ja < n) {
                int q = 1 + r.nextInt(n - ja);
                BigDecimal parcial = ValorDaDevolucao.calcular(total, n, ja, valorJa, q);
                assertThat(parcial.signum()).as("rodada %d", rodada).isGreaterThanOrEqualTo(0);
                ja += q;
                valorJa = valorJa.add(parcial);
                assertThat(valorJa).as("rodada %d", rodada).isLessThanOrEqualTo(total);
                parciais.add(parcial);
            }
            assertThat(valorJa).as("rodada %d: %s", rodada, parciais).isEqualByComparingTo(total);
        }
    }

    @Test
    void recusaQuantidadeAcimaDoVendidoOuZero() {
        assertThatThrownBy(() -> valor("10.00", 3, 2, "6.66", 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valor("10.00", 3, 0, "0.00", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valor("10.00", 0, 0, "0.00", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valor("10.001", 3, 0, "0.00", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
