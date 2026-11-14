package br.com.coronasemijoias.api.comum.dinheiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Conversão de Real para Dólar e Guarani (ADR-0006, itens 6, 9 e 10). Cada teste é uma linha da tabela
 * de exemplos do item 14 do ADR, com as taxas de venda reais publicadas pelo BCB. O TypeScript terá os
 * mesmos exemplos quando o primeiro front-end mostrar valores.
 */
class ConversaoDeMoedaTest {

    // Taxas de venda do ADR-0006, item 14.
    private static final BigDecimal USD_30_10 = d("5.42120000");
    private static final BigDecimal PYG_30_10 = d("0.00092960");
    private static final BigDecimal USD_05_11 = d("5.46210000");
    private static final BigDecimal PYG_05_11 = d("0.00088770");
    private static final BigDecimal USD_06_11 = d("5.50130000");
    private static final BigDecimal PYG_06_11 = d("0.00089500");

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private static void confere(String reais, BigDecimal usd, BigDecimal pyg, String dolar, String guarani) {
        assertThat(ConversaoDeMoeda.paraDolar(d(reais), usd)).isEqualTo(d(dolar));
        assertThat(ConversaoDeMoeda.paraGuarani(d(reais), pyg)).isEqualTo(d(guarani));
    }

    @Test
    void milDuzentosETrintaEQuatroEMeioEmSeisDeNovembro() {
        // 224,4124... e 1.379.396,648...: o Guarani arredonda para cima.
        confere("1234.56", USD_06_11, PYG_06_11, "224.41", "1379397");
    }

    @Test
    void umRealEmSeisDeNovembro() {
        // 0,18177... e 1.117,318...: o Guarani arredonda para baixo.
        confere("1.00", USD_06_11, PYG_06_11, "0.18", "1117");
    }

    @Test
    void umCentavoPodeMostrarZeroDolar() {
        confere("0.01", USD_06_11, PYG_06_11, "0.00", "11");
    }

    @Test
    void relatorioDeDoisDiasSomaOsConvertidosDeCadaDia() {
        BigDecimal dolar5 = ConversaoDeMoeda.paraDolar(d("49.90"), USD_05_11);
        BigDecimal dolar6 = ConversaoDeMoeda.paraDolar(d("100.00"), USD_06_11);
        BigDecimal guarani5 = ConversaoDeMoeda.paraGuarani(d("49.90"), PYG_05_11);
        BigDecimal guarani6 = ConversaoDeMoeda.paraGuarani(d("100.00"), PYG_06_11);

        assertThat(dolar5).isEqualTo(d("9.14"));
        assertThat(dolar6).isEqualTo(d("18.18"));
        assertThat(dolar5.add(dolar6)).isEqualTo(d("27.32"));
        assertThat(guarani5).isEqualTo(d("56213"));
        assertThat(guarani6).isEqualTo(d("111732"));
        assertThat(guarani5.add(guarani6)).isEqualTo(d("167945"));
    }

    @Test
    void converterTudoPelaCotacaoDeHojeEErradoEDiferePorSeteCentavos() {
        confere("149.90", USD_06_11, PYG_06_11, "27.25", "167486");
    }

    @Test
    void tresVendasDe10e05SomamOsConvertidosEntregamCincoE49() {
        BigDecimal dolar = ConversaoDeMoeda.paraDolar(d("10.05"), USD_06_11);
        BigDecimal guarani = ConversaoDeMoeda.paraGuarani(d("10.05"), PYG_06_11);
        assertThat(dolar).isEqualTo(d("1.83"));
        assertThat(guarani).isEqualTo(d("11229"));
        assertThat(dolar.add(dolar).add(dolar)).isEqualTo(d("5.49"));
        assertThat(guarani.add(guarani).add(guarani)).isEqualTo(d("33687"));
    }

    @Test
    void converterOTotalNaoERegraEDaUmCentavoAMenosEmDolar() {
        confere("30.15", USD_06_11, PYG_06_11, "5.48", "33687");
    }

    @Test
    void vendaDeCemReaisNoSabadoUsaACotacaoDeSexta() {
        confere("100.00", USD_06_11, PYG_06_11, "18.18", "111732");
    }

    @Test
    void vendaDeCemReaisEmFinadosUsaACotacaoDeTrintaDeOutubro() {
        confere("100.00", USD_30_10, PYG_30_10, "18.45", "107573");
    }

    @Test
    void acertoConverteCadaValorSozinhoPelaCotacaoDoFechamento() {
        confere("149.90", USD_06_11, PYG_06_11, "27.25", "167486");
        confere("11.24", USD_06_11, PYG_06_11, "2.04", "12559");
        confere("138.66", USD_06_11, PYG_06_11, "25.20", "154927");
        // As linhas do acerto em US$ não fecham por 1 centavo; a conta que fecha é a em reais.
        assertThat(d("27.25").subtract(d("2.04"))).isEqualTo(d("25.21"));
        assertThat(d("149.90").subtract(d("11.24"))).isEqualTo(d("138.66"));
    }

    @Test
    void valorMaximoDoBancoNaoPerdeDigitos() {
        // R$ 999.999.999.999,99 é o maior NUMERIC(14,2). Multiplicado por 10^8 passaria de 2^53 - 1.
        confere("999999999999.99", USD_06_11, PYG_06_11, "181775216766.94", "1117318435754179");
    }

    @Test
    void estornoNegativoArredondaAfastandoDoZero() {
        confere("-1234.56", USD_06_11, PYG_06_11, "-224.41", "-1379397");
    }

    @Test
    void umaDivisaoSoComArredondamentoNoFim() {
        // 0,01 ÷ 2 = 0,005, que vira 0,01 (HALF_UP); 0,01 ÷ 0,02 = 0,5, que vira 1.
        assertThat(ConversaoDeMoeda.paraDolar(d("0.01"), d("2.00000000"))).isEqualTo(d("0.01"));
        assertThat(ConversaoDeMoeda.paraGuarani(d("0.01"), d("0.02000000"))).isEqualTo(d("1"));
    }

    @Test
    void zeroContinuaZero() {
        assertThat(ConversaoDeMoeda.paraDolar(d("0.00"), USD_06_11)).isEqualTo(d("0.00"));
        assertThat(ConversaoDeMoeda.paraGuarani(d("0.00"), PYG_06_11)).isEqualTo(d("0"));
    }

    @Test
    void recusaTaxaZeroOuNegativa() {
        assertThatThrownBy(() -> ConversaoDeMoeda.paraDolar(d("1.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversaoDeMoeda.paraGuarani(d("1.00"), d("-0.0009")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recusaValorEmRealComMaisDeDuasCasas() {
        assertThatThrownBy(() -> ConversaoDeMoeda.paraDolar(d("1.005"), USD_06_11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duas casas");
    }

    @Test
    void recusaNulos() {
        assertThatThrownBy(() -> ConversaoDeMoeda.paraDolar(null, USD_06_11)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConversaoDeMoeda.paraGuarani(d("1.00"), null)).isInstanceOf(NullPointerException.class);
    }

    // --- o caminho inverso, só para a prévia da comissão (tarefa 7.5): quanto falta, em Real ---

    @Test
    void guaraniParaReaisMultiplicaPelaTaxaEArredondaEmDuasCasas() {
        // O exemplo do ADR-0012 (item 18): faltam 557.411 Gs; 1 Gs = R$ 0,00090000.
        assertThat(ConversaoDeMoeda.guaraniParaReais(d("557411"), d("0.00090000"))).isEqualTo(d("501.67"));
        // 1.117 Gs em 6 de novembro: 0,999715 vira 1,00.
        assertThat(ConversaoDeMoeda.guaraniParaReais(d("1117"), PYG_06_11)).isEqualTo(d("1.00"));
        assertThat(ConversaoDeMoeda.guaraniParaReais(d("0"), PYG_06_11)).isEqualTo(d("0.00"));
    }

    @Test
    void guaraniParaReaisArredondaMeiaParaCima() {
        // 5 Gs x 0,001 = 0,005, que vira 0,01.
        assertThat(ConversaoDeMoeda.guaraniParaReais(d("5"), d("0.00100000"))).isEqualTo(d("0.01"));
        assertThat(ConversaoDeMoeda.guaraniParaReais(d("4"), d("0.00100000"))).isEqualTo(d("0.00"));
    }

    @Test
    void guaraniParaReaisRecusaTaxaInvalidaGuaraniComCentavosENulos() {
        assertThatThrownBy(() -> ConversaoDeMoeda.guaraniParaReais(d("100"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversaoDeMoeda.guaraniParaReais(d("100.5"), d("0.0009"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversaoDeMoeda.guaraniParaReais(null, d("0.0009"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConversaoDeMoeda.guaraniParaReais(d("100"), null)).isInstanceOf(NullPointerException.class);
    }
}
