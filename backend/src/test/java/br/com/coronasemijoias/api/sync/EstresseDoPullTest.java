package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Estresse do pull (ADR-0002, item 5): escritores em paralelo com inserts, updates, exclusões
 * lógicas, {@code SAVEPOINT}, {@code ROLLBACK}, pausas e transações longas, e um leitor paginando
 * o pull sem parar. No fim, o que o leitor juntou precisa ser idêntico ao banco, o cursor nunca
 * pode ter andado para trás e a versão de um registro nunca pode ter voltado.
 *
 * <p>Propriedades (passadas pelo Maven, ver o {@code pom.xml}):
 * <ul>
 *   <li>{@code corona.estresse.duracao}: padrão {@code PT20S} (todo Pull Request); o workflow
 *       semanal usa {@code PT10M}, com as pausas e transações longas do ADR;</li>
 *   <li>{@code corona.estresse.semente}: padrão fixo, impresso no log. Cada escritor usa
 *       {@code semente + número do escritor}; a ordem entre as threads não se repete, mas as
 *       escolhas de cada uma sim.</li>
 * </ul>
 */
class EstresseDoPullTest extends TesteDeIntegracao {

    private static final Logger LOG = LoggerFactory.getLogger(EstresseDoPullTest.class);

    private static final int ESCRITORES = 8;
    private static final String[] MUTAVEIS = {"teste_sync_a", "teste_sync_b"};
    private static final String[] TODAS = {"teste_sync_a", "teste_sync_b", "teste_sync_imutavel"};

    @Autowired
    ServicoDePull pull;

    private record Estado(int versao, Instant excluidaEm) {
    }

    /** Ids já confirmados no banco, que os escritores podem alterar. */
    private final Map<String, List<UUID>> conhecidos = new HashMap<>();
    private final AtomicLong confirmadas = new AtomicLong();
    private final AtomicLong desfeitas = new AtomicLong();
    private final AtomicLong abortadasPorConflito = new AtomicLong();
    private final AtomicLong savepointsDesfeitos = new AtomicLong();
    private final AtomicLong transacoesLongas = new AtomicLong();
    private final AtomicLong operacoes = new AtomicLong();

    @Test
    void oQueOLeitorJuntouEIdenticoAoBanco() throws Exception {
        Duration duracao = Duration.parse(System.getProperty("corona.estresse.duracao", "PT20S"));
        long semente = Long.parseLong(System.getProperty("corona.estresse.semente", "20261109"));
        boolean longo = duracao.compareTo(Duration.ofMinutes(2)) > 0;
        int pausaMaximaMs = longo ? 2000 : 250;
        int transacaoLongaMs = longo ? 5000 : 2000;
        LOG.info("Estresse do pull: semente={} duração={} escritores={} pausa=0..{}ms transação longa={}ms",
                semente, duracao, ESCRITORES, pausaMaximaMs, transacaoLongaMs);
        for (String tabela : MUTAVEIS) {
            conhecidos.put(tabela, Collections.synchronizedList(new ArrayList<>()));
        }

        Instant fim = Instant.now().plus(duracao);
        AtomicBoolean escritoresTerminaram = new AtomicBoolean(false);
        Leitor leitor = new Leitor(new Random(semente - 1));
        ExecutorService threads = Executors.newFixedThreadPool(ESCRITORES + 1);
        try {
            List<Future<?>> escritores = new ArrayList<>();
            for (int i = 0; i < ESCRITORES; i++) {
                Random aleatorio = new Random(semente + i);
                escritores.add(threads.submit(() -> {
                    escrever(aleatorio, fim, pausaMaximaMs, transacaoLongaMs);
                    return null;
                }));
            }
            Future<?> leitura = threads.submit(() -> {
                leitor.lerAte(escritoresTerminaram);
                return null;
            });
            long limite = duracao.plusMinutes(3).toMillis();
            for (Future<?> escritor : escritores) {
                escritor.get(limite, TimeUnit.MILLISECONDS);
            }
            escritoresTerminaram.set(true);
            leitura.get(limite, TimeUnit.MILLISECONDS);
        } finally {
            threads.shutdownNow();
        }

        // Espera todas as transações terminarem (inclusive as do próprio PostgreSQL, como o
        // autovacuum) e então o leitor esvazia o pull e compara com o banco.
        esperarTodasAsTransacoesTerminarem();
        leitor.esvaziar();
        Map<String, Estado> noBanco = lerBanco();
        LOG.info("Estresse do pull terminado: semente={} transações confirmadas={} desfeitas={} "
                        + "abortadas por conflito={} savepoints desfeitos={} transações longas={} operações={} "
                        + "lotes lidos={} linhas recebidas={} registros conferidos={}",
                semente, confirmadas, desfeitas, abortadasPorConflito, savepointsDesfeitos, transacoesLongas,
                operacoes, leitor.lotes, leitor.linhasRecebidas, noBanco.size());

        assertThat(confirmadas.get()).as("o estresse precisa ter gravado alguma coisa").isPositive();
        assertThat(desfeitas.get()).as("o estresse precisa ter desfeito transações").isPositive();
        assertThat(diferencas(leitor.mapa, noBanco)).as("o que o leitor juntou é idêntico ao banco").isEmpty();
    }

    /** Diferenças entre o mapa do leitor e o banco, uma por registro (as 20 primeiras). */
    private static List<String> diferencas(Map<String, Estado> doLeitor, Map<String, Estado> doBanco) {
        List<String> diferencas = new ArrayList<>();
        for (Map.Entry<String, Estado> noBanco : doBanco.entrySet()) {
            Estado lido = doLeitor.get(noBanco.getKey());
            if (!noBanco.getValue().equals(lido)) {
                diferencas.add(noBanco.getKey() + ": banco " + noBanco.getValue() + ", leitor " + lido);
            }
        }
        for (String chave : doLeitor.keySet()) {
            if (!doBanco.containsKey(chave)) {
                diferencas.add(chave + ": só o leitor tem " + doLeitor.get(chave));
            }
        }
        return diferencas.subList(0, Math.min(20, diferencas.size()));
    }

    // ------------------------------------------------------------------ escritores

    private void escrever(Random aleatorio, Instant fim, int pausaMaximaMs, int transacaoLongaMs) throws Exception {
        try (Connection conexao = conectarComoAplicacao()) {
            while (Instant.now().isBefore(fim)) {
                List<Pendente> inseridas = new ArrayList<>();
                try {
                    int quantas = 1 + aleatorio.nextInt(20);
                    for (int i = 0; i < quantas; i++) {
                        if (aleatorio.nextInt(8) == 0) {
                            comSavepoint(conexao, aleatorio, inseridas);
                        } else {
                            operar(conexao, aleatorio, inseridas);
                        }
                    }
                    Thread.sleep(aleatorio.nextInt(pausaMaximaMs + 1));
                    if (aleatorio.nextInt(40) == 0) {
                        transacoesLongas.incrementAndGet();
                        Thread.sleep(transacaoLongaMs);
                    }
                    if (aleatorio.nextInt(100) < 15) {
                        conexao.rollback();
                        desfeitas.incrementAndGet();
                    } else {
                        conexao.commit();
                        confirmadas.incrementAndGet();
                        for (Pendente p : inseridas) {
                            conhecidos.get(p.tabela()).add(p.id());
                        }
                    }
                } catch (SQLException e) {
                    conexao.rollback();
                    // Dois escritores alterando as mesmas linhas em ordem diferente: o PostgreSQL
                    // desfaz um deles. É conflito esperado, não erro do pull.
                    if (!"40P01".equals(e.getSQLState())) {
                        throw e;
                    }
                    abortadasPorConflito.incrementAndGet();
                }
            }
        }
    }

    private record Pendente(String tabela, UUID id) {
    }

    private void comSavepoint(Connection conexao, Random aleatorio, List<Pendente> inseridas) throws SQLException {
        try (Statement comando = conexao.createStatement()) {
            comando.execute("SAVEPOINT s");
            int marca = inseridas.size();
            int quantas = 1 + aleatorio.nextInt(3);
            for (int i = 0; i < quantas; i++) {
                operar(conexao, aleatorio, inseridas);
            }
            if (aleatorio.nextBoolean()) {
                comando.execute("ROLLBACK TO SAVEPOINT s");
                inseridas.subList(marca, inseridas.size()).clear();
                savepointsDesfeitos.incrementAndGet();
            } else {
                comando.execute("RELEASE SAVEPOINT s");
            }
        }
    }

    private void operar(Connection conexao, Random aleatorio, List<Pendente> inseridas) throws SQLException {
        operacoes.incrementAndGet();
        int tipo = aleatorio.nextInt(10);
        if (tipo < 5) {
            String tabela = TODAS[aleatorio.nextInt(TODAS.length)];
            UUID id = UuidV7.gerar();
            switch (tabela) {
                case "teste_sync_a" -> atualizar(conexao, "INSERT INTO teste_sync_a (id, texto, valor, quantidade) VALUES (?, ?, ?, ?)",
                        id, "novo " + aleatorio.nextInt(1000), dinheiro(aleatorio), aleatorio.nextInt(1000));
                case "teste_sync_b" -> atualizar(conexao, "INSERT INTO teste_sync_b (id, nome, porcentagem) VALUES (?, ?, ?)",
                        id, "novo " + aleatorio.nextInt(1000), BigDecimal.valueOf(aleatorio.nextInt(10001), 2));
                default -> atualizar(conexao, "INSERT INTO teste_sync_imutavel (id, valor) VALUES (?, ?)", id, dinheiro(aleatorio));
            }
            if (!tabela.equals("teste_sync_imutavel")) {
                inseridas.add(new Pendente(tabela, id));
            }
            return;
        }
        String tabela = MUTAVEIS[aleatorio.nextInt(MUTAVEIS.length)];
        UUID alvo = sortear(tabela, aleatorio);
        if (alvo == null) {
            return;
        }
        if (tipo < 8) {
            String coluna = tabela.equals("teste_sync_a") ? "texto" : "nome";
            atualizar(conexao, "UPDATE " + tabela + " SET " + coluna + " = ? WHERE id = ?",
                    "alterado " + aleatorio.nextInt(1000), alvo);
        } else {
            atualizar(conexao, "UPDATE " + tabela + " SET deleted_at = now() WHERE id = ?", alvo);
        }
    }

    private UUID sortear(String tabela, Random aleatorio) {
        List<UUID> ids = conhecidos.get(tabela);
        synchronized (ids) {
            return ids.isEmpty() ? null : ids.get(aleatorio.nextInt(ids.size()));
        }
    }

    private static BigDecimal dinheiro(Random aleatorio) {
        return BigDecimal.valueOf(aleatorio.nextInt(10_000_000), 2);
    }

    private static void atualizar(Connection conexao, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement comando = conexao.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                comando.setObject(i + 1, parametros[i]);
            }
            comando.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ leitor

    private final class Leitor {

        private final Random aleatorio;
        private final Map<String, Estado> mapa = new HashMap<>();
        private String cursor;
        private CursorDeSync ultimoCursor;
        private long ultimoXid;
        private long ultimoSeq;
        private boolean recebeuAlguma;
        private long lotes;
        private long linhasRecebidas;

        Leitor(Random aleatorio) {
            this.aleatorio = aleatorio;
        }

        void lerAte(AtomicBoolean parar) throws InterruptedException {
            while (!parar.get()) {
                LoteDoPull lote = pull.puxar(cursor, 1 + aleatorio.nextInt(50), FiltroDoVendedor.SEM_FILTRO);
                aplicar(lote);
                if (lote.linhas().isEmpty()) {
                    Thread.sleep(5 + aleatorio.nextInt(20));
                }
            }
        }

        void esvaziar() {
            LoteDoPull lote;
            do {
                lote = pull.puxar(cursor, 1 + aleatorio.nextInt(50), FiltroDoVendedor.SEM_FILTRO);
                aplicar(lote);
            } while (!lote.linhas().isEmpty() || lote.haMais());
        }

        private void aplicar(LoteDoPull lote) {
            lotes++;
            for (LinhaDoPull linha : lote.linhas()) {
                if (recebeuAlguma && CursorDeSync.compararPosicao(linha.syncXid(), linha.syncSeq(), ultimoXid, ultimoSeq) <= 0) {
                    throw new AssertionError("O pull andou para trás: (" + linha.syncXid() + ", " + linha.syncSeq()
                            + ") depois de (" + ultimoXid + ", " + ultimoSeq + ")");
                }
                recebeuAlguma = true;
                ultimoXid = linha.syncXid();
                ultimoSeq = linha.syncSeq();
                String chave = linha.tabela() + ":" + linha.id();
                Estado antes = mapa.get(chave);
                if (antes != null && linha.versao() <= antes.versao()) {
                    throw new AssertionError("A versão de " + chave + " voltou: " + antes.versao() + " e depois " + linha.versao());
                }
                mapa.put(chave, new Estado(linha.versao(), linha.excluidaEm()));
                linhasRecebidas++;
            }
            CursorDeSync proximo = CursorDeSync.ler(lote.proximoCursor());
            if (ultimoCursor != null
                    && CursorDeSync.compararPosicao(proximo.xid(), proximo.seq(), ultimoCursor.xid(), ultimoCursor.seq()) < 0) {
                throw new AssertionError("O cursor andou para trás: " + proximo + " depois de " + ultimoCursor);
            }
            ultimoCursor = proximo;
            cursor = lote.proximoCursor();
        }
    }

    /**
     * Um identificador novo é maior que o de toda transação dos escritores; quando o menor
     * identificador ainda aberto passar dele, todas terminaram.
     */
    private static void esperarTodasAsTransacoesTerminarem() throws Exception {
        try (Connection conexao = conectarComoAplicacao(); Statement comando = conexao.createStatement()) {
            conexao.setAutoCommit(true);
            long marco;
            try (ResultSet linha = comando.executeQuery("SELECT pg_current_xact_id()::text")) {
                linha.next();
                marco = Long.parseLong(linha.getString(1));
            }
            Instant limite = Instant.now().plusSeconds(60);
            while (true) {
                try (ResultSet linha = comando.executeQuery("SELECT pg_snapshot_xmin(pg_current_snapshot())::text")) {
                    linha.next();
                    if (Long.parseLong(linha.getString(1)) > marco) {
                        return;
                    }
                }
                if (Instant.now().isAfter(limite)) {
                    throw new AssertionError("Alguma transação continua aberta 60 s depois do fim dos escritores.");
                }
                Thread.sleep(50);
            }
        }
    }

    private static Map<String, Estado> lerBanco() throws SQLException {
        Map<String, Estado> estados = new HashMap<>();
        try (Connection conexao = conectarComoAplicacao(); Statement comando = conexao.createStatement()) {
            for (String tabela : TODAS) {
                try (ResultSet linhas = comando.executeQuery("SELECT id, version, deleted_at FROM " + tabela)) {
                    while (linhas.next()) {
                        OffsetDateTime excluida = linhas.getObject(3, OffsetDateTime.class);
                        estados.put(tabela + ":" + linhas.getObject(1),
                                new Estado(linhas.getInt(2), excluida == null ? null : excluida.toInstant()));
                    }
                }
            }
            conexao.rollback();
        }
        return estados;
    }
}
