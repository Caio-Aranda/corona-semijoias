package br.com.coronasemijoias.api.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
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

/**
 * Venda do vendedor e estorno (tarefa 4.2; ADR-0011): preço congelado, totais do servidor, desconto, saldo da maleta,
 * idempotência (201, 200 e 409), estorno, imutabilidade, isolamento entre vendedores e 403 cruzado. Cada teste cria os
 * próprios vendedor, cliente, semana e peças.
 */
class VendasDoVendedorTest extends TesteDeIntegracao {

    private static final String VENDAS = "/api/vendedor/vendas";
    private static final String REMESSAS = "/api/dona/remessas";
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

    // --- apoio ---

    private RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    /** Um token por vendedor e por teste: o token leva o aparelho, e a maleta fica ligada ao primeiro que vende. */
    private final Map<UUID, RequestPostProcessor> aparelhos = new java.util.concurrent.ConcurrentHashMap<>();

    private RequestPostProcessor como(UsuarioDeTeste vendedor) {
        return aparelhos.computeIfAbsent(vendedor.id(), id -> UsuariosDeTeste.comToken(usuarios.token(vendedor)));
    }

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    private ResultActions postar(String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo == null ? mapa() : corpo)));
    }

    private ResultActions ler(String caminho, RequestPostProcessor quem) throws Exception {
        return mvc.perform(get(caminho).with(quem));
    }

    private JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    private static BigDecimal valor(String texto) {
        return new BigDecimal(texto);
    }

    /** Vendedor com usuário próprio (o token leva o vendedor). */
    private UsuarioDeTeste novoVendedor() {
        return usuarios.criarVendedor();
    }

    private UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(2400, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 80_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private UUID peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "V-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return id;
    }

    /**
     * Maleta enviada ao vendedor com os itens (produto, quantidade) e já conferida por ele, com tudo certo: vender só
     * depois de conferir (decisão de 11/11/2026, tarefa 5.3).
     */
    private UUID maleta(UsuarioDeTeste vendedor, Object... pares) throws Exception {
        UUID id = maletaSemConferir(vendedor, pares);
        List<Map<String, Object>> contadas = new java.util.ArrayList<>();
        for (int i = 0; i < pares.length; i += 2) {
            contadas.add(mapa("produtoId", pares[i], "contada", pares[i + 1]));
        }
        postar("/api/vendedor/maletas/" + id + "/conferencia", como(vendedor), mapa("id", UUID.randomUUID(), "itens", contadas))
                .andExpect(status().isCreated());
        return id;
    }

    /** Maleta já enviada ao vendedor com os itens (produto, quantidade), ainda sem a conferência dele. */
    private UUID maletaSemConferir(UsuarioDeTeste vendedor, Object... pares) throws Exception {
        JsonNode r = corpoDe(postar(REMESSAS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(),
                "semanaId", semana(), "tipo", "NOVA")).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + pares[i]).with(dona())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", pares[i + 1], "version", r.get("version").asInt()))))
                    .andExpect(status().isOk()));
        }
        postar(REMESSAS + "/" + r.get("id").asString() + "/enviar", dona(), null).andExpect(status().isCreated());
        return UUID.fromString(r.get("id").asString());
    }

    private static Map<String, Object> linha(UUID produto, int quantidade, String desconto) {
        return desconto == null ? mapa("produtoId", produto, "quantidade", quantidade)
                : mapa("produtoId", produto, "quantidade", quantidade, "desconto", desconto);
    }

    private static Map<String, Object> pagamento(String valor, String forma) {
        return mapa("id", UUID.randomUUID(), "valor", valor, "forma", forma);
    }

    private static Map<String, Object> venda(UUID id, UUID maleta, UUID cliente, List<Map<String, Object>> itens,
            List<Map<String, Object>> pagamentos) {
        return mapa("id", id, "remessaId", maleta, "clienteId", cliente, "itens", itens, "pagamentos", pagamentos);
    }

    private int saldo(UUID maleta, UUID produto) {
        return jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                Integer.class, maleta, produto);
    }

    private int central(UUID produto) {
        return jdbc.queryForObject("SELECT estoque_atual FROM corona.produtos WHERE id = ?", Integer.class, produto);
    }

    private int movimentosDaVenda(UUID venda) {
        return jdbc.queryForObject("SELECT count(*) FROM corona.movimentos_remessa WHERE venda_id = ?", Integer.class, venda);
    }

    private void limiteDeDesconto(int percentual) {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'limite_desconto_percentual'", percentual);
    }

    // --- a venda ---

    @Test
    void vendeAVistaPeloPrecoCongeladoDaRemessaERecalculaOsTotais() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        // A dona muda o preço depois do envio: a venda continua no preço congelado.
        jdbc.update("UPDATE corona.produtos SET preco_venda = 99.00 WHERE id = ?", anel);
        UUID id = UUID.randomUUID();

        // O app até tenta mandar preço e total: o servidor ignora.
        Map<String, Object> pedido = venda(id, maleta, cliente, List.of(linha(anel, 2, null)), List.of(pagamento("100.00", "PIX")));
        pedido.put("totalConferido", "1.00");
        JsonNode r = corpoDe(postar(VENDAS, como(v), pedido).andExpect(status().isCreated()));

        assertThat(dec(r.get("subtotal"))).isEqualByComparingTo("100.00");
        assertThat(dec(r.get("desconto"))).isEqualByComparingTo("0");
        assertThat(dec(r.get("total"))).isEqualByComparingTo("100.00");
        assertThat(dec(r.get("aReceber"))).isEqualByComparingTo("0");
        assertThat(r.get("status").asString()).isEqualTo("CONCLUIDA");
        assertThat(dec(r.get("itens").get(0).get("precoUnitario"))).isEqualByComparingTo("50.00");
        assertThat(saldo(maleta, anel)).isEqualTo(3);
        assertThat(central(anel)).isEqualTo(5); // o estoque central já saiu no envio e não muda na venda
        assertThat(movimentosDaVenda(id)).isEqualTo(1);
        // Nada de custo em nenhuma parte da resposta.
        assertThat(r.toString().toLowerCase()).doesNotContain("custo");
    }

    @Test
    void vendaAPrazoGravaSoOQueFoiPagoEDeixaOResto() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("40.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        JsonNode r = corpoDe(postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 3, null)),
                List.of(pagamento("20.00", "DINHEIRO")))).andExpect(status().isCreated()));
        assertThat(dec(r.get("total"))).isEqualByComparingTo("120.00");
        assertThat(dec(r.get("pago"))).isEqualByComparingTo("20.00");
        assertThat(dec(r.get("aReceber"))).isEqualByComparingTo("100.00");

        JsonNode semPagar = corpoDe(postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente,
                List.of(linha(anel, 1, null)), List.of())).andExpect(status().isCreated()));
        assertThat(dec(semPagar.get("aReceber"))).isEqualByComparingTo("40.00");
    }

    @Test
    void reenviarAMesmaVendaDevolve200SemBaixarDeNovoEDadosDiferentesDao409() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("30.00", 10);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        UUID id = UUID.randomUUID();
        Map<String, Object> pedido = venda(id, maleta, cliente, List.of(linha(anel, 2, null)), List.of(pagamento("60.00", "PIX")));

        postar(VENDAS, como(v), pedido).andExpect(status().isCreated());
        postar(VENDAS, como(v), pedido).andExpect(status().isOk());
        assertThat(saldo(maleta, anel)).isEqualTo(8);
        assertThat(movimentosDaVenda(id)).isEqualTo(1);

        Map<String, Object> outra = venda(id, maleta, cliente, List.of(linha(anel, 3, null)), List.of(pagamento("90.00", "PIX")));
        postar(VENDAS, como(v), outra).andExpect(status().isConflict());
        assertThat(saldo(maleta, anel)).isEqualTo(8);
    }

    @Test
    void vendedorComCadastroInativoNaoVendeNemConfereMesmoComUsuarioAtivo() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("30.00", 10);
        UUID maleta = maletaSemConferir(v, anel, 10);
        UUID cliente = cliente(v);
        // Só o cadastro fica inativo (o usuário continua ativo): o servidor recusa mesmo assim.
        jdbc.update("UPDATE corona.vendedores SET ativo = false WHERE id = ?", v.vendedorId());
        UUID id = UUID.randomUUID();
        JsonNode erro = corpoDe(postar(VENDAS, como(v), venda(id, maleta, cliente, List.of(linha(anel, 1, null)), List.of()))
                .andExpect(status().isConflict()));
        assertThat(erro.get("detail").asString()).isEqualTo("Este vendedor está desativado. Fale com a dona.");
        assertThat(saldo(maleta, anel)).isEqualTo(10);
        postar("/api/vendedor/maletas/" + maleta + "/conferencia", como(v),
                mapa("id", UUID.randomUUID(), "itens", List.of(mapa("produtoId", anel, "contada", 10))))
                .andExpect(status().isConflict());
        // Reativado o cadastro, confere e volta a vender.
        jdbc.update("UPDATE corona.vendedores SET ativo = true WHERE id = ?", v.vendedorId());
        postar("/api/vendedor/maletas/" + maleta + "/conferencia", como(v),
                mapa("id", UUID.randomUUID(), "itens", List.of(mapa("produtoId", anel, "contada", 10))))
                .andExpect(status().isCreated());
        postar(VENDAS, como(v), venda(id, maleta, cliente, List.of(linha(anel, 1, null)), List.of())).andExpect(status().isCreated());
        assertThat(saldo(maleta, anel)).isEqualTo(9);
    }

    @Test
    void inativarOCadastroDoVendedorDesativaOUsuarioEEncerraAsSessoesComAuditoria() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        ler(VENDAS, como(v)).andExpect(status().isOk());
        postar("/api/dona/vendedores/" + v.vendedorId() + "/inativar", dona(), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT ativo FROM corona.usuarios WHERE id = ?", Boolean.class, v.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.sessoes WHERE usuario_id = ? AND revogada_em IS NULL",
                Integer.class, v.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.auditoria WHERE registro_id = ? AND acao = 'USUARIO_DESATIVADO'",
                Integer.class, v.id())).isEqualTo(1);
        // No Corona Vendas, o vendedor desativado recebe a ordem de apagar os dados (tarefa 5.3).
        ler(VENDAS, como(v)).andExpect(status().isForbidden());
    }

    @Test
    void pagamentoComIdJaUsadoEmOutraVendaDa409ESemGravarNada() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("30.00", 10);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        Map<String, Object> pagamento = pagamento("30.00", "PIX");
        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, null)), List.of(pagamento)))
                .andExpect(status().isCreated());
        UUID outra = UUID.randomUUID();
        JsonNode erro = corpoDe(postar(VENDAS, como(v), venda(outra, maleta, cliente, List.of(linha(anel, 1, null)),
                List.of(pagamento))).andExpect(status().isConflict()));
        assertThat(erro.get("detail").asString()).contains("já foi registrada");
        assertThat(saldo(maleta, anel)).isEqualTo(9);
        assertThat(movimentosDaVenda(outra)).isZero();
    }

    @Test
    void recusaAPecaQueNaoEstaNaMaletaESaldoQueNaoBasta() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("30.00", 3);
        UUID fora = peca("30.00", 3);
        UUID maleta = maleta(v, anel, 2);
        UUID cliente = cliente(v);

        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(fora, 1, null)), List.of()))
                .andExpect(status().isBadRequest());
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), venda(id, maleta, cliente, List.of(linha(anel, 3, null)), List.of())).andExpect(status().isConflict());
        assertThat(saldo(maleta, anel)).isEqualTo(2);
        assertThat(movimentosDaVenda(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.vendas WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    void vendeParaClienteCadastradoPorOutroVendedorMasNaoComMaletaDeOutro() throws Exception {
        UsuarioDeTeste a = novoVendedor();
        UsuarioDeTeste b = novoVendedor();
        UUID anel = peca("30.00", 5);
        UUID maletaDeA = maleta(a, anel, 5);
        UUID clienteDeB = cliente(b);

        // O cliente é da loja (11/11/2026): A vende para o cliente que B cadastrou.
        postar(VENDAS, como(a), venda(UUID.randomUUID(), maletaDeA, clienteDeB, List.of(linha(anel, 1, null)), List.of()))
                .andExpect(status().isCreated());
        // Cliente que não existe continua recusado.
        postar(VENDAS, como(a), venda(UUID.randomUUID(), maletaDeA, UUID.randomUUID(), List.of(linha(anel, 1, null)), List.of()))
                .andExpect(status().isBadRequest());
        // B não vende da maleta de A: 404, sem dizer de quem é.
        postar(VENDAS, como(b), venda(UUID.randomUUID(), maletaDeA, clienteDeB, List.of(linha(anel, 1, null)), List.of()))
                .andExpect(status().isNotFound());
        assertThat(saldo(maletaDeA, anel)).isEqualTo(4);
    }

    @Test
    void vendedorNaoVeVendasNemFiadoDeOutroVendedorMesmoDoMesmoCliente() throws Exception {
        UsuarioDeTeste a = novoVendedor();
        UsuarioDeTeste b = novoVendedor();
        UUID anel = peca("30.00", 10);
        UUID maletaDeA = maleta(a, anel, 5);
        UUID maletaDeB = maleta(b, anel, 5);
        UUID cliente = cliente(a); // da loja: os dois vendem para ele
        UUID vendaDeA = UUID.randomUUID();
        UUID vendaDeB = UUID.randomUUID();
        postar(VENDAS, como(a), venda(vendaDeA, maletaDeA, cliente, List.of(linha(anel, 1, null)), List.of(pagamento("10.00", "PIX"))))
                .andExpect(status().isCreated());
        postar(VENDAS, como(b), venda(vendaDeB, maletaDeB, cliente, List.of(linha(anel, 2, null)), List.of()))
                .andExpect(status().isCreated());
        // A só vê a venda dele (e B só a dele); a venda do outro é 404.
        JsonNode deA = corpoDe(ler(VENDAS, como(a)).andExpect(status().isOk()));
        assertThat(deA).hasSize(1);
        assertThat(deA.get(0).get("id").asString()).isEqualTo(vendaDeA.toString());
        ler(VENDAS + "/" + vendaDeB, como(a)).andExpect(status().isNotFound());
        // O fiado (a receber) de A é só o dele: R$ 20,00 (30 - 10 pagos); o de B (60,00) não aparece para A.
        JsonNode aReceber = corpoDe(ler("/api/vendedor/relatorios/a-receber", como(a)).andExpect(status().isOk()));
        assertThat(dec(aReceber.get("totais").get("total"))).isEqualByComparingTo("20.00");
        assertThat(aReceber.get("clientes")).hasSize(1);
        JsonNode deBReceber = corpoDe(ler("/api/vendedor/relatorios/a-receber", como(b)).andExpect(status().isOk()));
        assertThat(dec(deBReceber.get("totais").get("total"))).isEqualByComparingTo("60.00");
    }

    @Test
    void naoVendeDeMaletaEmRascunhoNemCancelada() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("30.00", 5);
        UUID cliente = cliente(v);
        JsonNode rascunho = corpoDe(postar(REMESSAS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", v.vendedorId(),
                "semanaId", semana(), "tipo", "NOVA")).andExpect(status().isCreated()));
        postar(VENDAS, como(v), venda(UUID.randomUUID(), UUID.fromString(rascunho.get("id").asString()), cliente,
                List.of(linha(anel, 1, null)), List.of())).andExpect(status().isConflict());

        UUID enviada = maletaSemConferir(v, anel, 2);
        postar(REMESSAS + "/" + enviada + "/cancelar", dona(), mapa("motivo", "teste")).andExpect(status().isOk());
        postar(VENDAS, como(v), venda(UUID.randomUUID(), enviada, cliente, List.of(linha(anel, 1, null)), List.of()))
                .andExpect(status().isConflict());
    }

    // --- desconto e pagamento ---

    @Test
    void descontoRespeitaOLimiteDaDonaValidadoNoServidor() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        try {
            limiteDeDesconto(0);
            postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, "1.00")), List.of()))
                    .andExpect(status().isConflict());

            limiteDeDesconto(10);
            postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, "10.01")), List.of()))
                    .andExpect(status().isConflict());
            JsonNode r = corpoDe(postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente,
                    List.of(linha(anel, 2, "20.00")), List.of(pagamento("180.00", "DINHEIRO")))).andExpect(status().isCreated()));
            assertThat(dec(r.get("subtotal"))).isEqualByComparingTo("200.00");
            assertThat(dec(r.get("desconto"))).isEqualByComparingTo("20.00");
            assertThat(dec(r.get("total"))).isEqualByComparingTo("180.00");

            postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, "-1.00")), List.of()))
                    .andExpect(status().isBadRequest());
            postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, "1.005")), List.of()))
                    .andExpect(status().isBadRequest());
        } finally {
            limiteDeDesconto(0);
        }
    }

    @Test
    void recusaPagamentoMaiorQueOTotalEFormaDesconhecida() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("25.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, null)),
                List.of(pagamento("25.01", "PIX")))).andExpect(status().isBadRequest());
        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, null)),
                List.of(pagamento("25.00", "FIADO")))).andExpect(status().isBadRequest());
        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, null)),
                List.of(pagamento("0.00", "PIX")))).andExpect(status().isBadRequest());
        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(), List.of())).andExpect(status().isBadRequest());
        assertThat(saldo(maleta, anel)).isEqualTo(5);
    }

    // --- estorno ---

    @Test
    void estornaAVendaInteiraDevolveAPecaEReembolsaOPago() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), venda(id, maleta, cliente, List.of(linha(anel, 2, null)),
                List.of(pagamento("60.00", "DINHEIRO"), pagamento("40.00", "PIX")))).andExpect(status().isCreated());
        assertThat(saldo(maleta, anel)).isEqualTo(3);

        UUID estorno = UUID.randomUUID();
        JsonNode r = corpoDe(postar(VENDAS + "/" + id + "/estorno", como(v), mapa("id", estorno, "motivo", "Cliente desistiu"))
                .andExpect(status().isCreated()));
        assertThat(r.get("status").asString()).isEqualTo("ESTORNADA");
        assertThat(saldo(maleta, anel)).isEqualTo(5);
        assertThat(dec(r.get("pago"))).isEqualByComparingTo("0"); // pagamentos menos reembolsos
        assertThat(dec(r.get("aReceber"))).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO' AND valor < 0",
                Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ?", Integer.class, id))
                .isEqualTo(4);
        assertThat(central(anel)).isEqualTo(0);

        // Mesmo estorno de novo: 200, sem mexer; outro estorno da mesma venda: 409.
        postar(VENDAS + "/" + id + "/estorno", como(v), mapa("id", estorno, "motivo", "Cliente desistiu")).andExpect(status().isOk());
        postar(VENDAS + "/" + id + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "de novo"))
                .andExpect(status().isConflict());
        assertThat(saldo(maleta, anel)).isEqualTo(5);

        // Venda estornada nunca é editada: a peça volta a poder ser vendida numa venda nova.
        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 5, null)), List.of()))
                .andExpect(status().isCreated());
    }

    @Test
    void estornoExigeMotivo() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), venda(id, maleta, cliente(v), List.of(linha(anel, 1, null)), List.of())).andExpect(status().isCreated());
        postar(VENDAS + "/" + id + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "  "))
                .andExpect(status().isBadRequest());
        assertThat(saldo(maleta, anel)).isEqualTo(4);
    }

    @Test
    void vendedorSoEstornaNoDiaDaVendaEADonaEstornaSempre() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), venda(id, maleta, cliente, List.of(linha(anel, 1, "0.00")), List.of())).andExpect(status().isCreated());
        // Uma venda de ontem, gravada direto (o gatilho só impede alterar, não inserir).
        UUID antiga = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.vendas (id, vendedor_id, cliente_id, remessa_id, subtotal, desconto, total, vendida_em)
                VALUES (?, ?, ?, ?, 50.00, 0, 50.00, now() - interval '2 days')""", antiga, v.vendedorId(), cliente, maleta);
        jdbc.update("""
                INSERT INTO corona.itens_venda (id, venda_id, produto_id, quantidade, preco_unitario, desconto)
                VALUES (uuidv7(), ?, ?, 1, 50.00, 0)""", antiga, anel);
        jdbc.update("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id)
                VALUES (uuidv7(), ?, ?, 'VENDA', -1, ?)""", maleta, anel, antiga);

        postar(VENDAS + "/" + antiga + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isConflict());
        assertThat(saldo(maleta, anel)).isEqualTo(3);
        postar("/api/dona/vendas/" + antiga + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        assertThat(saldo(maleta, anel)).isEqualTo(4);
    }

    // --- conciliação ---

    @Test
    void aConciliacaoEstoqueMaisMaletasContinuaFechandoComVendasEEstornos() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 6);
        UUID cliente = cliente(v);
        UUID vendaA = UUID.randomUUID();
        postar(VENDAS, como(v), venda(vendaA, maleta, cliente, List.of(linha(anel, 2, null)), List.of())).andExpect(status().isCreated());
        postar(VENDAS, como(v), venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, null)), List.of()))
                .andExpect(status().isCreated());
        postar(VENDAS + "/" + vendaA + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano")).andExpect(status().isCreated());

        // Central 4 + na maleta 5 (6 - 1 vendida) = 9 = 10 que entraram - 1 vendida.
        // Só esta peça: outros testes podem deixar diferença de propósito no mesmo banco.
        assertThat(estoque.conciliar().divergencias()).noneMatch(d -> d.produtoId().equals(anel));
        assertThat(estoque.conciliar().diferencasComMaletas()).noneMatch(d -> d.produtoId().equals(anel));
        JsonNode relatorio = corpoDe(ler("/api/dona/relatorios/estoque/conciliacao?produtoId=" + anel, dona()).andExpect(status().isOk()));
        assertThat(relatorio.get("total").asLong()).isZero();
    }

    // --- imutabilidade ---

    @Test
    void vendaItensPagamentosEMovimentosNaoSaoAlteradosNemApagados() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), venda(id, maleta, cliente(v), List.of(linha(anel, 1, null)), List.of(pagamento("50.00", "PIX"))))
                .andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbc.update("UPDATE corona.vendas SET total = 1.00, subtotal = 1.00 WHERE id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.itens_venda SET quantidade = 9 WHERE venda_id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.pagamentos_cliente SET valor = 1.00 WHERE venda_id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.itens_venda WHERE venda_id = ?", id)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.vendas WHERE id = ?", id)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.movimentos_remessa SET quantidade = -9 WHERE venda_id = ?", id))
                .isInstanceOf(Exception.class);
    }

    // --- permissões e isolamento ---

    @Test
    void vendedorSoEnxergaAsProprias_eOPerfilErradoDa403() throws Exception {
        UsuarioDeTeste a = novoVendedor();
        UsuarioDeTeste b = novoVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(a, anel, 5);
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(a), venda(id, maleta, cliente(a), List.of(linha(anel, 1, null)), List.of())).andExpect(status().isCreated());

        // B não vê a venda de A (404), não a lista e não a estorna.
        ler(VENDAS + "/" + id, como(b)).andExpect(status().isNotFound());
        assertThat(corpoDe(ler(VENDAS, como(b)).andExpect(status().isOk())).size()).isZero();
        postar(VENDAS + "/" + id + "/estorno", como(b), mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isNotFound());
        assertThat(corpoDe(ler(VENDAS, como(a)).andExpect(status().isOk())).size()).isEqualTo(1);

        // A dona não usa a rota do vendedor e o vendedor não usa a da dona.
        ler(VENDAS, dona()).andExpect(status().isForbidden());
        postar(VENDAS, dona(), venda(UUID.randomUUID(), maleta, UUID.randomUUID(), List.of(linha(anel, 1, null)), List.of()))
                .andExpect(status().isForbidden());
        ler("/api/dona/vendas/" + id, como(a)).andExpect(status().isForbidden());
        postar("/api/dona/vendas/" + id + "/estorno", como(a), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isForbidden());
        ler(VENDAS, UsuariosDeTeste.comToken("invalido")).andExpect(status().isUnauthorized());
        ler("/api/dona/vendas/" + id, dona()).andExpect(status().isOk());
    }

    @Test
    void listaComFiltrosPorClienteESituacao() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID c1 = cliente(v);
        UUID c2 = cliente(v);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        postar(VENDAS, como(v), venda(a, maleta, c1, List.of(linha(anel, 1, null)), List.of())).andExpect(status().isCreated());
        postar(VENDAS, como(v), venda(b, maleta, c2, List.of(linha(anel, 1, null)), List.of())).andExpect(status().isCreated());
        postar(VENDAS + "/" + b + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isCreated());

        assertThat(corpoDe(ler(VENDAS + "?clienteId=" + c1, como(v))).size()).isEqualTo(1);
        assertThat(corpoDe(ler(VENDAS + "?status=ESTORNADA", como(v))).get(0).get("id").asString()).isEqualTo(b.toString());
        assertThat(corpoDe(ler(VENDAS + "?status=CONCLUIDA", como(v))).size()).isEqualTo(1);
        ler(VENDAS + "?status=QUALQUER", como(v)).andExpect(status().isBadRequest());
        assertThat(corpoDe(ler(VENDAS + "?de=2999-01-01", como(v))).size()).isZero();
    }

    // --- concorrência ---

    @Test
    void duasVendasAoMesmoTempoDaUltimaPecaGravamSoUma() throws Exception {
        UsuarioDeTeste v = novoVendedor();
        UUID anel = peca("50.00", 1);
        UUID maleta = maleta(v, anel, 1);
        UUID cliente = cliente(v);
        int tentativas = 6;
        ExecutorService pool = Executors.newFixedThreadPool(tentativas);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        RequestPostProcessor quem = como(v);
        for (int i = 0; i < tentativas; i++) {
            Callable<Integer> tarefa = () -> {
                largada.await();
                return postar(VENDAS, quem, venda(UUID.randomUUID(), maleta, cliente, List.of(linha(anel, 1, null)), List.of()))
                        .andReturn().getResponse().getStatus();
            };
            resultados.add(pool.submit(tarefa));
        }
        largada.countDown();
        int criadas = 0;
        int recusadas = 0;
        for (Future<Integer> f : resultados) {
            int s = f.get(30, TimeUnit.SECONDS);
            if (s == 201) {
                criadas++;
            } else if (s == 409) {
                recusadas++;
            }
        }
        pool.shutdown();
        assertThat(criadas).isEqualTo(1);
        assertThat(recusadas).isEqualTo(tentativas - 1);
        assertThat(saldo(maleta, anel)).isZero();
    }
}
