package br.com.coronasemijoias.api.vendedor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.fotos.ImagensDeTeste;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.CpfsDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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

    /** As cotações de 1990 deste teste são gravadas de verdade (a tabela é imutável): saem no fim, para não vazar. */
    @AfterAll
    static void tirarAsCotacoesDe1990() throws SQLException {
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirarDias(con, List.of("1990-01-05", "1990-01-06"));
        }
    }

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

    private static String sha256(byte[] conteudo) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(conteudo));
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

    @Test
    void aPecaLidaTrazCodigoDoFornecedorECategoriaSemCustoNemEstoqueDaLoja() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Aneis " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Anel Lua " + sufixo,
                "categoriaId", categoria, "precoVenda", "55.00", "precoCusto", "21.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "L-" + sufixo, "tipo", "LOJA", "principal", true),
                        mapa("codigo", "FAB-" + sufixo, "tipo", "FORNECEDOR", "principal", false))))
                .andExpect(status().isCreated())).get("id").asString());
        estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, 4, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        UUID maleta = maleta(a, new Peca(id, "L-" + sufixo), 3);

        JsonNode lida = corpoDe(ler("/api/vendedor/maletas/" + maleta + "/pecas?codigo=L-" + sufixo, como(a)).andExpect(status().isOk()));
        assertThat(lida.get("produto").get("codigoFornecedor").asString()).isEqualTo("FAB-" + sufixo);
        assertThat(lida.get("produto").get("categoria").asString()).isEqualTo("Aneis " + sufixo);
        assertThat(lida.get("saldo").asInt()).isEqualTo(3);
        // Lida pelo código do fornecedor também acha a mesma peça, e a maleta traz os mesmos dados.
        assertThat(corpoDe(ler("/api/vendedor/maletas/" + maleta + "/pecas?codigo=FAB-" + sufixo, como(a))).get("produto").get("id").asString())
                .isEqualTo(id.toString());
        JsonNode daMaleta = corpoDe(ler("/api/vendedor/maletas/" + maleta, como(a)).andExpect(status().isOk()));
        assertThat(daMaleta.get("itens").get(0).get("produto").get("categoria").asString()).isEqualTo("Aneis " + sufixo);
        // Sem custo e sem estoque da loja nem de outras maletas.
        String texto = lida.toString().toLowerCase();
        assertThat(texto).doesNotContain("custo").doesNotContain("estoqueatual").doesNotContain("emmaletas").doesNotContain("21.00");
    }

    @Test
    void buscaPeloNomeSoMostraPecasDaPropriaMaletaComSaldoEMiniatura() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        Peca deA = peca("40.00", 5);
        Peca deB = peca("40.00", 5);
        Peca fora = peca("40.00", 5);
        UUID maletaDeA = maleta(a, deA, 2);
        UUID maletaDeB = maleta(b, deB, 2);
        String nomeDeA = jdbc.queryForObject("SELECT nome FROM corona.produtos WHERE id = ?", String.class, deA.id());
        String nomeDeB = jdbc.queryForObject("SELECT nome FROM corona.produtos WHERE id = ?", String.class, deB.id());
        String base = "/api/vendedor/maletas/" + maletaDeA + "/pecas/busca?texto=";

        // Pelo nome (parte dele, sem diferenciar maiúsculas), pelo código parcial e pela referência.
        JsonNode porNome = corpoDe(ler(base + nomeDeA.substring(2, 9).toUpperCase(), como(a)).andExpect(status().isOk()));
        assertThat(porNome).hasSize(1);
        assertThat(porNome.get(0).get("produto").get("id").asString()).isEqualTo(deA.id().toString());
        assertThat(porNome.get(0).get("saldo").asInt()).isEqualTo(2);
        assertThat(porNome.get(0).get("produto").has("fotoHash")).isTrue();
        assertThat(corpoDe(ler(base + deA.codigo().substring(0, 5), como(a)))).hasSize(1);
        // A peça de outra maleta (do mesmo ou de outro vendedor) nunca aparece, nem pelo nome exato nem pelo código.
        assertThat(corpoDe(ler(base + nomeDeB, como(a)))).isEmpty();
        assertThat(corpoDe(ler(base + deB.codigo(), como(a)))).isEmpty();
        assertThat(corpoDe(ler(base + jdbc.queryForObject("SELECT nome FROM corona.produtos WHERE id = ?", String.class, fora.id()), como(a)))).isEmpty();
        // Maleta de outro vendedor: 404 sem dizer de quem é. Sem login de vendedor: recusado.
        ler("/api/vendedor/maletas/" + maletaDeB + "/pecas/busca?texto=" + nomeDeB, como(a)).andExpect(status().isNotFound());
        ler(base + nomeDeA, dona()).andExpect(status().isForbidden());
        // Texto vazio não lista a maleta toda: lista vazia. Curinga digitado vale ao pé da letra.
        assertThat(corpoDe(ler(base, como(a)))).isEmpty();
        assertThat(corpoDe(ler(base + "%25", como(a)))).isEmpty();
        // Sem custo nem estoque da loja.
        assertThat(porNome.toString().toLowerCase()).doesNotContain("custo").doesNotContain("estoqueatual").doesNotContain("emmaletas");
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

        // A dona vê o resultado (e o vendedor não usa a rota da dona).
        JsonNode daDona = corpoDe(ler(REMESSAS + "/" + maleta + "/conferencia", dona()).andExpect(status().isOk()));
        assertThat(daDona.get("divergencias").asInt()).isEqualTo(2);
        assertThat(daDona.get("itens").size()).isEqualTo(2);
        ler(REMESSAS + "/" + maleta + "/conferencia", quem).andExpect(status().isForbidden());

        // A maleta conferida continua aparecendo e a dona ainda consegue receber a devolução dela.
        assertThat(corpoDe(ler("/api/vendedor/maletas", quem).andExpect(status().isOk())).get(0).get("status").asString())
                .isEqualTo("CONFERIDA");
        postar(REMESSAS + "/" + maleta + "/devolucoes", dona(),
                mapa("itens", List.of(mapa("id", UUID.randomUUID(), "produtoId", anel.id(), "quantidade", 1))))
                .andExpect(status().isCreated());
    }

    @Test
    void aDonaSoVeAConferenciaDepoisQueOVendedorConferiu() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        Peca anel = peca("30.00", 10);
        UUID maleta = maleta(a, anel, 2);
        ler(REMESSAS + "/" + maleta + "/conferencia", dona()).andExpect(status().isNotFound());
        ler("/api/vendedor/maletas/" + maleta + "/conferencia", como(a)).andExpect(status().isNotFound());
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

    // A troca de computador (e as maletas passando para o novo) está em aparelhos.AparelhosTest (tarefa 5.3).

    // --- clientes ---

    @Test
    void cadaRepresentanteVeSoOsSeusClientes() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        RequestPostProcessor quemA = como(a);
        UUID id = UUID.randomUUID();
        String cpf = CpfsDeTeste.novo();
        String nome = "Maria da Silva " + UUID.randomUUID().toString().substring(0, 6);
        // Telefone e WhatsApp únicos deste teste: outras classes gravam clientes no mesmo banco, e um número fixo coincidia.
        String fone = String.valueOf(10000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 90000000L));
        String zap = String.valueOf(10000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 90000000L));
        String telefone = "(11) 9" + fone.substring(0, 4) + "-" + fone.substring(4);
        Map<String, Object> pedido = mapa("id", id, "nome", nome, "telefone", telefone, "cpf", CpfsDeTeste.comMascara(cpf),
                "whatsapp", "(11) 9" + zap.substring(0, 4) + "-" + zap.substring(4), "observacoes", "Só paga em dinheiro", "dataNascimento", "1990-05-20");

        JsonNode criado = corpoDe(postar("/api/vendedor/clientes", quemA, pedido).andExpect(status().isCreated()));
        assertThat(criado.get("cpf").asString()).isEqualTo(cpf);
        assertThat(criado.get("meu").asBoolean()).isTrue();
        postar("/api/vendedor/clientes", quemA, pedido).andExpect(status().isOk());
        postar("/api/vendedor/clientes", quemA, mapa("id", id, "nome", "Outro nome")).andExpect(status().isConflict());
        // O mesmo id vindo de outro vendedor também é 409, sem dizer de quem é o cliente.
        postar("/api/vendedor/clientes", como(b), pedido).andExpect(status().isConflict());
        postar("/api/vendedor/clientes", quemA, mapa("nome", "Com CPF errado", "cpf", "111.111.111-11")).andExpect(status().isBadRequest());
        postar("/api/vendedor/clientes", quemA, mapa("nome", "  ")).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT vendedor_responsavel_id FROM corona.clientes WHERE id = ?", UUID.class, id))
                .isEqualTo(a.vendedorId());
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=maria da silva", quemA)).size()).isEqualTo(1);
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=9" + fone, quemA)).size()).isEqualTo(1);
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=" + cpf.substring(0, 8), quemA)).size()).isEqualTo(1);
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=maria_da silva", quemA)).size()).isZero(); // curinga digitado vale ao pé da letra: se "_" fosse curinga, acharia "Maria da Silva". Sem dígito no texto: a busca por telefone usa só os dígitos e "%25" virava "25", que acha qualquer telefone com 25 de outra classe
        ler("/api/vendedor/clientes", dona()).andExpect(status().isForbidden());
        postar("/api/vendedor/clientes", dona(), pedido).andExpect(status().isForbidden());

        // Cada representante vê SÓ a sua lista (ADR-0016, item 4; §23.5): o outro, B, não acha o cliente de A por nome, telefone,
        // WhatsApp nem CPF, e o cliente de A é 404 para B pelo id (sem dizer que existe).
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=maria da silva", como(b)).andExpect(status().isOk()))).isEmpty();
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=9" + zap, como(b)))).isEmpty();
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=9" + fone, como(b)))).isEmpty();
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=" + cpf.substring(0, 8), como(b)))).isEmpty();
        ler("/api/vendedor/clientes/" + id, como(b)).andExpect(status().isNotFound());
        JsonNode verParaA = corpoDe(ler("/api/vendedor/clientes/" + id, quemA).andExpect(status().isOk()));
        assertThat(verParaA.get("observacoes").asString()).isEqualTo("Só paga em dinheiro");
        assertThat(verParaA.get("dataNascimento").asString()).isEqualTo("1990-05-20");
        // Cliente inativo deixa de aparecer na lista, mas ainda se vê pelo id (histórico), só para o dono.
        jdbc.update("UPDATE corona.clientes SET ativo = false WHERE id = ?", id);
        ler("/api/vendedor/clientes/" + id, quemA).andExpect(status().isOk());
        assertThat(corpoDe(ler("/api/vendedor/clientes?busca=maria da silva", quemA))).isEmpty();
    }

    @Test
    void cpfRepetidoEBarradoComOClienteExistenteInclusiveDeOutroVendedor() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        String cpf = CpfsDeTeste.novo();
        UUID idDeA = UUID.randomUUID();
        postar("/api/vendedor/clientes", como(a), mapa("id", idDeA, "nome", "Cliente de A", "cpf", cpf, "telefone", "(67) 3333-0000"))
                .andExpect(status().isCreated());
        JsonNode erro = corpoDe(postar("/api/vendedor/clientes", como(b), mapa("id", UUID.randomUUID(), "nome", "Outra pessoa", "cpf", CpfsDeTeste.comMascara(cpf)))
                .andExpect(status().isConflict()));
        assertThat(erro.get("codigo").asString()).isEqualTo("CLIENTE_CPF_JA_CADASTRADO");
        assertThat(erro.get("detail").asString()).contains("Este cliente já está cadastrado").contains("Cliente de A");
        assertThat(erro.get("clienteExistente").get("id").asString()).isEqualTo(idDeA.toString());
        assertThat(erro.get("clienteExistente").get("nome").asString()).isEqualTo("Cliente de A");
        assertThat(erro.get("clienteExistente").get("telefone").asString()).isEqualTo("(67) 3333-0000");
        assertThat(erro.get("clienteExistente").has("observacoes")).isFalse();
        // Nada foi gravado para B, e o reenvio idêntico do cliente de A continua 200.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.clientes WHERE cpf = ?", Integer.class, cpf)).isEqualTo(1);
        postar("/api/vendedor/clientes", como(a), mapa("id", idDeA, "nome", "Cliente de A", "cpf", cpf, "telefone", "(67) 3333-0000"))
                .andExpect(status().isOk());
        // Dois cadastros do mesmo CPF ao mesmo tempo: um só entra.
        String cpfNovo = CpfsDeTeste.novo();
        java.util.List<Integer> codigos = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread t1 = new Thread(() -> codigos.add(statusDe(() -> postar("/api/vendedor/clientes", como(a),
                mapa("id", UUID.randomUUID(), "nome", "Paralelo 1", "cpf", cpfNovo)))));
        Thread t2 = new Thread(() -> codigos.add(statusDe(() -> postar("/api/vendedor/clientes", como(b),
                mapa("id", UUID.randomUUID(), "nome", "Paralelo 2", "cpf", cpfNovo)))));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.clientes WHERE cpf = ?", Integer.class, cpfNovo)).isEqualTo(1);
    }

    private int statusDe(java.util.concurrent.Callable<org.springframework.test.web.servlet.ResultActions> chamada) {
        try {
            return chamada.call().andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return -1;
        }
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

    @Test
    void fotoDaMinhaMaletaTrazOSha256DoConteudoBaixado() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        Peca peca = peca("50.00", 5);
        String hash = corpoDe(mvc.perform(multipart("/api/dona/produtos/" + peca.id() + "/fotos")
                .file(new MockMultipartFile("arquivo", "foto.jpg", "image/jpeg", ImagensDeTeste.jpeg(900, 600, 91)))
                .with(dona())).andExpect(status().isCreated())).get("hash").asString();
        maleta(a, peca, 2);

        for (String tamanho : List.of("miniatura", "media", "grande")) {
            var resposta = ler("/api/vendedor/fotos/" + hash + "/" + tamanho, como(a)).andExpect(status().isOk())
                    .andExpect(header().exists("X-Conteudo-Sha256")).andReturn().getResponse();
            // O app confere este valor com os bytes que recebeu antes de guardar o arquivo (tarefa 5.9).
            assertThat(resposta.getHeader("X-Conteudo-Sha256")).hasSize(64)
                    .isEqualTo(sha256(resposta.getContentAsByteArray()));
            assertThat(resposta.getHeader(HttpHeaders.ETAG)).contains(hash);
            // Com o mesmo ETag não há o que baixar de novo, e então também não há o que conferir.
            mvc.perform(get("/api/vendedor/fotos/" + hash + "/" + tamanho).with(como(a))
                    .header(HttpHeaders.IF_NONE_MATCH, resposta.getHeader(HttpHeaders.ETAG)))
                    .andExpect(status().isNotModified()).andExpect(header().doesNotExist("X-Conteudo-Sha256"));
            // Outro vendedor, sem essa peça na maleta, não recebe nem o arquivo nem o valor.
            ler("/api/vendedor/fotos/" + hash + "/" + tamanho, como(b)).andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("X-Conteudo-Sha256"));
        }
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
        // Vender só depois de conferir (tarefa 5.3).
        postar("/api/vendedor/maletas/" + maleta + "/conferencia", quem,
                mapa("id", UUID.randomUUID(), "itens", List.of(contada(anel, 10), contada(colar, 10)))).andExpect(status().isCreated());

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

    /**
     * CLAUDE.md e ADR-0006 (itens 7 e 10): Dólar e Guarani pela cotação do dia de cada lançamento, e o total em outra moeda
     * é a soma dos lançamentos já convertidos ("a de hoje" é proibida). A venda usa a cotação gravada nela (ADR-0011, item
     * 10); o pagamento, a do dia em que foi pago. As vendas são de 1990, longe de qualquer cotação de outro teste.
     */
    @Test
    void relatoriosConvertemCadaLancamentoPelaCotacaoDoSeuDia() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        Peca anel = peca("100.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        // 05/01/1990: US$ 5,00 e Gs. 0,001 real. 06/01/1990: US$ 4,00 e Gs. 0,002 real.
        UUID dia5 = cotacao("1990-01-05", "5.00000000", "0.00100000");
        UUID dia6 = cotacao("1990-01-06", "4.00000000", "0.00200000");
        vendaAntiga(v, maleta, cliente, anel, "1990-01-05T15:00:00Z", dia5, "100.00", "30.00");
        vendaAntiga(v, maleta, cliente, anel, "1990-01-06T15:00:00Z", dia6, "100.00", "40.00");
        RequestPostProcessor quem = como(v);

        // Vendas: 100 ÷ 5 = 20,00 e 100 ÷ 4 = 25,00, US$ 45,00; Gs. 100.000 + 50.000 = 150.000. A conversão do total
        // (R$ 200 por uma cotação só) daria outro número.
        JsonNode porCliente = corpoDe(ler("/api/vendedor/relatorios/vendas", quem).andExpect(status().isOk()));
        JsonNode linha = porCliente.get("linhas").get(0);
        assertThat(dec(linha.get("total"))).isEqualByComparingTo("200.00");
        assertThat(dec(linha.get("totalEmDolar"))).isEqualByComparingTo("45.00");
        assertThat(dec(linha.get("totalEmGuarani"))).isEqualByComparingTo("150000");
        assertThat(dec(linha.get("descontoEmDolar"))).isEqualByComparingTo("0.00");
        assertThat(dec(porCliente.get("totais").get("totalEmDolar"))).isEqualByComparingTo("45.00");
        assertThat(dec(porCliente.get("totais").get("totalEmGuarani"))).isEqualByComparingTo("150000");

        JsonNode porDia = corpoDe(ler("/api/vendedor/relatorios/vendas?agrupar=dia", quem).andExpect(status().isOk()));
        assertThat(dec(porDia.get("linhas").get(0).get("totalEmDolar"))).isEqualByComparingTo("20.00");
        assertThat(dec(porDia.get("linhas").get(1).get("totalEmDolar"))).isEqualByComparingTo("25.00");
        JsonNode porPeca = corpoDe(ler("/api/vendedor/relatorios/vendas?agrupar=peca", quem).andExpect(status().isOk()));
        assertThat(dec(porPeca.get("totais").get("totalEmDolar"))).isEqualByComparingTo("45.00");

        // Recebimentos: 30 ÷ 5 = 6,00 e 40 ÷ 4 = 10,00, US$ 16,00; Gs. 30.000 + 20.000 = 50.000.
        JsonNode receb = corpoDe(ler("/api/vendedor/relatorios/recebimentos", quem).andExpect(status().isOk()));
        assertThat(dec(receb.get("linhas").get(0).get("totalEmDolar"))).isEqualByComparingTo("16.00");
        assertThat(dec(receb.get("totais").get("totalEmDolar"))).isEqualByComparingTo("16.00");
        assertThat(dec(receb.get("totais").get("totalEmGuarani"))).isEqualByComparingTo("50000");

        // A receber: vendido US$ 45,00 menos pago US$ 16,00 = US$ 29,00 (R$ 130,00).
        JsonNode devedores = corpoDe(ler("/api/vendedor/relatorios/a-receber", quem).andExpect(status().isOk()));
        JsonNode devedor = devedores.get("clientes").get(0);
        assertThat(dec(devedor.get("aReceber"))).isEqualByComparingTo("130.00");
        assertThat(dec(devedor.get("vendidoEmDolar"))).isEqualByComparingTo("45.00");
        assertThat(dec(devedor.get("pagoEmDolar"))).isEqualByComparingTo("16.00");
        assertThat(dec(devedor.get("aReceberEmDolar"))).isEqualByComparingTo("29.00");
        assertThat(dec(devedor.get("aReceberEmGuarani"))).isEqualByComparingTo("100000");
        assertThat(dec(devedores.get("totais").get("totalEmDolar"))).isEqualByComparingTo("29.00");
    }

    private UUID cotacao(String dia, String usd, String pyg) {
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
                VALUES (uuidv7(), ?::date, ?::numeric, ?::numeric, 1, 1, ?::numeric, ?::numeric, 1, 1, now(), 'teste')""",
                dia, usd, usd, pyg, pyg);
        return jdbc.queryForObject("SELECT id FROM corona.cotacoes WHERE data_cotacao = ?::date", UUID.class, dia);
    }

    /** Venda de outro dia, gravada direto (o gatilho só impede alterar), com um item e um pagamento no mesmo instante. */
    private void vendaAntiga(UsuarioDeTeste v, UUID maleta, UUID cliente, Peca peca, String quando, UUID cotacao,
            String total, String pago) {
        UUID venda = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.vendas (id, vendedor_id, cliente_id, remessa_id, subtotal, desconto, total, cotacao_id, vendida_em)
                VALUES (?, ?, ?, ?, ?::numeric, 0, ?::numeric, ?, ?::timestamptz)""",
                venda, v.vendedorId(), cliente, maleta, total, total, cotacao, quando);
        jdbc.update("""
                INSERT INTO corona.itens_venda (id, venda_id, produto_id, quantidade, preco_unitario, desconto)
                VALUES (uuidv7(), ?, ?, 1, ?::numeric, 0)""", venda, peca.id(), total);
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em)
                VALUES (uuidv7(), ?, 'PAGAMENTO', 'DINHEIRO', ?::numeric, ?::timestamptz)""", venda, pago, quando);
    }
}
