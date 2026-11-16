package br.com.coronasemijoias.api.testes;

import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rodadas da conferência diária feitas à mão para os testes (tarefa 8.4). O aviso da tela inicial olha a última rodada; sem nenhuma
 * (ou com uma velha, ou com diferença), a lista de avisos ganha uma linha a mais. Os testes que esperam a lista de avisos limpa
 * gravam {@link #limpa} na preparação, e os que gravam rodadas com diferença ou falha deixam uma limpa no fim.
 */
public final class RodadasDeTeste {

    private RodadasDeTeste() {
    }

    /** Uma rodada automática sem diferença, começada em {@code iniciada}. Devolve o id. */
    public static UUID limpa(JdbcTemplate jdbc, Instant iniciada) {
        return gravar(jdbc, iniciada, "SEM_DIFERENCA", 0, List.of());
    }

    public static UUID comDiferenca(JdbcTemplate jdbc, Instant iniciada, int total) {
        return gravar(jdbc, iniciada, "COM_DIFERENCA", total, List.of());
    }

    public static UUID falhou(JdbcTemplate jdbc, Instant iniciada, String... chavesComFalha) {
        return gravar(jdbc, iniciada, "FALHOU", 0, List.of(chavesComFalha));
    }

    public static UUID gravar(JdbcTemplate jdbc, Instant iniciada, String situacao, int total, List<String> chavesComFalha) {
        UUID id = UUID.randomUUID();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO corona.rodadas_conferencia (id, origem, iniciada_em, terminada_em, situacao, chaves_conferidas,
                                                            chaves_com_falha, total_diferencas)
                    VALUES (?, 'AUTOMATICA', ?, ?, ?, ?, ?, ?)""");
            ps.setObject(1, id);
            ps.setObject(2, OffsetDateTime.ofInstant(iniciada, ZoneOffset.UTC));
            ps.setObject(3, OffsetDateTime.ofInstant(iniciada, ZoneOffset.UTC));
            ps.setString(4, situacao);
            Array conferidas = con.createArrayOf("text", new Object[] {"C1"});
            ps.setArray(5, conferidas);
            ps.setArray(6, con.createArrayOf("text", chavesComFalha.toArray()));
            ps.setInt(7, total);
            return ps;
        });
        return id;
    }
}
