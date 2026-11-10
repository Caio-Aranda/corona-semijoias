package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.seguranca.PropriedadesDeSeguranca;
import br.com.coronasemijoias.api.seguranca.RotasPublicas;
import br.com.coronasemijoias.api.seguranca.VerificadorDeRotas;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("dev")
class OpenApiDesenvolvimentoTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    ApplicationContext contexto;

    @Autowired
    RotasPublicas publicas;

    @Autowired
    PropriedadesDeSeguranca propriedades;

    private JsonNode documento(String caminho) throws Exception {
        String corpo = mvc.perform(get(caminho))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(corpo);
    }

    @Test
    void osDoisGruposExistem() throws Exception {
        String configuracao = documento("/v3/api-docs/swagger-config").toString();
        assertThat(configuracao).contains("\"name\":\"dona\"").contains("\"name\":\"vendedor\"");
    }

    @Test
    void grupoDonaTemSuasRotasMaisALogin() throws Exception {
        JsonNode paths = documento("/v3/api-docs/dona").get("paths");
        assertThat(paths.propertyNames()).contains("/api/dona/teste/dinheiro", "/api/auth/teste")
                .doesNotContain("/api/vendedor/teste/dinheiro");
    }

    @Test
    void grupoVendedorTemSuasRotasMaisALogin() throws Exception {
        JsonNode paths = documento("/v3/api-docs/vendedor").get("paths");
        assertThat(paths.propertyNames()).contains("/api/vendedor/teste/dinheiro", "/api/auth/teste")
                .doesNotContain("/api/dona/teste/dinheiro");
    }

    @Test
    void dinheiroApareceComoTextoNosDoisGrupos() throws Exception {
        for (String grupo : new String[] {"dona", "vendedor"}) {
            JsonNode valor = documento("/v3/api-docs/" + grupo)
                    .get("components").get("schemas").get("ValorDeTeste").get("properties").get("valor");
            assertThat(valor.get("type").asString()).as(grupo).isEqualTo("string");
            assertThat(valor.get("format").asString()).as(grupo).isEqualTo("decimal");
            assertThat(valor.get("pattern").asString()).as(grupo).isEqualTo("^-?\\d{1,12}(\\.\\d{1,2})?$");
        }
    }

    @Test
    void interfaceDoSwaggerAbre() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void loginEEuNosDoisGruposComOEsquemaBearer() throws Exception {
        for (String grupo : new String[] {"dona", "vendedor"}) {
            JsonNode documento = documento("/v3/api-docs/" + grupo);
            JsonNode bearer = documento.get("components").get("securitySchemes").get("bearer");
            assertThat(bearer.get("type").asString()).as(grupo).isEqualTo("http");
            assertThat(bearer.get("scheme").asString()).as(grupo).isEqualTo("bearer");
            assertThat(bearer.get("bearerFormat").asString()).as(grupo).isEqualTo("JWT");

            JsonNode paths = documento.get("paths");
            // O login não exige token; o resto exige.
            JsonNode login = paths.get("/api/auth/login").get("post");
            assertThat(login.get("security").isArray()).as(grupo).isTrue();
            assertThat(login.get("security").isEmpty()).as(grupo).isTrue();
            JsonNode eu = paths.get("/api/auth/eu").get("get");
            assertThat(eu.get("security").get(0).has("bearer")).as(grupo).isTrue();
        }
    }

    @Test
    void grupoVendedorNaoTemNadaDaDona() throws Exception {
        String documento = documento("/v3/api-docs/vendedor").toString();
        assertThat(documento).doesNotContain("/api/dona");
    }

    /**
     * ADR-0003, item 2: o documento do vendedor não tem custo nem texto que cite o outro aplicativo.
     * "Corona Gestão" aqui já pegou uma descrição que vazava para o código gerado do Corona Vendas.
     */
    @Test
    void grupoVendedorNaoTemCustoNemMarcadoresDaDona() throws Exception {
        String documento = documento("/v3/api-docs/vendedor").toString();
        assertThat(documento).doesNotContain("preco_custo", "precoCusto", "custo", "owner-app",
                "api-client-dona", "Corona Gestão");
    }

    @Test
    void comADocumentacaoLigadaTodaRotaTemPerfilOuEhPublica() {
        // No perfil dev entram as rotas da documentação: elas estão na lista pública.
        assertThat(VerificadorDeRotas.violacoes(VerificadorDeRotas.rotasDo(contexto), publicas,
                propriedades.grupos(), Set.of("ADMIN", "VENDEDOR"))).isEmpty();
        assertThat(VerificadorDeRotas.rotasDo(contexto).keySet().stream().flatMap(i -> i.getPatternValues().stream()))
                .anyMatch(p -> p.startsWith("/v3/api-docs"));
    }
}
