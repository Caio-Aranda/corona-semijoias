package br.com.coronasemijoias.api.acessos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.acessos.AlertaDeErrosPorLogin.LoginComMuitosErros;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Alerta de mais de 20 erros por hora no mesmo login, somando os IPs (ADR-0004, item 7): só a regra e
 * a consulta, sem tela. Os erros vêm de IPs diferentes, então nenhum bloqueio acontece.
 */
class AlertaDeErrosPorLoginTest extends TesteDeIntegracao {

    @Autowired
    ControleDeTentativas tentativas;

    @Autowired
    AlertaDeErrosPorLogin alerta;

    private static String login() {
        return "alerta." + UUID.randomUUID().toString().substring(0, 8);
    }

    /** {@code quantos} erros do login, cada um de um IP, terminando em {@code ultimo}, um por minuto para trás. */
    private void errar(String login, int quantos, Instant ultimo) {
        for (int i = 0; i < quantos; i++) {
            tentativas.registrarErro(login, "198.51.100." + (i + 1), null, ultimo.minus(Duration.ofMinutes(i)));
        }
    }

    private List<String> alertados(Instant agora, String... logins) {
        return alerta.loginsComMuitosErros(agora).stream().map(LoginComMuitosErros::login)
                .filter(List.of(logins)::contains).toList();
    }

    @Test
    void maisDe20ErrosNaUltimaHoraSomandoOsIpsGeramOAlerta() {
        Instant agora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String vinteEUm = login();
        String vinte = login();
        errar(vinteEUm, 21, agora);
        errar(vinte, 20, agora);

        List<LoginComMuitosErros> lista = alerta.loginsComMuitosErros(agora);

        LoginComMuitosErros alertado = lista.stream().filter(l -> l.login().equals(vinteEUm)).findFirst().orElseThrow();
        assertThat(alertado.erros()).isEqualTo(21);
        assertThat(alertado.ultimoErro()).isEqualTo(agora);
        assertThat(alertado.primeiroErro()).isEqualTo(agora.minus(Duration.ofMinutes(20)));
        assertThat(alertados(agora, vinteEUm, vinte)).containsExactly(vinteEUm);
    }

    @Test
    void errosComMaisDeUmaHoraNaoContam() {
        Instant agora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String login = login();
        // 21 erros, mas o mais antigo tem 1 h 01 min: na última hora só há 20.
        errar(login, 20, agora);
        tentativas.registrarErro(login, "198.51.100.99", null, agora.minus(Duration.ofMinutes(61)));

        assertThat(alertados(agora, login)).isEmpty();
        // Meia hora depois, a janela ainda tem os mesmos 20 erros: continua sem alerta.
        assertThat(alertados(agora.plus(Duration.ofMinutes(30)), login)).isEmpty();
    }

    @Test
    void oAlertaNuncaBloqueia() {
        Instant agora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String login = login();
        errar(login, 25, agora);
        // Cada IP errou uma vez só: nenhum bloqueio, nem de login + IP nem de IP.
        assertThat(tentativas.bloqueioEmVigor(login, "198.51.100.200", agora)).isEmpty();
        assertThat(tentativas.bloqueioEmVigor(login, "198.51.100.1", agora)).isEmpty();
    }
}
