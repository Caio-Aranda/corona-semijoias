package br.com.coronasemijoias.api.cadastros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ativo e inativo em todos os cadastros e data de nascimento do cliente (tarefa 1.14). */
class AtivoEInativoDosCadastrosTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static String unico(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    private ResultActions enviar(String metodo, String caminho, boolean dona, Object corpo) throws Exception {
        MockHttpServletRequestBuilder pedido = switch (metodo) {
            case "POST" -> post(caminho);
            case "PUT" -> put(caminho);
            case "DELETE" -> delete(caminho);
            default -> get(caminho);
        };
        pedido.with(dona ? usuarios.comoDona() : usuarios.comoVendedor());
        if (corpo != null) {
            pedido.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
        }
        return mvc.perform(pedido);
    }

    private JsonNode criar(String recurso, Map<String, Object> corpo) throws Exception {
        String resposta = enviar("POST", "/api/dona/" + recurso, true, corpo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resposta);
    }

    private record Cadastro(String recurso, String tabela, Map<String, Object> corpo, String buscaPor) {
    }

    private Cadastro[] cadastros() {
        String nome = "Item" + UUID.randomUUID().toString().substring(0, 8);
        return new Cadastro[] {
                new Cadastro("fornecedores", "fornecedores", mapa("razaoSocial", nome), nome),
                new Cadastro("categorias", "categorias", mapa("nome", nome), nome),
                new Cadastro("pracas", "pracas", mapa("nome", nome), nome),
                new Cadastro("restricoes", "restricoes", mapa("codigo", nome, "descricao", "Teste"), nome),
                new Cadastro("clientes", "clientes", mapa("nome", nome), nome)
        };
    }

    @Test
    void novoCadastroNasceAtivoEAparecemNaListaPadrao() throws Exception {
        for (Cadastro c : cadastros()) {
            JsonNode criado = criar(c.recurso(), c.corpo());
            assertThat(criado.get("ativo").asBoolean()).as(c.recurso()).isTrue();
            enviar("GET", "/api/dona/" + c.recurso() + "?busca=" + c.buscaPor().replace(" ", "%20"), true, null)
                    .andExpect(jsonPath("$.total").value(1));
        }
    }

    @Test
    void inativarEReativarMudamAsListasMasNaoApagamNada() throws Exception {
        for (Cadastro c : cadastros()) {
            String base = "/api/dona/" + c.recurso();
            String busca = "busca=" + c.buscaPor().replace(" ", "%20");
            JsonNode criado = criar(c.recurso(), c.corpo());
            String id = criado.get("id").asString();

            enviar("POST", base + "/" + id + "/inativar", true, mapa("version", 1)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ativo").value(false)).andExpect(jsonPath("$.version").value(2));

            enviar("GET", base + "?" + busca, true, null).andExpect(jsonPath("$.total").value(0));
            enviar("GET", base + "?situacao=ATIVOS&" + busca, true, null).andExpect(jsonPath("$.total").value(0));
            enviar("GET", base + "?situacao=INATIVOS&" + busca, true, null).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.itens[0].ativo").value(false));
            enviar("GET", base + "?situacao=TODOS&" + busca, true, null).andExpect(jsonPath("$.total").value(1));
            // A ficha de um inativo continua abrindo.
            enviar("GET", base + "/" + id, true, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ativo").value(false));
            // Não foi excluído.
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM corona." + c.tabela() + " WHERE id = ?::uuid",
                    Boolean.class, id)).as(c.recurso()).isTrue();

            enviar("POST", base + "/" + id + "/reativar", true, mapa("version", 2)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ativo").value(true)).andExpect(jsonPath("$.version").value(3));
            enviar("GET", base + "?situacao=ATIVOS&" + busca, true, null).andExpect(jsonPath("$.total").value(1));
            enviar("GET", base + "?situacao=INATIVOS&" + busca, true, null).andExpect(jsonPath("$.total").value(0));
        }
    }

    @Test
    void situacaoInvalidaVolta400EmPortuguesSimples() throws Exception {
        for (Cadastro c : cadastros()) {
            enviar("GET", "/api/dona/" + c.recurso() + "?situacao=QUALQUER", true, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campos[0].mensagem").value("Escolha ATIVOS, INATIVOS ou TODOS."));
        }
    }

    @Test
    void inativarComVersaoVelhaVolta409() throws Exception {
        for (Cadastro c : cadastros()) {
            String id = criar(c.recurso(), c.corpo()).get("id").asString();
            enviar("POST", "/api/dona/" + c.recurso() + "/" + id + "/inativar", true, mapa("version", 7))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CADASTRO_ALTERADO"));
        }
    }

    @Test
    void inativarSemMandarAVersaoTambemFunciona() throws Exception {
        for (Cadastro c : cadastros()) {
            String id = criar(c.recurso(), c.corpo()).get("id").asString();
            enviar("POST", "/api/dona/" + c.recurso() + "/" + id + "/inativar", true, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ativo").value(false));
        }
    }

    @Test
    void inativarCadastroInexistenteOuExcluidoVolta404() throws Exception {
        for (Cadastro c : cadastros()) {
            String base = "/api/dona/" + c.recurso();
            enviar("POST", base + "/" + UUID.randomUUID() + "/inativar", true, null).andExpect(status().isNotFound());
            String id = criar(c.recurso(), c.corpo()).get("id").asString();
            enviar("DELETE", base + "/" + id, true, null).andExpect(status().isNoContent());
            enviar("POST", base + "/" + id + "/reativar", true, null).andExpect(status().isNotFound());
        }
    }

    @Test
    void inativarQuemEstaEmUsoEhPermitidoEPreservaOVinculo() throws Exception {
        String praca = criar("pracas", mapa("nome", unico("Praça em uso"))).get("id").asString();
        String restricao = criar("restricoes", mapa("codigo", unico("R"), "descricao", "Teste")).get("id").asString();
        JsonNode vendedor = json.readTree(enviar("POST", "/api/dona/vendedores", true,
                mapa("nome", unico("Vendedora"), "semLimite", true, "pracaId", praca, "restricaoId", restricao))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        enviar("POST", "/api/dona/pracas/" + praca + "/inativar", true, null).andExpect(status().isOk());
        enviar("POST", "/api/dona/restricoes/" + restricao + "/inativar", true, null).andExpect(status().isOk());

        enviar("GET", "/api/dona/vendedores/" + vendedor.get("id").asString(), true, null)
                .andExpect(jsonPath("$.pracaId").value(praca)).andExpect(jsonPath("$.restricaoId").value(restricao));
        // E o inativo ainda pode ser ligado de novo ao salvar a ficha como está.
        enviar("PUT", "/api/dona/vendedores/" + vendedor.get("id").asString(), true,
                mapa("nome", vendedor.get("nome").asString(), "semLimite", true, "pracaId", praca, "restricaoId", restricao, "version", 1))
                .andExpect(status().isOk());
    }

    @Test
    void inativarRegistraNaAuditoria() throws Exception {
        for (Cadastro c : cadastros()) {
            String id = criar(c.recurso(), c.corpo()).get("id").asString();
            enviar("POST", "/api/dona/" + c.recurso() + "/" + id + "/inativar", true, null).andExpect(status().isOk());
            enviar("POST", "/api/dona/" + c.recurso() + "/" + id + "/reativar", true, null).andExpect(status().isOk());
            assertThat(jdbc.queryForList(
                    "SELECT acao FROM corona.auditoria WHERE tabela = ? AND registro_id = ?::uuid ORDER BY ocorrida_em, id",
                    String.class, c.tabela(), id)).as(c.recurso()).containsExactly("INATIVADO", "REATIVADO");
        }
    }

    @Test
    void vendedorNaoInativaNemReativa() throws Exception {
        for (Cadastro c : cadastros()) {
            String base = "/api/dona/" + c.recurso() + "/" + UUID.randomUUID();
            enviar("POST", base + "/inativar", false, null).andExpect(status().isForbidden());
            enviar("POST", base + "/reativar", false, null).andExpect(status().isForbidden());
        }
    }

    @Test
    void semanaNaoTemAtivoEInativo() throws Exception {
        enviar("POST", "/api/dona/semanas/" + UUID.randomUUID() + "/inativar", true, null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'corona' AND table_name = 'semanas' AND column_name = 'ativo'""", Integer.class))
                .isZero();
    }

    // --- data de nascimento do cliente ---

    @Test
    void clienteGuardaADataDeNascimentoEEditaComElaOuSemEla() throws Exception {
        JsonNode criado = criar("clientes", mapa("nome", unico("Cliente"), "dataNascimento", "1990-05-17"));
        String id = criado.get("id").asString();
        assertThat(criado.get("dataNascimento").asString()).isEqualTo("1990-05-17");
        enviar("GET", "/api/dona/clientes/" + id, true, null).andExpect(jsonPath("$.dataNascimento").value("1990-05-17"));

        enviar("PUT", "/api/dona/clientes/" + id, true,
                mapa("nome", criado.get("nome").asString(), "dataNascimento", "1991-01-02", "version", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataNascimento").value("1991-01-02"));
        enviar("PUT", "/api/dona/clientes/" + id, true, mapa("nome", criado.get("nome").asString(), "version", 2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataNascimento").doesNotExist());
    }

    @Test
    void dataDeNascimentoNoFuturoOuAbsurdaEhRecusadaComMensagemSimples() throws Exception {
        String amanha = LocalDate.now().plusDays(2).toString();
        enviar("POST", "/api/dona/clientes", true, mapa("nome", unico("Cliente"), "dataNascimento", amanha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("dataNascimento"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("A data de nascimento não pode ser no futuro."));
        enviar("POST", "/api/dona/clientes", true, mapa("nome", unico("Cliente"), "dataNascimento", "1850-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("dataNascimento"));
        // Hoje vale.
        enviar("POST", "/api/dona/clientes", true,
                mapa("nome", unico("Cliente"), "dataNascimento", LocalDate.now().toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void bancoRecusaDataDeNascimentoAbsurda() {
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO corona.clientes (id, nome, data_nascimento) VALUES (?, 'X', DATE '1800-01-01')",
                        UUID.randomUUID()));
    }
}
