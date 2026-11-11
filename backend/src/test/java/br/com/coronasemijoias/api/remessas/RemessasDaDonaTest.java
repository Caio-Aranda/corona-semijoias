package br.com.coronasemijoias.api.remessas;

import static org.assertj.core.api.Assertions.assertThat;
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

/** Remessas (tarefa 3.2; ADR-0010): rascunho, envio que baixa o estoque, limite, restrição, preço congelado e idempotência. */
class RemessasDaDonaTest extends TesteDeIntegracao {

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

    private String textoDe(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    // --- fixtures ---

    private UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(2200, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(enviarPost("/api/dona/semanas", mapa("numero", 50_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private UUID vendedor(String limite, UUID restricao) throws Exception {
        Map<String, Object> corpo = mapa("nome", "Vendedor " + UUID.randomUUID().toString().substring(0, 8));
        if (limite != null) {
            corpo.put("limiteConsignacao", limite);
        } else {
            corpo.put("semLimite", true); // o limite é obrigatório (V026)
        }
        if (restricao != null) {
            corpo.put("restricaoId", restricao);
        }
        return UUID.fromString(corpoDe(enviarPost("/api/dona/vendedores", corpo).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID restricao(boolean impede) throws Exception {
        return UUID.fromString(corpoDe(enviarPost("/api/dona/restricoes", mapa("codigo", "R" + UUID.randomUUID().toString().substring(0, 8),
                "descricao", "Atraso no acerto", "bloqueiaRemessa", impede)).andExpect(status().isCreated())).get("id").asString());
    }

    /** Peça com o preço de venda e saldo central lançado pelo serviço de estoque (nunca direto na tabela). */
    private UUID peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(enviarPost("/api/dona/categorias", mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(enviarPost("/api/dona/produtos", mapa("nome", "Brinco " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "R-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return id;
    }

    private JsonNode rascunho(UUID id, UUID vendedor, UUID semana) throws Exception {
        return corpoDe(enviarPost(BASE, mapa("id", id, "vendedorId", vendedor, "semanaId", semana, "tipo", "NOVA"))
                .andExpect(status().isCreated()));
    }

    private JsonNode comItem(JsonNode remessa, UUID produto, int quantidade) throws Exception {
        return corpoDe(mvc.perform(put(BASE + "/" + remessa.get("id").asString() + "/itens/" + produto).with(dona())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", quantidade, "version", remessa.get("version").asInt()))))
                .andExpect(status().isOk()));
    }

    /** Rascunho pronto com os itens (produto, quantidade). */
    private JsonNode montada(UUID vendedor, Object... pares) throws Exception {
        JsonNode r = rascunho(UUID.randomUUID(), vendedor, semana());
        for (int i = 0; i < pares.length; i += 2) {
            r = comItem(r, (UUID) pares[i], (Integer) pares[i + 1]);
        }
        return r;
    }

    private ResultActions enviarRemessa(JsonNode r) throws Exception {
        return enviarPost(BASE + "/" + r.get("id").asString() + "/enviar", null);
    }

    private int saldo(UUID produto) {
        return jdbc.queryForObject("SELECT estoque_atual FROM corona.produtos WHERE id = ?", Integer.class, produto);
    }

    private int movimentos(UUID produto) {
        return jdbc.queryForObject("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ?", Integer.class, produto);
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
    void vendedorRecebe403ESemLoginRecebe401() throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        for (String rota : List.of("", "/" + id)) {
            mvc.perform(get(BASE + rota).with(vendedor)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + rota)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(BASE).with(vendedor).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + id + "/enviar").with(vendedor)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + id + "/enviar")).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE + "/" + id + "?version=1").with(vendedor)).andExpect(status().isForbidden());
    }

    // --- rascunho ---

    @Test
    void rascunhoNaoMexeNoEstoqueETemTotaisCalculadosPeloServidor() throws Exception {
        UUID p = peca("70.00", 10);
        int antes = movimentos(p);
        JsonNode r = montada(vendedor("1000.00", null), p, 3);
        assertThat(r.get("status").asString()).isEqualTo("RASCUNHO");
        assertThat(r.get("totalPecas").asInt()).isEqualTo(3);
        assertThat(dec(r.get("totalValor"))).isEqualByComparingTo("210.00");
        assertThat(saldo(p)).isEqualTo(10);
        assertThat(movimentos(p)).isEqualTo(antes);
        // O mesmo produto de novo troca a quantidade (uma linha por produto) e remover tira a linha.
        r = comItem(r, p, 5);
        assertThat(r.get("itens")).hasSize(1);
        assertThat(r.get("totalPecas").asInt()).isEqualTo(5);
        JsonNode sem = corpoDe(mvc.perform(delete(BASE + "/" + r.get("id").asString() + "/itens/" + p + "?version="
                + r.get("version").asInt()).with(dona())).andExpect(status().isOk()));
        assertThat(sem.get("itens")).isEmpty();
        assertThat(saldo(p)).isEqualTo(10);
    }

    @Test
    void versaoVelhaNoRascunhoDa409() throws Exception {
        UUID p = peca("10.00", 5);
        JsonNode r = rascunho(UUID.randomUUID(), vendedor("100.00", null), semana());
        comItem(r, p, 1);
        mvc.perform(put(BASE + "/" + r.get("id").asString() + "/itens/" + p).with(dona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", 2, "version", r.get("version").asInt()))))
                .andExpect(status().isConflict());
    }

    @Test
    void quantidadeInvalidaEProdutoExcluidoOuInativoSaoRecusadosNoRascunho() throws Exception {
        UUID p = peca("10.00", 5);
        JsonNode r = rascunho(UUID.randomUUID(), vendedor("100.00", null), semana());
        for (int q : new int[] {0, -1}) {
            mvc.perform(put(BASE + "/" + r.get("id").asString() + "/itens/" + p).with(dona()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", q, "version", r.get("version").asInt()))))
                    .andExpect(status().isBadRequest());
        }
        UUID inativo = peca("10.00", 5);
        jdbc.update("UPDATE corona.produtos SET ativo = false WHERE id = ?", inativo);
        UUID excluido = peca("10.00", 5);
        jdbc.update("UPDATE corona.produtos SET deleted_at = now() WHERE id = ?", excluido);
        for (UUID ruim : List.of(inativo, excluido, UUID.randomUUID())) {
            mvc.perform(put(BASE + "/" + r.get("id").asString() + "/itens/" + ruim).with(dona()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", 1, "version", r.get("version").asInt()))))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void rascunhoExcluidoSomeDaListaMasContinuaNoBanco() throws Exception {
        JsonNode r = rascunho(UUID.randomUUID(), vendedor("100.00", null), semana());
        mvc.perform(delete(BASE + "/" + r.get("id").asString() + "?version=" + r.get("version").asInt()).with(dona()))
                .andExpect(status().isNoContent());
        ler(BASE + "/" + r.get("id").asString()).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM corona.remessas WHERE id = ?", Boolean.class,
                UUID.fromString(r.get("id").asString()))).isTrue();
    }

    // --- criação: vendedor e idempotência ---

    @Test
    void criacaoEhIdempotenteEConteudoDiferenteDa409() throws Exception {
        UUID v = vendedor("100.00", null);
        UUID s = semana();
        UUID id = UUID.randomUUID();
        Map<String, Object> corpo = mapa("id", id, "vendedorId", v, "semanaId", s, "tipo", "NOVA");
        enviarPost(BASE, corpo).andExpect(status().isCreated());
        enviarPost(BASE, corpo).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.remessas WHERE id = ?", Integer.class, id)).isEqualTo(1);
        enviarPost(BASE, mapa("id", id, "vendedorId", v, "semanaId", s, "tipo", "REPOSICAO")).andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.codigo")
                        .value("REMESSA_JA_REGISTRADA_DIFERENTE"));
    }

    @Test
    void vendedorInativoOuComRestricaoQueImpedeNaoRecebeRascunhoNemEnvio() throws Exception {
        UUID p = peca("10.00", 5);
        UUID s = semana();
        UUID inativo = vendedor("100.00", null);
        jdbc.update("UPDATE corona.vendedores SET ativo = false WHERE id = ?", inativo);
        enviarPost(BASE, mapa("id", UUID.randomUUID(), "vendedorId", inativo, "semanaId", s, "tipo", "NOVA"))
                .andExpect(status().isConflict());
        UUID impedido = vendedor("100.00", restricao(true));
        enviarPost(BASE, mapa("id", UUID.randomUUID(), "vendedorId", impedido, "semanaId", s, "tipo", "NOVA"))
                .andExpect(status().isConflict());

        // Rascunho feito antes: o vendedor fica inativo ou ganha a restrição, e o envio recusa sem baixar nada.
        UUID depois = vendedor("100.00", null);
        JsonNode r = montada(depois, p, 1);
        jdbc.update("UPDATE corona.vendedores SET ativo = false WHERE id = ?", depois);
        enviarRemessa(r).andExpect(status().isConflict());
        assertThat(saldo(p)).isEqualTo(5);
        UUID comRestricao = vendedor("100.00", null);
        JsonNode r2 = montada(comRestricao, p, 1);
        jdbc.update("UPDATE corona.vendedores SET restricao_id = ? WHERE id = ?", restricao(true), comRestricao);
        enviarRemessa(r2).andExpect(status().isConflict());
        assertThat(saldo(p)).isEqualTo(5);
    }

    @Test
    void restricaoQueNaoImpedeSoAparecemComoAviso() throws Exception {
        UUID p = peca("10.00", 5);
        UUID v = vendedor("100.00", restricao(false));
        JsonNode r = montada(v, p, 1);
        assertThat(r.get("avisos")).isNotEmpty();
        enviarRemessa(r).andExpect(status().isCreated());
    }

    // --- envio ---

    @Test
    void envioBaixaOEstoqueCongelaOPrecoEMudarOPrecoDepoisNaoMudaARemessa() throws Exception {
        UUID p = peca("70.00", 10);
        JsonNode r = montada(vendedor("1000.00", null), p, 4);
        JsonNode enviada = corpoDe(enviarRemessa(r).andExpect(status().isCreated()));
        assertThat(enviada.get("status").asString()).isEqualTo("ENVIADA");
        assertThat(saldo(p)).isEqualTo(6);
        assertThat(dec(enviada.get("totalValor"))).isEqualByComparingTo("280.00");
        // Um movimento REMESSA, origem MALETA, ligado à remessa.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM corona.movimentacoes_estoque
                 WHERE produto_id = ? AND tipo = 'REMESSA' AND origem_tipo = 'MALETA' AND origem_id = ? AND quantidade = -4""",
                Integer.class, p, UUID.fromString(r.get("id").asString()))).isEqualTo(1);

        jdbc.update("UPDATE corona.produtos SET preco_venda = 99.00 WHERE id = ?", p);
        JsonNode lida = corpoDe(ler(BASE + "/" + r.get("id").asString()).andExpect(status().isOk()));
        assertThat(dec(lida.get("itens").get(0).get("precoUnitario"))).isEqualByComparingTo("70.00");
        assertThat(dec(lida.get("totalValor"))).isEqualByComparingTo("280.00");
        // Enviada não aceita mais edição.
        mvc.perform(put(BASE + "/" + r.get("id").asString() + "/itens/" + p).with(dona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", 1, "version", lida.get("version").asInt()))))
                .andExpect(status().isConflict());
    }

    @Test
    void precoEhCongeladoNoEnvioENaoNoRascunho() throws Exception {
        UUID p = peca("70.00", 10);
        JsonNode r = montada(vendedor("1000.00", null), p, 2);
        jdbc.update("UPDATE corona.produtos SET preco_venda = 80.00 WHERE id = ?", p);
        JsonNode enviada = corpoDe(enviarRemessa(r).andExpect(status().isCreated()));
        assertThat(dec(enviada.get("totalValor"))).isEqualByComparingTo("160.00");
    }

    @Test
    void envioSemItensEhRecusado() throws Exception {
        enviarRemessa(rascunho(UUID.randomUUID(), vendedor("100.00", null), semana())).andExpect(status().isBadRequest());
    }

    @Test
    void envioIdempotenteNaoBaixaDeNovo() throws Exception {
        UUID p = peca("10.00", 10);
        JsonNode r = montada(vendedor("1000.00", null), p, 3);
        enviarRemessa(r).andExpect(status().isCreated());
        int movimentos = movimentos(p);
        enviarRemessa(r).andExpect(status().isOk());
        enviarRemessa(r).andExpect(status().isOk());
        assertThat(saldo(p)).isEqualTo(7);
        assertThat(movimentos(p)).isEqualTo(movimentos);
    }

    @Test
    void saldoInsuficienteRecusaARemessaInteiraDizendoQualPeca() throws Exception {
        UUID boa = peca("10.00", 10);
        UUID curta = peca("10.00", 2);
        JsonNode r = montada(vendedor("1000.00", null), boa, 3, curta, 5);
        String texto = textoDe(enviarRemessa(r).andExpect(status().isConflict()));
        assertThat(texto).contains("Não há peças suficientes no estoque").contains("Brinco");
        assertThat(saldo(boa)).isEqualTo(10);
        assertThat(saldo(curta)).isEqualTo(2);
        assertThat(corpoDe(ler(BASE + "/" + r.get("id").asString())).get("status").asString()).isEqualTo("RASCUNHO");
    }

    @Test
    void produtoInativoOuExcluidoDepoisDeMontarRecusaOEnvio() throws Exception {
        UUID a = peca("10.00", 5);
        UUID b = peca("10.00", 5);
        UUID v = vendedor("1000.00", null);
        JsonNode r = montada(v, a, 1, b, 1);
        jdbc.update("UPDATE corona.produtos SET ativo = false WHERE id = ?", a);
        enviarRemessa(r).andExpect(status().is4xxClientError());
        jdbc.update("UPDATE corona.produtos SET ativo = true, deleted_at = now() WHERE id = ?", a);
        enviarRemessa(r).andExpect(status().is4xxClientError());
        assertThat(saldo(a)).isEqualTo(5);
        assertThat(saldo(b)).isEqualTo(5);
    }

    // --- limite de consignação ---

    @Test
    void limiteExcedidoRecusaComLimiteEmAbertoEQuantoFalta() throws Exception {
        UUID p = peca("100.00", 20);
        UUID v = vendedor("500.00", null);
        enviarRemessa(montada(v, p, 3)).andExpect(status().isCreated());
        // Em aberto 300; esta remessa vale 300; limite 500: passa 100.
        String texto = textoDe(enviarRemessa(montada(v, p, 3)).andExpect(status().isConflict()));
        assertThat(texto).contains("500,00").contains("300,00").contains("100,00");
        assertThat(saldo(p)).isEqualTo(17);
    }

    @Test
    void noLimiteExatoPassaEUmCentavoAMaisNao() throws Exception {
        UUID p = peca("100.00", 20);
        UUID v = vendedor("500.00", null);
        enviarRemessa(montada(v, p, 2)).andExpect(status().isCreated());
        enviarRemessa(montada(v, p, 3)).andExpect(status().isCreated());
        UUID q = peca("0.01", 5);
        enviarRemessa(montada(v, q, 1)).andExpect(status().isConflict());
        assertThat(saldo(q)).isEqualTo(5);
    }

    @Test
    void reposicaoEntraNaMesmaConta() throws Exception {
        UUID p = peca("100.00", 20);
        UUID v = vendedor("300.00", null);
        enviarRemessa(montada(v, p, 3)).andExpect(status().isCreated());
        JsonNode repo = corpoDe(enviarPost(BASE, mapa("id", UUID.randomUUID(), "vendedorId", v, "semanaId", semana(), "tipo", "REPOSICAO"))
                .andExpect(status().isCreated()));
        repo = comItem(repo, p, 1);
        enviarRemessa(repo).andExpect(status().isConflict());
    }

    @Test
    void semLimiteMarcadoDePropositoNaoBloqueia() throws Exception {
        UUID p = peca("1000.00", 20);
        UUID v = vendedor(null, null); // o helper marca "Sem limite"
        enviarRemessa(montada(v, p, 15)).andExpect(status().isCreated());
    }

    @Test
    void vendedorSemLimiteDefinidoNaoRecebeMaletaNemRascunho() throws Exception {
        UUID p = peca("10.00", 20);
        UUID v = vendedor(null, null);
        UUID semana = semana();
        JsonNode pronto = comItem(rascunho(UUID.randomUUID(), v, semana), p, 1);
        // Como ficam os vendedores antigos (limite 0 virou "sem limite definido" na V026): os dois campos vazios.
        jdbc.update("UPDATE corona.vendedores SET limite_consignacao = NULL, limite_consignacao_ilimitado = false WHERE id = ?", v);
        String texto = textoDe(enviarRemessa(pronto).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_CONSIGNACAO_NAO_DEFINIDO")));
        assertThat(texto).contains("Defina o limite deste vendedor antes de montar a maleta");
        enviarPost(BASE, mapa("id", UUID.randomUUID(), "vendedorId", v, "semanaId", semana, "tipo", "NOVA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("LIMITE_DE_CONSIGNACAO_NAO_DEFINIDO"));
        assertThat(saldo(p)).isEqualTo(20);
        // Definido o limite (ou "Sem limite"), a mesma remessa segue.
        jdbc.update("UPDATE corona.vendedores SET limite_consignacao_ilimitado = true WHERE id = ?", v);
        enviarRemessa(pronto).andExpect(status().isCreated());
    }

    @Test
    void limiteZeroValeZeroENaoSignificaSemLimite() throws Exception {
        UUID p = peca("10.00", 20);
        UUID v = vendedor("0.00", null);
        enviarRemessa(montada(v, p, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_CONSIGNACAO_EXCEDIDO"));
        assertThat(saldo(p)).isEqualTo(20);
    }

    @Test
    void dossEnviosSimultaneosDoMesmoVendedorNaoFuramOLimite() throws Exception {
        UUID p = peca("100.00", 50);
        UUID v = vendedor("150.00", null);
        JsonNode a = montada(v, p, 1);
        JsonNode b = montada(v, p, 1);
        List<Integer> codigos = paralelo(List.of(
                () -> enviarRemessa(a).andReturn().getResponse().getStatus(),
                () -> enviarRemessa(b).andReturn().getResponse().getStatus()));
        assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        assertThat(saldo(p)).isEqualTo(49);
    }

    @Test
    void envioSimultaneoDeVendedoresDiferentesNuncaDeixaOSaldoNegativo() throws Exception {
        UUID p = peca("10.00", 5);
        JsonNode a = montada(vendedor("1000.00", null), p, 5);
        JsonNode b = montada(vendedor("1000.00", null), p, 5);
        List<Integer> codigos = paralelo(List.of(
                () -> enviarRemessa(a).andReturn().getResponse().getStatus(),
                () -> enviarRemessa(b).andReturn().getResponse().getStatus()));
        assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        assertThat(saldo(p)).isZero();
    }

    // --- conciliação ---

    @Test
    void estoqueMaisMaletaEhIgualAoEstoqueAntes() throws Exception {
        UUID p = peca("10.00", 12);
        UUID v = vendedor("1000.00", null);
        JsonNode r = montada(v, p, 5);
        enviarRemessa(r).andExpect(status().isCreated());
        int naMaleta = jdbc.queryForObject("""
                SELECT coalesce(sum(i.qtd_enviada), 0) FROM corona.itens_remessa i
                  JOIN corona.remessas x ON x.id = i.remessa_id
                 WHERE i.produto_id = ? AND x.status = 'ENVIADA' AND i.deleted_at IS NULL""", Integer.class, p);
        assertThat(saldo(p) + naMaleta).isEqualTo(12);
        assertThat(corpoDe(ler("/api/dona/estoque/conciliacao").andExpect(status().isOk())).get("divergencias")).isEmpty();
    }

    // --- listagem e custo ---

    @Test
    void listaComFiltrosENuncaMostraCusto() throws Exception {
        UUID p = peca("10.00", 5);
        UUID v = vendedor("1000.00", null);
        JsonNode r = montada(v, p, 1);
        JsonNode lista = corpoDe(ler(BASE + "?vendedorId=" + v + "&status=RASCUNHO").andExpect(status().isOk()));
        assertThat(lista.toString()).contains(r.get("id").asString());
        assertThat(corpoDe(ler(BASE + "?vendedorId=" + v + "&status=ENVIADA")).get("itens")).isEmpty();
        String texto = textoDe(ler(BASE + "/" + r.get("id").asString()).andExpect(status().isOk())).toLowerCase();
        assertThat(texto).doesNotContain("custo");
    }
}
