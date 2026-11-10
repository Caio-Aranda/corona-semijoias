package br.com.coronasemijoias.api.schema;

import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.TabelaDoServidor;
import br.com.coronasemijoias.api.sync.TabelaSincronizavel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lê o catálogo do PostgreSQL (schema {@code corona}) e lista, em português, tudo que está fora
 * das convenções do ADR-0002. Lista vazia é o esperado. Usado pelo {@code ConvencoesDoSchemaTest}.
 */
public final class VerificadorDeConvencoes {

    private static final int LINHA = 1;
    private static final int ANTES = 2;
    private static final int INSERT = 4;
    private static final int DELETE = 8;
    private static final int UPDATE = 16;
    private static final int TRUNCATE = 32;

    private static final String TIMESTAMPTZ = "timestamp with time zone";

    /** Colunas de controle de uma tabela sincronizável: tipo e se aceita nulo. */
    private static final Map<String, Coluna> CONTROLE_SINCRONIZAVEL = new LinkedHashMap<>();

    static {
        CONTROLE_SINCRONIZAVEL.put("id", new Coluna("uuid", true));
        CONTROLE_SINCRONIZAVEL.put("created_at", new Coluna(TIMESTAMPTZ, true));
        CONTROLE_SINCRONIZAVEL.put("updated_at", new Coluna(TIMESTAMPTZ, true));
        CONTROLE_SINCRONIZAVEL.put("version", new Coluna("integer", true));
        CONTROLE_SINCRONIZAVEL.put("deleted_at", new Coluna(TIMESTAMPTZ, false));
        CONTROLE_SINCRONIZAVEL.put("origem_dispositivo", new Coluna("uuid", false));
        CONTROLE_SINCRONIZAVEL.put("sync_seq", new Coluna("bigint", true));
        CONTROLE_SINCRONIZAVEL.put("sync_xid", new Coluna("xid8", true));
    }

    private static final Set<String> NUMERIC_PERMITIDOS = Set.of("numeric(14,2)", "numeric(5,2)");

    /** O único lugar onde numeric(18,8) é aceito: as oito colunas de taxa e paridade (ADR-0006, item 8). */
    private static final Set<String> COLUNAS_DE_COTACAO = Set.of(
            "cotacoes.usd_compra", "cotacoes.usd_venda", "cotacoes.usd_paridade_compra", "cotacoes.usd_paridade_venda",
            "cotacoes.pyg_compra", "cotacoes.pyg_venda", "cotacoes.pyg_paridade_compra", "cotacoes.pyg_paridade_venda");
    private static final Set<String> SO_DAS_SINCRONIZAVEIS = Set.of("sync_seq", "sync_xid", "origem_dispositivo");

    private record Coluna(String tipo, boolean naoNulo) {
    }

    private record Gatilho(String nome, String funcao, int tipo, boolean habilitado) {

        boolean e(String funcaoEsperada, int bits) {
            return habilitado && funcao.equals(funcaoEsperada) && (tipo & bits) == bits;
        }
    }

    private final JdbcTemplate jdbc;
    private final List<String> violacoes = new ArrayList<>();
    private final Map<String, Map<String, Coluna>> colunas = new HashMap<>();
    private final Map<String, List<Gatilho>> gatilhos = new HashMap<>();
    private final Map<String, List<List<String>>> indices = new HashMap<>();
    private final Map<String, List<String>> chavePrimaria = new HashMap<>();
    private final Map<String, List<String>> checks = new HashMap<>();
    private final Set<String> tabelas = new TreeSet<>();

    private VerificadorDeConvencoes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static List<String> verificar(JdbcTemplate jdbc, RegistroDeTabelas registro, String usuarioDaAplicacao) {
        VerificadorDeConvencoes verificador = new VerificadorDeConvencoes(jdbc);
        verificador.lerCatalogo();
        verificador.conferir(registro, usuarioDaAplicacao);
        return List.copyOf(verificador.violacoes);
    }

    private void lerCatalogo() {
        tabelas.addAll(jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'corona' AND c.relkind IN ('r', 'p')""", String.class));

        jdbc.query("""
                SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'corona' AND c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped""",
                linha -> {
                    colunas.computeIfAbsent(linha.getString(1), t -> new LinkedHashMap<>())
                            .put(linha.getString(2), new Coluna(linha.getString(3), linha.getBoolean(4)));
                });

        jdbc.query("""
                SELECT c.relname, t.tgname, pn.nspname || '.' || p.proname, t.tgtype, t.tgenabled <> 'D'
                  FROM pg_trigger t
                  JOIN pg_class c ON c.oid = t.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_proc p ON p.oid = t.tgfoid
                  JOIN pg_namespace pn ON pn.oid = p.pronamespace
                 WHERE n.nspname = 'corona' AND NOT t.tgisinternal""",
                linha -> {
                    gatilhos.computeIfAbsent(linha.getString(1), t -> new ArrayList<>()).add(new Gatilho(
                            linha.getString(2), linha.getString(3), linha.getInt(4), linha.getBoolean(5)));
                });

        jdbc.query("""
                SELECT c.relname, i.indisprimary,
                       array_to_string(ARRAY(SELECT a.attname
                                               FROM unnest(i.indkey) WITH ORDINALITY k(attnum, ordem)
                                               JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                                              ORDER BY k.ordem), ',')
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'corona' AND i.indisvalid AND i.indexprs IS NULL AND i.indpred IS NULL""",
                linha -> {
                    List<String> cols = List.of(linha.getString(3).split(","));
                    indices.computeIfAbsent(linha.getString(1), t -> new ArrayList<>()).add(cols);
                    if (linha.getBoolean(2)) {
                        chavePrimaria.put(linha.getString(1), cols);
                    }
                });

        jdbc.query("""
                SELECT c.relname, pg_get_constraintdef(k.oid)
                  FROM pg_constraint k
                  JOIN pg_class c ON c.oid = k.conrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'corona' AND k.contype = 'c'""",
                linha -> {
                    checks.computeIfAbsent(linha.getString(1), t -> new ArrayList<>()).add(linha.getString(2));
                });
    }

    private void conferir(RegistroDeTabelas registro, String usuarioDaAplicacao) {
        Map<String, TabelaSincronizavel> sincronizaveis = new HashMap<>();
        registro.sincronizaveis().forEach(t -> sincronizaveis.put(t.nome(), t));
        Map<String, TabelaDoServidor> doServidor = new HashMap<>();
        registro.soDoServidor().forEach(t -> doServidor.put(t.nome(), t));

        for (String tabela : tabelas) {
            if (RegistroDeTabelas.TABELAS_DE_FERRAMENTA.contains(tabela)) {
                continue;
            }
            conferirTipos(tabela);
            conferirId(tabela);
            conferirRecusaDeApagar(tabela);
            if (sincronizaveis.containsKey(tabela)) {
                conferirSincronizavel(sincronizaveis.get(tabela));
            } else if (doServidor.containsKey(tabela)) {
                conferirDoServidor(doServidor.get(tabela));
            } else {
                violacao(tabela, "não está em nenhuma das duas listas do RegistroDeTabelas (sincronizável ou só do servidor)");
            }
        }
        for (String registrada : sincronizaveis.keySet()) {
            if (!tabelas.contains(registrada)) {
                violacao(registrada, "está no RegistroDeTabelas mas não existe no banco");
            }
        }
        for (String registrada : doServidor.keySet()) {
            if (!tabelas.contains(registrada)) {
                violacao(registrada, "está no RegistroDeTabelas mas não existe no banco");
            }
        }
        conferirPrivilegios(usuarioDaAplicacao);
    }

    private void conferirTipos(String tabela) {
        colunas.getOrDefault(tabela, Map.of()).forEach((nome, coluna) -> {
            String tipo = coluna.tipo().replace("[]", "");
            if (tipo.startsWith("real") || tipo.startsWith("double precision")) {
                violacao(tabela, "coluna " + nome + " é " + coluna.tipo() + " (proibido float: dinheiro é NUMERIC(14,2))");
            } else if (tipo.equals("money")) {
                violacao(tabela, "coluna " + nome + " é money (dinheiro é NUMERIC(14,2))");
            } else if (tipo.equals("numeric(18,8)")) {
                if (!COLUNAS_DE_COTACAO.contains(tabela + "." + nome)) {
                    violacao(tabela, "coluna " + nome + " é numeric(18,8) (numeric(18,8) só em colunas de cotação (ADR-0006))");
                }
            } else if (tipo.startsWith("numeric") && !NUMERIC_PERMITIDOS.contains(tipo)) {
                violacao(tabela, "coluna " + nome + " é " + coluna.tipo()
                        + " (numeric só como NUMERIC(14,2) para dinheiro ou NUMERIC(5,2) para porcentagem)");
            } else if (tipo.startsWith("timestamp") && tipo.endsWith("without time zone")) {
                violacao(tabela, "coluna " + nome + " é " + coluna.tipo() + " (datas sempre com fuso: timestamptz)");
            }
        });
    }

    private void conferirId(String tabela) {
        Coluna id = colunas.getOrDefault(tabela, Map.of()).get("id");
        if (id == null || !id.tipo().equals("uuid") || !List.of("id").equals(chavePrimaria.get(tabela))) {
            violacao(tabela, "precisa de \"id uuid PRIMARY KEY\" como única chave primária");
        }
        boolean temCheckDoId = checks.getOrDefault(tabela, List.of()).stream()
                .anyMatch(def -> def.contains("uuid_extract_version(id)"));
        if (!temCheckDoId) {
            violacao(tabela, "falta o CHECK de UUID v4 ou v7 no id (use as funções corona.aplicar_convencoes_*)");
        }
    }

    private void conferirRecusaDeApagar(String tabela) {
        if (!temGatilho(tabela, "corona.recusar_exclusao", ANTES | DELETE)) {
            violacao(tabela, "falta o gatilho que recusa DELETE");
        }
        if (!temGatilho(tabela, "corona.recusar_exclusao", ANTES | TRUNCATE)) {
            violacao(tabela, "falta o gatilho que recusa TRUNCATE");
        }
    }

    private void conferirSincronizavel(TabelaSincronizavel registrada) {
        String tabela = registrada.nome();
        Map<String, Coluna> daTabela = colunas.getOrDefault(tabela, Map.of());
        CONTROLE_SINCRONIZAVEL.forEach((nome, esperada) -> {
            Coluna coluna = daTabela.get(nome);
            if (coluna == null) {
                violacao(tabela, "falta a coluna de controle " + nome + " (" + esperada.tipo() + ")");
            } else if (!coluna.equals(esperada)) {
                violacao(tabela, "coluna de controle " + nome + " deveria ser " + descrever(esperada)
                        + ", é " + descrever(coluna));
            }
        });
        if (!temGatilho(tabela, "corona.controle_sincronizavel", LINHA | ANTES | INSERT | UPDATE)) {
            violacao(tabela, "falta o gatilho de controle corona.controle_sincronizavel (BEFORE INSERT OR UPDATE, por linha, ligado)");
        }
        if (registrada.imutavel() && !temGatilho(tabela, "corona.recusar_alteracao", ANTES | UPDATE)) {
            violacao(tabela, "é imutável e falta o gatilho que recusa UPDATE");
        }
        if (!indices.getOrDefault(tabela, List.of()).contains(List.of("sync_xid", "sync_seq"))) {
            violacao(tabela, "falta o índice (sync_xid, sync_seq) do pull");
        }
        for (String coluna : registrada.colunas()) {
            if (!daTabela.containsKey(coluna)) {
                violacao(tabela, "a coluna " + coluna + " do RegistroDeTabelas não existe na tabela");
            }
        }
        daTabela.keySet().stream()
                .filter(nome -> nome.contains("custo"))
                .forEach(nome -> violacao(tabela, "coluna " + nome
                        + " numa tabela sincronizável (custo nunca vai ao app do vendedor; ADR-0003)"));
    }

    private void conferirDoServidor(TabelaDoServidor registrada) {
        String tabela = registrada.nome();
        Map<String, Coluna> daTabela = colunas.getOrDefault(tabela, Map.of());
        for (String nome : SO_DAS_SINCRONIZAVEIS) {
            if (daTabela.containsKey(nome)) {
                violacao(tabela, "é só do servidor e não pode ter a coluna " + nome);
            }
        }
        Coluna criacao = daTabela.get("created_at");
        if (criacao == null || !criacao.equals(new Coluna(TIMESTAMPTZ, true))) {
            violacao(tabela, "falta created_at timestamptz NOT NULL");
        }
        boolean editavel = daTabela.containsKey("updated_at") || daTabela.containsKey("version");
        if (registrada.imutavel()) {
            if (editavel) {
                violacao(tabela, "é de histórico (imutável) e não pode ter updated_at nem version");
            }
            if (!temGatilho(tabela, "corona.recusar_alteracao", ANTES | UPDATE)) {
                violacao(tabela, "é imutável e falta o gatilho que recusa UPDATE");
            }
        } else if (editavel && !temGatilho(tabela, "corona.controle_do_servidor", LINHA | ANTES | INSERT | UPDATE)) {
            violacao(tabela, "tem updated_at ou version e falta o gatilho de controle corona.controle_do_servidor");
        }
    }

    private void conferirPrivilegios(String usuario) {
        jdbc.query("""
                SELECT c.relname,
                       has_table_privilege(?, c.oid, 'DELETE'),
                       has_table_privilege(?, c.oid, 'TRUNCATE'),
                       has_table_privilege(?, c.oid, 'SELECT, INSERT, UPDATE, REFERENCES, TRIGGER')
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'corona' AND c.relkind IN ('r', 'p')""",
                linha -> {
                    String tabela = linha.getString(1);
                    if (linha.getBoolean(2)) {
                        violacao(tabela, "o usuário da aplicação tem DELETE");
                    }
                    if (linha.getBoolean(3)) {
                        violacao(tabela, "o usuário da aplicação tem TRUNCATE");
                    }
                    if (tabela.equals("flyway_schema_history") && linha.getBoolean(4)) {
                        violacao(tabela, "o usuário da aplicação tem acesso ao histórico das migrações");
                    }
                },
                usuario, usuario, usuario);
    }

    private boolean temGatilho(String tabela, String funcao, int bits) {
        return gatilhos.getOrDefault(tabela, List.of()).stream().anyMatch(g -> g.e(funcao, bits));
    }

    private static String descrever(Coluna coluna) {
        return coluna.tipo() + (coluna.naoNulo() ? " NOT NULL" : " (aceita nulo)");
    }

    private void violacao(String tabela, String descricao) {
        violacoes.add(tabela + ": " + descricao);
    }
}
