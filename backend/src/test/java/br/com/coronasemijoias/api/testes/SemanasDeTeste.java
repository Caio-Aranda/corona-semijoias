package br.com.coronasemijoias.api.testes;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fecha uma semana por SQL, como o serviço faz: o registro do fechamento primeiro e depois a situação (a semana nunca reabre). */
public final class SemanasDeTeste {

    private SemanasDeTeste() {
    }

    public static void fecharNoBanco(JdbcTemplate jdbc, UUID semana, UUID usuarioId) {
        jdbc.update("INSERT INTO corona.fechamentos_semana (id, semana_id, fechada_por) VALUES (?, ?, ?)", UUID.randomUUID(), semana, usuarioId);
        jdbc.update("UPDATE corona.semanas SET situacao = 'FECHADA' WHERE id = ?", semana);
    }
}
