package br.com.coronasemijoias.api.testes;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Os testes das contas, das vendas a prazo e das trocas conferem a C1 e a C5 com a consulta de PRODUÇÃO (tarefa 7.8): uma fórmula
 * só, sem SQL copiado. O banco é dividido entre as classes de teste, então só valem as diferenças das vendas (e das parcelas
 * delas) que o teste criou.
 */
public final class ConciliacoesNosTestes {

    private ConciliacoesNosTestes() {
    }

    /** As diferenças da C1 e da C5 das vendas dadas, como texto: {@code "C1 VENDA <id> <descrição>"}. */
    public static List<String> c1eC5(JdbcTemplate jdbc, List<UUID> vendas) {
        Set<UUID> referencias = new HashSet<>(vendas);
        if (!vendas.isEmpty()) {
            String ids = String.join(",", vendas.stream().map(u -> "'" + u + "'::uuid").toList());
            referencias.addAll(jdbc.queryForList("SELECT id FROM corona.contas_receber WHERE venda_id IN (" + ids + ")", UUID.class));
        }
        ConciliacoesDaFase7 conciliacoes = new ConciliacoesDaFase7(jdbc);
        List<String> achadas = new ArrayList<>();
        for (String chave : List.of("C1", "C5")) {
            for (var d : conciliacoes.conferir(chave).conferencias().get(0).divergencias()) {
                if (d.referencia() != null && referencias.contains(d.referencia())) {
                    achadas.add(chave + " " + d.tipo() + " " + d.referencia() + " " + d.descricao());
                }
            }
        }
        return achadas;
    }

    /** Todos os ids ligados aos vendedores dados (vendedor, vendas, acertos, maletas, devoluções, comissões, eventos, contas, recebimentos e caixa). */
    public static Set<UUID> referenciasDosVendedores(JdbcTemplate jdbc, List<UUID> vendedores) {
        String v = "(" + String.join(",", vendedores.stream().map(u -> "'" + u + "'::uuid").toList()) + ")";
        String acertos = "SELECT id FROM corona.acertos WHERE vendedor_id IN " + v;
        String recebimentos = "SELECT id FROM corona.recebimentos_vendedor WHERE vendedor_id IN " + v;
        Set<UUID> ids = new HashSet<>(vendedores);
        for (String c : List.of("SELECT id FROM corona.vendas WHERE vendedor_id IN " + v, acertos, recebimentos,
                "SELECT id FROM corona.remessas WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.devolucoes WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.comissoes WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.acerto_eventos WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.pendencias_acerto WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.contas_receber WHERE vendedor_id IN " + v,
                "SELECT id FROM corona.lancamentos_caixa WHERE origem_id IN (" + recebimentos + ") OR origem_id IN (" + acertos + ")")) {
            ids.addAll(jdbc.queryForList(c, UUID.class));
        }
        return ids;
    }

    /** Quantas diferenças da conciliação (pela consulta de produção, sobre este {@code jdbc}) são das referências dadas. */
    public static int contar(JdbcTemplate jdbc, String chave, Set<UUID> referencias) {
        return (int) new ConciliacoesDaFase7(jdbc).conferir(chave).conferencias().get(0).divergencias().stream()
                .filter(d -> d.referencia() != null && referencias.contains(d.referencia())).count();
    }

    /** Um {@link JdbcTemplate} sobre uma conexão já aberta (a do dono das tabelas, com a transação estragada). */
    public static JdbcTemplate naConexao(java.sql.Connection con) {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(con, true));
    }
}
