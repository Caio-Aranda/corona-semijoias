package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Apoio dos testes da autorização, da revisão e do antifraude da devolução (tarefa 6.4): cada teste cria os próprios
 * vendedor, cliente, semana, peças e maleta, e devolve a garantia, o desconto e o relógio aos padrões no fim (o banco e o
 * contexto são divididos entre os testes).
 */
abstract class ApoioDasDevolucoes extends TesteDeIntegracao {

    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    static final String DEVOLUCOES = "/api/vendedor/devolucoes";
    static final String DEVOLUCOES_DA_DONA = "/api/dona/devolucoes";
    static final String AUTORIZACOES = "/api/vendedor/autorizacoes";
    static final String AUTORIZACOES_DA_DONA = "/api/dona/autorizacoes";
    static final String VENDAS = "/api/vendedor/vendas";
    static final String REMESSAS = "/api/dona/remessas";
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

    @Autowired
    ServicoDeVendas vendas;

    @Autowired
    ServicoDeDevolucoes devolucoes;

    @Autowired
    ServicoDeAutorizacoes autorizacoes;

    @Autowired
    RelogioDeTeste relogio;

    @AfterEach
    void voltarAosPadroes() {
        relogio.reiniciar();
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 7 WHERE chave = 'prazo_arrependimento_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 30 WHERE chave = 'prazo_defeito_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'limite_desconto_percentual'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 20 WHERE chave = 'percentual_alerta_devolucao'");
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = NULL WHERE chave = 'limite_reembolso_semanal'");
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = NULL WHERE chave = 'valor_aprovacao_reembolso'");
    }

    // --- configurações ---

    void limiteSemanal(String valor) {
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = ? WHERE chave = 'limite_reembolso_semanal'",
                valor == null ? null : new BigDecimal(valor));
    }

    void valorDeAprovacao(String valor) {
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = ? WHERE chave = 'valor_aprovacao_reembolso'",
                valor == null ? null : new BigDecimal(valor));
    }

    void limiteDeDesconto(int percentual) {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'limite_desconto_percentual'", percentual);
    }

    // --- requisições ---

    RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    /** Um token por vendedor e por teste: o token leva o computador, e a maleta fica ligada a ele na conferência. */
    private final Map<UUID, RequestPostProcessor> tokens = new ConcurrentHashMap<>();

    RequestPostProcessor como(UsuarioDeTeste vendedor) {
        return tokens.computeIfAbsent(vendedor.id(), id -> UsuariosDeTeste.comToken(usuarios.token(vendedor)));
    }

    static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    ResultActions postar(String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo == null ? mapa() : corpo)));
    }

    ResultActions ler(String caminho, RequestPostProcessor quem) throws Exception {
        return mvc.perform(get(caminho).with(quem));
    }

    JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    String codigoDe(ResultActions r) throws Exception {
        return corpoDe(r).get("codigo").asString();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static String codigoDoErro(Throwable t) {
        assertThat(t).isInstanceOf(ErroDaApi.class);
        return ((ErroDaApi) t).codigo().name();
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // --- cadastros, maleta e venda ---

    UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(3100, 1, 1).plusDays(10L * n);
        return semana(40_000 + n, inicio, inicio.plusDays(6));
    }

    UUID semana(int numero, LocalDate inicio, LocalDate fim) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", numero,
                "inicio", inicio.toString(), "fim", fim.toString())).andExpect(status().isCreated())).get("id").asString());
    }

    UUID peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "1.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "AU-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return id;
    }

    /** Maleta enviada ao vendedor com os itens (produto, quantidade) e já conferida por ele. */
    UUID maleta(UsuarioDeTeste vendedor, Object... pares) throws Exception {
        JsonNode r = corpoDe(postar(REMESSAS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(),
                "semanaId", semana(), "tipo", "NOVA")).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + pares[i]).with(dona())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", pares[i + 1], "version", r.get("version").asInt()))))
                    .andExpect(status().isOk()));
        }
        UUID id = UUID.fromString(r.get("id").asString());
        postar(REMESSAS + "/" + id + "/enviar", dona(), null).andExpect(status().isCreated());
        List<Map<String, Object>> contadas = new ArrayList<>();
        for (int i = 0; i < pares.length; i += 2) {
            contadas.add(mapa("produtoId", pares[i], "contada", pares[i + 1]));
        }
        postar("/api/vendedor/maletas/" + id + "/conferencia", como(vendedor), mapa("id", UUID.randomUUID(), "itens", contadas))
                .andExpect(status().isCreated());
        return id;
    }

    static Map<String, Object> linha(UUID produto, int quantidade) {
        return mapa("produtoId", produto, "quantidade", quantidade);
    }

    static Map<String, Object> pagamento(String valor, String forma) {
        return mapa("id", UUID.randomUUID(), "valor", valor, "forma", forma);
    }

    /** Venda pela rota do vendedor (agora); devolve o id. */
    UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, List<Map<String, Object>> itens, List<Map<String, Object>> pagamentos)
            throws Exception {
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), mapa("id", id, "remessaId", maleta, "clienteId", cliente, "itens", itens,
                "pagamentos", pagamentos)).andExpect(status().isCreated());
        return id;
    }

    /** Venda à vista gravada pelo serviço com a hora escolhida. */
    UUID venderEm(UsuarioDeTeste v, UUID maleta, UUID cliente, UUID produto, int quantidade, String total, Instant quando) {
        return venderEm(v, maleta, cliente, produto, quantidade, null, total, quando);
    }

    UUID venderEm(UsuarioDeTeste v, UUID maleta, UUID cliente, UUID produto, int quantidade, String desconto, String total,
            Instant quando) {
        UUID id = UUID.randomUUID();
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(id, maleta, cliente,
                List.of(new LinhaDoPedido(produto, quantidade, desconto == null ? null : new BigDecimal(desconto))),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal(total), "DINHEIRO")), null), quando, false);
        return id;
    }

    /** Meio-dia (Brasília) de {@code dias} dias atrás, pelo relógio da aplicação. */
    Instant diasAtras(int dias) {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA).minusDays(dias).atTime(LocalTime.NOON).atZone(BRASILIA).toInstant();
    }

    static Instant brasilia(String dataHora) {
        return java.time.LocalDateTime.parse(dataHora).atZone(BRASILIA).toInstant();
    }

    // --- devolução ---

    static Map<String, Object> devolvida(UUID produto, int quantidade, String estado) {
        return mapa("produtoId", produto, "quantidade", quantidade, "estado", estado);
    }

    static Map<String, Object> devolucao(UUID id, UUID venda, String motivo, String forma, List<Map<String, Object>> itens) {
        return mapa("id", id, "vendaId", venda, "motivo", motivo, "formaReembolso", forma, "itens", itens);
    }

    static Map<String, Object> devolucaoAutorizada(UUID venda, String motivo, UUID autorizacao, List<Map<String, Object>> itens) {
        Map<String, Object> corpo = devolucao(UUID.randomUUID(), venda, motivo, "PIX", itens);
        corpo.put("autorizacaoId", autorizacao);
        return corpo;
    }

    /** Devolução pela rota do vendedor, esperando 201; devolve o corpo. */
    JsonNode devolver(UsuarioDeTeste v, UUID venda, String motivo, List<Map<String, Object>> itens) throws Exception {
        return corpoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, motivo, "PIX", itens))
                .andExpect(status().isCreated()));
    }

    ServicoDeDevolucoes.Resultado devolverPeloServico(UsuarioDeTeste v, UUID venda, String motivo, List<PecaDoPedido> pecas,
            UUID autorizacao, Instant quando, ModoDaDevolucao modo) {
        return devolucoes.devolver(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDaDevolucaoDoCliente(UUID.randomUUID(),
                venda, motivo, null, "DINHEIRO", pecas, null, autorizacao), quando, false, modo);
    }

    Map<String, Object> devolucaoGravada(UUID id) {
        return jdbc.queryForMap("""
                SELECT situacao, autorizacao_id, dentro_garantia, valor_devolvido, valor_reembolsado, revisao_fora_do_prazo,
                       revisao_acima_do_limite_semanal, revisao_acima_do_valor_de_aprovacao, revisao_autorizacao_invalida,
                       revisao_reembolso_maior_que_pago, revisada_em, revisada_por, observacao_revisao
                  FROM corona.devolucoes WHERE id = ?""", id);
    }

    /** Os motivos da revisão gravados, na ordem fixa da API. */
    List<String> motivosGravados(UUID devolucao) {
        Map<String, Object> d = devolucaoGravada(devolucao);
        List<String> motivos = new ArrayList<>();
        if (Boolean.TRUE.equals(d.get("revisao_fora_do_prazo"))) {
            motivos.add("FORA_DO_PRAZO");
        }
        if (Boolean.TRUE.equals(d.get("revisao_acima_do_limite_semanal"))) {
            motivos.add("ACIMA_DO_LIMITE_SEMANAL");
        }
        if (Boolean.TRUE.equals(d.get("revisao_acima_do_valor_de_aprovacao"))) {
            motivos.add("ACIMA_DO_VALOR_SEM_AUTORIZACAO");
        }
        if (Boolean.TRUE.equals(d.get("revisao_autorizacao_invalida"))) {
            motivos.add("AUTORIZACAO_INVALIDA");
        }
        if (Boolean.TRUE.equals(d.get("revisao_reembolso_maior_que_pago"))) {
            motivos.add("REEMBOLSO_MAIOR_QUE_O_PAGO");
        }
        return motivos;
    }

    String situacaoDaDevolucao(UUID id) {
        return jdbc.queryForObject("SELECT situacao FROM corona.devolucoes WHERE id = ?", String.class, id);
    }

    // --- autorização ---

    static Map<String, Object> pedidoDeAutorizacao(UUID id, UUID venda, String motivo, List<Map<String, Object>> itens) {
        return mapa("id", id, "vendaId", venda, "motivo", motivo, "itens", itens);
    }

    /** Pedido de autorização pela rota do vendedor, esperando 201; devolve o corpo. */
    JsonNode pedir(UsuarioDeTeste v, UUID venda, String motivo, List<Map<String, Object>> itens) throws Exception {
        return corpoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(UUID.randomUUID(), venda, motivo, itens))
                .andExpect(status().isCreated()));
    }

    ServicoDeAutorizacoes.Resultado pedirPeloServico(UsuarioDeTeste v, UUID venda, String motivo, List<PecaDoPedido> pecas,
            Instant quando) {
        return autorizacoes.pedir(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new ServicoDeAutorizacoes.PedidoDeAutorizacao(
                UUID.randomUUID(), venda, motivo, null, pecas), quando, false, ModoDaDevolucao.ONLINE);
    }

    JsonNode aprovar(UUID autorizacao) throws Exception {
        return corpoDe(postar(AUTORIZACOES_DA_DONA + "/" + autorizacao + "/aprovar", dona(), null).andExpect(status().isOk()));
    }

    String situacaoDaAutorizacao(UUID id) {
        return jdbc.queryForObject("SELECT situacao FROM corona.autorizacoes_excecao WHERE id = ?", String.class, id);
    }

    static UUID id(JsonNode corpo) {
        return UUID.fromString(corpo.get("id").asString());
    }
}
