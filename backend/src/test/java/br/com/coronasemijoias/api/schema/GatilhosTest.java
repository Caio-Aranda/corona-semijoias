package br.com.coronasemijoias.api.schema;

import static br.com.coronasemijoias.api.testes.Sql.executar;
import static br.com.coronasemijoias.api.testes.Sql.valor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.identificadores.Identificadores;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gatilhos e privilégios da V002 (ADR-0002, itens 3 e 5), no PostgreSQL real, com o usuário da
 * aplicação e com o usuário das migrações.
 */
class GatilhosTest extends TesteDeIntegracao {

    private static final OffsetDateTime ANO_2000 = OffsetDateTime.parse("2000-01-01T00:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transacao;

    @Autowired
    OrigemDoDispositivo origemDoDispositivo;

    @Autowired
    br.com.coronasemijoias.api.testes.UsuariosDeTeste usuarios;

    /** A origem tem chave estrangeira para dispositivos (V031): o computador precisa estar cadastrado. */
    private UUID aparelhoCadastrado() {
        return usuarios.registrarAparelho(usuarios.criarVendedor(), UuidV7.gerar(), "ATIVO");
    }

    @Test
    void insercaoIgnoraOsCamposDeControleEnviadosPeloCliente() throws SQLException {
        UUID id = UuidV7.gerar();
        try (Connection app = conectarComoAplicacao()) {
            executar(app, """
                    INSERT INTO teste_sync_a (id, texto, created_at, updated_at, version, origem_dispositivo, sync_seq, sync_xid)
                    VALUES (?, 'cliente tentou', ?, ?, 42, ?, 999999999, '1'::xid8)""",
                    id, ANO_2000, ANO_2000, UUID.randomUUID());

            assertThat(valor(app, Integer.class, "SELECT version FROM teste_sync_a WHERE id = ?", id)).isEqualTo(1);
            assertThat(valor(app, Boolean.class,
                    "SELECT created_at = now() AND updated_at = now() FROM teste_sync_a WHERE id = ?", id)).isTrue();
            assertThat(valor(app, UUID.class, "SELECT origem_dispositivo FROM teste_sync_a WHERE id = ?", id)).isNull();
            assertThat(valor(app, Long.class, "SELECT sync_seq FROM teste_sync_a WHERE id = ?", id))
                    .isNotEqualTo(999999999L);
            assertThat(valor(app, Boolean.class,
                    "SELECT sync_xid = pg_current_xact_id() FROM teste_sync_a WHERE id = ?", id)).isTrue();
            app.commit();
        }
    }

    @Test
    void atualizacaoIgnoraOsCamposDeControleEPreservaIdECriacao() throws SQLException {
        UUID id = UuidV7.gerar();
        UUID outroId = UuidV7.gerar();
        try (Connection app = conectarComoAplicacao()) {
            executar(app, "INSERT INTO teste_sync_a (id, texto) VALUES (?, 'antes')", id);
            app.commit();
            OffsetDateTime criacao = valor(app, OffsetDateTime.class, "SELECT created_at FROM teste_sync_a WHERE id = ?", id);
            long seqAntes = valor(app, Long.class, "SELECT sync_seq FROM teste_sync_a WHERE id = ?", id);
            app.commit();

            int alteradas = executar(app, """
                    UPDATE teste_sync_a
                       SET texto = 'depois', id = ?, created_at = ?, version = 99, sync_seq = 1,
                           sync_xid = '1'::xid8, origem_dispositivo = ?
                     WHERE id = ?""", outroId, ANO_2000, UUID.randomUUID(), id);
            assertThat(alteradas).isEqualTo(1);

            assertThat(valor(app, String.class, "SELECT texto FROM teste_sync_a WHERE id = ?", id)).isEqualTo("depois");
            assertThat(valor(app, Long.class, "SELECT count(*) FROM teste_sync_a WHERE id = ?", outroId)).isZero();
            assertThat(valor(app, Integer.class, "SELECT version FROM teste_sync_a WHERE id = ?", id)).isEqualTo(2);
            assertThat(valor(app, OffsetDateTime.class, "SELECT created_at FROM teste_sync_a WHERE id = ?", id))
                    .isEqualTo(criacao);
            assertThat(valor(app, Long.class, "SELECT sync_seq FROM teste_sync_a WHERE id = ?", id))
                    .isGreaterThan(seqAntes);
            assertThat(valor(app, Boolean.class,
                    "SELECT sync_xid = pg_current_xact_id() FROM teste_sync_a WHERE id = ?", id)).isTrue();
            assertThat(valor(app, UUID.class, "SELECT origem_dispositivo FROM teste_sync_a WHERE id = ?", id)).isNull();
            app.commit();
        }
    }

    @Test
    void origemDoDispositivoVemDaSessaoENaoDoCorpo() throws SQLException {
        UUID aparelho = aparelhoCadastrado();
        UUID primeiro = UuidV7.gerar();
        UUID segundo = UuidV7.gerar();
        try (Connection app = conectarComoAplicacao()) {
            valor(app, String.class, "SELECT set_config('corona.origem_dispositivo', ?, true)", aparelho.toString());
            executar(app, "INSERT INTO teste_sync_a (id, texto, origem_dispositivo) VALUES (?, 'x', ?)",
                    primeiro, UUID.randomUUID());
            app.commit();

            // A configuração valia só para a transação anterior.
            executar(app, "INSERT INTO teste_sync_a (id, texto) VALUES (?, 'y')", segundo);
            app.commit();

            // No update, a origem é preservada mesmo com outro aparelho na sessão.
            valor(app, String.class, "SELECT set_config('corona.origem_dispositivo', ?, true)", UUID.randomUUID().toString());
            executar(app, "UPDATE teste_sync_a SET texto = 'z' WHERE id = ?", primeiro);
            app.commit();

            assertThat(valor(app, UUID.class, "SELECT origem_dispositivo FROM teste_sync_a WHERE id = ?", primeiro))
                    .isEqualTo(aparelho);
            assertThat(valor(app, UUID.class, "SELECT origem_dispositivo FROM teste_sync_a WHERE id = ?", segundo))
                    .isNull();
            app.commit();
        }
    }

    @Test
    void origemDoDispositivoPeloJavaSoDentroDeTransacao() {
        UUID aparelho = aparelhoCadastrado();
        UUID id = UuidV7.gerar();
        transacao.executeWithoutResult(estado -> {
            origemDoDispositivo.definirNaTransacaoAtual(aparelho);
            jdbc.update("INSERT INTO teste_sync_a (id, texto) VALUES (?, 'pelo java')", id);
        });
        assertThat(jdbc.queryForObject("SELECT origem_dispositivo FROM teste_sync_a WHERE id = ?", UUID.class, id))
                .isEqualTo(aparelho);
        assertThatThrownBy(() -> origemDoDispositivo.definirNaTransacaoAtual(aparelho))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aAplicacaoNaoTemPermissaoDeApagar() throws SQLException {
        UUID id = inserirEmTesteSyncA();
        try (Connection app = conectarComoAplicacao()) {
            assertThatThrownBy(() -> executar(app, "DELETE FROM teste_sync_a WHERE id = ?", id))
                    .hasMessageContaining("permission denied for table teste_sync_a");
            app.rollback();
            assertThatThrownBy(() -> executar(app, "TRUNCATE teste_sync_a"))
                    .hasMessageContaining("permission denied for table teste_sync_a");
            app.rollback();
        }
    }

    @Test
    void oGatilhoRecusaDeleteETruncateMesmoParaODonoDaTabela() throws SQLException {
        UUID id = inserirEmTesteSyncA();
        try (Connection dono = conectarComoMigracao()) {
            for (String tabela : new String[] {"teste_sync_a", "teste_servidor", "teste_servidor_historico", "sync_estado"}) {
                // A recusa é por comando: vale até para um DELETE que não acharia linha nenhuma.
                assertThatThrownBy(() -> executar(dono, "DELETE FROM " + tabela + " WHERE false"))
                        .hasMessageContaining("Não é permitido apagar registros de " + tabela);
                dono.rollback();
                assertThatThrownBy(() -> executar(dono, "DELETE FROM " + tabela))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("Não é permitido apagar registros de " + tabela + " (comando DELETE)")
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23001"));
                dono.rollback();
                assertThatThrownBy(() -> executar(dono, "TRUNCATE " + tabela))
                        .hasMessageContaining("Não é permitido apagar registros de " + tabela + " (comando TRUNCATE)");
                dono.rollback();
            }
            assertThat(valor(dono, Long.class, "SELECT count(*) FROM teste_sync_a WHERE id = ?", id)).isEqualTo(1);
            dono.rollback();
        }
    }

    @Test
    void tabelaImutavelRecusaUpdate() throws SQLException {
        UUID id = UuidV7.gerar();
        try (Connection app = conectarComoAplicacao()) {
            executar(app, "INSERT INTO teste_sync_imutavel (id, valor) VALUES (?, 10.05)", id);
            executar(app, "INSERT INTO teste_servidor_historico (evento) VALUES ('entrou')");
            app.commit();

            assertThatThrownBy(() -> executar(app, "UPDATE teste_sync_imutavel SET valor = 0 WHERE id = ?", id))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Registros de teste_sync_imutavel não podem ser alterados")
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23001"));
            app.rollback();
            // A exclusão lógica também é um UPDATE: numa tabela imutável, ela também é recusada.
            assertThatThrownBy(() -> executar(app, "UPDATE teste_sync_imutavel SET deleted_at = now() WHERE id = ?", id))
                    .hasMessageContaining("não podem ser alterados");
            app.rollback();
            assertThatThrownBy(() -> executar(app, "UPDATE teste_servidor_historico SET evento = 'outro'"))
                    .hasMessageContaining("Registros de teste_servidor_historico não podem ser alterados");
            app.rollback();
        }
    }

    @Test
    void tabelaDoServidorEditavelTemVersaoEPreservaACriacao() throws SQLException {
        try (Connection app = conectarComoAplicacao()) {
            UUID id = valor(app, UUID.class,
                    "INSERT INTO teste_servidor (descricao, version, created_at) VALUES ('a', 50, ?) RETURNING id", ANO_2000);
            app.commit();
            assertThat(valor(app, Integer.class, "SELECT version FROM teste_servidor WHERE id = ?", id)).isEqualTo(1);
            OffsetDateTime criacao = valor(app, OffsetDateTime.class, "SELECT created_at FROM teste_servidor WHERE id = ?", id);
            assertThat(criacao).isAfter(ANO_2000);
            app.commit();

            executar(app, "UPDATE teste_servidor SET descricao = 'b', version = 7, created_at = ? WHERE id = ?", ANO_2000, id);
            app.commit();
            assertThat(valor(app, Integer.class, "SELECT version FROM teste_servidor WHERE id = ?", id)).isEqualTo(2);
            assertThat(valor(app, OffsetDateTime.class, "SELECT created_at FROM teste_servidor WHERE id = ?", id))
                    .isEqualTo(criacao);
            assertThat(Identificadores.aceito(id)).as("uuidv7() do PostgreSQL passa no validador").isTrue();
            app.commit();
        }
    }

    @Test
    void identificadorForaDeV4EV7ERecusadoPeloBanco() throws SQLException {
        try (Connection app = conectarComoAplicacao()) {
            assertThatThrownBy(() -> executar(app, "INSERT INTO teste_sync_a (id, texto) VALUES (?, 'v1')",
                            UUID.fromString("c232ab00-9414-11ec-b3c8-9f6bdeced846")))
                    .hasMessageContaining("teste_sync_a_id_v4_ou_v7");
            app.rollback();
            assertThatThrownBy(() -> executar(app, "INSERT INTO teste_sync_a (id, texto) VALUES (?, 'nulo')",
                            new UUID(0, 0)))
                    .hasMessageContaining("teste_sync_a_id_v4_ou_v7");
            app.rollback();
            executar(app, "INSERT INTO teste_sync_a (id, texto) VALUES (?, 'v4')", UUID.randomUUID());
            app.commit();
        }
    }

    @Test
    void aAplicacaoNaoConsegueDesligarOsGatilhos() throws SQLException {
        try (Connection app = conectarComoAplicacao()) {
            assertThatThrownBy(() -> executar(app, "ALTER TABLE teste_sync_a DISABLE TRIGGER USER"))
                    .hasMessageContaining("must be owner of table teste_sync_a");
            app.rollback();
            assertThatThrownBy(() -> executar(app, "SET session_replication_role = replica"))
                    .hasMessageContaining("permission denied to set parameter \"session_replication_role\"");
            app.rollback();
            assertThatThrownBy(() -> valor(app, String.class,
                            "SELECT corona.aplicar_convencoes_sincronizavel('corona.teste_sync_a')"))
                    .hasMessageContaining("permission denied for function aplicar_convencoes_sincronizavel");
            app.rollback();
        }
    }

    @Test
    void aAplicacaoSoLeASyncEstado() throws SQLException {
        try (Connection app = conectarComoAplicacao()) {
            assertThat(valor(app, Long.class, "SELECT count(*) FROM sync_estado")).isEqualTo(1);
            assertThat(valor(app, Long.class, "SELECT epoca FROM sync_estado")).isPositive();
            app.rollback();
            assertThatThrownBy(() -> executar(app, "UPDATE sync_estado SET epoca = epoca + 1"))
                    .hasMessageContaining("permission denied for table sync_estado");
            app.rollback();
            assertThatThrownBy(() -> executar(app, "INSERT INTO sync_estado DEFAULT VALUES"))
                    .hasMessageContaining("permission denied for table sync_estado");
            app.rollback();
        }
    }

    @Test
    void syncEstadoTemUmaLinhaSo() throws SQLException {
        try (Connection dono = conectarComoMigracao()) {
            assertThatThrownBy(() -> executar(dono, "INSERT INTO sync_estado DEFAULT VALUES"))
                    .hasMessageContaining("sync_estado_unica");
            dono.rollback();
            assertThatThrownBy(() -> executar(dono, "INSERT INTO sync_estado (unica) VALUES (false)"))
                    .hasMessageContaining("sync_estado_uma_linha_so");
            dono.rollback();
        }
    }

    @Test
    void limitesDeTransacaoValemSoParaAAplicacao() throws SQLException {
        try (Connection app = conectarComoAplicacao(); Connection dono = conectarComoMigracao()) {
            assertThat(valor(app, String.class, "SHOW transaction_timeout")).isEqualTo("1min");
            assertThat(valor(app, String.class, "SHOW idle_in_transaction_session_timeout")).isEqualTo("30s");
            assertThat(valor(dono, String.class, "SHOW transaction_timeout")).isEqualTo("0");
            assertThat(valor(dono, String.class, "SHOW idle_in_transaction_session_timeout")).isEqualTo("0");
            app.rollback();
            dono.rollback();
        }
    }

    @Test
    void funcoesDeConvencaoExplicamOErro() throws SQLException {
        try (Connection dono = conectarComoMigracao()) {
            executar(dono, "CREATE TABLE corona.sem_id (codigo integer PRIMARY KEY)");
            assertThatThrownBy(() -> valor(dono, String.class,
                            "SELECT corona.aplicar_convencoes_sincronizavel('corona.sem_id')"))
                    .hasMessageContaining("precisa ter a coluna \"id uuid PRIMARY KEY\"");
            dono.rollback();

            executar(dono, "CREATE TABLE corona.com_controle (id uuid PRIMARY KEY, version integer, sync_seq bigint)");
            assertThatThrownBy(() -> valor(dono, String.class,
                            "SELECT corona.aplicar_convencoes_sincronizavel('corona.com_controle')"))
                    .hasMessageContaining("Não declare sync_seq, version na tabela");
            dono.rollback();

            executar(dono, "CREATE TABLE corona.com_linha (id uuid PRIMARY KEY)");
            executar(dono, "INSERT INTO corona.com_linha VALUES (gen_random_uuid())");
            assertThatThrownBy(() -> valor(dono, String.class,
                            "SELECT corona.aplicar_convencoes_sincronizavel('corona.com_linha')"))
                    .hasMessageContaining("com a tabela corona.com_linha vazia");
            dono.rollback();

            executar(dono, "CREATE TABLE corona.sem_decisao (id uuid PRIMARY KEY)");
            assertThatThrownBy(() -> valor(dono, String.class,
                            "SELECT corona.aplicar_convencoes_do_servidor('corona.sem_decisao', NULL)"))
                    .hasMessageContaining("Informe editavel => true ou editavel => false");
            dono.rollback();
        }
    }

    private UUID inserirEmTesteSyncA() throws SQLException {
        UUID id = UuidV7.gerar();
        try (Connection app = conectarComoAplicacao()) {
            executar(app, "INSERT INTO teste_sync_a (id, texto) VALUES (?, 'para apagar')", id);
            app.commit();
        }
        return id;
    }
}
