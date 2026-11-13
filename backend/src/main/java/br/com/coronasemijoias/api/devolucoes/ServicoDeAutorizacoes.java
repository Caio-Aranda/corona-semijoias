package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.devolucoes.RegrasDaDevolucao.Peca;
import br.com.coronasemijoias.api.devolucoes.RegrasDaDevolucao.PecaCalculada;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pedido de autorização da dona para uma devolução (tarefa 6.4; ADR-0014, item 6), aprovação, negação e a marcação das
 * vencidas. Regras:
 * <ul>
 *   <li>O vendedor manda a venda, o motivo, a observação e as peças (como na devolução); o tipo ({@code FORA_DO_PRAZO}
 *       ou {@code ACIMA_DO_VALOR}) e o valor pedido (o preço pago, com o que já voltou) são do servidor.</li>
 *   <li>Um pedido aberto por venda: com um esperando a dona ou aprovado e ainda válido, 409 (dúvida P10).</li>
 *   <li>Aprovada, vale até o dia (Brasília) da aprovação + 7, inclusive (dúvida P7), e serve para uma devolução só.</li>
 *   <li>Trava só a venda (e, na decisão, o pedido): nunca pega a trava do vendedor nem a da maleta, então não inverte a
 *       ordem da devolução (vendedor, maleta, venda, autorização).</li>
 * </ul>
 */
@Service
public class ServicoDeAutorizacoes {

    /** Dias além do da aprovação em que a autorização ainda vale (ESPECIFICACAO §7.3: "vale 7 dias"). */
    static final int DIAS_DE_VALIDADE = 7;

    public record PedidoDeAutorizacao(UUID id, UUID vendaId, String motivo, String observacao, List<PecaDoPedido> itens) {
    }

    /** {@code jaExistia}: o pedido (ou a decisão) já estava gravado (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID autorizacaoId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Configuracoes configuracoes;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;

    ServicoDeAutorizacoes(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, Configuracoes configuracoes,
            OrigemDoDispositivo origem, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.configuracoes = configuracoes;
        this.origem = origem;
        this.relogio = relogio;
    }

    /**
     * Registra o pedido. {@code momento} nulo = agora (a rota online). Na rota online, o pedido que não precisa de
     * autorização é recusado (409); pelo envio do computador (tarefa 6.7) é gravado como fora do prazo (dúvida P13): o
     * computador só pede pelo prazo.
     */
    @Transactional
    public Resultado pedir(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeAutorizacao pedido, Instant momento,
            boolean horaAjustada, ModoDaDevolucao modo) {
        if (pedido == null) {
            throw Validacoes.recusar("autorizacao", "Informe o pedido de autorização.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.vendaId() == null) {
            throw Validacoes.recusar("vendaId", "Escolha a venda da devolução.");
        }
        String motivo = RegrasDaDevolucao.conferirMotivo(pedido.motivo());
        String observacao = RegrasDaDevolucao.conferirObservacao(pedido.observacao(), motivo);
        List<Peca> pecas = RegrasDaDevolucao.conferirPecas(pedido.itens(), motivo);

        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT vendedor_id FROM corona.vendas WHERE id = ? AND deleted_at IS NULL", pedido.vendaId());
        if (achada.isEmpty() || !achada.get(0).get("vendedor_id").equals(vendedorId)) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        Map<String, Object> venda = jdbc.queryForMap(
                "SELECT status, cliente_id, total, vendida_em FROM corona.vendas WHERE id = ? FOR UPDATE", pedido.vendaId());

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT venda_id, vendedor_id, motivo, observacao FROM corona.autorizacoes_excecao WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (!mesmoPedido(id, vendedorId, pedido.vendaId(), motivo, observacao, pecas, existente.get(0))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.AUTORIZACAO_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(id, true);
        }

        VendedorAtivo.exigir(jdbc, vendedorId);
        if ("ESTORNADA".equals(venda.get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        BigDecimal pago = jdbc.queryForObject("""
                SELECT coalesce(sum(valor), 0) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO'""",
                BigDecimal.class, pedido.vendaId());
        if (pago.compareTo((BigDecimal) venda.get("total")) < 0) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_A_PRAZO_SEM_DEVOLUCAO);
        }
        Instant quando = momento == null ? relogio.instant() : momento;
        LocalDate dia = RegrasDaDevolucao.dia(quando);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.autorizacoes_excecao
                                WHERE venda_id = ? AND deleted_at IS NULL
                                  AND (situacao = 'PEDIDA' OR (situacao = 'APROVADA' AND valida_ate >= ?)))""",
                Boolean.class, pedido.vendaId(), dia))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.AUTORIZACAO_JA_PEDIDA);
        }

        List<PecaCalculada> calculadas = RegrasDaDevolucao.calcular(jdbc, pedido.vendaId(), pecas);
        BigDecimal valorPedido = RegrasDaDevolucao.somar(calculadas);
        Instant vendidaEm = ((Timestamp) venda.get("vendida_em")).toInstant();
        boolean foraDoPrazo = RegrasDaDevolucao.diasDesdeAVenda(vendidaEm, quando) > RegrasDaDevolucao.prazo(configuracoes, motivo);
        Optional<BigDecimal> valorDeAprovacao = configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.VALOR_APROVACAO_REEMBOLSO);
        String tipo;
        if (foraDoPrazo) {
            tipo = "FORA_DO_PRAZO";
        } else if (valorDeAprovacao.isPresent() && valorPedido.compareTo(valorDeAprovacao.get()) > 0) {
            tipo = "ACIMA_DO_VALOR";
        } else if (modo != ModoDaDevolucao.ENVIO) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.AUTORIZACAO_DESNECESSARIA);
        } else {
            tipo = "FORA_DO_PRAZO";
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        jdbc.update("""
                INSERT INTO corona.autorizacoes_excecao (id, venda_id, vendedor_id, cliente_id, motivo, observacao, tipo, situacao,
                    valor_pedido, cotacao_id, pedida_em, hora_ajustada, pedida_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PEDIDA', ?, ?, ?, ?, ?)""", id, pedido.vendaId(), vendedorId, venda.get("cliente_id"),
                motivo, observacao, tipo, valorPedido, RegrasDaDevolucao.cotacaoDoDia(jdbc, dia), Timestamp.from(quando),
                horaAjustada, usuarioId);
        // Uma linha por peça da venda (a quantidade de todos os estados junta).
        Map<UUID, Integer> porLinha = new LinkedHashMap<>();
        for (PecaCalculada p : calculadas) {
            porLinha.merge(p.linha().id(), p.quantidade(), Integer::sum);
        }
        porLinha.forEach((linha, quantidade) -> jdbc.update("""
                INSERT INTO corona.itens_autorizacao (id, autorizacao_id, item_venda_id, quantidade) VALUES (?, ?, ?, ?)""",
                UuidV7.gerar(), id, linha, quantidade));

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_id", pedido.vendaId().toString());
        depois.put("motivo", motivo);
        depois.put("tipo", tipo);
        depois.put("valor_pedido", valorPedido.toPlainString());
        auditoria.registrar(Ator.usuario(usuarioId), "AUTORIZACAO_PEDIDA", "autorizacoes_excecao", id, null, depois);
        return new Resultado(id, false);
    }

    /** A dona aprova: vale até o dia de Brasília de agora + 7. Repetir devolve 200; depois de negar, 409. */
    @Transactional
    public Resultado aprovar(UUID autorizacaoId, UUID usuarioId) {
        String situacao = travar(autorizacaoId);
        if ("APROVADA".equals(situacao) || "USADA".equals(situacao) || "VENCIDA".equals(situacao)) {
            return new Resultado(autorizacaoId, true);
        }
        if (!"PEDIDA".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.AUTORIZACAO_JA_DECIDIDA);
        }
        Instant agora = relogio.instant();
        LocalDate validaAte = RegrasDaDevolucao.dia(agora).plusDays(DIAS_DE_VALIDADE);
        jdbc.update("""
                UPDATE corona.autorizacoes_excecao SET situacao = 'APROVADA', decidida_em = ?, decidida_por = ?, valida_ate = ?
                 WHERE id = ?""", Timestamp.from(agora), usuarioId, validaAte, autorizacaoId);
        auditoria.registrar(Ator.usuario(usuarioId), "AUTORIZACAO_APROVADA", "autorizacoes_excecao", autorizacaoId,
                Map.of("situacao", situacao), Map.of("situacao", "APROVADA", "valida_ate", validaAte.toString()));
        return new Resultado(autorizacaoId, false);
    }

    /** A dona nega, com o motivo (obrigatório). Repetir devolve 200; depois de aprovar, 409. */
    @Transactional
    public Resultado negar(UUID autorizacaoId, UUID usuarioId, String motivo) {
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de negar o pedido.");
        }
        if (limpo.length() > RegrasDaDevolucao.LIMITE_DO_TEXTO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + RegrasDaDevolucao.LIMITE_DO_TEXTO + " caracteres.");
        }
        String situacao = travar(autorizacaoId);
        if ("NEGADA".equals(situacao)) {
            return new Resultado(autorizacaoId, true);
        }
        if (!"PEDIDA".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.AUTORIZACAO_JA_DECIDIDA);
        }
        jdbc.update("""
                UPDATE corona.autorizacoes_excecao SET situacao = 'NEGADA', decidida_em = ?, decidida_por = ?, motivo_decisao = ?
                 WHERE id = ?""", Timestamp.from(relogio.instant()), usuarioId, limpo, autorizacaoId);
        auditoria.registrar(Ator.usuario(usuarioId), "AUTORIZACAO_NEGADA", "autorizacoes_excecao", autorizacaoId,
                Map.of("situacao", situacao), Map.of("situacao", "NEGADA", "motivo", limpo));
        return new Resultado(autorizacaoId, false);
    }

    /**
     * As aprovadas cujo último dia (Brasília) já passou viram vencidas (dúvida P8). Só a situação: a regra do uso olha a
     * data e nunca depende disto ter rodado. Devolve quantas mudaram.
     */
    @Transactional
    public int marcarVencidas() {
        LocalDate hoje = RegrasDaDevolucao.dia(relogio.instant());
        List<UUID> vencidas = jdbc.queryForList("""
                SELECT id FROM corona.autorizacoes_excecao
                 WHERE situacao = 'APROVADA' AND valida_ate < ? AND deleted_at IS NULL
                 ORDER BY id FOR UPDATE SKIP LOCKED""", UUID.class, hoje);
        for (UUID id : vencidas) {
            jdbc.update("UPDATE corona.autorizacoes_excecao SET situacao = 'VENCIDA' WHERE id = ? AND situacao = 'APROVADA'", id);
            auditoria.registrar(Ator.PELO_SERVIDOR, "AUTORIZACAO_VENCIDA", "autorizacoes_excecao", id,
                    Map.of("situacao", "APROVADA"), Map.of("situacao", "VENCIDA"));
        }
        return vencidas.size();
    }

    private String travar(UUID autorizacaoId) {
        List<String> situacao = jdbc.queryForList(
                "SELECT situacao FROM corona.autorizacoes_excecao WHERE id = ? AND deleted_at IS NULL FOR UPDATE", String.class,
                autorizacaoId);
        if (situacao.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.AUTORIZACAO_NAO_ENCONTRADA);
        }
        return situacao.get(0);
    }

    /** O pedido repetido é o mesmo? Compara dono, venda, motivo, observação e a quantidade de cada peça. */
    private boolean mesmoPedido(UUID id, UUID vendedorId, UUID vendaId, String motivo, String observacao, List<Peca> pecas,
            Map<String, Object> gravado) {
        if (!vendedorId.equals(gravado.get("vendedor_id")) || !vendaId.equals(gravado.get("venda_id"))
                || !motivo.equals(gravado.get("motivo")) || !Objects.equals(observacao, gravado.get("observacao"))) {
            return false;
        }
        Map<UUID, Integer> gravadas = new LinkedHashMap<>();
        jdbc.query("""
                SELECT iv.produto_id, a.quantidade FROM corona.itens_autorizacao a
                  JOIN corona.itens_venda iv ON iv.id = a.item_venda_id
                 WHERE a.autorizacao_id = ?""", rs -> {
                    gravadas.put(rs.getObject(1, UUID.class), rs.getInt(2));
                }, id);
        return RegrasDaDevolucao.quantidadePorProduto(pecas).equals(gravadas);
    }
}
