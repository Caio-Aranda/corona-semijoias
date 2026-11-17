package br.com.coronasemijoias.api.vendas;

import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura das vendas (tarefa 4.2). Nada de custo: só preço de venda, desconto e pagamentos. Dólar e Guarani são
 * calculados na leitura pela cotação do dia da venda (ADR-0006); o banco guarda só Real.
 */
@Component
public class ConsultasDeVendas {

    public record Cotacao(LocalDate data, BigDecimal dolar, BigDecimal guarani) {
    }

    public record Peca(UUID id, String nome, String fotoHash) {
    }

    public record Item(Peca produto, int quantidade, BigDecimal precoUnitario, BigDecimal desconto, BigDecimal total) {
    }

    /** {@code creditoId}: o crédito do cliente da forma {@code CREDITO} (tarefa 6.5). */
    public record PagamentoLido(UUID id, String tipo, String forma, BigDecimal valor, Instant em, UUID creditoId,
            boolean posterior) {
    }

    /** Uma parcela da venda a prazo (tarefa 7.3). "Vencida" é calculada pela data de hoje em Brasília. */
    public record ParcelaDaVenda(UUID id, int numero, int totalDeParcelas, LocalDate vencimento, BigDecimal valor,
            BigDecimal valorPago, BigDecimal valorCancelado, BigDecimal emAberto, String status, boolean vencida) {
    }

    public record Venda(UUID id, UUID vendedorId, UUID clienteId, String clienteNome, UUID remessaId, String status,
            BigDecimal subtotal, BigDecimal desconto, BigDecimal total, BigDecimal totalDolar, BigDecimal totalGuarani,
            Cotacao cotacao, BigDecimal pago, BigDecimal devolvido, BigDecimal aReceber, Instant vendidaEm, Instant estornadaEm,
            String motivoDoEstorno, UUID trocaId, List<Item> itens, List<PagamentoLido> pagamentos,
            List<ParcelaDaVenda> parcelas) {
    }

    private final JdbcTemplate jdbc;
    private final java.time.Clock relogio;

    ConsultasDeVendas(JdbcTemplate jdbc, java.time.Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    /** {@code vendedorId} nulo é a dona (vê qualquer venda); o vendedor só vê as dele (404 nas outras). */
    public Venda ver(UUID id, UUID vendedorId) {
        List<Venda> achadas = consultar("v.id = ?" + (vendedorId == null ? "" : " AND v.vendedor_id = ?"),
                vendedorId == null ? new Object[] {id} : new Object[] {id, vendedorId});
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        return achadas.get(0);
    }

    /** As vendas do vendedor, da mais nova para a mais antiga, com filtros opcionais. */
    public List<Venda> listar(UUID vendedorId, LocalDate de, LocalDate ate, UUID clienteId, String status) {
        StringBuilder onde = new StringBuilder("v.vendedor_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(vendedorId);
        if (de != null) {
            onde.append(" AND (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            args.add(de);
        }
        if (ate != null) {
            onde.append(" AND (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            args.add(ate);
        }
        if (clienteId != null) {
            onde.append(" AND v.cliente_id = ?");
            args.add(clienteId);
        }
        if (status != null) {
            onde.append(" AND v.status = ?");
            args.add(status);
        }
        return consultar(onde.toString(), args.toArray());
    }

    private List<Venda> consultar(String onde, Object[] args) {
        List<Venda> cabecas = jdbc.query("""
                SELECT v.id, v.vendedor_id, v.cliente_id, c.nome, v.remessa_id, v.status, v.subtotal, v.desconto, v.total,
                       v.vendida_em, v.estornada_em, v.motivo_estorno, v.troca_id, ct.data_cotacao, ct.usd_venda, ct.pyg_venda,
                       coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p WHERE p.venda_id = v.id), 0) AS liquido,
                       coalesce((SELECT sum(d.valor_devolvido) FROM corona.devolucoes d
                                  WHERE d.venda_id = v.id AND d.situacao <> 'CANCELADA' AND d.deleted_at IS NULL), 0) AS devolvido
                  FROM corona.vendas v JOIN corona.clientes c ON c.id = v.cliente_id
                  LEFT JOIN corona.cotacoes ct ON ct.id = v.cotacao_id
                 WHERE v.deleted_at IS NULL AND
                       """ + onde + " ORDER BY v.vendida_em DESC, v.id DESC", (rs, n) -> {
                    BigDecimal total = rs.getBigDecimal("total");
                    BigDecimal usd = rs.getBigDecimal("usd_venda");
                    BigDecimal pyg = rs.getBigDecimal("pyg_venda");
                    Cotacao cotacao = usd == null ? null
                            : new Cotacao(rs.getObject("data_cotacao", LocalDate.class), usd, pyg);
                    String status = rs.getString("status");
                    BigDecimal pago = rs.getBigDecimal("liquido");
                    BigDecimal devolvido = rs.getBigDecimal("devolvido");
                    // O devolvido (devoluções não canceladas, tarefa 6.3) sai do que o cliente deve: total - devolvido - pago.
                    BigDecimal aReceber = "ESTORNADA".equals(status) ? BigDecimal.ZERO.setScale(2)
                            : total.subtract(devolvido).subtract(pago);
                    return new Venda(rs.getObject("id", UUID.class), rs.getObject("vendedor_id", UUID.class),
                            rs.getObject("cliente_id", UUID.class), rs.getString("nome"), rs.getObject("remessa_id", UUID.class),
                            status, rs.getBigDecimal("subtotal"), rs.getBigDecimal("desconto"), total,
                            usd == null ? null : ConversaoDeMoeda.paraDolar(total, usd),
                            pyg == null ? null : ConversaoDeMoeda.paraGuarani(total, pyg), cotacao, pago, devolvido, aReceber,
                            rs.getTimestamp("vendida_em").toInstant(),
                            rs.getTimestamp("estornada_em") == null ? null : rs.getTimestamp("estornada_em").toInstant(),
                            rs.getString("motivo_estorno"), rs.getObject("troca_id", UUID.class), new ArrayList<>(), new ArrayList<>(),
                            new ArrayList<>());
                }, args);
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), java.time.ZoneId.of("America/Sao_Paulo"));
        if (cabecas.isEmpty()) {
            return cabecas;
        }
        // Uma consulta por tipo de filho para todas as vendas (e não 2 ou 3 por venda; auditoria M3).
        java.util.Map<UUID, Venda> porId = new java.util.HashMap<>();
        for (Venda v : cabecas) {
            porId.put(v.id(), v);
        }
        UUID[] ids = porId.keySet().toArray(new UUID[0]);
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT i.venda_id, i.produto_id, p.nome, p.foto_principal_hash, i.quantidade, i.preco_unitario, i.desconto
                      FROM corona.itens_venda i JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE i.venda_id = ANY (?) ORDER BY p.nome, i.produto_id""");
            ps.setArray(1, con.createArrayOf("uuid", ids));
            return ps;
        }, rs -> {
            int q = rs.getInt("quantidade");
            BigDecimal preco = rs.getBigDecimal("preco_unitario");
            BigDecimal desconto = rs.getBigDecimal("desconto");
            porId.get(rs.getObject("venda_id", UUID.class)).itens()
                    .add(new Item(new Peca(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                            rs.getString("foto_principal_hash")), q, preco, desconto,
                            preco.multiply(BigDecimal.valueOf(q)).subtract(desconto)));
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT venda_id, id, tipo, forma, valor, pago_em, credito_id, posterior FROM corona.pagamentos_cliente
                     WHERE venda_id = ANY (?) ORDER BY pago_em, id""");
            ps.setArray(1, con.createArrayOf("uuid", ids));
            return ps;
        }, rs -> {
            porId.get(rs.getObject("venda_id", UUID.class)).pagamentos()
                    .add(new PagamentoLido(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getString("forma"),
                            rs.getBigDecimal("valor"), rs.getTimestamp("pago_em").toInstant(),
                            rs.getObject("credito_id", UUID.class), rs.getBoolean("posterior")));
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT venda_id, id, parcela, total_parcelas, vencimento, valor, valor_pago, valor_cancelado,
                           valor - valor_pago - valor_cancelado AS em_aberto, status,
                           (status IN ('ABERTA', 'PARCIAL') AND vencimento < ?::date) AS vencida
                      FROM corona.contas_receber WHERE venda_id = ANY (?) AND deleted_at IS NULL ORDER BY parcela""");
            ps.setObject(1, hoje);
            ps.setArray(2, con.createArrayOf("uuid", ids));
            return ps;
        }, rs -> {
            porId.get(rs.getObject("venda_id", UUID.class)).parcelas()
                    .add(new ParcelaDaVenda(rs.getObject("id", UUID.class), rs.getInt("parcela"), rs.getInt("total_parcelas"),
                            rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"), rs.getBigDecimal("valor_pago"),
                            rs.getBigDecimal("valor_cancelado"), rs.getBigDecimal("em_aberto"), rs.getString("status"),
                            rs.getBoolean("vencida")));
        });
        return cabecas;
    }
}
