package br.com.coronasemijoias.api.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
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

/** Movimentações de estoque (tarefa 2.2; ADR-0008). */
class EstoqueDaDonaTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/estoque";
    private static final String INSERT_CRU = """
            INSERT INTO corona.movimentacoes_estoque (id, produto_id, tipo, quantidade, saldo_depois, origem_tipo, usuario_id, ocorrida_em)
            VALUES (uuidv7(), ?, ?, ?, 0, 'MANUAL', ?, now())""";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ServicoDeEstoque servico;

    private RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    private UUID donaId() {
        return usuarios.dona().id();
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

    private UUID produto() throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(enviar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        Map<String, Object> codigo = mapa("codigo", "E-" + sufixo, "tipo", "LOJA", "principal", true);
        JsonNode criado = corpoDe(enviar("/api/dona/produtos", dona(), mapa("nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", "70.00", "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(codigo))).andExpect(status().isCreated()));
        return UUID.fromString(criado.get("id").asString());
    }

    /** Produto com o id escolhido pelo teste (como o Corona Gestão faz, com UUID versão 4). */
    private UUID produtoComId(UUID id) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(enviar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        Map<String, Object> codigo = mapa("codigo", "O-" + sufixo, "tipo", "LOJA", "principal", true);
        JsonNode criado = corpoDe(enviar("/api/dona/produtos", dona(), mapa("id", id, "nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", "70.00", "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(codigo))).andExpect(status().isCreated()));
        assertThat(criado.get("id").asString()).isEqualTo(id.toString());
        return id;
    }

    private int saldo(UUID produto) {
        return jdbc.queryForObject("SELECT estoque_atual FROM corona.produtos WHERE id = ?", Integer.class, produto);
    }

    private int somaDosMovimentos(UUID p) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(quantidade), 0) FROM corona.movimentacoes_estoque WHERE produto_id = ?", Integer.class, p);
    }

    private int quantosMovimentos(UUID p) {
        return jdbc.queryForObject("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ?", Integer.class, p);
    }

    private JsonNode lancar(UUID produto, String tipo, int quantidade) throws Exception {
        return corpoDe(enviar(BASE + "/movimentacoes", dona(),
                mapa("produtoId", produto, "tipo", tipo, "quantidade", quantidade)).andExpect(status().isCreated()));
    }

    private static Pedido pedido(UUID produto, TipoDeMovimento tipo, int quantidade) {
        return new Pedido(UUID.randomUUID(), produto, tipo, quantidade, null, OrigemDoMovimento.MANUAL, null);
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
    void vendedorRecebe403EmTodasAsRotasESemLoginRecebe401() throws Exception {
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        UUID id = UUID.randomUUID();
        enviar(BASE + "/movimentacoes", vendedor, mapa()).andExpect(status().isForbidden());
        enviar(BASE + "/ajustes", vendedor, mapa()).andExpect(status().isForbidden());
        enviar(BASE + "/movimentacoes/" + id + "/estorno", vendedor, mapa()).andExpect(status().isForbidden());
        for (String rota : List.of("/movimentacoes", "/produtos/" + id, "/conciliacao")) {
            mvc.perform(get(BASE + rota).with(vendedor)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + rota)).andExpect(status().isUnauthorized());
        }
    }

    // --- entrada, saída, perda, ficha e lista ---

    @Test
    void entradaSaidaEPerdaMudamOSaldoEFicaNaFicha() throws Exception {
        UUID p = produto();
        assertThat(lancar(p, "ENTRADA", 10).get("quantidade").asInt()).isEqualTo(10);
        assertThat(lancar(p, "SAIDA", 3).get("quantidade").asInt()).isEqualTo(-3);
        JsonNode perda = lancar(p, "AVARIA", 2);
        assertThat(perda.get("quantidade").asInt()).isEqualTo(-2);
        assertThat(perda.get("saldoDepois").asInt()).isEqualTo(5);
        assertThat(saldo(p)).isEqualTo(5);

        JsonNode ficha = corpoDe(ler(BASE + "/produtos/" + p).andExpect(status().isOk()));
        assertThat(ficha.get("saldo").asInt()).isEqualTo(5);
        assertThat(ficha.get("produto").has("fotoPrincipal")).as("a foto vem junto, mesmo sem foto").isTrue();
        assertThat(ficha.get("historico").get("itens")).hasSize(3);
        assertThat(ficha.get("historico").get("itens").get(0).get("tipo").asString()).isEqualTo("AVARIA");
        ler(BASE + "/produtos/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void listaFiltraPorProdutoTipoUsuarioOrigemEPeriodoEPagina() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 10);
        lancar(p, "SAIDA", 1);
        lancar(p, "SAIDA", 1);
        String filtro = BASE + "/movimentacoes?produtoId=" + p;
        JsonNode todos = corpoDe(ler(filtro).andExpect(status().isOk()));
        assertThat(todos.get("total").asInt()).isEqualTo(3);
        assertThat(todos.get("itens").get(0).get("produto").has("fotoPrincipal")).isTrue();
        assertThat(corpoDe(ler(filtro + "&tipo=SAIDA")).get("total").asInt()).isEqualTo(2);
        assertThat(corpoDe(ler(filtro + "&usuarioId=" + donaId())).get("total").asInt()).isEqualTo(3);
        assertThat(corpoDe(ler(filtro + "&usuarioId=" + UUID.randomUUID())).get("total").asInt()).isZero();
        assertThat(corpoDe(ler(filtro + "&origem=MANUAL")).get("total").asInt()).isEqualTo(3);
        assertThat(corpoDe(ler(filtro + "&origem=COMPRA")).get("total").asInt()).isZero();
        assertThat(corpoDe(ler(filtro + "&de=2999-01-01")).get("total").asInt()).isZero();
        assertThat(corpoDe(ler(filtro + "&de=2000-01-01&ate=2999-01-01")).get("total").asInt()).isEqualTo(3);
        JsonNode pagina = corpoDe(ler(filtro + "&tamanho=2&pagina=1"));
        assertThat(pagina.get("itens")).hasSize(1);
        assertThat(pagina.get("total").asInt()).isEqualTo(3);
        ler(BASE + "/movimentacoes?tipo=NADA").andExpect(status().isBadRequest());
        ler(BASE + "/movimentacoes?origem=NADA").andExpect(status().isBadRequest());
    }

    @Test
    void pedidosInvalidosSaoRecusados() throws Exception {
        UUID p = produto();
        enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", p, "tipo", "ENTRADA", "quantidade", 0)).andExpect(status().isBadRequest());
        enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", p, "tipo", "ENTRADA", "quantidade", -2)).andExpect(status().isBadRequest());
        enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", p, "tipo", "ENTRADA")).andExpect(status().isBadRequest());
        // maleta é da Fase 3 e ajuste tem rota própria
        enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", p, "tipo", "REMESSA", "quantidade", 1)).andExpect(status().isBadRequest());
        enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", p, "tipo", "AJUSTE", "quantidade", 1)).andExpect(status().isBadRequest());
        enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", UUID.randomUUID(), "tipo", "ENTRADA", "quantidade", 1))
                .andExpect(status().isNotFound());
        enviar(BASE + "/movimentacoes", dona(), mapa("id", UUID.nameUUIDFromBytes(new byte[] {1}), "produtoId", p,
                "tipo", "ENTRADA", "quantidade", 1)).andExpect(status().isBadRequest());
        assertThat(saldo(p)).isZero();
    }

    @Test
    void produtoExcluidoRecusaEInativoAindaPodeSerMovimentado() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 4);
        enviar("/api/dona/produtos/" + p + "/inativar", dona(), mapa()).andExpect(status().isOk());
        lancar(p, "AVARIA", 1); // a peça inativa ainda existe na loja
        mvc.perform(delete("/api/dona/produtos/" + p).with(dona())).andExpect(status().isNoContent());
        enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", p, "tipo", "ENTRADA", "quantidade", 1))
                .andExpect(status().isNotFound());
    }

    // --- saldo nunca negativo ---

    @Test
    void saidaQueDeixariaSaldoNegativoEhRecusadaDizendoQuantoHa() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 3);
        String corpo = textoDe(enviar(BASE + "/movimentacoes", dona(), mapa("produtoId", p, "tipo", "SAIDA", "quantidade", 4))
                .andExpect(status().isConflict()));
        assertThat(corpo).contains("Não há peças suficientes no estoque").contains("Há 3");
        assertThat(saldo(p)).isEqualTo(3);
        assertThat(quantosMovimentos(p)).isEqualTo(1);
    }

    @Test
    void bancoTemSegundaTravaContraSaldoNegativo() throws Exception {
        UUID p = produto();
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.produtos SET estoque_atual = -1 WHERE id = ?", p))
                .hasMessageContaining("produtos_estoque_atual_nao_negativo");
    }

    // --- idempotência ---

    @Test
    void mesmoIdComMesmosDadosDevolveOJaGravadoSemSomarDeNovo() throws Exception {
        UUID p = produto();
        UUID id = UUID.randomUUID();
        Map<String, Object> corpo = mapa("id", id, "produtoId", p, "tipo", "ENTRADA", "quantidade", 5, "motivo", "Chegou da feira");
        enviar(BASE + "/movimentacoes", dona(), corpo).andExpect(status().isCreated());
        JsonNode deNovo = corpoDe(enviar(BASE + "/movimentacoes", dona(), corpo).andExpect(status().isOk()));
        assertThat(deNovo.get("id").asString()).isEqualTo(id.toString());
        assertThat(saldo(p)).isEqualTo(5);
        assertThat(quantosMovimentos(p)).isEqualTo(1);
    }

    @Test
    void mesmoIdComDadosDiferentesEhConflito() throws Exception {
        UUID p = produto();
        UUID id = UUID.randomUUID();
        enviar(BASE + "/movimentacoes", dona(), mapa("id", id, "produtoId", p, "tipo", "ENTRADA", "quantidade", 5)).andExpect(status().isCreated());
        enviar(BASE + "/movimentacoes", dona(), mapa("id", id, "produtoId", p, "tipo", "ENTRADA", "quantidade", 6)).andExpect(status().isConflict());
        enviar(BASE + "/movimentacoes", dona(), mapa("id", id, "produtoId", p, "tipo", "SAIDA", "quantidade", 5)).andExpect(status().isConflict());
        assertThat(saldo(p)).isEqualTo(5);
    }

    @Test
    void reenvioSimultaneoDoMesmoIdSomaUmaVezSo() throws Exception {
        UUID p = produto();
        UUID id = UUID.randomUUID();
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tarefas.add(() -> servico.registrar(new Pedido(id, p, TipoDeMovimento.ENTRADA, 7, null, OrigemDoMovimento.MANUAL, null),
                    donaId()).movimento().quantidade());
        }
        paralelo(tarefas);
        assertThat(saldo(p)).isEqualTo(7);
        assertThat(quantosMovimentos(p)).isEqualTo(1);
    }

    // --- ajuste manual ---

    @Test
    void ajustePorNovaQuantidadeEPorDiferencaComMotivoEAuditoria() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 10);
        JsonNode para = corpoDe(enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "novaQuantidade", 7, "motivo", "Contei de novo"))
                .andExpect(status().isCreated()));
        assertThat(para.get("tipo").asString()).isEqualTo("AJUSTE");
        assertThat(para.get("quantidade").asInt()).isEqualTo(-3);
        assertThat(para.get("origem").asString()).isEqualTo("AJUSTE_MANUAL");
        assertThat(saldo(p)).isEqualTo(7);
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "diferenca", 5, "motivo", "Achei uma caixa")).andExpect(status().isCreated());
        assertThat(saldo(p)).isEqualTo(12);

        UUID movimento = UUID.fromString(para.get("id").asString());
        Map<String, Object> auditoria = jdbc.queryForMap("""
                SELECT acao, ator_usuario_id, antes::text AS antes, depois::text AS depois
                  FROM corona.auditoria WHERE tabela = 'movimentacoes_estoque' AND registro_id = ?""", movimento);
        assertThat(auditoria.get("acao")).isEqualTo("ESTOQUE_AJUSTADO");
        assertThat(auditoria.get("ator_usuario_id")).isEqualTo(donaId());
        assertThat((String) auditoria.get("antes")).contains("10");
        assertThat((String) auditoria.get("depois")).contains("7").contains("Contei de novo");
    }

    @Test
    void ajusteSemMotivoSemMudancaOuAbaixoDeZeroEhRecusado() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 2);
        String semMotivo = textoDe(enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "novaQuantidade", 1))
                .andExpect(status().isBadRequest()));
        assertThat(semMotivo).contains("Diga o motivo do ajuste");
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "novaQuantidade", 1, "motivo", "  ")).andExpect(status().isBadRequest());
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "novaQuantidade", 2, "motivo", "igual")).andExpect(status().isBadRequest());
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "diferenca", 0, "motivo", "zero")).andExpect(status().isBadRequest());
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "novaQuantidade", 1, "diferenca", 1, "motivo", "os dois")).andExpect(status().isBadRequest());
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "motivo", "nenhum")).andExpect(status().isBadRequest());
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "novaQuantidade", -1, "motivo", "negativo")).andExpect(status().isBadRequest());
        enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "diferenca", -3, "motivo", "tirei demais")).andExpect(status().isConflict());
        assertThat(saldo(p)).isEqualTo(2);
    }

    @Test
    void ajusteRepetidoComOMesmoIdNaoSomaDeNovo() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 10);
        Map<String, Object> corpo = mapa("id", UUID.randomUUID(), "produtoId", p, "novaQuantidade", 4, "motivo", "Contagem");
        enviar(BASE + "/ajustes", dona(), corpo).andExpect(status().isCreated());
        enviar(BASE + "/ajustes", dona(), corpo).andExpect(status().isOk());
        assertThat(saldo(p)).isEqualTo(4);
        assertThat(quantosMovimentos(p)).isEqualTo(2);
    }

    // --- estorno ---

    @Test
    void estornoCriaMovimentoInversoLigadoAoOriginalComMotivoEAuditoria() throws Exception {
        UUID p = produto();
        String original = lancar(p, "ENTRADA", 5).get("id").asString();
        JsonNode estorno = corpoDe(enviar(BASE + "/movimentacoes/" + original + "/estorno", dona(), mapa("motivo", "Lancei errado"))
                .andExpect(status().isCreated()));
        assertThat(estorno.get("tipo").asString()).isEqualTo("ENTRADA");
        assertThat(estorno.get("quantidade").asInt()).isEqualTo(-5);
        assertThat(estorno.get("estornaId").asString()).isEqualTo(original);
        assertThat(estorno.get("origem").asString()).isEqualTo("ESTORNO");
        assertThat(saldo(p)).isZero();

        boolean achou = false;
        for (JsonNode item : corpoDe(ler(BASE + "/movimentacoes?produtoId=" + p)).get("itens")) {
            if (item.get("id").asString().equals(original)) {
                achou = true;
                assertThat(item.get("quantidade").asInt()).isEqualTo(5);
                assertThat(item.get("desfeitoPorId").asString()).isEqualTo(estorno.get("id").asString());
            }
        }
        assertThat(achou).isTrue();
        Map<String, Object> auditoria = jdbc.queryForMap(
                "SELECT acao, ator_usuario_id FROM corona.auditoria WHERE tabela = 'movimentacoes_estoque' AND registro_id = ?",
                UUID.fromString(estorno.get("id").asString()));
        assertThat(auditoria.get("acao")).isEqualTo("MOVIMENTO_ESTORNADO");
        assertThat(auditoria.get("ator_usuario_id")).isEqualTo(donaId());
    }

    @Test
    void estornoDeSaidaDevolveAsPecasEDeAjusteInverteOAjuste() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 5);
        JsonNode saida = lancar(p, "SAIDA", 2);
        enviar(BASE + "/movimentacoes/" + saida.get("id").asString() + "/estorno", dona(), mapa("motivo", "Voltou")).andExpect(status().isCreated());
        assertThat(saldo(p)).isEqualTo(5);
        JsonNode ajuste = corpoDe(enviar(BASE + "/ajustes", dona(), mapa("produtoId", p, "diferenca", -2, "motivo", "x")).andExpect(status().isCreated()));
        enviar(BASE + "/movimentacoes/" + ajuste.get("id").asString() + "/estorno", dona(), mapa("motivo", "Era engano")).andExpect(status().isCreated());
        assertThat(saldo(p)).isEqualTo(5);
        assertThat(somaDosMovimentos(p)).isEqualTo(5);
    }

    @Test
    void estornoRecusaSemMotivoDuasVezesDeEstornoESaldoNegativo() throws Exception {
        UUID p = produto();
        String entrada = lancar(p, "ENTRADA", 5).get("id").asString();
        lancar(p, "SAIDA", 4);
        enviar(BASE + "/movimentacoes/" + entrada + "/estorno", dona(), mapa()).andExpect(status().isBadRequest());
        enviar(BASE + "/movimentacoes/" + entrada + "/estorno", dona(), mapa("motivo", " ")).andExpect(status().isBadRequest());
        // deixaria o saldo em -4
        String recusa = textoDe(enviar(BASE + "/movimentacoes/" + entrada + "/estorno", dona(), mapa("motivo", "erro"))
                .andExpect(status().isConflict()));
        assertThat(recusa).contains("Não há peças suficientes no estoque");
        assertThat(saldo(p)).isEqualTo(1);
        enviar(BASE + "/movimentacoes/" + UUID.randomUUID() + "/estorno", dona(), mapa("motivo", "erro")).andExpect(status().isNotFound());

        UUID q = produto();
        String e2 = lancar(q, "ENTRADA", 5).get("id").asString();
        JsonNode estorno = corpoDe(enviar(BASE + "/movimentacoes/" + e2 + "/estorno", dona(), mapa("motivo", "erro")).andExpect(status().isCreated()));
        String jaDesfeito = textoDe(enviar(BASE + "/movimentacoes/" + e2 + "/estorno", dona(), mapa("motivo", "de novo"))
                .andExpect(status().isConflict()));
        assertThat(jaDesfeito).contains("Este lançamento já foi desfeito.");
        // estorno de estorno
        enviar(BASE + "/movimentacoes/" + estorno.get("id").asString() + "/estorno", dona(), mapa("motivo", "desfazer o desfazer"))
                .andExpect(status().isConflict());
        assertThat(saldo(q)).isZero();
    }

    @Test
    void estornoRepetidoComOMesmoIdEIdempotenteEComOutroMotivoEConflito() throws Exception {
        UUID p = produto();
        String original = lancar(p, "ENTRADA", 5).get("id").asString();
        UUID id = UUID.randomUUID();
        Map<String, Object> corpo = mapa("id", id, "motivo", "Lancei errado");
        enviar(BASE + "/movimentacoes/" + original + "/estorno", dona(), corpo).andExpect(status().isCreated());
        enviar(BASE + "/movimentacoes/" + original + "/estorno", dona(), corpo).andExpect(status().isOk());
        enviar(BASE + "/movimentacoes/" + original + "/estorno", dona(), mapa("id", id, "motivo", "Outro motivo")).andExpect(status().isConflict());
        assertThat(saldo(p)).isZero();
    }

    @Test
    void doisEstornosAoMesmoTempoDesfazemUmaVezSo() throws Exception {
        UUID p = produto();
        UUID original = UUID.fromString(lancar(p, "ENTRADA", 5).get("id").asString());
        lancar(p, "ENTRADA", 5);
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tarefas.add(() -> {
                try {
                    servico.estornar(UUID.randomUUID(), original, "erro", donaId());
                    return 1;
                } catch (ErroDaApi e) {
                    return 0;
                }
            });
        }
        assertThat(paralelo(tarefas).stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(saldo(p)).isEqualTo(5);
        assertThat(somaDosMovimentos(p)).isEqualTo(5);
    }

    // --- imutabilidade e travas do banco ---

    @Test
    void movimentoNaoPodeSerAlteradoNemApagadoNemPeloBanco() throws Exception {
        UUID p = produto();
        String id = lancar(p, "ENTRADA", 5).get("id").asString();
        try (Connection c = conectarComoAplicacao()) {
            c.setAutoCommit(true);
            assertThatThrownBy(() -> executar(c, "UPDATE corona.movimentacoes_estoque SET quantidade = 99 WHERE id = ?::uuid", id))
                    .hasMessageContaining("não podem ser alterados");
            assertThatThrownBy(() -> executar(c, "UPDATE corona.movimentacoes_estoque SET motivo = 'x' WHERE id = ?::uuid", id))
                    .hasMessageContaining("não podem ser alterados");
            assertThatThrownBy(() -> executar(c, "DELETE FROM corona.movimentacoes_estoque WHERE id = ?::uuid", id))
                    .hasMessageContaining("permission denied"); // o usuário da aplicação nem tem permissão de DELETE
            try (Connection dono = conectarComoMigracao()) {
                dono.setAutoCommit(true);
                assertThatThrownBy(() -> executar(dono, "DELETE FROM corona.movimentacoes_estoque WHERE id = ?::uuid", id))
                        .hasMessageContaining("Não é permitido apagar"); // e o gatilho recusa até o dono da tabela
            }
        }
        assertThat(saldo(p)).isEqualTo(5);
    }

    private static void executar(Connection c, String sql, String id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    @Test
    void bancoRecusaSinalErradoParaOTipo() throws Exception {
        UUID p = produto();
        UUID dona = donaId();
        assertThatThrownBy(() -> jdbc.update(INSERT_CRU, p, "ENTRADA", -1, dona)).hasMessageContaining("movimentacoes_estoque_sinal_do_tipo");
        assertThatThrownBy(() -> jdbc.update(INSERT_CRU, p, "SAIDA", 1, dona)).hasMessageContaining("movimentacoes_estoque_sinal_do_tipo");
        assertThatThrownBy(() -> jdbc.update(INSERT_CRU, p, "ENTRADA", 0, dona)).hasMessageContaining("movimentacoes_estoque_quantidade_nao_zero");
        assertThatThrownBy(() -> jdbc.update(INSERT_CRU, p, "AJUSTE", 1, dona)).hasMessageContaining("movimentacoes_estoque_motivo_do_estorno");
    }

    // --- concorrência real ---

    @Test
    void entradasESaidasAoMesmoTempoNoMesmoProdutoDaoSaldoExato() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 200);
        int entradas = 0;
        int saidas = 0;
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int t = 0; t < 24; t++) {
            boolean entrada = t % 2 == 0;
            int quantidade = 1 + (t % 5);
            if (entrada) {
                entradas += quantidade * 10;
            } else {
                saidas += quantidade * 10;
            }
            tarefas.add(() -> {
                for (int i = 0; i < 10; i++) {
                    servico.registrar(pedido(p, entrada ? TipoDeMovimento.ENTRADA : TipoDeMovimento.SAIDA, quantidade), donaId());
                }
                return 1;
            });
        }
        paralelo(tarefas);
        assertThat(saldo(p)).isEqualTo(200 + entradas - saidas);
        assertThat(somaDosMovimentos(p)).isEqualTo(saldo(p));
        assertThat(servico.conciliar().divergencias()).extracting(d -> d.produtoId()).doesNotContain(p);
    }

    @Test
    void saidasAoMesmoTempoNuncaDeixamOSaldoNegativo() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 10);
        AtomicInteger aceitas = new AtomicInteger();
        AtomicInteger recusadas = new AtomicInteger();
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int t = 0; t < 30; t++) {
            tarefas.add(() -> {
                try {
                    servico.registrar(pedido(p, TipoDeMovimento.SAIDA, 1), donaId());
                    return aceitas.incrementAndGet();
                } catch (ErroDaApi e) {
                    return -recusadas.incrementAndGet();
                }
            });
        }
        paralelo(tarefas);
        assertThat(aceitas.get()).isEqualTo(10);
        assertThat(recusadas.get()).isEqualTo(20);
        assertThat(saldo(p)).isZero();
        assertThat(jdbc.queryForObject("SELECT min(saldo_depois) FROM corona.movimentacoes_estoque WHERE produto_id = ?", Integer.class, p)).isZero();
    }

    @Test
    void operacoesComVariosProdutosEmOrdensCruzadasNaoTravamNemPerdemNada() throws Exception {
        UUID a = produto();
        UUID b = produto();
        UUID c = produto();
        for (UUID p : List.of(a, b, c)) {
            lancar(p, "ENTRADA", 1000);
        }
        int[] esperado = new int[3];
        List<Callable<Integer>> tarefas = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            // ordens diferentes de propósito: quem ordena os bloqueios é o serviço
            List<UUID> ordem = switch (t % 4) {
                case 0 -> List.of(a, b, c);
                case 1 -> List.of(c, b, a);
                case 2 -> List.of(b, a);
                default -> List.of(c, a);
            };
            for (UUID p : ordem) {
                esperado[p.equals(a) ? 0 : p.equals(b) ? 1 : 2] += 5;
            }
            tarefas.add(() -> {
                for (int i = 0; i < 5; i++) {
                    List<Pedido> lote = new ArrayList<>();
                    for (UUID p : ordem) {
                        lote.add(pedido(p, TipoDeMovimento.SAIDA, 1));
                    }
                    servico.registrar(lote, donaId());
                }
                return 1;
            });
        }
        paralelo(tarefas);
        assertThat(saldo(a)).isEqualTo(1000 - esperado[0]);
        assertThat(saldo(b)).isEqualTo(1000 - esperado[1]);
        assertThat(saldo(c)).isEqualTo(1000 - esperado[2]);
    }

    /**
     * ADR-0008, item 7: o serviço trava os produtos na MESMA ordem do banco ({@code ORDER BY id}, que compara o uuid byte a
     * byte, sem sinal). O estorno da compra, o cancelamento da maleta e a contagem travam peça por peça nessa ordem; se o
     * serviço usasse a ordem do Java ({@code UUID.compareTo}, com sinal), um id que começa com 8 a f trocaria de lugar e
     * duas operações com as mesmas peças se esperariam em volta. Os ids do Corona Gestão são versão 4 (aleatórios).
     */
    @Test
    void travaOsProdutosNaOrdemDoBancoMesmoQuandoOJavaOrdenaDiferente() throws Exception {
        UUID primeiro = produtoComId(UUID.fromString("1" + UUID.randomUUID().toString().substring(1)));
        UUID segundo = produtoComId(UUID.fromString("9" + UUID.randomUUID().toString().substring(1)));
        // O caso que importa: o banco põe "primeiro" antes, o Java põe "segundo" antes.
        assertThat(jdbc.queryForObject("SELECT CAST(? AS uuid) < CAST(? AS uuid)", Boolean.class, primeiro, segundo)).isTrue();
        assertThat(segundo.compareTo(primeiro)).isNegative();
        lancar(primeiro, "ENTRADA", 5);
        lancar(segundo, "ENTRADA", 5);
        UUID dona = donaId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection outra = conectarComoAplicacao()) {
            // Outra operação segura o último produto na ordem do banco.
            travar(outra, segundo, false);
            int pidDaOutra = pid(outra);
            Future<List<ServicoDeEstoque.Resultado>> lote = pool.submit(() -> servico.registrar(
                    List.of(pedido(segundo, TipoDeMovimento.SAIDA, 1), pedido(primeiro, TipoDeMovimento.SAIDA, 1)), dona));
            esperarAlguemParadoNaTravaDe(pidDaOutra);
            // Na ordem do banco, o serviço já travou o primeiro e está parado no segundo.
            try (Connection sonda = conectarComoAplicacao()) {
                assertThat(travar(sonda, primeiro, true))
                        .as("o serviço deveria ter travado %s (primeiro na ordem do banco) antes de esperar %s", primeiro, segundo)
                        .isFalse();
                sonda.rollback();
            }
            outra.rollback();
            lote.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(saldo(primeiro)).isEqualTo(4);
        assertThat(saldo(segundo)).isEqualTo(4);
    }

    /** Trava a linha do produto. Com {@code semEsperar}, devolve false se outra transação já a segura. */
    private static boolean travar(Connection c, UUID produto, boolean semEsperar) throws Exception {
        String sql = "SELECT id FROM corona.produtos WHERE id = ? FOR UPDATE" + (semEsperar ? " NOWAIT" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, produto);
            ps.executeQuery().close();
            return true;
        } catch (java.sql.SQLException e) {
            if ("55P03".equals(e.getSQLState())) { // lock_not_available
                return false;
            }
            throw e;
        }
    }

    private static int pid(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_backend_pid()"); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void esperarAlguemParadoNaTravaDe(int pid) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pg_locks l WHERE NOT l.granted AND ? = ANY (pg_blocking_pids(l.pid)))""",
                Boolean.class, pid))) {
            assertThat(System.nanoTime()).as("o serviço não chegou a esperar a trava").isLessThan(limite);
            Thread.sleep(20);
        }
    }

    @Test
    void loteComUmProdutoSemSaldoNaoGravaNada() throws Exception {
        UUID a = produto();
        UUID b = produto();
        lancar(a, "ENTRADA", 5);
        List<Pedido> lote = List.of(pedido(a, TipoDeMovimento.SAIDA, 2), pedido(b, TipoDeMovimento.SAIDA, 1));
        assertThatThrownBy(() -> servico.registrar(lote, donaId())).isInstanceOf(ErroDaApi.class);
        assertThat(saldo(a)).isEqualTo(5);
        assertThat(saldo(b)).isZero();
        assertThat(quantosMovimentos(a)).isEqualTo(1);
    }

    @Test
    void mesmoProdutoDuasVezesNoLoteSomaNaOrdemEIdRepetidoNoLoteEhRecusado() throws Exception {
        UUID p = produto();
        servico.registrar(List.of(pedido(p, TipoDeMovimento.ENTRADA, 3), pedido(p, TipoDeMovimento.SAIDA, 2)), donaId());
        assertThat(saldo(p)).isEqualTo(1);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> servico.registrar(List.of(
                new Pedido(id, p, TipoDeMovimento.ENTRADA, 1, null, OrigemDoMovimento.MANUAL, null),
                new Pedido(id, p, TipoDeMovimento.ENTRADA, 1, null, OrigemDoMovimento.MANUAL, null)), donaId()))
                .isInstanceOf(ErroDaApi.class);
        assertThat(saldo(p)).isEqualTo(1);
    }

    // --- conciliação ---

    @Test
    void conciliacaoVemVaziaEApontaDivergenciaProvocadaNoBanco() throws Exception {
        UUID p = produto();
        lancar(p, "ENTRADA", 8);
        lancar(p, "SAIDA", 3);
        assertThat(corpoDe(ler(BASE + "/conciliacao").andExpect(status().isOk())).get("divergencias")).isEmpty();
        assertThat(servico.conciliar().divergencias()).isEmpty();

        // alguém mexe no saldo por fora do serviço (só um teste faz isto)
        jdbc.update("UPDATE corona.produtos SET estoque_atual = 99 WHERE id = ?", p);
        JsonNode apontou = corpoDe(ler(BASE + "/conciliacao").andExpect(status().isOk()));
        assertThat(apontou.get("divergencias")).hasSize(1);
        JsonNode d = apontou.get("divergencias").get(0);
        assertThat(d.get("produto").get("id").asString()).isEqualTo(p.toString());
        assertThat(d.get("produto").has("fotoPrincipal")).isTrue();
        assertThat(d.get("saldoGuardado").asInt()).isEqualTo(99);
        assertThat(d.get("somaDosMovimentos").asInt()).isEqualTo(5);
        assertThat(d.get("diferenca").asInt()).isEqualTo(94);

        jdbc.update("UPDATE corona.produtos SET estoque_atual = 5 WHERE id = ?", p);
        assertThat(servico.conciliar().divergencias()).isEmpty();
    }

    @Test
    void produtoSemMovimentoComSaldoNaoZeroTambemDiverge() throws Exception {
        UUID p = produto();
        jdbc.update("UPDATE corona.produtos SET estoque_atual = 4 WHERE id = ?", p);
        assertThat(servico.conciliar().divergencias()).extracting(d -> d.produtoId()).contains(p);
        jdbc.update("UPDATE corona.produtos SET estoque_atual = 0 WHERE id = ?", p);
        assertThat(servico.conciliar().divergencias()).isEmpty();
    }
}
