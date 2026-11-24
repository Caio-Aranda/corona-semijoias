package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Por quanto tempo o servidor aceita cada formato de operação (tarefa 5.13; ADR-0013, item 17): o formato mais novo de
 * um tipo vale sempre; o anterior vale por 90 dias depois de publicado o novo, e nem um instante a mais. Escrito antes
 * do código.
 */
class FormatosDeOperacaoTest {

    private static final Instant PUBLICOU_O_1 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant PUBLICOU_O_2 = Instant.parse("2027-02-01T12:00:00Z");

    private static FormatosDeOperacao em(Instant agora) {
        FormatosDeOperacao f = new FormatosDeOperacao(Clock.fixed(agora, ZoneOffset.UTC), Duration.ofDays(90));
        f.publicar("VENDA", 2, PUBLICOU_O_2);
        return f;
    }

    @Test
    void producaoTemSoOFormato1DeCadaTipoEElesValemSempre() {
        FormatosDeOperacao f = new FormatosDeOperacao(Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofDays(90));
        for (String tipo : new String[] {"VENDA", "ESTORNO_VENDA", "CLIENTE_NOVO", "CLIENTE_ALTERADO", "CONFERENCIA"}) {
            assertThat(f.conhecido(tipo, 1)).as(tipo).isTrue();
            // Em 2040 o formato 1 do cadastro do cliente ja passou dos 90 dias do formato 2 (tarefa 9.1).
            assertThat(f.aceitoAgora(tipo, 1)).as(tipo).isEqualTo(!tipo.startsWith("CLIENTE_"));
            // O cadastro do cliente ganhou o formato 2 no consignado (tarefa 9.1); os outros seguem so com o 1.
            assertThat(f.conhecido(tipo, 2)).as(tipo).isEqualTo(tipo.startsWith("CLIENTE_"));
        }
        assertThat(f.conhecido("DESCONHECIDO", 1)).isFalse();
        assertThat(f.conhecido(null, 1)).isFalse();
        assertThat(f.conhecido("VENDA", 0)).isFalse();
    }

    @Test
    void osCincoTiposDaFase6TemOFormato1EAVendaNaoGanhaFormato() {
        FormatosDeOperacao f = new FormatosDeOperacao(Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofDays(90));
        for (String tipo : new String[] {"DEVOLUCAO", "PEDIDO_AUTORIZACAO", "TROCA", "CANCELAR_DEVOLUCAO", "CANCELAR_TROCA"}) {
            assertThat(f.conhecido(tipo, 1)).as(tipo).isTrue();
            // Em 2040 o formato 1 do cadastro do cliente ja passou dos 90 dias do formato 2 (tarefa 9.1).
            assertThat(f.aceitoAgora(tipo, 1)).as(tipo).isEqualTo(!tipo.startsWith("CLIENTE_"));
            assertThat(f.aceitoAte(tipo, 1)).as(tipo).isEmpty();
            assertThat(f.conhecido(tipo, 2)).as(tipo).isFalse();
        }
        // A venda com crédito é o formato 1 ampliado (dúvida S3): nenhum formato 2, nada começa a vencer.
        assertThat(f.conhecido("VENDA", 2)).isFalse();
        assertThat(f.aceitoAte("VENDA", 1)).isEmpty();
        assertThat(FormatosDeOperacao.FORMATO_1_DA_FASE_6).isEqualTo(Instant.parse("2026-11-13T00:00:00Z"));
    }

    @Test
    void formato1ContinuaAceitoDepoisDeExistirO2AteOs90Dias() {
        assertThat(em(PUBLICOU_O_2).aceitoAgora("VENDA", 1)).isTrue();
        assertThat(em(PUBLICOU_O_2.plus(Duration.ofDays(89))).aceitoAgora("VENDA", 1)).isTrue();
        assertThat(em(PUBLICOU_O_2.plus(Duration.ofDays(90)).minusMillis(1)).aceitoAgora("VENDA", 1)).isTrue();
        // O formato novo vale, e os outros tipos (ainda no 1) não são afetados.
        assertThat(em(PUBLICOU_O_2.plus(Duration.ofDays(400))).aceitoAgora("VENDA", 2)).isTrue();
        assertThat(em(PUBLICOU_O_2.plus(Duration.ofDays(400))).aceitoAgora("ESTORNO_VENDA", 1)).isTrue();
    }

    @Test
    void noDia90ExatoOFormato1VenceMasContinuaConhecido() {
        FormatosDeOperacao f = em(PUBLICOU_O_2.plus(Duration.ofDays(90)));
        assertThat(f.aceitoAgora("VENDA", 1)).isFalse();
        // Conhecido: o servidor ainda sabe ler (para responder JA_RECEBIDO a quem reenvia o que já entrou).
        assertThat(f.conhecido("VENDA", 1)).isTrue();
        assertThat(f.aceitoAte("VENDA", 1)).contains(PUBLICOU_O_2.plus(Duration.ofDays(90)));
        assertThat(f.aceitoAte("VENDA", 2)).isEmpty();
    }

    @Test
    void antesDePublicarONovoOAntigoNaoComecaAVencer() {
        // Formato novo anunciado com data futura: o prazo do antigo conta só a partir dela.
        assertThat(em(PUBLICOU_O_1).aceitoAgora("VENDA", 1)).isTrue();
    }

    @Test
    void prazoVemDaConfiguracao() {
        FormatosDeOperacao f = new FormatosDeOperacao(
                Clock.fixed(PUBLICOU_O_2.plus(Duration.ofDays(100)), ZoneOffset.UTC), Duration.ofDays(180));
        f.publicar("VENDA", 2, PUBLICOU_O_2);
        assertThat(f.aceitoAgora("VENDA", 1)).isTrue();
    }

    @Test
    void reiniciarVoltaAoCatalogoDeProducao() {
        FormatosDeOperacao f = em(PUBLICOU_O_2.plus(Duration.ofDays(200)));
        assertThat(f.aceitoAgora("VENDA", 1)).isFalse();
        f.reiniciar();
        assertThat(f.aceitoAgora("VENDA", 1)).isTrue();
        assertThat(f.conhecido("VENDA", 2)).isFalse();
    }
}
