package br.com.coronasemijoias.api.compras;

import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Leituras das compras e do histórico de custo (só da dona; nunca grava). */
@Component
public class ConsultasDeCompras {

    public static final int TAMANHO_PADRAO = 50;
    public static final int TAMANHO_MAXIMO = 200;
    private static final Set<String> SITUACOES = Set.of("REGISTRADA", "ESTORNADA");

    public record Produto(UUID id, String nome, String referencia, String codigo, String fotoPrincipalHash) {
    }

    public record Item(UUID id, Produto produto, int quantidade, BigDecimal custoUnitario, BigDecimal total) {
    }

    public record Resumo(UUID id, UUID fornecedorId, String fornecedorNome, LocalDate dataCompra, String numero,
            BigDecimal total, String situacao, int itens, int pecas, Instant registradaEm) {
    }

    public record Detalhe(Resumo resumo, String observacoes, UUID usuarioId, String usuarioNome, String estornoMotivo,
            Instant estornadaEm, List<Item> itens) {
    }

    public record Filtro(LocalDate de, LocalDate ate, UUID fornecedorId, UUID produtoId, String situacao) {
    }

    public record LinhaDeCusto(UUID compraId, LocalDate dataCompra, String fornecedorNome, String situacaoDaCompra,
            BigDecimal custo, BigDecimal custoAnterior, int quantidade) {
    }

    public record HistoricoDeCusto(Produto produto, BigDecimal custoAtual, Page<LinhaDeCusto> linhas) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeCompras(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Detalhe ver(UUID id) {
        List<Detalhe> achadas = jdbc.query(RESUMO + " WHERE c.id = ?", (rs, n) -> new Detalhe(resumo(rs),
                rs.getString("observacoes"), rs.getObject("usuario_id", UUID.class), rs.getString("usuario_nome"),
                rs.getString("estorno_motivo"), instant(rs.getTimestamp("estornada_em")), List.of()), id);
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.COMPRA_NAO_ENCONTRADA);
        }
        List<Item> itens = jdbc.query("""
                SELECT i.id, i.quantidade, i.custo_unitario, i.total, p.id AS produto_id, p.nome, p.referencia,
                       p.foto_principal_hash, (SELECT cd.codigo FROM corona.produto_codigos cd
                         WHERE cd.produto_id = p.id AND cd.principal AND cd.deleted_at IS NULL LIMIT 1) AS codigo
                  FROM corona.itens_compra i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.compra_id = ? ORDER BY p.nome, i.id""",
                (rs, n) -> new Item(rs.getObject("id", UUID.class), produto(rs), rs.getInt("quantidade"),
                        rs.getBigDecimal("custo_unitario"), rs.getBigDecimal("total")), id);
        Detalhe d = achadas.get(0);
        return new Detalhe(d.resumo(), d.observacoes(), d.usuarioId(), d.usuarioNome(), d.estornoMotivo(), d.estornadaEm(), itens);
    }

    @Transactional(readOnly = true)
    public Page<Resumo> listar(Filtro f, Integer pagina, Integer tamanho) {
        if (f.situacao() != null && !SITUACOES.contains(f.situacao())) {
            throw Validacoes.recusar("situacao", "Escolha uma das opções da lista.");
        }
        if (f.de() != null && f.ate() != null && f.de().isAfter(f.ate())) {
            throw Validacoes.recusar("de", "A data inicial não pode ser depois da data final.");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder(" WHERE true");
        if (f.de() != null) {
            onde.append(" AND c.data_compra >= ?");
            args.add(f.de());
        }
        if (f.ate() != null) {
            onde.append(" AND c.data_compra <= ?");
            args.add(f.ate());
        }
        if (f.fornecedorId() != null) {
            onde.append(" AND c.fornecedor_id = ?");
            args.add(f.fornecedorId());
        }
        if (f.produtoId() != null) {
            onde.append(" AND EXISTS (SELECT 1 FROM corona.itens_compra x WHERE x.compra_id = c.id AND x.produto_id = ?)");
            args.add(f.produtoId());
        }
        if (f.situacao() != null) {
            onde.append(" AND c.situacao = ?");
            args.add(f.situacao());
        }
        PageRequest pedida = pagina(pagina, tamanho);
        Long total = jdbc.queryForObject("SELECT count(*) FROM corona.compras c" + onde, Long.class, args.toArray());
        List<Object> comPagina = new ArrayList<>(args);
        comPagina.add(pedida.getPageSize());
        comPagina.add(pedida.getOffset());
        List<Resumo> itens = jdbc.query(RESUMO + onde + " ORDER BY c.data_compra DESC, c.created_at DESC, c.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> resumo(rs), comPagina.toArray());
        return new PageImpl<>(itens, pedida, total == null ? 0 : total);
    }

    /** Custo atual e o histórico dos custos do produto, do mais novo para o mais antigo. */
    @Transactional(readOnly = true)
    public HistoricoDeCusto custos(UUID produtoId, Integer pagina, Integer tamanho) {
        List<Produto> achados = jdbc.query("""
                SELECT p.id AS produto_id, p.nome, p.referencia, p.foto_principal_hash,
                       (SELECT cd.codigo FROM corona.produto_codigos cd
                         WHERE cd.produto_id = p.id AND cd.principal AND cd.deleted_at IS NULL LIMIT 1) AS codigo
                  FROM corona.produtos p WHERE p.id = ? AND p.deleted_at IS NULL""", (rs, n) -> produto(rs), produtoId);
        if (achados.isEmpty()) {
            throw ServicoDeCadastro.naoEncontrado();
        }
        List<BigDecimal> atual = jdbc.query("SELECT preco_custo FROM corona.produtos_custos WHERE produto_id = ?",
                (rs, n) -> rs.getBigDecimal(1), produtoId);
        PageRequest pedida = pagina(pagina, tamanho);
        Long total = jdbc.queryForObject("SELECT count(*) FROM corona.historico_custo WHERE produto_id = ?", Long.class, produtoId);
        List<LinhaDeCusto> linhas = jdbc.query("""
                SELECT h.compra_id, h.data_compra, f.razao_social AS fornecedor, c.situacao, h.custo, h.custo_anterior,
                       i.quantidade
                  FROM corona.historico_custo h
                  JOIN corona.compras c ON c.id = h.compra_id
                  JOIN corona.fornecedores f ON f.id = c.fornecedor_id
                  JOIN corona.itens_compra i ON i.compra_id = h.compra_id AND i.produto_id = h.produto_id
                 WHERE h.produto_id = ? ORDER BY h.ordem DESC LIMIT ? OFFSET ?""",
                (rs, n) -> new LinhaDeCusto(rs.getObject("compra_id", UUID.class), rs.getDate("data_compra").toLocalDate(),
                        rs.getString("fornecedor"), rs.getString("situacao"), rs.getBigDecimal("custo"),
                        rs.getBigDecimal("custo_anterior"), rs.getInt("quantidade")),
                produtoId, pedida.getPageSize(), pedida.getOffset());
        return new HistoricoDeCusto(achados.get(0), atual.isEmpty() ? null : atual.get(0),
                new PageImpl<>(linhas, pedida, total == null ? 0 : total));
    }

    private static final String RESUMO = """
            SELECT c.id, c.fornecedor_id, f.razao_social AS fornecedor_nome, c.data_compra, c.numero, c.total, c.situacao,
                   c.observacoes, c.usuario_id, u.nome AS usuario_nome, c.estorno_motivo, c.estornada_em, c.created_at,
                   (SELECT count(*) FROM corona.itens_compra x WHERE x.compra_id = c.id) AS itens,
                   (SELECT coalesce(sum(x.quantidade), 0) FROM corona.itens_compra x WHERE x.compra_id = c.id) AS pecas
              FROM corona.compras c
              JOIN corona.fornecedores f ON f.id = c.fornecedor_id
              JOIN corona.usuarios u ON u.id = c.usuario_id""";

    private static Resumo resumo(ResultSet rs) throws SQLException {
        return new Resumo(rs.getObject("id", UUID.class), rs.getObject("fornecedor_id", UUID.class),
                rs.getString("fornecedor_nome"), rs.getDate("data_compra").toLocalDate(), rs.getString("numero"),
                rs.getBigDecimal("total"), rs.getString("situacao"), rs.getInt("itens"), rs.getInt("pecas"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Produto produto(ResultSet rs) throws SQLException {
        return new Produto(rs.getObject("produto_id", UUID.class), rs.getString("nome"), rs.getString("referencia"),
                rs.getString("codigo"), rs.getString("foto_principal_hash"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static PageRequest pagina(Integer pagina, Integer tamanho) {
        int t = tamanho == null ? TAMANHO_PADRAO : Math.max(1, Math.min(tamanho, TAMANHO_MAXIMO));
        return PageRequest.of(pagina == null ? 0 : Math.max(0, pagina), t);
    }
}
