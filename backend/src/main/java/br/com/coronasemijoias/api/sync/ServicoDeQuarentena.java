package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.FamiliaRevogada;
import br.com.coronasemijoias.api.sync.ServicoDeEnvio.OperacaoEnviada;
import br.com.coronasemijoias.api.sync.ServicoDeEnvio.ResultadoDaOperacao;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quarentena (tarefa 5.8; ADR-0013, item 14): o computador bloqueado (ou do vendedor desativado) manda o que tinha
 * sem enviar, e o servidor guarda <strong>sem aplicar</strong>. Quem prova ser aquele computador é o token de renovação
 * já revogado por bloqueio ou desativação, há no máximo 30 dias, e o identificador do computador. Qualquer outra
 * situação responde o mesmo 401 de "sessão terminada", sem revelar nada.
 *
 * <p>A dona aprova (a operação passa pelo mesmo serviço do envio, com {@code tentativaDaDona}) ou descarta.
 */
@Service
public class ServicoDeQuarentena {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDeQuarentena.class);

    /** Até quando, depois da revogação, o computador ainda pode guardar o que tinha (decisão da orquestradora). */
    public static final Duration PRAZO_APOS_A_REVOGACAO = Duration.ofDays(30);

    /** Teto de envios guardados por computador, para a rota aberta não virar depósito. */
    static final int LIMITE_POR_COMPUTADOR = 5000;

    public record Pedido(String tokenDeRenovacao, UUID dispositivoId, Instant horaLocal, List<OperacaoEnviada> operacoes) {
    }

    /** {@code resultado}: {@code GUARDADO}, {@code JA_GUARDADO} ou {@code RECUSADO}. */
    public record ResultadoGuardado(UUID operacaoId, String resultado, String codigo, String mensagem) {
    }

    private final ServicoDeSessoes sessoes;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final OperacoesDoVendedor operacoes;
    private final ServicoDeEnvio envio;
    private final ServicoDeAuditoria auditoria;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    public ServicoDeQuarentena(ServicoDeSessoes sessoes, JdbcTemplate jdbc, JsonMapper json, OperacoesDoVendedor operacoes,
            ServicoDeEnvio envio, ServicoDeAuditoria auditoria, PlatformTransactionManager transacoes, Clock relogio) {
        this.sessoes = sessoes;
        this.jdbc = jdbc;
        this.json = json;
        this.operacoes = operacoes;
        this.envio = envio;
        this.auditoria = auditoria;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.relogio = relogio;
    }

    public List<ResultadoGuardado> guardar(Pedido pedido) {
        if (pedido == null) {
            throw negado();
        }
        FamiliaRevogada familia = sessoes.familiaRevogadaPeloToken(pedido.tokenDeRenovacao())
                .orElseThrow(ServicoDeQuarentena::negado);
        Instant agora = agora();
        boolean aceita = familia.motivo() != null && familia.motivo().apagaOsDados()
                && pedido.dispositivoId() != null && pedido.dispositivoId().equals(familia.dispositivoId())
                && !agora.isAfter(familia.revogadaEm().plus(PRAZO_APOS_A_REVOGACAO));
        if (!aceita) {
            throw negado();
        }
        List<OperacaoEnviada> recebidas = pedido.operacoes() == null ? List.of() : pedido.operacoes();
        if (recebidas.size() > ServicoDeEnvio.MAXIMO_DE_OPERACOES) {
            throw new ErroDaApi(HttpStatus.CONTENT_TOO_LARGE, CodigoErro.LOTE_GRANDE_DEMAIS);
        }
        UUID vendedorId = jdbc.query("SELECT vendedor_id FROM corona.usuarios WHERE id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), familia.usuarioId()).stream().filter(Objects::nonNull).findFirst()
                .orElseThrow(ServicoDeQuarentena::negado);
        long diferenca = pedido.horaLocal() == null ? 0 : Duration.between(agora, pedido.horaLocal()).toMillis();
        List<ResultadoGuardado> resultados = new ArrayList<>();
        for (OperacaoEnviada op : recebidas) {
            resultados.add(guardarUma(op, familia, vendedorId, pedido.dispositivoId(), pedido.horaLocal(), diferenca, agora));
        }
        return resultados;
    }

    private ResultadoGuardado guardarUma(OperacaoEnviada op, FamiliaRevogada familia, UUID vendedorId, UUID dispositivo,
            Instant horaLocal, long diferenca, Instant agora) {
        if (op == null || op.operacaoId() == null) {
            return recusada(null, CodigoErro.PEDIDO_INVALIDO);
        }
        String resumo;
        try {
            resumo = operacoes.ler(op.operacaoId(), op.tipo(), op.formato() == null ? 0 : op.formato(), op.dados()).resumo();
        } catch (OperacoesDoVendedor.OperacaoIlegivel e) {
            return recusada(op.operacaoId(), CodigoErro.PEDIDO_INVALIDO);
        }
        Optional<String[]> anterior = anterior(op.operacaoId());
        if (anterior.isPresent()) {
            return mesmoEnvio(op.operacaoId(), anterior.get(), dispositivo, resumo);
        }
        Long guardados = jdbc.queryForObject("SELECT count(*) FROM corona.envios_em_quarentena WHERE dispositivo_id = ?",
                Long.class, dispositivo);
        if (guardados != null && guardados >= LIMITE_POR_COMPUTADOR) {
            return recusada(op.operacaoId(), CodigoErro.LOTE_GRANDE_DEMAIS);
        }
        try {
            transacao.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO corona.envios_em_quarentena (id, dispositivo_id, usuario_id, vendedor_id, tipo, formato, resumo,
                            dados, criado_em_local, ultimo_contato_servidor, hora_local_do_envio, diferenca_do_relogio_ms,
                            recebido_em)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""",
                        op.operacaoId(), dispositivo, familia.usuarioId(), vendedorId, op.tipo(),
                        op.formato() == null ? 1 : op.formato(), resumo, json.writeValueAsString(op.dados()),
                        paraTimestamp(op.criadoEmLocal()), paraTimestamp(op.ultimoContatoServidor()), paraTimestamp(horaLocal),
                        diferenca, Timestamp.from(agora));
                auditoria.registrar(Ator.usuario(familia.usuarioId()), "QUARENTENA_GUARDADA", "envios_em_quarentena",
                        op.operacaoId(), null, Map.of("tipo", op.tipo(), "dispositivo_id", dispositivo.toString()));
            });
            return new ResultadoGuardado(op.operacaoId(), "GUARDADO", null, null);
        } catch (DuplicateKeyException e) {
            return anterior(op.operacaoId()).map(a -> mesmoEnvio(op.operacaoId(), a, dispositivo, resumo))
                    .orElseGet(() -> recusada(op.operacaoId(), CodigoErro.ERRO_INTERNO));
        }
    }

    private ResultadoGuardado mesmoEnvio(UUID id, String[] anterior, UUID dispositivo, String resumo) {
        if (anterior[0].equals(dispositivo.toString()) && anterior[1].equals(resumo)) {
            return new ResultadoGuardado(id, "JA_GUARDADO", null, null);
        }
        // Mesmo id com outro conteúdo (ou de outro computador): nada é sobrescrito.
        return recusada(id, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
    }

    private Optional<String[]> anterior(UUID id) {
        return jdbc.query("SELECT dispositivo_id::text, resumo FROM corona.envios_em_quarentena WHERE id = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, id).stream().findFirst();
    }

    private static ResultadoGuardado recusada(UUID id, CodigoErro codigo) {
        return new ResultadoGuardado(id, "RECUSADO", codigo.name(), codigo.mensagem());
    }

    private static ErroDaApi negado() {
        return new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA);
    }

    /**
     * A dona aprova: a operação passa pelo mesmo serviço do envio (as mesmas regras, o mesmo {@code sync_log}) e a linha
     * da quarentena guarda o que aconteceu. Uma recusa do serviço não desfaz a decisão: ela aparece nos envios com
     * problema, onde a dona resolve.
     */
    public ResultadoDaOperacao aprovar(UUID id, UUID donaId) {
        Linha linha = pendente(id);
        JsonNode dados = json.readTree(linha.dados());
        OperacaoEnviada op = new OperacaoEnviada(id, linha.tipo(), linha.formato(), linha.criadoEmLocal(),
                linha.ultimoContato(), dados);
        OperacoesDoVendedor.Contexto contexto = new OperacoesDoVendedor.Contexto(linha.vendedorId(), linha.usuarioId(),
                linha.dispositivoId(), agora(), linha.diferenca(), linha.ultimoContato(), false, true);
        ResultadoDaOperacao resultado = envio.processar(op, contexto);
        if (resultado.resultado() == ServicoDeEnvio.Resultado.NAO_PROCESSADO) {
            // Erro passageiro: a linha continua guardada, a dona tenta de novo.
            throw new ErroDaApi(HttpStatus.SERVICE_UNAVAILABLE, CodigoErro.ERRO_INTERNO);
        }
        transacao.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE corona.envios_em_quarentena SET situacao = 'APROVADO', decidido_em = ?, decidido_por = ?,
                        resultado_da_aprovacao = ? WHERE id = ? AND situacao = 'GUARDADO'""",
                    Timestamp.from(agora()), donaId, resultado.resultado().name(), id);
            auditoria.registrar(Ator.usuario(donaId), "QUARENTENA_APROVADA", "envios_em_quarentena", id, null,
                    Map.of("resultado", resultado.resultado().name()));
        });
        LOG.info("Quarentena {} aprovada pela dona: {}", id, resultado.resultado());
        return resultado;
    }

    /** A dona descarta: nada é aplicado. */
    public void descartar(UUID id, UUID donaId, String motivo) {
        pendente(id);
        String curto = motivo == null ? null : motivo.substring(0, Math.min(500, motivo.length()));
        transacao.executeWithoutResult(status -> {
            int n = jdbc.update("""
                    UPDATE corona.envios_em_quarentena SET situacao = 'DESCARTADO', decidido_em = ?, decidido_por = ?,
                        motivo_do_descarte = ? WHERE id = ? AND situacao = 'GUARDADO'""",
                    Timestamp.from(agora()), donaId, curto, id);
            if (n != 1) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PEDIDO_INVALIDO);
            }
            auditoria.registrar(Ator.usuario(donaId), "QUARENTENA_DESCARTADA", "envios_em_quarentena", id, null,
                    curto == null ? Map.of() : Map.of("motivo", curto));
        });
    }

    private record Linha(UUID vendedorId, UUID usuarioId, UUID dispositivoId, String tipo, int formato, String dados,
            Instant criadoEmLocal, Instant ultimoContato, long diferenca) {
    }

    private Linha pendente(UUID id) {
        return jdbc.query("""
                SELECT vendedor_id, usuario_id, dispositivo_id, tipo, formato, dados::text, criado_em_local,
                       ultimo_contato_servidor, coalesce(diferenca_do_relogio_ms, 0)
                  FROM corona.envios_em_quarentena WHERE id = ? AND situacao = 'GUARDADO'""",
                (rs, i) -> new Linha(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getInt(5), rs.getString(6), instante(rs.getTimestamp(7)),
                        instante(rs.getTimestamp(8)), rs.getLong(9)),
                id).stream().findFirst().orElseThrow(() -> new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.PEDIDO_INVALIDO));
    }

    private static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp paraTimestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
