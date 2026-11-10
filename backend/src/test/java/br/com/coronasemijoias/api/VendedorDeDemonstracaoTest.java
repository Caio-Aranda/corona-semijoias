package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** O vendedor de demonstração nasce só no perfil dev, uma vez só, e nunca em outro perfil. */
class VendedorDeDemonstracaoTest extends TesteDeIntegracao {

    private static Map<String, String> configuracao(PostgreSQLContainer banco, String perfil) {
        String url = urlJdbc(banco);
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
        c.put("corona.admin-inicial.login", "dona.loja");
        c.put("corona.admin-inicial.senha", "Senha-Inicial-123");
        c.put("corona.vendedor-demo.nome", "Vendedora de Teste");
        c.put("corona.vendedor-demo.login", "Vendedora.Teste");
        c.put("corona.vendedor-demo.senha", "Senha-Demo-123");
        c.put("spring.profiles.active", perfil);
        return c;
    }

    private static void subirEFechar(Map<String, String> configuracao) {
        List<String> argumentos = new ArrayList<>();
        configuracao.forEach((chave, valor) -> argumentos.add("--" + chave + "=" + valor));
        try (ConfigurableApplicationContext contexto =
                new SpringApplication(CoronaApiApplication.class).run(argumentos.toArray(String[]::new))) {
            assertThat(contexto.isActive()).isTrue();
        }
    }

    private static List<String> vendedores(PostgreSQLContainer banco) throws Exception {
        List<String> logins = new ArrayList<>();
        try (Connection conexao = conectar(banco, APP_USUARIO, APP_SENHA);
                ResultSet linhas = conexao.createStatement()
                        .executeQuery("SELECT login FROM corona.usuarios WHERE perfil = 'VENDEDOR'")) {
            while (linhas.next()) {
                logins.add(linhas.getString(1));
            }
        }
        return logins;
    }

    @Test
    void nasceSoNoPerfilDevEUmaVezSo() throws Exception {
        try (PostgreSQLContainer banco = novoContainer()) {
            banco.start();
            subirEFechar(configuracao(banco, "dev"));
            assertThat(vendedores(banco)).containsExactly("vendedora.teste");
            subirEFechar(configuracao(banco, "dev"));
            assertThat(vendedores(banco)).containsExactly("vendedora.teste");
            assertThat(cadastrosLigados(banco)).isEqualTo(1);
        }
    }

    /** Quantos vendedores o usuário de demonstração tem ligados (1: o cadastro criado junto com ele). */
    private static int cadastrosLigados(PostgreSQLContainer banco) throws Exception {
        try (Connection conexao = conectar(banco, APP_USUARIO, APP_SENHA);
                ResultSet linhas = conexao.createStatement().executeQuery("""
                        SELECT count(*) FROM corona.usuarios u JOIN corona.vendedores v ON v.id = u.vendedor_id
                         WHERE u.login = 'vendedora.teste' AND v.nome = 'Vendedora de Teste'""")) {
            linhas.next();
            return linhas.getInt(1);
        }
    }

    @Test
    void foraDoPerfilDevNaoNasce() throws Exception {
        try (PostgreSQLContainer banco = novoContainer()) {
            banco.start();
            subirEFechar(configuracao(banco, "prod"));
            assertThat(vendedores(banco)).isEmpty();
        }
    }
}
