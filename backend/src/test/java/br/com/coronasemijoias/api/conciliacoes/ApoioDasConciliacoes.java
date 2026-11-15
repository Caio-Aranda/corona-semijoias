package br.com.coronasemijoias.api.conciliacoes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Diferenca;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Apoio dos testes das conciliações da Fase 7 (tarefa 7.8): monta UMA massa por classe (vários vendedores, semanas, acertos,
 * vendas a prazo, compras com contas a pagar e caixa) e confere as conciliações de produção sobre ela. O banco é dividido entre
 * as classes de teste, então a consulta olha o banco inteiro mas os testes só olham as diferenças cujas referências são desta
 * massa. Para "estragar um dado de propósito" o teste roda a MESMA consulta de produção sobre a conexão do dono das tabelas,
 * com os gatilhos desligados e a transação desfeita no fim: nada estragado chega a ser gravado.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ApoioDasConciliacoes extends TesteDeIntegracao {

    static final String ACERTOS = "/api/dona/acertos";
    static final String REMESSAS = "/api/dona/remessas";
    static final String VENDAS = "/api/vendedor/vendas";
    static final String CONTAS = "/api/dona/contas-a-pagar";
    static final String COMPRAS = "/api/dona/compras";
    static final String CAIXA = "/api/dona/caixa";
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
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
    RelogioDeTeste relogio;

    @Autowired
    TransactionTemplate transacao;

    @Autowired
    ConciliacoesDaFase7 conciliacoes;

    /** O que a massa criou: os testes só olham as referências daqui (o banco é dividido entre as classes de teste). */
    final List<UUID> vendedores = new ArrayList<>();
    final List<UUID> pecas = new ArrayList<>();
    final List<UUID> compras = new ArrayList<>();
    final List<UUID> lancamentosManuais = new ArrayList<>();

    @AfterAll
    static void tirarAsCotacoesEOsAcertosDaMassa() throws SQLException {
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    // --- requisições ---

    RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

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

    ResultActions postarDona(String caminho, Object corpo) throws Exception {
        return postar(caminho, dona(), corpo);
    }

    ResultActions ler(String caminho, RequestPostProcessor quem) throws Exception {
        return mvc.perform(get(caminho).with(quem));
    }

    JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    UUID umId(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    // --- cadastros, maletas e vendas ---

    UsuarioDeTeste novoVendedor() {
        UsuarioDeTeste v = usuarios.criarVendedor();
        vendedores.add(v.vendedorId());
        return v;
    }

    UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postarDona("/api/dona/clientes", mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(3900, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postarDona("/api/dona/semanas", mapa("numero", 98_000 + n, "inicio", inicio.toString(),
                "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated())).get("id").asString());
    }

    UUID peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postarDona("/api/dona/categorias", mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postarDona("/api/dona/produtos", mapa("nome", "Anel " + sufixo, "categoriaId", categoria,
                "precoVenda", preco, "precoCusto", "1.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "CR-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        pecas.add(id);
        return id;
    }

    /** Maleta nova enviada ao vendedor e já conferida por ele, numa semana nova. */
    UUID maletaConferida(UsuarioDeTeste vendedor, UUID semana, Object... pares) throws Exception {
        JsonNode r = corpoDe(postarDona(REMESSAS, mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana,
                "tipo", "NOVA")).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + pares[i]).with(dona())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", pares[i + 1], "version", r.get("version").asInt()))))
                    .andExpect(status().isOk()));
        }
        UUID id = UUID.fromString(r.get("id").asString());
        postarDona(REMESSAS + "/" + id + "/enviar", null).andExpect(status().isCreated());
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

    /** Venda à vista pela rota do vendedor; devolve o id. */
    UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, UUID peca, int quantidade, String valor) throws Exception {
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), mapa("id", id, "remessaId", maleta, "clienteId", cliente, "itens", List.of(linha(peca, quantidade)),
                "pagamentos", List.of(pagamento(valor, "DINHEIRO")))).andExpect(status().isCreated());
        return id;
    }

    LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }

    /** Venda a prazo: {@code pagoNaHora} pode ser nulo; o resto vira parcelas, a primeira amanhã. */
    UUID venderAPrazo(UsuarioDeTeste v, UUID maleta, UUID cliente, UUID peca, int quantidade, String pagoNaHora, int parcelas)
            throws Exception {
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), mapa("id", id, "remessaId", maleta, "clienteId", cliente, "itens", List.of(linha(peca, quantidade)),
                "pagamentos", pagoNaHora == null ? List.of() : List.of(pagamento(pagoNaHora, "DINHEIRO")),
                "parcelamento", mapa("quantidade", parcelas, "primeiroVencimento", hoje().plusDays(1).toString())))
                .andExpect(status().isCreated());
        return id;
    }

    // --- acerto ---

    void configurarComissao(int meta, int abaixo, int naMeta) {
        transacao.executeWithoutResult(s -> {
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'comissao_meta_guarani'", meta);
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'comissao_percentual_abaixo_da_meta'", abaixo);
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'comissao_percentual_na_meta'", naMeta);
        });
    }

    UUID iniciar(UsuarioDeTeste vendedor) throws Exception {
        return iniciar(vendedor, null);
    }

    UUID iniciar(UsuarioDeTeste vendedor, UUID semana) throws Exception {
        UUID id = UUID.randomUUID();
        postarDona(ACERTOS, mapa("id", id, "vendedorId", vendedor.vendedorId(), "semanaId", semana)).andExpect(status().isCreated());
        return id;
    }

    void bipar(UUID acerto, UUID peca, int quantidade, String estado) throws Exception {
        postarDona(ACERTOS + "/" + acerto + "/leituras", mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", quantidade,
                "estadoPeca", estado)).andExpect(status().isCreated());
    }

    static Map<String, Object> decisaoDeFalta(UUID produto, String decisao, String motivo) {
        return mapa("produtoId", produto, "decisao", decisao, "motivo", motivo);
    }

    static Map<String, Object> defeitoAprovado(UUID produto, int aprovadas) {
        return mapa("produtoId", produto, "aprovadas", aprovadas);
    }

    /** O corpo do fechar (líquido positivo: o vendedor ficou devendo tudo, em uma parcela daqui a 30 dias). */
    Map<String, Object> pedidoDeFechamento(List<?> faltas, List<?> defeitos, String liquidoMostrado) {
        Map<String, Object> pedido = mapa("id", UUID.randomUUID(), "faltas", faltas, "defeitos", defeitos, "cobrancas", List.of(),
                "pendenciasAntigas", List.of(), "liquidoMostrado", liquidoMostrado);
        if (new BigDecimal(liquidoMostrado).signum() > 0) {
            pedido.put("pagamento", mapa("restante", mapa("parcelas", 1, "primeiroVencimento", hoje().plusDays(30).toString())));
        }
        return pedido;
    }

    ResultActions fechar(UUID acerto, Map<String, Object> pedido) throws Exception {
        return postarDona(ACERTOS + "/" + acerto + "/fechar", pedido);
    }

    // --- contas a pagar ---

    UUID fornecedor() throws Exception {
        String nome = "Fornecedor " + UUID.randomUUID().toString().substring(0, 8);
        return UUID.fromString(corpoDe(postarDona("/api/dona/fornecedores", mapa("razaoSocial", nome)).andExpect(status().isCreated()))
                .get("id").asString());
    }

    /** Uma compra de uma peça só, de quantidade 1, no custo dado. Devolve o id. */
    UUID compra(UUID fornecedor, String total) throws Exception {
        UUID peca = peca("900.00", 0);
        UUID id = UUID.randomUUID();
        postarDona(COMPRAS, mapa("id", id, "fornecedorId", fornecedor, "dataCompra", "2026-11-01", "numero", "NF " + id,
                "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "custoUnitario", total)))).andExpect(status().isCreated());
        compras.add(id);
        return id;
    }

    List<UUID> contasDe(UUID compra, int parcelas) throws Exception {
        JsonNode r = corpoDe(postarDona(COMPRAS + "/" + compra + "/contas-a-pagar", mapa("id", UUID.randomUUID(), "parcelas", parcelas,
                "primeiroVencimento", LocalDate.of(2027, 6, 10))).andExpect(status().isCreated()));
        return r.valueStream().map(c -> UUID.fromString(c.get("id").asString())).toList();
    }

    UUID pagar(UUID conta, String valor, String forma) throws Exception {
        UUID id = UUID.randomUUID();
        postarDona(CONTAS + "/" + conta + "/pagamentos", mapa("id", id, "valor", valor, "forma", forma)).andExpect(status().isCreated());
        return id;
    }

    // --- o que é da massa ---

    private static String lista(List<UUID> ids) {
        if (ids.isEmpty()) {
            return "('00000000-0000-0000-0000-000000000000'::uuid)";
        }
        return ids.stream().map(i -> "'" + i + "'::uuid").collect(Collectors.joining(", ", "(", ")"));
    }

    /** Todos os ids (de qualquer tabela) que nasceram da massa desta classe; só UUIDs gerados aqui entram nas consultas. */
    Set<UUID> idsDaMassa() {
        String v = lista(vendedores);
        String vendas = "SELECT id FROM corona.vendas WHERE vendedor_id IN " + v;
        String acertos = "SELECT id FROM corona.acertos WHERE vendedor_id IN " + v;
        String recebimentos = "SELECT id FROM corona.recebimentos_vendedor WHERE vendedor_id IN " + v;
        String contasPagar = "SELECT id FROM corona.contas_pagar WHERE compra_id IN " + lista(compras);
        String baixasPagar = "SELECT id FROM corona.baixas_pagar WHERE conta_id IN (" + contasPagar + ")";
        Set<UUID> ids = new java.util.HashSet<>(vendedores);
        ids.addAll(pecas);
        ids.addAll(compras);
        for (String c : List.of(vendas, acertos, recebimentos, contasPagar, baixasPagar,
                "SELECT id FROM corona.remessas WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.devolucoes WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.comissoes WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.acerto_eventos WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.pendencias_acerto WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.contas_receber WHERE vendedor_id IN " + v + " OR venda_id IN (" + vendas + ")",
                "SELECT id FROM corona.lancamentos_caixa WHERE origem_id IN (" + recebimentos + ") OR origem_id IN (" + acertos
                        + ") OR origem_id IN (" + baixasPagar + ") OR id IN " + lista(lancamentosManuais))) {
            ids.addAll(jdbc.queryForList(c, UUID.class));
        }
        return ids;
    }

    /** Só as diferenças desta massa. */
    List<Diferenca> daMassa(List<Diferenca> todas) {
        Set<UUID> meus = idsDaMassa();
        return todas.stream().filter(d -> d.referencia() != null && meus.contains(d.referencia())).toList();
    }

    /** A conciliação de produção, sobre o banco normal. */
    List<Diferenca> diferencasDe(String chave) {
        return conciliacoes.conferir(chave).conferencias().get(0).divergencias();
    }

    /** Uma mudança para estragar o dado: tabelas com os gatilhos desligados e os comandos. */
    record Estrago(List<String> tabelas, List<String> comandos) {
        static Estrago de(List<String> tabelas, String... comandos) {
            return new Estrago(tabelas, List.of(comandos));
        }
    }

    /**
     * Estraga o dado de propósito (pelo dono das tabelas, com os gatilhos de usuário desligados, tudo dentro de uma transação
     * desfeita no fim) e roda a MESMA consulta de produção sobre essa conexão. Devolve as diferenças da conciliação pedida.
     */
    List<Diferenca> estragarEConferir(String chave, Estrago estrago) throws Exception {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            try {
                for (String t : estrago.tabelas()) {
                    s.execute("ALTER TABLE corona." + t + " DISABLE TRIGGER USER");
                }
                for (String c : estrago.comandos()) {
                    s.execute(c);
                }
                var comoDono = new JdbcTemplate(new SingleConnectionDataSource(con, true));
                return new ConciliacoesDaFase7(comoDono).conferir(chave).conferencias().get(0).divergencias();
            } finally {
                con.rollback();
            }
        }
    }

    static boolean tem(List<Diferenca> achadas, UUID referencia) {
        return achadas.stream().anyMatch(d -> referencia.equals(d.referencia()));
    }

    static boolean tem(List<Diferenca> achadas, String tipo, UUID referencia) {
        return achadas.stream().anyMatch(d -> tipo.equals(d.tipo()) && referencia.equals(d.referencia()));
    }
}
