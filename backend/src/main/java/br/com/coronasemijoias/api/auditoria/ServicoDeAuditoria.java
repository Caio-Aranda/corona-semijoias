package br.com.coronasemijoias.api.auditoria;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registra na tabela {@code auditoria} quem fez o quê (ESPECIFICACAO §9 e §11). O registro entra na
 * MESMA transação da mudança: se a mudança não vale, o registro dela também não vale. Nunca recebe
 * senha nem hash: quem chama passa só os campos que importam.
 */
@Service
public class ServicoDeAuditoria {

    /** De onde veio a ação. */
    public enum Origem {
        /** Uma pessoa logada, por uma rota da API. */
        API,
        /** Comando do servidor, sem pessoa logada ("pelo servidor"). */
        COMANDO
    }

    /** Quem fez: uma pessoa (origem API) ou o servidor (origem COMANDO, sem usuário). */
    public record Ator(UUID usuarioId, Origem origem) {

        /** A ação feita pelos comandos do servidor. */
        public static final Ator PELO_SERVIDOR = new Ator(null, Origem.COMANDO);

        public static Ator usuario(UUID usuarioId) {
            return new Ator(java.util.Objects.requireNonNull(usuarioId), Origem.API);
        }
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock relogio;

    public ServicoDeAuditoria(JdbcTemplate jdbc, JsonMapper json, Clock relogio) {
        this.jdbc = jdbc;
        this.json = json;
        this.relogio = relogio;
    }

    /** Grava um registro. Exige uma transação aberta (a da mudança que está sendo auditada). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Ator ator, String acao, String tabela, UUID registroId,
            Map<String, Object> antes, Map<String, Object> depois) {
        jdbc.update("""
                INSERT INTO corona.auditoria (id, ator_usuario_id, origem, acao, tabela, registro_id, antes, depois, ocorrida_em)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)""",
                UuidV7.gerar(), ator.usuarioId(), ator.origem().name(), acao, tabela, registroId,
                paraJson(antes), paraJson(depois), Timestamp.from(relogio.instant()));
    }

    private String paraJson(Map<String, Object> campos) {
        return campos == null ? null : json.writeValueAsString(campos);
    }
}
