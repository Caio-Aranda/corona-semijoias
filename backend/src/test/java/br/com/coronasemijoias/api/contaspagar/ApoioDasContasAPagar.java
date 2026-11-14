package br.com.coronasemijoias.api.contaspagar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Apoio dos testes das contas a pagar, do caixa manual e do fechar semana (tarefa 7.7): fornecedor, compra e requisições. */
abstract class ApoioDasContasAPagar extends TesteDeIntegracao {

    static final String CONTAS = "/api/dona/contas-a-pagar";
    static final String COMPRAS = "/api/dona/compras";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RelogioDeTeste relogio;

    @AfterEach
    void voltarOReloginho() {
        relogio.reiniciar();
    }

    RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    ResultActions postar(String caminho, Object corpo) throws Exception {
        return postarComo(caminho, dona(), corpo);
    }

    ResultActions postarComo(String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo == null ? mapa() : corpo)));
    }

    ResultActions ler(String caminho) throws Exception {
        return mvc.perform(get(caminho).with(dona()));
    }

    JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    UUID fornecedor() throws Exception {
        String nome = "Fornecedor " + UUID.randomUUID().toString().substring(0, 8);
        return UUID.fromString(corpoDe(postar("/api/dona/fornecedores", mapa("razaoSocial", nome)).andExpect(status().isCreated())).get("id").asString());
    }

    /** Uma compra de uma peça só, de quantidade 1, no custo dado: o total da compra é o custo. Devolve o id. */
    UUID compra(UUID fornecedor, String total) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", mapa("nome", "Cat " + sufixo)).andExpect(status().isCreated()))
                .get("id").asString());
        UUID peca = UUID.fromString(corpoDe(postar("/api/dona/produtos", mapa("nome", "Brinco " + sufixo, "categoriaId", categoria,
                "precoVenda", "900.00", "precoCusto", "1.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "C-" + sufixo, "tipo", "LOJA", "principal", true)))).andExpect(status().isCreated()))
                .get("id").asString());
        UUID id = UUID.randomUUID();
        postar(COMPRAS, mapa("id", id, "fornecedorId", fornecedor, "dataCompra", "2026-11-01", "numero", "NF " + sufixo,
                "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "custoUnitario", total)))).andExpect(status().isCreated());
        return id;
    }

    ResultActions gerar(UUID compra, UUID id, Integer parcelas, LocalDate primeiro) throws Exception {
        return postar(COMPRAS + "/" + compra + "/contas-a-pagar", mapa("id", id, "parcelas", parcelas, "primeiroVencimento", primeiro));
    }

    /** Gera as contas de uma compra nova e devolve os ids das parcelas, em ordem. */
    List<UUID> contasDe(UUID compra, int parcelas, LocalDate primeiro) throws Exception {
        JsonNode r = corpoDe(gerar(compra, UUID.randomUUID(), parcelas, primeiro).andExpect(status().isCreated()));
        return r.valueStream().map(c -> UUID.fromString(c.get("id").asString())).toList();
    }

    ResultActions pagar(UUID conta, UUID id, String valor, String forma) throws Exception {
        return postar(CONTAS + "/" + conta + "/pagamentos", mapa("id", id, "valor", valor, "forma", forma));
    }

    String situacaoDaConta(UUID conta) {
        return jdbc.queryForObject("SELECT status || ':' || valor_pago || ':' || valor_cancelado FROM corona.contas_pagar WHERE id = ?", String.class, conta);
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
