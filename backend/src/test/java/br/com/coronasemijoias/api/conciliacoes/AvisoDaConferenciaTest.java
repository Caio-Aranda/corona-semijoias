package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.conciliacoes.ConsultasDaConferenciaDiaria.Rodada;
import br.com.coronasemijoias.api.testes.RodadasDeTeste;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * O aviso da conferência diária na tela inicial (tarefa 8.4): olha só a última rodada, calculado na hora com o relógio. A regra é
 * conferida sem banco (todas as pontas) e, com o banco e o relógio controlado, pela rota de verdade. As rodadas dos testes ficam
 * sempre de agora (nunca no futuro), uma depois da outra e o fim de cada teste deixa uma rodada limpa, para não sujar os outros testes.
 */
class AvisoDaConferenciaTest extends ApoioDasConciliacoes {

    private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");

    @AfterEach
    void deixarUmaRodadaLimpa() {
        relogio.reiniciar();
        RodadasDeTeste.limpa(jdbc, depois());
    }

    /** O instante de agora, sempre depois de qualquer rodada gravada antes (o aviso olha a de início mais novo). */
    private static Instant depois() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Instant.now();
    }

    private static Rodada rodada(String situacao, int total, Instant iniciada) {
        return new Rodada(UUID.randomUUID(), iniciada, iniciada, "AUTOMATICA", null, situacao, total, List.of("C1"),
                "FALHOU".equals(situacao) ? List.of("C3") : List.of());
    }

    private static List<AvisoDaDona> avisos(Rodada rodada) {
        return FonteDeAvisosDaConferencia.avisosDe(Optional.ofNullable(rodada), AGORA);
    }

    // --- a regra, sem banco ---

    @Test
    void semNenhumaRodadaOAvisoDizQueNaoRodou() {
        List<AvisoDaDona> a = avisos(null);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).tipo()).isEqualTo("CONFERENCIA_NAO_RODOU");
        assertThat(a.get(0).titulo()).isEqualTo("A conferência de hoje não rodou");
        assertThat(a.get(0).dias()).isEmpty();
    }

    @Test
    void rodadaLimpaDeHoteNaoAvisaNada() {
        assertThat(avisos(rodada("SEM_DIFERENCA", 0, AGORA.minus(Duration.ofHours(3))))).isEmpty();
    }

    @Test
    void ate26HorasExatasNaoAvisaEDepoisAvisa() {
        assertThat(avisos(rodada("SEM_DIFERENCA", 0, AGORA.minus(Duration.ofHours(26))))).as("26 h exatas").isEmpty();
        List<AvisoDaDona> a = avisos(rodada("SEM_DIFERENCA", 0, AGORA.minus(Duration.ofHours(26)).minusSeconds(1)));
        assertThat(a).hasSize(1);
        assertThat(a.get(0).tipo()).isEqualTo("CONFERENCIA_NAO_RODOU");
        assertThat(a.get(0).texto()).contains("26 horas").contains("Conferir agora");
    }

    @Test
    void comDiferencaDizQuantasNoSingularENoPlural() {
        List<AvisoDaDona> uma = avisos(rodada("COM_DIFERENCA", 1, AGORA.minusSeconds(60)));
        assertThat(uma).hasSize(1);
        assertThat(uma.get(0).tipo()).isEqualTo("CONFERENCIA_COM_DIFERENCA");
        assertThat(uma.get(0).titulo()).isEqualTo("A conferência de hoje achou 1 diferença");
        assertThat(uma.get(0).texto()).isEqualTo("Abra a conferência para ver o que não bate. Nada foi mudado sozinho.");
        List<AvisoDaDona> tres = avisos(rodada("COM_DIFERENCA", 3, AGORA.minusSeconds(60)));
        assertThat(tres.get(0).titulo()).isEqualTo("A conferência de hoje achou 3 diferenças");
    }

    @Test
    void comDiferencaEVelhaDaOsDoisAvisos() {
        List<AvisoDaDona> a = avisos(rodada("COM_DIFERENCA", 2, AGORA.minus(Duration.ofHours(30))));
        assertThat(a).extracting(AvisoDaDona::tipo).containsExactly("CONFERENCIA_COM_DIFERENCA", "CONFERENCIA_NAO_RODOU");
    }

    @Test
    void rodadaQueNaoTerminouAvisaUmaVezSo() {
        List<AvisoDaDona> a = avisos(rodada("FALHOU", 0, AGORA.minusSeconds(60)));
        assertThat(a).hasSize(1);
        assertThat(a.get(0).tipo()).isEqualTo("CONFERENCIA_NAO_RODOU");
        assertThat(a.get(0).texto()).contains("Uma parte da conferência não terminou").contains("Conferir agora");
        assertThat(avisos(rodada("FALHOU", 0, AGORA.minus(Duration.ofHours(40))))).hasSize(1);
    }

    @Test
    void oDiaDoAvisoEODiaDeBrasiliaDaRodada() {
        // 02:00 UTC ainda é o dia anterior em Brasília.
        List<AvisoDaDona> a = avisos(rodada("COM_DIFERENCA", 2, Instant.parse("2026-11-16T02:00:00Z")));
        assertThat(a.get(0).dias()).containsExactly(LocalDate.of(2026, 11, 15));
    }

    // --- pela rota, com o banco e o relógio ---

    private List<String> tiposDaConferencia() throws Exception {
        JsonNode r = corpoDe(ler("/api/dona/avisos", dona()).andExpect(status().isOk()));
        return r.get("avisos").valueStream().map(a -> a.get("tipo").asString()).filter(t -> t.startsWith("CONFERENCIA_")).toList();
    }

    private JsonNode avisoDeTipo(String tipo) throws Exception {
        return corpoDe(ler("/api/dona/avisos", dona()).andExpect(status().isOk())).get("avisos").valueStream()
                .filter(a -> tipo.equals(a.get("tipo").asString())).findFirst().orElseThrow();
    }

    @Test
    void pelaRotaARodadaLimpaAvisaSoQuandoPassaDe26HorasECadaRodadaNovaTomaOLugar() throws Exception {
        RodadasDeTeste.limpa(jdbc, depois());
        assertThat(tiposDaConferencia()).isEmpty();

        relogio.avancar(Duration.ofHours(26).plusSeconds(1));
        assertThat(tiposDaConferencia()).containsExactly("CONFERENCIA_NAO_RODOU");
        relogio.reiniciar();

        RodadasDeTeste.comDiferenca(jdbc, depois(), 1);
        assertThat(tiposDaConferencia()).containsExactly("CONFERENCIA_COM_DIFERENCA");
        assertThat(avisoDeTipo("CONFERENCIA_COM_DIFERENCA").get("titulo").asString()).isEqualTo("A conferência de hoje achou 1 diferença");

        RodadasDeTeste.comDiferenca(jdbc, depois(), 4);
        assertThat(avisoDeTipo("CONFERENCIA_COM_DIFERENCA").get("titulo").asString()).isEqualTo("A conferência de hoje achou 4 diferenças");

        RodadasDeTeste.falhou(jdbc, depois(), "C3");
        assertThat(tiposDaConferencia()).containsExactly("CONFERENCIA_NAO_RODOU");

        RodadasDeTeste.limpa(jdbc, depois());
        assertThat(tiposDaConferencia()).as("uma rodada limpa depois faz o aviso sumir").isEmpty();
    }
}
