package br.com.coronasemijoias.api.contaspagar;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.caixa.ServicoDeCaixa;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * As contas a pagar das compras (tarefa 7.7; ADR-0012, itens 12, 13, 15 e 16; condições C4, C5 e C10): único lugar que grava em
 * {@code contas_pagar} e {@code baixas_pagar}. A dona gera as parcelas de uma compra (a soma é o total da compra), paga em
 * dinheiro, Transferência (PIX) ou boleto (parcial ou inteiro) e estorna o pagamento com motivo. Cada pagamento é uma baixa
 * imutável e uma saída de caixa na mesma transação ({@link ServicoDeCaixa}). A compra desfeita cancela o que está em aberto.
 * Os atalhos (pago, cancelado, situação) são sempre a soma das baixas e só mudam aqui, com a conta travada. Tudo em Real. Só a
 * dona (ADMIN).
 *
 * <p>Ordem de travas (ADR-0012, item 13): a compra, depois as contas por id ({@code FOR UPDATE}) e, por último, a semana da data do
 * lançamento ({@code FOR SHARE}, no caixa).
 */
@Service
public class ServicoDeContasAPagar {

    /** O pedido de gerar as contas: quantas parcelas (1 a 24) e o vencimento da primeira; as outras vencem de mês em mês. */
    public record PedidoParaGerar(UUID id, Integer parcelas, LocalDate primeiroVencimento) {
    }

    /** O pedido de pagamento: valor em Real, forma e a data (opcional; padrão hoje; nunca no futuro). */
    public record PedidoDePagamento(UUID id, BigDecimal valor, String forma, LocalDate data) {
    }

    /** O pedido de estorno do pagamento: motivo obrigatório. */
    public record PedidoDeEstorno(UUID id, String motivo) {
    }

    /** {@code jaExistia}: já estava gravado (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID id, boolean jaExistia) {
    }

    static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX", "BOLETO");
    static final int MAXIMO_DE_PARCELAS = 24;
    private static final BigDecimal VALOR_MAXIMO = new BigDecimal("999999999999.99");
    private static final int LIMITE_DO_MOTIVO = 400;

    private final JdbcTemplate jdbc;
    private final ServicoDeCaixa caixa;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    ServicoDeContasAPagar(JdbcTemplate jdbc, ServicoDeCaixa caixa, ServicoDeAuditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.caixa = caixa;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    // --- gerar ---

    /**
     * Gera as parcelas da compra: as {@code n - 1} primeiras iguais e a última fecha exato (só o {@link Dinheiro}), vencendo de mês
     * em mês. Uma geração por compra: o mesmo id e o mesmo pedido devolvem 200; outro pedido, 409.
     */
    @Transactional
    public Resultado gerar(UUID usuarioId, UUID compraId, PedidoParaGerar pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("parcelas", "Informe em quantas parcelas a compra será paga.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.parcelas() == null || pedido.parcelas() < 1 || pedido.parcelas() > MAXIMO_DE_PARCELAS) {
            throw Validacoes.recusar("parcelas", "Escolha de 1 a " + MAXIMO_DE_PARCELAS + " parcelas.");
        }
        if (pedido.primeiroVencimento() == null) {
            throw Validacoes.recusar("primeiroVencimento", "Informe o vencimento da primeira parcela.");
        }
        int quantas = pedido.parcelas();
        String resumo = resumo(compraId + "|" + quantas + "|" + pedido.primeiroVencimento());

        List<Map<String, Object>> compras = jdbc.queryForList(
                "SELECT fornecedor_id, total, situacao FROM corona.compras WHERE id = ? FOR UPDATE", compraId);
        if (compras.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.COMPRA_NAO_ENCONTRADA);
        }
        Map<String, Object> compra = compras.get(0);
        List<Map<String, Object>> jaGeradas = jdbc.queryForList(
                "SELECT geracao_id, conteudo FROM corona.contas_pagar WHERE compra_id = ? LIMIT 1", compraId);
        if (!jaGeradas.isEmpty()) {
            if (id.equals(jaGeradas.get(0).get("geracao_id")) && resumo.equals(jaGeradas.get(0).get("conteudo"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONTAS_A_PAGAR_JA_GERADAS);
        }
        if ("ESTORNADA".equals(compra.get("situacao"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COMPRA_JA_DESFEITA);
        }
        BigDecimal total = (BigDecimal) compra.get("total");
        if (total.signum() <= 0) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COMPRA_SEM_VALOR_A_PAGAR);
        }
        List<BigDecimal> valores;
        try {
            valores = Dinheiro.dividirEmParcelas(total, quantas);
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar("parcelas", "O valor da compra é pequeno demais para tantas parcelas. Escolha menos parcelas.");
        }
        if (valores.stream().anyMatch(v -> v.signum() <= 0)) {
            throw Validacoes.recusar("parcelas", "O valor da compra é pequeno demais para tantas parcelas. Escolha menos parcelas.");
        }
        UUID fornecedorId = (UUID) compra.get("fornecedor_id");
        List<String> criadas = new java.util.ArrayList<>();
        for (int i = 0; i < quantas; i++) {
            UUID contaId = UuidV7.gerar();
            LocalDate vencimento = pedido.primeiroVencimento().plusMonths(i);
            jdbc.update("""
                    INSERT INTO corona.contas_pagar (id, compra_id, fornecedor_id, geracao_id, parcela, total_parcelas, vencimento, valor,
                        conteudo, criada_em, criada_por) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", contaId, compraId, fornecedorId, id,
                    i + 1, quantas, vencimento, valores.get(i), resumo, Timestamp.from(relogio.instant()), usuarioId);
            criadas.add((i + 1) + "/" + quantas + " " + vencimento + ": R$ " + valores.get(i).toPlainString());
        }
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("compra_id", compraId.toString());
        depois.put("total", total.toPlainString());
        depois.put("parcelas", criadas);
        auditoria.registrar(Ator.usuario(usuarioId), "CONTAS_A_PAGAR_GERADAS", "contas_pagar", compraId, null, depois);
        return new Resultado(id, false);
    }

    // --- pagar ---

    /**
     * Registra o pagamento (parcial ou inteiro) de uma parcela: uma baixa e uma saída de caixa, com o mesmo valor e a mesma forma,
     * na mesma transação. Acima do que falta pagar é recusado. 201 na primeira gravação, 200 no reenvio igual, 409 com outros dados.
     */
    @Transactional
    public Resultado pagar(UUID usuarioId, UUID contaId, PedidoDePagamento pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("pagamento", "Informe o pagamento.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        BigDecimal valor = valorEmReais(pedido.valor());
        String forma = forma(pedido.forma());
        LocalDate data = pedido.data() == null ? caixa.hoje() : pedido.data();
        if (data.isAfter(caixa.hoje())) {
            throw Validacoes.recusar("data", "A data do pagamento não pode ser no futuro.");
        }
        String resumo = resumo(contaId + "|" + valor.toPlainString() + "|" + forma + "|" + (pedido.data() == null ? "" : pedido.data()));

        Map<String, Object> conta = travarConta(contaId);
        List<Map<String, Object>> existente = jdbc.queryForList("SELECT conta_id, conteudo FROM corona.baixas_pagar WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (contaId.equals(existente.get(0).get("conta_id")) && resumo.equals(existente.get(0).get("conteudo"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_A_FORNECEDOR_JA_REGISTRADO_DIFERENTE);
        }
        if ("CANCELADA".equals(conta.get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONTA_A_PAGAR_CANCELADA);
        }
        BigDecimal saldo = saldoDaConta(conta);
        if (valor.compareTo(saldo) > 0) {
            throw ErroDaApi.comDetalhe(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PAGAMENTO_ACIMA_DO_SALDO,
                    "O valor passa do que falta pagar desta conta (R$ " + saldo.toPlainString().replace('.', ',') + "). Confira o valor.");
        }
        jdbc.update("""
                INSERT INTO corona.baixas_pagar (id, conta_id, tipo, valor, forma, data_pagamento, conteudo, registrada_em, registrada_por)
                VALUES (?, ?, 'PAGAMENTO', ?, ?, ?, ?, ?, ?)""", id, contaId, valor, forma, data, resumo, Timestamp.from(relogio.instant()),
                usuarioId);
        caixa.lancarPagamentoAFornecedor(usuarioId, id, forma, valor, data);
        String situacao = recalcular(contaId);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("conta_id", contaId.toString());
        depois.put("valor", valor.toPlainString());
        depois.put("forma", forma);
        depois.put("data", data.toString());
        depois.put("situacao_da_conta", situacao);
        auditoria.registrar(Ator.usuario(usuarioId), "PAGAMENTO_FORNECEDOR_REGISTRADO", "baixas_pagar", id, null, depois);
        return new Resultado(id, false);
    }

    // --- estornar o pagamento ---

    /**
     * Estorna um pagamento (motivo obrigatório; sem limite de dia): a baixa negativa e o lançamento de caixa de sinal oposto, com a
     * data de hoje. Se a compra já foi desfeita, o que voltou a ficar em aberto é cancelado na hora.
     */
    @Transactional
    public Resultado estornarPagamento(UUID usuarioId, UUID baixaId, PedidoDeEstorno pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o pagamento.");
        }
        UUID estornoId = Validacoes.idDoPedido(pedido.id());
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o pagamento.");
        }
        if (motivo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT conta_id FROM corona.baixas_pagar WHERE id = ? AND tipo = 'PAGAMENTO' AND estorna_id IS NULL", baixaId);
        if (achada.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.PAGAMENTO_A_FORNECEDOR_NAO_ENCONTRADO);
        }
        UUID contaId = (UUID) achada.get(0).get("conta_id");
        Map<String, Object> conta = travarConta(contaId);
        String resumo = resumo(baixaId + "|" + motivo);

        List<Map<String, Object>> jaEstornado = jdbc.queryForList("SELECT id FROM corona.baixas_pagar WHERE estorna_id = ?", baixaId);
        if (!jaEstornado.isEmpty()) {
            if (estornoId.equals(jaEstornado.get(0).get("id"))) {
                return new Resultado(estornoId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_A_FORNECEDOR_JA_ESTORNADO);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.baixas_pagar WHERE id = ?)", Boolean.class, estornoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_A_FORNECEDOR_JA_REGISTRADO_DIFERENTE);
        }
        Map<String, Object> original = jdbc.queryForMap("SELECT valor, forma FROM corona.baixas_pagar WHERE id = ?", baixaId);
        BigDecimal valor = (BigDecimal) original.get("valor");
        String forma = (String) original.get("forma");
        LocalDate hoje = caixa.hoje();
        jdbc.update("""
                INSERT INTO corona.baixas_pagar (id, conta_id, tipo, valor, forma, data_pagamento, motivo, estorna_id, conteudo, registrada_em,
                    registrada_por) VALUES (?, ?, 'PAGAMENTO', ?, ?, ?, ?, ?, ?, ?, ?)""", estornoId, contaId, valor.negate(), forma, hoje, motivo,
                baixaId, resumo, Timestamp.from(relogio.instant()), usuarioId);
        caixa.estornarPagamentoAFornecedor(usuarioId, baixaId, estornoId);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("pagamento_id", baixaId.toString());
        depois.put("conta_id", contaId.toString());
        depois.put("valor", valor.negate().toPlainString());
        depois.put("motivo", motivo);
        // A compra já foi desfeita: o que voltou a ficar em aberto não será pago, então é cancelado agora.
        String situacaoDaCompra = jdbc.queryForObject("SELECT situacao FROM corona.compras WHERE id = ?", String.class, conta.get("compra_id"));
        if ("ESTORNADA".equals(situacaoDaCompra)) {
            cancelarSaldo(contaId, "Compra desfeita");
            depois.put("cancelado", valor.toPlainString());
        } else {
            recalcular(contaId);
        }
        auditoria.registrar(Ator.usuario(usuarioId), "PAGAMENTO_FORNECEDOR_ESTORNADO", "baixas_pagar", estornoId, null, depois);
        return new Resultado(estornoId, false);
    }

    // --- a compra desfeita ---

    /**
     * Cancela o que está em aberto das contas da compra desfeita (chamado só pelo {@code ServicoDeCompras.desfazer}, na mesma
     * transação, com a compra já travada): uma baixa de cancelamento do saldo de cada conta, sem lançamento de caixa. A parcela
     * paga continua paga (a devolução do fornecedor é lançamento manual de entrada).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelarAbertasDaCompra(UUID compraId, String motivo, UUID usuarioId) {
        List<UUID> contas = jdbc.queryForList("SELECT id FROM corona.contas_pagar WHERE compra_id = ? ORDER BY id FOR UPDATE", UUID.class,
                compraId);
        List<String> canceladas = new java.util.ArrayList<>();
        for (UUID contaId : contas) {
            BigDecimal cancelado = cancelarSaldo(contaId, "Compra desfeita: " + motivo);
            if (cancelado.signum() > 0) {
                canceladas.add(contaId + ": R$ " + cancelado.toPlainString());
            }
        }
        if (!canceladas.isEmpty()) {
            Map<String, Object> depois = new LinkedHashMap<>();
            depois.put("compra_id", compraId.toString());
            depois.put("contas", canceladas);
            auditoria.registrar(Ator.usuario(usuarioId), "CONTAS_A_PAGAR_CANCELADAS", "contas_pagar", compraId, null, depois);
        }
    }

    // --- internos ---

    /** Trava a conta ({@code FOR UPDATE}); 404 se não existe. Devolve a linha lida já com a trava. */
    private Map<String, Object> travarConta(UUID contaId) {
        List<Map<String, Object>> conta = jdbc.queryForList("""
                SELECT compra_id, valor, valor_pago, valor_cancelado, status FROM corona.contas_pagar WHERE id = ? AND deleted_at IS NULL
                 FOR UPDATE""", contaId);
        if (conta.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CONTA_A_PAGAR_NAO_ENCONTRADA);
        }
        return conta.get(0);
    }

    private static BigDecimal saldoDaConta(Map<String, Object> conta) {
        return ((BigDecimal) conta.get("valor")).subtract((BigDecimal) conta.get("valor_pago")).subtract((BigDecimal) conta.get("valor_cancelado"));
    }

    /** Cancela o saldo da conta (se houver) com uma baixa de cancelamento; devolve o valor cancelado (zero se não havia saldo). */
    private BigDecimal cancelarSaldo(UUID contaId, String motivo) {
        travarConta(contaId);
        Map<String, Object> soma = jdbc.queryForMap("""
                SELECT c.valor - coalesce(sum(b.valor), 0) AS saldo FROM corona.contas_pagar c
                  LEFT JOIN corona.baixas_pagar b ON b.conta_id = c.id WHERE c.id = ? GROUP BY c.id""", contaId);
        BigDecimal saldo = (BigDecimal) soma.get("saldo");
        if (saldo.signum() <= 0) {
            recalcular(contaId);
            return BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        }
        String motivoCurto = motivo.length() > LIMITE_DO_MOTIVO ? motivo.substring(0, LIMITE_DO_MOTIVO) : motivo;
        jdbc.update("""
                INSERT INTO corona.baixas_pagar (id, conta_id, tipo, valor, motivo, registrada_em) VALUES (?, ?, 'CANCELAMENTO', ?, ?, ?)""",
                UuidV7.gerar(), contaId, saldo, motivoCurto, Timestamp.from(relogio.instant()));
        recalcular(contaId);
        return saldo;
    }

    /** Os atalhos da conta são sempre a soma das baixas (C5): recalcula valor pago, cancelado e situação. Devolve a situação. */
    private String recalcular(UUID contaId) {
        Map<String, Object> soma = jdbc.queryForMap("""
                SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0) AS pago,
                       coalesce(sum(valor) FILTER (WHERE tipo = 'CANCELAMENTO'), 0) AS cancelado
                  FROM corona.baixas_pagar WHERE conta_id = ?""", contaId);
        BigDecimal pago = (BigDecimal) soma.get("pago");
        BigDecimal cancelado = (BigDecimal) soma.get("cancelado");
        BigDecimal valor = jdbc.queryForObject("SELECT valor FROM corona.contas_pagar WHERE id = ?", BigDecimal.class, contaId);
        String situacao;
        if (cancelado.signum() > 0) {
            situacao = "CANCELADA";
        } else if (pago.signum() == 0) {
            situacao = "ABERTA";
        } else if (pago.compareTo(valor) == 0) {
            situacao = "PAGA";
        } else {
            situacao = "PARCIAL";
        }
        jdbc.update("UPDATE corona.contas_pagar SET valor_pago = ?, valor_cancelado = ?, status = ? WHERE id = ?", pago, cancelado, situacao,
                contaId);
        return situacao;
    }

    static String forma(String forma) {
        if (forma == null || !FORMAS.contains(forma)) {
            throw Validacoes.recusar("forma", "Escolha como você pagou: dinheiro, Transferência (PIX) ou boleto.");
        }
        return forma;
    }

    static BigDecimal valorEmReais(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0) {
            throw Validacoes.recusar("valor", "Informe o valor pago, maior que zero.");
        }
        if (valor.stripTrailingZeros().scale() > Dinheiro.ESCALA || valor.compareTo(VALOR_MAXIMO) > 0) {
            throw Validacoes.recusar("valor", "Use um valor em reais com no máximo duas casas depois da vírgula.");
        }
        return valor.setScale(Dinheiro.ESCALA);
    }

    static String resumo(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
