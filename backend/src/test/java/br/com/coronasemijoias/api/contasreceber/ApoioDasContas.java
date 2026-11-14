package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
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
 * Apoio dos testes das contas a receber do cliente (tarefa 7.3): cada teste cria o próprio vendedor, cliente, semana,
 * peça e maleta. A conciliação do nível 1 (invariante 3 e atalhos = soma das baixas) roda depois de cada teste.
 */
abstract class ApoioDasContas extends TesteDeIntegracao {

    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
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
    RelogioDeTeste relogio;

    /** As vendas deste teste: a conciliação só olha estas (o banco é dividido entre os testes). */
    final List<UUID> vendasDoTeste = new ArrayList<>();

    /** Falso nos testes que estragam um dado de propósito (a conciliação é testada à parte). */
    boolean conciliar = true;

    @AfterEach
    void conciliarEVoltarAosPadroes() {
        relogio.reiniciar();
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'limite_desconto_percentual'");
        if (conciliar) {
            assertThat(diferencasDaConciliacao()).as("conciliação C1 e C5 do nível 1").isEmpty();
        }
    }

    /**
     * C1 (por venda: total menos o pago líquido = soma do em aberto das parcelas; venda estornada: zero) e C5 (atalhos
     * da parcela = soma das baixas). Só olha vendas que têm parcelas.
     */
    List<String> diferencasDaConciliacao() {
        // Só UUIDs gerados aqui entram no texto da consulta (nunca texto digitado).
        String ids = vendasDoTeste.isEmpty() ? "NULL::uuid"
                : String.join(",", vendasDoTeste.stream().map(u -> "'" + u + "'::uuid").toList());
        List<String> achadas = new ArrayList<>(jdbc.queryForList("""
                SELECT 'C1 ' || v.id || ' falta ' || (v.total - coalesce(p.liquido, 0)) || ' aberto ' || a.aberto
                  FROM corona.vendas v
                  JOIN (SELECT venda_id, sum(valor - valor_pago - valor_cancelado) AS aberto
                          FROM corona.contas_receber GROUP BY venda_id) a ON a.venda_id = v.id
                  LEFT JOIN (SELECT venda_id, sum(valor) AS liquido FROM corona.pagamentos_cliente
                              WHERE tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO', 'REEMBOLSO') GROUP BY venda_id) p ON p.venda_id = v.id
                 WHERE v.id IN (%s) AND v.status = 'CONCLUIDA' AND v.total - coalesce(p.liquido, 0) <> a.aberto""".formatted(ids), String.class));
        achadas.addAll(jdbc.queryForList("""
                SELECT 'C1 estornada ' || v.id FROM corona.vendas v
                  JOIN corona.contas_receber c ON c.venda_id = v.id
                 WHERE v.id IN (%s) AND v.status = 'ESTORNADA' GROUP BY v.id
                HAVING sum(c.valor - c.valor_pago - c.valor_cancelado) <> 0""".formatted(ids), String.class));
        achadas.addAll(jdbc.queryForList("""
                SELECT 'C5 ' || c.id FROM corona.contas_receber c
                 WHERE c.venda_id IN (%s) AND (c.valor_pago <> coalesce((SELECT sum(valor) FROM corona.baixas_receber b
                                                  WHERE b.conta_id = c.id AND b.tipo = 'PAGAMENTO'), 0)
                    OR c.valor_cancelado <> coalesce((SELECT sum(valor) FROM corona.baixas_receber b
                                                       WHERE b.conta_id = c.id AND b.tipo = 'CANCELAMENTO'), 0))""".formatted(ids), String.class));
        return achadas;
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

    ResultActions ler(String caminho, RequestPostProcessor quem) throws Exception {
        return mvc.perform(get(caminho).with(quem));
    }

    JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static String codigoDoErro(Throwable t) {
        assertThat(t).isInstanceOf(ErroDaApi.class);
        return ((ErroDaApi) t).codigo().name();
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    static BigDecimal v(String texto) {
        return new BigDecimal(texto);
    }

    // --- cadastros, maleta e venda ---

    UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(3300, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 60_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated())).get("id").asString());
    }

    UUID peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "1.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "CR-" + sufixo, "tipo", "LOJA", "principal", true))))
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

    /** Venda pela rota do vendedor (agora, sem parcelamento informado); devolve o id. */
    UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, List<Map<String, Object>> itens, List<Map<String, Object>> pagamentos)
            throws Exception {
        return vender(v, maleta, cliente, itens, pagamentos, null);
    }

    /** {@code parcelamento}: o mapa {@code quantidade} e {@code primeiroVencimento}; nulo = sem o campo. */
    UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, List<Map<String, Object>> itens, List<Map<String, Object>> pagamentos,
            Map<String, Object> parcelamento) throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> corpo = mapa("id", id, "remessaId", maleta, "clienteId", cliente, "itens", itens,
                "pagamentos", pagamentos);
        if (parcelamento != null) {
            corpo.put("parcelamento", parcelamento);
        }
        postar(VENDAS, como(v), corpo).andExpect(status().isCreated());
        vendasDoTeste.add(id);
        return id;
    }

    /** Meio-dia (Brasília) de {@code dias} dias atrás, pelo relógio da aplicação. */
    Instant diasAtras(int dias) {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA).minusDays(dias).atTime(LocalTime.NOON).atZone(BRASILIA).toInstant();
    }

    static Instant brasilia(String dataHora) {
        return java.time.LocalDateTime.parse(dataHora).atZone(BRASILIA).toInstant();
    }
}
