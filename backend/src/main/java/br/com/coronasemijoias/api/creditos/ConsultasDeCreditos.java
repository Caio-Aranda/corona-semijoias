package br.com.coronasemijoias.api.creditos;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Cotacao;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura dos créditos do cliente (tarefa 6.5). Só valores de venda (nada de custo): o valor, o que já foi usado, o
 * saldo, a origem e os usos. Dólar e Guarani do saldo pela cotação do dia em que o crédito nasceu (ADR-0006).
 */
@Component
public class ConsultasDeCreditos {

    private static final Set<String> SITUACOES = Set.of("ABERTO", "UTILIZADO", "CANCELADO");

    /** Um uso do crédito: {@code USO} e {@code ESTORNO} com a venda; {@code TRANSFERENCIA} com o crédito novo. */
    public record UsoDoCredito(UUID id, String tipo, UUID vendaId, UUID creditoDestinoId, BigDecimal valor, Instant em) {
    }

    /** O crédito do cliente. {@code saldo} = valor menos o usado (zero no cancelado). */
    public record CreditoDoCliente(UUID id, UUID clienteId, String clienteNome, UUID vendedorId, String origem, UUID trocaId,
            UUID devolucaoId, UUID creditoOrigemId, BigDecimal valor, BigDecimal valorUtilizado, BigDecimal saldo,
            BigDecimal saldoDolar, BigDecimal saldoGuarani, String situacao, Cotacao cotacao, Instant geradoEm,
            Instant canceladoEm, List<UsoDoCredito> usos) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeCreditos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code vendedorId} nulo é a dona (vê qualquer crédito); o vendedor só vê os dele (404 nos outros). */
    public CreditoDoCliente ver(UUID id, UUID vendedorId) {
        List<CreditoDoCliente> achados = consultar("cr.id = ?" + (vendedorId == null ? "" : " AND cr.vendedor_id = ?"),
                vendedorId == null ? new Object[] {id} : new Object[] {id, vendedorId});
        if (achados.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CREDITO_NAO_ENCONTRADO);
        }
        return achados.get(0);
    }

    /** Os créditos do vendedor (todas as situações), com filtro opcional de cliente e de situação. */
    public List<CreditoDoCliente> doVendedor(UUID vendedorId, UUID clienteId, String situacao) {
        return listar(vendedorId, clienteId, situacao);
    }

    /** A lista da dona: pela situação (padrão: em aberto), com filtros opcionais de cliente e de vendedor. */
    public List<CreditoDoCliente> daDona(UUID clienteId, UUID vendedorId, String situacao) {
        return listar(vendedorId, clienteId, situacao == null ? "ABERTO" : situacao);
    }

    private List<CreditoDoCliente> listar(UUID vendedorId, UUID clienteId, String situacao) {
        StringBuilder onde = new StringBuilder("TRUE");
        List<Object> args = new ArrayList<>();
        if (vendedorId != null) {
            onde.append(" AND cr.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (clienteId != null) {
            onde.append(" AND cr.cliente_id = ?");
            args.add(clienteId);
        }
        if (situacao != null) {
            if (!SITUACOES.contains(situacao)) {
                throw Validacoes.recusar("situacao", "Escolha a situação: em aberto, usado ou cancelado.");
            }
            onde.append(" AND cr.situacao = ?");
            args.add(situacao);
        }
        return consultar(onde.toString(), args.toArray());
    }

    private List<CreditoDoCliente> consultar(String onde, Object[] args) {
        List<CreditoDoCliente> cabecas = jdbc.query("""
                SELECT cr.id, cr.cliente_id, c.nome, cr.vendedor_id, cr.origem, cr.troca_id, cr.devolucao_id, cr.credito_origem_id,
                       cr.valor, cr.valor_utilizado, cr.situacao, cr.gerado_em, cr.cancelado_em,
                       ct.data_cotacao, ct.usd_venda, ct.pyg_venda
                  FROM corona.creditos_cliente cr JOIN corona.clientes c ON c.id = cr.cliente_id
                  LEFT JOIN corona.cotacoes ct ON ct.id = cr.cotacao_id
                 WHERE cr.deleted_at IS NULL AND \s""" + onde + " ORDER BY cr.gerado_em DESC, cr.id DESC LIMIT 500", (rs, n) -> {
                    String situacao = rs.getString("situacao");
                    BigDecimal valor = rs.getBigDecimal("valor");
                    BigDecimal usado = rs.getBigDecimal("valor_utilizado");
                    BigDecimal saldo = "CANCELADO".equals(situacao) ? BigDecimal.ZERO.setScale(2) : valor.subtract(usado);
                    BigDecimal usd = rs.getBigDecimal("usd_venda");
                    BigDecimal pyg = rs.getBigDecimal("pyg_venda");
                    Cotacao cotacao = usd == null ? null : new Cotacao(rs.getObject("data_cotacao", LocalDate.class), usd, pyg);
                    return new CreditoDoCliente(rs.getObject("id", UUID.class), rs.getObject("cliente_id", UUID.class),
                            rs.getString("nome"), rs.getObject("vendedor_id", UUID.class), rs.getString("origem"),
                            rs.getObject("troca_id", UUID.class), rs.getObject("devolucao_id", UUID.class),
                            rs.getObject("credito_origem_id", UUID.class), valor, usado, saldo,
                            usd == null ? null : ConversaoDeMoeda.paraDolar(saldo, usd),
                            pyg == null ? null : ConversaoDeMoeda.paraGuarani(saldo, pyg), situacao, cotacao,
                            rs.getTimestamp("gerado_em").toInstant(),
                            rs.getTimestamp("cancelado_em") == null ? null : rs.getTimestamp("cancelado_em").toInstant(),
                            new ArrayList<>());
                }, args);
        for (CreditoDoCliente cr : cabecas) {
            cr.usos().addAll(jdbc.query("""
                    SELECT id, tipo, venda_id, credito_destino_id, valor, usado_em FROM corona.usos_credito
                     WHERE credito_id = ? ORDER BY usado_em, id""", (rs, n) -> new UsoDoCredito(rs.getObject(1, UUID.class),
                            rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getBigDecimal(5),
                            rs.getTimestamp(6).toInstant()), cr.id()));
        }
        return cabecas;
    }
}
