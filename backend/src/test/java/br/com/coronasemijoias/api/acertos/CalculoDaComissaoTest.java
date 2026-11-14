package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Estorno;
import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Linha;
import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Original;
import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Resultado;
import br.com.coronasemijoias.api.acertos.CalculoDaComissao.TipoDaLinha;
import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Venda;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A comissão por meta (tarefa 7.5; ADR-0012, item 5; exemplo do item 18), sem banco: a soma em Guarani de cada venda pela
 * cotação dela, o percentual do acerto, a comissão por venda e o estorno proporcional pelo percentual da linha original. A
 * venda entra pelo TOTAL, que já é o valor depois do desconto (subtotal menos desconto).
 */
class CalculoDaComissaoTest {

    private static final BigDecimal TRINTA = BigDecimal.valueOf(30);
    private static final BigDecimal QUARENTA = BigDecimal.valueOf(40);
    private static final BigDecimal DA_SEMANA = d("0.00090000");

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Venda venda(String total, BigDecimal taxa) {
        return new Venda(UUID.randomUUID(), UUID.randomUUID(), d(total), taxa);
    }

    /** Venda com desconto: o total é o subtotal menos o desconto (a soma usa o total depois do desconto). */
    private static Venda vendaComDesconto(String subtotal, String desconto, BigDecimal taxa) {
        return venda(d(subtotal).subtract(d(desconto)).toPlainString(), taxa);
    }

    private static Resultado calcular(List<Venda> vendas, List<Estorno> estornos, Map<UUID, Original> fechados) {
        return CalculoDaComissao.calcular(700_000, TRINTA, QUARENTA, vendas, estornos, fechados);
    }

    private static Resultado calcular(List<Venda> vendas) {
        return calcular(vendas, List.of(), Map.of());
    }

    private static List<Linha> deTipo(Resultado r, TipoDaLinha tipo) {
        return r.linhas().stream().filter(l -> l.tipo() == tipo).toList();
    }

    // --- o exemplo do ADR-0012, item 18 ---

    @Test
    void oExemploDoAdrComVendaComDescontoEstornoEDevolucaoDeAcertoFechado() {
        Venda v1 = vendaComDesconto("100.00", "5.00", DA_SEMANA); // total 95,00
        Venda v2 = venda("33.33", DA_SEMANA);
        Venda v3 = venda("50.00", DA_SEMANA);
        UUID vendaDeAcertoFechado = UUID.randomUUID();
        Resultado r = calcular(List.of(v1, v2, v3),
                List.of(new Estorno(UUID.randomUUID(), v3.id(), d("50.00")),
                        new Estorno(UUID.randomUUID(), vendaDeAcertoFechado, d("20.00"))),
                Map.of(vendaDeAcertoFechado, new Original(d("20.00"), d("8.00"), QUARENTA, List.of())));

        // 105.556 + 37.033 + 55.556 - 55.556: a devolução da venda de acerto fechado não entra na soma.
        assertThat(r.vendidoParaMetaGuarani()).isEqualTo(142_589L);
        assertThat(r.naMeta()).isFalse();
        assertThat(r.percentual()).isEqualByComparingTo("30");
        List<Linha> comissoes = deTipo(r, TipoDaLinha.COMISSAO);
        assertThat(comissoes).extracting(Linha::valor).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("28.50"), d("10.00"), d("15.00"));
        assertThat(comissoes).extracting(Linha::base).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("95.00"), d("33.33"), d("50.00"));
        assertThat(comissoes).allSatisfy(l -> assertThat(l.percentual()).isEqualByComparingTo("30"));
        List<Linha> estornos = deTipo(r, TipoDaLinha.ESTORNO);
        assertThat(estornos).extracting(Linha::valor).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("-15.00"), d("-8.00"));
        // O estorno da venda do mesmo acerto usa o percentual do acerto; o da venda de acerto fechado, o da linha original (40%).
        assertThat(estornos.get(0).percentual()).isEqualByComparingTo("30");
        assertThat(estornos.get(1).percentual()).isEqualByComparingTo("40");
        assertThat(estornos.get(1).vendaId()).isEqualTo(vendaDeAcertoFechado);
        assertThat(r.comissao()).isEqualByComparingTo("30.50");
    }

    @Test
    void aVendaComDescontoEntraNaSomaPeloTotalDepoisDoDesconto() {
        // Subtotal 100,00 e desconto 5,00: a soma usa 95,00 (105.556 Gs), não 100,00 (111.111 Gs).
        Resultado r = calcular(List.of(vendaComDesconto("100.00", "5.00", DA_SEMANA)));
        assertThat(r.vendidoParaMetaGuarani()).isEqualTo(105_556L);
        assertThat(r.linhas().get(0).base()).isEqualByComparingTo("95.00");
        assertThat(r.linhas().get(0).valor()).isEqualByComparingTo("28.50");
    }

    @Test
    void odescontoPodeLevarAVendaParaBaixoDaMeta() {
        // 640,00 com 10,00 de desconto: o total 630,00 bate a meta; com desconto de 11,00 (629,00), não bate.
        assertThat(calcular(List.of(vendaComDesconto("640.00", "10.00", DA_SEMANA))).naMeta()).isTrue();
        assertThat(calcular(List.of(vendaComDesconto("640.00", "11.00", DA_SEMANA))).naMeta()).isFalse();
    }

    // --- a meta ---

    @Test
    void exatamenteNaMetaPagaQuarentaSobreTodasAsVendas() {
        Venda a = venda("630.00", DA_SEMANA); // 630 / 0,0009 = 700.000 exatos
        Resultado r = calcular(List.of(a));
        assertThat(r.vendidoParaMetaGuarani()).isEqualTo(700_000L);
        assertThat(r.naMeta()).isTrue();
        assertThat(r.percentual()).isEqualByComparingTo("40");
        assertThat(r.linhas().get(0).valor()).isEqualByComparingTo("252.00");
    }

    @Test
    void umGuaraniAbaixoDaMetaPagaTrinta() {
        // 699.999 Gs: a taxa faz R$ 630,00 valerem 699.999 Gs.
        BigDecimal taxa = d("630.00").divide(d("699999"), 14, RoundingMode.HALF_UP);
        Resultado r = calcular(List.of(venda("630.00", taxa)));
        assertThat(r.vendidoParaMetaGuarani()).isEqualTo(699_999L);
        assertThat(r.naMeta()).isFalse();
        assertThat(r.percentual()).isEqualByComparingTo("30");
    }

    @Test
    void oPercentualVaiSobreTodasAsVendasDoAcertoNaoSoSobreOQuePassaDaMeta() {
        Venda a = venda("630.00", DA_SEMANA);
        Venda b = venda("100.00", DA_SEMANA);
        Resultado r = calcular(List.of(a, b));
        assertThat(r.percentual()).isEqualByComparingTo("40");
        assertThat(r.linhas()).extracting(Linha::percentual).allSatisfy(p -> assertThat(p).isEqualByComparingTo("40"));
        assertThat(r.comissao()).isEqualByComparingTo("292.00");
    }

    @Test
    void cadaVendaPelaCotacaoDelaEArredondadaSozinhaASomaNaoEAConversaoDoTotal() {
        Venda a = venda("100.00", d("0.00090000")); // 111.111,11 -> 111.111
        Venda b = venda("100.00", d("0.00100000")); // 100.000
        Venda c = venda("33.33", d("0.00090000")); // 37.033,33 -> 37.033
        assertThat(calcular(List.of(a, b, c)).vendidoParaMetaGuarani()).isEqualTo(111_111L + 100_000L + 37_033L);
        // Duas vendas de R$ 0,01 a 0,02 cada (0,5 Gs): cada uma arredonda sozinha para 1, e a soma é 2; a conversão do total
        // (R$ 0,02) daria 1.
        Venda x = venda("0.01", d("0.02000000"));
        Venda y = venda("0.01", d("0.02000000"));
        assertThat(calcular(List.of(x, y)).vendidoParaMetaGuarani()).isEqualTo(2L);
    }

    @Test
    void vendaEstornadaNoMesmoAcertoSaiDaSomaConvertidaPelaCotacaoDaVenda() {
        Venda a = venda("50.00", DA_SEMANA);
        Venda b = venda("95.00", DA_SEMANA);
        Resultado r = calcular(List.of(a, b), List.of(new Estorno(UUID.randomUUID(), a.id(), d("50.00"))), Map.of());
        assertThat(r.vendidoParaMetaGuarani()).isEqualTo(105_556L);
        // Venda e estorno no mesmo acerto dão comissão líquida zero, com as duas linhas visíveis.
        List<Linha> daA = r.linhas().stream().filter(l -> l.vendaId().equals(a.id())).toList();
        assertThat(daA).hasSize(2);
        assertThat(daA.get(0).valor().add(daA.get(1).valor())).isEqualByComparingTo("0.00");
    }

    @Test
    void devolucaoParcialDaVendaDoMesmoAcertoSaiDaSomaSoNaParteDevolvida() {
        Venda a = venda("100.00", DA_SEMANA);
        Resultado r = calcular(List.of(a), List.of(new Estorno(UUID.randomUUID(), a.id(), d("30.00"))), Map.of());
        assertThat(r.vendidoParaMetaGuarani()).isEqualTo(111_111L - 33_333L);
        assertThat(deTipo(r, TipoDaLinha.ESTORNO).get(0).valor()).isEqualByComparingTo("-9.00");
    }

    @Test
    void devolucaoDeVendaDeAcertoFechadoNaoEntraNaSomaEEstornaPeloPercentualOriginalMesmoAbaixoDaMeta() {
        UUID antiga = UUID.randomUUID();
        // A comissão original foi de 40% (acerto antigo na meta); este acerto está em 30%.
        Resultado r = calcular(List.of(venda("100.00", DA_SEMANA)),
                List.of(new Estorno(UUID.randomUUID(), antiga, d("50.00"))),
                Map.of(antiga, new Original(d("200.00"), d("80.00"), QUARENTA, List.of())));
        assertThat(r.percentual()).isEqualByComparingTo("30");
        assertThat(r.vendidoParaMetaGuarani()).isEqualTo(111_111L);
        Linha estorno = deTipo(r, TipoDaLinha.ESTORNO).get(0);
        assertThat(estorno.valor()).isEqualByComparingTo("-20.00");
        assertThat(estorno.percentual()).isEqualByComparingTo("40");
        assertThat(estorno.base()).isEqualByComparingTo("50.00");
    }

    // --- o estorno pelo acumulado ---

    @Test
    void devolucoesRepetidasDaMesmaVendaSomamExatamenteAComissaoSemInverterOSinal() {
        Venda a = venda("10.05", DA_SEMANA);
        List<Estorno> tres = List.of(new Estorno(UUID.randomUUID(), a.id(), d("3.35")),
                new Estorno(UUID.randomUUID(), a.id(), d("3.35")), new Estorno(UUID.randomUUID(), a.id(), d("3.35")));
        Resultado r = calcular(List.of(a), tres, Map.of());
        List<Linha> estornos = deTipo(r, TipoDaLinha.ESTORNO);
        assertThat(estornos).hasSize(3).allSatisfy(l -> assertThat(l.valor().signum()).isNegative());
        assertThat(estornos.stream().map(Linha::valor).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-3.02");
        assertThat(r.comissao()).isEqualByComparingTo("0.00");
    }

    @Test
    void oEstornoContinuaDeOndeOsAcertosAnterioresPararam() {
        UUID antiga = UUID.randomUUID();
        // Comissão 3,02 sobre 10,05; uma devolução de 3,35 já feita antes; agora as outras duas.
        Resultado r = calcular(List.of(venda("1.00", DA_SEMANA)),
                List.of(new Estorno(UUID.randomUUID(), antiga, d("3.35")), new Estorno(UUID.randomUUID(), antiga, d("3.35"))),
                Map.of(antiga, new Original(d("10.05"), d("3.02"), TRINTA, List.of(d("3.35")))));
        List<Linha> estornos = deTipo(r, TipoDaLinha.ESTORNO);
        // Pelo acumulado: 3,02 x 3,35 / 10,05 = 1,01 (já estornado); x 6,70 / 10,05 = 2,01; x 10,05 / 10,05 = 3,02.
        assertThat(estornos).extracting(Linha::valor).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("-1.00"), d("-1.01"));
    }

    @Test
    void baseZeroNaoGeraLinhaDeEstorno() {
        UUID antiga = UUID.randomUUID();
        Resultado r = calcular(List.of(), List.of(new Estorno(UUID.randomUUID(), antiga, d("0.00"))),
                Map.of(antiga, new Original(d("0.00"), d("0.00"), TRINTA, List.of())));
        assertThat(r.linhas()).isEmpty();
        assertThat(r.comissao()).isEqualByComparingTo("0.00");
    }

    @Test
    void estornoDeVendaSemComissaoOriginalEErroDoSistema() {
        assertThatThrownBy(() -> calcular(List.of(), List.of(new Estorno(UUID.randomUUID(), UUID.randomUUID(), d("5.00"))), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- meta e percentuais da configuração ---

    @Test
    void metaEPercentuaisDiferentesDoPadraoVindosDaConfiguracao() {
        Venda a = venda("630.00", DA_SEMANA);
        Resultado abaixo = CalculoDaComissao.calcular(700_001, BigDecimal.valueOf(10), BigDecimal.valueOf(20), List.of(a), List.of(),
                Map.of());
        assertThat(abaixo.percentual()).isEqualByComparingTo("10");
        assertThat(abaixo.linhas().get(0).valor()).isEqualByComparingTo("63.00");
        Resultado na = CalculoDaComissao.calcular(700_000, BigDecimal.valueOf(10), BigDecimal.valueOf(20), List.of(a), List.of(),
                Map.of());
        assertThat(na.percentual()).isEqualByComparingTo("20");
        assertThat(na.linhas().get(0).valor()).isEqualByComparingTo("126.00");
    }

    @Test
    void semVendasASomaEZeroEAComissaoTambem() {
        Resultado r = calcular(List.of());
        assertThat(r.vendidoParaMetaGuarani()).isZero();
        assertThat(r.percentual()).isEqualByComparingTo("30");
        assertThat(r.comissao()).isEqualByComparingTo("0.00");
        assertThat(r.linhas()).isEmpty();
    }
}
