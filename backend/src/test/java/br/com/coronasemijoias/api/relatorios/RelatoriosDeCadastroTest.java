package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relatórios de cadastro (tarefa 1.12, ESPECIFICACAO §12 e §13): massa conhecida, resultado calculado
 * à mão e a conciliação "total do rodapé = soma das linhas = contagem do detalhamento".
 * Cada teste usa um texto único na busca, porque o banco é compartilhado entre os testes.
 */
class RelatoriosDeCadastroTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/relatorios/cadastros/";
    /** EAN-13 certo e diferente a cada chamada (o banco é compartilhado e o código não pode repetir). */
    private static String eanValido() {
        String base = String.format("%012d", java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            soma += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return base + (10 - soma % 10) % 10;
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static String marca() {
        return "Zq" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], pares[i + 1]);
        }
        return m;
    }

    private JsonNode ler(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode criarVia(String caminho, Map<String, Object> corpo) throws Exception {
        MockHttpServletRequestBuilder pedido = post(caminho).with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
        return ler(mvc.perform(pedido).andExpect(status().isCreated()));
    }

    private JsonNode relatorio(String nome, String... consulta) throws Exception {
        MockHttpServletRequestBuilder pedido = get(BASE + nome).with(usuarios.comoDona());
        for (int i = 0; i < consulta.length; i += 2) {
            pedido.param(consulta[i], consulta[i + 1]);
        }
        return ler(mvc.perform(pedido).andExpect(status().isOk()));
    }

    private static String[] com(String[] base, String... mais) {
        List<String> todos = new ArrayList<>(List.of(base));
        todos.addAll(List.of(mais));
        return todos.toArray(new String[0]);
    }

    private static JsonNode total(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (chave.equals(t.get("chave").asString())) {
                return t;
            }
        }
        throw new AssertionError("Total sem a chave " + chave + " em " + relatorio.get("totais"));
    }

    /**
     * Conciliação (§13): percorre todas as páginas; a contagem das linhas = total do relatório = total
     * "todos" do rodapé; cada total do rodapé = a contagem do seu detalhamento; e, quando há valor, o
     * valor do rodapé = soma das linhas.
     */
    private void conciliar(String nome, String campoDeValor, String chaveDoValor, String... filtros) throws Exception {
        List<JsonNode> linhas = new ArrayList<>();
        JsonNode primeira = null;
        for (int pagina = 0;; pagina++) {
            JsonNode r = relatorio(nome, com(filtros, "pagina", String.valueOf(pagina), "tamanho", "2"));
            if (primeira == null) {
                primeira = r;
            }
            r.get("itens").forEach(linhas::add);
            if (r.get("itens").isEmpty() || linhas.size() >= r.get("total").asLong()) {
                break;
            }
        }
        long total = primeira.get("total").asLong();
        assertThat(linhas).as("linhas de " + nome).hasSize((int) total);
        assertThat(total(primeira, "todos").get("quantidade").asLong()).isEqualTo(total);
        for (JsonNode t : primeira.get("totais")) {
            JsonNode detalhe = relatorio(nome, com(filtros, "recorte", t.get("chave").asString(), "tamanho", "100"));
            assertThat(detalhe.get("total").asLong()).as("detalhamento de " + t.get("chave").asString())
                    .isEqualTo(t.get("quantidade").asLong());
            assertThat(detalhe.get("itens")).hasSize((int) Math.min(100, t.get("quantidade").asLong()));
        }
        if (campoDeValor != null) {
            BigDecimal soma = BigDecimal.ZERO;
            for (JsonNode linha : linhas) {
                if (!linha.get(campoDeValor).isNull()) {
                    soma = soma.add(new BigDecimal(linha.get(campoDeValor).asString()));
                }
            }
            assertThat(new BigDecimal(total(primeira, chaveDoValor).get("valorEmReais").asString()))
                    .as("valor do rodapé de " + nome).isEqualByComparingTo(soma);
        }
    }

    // --- permissão ---

    @Test
    void vendedorRecebe403EmTodosOsRelatorios() throws Exception {
        for (String nome : List.of("vendedores", "clientes", "fornecedores", "produtos", "produtos-sem-codigo-de-barras",
                "codigos-em-conflito", "aniversariantes", "alteracoes-de-produtos")) {
            mvc.perform(get(BASE + nome).with(usuarios.comoVendedor())).andExpect(status().isForbidden());
        }
    }

    // --- vendedores ---

    @Test
    void vendedoresComLimiteRestricaoSituacaoEPraca() throws Exception {
        String m = marca();
        UUID praca = UUID.fromString(criarVia("/api/dona/pracas", mapa("nome", "Praça " + m)).get("id").asString());
        UUID restricao = UUID.fromString(
                criarVia("/api/dona/restricoes", mapa("codigo", "R" + m, "descricao", "Atraso", "bloqueiaRemessa", true))
                        .get("id").asString());
        criarVia("/api/dona/vendedores", mapa("nome", m + " A", "limiteConsignacao", "1000.00", "pracaId", praca,
                "comissaoPercentual", "30.00"));
        criarVia("/api/dona/vendedores", mapa("nome", m + " B", "limiteConsignacao", "250.50", "restricaoId", restricao));
        JsonNode c = criarVia("/api/dona/vendedores", mapa("nome", m + " C", "limiteConsignacao", "100.00"));
        jdbc.update("UPDATE corona.vendedores SET ativo = false WHERE id = ?::uuid", c.get("id").asString());

        JsonNode ativos = relatorio("vendedores", "busca", m);
        assertThat(ativos.get("total").asLong()).isEqualTo(2);
        assertThat(total(ativos, "limite").get("valorEmReais").asString()).isEqualTo("1250.50");
        assertThat(total(ativos, "com-restricao").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(ativos, "bloqueados").get("quantidade").asLong()).isEqualTo(1);

        JsonNode todos = relatorio("vendedores", "busca", m, "situacao", "TODOS");
        assertThat(todos.get("total").asLong()).isEqualTo(3);
        assertThat(total(todos, "limite").get("valorEmReais").asString()).isEqualTo("1350.50");
        assertThat(total(todos, "inativos").get("quantidade").asLong()).isEqualTo(1);
        assertThat(relatorio("vendedores", "busca", m, "situacao", "INATIVOS").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("vendedores", "busca", m, "situacao", "TODOS", "pracaId", praca.toString())
                .get("total").asLong()).isEqualTo(1);

        JsonNode b = relatorio("vendedores", "busca", m + " B").get("itens").get(0);
        assertThat(b.get("restricao").asString()).contains("Atraso");
        assertThat(b.get("bloqueiaRemessa").asBoolean()).isTrue();
        assertThat(b.get("limiteConsignacao").asString()).isEqualTo("250.50");

        conciliar("vendedores", "limiteConsignacao", "limite", "busca", m, "situacao", "TODOS");
    }

    @Test
    void periodoFiltraPelaDataDoCadastro() throws Exception {
        String m = marca();
        criarVia("/api/dona/vendedores", mapa("nome", m + " A", "limiteConsignacao", "10.00"));
        LocalDate hoje = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(relatorio("vendedores", "busca", m, "de", hoje.minusDays(1).toString(), "ate", hoje.plusDays(1).toString())
                .get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("vendedores", "busca", m, "ate", hoje.minusDays(2).toString()).get("total").asLong())
                .isZero();
        mvc.perform(get(BASE + "vendedores").param("de", hoje.toString()).param("ate", hoje.minusDays(3).toString())
                .with(usuarios.comoDona())).andExpect(status().isBadRequest());
    }

    // --- clientes ---

    @Test
    void clientesPorVendedorEComExclusaoLogica() throws Exception {
        String m = marca();
        UUID vendedor = UUID.fromString(criarVia("/api/dona/vendedores", mapa("nome", m + " V")).get("id").asString());
        criarVia("/api/dona/clientes", mapa("nome", m + " Ana", "vendedorResponsavelId", vendedor, "telefone", "11999"));
        criarVia("/api/dona/clientes", mapa("nome", m + " Bia", "vendedorResponsavelId", vendedor));
        criarVia("/api/dona/clientes", mapa("nome", m + " Cleo"));
        JsonNode apagada = criarVia("/api/dona/clientes", mapa("nome", m + " Dani"));
        jdbc.update("UPDATE corona.clientes SET deleted_at = now() WHERE id = ?::uuid", apagada.get("id").asString());

        JsonNode r = relatorio("clientes", "busca", m);
        assertThat(r.get("total").asLong()).isEqualTo(3);
        assertThat(total(r, "com-vendedor").get("quantidade").asLong()).isEqualTo(2);
        assertThat(total(r, "sem-vendedor").get("quantidade").asLong()).isEqualTo(1);
        assertThat(relatorio("clientes", "busca", m, "vendedorId", vendedor.toString()).get("total").asLong())
                .isEqualTo(2);
        conciliar("clientes", null, null, "busca", m);
    }

    // --- fornecedores ---

    @Test
    void fornecedoresComESemProdutos() throws Exception {
        String m = marca();
        UUID com = UUID.fromString(criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " Com")).get("id").asString());
        criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " Sem"));
        UUID cat = UUID.fromString(criarVia("/api/dona/categorias", mapa("nome", "Cat " + m)).get("id").asString());
        criarProduto(m + " P1", cat, com, "10.00", "30.00", m + "-1", "LOJA");

        JsonNode r = relatorio("fornecedores", "busca", m);
        assertThat(r.get("total").asLong()).isEqualTo(2);
        assertThat(total(r, "com-produtos").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(r, "sem-produtos").get("quantidade").asLong()).isEqualTo(1);
        conciliar("fornecedores", null, null, "busca", m);
    }

    private JsonNode criarProduto(String nome, UUID categoria, UUID fornecedor, String custo, String venda, String codigo,
            String tipo) throws Exception {
        Map<String, Object> cod = mapa("codigo", codigo, "tipo", tipo, "principal", true);
        if ("FORNECEDOR".equals(tipo)) {
            cod.put("fornecedorId", fornecedor);
        }
        Map<String, Object> corpo = mapa("nome", nome, "categoriaId", categoria, "precoVenda", venda, "precoCusto", custo,
                "codigos", List.of(cod));
        if (fornecedor != null) {
            corpo.put("fornecedorId", fornecedor);
        }
        return criarVia("/api/dona/produtos", corpo);
    }

    // --- produtos (com custo) ---

    @Test
    void produtosComCustoESomas() throws Exception {
        String m = marca();
        UUID cat = UUID.fromString(criarVia("/api/dona/categorias", mapa("nome", "Cat " + m)).get("id").asString());
        UUID forn = UUID.fromString(criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " F")).get("id").asString());
        criarProduto(m + " Anel", cat, forn, "10.00", "30.00", m + "-A", "LOJA");
        criarProduto(m + " Brinco", cat, forn, "5.25", "20.50", m + "-B", "LOJA");
        JsonNode inativo = criarProduto(m + " Colar", cat, null, "1.00", "9.99", m + "-C", "LOJA");
        mvc.perform(post("/api/dona/produtos/" + inativo.get("id").asString() + "/inativar").with(usuarios.comoDona()))
                .andExpect(status().isOk());

        JsonNode r = relatorio("produtos", "busca", m);
        assertThat(r.get("total").asLong()).isEqualTo(2);
        assertThat(total(r, "preco-venda").get("valorEmReais").asString()).isEqualTo("50.50");
        assertThat(total(r, "custo").get("valorEmReais").asString()).isEqualTo("15.25");
        JsonNode anel = relatorio("produtos", "busca", m + " Anel").get("itens").get(0);
        assertThat(anel.get("precoCusto").asString()).isEqualTo("10.00");
        assertThat(anel.get("codigoPrincipal").asString()).isEqualTo(m + "-A");
        assertThat(anel.get("categoria").asString()).isEqualTo("Cat " + m);

        assertThat(relatorio("produtos", "busca", m, "situacao", "TODOS").get("total").asLong()).isEqualTo(3);
        assertThat(relatorio("produtos", "busca", m, "situacao", "TODOS", "fornecedorId", forn.toString())
                .get("total").asLong()).isEqualTo(2);
        conciliar("produtos", "precoVenda", "preco-venda", "busca", m, "situacao", "TODOS");
        conciliar("produtos", "precoCusto", "custo", "busca", m, "situacao", "TODOS");
    }

    // --- sem código de barras ---

    @Test
    void produtosSemCodigoDeBarras() throws Exception {
        String m = marca();
        UUID cat = UUID.fromString(criarVia("/api/dona/categorias", mapa("nome", "Cat " + m)).get("id").asString());
        UUID forn = UUID.fromString(criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " F")).get("id").asString());
        criarProduto(m + " SoLoja", cat, forn, "1.00", "2.00", m + "-L", "LOJA");
        criarProduto(m + " ComFornecedor", cat, forn, "1.00", "2.00", m + "-F", "FORNECEDOR");
        criarProduto(m + " ComBarras", cat, forn, "1.00", "2.00", eanValido(), "EAN13");

        JsonNode r = relatorio("produtos-sem-codigo-de-barras", "busca", m);
        assertThat(r.get("total").asLong()).isEqualTo(2);
        assertThat(total(r, "so-codigo-da-loja").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(r, "com-codigo-do-fornecedor").get("quantidade").asLong()).isEqualTo(1);
        conciliar("produtos-sem-codigo-de-barras", null, null, "busca", m);
    }

    // --- códigos em conflito ---

    @Test
    void codigosQuaseIguaisEEan13Invalido() throws Exception {
        String m = marca().toLowerCase();
        UUID cat = UUID.fromString(criarVia("/api/dona/categorias", mapa("nome", "Cat " + m)).get("id").asString());
        UUID f1 = UUID.fromString(criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " F1")).get("id").asString());
        UUID f2 = UUID.fromString(criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " F2")).get("id").asString());
        // Parecidos só por traço, espaço e maiúscula: o servidor aceita, o relatório avisa.
        criarProduto(m + " P1", cat, f1, "1.00", "2.00", m + "-AB-12", "FORNECEDOR");
        criarProduto(m + " P2", cat, f2, "1.00", "2.00", m.toUpperCase() + " AB12", "FORNECEDOR");
        // Mesmo fornecedor: só "quase igual".
        criarProduto(m + " P3", cat, f1, "1.00", "2.00", m + "-ZZ-9", "LOJA");
        criarProduto(m + " P4", cat, f1, "1.00", "2.00", m + " ZZ9", "LOJA");
        // EAN-13 com poucos números (o servidor deixa gravar; a tela avisa) e outro certo.
        criarProduto(m + " P5", cat, f1, "1.00", "2.00", "78910001", "EAN13");
        criarProduto(m + " P6", cat, f1, "1.00", "2.00", eanValido(), "EAN13");

        JsonNode r = relatorio("codigos-em-conflito", "busca", m, "tamanho", "100");
        assertThat(total(r, "fornecedores-diferentes").get("quantidade").asLong()).isEqualTo(2);
        assertThat(total(r, "quase-iguais").get("quantidade").asLong()).isEqualTo(2);
        assertThat(total(r, "ean13-invalido").get("quantidade").asLong()).isEqualTo(1);
        assertThat(r.get("total").asLong()).isEqualTo(5);
        List<String> motivos = new ArrayList<>();
        r.get("itens").forEach(i -> motivos.add(i.get("motivo").asString()));
        assertThat(motivos).containsOnly("FORNECEDORES_DIFERENTES", "QUASE_IGUAL", "EAN13_INVALIDO");
        conciliar("codigos-em-conflito", null, null, "busca", m);
    }

    // --- aniversariantes ---

    @Test
    void aniversariantesDoMesSemDeslocarODia() throws Exception {
        String m = marca();
        criarVia("/api/dona/vendedores", mapa("nome", m + " Fim", "dataNascimento", "1990-03-31"));
        criarVia("/api/dona/vendedores", mapa("nome", m + " Inicio", "dataNascimento", "1985-03-01"));
        criarVia("/api/dona/vendedores", mapa("nome", m + " Abril", "dataNascimento", "1992-04-10"));
        criarVia("/api/dona/vendedores", mapa("nome", m + " Sem data"));

        JsonNode marco = relatorio("aniversariantes", "busca", m, "mes", "3");
        assertThat(marco.get("total").asLong()).isEqualTo(2);
        assertThat(marco.get("itens").get(0).get("dia").asInt()).isEqualTo(1);
        assertThat(marco.get("itens").get(0).get("dataNascimento").asString()).isEqualTo("1985-03-01");
        assertThat(marco.get("itens").get(1).get("dia").asInt()).isEqualTo(31);
        assertThat(marco.get("itens").get(1).get("dataNascimento").asString()).isEqualTo("1990-03-31");
        assertThat(relatorio("aniversariantes", "busca", m, "mes", "4").get("total").asLong()).isEqualTo(1);
        mvc.perform(get(BASE + "aniversariantes").param("mes", "13").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
        conciliar("aniversariantes", null, null, "busca", m, "mes", "3");
    }

    @Test
    void aniversariantesIncluemClientesComFiltroQuemEPracaDoVendedor() throws Exception {
        String m = marca();
        UUID praca = UUID.fromString(criarVia("/api/dona/pracas", mapa("nome", "Praça " + m)).get("id").asString());
        UUID vendedor = UUID.fromString(criarVia("/api/dona/vendedores",
                mapa("nome", m + " Vendedora", "dataNascimento", "1980-03-15", "pracaId", praca)).get("id").asString());
        criarVia("/api/dona/clientes", mapa("nome", m + " Cliente Ana", "dataNascimento", "1995-03-02",
                "vendedorResponsavelId", vendedor));
        criarVia("/api/dona/clientes", mapa("nome", m + " Cliente Bia", "dataNascimento", "1990-03-20"));
        criarVia("/api/dona/clientes", mapa("nome", m + " Cliente Abril", "dataNascimento", "1990-04-20"));
        criarVia("/api/dona/clientes", mapa("nome", m + " Cliente sem data"));
        JsonNode inativa = criarVia("/api/dona/clientes", mapa("nome", m + " Cliente Inativa", "dataNascimento", "1991-03-05"));
        mvc.perform(post("/api/dona/clientes/" + inativa.get("id").asString() + "/inativar").with(usuarios.comoDona()))
                .andExpect(status().isOk());

        JsonNode todos = relatorio("aniversariantes", "busca", m, "mes", "3");
        assertThat(todos.get("total").asLong()).isEqualTo(3);
        assertThat(total(todos, "vendedores").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(todos, "clientes").get("quantidade").asLong()).isEqualTo(2);
        assertThat(todos.get("itens").get(0).get("tipo").asString()).isEqualTo("CLIENTE");
        assertThat(todos.get("itens").get(1).get("tipo").asString()).isEqualTo("VENDEDOR");
        assertThat(relatorio("aniversariantes", "busca", m, "mes", "3", "quem", "CLIENTES").get("total").asLong()).isEqualTo(2);
        assertThat(relatorio("aniversariantes", "busca", m, "mes", "3", "quem", "VENDEDORES").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("aniversariantes", "busca", m, "mes", "3", "situacao", "INATIVOS").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("aniversariantes", "busca", m, "mes", "3", "situacao", "TODOS").get("total").asLong()).isEqualTo(4);
        // A praça do cliente é a do vendedor responsável.
        assertThat(relatorio("aniversariantes", "busca", m, "mes", "3", "pracaId", praca.toString()).get("total").asLong())
                .isEqualTo(2);
        mvc.perform(get(BASE + "aniversariantes").param("quem", "TALVEZ").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
        conciliar("aniversariantes", null, null, "busca", m, "mes", "3");
        conciliar("aniversariantes", null, null, "busca", m, "mes", "3", "quem", "CLIENTES", "situacao", "TODOS");
    }

    @Test
    void clientesEFornecedoresComFiltroDeSituacao() throws Exception {
        String m = marca();
        JsonNode ativo = criarVia("/api/dona/clientes", mapa("nome", m + " Ativo"));
        JsonNode inativo = criarVia("/api/dona/clientes", mapa("nome", m + " Inativo", "dataNascimento", "1990-01-01"));
        JsonNode fAtivo = criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " Forn Ativo"));
        JsonNode fInativo = criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " Forn Inativo"));
        for (String caminho : List.of("/api/dona/clientes/" + inativo.get("id").asString(),
                "/api/dona/fornecedores/" + fInativo.get("id").asString())) {
            mvc.perform(post(caminho + "/inativar").with(usuarios.comoDona())).andExpect(status().isOk());
        }
        assertThat(ativo.get("id").asString()).isNotBlank();
        assertThat(fAtivo.get("id").asString()).isNotBlank();
        for (String nome : List.of("clientes", "fornecedores")) {
            assertThat(relatorio(nome, "busca", m).get("total").asLong()).as(nome + " padrão").isEqualTo(1);
            assertThat(relatorio(nome, "busca", m, "situacao", "ATIVOS").get("total").asLong()).isEqualTo(1);
            JsonNode inativos = relatorio(nome, "busca", m, "situacao", "INATIVOS");
            assertThat(inativos.get("total").asLong()).isEqualTo(1);
            assertThat(inativos.get("itens").get(0).get("ativo").asBoolean()).isFalse();
            assertThat(relatorio(nome, "busca", m, "situacao", "TODOS").get("total").asLong()).isEqualTo(2);
            conciliar(nome, null, null, "busca", m, "situacao", "TODOS");
            mvc.perform(get(BASE + nome).param("situacao", "QUALQUER").with(usuarios.comoDona()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(relatorio("clientes", "busca", m, "situacao", "INATIVOS").get("itens").get(0).get("dataNascimento")
                .asString()).isEqualTo("1990-01-01");
    }

    // --- alterações de produtos (auditoria) ---

    @Test
    void alteracoesDeProdutosPelaAuditoria() throws Exception {
        String m = marca();
        UUID cat = UUID.fromString(criarVia("/api/dona/categorias", mapa("nome", "Cat " + m)).get("id").asString());
        JsonNode p = criarProduto(m + " Anel", cat, null, "10.00", "30.00", m + "-A", "LOJA");
        MockHttpServletRequestBuilder edicao = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/dona/produtos/" + p.get("id").asString()).with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("nome", m + " Anel", "categoriaId", cat, "precoVenda", "35.00",
                        "precoCusto", "10.00", "version", p.get("version").asInt())));
        mvc.perform(edicao).andExpect(status().isOk());

        JsonNode r = relatorio("alteracoes-de-produtos", "busca", m);
        assertThat(total(r, "criados").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(r, "preco-venda-alterado").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(r, "preco-custo-alterado").get("quantidade").asLong()).isZero();
        assertThat(r.get("total").asLong()).isEqualTo(2);
        assertThat(r.get("itens").get(0).get("produto").asString()).isEqualTo(m + " Anel");
        assertThat(r.get("itens").get(0).get("quem").asString()).isNotBlank();
        LocalDate hoje = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(relatorio("alteracoes-de-produtos", "busca", m, "ate", hoje.minusDays(2).toString()).get("total").asLong())
                .isZero();
        conciliar("alteracoes-de-produtos", null, null, "busca", m);
    }

    // --- erros ---

    // --- filtros que faltavam (tarefa 1.13, §12) ---

    @Test
    void vendedoresPorFaixaDeLimiteEComRestricao() throws Exception {
        String m = marca();
        UUID restricao = UUID.fromString(
                criarVia("/api/dona/restricoes", mapa("codigo", "R" + m, "descricao", "Atraso", "bloqueiaRemessa", false))
                        .get("id").asString());
        criarVia("/api/dona/vendedores", mapa("nome", m + " A", "limiteConsignacao", "100.00"));
        criarVia("/api/dona/vendedores", mapa("nome", m + " B", "limiteConsignacao", "500.00", "restricaoId", restricao));
        criarVia("/api/dona/vendedores", mapa("nome", m + " C", "limiteConsignacao", "900.00"));

        assertThat(relatorio("vendedores", "busca", m, "limiteDe", "200", "limiteAte", "900").get("total").asLong())
                .isEqualTo(2);
        assertThat(relatorio("vendedores", "busca", m, "limiteDe", "500.00", "limiteAte", "500.00").get("total").asLong())
                .isEqualTo(1);
        assertThat(relatorio("vendedores", "busca", m, "limiteAte", "99.99").get("total").asLong()).isZero();
        JsonNode com = relatorio("vendedores", "busca", m, "comRestricao", "SIM");
        assertThat(com.get("total").asLong()).isEqualTo(1);
        assertThat(total(com, "limite").get("valorEmReais").asString()).isEqualTo("500.00");
        assertThat(relatorio("vendedores", "busca", m, "comRestricao", "NAO").get("total").asLong()).isEqualTo(2);
        conciliar("vendedores", "limiteConsignacao", "limite", "busca", m, "limiteDe", "200", "comRestricao", "SIM");
        conciliar("vendedores", "limiteConsignacao", "limite", "busca", m, "limiteDe", "50", "limiteAte", "600");

        for (String[] ruim : new String[][] { { "limiteDe", "abc" }, { "limiteAte", "-1" }, { "comRestricao", "TALVEZ" } }) {
            mvc.perform(get(BASE + "vendedores").param(ruim[0], ruim[1]).with(usuarios.comoDona()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "vendedores").param("limiteDe", "10").param("limiteAte", "5").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void produtosPorEstoquePrecoEFoto() throws Exception {
        String m = marca();
        UUID cat = UUID.fromString(criarVia("/api/dona/categorias", mapa("nome", "Cat " + m)).get("id").asString());
        JsonNode zerado = criarProduto(m + " Zerado", cat, null, "1.00", "10.00", m + "-Z", "LOJA");
        JsonNode baixo = criarProduto(m + " Baixo", cat, null, "1.00", "20.00", m + "-B", "LOJA");
        JsonNode bom = criarProduto(m + " Bom", cat, null, "1.00", "30.00", m + "-G", "LOJA");
        jdbc.update("UPDATE corona.produtos SET estoque_minimo = 5, estoque_atual = 2 WHERE id = ?::uuid",
                baixo.get("id").asString());
        jdbc.update("UPDATE corona.produtos SET estoque_minimo = 5, estoque_atual = 9 WHERE id = ?::uuid",
                bom.get("id").asString());
        jdbc.update("""
                INSERT INTO corona.produtos_fotos (id, produto_id, arquivo_hash, principal, ordem, largura, altura,
                    tamanho_bytes, chave_miniatura, chave_media, chave_grande)
                VALUES (?::uuid, ?::uuid, ?, true, 0, 10, 10, 10, 'a', 'b', 'c')""", UUID.randomUUID().toString(),
                bom.get("id").asString(), "a".repeat(64));

        assertThat(relatorio("produtos", "busca", m, "estoque", "ZERADO").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("produtos", "busca", m, "estoque", "ABAIXO_DO_MINIMO").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("produtos", "busca", m, "estoque", "COM_ESTOQUE").get("total").asLong()).isEqualTo(2);
        assertThat(relatorio("produtos", "busca", m, "precoDe", "15", "precoAte", "30").get("total").asLong())
                .isEqualTo(2);
        assertThat(relatorio("produtos", "busca", m, "foto", "SEM").get("total").asLong()).isEqualTo(2);
        assertThat(relatorio("produtos", "busca", m, "foto", "COM").get("total").asLong()).isEqualTo(1);
        JsonNode comFoto = relatorio("produtos", "busca", m, "foto", "COM");
        assertThat(comFoto.get("itens").get(0).get("nome").asString()).isEqualTo(m + " Bom");
        assertThat(total(comFoto, "preco-venda").get("valorEmReais").asString()).isEqualTo("30.00");
        assertThat(zerado.get("id").asString()).isNotBlank();
        conciliar("produtos", "precoVenda", "preco-venda", "busca", m, "foto", "SEM", "precoDe", "5");
        conciliar("produtos", "precoCusto", "custo", "busca", m, "estoque", "COM_ESTOQUE");

        for (String[] ruim : new String[][] { { "estoque", "MUITO" }, { "foto", "TALVEZ" }, { "precoDe", "x" } }) {
            mvc.perform(get(BASE + "produtos").param(ruim[0], ruim[1]).with(usuarios.comoDona()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void fornecedoresEClientesComFiltroDeVinculo() throws Exception {
        String m = marca();
        UUID com = UUID.fromString(criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " Com")).get("id").asString());
        criarVia("/api/dona/fornecedores", mapa("razaoSocial", m + " Sem"));
        UUID cat = UUID.fromString(criarVia("/api/dona/categorias", mapa("nome", "Cat " + m)).get("id").asString());
        criarProduto(m + " P1", cat, com, "10.00", "30.00", m + "-1", "LOJA");
        assertThat(relatorio("fornecedores", "busca", m, "comProdutos", "SIM").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("fornecedores", "busca", m, "comProdutos", "NAO").get("total").asLong()).isEqualTo(1);
        conciliar("fornecedores", null, null, "busca", m, "comProdutos", "SIM");

        UUID vendedor = UUID.fromString(criarVia("/api/dona/vendedores", mapa("nome", m + " V")).get("id").asString());
        criarVia("/api/dona/clientes", mapa("nome", m + " Ana", "vendedorResponsavelId", vendedor));
        criarVia("/api/dona/clientes", mapa("nome", m + " Bia"));
        assertThat(relatorio("clientes", "busca", m, "comVendedor", "SIM").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("clientes", "busca", m, "comVendedor", "NAO").get("total").asLong()).isEqualTo(1);
        conciliar("clientes", null, null, "busca", m, "comVendedor", "NAO");
        mvc.perform(get(BASE + "clientes").param("comVendedor", "TALVEZ").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recorteDesconhecidoEPaginaInvalidaViramErroClaro() throws Exception {
        mvc.perform(get(BASE + "vendedores").param("recorte", "nao-existe").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "vendedores").param("tamanho", "101").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "vendedores").param("situacao", "QUALQUER").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
    }
}
