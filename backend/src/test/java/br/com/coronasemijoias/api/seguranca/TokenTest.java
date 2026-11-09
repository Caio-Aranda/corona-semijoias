package br.com.coronasemijoias.api.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.SessaoAberta;
import br.com.coronasemijoias.api.sessoes.TipoDeSessao;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.TokensDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Conferência do token a cada requisição (ADR-0004, item 1). */
class TokenTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    private ResultActions eu(String token) throws Exception {
        return mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(token)));
    }

    private void recusado(String token) throws Exception {
        eu(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"))
                .andExpect(jsonPath("$.title").value("Sua sessão terminou."));
    }

    /** Conteúdo de um token válido, com uma família de sessão aberta de verdade. */
    private JWTClaimsSet.Builder conteudoDe(UsuarioDeTeste usuario) {
        SessaoAberta sessao = usuarios.abrirSessao(usuario, TipoDeSessao.NORMAL);
        JWTClaimsSet.Builder conteudo = TokensDeTeste.conteudo(usuario.id(), usuario.perfil(), usuario.vendedorId())
                .claim("sid", sessao.sid().toString());
        if (sessao.dispositivoId() != null) {
            conteudo.claim("dispositivo_id", sessao.dispositivoId().toString());
        }
        return conteudo;
    }

    @Test
    void tokenSemSessaoOuComSessaoQueNaoConfereEhRecusado() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        // Sem sid.
        recusado(TokensDeTeste.assinar(conteudoDe(vendedor).claim("sid", null).build(), CHAVE_ATUAL, ID_CHAVE_ATUAL));
        // sid que não existe.
        recusado(TokensDeTeste.assinar(conteudoDe(vendedor).claim("sid", UUID.randomUUID().toString()).build(),
                CHAVE_ATUAL, ID_CHAVE_ATUAL));
        // sid da sessão de outra pessoa.
        UUID deOutro = usuarios.abrirSessao(usuarios.criarVendedor(), TipoDeSessao.NORMAL).sid();
        recusado(TokensDeTeste.assinar(conteudoDe(vendedor).claim("sid", deOutro.toString()).build(),
                CHAVE_ATUAL, ID_CHAVE_ATUAL));
        // Outro aparelho, ou sem aparelho, na mesma sessão.
        recusado(TokensDeTeste.assinar(conteudoDe(vendedor).claim("dispositivo_id", UUID.randomUUID().toString()).build(),
                CHAVE_ATUAL, ID_CHAVE_ATUAL));
        recusado(TokensDeTeste.assinar(conteudoDe(vendedor).claim("dispositivo_id", null).build(),
                CHAVE_ATUAL, ID_CHAVE_ATUAL));
        // Token comum com o sid de uma sessão da troca de senha.
        UUID restrita = usuarios.abrirSessao(vendedor, TipoDeSessao.TROCA_SENHA).sid();
        recusado(TokensDeTeste.assinar(conteudoDe(vendedor).claim("sid", restrita.toString()).build(),
                CHAVE_ATUAL, ID_CHAVE_ATUAL));
        // O mesmo token, certo, é aceito.
        eu(TokensDeTeste.assinar(conteudoDe(vendedor).build(), CHAVE_ATUAL, ID_CHAVE_ATUAL)).andExpect(status().isOk());
    }

    @Test
    void tokenMontadoComAChaveAtualEhAceito() throws Exception {
        UsuarioDeTeste dona = usuarios.dona();
        eu(TokensDeTeste.assinar(conteudoDe(dona).build(), CHAVE_ATUAL, ID_CHAVE_ATUAL)).andExpect(status().isOk());
    }

    @Test
    void tokenAssinadoComAChaveAnteriorEhAceito() throws Exception {
        UsuarioDeTeste dona = usuarios.dona();
        eu(TokensDeTeste.assinar(conteudoDe(dona).build(), CHAVE_ANTERIOR, ID_CHAVE_ANTERIOR))
                .andExpect(status().isOk());
    }

    @Test
    void tokenVencidoEhRecusado() throws Exception {
        Instant antes = Instant.now().minusSeconds(3600);
        JWTClaimsSet vencido = conteudoDe(usuarios.dona())
                .issueTime(Date.from(antes))
                .expirationTime(Date.from(antes.plusSeconds(900)))
                .build();
        recusado(TokensDeTeste.assinar(vencido, CHAVE_ATUAL, ID_CHAVE_ATUAL));
    }

    @Test
    void tokenComConteudoTrocadoEhRecusado() throws Exception {
        UsuarioDeTeste vendedor = usuarios.vendedor();
        String token = usuarios.token(vendedor);
        JWTClaimsSet virouDona = TokensDeTeste.conteudo(vendedor.id(), "ADMIN", null).build();
        String adulterado = TokensDeTeste.trocarConteudo(token, virouDona);
        recusado(adulterado);
        mvc.perform(get("/api/dona/teste/tempo").with(UsuariosDeTeste.comToken(adulterado)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSemAssinaturaEhRecusado() throws Exception {
        recusado(TokensDeTeste.semAssinatura(conteudoDe(usuarios.dona()).build(), ID_CHAVE_ATUAL));
    }

    @Test
    void tokenAssinadoComOutraChaveEhRecusado() throws Exception {
        recusado(TokensDeTeste.assinar(conteudoDe(usuarios.dona()).build(), bytesAleatorios(32), ID_CHAVE_ATUAL));
    }

    @Test
    void tokenComKidDesconhecidoOuSemKidEhRecusado() throws Exception {
        JWTClaimsSet conteudo = conteudoDe(usuarios.dona()).build();
        recusado(TokensDeTeste.assinar(conteudo, CHAVE_ATUAL, "outra-chave"));
        recusado(TokensDeTeste.assinar(conteudo, CHAVE_ATUAL, null));
        // O kid da anterior com a chave atual também não serve.
        recusado(TokensDeTeste.assinar(conteudo, CHAVE_ATUAL, ID_CHAVE_ANTERIOR));
    }

    @Test
    void tokenComIssErradoEhRecusado() throws Exception {
        JWTClaimsSet conteudo = conteudoDe(usuarios.dona()).issuer("outro-sistema").build();
        recusado(TokensDeTeste.assinar(conteudo, CHAVE_ATUAL, ID_CHAVE_ATUAL));
    }

    @Test
    void tokenSemPerfilOuSemJtiEhRecusado() throws Exception {
        UsuarioDeTeste dona = usuarios.dona();
        recusado(TokensDeTeste.assinar(conteudoDe(dona).claim("perfil", null).build(), CHAVE_ATUAL, ID_CHAVE_ATUAL));
        recusado(TokensDeTeste.assinar(conteudoDe(dona).jwtID(null).build(), CHAVE_ATUAL, ID_CHAVE_ATUAL));
    }

    @Test
    void tokenComPerfilDiferenteDoCadastroEhRecusado() throws Exception {
        // Assinatura válida, mas o perfil do token não é o do banco (token antigo ou forjado com a chave).
        UsuarioDeTeste vendedor = usuarios.vendedor();
        JWTClaimsSet conteudo = TokensDeTeste.conteudo(vendedor.id(), "ADMIN", null).build();
        recusado(TokensDeTeste.assinar(conteudo, CHAVE_ATUAL, ID_CHAVE_ATUAL));
    }

    @Test
    void usuarioDesativadoNoMeioDaValidadePerdeOAcessoNaProximaChamada() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        String token = usuarios.token(vendedor);
        eu(token).andExpect(status().isOk());

        usuarios.desativar(vendedor);

        eu(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"))
                .andExpect(jsonPath("$.title").value("Seu acesso foi desativado."));
    }

    @Test
    void euDevolveSoOsCincoCampos() throws Exception {
        UsuarioDeTeste vendedor = usuarios.vendedor();
        String corpo = eu(usuarios.token(vendedor)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode dados = json.readTree(corpo);
        assertThat(dados.propertyNames()).containsExactlyInAnyOrder("id", "nome", "login", "perfil", "vendedorId");
        assertThat(dados.get("id").asString()).isEqualTo(vendedor.id().toString());
        assertThat(dados.get("nome").asString()).isEqualTo(vendedor.nome());
        assertThat(dados.get("login").asString()).isEqualTo(vendedor.login());
        assertThat(dados.get("perfil").asString()).isEqualTo("VENDEDOR");
        assertThat(dados.get("vendedorId").asString()).isEqualTo(vendedor.vendedorId().toString());
        assertThat(corpo).doesNotContain("senha").doesNotContain("$2a$");

        String daDona = eu(usuarios.token(usuarios.dona())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(daDona).get("vendedorId").isNull()).isTrue();
    }
}
