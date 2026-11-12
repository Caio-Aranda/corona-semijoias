package br.com.coronasemijoias.api.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Retenção do {@code sync_log} (decisão do responsável de 11/11/2026; ADR-0013, linha datada): o JSON completo de cada
 * operação fica {@link #RETENCAO}; depois, a linha fica só com o resumo (quem, quando, tipo, resultado e o SHA-256) e o
 * JSON vira vazio. É um UPDATE, nunca DELETE. Roda todo dia na agenda da limpeza técnica; é seguro rodar de novo.
 */
@Service
public class EsvaziamentoDoRegistroDeEnvios {

    public static final Duration RETENCAO = Duration.ofDays(90);

    private static final Logger LOG = LoggerFactory.getLogger(EsvaziamentoDoRegistroDeEnvios.class);

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    public EsvaziamentoDoRegistroDeEnvios(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    /** Devolve quantas linhas tiveram o JSON esvaziado. */
    public int esvaziar() {
        Timestamp agora = Timestamp.from(relogio.instant());
        int linhas = jdbc.update("""
                UPDATE corona.sync_log SET dados = NULL, dados_esvaziados_em = ?
                 WHERE dados IS NOT NULL AND recebido_em < ?""", agora, Timestamp.from(relogio.instant().minus(RETENCAO)));
        if (linhas > 0) {
            LOG.info("Registro dos envios: {} envio(s) com mais de 90 dias ficaram só com o resumo.", linhas);
        }
        return linhas;
    }
}
