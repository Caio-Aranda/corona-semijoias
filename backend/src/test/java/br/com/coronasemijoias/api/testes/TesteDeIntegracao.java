package br.com.coronasemijoias.api.testes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base dos testes que precisam do banco. Sobe um PostgreSQL de verdade (a mesma imagem do
 * Compose) e roda nele o MESMO script {@code infra/postgres/init/01-papeis.sh}, então os testes
 * usam os dois usuários reais: o das migrações (Flyway) e o da aplicação. Sem banco em memória
 * e sem superusuário nos testes.
 *
 * <p>Não usamos {@code @ServiceConnection}: ele entregaria a conexão do superusuário do contêiner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({RotasDeTeste.class, TabelasDeTeste.class, UsuariosDeTeste.class, RelogioDeTeste.class, IpsDeTeste.class})
public abstract class TesteDeIntegracao {

    // Valores falsos, só de teste (o contêiner some quando os testes terminam).
    protected static final String BANCO = "corona";
    protected static final String ADMIN_USUARIO = "admin_teste";
    protected static final String MIGRACAO_USUARIO = "migracao_teste";
    protected static final String MIGRACAO_SENHA = "senha-migracao-teste";
    protected static final String APP_USUARIO = "app_teste";
    protected static final String APP_SENHA = "senha-app-teste";

    // Chaves do token geradas a cada execução: nenhum segredo real no repositório.
    public static final String ID_CHAVE_ATUAL = "teste-atual";
    public static final byte[] CHAVE_ATUAL = bytesAleatorios(32);
    public static final String ID_CHAVE_ANTERIOR = "teste-anterior";
    public static final byte[] CHAVE_ANTERIOR = bytesAleatorios(32);
    public static final String EMISSOR = "corona-api";
    public static final byte[] SEGREDO_DE_RENOVACAO = bytesAleatorios(32);
    /** Limite de erros por IP nos testes (produção: 50). */
    public static final int LIMITE_POR_IP = 12;
    /** Versões mínimas dos apps nos testes (produção: as do .env). */
    public static final String VERSAO_MINIMA_GESTAO = "1.2.0";
    public static final String VERSAO_MINIMA_VENDAS = "2.0.1";
    /** O "Nginx" dos testes (endereço de documentação, RFC 5737): só dele vale o X-Forwarded-For. */
    public static final String PROXY_CONFIAVEL = "192.0.2.10";

    // Primeiro administrador criado ao subir o contexto de teste (senha aleatória, nunca usada).
    public static final String ADMIN_INICIAL_LOGIN = "dona.inicial";
    public static final String ADMIN_INICIAL_SENHA = Base64.getEncoder().encodeToString(bytesAleatorios(18));

    /** Pasta dos arquivos enviados nos testes (documentos de vendedor), uma por execução. */
    protected static final Path PASTA_DE_ARQUIVOS = pastaTemporaria();

    /** Um único contêiner para todos os testes da execução. */
    protected static final PostgreSQLContainer POSTGRES = novoContainer();

    static {
        POSTGRES.start();
    }

    /**
     * Um PostgreSQL novo, igual ao do Compose e com o mesmo {@code 01-papeis.sh}. Quem chama o
     * {@code start()} e o {@code stop()} é o teste (por exemplo, o segundo servidor da restauração).
     */
    public static PostgreSQLContainer novoContainer() {
        Path script = Path.of("..", "infra", "postgres", "init", "01-papeis.sh").toAbsolutePath().normalize();
        return new PostgreSQLContainer("postgres:18.6")
                .withDatabaseName(BANCO)
                .withUsername(ADMIN_USUARIO)
                .withPassword("senha-admin-teste")
                // As mesmas opções do docker-compose.yml.
                .withCommand("postgres", "-c", "max_prepared_transactions=0", "-c", "timezone=UTC")
                .withEnv("DB_MIGRACAO_USER", MIGRACAO_USUARIO)
                .withEnv("DB_MIGRACAO_PASSWORD", MIGRACAO_SENHA)
                .withEnv("DB_APP_USER", APP_USUARIO)
                .withEnv("DB_APP_PASSWORD", APP_SENHA)
                // Executável (0755), senão a imagem só "lê" o script em vez de executá-lo.
                .withCopyFileToContainer(
                        MountableFile.forHostPath(script, 0755),
                        "/docker-entrypoint-initdb.d/01-papeis.sh");
    }

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registro) {
        String url = urlJdbc(POSTGRES);
        registro.add("spring.datasource.url", () -> url);
        registro.add("spring.datasource.username", () -> APP_USUARIO);
        registro.add("spring.datasource.password", () -> APP_SENHA);
        registro.add("spring.flyway.url", () -> url);
        registro.add("spring.flyway.user", () -> MIGRACAO_USUARIO);
        registro.add("spring.flyway.password", () -> MIGRACAO_SENHA);
        // As tabelas só de teste (db/migration-teste, V9000+) entram só aqui, nunca no jar.
        registro.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-teste");
        // A busca de cotações na internet nunca roda nos testes (ADR-0006): quem testa chama a busca com cliente falso.
        registro.add("corona.cotacoes.busca-agendada", () -> "false");
        registro.add("corona.sync.alerta-agendado", () -> "false");
        registro.add("corona.fotos.limpeza-agendada", () -> "false");
        registro.add("corona.limpeza-tecnica.agendada", () -> "false");
        registro.add("corona.conferencia-diaria.agendada", () -> "false");
        // As autorizações vencidas são marcadas pelo teste (ServicoDeAutorizacoes.marcarVencidas), nunca pela agenda.
        registro.add("corona.devolucoes.agenda-das-autorizacoes", () -> "false");
        // Os arquivos enviados nos testes vão para uma pasta temporária, nunca para o projeto.
        registro.add("corona.arquivos.pasta", () -> PASTA_DE_ARQUIVOS.toString());
        registrarSeguranca(registro);
    }

    /** Chaves do token e primeiro administrador de teste (sobrepõem o que vier do .env). */
    protected static void registrarSeguranca(DynamicPropertyRegistry registro) {
        Base64.Encoder base64 = Base64.getEncoder();
        registro.add("corona.seguranca.jwt.chave-atual.id", () -> ID_CHAVE_ATUAL);
        registro.add("corona.seguranca.jwt.chave-atual.valor", () -> base64.encodeToString(CHAVE_ATUAL));
        registro.add("corona.seguranca.jwt.chave-anterior.id", () -> ID_CHAVE_ANTERIOR);
        registro.add("corona.seguranca.jwt.chave-anterior.valor", () -> base64.encodeToString(CHAVE_ANTERIOR));
        registro.add("corona.seguranca.sessao.segredo-de-renovacao", () -> base64.encodeToString(SEGREDO_DE_RENOVACAO));
        // Limite por IP menor que o de produção (50): o teste chega nele sem 50 conferências BCrypt.
        registro.add("corona.seguranca.tentativas.limite-por-ip", () -> LIMITE_POR_IP);
        registro.add("corona.seguranca.tentativas.espera-da-trava", () -> "PT2S");
        registro.add("corona.seguranca.tentativas.proxies-confiaveis", () -> PROXY_CONFIAVEL);
        registro.add("corona.app.versao-minima.gestao", () -> VERSAO_MINIMA_GESTAO);
        registro.add("corona.app.versao-minima.vendas", () -> VERSAO_MINIMA_VENDAS);
        registro.add("corona.admin-inicial.nome", () -> "Dona Inicial");
        registro.add("corona.admin-inicial.login", () -> ADMIN_INICIAL_LOGIN);
        registro.add("corona.admin-inicial.senha", () -> ADMIN_INICIAL_SENHA);
    }

    public static byte[] bytesAleatorios(int quantidade) {
        byte[] bytes = new byte[quantidade];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public static String urlJdbc(PostgreSQLContainer container) {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + BANCO;
    }

    /** Conexão JDBC pura com o usuário da aplicação, sem autocommit (o teste controla a transação). */
    protected static Connection conectarComoAplicacao() throws SQLException {
        return conectar(POSTGRES, APP_USUARIO, APP_SENHA);
    }

    /** Conexão JDBC pura com o usuário das migrações (dono das tabelas), sem autocommit. */
    protected static Connection conectarComoMigracao() throws SQLException {
        return conectar(POSTGRES, MIGRACAO_USUARIO, MIGRACAO_SENHA);
    }

    protected static Connection conectar(PostgreSQLContainer container, String usuario, String senha)
            throws SQLException {
        Connection conexao = DriverManager.getConnection(urlJdbc(container), usuario, senha);
        conexao.setAutoCommit(false);
        return conexao;
    }

    private static Path pastaTemporaria() {
        try {
            Path pasta = Files.createTempDirectory("corona-arquivos-teste");
            pasta.toFile().deleteOnExit();
            return pasta;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
