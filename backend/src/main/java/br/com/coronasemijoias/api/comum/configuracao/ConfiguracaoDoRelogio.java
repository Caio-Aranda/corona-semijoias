package br.com.coronasemijoias.api.comum.configuracao;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** O relógio da aplicação, sempre em UTC (ADR-0002). Os testes podem trocar por um relógio fixo. */
@Configuration(proxyBeanMethods = false)
public class ConfiguracaoDoRelogio {

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }
}
