package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.comum.configuracao.ConfiguracaoInvalidaException;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.usuarios.ComandoRedefinirSenhaDeAdministrador;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Subida da API de verdade (como em produção), num PostgreSQL próprio e vazio: o primeiro
 * administrador é criado uma vez só, e a configuração que falta impede a subida com a explicação em
 * português. Os passos dependem da ordem (o banco é o mesmo).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubidaDaApiTest extends TesteDeIntegracao {

    private static final String SENHA_DA_DONA = "Senha-Inicial-Que-Nao-Pode-Vazar-123";
    private static final PostgreSQLContainer BANCO_VAZIO = novoContainer();

    @BeforeAll
    static void subirBanco() {
        BANCO_VAZIO.start();
    }

    @AfterAll
    static void derrubarBanco() {
        BANCO_VAZIO.stop();
    }

    /** Argumentos de linha de comando: têm prioridade sobre o .env de quem roda os testes. */
    private static Map<String, String> configuracao() {
        String url = urlJdbc(BANCO_VAZIO);
        Map<String, String> c = new TreeMap<>();
        c.put("spring.datasource.url", url);
        c.put("spring.datasource.username", APP_USUARIO);
        c.put("spring.datasource.password", APP_SENHA);
        c.put("spring.flyway.url", url);
        c.put("spring.flyway.user", MIGRACAO_USUARIO);
        c.put("spring.flyway.password", MIGRACAO_SENHA);
        c.put("server.port", "0");
        c.put("spring.main.banner-mode", "off");
        c.put("corona.seguranca.jwt.chave-atual.id", "chave-1");
        c.put("corona.seguranca.jwt.chave-atual.valor", Base64.getEncoder().encodeToString(bytesAleatorios(32)));
        c.put("corona.seguranca.jwt.chave-anterior.id", "");
        c.put("corona.seguranca.jwt.chave-anterior.valor", "");
        c.put("corona.seguranca.sessao.segredo-de-renovacao", Base64.getEncoder().encodeToString(bytesAleatorios(32)));
        c.put("corona.admin-inicial.nome", "Dona da Loja");
        c.put("corona.admin-inicial.login", "  Dona.Loja ");
        c.put("corona.admin-inicial.senha", SENHA_DA_DONA);
        return c;
    }

    private static ConfigurableApplicationContext subir(Map<String, String> configuracao) {
        List<String> argumentos = new ArrayList<>();
        configuracao.forEach((chave, valor) -> argumentos.add("--" + chave + "=" + valor));
        return new SpringApplication(CoronaApiApplication.class).run(argumentos.toArray(String[]::new));
    }

    /** Sobe esperando a recusa por configuração; devolve o erro (direto ou como causa). */
    private static ConfiguracaoInvalidaException erroDeConfiguracaoAoSubir(Map<String, String> configuracao) {
        Throwable erro = catchThrowable(() -> subir(configuracao).close());
        for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
            if (atual instanceof ConfiguracaoInvalidaException configuracaoInvalida) {
                return configuracaoInvalida;
            }
        }
        throw new AssertionError("A API devia recusar a configuração, mas deu: " + erro, erro);
    }

    private static long quantidadeDeUsuarios() throws Exception {
        try (Connection conexao = conectar(BANCO_VAZIO, APP_USUARIO, APP_SENHA);
                ResultSet linhas = conexao.createStatement().executeQuery("SELECT count(*) FROM corona.usuarios")) {
            linhas.next();
            return linhas.getLong(1);
        }
    }

    private static List<String> administradores() throws Exception {
        List<String> logins = new ArrayList<>();
        try (Connection conexao = conectar(BANCO_VAZIO, APP_USUARIO, APP_SENHA);
                ResultSet linhas = conexao.createStatement()
                        .executeQuery("SELECT login FROM corona.usuarios WHERE perfil = 'ADMIN'")) {
            while (linhas.next()) {
                logins.add(linhas.getString(1));
            }
        }
        return logins;
    }

    @Test
    @Order(1)
    void semAsVariaveisDoAdministradorNaoSobe(CapturedOutput saida) {
        // Vazia (ausente no .env) ou com o valor de exemplo do .env.example.
        String[][] casos = {{"nome", ""}, {"login", "troque-isto"}, {"senha", " Troque-Isto "}};
        for (String[] caso : casos) {
            Map<String, String> configuracao = configuracao();
            configuracao.put("corona.admin-inicial." + caso[0], caso[1]);
            assertThat(erroDeConfiguracaoAoSubir(configuracao).descricao())
                    .contains("ainda não existe nenhum administrador")
                    .contains("ADMIN_" + caso[0].toUpperCase());
        }
        // A explicação aparece em português na saída, no lugar do erro técnico.
        assertThat(saida.getAll())
                .contains("A API não conseguiu começar porque ainda não existe nenhum administrador")
                .contains("No arquivo .env, preencha ADMIN_NOME, ADMIN_LOGIN e ADMIN_SENHA");
    }

    @Test
    @Order(2)
    void loginDoAdministradorQueNuncaEntrariaImpedeASubida(CapturedOutput saida) throws Exception {
        // Com espaço no meio ou caractere invisível, o login seria recusado para sempre, e depois de
        // criado o ADMIN as variáveis não são mais lidas: a API precisa recusar antes de criar.
        for (String login : new String[] {"Maria Silva", "maria​silva", "maria	silva"}) {
            int inicio = saida.getAll().length();
            Map<String, String> configuracao = configuracao();
            configuracao.put("corona.admin-inicial.login", login);
            ConfiguracaoInvalidaException erro = erroDeConfiguracaoAoSubir(configuracao);
            assertThat(erro.descricao()).contains("ADMIN_LOGIN")
                    .contains("não pode ter espaço no meio nem caracteres especiais invisíveis");
            assertThat(erro.acao()).isEqualTo("Escolha um login sem espaços para ADMIN_LOGIN no arquivo .env "
                    + "(por exemplo, maria.silva) e rode de novo.");
            assertThat(saida.getAll().substring(inicio))
                    .contains("o login em ADMIN_LOGIN não pode ter espaço no meio")
                    .contains("(por exemplo, maria.silva)");
        }
        assertThat(quantidadeDeUsuarios()).isZero();
    }

    @Test
    @Order(3)
    void semAChaveDoTokenNaoSobe(CapturedOutput saida) {
        Map<String, String> configuracao = configuracao();
        configuracao.put("corona.seguranca.jwt.chave-atual.valor", "");
        assertThat(erroDeConfiguracaoAoSubir(configuracao).descricao()).contains("JWT_CHAVE_ATUAL");
        assertThat(saida.getAll()).contains("falta a chave que assina os tokens de acesso")
                .contains("openssl rand -base64 32");
    }

    @Test
    @Order(4)
    void primeiraSubidaCriaOAdministrador(CapturedOutput saida) throws Exception {
        assertThat(administradores()).isEmpty();
        try (ConfigurableApplicationContext contexto = subir(configuracao())) {
            assertThat(contexto.isRunning()).isTrue();
        }
        assertThat(administradores()).containsExactly("dona.loja");
        assertThat(saida.getAll()).contains("Primeiro administrador criado: login dona.loja")
                .doesNotContain(SENHA_DA_DONA);
    }

    @Test
    @Order(5)
    void segundaSubidaNaoDuplica(CapturedOutput saida) throws Exception {
        int inicio = saida.getAll().length();  // a saída capturada acumula os passos anteriores
        try (ConfigurableApplicationContext contexto = subir(configuracao())) {
            assertThat(contexto.isRunning()).isTrue();
        }
        assertThat(administradores()).containsExactly("dona.loja");
        assertThat(saida.getAll().substring(inicio))
                .doesNotContain("Primeiro administrador criado").doesNotContain(SENHA_DA_DONA);
    }

    @Test
    @Order(6)
    void comAdministradorExistenteAsVariaveisNaoSaoMaisExigidas() throws Exception {
        Map<String, String> configuracao = configuracao();
        configuracao.put("corona.admin-inicial.senha", "");
        try (ConfigurableApplicationContext contexto = subir(configuracao)) {
            assertThat(contexto.isRunning()).isTrue();
        }
        assertThat(administradores()).containsExactly("dona.loja");
    }

    @Test
    @Order(7)
    void comandoDoServidorRedefineASenhaSemAbrirAPortaDaApi(CapturedOutput saida) throws Exception {
        // A porta da API está ocupada (como estaria com a API rodando): o comando não pode tentar abri-la.
        try (ServerSocket ocupada = new ServerSocket(0)) {
            List<String> argumentos = new ArrayList<>(
                    List.of(ComandoRedefinirSenhaDeAdministrador.NOME, "--login=Dona.Loja"));
            configuracao().forEach((chave, valor) -> argumentos.add("--" + chave + "=" + valor));
            argumentos.add("--server.port=" + ocupada.getLocalPort());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            int codigo = ComandoRedefinirSenhaDeAdministrador.rodar(
                    argumentos.toArray(String[]::new), new PrintStream(bytes, true, StandardCharsets.UTF_8), () -> null);

            String terminal = bytes.toString(StandardCharsets.UTF_8);
            assertThat(codigo).as(terminal + saida.getAll()).isEqualTo(ComandoRedefinirSenhaDeAdministrador.SAIDA_OK);
            java.util.regex.Matcher achou = java.util.regex.Pattern.compile("Senha temporária: (\\S+)").matcher(terminal);
            assertThat(achou.find()).as(terminal).isTrue();
            assertThat(saida.getAll()).contains("redefinida pelo servidor").doesNotContain(achou.group(1));
        }
        try (Connection conexao = conectar(BANCO_VAZIO, APP_USUARIO, APP_SENHA);
                ResultSet linhas = conexao.createStatement().executeQuery(
                        "SELECT troca_de_senha_obrigatoria, credencial_alterada_em FROM corona.usuarios "
                                + "WHERE login = 'dona.loja'")) {
            linhas.next();
            assertThat(linhas.getBoolean(1)).isTrue();
            assertThat(linhas.getTimestamp(2)).isNotNull();
        }
    }
}
