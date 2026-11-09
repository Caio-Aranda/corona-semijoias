package br.com.coronasemijoias.api.autenticacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.IpsDeTeste;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redefinir ou trocar a senha revoga todas as famílias do usuário (ADR-0004, item 2): nenhum token
 * emitido antes vale depois, nem no MESMO SEGUNDO (o relógio fica parado) e nem o token restrito. Antes
 * da tarefa 11 só a comparação do {@code iat} (em segundos) existia, e o token do mesmo segundo passava.
 */
class RevogacaoNaTrocaDeCredencialTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    RelogioDeTeste relogio;

    @BeforeEach
    void pararORelogio() {
        // 100 ms depois de um segundo cheio: o teste inteiro acontece no mesmo segundo.
        relogio.fixar(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(100));
    }

    @AfterEach
    void reiniciarORelogio() {
        relogio.reiniciar();
    }

    private JsonNode corpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions entrar(UsuarioDeTeste usuario, String senha, RequestPostProcessor ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(ip).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(usuario.login(), senha))));
    }

    private JsonNode entrou(UsuarioDeTeste usuario) throws Exception {
        return corpo(entrar(usuario, usuario.senha(), r -> r).andExpect(status().isOk()));
    }

    private void derrubado(String tokenDeAcesso) throws Exception {
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(tokenDeAcesso)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }

    private void restritoDerrubado(String restrito) throws Exception {
        mvc.perform(post("/api/auth/trocar-senha").with(UsuariosDeTeste.comToken(restrito))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"senhaNova\":\"Senha-Nova-123\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }

    private void renovacaoDerrubada(String tokenDeRenovacao) throws Exception {
        mvc.perform(post("/api/auth/renovar").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tokenDeRenovacao", tokenDeRenovacao,
                                "dispositivoId", UsuariosDeTeste.APARELHO))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }

    private String redefinirPelaDona(UsuarioDeTeste usuario) throws Exception {
        return corpo(mvc.perform(post("/api/dona/usuarios/" + usuario.id() + "/redefinir-senha")
                .with(usuarios.comoDona())).andExpect(status().isOk())).get("senhaTemporaria").asString();
    }

    @Test
    void redefinirPelaDonaDerrubaOsTokensDoMesmoSegundo() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        JsonNode login = entrou(vendedor);
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(login.get("tokenDeAcesso").asString())))
                .andExpect(status().isOk());

        redefinirPelaDona(vendedor);

        derrubado(login.get("tokenDeAcesso").asString());
        renovacaoDerrubada(login.get("tokenDeRenovacao").asString());
    }

    @Test
    void redefinirPelaDonaDerrubaOTokenRestritoDoMesmoSegundo() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(vendedor, null);
        String restrito = entrou(vendedor).get("tokenDeAcesso").asString();

        redefinirPelaDona(vendedor);

        restritoDerrubado(restrito);
    }

    @Test
    void trocarASenhaDerrubaTodasAsFamiliasAntigasMasNaoAQueEntrega() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(vendedor, null);
        String restritoUsado = entrou(vendedor).get("tokenDeAcesso").asString();
        String outroRestrito = entrou(vendedor).get("tokenDeAcesso").asString();

        JsonNode trocou = corpo(mvc.perform(post("/api/auth/trocar-senha").with(UsuariosDeTeste.comToken(restritoUsado))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"senhaNova\":\"Senha-Nova-123\"}"))
                .andExpect(status().isOk()));

        restritoDerrubado(restritoUsado);
        restritoDerrubado(outroRestrito);
        // O que a troca devolveu nasceu no mesmo segundo e vale: o de acesso e o de renovação.
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(trocou.get("tokenDeAcesso").asString())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/renovar").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tokenDeRenovacao", trocou.get("tokenDeRenovacao").asString(),
                                "dispositivoId", UsuariosDeTeste.APARELHO))))
                .andExpect(status().isOk());
    }

    @Test
    void redefinirPelaDonaTiraOBloqueioDoLogin() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        RequestPostProcessor ip = IpsDeTeste.de(IpsDeTeste.aleatorio());
        relogio.reiniciar(); // o bloqueio precisa do relógio andando
        for (int i = 0; i < 5; i++) {
            entrar(vendedor, "senha-errada-1", ip).andExpect(status().isUnauthorized());
        }
        entrar(vendedor, vendedor.senha(), ip).andExpect(status().isTooManyRequests());

        String temporaria = redefinirPelaDona(vendedor);

        // A pessoa que errou 5 vezes não fica presa mais 15 minutos.
        entrar(vendedor, temporaria, ip).andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));
        assertThat(temporaria).isNotBlank();
    }
}
