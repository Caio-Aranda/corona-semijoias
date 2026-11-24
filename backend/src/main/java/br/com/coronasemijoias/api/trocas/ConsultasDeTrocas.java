package br.com.coronasemijoias.api.trocas;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Cotacao;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Peca;
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
 * Leitura das trocas (tarefa 6.5). Nada de custo nem de fornecedor: as peças que voltaram e as que saíram (nome e foto),
 * os valores em Real e, pela cotação do dia da troca, a diferença em Dólar e Guarani (ADR-0006).
 */
@Component
public class ConsultasDeTrocas {

    private static final Set<String> SITUACOES = Set.of("ATIVA", "CANCELADA");

    /** Uma peça da troca. {@code estado} e {@code destino} só nas que voltaram (Boa ou Com defeito; Maleta ou Loja). */
    public record PecaDaTroca(Peca produto, int quantidade, BigDecimal valor, String estado, String destino) {
    }

    /**
     * A troca. {@code diferenca} = total da venda nova menos o valor em crédito de A (positiva: a diferença a pagar; negativa:
     * ficou de crédito). {@code creditoEmAberto}: o que ainda sobra hoje no crédito da troca (zero se cancelada ou usada).
     */
    public record TrocaLida(UUID id, UUID vendedorId, UUID clienteId, String clienteNome, String situacao, UUID vendaOrigemId,
            UUID devolucaoId, UUID vendaNovaId, UUID creditoId, BigDecimal valorDevolvido, BigDecimal totalDaVendaNova,
            BigDecimal diferenca, BigDecimal diferencaDolar, BigDecimal diferencaGuarani, BigDecimal creditoEmAberto,
            Cotacao cotacao, Instant trocadaEm, boolean horaAjustada, Instant canceladaEm, String motivoCancelamento,
            List<PecaDaTroca> voltaram, List<PecaDaTroca> sairam) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeTrocas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code vendedorId} nulo é a dona (vê qualquer troca); o vendedor só vê as dele (404 nas outras). */
    public TrocaLida ver(UUID id, UUID vendedorId) {
        List<TrocaLida> achadas = consultar("t.id = ?" + (vendedorId == null ? "" : " AND t.vendedor_id = ?"),
                vendedorId == null ? new Object[] {id} : new Object[] {id, vendedorId});
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.TROCA_NAO_ENCONTRADA);
        }
        return achadas.get(0);
    }

    /**
     * As trocas, da mais nova para a mais antiga, no máximo 500. O vendedor passa o próprio id (só as dele); a dona pode
     * filtrar pelo vendedor. Filtros opcionais por dia (de e até, em Brasília) e situação.
     */
    public List<TrocaLida> listar(UUID vendedorId, LocalDate de, LocalDate ate, String situacao) {
        StringBuilder onde = new StringBuilder("TRUE");
        List<Object> args = new ArrayList<>();
        if (vendedorId != null) {
            onde.append(" AND t.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (de != null) {
            onde.append(" AND (t.trocada_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            args.add(de);
        }
        if (ate != null) {
            onde.append(" AND (t.trocada_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            args.add(ate);
        }
        if (situacao != null) {
            if (!SITUACOES.contains(situacao)) {
                throw Validacoes.recusar("situacao", "Escolha a situação: feita ou cancelada.");
            }
            onde.append(" AND t.situacao = ?");
            args.add(situacao);
        }
        return consultar(onde.toString(), args.toArray());
    }

    private List<TrocaLida> consultar(String onde, Object[] args) {
        List<TrocaLida> cabecas = jdbc.query("""
                SELECT t.id, t.vendedor_id, t.cliente_id, c.nome, t.situacao, t.venda_origem_id, t.devolucao_id, t.venda_nova_id,
                       t.diferenca, t.trocada_em, t.hora_ajustada, t.cancelada_em, t.motivo_cancelamento,
                       d.valor_devolvido, vn.total, cr.id AS credito_id,
                       CASE WHEN cr.situacao = 'ABERTO' THEN cr.valor - cr.valor_utilizado ELSE 0 END AS em_aberto,
                       ct.data_cotacao, ct.usd_venda, ct.pyg_venda
                  FROM corona.trocas t
                  JOIN corona.clientes c ON c.id = t.cliente_id
                  JOIN corona.devolucoes d ON d.id = t.devolucao_id
                  JOIN corona.vendas vn ON vn.id = t.venda_nova_id
                  LEFT JOIN corona.creditos_cliente cr ON cr.troca_id = t.id
                  LEFT JOIN corona.cotacoes ct ON ct.id = t.cotacao_id
                 WHERE t.deleted_at IS NULL AND \s""" + onde + " ORDER BY t.trocada_em DESC, t.id DESC LIMIT 500", (rs, n) -> {
                    BigDecimal diferenca = rs.getBigDecimal("diferenca");
                    BigDecimal usd = rs.getBigDecimal("usd_venda");
                    BigDecimal pyg = rs.getBigDecimal("pyg_venda");
                    Cotacao cotacao = usd == null ? null : new Cotacao(rs.getObject("data_cotacao", LocalDate.class), usd, pyg);
                    return new TrocaLida(rs.getObject("id", UUID.class), rs.getObject("vendedor_id", UUID.class),
                            rs.getObject("cliente_id", UUID.class), rs.getString("nome"), rs.getString("situacao"),
                            rs.getObject("venda_origem_id", UUID.class), rs.getObject("devolucao_id", UUID.class),
                            rs.getObject("venda_nova_id", UUID.class), rs.getObject("credito_id", UUID.class),
                            rs.getBigDecimal("valor_devolvido"), rs.getBigDecimal("total"), diferenca,
                            usd == null ? null : ConversaoDeMoeda.paraDolar(diferenca, usd),
                            pyg == null ? null : ConversaoDeMoeda.paraGuarani(diferenca, pyg),
                            rs.getBigDecimal("em_aberto").setScale(2), cotacao, rs.getTimestamp("trocada_em").toInstant(),
                            rs.getBoolean("hora_ajustada"),
                            rs.getTimestamp("cancelada_em") == null ? null : rs.getTimestamp("cancelada_em").toInstant(),
                            rs.getString("motivo_cancelamento"), new ArrayList<>(), new ArrayList<>());
                }, args);
        for (TrocaLida t : cabecas) {
            t.voltaram().addAll(jdbc.query("""
                    SELECT iv.produto_id, p.nome, p.foto_principal_hash, x.quantidade, x.valor, x.estado_peca, x.destino_peca
                      FROM corona.itens_devolucao x JOIN corona.itens_venda iv ON iv.id = x.item_venda_id
                      JOIN corona.produtos p ON p.id = iv.produto_id
                     WHERE x.devolucao_id = ? ORDER BY p.nome, iv.produto_id, x.estado_peca""", (rs, n) -> new PecaDaTroca(
                            new Peca(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), rs.getInt(4),
                            rs.getBigDecimal(5), rs.getString(6), rs.getString(7)), t.devolucaoId()));
            t.sairam().addAll(jdbc.query("""
                    SELECT i.produto_id, p.nome, p.foto_principal_hash, i.quantidade, i.preco_unitario * i.quantidade - i.desconto
                      FROM corona.itens_venda i JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE i.venda_id = ? ORDER BY p.nome, i.produto_id""", (rs, n) -> new PecaDaTroca(
                            new Peca(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), rs.getInt(4),
                            rs.getBigDecimal(5), null, null), t.vendaNovaId()));
        }
        return cabecas;
    }
}
