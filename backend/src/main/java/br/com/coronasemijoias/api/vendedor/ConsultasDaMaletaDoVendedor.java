package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.remessas.SqlDaMaleta;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A maleta lida pelo próprio vendedor (tarefa 4.3; ADR-0011, item 12): peças, saldo, preço de venda e foto. Nunca traz
 * custo, estoque central nem dados de outros vendedores: toda consulta filtra pelo vendedor do token.
 */
@Component
public class ConsultasDaMaletaDoVendedor {

    /**
     * A peça como o vendedor vê: nome, código da loja, foto, categoria e o código do fornecedor (COD Fábrica, quando
     * houver). Nunca custo nem estoque da loja.
     */
    public record Peca(UUID id, String nome, String codigo, String fotoHash, String categoria, String codigoFornecedor) {
    }

    public record Linha(Peca produto, int enviada, int vendida, int devolvida, int saldo, BigDecimal precoUnitario,
            BigDecimal valorEmAberto) {
    }

    public record Maleta(UUID id, String numero, int semanaNumero, String tipo, String status, Instant enviadaEm,
            UUID dispositivoId, int pecasEmAberto, BigDecimal valorEmAberto, List<Linha> itens) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDaMaletaDoVendedor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** As maletas que estão com o vendedor (enviada ou conferida), da mais nova para a mais antiga. */
    public List<Maleta> listar(UUID vendedorId) {
        return consultar("r.vendedor_id = ? AND r.status IN ('ENVIADA', 'CONFERIDA')", vendedorId);
    }

    /** Uma maleta do vendedor; 404 se não existe ou é de outro (sem dizer de quem). */
    public Maleta ver(UUID id, UUID vendedorId) {
        List<Maleta> achadas = consultar("r.vendedor_id = ? AND r.id = ? AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO')",
                vendedorId, id);
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        return achadas.get(0);
    }

    /** A peça lida pelo leitor, se estiver na maleta: o leitor nunca revela peça de fora. */
    public Linha peca(UUID maletaId, UUID vendedorId, String codigo) {
        Maleta m = ver(maletaId, vendedorId);
        String lido = codigo == null ? "" : codigo.strip();
        if (!lido.isEmpty()) {
            List<UUID> produtos = jdbc.query("""
                    SELECT produto_id FROM corona.produto_codigos
                     WHERE lower(codigo) = lower(?) AND ativo AND deleted_at IS NULL""", (rs, n) -> rs.getObject(1, UUID.class), lido);
            for (Linha l : m.itens()) {
                if (produtos.contains(l.produto().id())) {
                    return l;
                }
            }
        }
        throw ErroDaApi.comDetalhe(HttpStatus.NOT_FOUND, CodigoErro.PECA_NAO_ENCONTRADA,
                "Esta peça não consta na sua maleta.");
    }

    /** No máximo isto de peças na busca pelo nome. */
    static final int LIMITE_DA_BUSCA = 20;

    /**
     * Procura SÓ entre as peças da maleta do vendedor, pelo nome, pela referência ou por parte de um código (A etiqueta
     * não lê? Procure pelo nome). Mesma linha da leitura por código, com foto e saldo; nunca peça de fora da maleta.
     * Texto vazio devolve lista vazia (não despeja a maleta toda).
     */
    public List<Linha> buscar(UUID maletaId, UUID vendedorId, String texto) {
        Maleta m = ver(maletaId, vendedorId);
        String procurado = texto == null ? "" : texto.strip().toLowerCase(java.util.Locale.ROOT);
        if (procurado.isEmpty() || procurado.length() > 100 || m.itens().isEmpty()) {
            return List.of();
        }
        String padrao = "%" + procurado.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<UUID> achados = jdbc.query("""
                SELECT p.id FROM corona.produtos p
                 WHERE p.id = ANY (?::uuid[]) AND p.deleted_at IS NULL
                   AND (lower(p.nome) LIKE ? OR lower(coalesce(p.referencia, '')) LIKE ?
                        OR EXISTS (SELECT 1 FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.ativo
                                    AND c.deleted_at IS NULL AND lower(c.codigo) LIKE ?))""",
                (rs, n) -> rs.getObject(1, UUID.class),
                m.itens().stream().map(l -> l.produto().id().toString()).collect(java.util.stream.Collectors.joining(",", "{", "}")),
                padrao, padrao, padrao);
        return m.itens().stream().filter(l -> achados.contains(l.produto().id())).limit(LIMITE_DA_BUSCA).toList();
    }

    private List<Maleta> consultar(String onde, Object... args) {
        List<Maleta> cabecas = jdbc.query("""
                SELECT r.id, """ + SqlDaMaleta.NUMERO + " AS numero, " + """
                       s.numero AS semana, r.tipo, r.status, r.enviada_em, r.dispositivo_id
                  FROM corona.remessas r JOIN corona.semanas s ON s.id = r.semana_id
                  JOIN corona.vendedores v ON v.id = r.vendedor_id
                 WHERE r.deleted_at IS NULL AND\s""" + onde + " ORDER BY r.enviada_em DESC, r.id DESC",
                (rs, n) -> new Maleta(rs.getObject("id", UUID.class), rs.getString("numero"), rs.getInt("semana"),
                        rs.getString("tipo"), rs.getString("status"), rs.getTimestamp("enviada_em").toInstant(),
                        rs.getObject("dispositivo_id", UUID.class), 0, BigDecimal.ZERO.setScale(2), new ArrayList<>()), args);
        List<Maleta> prontas = new ArrayList<>();
        for (Maleta m : cabecas) {
            List<Linha> itens = jdbc.query("""
                    SELECT i.produto_id, p.nome, p.foto_principal_hash, i.qtd_enviada, i.preco_remessa,
                           (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.ativo
                               AND c.deleted_at IS NULL ORDER BY c.principal DESC, c.codigo LIMIT 1) AS codigo,
                           (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.ativo
                               AND c.deleted_at IS NULL AND c.tipo = 'FORNECEDOR' ORDER BY c.principal DESC, c.codigo LIMIT 1)
                               AS codigo_fornecedor,
                           (SELECT g.nome FROM corona.categorias g WHERE g.id = p.categoria_id) AS categoria,
                           coalesce((SELECT -sum(x.quantidade) FROM corona.movimentos_remessa x WHERE x.remessa_id = i.remessa_id
                               AND x.produto_id = i.produto_id AND x.tipo IN ('VENDA', 'ESTORNO_VENDA', 'DEVOLUCAO_CLIENTE')), 0) AS vendida,
                           coalesce((SELECT -sum(x.quantidade) FROM corona.movimentos_remessa x WHERE x.remessa_id = i.remessa_id
                               AND x.produto_id = i.produto_id AND x.tipo = 'DEVOLUCAO_DONA'), 0) AS devolvida,
                           i.qtd_enviada + coalesce((SELECT sum(x.quantidade) FROM corona.movimentos_remessa x
                               WHERE x.remessa_id = i.remessa_id AND x.produto_id = i.produto_id), 0) AS saldo
                      FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, p.id""", (rs, n) -> {
                        int saldo = rs.getInt("saldo");
                        BigDecimal preco = rs.getBigDecimal("preco_remessa");
                        return new Linha(new Peca(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                                rs.getString("codigo"), rs.getString("foto_principal_hash"), rs.getString("categoria"),
                                rs.getString("codigo_fornecedor")), rs.getInt("qtd_enviada"),
                                rs.getInt("vendida"), rs.getInt("devolvida"), saldo, preco,
                                preco.multiply(BigDecimal.valueOf(saldo)));
                    }, m.id());
            int pecas = itens.stream().mapToInt(Linha::saldo).sum();
            BigDecimal valor = itens.stream().map(Linha::valorEmAberto).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            prontas.add(new Maleta(m.id(), m.numero(), m.semanaNumero(), m.tipo(), m.status(), m.enviadaEm(), m.dispositivoId(),
                    pecas, valor, itens));
        }
        return prontas;
    }
}
