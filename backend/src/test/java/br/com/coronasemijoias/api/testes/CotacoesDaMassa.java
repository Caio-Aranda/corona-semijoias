package br.com.coronasemijoias.api.testes;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cotações gravadas de verdade pelos testes do acerto (tarefa 7.5), todas de 2291 em diante. A tabela é imutável e os testes
 * de cotações esperam o banco sem cotação que não seja delas, então quem grava cotação aqui as tira no fim ({@link #tirar}).
 * O acerto fechado guarda a cotação (imutável e obrigatória), então os acertos que apontam para elas saem junto, com tudo o
 * que é deles. Tudo pelo dono das tabelas, com os gatilhos de usuário desligados só nessa conexão.
 */
public final class CotacoesDaMassa {

    /** O dia da cotação do exemplo do ADR-0012 (item 18): 1 Gs = R$ 0,00090000; Dólar a 5,00. */
    public static final String DIA_DO_EXEMPLO = "2291-06-01";
    private static final String DESDE = "2291-01-01";

    private CotacoesDaMassa() {
    }

    /** Grava (ou acha) a cotação do dia, com Dólar e Guarani iguais às taxas de venda dadas. Devolve o id. */
    public static UUID gravar(JdbcTemplate jdbc, String dia, String usdVenda, String pygVenda) {
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
                VALUES (uuidv7(), ?::date, ?::numeric, ?::numeric, 1, 1, ?::numeric, ?::numeric, 1, 1, now(), 'teste')
                ON CONFLICT ON CONSTRAINT cotacoes_data_unica DO NOTHING""", dia, usdVenda, usdVenda, pygVenda, pygVenda);
        return jdbc.queryForObject("SELECT id FROM corona.cotacoes WHERE data_cotacao = ?::date", UUID.class, dia);
    }

    /** A cotação do exemplo do ADR-0012. */
    public static UUID doExemplo(JdbcTemplate jdbc) {
        return gravar(jdbc, DIA_DO_EXEMPLO, "5.00000000", "0.00090000");
    }

    /** Tira as cotações de 2291 em diante e os acertos fechados com elas. */
    public static void tirar(Connection comoMigracao) throws SQLException {
        String minhas = "(SELECT id FROM corona.cotacoes WHERE data_cotacao >= '" + DESDE + "')";
        String meusAcertos = "(SELECT id FROM corona.acertos WHERE cotacao_id IN " + minhas + ")";
        List<String> tabelas = new ArrayList<>();
        try (Statement s = comoMigracao.createStatement()) {
            try (var rs = s.executeQuery("""
                    SELECT DISTINCT c.conrelid::regclass::text FROM pg_constraint c
                     WHERE c.contype = 'f' AND c.confrelid = 'corona.cotacoes'::regclass""")) {
                while (rs.next()) {
                    tabelas.add(rs.getString(1));
                }
            }
            List<String> todas = new ArrayList<>(tabelas);
            for (String t : List.of("corona.cotacoes", "corona.pendencias_acerto", "corona.comissoes", "corona.acerto_eventos",
                    "corona.itens_acerto", "corona.leituras_acerto", "corona.remessas", "corona.recebimentos_vendedor",
                    "corona.lancamentos_caixa", "corona.baixas_receber", "corona.contas_receber")) {
                if (!todas.contains(t)) {
                    todas.add(t);
                }
            }
            for (String t : todas) {
                s.execute("ALTER TABLE " + t + " DISABLE TRIGGER USER");
            }
            try {
                // O dinheiro do vendedor (tarefa 7.6): recebimentos, baixas, contas e lançamentos dos acertos fechados dos testes.
                // Os ids saem antes (sem tabela temporária: o dono das tabelas não pode criar).
                List<String> recebimentos = ids(s, "SELECT id FROM corona.recebimentos_vendedor WHERE acerto_id IN " + meusAcertos
                        + " OR id IN (SELECT b.recebimento_vendedor_id FROM corona.baixas_receber b JOIN corona.contas_receber c"
                        + " ON c.id = b.conta_id WHERE c.origem_tipo = 'ACERTO' AND c.origem_id IN " + meusAcertos + ")");
                if (!recebimentos.isEmpty()) {
                    recebimentos.addAll(ids(s, "SELECT id FROM corona.recebimentos_vendedor WHERE estorna_id IN " + lista(recebimentos)));
                }
                String meusRecebimentos = lista(recebimentos);
                List<String> contas = ids(s, "SELECT id FROM corona.contas_receber WHERE devedor_tipo = 'VENDEDOR' AND (origem_id IN "
                        + meusAcertos + " OR origem_id IN " + meusRecebimentos + ")");
                String minhasContas = lista(contas);
                s.execute("DELETE FROM corona.lancamentos_caixa WHERE origem_id IN " + meusRecebimentos + " OR origem_id IN " + meusAcertos);
                s.execute("DELETE FROM corona.baixas_receber WHERE conta_id IN " + minhasContas
                        + " OR recebimento_vendedor_id IN " + meusRecebimentos);
                s.execute("DELETE FROM corona.contas_receber WHERE id IN " + minhasContas);
                s.execute("DELETE FROM corona.recebimentos_vendedor WHERE id IN " + meusRecebimentos);
                s.execute("DELETE FROM corona.pendencias_acerto WHERE acerto_id IN " + meusAcertos
                        + " OR cobrada_no_acerto_id IN " + meusAcertos);
                for (String t : List.of("comissoes", "acerto_eventos", "itens_acerto", "leituras_acerto")) {
                    s.execute("DELETE FROM corona." + t + " WHERE acerto_id IN " + meusAcertos);
                }
                s.execute("UPDATE corona.remessas SET acerto_id = NULL, status_antes_do_acerto = NULL WHERE acerto_id IN " + meusAcertos);
                s.execute("DELETE FROM corona.acertos WHERE id IN " + meusAcertos);
                for (String t : tabelas) {
                    if (!t.equals("corona.acertos")) {
                        s.execute("UPDATE " + t + " SET cotacao_id = NULL WHERE cotacao_id IN " + minhas);
                    }
                }
                s.execute("DELETE FROM corona.cotacoes WHERE data_cotacao >= '" + DESDE + "'");
            } finally {
                for (String t : todas) {
                    s.execute("ALTER TABLE " + t + " ENABLE TRIGGER USER");
                }
            }
            comoMigracao.commit();
        }
    }

    private static List<String> ids(Statement s, String consulta) throws SQLException {
        List<String> r = new ArrayList<>();
        try (var rs = s.executeQuery(consulta)) {
            while (rs.next()) {
                r.add(rs.getString(1));
            }
        }
        return r;
    }

    /** Uma lista de uuids para IN (...); vazia vira uma lista que nunca bate. */
    private static String lista(List<String> ids) {
        if (ids.isEmpty()) {
            return "('00000000-0000-0000-0000-000000000000'::uuid)";
        }
        return ids.stream().map(i -> "'" + i + "'::uuid").collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }
}
