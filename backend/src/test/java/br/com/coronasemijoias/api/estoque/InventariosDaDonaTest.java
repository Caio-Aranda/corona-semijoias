package br.com.coronasemijoias.api.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import br.com.coronasemijoias.api.testes.LeituraDoPull;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Contagem do estoque (tarefa 2.4; ADR-0008, item 8): esperado congelado, leituras, divergências, fechamento por
 * diferença, cancelamento, concorrência, permissão e o que o vendedor nunca recebe.
 */
class InventariosDaDonaTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/inventarios";

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

    /** Só um inventário em andamento por produto: cada teste começa e termina sem nenhum aberto. */
    @BeforeEach
    @AfterEach
    void encerrarOsAbertos() {
        UUID dona = usuarios.dona().id();
        jdbc.update("UPDATE corona.inventarios SET situacao = 'CANCELADO', cancelado_em = now(), cancelado_por = ? "
                + "WHERE situacao = 'EM_ANDAMENTO'", dona);
    }

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

    private ResultActions enviar(String caminho, Object corpo) throws Exception {
        return enviar(caminho, dona(), corpo);
    }

    private ResultActions ler(String caminho) throws Exception {
        return mvc.perform(get(caminho).with(dona()));
    }

    private JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Só o que a pessoa lê (título e frase): o caminho da rota, no corpo, tem a palavra técnica e nunca aparece na tela. */
    private String textoDe(ResultActions r) throws Exception {
        JsonNode corpo = json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        StringBuilder texto = new StringBuilder();
        for (String campo : List.of("title", "detail")) {
            if (corpo.has(campo)) {
                texto.append(corpo.get(campo).asString()).append(' ');
            }
        }
        if (corpo.has("errors")) {
            texto.append(corpo.get("errors").toString());
        }
        return texto.toString();
    }

    // --- massa de dados ---

    private record Peca(UUID id, String codigo, UUID categoria) {
    }

    private UUID categoria() throws Exception {
        return UUID.fromString(corpoDe(enviar("/api/dona/categorias", mapa("nome", "Cat " + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private UUID fornecedor() throws Exception {
        return UUID.fromString(corpoDe(enviar("/api/dona/fornecedores",
                mapa("razaoSocial", "Forn " + UUID.randomUUID().toString().substring(0, 8))).andExpect(status().isCreated())).get("id").asString());
    }

    private Peca peca(UUID categoria, UUID fornecedor, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String codigo = "I-" + sufixo;
        Map<String, Object> corpo = mapa("nome", "Anel " + sufixo, "categoriaId", categoria, "precoVenda", "70.00",
                "precoCusto", "10.00", "estoqueMinimo", 0, "codigos", List.of(mapa("codigo", codigo, "tipo", "LOJA", "principal", true)));
        if (fornecedor != null) {
            corpo.put("fornecedorId", fornecedor);
        }
        UUID id = UUID.fromString(corpoDe(enviar("/api/dona/produtos", corpo).andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            entrada(id, saldo);
        }
        return new Peca(id, codigo, categoria);
    }

    private void entrada(UUID produto, int quantidade) {
        estoque.registrar(new Pedido(UUID.randomUUID(), produto, TipoDeMovimento.ENTRADA, quantidade, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
    }

    private void saida(UUID produto, int quantidade) {
        estoque.registrar(new Pedido(UUID.randomUUID(), produto, TipoDeMovimento.SAIDA, quantidade, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
    }

    private int saldo(UUID produto) {
        return jdbc.queryForObject("SELECT estoque_atual FROM corona.produtos WHERE id = ?", Integer.class, produto);
    }

    private int quantosMovimentos(UUID produto, String origem) {
        return jdbc.queryForObject("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ? AND origem_tipo = ?",
                Integer.class, produto, origem);
    }

    // --- chamadas da contagem ---

    private JsonNode iniciar(UUID categoria) throws Exception {
        return corpoDe(enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA", "escopoId", categoria))
                .andExpect(status().isCreated()));
    }

    private ResultActions ler(UUID inventario, Peca p, int quantidade, UUID id) throws Exception {
        return enviar(BASE + "/" + inventario + "/leituras", mapa("id", id, "codigo", p.codigo(), "quantidade", quantidade));
    }

    private void somar(UUID inventario, Peca p, int quantidade) throws Exception {
        ler(inventario, p, quantidade, UUID.randomUUID()).andExpect(status().isCreated());
    }

    private void definir(UUID inventario, Peca p, int contado) throws Exception {
        enviar(BASE + "/" + inventario + "/contagens", mapa("id", UUID.randomUUID(), "produtoId", p.id(), "contado", contado))
                .andExpect(status().isCreated());
    }

    private ResultActions fechar(UUID inventario, boolean zerarNaoLidos) throws Exception {
        return enviar(BASE + "/" + inventario + "/fechar", mapa("zerarNaoLidos", zerarNaoLidos));
    }

    private JsonNode item(UUID inventario, Peca p) throws Exception {
        JsonNode itens = corpoDe(ler(BASE + "/" + inventario + "/itens?tamanho=200").andExpect(status().isOk())).get("itens");
        for (JsonNode i : itens) {
            if (i.get("produto").get("id").asString().equals(p.id().toString())) {
                return i;
            }
        }
        throw new AssertionError("A peça não está na contagem");
    }

    private void conciliado() throws Exception {
        assertThat(corpoDe(ler("/api/dona/estoque/conciliacao").andExpect(status().isOk())).get("divergencias")).isEmpty();
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

    // --- iniciar ---

    @Test
    void iniciarCongelaOEsperadoSoDasPecasDoEscopoEMostraAsFotos() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 7);
        Peca b = peca(cat, null, 0);
        Peca fora = peca(categoria(), null, 4);
        JsonNode inv = iniciar(cat);
        UUID id = UUID.fromString(inv.get("id").asString());
        assertThat(inv.get("situacao").asString()).isEqualTo("EM_ANDAMENTO");
        assertThat(inv.get("resumo").get("totalDePecas").asInt()).isEqualTo(2);
        assertThat(inv.get("resumo").get("naoLidos").asInt()).isEqualTo(2);
        assertThat(item(id, a).get("esperado").asInt()).isEqualTo(7);
        assertThat(item(id, b).get("esperado").asInt()).isEqualTo(0);
        assertThat(item(id, a).get("contado").isNull()).isTrue();
        assertThat(item(id, a).get("produto").has("fotoPrincipal")).isTrue();
        assertThatThrownBy(() -> item(id, fora)).isInstanceOf(AssertionError.class);
        // Mexer no estoque depois NÃO muda o esperado congelado.
        entrada(a.id(), 5);
        assertThat(item(id, a).get("esperado").asInt()).isEqualTo(7);
        assertThat(item(id, a).get("saldoAtual").asInt()).isEqualTo(12);
    }

    @Test
    void escopoPorFornecedorETodos() throws Exception {
        UUID cat = categoria();
        UUID forn = fornecedor();
        Peca doForn = peca(cat, forn, 3);
        peca(cat, null, 3);
        JsonNode porForn = corpoDe(enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "FORNECEDOR", "escopoId", forn))
                .andExpect(status().isCreated()));
        assertThat(porForn.get("resumo").get("totalDePecas").asInt()).isEqualTo(1);
        assertThat(item(UUID.fromString(porForn.get("id").asString()), doForn).get("esperado").asInt()).isEqualTo(3);
        enviar(BASE + "/" + porForn.get("id").asString() + "/cancelar", mapa()).andExpect(status().isOk());

        long total = jdbc.queryForObject("SELECT count(*) FROM corona.produtos WHERE deleted_at IS NULL", Long.class);
        JsonNode todos = corpoDe(enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "TODOS")).andExpect(status().isCreated()));
        assertThat(todos.get("resumo").get("totalDePecas").asLong()).isEqualTo(total);
    }

    @Test
    void escopoVazioInvalidoOuInexistenteERecusado() throws Exception {
        UUID vazia = categoria();
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA", "escopoId", vazia)).andExpect(status().isBadRequest());
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA", "escopoId", UUID.randomUUID())).andExpect(status().isBadRequest());
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA")).andExpect(status().isBadRequest());
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "TODOS", "escopoId", vazia)).andExpect(status().isBadRequest());
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "OUTRO")).andExpect(status().isBadRequest());
        enviar(BASE, mapa("id", UUID.randomUUID())).andExpect(status().isBadRequest());
    }

    @Test
    void soUmaContagemEmAndamentoPorPecaDeCadaVez() throws Exception {
        UUID cat = categoria();
        peca(cat, null, 1);
        JsonNode primeira = iniciar(cat);
        // Mesma seleção, e "todas as peças" (que cobre a seleção): recusadas.
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA", "escopoId", cat)).andExpect(status().isConflict());
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "TODOS")).andExpect(status().isConflict());
        // Outra seleção, sem peças em comum: liberada.
        UUID outra = categoria();
        peca(outra, null, 1);
        JsonNode segunda = iniciar(outra);
        // Depois de cancelar a primeira, a seleção dela volta a ficar livre.
        enviar(BASE + "/" + primeira.get("id").asString() + "/cancelar", mapa()).andExpect(status().isOk());
        iniciar(cat);
        assertThat(segunda.get("situacao").asString()).isEqualTo("EM_ANDAMENTO");
    }

    @Test
    void selecoesQueSeCruzamPorFornecedorTambemSaoRecusadas() throws Exception {
        UUID cat = categoria();
        UUID forn = fornecedor();
        peca(cat, forn, 1);
        iniciar(cat);
        enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "FORNECEDOR", "escopoId", forn)).andExpect(status().isConflict());
    }

    @Test
    void iniciarDeNovoComOMesmoIdDevolveOMesmoEOutrosDadosDao409() throws Exception {
        UUID cat = categoria();
        peca(cat, null, 2);
        UUID id = UUID.randomUUID();
        Map<String, Object> corpo = mapa("id", id, "escopo", "CATEGORIA", "escopoId", cat, "observacoes", "Vitrine");
        enviar(BASE, corpo).andExpect(status().isCreated());
        enviar(BASE, corpo).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.inventarios WHERE id = ?", Integer.class, id)).isEqualTo(1);
        enviar(BASE, mapa("id", id, "escopo", "CATEGORIA", "escopoId", cat, "observacoes", "Outra")).andExpect(status().isConflict());
    }

    @Test
    void iniciarEmParaleloComOMesmoEscopoCriaUmaSo() throws Exception {
        UUID cat = categoria();
        peca(cat, null, 1);
        List<Integer> codigos = paralelo(IntStream.range(0, 6).<Callable<Integer>>mapToObj(i -> () ->
                enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA", "escopoId", cat)).andReturn().getResponse().getStatus())
                .toList());
        assertThat(codigos.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(codigos.stream().filter(s -> s == 409).count()).isEqualTo(5);
    }

    // --- contagem ---

    @Test
    void leituraPorCodigoSomaSemDiferenciarMaiusculasEPorProdutoTambem() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 5);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        enviar(BASE + "/" + inv + "/leituras", mapa("id", UUID.randomUUID(), "codigo", "  " + a.codigo().toLowerCase() + " "))
                .andExpect(status().isCreated());
        enviar(BASE + "/" + inv + "/leituras", mapa("id", UUID.randomUUID(), "codigo", a.codigo().toUpperCase(), "quantidade", 2))
                .andExpect(status().isCreated());
        JsonNode resposta = corpoDe(enviar(BASE + "/" + inv + "/leituras", mapa("id", UUID.randomUUID(), "produtoId", a.id(), "quantidade", 3))
                .andExpect(status().isCreated()));
        assertThat(resposta.get("item").get("contado").asInt()).isEqualTo(6);
        assertThat(resposta.get("item").get("diferenca").asInt()).isEqualTo(1);
        assertThat(resposta.get("item").get("produto").has("fotoPrincipal")).isTrue();
        assertThat(resposta.get("resumo").get("lidos").asInt()).isEqualTo(1);
    }

    @Test
    void reenviarALeituraComOMesmoIdNaoSomaDeNovoEOutrosDadosDao409() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 5);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        UUID leitura = UUID.randomUUID();
        ler(inv, a, 2, leitura).andExpect(status().isCreated());
        ler(inv, a, 2, leitura).andExpect(status().isOk());
        assertThat(item(inv, a).get("contado").asInt()).isEqualTo(2);
        ler(inv, a, 3, leitura).andExpect(status().isConflict());
        assertThat(item(inv, a).get("contado").asInt()).isEqualTo(2);
    }

    @Test
    void contagemDefinidaSubstituiOValorELeituraDepoisSomaEmCima() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 5);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, a, 9);
        definir(inv, a, 4);
        assertThat(item(inv, a).get("contado").asInt()).isEqualTo(4);
        somar(inv, a, 1);
        assertThat(item(inv, a).get("contado").asInt()).isEqualTo(5);
        definir(inv, a, 0);
        assertThat(item(inv, a).get("contado").asInt()).isEqualTo(0);
        assertThat(item(inv, a).get("contado").isNull()).isFalse();
        enviar(BASE + "/" + inv + "/contagens", mapa("id", UUID.randomUUID(), "produtoId", a.id(), "contado", -1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leiturasInvalidasSaoRecusadasComMensagemSimples() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 1);
        Peca fora = peca(categoria(), null, 1);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        // Código que não existe, peça de fora da contagem, quantidade zero, sem código nem produto, os dois juntos.
        enviar(BASE + "/" + inv + "/leituras", mapa("id", UUID.randomUUID(), "codigo", "NAO-EXISTE")).andExpect(status().isBadRequest());
        ler(inv, fora, 1, UUID.randomUUID()).andExpect(status().isBadRequest());
        ler(inv, a, 0, UUID.randomUUID()).andExpect(status().isBadRequest());
        enviar(BASE + "/" + inv + "/leituras", mapa("id", UUID.randomUUID())).andExpect(status().isBadRequest());
        enviar(BASE + "/" + inv + "/leituras", mapa("id", UUID.randomUUID(), "codigo", a.codigo(), "produtoId", a.id()))
                .andExpect(status().isBadRequest());
        enviar(BASE + "/" + UUID.randomUUID() + "/leituras", mapa("id", UUID.randomUUID(), "codigo", a.codigo())).andExpect(status().isNotFound());
        assertThat(item(inv, a).get("contado").isNull()).isTrue();
    }

    @Test
    void leiturasEmParaleloSomamExatoEOMesmoIdContaUmaVez() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 50);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        List<Callable<Integer>> tarefas = new ArrayList<>(IntStream.range(0, 20).<Callable<Integer>>mapToObj(i -> () ->
                ler(inv, a, 1, UUID.randomUUID()).andReturn().getResponse().getStatus()).toList());
        UUID repetida = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            tarefas.add(() -> ler(inv, a, 5, repetida).andReturn().getResponse().getStatus());
        }
        List<Integer> status = paralelo(tarefas);
        assertThat(status).containsOnly(200, 201);
        assertThat(item(inv, a).get("contado").asInt()).isEqualTo(20 + 5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.leituras_inventario WHERE inventario_id = ?", Integer.class, inv))
                .isEqualTo(21);
    }

    @Test
    void divergenciasFiltrosEPaginacao() throws Exception {
        UUID cat = categoria();
        Peca igual = peca(cat, null, 3);
        Peca falta = peca(cat, null, 3);
        Peca sobra = peca(cat, null, 3);
        Peca naoLida = peca(cat, null, 3);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, igual, 3);
        somar(inv, falta, 1);
        somar(inv, sobra, 5);
        JsonNode resumo = corpoDe(ler(BASE + "/" + inv).andExpect(status().isOk())).get("resumo");
        assertThat(resumo.get("totalDePecas").asInt()).isEqualTo(4);
        assertThat(resumo.get("lidos").asInt()).isEqualTo(3);
        assertThat(resumo.get("naoLidos").asInt()).isEqualTo(1);
        assertThat(resumo.get("comDiferenca").asInt()).isEqualTo(2);
        assertThat(resumo.get("faltam").asInt()).isEqualTo(2);
        assertThat(resumo.get("sobram").asInt()).isEqualTo(2);

        JsonNode div = corpoDe(ler(BASE + "/" + inv + "/itens?filtro=DIVERGENTES").andExpect(status().isOk())).get("itens");
        assertThat(div).hasSize(2);
        JsonNode nao = corpoDe(ler(BASE + "/" + inv + "/itens?filtro=NAO_LIDOS").andExpect(status().isOk())).get("itens");
        assertThat(nao).hasSize(1);
        assertThat(nao.get(0).get("produto").get("id").asString()).isEqualTo(naoLida.id().toString());
        assertThat(nao.get(0).get("diferenca").isNull()).isTrue();
        JsonNode busca = corpoDe(ler(BASE + "/" + inv + "/itens?busca=" + falta.codigo()).andExpect(status().isOk()));
        assertThat(busca.get("itens")).hasSize(1);
        JsonNode pagina = corpoDe(ler(BASE + "/" + inv + "/itens?tamanho=3&pagina=1").andExpect(status().isOk()));
        assertThat(pagina.get("itens")).hasSize(1);
        assertThat(pagina.get("total").asInt()).isEqualTo(4);
        ler(BASE + "/" + inv + "/itens?filtro=XYZ").andExpect(status().isBadRequest());
        JsonNode lista = corpoDe(ler(BASE + "?situacao=EM_ANDAMENTO").andExpect(status().isOk()));
        assertThat(lista.get("itens").size()).isGreaterThanOrEqualTo(1);
        ler(BASE + "?situacao=XYZ").andExpect(status().isBadRequest());
    }

    // --- fechar ---

    @Test
    void fecharGeraUmAjustePorPecaComDiferencaComMotivoEOrigemEConciliacaoFicaVazia() throws Exception {
        UUID cat = categoria();
        Peca igual = peca(cat, null, 3);
        Peca falta = peca(cat, null, 6);
        Peca sobra = peca(cat, null, 2);
        Peca naoLida = peca(cat, null, 9);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, igual, 3);
        somar(inv, falta, 4);
        somar(inv, sobra, 5);
        JsonNode fechado = corpoDe(fechar(inv, false).andExpect(status().isOk()));
        assertThat(fechado.get("situacao").asString()).isEqualTo("FECHADO");
        assertThat(fechado.get("ajustes").asInt()).isEqualTo(2);
        assertThat(saldo(igual.id())).isEqualTo(3);
        assertThat(saldo(falta.id())).isEqualTo(4);
        assertThat(saldo(sobra.id())).isEqualTo(5);
        assertThat(saldo(naoLida.id())).isEqualTo(9);
        assertThat(quantosMovimentos(igual.id(), "INVENTARIO")).isEqualTo(0);
        assertThat(quantosMovimentos(naoLida.id(), "INVENTARIO")).isEqualTo(0);
        Map<String, Object> mov = jdbc.queryForMap("SELECT tipo, quantidade, motivo, origem_id, usuario_id FROM corona.movimentacoes_estoque "
                + "WHERE produto_id = ? AND origem_tipo = 'INVENTARIO'", falta.id());
        assertThat(mov.get("tipo")).isEqualTo("AJUSTE");
        assertThat(mov.get("quantidade")).isEqualTo(-2);
        assertThat(mov.get("motivo")).isEqualTo("Contagem do estoque");
        assertThat(mov.get("origem_id")).isEqualTo(inv);
        assertThat(mov.get("usuario_id")).isEqualTo(usuarios.dona().id());
        assertThat(jdbc.queryForObject("SELECT quantidade FROM corona.movimentacoes_estoque WHERE produto_id = ? AND origem_tipo = 'INVENTARIO'",
                Integer.class, sobra.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.itens_inventario WHERE inventario_id = ? AND movimento_id IS NOT NULL",
                Integer.class, inv)).isEqualTo(2);
        conciliado();
    }

    @Test
    void pecaNaoLidaSoViraZeroQuandoADonaConfirma() throws Exception {
        UUID cat = categoria();
        Peca lida = peca(cat, null, 2);
        Peca naoLida = peca(cat, null, 9);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, lida, 2);
        JsonNode fechado = corpoDe(fechar(inv, true).andExpect(status().isOk()));
        assertThat(fechado.get("zerouNaoLidos").asBoolean()).isTrue();
        assertThat(saldo(naoLida.id())).isEqualTo(0);
        assertThat(saldo(lida.id())).isEqualTo(2);
        assertThat(item(inv, naoLida).get("contado").asInt()).isEqualTo(0);
        conciliado();
    }

    @Test
    void entradaFeitaDuranteAContagemNaoEDesfeitaPeloFechamento() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 10);
        Peca b = peca(cat, null, 10);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        // Durante a contagem chegam 5 peças de A e saem 2 de B.
        entrada(a.id(), 5);
        saida(b.id(), 2);
        somar(inv, a, 10);   // contou o que havia antes: nada a ajustar
        somar(inv, b, 7);    // esperava 10, contou 7: faltam 3 sobre o esperado congelado
        fechar(inv, false).andExpect(status().isOk());
        assertThat(saldo(a.id())).isEqualTo(15);   // a entrada continua valendo
        assertThat(saldo(b.id())).isEqualTo(5);    // 8 (depois da saída) - 3
        conciliado();
    }

    @Test
    void fecharDeNovoDevolveOJaFechadoSemNovosAjustes() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 6);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, a, 4);
        fechar(inv, false).andExpect(status().isOk());
        JsonNode segunda = corpoDe(fechar(inv, false).andExpect(status().isOk()));
        assertThat(segunda.get("situacao").asString()).isEqualTo("FECHADO");
        assertThat(quantosMovimentos(a.id(), "INVENTARIO")).isEqualTo(1);
        assertThat(saldo(a.id())).isEqualTo(4);
    }

    @Test
    void fecharDuasVezesEmParaleloAjustaUmaSoVez() throws Exception {
        UUID cat = categoria();
        List<Peca> pecas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pecas.add(peca(cat, null, 10));
        }
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        for (Peca p : pecas) {
            somar(inv, p, 7);
        }
        List<Integer> status = paralelo(IntStream.range(0, 8).<Callable<Integer>>mapToObj(i -> () ->
                fechar(inv, false).andReturn().getResponse().getStatus()).toList());
        assertThat(status).containsOnly(200);
        for (Peca p : pecas) {
            assertThat(quantosMovimentos(p.id(), "INVENTARIO")).isEqualTo(1);
            assertThat(saldo(p.id())).isEqualTo(7);
        }
        conciliado();
    }

    @Test
    void fecharComMovimentoEmParaleloMantemASomaCerta() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 100);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, a, 90);
        List<Callable<Integer>> tarefas = new ArrayList<>();
        tarefas.add(() -> fechar(inv, false).andReturn().getResponse().getStatus());
        for (int i = 0; i < 10; i++) {
            tarefas.add(() -> {
                entrada(a.id(), 1);
                return 200;
            });
        }
        paralelo(tarefas);
        // 100 + 10 entradas - 10 do ajuste (90 - 100 esperado) = 100, em qualquer ordem.
        assertThat(saldo(a.id())).isEqualTo(100);
        conciliado();
    }

    @Test
    void fechamentoQueDeixariaSaldoNegativoERecusadoSemGravarNadaEDizAPeca() throws Exception {
        UUID cat = categoria();
        Peca ok = peca(cat, null, 4);
        Peca problema = peca(cat, null, 5);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, ok, 1);          // ajuste -3, possível
        definir(inv, problema, 0);  // ajuste -5...
        saida(problema.id(), 3);    // ...mas já saíram 3: o saldo é 2
        String texto = textoDe(fechar(inv, false).andExpect(status().isConflict()));
        assertThat(texto).contains("Anel").contains("2");
        assertThat(saldo(ok.id())).isEqualTo(4);
        assertThat(saldo(problema.id())).isEqualTo(2);
        assertThat(quantosMovimentos(ok.id(), "INVENTARIO")).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.inventarios WHERE id = ?", String.class, inv)).isEqualTo("EM_ANDAMENTO");
        // A dona corrige a leitura e consegue fechar.
        definir(inv, problema, 3);   // esperava 5, contou 3: tira 2 do saldo de 2
        fechar(inv, false).andExpect(status().isOk());
        assertThat(saldo(problema.id())).isEqualTo(0);
        assertThat(saldo(ok.id())).isEqualTo(1);
        conciliado();
    }

    @Test
    void fecharSemNenhumaDiferencaFechaSemAjustes() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 2);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, a, 2);
        JsonNode fechado = corpoDe(fechar(inv, false).andExpect(status().isOk()));
        assertThat(fechado.get("ajustes").asInt()).isEqualTo(0);
        assertThat(quantosMovimentos(a.id(), "INVENTARIO")).isEqualTo(0);
    }

    // --- cancelar e imutabilidade ---

    @Test
    void cancelarNaoGeraAjusteEFechadoOuCanceladoNaoAceitaMaisNada() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 6);
        UUID cancelada = UUID.fromString(iniciar(cat).get("id").asString());
        somar(cancelada, a, 1);
        JsonNode resposta = corpoDe(enviar(BASE + "/" + cancelada + "/cancelar", mapa("motivo", "Contagem errada")).andExpect(status().isOk()));
        assertThat(resposta.get("situacao").asString()).isEqualTo("CANCELADO");
        assertThat(saldo(a.id())).isEqualTo(6);
        assertThat(quantosMovimentos(a.id(), "INVENTARIO")).isEqualTo(0);
        enviar(BASE + "/" + cancelada + "/cancelar", mapa()).andExpect(status().isOk());
        ler(cancelada, a, 1, UUID.randomUUID()).andExpect(status().isConflict());
        fechar(cancelada, false).andExpect(status().isConflict());

        UUID fechada = UUID.fromString(iniciar(cat).get("id").asString());
        somar(fechada, a, 5);
        fechar(fechada, false).andExpect(status().isOk());
        ler(fechada, a, 1, UUID.randomUUID()).andExpect(status().isConflict());
        enviar(BASE + "/" + fechada + "/contagens", mapa("id", UUID.randomUUID(), "produtoId", a.id(), "contado", 1)).andExpect(status().isConflict());
        enviar(BASE + "/" + fechada + "/cancelar", mapa()).andExpect(status().isConflict());
        assertThat(item(fechada, a).get("contado").asInt()).isEqualTo(5);
        assertThat(saldo(a.id())).isEqualTo(5);
    }

    @Test
    void oBancoRecusaMudarContagemEncerradaEOEsperadoCongelado() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 3);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.itens_inventario SET esperado = 99 WHERE inventario_id = ?", inv))
                .hasMessageContaining("esperado congelado");
        somar(inv, a, 3);
        fechar(inv, false).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.itens_inventario SET contado = 1 WHERE inventario_id = ?", inv))
                .hasMessageContaining("encerrado");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.inventarios SET situacao = 'EM_ANDAMENTO' WHERE id = ?", inv))
                .hasMessageContaining("encerrado");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.leituras_inventario SET quantidade = 9 WHERE inventario_id = ?", inv))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.inventarios WHERE id = ?", inv)).isInstanceOf(Exception.class);
    }

    // --- auditoria, permissão, vendedor, mensagens ---

    @Test
    void iniciarFecharECancelarEntramNaAuditoria() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 3);
        UUID fechada = UUID.fromString(iniciar(cat).get("id").asString());
        somar(fechada, a, 2);
        fechar(fechada, false).andExpect(status().isOk());
        UUID cancelada = UUID.fromString(iniciar(cat).get("id").asString());
        enviar(BASE + "/" + cancelada + "/cancelar", mapa("motivo", "Engano")).andExpect(status().isOk());
        List<String> acoes = jdbc.queryForList("SELECT acao FROM corona.auditoria WHERE registro_id IN (?, ?) ORDER BY acao",
                String.class, fechada, cancelada);
        assertThat(acoes).contains("INVENTARIO_INICIADO", "INVENTARIO_FECHADO", "INVENTARIO_CANCELADO");
        assertThat(acoes.stream().filter(x -> x.equals("INVENTARIO_INICIADO")).count()).isEqualTo(2);
        // Fechar de novo não repete a auditoria.
        fechar(fechada, false).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.auditoria WHERE registro_id = ? AND acao = 'INVENTARIO_FECHADO'",
                Integer.class, fechada)).isEqualTo(1);
    }

    @Test
    void vendedorRecebe403EmTodasAsRotasESemLoginRecebe401() throws Exception {
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        UUID id = UUID.randomUUID();
        enviar(BASE, vendedor, mapa()).andExpect(status().isForbidden());
        for (String rota : List.of("/leituras", "/contagens", "/fechar", "/cancelar")) {
            enviar(BASE + "/" + id + rota, vendedor, mapa()).andExpect(status().isForbidden());
        }
        for (String rota : List.of("", "/" + id, "/" + id + "/itens")) {
            mvc.perform(get(BASE + rota).with(vendedor)).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void contagemNuncaApareceNoPullDoVendedor() throws Exception {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        assertThat(registro.sincronizaveis()).extracting(t -> t.nome())
                .doesNotContain("inventarios", "itens_inventario", "leituras_inventario");
        assertThat(registro.soDoServidor()).extracting(t -> t.nome())
                .contains("inventarios", "itens_inventario", "leituras_inventario");
        UUID cat = categoria();
        Peca a = peca(cat, null, 3);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        somar(inv, a, 2);
        fechar(inv, false).andExpect(status().isOk());
        LeituraDoPull tudo = LeituraDoPull.esvaziar(pull, null);
        Set<String> tabelas = new HashSet<>();
        tudo.linhas().forEach(l -> tabelas.add(l.tabela()));
        assertThat(tabelas).doesNotContain("inventarios", "itens_inventario", "leituras_inventario", "movimentacoes_estoque");
        assertThat(tudo.linhas().toString()).doesNotContain("esperado", "contado");
    }

    @Test
    void mensagensDeErroNaoTraemTermosProibidos() throws Exception {
        UUID cat = categoria();
        Peca a = peca(cat, null, 5);
        UUID inv = UUID.fromString(iniciar(cat).get("id").asString());
        definir(inv, a, 0);
        saida(a.id(), 3);
        List<String> textos = new ArrayList<>();
        textos.add(textoDe(fechar(inv, false).andExpect(status().isConflict())));
        textos.add(textoDe(enviar(BASE, mapa("id", UUID.randomUUID(), "escopo", "CATEGORIA", "escopoId", cat)).andExpect(status().isConflict())));
        textos.add(textoDe(enviar(BASE + "/" + inv + "/leituras", mapa("id", UUID.randomUUID(), "codigo", "NADA")).andExpect(status().isBadRequest())));
        textos.add(textoDe(enviar(BASE + "/" + UUID.randomUUID() + "/fechar", mapa()).andExpect(status().isNotFound())));
        UUID leitura = UUID.randomUUID();
        ler(inv, a, 1, leitura).andExpect(status().isCreated());
        textos.add(textoDe(ler(inv, a, 2, leitura).andExpect(status().isConflict())));
        enviar(BASE + "/" + inv + "/cancelar", mapa()).andExpect(status().isOk());
        textos.add(textoDe(ler(inv, a, 1, UUID.randomUUID()).andExpect(status().isConflict())));
        for (String texto : textos) {
            assertThat(texto.toLowerCase()).doesNotContain("estorn", "moviment", "invent", "sync", "idempot", "uuid");
        }
    }
}
