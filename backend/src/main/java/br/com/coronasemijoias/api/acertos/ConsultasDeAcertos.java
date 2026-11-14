package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.remessas.SqlDaMaleta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura dos acertos pela dona e pelo vendedor (tarefa 7.4; ADR-0012, itens 14 e 16). O vendedor só vê os dele (404 para o
 * de outro, sem dizer de quem é) e nunca recebe custo nem dado da dona. Nesta tarefa não há esperado: a ficha mostra a
 * contagem e o aviso de vendas que chegaram depois do início (a tela da 7.9 avisa "Chegou venda nova: confira a contagem").
 */
@Component
public class ConsultasDeAcertos {

    private static final int LIMITE = 500;
    private static final Set<String> SITUACOES = Set.of("EM_ACERTO", "ACERTADO", "DESISTIDO");

    /** Uma maleta ligada ao acerto, com o status que ela tinha antes. */
    public record Maleta(UUID id, String numero, String tipo, String status, String statusAntes) {
    }

    /** A peça como a contagem mostra: nome, código da loja e foto (a falta de foto nunca trava). */
    public record Produto(UUID id, String nome, String codigo, String fotoHash) {
    }

    /** A soma das leituras de uma peça, em um estado, no acerto (nunca negativa). */
    public record Contagem(Produto produto, String estadoPeca, int quantidade) {
    }

    /** Uma leitura como o vendedor a vê (sem o nome de quem leu). */
    public record Leitura(UUID id, UUID produtoId, int quantidade, String estadoPeca, Instant lidaEm, boolean peloVendedor,
            UUID dispositivoId) {
    }

    /** Uma leitura como a dona a vê: com quem leu. */
    public record LeituraDaDona(UUID id, UUID produtoId, int quantidade, String estadoPeca, Instant lidaEm, boolean peloVendedor,
            UUID dispositivoId, String lidaPorNome) {
    }

    /** A linha da lista de acertos da dona. */
    public record Resumo(UUID id, UUID vendedorId, String vendedorNome, UUID semanaId, int semanaNumero, String status,
            boolean pedidoPeloVendedor, Instant iniciadoEm, Instant desistidoEm, int maletas) {
    }

    /** A linha da lista de acertos do vendedor. */
    public record ResumoDoVendedor(UUID id, UUID semanaId, int semanaNumero, String status, boolean pedidoPeloVendedor,
            Instant iniciadoEm, Instant desistidoEm, int maletas) {
    }

    /** O acerto como o vendedor vê. */
    public record FichaDoVendedor(UUID id, UUID vendedorId, UUID semanaId, int semanaNumero, String status,
            boolean pedidoPeloVendedor, Instant iniciadoEm, Instant desistidoEm, String motivoDesistencia, List<Maleta> maletas,
            List<Contagem> contagem, List<Leitura> leituras) {
    }

    /**
     * O acerto como a dona vê. {@code vendasQueChegaramDepoisDoInicio}: vendas feitas sem internet antes do início que o servidor
     * recebeu depois (a contagem já bate; a tela avisa). {@code enviosAguardando}: o último número que o computador do vendedor
     * informou (vazio se nunca informou).
     */
    public record FichaDaDona(UUID id, UUID vendedorId, String vendedorNome, UUID semanaId, int semanaNumero, String status,
            boolean pedidoPeloVendedor, Instant iniciadoEm, Instant desistidoEm, String motivoDesistencia, List<Maleta> maletas,
            List<Contagem> contagem, List<LeituraDaDona> leituras, int vendasQueChegaramDepoisDoInicio, Integer enviosAguardando) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeAcertos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- dona ---

    public List<Resumo> daDona(UUID vendedorId, UUID semanaId, String situacao) {
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder("a.deleted_at IS NULL");
        if (vendedorId != null) {
            onde.append(" AND a.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (semanaId != null) {
            onde.append(" AND a.semana_id = ?");
            args.add(semanaId);
        }
        if (situacao != null && !situacao.isBlank()) {
            if (!SITUACOES.contains(situacao)) {
                throw Validacoes.recusar("situacao", "Escolha uma situação da lista.");
            }
            onde.append(" AND a.status = ?");
            args.add(situacao);
        }
        return jdbc.query("""
                SELECT a.id, a.vendedor_id, v.nome AS vendedor, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor,
                       a.iniciado_em, a.desistido_em,
                       (SELECT count(*) FROM corona.remessas r WHERE r.acerto_id = a.id) AS maletas
                  FROM corona.acertos a JOIN corona.vendedores v ON v.id = a.vendedor_id
                  JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE\s""" + onde + " ORDER BY a.iniciado_em DESC, a.id DESC LIMIT " + LIMITE,
                (rs, n) -> new Resumo(rs.getObject("id", UUID.class), rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"),
                        rs.getObject("semana_id", UUID.class), rs.getInt("semana"), rs.getString("status"),
                        rs.getBoolean("pedido_pelo_vendedor"), instante(rs.getTimestamp("iniciado_em")),
                        instante(rs.getTimestamp("desistido_em")), rs.getInt("maletas")), args.toArray());
    }

    public FichaDaDona fichaDaDona(UUID id) {
        return jdbc.query("""
                SELECT a.id, a.vendedor_id, v.nome AS vendedor, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor,
                       a.iniciado_em, a.desistido_em, a.motivo_desistencia
                  FROM corona.acertos a JOIN corona.vendedores v ON v.id = a.vendedor_id
                  JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE a.id = ? AND a.deleted_at IS NULL""", rs -> {
                    if (!rs.next()) {
                        throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
                    }
                    UUID vendedor = rs.getObject("vendedor_id", UUID.class);
                    return new FichaDaDona(id, vendedor, rs.getString("vendedor"), rs.getObject("semana_id", UUID.class),
                            rs.getInt("semana"), rs.getString("status"), rs.getBoolean("pedido_pelo_vendedor"),
                            instante(rs.getTimestamp("iniciado_em")), instante(rs.getTimestamp("desistido_em")),
                            rs.getString("motivo_desistencia"), maletas(id), contagem(id), leiturasDaDona(id),
                            vendasDepoisDoInicio(id), enviosAguardando(vendedor));
                }, id);
    }

    // --- vendedor ---

    public List<ResumoDoVendedor> doVendedor(UUID vendedorId) {
        return jdbc.query("""
                SELECT a.id, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor, a.iniciado_em, a.desistido_em,
                       (SELECT count(*) FROM corona.remessas r WHERE r.acerto_id = a.id) AS maletas
                  FROM corona.acertos a JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE a.vendedor_id = ? AND a.deleted_at IS NULL ORDER BY a.iniciado_em DESC, a.id DESC LIMIT\s""" + LIMITE,
                (rs, n) -> new ResumoDoVendedor(rs.getObject("id", UUID.class), rs.getObject("semana_id", UUID.class),
                        rs.getInt("semana"), rs.getString("status"), rs.getBoolean("pedido_pelo_vendedor"),
                        instante(rs.getTimestamp("iniciado_em")), instante(rs.getTimestamp("desistido_em")), rs.getInt("maletas")),
                vendedorId);
    }

    /** O acerto do vendedor; 404 se não existe ou é de outro (sem dizer de quem é). */
    public FichaDoVendedor fichaDoVendedor(UUID id, UUID vendedorId) {
        return jdbc.query("""
                SELECT a.id, a.vendedor_id, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor, a.iniciado_em,
                       a.desistido_em, a.motivo_desistencia
                  FROM corona.acertos a JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE a.id = ? AND a.vendedor_id = ? AND a.deleted_at IS NULL""", rs -> {
                    if (!rs.next()) {
                        throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
                    }
                    return new FichaDoVendedor(id, vendedorId, rs.getObject("semana_id", UUID.class), rs.getInt("semana"),
                            rs.getString("status"), rs.getBoolean("pedido_pelo_vendedor"), instante(rs.getTimestamp("iniciado_em")),
                            instante(rs.getTimestamp("desistido_em")), rs.getString("motivo_desistencia"), maletas(id),
                            contagem(id), leiturasDoVendedor(id));
                }, id, vendedorId);
    }

    // --- pedaços ---

    private List<Maleta> maletas(UUID acerto) {
        return jdbc.query("""
                SELECT r.id, """ + SqlDaMaleta.NUMERO + " AS numero, " + """
                       r.tipo, r.status, r.status_antes_do_acerto
                  FROM corona.remessas r JOIN corona.semanas s ON s.id = r.semana_id
                  JOIN corona.vendedores v ON v.id = r.vendedor_id
                 WHERE r.acerto_id = ? ORDER BY r.enviada_em, r.id""",
                (rs, n) -> new Maleta(rs.getObject("id", UUID.class), rs.getString("numero"), rs.getString("tipo"),
                        rs.getString("status"), rs.getString("status_antes_do_acerto")), acerto);
    }

    private List<Contagem> contagem(UUID acerto) {
        return jdbc.query("""
                SELECT l.produto_id, l.estado_peca, sum(l.quantidade) AS quantidade, p.nome, p.foto_principal_hash,
                       (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.ativo
                           AND c.deleted_at IS NULL ORDER BY c.principal DESC, c.codigo LIMIT 1) AS codigo
                  FROM corona.leituras_acerto l JOIN corona.produtos p ON p.id = l.produto_id
                 WHERE l.acerto_id = ?
                 GROUP BY l.produto_id, l.estado_peca, p.id, p.nome, p.foto_principal_hash
                HAVING sum(l.quantidade) > 0 ORDER BY p.nome, l.produto_id, l.estado_peca""",
                (rs, n) -> new Contagem(new Produto(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                        rs.getString("codigo"), rs.getString("foto_principal_hash")), rs.getString("estado_peca"),
                        rs.getInt("quantidade")), acerto);
    }

    private List<Leitura> leiturasDoVendedor(UUID acerto) {
        return jdbc.query("""
                SELECT id, produto_id, quantidade, estado_peca, lida_em, pelo_vendedor, dispositivo_id
                  FROM corona.leituras_acerto WHERE acerto_id = ? ORDER BY lida_em, id""",
                (rs, n) -> new Leitura(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class), rs.getInt("quantidade"),
                        rs.getString("estado_peca"), instante(rs.getTimestamp("lida_em")), rs.getBoolean("pelo_vendedor"),
                        rs.getObject("dispositivo_id", UUID.class)), acerto);
    }

    private List<LeituraDaDona> leiturasDaDona(UUID acerto) {
        return jdbc.query("""
                SELECT l.id, l.produto_id, l.quantidade, l.estado_peca, l.lida_em, l.pelo_vendedor, l.dispositivo_id, u.nome
                  FROM corona.leituras_acerto l JOIN corona.usuarios u ON u.id = l.lida_por
                 WHERE l.acerto_id = ? ORDER BY l.lida_em, l.id""",
                (rs, n) -> new LeituraDaDona(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class),
                        rs.getInt("quantidade"), rs.getString("estado_peca"), instante(rs.getTimestamp("lida_em")),
                        rs.getBoolean("pelo_vendedor"), rs.getObject("dispositivo_id", UUID.class), rs.getString("nome")), acerto);
    }

    private int vendasDepoisDoInicio(UUID acerto) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM corona.vendas v JOIN corona.remessas r ON r.id = v.remessa_id
                  JOIN corona.acertos a ON a.id = r.acerto_id
                 WHERE a.id = ? AND v.created_at >= a.iniciado_em""", Integer.class, acerto);
    }

    private Integer enviosAguardando(UUID vendedorId) {
        List<Integer> achado = jdbc.query("""
                SELECT d.aguardando_envio FROM corona.dispositivos d JOIN corona.usuarios u ON u.id = d.usuario_id
                 WHERE u.vendedor_id = ? AND d.situacao = 'ATIVO' AND d.aguardando_envio IS NOT NULL""",
                (rs, n) -> rs.getInt(1), vendedorId);
        return achado.isEmpty() ? null : achado.get(0);
    }

    private static Instant instante(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
