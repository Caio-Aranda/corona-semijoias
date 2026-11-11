package br.com.coronasemijoias.api.remessas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Devolução da maleta à dona, cancelamento (estorno do envio), estorno de devolução, leitura do saldo e conciliação
 * "estoque central + maletas" (tarefa 3.3; ADR-0010, itens 4, 5 e 12). Cada teste cria os próprios vendedor, semana e peças.
 */
class DevolucaoDaMaletaTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/remessas";
    private static final AtomicInteger SEQUENCIA = new AtomicInteger(1);

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

    private RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    private ResultActions enviarPost(String caminho, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(dona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo == null ? mapa() : corpo)));
    }

    private ResultActions ler(String caminho) throws Exception {
        return mvc.perform(get(caminho).with(dona()));
    }

    private JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    // --- fixtures ---

    private UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(2300, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(enviarPost("/api/dona/semanas", mapa("numero", 70_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private UUID vendedor(String limite) throws Exception {
        Map<String, Object> corpo = mapa("nome", "Vendedor " + UUID.randomUUID().toString().substring(0, 8));
        if (limite != null) {
            corpo.put("limiteConsignacao", limite);
        } else {
            corpo.put("semLimite", true); // o limite é obrigatório (V026)
        }
        return UUID.fromString(corpoDe(enviarPost("/api/dona/vendedores", corpo).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(enviarPost("/api/dona/categorias", mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(enviarPost("/api/dona/produtos", mapa("nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "D-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return id;
    }

    private JsonNode comItem(JsonNode remessa, UUID produto, int quantidade) throws Exception {
        return corpoDe(mvc.perform(put(BASE + "/" + remessa.get("id").asString() + "/itens/" + produto).with(dona())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", quantidade, "version", remessa.get("version").asInt()))))
                .andExpect(status().isOk()));
    }

    private JsonNode rascunho(UUID vendedor, Object... pares) throws Exception {
        JsonNode r = corpoDe(enviarPost(BASE, mapa("id", UUID.randomUUID(), "vendedorId", vendedor, "semanaId", semana(),
                "tipo", "NOVA")).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = comItem(r, (UUID) pares[i], (Integer) pares[i + 1]);
        }
        return r;
    }

    /** Maleta já enviada com os itens (produto, quantidade). */
    private UUID enviada(UUID vendedor, Object... pares) throws Exception {
        JsonNode r = rascunho(vendedor, pares);
        enviarPost(BASE + "/" + r.get("id").asString() + "/enviar", null).andExpect(status().isCreated());
        return UUID.fromString(r.get("id").asString());
    }

    private static Map<String, Object> linha(UUID id, UUID produto, int quantidade) {
        return mapa("id", id, "produtoId", produto, "quantidade", quantidade);
    }

    private ResultActions devolver(UUID remessa, Map<String, Object>... linhas) throws Exception {
        return enviarPost(BASE + "/" + remessa + "/devolucoes", mapa("itens", List.of(linhas)));
    }

    private UUID devolverOk(UUID remessa, UUID produto, int quantidade) throws Exception {
        UUID id = UUID.randomUUID();
        devolver(remessa, linha(id, produto, quantidade)).andExpect(status().isCreated());
        return id;
    }

    private ResultActions cancelar(UUID remessa, String motivo) throws Exception {
        return enviarPost(BASE + "/" + remessa + "/cancelar", mapa("motivo", motivo));
    }

    private ResultActions desfazer(UUID remessa, UUID devolucao, UUID idDoEstorno, String motivo) throws Exception {
        return enviarPost(BASE + "/" + remessa + "/devolucoes/" + devolucao + "/estorno", mapa("id", idDoEstorno, "motivo", motivo));
    }

    private JsonNode maleta(UUID remessa) throws Exception {
        return corpoDe(ler(BASE + "/" + remessa + "/maleta").andExpect(status().isOk()));
    }

    private int central(UUID produto) {
        return jdbc.queryForObject("SELECT estoque_atual FROM corona.produtos WHERE id = ?", Integer.class, produto);
    }

    private int saldoDaMaleta(UUID remessa, UUID produto) {
        return jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                Integer.class, remessa, produto);
    }

    private int movimentosDaMaleta(UUID remessa) {
        return jdbc.queryForObject("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ?", Integer.class, remessa);
    }

    private int movimentosDeEstoque(UUID produto) {
        return jdbc.queryForObject("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ?", Integer.class, produto);
    }

    private String statusDa(UUID remessa) {
        return jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, remessa);
    }

    private <T> List<T> paralelo(List<Callable<T>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tarefas.size(), 16));
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarefa : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return tarefa.call();
                }));
            }
            largada.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> f : futuros) {
                resultados.add(f.get(90, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    // --- permissão ---

    @Test
    void vendedorRecebe403ESemLoginRecebe401NasRotasNovas() throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        for (String rota : List.of("/" + id + "/maleta", "/em-aberto?vendedorId=" + id)) {
            mvc.perform(get(BASE + rota).with(vendedor)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + rota)).andExpect(status().isUnauthorized());
        }
        for (String rota : List.of("/" + id + "/devolucoes", "/" + id + "/cancelar",
                "/" + id + "/devolucoes/" + UUID.randomUUID() + "/estorno")) {
            mvc.perform(post(BASE + rota).with(vendedor).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(BASE + rota).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/dona/estoque/conciliacao").with(vendedor)).andExpect(status().isForbidden());
    }

    // --- devolução ---

    @Test
    @SuppressWarnings("unchecked")
    void devolucaoParcialETotalVoltaAoEstoqueCentralEBaixaOSaldoDaMaleta() throws Exception {
        UUID p = peca("70.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        assertThat(central(p)).isEqualTo(5);

        devolver(r, linha(UUID.randomUUID(), p, 2)).andExpect(status().isCreated());
        assertThat(central(p)).isEqualTo(7);
        assertThat(saldoDaMaleta(r, p)).isEqualTo(3);
        // O movimento da maleta tem quantidade negativa e o do estoque é uma DEVOLUCAO de origem MALETA ligada à remessa.
        assertThat(jdbc.queryForObject("SELECT quantidade FROM corona.movimentos_remessa WHERE remessa_id = ? AND tipo = 'DEVOLUCAO_DONA'",
                Integer.class, r)).isEqualTo(-2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM corona.movimentacoes_estoque
                 WHERE produto_id = ? AND tipo = 'DEVOLUCAO' AND origem_tipo = 'MALETA' AND origem_id = ? AND quantidade = 2""",
                Integer.class, p, r)).isEqualTo(1);

        devolver(r, linha(UUID.randomUUID(), p, 3)).andExpect(status().isCreated());
        assertThat(central(p)).isEqualTo(10);
        assertThat(saldoDaMaleta(r, p)).isZero();
        // Maleta zerada: não dá para devolver mais nada.
        devolver(r, linha(UUID.randomUUID(), p, 1)).andExpect(status().isConflict());
        assertThat(central(p)).isEqualTo(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void devolverMaisQueOSaldoRecusaETudoOuNada() throws Exception {
        UUID a = peca("10.00", 10);
        UUID b = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), a, 3, b, 2);
        // A peça "a" cabe, a peça "b" não: nada é gravado.
        devolver(r, linha(UUID.randomUUID(), a, 1), linha(UUID.randomUUID(), b, 3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEVOLUCAO_MAIOR_QUE_O_SALDO"));
        assertThat(movimentosDaMaleta(r)).isZero();
        assertThat(central(a)).isEqualTo(7);
        assertThat(central(b)).isEqualTo(8);
        // Peça que não está na maleta.
        UUID fora = peca("10.00", 5);
        devolver(r, linha(UUID.randomUUID(), fora, 1)).andExpect(status().is4xxClientError());
        assertThat(central(fora)).isEqualTo(5);
        // Quantidade inválida e lista vazia.
        devolver(r, linha(UUID.randomUUID(), a, 0)).andExpect(status().isBadRequest());
        devolver(r, linha(UUID.randomUUID(), a, -1)).andExpect(status().isBadRequest());
        enviarPost(BASE + "/" + r + "/devolucoes", mapa("itens", List.of())).andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void devolverVariasPecasNumaChamadaSoAGrava() throws Exception {
        UUID a = peca("10.00", 10);
        UUID b = peca("20.00", 10);
        UUID r = enviada(vendedor("1000.00"), a, 4, b, 4);
        JsonNode resposta = corpoDe(devolver(r, linha(UUID.randomUUID(), a, 4), linha(UUID.randomUUID(), b, 1))
                .andExpect(status().isCreated()));
        assertThat(central(a)).isEqualTo(10);
        assertThat(central(b)).isEqualTo(7);
        assertThat(dec(resposta.get("valorEmAberto"))).isEqualByComparingTo("60.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reenvioDaMesmaDevolucaoDa200SemMexerDeNovoEConteudoDiferenteDa409() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        UUID id = UUID.randomUUID();
        devolver(r, linha(id, p, 2)).andExpect(status().isCreated());
        int movimentosAntes = movimentosDeEstoque(p);
        devolver(r, linha(id, p, 2)).andExpect(status().isOk());
        assertThat(central(p)).isEqualTo(7);
        assertThat(saldoDaMaleta(r, p)).isEqualTo(3);
        assertThat(movimentosDeEstoque(p)).isEqualTo(movimentosAntes);
        assertThat(movimentosDaMaleta(r)).isEqualTo(1);
        // Mesmo id com outra quantidade ou outra peça: 409 e nada muda.
        devolver(r, linha(id, p, 3)).andExpect(status().isConflict());
        devolver(r, linha(id, peca("10.00", 1), 2)).andExpect(status().isConflict());
        assertThat(central(p)).isEqualTo(7);
        // Reenvio de uma chamada com uma peça repetida e outra nova grava só a nova (201).
        UUID novo = UUID.randomUUID();
        devolver(r, linha(id, p, 2), linha(novo, p, 1)).andExpect(status().is4xxClientError());
        UUID q = peca("10.00", 10);
        UUID r2 = enviada(vendedor("1000.00"), p, 1, q, 3);
        UUID idQ = UUID.randomUUID();
        devolver(r2, linha(idQ, q, 1)).andExpect(status().isCreated());
        devolver(r2, linha(idQ, q, 1), linha(UUID.randomUUID(), p, 1)).andExpect(status().isCreated());
        assertThat(saldoDaMaleta(r2, q)).isEqualTo(2);
        assertThat(saldoDaMaleta(r2, p)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void devolucaoSoEmMaletaEnviada() throws Exception {
        UUID p = peca("10.00", 10);
        UUID v = vendedor("1000.00");
        JsonNode rascunho = rascunho(v, p, 2);
        devolver(UUID.fromString(rascunho.get("id").asString()), linha(UUID.randomUUID(), p, 1)).andExpect(status().isConflict());
        UUID cancelada = enviada(v, p, 2);
        cancelar(cancelada, "Peça errada").andExpect(status().isOk());
        devolver(cancelada, linha(UUID.randomUUID(), p, 1)).andExpect(status().isConflict());
        devolver(UUID.randomUUID(), linha(UUID.randomUUID(), p, 1)).andExpect(status().isNotFound());
        assertThat(central(p)).isEqualTo(10);
    }

    // --- cancelamento ---

    @Test
    void cancelarRascunhoNaoMexeNoEstoqueEExigeMotivo() throws Exception {
        UUID p = peca("10.00", 10);
        JsonNode r = rascunho(vendedor("1000.00"), p, 2);
        UUID id = UUID.fromString(r.get("id").asString());
        cancelar(id, "  ").andExpect(status().isBadRequest());
        cancelar(id, null).andExpect(status().isBadRequest());
        assertThat(statusDa(id)).isEqualTo("RASCUNHO");
        int antes = movimentosDeEstoque(p);
        cancelar(id, "Não vai mais sair").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELADA"));
        assertThat(statusDa(id)).isEqualTo("CANCELADA");
        assertThat(jdbc.queryForObject("SELECT motivo_cancelamento FROM corona.remessas WHERE id = ?", String.class, id))
                .isEqualTo("Não vai mais sair");
        assertThat(central(p)).isEqualTo(10);
        assertThat(movimentosDeEstoque(p)).isEqualTo(antes);
        // Depois de cancelada não aceita mais nada.
        mvc.perform(put(BASE + "/" + id + "/itens/" + p).with(dona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", 3, "version", 1)))).andExpect(status().isConflict());
        enviarPost(BASE + "/" + id + "/enviar", null).andExpect(status().isConflict());
        assertThat(central(p)).isEqualTo(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelarEnviadaLimpaDevolveOEstoqueCentralEDuasVezesDa200() throws Exception {
        UUID a = peca("10.00", 10);
        UUID b = peca("10.00", 4);
        UUID r = enviada(vendedor("1000.00"), a, 6, b, 4);
        assertThat(central(a)).isEqualTo(4);
        assertThat(central(b)).isZero();
        cancelar(r, "Enviada por engano").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELADA"));
        assertThat(central(a)).isEqualTo(10);
        assertThat(central(b)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM corona.movimentacoes_estoque WHERE origem_tipo = 'ESTORNO' AND tipo = 'REMESSA'
                   AND produto_id IN (?, ?)""", Integer.class, a, b)).isEqualTo(2);
        int movimentos = movimentosDeEstoque(a) + movimentosDeEstoque(b);
        cancelar(r, "Enviada por engano").andExpect(status().isOk());
        cancelar(r, "Outro motivo").andExpect(status().isOk());
        assertThat(central(a)).isEqualTo(10);
        assertThat(movimentosDeEstoque(a) + movimentosDeEstoque(b)).isEqualTo(movimentos);
        // Cancelada: nem devolução nem leitura de saldo em aberto.
        devolver(r, linha(UUID.randomUUID(), a, 1)).andExpect(status().isConflict());
        JsonNode m = maleta(r);
        assertThat(m.get("status").asString()).isEqualTo("CANCELADA");
        assertThat(dec(m.get("valorEmAberto"))).isEqualByComparingTo("0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelarEnviadaComMovimentoRecusaENaoMudaNada() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        devolverOk(r, p, 1);
        cancelar(r, "Quero cancelar").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("MALETA_JA_MOVIMENTADA"));
        assertThat(statusDa(r)).isEqualTo("ENVIADA");
        assertThat(central(p)).isEqualTo(6);
        assertThat(saldoDaMaleta(r, p)).isEqualTo(4);
    }

    @Test
    void pecaQueEstaComOVendedorNaoPodeSerExcluidaMasDepoisDeDevolverPode() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        mvc.perform(delete("/api/dona/produtos/" + p).with(dona())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PECA_ESTA_EM_MALETA"));
        // a devolução segue funcionando e, com a maleta vazia, a peça pode ser excluída
        devolverOk(r, p, 5);
        mvc.perform(delete("/api/dona/produtos/" + p).with(dona())).andExpect(status().isNoContent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void devolucaoDesfeitaLiberaOCancelamento() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        UUID d = devolverOk(r, p, 2);
        desfazer(r, d, UUID.randomUUID(), "Contei errado").andExpect(status().isCreated());
        cancelar(r, "Cancelando").andExpect(status().isOk());
        assertThat(central(p)).isEqualTo(10);
    }

    @Test
    void cancelarMaletaQueNaoExiste() throws Exception {
        cancelar(UUID.randomUUID(), "Motivo").andExpect(status().isNotFound());
    }

    // --- estorno de devolução ---

    @Test
    @SuppressWarnings("unchecked")
    void desfazerDevolucaoVoltaAPecaParaAMaletaEBaixaOCentral() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        UUID d = devolverOk(r, p, 2);
        assertThat(central(p)).isEqualTo(7);
        UUID estorno = UUID.randomUUID();
        desfazer(r, d, estorno, "Contei errado").andExpect(status().isCreated());
        assertThat(central(p)).isEqualTo(5);
        assertThat(saldoDaMaleta(r, p)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT quantidade FROM corona.movimentos_remessa WHERE id = ? AND estorna_id = ?",
                Integer.class, estorno, d)).isEqualTo(2);
        // Reenvio idempotente; outro id para a mesma devolução, 409; desfazer o "desfazer", 409.
        int antes = movimentosDeEstoque(p);
        desfazer(r, d, estorno, "Contei errado").andExpect(status().isOk());
        assertThat(central(p)).isEqualTo(5);
        assertThat(movimentosDeEstoque(p)).isEqualTo(antes);
        desfazer(r, d, UUID.randomUUID(), "Contei errado").andExpect(status().isConflict());
        desfazer(r, d, estorno, "Outro motivo").andExpect(status().isConflict());
        desfazer(r, estorno, UUID.randomUUID(), "De novo").andExpect(status().isConflict());
        desfazer(r, UUID.randomUUID(), UUID.randomUUID(), "Nao existe").andExpect(status().isNotFound());
        desfazer(r, d, UUID.randomUUID(), "  ").andExpect(status().isBadRequest());
        assertThat(central(p)).isEqualTo(5);
        // Depois de desfeita, dá para devolver de novo.
        devolverOk(r, p, 5);
        assertThat(central(p)).isEqualTo(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void desfazerDevolucaoSemSaldoNoCentralRecusaComMensagemSimples() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        UUID d = devolverOk(r, p, 2);
        // A dona dá saída em todo o estoque central (7 peças): não sobra nada para voltar à maleta.
        estoque.registrar(new Pedido(null, p, TipoDeMovimento.SAIDA, 7, null, OrigemDoMovimento.MANUAL, null), usuarios.dona().id());
        assertThat(central(p)).isZero();
        desfazer(r, d, UUID.randomUUID(), "Contei errado").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEVOLUCAO_NAO_PODE_SER_DESFEITA"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("remessa"))));
        assertThat(central(p)).isZero();
        assertThat(saldoDaMaleta(r, p)).isEqualTo(3);
        assertThat(movimentosDaMaleta(r)).isEqualTo(1);
    }

    // --- leitura ---

    @Test
    @SuppressWarnings("unchecked")
    void maletaMostraSaldoPorPecaValorEmAbertoEMovimentos() throws Exception {
        UUID a = peca("70.00", 10);
        UUID b = peca("30.00", 10);
        UUID r = enviada(vendedor("1000.00"), a, 5, b, 2);
        UUID d = devolverOk(r, a, 2);
        JsonNode m = maleta(r);
        assertThat(m.get("status").asString()).isEqualTo("ENVIADA");
        assertThat(dec(m.get("valorEmAberto"))).isEqualByComparingTo("270.00"); // 3 x 70 + 2 x 30
        assertThat(m.get("pecasEmAberto").asInt()).isEqualTo(5);
        assertThat(m.get("itens")).hasSize(2);
        JsonNode linhaA = null;
        for (JsonNode i : m.get("itens")) {
            if (a.toString().equals(i.get("produto").get("id").asString())) {
                linhaA = i;
            }
        }
        assertThat(linhaA).isNotNull();
        assertThat(linhaA.get("enviada").asInt()).isEqualTo(5);
        assertThat(linhaA.get("devolvida").asInt()).isEqualTo(2);
        assertThat(linhaA.get("saldo").asInt()).isEqualTo(3);
        assertThat(dec(linhaA.get("precoUnitario"))).isEqualByComparingTo("70.00");
        assertThat(m.get("movimentos")).hasSize(1);
        assertThat(m.get("movimentos").get(0).get("id").asString()).isEqualTo(d.toString());
        assertThat(m.get("movimentos").get(0).get("quantidade").asInt()).isEqualTo(-2);
        ler(BASE + "/" + UUID.randomUUID() + "/maleta").andExpect(status().isNotFound());
        // Não vaza custo.
        assertThat(m.toString()).doesNotContain("Custo").doesNotContain("custo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void maletasEmAbertoDoVendedorMostramValorESobraDoLimiteEAlimentamOEnvioSeguinte() throws Exception {
        UUID p = peca("70.00", 30);
        UUID v = vendedor("500.00");
        UUID a = enviada(v, p, 5); // 350
        JsonNode antes = corpoDe(ler(BASE + "/em-aberto?vendedorId=" + v).andExpect(status().isOk()));
        assertThat(dec(antes.get("limite"))).isEqualByComparingTo("500.00");
        assertThat(dec(antes.get("valorEmAberto"))).isEqualByComparingTo("350.00");
        assertThat(dec(antes.get("limiteRestante"))).isEqualByComparingTo("150.00");
        assertThat(antes.get("maletas")).hasSize(1);
        assertThat(dec(antes.get("maletas").get(0).get("valorEmAberto"))).isEqualByComparingTo("350.00");

        // Mais 3 peças (210) passam do limite; depois de devolver 2 da primeira maleta, cabem.
        JsonNode segunda = rascunho(v, p, 3);
        String enviarSegunda = BASE + "/" + segunda.get("id").asString() + "/enviar";
        enviarPost(enviarSegunda, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_CONSIGNACAO_EXCEDIDO"));
        devolverOk(a, p, 2);
        JsonNode depois = corpoDe(ler(BASE + "/em-aberto?vendedorId=" + v).andExpect(status().isOk()));
        assertThat(dec(depois.get("valorEmAberto"))).isEqualByComparingTo("210.00");
        assertThat(dec(depois.get("limiteRestante"))).isEqualByComparingTo("290.00");
        enviarPost(enviarSegunda, null).andExpect(status().isCreated());
        JsonNode fim = corpoDe(ler(BASE + "/em-aberto?vendedorId=" + v).andExpect(status().isOk()));
        assertThat(fim.get("maletas")).hasSize(2);
        assertThat(dec(fim.get("valorEmAberto"))).isEqualByComparingTo("420.00");
    }

    @Test
    void maletasEmAbertoDeVendedorSemLimiteEExigeVendedor() throws Exception {
        UUID v = vendedor(null);
        JsonNode r = corpoDe(ler(BASE + "/em-aberto?vendedorId=" + v).andExpect(status().isOk()));
        assertThat(r.get("limite").isNull()).isTrue();
        assertThat(r.get("limiteRestante").isNull()).isTrue();
        assertThat(r.get("semLimite").asBoolean()).isTrue();
        assertThat(r.get("limiteDefinido").asBoolean()).isTrue();
        // Sem limite definido (vendedor antigo): o limite aparece como "não definido", nunca como "sem limite".
        jdbc.update("UPDATE corona.vendedores SET limite_consignacao_ilimitado = false WHERE id = ?", v);
        JsonNode indefinido = corpoDe(ler(BASE + "/em-aberto?vendedorId=" + v).andExpect(status().isOk()));
        assertThat(indefinido.get("semLimite").asBoolean()).isFalse();
        assertThat(indefinido.get("limiteDefinido").asBoolean()).isFalse();
        assertThat(r.get("maletas")).isEmpty();
        ler(BASE + "/em-aberto").andExpect(status().isBadRequest());
        ler(BASE + "/em-aberto?vendedorId=" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // --- concorrência ---

    /**
     * Segunda trava do saldo da maleta, no banco, como o CHECK do estoque central (ADR-0008, item 4): um movimento que
     * deixaria a peça negativa na maleta é recusado mesmo sem passar pelo serviço (a Fase 5 grava movimentos que vêm do
     * aparelho). Cada tentativa roda numa transação desfeita no fim, para não deixar a conciliação diferente.
     */
    @Test
    void bancoRecusaMovimentoQueDeixariaOSaldoDaMaletaNegativo() throws Exception {
        UUID p = peca("50.00", 5);
        UUID remessa = enviada(vendedor(null), p, 2);
        try (Connection c = conectarComoAplicacao()) {
            assertThatThrownBy(() -> movimentoCru(c, remessa, p, -3)).hasMessageContaining("saldo negativo");
            c.rollback();
            movimentoCru(c, remessa, p, -2); // até zero pode
            assertThatThrownBy(() -> movimentoCru(c, remessa, p, -1)).hasMessageContaining("saldo negativo");
            c.rollback();
            movimentoCru(c, remessa, p, -1);
            movimentoCru(c, remessa, p, 1); // o que volta para a maleta não é conferido
            c.rollback();
        }
        assertThat(saldoDaMaleta(remessa, p)).isEqualTo(2);
        assertThat(movimentosDaMaleta(remessa)).isZero();
    }

    private static void movimentoCru(Connection c, UUID remessa, UUID produto, int quantidade) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade)
                VALUES (?, ?, ?, 'DEVOLUCAO_DONA', ?)""")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, remessa);
            ps.setObject(3, produto);
            ps.setInt(4, quantidade);
            ps.executeUpdate();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void devolucoesSimultaneasNuncaDeixamASaldoDaMaletaNegativo() throws Exception {
        UUID p = peca("10.00", 20);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tarefas.add(() -> devolver(r, linha(UUID.randomUUID(), p, 1)).andReturn().getResponse().getStatus());
        }
        List<Integer> respostas = paralelo(tarefas);
        assertThat(respostas.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(respostas.stream().filter(s -> s == 409).count()).isEqualTo(7);
        assertThat(saldoDaMaleta(r, p)).isZero();
        assertThat(central(p)).isEqualTo(20);
    }

    @Test
    @SuppressWarnings("unchecked")
    void devolucaoECancelamentoSimultaneosFicamConsistentes() throws Exception {
        for (int rodada = 0; rodada < 4; rodada++) {
            UUID p = peca("10.00", 10);
            UUID r = enviada(vendedor("1000.00"), p, 4);
            List<Callable<Integer>> tarefas = List.of(
                    () -> devolver(r, linha(UUID.randomUUID(), p, 2)).andReturn().getResponse().getStatus(),
                    () -> cancelar(r, "Cancelando").andReturn().getResponse().getStatus());
            List<Integer> respostas = paralelo(tarefas);
            // Ou a devolução entrou (e o cancelamento foi recusado), ou o cancelamento entrou (e a devolução foi recusada).
            if (statusDa(r).equals("CANCELADA")) {
                assertThat(respostas).containsExactly(409, 200);
                assertThat(central(p)).isEqualTo(10);
                assertThat(movimentosDaMaleta(r)).isZero();
            } else {
                assertThat(respostas).containsExactly(201, 409);
                assertThat(central(p)).isEqualTo(8);
                assertThat(saldoDaMaleta(r, p)).isEqualTo(2);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void saidaDoCentralESaldoNuncaNegativoSobConcorrenciaComEnvioEDevolucao() throws Exception {
        UUID p = peca("10.00", 6);
        UUID v = vendedor("100000.00");
        UUID a = enviada(v, p, 3);
        List<Callable<Integer>> tarefas = new ArrayList<>();
        // Cinco tentativas de baixar 2 peças do central (restam 3) ao mesmo tempo em que a maleta devolve 1.
        for (int i = 0; i < 5; i++) {
            tarefas.add(() -> {
                try {
                    estoque.registrar(new Pedido(null, p, TipoDeMovimento.SAIDA, 2, null, OrigemDoMovimento.MANUAL,
                            null), usuarios.dona().id());
                    return 200;
                } catch (RuntimeException e) {
                    return 409;
                }
            });
        }
        tarefas.add(() -> devolver(a, linha(UUID.randomUUID(), p, 1)).andReturn().getResponse().getStatus());
        paralelo(tarefas);
        assertThat(central(p)).isGreaterThanOrEqualTo(0);
        assertThat(saldoDaMaleta(a, p)).isBetween(0, 3);
        assertThat(estoque.conciliar().divergencias()).extracting(d -> d.produtoId()).doesNotContain(p);
    }

    // --- conciliação ---

    @Test
    @SuppressWarnings("unchecked")
    void conciliacaoComMaletasFechaDepoisDeCompraRemessaDevolucaoCancelamentoEstornoEInventario() throws Exception {
        UUID p = peca("10.00", 0);
        UUID q = peca("20.00", 0);
        UUID dona = usuarios.dona().id();
        estoque.registrar(new Pedido(null, p, TipoDeMovimento.ENTRADA, 20, null, OrigemDoMovimento.COMPRA, null), dona);
        estoque.registrar(new Pedido(null, q, TipoDeMovimento.ENTRADA, 10, null, OrigemDoMovimento.COMPRA, null), dona);
        UUID v = vendedor("100000.00");
        UUID a = enviada(v, p, 8, q, 3);
        UUID b = enviada(v, p, 4);
        UUID d = devolverOk(a, p, 3);
        devolverOk(a, q, 1);
        desfazer(a, d, UUID.randomUUID(), "Contei errado").andExpect(status().isCreated());
        devolverOk(a, p, 2);
        cancelar(b, "Enviada por engano").andExpect(status().isOk());
        estoque.registrar(new Pedido(null, p, TipoDeMovimento.AVARIA, 1, "Quebrou", OrigemDoMovimento.MANUAL, null), dona);
        estoque.ajustarPara(UUID.randomUUID(), q, central(q) + 2, "Inventário", dona);
        assertThat(estoque.conciliar().divergencias()).isEmpty();
        assertThat(estoque.conciliar().diferencasComMaletas()).isEmpty();
        JsonNode corpo = corpoDe(ler("/api/dona/estoque/conciliacao").andExpect(status().isOk()));
        assertThat(corpo.get("divergencias")).isEmpty();
        assertThat(corpo.get("diferencasComMaletas")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void conciliacaoComMaletasFechaDepoisDeOperacoesSimultaneas() throws Exception {
        UUID p = peca("10.00", 50);
        UUID dona = usuarios.dona().id();
        UUID v = vendedor("100000.00");
        UUID a = enviada(v, p, 10);
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tarefas.add(() -> devolver(a, linha(UUID.randomUUID(), p, 2)).andReturn().getResponse().getStatus());
        }
        for (int i = 0; i < 3; i++) {
            tarefas.add(() -> {
                try {
                    estoque.registrar(new Pedido(null, p, TipoDeMovimento.AVARIA, 1, "Quebrou", OrigemDoMovimento.MANUAL, null), dona);
                    return 200;
                } catch (RuntimeException e) {
                    return 409;
                }
            });
        }
        tarefas.add(() -> {
            try {
                return enviada(v, p, 5) != null ? 201 : 0;
            } catch (Exception e) {
                return 500;
            }
        });
        paralelo(tarefas);
        assertThat(saldoDaMaleta(a, p)).isGreaterThanOrEqualTo(0);
        assertThat(estoque.conciliar().divergencias()).isEmpty();
        assertThat(estoque.conciliar().diferencasComMaletas()).isEmpty();
    }

    @Test
    void relatorioDeConferenciaDoEstoqueMostraADiferencaComAsMaletas() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        String rota = "/api/dona/relatorios/estoque/conciliacao?produtoId=" + p;
        assertThat(corpoDe(ler(rota).andExpect(status().isOk())).get("total").asLong()).isZero();
        jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (?, ?, ?, 'DEVOLUCAO_DONA', -1)",
                UUID.randomUUID(), r, p);
        JsonNode corpo = corpoDe(ler(rota).andExpect(status().isOk()));
        assertThat(corpo.get("total").asLong()).isEqualTo(1);
        JsonNode linha = corpo.get("itens").get(0);
        assertThat(linha.get("saldoGuardado").asInt()).isEqualTo(5);
        assertThat(linha.get("nasMaletas").asLong()).isEqualTo(4);
        assertThat(linha.get("diferencaComMaletas").asLong()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void conciliacaoComMaletasApontaADiferencaProvocadaNoBanco() throws Exception {
        UUID p = peca("10.00", 10);
        UUID r = enviada(vendedor("1000.00"), p, 5);
        assertThat(estoque.conciliar().diferencasComMaletas()).extracting(d -> d.produtoId()).doesNotContain(p);
        // Uma saída da maleta sem a devolução correspondente no estoque: uma peça "some".
        UUID movimento = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (?, ?, ?, 'DEVOLUCAO_DONA', -1)",
                movimento, r, p);
        JsonNode corpo = corpoDe(ler("/api/dona/estoque/conciliacao").andExpect(status().isOk()));
        JsonNode achada = null;
        for (JsonNode d : corpo.get("diferencasComMaletas")) {
            if (p.toString().equals(d.get("produto").get("id").asString())) {
                achada = d;
            }
        }
        assertThat(achada).isNotNull();
        assertThat(achada.get("estoqueCentral").asInt()).isEqualTo(5);
        assertThat(achada.get("saldoNasMaletas").asInt()).isEqualTo(4);
        assertThat(achada.get("esperado").asInt()).isEqualTo(10);
        assertThat(achada.get("diferenca").asInt()).isEqualTo(-1);
        // Conserta (movimento imutável: um estorno de volta) para não sujar os outros testes.
        jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, estorna_id) "
                + "VALUES (?, ?, ?, 'DEVOLUCAO_DONA', 1, ?)", UUID.randomUUID(), r, p, movimento);
        assertThat(estoque.conciliar().diferencasComMaletas()).extracting(d -> d.produtoId()).doesNotContain(p);
    }
}
