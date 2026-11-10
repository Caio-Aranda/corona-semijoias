package br.com.coronasemijoias.api.cotacoes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Acesso à tabela {@code cotacoes} (imutável: só inserção e leitura). Colunas sempre explícitas. A
 * gravação é uma linha só, com o Dólar e o Guarani juntos, então nunca fica cotação parcial.
 */
@Component
public class RepositorioDeCotacoes {

    private static final String COLUNAS = """
            id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra, usd_paridade_venda,
            pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte""";

    private static final RowMapper<Cotacao> MAPEADOR = RepositorioDeCotacoes::mapear;

    private final JdbcTemplate jdbc;

    public RepositorioDeCotacoes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava o dia se ainda não existir. Dia já gravado nunca é sobrescrito (ADR-0006, item 3): devolve
     * {@code false} e não muda nada.
     */
    public boolean gravar(LocalDate dia, TaxasDoDia taxas, Instant buscadaEm, String fonte) {
        int linhas = jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda,
                    buscada_em, fonte)
                VALUES (uuidv7(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT cotacoes_data_unica DO NOTHING""",
                dia,
                taxas.usdCompra(), taxas.usdVenda(), taxas.usdParidadeCompra(), taxas.usdParidadeVenda(),
                taxas.pygCompra(), taxas.pygVenda(), taxas.pygParidadeCompra(), taxas.pygParidadeVenda(),
                buscadaEm.atOffset(ZoneOffset.UTC), fonte);
        return linhas == 1;
    }

    /** Os dias do intervalo (inclusive) que já têm cotação. */
    public Set<LocalDate> diasComCotacao(LocalDate de, LocalDate ate) {
        List<LocalDate> dias = jdbc.query(
                "SELECT data_cotacao FROM corona.cotacoes WHERE data_cotacao BETWEEN ? AND ?",
                (resultado, i) -> resultado.getObject(1, LocalDate.class), de, ate);
        return new HashSet<>(dias);
    }

    /** A cotação de maior {@code data_cotacao} que seja menor ou igual ao dia (ADR-0006, item 7). */
    public Optional<Cotacao> ateODia(LocalDate dia) {
        return jdbc.query("SELECT " + COLUNAS
                        + " FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                MAPEADOR, dia).stream().findFirst();
    }

    /** A cotação de maior {@code data_cotacao} que existe. */
    public Optional<Cotacao> maisRecente() {
        return jdbc.query("SELECT " + COLUNAS + " FROM corona.cotacoes ORDER BY data_cotacao DESC LIMIT 1", MAPEADOR)
                .stream().findFirst();
    }

    private static Cotacao mapear(ResultSet r, int linha) throws SQLException {
        TaxasDoDia taxas = new TaxasDoDia(
                r.getBigDecimal("usd_compra"), r.getBigDecimal("usd_venda"),
                r.getBigDecimal("usd_paridade_compra"), r.getBigDecimal("usd_paridade_venda"),
                r.getBigDecimal("pyg_compra"), r.getBigDecimal("pyg_venda"),
                r.getBigDecimal("pyg_paridade_compra"), r.getBigDecimal("pyg_paridade_venda"));
        return new Cotacao(
                r.getObject("id", java.util.UUID.class),
                r.getObject("data_cotacao", LocalDate.class),
                taxas,
                r.getObject("buscada_em", OffsetDateTime.class).toInstant(),
                r.getString("fonte"));
    }
}
