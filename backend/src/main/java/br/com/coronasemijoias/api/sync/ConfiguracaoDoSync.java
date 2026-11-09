package br.com.coronasemijoias.api.sync;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracaoDoSync {

    @Bean
    public RegistroDeTabelas registroDeTabelas() {
        return RegistroDeTabelas.daAplicacao();
    }

    @Bean
    public ServicoDePull servicoDePull(DataSource dataSource, RegistroDeTabelas registro) {
        return new ServicoDePull(dataSource, registro);
    }
}
