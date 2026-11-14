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
        if ("EM_ACERTO".equals(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, remessaId))) {
            return;
        }
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

    /**
     * Maleta acertada (desde a V044 só vai para ACERTADA vinda do acerto em andamento, com saldo zero em todas as peças): liga a
     * maleta a um acerto em andamento, tira o que sobrou como perda (o fechamento de verdade não passa por aqui; este atalho só
     * prepara a premissa dos testes de venda, devolução e troca) e a passa a ACERTADA, mantendo a ligação.
     */
    public static void acertada(JdbcTemplate jdbc, UUID remessaId) {
        emAcerto(jdbc, remessaId);
        for (var sobra : jdbc.queryForList("""
                SELECT t.produto_id, sum(t.q) AS saldo FROM (
                    SELECT i.produto_id, i.qtd_enviada AS q FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.deleted_at IS NULL
                    UNION ALL
                    SELECT m.produto_id, m.quantidade FROM corona.movimentos_remessa m WHERE m.remessa_id = ?) t
                 GROUP BY t.produto_id HAVING sum(t.q) > 0""", remessaId, remessaId)) {
            jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (uuidv7(), ?, ?, 'PERDA', ?)",
                    remessaId, sobra.get("produto_id"), -((Number) sobra.get("saldo")).intValue());
        }
        jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", remessaId);
        // O acerto de mentira não fica em andamento (isso travaria o envio de maleta nova do vendedor): se nenhuma outra maleta
        // depende dele, ele é desistido (a maleta continua acertada, só para a premissa dos testes).
        jdbc.update("""
                UPDATE corona.acertos a SET status = 'DESISTIDO', desistencia_id = uuidv7(), desistido_em = now(),
                       desistido_por = (SELECT id FROM corona.usuarios ORDER BY created_at LIMIT 1),
                       motivo_desistencia = 'acerto de teste'
                 WHERE a.id = (SELECT acerto_id FROM corona.remessas WHERE id = ?) AND a.status = 'EM_ACERTO'
                   AND NOT EXISTS (SELECT 1 FROM corona.remessas r WHERE r.acerto_id = a.id AND r.status = 'EM_ACERTO')""", remessaId);
    }
}
