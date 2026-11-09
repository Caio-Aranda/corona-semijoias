package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class BancoDeDadosTest extends TesteDeIntegracao {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextoSobeEFlywayAplicaAPrimeiraMigracao() {
        List<String> versoes = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versoes).contains("001");
    }

    @Test
    void historicoDoFlywayPertenceAoUsuarioDasMigracoes() {
        String dono = jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'corona' AND tablename = 'flyway_schema_history'",
                String.class);
        String quemInstalou = jdbc.queryForObject(
                "SELECT installed_by FROM flyway_schema_history WHERE version = '001'", String.class);
        assertThat(dono).isEqualTo(MIGRACAO_USUARIO);
        assertThat(quemInstalou).isEqualTo(MIGRACAO_USUARIO);
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
