package br.com.coronasemijoias.api.vendedores;

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
 * Banco que já tinha usuários VENDEDOR e clientes antes da tarefa 1.3 (as migrações V011 e V012 rodam
 * por cima de dados existentes): ninguém perde acesso e todo usuário VENDEDOR passa a ter cadastro.
 */
class MigracaoDosVendedoresTest extends TesteDeIntegracao {

    private static final String HASH = "$2a$04$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234";

    private static Flyway flyway(PostgreSQLContainer banco, String ate) {
        return Flyway.configure()
                .dataSource(urlJdbc(banco), MIGRACAO_USUARIO, MIGRACAO_SENHA)
                .schemas("corona").defaultSchema("corona").createSchemas(false)
                .locations("classpath:db/migration")
                .target(ate)
                .load();
    }

    private static void usuario(Connection c, UUID id, String login, String perfil, UUID vendedorId) throws Exception {
        try (PreparedStatement sql = c.prepareStatement("""
                INSERT INTO corona.usuarios (id, nome, login, senha_hash, perfil, vendedor_id)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            sql.setObject(1, id);
            sql.setString(2, "Nome " + login);
            sql.setString(3, login);
            sql.setString(4, HASH);
            sql.setString(5, perfil);
            sql.setObject(6, vendedorId);
            sql.executeUpdate();
        }
    }

    @Test
    void usuariosVendedorAntigosGanhamCadastroEClientesPerdemSoOVinculoInexistente() throws Exception {
        try (PostgreSQLContainer banco = novoContainer()) {
            banco.start();
            flyway(banco, "10").migrate();

            UUID semVinculo = UUID.randomUUID();
            UUID comIdSemCadastro = UUID.randomUUID();
            UUID idOrfao = UUID.randomUUID();
            UUID dona = UUID.randomUUID();
            UUID cliente = UUID.randomUUID();
            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA)) {
                usuario(c, semVinculo, "vendedor.um", "VENDEDOR", null);
                usuario(c, comIdSemCadastro, "vendedor.dois", "VENDEDOR", idOrfao);
                usuario(c, dona, "dona.um", "ADMIN", null);
                try (PreparedStatement sql = c.prepareStatement(
                        "INSERT INTO corona.clientes (id, nome, vendedor_responsavel_id) VALUES (?, 'Cliente Antigo', ?)")) {
                    sql.setObject(1, cliente);
                    sql.setObject(2, UUID.randomUUID());
                    sql.executeUpdate();
                }
                c.commit();
            }

            flyway(banco, "latest").migrate();

            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA);
                    ResultSet linhas = c.createStatement().executeQuery("""
                            SELECT u.login, u.vendedor_id, v.nome, v.limite_consignacao, v.comissao_percentual, v.ativo
                              FROM corona.usuarios u LEFT JOIN corona.vendedores v ON v.id = u.vendedor_id
                             ORDER BY u.login""")) {
                linhas.next();
                assertThat(linhas.getString("login")).isEqualTo("dona.um");
                assertThat(linhas.getObject("vendedor_id")).isNull();
                linhas.next();
                assertThat(linhas.getString("login")).isEqualTo("vendedor.dois");
                assertThat(linhas.getObject("vendedor_id", UUID.class)).isEqualTo(idOrfao);
                assertThat(linhas.getString("nome")).isEqualTo("Nome vendedor.dois");
                linhas.next();
                assertThat(linhas.getString("login")).isEqualTo("vendedor.um");
                assertThat(linhas.getObject("vendedor_id", UUID.class)).isNotNull();
                assertThat(linhas.getString("nome")).isEqualTo("Nome vendedor.um");
                assertThat(linhas.getBigDecimal("limite_consignacao")).isEqualByComparingTo("0");
                assertThat(linhas.getBigDecimal("comissao_percentual")).isEqualByComparingTo("0");
                assertThat(linhas.getBoolean("ativo")).isTrue();
            }
            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA);
                    ResultSet linhas = c.createStatement().executeQuery("""
                            SELECT nome, vendedor_responsavel_id, deleted_at FROM corona.clientes""")) {
                linhas.next();
                assertThat(linhas.getString("nome")).isEqualTo("Cliente Antigo");
                assertThat(linhas.getObject("vendedor_responsavel_id")).isNull();
                assertThat(linhas.getObject("deleted_at")).isNull();
            }
            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA);
                    ResultSet linhas = c.createStatement().executeQuery(
                            "SELECT count(DISTINCT codigo) = count(*) AND min(codigo) >= 1 FROM corona.vendedores")) {
                linhas.next();
                assertThat(linhas.getBoolean(1)).as("códigos sequenciais e únicos").isTrue();
            }
        }
    }
}
