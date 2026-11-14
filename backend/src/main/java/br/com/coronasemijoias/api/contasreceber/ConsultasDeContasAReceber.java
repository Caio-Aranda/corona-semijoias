package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura das contas a receber do cliente (tarefa 7.3). Só valores em Real e nada de custo. "Vencida" é calculada pela data
 * de hoje em Brasília (parcela em aberto ou parcial com o vencimento antes de hoje) e nunca é gravada.
 */
@Component
public class ConsultasDeContasAReceber {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final int LIMITE = 1000;
    private static final Set<String> SITUACOES = Set.of("ABERTA", "PARCIAL", "PAGA", "CANCELADA", "EM_ABERTO", "VENCIDA");

    /** A parcela como o vendedor a vê: só a dele. */
    public record Conta(UUID id, UUID vendaId, UUID clienteId, String clienteNome, int numero, int totalDeParcelas,
            LocalDate vencimento, BigDecimal valor, BigDecimal valorPago, BigDecimal valorCancelado, BigDecimal emAberto,
            String status, boolean vencida) {
    }

    /** A parcela como a dona a vê: com o vendedor da venda. */
    public record ContaDaDona(UUID id, UUID vendaId, UUID vendedorId, String vendedorNome, UUID clienteId, String clienteNome,
            int numero, int totalDeParcelas, LocalDate vencimento, BigDecimal valor, BigDecimal valorPago,
            BigDecimal valorCancelado, BigDecimal emAberto, String status, boolean vencida) {
    }

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    ConsultasDeContasAReceber(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    /** As parcelas do vendedor, da que vence primeiro para a que vence depois. */
    public List<Conta> doVendedor(UUID vendedorId, String situacao, UUID clienteId, UUID vendaId) {
        List<Object> args = new ArrayList<>();
        String onde = filtros(args, vendedorId, situacao, clienteId, vendaId);
        return jdbc.query(SELECT + onde + ORDEM, (rs, n) -> new Conta(rs.getObject("id", UUID.class),
                rs.getObject("venda_id", UUID.class), rs.getObject("cliente_id", UUID.class), rs.getString("cliente_nome"),
                rs.getInt("parcela"), rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class),
                rs.getBigDecimal("valor"), rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_cancelado"),
                rs.getBigDecimal("em_aberto"), rs.getString("status"), rs.getBoolean("vencida")), args.toArray());
    }

    /** Todas as parcelas, com filtros opcionais por vendedor, cliente e situação. */
    public List<ContaDaDona> daDona(UUID vendedorId, String situacao, UUID clienteId) {
        List<Object> args = new ArrayList<>();
        String onde = filtros(args, vendedorId, situacao, clienteId, null);
        return jdbc.query(SELECT + onde + ORDEM, (rs, n) -> new ContaDaDona(rs.getObject("id", UUID.class),
                rs.getObject("venda_id", UUID.class), rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor_nome"),
                rs.getObject("cliente_id", UUID.class), rs.getString("cliente_nome"), rs.getInt("parcela"),
                rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"),
                rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_cancelado"), rs.getBigDecimal("em_aberto"),
                rs.getString("status"), rs.getBoolean("vencida")), args.toArray());
    }

    private static final String SELECT = """
            SELECT c.id, c.venda_id, v.vendedor_id, ved.nome AS vendedor_nome, c.cliente_id, cl.nome AS cliente_nome, c.parcela,
                   c.total_parcelas, c.vencimento, c.valor, c.valor_pago, c.valor_cancelado,
                   c.valor - c.valor_pago - c.valor_cancelado AS em_aberto, c.status,
                   (c.status IN ('ABERTA', 'PARCIAL') AND c.vencimento < ?::date) AS vencida
              FROM corona.contas_receber c
              JOIN corona.vendas v ON v.id = c.venda_id
              JOIN corona.vendedores ved ON ved.id = v.vendedor_id
              JOIN corona.clientes cl ON cl.id = c.cliente_id
             WHERE c.deleted_at IS NULL AND v.deleted_at IS NULL""";

    private static final String ORDEM = " ORDER BY c.vencimento, c.venda_id, c.parcela LIMIT " + LIMITE;

    /** Monta o WHERE: o primeiro argumento é sempre a data de hoje (a coluna "vencida" do SELECT). */
    private String filtros(List<Object> args, UUID vendedorId, String situacao, UUID clienteId, UUID vendaId) {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), BRASILIA);
        args.add(hoje);
        StringBuilder onde = new StringBuilder();
        if (vendedorId != null) {
            onde.append(" AND v.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (clienteId != null) {
            onde.append(" AND c.cliente_id = ?");
            args.add(clienteId);
        }
        if (vendaId != null) {
            onde.append(" AND c.venda_id = ?");
            args.add(vendaId);
        }
        if (situacao != null && !situacao.isBlank()) {
            if (!SITUACOES.contains(situacao)) {
                throw Validacoes.recusar("situacao", "Escolha a situação: em aberto, vencida, paga ou cancelada.");
            }
            switch (situacao) {
                case "EM_ABERTO" -> onde.append(" AND c.status IN ('ABERTA', 'PARCIAL')");
                case "VENCIDA" -> {
                    onde.append(" AND c.status IN ('ABERTA', 'PARCIAL') AND c.vencimento < ?::date");
                    args.add(hoje);
                }
                default -> {
                    onde.append(" AND c.status = ?");
                    args.add(situacao);
                }
            }
        }
        return onde.toString();
    }
}
