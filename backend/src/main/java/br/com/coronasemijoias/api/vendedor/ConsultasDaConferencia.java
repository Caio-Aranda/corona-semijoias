package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Leitura da conferência da maleta (tarefa 4.3): o vendedor lê a dele; a dona lê a de qualquer maleta. Sem custo. */
@Component
public class ConsultasDaConferencia {

    public record Linha(UUID produtoId, String produtoNome, int esperado, int contado, int diferenca) {
    }

    public record Conferencia(UUID id, UUID remessaId, Instant conferidaEm, int divergencias, List<Linha> itens) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDaConferencia(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A conferência da maleta; {@code vendedorId} nulo é a dona. 404 se ainda não houve conferência (ou se é de outro vendedor). */
    public Conferencia daMaleta(UUID remessaId, UUID vendedorId) {
        List<UUID> ids = vendedorId == null
                ? jdbc.query("SELECT id FROM corona.conferencias WHERE remessa_id = ?", (rs, n) -> rs.getObject(1, UUID.class), remessaId)
                : jdbc.query("SELECT id FROM corona.conferencias WHERE remessa_id = ? AND vendedor_id = ?",
                        (rs, n) -> rs.getObject(1, UUID.class), remessaId, vendedorId);
        if (ids.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        return pelaId(ids.get(0));
    }

    public Conferencia pelaId(UUID conferenciaId) {
        var cabeca = jdbc.queryForMap("SELECT remessa_id, divergencias, conferida_em FROM corona.conferencias WHERE id = ?",
                conferenciaId);
        List<Linha> itens = jdbc.query("""
                SELECT i.produto_id, p.nome, i.esperado, i.contado FROM corona.itens_conferencia i
                  JOIN corona.produtos p ON p.id = i.produto_id WHERE i.conferencia_id = ? ORDER BY p.nome, p.id""",
                (rs, n) -> new Linha(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(4) - rs.getInt(3)), conferenciaId);
        return new Conferencia(conferenciaId, (UUID) cabeca.get("remessa_id"),
                ((java.sql.Timestamp) cabeca.get("conferida_em")).toInstant(), ((Number) cabeca.get("divergencias")).intValue(),
                itens);
    }
}
