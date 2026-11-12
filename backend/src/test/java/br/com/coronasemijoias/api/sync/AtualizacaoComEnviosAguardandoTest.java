package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Atualização com envios aguardando (tarefa 5.13; ADR-0013, item 17; dúvidas R4 e R5): o app abaixo da versão mínima
 * ainda envia o que guardou (push, renovação da sessão que já existe e quarentena), mas não recebe dados nem vende pela
 * internet (426); o formato antigo de operação vale por 90 dias depois de publicado o novo, e o vencido é recusado com
 * um motivo claro, nunca perdido. PostgreSQL de verdade. Escritos antes do código.
 */
class AtualizacaoComEnviosAguardandoTest extends TesteDeIntegracao {

    private static final String PUSH = "/api/vendedor/sync/push";
    /** Abaixo da mínima do Corona Vendas nos testes (2.0.1, {@link TesteDeIntegracao#VERSAO_MINIMA_VENDAS}). */
    private static final String VELHA = "2.0.0";
    private static final String EM_DIA = "2.0.1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    ServicoDeEstoque estoque;

    @Autowired
    ServicoDeRemessas remessas;

    @Autowired
    ServicoDeConferencia conferencias;

    @Autowired
    ServicoDeVendas vendas;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    FormatosDeOperacao formatos;

    @AfterEach
    void reiniciar() {
        relogio.reiniciar();
        formatos.reiniciar();
    }

    // --- apoio ---

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], pares[i + 1]);
        }
        return m;
    }

    private record Montado(CenarioDeSync c, UsuarioDeTeste v, UUID peca, UUID maleta, UUID cliente) {
    }

    /** Vendedor com maleta conferida (3 peças de 30,00) e um cliente dele. */
    private Montado montar() {
        CenarioDeSync c = new CenarioDeSync(jdbc, usuarios, estoque, remessas, conferencias, vendas);
        UsuarioDeTeste v = usuarios.criarVendedor();
        usuarios.token(v);
        UUID peca = c.peca(new BigDecimal("30.00"), 10);
        UUID maleta = c.maleta(v, Map.of(peca, 3));
        c.conferir(v, maleta);
        return new Montado(c, v, peca, maleta, c.cliente(v.vendedorId()));
    }

    private static Map<String, Object> venda(UUID id, Montado m, int formato) {
        return mapa("operacaoId", id, "tipo", "VENDA", "formato", formato, "criadoEmLocal", Instant.now().toString(),
                "dados", mapa("id", id, "remessaId", m.maleta(), "clienteId", m.cliente(),
                        "itens", List.of(mapa("produtoId", m.peca(), "quantidade", 1)), "pagamentos", List.of(),
                        "vendidaEm", Instant.now().toString()));
    }

    /** Pedido do Corona Vendas na versão escolhida (o cabeçalho escrito vale mais que o completado pelo teste). */
    private static MockHttpServletRequestBuilder doVendas(MockHttpServletRequestBuilder pedido, String versao) {
        return pedido.header("X-App-Tipo", "vendas").header("X-App-Versao", versao);
    }

    private ResultActions push(String token, String versao, List<?> operacoes) throws Exception {
        return mvc.perform(doVendas(post(PUSH), versao).with(UsuariosDeTeste.comToken(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        mapa("horaLocal", Instant.now().toString(), "aguardandoDepoisDeste", 0, "operacoes", operacoes))));
    }

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private record Sessao(String acesso, String renovacao) {
    }

    private Sessao entrar(UsuarioDeTeste v, String versao) throws Exception {
        JsonNode r = corpo(mvc.perform(doVendas(post("/api/auth/login"), versao).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("login", v.login(), "senha", v.senha(),
                        "dispositivoId", usuarios.aparelhoDe(v))))).andExpect(status().isOk()));
        return new Sessao(r.get("tokenDeAcesso").asString(), r.get("tokenDeRenovacao").asString());
    }

    private ResultActions renovar(UsuarioDeTeste v, String renovacao, String tipo, String versao) throws Exception {
        return mvc.perform(post("/api/auth/renovar").header("X-App-Tipo", tipo).header("X-App-Versao", versao)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        mapa("tokenDeRenovacao", renovacao, "dispositivoId", usuarios.aparelhoDe(v)))));
    }

    // --- push abaixo da versão mínima ---

    @Test
    void appAbaixoDaMinimaEnviaAsVendasGuardadasEOuveQueEstaDesatualizado() throws Exception {
        Montado m = montar();
        String token = usuarios.token(m.v());
        Map<String, Object> a = venda(UUID.randomUUID(), m, 1);
        Map<String, Object> b = venda(UUID.randomUUID(), m, 1);
        push(token, VELHA, List.of(a, b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("ACEITO"))
                .andExpect(jsonPath("$.resultados[1].resultado").value("ACEITO"))
                .andExpect(jsonPath("$.appDesatualizado").value(true))
                .andExpect(jsonPath("$.versaoMinima").value(EM_DIA));
        // Aplicadas pelos mesmos serviços da Fase 4: o saldo da maleta baixou.
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(1);
        // A resposta se perdeu e o app velho manda de novo: nada duplica.
        push(token, VELHA, List.of(a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("JA_RECEBIDO"));
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(1);
    }

    @Test
    void appEmDiaNaoOuveQueEstaDesatualizado() throws Exception {
        Montado m = montar();
        push(usuarios.token(m.v()), EM_DIA, List.of(venda(UUID.randomUUID(), m, 1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("ACEITO"))
                .andExpect(jsonPath("$.appDesatualizado").value(false));
    }

    @Test
    void abaixoDaMinimaPullVendaNovaEOResto426() throws Exception {
        Montado m = montar();
        String token = usuarios.token(m.v());
        mvc.perform(doVendas(get("/api/vendedor/sync/pull"), VELHA).with(UsuariosDeTeste.comToken(token)))
                .andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.codigo").value("APP_DESATUALIZADO"));
        UUID id = UUID.randomUUID();
        mvc.perform(doVendas(post("/api/vendedor/vendas"), VELHA).with(UsuariosDeTeste.comToken(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapa("id", id,
                                "remessaId", m.maleta(), "clienteId", m.cliente(),
                                "itens", List.of(mapa("produtoId", m.peca(), "quantidade", 1))))))
                .andExpect(status().isUpgradeRequired());
        mvc.perform(doVendas(get("/api/auth/eu"), VELHA).with(UsuariosDeTeste.comToken(token)))
                .andExpect(status().isUpgradeRequired());
        mvc.perform(doVendas(post("/api/vendedor/sync/conferencia/diferencas"), VELHA).with(UsuariosDeTeste.comToken(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"tabelas\":[]}")).andExpect(status().isUpgradeRequired());
        // O push pelo GET, com barra no fim ou noutro caminho parecido não passa.
        mvc.perform(doVendas(get(PUSH), VELHA).with(UsuariosDeTeste.comToken(token))).andExpect(status().isUpgradeRequired());
        mvc.perform(doVendas(post(PUSH + "/"), VELHA).with(UsuariosDeTeste.comToken(token))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUpgradeRequired());
        mvc.perform(doVendas(post(PUSH + "x"), VELHA).with(UsuariosDeTeste.comToken(token))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUpgradeRequired());
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(3);
    }

    @Test
    void versaoIlegivelOuDoGestaoAbaixoDaMinimaNaoEnvia() throws Exception {
        Montado m = montar();
        String token = usuarios.token(m.v());
        for (String versao : new String[] {"", "2.0", "velha"}) {
            push(token, versao, List.of(venda(UUID.randomUUID(), m, 1))).andExpect(status().isUpgradeRequired());
        }
        mvc.perform(post(PUSH).header("X-App-Tipo", "gestao").header("X-App-Versao", "1.1.0")
                .with(UsuariosDeTeste.comToken(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUpgradeRequired());
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(3);
    }

    // --- a sessão que já existe continua (ajuste 1 da orquestradora) ---

    @Test
    void appAbaixoDaMinimaComTokenVencidoRenovaEEnvia() throws Exception {
        Montado m = montar();
        // Entrou quando a versão ainda era a mínima; depois a dona subiu a mínima.
        Sessao s = entrar(m.v(), EM_DIA);
        relogio.avancar(Duration.ofMinutes(16));
        push(s.acesso(), VELHA, List.of(venda(UUID.randomUUID(), m, 1))).andExpect(status().isUnauthorized());

        JsonNode nova = corpo(renovar(m.v(), s.renovacao(), "vendas", VELHA).andExpect(status().isOk()));
        String acesso = nova.get("tokenDeAcesso").asString();
        push(acesso, VELHA, List.of(venda(UUID.randomUUID(), m, 1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("ACEITO"))
                .andExpect(jsonPath("$.appDesatualizado").value(true));
        // Pull e venda nova continuam com 426, mesmo com a sessão renovada.
        mvc.perform(doVendas(get("/api/vendedor/sync/pull"), VELHA).with(UsuariosDeTeste.comToken(acesso)))
                .andExpect(status().isUpgradeRequired());
        // Entrar com a senha não: o 426 continua.
        mvc.perform(doVendas(post("/api/auth/login"), VELHA).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mapa("login", m.v().login(), "senha", m.v().senha(),
                                "dispositivoId", usuarios.aparelhoDe(m.v())))))
                .andExpect(status().isUpgradeRequired());
    }

    @Test
    void renovacaoAbaixoDaMinimaSoValeParaOCoronaVendas() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        Sessao s = entrar(v, EM_DIA);
        renovar(v, s.renovacao(), "gestao", "1.1.0").andExpect(status().isUpgradeRequired());
        renovar(v, s.renovacao(), "vendas", "lixo").andExpect(status().isUpgradeRequired());
        // Nada foi gasto: a renovação continua valendo.
        renovar(v, s.renovacao(), "vendas", VELHA).andExpect(status().isOk());
    }

    // --- quarentena (ajuste 2 da orquestradora) ---

    @Test
    void computadorBloqueadoComAppVelhoGuardaAsVendasAntesDeApagar() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        Sessao s = entrar(v, EM_DIA);
        mvc.perform(post("/api/dona/aparelhos/" + usuarios.aparelhoDe(v) + "/bloquear").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Roubado\"}")).andExpect(status().isOk());
        UUID id = UUID.randomUUID();
        Map<String, Object> cliente = mapa("operacaoId", id, "tipo", "CLIENTE_NOVO", "formato", 1,
                "criadoEmLocal", Instant.now().toString(), "dados", mapa("id", id, "nome", "Ana"));
        mvc.perform(doVendas(post("/api/vendedor/sync/quarentena"), VELHA).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mapa("tokenDeRenovacao", s.renovacao(),
                                "dispositivoId", usuarios.aparelhoDe(v), "horaLocal", Instant.now().toString(),
                                "operacoes", List.of(cliente)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("GUARDADO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.envios_em_quarentena WHERE id = ?", Long.class, id))
                .isEqualTo(1);
    }

    // --- formato antigo por 90 dias ---

    @Test
    void formato1DepoisDeExistirO2EhAceitoAte90Dias() throws Exception {
        Montado m = montar();
        formatos.publicar("VENDA", 2, relogio.instant().minus(Duration.ofDays(89)));
        push(usuarios.token(m.v()), VELHA, List.of(venda(UUID.randomUUID(), m, 1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("ACEITO"));
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(2);
    }

    @Test
    void formatoVencidoEhRecusadoComMotivoEFicaParaADonaMasOQueJaEntrouContinuaJaRecebido() throws Exception {
        Montado m = montar();
        String token = usuarios.token(m.v());
        Map<String, Object> entrou = venda(UUID.randomUUID(), m, 1);
        push(token, VELHA, List.of(entrou)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("ACEITO"));

        formatos.publicar("VENDA", 2, relogio.instant().minus(Duration.ofDays(91)));
        UUID vencida = UUID.randomUUID();
        Map<String, Object> opVencida = venda(vencida, m, 1);
        JsonNode r = corpo(push(token, VELHA, List.of(entrou, opVencida))
                .andExpect(status().isOk()));
        // A que já tinha entrado não vira conflito: o servidor ainda sabe ler o formato.
        assertThat(r.get("resultados").get(0).get("resultado").asString()).isEqualTo("JA_RECEBIDO");
        JsonNode recusada = r.get("resultados").get(1);
        assertThat(recusada.get("resultado").asString()).isEqualTo("RECUSADO");
        assertThat(recusada.get("codigo").asString()).isEqualTo("FORMATO_NAO_ACEITO");
        assertThat(recusada.get("mensagem").asString()).isNotBlank();
        // Nada aplicado, mas guardado no registro dos envios (a dona vê em "envios com problema").
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(2);
        Map<String, Object> log = jdbc.queryForMap("SELECT resultado, codigo, formato FROM corona.sync_log WHERE id = ?",
                vencida);
        assertThat(log.get("resultado")).isEqualTo("RECUSADO");
        assertThat(log.get("codigo")).isEqualTo("FORMATO_NAO_ACEITO");
        assertThat(((Number) log.get("formato")).intValue()).isEqualTo(1);
        // Reenviada, continua recusada pelo mesmo motivo (não vira conflito nem some).
        push(token, VELHA, List.of(opVencida)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("RECUSADO"))
                .andExpect(jsonPath("$.resultados[0].codigo").value("FORMATO_NAO_ACEITO"));
    }

    @Test
    void formatoQueNuncaExistiuContinuaPedidoInvalido() throws Exception {
        Montado m = montar();
        push(usuarios.token(m.v()), EM_DIA, List.of(venda(UUID.randomUUID(), m, 7))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("RECUSADO"))
                .andExpect(jsonPath("$.resultados[0].codigo").value("PEDIDO_INVALIDO"));
    }
}
