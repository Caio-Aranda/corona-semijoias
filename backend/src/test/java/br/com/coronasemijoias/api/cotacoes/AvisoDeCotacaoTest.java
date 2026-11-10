package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * O aviso à dona (ADR-0006, item 12): dias úteis sem cotação nos últimos 10 dias, contando hoje só
 * depois das 21:00 UTC. Calculado, nunca gravado.
 */
class AvisoDeCotacaoTest extends TesteDeCotacoes {

    @Test
    void semNenhumaCotacaoOsDiasUteisDosUltimosDezDiasAparecem() {
        // Segunda 09/11, 22:00 UTC: janela 30/10 a 09/11. Úteis: 30/10, 03, 04, 05, 06 e 09/11 (02/11 é feriado).
        AvisoDeCotacao.Aviso a = avisoAs("2026-11-09T22:00:00Z");

        assertThat(a.diasSemCotacao()).containsExactly(
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4),
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 9));
        assertThat(a.cotacaoEmUso()).isEmpty();
    }

    @Test
    void hojeSoContaDepoisDas21Utc() {
        gravarUteisAte(LocalDate.of(2026, 11, 6));

        assertThat(avisoAs("2026-11-09T16:30:00Z").aparece()).isFalse();
        assertThat(avisoAs("2026-11-09T20:59:59Z").aparece()).isFalse();
        assertThat(avisoAs("2026-11-09T21:00:00Z").aparece()).isFalse();
        assertThat(avisoAs("2026-11-09T21:00:01Z").diasSemCotacao()).containsExactly(LocalDate.of(2026, 11, 9));
    }

    @Test
    void sumeSozinhoQuandoOEstadoDoDiaMuda() {
        gravarUteisAte(LocalDate.of(2026, 11, 6));
        assertThat(avisoAs("2026-11-09T22:00:00Z").aparece()).isTrue();

        gravarCotacaoDe(LocalDate.of(2026, 11, 9));

        assertThat(avisoAs("2026-11-09T22:00:00Z").aparece()).isFalse();
    }

    @Test
    void fimDeSemanaEFeriadoNuncaContam() {
        gravarUteisAte(LocalDate.of(2026, 11, 6));
        // Sábado, domingo e a madrugada de segunda em UTC: só o que é dia útil entra na conta.
        assertThat(avisoAs("2026-11-07T23:00:00Z").aparece()).isFalse();
        assertThat(avisoAs("2026-11-08T23:00:00Z").aparece()).isFalse();
        assertThat(avisoAs("2026-11-09T00:30:00Z").aparece()).isFalse();
    }

    @Test
    void diaForaDaJanelaDeDezDiasNaoContaMais() {
        // Cotações de 12/11 a 24/11 e nada antes: em 25/11 (22:00 UTC) a janela é 15/11 a 25/11.
        gravarUteisAte(LocalDate.of(2026, 11, 24));
        gravarCotacaoDe(LocalDate.of(2026, 11, 25));
        AvisoDeCotacao.Aviso a = avisoAs("2026-11-25T22:00:00Z");

        assertThat(a.aparece()).isFalse();
        // Um dia útil sem cotação 11 dias atrás já não aparece (14/11 é sábado; usamos 13/11, sexta, fora da janela).
        assertThat(a.diasSemCotacao()).doesNotContain(LocalDate.of(2026, 11, 13));
    }

    @Test
    void devolveADataDaCotacaoEmUso() {
        gravarUteisAte(LocalDate.of(2026, 11, 6));

        assertThat(avisoAs("2026-11-09T22:00:00Z").cotacaoEmUso()).contains(LocalDate.of(2026, 11, 6));
    }

    @Test
    void ODiaDeFalhaQuandoTambemFeriadoNaoVirAvisoMasOutroDiaSemCotacaoVira() {
        // Em 03/11 (terça), com Finados (02/11) sem cotação e 03/11 ainda sem a busca: só falta 03/11 depois das 21h.
        gravarUteisAte(LocalDate.of(2026, 10, 30));
        assertThat(avisoAs("2026-11-03T22:00:00Z").diasSemCotacao()).containsExactly(LocalDate.of(2026, 11, 3));
    }
}
