package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Qual cotação vale (ADR-0006, item 7): para o lançamento, a de maior data menor ou igual à data em
 * Brasília; para o que não é lançamento e para o acerto no fechamento, a mais recente. Usa as taxas
 * reais do item 14 do ADR.
 */
class CotacaoVigenteTest extends TesteDeCotacoes {

    static final LocalDate QUINTA_30_10 = LocalDate.of(2026, 10, 30).minusDays(0);
    static final LocalDate QUINTA_05_11 = LocalDate.of(2026, 11, 5);
    static final LocalDate SEXTA_06_11 = LocalDate.of(2026, 11, 6);

    @BeforeEach
    void cotacoesReais() {
        gravar(QUINTA_30_10, "5.42120000", "0.00092960");
        gravar(QUINTA_05_11, "5.46210000", "0.00088770");
        gravar(SEXTA_06_11, "5.50130000", "0.00089500");
    }

    private void gravar(LocalDate dia, String usdVenda, String pygVenda) {
        BigDecimal usd = new BigDecimal(usdVenda);
        BigDecimal pyg = new BigDecimal(pygVenda);
        TaxasDoDia taxas = new TaxasDoDia(
                usd.subtract(new BigDecimal("0.00060000")), usd, BigDecimal.ONE.setScale(8), BigDecimal.ONE.setScale(8),
                pyg.subtract(new BigDecimal("0.00000010")), pyg, new BigDecimal("6147.00000000"), new BigDecimal("6355.00000000"));
        repositorio.gravar(dia, taxas, Instant.parse("2026-01-01T00:00:00Z"), "teste");
    }

    private LocalDate dataDoLancamento(String instanteUtc) {
        return vigente.paraLancamento(Instant.parse(instanteUtc)).orElseThrow().dataCotacao();
    }

    @Test
    void lancamentoNoDiaDaCotacaoUsaAPropriaCotacao() {
        assertThat(dataDoLancamento("2026-11-06T15:00:00Z")).isEqualTo(SEXTA_06_11);
    }

    @Test
    void sabadoUsaASexta() {
        assertThat(dataDoLancamento("2026-11-07T15:00:00Z")).isEqualTo(SEXTA_06_11);
        assertThat(dataDoLancamento("2026-11-08T15:00:00Z")).as("domingo").isEqualTo(SEXTA_06_11);
        Cotacao cotacao = vigente.paraLancamentoEm(LocalDate.of(2026, 11, 7)).orElseThrow();
        // R$ 100,00 no sábado 07/11: US$ 18,18 e Gs. 111.732, sem aviso (ADR-0006, item 14).
        assertThat(cotacao.emDolar(new BigDecimal("100.00"))).isEqualTo(new BigDecimal("18.18"));
        assertThat(cotacao.emGuarani(new BigDecimal("100.00"))).isEqualTo(new BigDecimal("111732"));
    }

    @Test
    void vendaDas2330DeSextaEmBrasiliaUsaACotacaoDeSextaMesmoJaSendoSabadoEmUtc() {
        // 23:30 de sexta em Brasília = 02:30 UTC de sábado.
        assertThat(dataDoLancamento("2026-11-07T02:30:00Z")).isEqualTo(SEXTA_06_11);
        // Controle: 02:30 UTC de quinta é 23:30 de quarta em Brasília, então vale a de quinta 05/11 não, a de 30/10.
        assertThat(dataDoLancamento("2026-11-05T02:30:00Z")).as("quarta 04/11 em Brasília").isEqualTo(QUINTA_30_10);
        // E a primeira hora de segunda em Brasília (03:00 UTC) já é segunda, mas a cotação ainda é a de sexta.
        assertThat(dataDoLancamento("2026-11-09T03:00:00Z")).isEqualTo(SEXTA_06_11);
    }

    @Test
    void finadosUsaAUltimaCotacaoAntesDoFeriado() {
        Cotacao cotacao = vigente.paraLancamentoEm(LocalDate.of(2026, 11, 2)).orElseThrow();
        assertThat(cotacao.dataCotacao()).isEqualTo(QUINTA_30_10);
        // R$ 100,00 em Finados: US$ 18,45 e Gs. 107.573 (ADR-0006, item 14).
        assertThat(cotacao.emDolar(new BigDecimal("100.00"))).isEqualTo(new BigDecimal("18.45"));
        assertThat(cotacao.emGuarani(new BigDecimal("100.00"))).isEqualTo(new BigDecimal("107573"));
    }

    @Test
    void hojeAntesDaBuscaUsaOUltimoDiaUtilEDepoisUsaAPropriaCotacao() {
        LocalDate segunda = LocalDate.of(2026, 11, 9);
        assertThat(vigente.paraLancamentoEm(segunda).orElseThrow().dataCotacao()).isEqualTo(SEXTA_06_11);

        gravar(segunda, "5.51000000", "0.00089800");

        assertThat(vigente.paraLancamentoEm(segunda).orElseThrow().dataCotacao()).isEqualTo(segunda);
        // O lançamento de sexta continua com a cotação de sexta.
        assertThat(vigente.paraLancamentoEm(SEXTA_06_11).orElseThrow().dataCotacao()).isEqualTo(SEXTA_06_11);
    }

    @Test
    void lancamentoAntesDaPrimeiraCotacaoNaoTemCotacao() {
        assertThat(vigente.paraLancamentoEm(LocalDate.of(2026, 10, 29))).isEmpty();
    }

    @Test
    void oQueNaoELancamentoEOAcertoUsamAMaisRecente() {
        assertThat(vigente.maisRecente().orElseThrow().dataCotacao()).isEqualTo(SEXTA_06_11);
        gravar(LocalDate.of(2026, 11, 9), "5.51000000", "0.00089800");
        assertThat(vigente.maisRecente().orElseThrow().dataCotacao()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void semNenhumaCotacaoNaoHaMaisRecente() {
        // Outro dia, sem os dados do @BeforeEach: consulta uma data anterior a todas as cotações.
        assertThat(vigente.paraLancamentoEm(LocalDate.of(2000, 1, 1))).isEmpty();
    }
}
