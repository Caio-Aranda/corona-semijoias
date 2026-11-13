package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Cotacao;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Peca;
import java.math.BigDecimal;
import java.time.Clock;
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
 * Leitura dos pedidos de autorização (tarefa 6.4). Nada de custo nem de fornecedor: a venda, o cliente, o vendedor, as
 * peças (nome e foto), o valor pedido em Real e, pela cotação do dia do pedido, em Dólar e Guarani (ADR-0006).
 */
@Component
public class ConsultasDeAutorizacoes {

    private static final Set<String> SITUACOES = Set.of("PEDIDA", "APROVADA", "NEGADA", "USADA", "VENCIDA");
    private static final int LIMITE_DA_LISTA = 500;

    public record ItemDaAutorizacao(UUID id, UUID itemVendaId, Peca produto, int quantidade) {
    }

    /**
     * {@code situacao}: a aprovada cujo último dia já passou aparece como {@code VENCIDA}, mesmo antes da agenda diária.
     * {@code diasDesdeAVenda}: dias de Brasília entre a venda e o pedido. {@code prazoDaGarantiaAte}: o último dia da
     * garantia pelo motivo, com o prazo de hoje.
     */
    public record Autorizacao(UUID id, UUID vendaId, UUID vendedorId, String vendedorNome, UUID clienteId, String clienteNome,
            String motivo, String observacao, String tipo, String situacao, BigDecimal valorPedido, BigDecimal valorPedidoDolar,
            BigDecimal valorPedidoGuarani, Cotacao cotacao, Instant vendidaEm, long diasDesdeAVenda, LocalDate prazoDaGarantiaAte,
            Instant pedidaEm, boolean horaAjustada, Instant decididaEm, String motivoDecisao, LocalDate validaAte,
            List<ItemDaAutorizacao> itens) {
    }

    private final JdbcTemplate jdbc;
    private final Configuracoes configuracoes;
    private final Clock relogio;

    ConsultasDeAutorizacoes(JdbcTemplate jdbc, Configuracoes configuracoes, Clock relogio) {
        this.jdbc = jdbc;
        this.configuracoes = configuracoes;
        this.relogio = relogio;
    }

    /** {@code vendedorId} nulo é a dona (vê qualquer pedido); o vendedor só vê os dele (404 nos outros). */
    public Autorizacao ver(UUID id, UUID vendedorId) {
        List<Autorizacao> achadas = consultar("a.id = ?" + (vendedorId == null ? "" : " AND a.vendedor_id = ?"),
                vendedorId == null ? new Object[] {id} : new Object[] {id, vendedorId});
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.AUTORIZACAO_NAO_ENCONTRADA);
        }
        return achadas.get(0);
    }

    /** Os pedidos do vendedor, do mais novo para o mais antigo, com filtro opcional de venda. */
    public List<Autorizacao> listarDoVendedor(UUID vendedorId, UUID vendaId) {
        if (vendaId == null) {
            return consultar("a.vendedor_id = ?", new Object[] {vendedorId});
        }
        return consultar("a.vendedor_id = ? AND a.venda_id = ?", new Object[] {vendedorId, vendaId});
    }

    /** A lista da dona: pela situação gravada (padrão: esperando a dona) e, se vier, pelo vendedor. */
    public List<Autorizacao> listarDaDona(String situacao, UUID vendedorId) {
        String filtro = situacao == null ? "PEDIDA" : situacao;
        if (!SITUACOES.contains(filtro)) {
            throw Validacoes.recusar("situacao", "Escolha a situação: esperando a dona, aprovado, negado, usado ou vencido.");
        }
        if (vendedorId == null) {
            return consultar("a.situacao = ?", new Object[] {filtro});
        }
        return consultar("a.situacao = ? AND a.vendedor_id = ?", new Object[] {filtro, vendedorId});
    }

    private List<Autorizacao> consultar(String onde, Object[] args) {
        LocalDate hoje = RegrasDaDevolucao.dia(relogio.instant());
        List<Autorizacao> cabecas = jdbc.query("""
                SELECT a.id, a.venda_id, a.vendedor_id, vd.nome AS vendedor_nome, a.cliente_id, c.nome AS cliente_nome, a.motivo,
                       a.observacao, a.tipo, a.situacao, a.valor_pedido, v.vendida_em, a.pedida_em, a.hora_ajustada,
                       a.decidida_em, a.motivo_decisao, a.valida_ate, ct.data_cotacao, ct.usd_venda, ct.pyg_venda
                  FROM corona.autorizacoes_excecao a
                  JOIN corona.vendas v ON v.id = a.venda_id
                  JOIN corona.vendedores vd ON vd.id = a.vendedor_id
                  JOIN corona.clientes c ON c.id = a.cliente_id
                  LEFT JOIN corona.cotacoes ct ON ct.id = a.cotacao_id
                 WHERE a.deleted_at IS NULL AND\s""" + onde + " ORDER BY a.pedida_em DESC, a.id DESC LIMIT " + LIMITE_DA_LISTA,
                (rs, n) -> {
                    BigDecimal valor = rs.getBigDecimal("valor_pedido");
                    BigDecimal usd = rs.getBigDecimal("usd_venda");
                    BigDecimal pyg = rs.getBigDecimal("pyg_venda");
                    Cotacao cotacao = usd == null ? null : new Cotacao(rs.getObject("data_cotacao", LocalDate.class), usd, pyg);
                    Instant vendidaEm = rs.getTimestamp("vendida_em").toInstant();
                    Instant pedidaEm = rs.getTimestamp("pedida_em").toInstant();
                    String motivo = rs.getString("motivo");
                    LocalDate validaAte = rs.getObject("valida_ate", LocalDate.class);
                    String situacao = rs.getString("situacao");
                    if ("APROVADA".equals(situacao) && validaAte != null && validaAte.isBefore(hoje)) {
                        situacao = "VENCIDA";
                    }
                    return new Autorizacao(rs.getObject("id", UUID.class), rs.getObject("venda_id", UUID.class),
                            rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor_nome"),
                            rs.getObject("cliente_id", UUID.class), rs.getString("cliente_nome"), motivo, rs.getString("observacao"),
                            rs.getString("tipo"), situacao, valor, usd == null ? null : ConversaoDeMoeda.paraDolar(valor, usd),
                            pyg == null ? null : ConversaoDeMoeda.paraGuarani(valor, pyg), cotacao, vendidaEm,
                            RegrasDaDevolucao.diasDesdeAVenda(vendidaEm, pedidaEm),
                            RegrasDaDevolucao.dia(vendidaEm).plusDays(RegrasDaDevolucao.prazo(configuracoes, motivo)), pedidaEm,
                            rs.getBoolean("hora_ajustada"),
                            rs.getTimestamp("decidida_em") == null ? null : rs.getTimestamp("decidida_em").toInstant(),
                            rs.getString("motivo_decisao"), validaAte, new ArrayList<>());
                }, args);
        for (Autorizacao a : cabecas) {
            a.itens().addAll(jdbc.query("""
                    SELECT x.id, x.item_venda_id, i.produto_id, p.nome, p.foto_principal_hash, x.quantidade
                      FROM corona.itens_autorizacao x JOIN corona.itens_venda i ON i.id = x.item_venda_id
                      JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE x.autorizacao_id = ? ORDER BY p.nome, i.produto_id""", (rs, n) -> new ItemDaAutorizacao(
                            rs.getObject("id", UUID.class), rs.getObject("item_venda_id", UUID.class),
                            new Peca(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                                    rs.getString("foto_principal_hash")),
                            rs.getInt("quantidade")), a.id()));
        }
        return cabecas;
    }
}
