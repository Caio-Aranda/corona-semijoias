package br.com.coronasemijoias.api.estoque;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Leituras do histórico do estoque: lista com filtros e ficha do produto (só leitura, nunca grava). */
@Component
public class ConsultasDeEstoque {

    public static final int TAMANHO_PADRAO = 50;
    public static final int TAMANHO_MAXIMO = 200;

    /** O produto como aparece nas telas do estoque: sempre com o hash da foto principal (pode ser vazio). */
    public record ProdutoResumo(UUID id, String nome, String referencia, String codigo, int estoqueMinimo, boolean ativo,
            String fotoPrincipalHash) {
    }

    public record Linha(UUID id, ProdutoResumo produto, String tipo, int quantidade, int saldoDepois, String origem,
            UUID origemId, String motivo, UUID usuarioId, String usuarioNome, Instant ocorridaEm, UUID estornaId,
            UUID desfeitoPorId) {
    }

    public record Filtro(UUID produtoId, String tipo, String origem, UUID usuarioId, LocalDate de, LocalDate ate) {
    }

    public record Ficha(ProdutoResumo produto, int saldo, Page<Linha> historico) {
    }

    private static final String COLUNAS = """
            m.id, m.tipo, m.quantidade, m.saldo_depois, m.origem_tipo, m.origem_id, m.motivo, m.usuario_id,
            u.nome AS usuario_nome, m.ocorrida_em, m.estorna_id, d.id AS desfeito_por_id,
            p.id AS produto_id, p.nome AS produto_nome, p.referencia, p.estoque_minimo, p.ativo, p.foto_principal_hash,
            (SELECT c.codigo FROM corona.produto_codigos c
              WHERE c.produto_id = p.id AND c.principal AND c.deleted_at IS NULL LIMIT 1) AS codigo""";

    private static final String ORIGENS = """
            FROM corona.movimentacoes_estoque m
            JOIN corona.produtos p ON p.id = m.produto_id
            JOIN corona.usuarios u ON u.id = m.usuario_id
            LEFT JOIN corona.movimentacoes_estoque d ON d.estorna_id = m.id""";

    private final JdbcTemplate jdbc;

    ConsultasDeEstoque(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Linha> listar(Filtro f, Integer pagina, Integer tamanho) {
        if (f.tipo() != null) {
            conhecido(ServicoDeEstoque.TipoDeMovimento.class, f.tipo(), "tipo");
        }
        if (f.origem() != null) {
            conhecido(ServicoDeEstoque.OrigemDoMovimento.class, f.origem(), "origem");
        }
        if (f.de() != null && f.ate() != null && f.de().isAfter(f.ate())) {
            throw Validacoes.recusar("de", "A data inicial não pode ser depois da data final.");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder(" WHERE true");
        if (f.produtoId() != null) {
            onde.append(" AND m.produto_id = ?");
            args.add(f.produtoId());
        }
        if (f.tipo() != null) {
            onde.append(" AND m.tipo = ?");
            args.add(f.tipo());
        }
        if (f.origem() != null) {
            onde.append(" AND m.origem_tipo = ?");
            args.add(f.origem());
        }
        if (f.usuarioId() != null) {
            onde.append(" AND m.usuario_id = ?");
            args.add(f.usuarioId());
        }
        // Datas em UTC: "de" vale desde 00:00 e "ate" vale até o fim do dia.
        if (f.de() != null) {
            onde.append(" AND m.ocorrida_em >= ?");
            args.add(java.sql.Timestamp.from(f.de().atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (f.ate() != null) {
            onde.append(" AND m.ocorrida_em < ?");
            args.add(java.sql.Timestamp.from(f.ate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        PageRequest pedida = pagina(pagina, tamanho);
        Long total = jdbc.queryForObject("SELECT count(*) " + ORIGENS + onde, Long.class, args.toArray());
        List<Object> comPagina = new ArrayList<>(args);
        comPagina.add(pedida.getPageSize());
        comPagina.add(pedida.getOffset());
        List<Linha> itens = jdbc.query("SELECT " + COLUNAS + " " + ORIGENS + onde
                + " ORDER BY m.ocorrida_em DESC, m.id DESC LIMIT ? OFFSET ?", (rs, i) -> linha(rs), comPagina.toArray());
        return new PageImpl<>(itens, pedida, total == null ? 0 : total);
    }

    /** Saldo e histórico de um produto (404 se não existe ou foi excluído). */
    @Transactional(readOnly = true)
    public Ficha ficha(UUID produtoId, Integer pagina, Integer tamanho) {
        List<Ficha> achados = jdbc.query("""
                SELECT p.id AS produto_id, p.nome AS produto_nome, p.referencia, p.estoque_minimo, p.ativo,
                       p.foto_principal_hash, p.estoque_atual,
                       (SELECT c.codigo FROM corona.produto_codigos c
                         WHERE c.produto_id = p.id AND c.principal AND c.deleted_at IS NULL LIMIT 1) AS codigo
                  FROM corona.produtos p WHERE p.id = ? AND p.deleted_at IS NULL""",
                (rs, i) -> new Ficha(resumo(rs), rs.getInt("estoque_atual"), null), produtoId);
        if (achados.isEmpty()) {
            throw br.com.coronasemijoias.api.cadastros.ServicoDeCadastro.naoEncontrado();
        }
        Page<Linha> historico = listar(new Filtro(produtoId, null, null, null, null, null), pagina, tamanho);
        return new Ficha(achados.get(0).produto(), achados.get(0).saldo(), historico);
    }

    private static <E extends Enum<E>> void conhecido(Class<E> tipo, String valor, String campo) {
        try {
            Enum.valueOf(tipo, valor);
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar(campo, "Escolha uma das opções da lista.");
        }
    }

    private static PageRequest pagina(Integer pagina, Integer tamanho) {
        int t = tamanho == null ? TAMANHO_PADRAO : Math.max(1, Math.min(tamanho, TAMANHO_MAXIMO));
        return PageRequest.of(pagina == null ? 0 : Math.max(0, pagina), t);
    }

    private static ProdutoResumo resumo(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProdutoResumo(rs.getObject("produto_id", UUID.class), rs.getString("produto_nome"),
                rs.getString("referencia"), rs.getString("codigo"), rs.getInt("estoque_minimo"), rs.getBoolean("ativo"),
                rs.getString("foto_principal_hash"));
    }

    private static Linha linha(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Linha(rs.getObject("id", UUID.class), resumo(rs), rs.getString("tipo"), rs.getInt("quantidade"),
                rs.getInt("saldo_depois"), rs.getString("origem_tipo"), rs.getObject("origem_id", UUID.class),
                rs.getString("motivo"), rs.getObject("usuario_id", UUID.class), rs.getString("usuario_nome"),
                rs.getTimestamp("ocorrida_em").toInstant(), rs.getObject("estorna_id", UUID.class),
                rs.getObject("desfeito_por_id", UUID.class));
    }
}
