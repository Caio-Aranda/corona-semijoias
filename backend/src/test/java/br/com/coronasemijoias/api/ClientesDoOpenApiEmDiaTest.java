package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tarefa 13 (ADR-0003, item 3): o documento OpenAPI de cada grupo fica versionado dentro do pacote do
 * cliente gerado. Este teste compara o documento atual com o arquivo e falha se divergirem. Para
 * regravar os arquivos: {@code npm run api:clientes} (passa {@code -Dcorona.openapi.gravar=true}).
 */
@ActiveProfiles("dev")
class ClientesDoOpenApiEmDiaTest extends TesteDeIntegracao {

    private static final boolean GRAVAR = Boolean.getBoolean("corona.openapi.gravar");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void documentoDaDonaEstaEmDia() throws Exception {
        conferir("dona", "api-client-dona");
    }

    @Test
    void documentoDoVendedorEstaEmDia() throws Exception {
        conferir("vendedor", "api-client-vendedor");
    }

    private void conferir(String grupo, String pacote) throws Exception {
        String corpo = mvc.perform(get("/v3/api-docs/" + grupo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> documento = json.readValue(corpo, Map.class);
        // O endereço do servidor depende da porta do teste e não faz parte do contrato.
        documento.remove("servers");
        String texto = json.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .withDefaultPrettyPrinter().writeValueAsString(documento).replace("\r\n", "\n") + "\n";

        Path arquivo = Path.of("..", "packages", pacote, "openapi.json");
        if (GRAVAR) {
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, texto, StandardCharsets.UTF_8);
            return;
        }
        assertThat(arquivo).as("Falta o arquivo %s. Rode: npm run api:clientes", arquivo).exists();
        String atual = Files.readString(arquivo, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(atual)
                .as("O OpenAPI do grupo %s mudou e o cliente está desatualizado. Rode: npm run api:clientes", grupo)
                .isEqualTo(texto);
    }
}
