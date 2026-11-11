package br.com.coronasemijoias.api.documentacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Achado M1 da auditoria: dois tipos com o mesmo nome simples viravam um esquema só no OpenAPI, e os clientes gerados
 * saíam com o tipo errado. O nome de esquema não pode se repetir entre classes diferentes.
 */
@ActiveProfiles("dev")
class NomesUnicosDosEsquemasTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void duasClassesDoProjetoNuncaDaoOMesmoNomeDeEsquema() {
        Set<String> repetidos = NomesUnicosDosEsquemas.nomesRepetidos();
        assertThat(repetidos).as("há nomes repetidos para o teste ter o que conferir").isNotEmpty();
        Map<String, List<String>> porNome = new TreeMap<>();
        for (Class<?> c : NomesUnicosDosEsquemas.classesDoProjeto()) {
            if (c.isAnonymousClass() || c.isLocalClass() || c.isSynthetic() || !c.isRecord() && !c.isEnum()) {
                continue;
            }
            porNome.computeIfAbsent(NomesUnicosDosEsquemas.nomeDoEsquema(c, repetidos), k -> new ArrayList<>())
                    .add(c.getName());
        }
        porNome.values().removeIf(v -> v.size() < 2);
        assertThat(porNome).as("nomes de esquema repetidos entre classes diferentes").isEmpty();
    }

    @Test
    void oDocumentoNaoMisturaOsEsquemasQueTinhamONomeIgual() throws Exception {
        JsonNode vendedor = documento("vendedor").get("components").get("schemas");
        assertThat(vendedor.has("Linha")).isFalse();
        assertThat(vendedor.has("Peca")).isFalse();
        assertThat(vendedor.has("ConsultasDaMaletaDoVendedorPeca")).isTrue();
        assertThat(vendedor.get("ConsultasDaMaletaDoVendedorPeca").get("properties").has("codigo")).isTrue();
        JsonNode dona = documento("dona").get("components").get("schemas");
        assertThat(dona.has("Pedido")).isFalse();
        assertThat(dona.has("Resposta")).isFalse();
    }

    @Test
    void oContratoDoVendedorNaoTemEstoqueDaLojaNemEmMaletasEODaDonaTem() throws Exception {
        String vendedor = mvc.perform(get("/v3/api-docs/vendedor")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(vendedor).doesNotContain("emMaletas").doesNotContain("vendedoresEmMaletas").doesNotContain("estoqueAtual");
        String dona = mvc.perform(get("/v3/api-docs/dona")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dona).contains("emMaletas").contains("vendedoresEmMaletas");
    }

    private JsonNode documento(String grupo) throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs/" + grupo)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }
}
