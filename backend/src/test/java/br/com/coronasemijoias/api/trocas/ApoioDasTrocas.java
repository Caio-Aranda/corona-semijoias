package br.com.coronasemijoias.api.trocas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.relatorios.ConferenciasDeDevolucaoETroca;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.DevolucaoDaTroca;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.PedidoDeTroca;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.VendaDaTroca;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Apoio dos testes da troca e do crédito do cliente (tarefa 6.5): cada teste cria os próprios vendedor, cliente, semana,
 * peças e maletas, e devolve a garantia, os limites, o desconto e o relógio aos padrões no fim (o banco e o contexto são
 * divididos entre os testes).
 */
public abstract class ApoioDasTrocas extends TesteDeIntegracao {

    protected static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    protected static final String TROCAS = "/api/vendedor/trocas";
    protected static final String TROCAS_DA_DONA = "/api/dona/trocas";
    protected static final String CREDITOS = "/api/vendedor/creditos";
    protected static final String CREDITOS_DA_DONA = "/api/dona/creditos";
    protected static final String VENDAS = "/api/vendedor/vendas";
    protected static final String DEVOLUCOES = "/api/vendedor/devolucoes";
    protected static final String REMESSAS = "/api/dona/remessas";
    private static final AtomicInteger SEQUENCIA = new AtomicInteger(1);

    /** As tabelas que uma troca grava: a contagem antes e depois prova o "tudo ou nada". */
    protected static final List<String> TABELAS_DA_TROCA = List.of("trocas", "devolucoes", "itens_devolucao", "creditos_cliente",
            "usos_credito", "vendas", "itens_venda", "pagamentos_cliente", "movimentos_remessa", "autorizacoes_excecao");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected UsuariosDeTeste usuarios;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ServicoDeEstoque estoque;

    @Autowired
    private ConferenciasDeDevolucaoETroca conferencias;

    @Autowired
    protected ServicoDeVendas vendas;

    @Autowired
    protected ServicoDeDevolucoes devolucoes;

    @Autowired
    protected ServicoDeTrocas trocas;

    @Autowired
    protected RelogioDeTeste relogio;

    /** Monta o que o teste precisa antes (outra classe pode ter deixado outro prazo) e devolve depois. */
    @org.junit.jupiter.api.BeforeEach
    @AfterEach
    void voltarAosPadroes() {
        relogio.reiniciar();
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 7 WHERE chave = 'prazo_arrependimento_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 30 WHERE chave = 'prazo_defeito_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'limite_desconto_percentual'");
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = NULL WHERE chave = 'limite_reembolso_semanal'");
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = NULL WHERE chave = 'valor_aprovacao_reembolso'");
    }

    /**
     * Depois de cada caso a conciliação do banco inteiro tem de vir vazia: os testes dividem o mesmo banco, e uma sobra
     * deixada aqui aparece no relatório de conciliação (RelatoriosDeEstoqueEComprasTest) quando a ordem das classes muda.
     */
    @AfterEach
    void conciliacaoContinuaVazia() {
        var c = estoque.conciliar();
        assertThat(c.divergencias()).isEmpty();
        assertThat(c.diferencasComMaletas()).isEmpty();
        // As conferências da tarefa 6.10 (maleta, devolvido por peça, créditos, dinheiro devolvido, contas da venda).
        var todas = conferencias.conferir();
        assertThat(todas.conferencias()).allSatisfy(x -> assertThat(x.divergencias()).as(x.chave()).isEmpty());
    }

    // --- configurações ---

    protected void limiteSemanal(String valor) {
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = ? WHERE chave = 'limite_reembolso_semanal'",
                valor == null ? null : new BigDecimal(valor));
    }

    protected void valorDeAprovacao(String valor) {
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = ? WHERE chave = 'valor_aprovacao_reembolso'",
                valor == null ? null : new BigDecimal(valor));
    }

    protected void limiteDeDesconto(int percentual) {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'limite_desconto_percentual'", percentual);
    }

    // --- requisições ---

    protected RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    /** Um token por vendedor e por teste: o token leva o computador, e a maleta fica ligada a ele na conferência. */
    private final Map<UUID, RequestPostProcessor> tokens = new ConcurrentHashMap<>();

    protected RequestPostProcessor como(UsuarioDeTeste vendedor) {
        return tokens.computeIfAbsent(vendedor.id(), id -> UsuariosDeTeste.comToken(usuarios.token(vendedor)));
    }

    protected static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    protected ResultActions postar(String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo == null ? mapa() : corpo)));
    }

    protected ResultActions ler(String caminho, RequestPostProcessor quem) throws Exception {
        return mvc.perform(get(caminho).with(quem));
    }

    protected JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected String codigoDe(ResultActions r) throws Exception {
        return corpoDe(r).get("codigo").asString();
    }

    protected static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    protected static String codigoDoErro(Throwable t) {
        assertThat(t).isInstanceOf(ErroDaApi.class);
        return ((ErroDaApi) t).codigo().name();
    }

    protected int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected BigDecimal valor(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    /** Quantas linhas cada tabela da troca tem agora (para conferir que nada foi gravado). */
    protected Map<String, Integer> contagens() {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String t : TABELAS_DA_TROCA) {
            c.put(t, contar("SELECT count(*) FROM corona." + t));
        }
        return c;
    }

    // --- cadastros, maleta e venda ---

    protected UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    protected UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(3300, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 30_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated()))
                .get("id").asString());
    }

    protected UUID peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Colar " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "1.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "TR-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return id;
    }

    /** Maleta enviada ao vendedor com os itens (produto, quantidade), ainda sem conferir. */
    protected UUID maletaEnviada(UsuarioDeTeste vendedor, Object... pares) throws Exception {
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
        return id;
    }

    /** Maleta enviada ao vendedor e já conferida por ele (liga a maleta ao computador). */
    protected UUID maleta(UsuarioDeTeste vendedor, Object... pares) throws Exception {
        UUID id = maletaEnviada(vendedor, pares);
        List<Map<String, Object>> contadas = new ArrayList<>();
        for (int i = 0; i < pares.length; i += 2) {
            contadas.add(mapa("produtoId", pares[i], "contada", pares[i + 1]));
        }
        postar("/api/vendedor/maletas/" + id + "/conferencia", como(vendedor), mapa("id", UUID.randomUUID(), "itens", contadas))
                .andExpect(status().isCreated());
        return id;
    }

    protected int saldo(UUID maleta, UUID produto) {
        return jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                Integer.class, maleta, produto);
    }

    protected static Map<String, Object> linha(UUID produto, int quantidade) {
        return mapa("produtoId", produto, "quantidade", quantidade);
    }

    protected static Map<String, Object> linhaComDesconto(UUID produto, int quantidade, String desconto) {
        return mapa("produtoId", produto, "quantidade", quantidade, "desconto", desconto);
    }

    protected static Map<String, Object> pagamento(String valor, String forma) {
        return mapa("id", UUID.randomUUID(), "valor", valor, "forma", forma);
    }

    protected static Map<String, Object> pagamentoComCredito(String valor, UUID credito) {
        return mapa("id", UUID.randomUUID(), "valor", valor, "forma", "CREDITO", "creditoId", credito);
    }

    protected static Map<String, Object> pedidoDeVenda(UUID id, UUID maleta, UUID cliente, List<Map<String, Object>> itens,
            List<Map<String, Object>> pagamentos) {
        return mapa("id", id, "remessaId", maleta, "clienteId", cliente, "itens", itens, "pagamentos", pagamentos);
    }

    /** Venda pela rota do vendedor (agora), esperando 201; devolve o id. */
    protected UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, List<Map<String, Object>> itens,
            List<Map<String, Object>> pagamentos) throws Exception {
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), pedidoDeVenda(id, maleta, cliente, itens, pagamentos)).andExpect(status().isCreated());
        return id;
    }

    /** Venda à vista em dinheiro gravada pelo serviço com a hora escolhida. */
    protected UUID venderEm(UsuarioDeTeste v, UUID maleta, UUID cliente, UUID produto, int quantidade, String total,
            Instant quando) {
        UUID id = UUID.randomUUID();
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(id, maleta, cliente,
                List.of(new LinhaDoPedido(produto, quantidade, null)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal(total), "DINHEIRO")), null), quando, false);
        return id;
    }

    /** Meio-dia (Brasília) de {@code dias} dias atrás, pelo relógio da aplicação. */
    protected Instant diasAtras(int dias) {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA).minusDays(dias).atTime(LocalTime.NOON).atZone(BRASILIA).toInstant();
    }

    protected static Instant brasilia(String dataHora) {
        return LocalDateTime.parse(dataHora).atZone(BRASILIA).toInstant();
    }

    // --- troca ---

    protected static Map<String, Object> devolvida(UUID produto, int quantidade, String estado) {
        return mapa("produtoId", produto, "quantidade", quantidade, "estado", estado);
    }

    /**
     * O pedido da troca como o app manda: os ids (troca, crédito, pagamento do crédito, devolução e venda nova), a
     * devolução da venda A e a venda nova B com os pagamentos do resto. Nunca valor de A, diferença nem valor do crédito.
     */
    protected static Map<String, Object> pedidoDeTroca(UUID vendaA, String motivo, List<Map<String, Object>> voltaram,
            UUID maletaB, List<Map<String, Object>> levou, List<Map<String, Object>> resto) {
        return mapa("id", UUID.randomUUID(), "creditoId", UUID.randomUUID(), "pagamentoDoCreditoId", UUID.randomUUID(),
                "devolucao", mapa("id", UUID.randomUUID(), "vendaId", vendaA, "motivo", motivo, "itens", voltaram),
                "venda", mapa("id", UUID.randomUUID(), "remessaId", maletaB, "itens", levou, "pagamentos", resto));
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> devolucaoDo(Map<String, Object> pedido) {
        return (Map<String, Object>) pedido.get("devolucao");
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> vendaDo(Map<String, Object> pedido) {
        return (Map<String, Object>) pedido.get("venda");
    }

    protected static UUID idDe(Map<String, Object> parte) {
        return (UUID) parte.get("id");
    }

    /** Troca pela rota do vendedor, esperando 201; devolve o corpo. */
    protected JsonNode trocar(UsuarioDeTeste v, Map<String, Object> pedido) throws Exception {
        return corpoDe(postar(TROCAS, como(v), pedido).andExpect(status().isCreated()));
    }

    /** Troca simples pela rota: uma peça A boa (motivo desistência) por uma peça B, com os pagamentos do resto. */
    protected JsonNode trocar(UsuarioDeTeste v, UUID vendaA, UUID pecaA, UUID maletaB, UUID pecaB, List<Map<String, Object>> resto)
            throws Exception {
        return trocar(v, pedidoDeTroca(vendaA, "ARREPENDIMENTO", List.of(devolvida(pecaA, 1, "BOA")), maletaB,
                List.of(linha(pecaB, 1)), resto));
    }

    /** O mesmo pedido em forma de serviço, para escolher a hora e o modo (rota online ou envio do computador). */
    protected PedidoDeTroca pedidoDoServico(UUID vendaA, String motivo, List<PecaDoPedido> voltaram, UUID autorizacao,
            UUID maletaB, List<LinhaDoPedido> levou, List<PagamentoDoPedido> resto) {
        return new PedidoDeTroca(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new DevolucaoDaTroca(UUID.randomUUID(), vendaA, motivo, null, voltaram, autorizacao),
                new VendaDaTroca(UUID.randomUUID(), maletaB, levou, resto, null));
    }

    protected ServicoDeTrocas.Resultado trocarPeloServico(UsuarioDeTeste v, PedidoDeTroca pedido, Instant quando,
            ModoDaDevolucao modo) {
        return trocas.trocar(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), pedido, quando, false, modo);
    }

    protected static UUID id(JsonNode corpo) {
        return UUID.fromString(corpo.get("id").asString());
    }

    /** O crédito em aberto que uma troca com B mais barata deixa: venda A de {@code valorA}, troca por B de {@code valorB}. */
    protected UUID creditoDeSobra(UsuarioDeTeste v, UUID cliente, String valorA, String valorB) throws Exception {
        UUID a = peca(valorA, 5);
        UUID b = peca(valorB, 5);
        UUID m = maleta(v, a, 2, b, 2);
        UUID vendaA = vender(v, m, cliente, List.of(linha(a, 1)), List.of(pagamento(valorA, "DINHEIRO")));
        JsonNode troca = trocar(v, vendaA, a, m, b, List.of());
        return UUID.fromString(troca.get("creditoId").asString());
    }

    protected Map<String, Object> credito(UUID id) {
        return jdbc.queryForMap("""
                SELECT cliente_id, vendedor_id, origem, troca_id, devolucao_id, credito_origem_id, valor, valor_utilizado, situacao
                  FROM corona.creditos_cliente WHERE id = ?""", id);
    }

    protected BigDecimal somaDosUsos(UUID credito) {
        return valor("SELECT coalesce(sum(valor), 0) FROM corona.usos_credito WHERE credito_id = ?", credito);
    }

    protected BigDecimal somaDosPagamentos(UUID venda) {
        return valor("SELECT coalesce(sum(valor), 0) FROM corona.pagamentos_cliente WHERE venda_id = ?", venda);
    }
}
