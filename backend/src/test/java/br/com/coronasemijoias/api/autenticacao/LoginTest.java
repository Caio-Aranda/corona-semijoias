package br.com.coronasemijoias.api.autenticacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.TokensDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class LoginTest extends TesteDeIntegracao {

    private static final String NAO_FOI_POSSIVEL_ENTRAR =
            "Não foi possível entrar. Usuário ou senha incorretos. Confira o que você digitou e tente de novo. "
                    + "Se esqueceu a senha, fale com o suporte do sistema.";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private ResultActions entrar(String login, String senha) throws Exception {
        String corpo = json.writeValueAsString(usuarios.corpoDoLogin(login, senha));
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private JsonNode corpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String corpoTexto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void loginCertoDaDonaDevolveTokenComOConteudoCerto() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        JsonNode resposta = corpo(entrar(dona.login(), dona.senha()).andExpect(status().isOk()));

        assertThat(resposta.get("tipoDoToken").asString()).isEqualTo("Bearer");
        assertThat(resposta.get("validadeEmSegundos").asLong()).isEqualTo(900);
        Instant hora = Instant.parse(resposta.get("horaDoServidor").asString());
        Instant expira = Instant.parse(resposta.get("expiraEm").asString());
        assertThat(expira).isEqualTo(hora.plusSeconds(900));
        assertThat(hora).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));

        String token = resposta.get("tokenDeAcesso").asString();
        JsonNode cabecalho = json.readTree(TokensDeTeste.parte(token, 0));
        assertThat(cabecalho.get("alg").asString()).isEqualTo("HS256");
        assertThat(cabecalho.get("kid").asString()).isEqualTo(ID_CHAVE_ATUAL);

        JsonNode conteudo = json.readTree(TokensDeTeste.parte(token, 1));
        // A dona (Corona Gestão) não tem aparelho; o sid é a família aberta pelo login.
        assertThat(conteudo.propertyNames()).containsExactlyInAnyOrder("sub", "perfil", "sid", "jti", "iat", "exp", "iss");
        assertThat(resposta.get("tokenDeRenovacao").asString()).hasSize(43);
        assertThat(Instant.parse(resposta.get("tokenDeRenovacaoExpiraEm").asString()))
                .isBetween(hora.plus(java.time.Duration.ofDays(30)), hora.plus(java.time.Duration.ofDays(30)).plusSeconds(1));
        assertThat(resposta.get("tempoMaximoOfflineDias").asInt()).isEqualTo(10);
        assertThat(conteudo.get("sub").asString()).isEqualTo(dona.id().toString());
        assertThat(conteudo.get("perfil").asString()).isEqualTo("ADMIN");
        assertThat(conteudo.get("iss").asString()).isEqualTo(EMISSOR);
        assertThat(UUID.fromString(conteudo.get("jti").asString())).isNotNull();
        assertThat(conteudo.get("exp").asLong() - conteudo.get("iat").asLong()).isEqualTo(900);
        assertThat(conteudo.get("iat").asLong()).isEqualTo(hora.getEpochSecond());

        // O token funciona.
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dona.id().toString()));
    }

    @Test
    void tokenDoVendedorLevaOVendedor() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        String token = corpo(entrar(vendedor.login(), vendedor.senha()).andExpect(status().isOk()))
                .get("tokenDeAcesso").asString();
        JsonNode conteudo = json.readTree(TokensDeTeste.parte(token, 1));
        assertThat(conteudo.propertyNames())
                .containsExactlyInAnyOrder("sub", "perfil", "vendedor_id", "sid", "dispositivo_id", "jti", "iat", "exp", "iss");
        assertThat(conteudo.get("perfil").asString()).isEqualTo("VENDEDOR");
        assertThat(conteudo.get("vendedor_id").asString()).isEqualTo(vendedor.vendedorId().toString());
        assertThat(conteudo.get("dispositivo_id").asString()).isEqualTo(UsuariosDeTeste.APARELHO.toString());
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.sessoes WHERE id = ?::uuid", UUID.class,
                conteudo.get("sid").asString())).isEqualTo(UsuariosDeTeste.APARELHO);
    }

    @Test
    void aparelhoTemDeCombinarComOPerfil() throws Exception {
        // Vendedor sem aparelho, ou dona com aparelho: só depois da senha certa, 400 e nada de token.
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        UsuarioDeTeste dona = usuarios.criarDona();
        // O aparelho é exigido pelo aplicativo (vendas), não pelo perfil.
        for (Map.Entry<String, Map<String, Object>> caso : List.of(
                Map.entry("vendas", Map.<String, Object>of("login", vendedor.login(), "senha", vendedor.senha())),
                Map.entry("gestao", Map.<String, Object>of("login", dona.login(), "senha", dona.senha(),
                        "dispositivoId", UUID.randomUUID())))) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .header("X-App-Tipo", caso.getKey())
                            .content(json.writeValueAsString(caso.getValue())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("PEDIDO_INVALIDO"))
                    .andExpect(jsonPath("$.tokenDeAcesso").doesNotExist());
        }
        // Com a senha errada, continua sendo a resposta da senha errada (não revela o perfil).
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", vendedor.login(), "senha", "errada-123"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
        assertThat(jdbc.queryForList("SELECT resultado FROM corona.tentativas_login WHERE login IN (?, ?) ORDER BY ocorrida_em",
                String.class, vendedor.login(), dona.login()))
                .containsExactly("PEDIDO_INVALIDO", "PEDIDO_INVALIDO", "SENHA_INCORRETA");
    }

    @Test
    void identificadorDeAparelhoForaDoFormatoEhPedidoMalformado() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        Map.of("login", vendedor.login(), "senha", vendedor.senha(), "dispositivoId", "notebook-da-maria"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void primeiroAdministradorDaConfiguracaoEntra() throws Exception {
        entrar(ADMIN_INICIAL_LOGIN, ADMIN_INICIAL_SENHA).andExpect(status().isOk());
    }

    @Test
    void loginComEspacosEMaiusculasEncontraOUsuarioNormalizado() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        entrar("  " + dona.login().toUpperCase() + " \t", dona.senha()).andExpect(status().isOk());
    }

    @Test
    void senhaErradaLoginInexistenteEInativoComSenhaErradaDaoAMesmaResposta() throws Exception {
        UsuarioDeTeste ativo = usuarios.criarDona();
        UsuarioDeTeste inativo = usuarios.criarDona();
        usuarios.desativar(inativo);

        String senhaErrada = corpoTexto(entrar(ativo.login(), "senha-errada-1").andExpect(status().isUnauthorized()));
        String inexistente = corpoTexto(entrar("ninguem." + UUID.randomUUID(), "senha-errada-1")
                .andExpect(status().isUnauthorized()));
        String inativoErrada = corpoTexto(entrar(inativo.login(), "senha-errada-1")
                .andExpect(status().isUnauthorized()));

        assertThat(inexistente).isEqualTo(senhaErrada);
        assertThat(inativoErrada).isEqualTo(senhaErrada);
        JsonNode problema = json.readTree(senhaErrada);
        assertThat(problema.get("codigo").asString()).isEqualTo("LOGIN_INVALIDO");
        assertThat(problema.get("title").asString()).isEqualTo("Não foi possível entrar.");
        assertThat(problema.get("detail").asString()).isEqualTo(NAO_FOI_POSSIVEL_ENTRAR);
    }

    @Test
    void inativoComSenhaCertaRecebeAcessoDesativado() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.desativar(vendedor);
        entrar(vendedor.login(), vendedor.senha())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"))
                .andExpect(jsonPath("$.title").value("Seu acesso foi desativado."))
                .andExpect(jsonPath("$.detail").value(
                        "Seu acesso foi desativado. Fale com a dona da loja para voltar a usar o sistema."));
    }

    @Test
    void senhaComMaisDe72BytesEhSenhaErradaENuncaErroInterno() throws Exception {
        // O BCrypt só lê 72 bytes: sem a recusa, a senha de 73 bytes "conferiria" com a de 72.
        String senha72 = "a".repeat(72);
        UsuarioDeTeste dona = usuarios.criar("ADMIN", null, true, senha72, new BCryptPasswordEncoder(4));
        entrar(dona.login(), senha72).andExpect(status().isOk());

        entrar(dona.login(), senha72 + "b")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
        // 37 letras com acento = 74 bytes em UTF-8.
        entrar(dona.login(), "ç".repeat(37))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
        entrar("ninguem." + UUID.randomUUID(), "x".repeat(5000))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
    }

    @Test
    void hashDeCustoMenorEhRegravadoComCusto12NoLoginCerto() throws Exception {
        UsuarioDeTeste dona = usuarios.criar("ADMIN", null, true, "senha-antiga-1", new BCryptPasswordEncoder(10));
        assertThat(usuarios.senhaHash(dona)).startsWith("$2a$10$");

        entrar(dona.login(), "senha-errada-1").andExpect(status().isUnauthorized());
        assertThat(usuarios.senhaHash(dona)).as("senha errada não regrava").startsWith("$2a$10$");

        entrar(dona.login(), dona.senha()).andExpect(status().isOk());
        String novo = usuarios.senhaHash(dona);
        assertThat(novo).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches(dona.senha(), novo)).isTrue();
        entrar(dona.login(), dona.senha()).andExpect(status().isOk());
    }

    @Test
    void campoAusenteEhPedidoMalformado() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"login\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void loginQueNaoPodeExistirDaAMesmaRespostaDaSenhaErrada() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String senhaErrada = corpoTexto(entrar(dona.login(), "senha-errada-1").andExpect(status().isUnauthorized()));
        String[] logins = {"", "   ", "	", "dona com espaço", "dona x", "dona", "dona x", "dona​x"};
        for (String login : logins) {
            assertThat(corpoTexto(entrar(login, "senha-errada-1").andExpect(status().isUnauthorized())))
                    .as("login %s", login.codePoints().boxed().toList())
                    .isEqualTo(senhaErrada);
        }
        // Senha vazia também: senha errada.
        assertThat(corpoTexto(entrar(dona.login(), "").andExpect(status().isUnauthorized()))).isEqualTo(senhaErrada);
    }

    @Test
    void hashESenhaNuncaSaemNaRespostaNemNoLog(CapturedOutput saida) throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String hash = usuarios.senhaHash(dona);
        int inicio = saida.getAll().length();

        String login = corpoTexto(entrar(dona.login(), dona.senha()).andExpect(status().isOk()));
        String token = json.readTree(login).get("tokenDeAcesso").asString();
        String hashRegravado = usuarios.senhaHash(dona);
        String eu = mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        entrar(dona.login(), "senha-errada-1").andExpect(status().isUnauthorized());
        String log = saida.getAll().substring(inicio);

        for (String texto : new String[] {login, TokensDeTeste.parte(token, 1), eu, log}) {
            assertThat(texto).doesNotContain(hash).doesNotContain(hashRegravado)
                    .doesNotContain(dona.senha()).doesNotContain("senha-errada-1").doesNotContain("$2a$");
        }
    }

    @Test
    void tokenVelhoNoCabecalhoNaoImpedeOLogin() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String corpo = json.writeValueAsString(Map.of("login", dona.login(), "senha", dona.senha()));
        mvc.perform(post("/api/auth/login").with(UsuariosDeTeste.comToken("token.quebrado.velho"))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());
    }
}
