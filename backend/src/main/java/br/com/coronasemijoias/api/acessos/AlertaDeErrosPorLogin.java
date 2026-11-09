package br.com.coronasemijoias.api.acessos;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Alerta de muitos erros num mesmo login vindos de IPs diferentes (ADR-0004, item 7): mais de
 * {@code alertaPorLogin} erros (20) em {@code janelaDoAlerta} (1 hora), somando todos os IPs. Só
 * alerta, nunca bloqueia (senão qualquer um trancaria a conta de outra pessoa).
 *
 * <p>Calculado na hora a partir de {@code tentativas_login}, sem tabela própria. O bloco "O que precisa
 * da sua atenção" da dona (§19.3) vai chamar {@link #loginsComMuitosErros(Instant)} quando existir; o
 * texto do aviso entra no glossário com a tarefa que o mostrar.
 */
@Component
public class AlertaDeErrosPorLogin {

    /** Um login com erros demais na janela. */
    public record LoginComMuitosErros(String login, long erros, Instant primeiroErro, Instant ultimoErro) {
    }

    private final JdbcTemplate jdbc;
    private final PropriedadesDasTentativas propriedades;

    public AlertaDeErrosPorLogin(JdbcTemplate jdbc, PropriedadesDasTentativas propriedades) {
        this.jdbc = jdbc;
        this.propriedades = propriedades;
    }

    /** Os logins com mais erros que o limite na janela que termina em {@code agora}, do pior para o melhor. */
    public List<LoginComMuitosErros> loginsComMuitosErros(Instant agora) {
        return jdbc.query("""
                SELECT login, count(*) AS erros, min(ocorrida_em) AS primeiro, max(ocorrida_em) AS ultimo
                  FROM corona.tentativas_login
                 WHERE resultado = 'SENHA_INCORRETA' AND ocorrida_em > ? AND ocorrida_em <= ?
                 GROUP BY login
                HAVING count(*) > ?
                 ORDER BY erros DESC, login""",
                (rs, i) -> new LoginComMuitosErros(rs.getString("login"), rs.getLong("erros"),
                        rs.getObject("primeiro", OffsetDateTime.class).toInstant(),
                        rs.getObject("ultimo", OffsetDateTime.class).toInstant()),
                Timestamp.from(agora.minus(propriedades.janelaDoAlerta())), Timestamp.from(agora),
                propriedades.alertaPorLogin());
    }
}
