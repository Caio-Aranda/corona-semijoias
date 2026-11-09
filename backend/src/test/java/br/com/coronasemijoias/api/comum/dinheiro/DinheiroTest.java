package br.com.coronasemijoias.api.comum.dinheiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regras de dinheiro do ADR-0002 (item 2): HALF_UP com escala 2, cada valor arredondado no
 * instante em que é calculado e totais como soma dos valores já arredondados.
 *
 * <p>Os exemplos numéricos da tabela do ADR estão em {@link ExemplosDoAdr}, um por linha.
 */
class DinheiroTest {

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private static List<BigDecimal> ds(String... valores) {
        return java.util.Arrays.stream(valores).map(BigDecimal::new).toList();
    }

    @Nested
    class ExemplosDoAdr {

        @Test
        void descontoDe10PorCentoPorLinhaEmTresLinhasDe10e05() {
            List<BigDecimal> linhas = ds("10.05", "10.05", "10.05");
            List<BigDecimal> descontos = linhas.stream().map(l -> Dinheiro.aplicarPorcentagem(l, d("10.00"))).toList();

            assertThat(descontos).containsExactly(d("1.01"), d("1.01"), d("1.01"));
            BigDecimal desconto = Dinheiro.somar(descontos);
            assertThat(desconto).isEqualTo(d("3.03"));
            assertThat(Dinheiro.subtrair(Dinheiro.somar(linhas), desconto)).isEqualTo(d("27.12"));
        }

        @Test
        void arredondarSoOTotalDaUmCentavoDeDiferencaENaoERegra() {
            assertThat(Dinheiro.aplicarPorcentagem(d("30.15"), d("10.00"))).isEqualTo(d("3.02"));
        }

        @Test
        void comissaoDe7e5PorCentoSobre33e33() {
            assertThat(Dinheiro.aplicarPorcentagem(d("33.33"), d("7.50"))).isEqualTo(d("2.50"));
        }

        @Test
        void parcelasDe100Em3Vezes() {
            assertThat(Dinheiro.dividirEmParcelas(d("100.00"), 3)).containsExactly(d("33.33"), d("33.33"), d("33.34"));
        }

        @Test
        void comissaoPorVendaEmTresVendasDe10e05() {
            assertThat(Dinheiro.comissaoPorVenda(ds("10.05", "10.05", "10.05"), d("7.50"))).isEqualTo(d("2.25"));
        }

        @Test
        void comissaoSobreOTotalDoAcertoEmTresVendasDe10e05() {
            assertThat(Dinheiro.comissaoSobreTotal(ds("10.05", "10.05", "10.05"), d("7.50"))).isEqualTo(d("2.26"));
        }

        @Test
        void estornoProporcionalDaComissaoEmDuasDevolucoes() {
            List<BigDecimal> estornos = Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("33.33", "66.67"));

            assertThat(estornos).containsExactly(d("2.50"), d("5.00"));
            assertThat(Dinheiro.somar(estornos)).isEqualTo(d("7.50"));
        }
    }

    @Nested
    class Arredondar {

        @Test
        void usaHalfUpComEscala2() {
            assertThat(Dinheiro.arredondar(d("2.49975"))).isEqualTo(d("2.50"));
            assertThat(Dinheiro.arredondar(d("1.005"))).isEqualTo(d("1.01"));
            assertThat(Dinheiro.arredondar(d("1.0049"))).isEqualTo(d("1.00"));
        }

        @Test
        void negativoArredondaParaLongeDoZero() {
            assertThat(Dinheiro.arredondar(d("-1.005"))).isEqualTo(d("-1.01"));
            assertThat(Dinheiro.arredondar(d("-1.0049"))).isEqualTo(d("-1.00"));
        }

        @Test
        void completaParaDuasCasas() {
            assertThat(Dinheiro.arredondar(d("0"))).isEqualTo(d("0.00"));
            assertThat(Dinheiro.arredondar(d("7"))).isEqualTo(d("7.00"));
        }

        @Test
        void recusaNulo() {
            assertThatThrownBy(() -> Dinheiro.arredondar(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class SomarESubtrair {

        @Test
        void somaValoresJaArredondados() {
            assertThat(Dinheiro.somar(ds("1.01", "1.01", "1.01"))).isEqualTo(d("3.03"));
            assertThat(Dinheiro.somar(ds("10.00", "-2.50"))).isEqualTo(d("7.50"));
        }

        @Test
        void somaVaziaEZero() {
            assertThat(Dinheiro.somar(List.of())).isEqualTo(d("0.00"));
        }

        @Test
        void recusaValorSemArredondar() {
            assertThatThrownBy(() -> Dinheiro.somar(ds("1.00", "1.005")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1.005");
        }

        @Test
        void subtrai() {
            assertThat(Dinheiro.subtrair(d("30.15"), d("3.03"))).isEqualTo(d("27.12"));
            assertThat(Dinheiro.subtrair(d("0.00"), d("2.50"))).isEqualTo(d("-2.50"));
            assertThatThrownBy(() -> Dinheiro.subtrair(d("1.00"), d("0.001")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class PrecoDaLinha {

        @Test
        void multiplicaSemArredondar() {
            assertThat(Dinheiro.precoDaLinha(d("10.05"), 3)).isEqualTo(d("30.15"));
            assertThat(Dinheiro.precoDaLinha(d("10.05"), 0)).isEqualTo(d("0.00"));
            assertThat(Dinheiro.precoDaLinha(d("7"), 2)).isEqualTo(d("14.00"));
        }

        @Test
        void recusaPrecoComMaisDeDuasCasas() {
            assertThatThrownBy(() -> Dinheiro.precoDaLinha(d("10.055"), 3)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class AplicarPorcentagem {

        @Test
        void porcentagemComDuasCasas() {
            assertThat(Dinheiro.aplicarPorcentagem(d("100.00"), d("7.25"))).isEqualTo(d("7.25"));
            // 10,05 x 7,25% = 0,728625
            assertThat(Dinheiro.aplicarPorcentagem(d("10.05"), d("7.25"))).isEqualTo(d("0.73"));
        }

        @Test
        void zeroEcemPorCento() {
            assertThat(Dinheiro.aplicarPorcentagem(d("33.33"), d("0"))).isEqualTo(d("0.00"));
            assertThat(Dinheiro.aplicarPorcentagem(d("33.33"), d("100.00"))).isEqualTo(d("33.33"));
            assertThat(Dinheiro.aplicarPorcentagem(d("0.00"), d("7.50"))).isEqualTo(d("0.00"));
        }

        @Test
        void valorNegativoDeReembolso() {
            assertThat(Dinheiro.aplicarPorcentagem(d("-33.33"), d("7.50"))).isEqualTo(d("-2.50"));
        }

        @Test
        void recusaPorcentagemForaDaFaixaOuComTresCasas() {
            assertThatThrownBy(() -> Dinheiro.aplicarPorcentagem(d("10.00"), d("100.01")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.aplicarPorcentagem(d("10.00"), d("-0.01")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.aplicarPorcentagem(d("10.00"), d("7.125")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void recusaValorSemArredondar() {
            assertThatThrownBy(() -> Dinheiro.aplicarPorcentagem(d("10.005"), d("10.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class DividirEmParcelas {

        @Test
        void umaParcelaSo() {
            assertThat(Dinheiro.dividirEmParcelas(d("100.00"), 1)).containsExactly(d("100.00"));
        }

        @Test
        void valorQueNaoDivideFicaComOResto() {
            // 10,00 / 7 = 1,428571... vira 1,43; 6 x 1,43 = 8,58; a última é 1,42.
            assertThat(Dinheiro.dividirEmParcelas(d("10.00"), 7))
                    .containsExactly(d("1.43"), d("1.43"), d("1.43"), d("1.43"), d("1.43"), d("1.43"), d("1.42"));
            assertThat(Dinheiro.dividirEmParcelas(d("0.01"), 3)).containsExactly(d("0.00"), d("0.00"), d("0.01"));
            assertThat(Dinheiro.dividirEmParcelas(d("0.02"), 3)).containsExactly(d("0.01"), d("0.01"), d("0.00"));
        }

        @Test
        void aSomaDasParcelasFechaSempreComOTotal() {
            for (String total : List.of("100.00", "10.00", "0.01", "999.99", "1234.56", "33.33")) {
                for (int n = 1; n <= 12; n++) {
                    List<BigDecimal> parcelas;
                    try {
                        parcelas = Dinheiro.dividirEmParcelas(d(total), n);
                    } catch (IllegalArgumentException valorPequenoDemais) {
                        continue;
                    }
                    assertThat(parcelas).hasSize(n);
                    assertThat(Dinheiro.somar(parcelas)).as("%s em %d parcelas", total, n).isEqualTo(d(total));
                }
            }
        }

        @Test
        void zero() {
            assertThat(Dinheiro.dividirEmParcelas(d("0.00"), 3)).containsExactly(d("0.00"), d("0.00"), d("0.00"));
        }

        @Test
        void negativoDeReembolso() {
            assertThat(Dinheiro.dividirEmParcelas(d("-100.00"), 3)).containsExactly(d("-33.33"), d("-33.33"), d("-33.34"));
        }

        @Test
        void recusaQuandoARegraDaUltimaParcelaInverteriaOSinal() {
            // 0,18 / 12 = 0,015 vira 0,02; 11 x 0,02 = 0,22 passaria do total e a última seria -0,04.
            assertThatThrownBy(() -> Dinheiro.dividirEmParcelas(d("0.18"), 12))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void recusaNumeroDeParcelasInvalidoOuValorSemArredondar() {
            assertThatThrownBy(() -> Dinheiro.dividirEmParcelas(d("100.00"), 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.dividirEmParcelas(d("100.00"), -1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.dividirEmParcelas(d("100.001"), 3)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class EstornoProporcional {

        @Test
        void devolucaoTotalDeUmaVezEstornaTudo() {
            assertThat(Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("100.00"))).containsExactly(d("7.50"));
        }

        @Test
        void devolucaoParcialSemFecharABaseSoArredonda() {
            assertThat(Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("33.33"))).containsExactly(d("2.50"));
            assertThat(Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("10.00", "10.00")))
                    .containsExactly(d("0.75"), d("0.75"));
        }

        @Test
        void oUltimoQueFechaABaseLevaOResto() {
            // 1,00 / 3 = 0,333... vira 0,33 nas duas primeiras; a terceira fecha a base e leva 0,34.
            assertThat(Dinheiro.estornosProporcionais(d("1.00"), d("3.00"), ds("1.00", "1.00", "1.00")))
                    .containsExactly(d("0.33"), d("0.33"), d("0.34"));
            assertThat(Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("33.33", "33.33", "33.34")))
                    .containsExactly(d("2.50"), d("2.50"), d("2.50"));
        }

        @Test
        void valorNegativo() {
            assertThat(Dinheiro.estornosProporcionais(d("-7.50"), d("100.00"), ds("33.33", "66.67")))
                    .containsExactly(d("-2.50"), d("-5.00"));
        }

        @Test
        void recusaDevolucaoMaiorQueABaseOuBaseInvalida() {
            assertThatThrownBy(() -> Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("60.00", "40.01")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.estornosProporcionais(d("7.50"), d("0.00"), ds("1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("0.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.estornosProporcionais(d("7.50"), d("100.00"), ds("10.005")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Comissao {

        @Test
        void umaVendaSoDaOMesmoNosDoisCasos() {
            assertThat(Dinheiro.comissaoPorVenda(ds("33.33"), d("7.50"))).isEqualTo(d("2.50"));
            assertThat(Dinheiro.comissaoSobreTotal(ds("33.33"), d("7.50"))).isEqualTo(d("2.50"));
        }

        @Test
        void semVendasEZero() {
            assertThat(Dinheiro.comissaoPorVenda(List.of(), d("7.50"))).isEqualTo(d("0.00"));
            assertThat(Dinheiro.comissaoSobreTotal(List.of(), d("7.50"))).isEqualTo(d("0.00"));
        }

        @Test
        void liquidoNegativoEntraNaConta() {
            assertThat(Dinheiro.comissaoPorVenda(ds("100.00", "-20.00"), d("7.50"))).isEqualTo(d("6.00"));
            assertThat(Dinheiro.comissaoSobreTotal(ds("100.00", "-20.00"), d("7.50"))).isEqualTo(d("6.00"));
        }
    }
}
