package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Relatórios da maleta e dados da nota de remessa (tarefa 3.4; ADR-0010, itens 13 e 15). Tudo é criado pelas rotas e pelo
 * serviço de estoque (envio, devolução, cancelamento); cada teste usa os próprios vendedores e peças. Os envios são
 * "colocados" em datas distantes (anos 2285) para a cotação do dia do envio ser a que o teste cadastrou, nunca a de hoje.
 */
class RelatoriosDeMaletaTest extends TesteDeIntegracao {

    private static final String REL = "/api/dona/relatorios/maleta/";
    private static final String REMESSAS = "/api/dona/remessas";
    private static final AtomicInteger SEQUENCIA = new AtomicInteger(1);

    /** Cotação A (vale de 09/03/2285 até 19/03/2285) e cotação B (de 20/03/2285 em diante). */
    private static final Instant ENVIO_A = Instant.parse("2285-03-10T12:00:00Z");
    private static final Instant ENVIO_B = Instant.parse("2285-03-25T12:00:00Z");

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

    // --- apoio ---

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

    private ResultActions enviarPost(String caminho, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo == null ? mapa() : corpo)));
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    private static BigDecimal dec(String v) {
        return new BigDecimal(v);
    }

    private void cotacao(String dia, String usd, String pyg) {
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
                VALUES (uuidv7(), ?::date, ?::numeric, ?::numeric, 1, 1, ?::numeric, ?::numeric, 1, 1, now(), 'teste')
                ON CONFLICT ON CONSTRAINT cotacoes_data_unica DO NOTHING""", dia, usd, usd, pyg, pyg);
    }

    /** Dólar a 5,00 e Guarani a 0,001 real (1 real = 1000 Gs.) até 19/03/2285; depois 6,00 e 0,002. */
    private void cotacoesDoTeste() {
        cotacao("2285-03-09", "5.00000000", "0.00100000");
        cotacao("2285-03-20", "6.00000000", "0.00200000");
    }

    private UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(2400, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(enviarPost("/api/dona/semanas", mapa("numero", 80_000 + n, "inicio", inicio.toString(),
                "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID praca() throws Exception {
        return UUID.fromString(corpoDe(enviarPost("/api/dona/pracas", mapa("nome", "Praça " + UUID.randomUUID()))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private UUID vendedor(String limite) throws Exception {
        Map<String, Object> corpo = mapa("nome", "Vend " + UUID.randomUUID().toString().substring(0, 8));
        if (limite != null) {
            corpo.put("limiteConsignacao", limite);
        }
        return UUID.fromString(corpoDe(enviarPost("/api/dona/vendedores", corpo).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID categoria() throws Exception {
        return UUID.fromString(corpoDe(enviarPost("/api/dona/categorias", mapa("nome", "Cat " + UUID.randomUUID()))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private UUID peca(UUID categoria, String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID id = UUID.fromString(corpoDe(enviarPost("/api/dona/produtos", mapa("nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "R-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        return id;
    }

    private JsonNode rascunho(UUID vendedor, UUID semana, UUID praca, String tipo, Object... pares) throws Exception {
        Map<String, Object> corpo = mapa("id", UUID.randomUUID(), "vendedorId", vendedor, "semanaId", semana, "tipo", tipo);
        if (praca != null) {
            corpo.put("pracaId", praca);
        }
        JsonNode r = corpoDe(enviarPost(REMESSAS, corpo).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + pares[i])
                    .with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", pares[i + 1], "version", r.get("version").asInt()))))
                    .andExpect(status().isOk()));
        }
        return r;
    }

    /** Maleta enviada, com a data do envio posta em {@code quando}. */
    private UUID enviada(Instant quando, UUID vendedor, UUID semana, UUID praca, String tipo, Object... pares) throws Exception {
        JsonNode r = rascunho(vendedor, semana, praca, tipo, pares);
        enviarPost(REMESSAS + "/" + r.get("id").asString() + "/enviar", null).andExpect(status().isCreated());
        UUID id = UUID.fromString(r.get("id").asString());
        jdbc.update("UPDATE corona.remessas SET enviada_em = ? WHERE id = ?", java.sql.Timestamp.from(quando), id);
        return id;
    }

    private void devolver(UUID remessa, UUID produto, int quantidade) throws Exception {
        enviarPost(REMESSAS + "/" + remessa + "/devolucoes", mapa("itens", List.of(mapa("id", UUID.randomUUID(),
                "produtoId", produto, "quantidade", quantidade)))).andExpect(status().isCreated());
    }

    private void cancelar(UUID remessa) throws Exception {
        enviarPost(REMESSAS + "/" + remessa + "/cancelar", mapa("motivo", "Engano na montagem")).andExpect(status().isOk());
    }

    private JsonNode relatorio(String rota, String... consulta) throws Exception {
        return corpoDe(mvc.perform(pedido(rota, consulta)).andExpect(status().isOk()));
    }

    private MockHttpServletRequestBuilder pedido(String rota, String... consulta) {
        MockHttpServletRequestBuilder p = get(REL + rota).with(usuarios.comoDona());
        for (int i = 0; i < consulta.length; i += 2) {
            p.param(consulta[i], consulta[i + 1]);
        }
        return p;
    }

    private static JsonNode total(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (chave.equals(t.get("chave").asString())) {
                return t;
            }
        }
        throw new AssertionError("Sem o total " + chave);
    }

    /** O cenário comum: o vendedor A tem uma maleta nova e uma reposição; o vendedor B teve uma maleta cancelada e um rascunho. */
    private record Cenario(UUID a, UUID b, UUID semana1, UUID semana2, UUID praca, UUID categoria, UUID p1, UUID p2, UUID ra1,
            UUID ra2, UUID rb1, UUID rascunhoB) {
    }

    private Cenario cenario() throws Exception {
        cotacoesDoTeste();
        UUID a = vendedor("1000.00");
        UUID b = vendedor(null);
        UUID s1 = semana();
        UUID s2 = semana();
        UUID praca = praca();
        UUID categoria = categoria();
        UUID p1 = peca(categoria, "50.00", 50);
        UUID p2 = peca(categoria, "30.00", 50);
        UUID ra1 = enviada(ENVIO_A, a, s1, praca, "NOVA", p1, 4, p2, 2);
        devolver(ra1, p2, 1);
        UUID ra2 = enviada(ENVIO_B, a, s2, null, "REPOSICAO", p2, 3);
        UUID rb1 = enviada(ENVIO_A, b, s1, null, "NOVA", p1, 2);
        cancelar(rb1);
        UUID rascunhoB = UUID.fromString(rascunho(b, s1, null, "NOVA", p1, 1).get("id").asString());
        return new Cenario(a, b, s1, s2, praca, categoria, p1, p2, ra1, ra2, rb1, rascunhoB);
    }

    // --- (a) remessas por vendedor e semana ---

    @Test
    void remessasPorVendedorSomamOsValoresCongeladosEConvertemPelaCotacaoDoDiaDoEnvio() throws Exception {
        Cenario c = cenario();
        JsonNode r = relatorio("remessas", "vendedorId", c.a().toString());
        assertThat(r.get("total").asInt()).isEqualTo(2);
        // 4x50 + 2x30 = 260 (cotação A: US$ 5, 1 real = 1000 Gs.) e 3x30 = 90 (cotação B: US$ 6, 1 real = 500 Gs.).
        JsonNode geral = total(r, "geral");
        assertThat(geral.get("quantidade").asInt()).isEqualTo(2);
        assertThat(geral.get("pecas").asInt()).isEqualTo(9);
        assertThat(dec(geral.get("valorEmReais"))).isEqualByComparingTo("350.00");
        assertThat(dec(geral.get("valorEmDolar"))).isEqualByComparingTo("67.00");
        assertThat(dec(geral.get("valorEmGuarani"))).isEqualByComparingTo("305000");
        JsonNode primeira = null;
        for (JsonNode linha : r.get("itens")) {
            if (linha.get("id").asString().equals(c.ra1().toString())) {
                primeira = linha;
            }
        }
        assertThat(primeira).isNotNull();
        assertThat(primeira.get("numero").asString()).matches("S\\d+-V\\d+-1");
        assertThat(dec(primeira.get("valorEmReais"))).isEqualByComparingTo("260.00");
        assertThat(dec(primeira.get("valorEmDolar"))).isEqualByComparingTo("52.00");
        assertThat(dec(primeira.get("valorEmGuarani"))).isEqualByComparingTo("260000");
        assertThat(primeira.get("dataDaCotacao").asString()).isEqualTo("2285-03-09");
        // O rodapé é a soma das linhas já convertidas.
        BigDecimal reais = BigDecimal.ZERO;
        BigDecimal dolar = BigDecimal.ZERO;
        BigDecimal guarani = BigDecimal.ZERO;
        for (JsonNode linha : r.get("itens")) {
            reais = reais.add(dec(linha.get("valorEmReais")));
            dolar = dolar.add(dec(linha.get("valorEmDolar")));
            guarani = guarani.add(dec(linha.get("valorEmGuarani")));
        }
        assertThat(reais).isEqualByComparingTo(dec(geral.get("valorEmReais")));
        assertThat(dolar).isEqualByComparingTo(dec(geral.get("valorEmDolar")));
        assertThat(guarani).isEqualByComparingTo(dec(geral.get("valorEmGuarani")));
        // O detalhamento do total traz exatamente as linhas que o formam.
        JsonNode detalhe = relatorio("remessas", "vendedorId", c.a().toString(), "recorte", "reposicoes");
        assertThat(detalhe.get("total").asInt()).isEqualTo(1);
        assertThat(detalhe.get("itens").get(0).get("id").asString()).isEqualTo(c.ra2().toString());
    }

    @Test
    void rascunhoNuncaApareceECanceladaFicaMarcadaEForaDoTotal() throws Exception {
        Cenario c = cenario();
        assertThat(relatorio("remessas", "vendedorId", c.b().toString()).get("total").asInt()).isZero();
        JsonNode todas = relatorio("remessas", "vendedorId", c.b().toString(), "situacao", "TODAS");
        assertThat(todas.get("total").asInt()).isEqualTo(1);
        assertThat(todas.get("itens").get(0).get("id").asString()).isEqualTo(c.rb1().toString());
        assertThat(todas.get("itens").get(0).get("cancelada").asBoolean()).isTrue();
        assertThat(todas.get("itens").get(0).get("motivoDoCancelamento").asString()).isEqualTo("Engano na montagem");
        assertThat(total(todas, "geral").get("quantidade").asInt()).isZero();
        assertThat(dec(total(todas, "canceladas").get("valorEmReais"))).isEqualByComparingTo("100.00");
        assertThat(todas.toString()).doesNotContain(c.rascunhoB().toString());
    }

    @Test
    void filtrosDasRemessas() throws Exception {
        Cenario c = cenario();
        String a = c.a().toString();
        assertThat(relatorio("remessas", "vendedorId", a, "semanaId", c.semana1().toString()).get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("remessas", "vendedorId", a, "pracaId", c.praca().toString()).get("itens").get(0).get("id").asString())
                .isEqualTo(c.ra1().toString());
        assertThat(relatorio("remessas", "vendedorId", a, "tipo", "REPOSICAO").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("remessas", "vendedorId", a, "de", "2285-03-11", "ate", "2285-03-31").get("itens").get(0)
                .get("id").asString()).isEqualTo(c.ra2().toString());
        assertThat(relatorio("remessas", "vendedorId", a, "de", "2285-03-10", "ate", "2285-03-10").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("remessas", "vendedorId", a, "produtoId", c.p1().toString()).get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("remessas", "vendedorId", a, "categoriaId", c.categoria().toString()).get("total").asInt()).isEqualTo(2);
        assertThat(relatorio("remessas", "busca", "Vend", "vendedorId", a).get("total").asInt()).isEqualTo(2);
        assertThat(relatorio("remessas", "vendedorId", a, "situacao", "ENVIADA").get("total").asInt()).isEqualTo(2);
        mvc.perform(pedido("remessas", "de", "2285-03-11", "ate", "2285-03-10")).andExpect(status().is4xxClientError());
        mvc.perform(pedido("remessas", "tipo", "QUALQUER")).andExpect(status().is4xxClientError());
        mvc.perform(pedido("remessas", "vendedorId", "nao-e-id")).andExpect(status().is4xxClientError());
    }

    // --- (e) reposições ---

    @Test
    void reposicoesTrazemSoAsMaletasDeReposicao() throws Exception {
        Cenario c = cenario();
        JsonNode r = relatorio("reposicoes", "vendedorId", c.a().toString());
        assertThat(r.get("total").asInt()).isEqualTo(1);
        JsonNode linha = r.get("itens").get(0);
        assertThat(linha.get("id").asString()).isEqualTo(c.ra2().toString());
        assertThat(linha.get("tipo").asString()).isEqualTo("REPOSICAO");
        assertThat(dec(linha.get("valorEmDolar"))).isEqualByComparingTo("15.00");
        assertThat(dec(linha.get("valorEmGuarani"))).isEqualByComparingTo("45000");
        assertThat(linha.get("dataDaCotacao").asString()).isEqualTo("2285-03-20");
        assertThat(relatorio("reposicoes", "vendedorId", c.b().toString(), "situacao", "TODAS").get("total").asInt()).isZero();
    }

    // --- (b) saldo por maleta ---

    @Test
    void saldoPorMaletaMostraEnviadaDevolvidaESaldoPecaAPeca() throws Exception {
        Cenario c = cenario();
        JsonNode r = relatorio("saldo", "remessaId", c.ra1().toString());
        assertThat(r.get("total").asInt()).isEqualTo(2);
        for (JsonNode linha : r.get("itens")) {
            if (linha.get("produtoId").asString().equals(c.p1().toString())) {
                assertThat(linha.get("enviada").asInt()).isEqualTo(4);
                assertThat(linha.get("devolvida").asInt()).isZero();
                assertThat(linha.get("saldo").asInt()).isEqualTo(4);
                assertThat(dec(linha.get("valorEmReais"))).isEqualByComparingTo("200.00");
                assertThat(dec(linha.get("valorEmDolar"))).isEqualByComparingTo("40.00");
                assertThat(linha.get("codigo").asString()).startsWith("R-");
            } else {
                assertThat(linha.get("enviada").asInt()).isEqualTo(2);
                assertThat(linha.get("devolvida").asInt()).isEqualTo(1);
                assertThat(linha.get("saldo").asInt()).isEqualTo(1);
                assertThat(dec(linha.get("valorEmReais"))).isEqualByComparingTo("30.00");
            }
        }
        assertThat(total(r, "enviadas").get("pecas").asInt()).isEqualTo(6);
        assertThat(total(r, "devolvidas").get("pecas").asInt()).isEqualTo(1);
        JsonNode saldo = total(r, "saldo");
        assertThat(saldo.get("pecas").asInt()).isEqualTo(5);
        assertThat(dec(saldo.get("valorEmReais"))).isEqualByComparingTo("230.00");
        assertThat(dec(saldo.get("valorEmDolar"))).isEqualByComparingTo("46.00");
        assertThat(dec(saldo.get("valorEmGuarani"))).isEqualByComparingTo("230000");
        // Detalhar o total traz as linhas que o formam.
        assertThat(relatorio("saldo", "remessaId", c.ra1().toString(), "recorte", "devolvidas").get("total").asInt()).isEqualTo(1);
    }

    @Test
    void saldoFicaForaParaRascunhoECanceladaEFiltraPorSituacaoEPeca() throws Exception {
        Cenario c = cenario();
        assertThat(relatorio("saldo", "vendedorId", c.b().toString(), "situacao", "TODAS").get("total").asInt()).isZero();
        // Uma maleta devolvida por inteiro fica "zerada".
        UUID v = vendedor(null);
        UUID tudo = enviada(ENVIO_A, v, c.semana1(), null, "NOVA", c.p1(), 2);
        devolver(tudo, c.p1(), 2);
        assertThat(relatorio("saldo", "vendedorId", v.toString()).get("total").asInt()).isZero();
        assertThat(relatorio("saldo", "vendedorId", v.toString(), "situacao", "ZERADAS").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("saldo", "vendedorId", v.toString(), "situacao", "TODAS").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("saldo", "vendedorId", c.a().toString(), "produtoId", c.p2().toString()).get("total").asInt())
                .isEqualTo(2);
        assertThat(relatorio("saldo", "vendedorId", c.a().toString(), "tipo", "REPOSICAO").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("saldo", "vendedorId", c.a().toString(), "busca", "R-").get("total").asInt()).isEqualTo(3);
        mvc.perform(pedido("saldo", "situacao", "NADA")).andExpect(status().is4xxClientError());
    }

    // --- (c) mercadoria em maletas por vendedor ---

    @Test
    void mercadoriaPorVendedorSomaAoPrecoCongeladoEMostraOQueSobraDoLimite() throws Exception {
        Cenario c = cenario();
        // Mudar o preço da peça depois do envio não muda nada aqui: vale o preço congelado.
        jdbc.update("UPDATE corona.produtos SET preco_venda = 999.00 WHERE id = ?", c.p1());
        JsonNode r = relatorio("mercadoria-por-vendedor", "vendedorId", c.a().toString());
        assertThat(r.get("total").asInt()).isEqualTo(1);
        JsonNode linha = r.get("itens").get(0);
        assertThat(linha.get("maletasEmAberto").asInt()).isEqualTo(2);
        assertThat(linha.get("pecasEmAberto").asInt()).isEqualTo(8);
        assertThat(dec(linha.get("valorEmReais"))).isEqualByComparingTo("320.00");
        // 230 a US$ 5 e Gs. 1000 por real, mais 90 a US$ 6 e Gs. 500 por real.
        assertThat(dec(linha.get("valorEmDolar"))).isEqualByComparingTo("61.00");
        assertThat(dec(linha.get("valorEmGuarani"))).isEqualByComparingTo("275000");
        assertThat(dec(linha.get("limite"))).isEqualByComparingTo("1000.00");
        assertThat(dec(linha.get("sobraDoLimite"))).isEqualByComparingTo("680.00");
        // Com filtro de semana só entra uma maleta, mas a sobra do limite continua contando tudo o que está em aberto.
        JsonNode so = relatorio("mercadoria-por-vendedor", "vendedorId", c.a().toString(), "semanaId", c.semana1().toString());
        assertThat(so.get("itens").get(0).get("maletasEmAberto").asInt()).isEqualTo(1);
        assertThat(dec(so.get("itens").get(0).get("valorEmReais"))).isEqualByComparingTo("230.00");
        assertThat(dec(so.get("itens").get(0).get("sobraDoLimite"))).isEqualByComparingTo("680.00");
        // O vendedor sem nada em aberto (maleta cancelada, rascunho) não aparece.
        assertThat(relatorio("mercadoria-por-vendedor", "vendedorId", c.b().toString()).get("total").asInt()).isZero();
        assertThat(dec(total(r, "emAberto").get("valorEmReais"))).isEqualByComparingTo("320.00");
    }

    @Test
    void vendedorSemLimiteCadastradoNaoTemSobra() throws Exception {
        cotacoesDoTeste();
        UUID v = vendedor(null);
        UUID p = peca(categoria(), "10.00", 5);
        enviada(ENVIO_A, v, semana(), null, "NOVA", p, 3);
        JsonNode linha = relatorio("mercadoria-por-vendedor", "vendedorId", v.toString()).get("itens").get(0);
        assertThat(linha.get("limite").isNull()).isTrue();
        assertThat(linha.get("sobraDoLimite").isNull()).isTrue();
    }

    // --- (d) maletas em aberto e atrasadas ---

    @Test
    void maletasEmAbertoSeparamAsAtrasadasPeloPrazoDeSeteDiasDesdeOEnvio() throws Exception {
        cotacoesDoTeste();
        UUID v = vendedor(null);
        UUID categoria = categoria();
        UUID p = peca(categoria, "20.00", 20);
        UUID semana = semana();
        UUID velha = enviada(Instant.now().minus(Duration.ofDays(10)), v, semana, null, "NOVA", p, 3);
        UUID nova = enviada(Instant.now().minus(Duration.ofDays(2)), v, semana, null, "REPOSICAO", p, 2);
        UUID zerada = enviada(Instant.now().minus(Duration.ofDays(30)), v, semana, null, "NOVA", p, 1);
        devolver(zerada, p, 1);
        JsonNode r = relatorio("em-aberto", "vendedorId", v.toString());
        // A maleta toda devolvida não está em aberto.
        assertThat(r.get("total").asInt()).isEqualTo(2);
        assertThat(r.get("itens").get(0).get("remessaId").asString()).isEqualTo(velha.toString());
        assertThat(r.get("itens").get(0).get("atrasada").asBoolean()).isTrue();
        assertThat(r.get("itens").get(0).get("diasDesdeOEnvio").asInt()).isBetween(10, 11);
        assertThat(r.get("itens").get(1).get("remessaId").asString()).isEqualTo(nova.toString());
        assertThat(r.get("itens").get(1).get("atrasada").asBoolean()).isFalse();
        assertThat(total(r, "emAberto").get("pecas").asInt()).isEqualTo(5);
        assertThat(dec(total(r, "atrasadas").get("valorEmReais"))).isEqualByComparingTo("60.00");
        assertThat(dec(total(r, "noPrazo").get("valorEmReais"))).isEqualByComparingTo("40.00");
        assertThat(relatorio("em-aberto", "vendedorId", v.toString(), "situacao", "ATRASADAS").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio("em-aberto", "vendedorId", v.toString(), "situacao", "NO_PRAZO").get("itens").get(0)
                .get("remessaId").asString()).isEqualTo(nova.toString());
        assertThat(relatorio("em-aberto", "vendedorId", v.toString(), "recorte", "atrasadas").get("total").asInt()).isEqualTo(1);
        // O prazo é escolhido na hora: com 1 dia as duas estão atrasadas; com 30, nenhuma.
        assertThat(total(relatorio("em-aberto", "vendedorId", v.toString(), "diasDeAtraso", "1"), "atrasadas")
                .get("quantidade").asInt()).isEqualTo(2);
        assertThat(total(relatorio("em-aberto", "vendedorId", v.toString(), "diasDeAtraso", "30"), "atrasadas")
                .get("quantidade").asInt()).isZero();
        mvc.perform(pedido("em-aberto", "diasDeAtraso", "-1")).andExpect(status().is4xxClientError());
        // A mercadoria por vendedor conta as atrasadas.
        assertThat(relatorio("mercadoria-por-vendedor", "vendedorId", v.toString()).get("itens").get(0).get("maletasAtrasadas")
                .asInt()).isEqualTo(1);
    }

    @Test
    void maletaCanceladaOuEmRascunhoNaoEstaEmAberto() throws Exception {
        Cenario c = cenario();
        assertThat(relatorio("em-aberto", "vendedorId", c.b().toString()).get("total").asInt()).isZero();
        assertThat(relatorio("em-aberto", "vendedorId", c.a().toString()).get("total").asInt()).isEqualTo(2);
    }

    // --- nota de remessa ---

    @Test
    void notaTrazTudoCongeladoParaImprimirSemCusto() throws Exception {
        Cenario c = cenario();
        jdbc.update("UPDATE corona.produtos SET preco_venda = 999.00 WHERE id = ?", c.p1());
        ResultActions pedido = mvc.perform(get(REMESSAS + "/" + c.ra1() + "/nota").with(usuarios.comoDona()));
        pedido.andExpect(status().isOk());
        JsonNode n = corpoDe(pedido);
        assertThat(n.get("loja").asString()).isEqualTo("Corona Semijoias");
        assertThat(n.get("numero").asString()).matches("S\\d+-V\\d+-1");
        assertThat(n.get("tipo").asString()).isEqualTo("NOVA");
        assertThat(n.get("cancelada").asBoolean()).isFalse();
        assertThat(n.get("vendedor").get("nome").asString()).startsWith("Vend ");
        assertThat(n.get("semana").get("numero").asInt()).isGreaterThan(80_000);
        assertThat(n.get("praca").asString()).startsWith("Praça ");
        assertThat(n.get("totalPecas").asInt()).isEqualTo(6);
        assertThat(dec(n.get("totalValor"))).isEqualByComparingTo("260.00");
        assertThat(n.get("itens")).hasSize(2);
        for (JsonNode item : n.get("itens")) {
            assertThat(item.get("codigo").asString()).startsWith("R-");
            assertThat(dec(item.get("subtotal"))).isEqualByComparingTo(dec(item.get("precoRemessa")).multiply(BigDecimal.valueOf(item.get("quantidade").asInt())));
            assertThat(dec(item.get("precoRemessa"))).isIn(dec("50.00"), dec("30.00"));
        }
        JsonNode cotacao = n.get("cotacao");
        assertThat(cotacao.get("data").asString()).isEqualTo("2285-03-09");
        assertThat(dec(cotacao.get("totalEmDolar"))).isEqualByComparingTo("52.00");
        assertThat(dec(cotacao.get("totalEmGuarani"))).isEqualByComparingTo("260000");
        assertThat(n.toString().toLowerCase()).doesNotContain("custo");
    }

    @Test
    void notaDeMaletaCanceladaVemMarcadaENotaDeRascunhoERecusada() throws Exception {
        Cenario c = cenario();
        JsonNode cancelada = corpoDe(mvc.perform(get(REMESSAS + "/" + c.rb1() + "/nota").with(usuarios.comoDona()))
                .andExpect(status().isOk()));
        assertThat(cancelada.get("cancelada").asBoolean()).isTrue();
        assertThat(cancelada.get("status").asString()).isEqualTo("CANCELADA");
        assertThat(cancelada.get("motivoDoCancelamento").asString()).isEqualTo("Engano na montagem");
        ResultActions rascunho = mvc.perform(get(REMESSAS + "/" + c.rascunhoB() + "/nota").with(usuarios.comoDona()))
                .andExpect(status().isConflict());
        assertThat(corpoDe(rascunho).toString()).contains("A nota só fica pronta depois que a maleta for enviada");
        mvc.perform(get(REMESSAS + "/" + UUID.randomUUID() + "/nota").with(usuarios.comoDona())).andExpect(status().isNotFound());
    }

    @Test
    void notaSemCotacaoNaDataDoEnvioVemSemCotacao() throws Exception {
        UUID v = vendedor(null);
        UUID p = peca(categoria(), "10.00", 5);
        // Antes de qualquer cotação do sistema.
        UUID r = enviada(Instant.parse("1990-01-10T12:00:00Z"), v, semana(), null, "NOVA", p, 1);
        JsonNode n = corpoDe(mvc.perform(get(REMESSAS + "/" + r + "/nota").with(usuarios.comoDona())).andExpect(status().isOk()));
        assertThat(n.get("cotacao").isNull()).isTrue();
        JsonNode linha = relatorio("remessas", "vendedorId", v.toString()).get("itens").get(0);
        assertThat(linha.get("valorEmDolar").isNull()).isTrue();
        assertThat(linha.get("valorEmGuarani").isNull()).isTrue();
        assertThat(dec(linha.get("valorEmReais"))).isEqualByComparingTo("10.00");
    }

    // --- permissão ---

    @Test
    void vendedorNaoVeNadaDisso() throws Exception {
        Cenario c = cenario();
        for (String rota : List.of("remessas", "reposicoes", "saldo", "mercadoria-por-vendedor", "em-aberto")) {
            mvc.perform(get(REL + rota).with(usuarios.comoVendedor())).andExpect(status().isForbidden());
        }
        mvc.perform(get(REMESSAS + "/" + c.ra1() + "/nota").with(usuarios.comoVendedor())).andExpect(status().isForbidden());
        mvc.perform(get(REL + "remessas")).andExpect(status().isUnauthorized());
    }
}
