package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Feriados bancários nacionais (ADR-0006, item 3): fixos mais os que dependem da Páscoa. */
class CalendarioBancarioTest {

    private final CalendarioBancario calendario = new CalendarioBancario();

    private static LocalDate dia(int ano, int mes, int dia) {
        return LocalDate.of(ano, mes, dia);
    }

    @Test
    void feriadosMoveisDeDoisMilEVinteESeis() {
        assertThat(calendario.ehFeriado(dia(2026, 2, 16))).as("segunda de Carnaval").isTrue();
        assertThat(calendario.ehFeriado(dia(2026, 2, 17))).as("terça de Carnaval").isTrue();
        assertThat(calendario.ehFeriado(dia(2026, 4, 3))).as("Sexta-feira Santa").isTrue();
        assertThat(calendario.ehFeriado(dia(2026, 6, 4))).as("Corpus Christi").isTrue();
    }

    @Test
    void feriadosMoveisDeDoisMilEVinteESete() {
        assertThat(calendario.ehFeriado(dia(2027, 2, 8))).as("segunda de Carnaval").isTrue();
        assertThat(calendario.ehFeriado(dia(2027, 2, 9))).as("terça de Carnaval").isTrue();
        assertThat(calendario.ehFeriado(dia(2027, 3, 26))).as("Sexta-feira Santa").isTrue();
        assertThat(calendario.ehFeriado(dia(2027, 5, 27))).as("Corpus Christi").isTrue();
    }

    @Test
    void feriadosMoveisConferidosNoBancoCentralEmDoisMilEVinteECinco() {
        assertThat(calendario.ehFeriado(dia(2025, 3, 3))).isTrue();
        assertThat(calendario.ehFeriado(dia(2025, 3, 4))).isTrue();
        assertThat(calendario.ehFeriado(dia(2025, 4, 18))).isTrue();
        assertThat(calendario.ehFeriado(dia(2025, 6, 19))).isTrue();
        assertThat(calendario.ehFeriado(dia(2025, 11, 20))).isTrue();
    }

    @Test
    void feriadosFixosEmDoisMilEVinteESeis() {
        List<LocalDate> fixos = List.of(
                dia(2026, 1, 1), dia(2026, 4, 21), dia(2026, 5, 1), dia(2026, 9, 7), dia(2026, 10, 12),
                dia(2026, 11, 2), dia(2026, 11, 15), dia(2026, 11, 20), dia(2026, 12, 25));
        assertThat(fixos).allSatisfy(data -> assertThat(calendario.ehFeriado(data)).as(data.toString()).isTrue());
    }

    @Test
    void finadosNumaSegundaEDiaSemArquivo() {
        LocalDate finados = dia(2026, 11, 2);
        assertThat(finados.getDayOfWeek().name()).isEqualTo("MONDAY");
        assertThat(calendario.ehFeriado(finados)).isTrue();
        assertThat(calendario.ehDiaUtil(finados)).isFalse();
    }

    @Test
    void conscienciaNumaSextaNaoEDiaUtil() {
        LocalDate dia = dia(2026, 11, 20);
        assertThat(dia.getDayOfWeek().name()).isEqualTo("FRIDAY");
        assertThat(calendario.ehDiaUtil(dia)).isFalse();
    }

    @Test
    void quartaFeiraDeCinzasEDiaUtil() {
        assertThat(calendario.ehFeriado(dia(2026, 2, 18))).isFalse();
        assertThat(calendario.ehDiaUtil(dia(2026, 2, 18))).isTrue();
    }

    @Test
    void vesperasEDiasDoBancoQueTemArquivo() {
        // Conferidos no BCB em 09/11/2026: 24/12/2025, 31/12/2025 e 02/01/2026 têm arquivo.
        assertThat(calendario.ehDiaUtil(dia(2025, 12, 24))).isTrue();
        assertThat(calendario.ehDiaUtil(dia(2025, 12, 31))).isTrue();
        assertThat(calendario.ehDiaUtil(dia(2026, 1, 2))).isTrue();
    }

    @Test
    void sabadoEDomingoNaoSaoDiasUteis() {
        assertThat(calendario.ehDiaUtil(dia(2026, 11, 7))).isFalse();
        assertThat(calendario.ehDiaUtil(dia(2026, 11, 8))).isFalse();
        assertThat(calendario.ehDiaUtil(dia(2026, 11, 9))).isTrue();
    }

    @Test
    void pascoaCalculadaParaVariosAnos() {
        assertThat(CalendarioBancario.pascoa(2024)).isEqualTo(dia(2024, 3, 31));
        assertThat(CalendarioBancario.pascoa(2025)).isEqualTo(dia(2025, 4, 20));
        assertThat(CalendarioBancario.pascoa(2026)).isEqualTo(dia(2026, 4, 5));
        assertThat(CalendarioBancario.pascoa(2027)).isEqualTo(dia(2027, 3, 28));
        assertThat(CalendarioBancario.pascoa(2038)).isEqualTo(dia(2038, 4, 25));
    }
}
