package br.com.coronasemijoias.api.sync;

import static br.com.coronasemijoias.api.testes.Sql.executar;
import static br.com.coronasemijoias.api.testes.Sql.valor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.testes.LeituraDoPull;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Restauração de backup (ADR-0002, item 5, R1): {@code pg_dump} do banco dos testes, restaurado
 * num segundo PostgreSQL novo, com o mesmo {@code 01-papeis.sh}. Sem o procedimento, o pull deixa
 * linhas para trás (o teste prova que enxerga o problema); com ele, tudo chega e a época sobe.
 */
class RestauracaoDoSyncTest extends TesteDeIntegracao {

    private static final Logger LOG = LoggerFactory.getLogger(RestauracaoDoSyncTest.class);
    private static final String BACKUP = "/tmp/backup.dump";

    @Autowired
    ServicoDePull pull;

    @Autowired
    RegistroDeTabelas registro;

    @TempDir
    Path pasta;

    @Test
    void semOProcedimentoLinhasFicamParaTrasEComEleTudoChegaEAEpocaSobe() throws Exception {
        // O aparelho está em dia com o servidor antigo.
        String cursorDoAparelho = LeituraDoPull.esvaziar(pull, null).cursorFinal();
        long epocaAntiga = CursorDeSync.ler(cursorDoAparelho).epoca();

        // O servidor antigo já rodou muitas transações: as linhas novas ficam com sync_xid alto.
        queimarIdentificadoresDeTransacao(2000);
        List<UUID> novas = new ArrayList<>();
        try (Connection app = conectarComoAplicacao()) {
            for (int i = 0; i < 5; i++) {
                UUID id = UuidV7.gerar();
                String tabela = i % 2 == 0 ? "teste_sync_a (id, texto)" : "teste_sync_b (id, nome)";
                executar(app, "INSERT INTO " + tabela + " VALUES (?, ?)", id, "antes do backup " + i);
                app.commit();
                novas.add(id);
            }
        }
        long menorXidDasNovas = menorXid(novas);

        Path backup = fazerBackup();

        try (PostgreSQLContainer novo = novoContainer()) {
            novo.start();
            restaurar(novo, backup);
            ServicoDePull pullNoNovo = new ServicoDePull(dataSourceDaAplicacao(novo), registro);
            Set<UUID> todasNoNovo = idsSincronizaveis(novo);

            // ---- Sem o procedimento ----
            long xminDoNovo;
            try (Connection app = conectar(novo, APP_USUARIO, APP_SENHA)) {
                xminDoNovo = Long.parseLong(valor(app, String.class, "SELECT pg_snapshot_xmin(pg_current_snapshot())::text"));
                app.rollback();
            }
            assertThat(menorXidDasNovas)
                    .as("pré-condição: as linhas novas têm sync_xid acima do xmin do servidor novo")
                    .isGreaterThan(xminDoNovo);
            List<UUID> cargaSemProcedimento = ids(LeituraDoPull.esvaziar(pullNoNovo, null));
            List<UUID> cursorSemProcedimento = ids(LeituraDoPull.esvaziar(pullNoNovo, cursorDoAparelho));
            assertThat(cargaSemProcedimento).doesNotContainAnyElementsOf(novas);
            assertThat(cursorSemProcedimento).doesNotContainAnyElementsOf(novas);
            LOG.info("Restauração SEM o procedimento: xmin do servidor novo = {}, menor sync_xid das 5 linhas novas = {}. "
                            + "Carga completa entregou {} de {} linhas; o aparelho em dia recebeu {} das 5 linhas novas.",
                    xminDoNovo, menorXidDasNovas, cargaSemProcedimento.size(), todasNoNovo.size(),
                    novas.stream().filter(cursorSemProcedimento::contains).count());

            // ---- Com o procedimento ----
            long epocaNova;
            try (Connection dono = conectar(novo, MIGRACAO_USUARIO, MIGRACAO_SENHA)) {
                epocaNova = valor(dono, Long.class, "SELECT corona.preparar_sync_apos_restauracao()");
                dono.commit();
            }
            assertThat(epocaNova).isEqualTo(epocaAntiga + 1);

            assertThatThrownBy(() -> pullNoNovo.puxar(cursorDoAparelho, 100, FiltroDoVendedor.SEM_FILTRO))
                    .isInstanceOf(CursorInvalidoException.class);
            LeituraDoPull cargaCompleta = LeituraDoPull.esvaziar(pullNoNovo, null);
            assertThat(new HashSet<>(ids(cargaCompleta))).isEqualTo(todasNoNovo).containsAll(novas);
            assertThat(ids(cargaCompleta)).doesNotHaveDuplicates();
            assertThat(cargaCompleta.cursorFinal()).startsWith(epocaNova + ":");

            // Uma gravação depois da restauração chega depois das antigas, com sync_xid acima de zero.
            UUID depois = UuidV7.gerar();
            try (Connection app = conectar(novo, APP_USUARIO, APP_SENHA)) {
                executar(app, "INSERT INTO teste_sync_a (id, texto) VALUES (?, 'depois da restauração')", depois);
                app.commit();
            }
            LeituraDoPull continuacao = LeituraDoPull.esvaziar(pullNoNovo, cargaCompleta.cursorFinal());
            assertThat(ids(continuacao)).containsExactly(depois);
            assertThat(continuacao.linhas().get(0).syncXid()).isPositive();

            LOG.info("Restauração COM o procedimento: época {} -> {}; o cursor antigo recebeu \"cursor inválido\"; "
                            + "a carga completa entregou {} de {} linhas (as 5 novas inclusive); "
                            + "a gravação feita depois chegou em seguida.",
                    epocaAntiga, epocaNova, cargaCompleta.linhas().size(), todasNoNovo.size());
        }
    }

    private static void queimarIdentificadoresDeTransacao(int quantidade) throws Exception {
        try (Connection app = conectarComoAplicacao(); Statement comando = app.createStatement()) {
            app.setAutoCommit(true);
            for (int i = 0; i < quantidade; i++) {
                comando.execute("SELECT pg_current_xact_id()");
            }
        }
    }

    private static long menorXid(List<UUID> ids) throws Exception {
        long menor = Long.MAX_VALUE;
        try (Connection app = conectarComoAplicacao()) {
            for (UUID id : ids) {
                String xid = valor(app, String.class, """
                        SELECT sync_xid::text FROM teste_sync_a WHERE id = ?
                        UNION ALL SELECT sync_xid::text FROM teste_sync_b WHERE id = ?""", id, id);
                menor = Math.min(menor, Long.parseLong(xid));
            }
            app.rollback();
        }
        return menor;
    }

    private Path fazerBackup() throws Exception {
        rodar(POSTGRES, "pg_dump -U " + MIGRACAO_USUARIO + " -d " + BANCO + " -n corona -Fc -f " + BACKUP,
                MIGRACAO_SENHA);
        Path local = pasta.resolve("backup.dump");
        POSTGRES.copyFileFromContainer(BACKUP, local.toString());
        assertThat(Files.size(local)).isPositive();
        return local;
    }

    /** O schema "corona" já existe no servidor novo (01-papeis.sh): restaura tudo menos ele. */
    private static void restaurar(PostgreSQLContainer novo, Path backup) throws Exception {
        novo.copyFileToContainer(MountableFile.forHostPath(backup), BACKUP);
        rodar(novo, "pg_restore -l " + BACKUP + " | grep -v -E '^[0-9]+; [0-9]+ [0-9]+ SCHEMA - corona ' > /tmp/lista"
                + " && pg_restore -U " + MIGRACAO_USUARIO + " -d " + BANCO
                + " --no-owner --single-transaction --exit-on-error -L /tmp/lista " + BACKUP, MIGRACAO_SENHA);
    }

    private static void rodar(PostgreSQLContainer container, String comando, String senha) throws Exception {
        ExecResult resultado = container.execInContainer("sh", "-c", "PGPASSWORD='" + senha + "' " + comando);
        assertThat(resultado.getExitCode())
                .as("%s%nsaída: %s%nerro: %s", comando, resultado.getStdout(), resultado.getStderr())
                .isZero();
    }

    private static DriverManagerDataSource dataSourceDaAplicacao(PostgreSQLContainer container) {
        return new DriverManagerDataSource(urlJdbc(container), APP_USUARIO, APP_SENHA);
    }

    private Set<UUID> idsSincronizaveis(PostgreSQLContainer container) throws Exception {
        Set<UUID> ids = new HashSet<>();
        try (Connection app = conectar(container, APP_USUARIO, APP_SENHA)) {
            for (TabelaSincronizavel tabela : registro.sincronizaveis()) {
                ids.addAll(br.com.coronasemijoias.api.testes.Sql.valores(app, UUID.class,
                        "SELECT id FROM corona." + tabela.nome()));
            }
            app.rollback();
        }
        return ids;
    }

    private static List<UUID> ids(LeituraDoPull leitura) {
        return leitura.linhas().stream().map(LinhaDoPull::id).toList();
    }
}
