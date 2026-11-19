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
    private final RegistroDeTabelas registroDoFormato1;

    public ServicoDePull(DataSource dataSource, RegistroDeTabelas registro) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.registro = Objects.requireNonNull(registro, "registro");
        this.registroDoFormato1 = registro.paraOFormato1();
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
        return puxar(cursor, tamanhoDoLote, filtro, null);
    }

    /**
     * @param soTabela recarga de uma tabela só (a conferência por resumo achou diferença nela; ADR-0013, item 6), ou
     *                 nulo para todas
     */
    public LoteDoPull puxar(String cursor, int tamanhoDoLote, FiltroDoVendedor filtro, String soTabela) {
        return puxar(cursor, tamanhoDoLote, filtro, soTabela, FORMATO_COMPLETO);
    }

    /** O pull como o Corona Vendas 1.0.0 (sem o consignado) o conhece. */
    public static final int FORMATO_ANTIGO = 1;

    /** O pull completo: as tabelas e colunas do consignado ao cliente (tarefa 9.1). */
    public static final int FORMATO_COMPLETO = 2;

    private RegistroDeTabelas registroDo(int formato) {
        return formato >= FORMATO_COMPLETO ? registro : registroDoFormato1;
    }

    /**
     * @param formato {@link #FORMATO_ANTIGO} (padrão do pedido, para o app que não conhece o consignado) ou
     *                {@link #FORMATO_COMPLETO}
     */
    public LoteDoPull puxar(String cursor, int tamanhoDoLote, FiltroDoVendedor filtro, String soTabela, int formato) {
        RegistroDeTabelas registro = registroDo(formato);
        if (tamanhoDoLote < 1 || tamanhoDoLote > LOTE_MAXIMO) {
            throw new IllegalArgumentException("O lote do pull vai de 1 a " + LOTE_MAXIMO + ", veio " + tamanhoDoLote + ".");
        }
        Objects.requireNonNull(filtro, "filtro");
        List<TabelaSincronizavel> tabelas = soTabela == null ? registro.sincronizaveis()
                : List.of(registro.sincronizavel(soTabela).orElseThrow(
                        () -> new IllegalArgumentException("Tabela desconhecida no pull: " + soTabela)));
        return transacao.execute(estado -> puxarNaTransacao(cursor, tamanhoDoLote, filtro, tabelas, registro));
    }

    /**
     * Conferência por resumo (ADR-0013, item 6): por tabela, quantas linhas são do vendedor e o SHA-256 da lista
     * ordenada {@code id:version} (lápides incluídas), numa fotografia só do banco.
     */
    public java.util.Map<String, ResumoDaTabela> conferir(FiltroDoVendedor filtro) {
        return conferir(filtro, FORMATO_COMPLETO);
    }

    public java.util.Map<String, ResumoDaTabela> conferir(FiltroDoVendedor filtro, int formato) {
        Objects.requireNonNull(filtro, "filtro");
        RegistroDeTabelas registro = registroDo(formato);
        return transacao.execute(estado -> {
            java.util.Map<String, ResumoDaTabela> resumos = new LinkedHashMap<>();
            for (TabelaSincronizavel tabela : registro.sincronizaveis()) {
                FiltroDoVendedor.CondicaoSql condicao = filtro.condicaoPara(tabela);
                java.security.MessageDigest sha = sha256();
                long[] quantidade = {0};
                jdbc.query("SELECT t.id, t.version FROM corona.\"" + tabela.nome() + "\" AS t WHERE (" + condicao.sql()
                        + ") ORDER BY t.id", linha -> {
                            quantidade[0]++;
                            sha.update((linha.getObject(1, UUID.class) + ":" + linha.getInt(2) + "\n")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }, condicao.parametros().toArray());
                resumos.put(tabela.nome(), new ResumoDaTabela(quantidade[0], java.util.HexFormat.of().formatHex(sha.digest())));
            }
            return resumos;
        });
    }

    /** Uma tabela na conferência por resumo. */
    public record ResumoDaTabela(long quantidade, String resumo) {
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LoteDoPull puxarNaTransacao(String textoDoCursor, int tamanhoDoLote, FiltroDoVendedor filtro,
            List<TabelaSincronizavel> tabelas, RegistroDeTabelas registro) {
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
        for (TabelaSincronizavel tabela : tabelas) {
            FiltroDoVendedor.CondicaoSql condicao = Objects.requireNonNull(filtro.condicaoPara(tabela),
                    () -> "O filtro não deu condição para a tabela " + tabela.nome());
            List<Object> parametros = new ArrayList<>();
            parametros.add(limite);
            parametros.add(Long.toUnsignedString(cursor.xid()));
            parametros.add(cursor.seq());
            parametros.addAll(condicao.parametros());
            parametros.add(tamanhoDoLote);
            List<LinhaDoPull> linhas = jdbc.query(consulta(tabela, condicao, filtro), leitor(tabela), parametros.toArray());
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
        List<LinhaDoPull> entregues = filtro.expande() ? expandir(lote, filtro, registro) : lote;
        return new LoteDoPull(entregues, proximo.texto(), haMais);
    }

    /**
     * Regra 1 do item 5 do ADR-0013: a linha que faz outras passarem a ser do vendedor leva junto o estado atual delas,
     * da mesma fotografia (esta transação). As expandidas não mexem no cursor; uma linha que chegar de novo pelo caminho
     * normal é ignorada pelo app (a {@code version} não é maior). O lote pode passar do limite pelo tamanho da expansão,
     * que nunca é cortada no meio. Cada expandida passa também pela condição do filtro (segunda barreira).
     */
    private List<LinhaDoPull> expandir(List<LinhaDoPull> lote, FiltroDoVendedor filtro, RegistroDeTabelas registro) {
        List<LinhaDoPull> entregues = new ArrayList<>(lote);
        java.util.Set<String> vistas = new java.util.HashSet<>();
        lote.forEach(l -> vistas.add(l.tabela() + ':' + l.id()));
        java.util.ArrayDeque<LinhaDoPull> fila = new java.util.ArrayDeque<>(lote);
        while (!fila.isEmpty()) {
            LinhaDoPull linha = fila.poll();
            TabelaSincronizavel origem = registro.sincronizavel(linha.tabela()).orElse(null);
            if (origem == null) {
                continue;
            }
            for (RegraDoVendedor.Expansao expansao : origem.regra().expansoes()) {
                TabelaSincronizavel alvo = registro.sincronizavel(expansao.tabela()).orElse(null);
                Object valor = linha.colunas().get(expansao.colunaNaLinha());
                if (alvo == null || valor == null) {
                    continue;
                }
                FiltroDoVendedor.CondicaoSql condicao = filtro.condicaoPara(alvo);
                List<Object> parametros = new ArrayList<>();
                parametros.add(valor);
                parametros.addAll(condicao.parametros());
                String sql = "SELECT " + colunas(alvo, filtro) + ", t.sync_xid::text AS posicao_xid, t.sync_seq AS posicao_seq"
                        + " FROM corona.\"" + alvo.nome() + "\" AS t"
                        + " WHERE t.\"" + expansao.colunaNaTabela() + "\" = ? AND (" + condicao.sql() + ")"
                        + " ORDER BY t.sync_xid, t.sync_seq";
                for (LinhaDoPull expandida : jdbc.query(sql, leitor(alvo), parametros.toArray())) {
                    if (vistas.add(expandida.tabela() + ':' + expandida.id())) {
                        entregues.add(expandida);
                        fila.add(expandida);
                    }
                }
            }
        }
        return entregues;
    }

    private static String colunas(TabelaSincronizavel tabela, FiltroDoVendedor filtro) {
        return tabela.colunasDoPull().stream().map(c -> filtro.expressao(tabela, c) + " AS \"" + c + '"')
                .collect(Collectors.joining(", "));
    }

    /**
     * A posição sai com outro nome ({@code posicao_xid}, em texto) e a ordem usa as colunas
     * qualificadas pela tabela. Cuidado: num {@code ORDER BY}, um nome solto vale primeiro como
     * nome de coluna da saída; com {@code sync_xid::text AS sync_xid}, a ordem seria a do texto
     * ('1000' antes de '999') e o {@code LIMIT} cortaria as linhas erradas. O estresse pegou isso.
     */
    private static String consulta(TabelaSincronizavel tabela, FiltroDoVendedor.CondicaoSql condicao, FiltroDoVendedor filtro) {
        return "SELECT " + colunas(tabela, filtro) + ", t.sync_xid::text AS posicao_xid, t.sync_seq AS posicao_seq"
                + " FROM corona.\"" + tabela.nome() + "\" AS t"
                + " WHERE t.sync_xid < ?::xid8"
                + " AND (t.sync_xid, t.sync_seq) > (?::xid8, ?)"
                + " AND (" + condicao.sql() + ")"
                + " ORDER BY t.sync_xid, t.sync_seq"
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
                    Long.parseUnsignedLong(linha.getString("posicao_xid")),
                    linha.getLong("posicao_seq"));
        };
    }

    /** Datas com fuso viram {@code Instant} (UTC); o resto vem como o driver entrega. */
    private static Object valor(ResultSet linha, ResultSetMetaData metadados, int coluna) throws SQLException {
        if ("timestamptz".equals(metadados.getColumnTypeName(coluna))) {
            OffsetDateTime quando = linha.getObject(coluna, OffsetDateTime.class);
            return quando == null ? null : quando.toInstant();
        }
        if ("date".equals(metadados.getColumnTypeName(coluna))) {
            return linha.getObject(coluna, java.time.LocalDate.class);
        }
        return linha.getObject(coluna);
    }
}
