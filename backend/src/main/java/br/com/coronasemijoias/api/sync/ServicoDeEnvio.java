package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.aparelhos.ServicoDeAparelhos;
import br.com.coronasemijoias.api.aparelhos.SituacaoDoAparelho;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Aplicada;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Contexto;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Lida;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.OperacaoIlegivel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O envio das operações guardadas no Corona Vendas (push; tarefa 5.5; ADR-0013, itens 2 e 11).
 *
 * <ul>
 *   <li>Um envio por vez por computador (trava na memória, espera de no máximo 5 s; depois 429 com Retry-After).</li>
 *   <li>Na ordem do lote, <strong>uma transação por operação</strong>: o registro, todos os efeitos e a linha do
 *       {@code sync_log} entram juntos; uma recusa não desfaz as outras.</li>
 *   <li>Resultado de cada uma: {@code ACEITO}, {@code JA_RECEBIDO} (mesmo id e mesmo resumo canônico),
 *       {@code CONFLITO} (mesmo id e outro conteúdo; nada é sobrescrito), {@code RECUSADO} (regra de negócio, guardada
 *       com o JSON para a dona) ou {@code NAO_PROCESSADO} (erro passageiro: o lote para ali, para não quebrar a ordem).</li>
 *   <li>A hora declarada é corrigida pela diferença do relógio do computador e limitada (ADR-0013, item 11).</li>
 * </ul>
 */
@Service
public class ServicoDeEnvio {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDeEnvio.class);

    public static final int MAXIMO_DE_OPERACOES = 50;
    public static final long MAXIMO_DE_BYTES = 512L * 1024;
    static final Duration TEMPO_DO_LOTE = Duration.ofSeconds(20);
    static final Duration ESPERA_DA_TRAVA = Duration.ofSeconds(5);

    /** Os 409 que significam "mesma chave com outro conteúdo" (ou CPF já cadastrado): vão para a revisão da dona. */
    private static final Set<CodigoErro> DE_CONFLITO = Set.of(CodigoErro.VENDA_JA_REGISTRADA_DIFERENTE,
            CodigoErro.CLIENTE_JA_REGISTRADO_DIFERENTE, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE,
            CodigoErro.CLIENTE_CPF_JA_CADASTRADO, CodigoErro.DEVOLUCAO_JA_REGISTRADA_DIFERENTE,
            CodigoErro.AUTORIZACAO_JA_REGISTRADA_DIFERENTE, CodigoErro.TROCA_JA_REGISTRADA_DIFERENTE);

    public enum Resultado { ACEITO, JA_RECEBIDO, CONFLITO, RECUSADO, NAO_PROCESSADO }

    /** Uma operação da outbox, como o app a guardou. */
    public record OperacaoEnviada(UUID operacaoId, String tipo, Integer formato, Instant criadoEmLocal,
            Instant ultimoContatoServidor, JsonNode dados) {
    }

    public record PedidoDeEnvio(Instant horaLocal, Integer aguardandoDepoisDeste, List<OperacaoEnviada> operacoes) {
    }

    /** O que aconteceu com uma operação; {@code mensagem} em português simples, para o vendedor. */
    public record ResultadoDaOperacao(UUID operacaoId, Resultado resultado, String codigo, String mensagem) {
    }

    /** {@code apagarDados}: o computador trocado terminou de enviar tudo e pode apagar os dados (ADR-0013, item 13). */
    public record Envio(List<ResultadoDaOperacao> resultados, boolean apagarDados) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final OperacoesDoVendedor operacoes;
    private final ServicoDeAparelhos aparelhos;
    private final ServicoDeAuditoria auditoria;
    private final TransactionTemplate transacao;
    private final Clock relogio;
    private final Map<UUID, Semaphore> travas = new ConcurrentHashMap<>();

    public ServicoDeEnvio(JdbcTemplate jdbc, JsonMapper json, OperacoesDoVendedor operacoes, ServicoDeAparelhos aparelhos,
            ServicoDeAuditoria auditoria, PlatformTransactionManager transacoes, Clock relogio) {
        this.jdbc = jdbc;
        this.json = json;
        this.operacoes = operacoes;
        this.aparelhos = aparelhos;
        this.auditoria = auditoria;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.relogio = relogio;
    }

    public Envio enviar(UsuarioAutenticado quem, PedidoDeEnvio pedido) {
        List<OperacaoEnviada> recebidas = pedido.operacoes() == null ? List.of() : pedido.operacoes();
        if (recebidas.size() > MAXIMO_DE_OPERACOES) {
            throw new ErroDaApi(HttpStatus.CONTENT_TOO_LARGE, CodigoErro.LOTE_GRANDE_DEMAIS);
        }
        Semaphore trava = travas.computeIfAbsent(quem.dispositivoId(), d -> new Semaphore(1));
        boolean conseguiu;
        try {
            conseguiu = trava.tryAcquire(ESPERA_DA_TRAVA.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            conseguiu = false;
        }
        if (!conseguiu) {
            ErroDaApi erro = new ErroDaApi(HttpStatus.TOO_MANY_REQUESTS, CodigoErro.ENVIO_EM_ANDAMENTO);
            erro.getHeaders().set(HttpHeaders.RETRY_AFTER, "2");
            throw erro;
        }
        try {
            return enviarNaVez(quem, pedido, recebidas);
        } finally {
            trava.release();
        }
    }

    private Envio enviarNaVez(UsuarioAutenticado quem, PedidoDeEnvio pedido, List<OperacaoEnviada> recebidas) {
        Instant agora = agora();
        Instant fim = agora.plus(TEMPO_DO_LOTE);
        long diferenca = pedido.horaLocal() == null ? 0 : Duration.between(agora, pedido.horaLocal()).toMillis();
        SituacaoDoAparelho situacao = aparelhos.situacao(quem.dispositivoId(), quem.id()).orElse(null);
        boolean trocado = situacao == SituacaoDoAparelho.SUBSTITUIDO;
        List<ResultadoDaOperacao> resultados = new ArrayList<>();
        boolean parou = false;
        for (OperacaoEnviada op : recebidas) {
            if (parou || agora().isAfter(fim)) {
                resultados.add(new ResultadoDaOperacao(op == null ? null : op.operacaoId(), Resultado.NAO_PROCESSADO,
                        CodigoErro.ERRO_INTERNO.name(), null));
                parou = true;
                continue;
            }
            Contexto ctx = new Contexto(quem.vendedorId(), quem.id(), quem.dispositivoId(), agora(), diferenca,
                    op == null ? null : op.ultimoContatoServidor(), trocado, false);
            ResultadoDaOperacao r = processar(op, ctx);
            resultados.add(r);
            parou = r.resultado() == Resultado.NAO_PROCESSADO;
        }
        boolean tudoResolvido = !parou && resultados.stream().noneMatch(r -> r.resultado() == Resultado.NAO_PROCESSADO);
        Integer aguardando = pedido.aguardandoDepoisDeste();
        boolean completo = tudoResolvido && (aguardando == null || aguardando == 0);
        aparelhos.registrarPush(quem.dispositivoId(), aguardando, completo);
        return new Envio(resultados, trocado && completo);
    }

    /** Uma operação: já recebida, ou aplicada numa transação própria com a linha do sync_log. */
    ResultadoDaOperacao processar(OperacaoEnviada op, Contexto ctx) {
        if (op == null || op.operacaoId() == null) {
            return new ResultadoDaOperacao(null, Resultado.RECUSADO, CodigoErro.PEDIDO_INVALIDO.name(),
                    CodigoErro.PEDIDO_INVALIDO.mensagem());
        }
        Lida lida;
        try {
            lida = operacoes.ler(op.operacaoId(), op.tipo(), op.formato() == null ? 0 : op.formato(), op.dados());
        } catch (OperacaoIlegivel e) {
            Optional<Registro> anterior = registro(op.operacaoId());
            if (anterior.isPresent()) {
                return jaVisto(anterior.get(), ctx, resumoBruto(op), op);
            }
            return registrarFalha(op, ctx, resumoBruto(op), null, Resultado.RECUSADO, CodigoErro.PEDIDO_INVALIDO.name(),
                    CodigoErro.PEDIDO_INVALIDO.mensagem());
        }
        Optional<Registro> anterior = registro(op.operacaoId());
        if (anterior.isPresent()) {
            return jaVisto(anterior.get(), ctx, lida.resumo(), op);
        }
        if (operacoes.vencido(lida.tipo(), lida.formato())) {
            // Formato antigo além dos 90 dias (ADR-0013, item 17): não aplica, mas guarda com o JSON para a dona ver.
            return registrarFalha(op, ctx, lida.resumo(), lida, Resultado.RECUSADO, CodigoErro.FORMATO_NAO_ACEITO.name(),
                    CodigoErro.FORMATO_NAO_ACEITO.mensagem());
        }
        try {
            Aplicada aplicada = transacao.execute(status -> {
                Aplicada a = operacoes.aplicar(lida, ctx);
                inserir(op, ctx, lida.resumo(), a.jaExistia() ? Resultado.JA_RECEBIDO : Resultado.ACEITO, null, null,
                        lida.horaDeclarada(), a.horaCorrigida());
                return a;
            });
            return new ResultadoDaOperacao(op.operacaoId(), aplicada.jaExistia() ? Resultado.JA_RECEBIDO : Resultado.ACEITO,
                    null, null);
        } catch (DuplicateKeyException e) {
            // A mesma operação entrou por outro caminho no meio (corrida rara): vale o que ficou registrado.
            return registro(op.operacaoId()).map(r -> jaVisto(r, ctx, lida.resumo(), op))
                    .orElse(new ResultadoDaOperacao(op.operacaoId(), Resultado.NAO_PROCESSADO, CodigoErro.ERRO_INTERNO.name(), null));
        } catch (ErroDaApi e) {
            if (e.getStatusCode().is5xxServerError()) {
                return new ResultadoDaOperacao(op.operacaoId(), Resultado.NAO_PROCESSADO, e.codigo().name(), null);
            }
            Resultado resultado = DE_CONFLITO.contains(e.codigo()) ? Resultado.CONFLITO : Resultado.RECUSADO;
            String codigo = e.codigo().name();
            String mensagem = e.getBody().getDetail();
            if (resultado == Resultado.RECUSADO && dependeDeRecusado(lida, ctx.vendedorId())) {
                codigo = CodigoErro.DEPENDE_DE_ENVIO_RECUSADO.name();
                mensagem = CodigoErro.DEPENDE_DE_ENVIO_RECUSADO.mensagem();
            }
            return registrarFalha(op, ctx, lida.resumo(), lida, resultado, codigo, mensagem);
        } catch (DataIntegrityViolationException e) {
            LOG.warn("Envio {} ({}) recusado pelo banco: {}", op.operacaoId(), op.tipo(), e.getMostSpecificCause().getMessage());
            return registrarFalha(op, ctx, lida.resumo(), lida, Resultado.RECUSADO, CodigoErro.DADOS_INVALIDOS.name(),
                    CodigoErro.DADOS_INVALIDOS.mensagem());
        } catch (RuntimeException e) {
            // Tempo esgotado, trava, conexão, erro interno: passageiro. O lote para aqui e o app tenta de novo.
            LOG.error("Envio {} ({}) não processado; o computador tenta de novo.", op.operacaoId(), op.tipo(), e);
            return new ResultadoDaOperacao(op.operacaoId(), Resultado.NAO_PROCESSADO, CodigoErro.ERRO_INTERNO.name(), null);
        }
    }

    /** Mesma operação de novo: mesmo resumo e mesmo vendedor = o resultado guardado; senão, conflito (nada muda). */
    private ResultadoDaOperacao jaVisto(Registro anterior, Contexto ctx, String resumo, OperacaoEnviada op) {
        if (!anterior.vendedorId().equals(ctx.vendedorId()) || !anterior.resumo().equals(resumo)) {
            transacao.executeWithoutResult(status -> auditoria.registrar(Ator.usuario(ctx.usuarioId()),
                    "ENVIO_REPETIDO_COM_OUTRO_CONTEUDO", "sync_log", op.operacaoId(), null,
                    Map.of("tipo", String.valueOf(op.tipo()), "dispositivo_id", String.valueOf(ctx.aparelho()))));
            return new ResultadoDaOperacao(op.operacaoId(), Resultado.CONFLITO, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE.name(),
                    CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE.mensagem());
        }
        return switch (anterior.resultado()) {
            case "ACEITO", "JA_RECEBIDO" -> new ResultadoDaOperacao(op.operacaoId(), Resultado.JA_RECEBIDO, null, null);
            default -> new ResultadoDaOperacao(op.operacaoId(), Resultado.valueOf(anterior.resultado()), anterior.codigo(),
                    anterior.mensagem());
        };
    }

    private ResultadoDaOperacao registrarFalha(OperacaoEnviada op, Contexto ctx, String resumo, Lida lida, Resultado resultado,
            String codigo, String mensagem) {
        try {
            transacao.executeWithoutResult(status -> inserir(op, ctx, resumo, resultado, codigo, mensagem,
                    lida == null ? null : lida.horaDeclarada(), null));
        } catch (DuplicateKeyException e) {
            return registro(op.operacaoId()).map(r -> jaVisto(r, ctx, resumo, op))
                    .orElse(new ResultadoDaOperacao(op.operacaoId(), Resultado.NAO_PROCESSADO, CodigoErro.ERRO_INTERNO.name(), null));
        }
        LOG.warn("Envio {} ({}) do computador {}: {} {}", op.operacaoId(), op.tipo(), ctx.aparelho(), resultado, codigo);
        return new ResultadoDaOperacao(op.operacaoId(), resultado, codigo, mensagem);
    }

    /**
     * A operação usa um registro cujo envio foi recusado (ou ficou em conflito) e ainda não foi resolvido. O crédito de
     * uma troca nasce com o id do app, mas a operação que o cria tem o id da troca: procura também a {@code TROCA} do
     * mesmo vendedor cujo {@code creditoId} é aquele (tarefa 6.7, dúvida S9; só enquanto o JSON está no sync_log).
     */
    private boolean dependeDeRecusado(Lida lida, UUID vendedorId) {
        for (UUID id : operacoes.dependencias(lida)) {
            Boolean recusado = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM corona.sync_log
                                    WHERE (id = ? OR (tipo = 'TROCA' AND vendedor_id = ? AND dados ->> 'creditoId' = ?))
                                      AND resultado IN ('RECUSADO', 'CONFLITO')
                                      AND (resolucao IS NULL OR resolucao = 'DESCARTADO'))""", Boolean.class, id, vendedorId,
                    id.toString());
            if (Boolean.TRUE.equals(recusado)) {
                return true;
            }
        }
        return false;
    }

    private void inserir(OperacaoEnviada op, Contexto ctx, String resumo, Resultado resultado, String codigo, String mensagem,
            Instant declarada, Instant corrigida) {
        jdbc.update("""
                INSERT INTO corona.sync_log (id, dispositivo_id, usuario_id, vendedor_id, tipo, formato, resumo, resultado, codigo,
                    mensagem, recebido_em, hora_declarada, hora_corrigida, diferenca_do_relogio_ms, dados)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
                op.operacaoId(), ctx.aparelho(), ctx.usuarioId(), ctx.vendedorId(), tipoGuardado(op.tipo()),
                op.formato() == null || op.formato() < 1 ? 1 : op.formato(), resumo, resultado.name(), codigo,
                mensagem == null ? null : mensagem.substring(0, Math.min(1000, mensagem.length())), Timestamp.from(ctx.agora()),
                declarada == null ? null : Timestamp.from(declarada), corrigida == null ? null : Timestamp.from(corrigida),
                ctx.diferencaDoRelogioMs(), op.dados() == null ? "null" : json.writeValueAsString(op.dados()));
    }

    /** O tipo como veio, se tiver o formato de um tipo (senão, "DESCONHECIDO"). */
    private static String tipoGuardado(String tipo) {
        return tipo != null && tipo.matches("^[A-Z][A-Z_]{0,39}$") ? tipo : "DESCONHECIDO";
    }

    private String resumoBruto(OperacaoEnviada op) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String texto = op.tipo() + "/" + op.formato() + "|" + (op.dados() == null ? "" : json.writeValueAsString(op.dados()));
            return HexFormat.of().formatHex(sha.digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A linha do sync_log desta operação. */
    record Registro(UUID vendedorId, String resumo, String resultado, String codigo, String mensagem) {
    }

    private Optional<Registro> registro(UUID operacaoId) {
        return jdbc.query("SELECT vendedor_id, resumo, resultado, codigo, mensagem FROM corona.sync_log WHERE id = ?",
                (rs, i) -> new Registro(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), operacaoId).stream().findFirst();
    }

    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
