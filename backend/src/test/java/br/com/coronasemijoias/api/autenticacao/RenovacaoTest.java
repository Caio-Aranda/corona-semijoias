package br.com.coronasemijoias.api.autenticacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.TokensDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sessão longa (ADR-0004, item 2): renovação com rotação, janela de tolerância, cada caso de revogação,
 * aparelho, validade de 30 dias e saída. As horas andam com o {@link RelogioDeTeste}.
 */
class RenovacaoTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RelogioDeTeste relogio;

    /** O que o login (ou a renovação) devolve: token de acesso, de renovação e a família. */
    record Sessao(String acesso, String renovacao, UUID sid, JsonNode resposta) {
    }

    @AfterEach
    void reiniciarORelogio() {
        relogio.reiniciar();
    }

    private JsonNode corpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Sessao sessao(JsonNode resposta) throws Exception {
        String acesso = resposta.get("tokenDeAcesso").asString();
        UUID sid = UUID.fromString(json.readTree(TokensDeTeste.parte(acesso, 1)).get("sid").asString());
        return new Sessao(acesso, resposta.get("tokenDeRenovacao").asString(), sid, resposta);
    }

    /** O computador do último login do teste (cada vendedor tem o seu; tarefa 5.3). */
    private UUID aparelho = UsuariosDeTeste.APARELHO;

    private Sessao entrar(UsuarioDeTeste usuario) throws Exception {
        if ("VENDEDOR".equals(usuario.perfil())) {
            aparelho = usuarios.aparelhoDe(usuario);
        }
        return sessao(corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(usuario.login(), usuario.senha()))))
                .andExpect(status().isOk())));
    }

    private ResultActions renovar(String tokenDeRenovacao, UUID aparelho) throws Exception {
        Map<String, Object> pedido = new HashMap<>();
        pedido.put("tokenDeRenovacao", tokenDeRenovacao);
        pedido.put("dispositivoId", aparelho);
        return mvc.perform(post("/api/auth/renovar").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(pedido)));
    }

    private Sessao renovou(String tokenDeRenovacao, UUID aparelho) throws Exception {
        return sessao(corpo(renovar(tokenDeRenovacao, aparelho).andExpect(status().isOk())));
    }

    private void recusada(String tokenDeRenovacao, UUID aparelho) throws Exception {
        renovar(tokenDeRenovacao, aparelho).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"))
                .andExpect(jsonPath("$.title").value("Sua sessão terminou."));
    }

    private ResultActions eu(String acesso) throws Exception {
        return mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(acesso)));
    }

    private String motivo(UUID sid) {
        return jdbc.queryForObject("SELECT motivo_da_revogacao FROM corona.sessoes WHERE id = ?", String.class, sid);
    }

    private long tokensDaFamilia(UUID sid) {
        return jdbc.queryForObject("SELECT count(*) FROM corona.tokens_de_renovacao WHERE sessao_id = ?", Long.class, sid);
    }

    private void familiaRevogada(Sessao sessao, String motivo, String... renovacoes) throws Exception {
        assertThat(motivo(sessao.sid())).isEqualTo(motivo);
        // A conferência de cada requisição recusa o token de acesso da família revogada.
        eu(sessao.acesso()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
        for (String renovacao : renovacoes) {
            recusada(renovacao, aparelho);
        }
    }

    // --- Renovação normal ---

    @Test
    void renovarTrocaOTokenEDevolveUmTokenDeAcessoDaMesmaFamilia() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        Sessao login = entrar(vendedor);
        relogio.avancar(Duration.ofMinutes(20)); // o token de acesso já venceu

        ResultActions resposta = renovar(login.renovacao(), aparelho).andExpect(status().isOk());
        assertThat(resposta.andReturn().getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        Sessao renovada = sessao(corpo(resposta));

        assertThat(renovada.renovacao()).hasSize(43).isNotEqualTo(login.renovacao());
        assertThat(renovada.sid()).isEqualTo(login.sid());
        JsonNode conteudo = json.readTree(TokensDeTeste.parte(renovada.acesso(), 1));
        assertThat(conteudo.get("dispositivo_id").asString()).isEqualTo(aparelho.toString());
        assertThat(conteudo.get("vendedor_id").asString()).isEqualTo(vendedor.vendedorId().toString());
        Instant hora = Instant.parse(renovada.resposta().get("horaDoServidor").asString());
        assertThat(hora).isAfter(Instant.now().plus(Duration.ofMinutes(19)));
        assertThat(renovada.resposta().get("tempoMaximoOfflineDias").asInt()).isEqualTo(10);
        assertThat(Instant.parse(renovada.resposta().get("tokenDeRenovacaoExpiraEm").asString()))
                .isBetween(hora.plus(Duration.ofDays(30)), hora.plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(renovada.resposta().get("trocaDeSenhaObrigatoria").asBoolean()).isFalse();
        eu(renovada.acesso()).andExpect(status().isOk());

        // No banco, só o SHA-256 do valor entregue.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.tokens_de_renovacao WHERE hash = sha256(convert_to(?, 'UTF8'))",
                Long.class, renovada.renovacao())).isEqualTo(1);
        assertThat(tokensDaFamilia(login.sid())).isEqualTo(2);
    }

    @Test
    void reapresentacaoDentroDaJanelaDevolveOMesmoSucessorSemCriarOutro() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());
        Sessao primeira = renovou(login.renovacao(), aparelho);

        // A resposta se perdeu; o app volta com o token anterior 1 min 59 s depois.
        relogio.avancar(Duration.ofMinutes(1).plusSeconds(59));
        Sessao reapresentada = renovou(login.renovacao(), aparelho);

        assertThat(reapresentada.renovacao()).isEqualTo(primeira.renovacao());
        assertThat(tokensDaFamilia(login.sid())).isEqualTo(2);
        // O sucessor continua valendo e renova normalmente.
        Sessao seguinte = renovou(reapresentada.renovacao(), aparelho);
        assertThat(seguinte.renovacao()).isNotEqualTo(primeira.renovacao());
        eu(seguinte.acesso()).andExpect(status().isOk());
    }

    // --- Cada caso de revogação ---

    @Test
    void reapresentacaoForaDaJanelaRevogaAFamilia() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());
        Sessao primeira = renovou(login.renovacao(), aparelho);

        relogio.avancar(Duration.ofMinutes(2).plusSeconds(1));
        recusada(login.renovacao(), aparelho);

        familiaRevogada(primeira, "REUSO_FORA_DA_JANELA", primeira.renovacao());
    }

    @Test
    void segundaReapresentacaoRevogaAFamilia() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());
        Sessao primeira = renovou(login.renovacao(), aparelho);
        renovou(login.renovacao(), aparelho);

        recusada(login.renovacao(), aparelho);

        familiaRevogada(primeira, "SEGUNDA_REAPRESENTACAO", primeira.renovacao());
    }

    @Test
    void tokenAnteriorDepoisDeOSucessorSerUsadoRevogaAFamilia() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());
        Sessao primeira = renovou(login.renovacao(), aparelho);
        Sessao segunda = renovou(primeira.renovacao(), aparelho);

        // Ainda dentro da janela, mas o sucessor já foi usado: alguém tem uma cópia.
        recusada(login.renovacao(), aparelho);

        familiaRevogada(segunda, "ANTERIOR_DEPOIS_DO_SUCESSOR", segunda.renovacao());
    }

    @Test
    void outroAparelhoEhRecusadoERevogaAFamilia() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());

        recusada(login.renovacao(), UUID.randomUUID());

        familiaRevogada(login, "OUTRO_APARELHO", login.renovacao());
    }

    @Test
    void aparelhoAusenteContaComoOutroAparelho() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());
        recusada(login.renovacao(), null);
        familiaRevogada(login, "OUTRO_APARELHO", login.renovacao());
    }

    @Test
    void naDonaOAparelhoFicaVazio() throws Exception {
        Sessao login = entrar(usuarios.criarDona());
        Sessao renovada = renovou(login.renovacao(), null);
        assertThat(json.readTree(TokensDeTeste.parte(renovada.acesso(), 1)).has("dispositivo_id")).isFalse();
        // Com um aparelho, é outro aparelho.
        recusada(renovada.renovacao(), UUID.randomUUID());
        assertThat(motivo(login.sid())).isEqualTo("OUTRO_APARELHO");
    }

    @Test
    void familiaRevogadaNaoRenovaNemPorOutroCaminho() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());
        recusada(login.renovacao(), UUID.randomUUID());
        // Nem com o aparelho certo: a família já caiu.
        recusada(login.renovacao(), aparelho);
    }

    @Test
    void tokenQueNaoExisteEhSessaoTerminada() throws Exception {
        recusada("nao-existe-" + UUID.randomUUID(), aparelho);
        renovar("", aparelho).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/renovar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    // --- Validade de 30 dias, contada de cada renovação ---

    @Test
    void cadaRenovacaoDaMais30DiasEDepoisDelesOTokenVence() throws Exception {
        Sessao login = entrar(usuarios.criarVendedor());

        relogio.avancar(Duration.ofDays(29));
        Sessao renovada = renovou(login.renovacao(), aparelho);
        relogio.avancar(Duration.ofDays(29));
        renovada = renovou(renovada.renovacao(), aparelho);

        relogio.avancar(Duration.ofDays(30).plusSeconds(1));
        recusada(renovada.renovacao(), aparelho);
        // Vencer não é suspeita: a família não é revogada, só não renova mais.
        assertThat(motivo(login.sid())).isNull();
    }

    @Test
    void usuarioDesativadoRecebeAcessoDesativado() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        Sessao login = entrar(vendedor);
        usuarios.desativar(vendedor);

        renovar(login.renovacao(), aparelho).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"))
                .andExpect(jsonPath("$.title").value("Seu acesso está inativo."));
    }

    // --- Saída ---

    @Test
    void sairDerrubaOTokenDeAcessoEODeRenovacaoDaquelaFamiliaSo() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        Sessao estaSessao = entrar(vendedor);
        Sessao outraSessao = entrar(vendedor);

        mvc.perform(post("/api/auth/sair").with(UsuariosDeTeste.comToken(estaSessao.acesso())))
                .andExpect(status().isNoContent());

        familiaRevogada(estaSessao, "SAIDA", estaSessao.renovacao());
        // A outra família da mesma pessoa continua.
        eu(outraSessao.acesso()).andExpect(status().isOk());
        renovou(outraSessao.renovacao(), aparelho);
    }

    @Test
    void sairSemTokenEhSessaoTerminada() throws Exception {
        mvc.perform(post("/api/auth/sair")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }

    @Test
    void oTokenRestritoTambemSai() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(vendedor, null);
        JsonNode resposta = corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(vendedor.login(), vendedor.senha()))))
                .andExpect(status().isOk()));
        String restrito = resposta.get("tokenDeAcesso").asString();

        mvc.perform(post("/api/auth/sair").with(UsuariosDeTeste.comToken(restrito))).andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/trocar-senha").with(UsuariosDeTeste.comToken(restrito))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"senhaNova\":\"Senha-Nova-123\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }
}
