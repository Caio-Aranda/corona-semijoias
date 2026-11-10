package br.com.coronasemijoias.api.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.sync.FiltroDoVendedor;
import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import br.com.coronasemijoias.api.testes.LeituraDoPull;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Compras (tarefa 2.3; ADR-0008): entrada por código, totais, custo, idempotência, desfazer e concorrência. */
class ComprasDaDonaTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/compras";

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
    ServicoDeCompras compras;

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

    private ResultActions enviar(String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
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

    private UUID fornecedor() throws Exception {
        String nome = "Fornecedor " + UUID.randomUUID().toString().substring(0, 8);
        return UUID.fromString(corpoDe(enviar("/api/dona/fornecedores", dona(), mapa("razaoSocial", nome))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private record Peca(UUID id, String codigo) {
    }

    private Peca peca(String custo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(enviar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        String codigo = "C-" + sufixo;
        JsonNode criado = corpoDe(enviar("/api/dona/produtos", dona(), mapa("nome", "Brinco " + sufixo,
                "categoriaId", categoria, "precoVenda", "70.00", "precoCusto", custo, "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", codigo, "tipo", "LOJA", "principal", true)))).andExpect(status().isCreated()));
        return new Peca(UUID.fromString(criado.get("id").asString()), codigo);
    }

    private static Map<String, Object> porProduto(Peca p, int quantidade, String custo) {
        return mapa("produtoId", p.id(), "quantidade", quantidade, "custoUnitario", custo);
    }

    private static Map<String, Object> porCodigo(String codigo, int quantidade, String custo) {
        return mapa("codigo", codigo, "quantidade", quantidade, "custoUnitario", custo);
    }

    private Map<String, Object> compra(UUID id, UUID fornecedor, Object... itens) {
        return mapa("id", id, "fornecedorId", fornecedor, "dataCompra", "2026-11-01", "numero", "NF 10",
                "itens", List.of(itens));
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    private int saldo(UUID produto) {
        return jdbc.queryForObject("SELECT estoque_atual FROM corona.produtos WHERE id = ?", Integer.class, produto);
    }

    private BigDecimal custo(UUID produto) {
        return jdbc.queryForObject("SELECT preco_custo FROM corona.produtos_custos WHERE produto_id = ?", BigDecimal.class, produto);
    }

    private int quantas(String tabela) {
        return jdbc.queryForObject("SELECT count(*) FROM corona." + tabela, Integer.class);
    }

    private JsonNode receber(Map<String, Object> corpo) throws Exception {
        return corpoDe(enviar(BASE, dona(), corpo).andExpect(status().isCreated()));
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

    private void conciliado() throws Exception {
        assertThat(corpoDe(ler("/api/dona/estoque/conciliacao").andExpect(status().isOk())).get("divergencias")).isEmpty();
    }

    // --- permissão ---

    @Test
    void vendedorRecebe403EmTodasAsRotasESemLoginRecebe401() throws Exception {
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        UUID id = UUID.randomUUID();
        enviar(BASE, vendedor, mapa()).andExpect(status().isForbidden());
        enviar(BASE + "/" + id + "/desfazer", vendedor, mapa()).andExpect(status().isForbidden());
        for (String rota : List.of("", "/" + id, "/produtos/" + id + "/custos")) {
            mvc.perform(get(BASE + rota).with(vendedor)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + rota)).andExpect(status().isUnauthorized());
        }
    }

    // --- entrada, totais e custo ---

    @Test
    void compraPorProdutoEPorCodigoSomaOEstoqueRecalculaOsTotaisEAtualizaOCusto() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("5.00");
        Peca b = peca("7.00");
        // O código chega com espaços e em maiúsculas diferentes: vale do mesmo jeito (ADR-0007).
        JsonNode r = receber(compra(UUID.randomUUID(), f, porProduto(a, 3, "3.34"),
                porCodigo("  " + b.codigo().toLowerCase() + " ", 2, "10.10")));
        assertThat(dec(r.get("compra").get("total"))).isEqualByComparingTo("30.22");
        assertThat(r.get("compra").get("situacao").asString()).isEqualTo("REGISTRADA");
        assertThat(r.get("itens")).hasSize(2);
        assertThat(r.get("itens").get(0).get("produto").has("fotoPrincipal")).as("a foto vem junto, mesmo sem foto").isTrue();
        assertThat(saldo(a.id())).isEqualTo(3);
        assertThat(saldo(b.id())).isEqualTo(2);
        assertThat(custo(a.id())).isEqualByComparingTo("3.34");
        assertThat(custo(b.id())).isEqualByComparingTo("10.10");
        // 3 x 3,34 = 10,02 (o custo é arredondado ANTES de multiplicar).
        BigDecimal totalDoItem = jdbc.queryForObject("SELECT total FROM corona.itens_compra WHERE produto_id = ?",
                BigDecimal.class, a.id());
        assertThat(totalDoItem).isEqualByComparingTo("10.02");
        assertThat(quantas("movimentacoes_estoque")).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT origem_tipo FROM corona.movimentacoes_estoque WHERE produto_id = ?",
                String.class, a.id())).isEqualTo("COMPRA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.historico_custo WHERE produto_id = ?", Integer.class,
                a.id())).isEqualTo(1);
        conciliado();
    }

    @Test
    void servidorIgnoraTotalEnviadoPeloAplicativo() throws Exception {
        Peca a = peca("1.00");
        Map<String, Object> corpo = compra(UUID.randomUUID(), fornecedor(), porProduto(a, 2, "4.00"));
        corpo.put("total", "0.01");
        JsonNode r = receber(corpo);
        assertThat(dec(r.get("compra").get("total"))).isEqualByComparingTo("8.00");
    }

    @Test
    void contratoRecusaMaisDeDuasCasasENegativoECustoZeroVale() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        enviar(BASE, dona(), compra(UUID.randomUUID(), f, porProduto(a, 1, "0.005"))).andExpect(status().isBadRequest());
        enviar(BASE, dona(), compra(UUID.randomUUID(), f, porProduto(a, 1, "-1.00"))).andExpect(status().isBadRequest());
        assertThat(saldo(a.id())).isZero();
        receber(compra(UUID.randomUUID(), f, porProduto(a, 1, "0.00")));
        assertThat(custo(a.id())).isEqualByComparingTo("0.00");
    }

    @Test
    void servicoArredondaOCustoMetadeParaCimaAntesDeMultiplicar() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        Peca b = peca("1.00");
        UUID id = UUID.randomUUID();
        compras.registrar(new ServicoDeCompras.PedidoDeCompra(id, f, java.time.LocalDate.of(2026, 11, 1), null, null,
                List.of(new ServicoDeCompras.ItemPedido(a.id(), null, 3, new BigDecimal("3.335")),
                        new ServicoDeCompras.ItemPedido(b.id(), null, 1, new BigDecimal("0.004")))), usuarios.dona().id());
        assertThat(custo(a.id())).isEqualByComparingTo("3.34");
        assertThat(custo(b.id())).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT total FROM corona.compras WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("10.02");
    }

    @Test
    void pedidosInvalidosSaoRecusadosSemGravarNada() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        int compras = quantas("compras");
        enviar(BASE, dona(), compra(UUID.randomUUID(), f)).andExpect(status().isBadRequest());
        enviar(BASE, dona(), compra(UUID.randomUUID(), f, porProduto(a, 0, "1.00"))).andExpect(status().isBadRequest());
        enviar(BASE, dona(), compra(UUID.randomUUID(), f, mapa("quantidade", 1, "custoUnitario", "1.00")))
                .andExpect(status().isBadRequest());
        enviar(BASE, dona(), compra(UUID.randomUUID(), f, mapa("produtoId", a.id(), "codigo", a.codigo(), "quantidade", 1,
                "custoUnitario", "1.00"))).andExpect(status().isBadRequest());
        enviar(BASE, dona(), compra(UUID.randomUUID(), UUID.randomUUID(), porProduto(a, 1, "1.00")))
                .andExpect(status().isBadRequest());
        enviar(BASE, dona(), compra(UUID.randomUUID(), f, porProduto(new Peca(UUID.randomUUID(), "x"), 1, "1.00")))
                .andExpect(status().isBadRequest());
        Map<String, Object> futura = compra(UUID.randomUUID(), f, porProduto(a, 1, "1.00"));
        futura.put("dataCompra", "2999-01-01");
        enviar(BASE, dona(), futura).andExpect(status().isBadRequest());
        Map<String, Object> semFornecedor = compra(UUID.randomUUID(), f, porProduto(a, 1, "1.00"));
        semFornecedor.remove("fornecedorId");
        enviar(BASE, dona(), semFornecedor).andExpect(status().isBadRequest());
        assertThat(quantas("compras")).isEqualTo(compras);
        assertThat(saldo(a.id())).isZero();
    }

    @Test
    void codigoInexistenteDaErroPorLinhaESemGravarNada() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        int movimentos = quantas("movimentacoes_estoque");
        ResultActions r = enviar(BASE, dona(), compra(UUID.randomUUID(), f, porProduto(a, 1, "1.00"),
                porCodigo("NAO-EXISTE-1", 1, "1.00"), porCodigo("NAO-EXISTE-2", 1, "1.00"))).andExpect(status().isBadRequest());
        JsonNode campos = corpoDe(r).get("campos");
        assertThat(campos).hasSize(2);
        assertThat(campos.get(0).get("campo").asString()).isEqualTo("itens[1].codigo");
        assertThat(campos.get(0).get("mensagem").asString()).contains("NAO-EXISTE-1").contains("Cadastre a peça");
        assertThat(campos.get(1).get("campo").asString()).isEqualTo("itens[2].codigo");
        assertThat(saldo(a.id())).isZero();
        assertThat(quantas("movimentacoes_estoque")).isEqualTo(movimentos);
    }

    @Test
    void mesmaPecaDuasVezesNaCompraEhRecusada_AindaQueUmaSejaPorCodigo() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        ResultActions r = enviar(BASE, dona(), compra(UUID.randomUUID(), f, porProduto(a, 1, "1.00"),
                porCodigo(a.codigo().toUpperCase(), 2, "2.00"))).andExpect(status().isBadRequest());
        assertThat(textoDe(r)).contains("aparece mais de uma vez").contains("Some as quantidades");
        assertThat(saldo(a.id())).isZero();
    }

    // --- idempotência ---

    @Test
    void mesmoIdEMesmoConteudoDevolveACompraSemSomarDeNovo() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        Peca b = peca("1.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 4, "2.00"), porCodigo(b.codigo(), 1, "3.00")));
        // O reenvio pode vir com os itens em outra ordem e a peça por código: é o mesmo conteúdo.
        JsonNode de_novo = corpoDe(enviar(BASE, dona(), compra(id, f, porCodigo(b.codigo(), 1, "3.00"),
                porProduto(a, 4, "2.00"))).andExpect(status().isOk()));
        assertThat(de_novo.get("compra").get("id").asString()).isEqualTo(id.toString());
        assertThat(saldo(a.id())).isEqualTo(4);
        assertThat(saldo(b.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.itens_compra WHERE compra_id = ?", Integer.class, id))
                .isEqualTo(2);
        conciliado();
    }

    @Test
    void mesmoIdComConteudoDiferenteEhConflito() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 4, "2.00")));
        enviar(BASE, dona(), compra(id, f, porProduto(a, 5, "2.00"))).andExpect(status().isConflict());
        enviar(BASE, dona(), compra(id, f, porProduto(a, 4, "2.01"))).andExpect(status().isConflict());
        enviar(BASE, dona(), compra(id, fornecedor(), porProduto(a, 4, "2.00"))).andExpect(status().isConflict());
        assertThat(saldo(a.id())).isEqualTo(4);
    }

    @Test
    void reenvioSimultaneoDoMesmoIdSomaUmaVezSo() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        UUID id = UUID.randomUUID();
        List<Integer> status = paralelo(java.util.stream.IntStream.range(0, 8).<Callable<Integer>>mapToObj(i -> () ->
                enviar(BASE, dona(), compra(id, f, porProduto(a, 3, "2.00"))).andReturn().getResponse().getStatus()).toList());
        assertThat(status).containsOnly(200, 201);
        assertThat(status.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(saldo(a.id())).isEqualTo(3);
        assertThat(quantas("compras") > 0).isTrue();
        conciliado();
    }

    // --- concorrência ---

    @Test
    void duasComprasDoMesmoProdutoEmParaleloComSaidasDaoSaldoExato() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        Peca b = peca("1.00");
        UUID dona = usuarios.dona().id();
        estoque.registrar(new Pedido(UUID.randomUUID(), a.id(), TipoDeMovimento.ENTRADA, 1000, null, OrigemDoMovimento.MANUAL, null), dona);
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // As duas listas têm os produtos em ordens opostas: o servidor ordena, então não há travamento.
            Object[] itens = i % 2 == 0 ? new Object[] {porProduto(a, 5, "2.00"), porProduto(b, 3, "2.00")}
                    : new Object[] {porProduto(b, 3, "2.00"), porProduto(a, 5, "2.00")};
            tarefas.add(() -> enviar(BASE, dona(), compra(UUID.randomUUID(), f, itens)).andReturn().getResponse().getStatus());
            tarefas.add(() -> {
                estoque.registrar(new Pedido(UUID.randomUUID(), a.id(), TipoDeMovimento.SAIDA, 7, null, OrigemDoMovimento.MANUAL, null), dona);
                return 201;
            });
        }
        assertThat(paralelo(tarefas)).containsOnly(201);
        assertThat(saldo(a.id())).isEqualTo(1000 + 10 * 5 - 10 * 7);
        assertThat(saldo(b.id())).isEqualTo(30);
        conciliado();
    }

    // --- desfazer ---

    @Test
    void desfazerDevolveOEstoqueMarcaADesfeitaEMantemOHistorico() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("5.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 4, "9.00")));
        assertThat(custo(a.id())).isEqualByComparingTo("9.00");
        UUID pedido = UUID.randomUUID();
        JsonNode r = corpoDe(enviar(BASE + "/" + id + "/desfazer", dona(), mapa("id", pedido, "motivo", "Veio errada"))
                .andExpect(status().isCreated()));
        assertThat(r.get("compra").get("situacao").asString()).isEqualTo("ESTORNADA");
        assertThat(r.get("motivoDeDesfeita").asString()).isEqualTo("Veio errada");
        assertThat(saldo(a.id())).isZero();
        assertThat(custo(a.id())).as("era a última compra: o custo volta").isEqualByComparingTo("5.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.historico_custo WHERE produto_id = ?", Integer.class,
                a.id())).as("o histórico nunca é apagado").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ? AND estorna_id IS NOT NULL",
                Integer.class, a.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.auditoria WHERE registro_id = ? AND acao IN ('COMPRA_REGISTRADA', 'COMPRA_ESTORNADA')",
                Integer.class, id)).isEqualTo(2);
        // Reenvio com o mesmo id: não é erro. Outro id: já foi desfeita.
        enviar(BASE + "/" + id + "/desfazer", dona(), mapa("id", pedido, "motivo", "Veio errada")).andExpect(status().isOk());
        enviar(BASE + "/" + id + "/desfazer", dona(), mapa("motivo", "Veio errada")).andExpect(status().isConflict());
        // Reenviar a compra original (já desfeita) também devolve o que está gravado, sem somar.
        enviar(BASE, dona(), compra(id, f, porProduto(a, 4, "9.00"))).andExpect(status().isOk());
        assertThat(saldo(a.id())).isZero();
        conciliado();
    }

    @Test
    void desfazerRecusaSemMotivoCompraInexistenteEPecasQueJaSairam() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("5.00");
        Peca b = peca("5.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 4, "9.00"), porProduto(b, 4, "9.00")));
        enviar(BASE + "/" + id + "/desfazer", dona(), mapa("motivo", " ")).andExpect(status().isBadRequest());
        enviar(BASE + "/" + UUID.randomUUID() + "/desfazer", dona(), mapa("motivo", "x")).andExpect(status().isNotFound());
        estoque.registrar(new Pedido(UUID.randomUUID(), b.id(), TipoDeMovimento.SAIDA, 3, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        ResultActions r = enviar(BASE + "/" + id + "/desfazer", dona(), mapa("motivo", "Erro")).andExpect(status().isConflict());
        assertThat(textoDe(r)).contains("Não é possível desfazer esta compra").contains("há só 1");
        // Tudo ou nada: a outra peça também continua com o estoque.
        assertThat(saldo(a.id())).isEqualTo(4);
        assertThat(saldo(b.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.compras WHERE id = ?", String.class, id)).isEqualTo("REGISTRADA");
        conciliado();
    }

    @Test
    void custoSoVoltaSeAComprasDesfeitaEraAUltima() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("5.00");
        UUID primeira = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        receber(compra(primeira, f, porProduto(a, 2, "6.00")));
        receber(compra(segunda, f, porProduto(a, 2, "8.00")));
        enviar(BASE + "/" + primeira + "/desfazer", dona(), mapa("motivo", "Erro")).andExpect(status().isCreated());
        assertThat(custo(a.id())).as("a segunda é a última: o custo não muda").isEqualByComparingTo("8.00");
        // Agora a segunda: volta ao custo anterior que continua valendo, que é o de antes das compras.
        enviar(BASE + "/" + segunda + "/desfazer", dona(), mapa("motivo", "Erro")).andExpect(status().isCreated());
        assertThat(custo(a.id())).isEqualByComparingTo("5.00");
        assertThat(saldo(a.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.historico_custo WHERE produto_id = ?", Integer.class,
                a.id())).isEqualTo(2);
    }

    @Test
    void custoAlteradoAMaoDepoisDaCompraNaoEhDesfeito() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("5.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 2, "6.00")));
        jdbc.update("UPDATE corona.produtos_custos SET preco_custo = 6.50 WHERE produto_id = ?", a.id());
        enviar(BASE + "/" + id + "/desfazer", dona(), mapa("motivo", "Erro")).andExpect(status().isCreated());
        assertThat(custo(a.id())).isEqualByComparingTo("6.50");
    }

    @Test
    void desfazerDuasVezesAoMesmoTempoDesfazUmaVezSo() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("5.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 2, "6.00")));
        List<Integer> status = paralelo(java.util.stream.IntStream.range(0, 6).<Callable<Integer>>mapToObj(i -> () ->
                enviar(BASE + "/" + id + "/desfazer", dona(), mapa("motivo", "Erro")).andReturn().getResponse().getStatus()).toList());
        assertThat(status.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(status.stream().filter(s -> s == 409).count()).isEqualTo(5);
        assertThat(saldo(a.id())).isZero();
        conciliado();
    }

    // --- imutabilidade ---

    @Test
    void compraEItensNaoPodemSerAlteradosNemApagadosNemPeloBanco() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("5.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 2, "6.00")));
        for (String sql : List.of("UPDATE corona.compras SET total = 1 WHERE id = '" + id + "'",
                "UPDATE corona.compras SET numero = 'X' WHERE id = '" + id + "'",
                "DELETE FROM corona.compras WHERE id = '" + id + "'",
                "UPDATE corona.itens_compra SET quantidade = 9 WHERE compra_id = '" + id + "'",
                "DELETE FROM corona.itens_compra WHERE compra_id = '" + id + "'",
                "UPDATE corona.historico_custo SET custo = 1 WHERE compra_id = '" + id + "'",
                "DELETE FROM corona.historico_custo WHERE compra_id = '" + id + "'")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute(sql)).as(sql).isInstanceOf(Exception.class);
        }
        assertThat(jdbc.queryForObject("SELECT total FROM corona.compras WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("12.00");
    }

    // --- consultas ---

    @Test
    void verListarFiltrarEHistoricoDeCusto() throws Exception {
        UUID f1 = fornecedor();
        UUID f2 = fornecedor();
        Peca a = peca("5.00");
        Peca b = peca("5.00");
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        receber(compra(c1, f1, porProduto(a, 2, "6.00")));
        Map<String, Object> segunda = compra(c2, f2, porProduto(a, 1, "7.00"), porProduto(b, 1, "3.00"));
        segunda.put("dataCompra", "2026-11-05");
        receber(segunda);
        enviar(BASE + "/" + c1 + "/desfazer", dona(), mapa("motivo", "Erro")).andExpect(status().isCreated());

        JsonNode ver = corpoDe(ler(BASE + "/" + c2).andExpect(status().isOk()));
        assertThat(ver.get("itens")).hasSize(2);
        assertThat(ver.get("compra").get("pecas").asInt()).isEqualTo(2);
        assertThat(ver.get("compra").get("fornecedorNome").asString()).startsWith("Fornecedor");
        ler(BASE + "/" + UUID.randomUUID()).andExpect(status().isNotFound());

        String filtro = BASE + "?produtoId=" + a.id();
        assertThat(corpoDe(ler(filtro)).get("total").asInt()).isEqualTo(2);
        assertThat(corpoDe(ler(filtro)).get("itens").get(0).get("id").asString()).as("mais nova primeiro").isEqualTo(c2.toString());
        assertThat(corpoDe(ler(filtro + "&situacao=ESTORNADA")).get("total").asInt()).isEqualTo(1);
        assertThat(corpoDe(ler(filtro + "&fornecedorId=" + f2)).get("total").asInt()).isEqualTo(1);
        assertThat(corpoDe(ler(filtro + "&de=2026-11-03&ate=2026-11-30")).get("total").asInt()).isEqualTo(1);
        assertThat(corpoDe(ler(filtro + "&tamanho=1&pagina=1")).get("itens")).hasSize(1);
        ler(BASE + "?situacao=XYZ").andExpect(status().isBadRequest());
        ler(BASE + "?de=2026-12-01&ate=2026-11-01").andExpect(status().isBadRequest());

        JsonNode custos = corpoDe(ler(BASE + "/produtos/" + a.id() + "/custos").andExpect(status().isOk()));
        assertThat(dec(custos.get("custoAtual"))).isEqualByComparingTo("7.00");
        assertThat(custos.get("historico").get("itens")).hasSize(2);
        assertThat(dec(custos.get("historico").get("itens").get(0).get("custo"))).isEqualByComparingTo("7.00");
        assertThat(custos.get("produto").has("fotoPrincipal")).isTrue();
        ler(BASE + "/produtos/" + UUID.randomUUID() + "/custos").andExpect(status().isNotFound());
    }

    // --- o vendedor não recebe nada disto ---

    @Test
    void comprasCustosEHistoricoNaoVaoParaOPullDoVendedor() throws Exception {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        assertThat(registro.sincronizaveis()).extracting(t -> t.nome()).doesNotContain("compras", "itens_compra", "historico_custo",
                "produtos_custos");
        assertThat(registro.soDoServidor()).extracting(t -> t.nome()).contains("compras", "itens_compra", "historico_custo");
        receber(compra(UUID.randomUUID(), fornecedor(), porProduto(peca("5.00"), 2, "6.00")));
        LeituraDoPull tudo = LeituraDoPull.esvaziar(pull, null);
        Set<String> tabelas = new java.util.HashSet<>();
        tudo.linhas().forEach(l -> tabelas.add(l.tabela()));
        assertThat(tabelas).doesNotContain("compras", "itens_compra", "historico_custo", "produtos_custos", "movimentacoes_estoque");
        assertThat(tudo.linhas().toString()).doesNotContain("custo");
    }

    @Test
    void conciliacaoContinuaVaziaDepoisDeCompras() throws Exception {
        receber(compra(UUID.randomUUID(), fornecedor(), porProduto(peca("5.00"), 2, "6.00")));
        conciliado();
    }

    @Test
    void mensagensDeErroNaoTraemTermosProibidos() throws Exception {
        UUID f = fornecedor();
        Peca a = peca("1.00");
        UUID id = UUID.randomUUID();
        receber(compra(id, f, porProduto(a, 1, "1.00")));
        estoque.registrar(new Pedido(UUID.randomUUID(), a.id(), TipoDeMovimento.SAIDA, 1, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        List<String> textos = List.of(
                textoDe(enviar(BASE + "/" + id + "/desfazer", dona(), mapa("motivo", "x")).andExpect(status().isConflict())),
                textoDe(enviar(BASE, dona(), compra(id, f, porProduto(a, 2, "1.00"))).andExpect(status().isConflict())),
                textoDe(enviar(BASE, dona(), compra(UUID.randomUUID(), f, porCodigo("NADA", 1, "1.00"))).andExpect(status().isBadRequest())),
                textoDe(enviar(BASE + "/" + UUID.randomUUID() + "/desfazer", dona(), mapa("motivo", "x")).andExpect(status().isNotFound())));
        for (String texto : textos) {
            assertThat(texto.toLowerCase()).doesNotContain("estorn", "moviment", "invent", "sync", "idempot", "uuid");
        }
    }
}
