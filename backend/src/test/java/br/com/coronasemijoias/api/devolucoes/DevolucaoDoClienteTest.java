package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import br.com.coronasemijoias.api.sync.FiltroPorVendedor;
import br.com.coronasemijoias.api.sync.LinhaDoPull;
import br.com.coronasemijoias.api.sync.LoteDoPull;
import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import br.com.coronasemijoias.api.sync.TabelaSincronizavel;
import br.com.coronasemijoias.api.testes.AcertosDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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
 * Devolução do cliente de venda à vista (tarefa 6.3; ADR-0014, itens 1 a 7 e 11; plano aprovado no PR #125): total e
 * parcial, centavos, quantidade acima do vendido (com a segunda trava no banco), concorrência, prazo em dias de Brasília,
 * destino da peça, cancelar, estorno recusado, idempotência, venda a prazo, permissões, nada de custo, conciliação,
 * imutabilidade, busca pela peça lida, pedido malformado e o registro de tabelas. Escritos antes do código; cada teste
 * cria os próprios vendedor, cliente, semana e peças.
 */
class DevolucaoDoClienteTest extends TesteDeIntegracao {

    private static final String DEVOLUCOES = "/api/vendedor/devolucoes";
    private static final String DEVOLUCOES_DA_DONA = "/api/dona/devolucoes";
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

    @Autowired
    ServicoDeVendas vendas;

    @Autowired
    ServicoDeDevolucoes devolucoes;

    @Autowired
    ServicoDePull pull;

    /** Os testes dividem o banco: cada um devolve a garantia e o desconto aos padrões. */
    @AfterEach
    void voltarAosPadroes() {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 7 WHERE chave = 'prazo_arrependimento_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 30 WHERE chave = 'prazo_defeito_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'limite_desconto_percentual'");
    }

    // --- apoio ---

    private RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    /** Um token por vendedor e por teste: o token leva o computador, e a maleta fica ligada a ele na conferência. */
    private final Map<UUID, RequestPostProcessor> tokens = new ConcurrentHashMap<>();

    private RequestPostProcessor como(UsuarioDeTeste vendedor) {
        return tokens.computeIfAbsent(vendedor.id(), id -> UsuariosDeTeste.comToken(usuarios.token(vendedor)));
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

    private String codigoDe(ResultActions r) throws Exception {
        return corpoDe(r).get("codigo").asString();
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    private UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(2900, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 60_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated()))
                .get("id").asString());
    }

    /** Peça com estoque na loja; o código da loja é "DV-" mais o começo do id, para a busca pela peça lida. */
    private UUID peca(String preco, int saldo) throws Exception {
        return pecaComCodigo(preco, saldo, "DV-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private UUID pecaComCodigo(String preco, int saldo, String codigo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Brinco " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", codigo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return id;
    }

    /** Maleta enviada ao vendedor com os itens (produto, quantidade) e já conferida por ele (liga a maleta ao computador). */
    private UUID maleta(UsuarioDeTeste vendedor, Object... pares) throws Exception {
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

    private static Map<String, Object> linha(UUID produto, int quantidade) {
        return mapa("produtoId", produto, "quantidade", quantidade);
    }

    private static Map<String, Object> linhaComDesconto(UUID produto, int quantidade, String desconto) {
        return mapa("produtoId", produto, "quantidade", quantidade, "desconto", desconto);
    }

    private static Map<String, Object> pagamento(String valor, String forma) {
        return mapa("id", UUID.randomUUID(), "valor", valor, "forma", forma);
    }

    /** Venda pela rota do vendedor; devolve o id. */
    private UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, List<Map<String, Object>> itens,
            List<Map<String, Object>> pagamentos) throws Exception {
        UUID id = UUID.randomUUID();
        postar(VENDAS, como(v), mapa("id", id, "remessaId", maleta, "clienteId", cliente, "itens", itens,
                "pagamentos", pagamentos)).andExpect(status().isCreated());
        return id;
    }

    private static Map<String, Object> devolvida(UUID produto, int quantidade, String estado) {
        return mapa("produtoId", produto, "quantidade", quantidade, "estado", estado);
    }

    private static Map<String, Object> devolucao(UUID id, UUID venda, String motivo, String forma, List<Map<String, Object>> itens) {
        return mapa("id", id, "vendaId", venda, "motivo", motivo, "formaReembolso", forma, "itens", itens);
    }

    /** Devolução pela rota do vendedor, esperando 201; devolve o corpo. */
    private JsonNode devolver(UsuarioDeTeste v, UUID venda, String motivo, String forma, List<Map<String, Object>> itens)
            throws Exception {
        return corpoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, motivo, forma, itens))
                .andExpect(status().isCreated()));
    }

    private int saldo(UUID maleta, UUID produto) {
        return jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                Integer.class, maleta, produto);
    }

    private int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private BigDecimal somaDosPagamentos(UUID venda) {
        return jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.pagamentos_cliente WHERE venda_id = ?",
                BigDecimal.class, venda);
    }

    private void limiteDeDesconto(int percentual) {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'limite_desconto_percentual'", percentual);
    }

    /** Venda gravada pelo serviço com a hora escolhida (a rota online usa a hora do banco), à vista. */
    private UUID venderEm(UsuarioDeTeste v, UUID maleta, UUID cliente, UUID produto, int quantidade, String total, Instant quando) {
        UUID id = UUID.randomUUID();
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(id, maleta, cliente,
                List.of(new LinhaDoPedido(produto, quantidade, null)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal(total), "DINHEIRO")), null), quando, false);
        return id;
    }

    private ServicoDeDevolucoes.Resultado devolverPeloServico(UsuarioDeTeste v, UUID aparelho, UUID venda, String motivo,
            List<PecaDoPedido> pecas, Instant quando, ModoDaDevolucao modo) {
        return devolucoes.devolver(v.vendedorId(), v.id(), aparelho, new PedidoDaDevolucaoDoCliente(UUID.randomUUID(), venda, motivo,
                null, "DINHEIRO", pecas, null), quando, false, modo);
    }

    private static String codigoDoErro(Throwable t) {
        assertThat(t).isInstanceOf(ErroDaApi.class);
        return ((ErroDaApi) t).codigo().name();
    }

    // --- 1. total e parcial ---

    @Test
    void devolucaoParcialEDepoisDoRestoReembolsaENaoMudaAVenda() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID brinco = peca("30.00", 10);
        UUID maleta = maleta(v, anel, 5, brinco, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 2), linha(brinco, 1)), List.of(pagamento("130.00", "PIX")));

        JsonNode parcial = devolver(v, venda, "ARREPENDIMENTO", "DINHEIRO", List.of(devolvida(anel, 1, "BOA")));
        assertThat(dec(parcial.get("valorDevolvido"))).isEqualByComparingTo("50.00");
        assertThat(dec(parcial.get("valorReembolsado"))).isEqualByComparingTo("50.00");
        assertThat(parcial.get("situacao").asString()).isEqualTo("ACEITA");
        assertThat(parcial.get("destino").asString()).isEqualTo("REEMBOLSO");
        assertThat(parcial.get("dentroGarantia").asBoolean()).isTrue();
        assertThat(parcial.get("formaReembolso").asString()).isEqualTo("DINHEIRO");
        assertThat(parcial.get("itens")).hasSize(1);
        assertThat(parcial.get("itens").get(0).get("destino").asString()).isEqualTo("MALETA");
        assertThat(parcial.get("itens").get(0).get("produto").get("nome").asString()).startsWith("Brinco ");
        assertThat(saldo(maleta, anel)).isEqualTo(4);

        // A venda continua concluída e igual; o reembolso é um registro negativo ligado à devolução.
        JsonNode lida = corpoDe(ler(VENDAS + "/" + venda, como(v)).andExpect(status().isOk()));
        assertThat(lida.get("status").asString()).isEqualTo("CONCLUIDA");
        assertThat(dec(lida.get("total"))).isEqualByComparingTo("130.00");
        assertThat(lida.get("itens")).hasSize(2);
        assertThat(dec(lida.get("devolvido"))).isEqualByComparingTo("50.00");
        assertThat(dec(lida.get("aReceber"))).isEqualByComparingTo("0");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO' AND valor = 130.00",
                venda)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente
                 WHERE venda_id = ? AND tipo = 'REEMBOLSO' AND valor = -50.00 AND forma = 'DINHEIRO' AND devolucao_id = ?""",
                venda, UUID.fromString(parcial.get("id").asString()))).isEqualTo(1);

        JsonNode resto = devolver(v, venda, "TAMANHO", "PIX", List.of(devolvida(anel, 1, "BOA"), devolvida(brinco, 1, "BOA")));
        assertThat(dec(resto.get("valorDevolvido"))).isEqualByComparingTo("80.00");
        assertThat(saldo(maleta, anel)).isEqualTo(5);
        assertThat(saldo(maleta, brinco)).isEqualTo(5);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
        lida = corpoDe(ler(VENDAS + "/" + venda, como(v)).andExpect(status().isOk()));
        assertThat(lida.get("status").asString()).isEqualTo("CONCLUIDA");
        assertThat(dec(lida.get("devolvido"))).isEqualByComparingTo("130.00");
        assertThat(dec(lida.get("aReceber"))).isEqualByComparingTo("0");
        // No relatório "a receber" do vendedor, a venda devolvida não vira dívida do cliente.
        JsonNode aReceber = corpoDe(ler("/api/vendedor/relatorios/a-receber", como(v)).andExpect(status().isOk()));
        assertThat(dec(aReceber.get("totais").get("total"))).isEqualByComparingTo("0");
        assertThat(aReceber.get("clientes")).isEmpty();
    }

    // --- 2. centavos ---

    @Test
    void tresDevolucoesDeUmaPecaFechamOsCentavosDaLinhaComDesconto() throws Exception {
        limiteDeDesconto(50);
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("4.00", 3);
        UUID maleta = maleta(v, anel, 3);
        UUID venda = vender(v, maleta, cliente(v), List.of(linhaComDesconto(anel, 3, "2.00")), List.of(pagamento("10.00", "PIX")));

        assertThat(dec(devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"))).get("valorDevolvido")))
                .isEqualByComparingTo("3.33");
        assertThat(dec(devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"))).get("valorDevolvido")))
                .isEqualByComparingTo("3.33");
        assertThat(dec(devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"))).get("valorDevolvido")))
                .isEqualByComparingTo("3.34");
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
    }

    @Test
    void comCancelamentoNoMeioASomaFechaNoTotalDaLinhaQueNaoDivideExato() throws Exception {
        limiteDeDesconto(50);
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("4.00", 3);
        UUID maleta = maleta(v, anel, 3);
        // 4,00 x 3 - 1,99 = 10,01: não divide exato por 3 (3,34 + 3,34 + 3,33).
        UUID venda = vender(v, maleta, cliente(v), List.of(linhaComDesconto(anel, 3, "1.99")), List.of(pagamento("10.01", "PIX")));

        JsonNode primeira = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        JsonNode segunda = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        assertThat(dec(primeira.get("valorDevolvido"))).isEqualByComparingTo("3.34");
        assertThat(dec(segunda.get("valorDevolvido"))).isEqualByComparingTo("3.34");
        postar(DEVOLUCOES + "/" + primeira.get("id").asString() + "/cancelar", como(v),
                mapa("id", UUID.randomUUID(), "motivo", "lançada por engano")).andExpect(status().isCreated());
        // Depois do cancelamento, uma parcial e a última: a soma das ativas fecha em 10,01.
        JsonNode terceira = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        JsonNode quarta = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        assertThat(dec(segunda.get("valorDevolvido")).add(dec(terceira.get("valorDevolvido"))).add(dec(quarta.get("valorDevolvido"))))
                .isEqualByComparingTo("10.01");
        assertThat(jdbc.queryForObject("""
                SELECT sum(i.valor) FROM corona.itens_devolucao i JOIN corona.devolucoes d ON d.id = i.devolucao_id
                 WHERE d.venda_id = ? AND d.situacao <> 'CANCELADA'""", BigDecimal.class, venda)).isEqualByComparingTo("10.01");
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
        // Nada mais pode voltar desta linha.
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_ACIMA_DO_VENDIDO");
    }

    @Test
    void linhaComCentavosDemaisParaAsPecasNaoDaErro() throws Exception {
        limiteDeDesconto(50);
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("0.02", 12);
        UUID maleta = maleta(v, anel, 12);
        // 0,02 x 12 - 0,06 = 0,18: dividir em 12 partes deixaria a última negativa.
        UUID venda = vender(v, maleta, cliente(v), List.of(linhaComDesconto(anel, 12, "0.06")), List.of(pagamento("0.18", "DINHEIRO")));
        JsonNode cinco = devolver(v, venda, "ARREPENDIMENTO", "DINHEIRO", List.of(devolvida(anel, 5, "BOA")));
        JsonNode sete = devolver(v, venda, "ARREPENDIMENTO", "DINHEIRO", List.of(devolvida(anel, 7, "BOA")));
        assertThat(dec(cinco.get("valorDevolvido"))).isEqualByComparingTo("0.08");
        assertThat(dec(sete.get("valorDevolvido"))).isEqualByComparingTo("0.10");
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
    }

    // --- 3. quantidade acima da vendida ---

    @Test
    void quantidadeAcimaDaVendidaDa409SemGravarNadaEOBancoTambemRecusa() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("20.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 2)), List.of(pagamento("40.00", "PIX")));

        ResultActions demais = postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 3, "BOA")))).andExpect(status().isConflict());
        assertThat(codigoDe(demais)).isEqualTo("DEVOLUCAO_ACIMA_DO_VENDIDO");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isZero();
        assertThat(saldo(maleta, anel)).isEqualTo(3);

        devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        JsonNode erro = corpoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 2, "BOA")))).andExpect(status().isConflict()));
        assertThat(erro.get("codigo").asString()).isEqualTo("DEVOLUCAO_ACIMA_DO_VENDIDO");
        assertThat(erro.get("detail").asString()).contains("ainda podem voltar só 1");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isEqualTo(1);
        assertThat(saldo(maleta, anel)).isEqualTo(4);

        // Segunda trava: gravado direto no banco, acima do vendido (ou acima do valor da linha), é recusado.
        UUID item = jdbc.queryForObject("SELECT id FROM corona.itens_venda WHERE venda_id = ?", UUID.class, venda);
        assertThatThrownBy(() -> gravarDireto(v, venda, maleta, cliente, item, 2, "1.00")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> gravarDireto(v, venda, maleta, cliente, item, 1, "20.01")).isInstanceOf(SQLException.class);
    }

    /** Uma devolução e um item gravados direto pelo usuário da aplicação, numa transação desfeita no fim. */
    private void gravarDireto(UsuarioDeTeste v, UUID venda, UUID maleta, UUID cliente, UUID itemDaVenda, int quantidade,
            String valor) throws SQLException {
        try (Connection c = conectarComoAplicacao()) {
            try {
                UUID dev = UUID.randomUUID();
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO corona.devolucoes (id, venda_id, cliente_id, vendedor_id, remessa_id, motivo, destino,
                            dentro_garantia, situacao, valor_devolvido, valor_reembolsado, forma_reembolso, devolvida_em,
                            registrada_por)
                        VALUES (?, ?, ?, ?, ?, 'ARREPENDIMENTO', 'REEMBOLSO', true, 'ACEITA', ?, ?, 'PIX', now(), ?)""")) {
                    s.setObject(1, dev);
                    s.setObject(2, venda);
                    s.setObject(3, cliente);
                    s.setObject(4, v.vendedorId());
                    s.setObject(5, maleta);
                    s.setBigDecimal(6, new BigDecimal(valor));
                    s.setBigDecimal(7, new BigDecimal(valor));
                    s.setObject(8, v.id());
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO corona.itens_devolucao (id, devolucao_id, item_venda_id, quantidade, valor, estado_peca, destino_peca)
                        VALUES (?, ?, ?, ?, ?, 'DEFEITO', 'LOJA')""")) {
                    s.setObject(1, UUID.randomUUID());
                    s.setObject(2, dev);
                    s.setObject(3, itemDaVenda);
                    s.setInt(4, quantidade);
                    s.setBigDecimal(5, new BigDecimal(valor));
                    s.executeUpdate();
                }
            } finally {
                c.rollback();
            }
        }
    }

    // --- 4. concorrência ---

    @Test
    void variasDevolucoesDaMesmaLinhaAoMesmoTempoGravamSoUma() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("25.00", 5);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)), List.of(pagamento("50.00", "PIX")));
        int tentativas = 6;
        ExecutorService pool = Executors.newFixedThreadPool(tentativas);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        RequestPostProcessor quem = como(v);
        for (int i = 0; i < tentativas; i++) {
            Callable<Integer> tarefa = () -> {
                largada.await();
                return postar(DEVOLUCOES, quem, devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                        List.of(devolvida(anel, 2, "BOA")))).andReturn().getResponse().getStatus();
            };
            resultados.add(pool.submit(tarefa));
        }
        largada.countDown();
        int criadas = 0;
        int recusadas = 0;
        for (Future<Integer> f : resultados) {
            int s = f.get(60, TimeUnit.SECONDS);
            if (s == 201) {
                criadas++;
            } else if (s == 409) {
                recusadas++;
            }
        }
        pool.shutdown();
        assertThat(criadas).isEqualTo(1);
        assertThat(recusadas).isEqualTo(tentativas - 1);
        assertThat(saldo(maleta, anel)).isEqualTo(5);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
    }

    @Test
    void devolucaoEEstornoDaMesmaVendaAoMesmoTempoSoUmPassaSemTravar() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("25.00", 20);
        UUID maleta = maleta(v, anel, 20);
        UUID cliente = cliente(v);
        RequestPostProcessor quem = como(v);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int rodada = 0; rodada < 4; rodada++) {
                UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("25.00", "PIX")));
                CountDownLatch largada = new CountDownLatch(1);
                Future<Integer> devolucao = pool.submit(() -> {
                    largada.await();
                    return postar(DEVOLUCOES, quem, devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                            List.of(devolvida(anel, 1, "BOA")))).andReturn().getResponse().getStatus();
                });
                Future<Integer> estorno = pool.submit(() -> {
                    largada.await();
                    return postar(VENDAS + "/" + venda + "/estorno", quem, mapa("id", UUID.randomUUID(), "motivo", "engano"))
                            .andReturn().getResponse().getStatus();
                });
                largada.countDown();
                int d = devolucao.get(60, TimeUnit.SECONDS);
                int e = estorno.get(60, TimeUnit.SECONDS);
                assertThat(List.of(d, e)).as("rodada %d", rodada).containsExactlyInAnyOrder(201, 409);
                String situacao = jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, venda);
                int ativas = contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ? AND situacao <> 'CANCELADA'", venda);
                assertThat(situacao).isEqualTo(d == 201 ? "CONCLUIDA" : "ESTORNADA");
                assertThat(ativas).isEqualTo(d == 201 ? 1 : 0);
                assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
            }
        } finally {
            pool.shutdown();
        }
        assertThat(saldo(maleta, anel)).isEqualTo(20);
    }

    // --- 5. prazo ---

    @Test
    void prazoContaEmDiasDeBrasiliaPeloMotivoEOEnvioForaDoPrazoVaiParaRevisao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID aparelho = usuarios.aparelhoDe(v);
        UUID anel = peca("10.00", 10);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        // 09/03/2026 às 23:30 em Brasília = 10/03/2026 às 02:30 UTC.
        UUID venda = venderEm(v, maleta, cliente, anel, 6, "60.00", Instant.parse("2026-03-10T02:30:00Z"));
        List<PecaDoPedido> uma = List.of(new PecaDoPedido(anel, 1, "BOA"));
        List<PecaDoPedido> umaComDefeito = List.of(new PecaDoPedido(anel, 1, "DEFEITO"));

        // Dia 7 (16/03 em Brasília) passa.
        devolverPeloServico(v, aparelho, venda, "ARREPENDIMENTO", uma, Instant.parse("2026-03-16T15:00:00Z"), ModoDaDevolucao.ONLINE);
        // Dia 8 (17/03 às 10:00 em Brasília): fora. Em UTC seriam só 7 dias (10/03 a 17/03): a conta é em Brasília.
        Instant diaOito = Instant.parse("2026-03-17T13:00:00Z");
        assertThatThrownBy(() -> devolverPeloServico(v, aparelho, venda, "ARREPENDIMENTO", uma, diaOito, ModoDaDevolucao.ONLINE))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("GARANTIA_VENCIDA"));
        assertThatThrownBy(() -> devolverPeloServico(v, aparelho, venda, "TAMANHO", uma, diaOito, ModoDaDevolucao.ONLINE))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("GARANTIA_VENCIDA"));
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isEqualTo(1);

        // Defeito: dia 30 (08/04) passa, dia 31 (09/04) não.
        devolverPeloServico(v, aparelho, venda, "DEFEITO", umaComDefeito, Instant.parse("2026-04-08T15:00:00Z"), ModoDaDevolucao.ONLINE);
        assertThatThrownBy(() -> devolverPeloServico(v, aparelho, venda, "DEFEITO", umaComDefeito,
                Instant.parse("2026-04-09T15:00:00Z"), ModoDaDevolucao.ONLINE))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("GARANTIA_VENCIDA"));

        // O prazo mudado pela dona vale na hora.
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 8 WHERE chave = 'prazo_arrependimento_dias'");
        devolverPeloServico(v, aparelho, venda, "ARREPENDIMENTO", uma, diaOito, ModoDaDevolucao.ONLINE);

        // Pelo envio do computador, fora do prazo não é recusada: entra para revisão da dona.
        ServicoDeDevolucoes.Resultado revisao = devolverPeloServico(v, aparelho, venda, "ARREPENDIMENTO", uma,
                Instant.parse("2026-03-25T15:00:00Z"), ModoDaDevolucao.ENVIO);
        Map<String, Object> gravada = jdbc.queryForMap("SELECT situacao, dentro_garantia FROM corona.devolucoes WHERE id = ?",
                revisao.devolucaoId());
        assertThat(gravada.get("situacao")).isEqualTo("EM_REVISAO");
        assertThat(gravada.get("dentro_garantia")).isEqualTo(false);
        // As de dentro do prazo ficam aceitas.
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ? AND situacao = 'ACEITA' AND dentro_garantia",
                venda)).isEqualTo(3);
    }

    // --- 6. destino da peça ---

    @Test
    void pecaBoaDeMaletaAbertaVoltaAoSaldoEVendeDeNovoPeloPrecoCongelado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(v, anel, 2);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        assertThat(saldo(maleta, anel)).isZero();
        JsonNode d = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        assertThat(saldo(maleta, anel)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.movimentos_remessa
                 WHERE devolucao_id = ? AND tipo = 'DEVOLUCAO_CLIENTE' AND quantidade = 1 AND venda_id = ? AND remessa_id = ?""",
                UUID.fromString(d.get("id").asString()), venda, maleta)).isEqualTo(1);

        jdbc.update("UPDATE corona.produtos SET preco_venda = 99.00 WHERE id = ?", anel);
        JsonNode nova = corpoDe(postar(VENDAS, como(v), mapa("id", UUID.randomUUID(), "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(linha(anel, 1)), "pagamentos", List.of(pagamento("50.00", "PIX")))).andExpect(status().isCreated()));
        assertThat(dec(nova.get("itens").get(0).get("precoUnitario"))).isEqualByComparingTo("50.00");
        assertThat(saldo(maleta, anel)).isZero();
    }

    @Test
    void pecaBoaDeMaletaAcertadaEPecaComDefeitoViramPecaParaALojaSemMovimento() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        // A maleta vende tudo: acertada sem sobra, ela não deixa diferença na conciliação do banco que os testes dividem.
        UUID maleta = maleta(v, anel, 3);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 3)), List.of(pagamento("150.00", "PIX")));

        // Com defeito, de maleta aberta: para a loja, sem movimento, saldo igual.
        JsonNode defeito = devolver(v, venda, "DEFEITO", "PIX", List.of(devolvida(anel, 1, "DEFEITO")));
        assertThat(defeito.get("itens").get(0).get("destino").asString()).isEqualTo("LOJA");
        assertThat(defeito.get("itens").get(0).get("estado").asString()).isEqualTo("DEFEITO");
        assertThat(saldo(maleta, anel)).isZero();

        // A maleta foi acertada: a peça boa também vai para a loja, sem movimento e sem mudar saldo nenhum.
        AcertosDeTeste.acertada(jdbc, maleta);
        int movimentos = contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ?", maleta);
        JsonNode boa = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        assertThat(boa.get("itens").get(0).get("destino").asString()).isEqualTo("LOJA");
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ?", maleta)).isEqualTo(movimentos);
        assertThat(saldo(maleta, anel)).isZero();
        assertThat(estoque.conciliar().diferencasComMaletas()).noneMatch(x -> x.produtoId().equals(anel));
        assertThat(dec(boa.get("valorReembolsado"))).isEqualByComparingTo("50.00");
    }

    @Test
    void maletaEmAcertoEMaletaEmOutroComputadorDao409() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));

        // Outro computador do mesmo vendedor.
        UUID outro = UUID.randomUUID();
        assertThatThrownBy(() -> devolverPeloServico(v, outro, venda, "ARREPENDIMENTO", List.of(new PecaDoPedido(anel, 1, "BOA")),
                null, ModoDaDevolucao.ONLINE)).satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("MALETA_EM_OUTRO_APARELHO"));

        AcertosDeTeste.emAcerto(jdbc, maleta);
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("MALETA_EM_ACERTO");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isZero();
    }

    // --- 7. cancelar ---

    @Test
    void cancelarDevolveTudoComoEraELiberaADevolucaoEOEstorno() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)),
                List.of(pagamento("60.00", "DINHEIRO"), pagamento("40.00", "PIX")));
        JsonNode d = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 2, "BOA")));
        String id = d.get("id").asString();
        assertThat(saldo(maleta, anel)).isEqualTo(5);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");

        UUID cancelamento = UUID.randomUUID();
        JsonNode cancelada = corpoDe(postar(DEVOLUCOES + "/" + id + "/cancelar", como(v), mapa("id", cancelamento,
                "motivo", "lançada por engano")).andExpect(status().isCreated()));
        assertThat(cancelada.get("situacao").asString()).isEqualTo("CANCELADA");
        assertThat(cancelada.get("motivoCancelamento").asString()).isEqualTo("lançada por engano");
        assertThat(saldo(maleta, anel)).isEqualTo(3);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("100.00");
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente
                 WHERE venda_id = ? AND tipo = 'ESTORNO_REEMBOLSO' AND valor = 100.00 AND estorna_id IS NOT NULL AND devolucao_id = ?""",
                venda, UUID.fromString(id))).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.movimentos_remessa
                 WHERE devolucao_id = ? AND tipo = 'DEVOLUCAO_CLIENTE' AND quantidade = -2 AND estorna_id IS NOT NULL""",
                UUID.fromString(id))).isEqualTo(1);
        JsonNode lida = corpoDe(ler(VENDAS + "/" + venda, como(v)).andExpect(status().isOk()));
        assertThat(dec(lida.get("aReceber"))).isEqualByComparingTo("0");
        assertThat(dec(lida.get("devolvido"))).isEqualByComparingTo("0");

        // Reenvio igual: 200 sem gravar de novo; outro cancelamento: 409.
        postar(DEVOLUCOES + "/" + id + "/cancelar", como(v), mapa("id", cancelamento, "motivo", "lançada por engano"))
                .andExpect(status().isOk());
        assertThat(codigoDe(postar(DEVOLUCOES + "/" + id + "/cancelar", como(v), mapa("id", UUID.randomUUID(), "motivo", "de novo"))
                .andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_JA_CANCELADA");
        assertThat(saldo(maleta, anel)).isEqualTo(3);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("100.00");

        // O que foi devolvido volta a poder ser devolvido; cancelada a nova, a venda volta a poder ser estornada.
        JsonNode outra = devolver(v, venda, "ARREPENDIMENTO", "DINHEIRO", List.of(devolvida(anel, 2, "BOA")));
        postar(VENDAS + "/" + venda + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isConflict());
        postar(DEVOLUCOES + "/" + outra.get("id").asString() + "/cancelar", como(v), mapa("id", UUID.randomUUID(),
                "motivo", "engano")).andExpect(status().isCreated());
        postar(VENDAS + "/" + venda + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        assertThat(saldo(maleta, anel)).isEqualTo(5);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
    }

    @Test
    void vendedorSoCancelaNoDiaDaDevolucaoEADonaCancelaDepois() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        JsonNode d = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        UUID id = UUID.fromString(d.get("id").asString());

        Instant amanha = Instant.now().plus(Duration.ofDays(1));
        assertThatThrownBy(() -> devolucoes.cancelar(id, v.vendedorId(), v.id(), usuarios.aparelhoDe(v), UUID.randomUUID(),
                "engano", amanha)).satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("CANCELAR_DEVOLUCAO_SO_DA_DONA"));
        assertThat(saldo(maleta, anel)).isEqualTo(5);

        JsonNode pelaDona = corpoDe(postar(DEVOLUCOES_DA_DONA + "/" + id + "/cancelar", dona(), mapa("id", UUID.randomUUID(),
                "motivo", "o cliente ficou com a peça")).andExpect(status().isCreated()));
        assertThat(pelaDona.get("situacao").asString()).isEqualTo("CANCELADA");
        assertThat(saldo(maleta, anel)).isEqualTo(4);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("50.00");
    }

    @Test
    void cancelarDepoisQueAPecaFoiVendidaDeNovoDa409SemGravarNada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 5);
        UUID maleta = maleta(v, anel, 1);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        JsonNode d = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        assertThat(saldo(maleta, anel)).isZero();

        assertThat(codigoDe(postar(DEVOLUCOES + "/" + d.get("id").asString() + "/cancelar", como(v), mapa("id", UUID.randomUUID(),
                "motivo", "engano")).andExpect(status().isConflict()))).isEqualTo("PECA_DA_DEVOLUCAO_JA_VENDIDA");
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.devolucoes WHERE id = ?", String.class,
                UUID.fromString(d.get("id").asString()))).isEqualTo("ACEITA");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'ESTORNO_REEMBOLSO'", venda))
                .isZero();
        assertThat(saldo(maleta, anel)).isZero();
    }

    // --- 8. estorno com devolução ---

    @Test
    void vendaComDevolucaoNaoEstornaEVendaEstornadaNaoRecebeDevolucao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));

        JsonNode erro = corpoDe(postar(VENDAS + "/" + venda + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isConflict()));
        assertThat(erro.get("codigo").asString()).isEqualTo("VENDA_COM_DEVOLUCAO");
        assertThat(erro.get("detail").asString()).isEqualTo("Esta venda já tem devolução. Cancele a devolução antes de estornar.");
        postar("/api/dona/vendas/" + venda + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isConflict());
        // Segunda trava: nem direto no banco.
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = now(), motivo_estorno = 'x'
                 WHERE id = ?""", UUID.randomUUID(), venda)).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, venda)).isEqualTo("CONCLUIDA");

        // Venda estornada não recebe devolução (409 e gatilho).
        UUID outra = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        postar(VENDAS + "/" + outra + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), outra, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("VENDA_JA_ESTORNADA");
        UUID item = jdbc.queryForObject("SELECT id FROM corona.itens_venda WHERE venda_id = ?", UUID.class, outra);
        assertThatThrownBy(() -> gravarDireto(v, outra, maleta, cliente, item, 1, "1.00")).isInstanceOf(SQLException.class);
    }

    // --- 9. idempotência ---

    @Test
    void reenvioIgualDa200SemDuplicarEDadosDiferentesDao409() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 3)), List.of(pagamento("150.00", "PIX")));
        UUID outraVenda = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        UUID id = UUID.randomUUID();
        Map<String, Object> pedido = devolucao(id, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));

        postar(DEVOLUCOES, como(v), pedido).andExpect(status().isCreated());
        JsonNode denovo = corpoDe(postar(DEVOLUCOES, como(v), pedido).andExpect(status().isOk()));
        assertThat(denovo.get("id").asString()).isEqualTo(id.toString());
        assertThat(contar("SELECT count(*) FROM corona.itens_devolucao WHERE devolucao_id = ?", id)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE devolucao_id = ?", id)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE devolucao_id = ?", id)).isEqualTo(1);
        assertThat(saldo(maleta, anel)).isEqualTo(2);

        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucao(id, venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 2, "BOA")))).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_JA_REGISTRADA_DIFERENTE");
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucao(id, outraVenda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_JA_REGISTRADA_DIFERENTE");
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucao(id, venda, "TAMANHO", "PIX",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_JA_REGISTRADA_DIFERENTE");
        assertThat(saldo(maleta, anel)).isEqualTo(2);
    }

    // --- 10. venda a prazo ---

    @Test
    void vendaAPrazoAbateAsParcelasEPagaEmPartesContaComoAVista() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID aPrazo = vender(v, maleta, cliente, List.of(linha(anel, 2)), List.of(pagamento("40.00", "DINHEIRO")));
        // 100,00 com 40,00 pagos: a devolução de 50,00 abate o que falta e não devolve dinheiro (tarefa 6.6).
        JsonNode abatida = devolver(v, aPrazo, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        assertThat(jdbc.queryForObject("SELECT valor_abatido FROM corona.devolucoes WHERE id = ?", BigDecimal.class,
                UUID.fromString(abatida.get("id").asString()))).isEqualByComparingTo("50.00");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", aPrazo)).isZero();

        UUID emPartes = vender(v, maleta, cliente, List.of(linha(anel, 2)),
                List.of(pagamento("60.00", "DINHEIRO"), pagamento("40.00", "CARTAO")));
        devolver(v, emPartes, "ARREPENDIMENTO", "CARTAO", List.of(devolvida(anel, 1, "BOA")));
    }

    // --- 11. permissões ---

    @Test
    void vendedorSoDevolveVeECancelaAsDeleENaoUsaARotaDaDona() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maletaDeA = maleta(a, anel, 3);
        maleta(b, anel, 3);
        UUID cliente = cliente(a);
        UUID venda = vender(a, maletaDeA, cliente, List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));

        assertThat(codigoDe(postar(DEVOLUCOES, como(b), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isNotFound()))).isEqualTo("VENDA_NAO_ENCONTRADA");
        JsonNode d = devolver(a, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        String id = d.get("id").asString();

        ler(DEVOLUCOES + "/" + id, como(b)).andExpect(status().isNotFound());
        assertThat(corpoDe(ler(DEVOLUCOES, como(b)).andExpect(status().isOk()))).isEmpty();
        assertThat(corpoDe(ler(DEVOLUCOES, como(a)).andExpect(status().isOk()))).hasSize(1);
        assertThat(codigoDe(postar(DEVOLUCOES + "/" + id + "/cancelar", como(b), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isNotFound()))).isEqualTo("DEVOLUCAO_NAO_ENCONTRADA");
        String codigo = jdbc.queryForObject("SELECT codigo FROM corona.produto_codigos WHERE produto_id = ?", String.class, anel);
        JsonNode deB = corpoDe(mvc.perform(get(DEVOLUCOES + "/vendas-com-a-peca").param("codigo", codigo).with(como(b)))
                .andExpect(status().isOk()));
        assertThat(deB.get("vendas")).isEmpty();

        postar(DEVOLUCOES_DA_DONA + "/" + id + "/cancelar", como(a), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isForbidden());
        ler(DEVOLUCOES, dona()).andExpect(status().isForbidden());
        postar(DEVOLUCOES, dona(), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"))))
                .andExpect(status().isForbidden());
        ler(DEVOLUCOES, UsuariosDeTeste.comToken("invalido")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.devolucoes WHERE id = ?", String.class, UUID.fromString(id)))
                .isEqualTo("ACEITA");
    }

    // --- 12. nada de custo ---

    @Test
    void nenhumaRespostaDasRotasNovasTemCustoNemFornecedor() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        JsonNode d = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        String codigo = jdbc.queryForObject("SELECT codigo FROM corona.produto_codigos WHERE produto_id = ?", String.class, anel);
        List<String> respostas = List.of(d.toString(),
                corpoDe(ler(DEVOLUCOES, como(v))).toString(),
                corpoDe(ler(DEVOLUCOES + "/" + d.get("id").asString(), como(v))).toString(),
                corpoDe(mvc.perform(get(DEVOLUCOES + "/vendas-com-a-peca").param("codigo", codigo).with(como(v)))).toString(),
                corpoDe(mvc.perform(get(DEVOLUCOES + "/vendas-com-a-peca").param("produtoId", anel.toString()).with(como(v))))
                        .toString(),
                corpoDe(postar(DEVOLUCOES + "/" + d.get("id").asString() + "/cancelar", como(v),
                        mapa("id", UUID.randomUUID(), "motivo", "engano"))).toString());
        for (String r : respostas) {
            assertThat(r.toLowerCase()).doesNotContain("custo").doesNotContain("fornecedor");
        }
    }

    // --- 13. conciliação ---

    @Test
    void conciliacaoDoEstoqueComAsMaletasFechaComDevolucoesECancelamento() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 6);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 4)), List.of(pagamento("200.00", "PIX")));
        devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        devolver(v, venda, "DEFEITO", "PIX", List.of(devolvida(anel, 1, "DEFEITO")));
        JsonNode cancelavel = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        postar(DEVOLUCOES + "/" + cancelavel.get("id").asString() + "/cancelar", como(v), mapa("id", UUID.randomUUID(),
                "motivo", "engano")).andExpect(status().isCreated());

        // Saldo da maleta = enviada - vendidas + devolvidas boas (§7.1): 6 - 4 + 1 = 3.
        assertThat(saldo(maleta, anel)).isEqualTo(3);
        assertThat(estoque.conciliar().divergencias()).noneMatch(x -> x.produtoId().equals(anel));
        assertThat(estoque.conciliar().diferencasComMaletas()).noneMatch(x -> x.produtoId().equals(anel));
        // O relatório de conciliação da dona faz a mesma conta.
        JsonNode relatorio = corpoDe(ler("/api/dona/relatorios/estoque/conciliacao?produtoId=" + anel, dona())
                .andExpect(status().isOk()));
        assertThat(relatorio.get("total").asLong()).isZero();
    }

    // --- 14. imutabilidade ---

    @Test
    void devolucaoEItensNaoSaoAlteradosNemApagados() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        UUID outraVenda = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        JsonNode d = devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        UUID id = UUID.fromString(d.get("id").asString());

        assertThatThrownBy(() -> jdbc.update("UPDATE corona.itens_devolucao SET quantidade = 2 WHERE devolucao_id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.itens_devolucao WHERE devolucao_id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.devolucoes WHERE id = ?", id)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET valor_devolvido = 1.00, valor_reembolsado = 1.00 WHERE id = ?",
                id)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET venda_id = ? WHERE id = ?", outraVenda, id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET vendedor_id = ? WHERE id = ?",
                usuarios.criarVendedor().vendedorId(), id)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET deleted_at = now() WHERE id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET situacao = 'MANTIDA' WHERE id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.pagamentos_cliente SET valor = -1.00 WHERE devolucao_id = ?", id))
                .isInstanceOf(Exception.class);

        postar(DEVOLUCOES + "/" + id + "/cancelar", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.devolucoes SET situacao = 'ACEITA', cancelamento_id = NULL, cancelada_em = NULL, cancelada_por = NULL,
                       motivo_cancelamento = NULL WHERE id = ?""", id)).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.devolucoes WHERE id = ?", String.class, id)).isEqualTo("CANCELADA");
    }

    // --- 15. busca pela peça lida ---

    @Test
    void lerOCodigoAchaAsVendasComOQueAindaPodeVoltar() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        String codigo = "Dv-Lido-" + UUID.randomUUID().toString().substring(0, 6);
        UUID anel = pecaComCodigo("50.00", 20, codigo);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        UUID antiga = vender(v, maleta, cliente, List.of(linha(anel, 3)), List.of(pagamento("150.00", "PIX")));
        devolver(v, antiga, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
        UUID aPrazo = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of());
        UUID estornada = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        postar(VENDAS + "/" + estornada + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());

        JsonNode r = corpoDe(mvc.perform(get(DEVOLUCOES + "/vendas-com-a-peca").param("codigo", "  " + codigo.toUpperCase() + "  ")
                .with(como(v))).andExpect(status().isOk()));
        assertThat(r.get("peca").get("id").asString()).isEqualTo(anel.toString());
        JsonNode lista = r.get("vendas");
        assertThat(lista).hasSize(2);
        // Da mais nova para a mais antiga: a venda a prazo (marcada) e depois a antiga. A estornada não aparece.
        assertThat(lista.get(0).get("vendaId").asString()).isEqualTo(aPrazo.toString());
        assertThat(lista.get(0).get("aPrazo").asBoolean()).isTrue();
        JsonNode daAntiga = lista.get(1);
        assertThat(daAntiga.get("vendaId").asString()).isEqualTo(antiga.toString());
        assertThat(daAntiga.get("aPrazo").asBoolean()).isFalse();
        assertThat(daAntiga.get("prazoArrependimentoAte").asString()).isNotBlank();
        assertThat(daAntiga.get("prazoDefeitoAte").asString()).isNotBlank();
        JsonNode linhaDaVenda = daAntiga.get("itens").get(0);
        assertThat(linhaDaVenda.get("vendidas").asInt()).isEqualTo(3);
        assertThat(linhaDaVenda.get("devolvidas").asInt()).isEqualTo(1);
        assertThat(linhaDaVenda.get("podemVoltar").asInt()).isEqualTo(2);
        assertThat(linhaDaVenda.get("produto").get("id").asString()).isEqualTo(anel.toString());

        // Pelo produto, o mesmo resultado; código desconhecido, nenhuma venda.
        assertThat(corpoDe(mvc.perform(get(DEVOLUCOES + "/vendas-com-a-peca").param("produtoId", anel.toString()).with(como(v))))
                .get("vendas")).hasSize(2);
        JsonNode nada = corpoDe(mvc.perform(get(DEVOLUCOES + "/vendas-com-a-peca").param("codigo", "nao-existe-" + UUID.randomUUID())
                .with(como(v))).andExpect(status().isOk()));
        assertThat(nada.get("vendas")).isEmpty();
        mvc.perform(get(DEVOLUCOES + "/vendas-com-a-peca").with(como(v))).andExpect(status().isBadRequest());
    }

    // --- 16. pedido malformado ---

    @Test
    void pedidoMalformadoDa400EmPortugues() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID outra = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5, outra, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 3)), List.of(pagamento("150.00", "PIX")));
        List<Map<String, Object>> uma = List.of(devolvida(anel, 1, "BOA"));

        List<Map<String, Object>> ruins = new ArrayList<>();
        ruins.add(devolucao(UUID.randomUUID(), venda, null, "PIX", uma));
        ruins.add(devolucao(UUID.randomUUID(), venda, "NAO_GOSTOU", "PIX", uma));
        ruins.add(devolucao(UUID.randomUUID(), venda, "DEFEITO", "PIX", uma));
        ruins.add(devolucao(UUID.randomUUID(), venda, "OUTRO", "PIX", uma));
        ruins.add(devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "FIADO", uma));
        ruins.add(devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", null, uma));
        ruins.add(devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA"), devolvida(anel, 1, "BOA"))));
        ruins.add(devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 0, "BOA"))));
        ruins.add(devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "QUEBRADA"))));
        ruins.add(devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of()));
        ruins.add(devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(outra, 1, "BOA"))));
        ruins.add(devolucao(UUID.randomUUID(), null, "ARREPENDIMENTO", "PIX", uma));
        Map<String, Object> longa = devolucao(UUID.randomUUID(), venda, "OUTRO", "PIX", uma);
        longa.put("observacao", "x".repeat(401));
        ruins.add(longa);
        for (Map<String, Object> ruim : ruins) {
            JsonNode erro = corpoDe(postar(DEVOLUCOES, como(v), ruim).andExpect(status().isBadRequest()));
            assertThat(erro.get("detail").asString()).as("%s", ruim).isNotBlank();
            assertThat(erro.toString()).as("%s", ruim).doesNotContain("Exception");
        }
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isZero();

        // A mesma peça, uma boa e uma com defeito, é aceita (motivo que não é defeito); "outro" com observação também.
        devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"), devolvida(anel, 1, "DEFEITO")));
        Map<String, Object> comObservacao = devolucao(UUID.randomUUID(), venda, "OUTRO", "PIX", uma);
        comObservacao.put("observacao", "Presente repetido");
        JsonNode outro = corpoDe(postar(DEVOLUCOES, como(v), comObservacao).andExpect(status().isCreated()));
        assertThat(outro.get("observacao").asString()).isEqualTo("Presente repetido");
    }

    // --- 17. registro de tabelas ---

    @Test
    void asTabelasNovasVaoAoVendedorDesdeATarefa67() throws Exception {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        for (String nome : List.of("devolucoes", "itens_devolucao")) {
            TabelaSincronizavel t = registro.sincronizavel(nome).orElseThrow();
            assertThat(t.regra().declarada()).as(nome).isTrue();
            // Desde a tarefa 6.7, o vendedor recebe as dele (filtro do vendedor; PullDaFase6Test).
            assertThat(t.regra().condicao()).as(nome).isNotEqualTo("FALSE");
        }
        assertThat(registro.sincronizavel("itens_devolucao").orElseThrow().imutavel()).isTrue();
        assertThat(registro.sincronizavel("pagamentos_cliente").orElseThrow().colunas()).contains("devolucao_id", "estorna_id");
        assertThat(registro.sincronizavel("movimentos_remessa").orElseThrow().colunas()).contains("devolucao_id");

        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        devolver(v, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));

        List<LinhaDoPull> recebidas = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 100; i++) {
            LoteDoPull lote = pull.puxar(cursor, 500, new FiltroPorVendedor(v.vendedorId()));
            recebidas.addAll(lote.linhas());
            cursor = lote.proximoCursor();
            if (!lote.haMais()) {
                break;
            }
        }
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("devolucoes")).isNotEmpty()
                .allSatisfy(l -> assertThat(l.colunas()).doesNotContainKeys("registrada_por", "revisada_por", "cancelada_por"));
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("itens_devolucao")).isNotEmpty();
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("pagamentos_cliente")).isNotEmpty()
                .allSatisfy(l -> assertThat(l.colunas()).containsKeys("devolucao_id", "estorna_id"));
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("movimentos_remessa")).isNotEmpty()
                .allSatisfy(l -> assertThat(l.colunas()).containsKeys("devolucao_id"));
    }
}
