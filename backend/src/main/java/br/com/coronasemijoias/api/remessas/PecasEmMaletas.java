package br.com.coronasemijoias.api.remessas;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Quantas unidades de cada peça estão nas maletas dos vendedores (item 13, 11/11/2026): só para as telas da DONA, para
 * distinguir "estoque da loja 0 e a peça está em maleta" de "acabou". É o saldo das maletas que estão com o vendedor
 * (a mesma fórmula da conciliação "estoque da loja + maletas": quantidade enviada mais os movimentos), por vendedor.
 * Uma consulta só para todas as peças pedidas (nunca uma por linha). O vendedor nunca recebe isto.
 */
@Service
public class PecasEmMaletas {

    /** Quantas unidades de uma peça um vendedor tem em mãos. */
    public record DoVendedor(String nome, int quantidade) {
    }

    /** O total da peça nas maletas e quem está com ela (a maior quantidade primeiro). */
    public record Situacao(int total, List<DoVendedor> vendedores) {
        public static final Situacao NENHUMA = new Situacao(0, List.of());
    }

    private final JdbcTemplate jdbc;

    PecasEmMaletas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Só as peças que estão em alguma maleta aparecem no mapa; as outras valem {@link Situacao#NENHUMA}. */
    public Map<UUID, Situacao> de(Collection<UUID> produtoIds) {
        Map<UUID, Situacao> saida = new HashMap<>();
        if (produtoIds == null || produtoIds.isEmpty()) {
            return saida;
        }
        // Um texto "{id1,id2}" convertido em uuid[] no próprio SQL (sem depender de arrays do driver).
        String ids = produtoIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        Map<UUID, java.util.ArrayList<DoVendedor>> porProduto = new HashMap<>();
        jdbc.query("""
                SELECT i.produto_id, v.nome, sum(i.qtd_enviada + coalesce(m.soma, 0)) AS quantidade
                  FROM corona.remessas r
                  JOIN corona.vendedores v ON v.id = r.vendedor_id
                  JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL AND i.produto_id = ANY (?::uuid[])
                  LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma FROM corona.movimentos_remessa
                              WHERE produto_id = ANY (?::uuid[]) GROUP BY remessa_id, produto_id) m
                         ON m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id
                 WHERE r.deleted_at IS NULL AND r.status IN\s""" + SqlDaMaleta.COM_O_VENDEDOR + """

                 GROUP BY i.produto_id, v.id, v.nome
                HAVING sum(i.qtd_enviada + coalesce(m.soma, 0)) > 0
                 ORDER BY i.produto_id, quantidade DESC, lower(v.nome)""", rs -> {
                    porProduto.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                            .add(new DoVendedor(rs.getString(2), rs.getInt(3)));
                }, ids, ids);
        porProduto.forEach((id, lista) -> saida.put(id,
                new Situacao(lista.stream().mapToInt(DoVendedor::quantidade).sum(), List.copyOf(lista))));
        return saida;
    }

    public Situacao de(UUID produtoId) {
        return de(List.of(produtoId)).getOrDefault(produtoId, Situacao.NENHUMA);
    }
}
