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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Senha temporária, token restrito, troca obrigatória, vencimento e revogação das sessões
 * (ADR-0004, itens 2 e 6). As datas andam com o {@link RelogioDeTeste}.
 */
class SenhaTemporariaTest extends TesteDeIntegracao {

    private static final String TROCA = "Crie sua senha nova. A senha que você recebeu é temporária. "
            + "Para continuar, crie uma senha nova que só você saiba.";

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

    /** Usuário criado pela API da dona: login, senha temporária e id. */
    record Criado(String id, String login, String senha, Instant venceEm) {
    }

    @AfterEach
    void reiniciarORelogio() {
        relogio.reiniciar();
    }

    private JsonNode corpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Criado criarPelaDona(String perfil) throws Exception {
        String login = "temp." + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> pedido = new LinkedHashMap<>();
        pedido.put("nome", "Pessoa " + login);
        pedido.put("login", login);
        pedido.put("perfil", perfil);
        JsonNode criado = corpo(mvc.perform(post("/api/dona/usuarios").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(pedido)))
                .andExpect(status().isCreated()));
        return new Criado(criado.get("id").asString(), login, criado.get("senhaTemporaria").asString(),
                Instant.parse(criado.get("senhaTemporariaVenceEm").asString()));
    }

    private ResultActions entrar(String login, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login", login, "senha", senha))));
    }

    private String tokenRestritoDe(Criado criado) throws Exception {
        return corpo(entrar(criado.login(), criado.senha()).andExpect(status().isOk()))
                .get("tokenDeAcesso").asString();
    }

    private ResultActions trocarSenha(String token, String senhaNova) throws Exception {
        return mvc.perform(post("/api/auth/trocar-senha").with(UsuariosDeTeste.comToken(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("senhaNova", senhaNova))));
    }

    private void recusadoComo(ResultActions resultado, int status, String codigo) throws Exception {
        resultado.andExpect(status().is(status)).andExpect(jsonPath("$.codigo").value(codigo));
    }

    // --- Login com senha temporária ---

    @Test
    void loginComSenhaTemporariaDevolveSoOTokenRestrito() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");

        JsonNode resposta = corpo(entrar(criado.login(), criado.senha()).andExpect(status().isOk()));

        assertThat(resposta.get("trocaDeSenhaObrigatoria").asBoolean()).isTrue();
        assertThat(resposta.get("validadeEmSegundos").asLong()).isEqualTo(900);
        String token = resposta.get("tokenDeAcesso").asString();
        JsonNode conteudo = json.readTree(TokensDeTeste.parte(token, 1));
        assertThat(conteudo.get("escopo").asString()).isEqualTo("TROCA_SENHA");
        assertThat(conteudo.has("sid")).isFalse();
        assertThat(conteudo.get("exp").asLong() - conteudo.get("iat").asLong()).isEqualTo(900);
        assertThat(resposta.has("tokenDeRenovacao")).isFalse();
    }

    @Test
    void tokenRestritoNaoAbreNenhumaOutraRota() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        String token = tokenRestritoDe(criado);

        for (var rota : new Object[][] {
                {"GET", "/api/auth/eu"}, {"GET", "/api/vendedor/teste/dinheiro"},
                {"POST", "/api/dona/usuarios"}, {"GET", "/api/dona/teste/tempo"}, {"GET", "/rota/que/nao/existe"}}) {
            var requisicao = rota[0].equals("GET") ? get((String) rota[1]) : post((String) rota[1]);
            mvc.perform(requisicao.with(UsuariosDeTeste.comToken(token)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("TROCA_DE_SENHA_OBRIGATORIA"))
                    .andExpect(jsonPath("$.title").value("Crie sua senha nova."))
                    .andExpect(jsonPath("$.detail").value(TROCA));
        }
    }

    @Test
    void senhaErradaContinuaSendoLoginInvalido() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        recusadoComo(entrar(criado.login(), criado.senha() + "x"), 401, "LOGIN_INVALIDO");
    }

    // --- Troca de senha ---

    @Test
    void trocaDeSenhaLiberaOTokenNormal() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        String restrito = tokenRestritoDe(criado);

        JsonNode resposta = corpo(trocarSenha(restrito, "Minha-Senha-Nova-1").andExpect(status().isOk()));

        assertThat(resposta.get("trocaDeSenhaObrigatoria").asBoolean()).isFalse();
        String normal = resposta.get("tokenDeAcesso").asString();
        assertThat(json.readTree(TokensDeTeste.parte(normal, 1)).has("escopo")).isFalse();
        // O token normal vale em toda rota.
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(normal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.login").value(criado.login()));

        // A marca saiu e a hora da troca foi gravada.
        Map<String, Object> linha = jdbc.queryForMap("""
                SELECT troca_de_senha_obrigatoria, senha_temporaria_vence_em, credencial_alterada_em
                  FROM corona.usuarios WHERE id = ?""", UUID.fromString(criado.id()));
        assertThat(linha.get("troca_de_senha_obrigatoria")).isEqualTo(false);
        assertThat(linha.get("senha_temporaria_vence_em")).isNull();
        assertThat(linha.get("credencial_alterada_em")).isNotNull();

        // Entra com a senha nova (token normal) e a temporária não entra mais.
        JsonNode novoLogin = corpo(entrar(criado.login(), "Minha-Senha-Nova-1").andExpect(status().isOk()));
        assertThat(novoLogin.get("trocaDeSenhaObrigatoria").asBoolean()).isFalse();
        recusadoComo(entrar(criado.login(), criado.senha()), 401, "LOGIN_INVALIDO");
    }

    @Test
    void oTokenRestritoUsadoNaTrocaNaoServeDeNovo() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        String restrito = tokenRestritoDe(criado);
        trocarSenha(restrito, "Minha-Senha-Nova-1").andExpect(status().isOk());

        recusadoComo(trocarSenha(restrito, "Outra-Senha-Nova-2"), 401, "SESSAO_TERMINADA");
    }

    @Test
    void cadaRegraDaSenhaNovaEhRecusadaComOCodigoCerto() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        String restrito = tokenRestritoDe(criado);

        // Fraca: curta, só letras, só números.
        for (String fraca : new String[] {"abc123", "somente-letras", "1234567890", "        "}) {
            trocarSenha(restrito, fraca).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("SENHA_FRACA"))
                    .andExpect(jsonPath("$.title").value("A senha nova ainda não está boa."))
                    .andExpect(jsonPath("$.detail").value("A senha nova ainda não está boa. "
                            + "Ela precisa ter pelo menos 8 caracteres, com letras e números."));
        }
        // Igual à temporária.
        trocarSenha(restrito, criado.senha()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SENHA_IGUAL_A_TEMPORARIA"))
                .andExpect(jsonPath("$.detail").value(
                        "A senha nova precisa ser diferente da temporária. Escolha outra."));
        // Mais de 72 bytes.
        trocarSenha(restrito, "a1" + "x".repeat(71)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SENHA_LONGA_DEMAIS"))
                .andExpect(jsonPath("$.title").value("Esta senha é longa demais."))
                .andExpect(jsonPath("$.detail").value("Esta senha é longa demais. "
                        + "Escolha uma senha mais curta, com no máximo 36 caracteres."));
        // Campo ausente.
        mvc.perform(post("/api/auth/trocar-senha").with(UsuariosDeTeste.comToken(restrito))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));

        // Nada disso trocou a senha: a temporária ainda entra.
        entrar(criado.login(), criado.senha()).andExpect(status().isOk());
    }

    @Test
    void senhaComExatamente72BytesEhAceita() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        trocarSenha(tokenRestritoDe(criado), "a1" + "x".repeat(70)).andExpect(status().isOk());
        entrar(criado.login(), "a1" + "x".repeat(70)).andExpect(status().isOk());
    }

    @Test
    void tokenNormalNaoUsaATrocaObrigatoria() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        recusadoComo(trocarSenha(usuarios.token(vendedor), "Senha-Nova-123"), 403, "SEM_PERMISSAO");
    }

    @Test
    void trocaSemTokenEhSessaoTerminada() throws Exception {
        mvc.perform(post("/api/auth/trocar-senha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"senhaNova\":\"Senha-Nova-123\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }

    @Test
    void escopoQueNaoConfereComASituacaoDaSenhaEhRecusado() throws Exception {
        // Token restrito de quem não deve mais trocar a senha.
        UsuarioDeTeste semMarca = usuarios.criarVendedor();
        recusadoComo(trocarSenha(usuarios.tokenRestrito(semMarca), "Senha-Nova-123"), 401, "SESSAO_TERMINADA");
        // Token comum de quem ainda deve trocar a senha.
        UsuarioDeTeste comMarca = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(comMarca, null);
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(usuarios.token(comMarca))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }

    // --- Vencimento ---

    @Test
    void senhaTemporariaVenceNoOitavoDia() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");

        relogio.avancar(Duration.ofDays(7).minusMinutes(5));
        entrar(criado.login(), criado.senha()).andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));

        relogio.avancar(Duration.ofDays(1));
        ResultActions vencida = entrar(criado.login(), criado.senha())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SENHA_TEMPORARIA_VENCIDA"))
                .andExpect(jsonPath("$.title").value("Sua senha temporária venceu."))
                .andExpect(jsonPath("$.detail").value("Sua senha temporária venceu. A senha que você recebeu "
                        + "só vale por alguns dias, e esse prazo acabou."));
        // Sem nenhum token na resposta.
        assertThat(corpo(vencida).has("tokenDeAcesso")).isFalse();
    }

    @Test
    void venceuMasSenhaErradaNaoRevelaNada() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        relogio.avancar(Duration.ofDays(8));
        recusadoComo(entrar(criado.login(), criado.senha() + "x"), 401, "LOGIN_INVALIDO");
        recusadoComo(entrar("nao.existe." + UUID.randomUUID(), criado.senha()), 401, "LOGIN_INVALIDO");
    }

    @Test
    void tokenRestritoVencidoNaoTrocaSenha() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        String restrito = tokenRestritoDe(criado);
        // 20 minutos depois o token restrito (15 min) venceu; e o prazo da senha ainda não.
        relogio.avancar(Duration.ofMinutes(20));
        recusadoComo(trocarSenha(restrito, "Senha-Nova-123"), 401, "SESSAO_TERMINADA");
    }

    @Test
    void tokenRestritoEmitidoNoPrazoTrocaASenhaMesmoSeATemporariaVencerNosMinutosSeguintes() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(vendedor, Instant.now().plus(Duration.ofMinutes(5)));
        String restrito = tokenRestritoDe(new Criado(vendedor.id().toString(), vendedor.login(), vendedor.senha(), null));

        // 10 minutos depois a senha temporária já venceu, mas o token restrito (15 minutos) ainda vale.
        relogio.avancar(Duration.ofMinutes(10));
        recusadoComo(entrar(vendedor.login(), vendedor.senha()), 401, "SENHA_TEMPORARIA_VENCIDA");
        trocarSenha(restrito, "Minha-Senha-Nova-1").andExpect(status().isOk());
    }

    @Test
    void senhaDefinitivaNaoExpira() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        trocarSenha(tokenRestritoDe(criado), "Minha-Senha-Nova-1").andExpect(status().isOk());
        relogio.avancar(Duration.ofDays(400));
        entrar(criado.login(), "Minha-Senha-Nova-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(false));
    }

    @Test
    void senhaSemVencimentoDoPrimeiroAdministradorNuncaVence() throws Exception {
        UsuarioDeTeste admin = usuarios.criarDona();
        usuarios.marcarTrocaObrigatoria(admin, null);
        relogio.avancar(Duration.ofDays(365));
        entrar(admin.login(), admin.senha()).andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));
    }

    @Test
    void primeiroAdministradorDaConfiguracaoEntraDiretoNaTrocaObrigatoria() throws Exception {
        entrar(ADMIN_INICIAL_LOGIN, ADMIN_INICIAL_SENHA).andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));
        Map<String, Object> linha = jdbc.queryForMap("""
                SELECT troca_de_senha_obrigatoria, senha_temporaria_vence_em FROM corona.usuarios WHERE login = ?""",
                ADMIN_INICIAL_LOGIN);
        assertThat(linha.get("troca_de_senha_obrigatoria")).isEqualTo(true);
        assertThat(linha.get("senha_temporaria_vence_em")).isNull();
        // Mesmo bem depois: sem vencimento.
        relogio.avancar(Duration.ofDays(60));
        entrar(ADMIN_INICIAL_LOGIN, ADMIN_INICIAL_SENHA).andExpect(status().isOk());
    }

    // --- Redefinição derruba o que vinha antes ---

    @Test
    void redefinirRenovaOPrazoEInvalidaASenhaEOsTokensAntigos() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        trocarSenha(tokenRestritoDe(criado), "Minha-Senha-Nova-1").andExpect(status().isOk());
        String tokenAntigo = corpo(entrar(criado.login(), "Minha-Senha-Nova-1").andExpect(status().isOk()))
                .get("tokenDeAcesso").asString();
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(tokenAntigo))).andExpect(status().isOk());

        // Dois segundos depois (a hora da troca e o iat têm precisão de segundos), a dona redefine.
        relogio.avancar(Duration.ofSeconds(2));
        JsonNode redefinido = corpo(mvc.perform(post("/api/dona/usuarios/" + criado.id() + "/redefinir-senha")
                .with(usuarios.comoDona())).andExpect(status().isOk()));
        String temporaria = redefinido.get("senhaTemporaria").asString();

        // O token de antes e a senha de antes pararam de valer.
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(tokenAntigo)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
        recusadoComo(entrar(criado.login(), "Minha-Senha-Nova-1"), 401, "LOGIN_INVALIDO");
        // A temporária nova entra, e só na troca obrigatória.
        entrar(criado.login(), temporaria).andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));
    }

    @Test
    void redefinirDepoisDeVencidaDaPrazoNovo() throws Exception {
        Criado criado = criarPelaDona("VENDEDOR");
        relogio.avancar(Duration.ofDays(8));
        entrar(criado.login(), criado.senha()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SENHA_TEMPORARIA_VENCIDA"));

        String nova = corpo(mvc.perform(post("/api/dona/usuarios/" + criado.id() + "/redefinir-senha")
                .with(usuarios.comoDona())).andExpect(status().isOk())).get("senhaTemporaria").asString();

        entrar(criado.login(), nova).andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));
        // A antiga continua sem entrar.
        recusadoComo(entrar(criado.login(), criado.senha()), 401, "LOGIN_INVALIDO");
        // E o novo prazo também é de 7 dias.
        relogio.avancar(Duration.ofDays(8));
        entrar(criado.login(), nova).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SENHA_TEMPORARIA_VENCIDA"));
    }

    @Test
    void tokenDaTrocaDeSenhaNaoEhDerrubadoPelaPropriaTroca() throws Exception {
        // O token normal devolvido pela troca nasce no mesmo segundo da hora da troca e tem de valer.
        Criado criado = criarPelaDona("VENDEDOR");
        String normal = corpo(trocarSenha(tokenRestritoDe(criado), "Minha-Senha-Nova-1").andExpect(status().isOk()))
                .get("tokenDeAcesso").asString();
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(normal))).andExpect(status().isOk());
    }
}
