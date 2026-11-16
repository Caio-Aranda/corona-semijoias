package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** O que a dona vê da conferência diária (tarefa 8.4): o histórico das rodadas e o detalhe de cada uma. Só leitura. */
@Service
public class ConsultasDaConferenciaDiaria {

    public static final int TAMANHO_PADRAO = 30;
    public static final int TAMANHO_MAXIMO = 100;

    /** Uma linha do histórico. {@code pedidaPor} é o nome de quem tocou em "Conferir agora" (nulo na rodada automática). */
    public record Rodada(UUID id, java.time.Instant iniciadaEm, java.time.Instant terminadaEm, String origem, String pedidaPor,
            String situacao, int totalDiferencas, List<String> chavesConferidas, List<String> chavesComFalha) {
    }

    public record Diferenca(String item, String tipo, String referencia, String descricao, String esperado, String encontrado,
            String unidade) {
    }

    /** As diferenças de uma conferência (uma chave), na ordem em que foram guardadas. */
    public record Grupo(String chave, List<Diferenca> diferencas) {
    }

    public record RodadaComDiferencas(Rodada rodada, List<Grupo> grupos) {
    }

    public record Pagina(List<Rodada> itens, int pagina, int tamanho, long total) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDaConferenciaDiaria(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SQL_RODADA = """
            SELECT r.id, r.iniciada_em, r.terminada_em, r.origem, u.nome AS pedida_por_nome, r.situacao, r.total_diferencas,
                   r.chaves_conferidas, r.chaves_com_falha
              FROM corona.rodadas_conferencia r
              LEFT JOIN corona.usuarios u ON u.id = r.pedida_por""";

    /** Da rodada mais nova para a mais velha. {@code tamanho} vai de 1 a 100 (padrão 30); {@code pagina} começa em zero. */
    @Transactional(readOnly = true)
    public Pagina listar(Integer pagina, Integer tamanho) {
        int p = pagina == null || pagina < 0 ? 0 : pagina;
        int t = tamanho == null ? TAMANHO_PADRAO : Math.max(1, Math.min(tamanho, TAMANHO_MAXIMO));
        List<Rodada> itens = jdbc.query(SQL_RODADA + " ORDER BY r.iniciada_em DESC, r.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> rodada(rs), t, (long) p * t);
        Long total = jdbc.queryForObject("SELECT count(*) FROM corona.rodadas_conferencia", Long.class);
        return new Pagina(itens, p, t, total == null ? 0 : total);
    }

    /** A rodada com as diferenças agrupadas por conferência; 404 em português se não existe. */
    @Transactional(readOnly = true)
    public RodadaComDiferencas ver(UUID id) {
        List<Rodada> achadas = jdbc.query(SQL_RODADA + " WHERE r.id = ?", (rs, i) -> rodada(rs), id);
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CONFERENCIA_NAO_ENCONTRADA);
        }
        Map<String, List<Diferenca>> grupos = new LinkedHashMap<>();
        jdbc.query("""
                SELECT chave, item, tipo, referencia, descricao, esperado, encontrado, unidade
                  FROM corona.diferencas_conferencia WHERE rodada_id = ? ORDER BY ordem""", rs -> {
            grupos.computeIfAbsent(rs.getString("chave"), c -> new ArrayList<>()).add(new Diferenca(rs.getString("item"),
                    rs.getString("tipo"), rs.getString("referencia"), rs.getString("descricao"), rs.getString("esperado"),
                    rs.getString("encontrado"), rs.getString("unidade")));
        }, id);
        List<Grupo> lista = grupos.entrySet().stream().map(e -> new Grupo(e.getKey(), e.getValue())).toList();
        return new RodadaComDiferencas(achadas.get(0), lista);
    }

    /** A última rodada que começou (por data de início); vazio se nunca houve nenhuma. Usada pelo aviso da tela inicial. */
    @Transactional(readOnly = true)
    public java.util.Optional<Rodada> ultima() {
        return jdbc.query(SQL_RODADA + " ORDER BY r.iniciada_em DESC, r.id DESC LIMIT 1", (rs, i) -> rodada(rs)).stream().findFirst();
    }

    private static Rodada rodada(java.sql.ResultSet rs) throws SQLException {
        return new Rodada(rs.getObject("id", UUID.class), rs.getObject("iniciada_em", OffsetDateTime.class).toInstant(),
                rs.getObject("terminada_em", OffsetDateTime.class).toInstant(), rs.getString("origem"), rs.getString("pedida_por_nome"),
                rs.getString("situacao"), rs.getInt("total_diferencas"), textos(rs.getArray("chaves_conferidas")),
                textos(rs.getArray("chaves_com_falha")));
    }

    private static List<String> textos(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
