package br.com.coronasemijoias.api.testes;

import java.nio.file.Path;
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
@Import(RotasDeTeste.class)
public abstract class TesteDeIntegracao {

    // Valores falsos, só de teste (o contêiner some quando os testes terminam).
    protected static final String BANCO = "corona";
    protected static final String ADMIN_USUARIO = "admin_teste";
    protected static final String MIGRACAO_USUARIO = "migracao_teste";
    protected static final String MIGRACAO_SENHA = "senha-migracao-teste";
    protected static final String APP_USUARIO = "app_teste";
    protected static final String APP_SENHA = "senha-app-teste";

    /** Um único contêiner para todos os testes da execução. */
    private static final PostgreSQLContainer POSTGRES = criarContainer();

    static {
        POSTGRES.start();
    }

    private static PostgreSQLContainer criarContainer() {
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
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + BANCO;
        registro.add("spring.datasource.url", () -> url);
        registro.add("spring.datasource.username", () -> APP_USUARIO);
        registro.add("spring.datasource.password", () -> APP_SENHA);
        registro.add("spring.flyway.url", () -> url);
        registro.add("spring.flyway.user", () -> MIGRACAO_USUARIO);
        registro.add("spring.flyway.password", () -> MIGRACAO_SENHA);
    }
}
