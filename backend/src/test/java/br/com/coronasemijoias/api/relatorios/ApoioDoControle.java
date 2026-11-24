package br.com.coronasemijoias.api.relatorios;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Apoio dos testes dos relatórios de Controle (tarefa 8.9): cada classe cria as próprias pessoas e filtra por elas. */
abstract class ApoioDoControle extends TesteDeIntegracao {

    static final String CONTROLE = "/api/dona/relatorios/controle";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    RequestPostProcessor como(UsuarioDeTeste u) {
        return UsuariosDeTeste.comToken(usuarios.token(u));
    }

    ResultActions chamar(String caminho) throws Exception {
        return mvc.perform(get(CONTROLE + caminho).with(dona()));
    }

    JsonNode ler(String caminho) throws Exception {
        return json.readTree(chamar(caminho).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static JsonNode total(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (t.get("chave").asString().equals(chave)) {
                return t;
            }
        }
        throw new AssertionError("Sem o total " + chave);
    }
}
