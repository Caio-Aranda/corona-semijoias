package br.com.coronasemijoias.api.documentacao;

import br.com.coronasemijoias.api.comum.json.DinheiroJsonModule;
import br.com.coronasemijoias.api.cotacoes.TaxaDeCotacao;
import br.com.coronasemijoias.api.seguranca.RotasPublicas;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dois documentos OpenAPI (ADR-0003): "dona" e "vendedor". As rotas de login ficam nos dois.
 * A interface e o JSON só ficam ligados no perfil "dev" (application-dev.properties).
 *
 * <p>Os dois documentos declaram o esquema {@code bearer} (token de acesso JWT) e o exigem em toda
 * operação, menos nas rotas públicas ({@link RotasPublicas}), que ficam com {@code security: []}.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracaoOpenApi {

    /** Nome do esquema de segurança nos documentos. */
    public static final String ESQUEMA_BEARER = "bearer";

    static {
        // Dinheiro aparece como texto no OpenAPI, para os clientes gerados (tarefa 13)
        // não criarem campos "number".
        SpringDocUtils.getConfig().replaceWithSchema(
                BigDecimal.class,
                new StringSchema().format("decimal").pattern(DinheiroJsonModule.PADRAO)
                        .example("123.45"));

        // A cotação tem tipo próprio: texto com 8 casas (ADR-0006, item 8). O padrão de dinheiro
        // (até 2 casas) recusaria a cotação.
        SpringDocUtils.getConfig().replaceWithSchema(
                TaxaDeCotacao.class,
                new StringSchema().format("decimal").pattern(TaxaDeCotacao.PADRAO)
                        .example("5.50130000"));
    }

    @Bean
    GroupedOpenApi grupoDona(RotasPublicas publicas) {
        return GroupedOpenApi.builder()
                .group("dona")
                .pathsToMatch("/api/dona/**", "/api/auth/**")
                .addOpenApiCustomizer(tokenDeAcesso(publicas))
                .build();
    }

    @Bean
    GroupedOpenApi grupoVendedor(RotasPublicas publicas) {
        return GroupedOpenApi.builder()
                .group("vendedor")
                .pathsToMatch("/api/vendedor/**", "/api/auth/**")
                .addOpenApiCustomizer(tokenDeAcesso(publicas))
                .build();
    }

    private static OpenApiCustomizer tokenDeAcesso(RotasPublicas publicas) {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSecuritySchemes(ESQUEMA_BEARER, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Token de acesso devolvido por POST /api/auth/login (vale 15 minutos)."));
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((caminho, item) -> {
                for (Map.Entry<PathItem.HttpMethod, Operation> operacao : item.readOperationsMap().entrySet()) {
                    boolean publica = publicas.ehPublica(operacao.getKey().name(), caminho);
                    List<SecurityRequirement> exigencia = publica
                            ? new ArrayList<>()
                            : new ArrayList<>(List.of(new SecurityRequirement().addList(ESQUEMA_BEARER)));
                    operacao.getValue().setSecurity(exigencia);
                }
            });
        };
    }
}
