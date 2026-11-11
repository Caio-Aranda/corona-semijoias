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

    public record PagamentoLido(UUID id, String tipo, String forma, BigDecimal valor, Instant em) {
    }

    public record Venda(UUID id, UUID vendedorId, UUID clienteId, String clienteNome, UUID remessaId, String status,
            BigDecimal subtotal, BigDecimal desconto, BigDecimal total, BigDecimal totalDolar, BigDecimal totalGuarani,
            Cotacao cotacao, BigDecimal pago, BigDecimal aReceber, Instant vendidaEm, Instant estornadaEm,
            String motivoDoEstorno, List<Item> itens, List<PagamentoLido> pagamentos) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeVendas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                       v.vendida_em, v.estornada_em, v.motivo_estorno, ct.data_cotacao, ct.usd_venda, ct.pyg_venda,
                       coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p WHERE p.venda_id = v.id), 0) AS liquido
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
                    BigDecimal aReceber = "ESTORNADA".equals(status) ? BigDecimal.ZERO.setScale(2) : total.subtract(pago);
                    return new Venda(rs.getObject("id", UUID.class), rs.getObject("vendedor_id", UUID.class),
                            rs.getObject("cliente_id", UUID.class), rs.getString("nome"), rs.getObject("remessa_id", UUID.class),
                            status, rs.getBigDecimal("subtotal"), rs.getBigDecimal("desconto"), total,
                            usd == null ? null : ConversaoDeMoeda.paraDolar(total, usd),
                            pyg == null ? null : ConversaoDeMoeda.paraGuarani(total, pyg), cotacao, pago, aReceber,
                            rs.getTimestamp("vendida_em").toInstant(),
                            rs.getTimestamp("estornada_em") == null ? null : rs.getTimestamp("estornada_em").toInstant(),
                            rs.getString("motivo_estorno"), new ArrayList<>(), new ArrayList<>());
                }, args);
        for (Venda v : cabecas) {
            v.itens().addAll(jdbc.query("""
                    SELECT i.produto_id, p.nome, p.foto_principal_hash, i.quantidade, i.preco_unitario, i.desconto
                      FROM corona.itens_venda i JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE i.venda_id = ? ORDER BY p.nome, i.produto_id""", (rs, n) -> {
                        int q = rs.getInt("quantidade");
                        BigDecimal preco = rs.getBigDecimal("preco_unitario");
                        BigDecimal desconto = rs.getBigDecimal("desconto");
                        return new Item(new Peca(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                                rs.getString("foto_principal_hash")), q, preco, desconto,
                                preco.multiply(BigDecimal.valueOf(q)).subtract(desconto));
                    }, v.id()));
            v.pagamentos().addAll(jdbc.query("""
                    SELECT id, tipo, forma, valor, pago_em FROM corona.pagamentos_cliente WHERE venda_id = ?
                     ORDER BY pago_em, id""", (rs, n) -> new PagamentoLido(rs.getObject("id", UUID.class), rs.getString("tipo"),
                            rs.getString("forma"), rs.getBigDecimal("valor"), rs.getTimestamp("pago_em").toInstant()), v.id()));
        }
        return cabecas;
    }
}
