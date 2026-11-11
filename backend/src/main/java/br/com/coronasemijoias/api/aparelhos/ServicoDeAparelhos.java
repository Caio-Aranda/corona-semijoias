package br.com.coronasemijoias.api.aparelhos;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.sessoes.MotivoDaRevogacao;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Os computadores do Corona Vendas (tarefa 5.3; ADR-0013, itens 13 e 14; ESPECIFICACAO §6.15 e §11).
 *
 * <ul>
 *   <li>O login registra o computador: o primeiro do vendedor nasce {@code ATIVO}; os seguintes, {@code
 *       AGUARDANDO_LIBERACAO}. Computador de outra pessoa ou bloqueado não entra.</li>
 *   <li>A dona libera ("Trocar computador"): o antigo ativo vira {@code SUBSTITUIDO} e as maletas abertas dele passam
 *       para o novo. Substitui a liberação por maleta da tarefa 4.3.</li>
 *   <li>A dona bloqueia (perdido ou roubado): as sessões do computador caem com o motivo próprio e a próxima chamada
 *       recebe a ordem de apagar os dados.</li>
 * </ul>
 *
 * <p>Tudo com auditoria. Um computador ativo por pessoa é garantido também pelo índice único do banco.
 */
@Service
public class ServicoDeAparelhos {

    private static final int LIMITE_DO_MOTIVO = 500;
    private static final int LIMITE_DA_VERSAO = 40;

    /** O que a dona vê de cada computador do vendedor. */
    public record AparelhoDoVendedor(
            UUID id, SituacaoDoAparelho situacao, Instant primeiroContatoEm, Instant ultimoContatoEm, String versaoApp,
            Instant ultimoPullEm, Instant ultimoPushEm, Instant ultimoEnvioCompletoEm, Integer aguardandoEnvio,
            Instant aguardandoInformadoEm, Instant liberadoEm, Instant substituidoEm, UUID substituidoPor,
            Instant bloqueadoEm, String motivoBloqueio) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeSessoes sessoes;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    public ServicoDeAparelhos(JdbcTemplate jdbc, ServicoDeSessoes sessoes, ServicoDeAuditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.sessoes = sessoes;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * Conferência do login, antes de abrir a sessão: o computador é de outra pessoa ou foi bloqueado? Vazio quando
     * pode entrar (inclusive computador novo, que ainda não existe).
     */
    @Transactional(readOnly = true)
    public Optional<CodigoErro> recusaNoLogin(UUID usuarioId, UUID dispositivoId) {
        if (dispositivoId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT usuario_id, situacao FROM corona.dispositivos WHERE id = ?", (rs, i) -> {
            if (!usuarioId.equals(rs.getObject("usuario_id", UUID.class))) {
                return CodigoErro.APARELHO_DE_OUTRA_PESSOA;
            }
            return SituacaoDoAparelho.BLOQUEADO.name().equals(rs.getString("situacao")) ? CodigoErro.APARELHO_BLOQUEADO : null;
        }, dispositivoId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * Registra o computador no login (na transação do login) ou atualiza o último contato. O primeiro computador da
     * pessoa nasce ativo; os seguintes esperam a dona. Devolve a situação.
     */
    @Transactional
    public SituacaoDoAparelho registrarNoLogin(UUID usuarioId, UUID dispositivoId, String versaoDoApp) {
        // Um login por vez da mesma pessoa decide "primeiro ou segundo computador" (o índice único é a segunda barreira).
        jdbc.queryForList("SELECT id FROM corona.usuarios WHERE id = ? FOR UPDATE", UUID.class, usuarioId);
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT usuario_id, situacao FROM corona.dispositivos WHERE id = ? FOR UPDATE", dispositivoId);
        Instant agora = agora();
        String versao = versao(versaoDoApp);
        if (!existente.isEmpty()) {
            if (!usuarioId.equals(existente.get(0).get("usuario_id"))) {
                throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.APARELHO_DE_OUTRA_PESSOA);
            }
            jdbc.update("UPDATE corona.dispositivos SET ultimo_contato_em = ?, versao_app = coalesce(?, versao_app) WHERE id = ?",
                    Timestamp.from(agora), versao, dispositivoId);
            return SituacaoDoAparelho.valueOf((String) existente.get(0).get("situacao"));
        }
        long outros = jdbc.queryForObject("SELECT count(*) FROM corona.dispositivos WHERE usuario_id = ?", Long.class, usuarioId);
        SituacaoDoAparelho situacao = outros == 0 ? SituacaoDoAparelho.ATIVO : SituacaoDoAparelho.AGUARDANDO_LIBERACAO;
        jdbc.update("""
                INSERT INTO corona.dispositivos (id, usuario_id, situacao, primeiro_contato_em, ultimo_contato_em, versao_app, liberado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                dispositivoId, usuarioId, situacao.name(), Timestamp.from(agora), Timestamp.from(agora), versao,
                situacao == SituacaoDoAparelho.ATIVO ? Timestamp.from(agora) : null);
        return situacao;
    }

    /** Renovação da sessão: guarda o último contato e a versão do app. */
    @Transactional
    public void registrarContato(UUID dispositivoId, String versaoDoApp) {
        if (dispositivoId != null) {
            jdbc.update("UPDATE corona.dispositivos SET ultimo_contato_em = ?, versao_app = coalesce(?, versao_app) WHERE id = ?",
                    Timestamp.from(agora()), versao(versaoDoApp), dispositivoId);
        }
    }

    /** O computador recebeu dados (pull, tarefa 5.4): guarda a hora, para a dona ver o último contato. */
    @Transactional
    public void registrarPull(UUID dispositivoId) {
        if (dispositivoId != null) {
            Timestamp agora = Timestamp.from(agora());
            jdbc.update("UPDATE corona.dispositivos SET ultimo_pull_em = ?, ultimo_contato_em = ? WHERE id = ?",
                    agora, agora, dispositivoId);
        }
    }
    /** A situação do computador desta pessoa (vazio se o computador não é dela ou não existe). */
    @Transactional(readOnly = true)
    public Optional<SituacaoDoAparelho> situacao(UUID dispositivoId, UUID usuarioId) {
        if (dispositivoId == null || usuarioId == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT situacao FROM corona.dispositivos WHERE id = ? AND usuario_id = ?", String.class,
                dispositivoId, usuarioId).stream().map(SituacaoDoAparelho::valueOf).findFirst();
    }

    /** Os computadores de um vendedor (de todos os usuários ligados a ele), do mais novo para o mais antigo. */
    @Transactional(readOnly = true)
    public List<AparelhoDoVendedor> doVendedor(UUID vendedorId) {
        if (jdbc.queryForList("SELECT id FROM corona.vendedores WHERE id = ?", UUID.class, vendedorId).isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        return jdbc.query("""
                SELECT d.* FROM corona.dispositivos d JOIN corona.usuarios u ON u.id = d.usuario_id
                 WHERE u.vendedor_id = ? ORDER BY d.primeiro_contato_em DESC, d.id""", ServicoDeAparelhos::aparelho, vendedorId);
    }

    /**
     * "Trocar computador": libera o computador que espera a dona. O ativo de antes vira trocado (ainda envia o que
     * guardou) e as maletas abertas que estavam nele passam para o novo. Liberar o que já está em uso não muda nada.
     */
    @Transactional
    public AparelhoDoVendedor liberar(UUID dispositivoId, UUID donaId) {
        Map<String, Object> alvo = travar(dispositivoId);
        SituacaoDoAparelho situacao = SituacaoDoAparelho.valueOf((String) alvo.get("situacao"));
        if (situacao == SituacaoDoAparelho.ATIVO) {
            return ler(dispositivoId);
        }
        if (situacao != SituacaoDoAparelho.AGUARDANDO_LIBERACAO) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.APARELHO_NAO_PODE_SER_LIBERADO);
        }
        UUID usuarioId = (UUID) alvo.get("usuario_id");
        Instant agora = agora();
        List<UUID> ativos = jdbc.queryForList(
                "SELECT id FROM corona.dispositivos WHERE usuario_id = ? AND situacao = 'ATIVO' FOR UPDATE", UUID.class, usuarioId);
        for (UUID antigo : ativos) {
            jdbc.update("""
                    UPDATE corona.dispositivos SET situacao = 'SUBSTITUIDO', substituido_em = ?, substituido_por = ?
                     WHERE id = ?""", Timestamp.from(agora), dispositivoId, antigo);
            auditoria.registrar(Ator.usuario(donaId), "APARELHO_SUBSTITUIDO", "dispositivos", antigo,
                    Map.of("situacao", "ATIVO"), Map.of("situacao", "SUBSTITUIDO", "substituido_por", dispositivoId.toString()));
        }
        jdbc.update("UPDATE corona.dispositivos SET situacao = 'ATIVO', liberado_em = ? WHERE id = ?",
                Timestamp.from(agora), dispositivoId);
        // As maletas com o vendedor que estavam ligadas a outro computador desta pessoa passam para o novo. As que ainda
        // não foram conferidas (sem computador) ficam livres: o novo computador as confere. Fase 7: incluir EM_ACERTO.
        UUID vendedorId = (UUID) alvo.get("vendedor_id");
        int maletas = vendedorId == null ? 0 : jdbc.update("""
                UPDATE corona.remessas SET dispositivo_id = ?
                 WHERE vendedor_id = ? AND deleted_at IS NULL AND status IN ('ENVIADA', 'CONFERIDA')
                   AND dispositivo_id IS NOT NULL AND dispositivo_id <> ?
                   AND dispositivo_id IN (SELECT id FROM corona.dispositivos WHERE usuario_id = ?)""",
                dispositivoId, vendedorId, dispositivoId, usuarioId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", "ATIVO");
        depois.put("maletas_passadas", maletas);
        auditoria.registrar(Ator.usuario(donaId), "APARELHO_LIBERADO", "dispositivos", dispositivoId,
                Map.of("situacao", situacao.name()), depois);
        return ler(dispositivoId);
    }

    /**
     * "Bloquear computador" (perdido ou roubado): sem volta. As sessões dele caem, e a próxima chamada (renovar, enviar,
     * receber) recebe 403 com a ordem de apagar os dados. Bloquear de novo não muda nada.
     */
    @Transactional
    public AparelhoDoVendedor bloquear(UUID dispositivoId, String motivo, UUID donaId) {
        String texto = motivo == null || motivo.isBlank() ? null : motivo.strip();
        if (texto != null && texto.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Escreva o motivo com até 500 letras.");
        }
        Map<String, Object> alvo = travar(dispositivoId);
        String antes = (String) alvo.get("situacao");
        if (SituacaoDoAparelho.BLOQUEADO.name().equals(antes)) {
            return ler(dispositivoId);
        }
        jdbc.update("UPDATE corona.dispositivos SET situacao = 'BLOQUEADO', bloqueado_em = ?, motivo_bloqueio = ? WHERE id = ?",
                Timestamp.from(agora()), texto, dispositivoId);
        int sessoesRevogadas = sessoes.revogarTodasDoAparelho(dispositivoId, MotivoDaRevogacao.APARELHO_BLOQUEADO);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", "BLOQUEADO");
        depois.put("motivo", texto);
        depois.put("sessoes_encerradas", sessoesRevogadas);
        auditoria.registrar(Ator.usuario(donaId), "APARELHO_BLOQUEADO", "dispositivos", dispositivoId,
                Map.of("situacao", antes), depois);
        return ler(dispositivoId);
    }

    private Map<String, Object> travar(UUID dispositivoId) {
        List<Map<String, Object>> linhas = jdbc.queryForList("""
                SELECT d.usuario_id, d.situacao, u.vendedor_id FROM corona.dispositivos d
                  JOIN corona.usuarios u ON u.id = d.usuario_id
                 WHERE d.id = ? FOR UPDATE OF d""", dispositivoId);
        if (linhas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.APARELHO_NAO_ENCONTRADO);
        }
        return linhas.get(0);
    }

    private AparelhoDoVendedor ler(UUID dispositivoId) {
        return jdbc.queryForObject("SELECT * FROM corona.dispositivos WHERE id = ?", ServicoDeAparelhos::aparelho, dispositivoId);
    }

    private static AparelhoDoVendedor aparelho(ResultSet rs, int linha) throws SQLException {
        Number aguardando = (Number) rs.getObject("aguardando_envio");
        return new AparelhoDoVendedor(rs.getObject("id", UUID.class), SituacaoDoAparelho.valueOf(rs.getString("situacao")),
                instante(rs, "primeiro_contato_em"), instante(rs, "ultimo_contato_em"), rs.getString("versao_app"),
                instante(rs, "ultimo_pull_em"), instante(rs, "ultimo_push_em"), instante(rs, "ultimo_envio_completo_em"),
                aguardando == null ? null : aguardando.intValue(), instante(rs, "aguardando_informado_em"),
                instante(rs, "liberado_em"), instante(rs, "substituido_em"), rs.getObject("substituido_por", UUID.class),
                instante(rs, "bloqueado_em"), rs.getString("motivo_bloqueio"));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    /** A versão que o app mandou no cabeçalho, só se couber (texto livre do cliente, nunca confiável). */
    private static String versao(String versaoDoApp) {
        if (versaoDoApp == null || versaoDoApp.isBlank()) {
            return null;
        }
        String limpa = versaoDoApp.strip();
        return limpa.length() > LIMITE_DA_VERSAO ? limpa.substring(0, LIMITE_DA_VERSAO) : limpa;
    }

    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
