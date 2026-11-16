package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.conciliacoes.ServicoDaConferenciaDiaria.Origem;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/** A agenda da conferência diária (tarefa 8.4): 03:00 de Brasília, desligada nos testes, grava rodada AUTOMATICA e nunca derruba a API. */
class AgendaDaConferenciaDiariaTest extends TesteDeIntegracao {

    @Autowired
    ApplicationContext contexto;

    @Autowired
    ServicoDaConferenciaDiaria servico;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static Method metodo() throws Exception {
        Method m = AgendaDaConferenciaDiaria.class.getDeclaredMethod("conferir");
        m.setAccessible(true);
        return m;
    }

    @Test
    void oCronEDasTresDaManhaDeBrasiliaEOProximoDisparoCaiAsSeisEmUtc() throws Exception {
        Scheduled agendado = metodo().getAnnotation(Scheduled.class);
        assertThat(agendado.cron()).isEqualTo("0 0 3 * * *");
        assertThat(agendado.zone()).isEqualTo("America/Sao_Paulo");
        ZonedDateTime de = Instant.parse("2026-11-16T05:00:00Z").atZone(ZoneId.of(agendado.zone()));
        ZonedDateTime proximo = CronExpression.parse(agendado.cron()).next(de);
        assertThat(proximo.toInstant()).isEqualTo(Instant.parse("2026-11-16T06:00:00Z"));
        assertThat(CronExpression.parse(agendado.cron()).next(proximo).toInstant()).isEqualTo(Instant.parse("2026-11-17T06:00:00Z"));
    }

    @Test
    void comAPropriedadeDesligadaOBeanNaoExiste() {
        assertThat(contexto.getBeanNamesForType(AgendaDaConferenciaDiaria.class)).isEmpty();
        assertThat(AgendaDaConferenciaDiaria.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class).matchIfMissing())
                .as("ligada por padrão em produção").isTrue();
    }

    @Test
    void chamarAAgendaGravaUmaRodadaAutomaticaSemQuemPediu() throws Exception {
        int antes = jdbc.queryForObject("SELECT count(*) FROM corona.rodadas_conferencia WHERE origem = 'AUTOMATICA'", Integer.class);
        metodo().invoke(new AgendaDaConferenciaDiaria(servico));
        Map<String, Object> ultima = jdbc.queryForMap(
                "SELECT * FROM corona.rodadas_conferencia WHERE origem = 'AUTOMATICA' ORDER BY iniciada_em DESC LIMIT 1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.rodadas_conferencia WHERE origem = 'AUTOMATICA'", Integer.class))
                .isEqualTo(antes + 1);
        assertThat(ultima.get("pedida_por")).isNull();
    }

    @Test
    void umaExcecaoDentroDaAgendaNaoSaiDoMetodo() throws Exception {
        ServicoDaConferenciaDiaria quebrado = Mockito.mock(ServicoDaConferenciaDiaria.class);
        Mockito.when(quebrado.rodar(Mockito.any(), Mockito.eq(Origem.AUTOMATICA), Mockito.isNull()))
                .thenThrow(new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONFERENCIA_EM_ANDAMENTO))
                .thenThrow(new IllegalStateException("erro de teste"));
        AgendaDaConferenciaDiaria agenda = new AgendaDaConferenciaDiaria(quebrado);
        Method m = metodo();
        assertThatCode(() -> m.invoke(agenda)).doesNotThrowAnyException();
        assertThatCode(() -> m.invoke(agenda)).doesNotThrowAnyException();
        Mockito.verify(quebrado, Mockito.times(2)).rodar(Mockito.any(UUID.class), Mockito.eq(Origem.AUTOMATICA), Mockito.isNull());
    }
}
