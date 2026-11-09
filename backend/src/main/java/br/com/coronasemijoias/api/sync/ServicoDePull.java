package br.com.coronasemijoias.api.sync;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pull com limite seguro (ADR-0002, item 5). Serviço interno: a rota {@code /sync/pull} é da Fase 5.
 *
 * <p>Só entrega linhas de transações já terminadas: {@code sync_xid} abaixo do
 * {@code pg_snapshot_xmin} (o menor identificador de transação ainda aberta). Esse conjunto está
 * fechado (nenhuma linha nova aparece nele), então o cursor {@code (sync_xid, sync_seq)} nunca
 * passa por cima de uma linha que ainda não estava visível.
 *
 * <p>Detalhes que a prova exige:
 * <ul>
 *   <li>o limite é calculado <strong>uma vez</strong> por pull e usado em todas as tabelas (com
 *       limites diferentes por tabela, uma transação que gravou em duas tabelas poderia ser
 *       entregue pela metade e a outra metade ficaria para trás do cursor);</li>
 *   <li>cada tabela devolve no máximo um lote, já ordenado; juntar e cortar depois é seguro porque
 *       todo candidato fora do corte vem depois do último entregue;</li>
 *   <li>a leitura roda numa transação própria, {@code REPEATABLE READ READ ONLY}: ela não recebe
 *       identificador de transação e não atrasa o pull de ninguém.</li>
 * </ul>
 */
public class ServicoDePull {

    public static final int LOTE_MAXIMO = 1000;

    private static final Comparator<LinhaDoPull> ORDEM_DO_PULL = (a, b) ->
            CursorDeSync.compararPosicao(a.syncXid(), a.syncSeq(), b.syncXid(), b.syncSeq());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final RegistroDeTabelas registro;

    public ServicoDePull(DataSource dataSource, RegistroDeTabelas registro) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.registro = Objects.requireNonNull(registro, "registro");
        this.transacao = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transacao.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transacao.setReadOnly(true);
    }

    /**
     * @param cursor o texto recebido do app; vazio ou nulo é a carga completa
     * @param tamanhoDoLote de 1 a {@link #LOTE_MAXIMO}
     * @param filtro a condição "só os dados do vendedor" ({@link FiltroDoVendedor#SEM_FILTRO} para todos)
     * @throws CursorInvalidoException cursor de outra época ou malformado: o app refaz a carga completa
     */
    public LoteDoPull puxar(String cursor, int tamanhoDoLote, FiltroDoVendedor filtro) {
        if (tamanhoDoLote < 1 || tamanhoDoLote > LOTE_MAXIMO) {
            throw new IllegalArgumentException("O lote do pull vai de 1 a " + LOTE_MAXIMO + ", veio " + tamanhoDoLote + ".");
        }
        Objects.requireNonNull(filtro, "filtro");
        return transacao.execute(estado -> puxarNaTransacao(cursor, tamanhoDoLote, filtro));
    }

    private LoteDoPull puxarNaTransacao(String textoDoCursor, int tamanhoDoLote, FiltroDoVendedor filtro) {
        long epoca = jdbc.queryForObject("SELECT epoca FROM corona.sync_estado", Long.class);
        CursorDeSync cursor = textoDoCursor == null || textoDoCursor.isBlank()
                ? CursorDeSync.inicio(epoca)
                : CursorDeSync.ler(textoDoCursor);
        if (cursor.epoca() != epoca) {
            throw new CursorInvalidoException("época " + cursor.epoca() + ", a atual é " + epoca);
        }
        String limite = jdbc.queryForObject("SELECT pg_snapshot_xmin(pg_current_snapshot())::text", String.class);

        List<LinhaDoPull> candidatos = new ArrayList<>();
        boolean algumaTabelaEncheuOLote = false;
        for (TabelaSincronizavel tabela : registro.sincronizaveis()) {
            FiltroDoVendedor.CondicaoSql condicao = Objects.requireNonNull(filtro.condicaoPara(tabela),
                    () -> "O filtro não deu condição para a tabela " + tabela.nome());
            List<Object> parametros = new ArrayList<>();
            parametros.add(limite);
            parametros.add(Long.toUnsignedString(cursor.xid()));
            parametros.add(cursor.seq());
            parametros.addAll(condicao.parametros());
            parametros.add(tamanhoDoLote);
            List<LinhaDoPull> linhas = jdbc.query(consulta(tabela, condicao), leitor(tabela), parametros.toArray());
            algumaTabelaEncheuOLote |= linhas.size() == tamanhoDoLote;
            candidatos.addAll(linhas);
        }
        candidatos.sort(ORDEM_DO_PULL);

        List<LinhaDoPull> lote = candidatos.subList(0, Math.min(tamanhoDoLote, candidatos.size()));
        boolean haMais = candidatos.size() > tamanhoDoLote || algumaTabelaEncheuOLote;
        CursorDeSync proximo = cursor;
        if (!lote.isEmpty()) {
            LinhaDoPull ultima = lote.get(lote.size() - 1);
            proximo = new CursorDeSync(epoca, ultima.syncXid(), ultima.syncSeq());
        }
        return new LoteDoPull(lote, proximo.texto(), haMais);
    }

    private static String consulta(TabelaSincronizavel tabela, FiltroDoVendedor.CondicaoSql condicao) {
        String colunas = tabela.colunasDoPull().stream().map(c -> '"' + c + '"').collect(Collectors.joining(", "));
        return "SELECT " + colunas + ", sync_xid::text AS sync_xid, sync_seq"
                + " FROM corona.\"" + tabela.nome() + "\""
                + " WHERE sync_xid < ?::xid8"
                + " AND (sync_xid, sync_seq) > (?::xid8, ?)"
                + " AND (" + condicao.sql() + ")"
                + " ORDER BY sync_xid, sync_seq"
                + " LIMIT ?";
    }

    private static RowMapper<LinhaDoPull> leitor(TabelaSincronizavel tabela) {
        List<String> colunas = tabela.colunasDoPull();
        return (ResultSet linha, int numero) -> {
            ResultSetMetaData metadados = linha.getMetaData();
            Map<String, Object> valores = new LinkedHashMap<>();
            for (int i = 0; i < colunas.size(); i++) {
                valores.put(colunas.get(i), valor(linha, metadados, i + 1));
            }
            return new LinhaDoPull(
                    tabela.nome(),
                    (UUID) valores.get("id"),
                    ((Number) valores.get("version")).intValue(),
                    (Instant) valores.get("deleted_at"),
                    Collections.unmodifiableMap(valores),
                    Long.parseUnsignedLong(linha.getString("sync_xid")),
                    linha.getLong("sync_seq"));
        };
    }

    /** Datas com fuso viram {@code Instant} (UTC); o resto vem como o driver entrega. */
    private static Object valor(ResultSet linha, ResultSetMetaData metadados, int coluna) throws SQLException {
        if ("timestamptz".equals(metadados.getColumnTypeName(coluna))) {
            OffsetDateTime quando = linha.getObject(coluna, OffsetDateTime.class);
            return quando == null ? null : quando.toInstant();
        }
        return linha.getObject(coluna);
    }
}
