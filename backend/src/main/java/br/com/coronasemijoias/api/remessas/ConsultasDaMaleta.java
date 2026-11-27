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

    /**
     * {@code limite} é vazio quando o vendedor está "sem limite" ({@code semLimite}) ou sem limite definido
     * ({@code limiteDefinido} falso: a remessa é recusada até a dona definir; V026).
     */
    public record EmAberto(UUID vendedorId, String vendedorNome, BigDecimal limite, boolean semLimite, boolean limiteDefinido,
            BigDecimal valorEmAberto, BigDecimal limiteRestante, List<MaletaEmAberto> maletas) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDaMaleta(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Peças que o representante deixou com as clientes, por maleta e peça: as saídas e voltas das notas que ainda não têm
     * acerto valendo (C1, 27/11/2026). Com acerto, o vendido sai de vez e o devolvido já voltou à maleta.
     */
    static final String COM_AS_CLIENTES = """
            (SELECT m.remessa_id, m.produto_id, -sum(m.quantidade) AS qtd
               FROM corona.movimentos_remessa m
               JOIN corona.notas_consignado n ON n.id = m.nota_id AND n.deleted_at IS NULL
              WHERE m.tipo IN ('CONSIGNADO_SAIDA', 'CONSIGNADO_RETORNO')
                AND NOT EXISTS (SELECT 1 FROM corona.acertos_nota a WHERE a.nota_id = n.id AND a.estornado_em IS NULL)
              GROUP BY m.remessa_id, m.produto_id)""";

    /**
     * O que está com o representante, por linha da maleta: o saldo da maleta mais as peças com as clientes. Na maleta já
     * fechada, o saldo é o que as clientes devolveram depois e a dona ainda não leu (§23.10, item 3); a falta da conclusão
     * já saiu como perda.
     */
    private static final String QTD_COM_ELE = "(i.qtd_enviada + coalesce(m.soma, 0) + coalesce(c.qtd, 0))";

    private static final String JUNTA_SALDO_E_CLIENTES = """
              LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma
                           FROM corona.movimentos_remessa GROUP BY remessa_id, produto_id) m
                     ON m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id
              LEFT JOIN """ + COM_AS_CLIENTES + """
             c ON c.remessa_id = i.remessa_id AND c.produto_id = i.produto_id
            """;

    /** Maletas que ainda podem ter algo com o representante (na maleta ou com as clientes). */
    private static final String COM_ELE_OU_FECHADA = "('ENVIADA', 'CONFERIDA', 'EM_ACERTO', 'FECHADA')";

    /**
     * Valor, a preço de venda congelado, de tudo o que ainda está com o representante: o saldo das maletas enviadas e as
     * peças que estão com as clientes dele (notas sem acerto). É o que conta no limite de consignação.
     */
    public BigDecimal valorEmAberto(UUID vendedorId) {
        BigDecimal valor = jdbc.queryForObject("SELECT coalesce(sum(" + QTD_COM_ELE + " * i.preco_remessa), 0)"
                + """

                  FROM corona.remessas r
                  JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL
                """ + JUNTA_SALDO_E_CLIENTES
                + " WHERE r.vendedor_id = ? AND r.deleted_at IS NULL AND r.status IN " + COM_ELE_OU_FECHADA,
                BigDecimal.class, vendedorId);
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
                "SELECT nome, limite_consignacao, limite_consignacao_ilimitado FROM corona.vendedores WHERE id = ? AND deleted_at IS NULL", vendedorId);
        if (vendedor.isEmpty()) {
            throw ServicoDeCadastro.naoEncontrado();
        }
        List<MaletaEmAberto> maletas = jdbc.query("""
                SELECT r.id, s.numero, r.tipo, r.status, r.enviada_em, r.total_pecas, r.total_valor,
                """ + "coalesce(sum(" + QTD_COM_ELE + "), 0) AS pecas, coalesce(sum(" + QTD_COM_ELE + " * i.preco_remessa), 0) AS valor"
                + """

                  FROM corona.remessas r
                  JOIN corona.semanas s ON s.id = r.semana_id
                  LEFT JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL
                """ + JUNTA_SALDO_E_CLIENTES
                + " WHERE r.vendedor_id = ? AND r.deleted_at IS NULL AND r.status IN " + COM_ELE_OU_FECHADA + """

                 GROUP BY r.id, s.numero, r.tipo, r.status, r.enviada_em, r.total_pecas, r.total_valor
                """ + " HAVING r.status <> 'FECHADA' OR coalesce(sum(" + QTD_COM_ELE + "), 0) > 0" + """

                 ORDER BY r.enviada_em DESC, r.id DESC""", (rs, n) -> new MaletaEmAberto(rs.getObject("id", UUID.class),
                rs.getInt("numero"), rs.getString("tipo"), rs.getString("status"), rs.getTimestamp("enviada_em").toInstant(),
                rs.getInt("total_pecas"), rs.getBigDecimal("total_valor"), rs.getInt("pecas"),
                Dinheiro.arredondar(rs.getBigDecimal("valor"))), vendedorId);
        BigDecimal total = Dinheiro.arredondar(maletas.stream().map(MaletaEmAberto::valorEmAberto)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal cadastrado = (BigDecimal) vendedor.get(0).get("limite_consignacao");
        boolean semLimite = Boolean.TRUE.equals(vendedor.get(0).get("limite_consignacao_ilimitado"));
        // Zero vale zero (V026); vazio com "Sem limite" desmarcado é limite ainda não definido.
        BigDecimal limite = cadastrado == null ? null : Dinheiro.arredondar(cadastrado);
        BigDecimal restante = limite == null ? null : limite.subtract(total).max(BigDecimal.ZERO);
        return new EmAberto(vendedorId, (String) vendedor.get(0).get("nome"), limite, semLimite, semLimite || limite != null, total,
                restante, maletas);
    }
}
