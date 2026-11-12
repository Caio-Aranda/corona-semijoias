package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Contexto;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Lida;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Envios com problema" (tarefa 5.5; ADR-0013, item 2; §19.3): as operações do Corona Vendas recusadas ou em conflito,
 * ainda sem decisão da dona. Ela escolhe <strong>Tentar de novo</strong> (depois de corrigir a causa: a operação guardada
 * passa outra vez pelo mesmo serviço, com o mesmo id) ou <strong>Descartar</strong> (com motivo). Nada é apagado; tudo
 * vai para a auditoria. A tela é da tarefa 5.10.
 */
@Service
public class EnviosComProblema {

    /** Um envio com problema, para a dona. {@code dados} vazio depois de 90 dias. */
    public record EnvioComProblema(UUID id, String tipo, String resultado, String codigo, String mensagem, Instant recebidoEm,
            UUID vendedorId, String vendedorNome, UUID dispositivoId, Instant horaDeclarada, JsonNode dados) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final OperacoesDoVendedor operacoes;
    private final ServicoDeAuditoria auditoria;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    public EnviosComProblema(JdbcTemplate jdbc, JsonMapper json, OperacoesDoVendedor operacoes, ServicoDeAuditoria auditoria,
            PlatformTransactionManager transacoes, Clock relogio) {
        this.jdbc = jdbc;
        this.json = json;
        this.operacoes = operacoes;
        this.auditoria = auditoria;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.relogio = relogio;
    }

    /** Os que esperam a dona, do mais antigo para o mais novo. */
    public List<EnvioComProblema> listar() {
        return jdbc.query("""
                SELECT s.id, s.tipo, s.resultado, s.codigo, s.mensagem, s.recebido_em, s.vendedor_id, v.nome, s.dispositivo_id,
                       s.hora_declarada, s.dados::text AS dados
                  FROM corona.sync_log s JOIN corona.vendedores v ON v.id = s.vendedor_id
                 WHERE s.resultado IN ('RECUSADO', 'CONFLITO') AND s.resolucao IS NULL
                 ORDER BY s.recebido_em, s.id""", (rs, i) -> new EnvioComProblema(rs.getObject("id", UUID.class),
                rs.getString("tipo"), rs.getString("resultado"), rs.getString("codigo"), rs.getString("mensagem"),
                instante(rs.getObject("recebido_em", OffsetDateTime.class)), rs.getObject("vendedor_id", UUID.class),
                rs.getString("nome"), rs.getObject("dispositivo_id", UUID.class),
                instante(rs.getObject("hora_declarada", OffsetDateTime.class)),
                rs.getString("dados") == null ? null : json.readTree(rs.getString("dados"))));
    }

    /**
     * Passa a operação guardada outra vez pelo mesmo serviço (mesmo id, mesma hora corrigida). Deu certo: fica ACEITO e
     * resolvido. Deu errado de novo: continua esperando, com o motivo novo, e a dona recebe o erro do serviço.
     */
    public EnvioComProblema tentarDeNovo(UUID id, UUID donaId) {
        Map<String, Object> envio = travarPendente(id);
        if (!"RECUSADO".equals(envio.get("resultado"))) {
            // Conflito é o mesmo id com outro conteúdo (ou CPF repetido): tentar de novo daria o mesmo. A dona resolve à mão.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ENVIO_JA_RESOLVIDO);
        }
        if (envio.get("dados") == null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ENVIO_SEM_DADOS);
        }
        UUID usuario = (UUID) envio.get("usuario_id");
        Lida lida = operacoes.ler(id, (String) envio.get("tipo"), ((Number) envio.get("formato")).intValue(),
                json.readTree(envio.get("dados").toString()));
        Number diferenca = (Number) envio.get("diferenca_do_relogio_ms");
        Contexto ctx = new Contexto((UUID) envio.get("vendedor_id"), usuario, (UUID) envio.get("dispositivo_id"), agora(),
                diferenca == null ? 0 : diferenca.longValue(), null, false, true);
        boolean[] aplicando = {false};
        try {
            transacao.executeWithoutResult(status -> {
                travarPendente(id); // de novo, agora com a trava até o fim: duas tentativas ao mesmo tempo esperam
                aplicando[0] = true;
                OperacoesDoVendedor.Aplicada a = operacoes.aplicar(lida, ctx);
                jdbc.update("""
                        UPDATE corona.sync_log SET resultado = 'ACEITO', codigo = NULL, mensagem = NULL, hora_corrigida = ?,
                               resolucao = 'TENTADO_DE_NOVO', resolvido_em = ?, resolvido_por = ? WHERE id = ?""",
                        a.horaCorrigida() == null ? null : Timestamp.from(a.horaCorrigida()), Timestamp.from(agora()), donaId, id);
                auditoria.registrar(Ator.usuario(donaId), "ENVIO_TENTADO_DE_NOVO", "sync_log", id,
                        Map.of("resultado", "RECUSADO"), Map.of("resultado", "ACEITO"));
            });
        } catch (ErroDaApi e) {
            if (aplicando[0]) {
                // Recusado de novo pelo serviço: continua esperando a dona, com o motivo novo.
                jdbc.update("UPDATE corona.sync_log SET codigo = ?, mensagem = ? WHERE id = ? AND resolucao IS NULL",
                        e.codigo().name(), e.getBody().getDetail(), id);
            }
            throw e;
        }
        return ver(id);
    }

    /** Descarta com motivo: a operação não entra, e a decisão fica registrada. */
    public EnvioComProblema descartar(UUID id, String motivo, UUID donaId) {
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de descartar este envio.");
        }
        if (limpo.length() > 500) {
            throw Validacoes.recusar("motivo", "Escreva o motivo com até 500 letras.");
        }
        transacao.executeWithoutResult(status -> {
            Map<String, Object> envio = travarPendente(id);
            jdbc.update("""
                    UPDATE corona.sync_log SET resolucao = 'DESCARTADO', resolvido_em = ?, resolvido_por = ?, motivo_da_resolucao = ?
                     WHERE id = ?""", Timestamp.from(agora()), donaId, limpo, id);
            Map<String, Object> depois = new LinkedHashMap<>();
            depois.put("resolucao", "DESCARTADO");
            depois.put("motivo", limpo);
            auditoria.registrar(Ator.usuario(donaId), "ENVIO_DESCARTADO", "sync_log", id,
                    Map.of("resultado", envio.get("resultado").toString()), depois);
        });
        return ver(id);
    }

    private Map<String, Object> travarPendente(UUID id) {
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT tipo, formato, resultado, resolucao, dados::text AS dados, usuario_id, vendedor_id, dispositivo_id,
                       diferenca_do_relogio_ms
                  FROM corona.sync_log WHERE id = ? FOR UPDATE""", id);
        if (achado.isEmpty() || "ACEITO".equals(achado.get(0).get("resultado"))
                || "JA_RECEBIDO".equals(achado.get(0).get("resultado"))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ENVIO_NAO_ENCONTRADO);
        }
        if (achado.get(0).get("resolucao") != null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ENVIO_JA_RESOLVIDO);
        }
        return achado.get(0);
    }

    private EnvioComProblema ver(UUID id) {
        return jdbc.query("""
                SELECT s.id, s.tipo, s.resultado, s.codigo, s.mensagem, s.recebido_em, s.vendedor_id, v.nome, s.dispositivo_id,
                       s.hora_declarada, s.dados::text AS dados
                  FROM corona.sync_log s JOIN corona.vendedores v ON v.id = s.vendedor_id WHERE s.id = ?""",
                (rs, i) -> new EnvioComProblema(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getString("resultado"),
                        rs.getString("codigo"), rs.getString("mensagem"), instante(rs.getObject("recebido_em", OffsetDateTime.class)),
                        rs.getObject("vendedor_id", UUID.class), rs.getString("nome"), rs.getObject("dispositivo_id", UUID.class),
                        instante(rs.getObject("hora_declarada", OffsetDateTime.class)),
                        rs.getString("dados") == null ? null : json.readTree(rs.getString("dados"))), id).get(0);
    }

    private static Instant instante(OffsetDateTime v) {
        return v == null ? null : v.toInstant();
    }

    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
