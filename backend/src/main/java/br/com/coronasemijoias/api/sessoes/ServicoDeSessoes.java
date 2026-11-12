package br.com.coronasemijoias.api.sessoes;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessão longa (ADR-0004, item 2): abre a família no login, renova com rotação e janela de tolerância,
 * revoga na saída, no reuso suspeito, no outro aparelho e na troca de credencial.
 *
 * <p>Não depende de {@code usuarios} nem de {@code seguranca} (quem redefine a senha precisa revogar as
 * sessões). Cada método é a sua própria transação e devolve um resultado em vez de lançar exceção: uma
 * revogação é gravada mesmo quando a resposta à pessoa é "sessão terminada".
 */
@Service
public class ServicoDeSessoes {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDeSessoes.class);

    /** A família recém-aberta. {@code tokenDeRenovacao} é nulo na família da troca de senha. */
    public record SessaoAberta(UUID sid, UUID dispositivoId, String tokenDeRenovacao, Instant renovacaoExpiraEm) {

        @Override
        public String toString() {
            return "SessaoAberta[sid=" + sid + ", dispositivoId=" + dispositivoId + ", tokenDeRenovacao=(oculto)]";
        }
    }

    /** O que a conferência por requisição precisa saber da família. */
    public record SituacaoDaSessao(UUID usuarioId, TipoDeSessao tipo, UUID dispositivoId, boolean revogada,
            MotivoDaRevogacao motivo) {
    }

    /** Resultado da renovação. */
    public sealed interface Renovacao permits Renovada, Recusada {
    }

    /** Renovação aceita: o token de renovação (novo, ou o mesmo sucessor dentro da janela). */
    public record Renovada(UUID sid, UUID usuarioId, UUID dispositivoId, String tokenDeRenovacao,
            Instant renovacaoExpiraEm) implements Renovacao {

        @Override
        public String toString() {
            return "Renovada[sid=" + sid + ", usuarioId=" + usuarioId + ", tokenDeRenovacao=(oculto)]";
        }
    }

    /**
     * Renovação recusada. {@code revogou} diz se a família foi revogada agora (e por quê); {@code jaRevogadaPor}, o motivo
     * gravado quando a família já estava revogada (o computador bloqueado recebe a ordem de apagar, tarefa 5.3), e
     * {@code dispositivoId}, o computador da família (vazio quando o token nem existe).
     */
    public record Recusada(MotivoDaRevogacao revogou, MotivoDaRevogacao jaRevogadaPor, UUID dispositivoId, UUID usuarioId)
            implements Renovacao {

        public Recusada(MotivoDaRevogacao revogou) {
            this(revogou, null, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final SegredoDeRenovacao segredo;
    private final PropriedadesDaSessao propriedades;
    private final Clock relogio;

    public ServicoDeSessoes(JdbcTemplate jdbc, SegredoDeRenovacao segredo, PropriedadesDaSessao propriedades,
            Clock relogio) {
        this.jdbc = jdbc;
        this.segredo = segredo;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    /** Abre uma família. A {@code NORMAL} nasce com o primeiro token de renovação (aleatório). */
    @Transactional
    public SessaoAberta abrir(UUID usuarioId, TipoDeSessao tipo, UUID dispositivoId) {
        Instant agora = agora();
        UUID sid = UuidV7.gerar();
        jdbc.update("""
                INSERT INTO corona.sessoes (id, usuario_id, tipo, dispositivo_id, criada_em, usada_em)
                VALUES (?, ?, ?, ?, ?, ?)""",
                sid, usuarioId, tipo.name(), dispositivoId, Timestamp.from(agora), Timestamp.from(agora));
        if (tipo != TipoDeSessao.NORMAL) {
            return new SessaoAberta(sid, dispositivoId, null, null);
        }
        String valor = segredo.aleatorio();
        Instant vence = agora.plus(propriedades.validade());
        inserirToken(UuidV7.gerar(), sid, null, valor, agora, vence);
        return new SessaoAberta(sid, dispositivoId, valor, vence);
    }

    /**
     * Renova (ADR-0004, item 2). A linha da família fica travada até o fim da transação, então duas
     * renovações da mesma família nunca correm juntas; o {@code UNIQUE (anterior_id)} do banco é a
     * segunda barreira contra dois sucessores.
     */
    @Transactional
    public Renovacao renovar(String valor, UUID dispositivoId) {
        if (valor == null || valor.isBlank()) {
            return new Recusada(null);
        }
        byte[] hash = SegredoDeRenovacao.hash(valor);
        List<UUID> familias = jdbc.queryForList(
                "SELECT sessao_id FROM corona.tokens_de_renovacao WHERE hash = ?", UUID.class, hash);
        if (familias.isEmpty()) {
            return new Recusada(null);
        }
        UUID sid = familias.get(0);
        Familia familia = jdbc.queryForObject("""
                SELECT usuario_id, dispositivo_id, revogada_em IS NOT NULL AS revogada, motivo_da_revogacao
                  FROM corona.sessoes WHERE id = ? FOR UPDATE""",
                (rs, i) -> new Familia(rs.getObject("usuario_id", UUID.class), rs.getObject("dispositivo_id", UUID.class),
                        rs.getBoolean("revogada"), motivo(rs.getString("motivo_da_revogacao"))),
                sid);
        // Lido só depois da trava: enxerga o que outra renovação da mesma família acabou de gravar.
        Token token = jdbc.queryForObject("""
                SELECT id, vence_em, usado_em, reapresentado_em FROM corona.tokens_de_renovacao WHERE hash = ?""",
                (rs, i) -> new Token(rs.getObject("id", UUID.class), instante(rs, "vence_em"), instante(rs, "usado_em"),
                        instante(rs, "reapresentado_em")),
                hash);
        if (familia.revogada()) {
            return new Recusada(null, familia.motivo(), familia.dispositivoId(), familia.usuarioId());
        }
        Instant agora = agora();
        if (!Objects.equals(familia.dispositivoId(), dispositivoId)) {
            revogar(sid, MotivoDaRevogacao.OUTRO_APARELHO, agora);
            return new Recusada(MotivoDaRevogacao.OUTRO_APARELHO);
        }
        List<Sucessor> sucessores = jdbc.query("""
                SELECT id, vence_em, usado_em FROM corona.tokens_de_renovacao WHERE anterior_id = ?""",
                (rs, i) -> new Sucessor(rs.getObject("id", UUID.class), instante(rs, "vence_em"), instante(rs, "usado_em")),
                token.id());
        if (!sucessores.isEmpty()) {
            return reapresentacao(sid, familia, token, sucessores.get(0), agora);
        }
        if (!agora.isBefore(token.venceEm())) {
            return new Recusada(null);
        }
        UUID novo = UuidV7.gerar();
        String valorNovo = segredo.derivado(novo);
        Instant vence = agora.plus(propriedades.validade());
        inserirToken(novo, sid, token.id(), valorNovo, agora, vence);
        jdbc.update("UPDATE corona.tokens_de_renovacao SET usado_em = ? WHERE id = ?", Timestamp.from(agora), token.id());
        jdbc.update("UPDATE corona.sessoes SET usada_em = ? WHERE id = ?", Timestamp.from(agora), sid);
        return new Renovada(sid, familia.usuarioId(), familia.dispositivoId(), valorNovo, vence);
    }

    /** O token anterior voltou: dentro da janela e pela primeira vez, devolve o mesmo sucessor; senão, revoga. */
    private Renovacao reapresentacao(UUID sid, Familia familia, Token token, Sucessor sucessor, Instant agora) {
        if (sucessor.usadoEm() != null) {
            return revogar(sid, MotivoDaRevogacao.ANTERIOR_DEPOIS_DO_SUCESSOR, agora);
        }
        if (token.reapresentadoEm() != null) {
            return revogar(sid, MotivoDaRevogacao.SEGUNDA_REAPRESENTACAO, agora);
        }
        if (agora.isAfter(token.usadoEm().plus(propriedades.janelaDeTolerancia()))) {
            return revogar(sid, MotivoDaRevogacao.REUSO_FORA_DA_JANELA, agora);
        }
        jdbc.update("UPDATE corona.tokens_de_renovacao SET reapresentado_em = ? WHERE id = ?",
                Timestamp.from(agora), token.id());
        return new Renovada(sid, familia.usuarioId(), familia.dispositivoId(), segredo.derivado(sucessor.id()),
                sucessor.venceEm());
    }

    /** A família já revogada a que um token de renovação pertence (tarefa 5.8, quarentena). */
    public record FamiliaRevogada(UUID usuarioId, UUID dispositivoId, MotivoDaRevogacao motivo, Instant revogadaEm) {
    }

    /**
     * Só leitura: a família do token de renovação (de qualquer geração) quando ela já foi revogada. Vazio se o token não
     * existe ou a família ainda vale. Não renova, não revoga e não olha a validade do token.
     */
    @Transactional(readOnly = true)
    public Optional<FamiliaRevogada> familiaRevogadaPeloToken(String valor) {
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT s.usuario_id, s.dispositivo_id, s.motivo_da_revogacao, s.revogada_em
                  FROM corona.tokens_de_renovacao t JOIN corona.sessoes s ON s.id = t.sessao_id
                 WHERE t.hash = ? AND s.revogada_em IS NOT NULL""",
                (rs, i) -> new FamiliaRevogada(rs.getObject("usuario_id", UUID.class),
                        rs.getObject("dispositivo_id", UUID.class), motivo(rs.getString("motivo_da_revogacao")),
                        instante(rs, "revogada_em")),
                SegredoDeRenovacao.hash(valor)).stream().findFirst();
    }

    /** A pessoa saiu: revoga a família do token. */
    @Transactional
    public void sair(UUID sid) {
        if (sid != null) {
            revogar(sid, MotivoDaRevogacao.SAIDA, agora());
        }
    }

    /** Redefinição ou troca de senha: revoga todas as famílias abertas do usuário. Devolve quantas. */
    @Transactional
    public int revogarTodasDoUsuario(UUID usuarioId, MotivoDaRevogacao motivo) {
        return jdbc.update("""
                UPDATE corona.sessoes SET revogada_em = ?, motivo_da_revogacao = ?
                 WHERE usuario_id = ? AND revogada_em IS NULL""",
                Timestamp.from(agora()), motivo.name(), usuarioId);
    }

    /** A dona bloqueou o computador: revoga todas as famílias abertas dele (tarefa 5.3). Devolve quantas. */
    @Transactional
    public int revogarTodasDoAparelho(UUID dispositivoId, MotivoDaRevogacao motivo) {
        return jdbc.update("""
                UPDATE corona.sessoes SET revogada_em = ?, motivo_da_revogacao = ?
                 WHERE dispositivo_id = ? AND revogada_em IS NULL""",
                Timestamp.from(agora()), motivo.name(), dispositivoId);
    }

    /** A família, para a conferência de cada requisição (ADR-0004, item 1). */
    @Transactional(readOnly = true)
    public Optional<SituacaoDaSessao> situacao(UUID sid) {
        if (sid == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT usuario_id, tipo, dispositivo_id, revogada_em IS NOT NULL AS revogada, motivo_da_revogacao
                  FROM corona.sessoes WHERE id = ?""",
                (rs, i) -> new SituacaoDaSessao(rs.getObject("usuario_id", UUID.class),
                        TipoDeSessao.valueOf(rs.getString("tipo")), rs.getObject("dispositivo_id", UUID.class),
                        rs.getBoolean("revogada"), motivo(rs.getString("motivo_da_revogacao"))),
                sid).stream().findFirst();
    }

    private Recusada revogar(UUID sid, MotivoDaRevogacao motivo, Instant agora) {
        int revogadas = jdbc.update("""
                UPDATE corona.sessoes SET revogada_em = ?, motivo_da_revogacao = ?
                 WHERE id = ? AND revogada_em IS NULL""",
                Timestamp.from(agora), motivo.name(), sid);
        if (revogadas > 0 && motivo != MotivoDaRevogacao.SAIDA) {
            LOG.warn("Sessão {} revogada: {}", sid, motivo);
        }
        return new Recusada(motivo);
    }

    private void inserirToken(UUID id, UUID sid, UUID anterior, String valor, Instant agora, Instant vence) {
        jdbc.update("""
                INSERT INTO corona.tokens_de_renovacao (id, sessao_id, anterior_id, hash, criado_em, vence_em)
                VALUES (?, ?, ?, ?, ?, ?)""",
                id, sid, anterior, SegredoDeRenovacao.hash(valor), Timestamp.from(agora), Timestamp.from(vence));
    }

    /** Microssegundos: a precisão do {@code timestamptz}, para o que é gravado ser o que é comparado. */
    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static MotivoDaRevogacao motivo(String texto) {
        return texto == null ? null : MotivoDaRevogacao.valueOf(texto);
    }

    private record Familia(UUID usuarioId, UUID dispositivoId, boolean revogada, MotivoDaRevogacao motivo) {
    }

    private record Token(UUID id, Instant venceEm, Instant usadoEm, Instant reapresentadoEm) {
    }

    private record Sucessor(UUID id, Instant venceEm, Instant usadoEm) {
    }
}
