package br.com.coronasemijoias.api.remessas;

import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.remessas.ConsultasDeRemessas.Produto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leituras do que está com o vendedor (tarefa 3.3): saldo por peça, valor em aberto a preço congelado e o que sobra do
 * limite. O saldo é sempre {@code qtd_enviada} mais a soma dos movimentos (ADR-0010, item 5), nunca um número guardado.
 * Só vale para maleta enviada (ou adiante): rascunho e cancelada não têm nada com o vendedor. Nunca devolve custo.
 */
@Component
public class ConsultasDaMaleta {

    /** Estados em que as peças estão com o vendedor. */
    static final String COM_O_VENDEDOR = "('ENVIADA', 'CONFERIDA', 'EM_ACERTO')";

    public record Linha(Produto produto, int enviada, int devolvida, int saldo, BigDecimal precoUnitario, BigDecimal valorEmAberto) {
    }

    public record Movimento(UUID id, String tipo, UUID produtoId, String produtoNome, int quantidade, UUID estornaId,
            boolean desfeito, Instant em) {
    }

    public record Maleta(UUID id, String status, String motivoDoCancelamento, UUID vendedorId, int pecasEmAberto,
            BigDecimal valorEmAberto, List<Linha> itens, List<Movimento> movimentos) {
    }

    public record MaletaEmAberto(UUID id, int semanaNumero, String tipo, String status, Instant enviadaEm, int totalPecas,
            BigDecimal totalValor, int pecasEmAberto, BigDecimal valorEmAberto) {
    }

    public record EmAberto(UUID vendedorId, String vendedorNome, BigDecimal limite, BigDecimal valorEmAberto,
            BigDecimal limiteRestante, List<MaletaEmAberto> maletas) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDaMaleta(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Valor, a preço de venda congelado, do que ainda está com o vendedor (saldo atual das maletas enviadas). */
    public BigDecimal valorEmAberto(UUID vendedorId) {
        BigDecimal valor = jdbc.queryForObject("""
                SELECT coalesce(sum((i.qtd_enviada + coalesce(m.soma, 0)) * i.preco_remessa), 0)
                  FROM corona.remessas r
                  JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL
                  LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma
                               FROM corona.movimentos_remessa GROUP BY remessa_id, produto_id) m
                         ON m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id
                 WHERE r.vendedor_id = ? AND r.deleted_at IS NULL AND r.status IN """ + COM_O_VENDEDOR, BigDecimal.class, vendedorId);
        return valor == null ? BigDecimal.ZERO : Dinheiro.arredondar(valor);
    }

    @Transactional(readOnly = true)
    public Maleta ver(UUID remessaId) {
        var cabecalho = jdbc.queryForList(
                "SELECT status, motivo_cancelamento, vendedor_id FROM corona.remessas WHERE id = ? AND deleted_at IS NULL", remessaId);
        if (cabecalho.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        String status = (String) cabecalho.get(0).get("status");
        boolean comOVendedor = List.of("ENVIADA", "CONFERIDA", "EM_ACERTO").contains(status);
        List<Linha> itens = jdbc.query("""
                SELECT i.qtd_enviada, i.preco_remessa, p.preco_venda, p.id AS produto_id, p.nome, p.referencia,
                       p.foto_principal_hash, (SELECT cd.codigo FROM corona.produto_codigos cd
                         WHERE cd.produto_id = p.id AND cd.principal AND cd.deleted_at IS NULL LIMIT 1) AS codigo,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) AS soma,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id
                                    AND m.tipo = 'DEVOLUCAO_DONA'), 0) AS soma_devolvida
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, i.id""", (rs, n) -> {
                    int enviada = rs.getInt("qtd_enviada");
                    BigDecimal congelado = rs.getBigDecimal("preco_remessa");
                    BigDecimal preco = congelado != null ? congelado : rs.getBigDecimal("preco_venda");
                    int saldo = comOVendedor ? enviada + rs.getInt("soma") : 0;
                    return new Linha(new Produto(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                            rs.getString("referencia"), rs.getString("codigo"), rs.getString("foto_principal_hash")),
                            enviada, comOVendedor ? -rs.getInt("soma_devolvida") : 0, saldo, preco,
                            Dinheiro.precoDaLinha(preco, saldo));
                }, remessaId);
        List<Movimento> movimentos = jdbc.query("""
                SELECT m.id, m.tipo, m.produto_id, p.nome, m.quantidade, m.estorna_id, m.created_at,
                       EXISTS (SELECT 1 FROM corona.movimentos_remessa e WHERE e.estorna_id = m.id) AS desfeito
                  FROM corona.movimentos_remessa m JOIN corona.produtos p ON p.id = m.produto_id
                 WHERE m.remessa_id = ? ORDER BY m.created_at, m.id""",
                (rs, n) -> new Movimento(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getObject("produto_id", UUID.class),
                        rs.getString("nome"), rs.getInt("quantidade"), rs.getObject("estorna_id", UUID.class),
                        rs.getBoolean("desfeito"), rs.getTimestamp("created_at").toInstant()), remessaId);
        int pecas = itens.stream().mapToInt(Linha::saldo).sum();
        BigDecimal valor = Dinheiro.arredondar(itens.stream().map(Linha::valorEmAberto).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new Maleta(remessaId, status, (String) cabecalho.get(0).get("motivo_cancelamento"),
                (UUID) cabecalho.get(0).get("vendedor_id"), pecas, valor, itens, movimentos);
    }

    @Transactional(readOnly = true)
    public EmAberto emAberto(UUID vendedorId) {
        var vendedor = jdbc.queryForList(
                "SELECT nome, limite_consignacao FROM corona.vendedores WHERE id = ? AND deleted_at IS NULL", vendedorId);
        if (vendedor.isEmpty()) {
            throw ServicoDeCadastro.naoEncontrado();
        }
        List<MaletaEmAberto> maletas = jdbc.query("""
                SELECT r.id, s.numero, r.tipo, r.status, r.enviada_em, r.total_pecas, r.total_valor,
                       coalesce(sum(i.qtd_enviada + coalesce(m.soma, 0)), 0) AS pecas,
                       coalesce(sum((i.qtd_enviada + coalesce(m.soma, 0)) * i.preco_remessa), 0) AS valor
                  FROM corona.remessas r
                  JOIN corona.semanas s ON s.id = r.semana_id
                  LEFT JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL
                  LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma
                               FROM corona.movimentos_remessa GROUP BY remessa_id, produto_id) m
                         ON m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id
                 WHERE r.vendedor_id = ? AND r.deleted_at IS NULL AND r.status IN """ + COM_O_VENDEDOR + """

                 GROUP BY r.id, s.numero, r.tipo, r.status, r.enviada_em, r.total_pecas, r.total_valor
                 ORDER BY r.enviada_em DESC, r.id DESC""", (rs, n) -> new MaletaEmAberto(rs.getObject("id", UUID.class),
                rs.getInt("numero"), rs.getString("tipo"), rs.getString("status"), rs.getTimestamp("enviada_em").toInstant(),
                rs.getInt("total_pecas"), rs.getBigDecimal("total_valor"), rs.getInt("pecas"),
                Dinheiro.arredondar(rs.getBigDecimal("valor"))), vendedorId);
        BigDecimal total = Dinheiro.arredondar(maletas.stream().map(MaletaEmAberto::valorEmAberto)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal cadastrado = (BigDecimal) vendedor.get(0).get("limite_consignacao");
        // Limite 0 no cadastro significa "sem limite cadastrado" (decisão da tarefa 3.2).
        BigDecimal limite = cadastrado != null && cadastrado.signum() > 0 ? Dinheiro.arredondar(cadastrado) : null;
        BigDecimal restante = limite == null ? null : limite.subtract(total).max(BigDecimal.ZERO);
        return new EmAberto(vendedorId, (String) vendedor.get(0).get("nome"), limite, total, restante, maletas);
    }
}
