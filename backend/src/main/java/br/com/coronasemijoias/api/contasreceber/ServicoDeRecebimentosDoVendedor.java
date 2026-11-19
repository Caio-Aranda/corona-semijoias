package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.caixa.ServicoDeCaixa;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Os recebimentos do vendedor (tarefa 7.6; ADR-0012, itens 7, 8, 9, 11 e 16): único lugar que grava em
 * {@code recebimentos_vendedor}. O que o vendedor paga à dona entra em dinheiro, Transferência (PIX) ou boleto pago; o recebimento
 * é imutável (a correção é o estorno, com motivo) e gera, na mesma transação, um lançamento de caixa
 * ({@link ServicoDeCaixa}) e, quando paga contas, as baixas ({@link ServicoDeContasAReceber}). Só a dona registra e estorna.
 *
 * <p>Ordem de travas (ADR-0012, item 13): vendedor ({@code FOR NO KEY UPDATE}), depois o recebimento original (no estorno) e,
 * por último, as contas por id. O vendedor é travado também pelo fechamento do acerto: os dois nunca passam juntos.
 */
@Service
public class ServicoDeRecebimentosDoVendedor {

    /** O pedido de recebimento avulso (fora do acerto). Sem {@code contaId}, distribui pelas contas em aberto por vencimento. */
    public record PedidoDeRecebimento(UUID id, UUID vendedorId, String forma, BigDecimal valor, UUID contaId) {
    }

    /** O pedido de estorno: motivo obrigatório. {@code vencimento} só vale para recebimento que pagou o acerto (padrão: hoje). */
    public record PedidoDeEstorno(UUID id, String motivo, LocalDate vencimento) {
    }

    /** {@code jaExistia}: o recebimento (ou o estorno) já estava gravado (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID id, boolean jaExistia) {
    }

    static final Set<String> FORMAS_DO_REPASSE = Set.of("DINHEIRO", "PIX", "BOLETO");
    private static final BigDecimal VALOR_MAXIMO = new BigDecimal("999999999999.99");
    private static final int LIMITE_DO_MOTIVO = 400;

    private final JdbcTemplate jdbc;
    private final ServicoDeCaixa caixa;
    private final ServicoDeContasAReceber contas;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    ServicoDeRecebimentosDoVendedor(JdbcTemplate jdbc, ServicoDeCaixa caixa, ServicoDeContasAReceber contas, ServicoDeAuditoria auditoria,
            Clock relogio) {
        this.jdbc = jdbc;
        this.caixa = caixa;
        this.contas = contas;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    // --- recebimento avulso ---

    /** Registra o recebimento avulso do vendedor (dona): 201 na primeira gravação, 200 no reenvio igual, 409 com outros dados. */
    @Transactional
    public Resultado receberAvulso(UUID usuarioId, PedidoDeRecebimento pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("recebimento", "Informe o recebimento.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.vendedorId() == null) {
            throw Validacoes.recusar("vendedorId", "Escolha o representante.");
        }
        String forma = formaDoRepasse(pedido.forma(), true);
        BigDecimal valor = valorEmReais(pedido.valor());
        String resumo = resumo(pedido.vendedorId(), forma, valor, pedido.contaId());
        trancarVendedor(pedido.vendedorId());

        List<Map<String, Object>> existente = jdbc.queryForList("SELECT conteudo, estorna_id FROM corona.recebimentos_vendedor WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (resumo.equals(existente.get(0).get("conteudo"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_REGISTRADO_DIFERENTE);
        }
        Instant hora = relogio.instant();
        jdbc.update("""
                INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, aplicado_em, forma, valor, recebido_em, registrado_por,
                    conteudo) VALUES (?, ?, 'AVULSO', 'CONTAS', ?, ?, ?, ?, ?)""", id, pedido.vendedorId(), forma, valor, Timestamp.from(hora),
                usuarioId, resumo);
        caixa.lancarRecebimento(usuarioId, id, forma, valor);
        List<ServicoDeContasAReceber.BaixaFeita> baixas = contas.baixarPeloRecebimento(pedido.vendedorId(), id, forma, valor,
                pedido.contaId(), hora);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("vendedor_id", pedido.vendedorId().toString());
        depois.put("forma", forma);
        depois.put("valor", valor.toPlainString());
        depois.put("contas", baixas.stream().map(b -> b.contaId() + ": R$ " + b.valor().toPlainString()).toList());
        auditoria.registrar(Ator.usuario(usuarioId), "RECEBIMENTO_VENDEDOR_REGISTRADO", "recebimentos_vendedor", id, null, depois);
        return new Resultado(id, false);
    }

    // --- o recebimento dentro do fechamento ---

    /**
     * Grava um recebimento do fechamento do acerto (chamado só pelo {@code ServicoDeFechamento}, que já travou o vendedor e o
     * acerto). {@code naContaId} nulo: paga o acerto ({@code aplicado_em = ACERTO}, só dinheiro ou Transferência). Com conta:
     * paga a conta antiga do vendedor ({@code aplicado_em = CONTAS}) e não entra nos totais do acerto (ADR-0012, item 8).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID registrarNoFechamento(UUID usuarioId, UUID acertoId, UUID vendedorId, UUID recebimentoId, String forma, BigDecimal valor,
            boolean paraContaAntiga, UUID contaId, Instant hora) {
        UUID id = Validacoes.idDoPedido(recebimentoId);
        String formaConferida = formaDoRepasse(forma, false);
        BigDecimal valorConferido = valorEmReais(valor);
        try {
            jdbc.update("""
                    INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, acerto_id, aplicado_em, forma, valor, recebido_em,
                        registrado_por) VALUES (?, ?, 'ACERTO', ?, ?, ?, ?, ?, ?)""", id, vendedorId, acertoId,
                    paraContaAntiga ? "CONTAS" : "ACERTO", formaConferida, valorConferido, Timestamp.from(hora), usuarioId);
        } catch (DuplicateKeyException e) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_REGISTRADO_DIFERENTE);
        }
        caixa.lancarRecebimento(usuarioId, id, formaConferida, valorConferido);
        if (paraContaAntiga) {
            contas.baixarPeloRecebimento(vendedorId, id, formaConferida, valorConferido, contaId, hora);
        }
        return id;
    }

    // --- estorno ---

    /**
     * Estorna um recebimento (dona; motivo obrigatório; sem limite de dia): um registro negativo, o estorno do lançamento de
     * caixa e, conforme onde o recebimento foi aplicado, as baixas desfeitas (contas voltam a ficar em aberto) ou uma conta nova
     * do vendedor (o acerto já fechado nunca muda; ADR-0012, item 9).
     */
    @Transactional
    public Resultado estornar(UUID usuarioId, UUID recebimentoId, PedidoDeEstorno pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o recebimento.");
        }
        UUID estornoId = Validacoes.idDoPedido(pedido.id());
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o recebimento.");
        }
        if (motivo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT vendedor_id, momento, acerto_id, aplicado_em, forma, valor, estorna_id FROM corona.recebimentos_vendedor
                 WHERE id = ? AND deleted_at IS NULL""", recebimentoId);
        if (achado.isEmpty() || achado.get(0).get("estorna_id") != null) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.RECEBIMENTO_NAO_ENCONTRADO);
        }
        Map<String, Object> o = achado.get(0);
        UUID vendedorId = (UUID) o.get("vendedor_id");
        trancarVendedor(vendedorId);
        jdbc.queryForList("SELECT id FROM corona.recebimentos_vendedor WHERE id = ? FOR UPDATE", UUID.class, recebimentoId);

        List<Map<String, Object>> jaEstornado = jdbc.queryForList("SELECT id FROM corona.recebimentos_vendedor WHERE estorna_id = ?", recebimentoId);
        if (!jaEstornado.isEmpty()) {
            if (estornoId.equals(jaEstornado.get(0).get("id"))) {
                return new Resultado(estornoId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_ESTORNADO);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.recebimentos_vendedor WHERE id = ?)", Boolean.class,
                estornoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_REGISTRADO_DIFERENTE);
        }
        Instant hora = relogio.instant();
        BigDecimal valor = (BigDecimal) o.get("valor");
        String aplicadoEm = (String) o.get("aplicado_em");
        jdbc.update("""
                INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, acerto_id, aplicado_em, forma, valor, recebido_em,
                    registrado_por, motivo, estorna_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", estornoId, vendedorId, o.get("momento"),
                o.get("acerto_id"), aplicadoEm, o.get("forma"), valor.negate(), Timestamp.from(hora), usuarioId, motivo, recebimentoId);
        caixa.estornarRecebimento(usuarioId, recebimentoId, estornoId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("recebimento_id", recebimentoId.toString());
        depois.put("vendedor_id", vendedorId.toString());
        depois.put("valor", valor.negate().toPlainString());
        depois.put("motivo", motivo);
        if ("CONTAS".equals(aplicadoEm)) {
            List<ServicoDeContasAReceber.BaixaFeita> desfeitas = contas.estornarBaixasDoRecebimento(recebimentoId, estornoId, hora);
            depois.put("contas", desfeitas.stream().map(b -> b.contaId() + ": R$ " + b.valor().toPlainString()).toList());
        } else {
            LocalDate vencimento = pedido.vencimento() == null ? caixa.hoje() : pedido.vencimento();
            ServicoDeContasAReceber.ParcelaDoVendedor conta = contas.gerarContaDoEstornoDeRecebimento(vendedorId, estornoId, valor, vencimento);
            depois.put("conta_nova", conta.id().toString());
        }
        auditoria.registrar(Ator.usuario(usuarioId), "RECEBIMENTO_VENDEDOR_ESTORNADO", "recebimentos_vendedor", estornoId, null, depois);
        return new Resultado(estornoId, false);
    }

    // --- internos ---

    /** Trava o vendedor (o mesmo trinco do fechamento do acerto); 404 se não existe. */
    private void trancarVendedor(UUID vendedorId) {
        List<Integer> achado = jdbc.queryForList("SELECT 1 FROM corona.vendedores WHERE id = ? AND deleted_at IS NULL FOR NO KEY UPDATE",
                Integer.class, vendedorId);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
    }

    public static String formaDoRepasse(String forma, boolean aceitaBoleto) {
        if (forma == null || !FORMAS_DO_REPASSE.contains(forma) || (!aceitaBoleto && "BOLETO".equals(forma))) {
            throw Validacoes.recusar("forma", aceitaBoleto
                    ? "Escolha como o vendedor pagou: dinheiro, Transferência (PIX) ou boleto."
                    : "O pagamento no acerto é em dinheiro ou Transferência (PIX). O boleto vira uma conta a receber.");
        }
        return forma;
    }

    public static BigDecimal valorEmReais(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0) {
            throw Validacoes.recusar("valor", "Informe o valor recebido, maior que zero.");
        }
        if (valor.stripTrailingZeros().scale() > Dinheiro.ESCALA || valor.compareTo(VALOR_MAXIMO) > 0) {
            throw Validacoes.recusar("valor", "Use um valor em reais com no máximo duas casas depois da vírgula.");
        }
        return valor.setScale(Dinheiro.ESCALA);
    }

    private static String resumo(UUID vendedorId, String forma, BigDecimal valor, UUID contaId) {
        String texto = vendedorId + "|" + forma + "|" + valor.toPlainString() + "|" + contaId;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
