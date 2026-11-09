package br.com.coronasemijoias.api.acessos;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registro das tentativas de login e bloqueio (ADR-0004, item 7), sobre a tabela só de inserção
 * {@code tentativas_login}.
 *
 * <ul>
 *   <li><b>Login + IP:</b> {@code tentativas_login_antes_do_bloqueio} erros seguidos (sem um sucesso da
 *       combinação, um bloqueio anterior dela ou uma redefinição de senha do login no meio) bloqueiam a
 *       combinação por {@code tempo_bloqueio_login_minutos}, contados do último erro. Os dois números
 *       vêm de {@code configuracoes}.</li>
 *   <li><b>Só IP:</b> {@code limitePorIp} erros em {@code janelaPorIp} (janela móvel, qualquer login)
 *       bloqueiam o IP por {@code bloqueioPorIp} (configuração da aplicação). A redefinição da senha
 *       não retira este bloqueio.</li>
 * </ul>
 *
 * O fim do bloqueio é gravado na linha do erro que o completou, então "está bloqueado?" é uma consulta
 * simples. Quem chama segura a {@link TravaPorIp} entre a conferência e a gravação.
 */
@Component
public class ControleDeTentativas {

    /** Maior duração possível do bloqueio de login + IP (o CHECK de {@code configuracoes} vai até 1440 minutos). */
    private static final Duration MAIOR_BLOQUEIO_DE_LOGIN = Duration.ofMinutes(1440);

    /** Um bloqueio em vigor, até {@code ate}. */
    public record Bloqueio(Instant ate) {

        /** O que falta, nunca negativo. */
        public Duration falta(Instant agora) {
            Duration falta = Duration.between(agora, ate);
            return falta.isNegative() ? Duration.ZERO : falta;
        }
    }

    /** Uma tentativa a gravar. {@code ip} é nulo só no {@code BLOQUEIO_LIMPO}. */
    public record Tentativa(String login, String ip, UUID dispositivoId, ResultadoDaTentativa resultado,
            Instant ocorridaEm) {
    }

    private final JdbcTemplate jdbc;
    private final Configuracoes configuracoes;
    private final PropriedadesDasTentativas propriedades;

    public ControleDeTentativas(JdbcTemplate jdbc, Configuracoes configuracoes, PropriedadesDasTentativas propriedades) {
        this.jdbc = jdbc;
        this.configuracoes = configuracoes;
        this.propriedades = propriedades;
    }

    /** O bloqueio que vale agora para este login e IP (o que termina mais tarde), se houver. */
    public Optional<Bloqueio> bloqueioEmVigor(String login, String ip, Instant agora) {
        String registro = paraRegistro(login);
        Instant doLogin = jdbc.queryForObject("""
                SELECT max(bloqueio_ate) FROM corona.tentativas_login
                 WHERE login = ? AND ip = ?::inet AND bloqueio_ate > ? AND ocorrida_em > ?
                   AND ocorrida_em > coalesce((SELECT max(l.ocorrida_em) FROM corona.tentativas_login l
                                                WHERE l.login = ? AND l.resultado = 'BLOQUEIO_LIMPO'),
                                              '-infinity'::timestamptz)""",
                ControleDeTentativas::instante,
                registro, ip, Timestamp.from(agora), Timestamp.from(agora.minus(MAIOR_BLOQUEIO_DE_LOGIN)), registro);
        Instant doIp = jdbc.queryForObject("""
                SELECT max(bloqueio_do_ip_ate) FROM corona.tentativas_login
                 WHERE ip = ?::inet AND bloqueio_do_ip_ate > ? AND ocorrida_em > ?""",
                ControleDeTentativas::instante,
                ip, Timestamp.from(agora), Timestamp.from(agora.minus(propriedades.bloqueioPorIp())));
        return Stream.of(doLogin, doIp).filter(i -> i != null).max(Comparator.naturalOrder()).map(Bloqueio::new);
    }

    /** Grava uma tentativa que não é erro de senha (sucesso, bloqueada, desativado...). */
    public void registrar(Tentativa tentativa) {
        if (tentativa.resultado() == ResultadoDaTentativa.SENHA_INCORRETA) {
            throw new IllegalArgumentException("erro de senha passa por registrarErro");
        }
        inserir(tentativa, null, null);
    }

    /**
     * Grava um erro de senha e, se ele completar um limite, o fim do bloqueio na mesma linha. Devolve o
     * bloqueio que este erro começou, se começou.
     */
    public Optional<Bloqueio> registrarErro(String login, String ip, UUID dispositivoId, Instant agora) {
        String registro = paraRegistro(login);
        Long errosDoLogin = jdbc.queryForObject("""
                SELECT count(*) FROM corona.tentativas_login
                 WHERE login = ? AND ip = ?::inet AND resultado = 'SENHA_INCORRETA'
                   AND ocorrida_em > coalesce((SELECT max(t.ocorrida_em) FROM corona.tentativas_login t
                                                WHERE t.login = ?
                                                  AND (t.resultado = 'BLOQUEIO_LIMPO'
                                                       OR (t.ip = ?::inet AND (t.resultado = 'SUCESSO'
                                                                                OR t.bloqueio_ate IS NOT NULL)))),
                                              '-infinity'::timestamptz)""",
                Long.class, registro, ip, registro, ip);
        Long errosDoIp = jdbc.queryForObject("""
                SELECT count(*) FROM corona.tentativas_login
                 WHERE ip = ?::inet AND resultado = 'SENHA_INCORRETA' AND ocorrida_em > ?
                   AND ocorrida_em > coalesce((SELECT max(t.ocorrida_em) FROM corona.tentativas_login t
                                                WHERE t.ip = ?::inet AND t.bloqueio_do_ip_ate IS NOT NULL),
                                              '-infinity'::timestamptz)""",
                Long.class, ip, Timestamp.from(agora.minus(propriedades.janelaPorIp())), ip);
        int limiteDoLogin = configuracoes.inteiro(ChaveDeConfiguracao.TENTATIVAS_LOGIN_ANTES_DO_BLOQUEIO);
        Instant bloqueioDoLogin = errosDoLogin + 1 >= limiteDoLogin
                ? agora.plus(Duration.ofMinutes(configuracoes.inteiro(ChaveDeConfiguracao.TEMPO_BLOQUEIO_LOGIN_MINUTOS)))
                : null;
        Instant bloqueioDoIp = errosDoIp + 1 >= propriedades.limitePorIp() ? agora.plus(propriedades.bloqueioPorIp()) : null;
        inserir(new Tentativa(login, ip, dispositivoId, ResultadoDaTentativa.SENHA_INCORRETA, agora),
                bloqueioDoLogin, bloqueioDoIp);
        return Stream.of(bloqueioDoLogin, bloqueioDoIp).filter(i -> i != null).max(Comparator.naturalOrder())
                .map(Bloqueio::new);
    }

    /** Redefinição de senha: retira o bloqueio daquele login, em qualquer IP (o bloqueio só por IP continua). */
    public void limparBloqueio(String login, Instant agora) {
        inserir(new Tentativa(login, null, null, ResultadoDaTentativa.BLOQUEIO_LIMPO, agora), null, null);
    }

    private void inserir(Tentativa t, Instant bloqueioAte, Instant bloqueioDoIpAte) {
        jdbc.update("""
                INSERT INTO corona.tentativas_login
                    (id, login, ip, dispositivo_id, resultado, bloqueio_ate, bloqueio_do_ip_ate, ocorrida_em)
                VALUES (?, ?, ?::inet, ?, ?, ?, ?, ?)""",
                UuidV7.gerar(), paraRegistro(t.login()), t.ip(), t.dispositivoId(), t.resultado().name(),
                bloqueioAte == null ? null : Timestamp.from(bloqueioAte),
                bloqueioDoIpAte == null ? null : Timestamp.from(bloqueioDoIpAte),
                Timestamp.from(t.ocorridaEm()));
    }

    /**
     * O login como vai para a tabela: o já normalizado, com caractere de controle trocado por "?" (o
     * PostgreSQL não guarda o caractere nulo) e no máximo 200 caracteres.
     */
    static String paraRegistro(String login) {
        if (login == null) {
            return "";
        }
        StringBuilder limpo = new StringBuilder();
        login.codePoints().limit(200).forEach(c -> limpo.appendCodePoint(Character.isISOControl(c) ? '?' : c));
        return limpo.toString();
    }

    private static Instant instante(java.sql.ResultSet rs, int linha) throws java.sql.SQLException {
        OffsetDateTime valor = rs.getObject(1, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }
}
