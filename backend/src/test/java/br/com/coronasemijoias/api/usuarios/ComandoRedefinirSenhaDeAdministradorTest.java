package br.com.coronasemijoias.api.usuarios;

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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comando do servidor que redefine a senha de um administrador (ADR-0004, item 8): a senha aparece
 * uma vez no terminal e nunca no log.
 */
@ExtendWith(OutputCaptureExtension.class)
class ComandoRedefinirSenhaDeAdministradorTest extends TesteDeIntegracao {

    private static final Pattern SENHA_NO_TERMINAL = Pattern.compile("Senha temporária: (\\S+)");

    @Autowired
    ComandoRedefinirSenhaDeAdministrador comando;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    MockMvc mvc;

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

    private record Resultado(int saida, String terminal) {
    }

    private Resultado executar(String login) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int saida = comando.executar(login, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Resultado(saida, bytes.toString(StandardCharsets.UTF_8));
    }

    private static String senhaDoTerminal(String terminal) {
        Matcher achou = SENHA_NO_TERMINAL.matcher(terminal);
        assertThat(achou.find()).as(terminal).isTrue();
        return achou.group(1);
    }

    @Test
    void registraNaAuditoriaComoFeitoPeloServidor() {
        UsuarioDeTeste admin = usuarios.criarDona();
        Resultado resultado = executar(admin.login());
        String senha = senhaDoTerminal(resultado.terminal());

        List<Map<String, Object>> trilha = jdbc.queryForList(
                "SELECT ator_usuario_id, origem, acao, tabela, antes::text AS antes, depois::text AS depois "
                        + "FROM corona.auditoria WHERE registro_id = ?", admin.id());
        assertThat(trilha).hasSize(1);
        Map<String, Object> linha = trilha.get(0);
        assertThat(linha.get("origem")).isEqualTo("COMANDO");
        assertThat(linha.get("ator_usuario_id")).isNull();
        assertThat(linha.get("acao")).isEqualTo("SENHA_REDEFINIDA");
        assertThat(linha.get("tabela")).isEqualTo("usuarios");
        assertThat(String.valueOf(linha.get("antes")) + linha.get("depois")).doesNotContain(senha).doesNotContain("$2");
    }

    @Test
    void recusaDoComandoNaoGravaAuditoria() {
        UsuarioDeTeste desativada = usuarios.criarDona();
        usuarios.desativar(desativada);
        Resultado resultado = executar(desativada.login());
        assertThat(resultado.saida()).isEqualTo(ComandoRedefinirSenhaDeAdministrador.SAIDA_RECUSADO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.auditoria WHERE registro_id = ?", Integer.class,
                desativada.id())).isZero();
    }

    @Test
    void redefineMostraUmaVezMarcaATrocaEDerrubaAsSessoes(CapturedOutput saida) throws Exception {
        UsuarioDeTeste admin = usuarios.criarDona();
        String tokenAntigo = usuarios.token(admin);
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(tokenAntigo))).andExpect(status().isOk());

        relogio.avancar(Duration.ofSeconds(2));
        Resultado resultado = executar("  " + admin.login().toUpperCase() + " ");

        assertThat(resultado.saida()).isEqualTo(ComandoRedefinirSenhaDeAdministrador.SAIDA_OK);
        String senha = senhaDoTerminal(resultado.terminal());
        assertThat(RegrasDaSenha.serve(senha)).isTrue();
        assertThat(resultado.terminal())
                .containsOnlyOnce(senha)
                .contains("Anote esta senha agora: ela não será mostrada de novo.")
                .contains("Vale até:")
                .contains("(horário de Brasília)");

        // O registro diz o que foi feito, sem a senha.
        assertThat(saida.getAll()).contains("Senha temporária do administrador " + admin.login()
                + " redefinida pelo servidor").doesNotContain(senha);

        // No banco: troca obrigatória, vencimento e hora da troca de credencial.
        Map<String, Object> linha = jdbc.queryForMap("""
                SELECT troca_de_senha_obrigatoria, senha_temporaria_vence_em, credencial_alterada_em
                  FROM corona.usuarios WHERE id = ?""", admin.id());
        assertThat(linha.get("troca_de_senha_obrigatoria")).isEqualTo(true);
        assertThat(linha.get("senha_temporaria_vence_em")).isNotNull();
        assertThat(linha.get("credencial_alterada_em")).isNotNull();
        assertThat(usuarios.senhaHash(admin)).doesNotContain(senha);

        // O token de antes caiu, a senha de antes não entra e a nova entra só na troca obrigatória.
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(tokenAntigo)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login", admin.login(), "senha", admin.senha()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login", admin.login(), "senha", senha))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));
    }

    @Test
    void limpaOBloqueioDoLoginERevogaAsSessoesNoMesmoSegundo() throws Exception {
        UsuarioDeTeste admin = usuarios.criarDona();
        String ip = IpsDeTeste.aleatorio();
        relogio.fixar(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(100));
        String tokenAntigo = usuarios.token(admin);
        for (int i = 0; i < 5; i++) {
            entrar(admin.login(), "senha-errada-1", ip).andExpect(status().isUnauthorized());
        }
        entrar(admin.login(), admin.senha(), ip).andExpect(status().isTooManyRequests());

        // No mesmo segundo do token antigo (relógio parado).
        Resultado resultado = executar(admin.login());
        assertThat(resultado.terminal()).contains("O bloqueio por tentativas erradas desse login foi retirado.");
        String senha = senhaDoTerminal(resultado.terminal());
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(tokenAntigo)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
        // Do mesmo IP, a senha nova entra na hora, sem esperar os 15 minutos.
        entrar(admin.login(), senha, ip).andExpect(status().isOk())
                .andExpect(jsonPath("$.trocaDeSenhaObrigatoria").value(true));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM corona.sessoes
                 WHERE usuario_id = ? AND motivo_da_revogacao = 'SENHA_REDEFINIDA_PELO_SERVIDOR'""",
                Long.class, admin.id())).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions entrar(String login, String senha, String ip)
            throws Exception {
        return mvc.perform(post("/api/auth/login").with(IpsDeTeste.de(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login", login, "senha", senha))));
    }

    @Test
    void vendedorNaoEhAdministradorENadaMuda() {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        String hashAntes = usuarios.senhaHash(vendedor);

        Resultado resultado = executar(vendedor.login());

        assertThat(resultado.saida()).isEqualTo(ComandoRedefinirSenhaDeAdministrador.SAIDA_RECUSADO);
        assertThat(resultado.terminal()).contains("Não existe nenhum administrador com o login")
                .doesNotContain("Senha temporária:");
        assertThat(usuarios.senhaHash(vendedor)).isEqualTo(hashAntes);
    }

    @Test
    void loginQueNaoExisteEhRecusado() {
        Resultado resultado = executar("ninguem.assim");
        assertThat(resultado.saida()).isEqualTo(ComandoRedefinirSenhaDeAdministrador.SAIDA_RECUSADO);
        assertThat(resultado.terminal()).contains("Não existe nenhum administrador com o login \"ninguem.assim\"");
    }

    @Test
    void administradorDesativadoNaoEhReativadoENadaMuda() {
        UsuarioDeTeste admin = usuarios.criarDona();
        usuarios.desativar(admin);
        String hashAntes = usuarios.senhaHash(admin);

        Resultado resultado = executar(admin.login());

        assertThat(resultado.saida()).isEqualTo(ComandoRedefinirSenhaDeAdministrador.SAIDA_RECUSADO);
        assertThat(resultado.terminal()).contains("está inativo").doesNotContain("Senha temporária:");
        assertThat(usuarios.senhaHash(admin)).isEqualTo(hashAntes);
    }

    @Test
    void semLoginNoComandoEPedidoDeUso() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int saida = ComandoRedefinirSenhaDeAdministrador.rodar(
                new String[] {ComandoRedefinirSenhaDeAdministrador.NOME},
                new PrintStream(bytes, true, StandardCharsets.UTF_8), () -> null);
        assertThat(saida).isEqualTo(ComandoRedefinirSenhaDeAdministrador.SAIDA_USO_ERRADO);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("Informe o login do administrador");
    }

    @Test
    void reconheceOComandoPeloPrimeiroArgumento() {
        assertThat(ComandoRedefinirSenhaDeAdministrador.ehEste(new String[] {"redefinir-senha-admin", "--login=x"}))
                .isTrue();
        assertThat(ComandoRedefinirSenhaDeAdministrador.ehEste(new String[] {"--login=x"})).isFalse();
        assertThat(ComandoRedefinirSenhaDeAdministrador.ehEste(new String[0])).isFalse();
        assertThat(ComandoRedefinirSenhaDeAdministrador.loginDosArgumentos(
                new String[] {"redefinir-senha-admin", "--login=maria.silva"})).isEqualTo("maria.silva");
    }
}
