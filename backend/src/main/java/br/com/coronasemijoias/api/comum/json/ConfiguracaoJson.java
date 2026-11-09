package br.com.coronasemijoias.api.comum.json;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConfiguracaoJson {

    /** Vale para toda a API: dinheiro (BigDecimal) trafega como texto decimal. */
    @Bean
    JsonMapperBuilderCustomizer dinheiroComoTexto() {
        return builder -> builder.addModule(new DinheiroJsonModule());
    }
}
