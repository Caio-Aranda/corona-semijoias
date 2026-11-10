package br.com.coronasemijoias.api.produtos;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Preço de custo (tabela {@code produtos_custos}, só do servidor). É lido e gravado só pelas rotas
 * da dona, na mesma transação do produto. Nenhuma rota do vendedor chega aqui.
 */
@Component
public class CustosDosProdutos {

    private final JdbcTemplate jdbc;

    CustosDosProdutos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void gravar(UUID produtoId, BigDecimal custo) {
        jdbc.update("""
                INSERT INTO corona.produtos_custos (produto_id, preco_custo) VALUES (?, ?)
                ON CONFLICT (produto_id) DO UPDATE SET preco_custo = EXCLUDED.preco_custo""", produtoId, custo);
    }

    public Optional<BigDecimal> obter(UUID produtoId) {
        return jdbc.query("SELECT preco_custo FROM corona.produtos_custos WHERE produto_id = ?",
                (rs, n) -> rs.getBigDecimal(1), produtoId).stream().findFirst();
    }

    Map<UUID, BigDecimal> dosProdutos(Collection<UUID> ids) {
        Map<UUID, BigDecimal> custos = new HashMap<>();
        if (ids.isEmpty()) {
            return custos;
        }
        String marcas = String.join(",", ids.stream().map(i -> "?").toList());
        jdbc.query("SELECT produto_id, preco_custo FROM corona.produtos_custos WHERE produto_id IN (" + marcas + ")",
                rs -> {
                    custos.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                }, ids.toArray());
        return custos;
    }
}
