package br.com.coronasemijoias.api.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Quais computadores do Corona Vendas estão parados (tarefa 5.11; ADR-0013, item 21; dúvida D4: 3 dias). Calculado na
 * hora, nada é gravado. Um computador {@code ATIVO} de um vendedor ativo está parado quando:
 *
 * <ul>
 *   <li>não fala com o servidor há {@code dias} ou mais (login, recebimento ou envio), ou
 *   <li>disse ter operações aguardando envio e o último envio dele é de {@code dias} ou mais atrás.
 * </ul>
 *
 * Computador trocado ou bloqueado não conta (já está fora de uso), nem o de vendedor desligado.
 */
@Component
public class ComputadoresParados {

    /** Um computador parado: de quem é, desde quando está sem enviar e há quantos dias inteiros. */
    public record Parado(UUID computadorId, String vendedor, Instant desde, long dias) {
    }

    /** Um segundo computador que entrou e espera a dona liberar. */
    public record Novo(UUID computadorId, String vendedor, Instant entrouEm) {
    }

    private final JdbcTemplate jdbc;
    private final Clock relogio;
    private final int dias;

    public ComputadoresParados(JdbcTemplate jdbc, Clock relogio,
            @Value("${corona.sync.aviso-sem-enviar-dias:3}") int dias) {
        this.jdbc = jdbc;
        this.relogio = relogio;
        this.dias = Math.max(1, dias);
    }

    public int prazoEmDias() {
        return dias;
    }

    public List<Parado> semEnviar() {
        Instant agora = relogio.instant();
        Instant limite = agora.minus(Duration.ofDays(dias));
        List<Parado> parados = new ArrayList<>();
        jdbc.query("""
                SELECT d.id, u.nome, d.ultimo_contato_em, d.aguardando_envio,
                       coalesce(d.ultimo_push_em, d.aguardando_informado_em, d.primeiro_contato_em) AS ultimo_envio
                  FROM corona.dispositivos d
                  JOIN corona.usuarios u ON u.id = d.usuario_id
                 WHERE d.situacao = 'ATIVO' AND u.ativo AND u.perfil = 'VENDEDOR'
                 ORDER BY u.nome, d.id""", rs -> {
            Instant contato = rs.getTimestamp("ultimo_contato_em").toInstant();
            Instant envio = rs.getTimestamp("ultimo_envio").toInstant();
            int aguardando = rs.getInt("aguardando_envio");
            Instant desde = null;
            if (!contato.isAfter(limite)) {
                desde = contato;
            }
            if (aguardando > 0 && !envio.isAfter(limite) && (desde == null || envio.isBefore(desde))) {
                desde = envio;
            }
            if (desde != null) {
                parados.add(new Parado(rs.getObject("id", UUID.class), rs.getString("nome"), desde,
                        Duration.between(desde, agora).toDays()));
            }
        });
        return parados;
    }

    public List<Novo> aguardandoLiberacao() {
        return jdbc.query("""
                SELECT d.id, u.nome, d.primeiro_contato_em
                  FROM corona.dispositivos d
                  JOIN corona.usuarios u ON u.id = d.usuario_id
                 WHERE d.situacao = 'AGUARDANDO_LIBERACAO' AND u.ativo AND u.perfil = 'VENDEDOR'
                 ORDER BY d.primeiro_contato_em, d.id""",
                (rs, i) -> new Novo(rs.getObject("id", UUID.class), rs.getString("nome"),
                        rs.getTimestamp("primeiro_contato_em").toInstant()));
    }
}
