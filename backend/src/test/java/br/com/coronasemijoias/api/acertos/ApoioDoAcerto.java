package br.com.coronasemijoias.api.acertos;

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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Apoio dos testes do acerto, parte 1 (tarefa 7.4): cada teste cria o próprio vendedor, semana, peça e maletas. Depois de
 * cada teste a conciliação "estoque da loja + maletas" (Fase 3) tem de continuar zerada.
 */
abstract class ApoioDoAcerto extends TesteDeIntegracao {

    static final String ACERTOS_DA_DONA = "/api/dona/acertos";
    static final String ACERTOS_DO_VENDEDOR = "/api/vendedor/acertos";
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

    @AfterEach
    void conciliarEVoltarAosPadroes() {
        relogio.reiniciar();
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'limite_desconto_percentual'");
        assertThat(estoque.conciliar().diferencasComMaletas()).as("estoque com as maletas").isEmpty();
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

    // --- cadastros, maletas e vendas ---

    UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(3800, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 97_000 + n,
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

    /** Maleta enviada (rascunho montado e enviado), sem conferir. {@code tipo}: NOVA ou REPOSICAO. */
    UUID maletaEnviada(UsuarioDeTeste vendedor, UUID semana, String tipo, Object... pares) throws Exception {
        UUID id = rascunho(vendedor, semana, tipo, pares);
        postar(REMESSAS + "/" + id + "/enviar", dona(), null).andExpect(status().isCreated());
        return id;
    }

    /** Maleta nova enviada e já conferida pelo vendedor, numa semana nova. */
    UUID maleta(UsuarioDeTeste vendedor, Object... pares) throws Exception {
        return maletaConferida(vendedor, semana(), pares);
    }

    UUID maletaConferida(UsuarioDeTeste vendedor, UUID semana, Object... pares) throws Exception {
        UUID id = maletaEnviada(vendedor, semana, "NOVA", pares);
        List<Map<String, Object>> contadas = new ArrayList<>();
        for (int i = 0; i < pares.length; i += 2) {
            contadas.add(mapa("produtoId", pares[i], "contada", pares[i + 1]));
        }
        postar("/api/vendedor/maletas/" + id + "/conferencia", como(vendedor), mapa("id", UUID.randomUUID(), "itens", contadas))
                .andExpect(status().isCreated());
        return id;
    }

    UUID rascunho(UsuarioDeTeste vendedor, UUID semana, String tipo, Object... pares) throws Exception {
        JsonNode r = corpoDe(postar(REMESSAS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(),
                "semanaId", semana, "tipo", tipo)).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + pares[i]).with(dona())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", pares[i + 1], "version", r.get("version").asInt()))))
                    .andExpect(status().isOk()));
        }
        return UUID.fromString(r.get("id").asString());
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
        postar(VENDAS, como(v), mapa("id", id, "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(linha(peca, quantidade)), "pagamentos", List.of(pagamento(valor, "DINHEIRO"))))
                .andExpect(status().isCreated());
        return id;
    }

    // --- acerto ---

    /** Inicia o acerto pela rota da dona; devolve o id. */
    UUID iniciar(UsuarioDeTeste vendedor, UUID semana) throws Exception {
        UUID id = UUID.randomUUID();
        postar(ACERTOS_DA_DONA, dona(), mapa("id", id, "vendedorId", vendedor.vendedorId(), "semanaId", semana))
                .andExpect(status().isCreated());
        return id;
    }

    String statusDaMaleta(UUID maleta) {
        return jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta);
    }

    int saldoDaMaleta(UUID maleta, UUID peca) {
        return contar("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ?""", maleta, peca);
    }

    // --- SQL direto (testes do banco) ---

    UUID acertoNoBanco(UsuarioDeTeste vendedor, UUID semana) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                VALUES (?, ?, ?, 'EM_ACERTO', false, now(), ?)""", id, vendedor.vendedorId(), semana, usuarios.dona().id());
        return id;
    }

    void ligarNoBanco(UUID maleta, UUID acerto) {
        jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO', acerto_id = ?, status_antes_do_acerto = status WHERE id = ?",
                acerto, maleta);
    }

    void desistirNoBanco(UUID acerto) {
        jdbc.update("""
                UPDATE corona.acertos SET status = 'DESISTIDO', desistencia_id = ?, desistido_em = now(), desistido_por = ?,
                       motivo_desistencia = 'teste' WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), acerto);
    }

    void lerNoBanco(UUID acerto, UUID peca, int quantidade, String estado) {
        jdbc.update("""
                INSERT INTO corona.leituras_acerto (id, acerto_id, produto_id, quantidade, estado_peca, lida_em, lida_por, pelo_vendedor)
                VALUES (?, ?, ?, ?, ?, now(), ?, false)""", UUID.randomUUID(), acerto, peca, quantidade, estado, usuarios.dona().id());
    }
}
