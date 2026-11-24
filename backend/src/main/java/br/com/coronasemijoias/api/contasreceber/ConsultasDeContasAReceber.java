package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.cotacoes.ContasEmTresMoedas;
import br.com.coronasemijoias.api.cotacoes.ContasEmTresMoedas.Moedas;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            String status, boolean vencida, String nivel, String origem, String formaPrevista) {
    }

    /** A parcela como a dona a vê: com o vendedor da venda. */
    public record ContaDaDona(UUID id, UUID vendaId, UUID vendedorId, String vendedorNome, UUID clienteId, String clienteNome,
            int numero, int totalDeParcelas, LocalDate vencimento, BigDecimal valor, BigDecimal valorPago,
            BigDecimal valorCancelado, BigDecimal emAberto, String status, boolean vencida, String nivel, String origem,
            String formaPrevista, BigDecimal valorEmDolar, BigDecimal valorEmGuarani, BigDecimal valorPagoEmDolar,
            BigDecimal valorPagoEmGuarani) {

        ContaDaDona comMoedas(Moedas doValor, Moedas doPago) {
            return new ContaDaDona(id, vendaId, vendedorId, vendedorNome, clienteId, clienteNome, numero, totalDeParcelas, vencimento,
                    valor, valorPago, valorCancelado, emAberto, status, vencida, nivel, origem, formaPrevista, doValor.dolar(),
                    doValor.guarani(), doPago.dolar(), doPago.guarani());
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    ConsultasDeContasAReceber(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    /** As parcelas do vendedor nos dois níveis (o que os clientes devem a ele e o que ele deve à loja), por vencimento. */
    public List<Conta> doVendedor(UUID vendedorId, String situacao, UUID clienteId, UUID vendaId) {
        return doVendedor(vendedorId, situacao, clienteId, vendaId, null);
    }

    /** {@code nivel}: CLIENTE, VENDEDOR ou vazio (os dois). Cliente ou venda no filtro só tem conta do nível CLIENTE. */
    public List<Conta> doVendedor(UUID vendedorId, String situacao, UUID clienteId, UUID vendaId, String nivel) {
        String qual = nivel(nivel);
        List<Conta> todas = new ArrayList<>();
        if (!"VENDEDOR".equals(qual)) {
            List<Object> args = new ArrayList<>();
            String onde = filtros(args, vendedorId, situacao, clienteId, vendaId);
            todas.addAll(jdbc.query(SELECT + onde + ORDEM, (rs, n) -> new Conta(rs.getObject("id", UUID.class),
                    rs.getObject("venda_id", UUID.class), rs.getObject("cliente_id", UUID.class), rs.getString("cliente_nome"),
                    rs.getInt("parcela"), rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class),
                    rs.getBigDecimal("valor"), rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_cancelado"),
                    rs.getBigDecimal("em_aberto"), rs.getString("status"), rs.getBoolean("vencida"), "CLIENTE", "VENDA", null),
                    args.toArray()));
        }
        if (!"CLIENTE".equals(qual) && clienteId == null && vendaId == null) {
            List<Object> args = new ArrayList<>();
            String onde = filtrosDoVendedor(args, vendedorId, situacao);
            todas.addAll(jdbc.query(SELECT_DO_VENDEDOR + onde + ORDEM_DO_VENDEDOR, (rs, n) -> new Conta(rs.getObject("id", UUID.class), null,
                    null, null, rs.getInt("parcela"), rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class),
                    rs.getBigDecimal("valor"), rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_cancelado"),
                    rs.getBigDecimal("em_aberto"), rs.getString("status"), rs.getBoolean("vencida"), "VENDEDOR",
                    rs.getString("origem_tipo"), rs.getString("forma_prevista")), args.toArray()));
        }
        todas.sort(java.util.Comparator.comparing(Conta::vencimento));
        return todas;
    }

    /** Todas as parcelas dos dois níveis, com filtros opcionais por vendedor, cliente, situação e nível. */
    public List<ContaDaDona> daDona(UUID vendedorId, String situacao, UUID clienteId) {
        return daDona(vendedorId, situacao, clienteId, null);
    }

    public List<ContaDaDona> daDona(UUID vendedorId, String situacao, UUID clienteId, String nivel) {
        String qual = nivel(nivel);
        List<ContaDaDona> todas = new ArrayList<>();
        if (!"VENDEDOR".equals(qual)) {
            List<Object> args = new ArrayList<>();
            String onde = filtros(args, vendedorId, situacao, clienteId, null);
            todas.addAll(jdbc.query(SELECT + onde + ORDEM, (rs, n) -> new ContaDaDona(rs.getObject("id", UUID.class),
                    rs.getObject("venda_id", UUID.class), rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor_nome"),
                    rs.getObject("cliente_id", UUID.class), rs.getString("cliente_nome"), rs.getInt("parcela"),
                    rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"),
                    rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_cancelado"), rs.getBigDecimal("em_aberto"),
                    rs.getString("status"), rs.getBoolean("vencida"), "CLIENTE", "VENDA", null, null, null, null, null), args.toArray()));
        }
        if (!"CLIENTE".equals(qual) && clienteId == null) {
            List<Object> args = new ArrayList<>();
            String onde = filtrosDoVendedor(args, vendedorId, situacao);
            todas.addAll(jdbc.query(SELECT_DO_VENDEDOR + onde + ORDEM_DO_VENDEDOR, (rs, n) -> new ContaDaDona(rs.getObject("id", UUID.class),
                    null, rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor_nome"), null, null, rs.getInt("parcela"),
                    rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"),
                    rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_cancelado"), rs.getBigDecimal("em_aberto"),
                    rs.getString("status"), rs.getBoolean("vencida"), "VENDEDOR", rs.getString("origem_tipo"),
                    rs.getString("forma_prevista"), null, null, null, null), args.toArray()));
        }
        todas.sort(java.util.Comparator.comparing(ContaDaDona::vencimento));
        return emTresMoedas(todas);
    }

    /**
     * Dólar e Guarani de cada conta (achado M3 da revisão de 23/11/2026): o valor pela cotação gravada na venda ou no acerto (sem ela,
     * pela do momento da venda ou da conta); cada pagamento pela do momento dele (o estorno, pela do pagamento que desfaz).
     */
    private List<ContaDaDona> emTresMoedas(List<ContaDaDona> contas) {
        if (contas.isEmpty()) {
            return contas;
        }
        String ids = ContasEmTresMoedas.lista(contas.stream().map(ContaDaDona::id).toList());
        Map<UUID, Moedas> doValor = ContasEmTresMoedas.somar(jdbc, "SELECT cr.id, cr.valor, coalesce(cv.usd_venda, ct.usd_venda),"
                + " coalesce(cv.pyg_venda, ct.pyg_venda) FROM corona.contas_receber cr"
                + " LEFT JOIN corona.vendas v ON v.id = cr.venda_id"
                + " LEFT JOIN corona.acertos a ON cr.origem_tipo = 'ACERTO' AND a.id = cr.origem_id"
                + " LEFT JOIN corona.cotacoes cv ON cv.id = coalesce(v.cotacao_id, a.cotacao_id) "
                + CotacaoVigente.joinDoMomento("coalesce(v.vendida_em, cr.created_at)", "c.usd_venda, c.pyg_venda")
                + " WHERE cr.id = ANY (?::uuid[])", ids);
        Map<UUID, Moedas> doPago = ContasEmTresMoedas.somar(jdbc, "SELECT b.conta_id, b.valor, ct.usd_venda, ct.pyg_venda"
                + " FROM corona.baixas_receber b LEFT JOIN corona.baixas_receber o ON o.id = b.estorna_id "
                + CotacaoVigente.joinDoMomento("coalesce(o.baixada_em, b.baixada_em)", "c.usd_venda, c.pyg_venda")
                + " WHERE b.tipo = 'PAGAMENTO' AND b.deleted_at IS NULL AND b.conta_id = ANY (?::uuid[])", ids);
        return contas.stream().map(c -> c.comMoedas(doValor.getOrDefault(c.id(), Moedas.SEM_COTACAO),
                doPago.getOrDefault(c.id(), Moedas.ZERO))).toList();
    }

    private static String nivel(String nivel) {
        if (nivel == null || nivel.isBlank()) {
            return null;
        }
        if (!Set.of("CLIENTE", "VENDEDOR").contains(nivel)) {
            throw Validacoes.recusar("nivel", "Escolha o nível: o que os clientes devem ou o que o representante deve à loja.");
        }
        return nivel;
    }

    /** As contas que o vendedor deve à loja (nível 2). */
    private static final String SELECT_DO_VENDEDOR = """
            SELECT c.id, c.vendedor_id, ved.nome AS vendedor_nome, c.origem_tipo, c.parcela, c.total_parcelas, c.vencimento, c.valor,
                   c.valor_pago, c.valor_cancelado, c.valor - c.valor_pago - c.valor_cancelado AS em_aberto, c.status, c.forma_prevista,
                   (c.status IN ('ABERTA', 'PARCIAL') AND c.vencimento < ?::date) AS vencida
              FROM corona.contas_receber c JOIN corona.vendedores ved ON ved.id = c.vendedor_id
             WHERE c.devedor_tipo = 'VENDEDOR' AND c.deleted_at IS NULL""";

    private static final String ORDEM_DO_VENDEDOR = " ORDER BY c.vencimento, c.origem_id, c.parcela LIMIT " + LIMITE;

    private String filtrosDoVendedor(List<Object> args, UUID vendedorId, String situacao) {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), BRASILIA);
        args.add(hoje);
        StringBuilder onde = new StringBuilder();
        if (vendedorId != null) {
            onde.append(" AND c.vendedor_id = ?");
            args.add(vendedorId);
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
