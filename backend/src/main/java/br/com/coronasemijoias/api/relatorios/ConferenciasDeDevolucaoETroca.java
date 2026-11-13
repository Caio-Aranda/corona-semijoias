package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * As conferências da devolução e da troca (tarefa 6.10; ESPECIFICACAO §13; ADR-0012, C1; ADR-0014). Só leitura: cada uma
 * olha o banco inteiro num retrato só e devolve as divergências (o que não bate), nunca corrige nada. Todas devem vir
 * vazias; o que aparece aqui é defeito do sistema, não da loja.
 *
 * <ol>
 *   <li>{@code maleta}: o movimento da maleta de cada devolução e de cada troca bate com as peças que deviam voltar a ela, e a
 *       conta de estoque da loja mais as maletas (C1) fecha com devolução, troca e os estornos;</li>
 *   <li>{@code devolvido-por-peca}: por linha vendida, o devolvido (peças e valor) nunca passa do vendido;</li>
 *   <li>{@code creditos}: por crédito, o usado é a soma dos usos e nunca passa do valor (créditos menos usos = em aberto);</li>
 *   <li>{@code dinheiro-devolvido}: por venda, o dinheiro devolvido não passa do pago (salvo devolução que a dona já foi
 *       avisada para revisar);</li>
 *   <li>{@code contas-da-venda}: por venda, o recebido líquido (pagamentos menos reembolsos, inclusive os em crédito) fica entre zero e o total menos o devolvido.</li>
 * </ol>
 */
@Service
public class ConferenciasDeDevolucaoETroca {

    public record Divergencia(String referencia, String descricao) {
    }

    public record Conferencia(String chave, String titulo, String explicacao, long conferidos, List<Divergencia> divergencias) {
    }

    public record Resultado(Instant geradaEm, boolean tudoCerto, List<Conferencia> conferencias) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeEstoque estoque;

    ConferenciasDeDevolucaoETroca(JdbcTemplate jdbc, ServicoDeEstoque estoque) {
        this.jdbc = jdbc;
        this.estoque = estoque;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Resultado conferir() {
        List<Conferencia> todas = List.of(maleta(), devolvidoPorPeca(), creditos(), dinheiroDevolvido(), contasDaVenda());
        boolean certo = todas.stream().allMatch(c -> c.divergencias().isEmpty());
        return new Resultado(Instant.now(), certo, todas);
    }

    private long contar(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static String reais(BigDecimal v) {
        return "R$ " + v.setScale(2).toPlainString().replace('.', ',');
    }

    // --- 1. a maleta ---

    private Conferencia maleta() {
        List<Divergencia> d = new ArrayList<>(jdbc.query("""
                SELECT dv.id, dv.situacao, coalesce(esp.qtd, 0) AS esperado, coalesce(mov.qtd, 0) AS movido
                  FROM corona.devolucoes dv
                  LEFT JOIN (SELECT i.devolucao_id, sum(i.quantidade) AS qtd FROM corona.itens_devolucao i
                              WHERE i.destino_peca = 'MALETA' GROUP BY i.devolucao_id) esp ON esp.devolucao_id = dv.id
                  LEFT JOIN (SELECT m.devolucao_id, sum(m.quantidade) AS qtd FROM corona.movimentos_remessa m
                              WHERE m.devolucao_id IS NOT NULL GROUP BY m.devolucao_id) mov ON mov.devolucao_id = dv.id
                 WHERE dv.deleted_at IS NULL
                   AND coalesce(mov.qtd, 0) <> CASE WHEN dv.situacao = 'CANCELADA' THEN 0 ELSE coalesce(esp.qtd, 0) END
                 ORDER BY dv.devolvida_em, dv.id""", (rs, n) -> new Divergencia("Devolução " + rs.getObject("id"),
                "A maleta devia ter recebido " + ("CANCELADA".equals(rs.getString("situacao")) ? 0 : rs.getLong("esperado"))
                        + " peça(s) desta devolução e recebeu " + rs.getLong("movido") + ".")));
        ServicoDeEstoque.Conciliacao c1 = estoque.conciliar();
        for (ServicoDeEstoque.DiferencaComMaletas x : c1.diferencasComMaletas()) {
            d.add(new Divergencia("Peça " + x.nome() + " (" + x.produtoId() + ")", "O estoque da loja mais o das maletas ("
                    + (x.estoqueCentral() + x.saldoNasMaletas()) + ") não bate com o que entrou, saiu e foi vendido ("
                    + x.esperado() + ")."));
        }
        long conferidos = contar("SELECT count(*) FROM corona.devolucoes WHERE deleted_at IS NULL") + c1.produtosConferidos();
        return new Conferencia("maleta", "Saldo da maleta com devolução e troca",
                "As peças que voltam à maleta pela devolução ou pela troca batem com o que a maleta recebeu, e o estoque "
                        + "da loja mais as maletas fecha.", conferidos, d);
    }

    // --- 2. devolvido por peça ---

    private Conferencia devolvidoPorPeca() {
        List<Divergencia> d = jdbc.query("""
                SELECT iv.id, iv.venda_id, iv.quantidade, iv.preco_unitario * iv.quantidade - iv.desconto AS total,
                       sum(i.quantidade) AS devolvidas, sum(i.valor) AS valor
                  FROM corona.itens_devolucao i
                  JOIN corona.devolucoes dv ON dv.id = i.devolucao_id AND dv.situacao <> 'CANCELADA'
                  JOIN corona.itens_venda iv ON iv.id = i.item_venda_id
                 GROUP BY iv.id, iv.venda_id, iv.quantidade, iv.preco_unitario, iv.desconto
                HAVING sum(i.quantidade) > iv.quantidade OR sum(i.valor) > iv.preco_unitario * iv.quantidade - iv.desconto
                 ORDER BY iv.venda_id, iv.id""", (rs, n) -> new Divergencia("Venda " + rs.getObject("venda_id") + ", linha "
                + rs.getObject("id"), "Voltaram " + rs.getLong("devolvidas") + " peça(s) (" + reais(rs.getBigDecimal("valor"))
                + "), mais que as " + rs.getLong("quantidade") + " vendidas (" + reais(rs.getBigDecimal("total")) + ")."));
        long conferidos = contar("""
                SELECT count(DISTINCT i.item_venda_id) FROM corona.itens_devolucao i
                  JOIN corona.devolucoes dv ON dv.id = i.devolucao_id AND dv.situacao <> 'CANCELADA'""");
        return new Conferencia("devolvido-por-peca", "Devolvido por peça, nunca mais que o vendido",
                "Em cada peça vendida, o que voltou (em peças e em dinheiro) não passa do que foi vendido.", conferidos, d);
    }

    // --- 3. créditos ---

    private Conferencia creditos() {
        List<Divergencia> d = jdbc.query("""
                SELECT cr.id, cr.valor, cr.valor_utilizado, coalesce(u.soma, 0) AS usos
                  FROM corona.creditos_cliente cr
                  LEFT JOIN (SELECT credito_id, sum(valor) AS soma FROM corona.usos_credito GROUP BY credito_id) u
                         ON u.credito_id = cr.id
                 WHERE cr.deleted_at IS NULL
                   AND (cr.valor_utilizado <> coalesce(u.soma, 0) OR coalesce(u.soma, 0) < 0 OR coalesce(u.soma, 0) > cr.valor
                        OR (cr.situacao = 'ABERTO' AND cr.valor_utilizado >= cr.valor)
                        OR (cr.situacao = 'UTILIZADO' AND cr.valor_utilizado <> cr.valor))
                 ORDER BY cr.gerado_em, cr.id""", (rs, n) -> new Divergencia("Crédito " + rs.getObject("id"),
                "Do crédito de " + reais(rs.getBigDecimal("valor")) + ", o saldo guardado diz "
                        + reais(rs.getBigDecimal("valor_utilizado")) + " usados, mas os usos somam "
                        + reais(rs.getBigDecimal("usos")) + "."));
        return new Conferencia("creditos", "Créditos menos usos é o que está em aberto",
                "Em cada crédito, o usado é a soma dos usos e nunca passa do valor; o que sobra é o crédito em aberto.",
                contar("SELECT count(*) FROM corona.creditos_cliente WHERE deleted_at IS NULL"), d);
    }

    // --- 4. dinheiro devolvido ---

    private Conferencia dinheiroDevolvido() {
        List<Divergencia> d = jdbc.query("""
                SELECT p.venda_id, coalesce(sum(p.valor) FILTER (WHERE p.tipo = 'PAGAMENTO'), 0) AS pago,
                       -coalesce(sum(p.valor) FILTER (WHERE p.tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO')), 0) AS devolvido
                  FROM corona.pagamentos_cliente p
                 WHERE p.deleted_at IS NULL AND p.forma <> 'CREDITO'
                 GROUP BY p.venda_id
                HAVING -coalesce(sum(p.valor) FILTER (WHERE p.tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO')), 0)
                       > coalesce(sum(p.valor) FILTER (WHERE p.tipo = 'PAGAMENTO'), 0)
                   AND NOT EXISTS (SELECT 1 FROM corona.devolucoes dv WHERE dv.venda_id = p.venda_id
                                    AND dv.situacao IN ('EM_REVISAO', 'MANTIDA'))
                 ORDER BY p.venda_id""", (rs, n) -> new Divergencia("Venda " + rs.getObject("venda_id"),
                "Foram devolvidos " + reais(rs.getBigDecimal("devolvido")) + " em dinheiro, mais que os "
                        + reais(rs.getBigDecimal("pago")) + " pagos."));
        long conferidos = contar("""
                SELECT count(DISTINCT venda_id) FROM corona.pagamentos_cliente
                 WHERE deleted_at IS NULL AND tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO') AND forma <> 'CREDITO'""");
        return new Conferencia("dinheiro-devolvido", "Dinheiro devolvido, nunca mais que o pago",
                "Em cada venda, o dinheiro que voltou ao cliente não passa do que ele pagou (salvo devolução que a dona já "
                        + "foi avisada para revisar).", conferidos, d);
    }

    // --- 5. as contas da venda ---

    private Conferencia contasDaVenda() {
        List<Divergencia> d = jdbc.query("""
                SELECT v.id, v.total, sum(p.valor) AS liquido,
                       v.total - coalesce(dv.devolvido, 0) AS teto
                  FROM corona.vendas v JOIN corona.pagamentos_cliente p ON p.venda_id = v.id AND p.deleted_at IS NULL
                  LEFT JOIN (SELECT venda_id, sum(valor_devolvido) AS devolvido
                               FROM corona.devolucoes WHERE situacao <> 'CANCELADA' AND deleted_at IS NULL
                              GROUP BY venda_id) dv ON dv.venda_id = v.id
                 WHERE v.deleted_at IS NULL AND v.status = 'CONCLUIDA'
                 GROUP BY v.id, v.total, dv.devolvido
                HAVING sum(p.valor) > v.total - coalesce(dv.devolvido, 0) OR sum(p.valor) < 0
                 ORDER BY v.id""", (rs, n) -> new Divergencia("Venda " + rs.getObject("id"), "A venda é de "
                + reais(rs.getBigDecimal("total")) + ", e o recebido, já descontado o que voltou, é "
                + reais(rs.getBigDecimal("liquido")) + " (o máximo certo seria " + reais(rs.getBigDecimal("teto")) + ")."));
        long conferidos = contar("""
                SELECT count(DISTINCT v.id) FROM corona.vendas v JOIN corona.pagamentos_cliente p ON p.venda_id = v.id
                 WHERE v.deleted_at IS NULL AND v.status = 'CONCLUIDA' AND p.deleted_at IS NULL""");
        return new Conferencia("contas-da-venda", "Contas da venda com devolução",
                "Em cada venda, o que foi recebido, menos o que voltou ao cliente, fica entre zero e o total da venda menos o que foi devolvido.",
                conferidos, d);
    }
}
