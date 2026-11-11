package br.com.coronasemijoias.api.vendedor;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Rotas do vendedor da tarefa 4.3 (ADR-0011): a própria maleta sem custo, leitura da peça pelo código, conferência com
 * dispositivo único, clientes dele, cotação, foto e relatórios próprios. Sempre com o isolamento entre vendedores e o
 * 403 do perfil errado.
 */
class RotasDoVendedorTest extends TesteDeIntegracao {

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

    private RequestPostProcessor como(UsuarioDeTeste vendedor) {
        return UsuariosDeTeste.comToken(usuarios.token(vendedor));
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

    private UUID cliente(UsuarioDeTeste dono) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID(),
                "vendedorResponsavelId", dono.vendedorId())).andExpect(status().isCreated())).get("id").asString());
    }

    private UUID semana() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(2500, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 90_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private record Peca(UUID id, String codigo) {
    }

    private Peca peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        String codigo = "R-" + sufixo;
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Brinco " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", codigo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        if (saldo > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                    usuarios.dona().id());
        }
        return new Peca(id, codigo);
    }

    private UUID maleta(UsuarioDeTeste vendedor, Object... pares) throws Exception {
        JsonNode r = corpoDe(postar(REMESSAS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(),
                "semanaId", semana(), "tipo", "NOVA")).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + ((Peca) pares[i]).id()).with(dona())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", pares[i + 1], "version", r.get("version").asInt()))))
                    .andExpect(status().isOk()));
        }
        postar(REMESSAS + "/" + r.get("id").asString() + "/enviar", dona(), null).andExpect(status().isCreated());
        return UUID.fromString(r.get("id").asString());
    }

    private ResultActions vender(RequestPostProcessor quem, UUID maleta, UUID cliente, Peca peca, int quantidade,
            List<Map<String, Object>> pagamentos) throws Exception {
        return postar("/api/vendedor/vendas", quem, mapa("id", UUID.randomUUID(), "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", peca.id(), "quantidade", quantidade)), "pagamentos", pagamentos));
    }

    private static Map<String, Object> pagamento(String valor, String forma) {
        return mapa("id", UUID.randomUUID(), "valor", valor, "forma", forma);
    }

    private static Map<String, Object> contada(Peca peca, int contada) {
        return mapa("produtoId", peca.id(), "contada", contada);
    }

    // --- a maleta ---

    @Test
    void vendedorVeASuaMaletaSemCustoEOutroVendedorNao() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        Peca brinco = peca("40.00", 10);
        UUID maleta = maleta(a, brinco, 4);
        RequestPostProcessor quemA = como(a);
        cliente(a);

        JsonNode lista = corpoDe(ler("/api/vendedor/maletas", quemA).andExpect(status().isOk()));
        assertThat(lista.size()).isEqualTo(1);
        JsonNode m = lista.get(0);
        assertThat(m.get("id").asString()).isEqualTo(maleta.toString());
        assertThat(m.get("status").asString()).isEqualTo("ENVIADA");
        assertThat(m.get("pecasEmAberto").asInt()).isEqualTo(4);
        assertThat(dec(m.get("valorEmAberto"))).isEqualByComparingTo("160.00");
        JsonNode linha = m.get("itens").get(0);
        assertThat(linha.get("produto").get("codigo").asString()).isEqualTo(brinco.codigo());
        assertThat(dec(linha.get("precoUnitario"))).isEqualByComparingTo("40.00");
        assertThat(linha.get("saldo").asInt()).isEqualTo(4);
        assertThat(m.toString().toLowerCase()).doesNotContain("custo").doesNotContain("estoque");

        assertThat(corpoDe(ler("/api/vendedor/maletas", como(b)).andExpect(status().isOk())).size()).isZero();
        ler("/api/vendedor/maletas/" + maleta, como(b)).andExpect(status().isNotFound());
        ler("/api/vendedor/maletas/" + maleta, dona()).andExpect(status().isForbidden());
        ler("/api/vendedor/maletas", dona()).andExpect(status().isForbidden());
    }

    @Test
    void oLeitorSoAchaPecaQueEstaNaMaleta() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        Peca dentro = peca("40.00", 5);
        Peca fora = peca("40.00", 5);
        UUID maleta = maleta(a, dentro, 2);
        RequestPostProcessor quem = como(a);

        JsonNode achada = corpoDe(ler("/api/vendedor/maletas/" + maleta + "/pecas?codigo=" + dentro.codigo().toLowerCase(), quem)
                .andExpect(status().isOk()));
        assertThat(achada.get("produto").get("id").asString()).isEqualTo(dentro.id().toString());
        ler("/api/vendedor/maletas/" + maleta + "/pecas?codigo=" + fora.codigo(), quem).andExpect(status().isNotFound());
        ler("/api/vendedor/maletas/" + maleta + "/pecas?codigo=NAO-EXISTE", quem).andExpect(status().isNotFound());
        ler("/api/vendedor/maletas/" + maleta + "/pecas?codigo=", quem).andExpect(status().isNotFound());
    }

    // --- conferência e aparelho único ---

    @Test
    void conferenciaGravaADiferencaLigaOAparelhoEEhUmaSoPorMaleta() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        Peca anel = peca("30.00", 10);
        Peca colar = peca("50.00", 10);
        UUID maleta = maleta(a, anel, 5, colar, 3);
        RequestPostProcessor quem = como(a);
        UUID id = UUID.randomUUID();

        // Contou 4 anéis (falta 1) e não contou nenhum colar (faltam 3).
        Map<String, Object> pedido = mapa("id", id, "itens", List.of(contada(anel, 4)));
        JsonNode r = corpoDe(postar("/api/vendedor/maletas/" + maleta + "/conferencia", quem, pedido).andExpect(status().isCreated()));
        assertThat(r.get("divergencias").asInt()).isEqualTo(2);
        assertThat(r.get("itens").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta)).isEqualTo("CONFERIDA");
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, maleta)).isNotNull();

        // Mesma conferência de novo: 200. Outra contagem com o mesmo id: 409. Outra conferência: 409.
        postar("/api/vendedor/maletas/" + maleta + "/conferencia", quem, pedido).andExpect(status().isOk());
        postar("/api/vendedor/maletas/" + maleta + "/conferencia", quem, mapa("id", id, "itens", List.of(contada(anel, 5))))
                .andExpect(status().isConflict());
        postar("/api/vendedor/maletas/" + maleta + "/conferencia", quem,
                mapa("id", UUID.randomUUID(), "itens", List.of(contada(anel, 5), contada(colar, 3)))).andExpect(status().isConflict());
        assertThat(corpoDe(ler("/api/vendedor/maletas/" + maleta + "/conferencia", quem).andExpect(status().isOk()))
                .get("divergencias").asInt()).isEqualTo(2);

        // A maleta conferida continua aparecendo e a dona ainda consegue receber a devolução dela.
        assertThat(corpoDe(ler("/api/vendedor/maletas", quem).andExpect(status().isOk())).get(0).get("status").asString())
                .isEqualTo("CONFERIDA");
        postar(REMESSAS + "/" + maleta + "/devolucoes", dona(),
                mapa("itens", List.of(mapa("id", UUID.randomUUID(), "produtoId", anel.id(), "quantidade", 1))))
                .andExpect(status().isCreated());
    }

    @Test
    void conferenciaRecusaPecaDeForaECampoInvalido() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        Peca anel = peca("30.00", 10);
        Peca fora = peca("30.00", 10);
        UUID maleta = maleta(a, anel, 5);
        String caminho = "/api/vendedor/maletas/" + maleta + "/conferencia";

        postar(caminho, como(a), mapa("id", UUID.randomUUID(), "itens", List.of(contada(fora, 1)))).andExpect(status().isBadRequest());
        postar(caminho, como(a), mapa("id", UUID.randomUUID(), "itens", List.of(contada(anel, -1)))).andExpect(status().isBadRequest());
        postar(caminho, como(a), mapa("id", UUID.randomUUID(), "itens", List.of(contada(anel, 1), contada(anel, 2))))
                .andExpect(status().isBadRequest());
        postar(caminho, como(b), mapa("id", UUID.randomUUID(), "itens", List.of(contada(anel, 5)))).andExpect(status().isNotFound());
        postar(caminho, dona(), mapa("id", UUID.randomUUID(), "itens", List.of())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta)).isEqualTo("ENVIADA");
    }

    @Test
    void umaMaletaSoVendeNoAparelhoQueAProvouAteADonaLiberar() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        Peca anel = peca("30.00", 10);
        UUID maleta = maleta(a, anel, 5);
        UUID cliente = cliente(a);
        RequestPostProcessor aparelho1 = como(a); // cada token de teste leva um aparelho novo
        RequestPostProcessor aparelho2 = como(a);

        vender(aparelho1, maleta, cliente, anel, 1, List.of()).andExpect(status().isCreated());
        // O segundo aparelho não vende, não confere e recebe o aviso em português.
        vender(aparelho2, maleta, cliente, anel, 1, List.of()).andExpect(status().isConflict());
        postar("/api/vendedor/maletas/" + maleta + "/conferencia", aparelho2,
                mapa("id", UUID.randomUUID(), "itens", List.of(contada(anel, 4)))).andExpect(status().isConflict());
        // O vendedor não libera sozinho.
        postar(REMESSAS + "/" + maleta + "/liberar-aparelho", aparelho1, null).andExpect(status().isForbidden());

        assertThat(corpoDe(postar(REMESSAS + "/" + maleta + "/liberar-aparelho", dona(), null).andExpect(status().isOk()))
                .get("tinhaAparelho").asBoolean()).isTrue();
        vender(aparelho2, maleta, cliente, anel, 1, List.of()).andExpect(status().isCreated());
        vender(aparelho1, maleta, cliente, anel, 1, List.of()).andExpect(status().isConflict());
        // Liberar maleta que já está livre não muda nada.
        UUID outra = maleta(a, peca("10.00", 3), 1);
        assertThat(corpoDe(postar(REMESSAS + "/" + outra + "/liberar-aparelho", dona(), null).andExpect(status().isOk()))
                .get("tinhaAparelho").asBoolean()).isFalse();
    }

    // --- clientes ---

    @Test
    void cadastraEBuscaSoOsProprioClientes() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        RequestPostProcessor quemA = como(a);
        UUID id = UUID.randomUUID();
        String nome = "Maria da Silva " + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> pedido = mapa("id", id, "nome", nome, "telefone", "(11) 98888-7777", "cpf", "529.982.247-25");

        JsonNode criado = corpoDe(postar("/api/vendedor/clientes", quemA, pedido).andExpect(status().isCreated()));
        assertThat(criado.get("cpf").asString()).isEqualTo("52998224725");
        postar("/api/vendedor/clientes", quemA, pedido).andExpect(status().isOk());
        postar("/api/vendedor/clientes", quemA, mapa("id", id, "nome", "Outro nome")).andExpect(status().isConflict());
        // O mesmo id vindo de outro vendedor também é 409, sem dizer de quem é o cliente.
        postar("/api/vendedor/clientes", como(b), pedido).andExpect(status().isConflict());
        postar("/api/vendedor/clientes", quemA, mapa("nome", "Com CPF errado", "cpf", "111.111.111-11")).andExpect(status().isBadRequest());
        postar("/api/vendedor/clientes", quemA, mapa("nome", "  ")).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT vendedor_responsavel_id FROM corona.clientes WHERE id = ?", UUID.class, id))
                .isEqualTo(a.vendedorId());
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=maria da silva", quemA)).size()).isEqualTo(1);
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=98888", quemA)).size()).isEqualTo(1);
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=52998224", quemA)).size()).isEqualTo(1);
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=%25", quemA)).size()).isZero(); // curinga digitado vale ao pé da letra
        assertThat(corpoDe(ler("/api/vendedor/clientes", como(b))).size()).isZero();
        ler("/api/vendedor/clientes/" + id, como(b)).andExpect(status().isNotFound());
        ler("/api/vendedor/clientes/" + id, quemA).andExpect(status().isOk());
        ler("/api/vendedor/clientes", dona()).andExpect(status().isForbidden());
        postar("/api/vendedor/clientes", dona(), pedido).andExpect(status().isForbidden());
    }

    // --- cotação e foto ---

    @Test
    void cotacaoDoDiaEFotoDeFora() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        JsonNode c = corpoDe(ler("/api/vendedor/cotacao", como(a)).andExpect(status().isOk()));
        assertThat(c.has("disponivel")).isTrue();
        ler("/api/vendedor/cotacao", dona()).andExpect(status().isForbidden());
        // Foto que não é de peça da maleta dele: 404.
        ler("/api/vendedor/fotos/" + "a".repeat(64) + "/miniatura", como(a)).andExpect(status().isNotFound());
        ler("/api/vendedor/fotos/" + "a".repeat(64) + "/miniatura", dona()).andExpect(status().isForbidden());
    }

    // --- relatórios ---

    @Test
    void relatoriosSoComOsDadosDoVendedorSemAsEstornadas() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        Peca anel = peca("50.00", 20);
        Peca colar = peca("100.00", 20);
        UUID maleta = maleta(a, anel, 10, colar, 10);
        UUID maria = cliente(a);
        UUID joao = cliente(a);
        RequestPostProcessor quem = como(a);

        // Maria: 2 anéis (100) pagos 60 em dinheiro. João: 1 colar (100) pago em Pix. Uma venda estornada não conta.
        vender(quem, maleta, maria, anel, 2, List.of(pagamento("60.00", "DINHEIRO"))).andExpect(status().isCreated());
        vender(quem, maleta, joao, colar, 1, List.of(pagamento("100.00", "PIX"))).andExpect(status().isCreated());
        UUID estornada = UUID.fromString(corpoDe(vender(quem, maleta, maria, colar, 1, List.of(pagamento("100.00", "PIX")))
                .andExpect(status().isCreated())).get("id").asString());
        postar("/api/vendedor/vendas/" + estornada + "/estorno", quem, mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());

        JsonNode porCliente = corpoDe(ler("/api/vendedor/relatorios/vendas", quem).andExpect(status().isOk()));
        assertThat(porCliente.get("linhas").size()).isEqualTo(2);
        assertThat(dec(porCliente.get("totais").get("total"))).isEqualByComparingTo("200.00");

        JsonNode porPeca = corpoDe(ler("/api/vendedor/relatorios/vendas?agrupar=peca", quem).andExpect(status().isOk()));
        assertThat(porPeca.get("linhas").size()).isEqualTo(2);
        assertThat(dec(porPeca.get("totais").get("total"))).isEqualByComparingTo("200.00");

        JsonNode porDia = corpoDe(ler("/api/vendedor/relatorios/vendas?agrupar=dia", quem).andExpect(status().isOk()));
        assertThat(porDia.get("linhas").size()).isEqualTo(1);
        assertThat(porDia.get("linhas").get(0).get("quantidade").asInt()).isEqualTo(2);

        // Recebimentos: o estorno devolve o Pix da venda estornada (100 pago, 100 reembolsado).
        JsonNode receb = corpoDe(ler("/api/vendedor/relatorios/recebimentos", quem).andExpect(status().isOk()));
        assertThat(dec(receb.get("totais").get("total"))).isEqualByComparingTo("160.00");

        JsonNode devedores = corpoDe(ler("/api/vendedor/relatorios/a-receber", quem).andExpect(status().isOk()));
        assertThat(devedores.get("clientes").size()).isEqualTo(1);
        assertThat(devedores.get("clientes").get(0).get("clienteId").asString()).isEqualTo(maria.toString());
        assertThat(dec(devedores.get("totais").get("total"))).isEqualByComparingTo("40.00");

        // Período futuro: vazio. Período invertido: 400. Agrupar desconhecido: 400.
        assertThat(corpoDe(ler("/api/vendedor/relatorios/vendas?de=2999-01-01", quem)).get("linhas").size()).isZero();
        ler("/api/vendedor/relatorios/vendas?de=2026-02-01&ate=2026-01-01", quem).andExpect(status().isBadRequest());
        ler("/api/vendedor/relatorios/vendas?agrupar=custo", quem).andExpect(status().isBadRequest());

        // O outro vendedor não enxerga nada; a dona recebe 403.
        assertThat(corpoDe(ler("/api/vendedor/relatorios/vendas", como(b))).get("linhas").size()).isZero();
        assertThat(corpoDe(ler("/api/vendedor/relatorios/a-receber", como(b))).get("clientes").size()).isZero();
        ler("/api/vendedor/relatorios/vendas", dona()).andExpect(status().isForbidden());
        ler("/api/vendedor/relatorios/recebimentos", dona()).andExpect(status().isForbidden());
        ler("/api/vendedor/relatorios/a-receber", dona()).andExpect(status().isForbidden());
    }
}
