package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
