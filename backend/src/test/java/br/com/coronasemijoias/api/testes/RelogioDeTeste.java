package br.com.coronasemijoias.api.testes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * O relógio da aplicação nos testes de integração: a hora real mais um deslocamento que o teste pode
 * avançar (por exemplo, 8 dias, para vencer a senha temporária) sem esperar e sem criar um contexto de
 * teste novo. Quem avança TEM de chamar {@link #reiniciar()} no {@code @AfterEach}, porque o contexto é
 * compartilhado entre os testes.
 */
@TestComponent
@Primary
public class RelogioDeTeste extends Clock {

    private volatile Duration deslocamento = Duration.ZERO;

    public void avancar(Duration tempo) {
        deslocamento = deslocamento.plus(tempo);
    }

    public void reiniciar() {
        deslocamento = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(deslocamento);
    }
}
