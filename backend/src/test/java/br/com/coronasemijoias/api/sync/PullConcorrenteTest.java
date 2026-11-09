package br.com.coronasemijoias.api.sync;

import static br.com.coronasemijoias.api.testes.Sql.executar;
import static br.com.coronasemijoias.api.testes.Sql.valor;
import static br.com.coronasemijoias.api.testes.Sql.valores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.sync.FiltroDoVendedor.CondicaoSql;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Testes determinísticos do pull com limite seguro (ADR-0002, item 5): duas conexões, A e B,
 * controladas passo a passo pelo teste, contra o PostgreSQL real. Cada teste começa do fim atual
 * do pull, então os dados dos outros testes não atrapalham.
 */
class PullConcorrenteTest extends TesteDeIntegracao {

    private static final FiltroDoVendedor TODOS = FiltroDoVendedor.SEM_FILTRO;

    @Autowired
    ServicoDePull pull;

    @Test
    void transacaoAbertaSeguraAsConfirmadasDepoisDelaEDepoisEntregaTudoNaOrdem() throws SQLException {
        String inicio = fimAtual();
        UUID deA = UuidV7.gerar();
        UUID deB = UuidV7.gerar();
        try (Connection a = conectarComoAplicacao(); Connection b = conectarComoAplicacao()) {
            inserirA(a, deA, "de A"); // A grava primeiro: recebe o identificador de transação menor
            inserirA(b, deB, "de B");
            b.commit();

            // O pull ingênuo (só pelo sync_seq) veria só a linha de B, que tem sync_seq MAIOR que a
            // de A: guardaria o cursor depois de B e nunca mais entregaria a de A.
            try (Connection outra = conectarComoAplicacao()) {
                assertThat(valores(outra, UUID.class,
                        "SELECT id FROM teste_sync_a WHERE id IN (?, ?) ORDER BY sync_seq", deA, deB))
                        .containsExactly(deB);
                long seqDeB = valor(outra, Long.class, "SELECT sync_seq FROM teste_sync_a WHERE id = ?", deB);
                assertThat(valor(a, Long.class, "SELECT sync_seq FROM teste_sync_a WHERE id = ?", deA))
                        .isLessThan(seqDeB);
                outra.rollback();
            }
            LoteDoPull comAAberta = pull.puxar(inicio, 100, TODOS);
            assertThat(comAAberta.linhas()).isEmpty();
            assertThat(comAAberta.proximoCursor()).isEqualTo(inicio);

            a.commit();
        }
        LoteDoPull depois = pull.puxar(inicio, 100, TODOS);
        assertThat(ids(depois)).containsExactly(deA, deB);
        LoteDoPull vazio = pull.puxar(depois.proximoCursor(), 100, TODOS);
        assertThat(vazio.linhas()).isEmpty();
        assertThat(vazio.proximoCursor()).isEqualTo(depois.proximoCursor());
        assertThat(vazio.haMais()).isFalse();
    }

    @Test
    void transacaoDeIdentificadorMenorConfirmandoPrimeiroSaiLogo() throws SQLException {
        String inicio = fimAtual();
        UUID deA = UuidV7.gerar();
        UUID deB = UuidV7.gerar();
        String meio;
        try (Connection a = conectarComoAplicacao(); Connection b = conectarComoAplicacao()) {
            inserirA(a, deA, "de A");
            inserirA(b, deB, "de B");
            a.commit();

            LoteDoPull soA = pull.puxar(inicio, 100, TODOS);
            assertThat(ids(soA)).containsExactly(deA);
            meio = soA.proximoCursor();

            b.commit();
        }
        assertThat(ids(pull.puxar(meio, 100, TODOS))).containsExactly(deB);
    }

    @Test
    void savepointMantemOMesmoIdentificadorEOQueFoiDesfeitoNuncaAparece() throws SQLException {
        String inicio = fimAtual();
        UUID antes = UuidV7.gerar();
        UUID desfeita = UuidV7.gerar();
        UUID depois = UuidV7.gerar();
        UUID dentroDoRelease = UuidV7.gerar();
        UUID transacaoDesfeita = UuidV7.gerar();
        try (Connection a = conectarComoAplicacao()) {
            inserirA(a, antes, "antes do savepoint");
            executar(a, "SAVEPOINT s1");
            inserirA(a, desfeita, "desfeita pelo rollback to");
            executar(a, "ROLLBACK TO SAVEPOINT s1");
            inserirA(a, depois, "depois do rollback to");
            executar(a, "SAVEPOINT s2");
            inserirB(a, dentroDoRelease, "mantida pelo release");
            executar(a, "RELEASE SAVEPOINT s2");
            a.commit();

            inserirA(a, transacaoDesfeita, "rollback total");
            a.rollback();
        }

        LoteDoPull lote = pull.puxar(inicio, 100, TODOS);
        assertThat(ids(lote)).containsExactly(antes, depois, dentroDoRelease);
        assertThat(lote.linhas()).extracting(LinhaDoPull::syncXid).containsOnly(lote.linhas().get(0).syncXid());
        assertThat(ids(pull.puxar(lote.proximoCursor(), 100, TODOS))).isEmpty();
    }

    @Test
    void linhaEntregueQueMudaVoltaComVersaoNovaEExclusaoVoltaComoLapide() throws SQLException {
        String inicio = fimAtual();
        UUID id = UuidV7.gerar();
        try (Connection a = conectarComoAplicacao()) {
            inserirA(a, id, "versão 1");
            a.commit();
            LoteDoPull primeira = pull.puxar(inicio, 100, TODOS);
            assertThat(primeira.linhas()).singleElement().satisfies(l -> {
                assertThat(l.id()).isEqualTo(id);
                assertThat(l.versao()).isEqualTo(1);
                assertThat(l.excluidaEm()).isNull();
            });

            executar(a, "UPDATE teste_sync_a SET texto = 'versão 2' WHERE id = ?", id);
            a.commit();
            LoteDoPull segunda = pull.puxar(primeira.proximoCursor(), 100, TODOS);
            assertThat(segunda.linhas()).singleElement().satisfies(l -> {
                assertThat(l.versao()).isEqualTo(2);
                assertThat(l.colunas()).containsEntry("texto", "versão 2");
            });

            executar(a, "UPDATE teste_sync_a SET deleted_at = now() WHERE id = ?", id);
            a.commit();
            LoteDoPull terceira = pull.puxar(segunda.proximoCursor(), 100, TODOS);
            assertThat(terceira.linhas()).singleElement().satisfies(l -> {
                assertThat(l.versao()).isEqualTo(3);
                assertThat(l.excluidaEm()).as("lápide").isNotNull();
            });
        }
    }

    @Test
    void loteMenorQueUmaTransacaoNaoRepeteNemPulaLinha() throws SQLException {
        String inicio = fimAtual();
        List<UUID> gravadas = new ArrayList<>();
        try (Connection a = conectarComoAplicacao()) {
            for (int i = 0; i < 7; i++) {
                UUID id = UuidV7.gerar();
                // Intercala as duas tabelas: a ordem do pull é a ordem do sync_seq, entre tabelas também.
                if (i % 2 == 0) {
                    inserirA(a, id, "linha " + i);
                } else {
                    inserirB(a, id, "linha " + i);
                }
                gravadas.add(id);
            }
            a.commit();
        }

        List<UUID> recebidas = new ArrayList<>();
        List<Integer> tamanhos = new ArrayList<>();
        Set<Long> xids = new HashSet<>();
        String cursor = inicio;
        LoteDoPull lote;
        do {
            lote = pull.puxar(cursor, 3, TODOS);
            tamanhos.add(lote.linhas().size());
            recebidas.addAll(ids(lote));
            lote.linhas().forEach(l -> xids.add(l.syncXid()));
            cursor = lote.proximoCursor();
        } while (!lote.linhas().isEmpty());

        assertThat(tamanhos).containsExactly(3, 3, 1, 0);
        assertThat(recebidas).containsExactlyElementsOf(gravadas);
        assertThat(xids).as("todas da mesma transação").hasSize(1);
    }

    @Test
    void aOrdemEPelaPosicaoNumericaQuandoOXidMudaDeQuantidadeDeDigitos() throws SQLException {
        // Regressão achada pelo estresse: ordenar pelo texto do xid põe '1000' antes de '999' e o
        // LIMIT corta a linha errada. As duas linhas recebem sync_xid à mão (gatilhos desligados).
        UUID de999 = UuidV7.gerar();
        UUID de1000 = UuidV7.gerar();
        try (Connection dono = conectarComoMigracao()) {
            executar(dono, "ALTER TABLE teste_sync_a DISABLE TRIGGER USER");
            executar(dono, "INSERT INTO teste_sync_a (id, texto, sync_seq, sync_xid)"
                    + " VALUES (?, 'xid 1000', nextval('corona.sync_seq_global'), '1000'::xid8)", de1000);
            executar(dono, "INSERT INTO teste_sync_a (id, texto, sync_seq, sync_xid)"
                    + " VALUES (?, 'xid 999', nextval('corona.sync_seq_global'), '999'::xid8)", de999);
            executar(dono, "ALTER TABLE teste_sync_a ENABLE TRIGGER USER");
            dono.commit();
        }
        // O limite seguro precisa estar acima de 1000.
        try (Connection app = conectarComoAplicacao()) {
            app.setAutoCommit(true);
            while (Long.parseLong(valor(app, String.class, "SELECT pg_current_xact_id()::text")) <= 1001) {
                // cada SELECT em autocommit é uma transação nova e gasta um identificador
            }
        }
        long epoca = CursorDeSync.ler(fimAtual()).epoca();
        FiltroDoVendedor soAsDuas = tabela -> tabela.nome().equals("teste_sync_a")
                ? new CondicaoSql("id IN (?, ?)", List.of(de999, de1000))
                : CondicaoSql.NENHUMA_LINHA;

        LoteDoPull primeiro = pull.puxar(epoca + ":998:0", 1, soAsDuas);
        assertThat(ids(primeiro)).containsExactly(de999);
        assertThat(ids(pull.puxar(primeiro.proximoCursor(), 1, soAsDuas))).containsExactly(de1000);
    }

    @Test
    void oFiltroDoVendedorEntraComoParametro() throws SQLException {
        String inicio = fimAtual();
        UUID doVendedor1 = UuidV7.gerar();
        UUID doVendedor2 = UuidV7.gerar();
        try (Connection a = conectarComoAplicacao()) {
            inserirA(a, doVendedor1, "vendedor-1");
            inserirA(a, doVendedor2, "vendedor-2");
            inserirB(a, UuidV7.gerar(), "vendedor-1");
            a.commit();
        }
        FiltroDoVendedor soOVendedor1 = tabela -> tabela.nome().equals("teste_sync_a")
                ? new CondicaoSql("texto = ?", List.of("vendedor-1"))
                : CondicaoSql.NENHUMA_LINHA;

        assertThat(ids(pull.puxar(inicio, 100, soOVendedor1))).containsExactly(doVendedor1);
    }

    @Test
    void oPullSoLevaAsColunasDaListaExplicitaENuncaTabelaDoServidor() throws SQLException {
        String inicio = fimAtual();
        UUID id = UuidV7.gerar();
        try (Connection a = conectarComoAplicacao()) {
            executar(a, "INSERT INTO teste_servidor (descricao) VALUES ('só do servidor')");
            inserirA(a, id, "com colunas");
            a.commit();
        }
        LoteDoPull lote = pull.puxar(inicio, 100, TODOS);
        assertThat(lote.linhas()).singleElement().satisfies(l -> {
            assertThat(l.tabela()).isEqualTo("teste_sync_a");
            assertThat(l.colunas()).containsOnlyKeys(
                    "id", "version", "created_at", "updated_at", "deleted_at", "texto", "valor", "quantidade");
        });
    }

    @Test
    void cursorDeOutraEpocaOuMalformadoECursorInvalido() throws SQLException {
        String daEpocaAtual = fimAtual();
        long epoca = CursorDeSync.ler(daEpocaAtual).epoca();

        assertThatThrownBy(() -> pull.puxar((epoca + 1) + ":0:0", 10, TODOS))
                .isInstanceOf(CursorInvalidoException.class)
                .hasMessageContaining("refaça a carga completa");
        assertThatThrownBy(() -> pull.puxar("lixo", 10, TODOS)).isInstanceOf(CursorInvalidoException.class);

        // Simula a restauração: a época sobe e o cursor guardado pelo app fica velho.
        try (Connection dono = conectarComoMigracao()) {
            executar(dono, "UPDATE sync_estado SET epoca = epoca + 1");
            dono.commit();
            try {
                assertThatThrownBy(() -> pull.puxar(daEpocaAtual, 10, TODOS))
                        .isInstanceOf(CursorInvalidoException.class)
                        .hasMessageContaining("época " + epoca + ", a atual é " + (epoca + 1));
                assertThat(pull.puxar(null, 1, TODOS).proximoCursor()).startsWith((epoca + 1) + ":");
            } finally {
                executar(dono, "UPDATE sync_estado SET epoca = epoca - 1");
                dono.commit();
            }
        }
    }

    @Test
    void tamanhoDeLoteForaDaFaixaERecusado() {
        assertThatThrownBy(() -> pull.puxar(null, 0, TODOS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pull.puxar(null, ServicoDePull.LOTE_MAXIMO + 1, TODOS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Esvazia o pull e devolve o cursor do fim (nenhuma transação aberta nos testes neste momento). */
    private String fimAtual() {
        String cursor = null;
        LoteDoPull lote;
        do {
            lote = pull.puxar(cursor, ServicoDePull.LOTE_MAXIMO, TODOS);
            cursor = lote.proximoCursor();
        } while (lote.haMais());
        return cursor;
    }

    private static List<UUID> ids(LoteDoPull lote) {
        return lote.linhas().stream().map(LinhaDoPull::id).toList();
    }

    private static void inserirA(Connection conexao, UUID id, String texto) throws SQLException {
        executar(conexao, "INSERT INTO teste_sync_a (id, texto) VALUES (?, ?)", id, texto);
    }

    private static void inserirB(Connection conexao, UUID id, String nome) throws SQLException {
        executar(conexao, "INSERT INTO teste_sync_b (id, nome) VALUES (?, ?)", id, nome);
    }
}
