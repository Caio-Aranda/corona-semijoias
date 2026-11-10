package br.com.coronasemijoias.api.estoque;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.estoque.ConsultasDeEstoque.ProdutoResumo;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/** Leituras da contagem do estoque (só da dona; nunca grava). */
@Component
public class ConsultasDeInventario {

    public static final int TAMANHO_PADRAO = 50;
    public static final int TAMANHO_MAXIMO = 200;
    private static final Set<String> SITUACOES = Set.of("EM_ANDAMENTO", "FECHADO", "CANCELADO");
    private static final Set<String> FILTROS = Set.of("TODOS", "DIVERGENTES", "NAO_LIDOS", "LIDOS");

    /** Os números da contagem. {@code faltam} e {@code sobram} são em peças, só das já lidas. */
    public record Resumo(int totalDePecas, int lidos, int naoLidos, int comDiferenca, long faltam, long sobram,
            long pecasEsperadas, long pecasContadas) {
    }

    public record Detalhe(UUID id, String escopo, UUID escopoId, String escopoNome, String situacao, Instant iniciadoEm,
            String iniciadoPorNome, Instant fechadoEm, String fechadoPorNome, Boolean zerouNaoLidos, Instant canceladoEm,
            String motivoDoCancelamento, String observacoes, int ajustes, Resumo resumo) {
    }

    public record Linha(UUID id, String escopo, UUID escopoId, String escopoNome, String situacao, Instant iniciadoEm,
            Instant fechadoEm, int totalDePecas, int lidos) {
    }

    /** {@code contado} e {@code diferenca} ficam vazios enquanto a peça não foi lida. */
    public record Item(ProdutoResumo produto, int esperado, Integer contado, Integer diferenca, int saldoAtual, boolean ajustada) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeInventario(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Detalhe ver(UUID id) {
        List<Detalhe> achados = jdbc.query("""
                SELECT v.id, v.escopo, v.escopo_id, v.situacao, v.iniciado_em, v.fechado_em, v.zerou_nao_lidos, v.cancelado_em,
                       v.motivo_cancelamento, v.observacoes, ui.nome AS iniciado_por, uf.nome AS fechado_por,
                       CASE v.escopo WHEN 'CATEGORIA' THEN (SELECT c.nome FROM corona.categorias c WHERE c.id = v.escopo_id)
                                     WHEN 'FORNECEDOR' THEN (SELECT f.razao_social FROM corona.fornecedores f WHERE f.id = v.escopo_id)
                       END AS escopo_nome,
                       (SELECT count(*) FROM corona.itens_inventario x WHERE x.inventario_id = v.id AND x.movimento_id IS NOT NULL) AS ajustes
                  FROM corona.inventarios v
                  JOIN corona.usuarios ui ON ui.id = v.iniciado_por
                  LEFT JOIN corona.usuarios uf ON uf.id = v.fechado_por
                 WHERE v.id = ?""",
                (rs, n) -> new Detalhe(rs.getObject("id", UUID.class), rs.getString("escopo"), rs.getObject("escopo_id", UUID.class),
                        rs.getString("escopo_nome"), rs.getString("situacao"), instant(rs, "iniciado_em"), rs.getString("iniciado_por"),
                        instant(rs, "fechado_em"), rs.getString("fechado_por"), (Boolean) rs.getObject("zerou_nao_lidos"),
                        instant(rs, "cancelado_em"), rs.getString("motivo_cancelamento"), rs.getString("observacoes"),
                        rs.getInt("ajustes"), null), id);
        if (achados.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CONTAGEM_NAO_ENCONTRADA);
        }
        Detalhe d = achados.get(0);
        return new Detalhe(d.id(), d.escopo(), d.escopoId(), d.escopoNome(), d.situacao(), d.iniciadoEm(), d.iniciadoPorNome(),
                d.fechadoEm(), d.fechadoPorNome(), d.zerouNaoLidos(), d.canceladoEm(), d.motivoDoCancelamento(), d.observacoes(),
                d.ajustes(), resumo(id));
    }

    private Resumo resumo(UUID id) {
        return jdbc.queryForObject("""
                SELECT count(*) AS total, count(contado) AS lidos, count(*) FILTER (WHERE contado IS NULL) AS nao_lidos,
                       count(*) FILTER (WHERE contado IS NOT NULL AND contado <> esperado) AS com_diferenca,
                       coalesce(sum(greatest(esperado - contado, 0)), 0) AS faltam,
                       coalesce(sum(greatest(contado - esperado, 0)), 0) AS sobram,
                       coalesce(sum(esperado), 0) AS esperadas, coalesce(sum(contado), 0) AS contadas
                  FROM corona.itens_inventario WHERE inventario_id = ?""",
                (rs, n) -> new Resumo(rs.getInt("total"), rs.getInt("lidos"), rs.getInt("nao_lidos"), rs.getInt("com_diferenca"),
                        rs.getLong("faltam"), rs.getLong("sobram"), rs.getLong("esperadas"), rs.getLong("contadas")), id);
    }

    @Transactional(readOnly = true)
    public Page<Linha> listar(String situacao, Integer pagina, Integer tamanho) {
        if (situacao != null && !SITUACOES.contains(situacao)) {
            throw Validacoes.recusar("situacao", "Escolha uma das opções da lista.");
        }
        List<Object> args = new ArrayList<>();
        String onde = "";
        if (situacao != null) {
            onde = " WHERE v.situacao = ?";
            args.add(situacao);
        }
        PageRequest pedida = pagina(pagina, tamanho);
        Long total = jdbc.queryForObject("SELECT count(*) FROM corona.inventarios v" + onde, Long.class, args.toArray());
        List<Object> comPagina = new ArrayList<>(args);
        comPagina.add(pedida.getPageSize());
        comPagina.add(pedida.getOffset());
        List<Linha> itens = jdbc.query("""
                SELECT v.id, v.escopo, v.escopo_id, v.situacao, v.iniciado_em, v.fechado_em,
                       CASE v.escopo WHEN 'CATEGORIA' THEN (SELECT c.nome FROM corona.categorias c WHERE c.id = v.escopo_id)
                                     WHEN 'FORNECEDOR' THEN (SELECT f.razao_social FROM corona.fornecedores f WHERE f.id = v.escopo_id)
                       END AS escopo_nome,
                       (SELECT count(*) FROM corona.itens_inventario x WHERE x.inventario_id = v.id) AS total,
                       (SELECT count(x.contado) FROM corona.itens_inventario x WHERE x.inventario_id = v.id) AS lidos
                  FROM corona.inventarios v""" + onde + " ORDER BY v.iniciado_em DESC, v.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Linha(rs.getObject("id", UUID.class), rs.getString("escopo"), rs.getObject("escopo_id", UUID.class),
                        rs.getString("escopo_nome"), rs.getString("situacao"), instant(rs, "iniciado_em"), instant(rs, "fechado_em"),
                        rs.getInt("total"), rs.getInt("lidos")),
                comPagina.toArray());
        return new PageImpl<>(itens, pedida, total == null ? 0 : total);
    }

    /** As peças da contagem: filtro TODOS, DIVERGENTES, NAO_LIDOS ou LIDOS e busca por nome, referência ou código. */
    @Transactional(readOnly = true)
    public Page<Item> itens(UUID id, String filtro, String busca, Integer pagina, Integer tamanho) {
        String f = filtro == null ? "TODOS" : filtro;
        if (!FILTROS.contains(f)) {
            throw Validacoes.recusar("filtro", "Escolha uma das opções da lista.");
        }
        Boolean existe = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.inventarios WHERE id = ?)", Boolean.class, id);
        if (!Boolean.TRUE.equals(existe)) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CONTAGEM_NAO_ENCONTRADA);
        }
        List<Object> args = new ArrayList<>();
        args.add(id);
        StringBuilder onde = new StringBuilder(" WHERE i.inventario_id = ?");
        switch (f) {
            case "DIVERGENTES" -> onde.append(" AND i.contado IS NOT NULL AND i.contado <> i.esperado");
            case "NAO_LIDOS" -> onde.append(" AND i.contado IS NULL");
            case "LIDOS" -> onde.append(" AND i.contado IS NOT NULL");
            default -> {
            }
        }
        String texto = Validacoes.limpar(busca);
        if (texto != null) {
            String padrao = "%" + texto.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            onde.append(" ").append("""
                     AND (lower(p.nome) LIKE ? OR lower(coalesce(p.referencia, '')) LIKE ?
                          OR EXISTS (SELECT 1 FROM corona.produto_codigos cx
                                      WHERE cx.produto_id = p.id AND cx.deleted_at IS NULL AND lower(cx.codigo) LIKE ?))""");
            args.add(padrao);
            args.add(padrao);
            args.add(padrao);
        }
        String de = " FROM corona.itens_inventario i JOIN corona.produtos p ON p.id = i.produto_id";
        PageRequest pedida = pagina(pagina, tamanho);
        Long total = jdbc.queryForObject("SELECT count(*)" + de + onde, Long.class, args.toArray());
        List<Object> comPagina = new ArrayList<>(args);
        comPagina.add(pedida.getPageSize());
        comPagina.add(pedida.getOffset());
        List<Item> itens = jdbc.query(COLUNAS + de + onde + " ORDER BY lower(p.nome), p.id LIMIT ? OFFSET ?",
                (rs, n) -> item(rs), comPagina.toArray());
        return new PageImpl<>(itens, pedida, total == null ? 0 : total);
    }

    /** Uma peça da contagem (para a resposta de cada leitura). */
    @Transactional(readOnly = true)
    public Item item(UUID inventarioId, UUID produtoId) {
        return jdbc.query(COLUNAS + " FROM corona.itens_inventario i JOIN corona.produtos p ON p.id = i.produto_id"
                + " WHERE i.inventario_id = ? AND i.produto_id = ?", (rs, n) -> item(rs), inventarioId, produtoId)
                .stream().findFirst().orElseThrow(() -> new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CONTAGEM_NAO_ENCONTRADA));
    }

    private static final String COLUNAS = """
            SELECT p.id AS produto_id, p.nome AS produto_nome, p.referencia, p.estoque_minimo, p.ativo, p.foto_principal_hash,
                   p.estoque_atual, i.esperado, i.contado, i.movimento_id,
                   (SELECT c.codigo FROM corona.produto_codigos c
                     WHERE c.produto_id = p.id AND c.principal AND c.deleted_at IS NULL LIMIT 1) AS codigo""";

    private static Item item(ResultSet rs) throws SQLException {
        ProdutoResumo produto = new ProdutoResumo(rs.getObject("produto_id", UUID.class), rs.getString("produto_nome"),
                rs.getString("referencia"), rs.getString("codigo"), rs.getInt("estoque_minimo"), rs.getBoolean("ativo"),
                rs.getString("foto_principal_hash"));
        Integer contado = (Integer) rs.getObject("contado");
        int esperado = rs.getInt("esperado");
        return new Item(produto, esperado, contado, contado == null ? null : contado - esperado, rs.getInt("estoque_atual"),
                rs.getObject("movimento_id") != null);
    }

    private static Instant instant(ResultSet rs, String coluna) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(coluna);
        return t == null ? null : t.toInstant();
    }

    private static PageRequest pagina(Integer pagina, Integer tamanho) {
        int t = tamanho == null ? TAMANHO_PADRAO : Math.max(1, Math.min(tamanho, TAMANHO_MAXIMO));
        return PageRequest.of(pagina == null ? 0 : Math.max(0, pagina), t);
    }
}
