package br.com.coronasemijoias.api.acessos;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Apaga de verdade, depois de {@link #RETENCAO}, os dados técnicos que não são dados de negócio: sessões encerradas
 * (com os tokens de renovação delas) e tentativas de login. É a única exceção à regra "nunca DELETE" (ADR-0002, linha
 * de 11/11/2026; migração V028). Sessão ainda aberta nunca é apagada. Roda todo dia pela {@link AgendaDaLimpezaTecnica};
 * é seguro rodar de novo a qualquer hora.
 */
@Service
public class LimpezaDeDadosTecnicos {

    /** Quanto tempo os dados técnicos ficam no banco. */
    public static final Duration RETENCAO = Duration.ofDays(90);

    /** Uma sessão sem renovar por este tempo já venceu (30 dias da última renovação, ADR-0004). */
    private static final Duration VALIDADE_DA_SESSAO = Duration.ofDays(30);

    private static final Logger LOG = LoggerFactory.getLogger(LimpezaDeDadosTecnicos.class);

    /** Quantos registros saíram de cada tabela. */
    public record Resultado(int sessoes, int tokens, int tentativas) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    public LimpezaDeDadosTecnicos(JdbcTemplate jdbc, TransactionTemplate transacao, Clock relogio) {
        this.jdbc = jdbc;
        this.transacao = transacao;
        this.relogio = relogio;
    }

    public Resultado limpar() {
        Timestamp corte = Timestamp.from(relogio.instant().minus(RETENCAO));
        Timestamp corteDaExpirada = Timestamp.from(relogio.instant().minus(RETENCAO).minus(VALIDADE_DA_SESSAO));
        Resultado r = transacao.execute(status -> {
            // Só nesta transação o banco deixa passar o DELETE destas tabelas (V028).
            jdbc.queryForObject("SELECT set_config('corona.limpeza_tecnica', 'sim', true)", String.class);
            String encerradas = "SELECT id FROM corona.sessoes WHERE (revogada_em IS NOT NULL AND revogada_em < ?) "
                    + "OR (revogada_em IS NULL AND usada_em < ?)";
            int tokens = jdbc.update("DELETE FROM corona.tokens_de_renovacao WHERE sessao_id IN (" + encerradas + ")",
                    corte, corteDaExpirada);
            int sessoes = jdbc.update("DELETE FROM corona.sessoes WHERE (revogada_em IS NOT NULL AND revogada_em < ?) "
                    + "OR (revogada_em IS NULL AND usada_em < ?)", corte, corteDaExpirada);
            int tentativas = jdbc.update("DELETE FROM corona.tentativas_login WHERE ocorrida_em < ?", corte);
            return new Resultado(sessoes, tokens, tentativas);
        });
        if (r.sessoes() + r.tokens() + r.tentativas() > 0) {
            LOG.info("Limpeza técnica: {} sessão(ões), {} token(s) de renovação e {} tentativa(s) de login com mais de 90 dias saíram do banco.",
                    r.sessoes(), r.tokens(), r.tentativas());
        }
        return r;
    }
}
