package br.com.coronasemijoias.api.comum.dinheiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A comissão por venda da tarefa 7.5 (ADR-0012, item 5; ADR-0002, item 2): cada venda arredondada uma vez, o estorno pelo
 * acumulado e nenhum método novo de arredondamento (só {@link Dinheiro}).
 */
class ComissaoPorVendaTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void trintaETresEmTrintaEHalfUpPorVenda() {
        assertThat(Dinheiro.aplicarPorcentagem(d("33.33"), d("30"))).isEqualByComparingTo("10.00");
    }

    @Test
    void tresVendasDeDezE05ATrintaDao9E06EATrintaESomaDaOutraConta() {
        BigDecimal cada = Dinheiro.aplicarPorcentagem(d("10.05"), d("30"));
        assertThat(cada).isEqualByComparingTo("3.02");
        assertThat(Dinheiro.comissaoPorVenda(List.of(d("10.05"), d("10.05"), d("10.05")), d("30"))).isEqualByComparingTo("9.06");
    }

    @Test
    void dezE05AQuarentaDao4E02() {
        assertThat(Dinheiro.aplicarPorcentagem(d("10.05"), d("40"))).isEqualByComparingTo("4.02");
    }

    @Test
    void estornoPeloAcumuladoNaDevolucaoDe20DaComissaoDe8() {
        // O exemplo do ADR-0012: a venda de 20,00 tinha comissão de 8,00 (40%); devolver os 20,00 estorna 8,00.
        assertThat(Dinheiro.estornosProporcionais(d("8.00"), d("20.00"), List.of(d("20.00")))).containsExactly(d("8.00"));
    }

    @Test
    void devolucoesPequenasRepetidasNuncaInvertemOSinalENuncaPassamDaComissao() {
        // Comissão de 0,04 sobre 0,10: cem devoluções de 0,001 não existem; dez de 0,01 somam exatamente 0,04.
        List<BigDecimal> bases = java.util.Collections.nCopies(10, d("0.01"));
        List<BigDecimal> estornos = Dinheiro.estornosProporcionais(d("0.04"), d("0.10"), bases);
        assertThat(estornos).allSatisfy(e -> assertThat(e.signum()).isGreaterThanOrEqualTo(0));
        assertThat(Dinheiro.somar(estornos)).isEqualByComparingTo("0.04");
        // Três de 3,35 sobre 10,05 a 30% (comissão 3,02): o acumulado fecha em 3,02, sem sobra nem falta.
        List<BigDecimal> tres = Dinheiro.estornosProporcionais(d("3.02"), d("10.05"), List.of(d("3.35"), d("3.35"), d("3.35")));
        assertThat(Dinheiro.somar(tres)).isEqualByComparingTo("3.02");
    }
}
