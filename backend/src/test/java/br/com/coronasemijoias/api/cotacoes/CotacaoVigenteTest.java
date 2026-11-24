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
        return vigente.vigenteEm(Instant.parse(instanteUtc)).orElseThrow().dataCotacao();
    }

    @Test
    void lancamentoNoDiaDaCotacaoUsaAPropriaCotacao() {
        assertThat(dataDoLancamento("2026-11-06T15:00:00Z")).isEqualTo(SEXTA_06_11);
    }

    @Test
    void sabadoUsaASexta() {
        assertThat(dataDoLancamento("2026-11-07T15:00:00Z")).isEqualTo(SEXTA_06_11);
        assertThat(dataDoLancamento("2026-11-08T15:00:00Z")).as("domingo").isEqualTo(SEXTA_06_11);
        Cotacao cotacao = vigente.vigenteEm(Instant.parse("2026-11-07T15:00:00Z")).orElseThrow();
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
        Cotacao cotacao = vigente.vigenteEm(Instant.parse("2026-11-02T15:00:00Z")).orElseThrow();
        assertThat(cotacao.dataCotacao()).isEqualTo(QUINTA_30_10);
        // R$ 100,00 em Finados: US$ 18,45 e Gs. 107.573 (ADR-0006, item 14).
        assertThat(cotacao.emDolar(new BigDecimal("100.00"))).isEqualTo(new BigDecimal("18.45"));
        assertThat(cotacao.emGuarani(new BigDecimal("100.00"))).isEqualTo(new BigDecimal("107573"));
    }

    @Test
    void hojeAntesDaBuscaUsaOUltimoDiaUtilEDepoisUsaAPropriaCotacao() {
        LocalDate segunda = LocalDate.of(2026, 11, 9);
        Instant segundaDeManha = Instant.parse("2026-11-09T12:00:00Z");
        assertThat(vigente.vigenteEm(segundaDeManha).orElseThrow().dataCotacao()).isEqualTo(SEXTA_06_11);

        gravar(segunda, "5.51000000", "0.00089800");

        assertThat(vigente.vigenteEm(segundaDeManha).orElseThrow().dataCotacao()).isEqualTo(segunda);
        // O lançamento de sexta continua com a cotação de sexta.
        assertThat(vigente.vigenteEm(Instant.parse("2026-11-06T15:00:00Z")).orElseThrow().dataCotacao()).isEqualTo(SEXTA_06_11);
    }

    @Test
    void lancamentoAntesDaPrimeiraCotacaoNaoTemCotacao() {
        assertThat(vigente.vigenteEm(Instant.parse("2026-10-29T15:00:00Z"))).isEmpty();
    }

    @Test
    void oQueNaoELancamentoEOAcertoUsamAVigenteAgora() {
        Instant agora = Instant.parse("2026-11-09T12:00:00Z");
        assertThat(vigente.vigenteEm(agora).orElseThrow().dataCotacao()).isEqualTo(SEXTA_06_11);
        gravar(LocalDate.of(2026, 11, 9), "5.51000000", "0.00089800");
        assertThat(vigente.vigenteEm(agora).orElseThrow().dataCotacao()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void semNenhumaCotacaoNaoHaMaisRecente() {
        // Outro dia, sem os dados do @BeforeEach: consulta uma data anterior a todas as cotações.
        assertThat(vigente.vigenteEm(Instant.parse("2000-01-01T15:00:00Z"))).isEmpty();
    }

    // --- a cotação da dona (ADR-0006, item 15) ---

    private static final LocalDate DIA = LocalDate.of(2026, 11, 23);
    private static final String DEZ_DA_MANHA = "2026-11-23T13:00:00Z"; // 10:00 em Brasília

    private java.util.UUID vigenteAs(String instanteUtc) {
        return vigente.vigenteEm(Instant.parse(instanteUtc)).orElseThrow().id();
    }

    @Test
    void bcbDoDiaAntesDaDonaDepoisDaDonaEAteODiaSeguinte() {
        gravar(LocalDate.of(2026, 11, 20), "5.50000000", "0.00089000");
        Cotacao dona = gravarDaDona(DEZ_DA_MANHA, "5.80", "1350");
        // O BCB do dia é gravado depois da dona, mas vale desde 00:00 de Brasília: não passa por cima dela.
        gravar(DIA, "5.51000000", "0.00089100");
        java.util.UUID bcbDoDia = vigenteAs("2026-11-23T05:00:00Z");

        assertThat(bcbDoDia).isNotEqualTo(dona.id());
        assertThat(vigente.vigenteEm(Instant.parse("2026-11-23T05:00:00Z")).orElseThrow().dataCotacao()).isEqualTo(DIA);
        assertThat(vigenteAs("2026-11-23T12:59:00Z")).isEqualTo(bcbDoDia);
        assertThat(vigenteAs(DEZ_DA_MANHA)).isEqualTo(dona.id());
        assertThat(vigenteAs("2026-11-23T18:00:00Z")).isEqualTo(dona.id());
        // No dia seguinte, sem BCB novo, ainda vale a dela; depois da busca, vale a do BCB do dia.
        assertThat(vigenteAs("2026-11-24T12:00:00Z")).isEqualTo(dona.id());
        gravar(DIA.plusDays(1), "5.52000000", "0.00089200");
        assertThat(vigente.vigenteEm(Instant.parse("2026-11-24T12:00:00Z")).orElseThrow().dataCotacao())
                .isEqualTo(DIA.plusDays(1));
    }

    @Test
    void sextaAs2330EmBrasiliaUsaASextaMesmoSendoSabadoEmUtc() {
        gravar(LocalDate.of(2026, 11, 5), "5.50000000", "0.00089000");
        Cotacao sexta = gravarDaDona("2026-11-06T12:00:00Z", "5.60", "1300");
        assertThat(vigenteAs("2026-11-07T02:30:00Z")).isEqualTo(sexta.id());
    }

    @Test
    void duasDaDonaComOMesmoVigenteDesdeDesempataPeloMaiorId() {
        Cotacao a = gravarDaDona(DEZ_DA_MANHA, "5.80", "1350");
        Cotacao b = gravarDaDona(DEZ_DA_MANHA, "5.90", "1360");
        java.util.UUID maior = a.id().compareTo(b.id()) > 0 ? a.id() : b.id();
        assertThat(vigenteAs(DEZ_DA_MANHA)).isEqualTo(maior);
    }

    @Test
    void doDiaListaTodasAsDoDiaEmOrdemDeVigencia() {
        gravar(DIA, "5.51000000", "0.00089100");
        Cotacao dona1 = gravarDaDona(DEZ_DA_MANHA, "5.80", "1350");
        Cotacao dona2 = gravarDaDona("2026-11-23T17:00:00Z", "5.85", "1360");

        assertThat(repositorio.doDia(DIA)).extracting(Cotacao::origem)
                .containsExactly("BANCO_CENTRAL", "DONA", "DONA");
        assertThat(repositorio.doDia(DIA)).extracting(Cotacao::id).endsWith(dona1.id(), dona2.id());
    }

    @Test
    void diasComCotacaoContaSoOBancoCentral() {
        gravarDaDona(DEZ_DA_MANHA, "5.80", "1350");
        assertThat(repositorio.diasComCotacao(DIA, DIA)).isEmpty();
        gravar(DIA, "5.51000000", "0.00089100");
        assertThat(repositorio.diasComCotacao(DIA, DIA)).containsExactly(DIA);
    }
}
