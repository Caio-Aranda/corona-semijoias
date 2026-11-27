package br.com.coronasemijoias.api.remessas;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

/**
 * Leituras das remessas (só da dona; nunca grava e nunca devolve custo). No rascunho o preço mostrado é o de venda de
 * hoje; depois do envio, o preço congelado. Os totais são sempre calculados aqui, pelos itens.
 */
@Component
public class ConsultasDeRemessas {

    public static final int TAMANHO_PADRAO = 50;
    public static final int TAMANHO_MAXIMO = 200;
    private static final Set<String> STATUS = Set.of("RASCUNHO", "ENVIADA", "CONFERIDA", "EM_ACERTO", "ACERTADA", "CANCELADA", "FECHADA");

    public record Produto(UUID id, String nome, String referencia, String codigo, String fotoPrincipalHash) {
    }

    public record Item(UUID id, Produto produto, int quantidade, BigDecimal precoUnitario, BigDecimal total,
            boolean precoCongelado) {
    }

    public record Resumo(UUID id, String codigo, int version, UUID vendedorId, String vendedorNome, UUID semanaId, int semanaNumero,
            UUID pracaId, String pracaNome, String tipo, String status, int totalPecas, BigDecimal totalValor,
            Instant criadaEm, Instant enviadaEm) {
    }

    public record Detalhe(Resumo resumo, List<Item> itens, List<String> avisos) {
    }

    public record Filtro(UUID vendedorId, UUID semanaId, String status) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeRemessas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Detalhe ver(UUID id) {
        List<Resumo> achadas = jdbc.query(RESUMO + " WHERE r.id = ? AND r.deleted_at IS NULL", (rs, n) -> resumo(rs), id);
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        Resumo r = achadas.get(0);
        List<String> semEstoque = new ArrayList<>();
        List<Item> itens = jdbc.query("""
                SELECT i.id, i.qtd_enviada, i.preco_remessa, p.id AS produto_id, p.nome, p.referencia, p.preco_venda,
                       p.foto_principal_hash, p.estoque_atual, (SELECT cd.codigo FROM corona.produto_codigos cd
                         WHERE cd.produto_id = p.id AND cd.principal AND cd.deleted_at IS NULL LIMIT 1) AS codigo
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, i.id""", (rs, n) -> {
                    BigDecimal congelado = rs.getBigDecimal("preco_remessa");
                    BigDecimal preco = congelado != null ? congelado : rs.getBigDecimal("preco_venda");
                    int qtd = rs.getInt("qtd_enviada");
                    int estoque = rs.getInt("estoque_atual");
                    if (qtd > estoque) {
                        semEstoque.add("A maleta tem " + qtd + " peça(s) de \"" + rs.getString("nome") + "\", mas só há "
                                + Math.max(estoque, 0) + " no estoque. Diminua a quantidade ou receba a compra antes de enviar.");
                    }
                    return new Item(rs.getObject("id", UUID.class), new Produto(rs.getObject("produto_id", UUID.class),
                            rs.getString("nome"), rs.getString("referencia"), rs.getString("codigo"),
                            rs.getString("foto_principal_hash")), qtd, preco, Dinheiro.precoDaLinha(preco, qtd), congelado != null);
                }, id);
        List<String> avisos = new ArrayList<>();
        if ("RASCUNHO".equals(r.status())) {
            // Restrição que não impede: só aviso.
            jdbc.query("""
                    SELECT rs.descricao FROM corona.vendedores v JOIN corona.restricoes rs ON rs.id = v.restricao_id
                     WHERE v.id = ? AND rs.deleted_at IS NULL AND NOT rs.bloqueia_remessa""",
                    x -> {
                        avisos.add("Atenção: este representante tem a restrição \"" + x.getString(1) + "\".");
                    }, r.vendedorId());
            // Peça acima do estoque da loja: o envio vai recusar; a dona fica sabendo já na montagem (E2, 27/11/2026).
            avisos.addAll(semEstoque);
        }
        return new Detalhe(r, itens, avisos);
    }

    @Transactional(readOnly = true)
    public Page<Resumo> listar(Filtro f, Integer pagina, Integer tamanho) {
        if (f.status() != null && !STATUS.contains(f.status())) {
            throw Validacoes.recusar("status", "Escolha uma das opções da lista.");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder(" WHERE r.deleted_at IS NULL");
        if (f.vendedorId() != null) {
            onde.append(" AND r.vendedor_id = ?");
            args.add(f.vendedorId());
        }
        if (f.semanaId() != null) {
            onde.append(" AND r.semana_id = ?");
            args.add(f.semanaId());
        }
        if (f.status() != null) {
            onde.append(" AND r.status = ?");
            args.add(f.status());
        }
        int t = tamanho == null ? TAMANHO_PADRAO : Math.max(1, Math.min(tamanho, TAMANHO_MAXIMO));
        PageRequest pedida = PageRequest.of(pagina == null ? 0 : Math.max(0, pagina), t);
        Long total = jdbc.queryForObject("SELECT count(*) FROM corona.remessas r" + onde, Long.class, args.toArray());
        List<Object> comPagina = new ArrayList<>(args);
        comPagina.add(pedida.getPageSize());
        comPagina.add(pedida.getOffset());
        List<Resumo> itens = jdbc.query(RESUMO + onde + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> resumo(rs), comPagina.toArray());
        return new PageImpl<>(itens, pedida, total == null ? 0 : total);
    }

    private static final String RESUMO = """
            SELECT r.id, r.codigo, r.version, r.vendedor_id, v.nome AS vendedor_nome, r.semana_id, s.numero AS semana_numero,
                   r.praca_id, pr.nome AS praca_nome, r.tipo, r.status, r.created_at, r.enviada_em,
                   coalesce((SELECT sum(i.qtd_enviada) FROM corona.itens_remessa i
                              WHERE i.remessa_id = r.id AND i.deleted_at IS NULL), 0) AS pecas,
                   coalesce((SELECT sum(i.qtd_enviada * coalesce(i.preco_remessa, p.preco_venda))
                               FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                              WHERE i.remessa_id = r.id AND i.deleted_at IS NULL), 0) AS valor
              FROM corona.remessas r
              JOIN corona.vendedores v ON v.id = r.vendedor_id
              JOIN corona.semanas s ON s.id = r.semana_id
              LEFT JOIN corona.pracas pr ON pr.id = r.praca_id""";

    private static Resumo resumo(ResultSet rs) throws SQLException {
        return new Resumo(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getInt("version"), rs.getObject("vendedor_id", UUID.class),
                rs.getString("vendedor_nome"), rs.getObject("semana_id", UUID.class), rs.getInt("semana_numero"),
                rs.getObject("praca_id", UUID.class), rs.getString("praca_nome"), rs.getString("tipo"), rs.getString("status"),
                rs.getInt("pecas"), Dinheiro.arredondar(rs.getBigDecimal("valor")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("enviada_em")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
