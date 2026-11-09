package br.com.coronasemijoias.api.acessos;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Um pedido de login por vez de cada IP (ADR-0004, item 7). A trava cobre a conferência do bloqueio,
 * o BCrypt e a gravação da tentativa: sem ela, cem pedidos em paralelo passariam todos antes de o
 * primeiro erro ser gravado. Fica na memória (não no banco) para quem espera não segurar uma conexão
 * do banco; vale enquanto a API roda numa instância só (mais de uma pede um ADR).
 *
 * <p>Conjunto fixo de travas, escolhidas pelo IP: a memória não cresce com o número de IPs. Dois IPs
 * podem cair na mesma trava (1 em 1024); o efeito é só esperar a vez do outro.
 */
@Component
public class TravaPorIp {

    private static final int QUANTIDADE = 1024;

    private final ReentrantLock[] travas = new ReentrantLock[QUANTIDADE];

    public TravaPorIp() {
        for (int i = 0; i < QUANTIDADE; i++) {
            travas[i] = new ReentrantLock(true);
        }
    }

    /** A vez daquele IP, liberada no {@code close()}. Vazio se a espera passou do limite. */
    public Optional<Vez> tentar(String ip, Duration espera) {
        ReentrantLock trava = travas[Math.floorMod(ip.hashCode(), QUANTIDADE)];
        try {
            if (trava.tryLock(espera.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.of(trava::unlock);
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** A vez de um IP: libera ao fechar. */
    @FunctionalInterface
    public interface Vez extends AutoCloseable {

        @Override
        void close();
    }
}
