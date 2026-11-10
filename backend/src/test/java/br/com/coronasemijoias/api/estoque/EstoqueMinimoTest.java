package br.com.coronasemijoias.api.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Estoque mínimo (tarefa 2.4): lista dos produtos que precisam de reposição e o aviso da tela inicial da dona. */
class EstoqueMinimoTest extends TesteDeIntegracao {

    private static final String ROTA = "/api/dona/estoque/abaixo-do-minimo";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ServicoDeEstoque estoque;

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    private ResultActions enviar(String caminho, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions ler(String caminho) throws Exception {
        return mvc.perform(get(caminho).with(usuarios.comoDona()));
    }

    private JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private UUID categoria() throws Exception {
        return UUID.fromString(corpoDe(enviar("/api/dona/categorias", mapa("nome", "Cat " + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private UUID fornecedor() throws Exception {
        return UUID.fromString(corpoDe(enviar("/api/dona/fornecedores",
                mapa("razaoSocial", "Forn " + UUID.randomUUID().toString().substring(0, 8))).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID produto(UUID categoria, UUID fornecedor, int minimo, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> corpo = mapa("nome", "Colar " + sufixo, "categoriaId", categoria, "precoVenda", "70.00",
                "precoCusto", "10.00", "estoqueMinimo", minimo,
                "codigos", List.of(mapa("codigo", "M-" + sufixo, "tipo", "LOJA", "principal", true)));
        if (fornecedor != null) {
            corpo.put("fornecedorId", fornecedor);
        }
        UUID id = UUID.fromString(corpoDe(enviar("/api/dona/produtos", corpo).andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(UUID.randomUUID(), id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return id;
    }

    private JsonNode lista(String filtros) throws Exception {
        return corpoDe(ler(ROTA + filtros).andExpect(status().isOk()));
    }

    private static List<String> ids(JsonNode lista) {
        return lista.get("itens").valueStream().map(i -> i.get("produto").get("id").asString()).toList();
    }

    @Test
    void listaSoQuemEstaNoMinimoOuAbaixoComMinimoMaiorQueZeroEMostraQuantoFalta() throws Exception {
        UUID cat = categoria();
        UUID abaixo = produto(cat, null, 10, 3);
        UUID igual = produto(cat, null, 5, 5);
        UUID acima = produto(cat, null, 5, 6);
        UUID semMinimo = produto(cat, null, 0, 0);
        JsonNode l = lista("?categoriaId=" + cat);
        assertThat(ids(l)).containsExactlyInAnyOrder(abaixo.toString(), igual.toString());
        assertThat(ids(l)).doesNotContain(acima.toString(), semMinimo.toString());
        assertThat(l.get("total").asInt()).isEqualTo(2);
        // A que mais falta vem primeiro.
        JsonNode primeiro = l.get("itens").get(0);
        assertThat(primeiro.get("produto").get("id").asString()).isEqualTo(abaixo.toString());
        assertThat(primeiro.get("saldo").asInt()).isEqualTo(3);
        assertThat(primeiro.get("minimo").asInt()).isEqualTo(10);
        assertThat(primeiro.get("falta").asInt()).isEqualTo(7);
        assertThat(l.get("itens").get(1).get("falta").asInt()).isEqualTo(0);
        assertThat(primeiro.get("produto").has("fotoPrincipal")).isTrue();
    }

    @Test
    void filtrosPorFornecedorBuscaEPaginacaoEInativoNaoEntra() throws Exception {
        UUID cat = categoria();
        UUID forn = fornecedor();
        UUID a = produto(cat, forn, 4, 1);
        UUID b = produto(cat, forn, 4, 2);
        UUID outroForn = produto(cat, null, 4, 0);
        UUID inativo = produto(cat, forn, 4, 0);
        mvc.perform(post("/api/dona/produtos/" + inativo + "/inativar").with(usuarios.comoDona())).andExpect(status().isOk());
        assertThat(ids(lista("?fornecedorId=" + forn))).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(ids(lista("?categoriaId=" + cat))).contains(outroForn.toString()).doesNotContain(inativo.toString());
        String codigo = jdbc.queryForObject("SELECT codigo FROM corona.produto_codigos WHERE produto_id = ?", String.class, a);
        assertThat(ids(lista("?busca=" + codigo))).containsExactly(a.toString());
        JsonNode pagina = lista("?categoriaId=" + cat + "&tamanho=2&pagina=1");
        assertThat(pagina.get("itens")).hasSize(1);
        assertThat(pagina.get("total").asInt()).isEqualTo(3);
        ler(ROTA + "?tamanho=0").andExpect(status().isOk());
    }

    @Test
    void entradaTiraDaListaESaidaColoca() throws Exception {
        UUID cat = categoria();
        UUID p = produto(cat, null, 5, 2);
        assertThat(ids(lista("?categoriaId=" + cat))).contains(p.toString());
        estoque.registrar(new Pedido(UUID.randomUUID(), p, TipoDeMovimento.ENTRADA, 10, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        assertThat(ids(lista("?categoriaId=" + cat))).doesNotContain(p.toString());
        estoque.registrar(new Pedido(UUID.randomUUID(), p, TipoDeMovimento.SAIDA, 8, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        assertThat(ids(lista("?categoriaId=" + cat))).contains(p.toString());
    }

    @Test
    void oAvisoDaTelaInicialContaOsProdutosEFicaVazioQuandoNaoHaNenhum() throws Exception {
        jdbc.update("UPDATE corona.produtos SET estoque_minimo = 0");
        JsonNode vazio = corpoDe(ler("/api/dona/avisos").andExpect(status().isOk()));
        assertThat(vazio.get("avisos").valueStream().map(a -> a.get("tipo").asString())).doesNotContain("ESTOQUE_BAIXO");

        UUID cat = categoria();
        produto(cat, null, 5, 1);
        produto(cat, null, 5, 0);
        JsonNode aviso = corpoDe(ler("/api/dona/avisos").andExpect(status().isOk())).get("avisos").valueStream()
                .filter(a -> a.get("tipo").asString().equals("ESTOQUE_BAIXO")).findFirst().orElseThrow();
        assertThat(aviso.get("titulo").asString()).contains("2 produtos estão acabando");
        assertThat(aviso.get("texto").asString().toLowerCase()).doesNotContain("estoque_minimo", "moviment", "invent");
        assertThat(lista("").get("total").asInt()).isEqualTo(2);
    }

    @Test
    void oEstoqueMinimoSeEditaPelaApiDeProdutosEOvalorNegativoERecusado() throws Exception {
        UUID cat = categoria();
        UUID p = produto(cat, null, 2, 10);
        JsonNode atual = corpoDe(ler("/api/dona/produtos/" + p).andExpect(status().isOk()));
        Map<String, Object> corpo = mapa("nome", atual.get("nome").asString(), "categoriaId", cat, "precoVenda", "70.00",
                "precoCusto", "10.00", "estoqueMinimo", 20, "version", atual.get("version").asInt());
        mvc.perform(put("/api/dona/produtos/" + p).with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isOk());
        assertThat(ids(lista("?categoriaId=" + cat))).contains(p.toString());
        corpo.put("estoqueMinimo", -1);
        corpo.put("version", atual.get("version").asInt() + 1);
        mvc.perform(put("/api/dona/produtos/" + p).with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isBadRequest());
    }

    @Test
    void vendedorRecebe403ESemLoginRecebe401() throws Exception {
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        mvc.perform(get(ROTA).with(vendedor)).andExpect(status().isForbidden());
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
    }
}
