package br.com.coronasemijoias.api.documentacao;

import br.com.coronasemijoias.api.comum.json.DinheiroJsonModule;
import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dois documentos OpenAPI (ADR-0003): "dona" e "vendedor". As rotas de login ficam nos dois.
 * A interface e o JSON só ficam ligados no perfil "dev" (application-dev.properties).
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracaoOpenApi {

    static {
        // Dinheiro aparece como texto no OpenAPI, para os clientes gerados (tarefa 13)
        // não criarem campos "number".
        SpringDocUtils.getConfig().replaceWithSchema(
                BigDecimal.class,
                new StringSchema().format("decimal").pattern(DinheiroJsonModule.PADRAO)
                        .example("123.45"));
    }

    @Bean
    GroupedOpenApi grupoDona() {
        return GroupedOpenApi.builder()
                .group("dona")
                .pathsToMatch("/api/dona/**", "/api/auth/**")
                .build();
    }

    @Bean
    GroupedOpenApi grupoVendedor() {
        return GroupedOpenApi.builder()
                .group("vendedor")
                .pathsToMatch("/api/vendedor/**", "/api/auth/**")
                .build();
    }
}
