package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura do caixa (tarefas 7.6 e 7.7; ADR-0012, item 11): só a dona. O saldo é sempre a soma dos lançamentos, sem atalho gravado.
 * Tudo em Real.
 */
@Component
public class ConsultasDoCaixa {

    private static final int LIMITE = 5000;

    /**
     * Um lançamento, com o saldo do caixa depois dele (na ordem do extrato; vazio quando o lançamento é lido sozinho).
     * {@code vendedorNome}: de quem veio ou a quem foi o dinheiro; {@code fornecedorNome}: a quem foi pago; {@code motivo}: do estorno.
     */
    public record Lancamento(UUID id, String tipo, BigDecimal valor, String forma, String categoria, String origemTipo, UUID origemId,
            String vendedorNome, LocalDate dataLancamento, Instant lancadoEm, String descricao, UUID estornaId, BigDecimal saldoDepois,
            String fornecedorNome, String motivo) {
    }

    /** O extrato do período: o saldo antes, as entradas, as saídas (com sinal), o saldo no fim e os lançamentos. */
    public record Extrato(LocalDate de, LocalDate ate, BigDecimal saldoAnterior, BigDecimal entradas, BigDecimal saidas,
            BigDecimal saldoFinal, List<Lancamento> lancamentos) {
    }

    /** O saldo do caixa agora e se está negativo (só avisa; nada é bloqueado). */
    public record Saldo(BigDecimal saldo, boolean negativo) {
    }

    private static final String SELECAO = """
            SELECT l.id, l.tipo, l.valor, l.forma, c.nome AS categoria, l.origem_tipo, l.origem_id, l.data_lancamento, l.lancado_em,
                   l.descricao, l.estorna_id, l.motivo,
                   coalesce(vr.nome, va.nome) AS vendedor_nome, f.razao_social AS fornecedor_nome
              FROM corona.lancamentos_caixa l JOIN corona.categorias_caixa c ON c.id = l.categoria_id
              LEFT JOIN corona.recebimentos_vendedor r ON l.origem_tipo = 'RECEBIMENTO_VENDEDOR' AND r.id = l.origem_id
              LEFT JOIN corona.vendedores vr ON vr.id = r.vendedor_id
              LEFT JOIN corona.acertos a ON l.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND a.id = l.origem_id
              LEFT JOIN corona.vendedores va ON va.id = a.vendedor_id
              LEFT JOIN corona.baixas_pagar bp ON l.origem_tipo = 'PAGAMENTO_FORNECEDOR' AND bp.id = l.origem_id
              LEFT JOIN corona.contas_pagar cp ON cp.id = bp.conta_id
              LEFT JOIN corona.fornecedores f ON f.id = cp.fornecedor_id""";

    private final JdbcTemplate jdbc;

    ConsultasDoCaixa(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Saldo saldo() {
        BigDecimal saldo = jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa", BigDecimal.class);
        return new Saldo(saldo, saldo.signum() < 0);
    }

    /** Um lançamento sozinho (a resposta de lançar e de estornar). */
    public Lancamento ver(UUID id) {
        List<Lancamento> achado = jdbc.query(SELECAO + " WHERE l.id = ?", (rs, n) -> linha(rs, null), id);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.LANCAMENTO_DO_CAIXA_NAO_ENCONTRADO);
        }
        return achado.get(0);
    }

    public Extrato extrato(LocalDate de, LocalDate ate) {
        if (de != null && ate != null && ate.isBefore(de)) {
            throw Validacoes.recusar("ate", "A data final não pode ser antes da inicial.");
        }
        BigDecimal anterior = de == null ? BigDecimal.ZERO.setScale(2)
                : jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa WHERE data_lancamento < ?", BigDecimal.class, de);
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder(" WHERE true");
        if (de != null) {
            onde.append(" AND l.data_lancamento >= ?");
            args.add(de);
        }
        if (ate != null) {
            onde.append(" AND l.data_lancamento <= ?");
            args.add(ate);
        }
        BigDecimal[] corrente = {anterior};
        List<Lancamento> lista = jdbc.query(SELECAO + onde + " ORDER BY l.data_lancamento, l.lancado_em, l.id LIMIT " + LIMITE, (rs, n) -> {
            corrente[0] = corrente[0].add(rs.getBigDecimal("valor"));
            return linha(rs, corrente[0]);
        }, args.toArray());
        BigDecimal entradas = lista.stream().map(Lancamento::valor).filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        BigDecimal saidas = lista.stream().map(Lancamento::valor).filter(v -> v.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        return new Extrato(de, ate, anterior, entradas, saidas, anterior.add(entradas).add(saidas), lista);
    }

    private static Lancamento linha(ResultSet rs, BigDecimal saldoDepois) throws SQLException {
        return new Lancamento(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getBigDecimal("valor"), rs.getString("forma"),
                rs.getString("categoria"), rs.getString("origem_tipo"), rs.getObject("origem_id", UUID.class), rs.getString("vendedor_nome"),
                rs.getObject("data_lancamento", LocalDate.class), rs.getTimestamp("lancado_em").toInstant(), rs.getString("descricao"),
                rs.getObject("estorna_id", UUID.class), saldoDepois, rs.getString("fornecedor_nome"), rs.getString("motivo"));
    }
}
