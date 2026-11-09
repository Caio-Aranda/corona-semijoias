package br.com.coronasemijoias.api.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A dona cria usuários e redefine a senha de quem esqueceu (ADR-0004, itens 6 e 8). */
@ExtendWith(OutputCaptureExtension.class)
class UsuariosDaDonaTest extends TesteDeIntegracao {

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

    @AfterEach
    void reiniciarORelogio() {
        relogio.reiniciar();
    }

    private static String loginNovo() {
        return "teste." + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> pedido(String login, String perfil) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("nome", "Maria da Silva");
        corpo.put("login", login);
        corpo.put("perfil", perfil);
        return corpo;
    }

    private ResultActions criar(RequestPostProcessor quem, Map<String, Object> corpo) throws Exception {
        return mvc.perform(post("/api/dona/usuarios").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private JsonNode corpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void criaVendedorComSenhaTemporariaQueObedeceAsRegras(CapturedOutput saida) throws Exception {
        String login = loginNovo();
        UUID vendedorId = UUID.randomUUID();
        Map<String, Object> corpoDoPedido = pedido("  " + login.toUpperCase() + " ", "VENDEDOR");
        corpoDoPedido.put("vendedorId", vendedorId.toString());

        ResultActions resposta = criar(usuarios.comoDona(), corpoDoPedido)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"));
        JsonNode criado = corpo(resposta);

        String senha = criado.get("senhaTemporaria").asString();
        assertThat(RegrasDaSenha.serve(senha)).isTrue();
        assertThat(senha).hasSize(12).matches("[A-Za-z0-9]+").doesNotContain("0", "O", "1", "l", "I", "o");
        assertThat(criado.get("login").asString()).isEqualTo(login);
        assertThat(criado.get("perfil").asString()).isEqualTo("VENDEDOR");
        assertThat(criado.get("vendedorId").asString()).isEqualTo(vendedorId.toString());
        assertThat(criado.has("senhaHash")).isFalse();
        // Vale 7 dias a partir de agora (configuração do servidor).
        Instant vence = Instant.parse(criado.get("senhaTemporariaVenceEm").asString());
        assertThat(vence).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(7)).plusSeconds(1));

        // No banco: só o hash, com a marca de troca obrigatória e o mesmo vencimento.
        Map<String, Object> linha = jdbc.queryForMap("""
                SELECT senha_hash, troca_de_senha_obrigatoria, senha_temporaria_vence_em, credencial_alterada_em, perfil
                  FROM corona.usuarios WHERE login = ?""", login);
        assertThat((String) linha.get("senha_hash")).isNotEqualTo(senha).startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches(senha, (String) linha.get("senha_hash"))).isTrue();
        assertThat(linha.get("troca_de_senha_obrigatoria")).isEqualTo(true);
        assertThat(linha.get("credencial_alterada_em")).isNull();
        assertThat(((java.sql.Timestamp) linha.get("senha_temporaria_vence_em")).toInstant()).isEqualTo(vence);

        // A senha só existe na resposta: não vai para o log nem para nenhuma coluna.
        assertThat(saida.getAll()).doesNotContain(senha);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM corona.usuarios WHERE nome = ? OR login = ? OR senha_hash = ?",
                Integer.class, senha, senha, senha)).isZero();
    }

    @Test
    void criaOutraDonaSemVendedor() throws Exception {
        criar(usuarios.comoDona(), pedido(loginNovo(), "ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perfil").value("ADMIN"))
                .andExpect(jsonPath("$.vendedorId").doesNotExist());
    }

    @Test
    void duasCriacoesGeramSenhasDiferentes() throws Exception {
        String a = corpo(criar(usuarios.comoDona(), pedido(loginNovo(), "VENDEDOR"))).get("senhaTemporaria").asString();
        String b = corpo(criar(usuarios.comoDona(), pedido(loginNovo(), "VENDEDOR"))).get("senhaTemporaria").asString();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void loginRepetidoEhRecusadoMesmoComOutraGrafia() throws Exception {
        String login = loginNovo();
        criar(usuarios.comoDona(), pedido(login, "VENDEDOR")).andExpect(status().isCreated());

        for (String repetido : new String[] {login, login.toUpperCase(), "  " + login + "  "}) {
            criar(usuarios.comoDona(), pedido(repetido, "VENDEDOR"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("LOGIN_JA_USADO"))
                    .andExpect(jsonPath("$.title").value("Este login já está em uso."))
                    .andExpect(jsonPath("$.detail").value(
                            "Este login já está em uso. Escolha outro login para esta pessoa, "
                                    + "por exemplo juntando o sobrenome."));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.usuarios WHERE login = ?", Integer.class, login))
                .isEqualTo(1);
    }

    @Test
    void loginDeUsuarioQueJaExisteNoBancoTambemEhRecusado() throws Exception {
        UsuarioDeTeste existente = usuarios.criarVendedor();
        criar(usuarios.comoDona(), pedido(existente.login(), "VENDEDOR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LOGIN_JA_USADO"));
    }

    @Test
    void loginForaDoFormatoEhRecusado() throws Exception {
        for (String login : new String[] {"", "ab", "x".repeat(41), "maria silva", "joão", "maria!", ".maria", "maria.",
                "maria@loja"}) {
            criar(usuarios.comoDona(), pedido(login, "VENDEDOR"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("LOGIN_FORA_DO_FORMATO"))
                    .andExpect(jsonPath("$.title").value("Este login não pode ser usado."))
                    .andExpect(jsonPath("$.detail").value(
                            "Este login não pode ser usado. Use de 3 a 40 letras minúsculas sem acento, números, "
                                    + "ponto, traço ou traço baixo, sem espaços. Por exemplo: maria.silva."));
        }
    }

    @Test
    void perfilQueNaoExisteEAdminComVendedorSaoRecusados() throws Exception {
        criar(usuarios.comoDona(), pedido(loginNovo(), "GERENTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("perfil"));
        Map<String, Object> adminComVendedor = pedido(loginNovo(), "ADMIN");
        adminComVendedor.put("vendedorId", UUID.randomUUID().toString());
        criar(usuarios.comoDona(), adminComVendedor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("vendedorId"));
    }

    @Test
    void faltaDeCampoEhPedidoInvalido() throws Exception {
        Map<String, Object> semNome = pedido(loginNovo(), "VENDEDOR");
        semNome.put("nome", "   ");
        criar(usuarios.comoDona(), semNome).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
        Map<String, Object> semLogin = pedido(loginNovo(), "VENDEDOR");
        semLogin.remove("login");
        criar(usuarios.comoDona(), semLogin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void vendedorNaoCriaUsuario() throws Exception {
        criar(usuarios.comoVendedor(), pedido(loginNovo(), "VENDEDOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
    }

    @Test
    void semTokenNaoCriaUsuario() throws Exception {
        mvc.perform(post("/api/dona/usuarios").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(pedido(loginNovo(), "VENDEDOR"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
    }

    // --- Redefinir a senha ---

    private ResultActions redefinir(RequestPostProcessor quem, Object id) throws Exception {
        return mvc.perform(post("/api/dona/usuarios/" + id + "/redefinir-senha").with(quem));
    }

    @Test
    void redefinirDaOutraSenhaTemporariaComPrazoNovo(CapturedOutput saida) throws Exception {
        JsonNode criado = corpo(criar(usuarios.comoDona(), pedido(loginNovo(), "VENDEDOR")).andExpect(status().isCreated()));
        String id = criado.get("id").asString();
        String primeira = criado.get("senhaTemporaria").asString();

        // Passam-se 3 dias e a dona redefine: a contagem de 7 dias recomeça de agora.
        relogio.avancar(Duration.ofDays(3));
        JsonNode redefinido = corpo(redefinir(usuarios.comoDona(), id)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));

        String segunda = redefinido.get("senhaTemporaria").asString();
        assertThat(segunda).isNotEqualTo(primeira);
        assertThat(RegrasDaSenha.serve(segunda)).isTrue();
        Instant vence = Instant.parse(redefinido.get("senhaTemporariaVenceEm").asString());
        assertThat(vence).isAfter(Instant.parse(criado.get("senhaTemporariaVenceEm").asString()));
        assertThat(vence).isBetween(Instant.now().plus(Duration.ofDays(10)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(10)).plusSeconds(1));

        Map<String, Object> linha = jdbc.queryForMap("""
                SELECT senha_hash, troca_de_senha_obrigatoria, credencial_alterada_em
                  FROM corona.usuarios WHERE id = ?""", UUID.fromString(id));
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        assertThat(bcrypt.matches(segunda, (String) linha.get("senha_hash"))).isTrue();
        assertThat(bcrypt.matches(primeira, (String) linha.get("senha_hash"))).isFalse();
        assertThat(linha.get("troca_de_senha_obrigatoria")).isEqualTo(true);
        assertThat(((java.sql.Timestamp) linha.get("credencial_alterada_em")).toInstant())
                .isBetween(Instant.now().plus(Duration.ofDays(3)).minusSeconds(60),
                        Instant.now().plus(Duration.ofDays(3)).plusSeconds(1));
        assertThat(saida.getAll()).doesNotContain(primeira).doesNotContain(segunda);
    }

    @Test
    void redefinirUsuarioQueNaoExisteDa404() throws Exception {
        redefinir(usuarios.comoDona(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NAO_ENCONTRADO"))
                .andExpect(jsonPath("$.detail").value("Não encontramos este usuário. Confira o usuário e tente de novo."));
    }

    @Test
    void aDonaNaoRedefineAPropriaSenha() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String hashAntes = usuarios.senhaHash(dona);
        redefinir(UsuariosDeTeste.comToken(usuarios.token(dona)), dona.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NAO_REDEFINIR_A_PROPRIA_SENHA"));
        assertThat(usuarios.senhaHash(dona)).isEqualTo(hashAntes);
    }

    @Test
    void vendedorNaoRedefineSenha() throws Exception {
        UsuarioDeTeste outro = usuarios.criarVendedor();
        redefinir(usuarios.comoVendedor(), outro.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
    }

    @Test
    void idMalFormadoNaoEhErroInterno() throws Exception {
        mvc.perform(post("/api/dona/usuarios/nao-e-um-id/redefinir-senha").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
    }
}
