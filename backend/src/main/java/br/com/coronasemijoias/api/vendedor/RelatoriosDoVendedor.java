package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.cotacoes.ServicoDeCotacoesDaDona;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Relatórios do vendedor (§12, "App do vendedor"; tarefa 4.3): só dados dele, sem custo. Venda estornada não conta.
 * Dólar e Guarani saem da cotação de hoje, só para mostrar; o banco guarda Real (ADR-0006).
 */
@Component
public class RelatoriosDoVendedor {

    /** Uma linha do relatório: {@code id} e {@code nome} da chave (cliente, peça, forma ou dia). */
    public record Linha(String id, String nome, long quantidade, BigDecimal total, BigDecimal desconto) {
    }

    public record Totais(BigDecimal total, BigDecimal totalEmDolar, BigDecimal totalEmGuarani, LocalDate dataDaCotacao) {
    }

    public record Relatorio(List<Linha> linhas, Totais totais) {
    }

    public record Devedor(UUID clienteId, String clienteNome, BigDecimal vendido, BigDecimal pago, BigDecimal aReceber) {
    }

    public record RelatorioAReceber(List<Devedor> clientes, Totais totais) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeCotacoesDaDona cotacoes;

    RelatoriosDoVendedor(JdbcTemplate jdbc, ServicoDeCotacoesDaDona cotacoes) {
        this.jdbc = jdbc;
        this.cotacoes = cotacoes;
    }

    /** Vendas por cliente, por peça ou por dia, no período (datas em horário de Brasília). */
    public Relatorio vendas(UUID vendedorId, LocalDate de, LocalDate ate, String agrupar) {
        String grupo = agrupar == null ? "cliente" : agrupar;
        String sql = switch (grupo) {
            case "cliente" -> """
                    SELECT c.id::text AS chave, c.nome AS nome, count(*) AS quantidade, sum(v.total) AS total,
                           sum(v.desconto) AS desconto
                      FROM corona.vendas v JOIN corona.clientes c ON c.id = v.cliente_id
                     WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND\s""" + periodo()
                    + " GROUP BY c.id, c.nome ORDER BY lower(c.nome), c.id";
            case "peca" -> """
                    SELECT p.id::text AS chave, p.nome AS nome, sum(i.quantidade) AS quantidade,
                           sum(i.preco_unitario * i.quantidade - i.desconto) AS total, sum(i.desconto) AS desconto
                      FROM corona.vendas v JOIN corona.itens_venda i ON i.venda_id = v.id JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND\s""" + periodo()
                    + " GROUP BY p.id, p.nome ORDER BY lower(p.nome), p.id";
            case "dia" -> """
                    SELECT to_char((v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date, 'YYYY-MM-DD') AS chave,
                           to_char((v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date, 'DD/MM/YYYY') AS nome,
                           count(*) AS quantidade, sum(v.total) AS total, sum(v.desconto) AS desconto
                      FROM corona.vendas v
                     WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND\s""" + periodo()
                    + " GROUP BY 1, 2 ORDER BY 1";
            default -> throw Validacoes.recusar("agrupar", "Escolha agrupar por cliente, peça ou dia.");
        };
        List<Linha> linhas = jdbc.query(sql, (rs, n) -> new Linha(rs.getString("chave"), rs.getString("nome"),
                rs.getLong("quantidade"), rs.getBigDecimal("total"), rs.getBigDecimal("desconto")), vendedorId, de, de, ate, ate);
        return new Relatorio(linhas, totais(linhas.stream().map(Linha::total).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    /** O que o vendedor recebeu no período, por forma de pagamento (pagamentos menos reembolsos). */
    public Relatorio recebimentos(UUID vendedorId, LocalDate de, LocalDate ate) {
        List<Linha> linhas = jdbc.query("""
                SELECT p.forma AS chave, p.forma AS nome, count(*) FILTER (WHERE p.tipo = 'PAGAMENTO') AS quantidade,
                       sum(p.valor) AS total, 0::numeric AS desconto
                  FROM corona.pagamentos_cliente p JOIN corona.vendas v ON v.id = p.venda_id
                 WHERE v.vendedor_id = ? AND v.deleted_at IS NULL
                   AND (?::date IS NULL OR (p.pago_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?::date)
                   AND (?::date IS NULL OR (p.pago_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?::date)
                 GROUP BY p.forma ORDER BY p.forma""", (rs, n) -> new Linha(rs.getString("chave"), rs.getString("nome"),
                rs.getLong("quantidade"), rs.getBigDecimal("total"), rs.getBigDecimal("desconto").setScale(2)),
                vendedorId, de, de, ate, ate);
        return new Relatorio(linhas, totais(linhas.stream().map(Linha::total).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    /** Valores a receber dos clientes do vendedor: vendas (não estornadas) menos o pago, só quem deve. */
    public RelatorioAReceber aReceber(UUID vendedorId) {
        List<Devedor> clientes = jdbc.query("""
                SELECT c.id, c.nome, sum(v.total) AS vendido,
                       sum(coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p WHERE p.venda_id = v.id), 0)) AS pago
                  FROM corona.vendas v JOIN corona.clientes c ON c.id = v.cliente_id
                 WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL
                 GROUP BY c.id, c.nome HAVING sum(v.total) > sum(coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p
                                                                          WHERE p.venda_id = v.id), 0))
                 ORDER BY lower(c.nome), c.id""", (rs, n) -> {
                    BigDecimal vendido = rs.getBigDecimal("vendido");
                    BigDecimal pago = rs.getBigDecimal("pago");
                    return new Devedor(rs.getObject("id", UUID.class), rs.getString("nome"), vendido, pago, vendido.subtract(pago));
                }, vendedorId);
        BigDecimal total = clientes.stream().map(Devedor::aReceber).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RelatorioAReceber(new ArrayList<>(clientes), totais(total));
    }

    private static String periodo() {
        return "(?::date IS NULL OR (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?::date) "
                + "AND (?::date IS NULL OR (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?::date)";
    }

    private Totais totais(BigDecimal reais) {
        BigDecimal total = reais.setScale(2);
        return cotacoes.cotacaoDeHoje()
                .map(c -> new Totais(total, c.emDolar(total), c.emGuarani(total), c.dataCotacao()))
                .orElseGet(() -> new Totais(total, null, null, null));
    }
}
