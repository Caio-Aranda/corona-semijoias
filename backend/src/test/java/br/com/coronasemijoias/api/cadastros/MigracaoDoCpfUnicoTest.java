package br.com.coronasemijoias.api.cadastros;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Banco que já tinha clientes com o mesmo CPF antes da V030, que torna o CPF único entre os não excluídos: nada se
 * perde, fica o cadastro mais antigo com o CPF e os outros ganham o aviso nas observações.
 */
class MigracaoDoCpfUnicoTest extends TesteDeIntegracao {

    private static Flyway flyway(PostgreSQLContainer banco, String ate) {
        return Flyway.configure()
                .dataSource(urlJdbc(banco), MIGRACAO_USUARIO, MIGRACAO_SENHA)
                .schemas("corona").defaultSchema("corona").createSchemas(false)
                .locations("classpath:db/migration")
                .target(ate)
                .load();
    }

    private static void cliente(Connection c, UUID id, String nome, String cpf, boolean excluido) throws Exception {
        try (PreparedStatement sql = c.prepareStatement(
                "INSERT INTO corona.clientes (id, nome, cpf, deleted_at) VALUES (?, ?, ?, " + (excluido ? "now()" : "NULL") + ")")) {
            sql.setObject(1, id);
            sql.setString(2, nome);
            sql.setString(3, cpf);
            sql.executeUpdate();
        }
    }

    @Test
    void cpfRepetidoFicaSoNoMaisAntigoEOsOutrosGanhamAvisoNasObservacoes() throws Exception {
        try (PostgreSQLContainer banco = novoContainer()) {
            banco.start();
            flyway(banco, "26").migrate();
            UUID antigo = UUID.randomUUID();
            UUID repetido = UUID.randomUUID();
            UUID excluido = UUID.randomUUID();
            UUID unico = UUID.randomUUID();
            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA)) {
                cliente(c, antigo, "Maria Antiga", "52998224725", false);
                c.commit(); // created_at é a hora da transação: o mais antigo precisa de transação própria
                Thread.sleep(20);
                cliente(c, repetido, "Maria Repetida", "52998224725", false);
                cliente(c, excluido, "Maria Excluída", "52998224725", true);
                cliente(c, unico, "Ana Única", "39053344705", false);
                c.commit();
            }

            flyway(banco, "latest").migrate();

            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA);
                    ResultSet linhas = c.createStatement().executeQuery(
                            "SELECT id, cpf, observacoes FROM corona.clientes ORDER BY nome")) {
                while (linhas.next()) {
                    UUID id = linhas.getObject("id", UUID.class);
                    if (id.equals(antigo) || id.equals(excluido) || id.equals(unico)) {
                        assertThat(linhas.getString("cpf")).isNotNull();
                        assertThat(linhas.getString("observacoes")).isNull();
                    } else {
                        assertThat(id).isEqualTo(repetido);
                        assertThat(linhas.getString("cpf")).isNull();
                        assertThat(linhas.getString("observacoes")).contains("52998224725").contains(antigo.toString());
                    }
                }
            }
            // E o índice agora barra o repetido.
            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA)) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> cliente(c, UUID.randomUUID(), "De novo", "52998224725", false))
                        .hasMessageContaining("clientes_cpf_unico");
            }
        }
    }
}
