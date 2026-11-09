package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class BancoDeDadosTest extends TesteDeIntegracao {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextoSobeEFlywayAplicaAsMigracoes() throws Exception {
        // O histórico do Flyway só é visível para o usuário das migrações (V002).
        List<String> versoes = new ArrayList<>();
        try (Connection migracao = conectarComoMigracao();
                Statement comando = migracao.createStatement();
                ResultSet linhas = comando.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (linhas.next()) {
                versoes.add(linhas.getString(1));
            }
        }
        assertThat(versoes).contains("001", "002", "003", "9001");
    }

    @Test
    void historicoDoFlywayPertenceAoUsuarioDasMigracoes() throws Exception {
        String dono = jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'corona' AND tablename = 'flyway_schema_history'",
                String.class);
        assertThat(dono).isEqualTo(MIGRACAO_USUARIO);
        try (Connection migracao = conectarComoMigracao();
                Statement comando = migracao.createStatement();
                ResultSet linhas = comando.executeQuery(
                        "SELECT DISTINCT installed_by FROM flyway_schema_history")) {
            assertThat(linhas.next()).isTrue();
            assertThat(linhas.getString(1)).isEqualTo(MIGRACAO_USUARIO);
            assertThat(linhas.next()).isFalse();
        }
    }

    @Test
    void aAplicacaoNaoLeNemGravaNoHistoricoDoFlyway() {
        assertThatThrownBy(() -> jdbc.queryForList("SELECT version FROM flyway_schema_history"))
                .rootCause()
                .hasMessageContaining("permission denied for table flyway_schema_history");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE flyway_schema_history SET success = true WHERE version = '001'"))
                .rootCause()
                .hasMessageContaining("permission denied for table flyway_schema_history");
    }

    @Test
    void aAplicacaoConectaComOUsuarioDaAplicacao() {
        String usuario = jdbc.queryForObject("SELECT current_user", String.class);
        assertThat(usuario).isEqualTo(APP_USUARIO);
    }

    @Test
    void aAplicacaoNaoCriaTabelasNoSchema() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE corona.nao_pode (id int)"))
                .rootCause()
                .hasMessageContaining("permission denied for schema corona");
    }

    @Test
    void aConexaoDaAplicacaoTrabalhaEmUtc() {
        String fuso = jdbc.queryForObject("SHOW timezone", String.class);
        assertThat(fuso).isEqualTo("UTC");
    }
}
