package br.com.coronasemijoias.api.aplicativo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CabecalhosDeAppDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Tipo e versão do aplicativo (ADR-0004, item 10): 400, 426, 403, frases por aplicativo e registro. */
class TipoEVersaoDoAppTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** Login com os cabeçalhos que o teste escolher (null = não manda). */
    private ResultActions entrar(String tipo, String versao, Map<String, Object> corpo) throws Exception {
        MockHttpServletRequestBuilder pedido = post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo))
                .header(CabecalhosDeAppDeTeste.NAO_COMPLETAR, "sim");
        if (tipo != null) {
            pedido.header("X-App-Tipo", tipo);
        }
        if (versao != null) {
            pedido.header("X-App-Versao", versao);
        }
        return mvc.perform(pedido);
    }

    private Map<String, Object> corpo(String login, String senha, boolean comAparelho) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("login", login);
        corpo.put("senha", senha);
        if (comAparelho) {
            corpo.put("dispositivoId", usuarios.aparelhoDe(login).toString());
        }
        return corpo;
    }

    private List<Map<String, Object>> tentativas(String login) {
        return jdbc.queryForList(
                "SELECT tipo_app, resultado FROM corona.tentativas_login WHERE login = ? ORDER BY ocorrida_em", login);
    }

    // --- 400 APP_TIPO_INVALIDO ---

    @Test
    void tipoAusenteOuInvalidoDa400ATodaChamadaInclusiveLogin() throws Exception {
        for (String tipo : new String[] {null, "", "admin", "Gestao", "gestao,vendas"}) {
            entrar(tipo, "99.0.0", corpo("qualquer", "x", false))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("APP_TIPO_INVALIDO"));
        }
        mvc.perform(get("/api/auth/eu").header("X-App-Versao", "99.0.0")
                        .header(CabecalhosDeAppDeTeste.NAO_COMPLETAR, "sim"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("APP_TIPO_INVALIDO"));
    }

    // --- 426 APP_DESATUALIZADO ---

    @Test
    void versaoAbaixoDoMinimoDoTipoDa426ComAsVersoes() throws Exception {
        // Gestão pede 1.2.0; Vendas pede 2.0.1.
        entrar("gestao", "1.1.9", corpo("qualquer", "x", false))
                .andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.codigo").value("APP_DESATUALIZADO"))
                .andExpect(jsonPath("$.title").value("Seu aplicativo está desatualizado."))
                .andExpect(jsonPath("$.detail").value("Seu aplicativo está desatualizado. Atualize para continuar usando."))
                .andExpect(jsonPath("$.versaoMinima").value("1.2.0"))
                .andExpect(jsonPath("$.versaoAtual").value("1.1.9"));
        entrar("vendas", "2.0.0", corpo("qualquer", "x", true))
                .andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.versaoMinima").value("2.0.1"));
        // O mínimo é por tipo: 1.5.0 serve ao Gestão, não ao Vendas.
        entrar("gestao", "1.5.0", corpo("qualquer", "x", false)).andExpect(status().isUnauthorized());
        entrar("vendas", "1.5.0", corpo("qualquer", "x", true)).andExpect(status().isUpgradeRequired());
    }

    @Test
    void versaoEComparadaNumeroANumeroEIgualAoMinimoPassa() throws Exception {
        entrar("gestao", "1.2.0", corpo("qualquer", "x", false)).andExpect(status().isUnauthorized());
        entrar("gestao", "1.10.0", corpo("qualquer", "x", false)).andExpect(status().isUnauthorized());
        entrar("gestao", "1.1.20", corpo("qualquer", "x", false)).andExpect(status().isUpgradeRequired());
    }

    @Test
    void versaoAusenteOuIlegivelDa426() throws Exception {
        for (String versao : new String[] {null, "", "abc", "2", "2.0", "2.0.0.1", "2.0.x"}) {
            entrar("gestao", versao, corpo("qualquer", "x", false))
                    .andExpect(status().isUpgradeRequired())
                    .andExpect(jsonPath("$.codigo").value("APP_DESATUALIZADO"));
        }
    }

    @Test
    void versaoVelhaTambemBarraARotaComToken() throws Exception {
        String token = usuarios.token(usuarios.criarDona());
        mvc.perform(get("/api/auth/eu").header("Authorization", "Bearer " + token)
                        .header("X-App-Tipo", "gestao").header("X-App-Versao", "1.0.0"))
                .andExpect(status().isUpgradeRequired());
    }

    @Test
    void saudeEDocumentacaoNaoExigemCabecalho() throws Exception {
        // Sem nenhum cabeçalho (o MockMvc de teste só completa o que falta; aqui o tipo vai vazio de propósito).
        mvc.perform(get("/actuator/health").header("X-App-Tipo", "").header("X-App-Versao", ""))
                .andExpect(status().isOk());
        // A documentação existe só no perfil dev; sem ele a resposta nunca é 400/426 do filtro.
        int codigo = mvc.perform(get("/v3/api-docs").header("X-App-Tipo", "").header("X-App-Versao", ""))
                .andReturn().getResponse().getStatus();
        assertThat(codigo).isNotIn(400, 426);
    }

    // --- Frases por aplicativo ---

    @Test
    void loginInvalidoAcrescentaAUltimaFraseDoAplicativo() throws Exception {
        entrar("gestao", "99.0.0", corpo("ninguem", "errada-1", false))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"))
                .andExpect(jsonPath("$.detail").value("Não foi possível entrar. Usuário ou senha incorretos. "
                        + "Confira o que você digitou e tente de novo. Se esqueceu a senha, fale com o suporte do sistema."));
        entrar("vendas", "99.0.0", corpo("ninguem", "errada-1", true))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Não foi possível entrar. Usuário ou senha incorretos. "
                        + "Confira o que você digitou e tente de novo. Se esqueceu a senha, peça uma nova à dona da loja."));
    }

    @Test
    void senhaTemporariaVencidaAcrescentaAUltimaFraseDoAplicativo() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        usuarios.marcarTrocaObrigatoria(dona, Instant.now().minusSeconds(3600));
        entrar("gestao", "99.0.0", corpo(dona.login(), dona.senha(), false))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SENHA_TEMPORARIA_VENCIDA"))
                .andExpect(jsonPath("$.detail").value("Sua senha temporária venceu. A senha que você recebeu só vale por "
                        + "alguns dias, e esse prazo acabou. Peça uma senha nova a outra pessoa administradora ou fale "
                        + "com o suporte do sistema."));

        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(vendedor, Instant.now().minusSeconds(3600));
        entrar("vendas", "99.0.0", corpo(vendedor.login(), vendedor.senha(), true))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sua senha temporária venceu. A senha que você recebeu só vale por "
                        + "alguns dias, e esse prazo acabou. Peça uma senha nova à dona da loja."));
    }

    // --- Aparelho pelo aplicativo ---

    @Test
    void aparelhoEExigidoPeloAplicativoENaoPeloPerfil() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        entrar("vendas", "99.0.0", corpo(vendedor.login(), vendedor.senha(), false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("PEDIDO_INVALIDO"));
        entrar("vendas", "99.0.0", corpo(vendedor.login(), vendedor.senha(), true)).andExpect(status().isOk());

        UsuarioDeTeste dona = usuarios.criarDona();
        entrar("gestao", "99.0.0", corpo(dona.login(), dona.senha(), true)).andExpect(status().isBadRequest());
        entrar("gestao", "99.0.0", corpo(dona.login(), dona.senha(), false)).andExpect(status().isOk());
    }

    // --- 403 APP_INCOMPATIVEL ---

    @Test
    void perfilNoAppErradoDa403ComAsMensagensDoGlossarioERegistraATentativa() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        entrar("vendas", "99.0.0", corpo(dona.login(), dona.senha(), true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APP_INCOMPATIVEL"))
                .andExpect(jsonPath("$.title").value("Este é o Corona Vendas."))
                .andExpect(jsonPath("$.detail").value(
                        "Este é o Corona Vendas. Seu acesso é de dona da loja. Para entrar, abra o Corona Gestão."))
                .andExpect(jsonPath("$.tokenDeAcesso").doesNotExist());

        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        entrar("gestao", "99.0.0", corpo(vendedor.login(), vendedor.senha(), false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APP_INCOMPATIVEL"))
                .andExpect(jsonPath("$.title").value("Este é o Corona Gestão."))
                .andExpect(jsonPath("$.detail").value(
                        "Este é o Corona Gestão. Seu acesso é de vendedor. Para entrar, abra o Corona Vendas."));

        assertThat(tentativas(dona.login())).extracting(l -> l.get("resultado")).containsExactly("APP_INCOMPATIVEL");
        assertThat(tentativas(dona.login())).extracting(l -> l.get("tipo_app")).containsExactly("vendas");
        assertThat(tentativas(vendedor.login())).extracting(l -> l.get("tipo_app")).containsExactly("gestao");
    }

    @Test
    void appErradoComSenhaErradaNaoRevelaNada() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        entrar("vendas", "99.0.0", corpo(dona.login(), "errada-123", true))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
    }

    // --- tipo_app gravado ---

    @Test
    void tipoDoAppFicaGravadoEmCadaTentativa() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        entrar("gestao", "99.0.0", corpo(dona.login(), "errada-123", false)).andExpect(status().isUnauthorized());
        entrar("gestao", "99.0.0", corpo(dona.login(), dona.senha(), false)).andExpect(status().isOk());

        assertThat(tentativas(dona.login())).extracting(l -> l.get("resultado"))
                .containsExactly("SENHA_INCORRETA", "SUCESSO");
        assertThat(tentativas(dona.login())).extracting(l -> l.get("tipo_app")).containsExactly("gestao", "gestao");

        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        entrar("vendas", "99.0.0", corpo(vendedor.login(), vendedor.senha(), true)).andExpect(status().isOk());
        assertThat(tentativas(vendedor.login())).extracting(l -> l.get("tipo_app")).containsExactly("vendas");
    }

    // --- O cabeçalho não autoriza ---

    @Test
    void permissaoVemDoPerfilDoTokenENaoDoCabecalho() throws Exception {
        String tokenDoVendedor = usuarios.token(usuarios.criarVendedor());
        String tokenDaDona = usuarios.token(usuarios.criarDona());
        // Vendedor dizendo ser o Gestão: a rota da dona continua negada.
        mvc.perform(get("/api/dona/teste/tempo").header("Authorization", "Bearer " + tokenDoVendedor)
                        .header("X-App-Tipo", "gestao").header("X-App-Versao", "99.0.0"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
        // Dona dizendo ser o Vendas: a rota do vendedor continua negada.
        mvc.perform(get("/api/vendedor/teste/dinheiro").header("Authorization", "Bearer " + tokenDaDona)
                        .header("X-App-Tipo", "vendas").header("X-App-Versao", "99.0.0"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
        // E a dona segue entrando na própria rota com o cabeçalho "errado".
        mvc.perform(get("/api/dona/teste/tempo").header("Authorization", "Bearer " + tokenDaDona)
                        .header("X-App-Tipo", "vendas").header("X-App-Versao", "99.0.0"))
                .andExpect(status().isOk());
    }
}
