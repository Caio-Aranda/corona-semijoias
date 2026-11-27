package br.com.coronasemijoias.api.remessas;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Quantas unidades de cada peça estão com os vendedores (item 13, 11/11/2026): só para as telas da DONA, para
 * distinguir "estoque da loja 0 e a peça está fora" de "acabou". É o saldo das maletas que estão com o vendedor
 * (quantidade enviada mais os movimentos) mais as peças que ele deixou com as clientes em notas sem acerto, também de
 * maleta já fechada (C6, 27/11/2026), por vendedor.
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
        // Com o representante = o saldo das maletas com ele mais as peças que ele deixou com as clientes (C6, 27/11/2026).
        jdbc.query("SELECT i.produto_id, v.nome, sum(" + ConsultasDaMaleta.QTD_COM_ELE + ") AS quantidade" + """

                  FROM corona.remessas r
                  JOIN corona.vendedores v ON v.id = r.vendedor_id
                  JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL AND i.produto_id = ANY (?::uuid[])
                """ + ConsultasDaMaleta.JUNTA_SALDO_E_CLIENTES
                + " WHERE r.deleted_at IS NULL AND r.status IN " + ConsultasDaMaleta.COM_ELE_OU_FECHADA + """

                 GROUP BY i.produto_id, v.id, v.nome
                """ + " HAVING sum(" + ConsultasDaMaleta.QTD_COM_ELE + ") > 0" + """

                 ORDER BY i.produto_id, quantidade DESC, lower(v.nome)""", rs -> {
                    porProduto.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                            .add(new DoVendedor(rs.getString(2), rs.getInt(3)));
                }, ids);
        porProduto.forEach((id, lista) -> saida.put(id,
                new Situacao(lista.stream().mapToInt(DoVendedor::quantidade).sum(), List.copyOf(lista))));
        return saida;
    }

    public Situacao de(UUID produtoId) {
        return de(List.of(produtoId)).getOrDefault(produtoId, Situacao.NENHUMA);
    }
}
