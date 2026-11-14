package br.com.coronasemijoias.api.testes;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Põe e tira maletas do acerto por SQL, para os testes das outras operações (venda, devolução, troca, recebimento) que só
 * precisam da maleta {@code EM_ACERTO} (tarefa 7.4). Desde a V043 o banco não deixa mudar o status direto: a maleta entra
 * ligada a um acerto em andamento do mesmo vendedor, como o serviço faz.
 */
public final class AcertosDeTeste {

    private AcertosDeTeste() {
    }

    /** Liga a maleta a um acerto em andamento do vendedor dela (cria um, se não houver), como o iniciar faz. */
    public static void emAcerto(JdbcTemplate jdbc, UUID remessaId) {
        UUID vendedor = jdbc.queryForObject("SELECT vendedor_id FROM corona.remessas WHERE id = ?", UUID.class, remessaId);
        UUID semana = jdbc.queryForObject("SELECT semana_id FROM corona.remessas WHERE id = ?", UUID.class, remessaId);
        UUID acerto = jdbc.query("SELECT id FROM corona.acertos WHERE vendedor_id = ? AND status = 'EM_ACERTO'",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, vendedor);
        if (acerto == null) {
            acerto = UUID.randomUUID();
            UUID quem = jdbc.queryForObject("SELECT id FROM corona.usuarios ORDER BY created_at LIMIT 1", UUID.class);
            jdbc.update("""
                    INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                    VALUES (?, ?, ?, 'EM_ACERTO', false, now(), ?)""", acerto, vendedor, semana, quem);
        }
        jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO', acerto_id = ?, status_antes_do_acerto = status WHERE id = ?",
                acerto, remessaId);
    }

    /** Tira a maleta do acerto, de volta ao status de antes (como o desistir faz). */
    public static void sairDoAcerto(JdbcTemplate jdbc, UUID remessaId) {
        jdbc.update("""
                UPDATE corona.remessas SET status = status_antes_do_acerto, acerto_id = NULL, status_antes_do_acerto = NULL
                 WHERE id = ? AND status = 'EM_ACERTO'""", remessaId);
    }

    /** Maleta acertada (o fechamento chega na 7.5): sai do acerto em andamento e vai para ACERTADA. */
    public static void acertada(JdbcTemplate jdbc, UUID remessaId) {
        sairDoAcerto(jdbc, remessaId);
        jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", remessaId);
    }
}
