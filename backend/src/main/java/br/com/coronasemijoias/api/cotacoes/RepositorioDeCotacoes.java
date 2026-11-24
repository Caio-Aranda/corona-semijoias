package br.com.coronasemijoias.api.cotacoes;

import java.math.BigDecimal;
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
import java.util.UUID;
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
            pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte,
            origem, vigente_desde, lancada_por, guaranis_por_real""";

    private static final RowMapper<Cotacao> MAPEADOR = RepositorioDeCotacoes::mapear;

    private final JdbcTemplate jdbc;

    public RepositorioDeCotacoes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava o dia do Banco Central se ainda não existir (vale desde 00:00 do dia em Brasília). Dia já gravado nunca é
     * sobrescrito (ADR-0006, item 3): devolve {@code false} e não muda nada.
     */
    public boolean gravar(LocalDate dia, TaxasDoDia taxas, Instant buscadaEm, String fonte) {
        int linhas = jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda,
                    buscada_em, fonte, vigente_desde)
                VALUES (uuidv7(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (data_cotacao) WHERE origem = 'BANCO_CENTRAL' DO NOTHING""",
                dia,
                taxas.usdCompra(), taxas.usdVenda(), taxas.usdParidadeCompra(), taxas.usdParidadeVenda(),
                taxas.pygCompra(), taxas.pygVenda(), taxas.pygParidadeCompra(), taxas.pygParidadeVenda(),
                buscadaEm.atOffset(ZoneOffset.UTC), fonte,
                dia.atStartOfDay(CotacaoVigente.BRASILIA).toInstant().atOffset(ZoneOffset.UTC));
        return linhas == 1;
    }

    /**
     * Grava a cotação que a dona digitou (ADR-0006, item 15): uma linha nova que vale desde o instante dado, com o
     * {@code id} que o chamador escolheu.
     */
    public Cotacao gravarDaDona(UUID id, TaxasDoDia taxas, BigDecimal guaranisPorReal, UUID usuarioId, Instant agora) {
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda,
                    buscada_em, fonte, origem, vigente_desde, lancada_por, guaranis_por_real)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Digitada pela dona', 'DONA', ?, ?, ?)""",
                id, CotacaoVigente.diaEmBrasilia(agora),
                taxas.usdCompra(), taxas.usdVenda(), taxas.usdParidadeCompra(), taxas.usdParidadeVenda(),
                taxas.pygCompra(), taxas.pygVenda(), taxas.pygParidadeCompra(), taxas.pygParidadeVenda(),
                agora.atOffset(ZoneOffset.UTC), agora.atOffset(ZoneOffset.UTC), usuarioId, guaranisPorReal);
        return porId(id).orElseThrow();
    }

    /** Como {@link #gravarDaDona(UUID, TaxasDoDia, BigDecimal, UUID, Instant)}, com o id (UUID v7) gerado agora. */
    public Cotacao gravarDaDona(TaxasDoDia taxas, BigDecimal guaranisPorReal, UUID usuarioId, Instant agora) {
        return gravarDaDona(jdbc.queryForObject("SELECT uuidv7()", UUID.class), taxas, guaranisPorReal, usuarioId, agora);
    }

    public Optional<Cotacao> porId(UUID id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM corona.cotacoes WHERE id = ?", MAPEADOR, id).stream().findFirst();
    }

    /** Os dias do intervalo (inclusive) que já têm cotação do Banco Central (a da dona não conta). */
    public Set<LocalDate> diasComCotacao(LocalDate de, LocalDate ate) {
        List<LocalDate> dias = jdbc.query(
                "SELECT data_cotacao FROM corona.cotacoes WHERE origem = 'BANCO_CENTRAL' AND data_cotacao BETWEEN ? AND ?",
                (resultado, i) -> resultado.getObject(1, LocalDate.class), de, ate);
        return new HashSet<>(dias);
    }

    /** A cotação de maior {@code vigente_desde} menor ou igual ao instante; empate: maior id (ADR-0006, itens 7 e 15). */
    public Optional<Cotacao> vigenteEm(Instant momento) {
        return jdbc.query("SELECT " + COLUNAS + " FROM corona.cotacoes c WHERE c.vigente_desde <= ? ORDER BY "
                        + CotacaoVigente.SQL_ORDEM_DA_VIGENTE + " LIMIT 1",
                MAPEADOR, momento.atOffset(ZoneOffset.UTC)).stream().findFirst();
    }

    /** Todas as cotações do dia (BCB e da dona), na ordem em que passaram a valer. */
    public List<Cotacao> doDia(LocalDate dia) {
        return jdbc.query("SELECT " + COLUNAS + " FROM corona.cotacoes WHERE data_cotacao = ? ORDER BY vigente_desde, id",
                MAPEADOR, dia);
    }

    private static Cotacao mapear(ResultSet r, int linha) throws SQLException {
        TaxasDoDia taxas = new TaxasDoDia(
                r.getBigDecimal("usd_compra"), r.getBigDecimal("usd_venda"),
                r.getBigDecimal("usd_paridade_compra"), r.getBigDecimal("usd_paridade_venda"),
                r.getBigDecimal("pyg_compra"), r.getBigDecimal("pyg_venda"),
                r.getBigDecimal("pyg_paridade_compra"), r.getBigDecimal("pyg_paridade_venda"));
        return new Cotacao(
                r.getObject("id", UUID.class),
                r.getObject("data_cotacao", LocalDate.class),
                taxas,
                r.getObject("buscada_em", OffsetDateTime.class).toInstant(),
                r.getString("fonte"),
                r.getString("origem"),
                r.getObject("vigente_desde", OffsetDateTime.class).toInstant(),
                r.getObject("lancada_por", UUID.class),
                r.getBigDecimal("guaranis_por_real"));
    }
}
