package br.com.coronasemijoias.api.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liberação de restrição na auditoria (§11; tarefa 8.9): trocar ou tirar a restrição de um vendedor, e mudar o que a restrição
 * bloqueia, passam a deixar registro na mesma transação, sem mudar a regra. Dados únicos desta classe.
 */
class RestricoesNaAuditoriaTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode enviar(boolean criar, String caminho, RequestPostProcessor quem, Map<String, Object> corpo) throws Exception {
        var pedido = (criar ? post(caminho) : put(caminho)).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo));
        var resposta = mvc.perform(pedido).andExpect(status().is2xxSuccessful()).andReturn().getResponse();
        return json.readTree(resposta.getContentAsString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], pares[i + 1]);
        }
        return m;
    }

    private List<Map<String, Object>> registros(String tabela, String registroId, String acao) {
        return jdbc.queryForList("""
                SELECT ator_usuario_id, antes::text AS antes, depois::text AS depois FROM corona.auditoria
                 WHERE tabela = ? AND registro_id = ?::uuid AND acao = ?""", tabela, registroId, acao);
    }

    @Test
    void trocarETirarARestricaoDoVendedorFicaNaAuditoria() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        RequestPostProcessor eladona = UsuariosDeTeste.comToken(usuarios.token(dona));
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String r1 = enviar(true, "/api/dona/restricoes", eladona,
                mapa("codigo", "R1-" + sufixo, "descricao", "Atraso", "bloqueiaRemessa", true)).get("id").asString();
        String r2 = enviar(true, "/api/dona/restricoes", eladona,
                mapa("codigo", "R2-" + sufixo, "descricao", "Documento", "bloqueiaRemessa", false)).get("id").asString();
        JsonNode v = enviar(true, "/api/dona/vendedores", eladona,
                mapa("nome", "Vendedora " + sufixo, "semLimite", true, "restricaoId", r1));
        String id = v.get("id").asString();

        // Criar já com restrição não é uma liberação: nada registrado ainda.
        assertThat(registros("vendedores", id, "VENDEDOR_RESTRICAO_ALTERADA")).isEmpty();

        // Editar sem mexer na restrição: nada registrado.
        JsonNode igual = enviar(false, "/api/dona/vendedores/" + id, eladona,
                mapa("nome", "Vendedora " + sufixo + " b", "semLimite", true, "restricaoId", r1, "version", v.get("version").asInt()));
        assertThat(registros("vendedores", id, "VENDEDOR_RESTRICAO_ALTERADA")).isEmpty();

        // Trocar R1 por R2.
        JsonNode trocada = enviar(false, "/api/dona/vendedores/" + id, eladona,
                mapa("nome", "Vendedora " + sufixo + " b", "semLimite", true, "restricaoId", r2, "version", igual.get("version").asInt()));
        List<Map<String, Object>> troca = registros("vendedores", id, "VENDEDOR_RESTRICAO_ALTERADA");
        assertThat(troca).hasSize(1);
        assertThat(troca.get(0).get("ator_usuario_id").toString()).isEqualTo(dona.id().toString());
        assertThat((String) troca.get(0).get("antes")).contains("R1-" + sufixo);
        assertThat((String) troca.get(0).get("depois")).contains("R2-" + sufixo);

        // Tirar a restrição (liberar).
        enviar(false, "/api/dona/vendedores/" + id, eladona,
                mapa("nome", "Vendedora " + sufixo + " b", "semLimite", true, "version", trocada.get("version").asInt()));
        List<Map<String, Object>> todas = registros("vendedores", id, "VENDEDOR_RESTRICAO_ALTERADA");
        assertThat(todas).hasSize(2);
        assertThat(todas.stream().map(m -> (String) m.get("depois")).toList()).anyMatch(d -> d.contains("Nenhuma"));
    }

    @Test
    void mudarOQueARestricaoBloqueiaFicaNaAuditoria() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        RequestPostProcessor eladona = UsuariosDeTeste.comToken(usuarios.token(dona));
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        JsonNode r = enviar(true, "/api/dona/restricoes", eladona,
                mapa("codigo", "R3-" + sufixo, "descricao", "Atraso", "bloqueiaRemessa", true));
        String id = r.get("id").asString();
        // Sem mudança: nada registrado.
        JsonNode igual = enviar(false, "/api/dona/restricoes/" + id, eladona,
                mapa("codigo", "R3-" + sufixo, "descricao", "Atraso", "bloqueiaRemessa", true, "version", r.get("version").asInt()));
        assertThat(registros("restricoes", id, "RESTRICAO_ALTERADA")).isEmpty();
        // Liberar a remessa.
        enviar(false, "/api/dona/restricoes/" + id, eladona,
                mapa("codigo", "R3-" + sufixo, "descricao", "Atraso", "bloqueiaRemessa", false, "version", igual.get("version").asInt()));
        List<Map<String, Object>> alteradas = registros("restricoes", id, "RESTRICAO_ALTERADA");
        assertThat(alteradas).hasSize(1);
        assertThat((String) alteradas.get(0).get("antes")).contains("\"bloqueia_remessa\": true");
        assertThat((String) alteradas.get(0).get("depois")).contains("\"bloqueia_remessa\": false");
        assertThat(alteradas.get(0).get("ator_usuario_id").toString()).isEqualTo(dona.id().toString());
    }
}
