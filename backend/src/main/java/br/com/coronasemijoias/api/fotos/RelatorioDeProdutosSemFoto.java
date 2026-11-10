package br.com.coronasemijoias.api.fotos;

import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatório "produtos sem foto" (§12 e §20): as peças que ainda não têm nenhuma foto. Por padrão só as
 * ativas; filtros de categoria, fornecedor e situação, busca por nome, referência ou código, e página.
 * A tela do relatório vem na tarefa 1.12.
 */
@Service
public class RelatorioDeProdutosSemFoto {

    private static final int TAMANHO_PADRAO = 20;
    private static final int TAMANHO_MAXIMO = 100;

    public record Item(UUID id, String nome, String referencia, String codigoPrincipal, UUID categoriaId,
            UUID fornecedorId, boolean ativo) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public RelatorioDeProdutosSemFoto(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Pagina<Item> listar(String busca, UUID categoriaId, UUID fornecedorId, Boolean ativo, Integer pagina,
            Integer tamanho) {
        int p = pagina == null ? 0 : pagina;
        int t = tamanho == null ? TAMANHO_PADRAO : tamanho;
        if (p < 0) {
            throw Validacoes.recusar("pagina", "A página não pode ser negativa.");
        }
        if (t < 1 || t > TAMANHO_MAXIMO) {
            throw Validacoes.recusar("tamanho", "Escolha de 1 a " + TAMANHO_MAXIMO + " itens por página.");
        }
        String limpo = busca == null ? "" : busca.strip().replaceAll("[%_\\\\]", "").toLowerCase(Locale.ROOT);
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("texto", "%" + limpo + "%")
                .addValue("categoria", categoriaId)
                .addValue("fornecedor", fornecedorId)
                .addValue("ativo", ativo == null ? Boolean.TRUE : ativo)
                .addValue("limite", t)
                .addValue("inicio", (long) p * t);
        String filtro = """
                FROM corona.produtos p
                WHERE p.deleted_at IS NULL
                  AND p.ativo = :ativo
                  AND (CAST(:categoria AS uuid) IS NULL OR p.categoria_id = CAST(:categoria AS uuid))
                  AND (CAST(:fornecedor AS uuid) IS NULL OR p.fornecedor_id = CAST(:fornecedor AS uuid))
                  AND NOT EXISTS (SELECT 1 FROM corona.produtos_fotos f WHERE f.produto_id = p.id AND f.deleted_at IS NULL)
                  AND (lower(p.nome) LIKE :texto OR lower(coalesce(p.referencia, '')) LIKE :texto
                       OR EXISTS (SELECT 1 FROM corona.produto_codigos c
                                  WHERE c.produto_id = p.id AND c.deleted_at IS NULL AND lower(c.codigo) LIKE :texto))""";
        Long total = jdbc.queryForObject("SELECT count(*) " + filtro, parametros, Long.class);
        List<Item> itens = jdbc.query("""
                SELECT p.id, p.nome, p.referencia, p.categoria_id, p.fornecedor_id, p.ativo,
                       (SELECT c.codigo FROM corona.produto_codigos c
                        WHERE c.produto_id = p.id AND c.deleted_at IS NULL
                        ORDER BY c.principal DESC, c.created_at LIMIT 1) AS codigo_principal
                """ + filtro + " ORDER BY lower(p.nome), p.id LIMIT :limite OFFSET :inicio", parametros,
                (rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("referencia"),
                        rs.getString("codigo_principal"), rs.getObject("categoria_id", UUID.class),
                        rs.getObject("fornecedor_id", UUID.class), rs.getBoolean("ativo")));
        return new Pagina<>(itens, total == null ? 0 : total, p, t);
    }
}
