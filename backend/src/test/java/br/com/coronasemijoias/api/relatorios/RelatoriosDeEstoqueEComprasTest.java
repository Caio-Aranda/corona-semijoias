package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import br.com.coronasemijoias.api.testes.LeituraDoPull;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relatórios de estoque e de compras (tarefa 2.5, §12): massa montada no teste, números conferidos à mão,
 * cada filtro, "exportação = tela" (a exportação é o relatório inteiro, pedido por páginas com os mesmos
 * filtros), permissão, nada de custo nem compra no pull do vendedor, desempenho e mensagens sem termos técnicos.
 */
class RelatoriosDeEstoqueEComprasTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/relatorios/";

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

    @Autowired
    ServicoDePull pull;

    @Autowired
    ConsultasDeEstoqueECompras consultas;

    @Autowired
    RelogioDeTeste relogio;

    @AfterEach
    void reiniciarORelogio() {
        relogio.reiniciar();
    }

    // --- apoio ---

    private static String marca() {
        return "Zr" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], pares[i + 1]);
        }
        return m;
    }

    private JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode criar(String caminho, Map<String, Object> corpo) throws Exception {
        return corpoDe(mvc.perform(post(caminho).with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isCreated()));
    }

    private UUID categoria(String m) throws Exception {
        return UUID.fromString(criar("/api/dona/categorias", mapa("nome", "Cat " + m + UUID.randomUUID().toString().substring(0, 4)))
                .get("id").asString());
    }

    private UUID fornecedor(String nome) throws Exception {
        return UUID.fromString(criar("/api/dona/fornecedores", mapa("razaoSocial", nome)).get("id").asString());
    }

    private UUID produto(String m, String nome, UUID categoria, UUID fornecedor, String custo, int minimo) throws Exception {
        Map<String, Object> corpo = mapa("nome", nome + " " + m, "categoriaId", categoria, "precoVenda", "70.00",
                "estoqueMinimo", minimo, "codigos",
                List.of(mapa("codigo", "R-" + UUID.randomUUID().toString().substring(0, 10), "tipo", "LOJA", "principal", true)));
        if (custo != null) {
            corpo.put("precoCusto", custo);
        }
        if (fornecedor != null) {
            corpo.put("fornecedorId", fornecedor);
        }
        return UUID.fromString(criar("/api/dona/produtos", corpo).get("id").asString());
    }

    private UUID dona() {
        return usuarios.dona().id();
    }

    private ServicoDeEstoque.Resultado mover(UUID produto, TipoDeMovimento tipo, int quantidade, String motivo,
            OrigemDoMovimento origem) {
        return estoque.registrar(new Pedido(UUID.randomUUID(), produto, tipo, quantidade, motivo, origem, null), dona());
    }

    private void entrada(UUID produto, int quantidade) {
        mover(produto, TipoDeMovimento.ENTRADA, quantidade, null, OrigemDoMovimento.MANUAL);
    }

    private JsonNode relatorio(String rota, String... consulta) throws Exception {
        MockHttpServletRequestBuilder pedido = get(BASE + rota).with(usuarios.comoDona());
        for (int i = 0; i < consulta.length; i += 2) {
            pedido.param(consulta[i], consulta[i + 1]);
        }
        return corpoDe(mvc.perform(pedido).andExpect(status().isOk()));
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

    private static BigDecimal valor(JsonNode relatorio, String chave) {
        return new BigDecimal(total(relatorio, chave).get("valorEmReais").asString());
    }

    private static long quantidade(JsonNode relatorio, String chave) {
        return total(relatorio, chave).get("quantidade").asLong();
    }

    private static long pecas(JsonNode relatorio, String chave) {
        return total(relatorio, chave).get("pecas").asLong();
    }

    /**
     * Tela x exportação, mais a conciliação do rodapé: a exportação (todas as páginas de 100) tem as mesmas linhas
     * e o mesmo rodapé que a tela (páginas pequenas, percorridas até o fim); cada total do rodapé = o seu
     * detalhamento; e, se o relatório tem valor, a soma das linhas = o total indicado.
     */
    private JsonNode conferir(String rota, String campoDeValor, String chaveDoValor, String... filtros) throws Exception {
        List<JsonNode> daTela = new ArrayList<>();
        JsonNode primeira = null;
        for (int pagina = 0;; pagina++) {
            JsonNode r = relatorio(rota, com(filtros, "pagina", String.valueOf(pagina), "tamanho", "2"));
            if (primeira == null) {
                primeira = r;
            }
            r.get("itens").forEach(daTela::add);
            if (r.get("itens").isEmpty() || daTela.size() >= r.get("total").asLong()) {
                break;
            }
        }
        JsonNode inteira = relatorio(rota, com(filtros, "pagina", "0", "tamanho", "100"));
        assertThat(daTela).as("linhas de " + rota).hasSize((int) primeira.get("total").asLong());
        assertThat(inteira.get("itens").toString()).as("exportação = tela em " + rota)
                .isEqualTo(json.writeValueAsString(daTela));
        assertThat(inteira.get("totais").toString()).isEqualTo(primeira.get("totais").toString());
        assertThat(quantidade(primeira, "todos")).isEqualTo(primeira.get("total").asLong());
        for (JsonNode t : primeira.get("totais")) {
            JsonNode detalhe = relatorio(rota, com(filtros, "recorte", t.get("chave").asString(), "tamanho", "100"));
            assertThat(detalhe.get("total").asLong()).as("detalhamento de " + t.get("chave").asString())
                    .isEqualTo(t.get("quantidade").asLong());
        }
        if (campoDeValor != null) {
            BigDecimal soma = BigDecimal.ZERO;
            for (JsonNode linha : daTela) {
                if (!linha.get(campoDeValor).isNull()) {
                    soma = soma.add(new BigDecimal(linha.get(campoDeValor).asString()));
                }
            }
            assertThat(valor(primeira, chaveDoValor)).as("soma das linhas de " + rota).isEqualByComparingTo(soma);
        }
        return inteira;
    }

    // --- permissão ---

    @Test
    void vendedorRecebe403ESemLoginRecebe401EmTodosOsRelatorios() throws Exception {
        UUID id = UUID.randomUUID();
        for (String rota : List.of("estoque/posicao-atual", "estoque/movimentacoes", "estoque/abaixo-do-minimo",
                "estoque/parados", "estoque/inventarios", "estoque/conciliacao", "compras/por-compra",
                "compras/por-fornecedor", "compras/por-compra/" + id + "/itens", "compras/historico-de-custo")) {
            mvc.perform(get(BASE + rota).with(usuarios.comoVendedor())).andExpect(status().isForbidden());
            mvc.perform(get(BASE + rota)).andExpect(status().isUnauthorized());
        }
    }

    // --- posição atual ---

    @Test
    void posicaoAtualValorACustoTotalEFiltros() throws Exception {
        String m = marca();
        UUID catA = categoria(m);
        UUID catB = categoria(m);
        UUID forn = fornecedor("Forn " + m);
        UUID a = produto(m, "Anel", catA, forn, "5.00", 0);
        UUID b = produto(m, "Brinco", catB, null, "7.50", 0);
        UUID c = produto(m, "Colar", catA, null, "2.00", 0);
        UUID d = produto(m, "Pulseira", catA, null, "10.00", 0);
        entrada(a, 3);
        entrada(b, 4);
        entrada(d, 2);
        jdbc.update("UPDATE corona.produtos SET ativo = false WHERE id = ?", d);

        // Ativos: 3 x 5,00 + 4 x 7,50 + 0 x 2,00 = 15,00 + 30,00 = 45,00; 7 peças.
        JsonNode ativos = conferir("estoque/posicao-atual", "valorACusto", "valor-a-custo", "busca", m);
        assertThat(ativos.get("total").asLong()).isEqualTo(3);
        assertThat(valor(ativos, "valor-a-custo")).isEqualByComparingTo("45.00");
        assertThat(pecas(ativos, "valor-a-custo")).isEqualTo(7);
        assertThat(quantidade(ativos, "com-saldo")).isEqualTo(2);
        assertThat(quantidade(ativos, "zerados")).isEqualTo(1);
        // Inclui o inativo: + 2 x 10,00.
        JsonNode todos = conferir("estoque/posicao-atual", "valorACusto", "valor-a-custo", "busca", m, "situacao", "TODOS");
        assertThat(valor(todos, "valor-a-custo")).isEqualByComparingTo("65.00");
        assertThat(pecas(todos, "valor-a-custo")).isEqualTo(9);
        assertThat(relatorio("estoque/posicao-atual", "busca", m, "situacao", "INATIVOS").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("estoque/posicao-atual", "busca", m, "categoriaId", catB.toString()).get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("estoque/posicao-atual", "busca", m, "fornecedorId", forn.toString()).get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("estoque/posicao-atual", "produtoId", b.toString()).get("itens").get(0).get("valorACusto").asString())
                .isEqualTo("30.00");
        JsonNode linha = relatorio("estoque/posicao-atual", "produtoId", a.toString()).get("itens").get(0);
        assertThat(linha.has("fotoPrincipalHash")).isTrue();
        assertThat(linha.get("codigo").asString()).startsWith("R-");
        assertThat(c).isNotNull();
    }

    @Test
    void posicaoAtualSemCustoAvisaEOValorNaoInventaCusto() throws Exception {
        String m = marca();
        UUID cat = categoria(m);
        UUID a = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.produtos (id, nome, categoria_id, preco_venda) VALUES (?, ?, ?, 50)", a, "Anel " + m, cat);
        entrada(a, 3);
        JsonNode r = conferir("estoque/posicao-atual", "valorACusto", "valor-a-custo", "busca", m);
        assertThat(quantidade(r, "sem-custo")).isEqualTo(1);
        assertThat(valor(r, "valor-a-custo")).isEqualByComparingTo("0.00");
        assertThat(r.get("itens").get(0).get("custoUnitario").isNull()).isTrue();
    }

    // --- movimentações ---

    @Test
    void movimentacoesTotaisPorTipoEntradasESaidasEFiltros() throws Exception {
        String m = marca();
        UUID cat = categoria(m);
        UUID p = produto(m, "Anel", cat, null, "5.00", 0);
        UUID outro = produto(m, "Brinco", cat, null, "5.00", 0);
        entrada(p, 10);
        ServicoDeEstoque.Resultado saida = mover(p, TipoDeMovimento.SAIDA, 3, null, OrigemDoMovimento.MANUAL);
        mover(p, TipoDeMovimento.AVARIA, 1, "quebrou", OrigemDoMovimento.MANUAL);
        mover(p, TipoDeMovimento.AJUSTE, 2, "contagem", OrigemDoMovimento.AJUSTE_MANUAL);
        estoque.estornar(UUID.randomUUID(), saida.movimento().id(), "lançado errado", dona());
        entrada(outro, 5);

        // Do produto p: +10, -3, -1, +2 e o desfazer da saída (+3): 5 linhas.
        JsonNode r = conferir("estoque/movimentacoes", null, null, "produtoId", p.toString());
        assertThat(r.get("total").asLong()).isEqualTo(5);
        assertThat(pecas(r, "entradas")).isEqualTo(15);
        assertThat(pecas(r, "saidas")).isEqualTo(4);
        assertThat(pecas(r, "tipo-entrada")).isEqualTo(10);
        assertThat(pecas(r, "tipo-saida")).isZero();
        assertThat(pecas(r, "tipo-ajuste")).isEqualTo(2);
        assertThat(pecas(r, "tipo-avaria")).isEqualTo(-1);
        assertThat(relatorio("estoque/movimentacoes", "produtoId", p.toString(), "tipo", "SAIDA").get("total").asLong()).isEqualTo(2);
        assertThat(relatorio("estoque/movimentacoes", "produtoId", p.toString(), "origem", "ESTORNO").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("estoque/movimentacoes", "busca", m).get("total").asLong()).isEqualTo(6);
        assertThat(relatorio("estoque/movimentacoes", "busca", m, "usuarioId", dona().toString()).get("total").asLong()).isEqualTo(6);
        assertThat(relatorio("estoque/movimentacoes", "busca", m, "usuarioId", UUID.randomUUID().toString()).get("total").asLong()).isZero();
        assertThat(relatorio("estoque/movimentacoes", "busca", m, "categoriaId", cat.toString()).get("total").asLong()).isEqualTo(6);
        LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
        assertThat(relatorio("estoque/movimentacoes", "busca", m, "de", hoje.minusDays(1).toString(), "ate", hoje.plusDays(1).toString())
                .get("total").asLong()).isEqualTo(6);
        assertThat(relatorio("estoque/movimentacoes", "busca", m, "ate", hoje.minusDays(2).toString()).get("total").asLong()).isZero();
        mvc.perform(get(BASE + "estoque/movimentacoes").param("de", hoje.toString()).param("ate", hoje.minusDays(3).toString())
                .with(usuarios.comoDona())).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "estoque/movimentacoes").param("tipo", "INVENTADO").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
        JsonNode desfeita = null;
        for (JsonNode l : r.get("itens")) {
            if (l.get("id").asString().equals(saida.movimento().id().toString())) {
                desfeita = l;
            }
        }
        assertThat(desfeita).isNotNull();
        assertThat(desfeita.get("desfeito").asBoolean()).isTrue();
        assertThat(desfeita.get("usuario").asString()).isNotBlank();
    }

    // --- estoque mínimo ---

    @Test
    void abaixoDoMinimoMostraQuantoFalta() throws Exception {
        String m = marca();
        UUID cat = categoria(m);
        UUID m1 = produto(m, "A", cat, null, "1.00", 5);
        UUID m2 = produto(m, "B", cat, null, "1.00", 4);
        UUID m3 = produto(m, "C", cat, null, "1.00", 3);
        UUID m4 = produto(m, "D", cat, null, "1.00", 0);
        UUID m5 = produto(m, "E", cat, null, "1.00", 2);
        entrada(m1, 2);
        entrada(m2, 4);
        entrada(m5, 9);
        JsonNode r = conferir("estoque/abaixo-do-minimo", null, null, "busca", m);
        // A (2 de 5: faltam 3), B (4 de 4: 0) e C (0 de 3: faltam 3). D não tem mínimo; E está folgado.
        assertThat(r.get("total").asLong()).isEqualTo(3);
        assertThat(quantidade(r, "zerados")).isEqualTo(1);
        assertThat(pecas(r, "falta")).isEqualTo(6);
        assertThat(r.get("itens").get(0).get("falta").asInt()).isLessThanOrEqualTo(3);
        assertThat(relatorio("estoque/abaixo-do-minimo", "busca", m, "recorte", "zerados").get("itens").get(0).get("nome").asString())
                .contains("C");
        jdbc.update("UPDATE corona.produtos SET ativo = false WHERE id = ?", m1);
        assertThat(relatorio("estoque/abaixo-do-minimo", "busca", m).get("total").asLong()).isEqualTo(2);
        assertThat(relatorio("estoque/abaixo-do-minimo", "busca", m, "situacao", "TODOS").get("total").asLong()).isEqualTo(3);
        assertThat(m3).isNotNull();
        assertThat(m4).isNotNull();
    }

    // --- parados ---

    @Test
    void produtosParadosSemSaidaHaNDias() throws Exception {
        String m = marca();
        UUID cat = categoria(m);
        UUID p1 = produto(m, "Anel", cat, null, "10.00", 0);
        UUID p2 = produto(m, "Brinco", cat, null, "4.00", 0);
        UUID p3 = produto(m, "Colar", cat, null, "9.00", 0);
        UUID p4 = produto(m, "Pulseira", cat, null, "1.00", 0);
        entrada(p1, 5);
        mover(p1, TipoDeMovimento.SAIDA, 1, null, OrigemDoMovimento.MANUAL);
        entrada(p2, 3);
        entrada(p4, 3);
        mover(p4, TipoDeMovimento.AVARIA, 1, "quebrou", OrigemDoMovimento.MANUAL);

        ConsultasDeEstoqueECompras.Filtros agora = filtrosDeParados(m, 30);
        // Hoje: o produto é novo (cadastrado há menos de 30 dias) e a saída é de agora: ninguém está parado.
        assertThat(consultas.parados(agora).total()).isZero();

        relogio.avancar(Duration.ofDays(40));
        RelatorioDeCadastro<ConsultasDeEstoqueECompras.LinhaDeProdutoParado> r = consultas.parados(agora);
        // Parados há 30 dias: p1 (saiu há 40), p2 (nunca saiu) e p4 (perda não é saída). p3 não tem saldo.
        assertThat(r.total()).isEqualTo(3);
        // p1: 4 peças x 10,00 = 40,00; p2: 3 x 4,00 = 12,00; p4: 2 x 1,00 = 2,00.
        assertThat(r.totais().stream().filter(t -> t.chave().equals("valor-a-custo")).findFirst().orElseThrow().valorEmReais())
                .isEqualByComparingTo("54.00");
        assertThat(r.totais().stream().filter(t -> t.chave().equals("valor-a-custo")).findFirst().orElseThrow().pecas()).isEqualTo(9);
        assertThat(r.totais().stream().filter(t -> t.chave().equals("nunca-saiu")).findFirst().orElseThrow().quantidade()).isEqualTo(2);
        ConsultasDeEstoqueECompras.LinhaDeProdutoParado primeiro = r.itens().get(0);
        assertThat(primeiro.produtoId()).isEqualTo(p1);
        assertThat(primeiro.ultimaSaidaEm()).isNotNull();
        assertThat(primeiro.diasSemSair()).isGreaterThanOrEqualTo(39);
        // Com 60 dias o produto cadastrado há "40" dias ainda não conta.
        assertThat(consultas.parados(filtrosDeParados(m, 60)).total()).isZero();
        // Uma saída desfeita não conta como saída: p5 fica parado.
        assertThat(p3).isNotNull();
    }

    private static ConsultasDeEstoqueECompras.Filtros filtrosDeParados(String busca, int dias) {
        return new ConsultasDeEstoqueECompras.Filtros(busca, null, null, null, null, null, null, null, null, null, dias,
                null, null, null, 0, 100);
    }

    @Test
    void paradosRecusaNumeroDeDiasInvalidoNaRota() throws Exception {
        mvc.perform(get(BASE + "estoque/parados").param("dias", "0").with(usuarios.comoDona())).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "estoque/parados").param("dias", "5000").with(usuarios.comoDona())).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "estoque/parados").param("dias", "30").with(usuarios.comoDona())).andExpect(status().isOk());
    }

    // --- inventário e divergências ---

    @Test
    void inventarioMostraEsperadoContadoDiferencaEAjuste() throws Exception {
        String m = marca();
        UUID cat = categoria(m);
        UUID i1 = produto(m, "Anel", cat, null, "1.00", 0);
        UUID i2 = produto(m, "Brinco", cat, null, "1.00", 0);
        UUID i3 = produto(m, "Colar", cat, null, "1.00", 0);
        entrada(i1, 5);
        entrada(i2, 3);
        entrada(i3, 4);
        jdbc.update("UPDATE corona.inventarios SET situacao = 'CANCELADO', cancelado_em = now(), cancelado_por = ? "
                + "WHERE situacao = 'EM_ANDAMENTO'", dona());
        UUID inv = UUID.fromString(criar("/api/dona/inventarios", mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA",
                "escopoId", cat)).get("id").asString());
        criar("/api/dona/inventarios/" + inv + "/contagens", mapa("id", UUID.randomUUID(), "produtoId", i1, "contado", 4));
        criar("/api/dona/inventarios/" + inv + "/contagens", mapa("id", UUID.randomUUID(), "produtoId", i2, "contado", 5));
        mvc.perform(post("/api/dona/inventarios/" + inv + "/fechar").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"zerarNaoLidos\":false}")).andExpect(status().isOk());

        JsonNode r = conferir("estoque/inventarios", null, null, "inventarioId", inv.toString());
        assertThat(r.get("total").asLong()).isEqualTo(3);
        assertThat(quantidade(r, "com-divergencia")).isEqualTo(2);
        assertThat(quantidade(r, "nao-lidos")).isEqualTo(1);
        assertThat(pecas(r, "sobras")).isEqualTo(2);
        assertThat(pecas(r, "faltas")).isEqualTo(1);
        for (JsonNode l : r.get("itens")) {
            String id = l.get("produtoId").asString();
            if (id.equals(i1.toString())) {
                assertThat(l.get("esperado").asInt()).isEqualTo(5);
                assertThat(l.get("contado").asInt()).isEqualTo(4);
                assertThat(l.get("diferenca").asInt()).isEqualTo(-1);
                assertThat(l.get("ajuste").asInt()).isEqualTo(-1);
            } else if (id.equals(i2.toString())) {
                assertThat(l.get("diferenca").asInt()).isEqualTo(2);
                assertThat(l.get("ajuste").asInt()).isEqualTo(2);
            } else {
                assertThat(l.get("contado").isNull()).isTrue();
                assertThat(l.get("ajuste").isNull()).isTrue();
            }
        }
        assertThat(relatorio("estoque/inventarios", "inventarioId", inv.toString(), "divergencia", "SIM").get("total").asLong()).isEqualTo(2);
        assertThat(relatorio("estoque/inventarios", "inventarioId", inv.toString(), "situacao", "FECHADO").get("total").asLong()).isEqualTo(3);
        assertThat(relatorio("estoque/inventarios", "inventarioId", inv.toString(), "situacao", "EM_ANDAMENTO").get("total").asLong()).isZero();
        assertThat(relatorio("estoque/inventarios", "inventarioId", inv.toString(), "busca", "Anel").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("estoque/inventarios", "inventarioId", inv.toString(), "categoriaId", cat.toString()).get("total").asLong()).isEqualTo(3);
    }

    // --- conciliação ---

    @Test
    void conciliacaoVemVaziaEMostraOQueDiverge() throws Exception {
        String m = marca();
        UUID p = produto(m, "Anel", categoria(m), null, "1.00", 0);
        entrada(p, 4);
        assertThat(relatorio("estoque/conciliacao").get("total").asLong()).isZero();
        jdbc.update("UPDATE corona.produtos SET estoque_atual = estoque_atual + 1 WHERE id = ?", p);
        try {
            JsonNode r = conferir("estoque/conciliacao", null, null, "busca", m);
            assertThat(r.get("total").asLong()).isEqualTo(1);
            JsonNode l = r.get("itens").get(0);
            assertThat(l.get("saldoGuardado").asInt()).isEqualTo(5);
            assertThat(l.get("somaDosMovimentos").asLong()).isEqualTo(4);
            assertThat(l.get("diferenca").asLong()).isEqualTo(-1);
        } finally {
            jdbc.update("UPDATE corona.produtos SET estoque_atual = estoque_atual - 1 WHERE id = ?", p);
        }
        assertThat(relatorio("estoque/conciliacao").get("total").asLong()).isZero();
    }

    // --- compras ---

    private record Compras(String marca, UUID f1, UUID f2, UUID catA, UUID catB, UUID a, UUID b, UUID c1, UUID c2, UUID c3,
            UUID c4) {
    }

    private UUID comprar(UUID fornecedor, String data, String numero, Object... itens) throws Exception {
        UUID id = UUID.randomUUID();
        criar("/api/dona/compras", mapa("id", id, "fornecedorId", fornecedor, "dataCompra", data, "numero", numero,
                "itens", List.of(itens)));
        return id;
    }

    private static Map<String, Object> item(UUID produto, int quantidade, String custo) {
        return mapa("produtoId", produto, "quantidade", quantidade, "custoUnitario", custo);
    }

    /** Compra 1 (F1): A 3 x 3,34 + B 2 x 10,10 = 30,22. Compra 2 (F1): A 1 x 5,55. Compra 3 (F2): B 4 x 2,50 = 10,00. Compra 4 (F1): A 10 x 1,00, desfeita. */
    private Compras massaDeCompras() throws Exception {
        String m = marca();
        UUID f1 = fornecedor("Forn " + m + " A");
        UUID f2 = fornecedor("Forn " + m + " B");
        UUID catA = categoria(m);
        UUID catB = categoria(m);
        UUID a = produto(m, "Anel", catA, null, "5.00", 0);
        UUID b = produto(m, "Brinco", catB, null, "7.00", 0);
        UUID c1 = comprar(f1, "2026-11-01", m + "-1", item(a, 3, "3.34"), item(b, 2, "10.10"));
        UUID c2 = comprar(f1, "2026-11-05", m + "-2", item(a, 1, "5.55"));
        UUID c3 = comprar(f2, "2026-11-05", m + "-3", item(b, 4, "2.50"));
        UUID c4 = comprar(f1, "2026-11-07", m + "-4", item(a, 10, "1.00"));
        mvc.perform(post("/api/dona/compras/" + c4 + "/desfazer").with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("id", UUID.randomUUID(), "motivo", "lançada em duplicidade"))))
                .andExpect(status().isCreated());
        return new Compras(m, f1, f2, catA, catB, a, b, c1, c2, c3, c4);
    }

    @Test
    void comprasPorCompraTotalGeralSemAsEstornadasEFiltros() throws Exception {
        Compras x = massaDeCompras();
        String[] daF1 = {"fornecedorId", x.f1().toString()};
        // Registradas de F1: 30,22 + 5,55 = 35,77; peças 5 + 1 = 6. A estornada (10,00) não entra.
        JsonNode reg = conferir("compras/por-compra", "total", "registradas", daF1);
        assertThat(reg.get("total").asLong()).isEqualTo(2);
        assertThat(valor(reg, "registradas")).isEqualByComparingTo("35.77");
        assertThat(pecas(reg, "registradas")).isEqualTo(6);
        assertThat(quantidade(reg, "estornadas")).isZero();
        // Mostrando as estornadas: aparecem marcadas e continuam FORA do total.
        JsonNode todas = relatorio("compras/por-compra", com(daF1, "situacao", "TODAS"));
        assertThat(todas.get("total").asLong()).isEqualTo(3);
        assertThat(valor(todas, "registradas")).isEqualByComparingTo("35.77");
        assertThat(valor(todas, "estornadas")).isEqualByComparingTo("10.00");
        boolean marcada = false;
        for (JsonNode l : todas.get("itens")) {
            if (l.get("id").asString().equals(x.c4().toString())) {
                marcada = l.get("estornada").asBoolean() && "ESTORNADA".equals(l.get("situacao").asString())
                        && !l.get("motivoDoEstorno").isNull();
            }
        }
        assertThat(marcada).isTrue();
        conferir("compras/por-compra", null, null, com(daF1, "situacao", "TODAS"));
        assertThat(relatorio("compras/por-compra", com(daF1, "situacao", "ESTORNADAS")).get("total").asLong()).isEqualTo(1);
        // Período (datas de calendário, inclusive).
        assertThat(valor(relatorio("compras/por-compra", com(daF1, "de", "2026-11-02", "ate", "2026-11-06")), "registradas"))
                .isEqualByComparingTo("5.55");
        assertThat(relatorio("compras/por-compra", com(daF1, "ate", "2026-11-01")).get("total").asLong()).isEqualTo(1);
        // Categoria e produto: compras que têm o produto.
        assertThat(relatorio("compras/por-compra", "busca", x.marca(), "categoriaId", x.catB().toString()).get("total").asLong())
                .isEqualTo(2);
        assertThat(relatorio("compras/por-compra", "busca", x.marca(), "produtoId", x.a().toString()).get("total").asLong())
                .isEqualTo(2);
        assertThat(relatorio("compras/por-compra", "busca", x.marca() + "-3").get("total").asLong()).isEqualTo(1);
        assertThat(relatorio("compras/por-compra", "usuarioId", dona().toString(), "fornecedorId", x.f2().toString())
                .get("total").asLong()).isEqualTo(1);
    }

    @Test
    void comprasPorFornecedorTotalPorFornecedorEGeralFechamComOsItens() throws Exception {
        Compras x = massaDeCompras();
        JsonNode r = conferir("compras/por-fornecedor", "total", "geral", "busca", x.marca());
        assertThat(r.get("total").asLong()).isEqualTo(2);
        JsonNode f1 = null;
        JsonNode f2 = null;
        for (JsonNode l : r.get("itens")) {
            if (l.get("fornecedorId").asString().equals(x.f1().toString())) {
                f1 = l;
            } else {
                f2 = l;
            }
        }
        assertThat(f1.get("compras").asLong()).isEqualTo(2);
        assertThat(f1.get("pecas").asLong()).isEqualTo(6);
        assertThat(f1.get("total").asString()).isEqualTo("35.77");
        assertThat(f2.get("total").asString()).isEqualTo("10.00");
        assertThat(valor(r, "geral")).isEqualByComparingTo("45.77");
        assertThat(pecas(r, "geral")).isEqualTo(10);
        // Soma dos itens = soma dos totais das compras registradas (o total que o servidor gravou).
        BigDecimal dasCompras = jdbc.queryForObject("SELECT sum(total) FROM corona.compras WHERE situacao = 'REGISTRADA' "
                + "AND fornecedor_id IN (?, ?)", BigDecimal.class, x.f1(), x.f2());
        assertThat(valor(r, "geral")).isEqualByComparingTo(dasCompras);
        // Com as estornadas: aparecem em colunas próprias e o total não muda.
        JsonNode todas = relatorio("compras/por-fornecedor", "busca", x.marca(), "situacao", "TODAS");
        assertThat(valor(todas, "geral")).isEqualByComparingTo("45.77");
        assertThat(valor(todas, "estornadas")).isEqualByComparingTo("10.00");
        for (JsonNode l : todas.get("itens")) {
            if (l.get("fornecedorId").asString().equals(x.f1().toString())) {
                assertThat(l.get("comprasEstornadas").asLong()).isEqualTo(1);
                assertThat(l.get("totalEstornado").asString()).isEqualTo("10.00");
                assertThat(l.get("total").asString()).isEqualTo("35.77");
            }
        }
        conferir("compras/por-fornecedor", "total", "geral", "busca", x.marca(), "situacao", "TODAS");
        // Categoria A: só os itens do Anel (3 x 3,34 + 1 x 5,55 = 15,57), só em F1.
        JsonNode daCategoria = conferir("compras/por-fornecedor", "total", "geral", "busca", x.marca(), "categoriaId", x.catA().toString());
        assertThat(daCategoria.get("total").asLong()).isEqualTo(1);
        assertThat(valor(daCategoria, "geral")).isEqualByComparingTo("15.57");
        assertThat(relatorio("compras/por-fornecedor", "busca", x.marca(), "produtoId", x.b().toString(), "fornecedorId", x.f2().toString())
                .get("total").asLong()).isEqualTo(1);
        assertThat(valor(relatorio("compras/por-fornecedor", "busca", x.marca(), "de", "2026-11-05", "ate", "2026-11-05"), "geral"))
                .isEqualByComparingTo("15.55");
    }

    @Test
    void itensDeUmaCompraSomamOTotalDaCompra() throws Exception {
        Compras x = massaDeCompras();
        JsonNode r = conferir("compras/por-compra/" + x.c1() + "/itens", "total", "total");
        assertThat(r.get("total").asLong()).isEqualTo(2);
        assertThat(valor(r, "total")).isEqualByComparingTo("30.22");
        assertThat(pecas(r, "total")).isEqualTo(5);
        JsonNode compra = relatorio("compras/por-compra", "fornecedorId", x.f1().toString(), "busca", x.marca() + "-1").get("itens").get(0);
        assertThat(new BigDecimal(compra.get("total").asString())).isEqualByComparingTo(valor(r, "total"));
        JsonNode primeiro = r.get("itens").get(0);
        assertThat(primeiro.has("fotoPrincipalHash")).isTrue();
        assertThat(primeiro.get("numero").asString()).isEqualTo(x.marca() + "-1");
        mvc.perform(get(BASE + "compras/por-compra/" + UUID.randomUUID() + "/itens").with(usuarios.comoDona()))
                .andExpect(status().isNotFound());
    }

    @Test
    void historicoDeCustoComCustoAnteriorEVariacao() throws Exception {
        Compras x = massaDeCompras();
        JsonNode r = conferir("compras/historico-de-custo", null, null, "produtoId", x.a().toString());
        // Ordem: o mais recente primeiro. Anel: 5,00 -> 3,34 -> 5,55 -> 1,00 (esta, da compra desfeita).
        assertThat(r.get("total").asLong()).isEqualTo(3);
        assertThat(r.get("itens").get(0).get("custo").asString()).isEqualTo("1.00");
        assertThat(r.get("itens").get(0).get("compraEstornada").asBoolean()).isTrue();
        assertThat(r.get("itens").get(0).get("variacao").asString()).isEqualTo("-4.55");
        assertThat(r.get("itens").get(0).get("variacaoPercentual").asString()).isEqualTo("-81.98");
        assertThat(r.get("itens").get(1).get("custoAnterior").asString()).isEqualTo("3.34");
        assertThat(r.get("itens").get(1).get("variacao").asString()).isEqualTo("2.21");
        assertThat(r.get("itens").get(1).get("variacaoPercentual").asString()).isEqualTo("66.17");
        JsonNode primeira = r.get("itens").get(2);
        assertThat(primeira.get("custoAnterior").asString()).isEqualTo("5.00");
        assertThat(primeira.get("variacaoPercentual").asString()).isEqualTo("-33.20");
        assertThat(primeira.get("numeroDaCompra").asString()).isEqualTo(x.marca() + "-1");
        assertThat(primeira.get("fornecedor").asString()).contains(x.marca());
        assertThat(quantidade(r, "aumentos")).isEqualTo(1);
        assertThat(quantidade(r, "reducoes")).isEqualTo(2);
        assertThat(relatorio("compras/historico-de-custo", "busca", x.marca()).get("total").asLong()).isEqualTo(5);
        assertThat(relatorio("compras/historico-de-custo", "busca", x.marca(), "fornecedorId", x.f2().toString()).get("total").asLong()).isZero();
        assertThat(relatorio("compras/historico-de-custo", "busca", x.marca(), "categoriaId", x.catB().toString()).get("total").asLong())
                .isEqualTo(2);
        assertThat(relatorio("compras/historico-de-custo", "busca", x.marca(), "de", "2026-11-05", "ate", "2026-11-05")
                .get("total").asLong()).isEqualTo(2);
    }

    // --- custo e compras fora do pull ---

    @Test
    void custoECompraNuncaApareceNoPullDoVendedor() throws Exception {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        Set<String> soServidor = new HashSet<>();
        registro.soDoServidor().forEach(t -> soServidor.add(t.nome()));
        assertThat(soServidor).contains("compras", "itens_compra", "historico_custo", "produtos_custos", "movimentacoes_estoque");
        assertThat(registro.sincronizaveis()).extracting(t -> t.nome())
                .doesNotContain("compras", "itens_compra", "historico_custo", "produtos_custos", "movimentacoes_estoque");
        Compras x = massaDeCompras();
        LeituraDoPull tudo = LeituraDoPull.esvaziar(pull, null);
        Set<String> tabelas = new HashSet<>();
        tudo.linhas().forEach(l -> tabelas.add(l.tabela()));
        assertThat(tabelas).doesNotContain("compras", "itens_compra", "historico_custo", "produtos_custos", "movimentacoes_estoque");
        String texto = tudo.linhas().toString();
        assertThat(texto).doesNotContain("custo", "valorACusto", "custoUnitario", x.marca() + "-1");
    }

    // --- mensagens ---

    @Test
    void mensagensDeErroNaoTraemTermosProibidos() throws Exception {
        for (String[] par : new String[][] {{"estoque/movimentacoes", "tipo", "XPTO"}, {"estoque/movimentacoes", "origem", "XPTO"},
                {"estoque/posicao-atual", "situacao", "XPTO"}, {"estoque/posicao-atual", "categoriaId", "abc"},
                {"estoque/parados", "dias", "0"}, {"estoque/inventarios", "situacao", "XPTO"},
                {"compras/por-compra", "situacao", "XPTO"}, {"compras/por-fornecedor", "fornecedorId", "abc"},
                {"estoque/movimentacoes", "tamanho", "1000"}}) {
            String texto = mvc.perform(get(BASE + par[0]).param(par[1], par[2]).with(usuarios.comoDona()))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode corpo = json.readTree(texto);
            String legivel = (corpo.has("title") ? corpo.get("title").asString() : "") + " "
                    + (corpo.has("detail") ? corpo.get("detail").asString() : "") + " "
                    + (corpo.has("errors") ? corpo.get("errors").toString() : "");
            assertThat(legivel.toLowerCase()).doesNotContain("uuid", "sql", "exception", "null", "estorno_id", "enum", "payload");
        }
    }

    // --- desempenho: 10 mil produtos e 50 mil movimentos, tudo desfeito no fim do teste ---

    @Test
    @Transactional
    void desempenhoComDezMilProdutosECinquentaMilMovimentos() throws Exception {
        String m = marca();
        UUID cat = categoria(m);
        UUID forn = fornecedor("Forn " + m);
        jdbc.update("""
                INSERT INTO corona.produtos (id, nome, categoria_id, fornecedor_id, preco_venda, estoque_minimo, estoque_atual)
                SELECT gen_random_uuid(), 'Peca ' || n || ' ' || ?, ?, ?, 50, 3, 5
                  FROM generate_series(1, 10000) n""", m, cat, forn);
        jdbc.update("""
                INSERT INTO corona.produtos_custos (produto_id, preco_custo)
                SELECT id, 12.34 FROM corona.produtos WHERE nome LIKE '% ' || ?""", m);
        // 5 movimentos por produto: 5 entradas de 1 peça cada (saldo 5, igual ao saldo guardado).
        jdbc.update("""
                INSERT INTO corona.movimentacoes_estoque (id, produto_id, tipo, quantidade, saldo_depois, origem_tipo,
                                                          usuario_id, ocorrida_em)
                SELECT gen_random_uuid(), p.id, 'ENTRADA', 1, k, 'MANUAL', ?, now() - (k || ' hours')::interval
                  FROM corona.produtos p, generate_series(1, 5) k WHERE p.nome LIKE '% ' || ?""", dona(), m);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.movimentacoes_estoque m JOIN corona.produtos p ON p.id = m.produto_id "
                + "WHERE p.nome LIKE '% ' || ?", Integer.class, m)).isEqualTo(50_000);

        for (String[] rota : new String[][] {{"estoque/posicao-atual"}, {"estoque/movimentacoes"}, {"estoque/abaixo-do-minimo"},
                {"estoque/parados"}, {"estoque/conciliacao"}, {"estoque/inventarios"}}) {
            long inicio = System.nanoTime();
            JsonNode r = relatorio(rota[0], "fornecedorId", forn.toString(), "categoriaId", cat.toString(), "tamanho", "100",
                    "situacao", rota[0].endsWith("inventarios") ? "TODOS" : "ATIVOS");
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            assertThat(r.get("itens").size()).isLessThanOrEqualTo(100);
            assertThat(ms).as(rota[0] + " em " + ms + " ms").isLessThan(8_000);
        }
        JsonNode posicao = relatorio("estoque/posicao-atual", "categoriaId", cat.toString());
        assertThat(posicao.get("total").asLong()).isEqualTo(10_000);
        assertThat(pecas(posicao, "valor-a-custo")).isEqualTo(50_000);
        assertThat(valor(posicao, "valor-a-custo")).isEqualByComparingTo("617000.00");
        JsonNode movs = relatorio("estoque/movimentacoes", "categoriaId", cat.toString());
        assertThat(movs.get("total").asLong()).isEqualTo(50_000);
        assertThat(movs.get("itens").size()).isEqualTo(20);
        assertThat(relatorio("estoque/abaixo-do-minimo", "categoriaId", cat.toString()).get("total").asLong()).isZero();
    }
}
