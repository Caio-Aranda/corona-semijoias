package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.caixa.ServicoDeCaixa;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.cotacoes.Cotacao;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
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

    /**
     * O pedido de recebimento avulso (fora do acerto). Sem {@code contaId}, distribui pelas contas em aberto por vencimento.
     * {@code moeda} (padrão REAL): em REAL vem o {@code valor}; em DOLAR ou GUARANI vem só o {@code valorNaMoeda} e o servidor
     * converte pela cotação do dia (23/11/2026). {@code cotacaoData}, opcional: a data da cotação que a tela mostrou.
     */
    public record PedidoDeRecebimento(UUID id, UUID vendedorId, String forma, BigDecimal valor, UUID contaId, String moeda,
            BigDecimal valorNaMoeda, LocalDate cotacaoData) {
    }

    /**
     * O valor de um recebimento depois de conferido: {@code valor} em Real (o que vale e é gravado) e, em Dólar ou Guarani, a moeda,
     * o valor digitado (escala 2) e a cotação que converteu. Em REAL, {@code valorNaMoeda} e {@code cotacaoId} são nulos.
     */
    public record ValorRecebido(BigDecimal valor, String moeda, BigDecimal valorNaMoeda, UUID cotacaoId) {

        public static ValorRecebido emReais(BigDecimal valor) {
            return new ValorRecebido(valor, REAL, null, null);
        }
    }

    /** O pedido de estorno: motivo obrigatório. {@code vencimento} só vale para recebimento que pagou o acerto (padrão: hoje). */
    public record PedidoDeEstorno(UUID id, String motivo, LocalDate vencimento) {
    }

    /** {@code jaExistia}: o recebimento (ou o estorno) já estava gravado (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID id, boolean jaExistia) {
    }

    static final Set<String> FORMAS_DO_REPASSE = Set.of("DINHEIRO", "PIX", "BOLETO");
    public static final String REAL = "REAL";
    static final Set<String> MOEDAS = Set.of(REAL, "DOLAR", "GUARANI");
    private static final BigDecimal VALOR_MAXIMO = new BigDecimal("999999999999.99");
    private static final int LIMITE_DO_MOTIVO = 400;

    private final JdbcTemplate jdbc;
    private final ServicoDeCaixa caixa;
    private final ServicoDeContasAReceber contas;
    private final ServicoDeAuditoria auditoria;
    private final CotacaoVigente cotacoes;
    private final Clock relogio;

    ServicoDeRecebimentosDoVendedor(JdbcTemplate jdbc, ServicoDeCaixa caixa, ServicoDeContasAReceber contas, ServicoDeAuditoria auditoria,
            CotacaoVigente cotacoes, Clock relogio) {
        this.jdbc = jdbc;
        this.caixa = caixa;
        this.contas = contas;
        this.auditoria = auditoria;
        this.cotacoes = cotacoes;
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
        String moeda = moedaDoRecebimento(pedido.moeda(), pedido.valor(), pedido.valorNaMoeda());
        // O resumo do 409 leva o que a dona digitou (em Dólar ou Guarani, a moeda e o valor nela), nunca o Real convertido.
        BigDecimal digitado = REAL.equals(moeda) ? valorEmReais(pedido.valor()) : valorNaMoeda(moeda, pedido.valorNaMoeda());
        String resumo = REAL.equals(moeda) ? resumo(pedido.vendedorId(), forma, digitado, pedido.contaId())
                : resumo(pedido.vendedorId(), forma, digitado, pedido.contaId() + "|" + moeda);
        trancarVendedor(pedido.vendedorId());

        List<Map<String, Object>> existente = jdbc.queryForList("SELECT conteudo, estorna_id FROM corona.recebimentos_vendedor WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (resumo.equals(existente.get(0).get("conteudo"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_REGISTRADO_DIFERENTE);
        }
        Instant hora = relogio.instant();
        ValorRecebido recebido = REAL.equals(moeda) ? ValorRecebido.emReais(digitado) : converterPelaCotacaoDe(hora, moeda, digitado,
                pedido.cotacaoData());
        BigDecimal valor = recebido.valor();
        jdbc.update("""
                INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, aplicado_em, forma, valor, recebido_em, registrado_por,
                    conteudo, moeda, valor_na_moeda, cotacao_id) VALUES (?, ?, 'AVULSO', 'CONTAS', ?, ?, ?, ?, ?, ?, ?, ?)""", id,
                pedido.vendedorId(), forma, valor, Timestamp.from(hora), usuarioId, resumo, recebido.moeda(), recebido.valorNaMoeda(),
                recebido.cotacaoId());
        caixa.lancarRecebimento(usuarioId, id, forma, valor);
        List<ServicoDeContasAReceber.BaixaFeita> baixas = contas.baixarPeloRecebimento(pedido.vendedorId(), id, forma, valor,
                pedido.contaId(), hora);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("vendedor_id", pedido.vendedorId().toString());
        depois.put("forma", forma);
        depois.put("valor", valor.toPlainString());
        if (!REAL.equals(recebido.moeda())) {
            depois.put("moeda", recebido.moeda());
            depois.put("valor_na_moeda", recebido.valorNaMoeda().toPlainString());
        }
        depois.put("contas", baixas.stream().map(b -> b.contaId() + ": R$ " + b.valor().toPlainString()).toList());
        auditoria.registrar(Ator.usuario(usuarioId), "RECEBIMENTO_VENDEDOR_REGISTRADO", "recebimentos_vendedor", id, null, depois);
        return new Resultado(id, false);
    }

    /**
     * Converte pela cotação do dia do lançamento (ADR-0006, item 7). Sem cotação, 409; se a tela mostrou outra cotação (chegou uma
     * nova com a tela aberta), 409 também: nada é gravado e a dona confere o valor em reais de novo.
     */
    private ValorRecebido converterPelaCotacaoDe(Instant hora, String moeda, BigDecimal digitado, LocalDate cotacaoDaTela) {
        Cotacao cotacao = cotacoes.vigenteEm(hora)
                .orElseThrow(() -> new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEM_COTACAO_PARA_O_RECEBIMENTO));
        if (cotacaoDaTela != null && !cotacaoDaTela.equals(cotacao.dataCotacao())) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COTACAO_DO_RECEBIMENTO_MUDOU);
        }
        return converter(moeda, digitado, cotacao);
    }

    // --- o recebimento dentro do fechamento ---

    /**
     * Grava um recebimento do fechamento do acerto (chamado só pelo {@code ServicoDeFechamento}, que já travou o vendedor e o
     * acerto). {@code naContaId} nulo: paga o acerto ({@code aplicado_em = ACERTO}, só dinheiro ou Transferência). Com conta:
     * paga a conta antiga do vendedor ({@code aplicado_em = CONTAS}) e não entra nos totais do acerto (ADR-0012, item 8).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID registrarNoFechamento(UUID usuarioId, UUID acertoId, UUID vendedorId, UUID recebimentoId, String forma, ValorRecebido recebido,
            boolean paraContaAntiga, UUID contaId, Instant hora) {
        UUID id = Validacoes.idDoPedido(recebimentoId);
        String formaConferida = formaDoRepasse(forma, false);
        BigDecimal valorConferido = valorEmReais(recebido.valor());
        try {
            jdbc.update("""
                    INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, acerto_id, aplicado_em, forma, valor, recebido_em,
                        registrado_por, moeda, valor_na_moeda, cotacao_id) VALUES (?, ?, 'ACERTO', ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, vendedorId,
                    acertoId, paraContaAntiga ? "CONTAS" : "ACERTO", formaConferida, valorConferido, Timestamp.from(hora), usuarioId,
                    recebido.moeda(), recebido.valorNaMoeda(), recebido.cotacaoId());
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
                SELECT vendedor_id, momento, acerto_id, aplicado_em, forma, valor, estorna_id, moeda, valor_na_moeda, cotacao_id
                  FROM corona.recebimentos_vendedor WHERE id = ? AND deleted_at IS NULL""", recebimentoId);
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
        BigDecimal naMoeda = (BigDecimal) o.get("valor_na_moeda");
        jdbc.update("""
                INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, acerto_id, aplicado_em, forma, valor, recebido_em,
                    registrado_por, motivo, estorna_id, moeda, valor_na_moeda, cotacao_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                estornoId, vendedorId, o.get("momento"), o.get("acerto_id"), aplicadoEm, o.get("forma"), valor.negate(), Timestamp.from(hora),
                usuarioId, motivo, recebimentoId, o.get("moeda"), naMoeda == null ? null : naMoeda.negate(), o.get("cotacao_id"));
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

    /**
     * A moeda do pedido (400 se não é REAL, DOLAR ou GUARANI). Em REAL vem só o {@code valor}; nas outras, só o {@code valorNaMoeda}:
     * o app nunca manda o valor convertido (o servidor converte).
     */
    public static String moedaDoRecebimento(String moeda, BigDecimal valor, BigDecimal valorNaMoeda) {
        String m = moeda == null ? REAL : moeda;
        if (!MOEDAS.contains(m)) {
            throw Validacoes.recusar("moeda", "Escolha a moeda do recebimento: Real, Dólar ou Guarani.");
        }
        if (REAL.equals(m) && valorNaMoeda != null) {
            throw Validacoes.recusar("valorNaMoeda", "Em reais, informe só o valor.");
        }
        if (!REAL.equals(m) && valor != null) {
            throw Validacoes.recusar("valor", "Em Dólar ou Guarani, informe só o valor na moeda: o sistema converte para reais.");
        }
        return m;
    }

    /** O valor digitado em Dólar (até 2 casas) ou Guarani (inteiro), maior que zero, com escala 2 (400 fora disso). */
    public static BigDecimal valorNaMoeda(String moeda, BigDecimal valor) {
        if (valor == null || valor.signum() <= 0) {
            throw Validacoes.recusar("valorNaMoeda", "Informe o valor recebido, maior que zero.");
        }
        if ("GUARANI".equals(moeda) && valor.stripTrailingZeros().scale() > 0) {
            throw Validacoes.recusar("valorNaMoeda", "O Guarani não tem centavos: informe o valor sem vírgula.");
        }
        if (valor.stripTrailingZeros().scale() > Dinheiro.ESCALA || valor.compareTo(VALOR_MAXIMO) > 0) {
            throw Validacoes.recusar("valorNaMoeda", "Use um valor em Dólar com no máximo duas casas depois da vírgula.");
        }
        return valor.setScale(Dinheiro.ESCALA);
    }

    /**
     * Converte o valor digitado para Real pela taxa de venda da cotação dada (R$ = valor x taxa, HALF_UP com 2 casas; ADR-0006,
     * adendo do item 13). Recusa (400) o que dá menos de R$ 0,01 ou passa do máximo.
     */
    public static ValorRecebido converter(String moeda, BigDecimal valorNaMoeda, Cotacao cotacao) {
        BigDecimal reais = "DOLAR".equals(moeda)
                ? ConversaoDeMoeda.dolarParaReais(valorNaMoeda, cotacao.taxas().usdVenda())
                : cotacao.guaranisPorReal() != null
                        ? ConversaoDeMoeda.guaraniParaReaisPorGuaranisPorReal(valorNaMoeda, cotacao.guaranisPorReal())
                        : ConversaoDeMoeda.guaraniParaReais(valorNaMoeda, cotacao.taxas().pygVenda());
        if (reais.signum() <= 0) {
            throw Validacoes.recusar("valorNaMoeda", "O valor é pequeno demais para virar reais. Confira o valor.");
        }
        if (reais.compareTo(VALOR_MAXIMO) > 0) {
            throw Validacoes.recusar("valorNaMoeda", "O valor passa do máximo que o sistema aceita. Confira o valor.");
        }
        return new ValorRecebido(reais, moeda, valorNaMoeda, cotacao.id());
    }

    private static String resumo(UUID vendedorId, String forma, BigDecimal valor, Object contaId) {
        String texto = vendedorId + "|" + forma + "|" + valor.toPlainString() + "|" + contaId;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
