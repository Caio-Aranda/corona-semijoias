package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura do caixa (tarefa 7.6; ADR-0012, item 11): só a dona. O saldo é sempre a soma dos lançamentos, sem atalho gravado.
 * Tudo em Real. O lançamento manual, as categorias e o aviso de saldo negativo são da tarefa 7.7.
 */
@Component
public class ConsultasDoCaixa {

    private static final int LIMITE = 5000;

    /** Um lançamento, com o saldo do caixa depois dele (na ordem do extrato). {@code vendedorNome}: de quem veio ou a quem foi o dinheiro. */
    public record Lancamento(UUID id, String tipo, BigDecimal valor, String forma, String categoria, String origemTipo, UUID origemId,
            String vendedorNome, LocalDate dataLancamento, Instant lancadoEm, String descricao, UUID estornaId, BigDecimal saldoDepois) {
    }

    /** O extrato do período: o saldo antes, as entradas, as saídas (com sinal), o saldo no fim e os lançamentos. */
    public record Extrato(LocalDate de, LocalDate ate, BigDecimal saldoAnterior, BigDecimal entradas, BigDecimal saidas,
            BigDecimal saldoFinal, List<Lancamento> lancamentos) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDoCaixa(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Extrato extrato(LocalDate de, LocalDate ate) {
        if (de != null && ate != null && ate.isBefore(de)) {
            throw Validacoes.recusar("ate", "A data final não pode ser antes da inicial.");
        }
        BigDecimal anterior = de == null ? BigDecimal.ZERO.setScale(2)
                : jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa WHERE data_lancamento < ?", BigDecimal.class, de);
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder();
        if (de != null) {
            onde.append(" AND l.data_lancamento >= ?");
            args.add(de);
        }
        if (ate != null) {
            onde.append(" AND l.data_lancamento <= ?");
            args.add(ate);
        }
        BigDecimal[] corrente = {anterior};
        List<Lancamento> lista = jdbc.query("""
                SELECT l.id, l.tipo, l.valor, l.forma, c.nome AS categoria, l.origem_tipo, l.origem_id, l.data_lancamento, l.lancado_em,
                       l.descricao, l.estorna_id,
                       coalesce(vr.nome, va.nome) AS vendedor_nome
                  FROM corona.lancamentos_caixa l JOIN corona.categorias_caixa c ON c.id = l.categoria_id
                  LEFT JOIN corona.recebimentos_vendedor r ON l.origem_tipo = 'RECEBIMENTO_VENDEDOR' AND r.id = l.origem_id
                  LEFT JOIN corona.vendedores vr ON vr.id = r.vendedor_id
                  LEFT JOIN corona.acertos a ON l.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND a.id = l.origem_id
                  LEFT JOIN corona.vendedores va ON va.id = a.vendedor_id
                 WHERE true""" + onde + " ORDER BY l.data_lancamento, l.lancado_em, l.id LIMIT " + LIMITE, (rs, n) -> {
                    BigDecimal valor = rs.getBigDecimal("valor");
                    corrente[0] = corrente[0].add(valor);
                    return new Lancamento(rs.getObject("id", UUID.class), rs.getString("tipo"), valor, rs.getString("forma"),
                            rs.getString("categoria"), rs.getString("origem_tipo"), rs.getObject("origem_id", UUID.class),
                            rs.getString("vendedor_nome"), rs.getObject("data_lancamento", LocalDate.class),
                            rs.getTimestamp("lancado_em").toInstant(), rs.getString("descricao"), rs.getObject("estorna_id", UUID.class),
                            corrente[0]);
                }, args.toArray());
        BigDecimal entradas = lista.stream().map(Lancamento::valor).filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        BigDecimal saidas = lista.stream().map(Lancamento::valor).filter(v -> v.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        return new Extrato(de, ate, anterior, entradas, saidas, anterior.add(entradas).add(saidas), lista);
    }
}
