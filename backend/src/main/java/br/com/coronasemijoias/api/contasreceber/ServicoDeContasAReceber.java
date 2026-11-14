package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contas a receber do cliente (tarefa 7.3; ADR-0012, itens 2 e 7). Único lugar que escreve em {@code contas_receber}
 * (inclusive os atalhos {@code valor_pago}, {@code valor_cancelado} e {@code status}), sempre na transação de quem chama
 * e com a linha travada. O app nunca manda valor de parcela: o servidor divide o que falta pagar com
 * {@link Dinheiro#dividirEmParcelas}. Tudo em Real; vencimento é o dia do calendário de Brasília.
 */
@Service
public class ServicoDeContasAReceber {

    public static final int MAXIMO_DE_PARCELAS = 24;
    private static final int MAXIMO_DE_MESES_ATE_O_PRIMEIRO_VENCIMENTO = 12;

    /** O que o app pode pedir: quantas parcelas e quando vence a primeira (as outras são mensais). */
    public record Parcelamento(Integer quantidade, LocalDate primeiroVencimento) {
    }

    /** Uma parcela gravada. */
    public record Parcela(UUID id, int numero, int total, LocalDate vencimento, BigDecimal valor) {
    }

    /** O recebimento do cliente depois da venda: o id vem do aplicativo (idempotência); valor e forma, do vendedor. */
    public record Recebimento(UUID id, BigDecimal valor, String forma) {
    }

    /** {@code jaExistia}: o recebimento (ou o estorno) já estava gravado (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID id, UUID vendaId, boolean jaExistia) {
    }

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final Set<String> FORMAS_DO_RECEBIMENTO = Set.of("DINHEIRO", "PIX");
    private static final int LIMITE_DO_MOTIVO = 400;

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;

    ServicoDeContasAReceber(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
        this.relogio = relogio;
    }

    /**
     * Gera as parcelas da venda sobre o que falta pagar ({@code emAberto}: total menos o pago na hora). Sem nada a
     * receber, não grava nada (e recusa o parcelamento pedido). Sem {@code parcelamento}, uma parcela um mês depois da
     * venda. {@code online}: o primeiro vencimento não pode ser antes do dia da venda; pelo envio a data é aceita (a
     * correção da hora pode mudar o dia).
     */
    @Transactional
    public List<Parcela> gerarParcelas(UUID vendaId, UUID clienteId, BigDecimal emAberto, Parcelamento parcelamento,
            LocalDate diaDaVenda, boolean online) {
        if (emAberto.signum() <= 0) {
            if (parcelamento != null) {
                throw Validacoes.recusar("parcelamento", "Esta venda já está paga. Não há parcelas.");
            }
            return List.of();
        }
        int quantidade = 1;
        LocalDate primeiro = diaDaVenda.plusMonths(1);
        if (parcelamento != null) {
            if (parcelamento.quantidade() == null || parcelamento.quantidade() < 1
                    || parcelamento.quantidade() > MAXIMO_DE_PARCELAS) {
                throw Validacoes.recusar("parcelamento", "O número de parcelas vai de 1 a " + MAXIMO_DE_PARCELAS + ".");
            }
            quantidade = parcelamento.quantidade();
            if (parcelamento.primeiroVencimento() != null) {
                primeiro = parcelamento.primeiroVencimento();
            }
            if (online && primeiro.isBefore(diaDaVenda)) {
                throw Validacoes.recusar("parcelamento", "O primeiro vencimento não pode ser antes do dia da venda.");
            }
            if (primeiro.isAfter(diaDaVenda.plusMonths(MAXIMO_DE_MESES_ATE_O_PRIMEIRO_VENCIMENTO))) {
                throw Validacoes.recusar("parcelamento", "O primeiro vencimento está longe demais. Escolha uma data até um ano depois da venda.");
            }
        }
        List<BigDecimal> valores;
        try {
            valores = Dinheiro.dividirEmParcelas(emAberto, quantidade);
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar("parcelamento", "O valor a prazo (R$ " + emAberto.toPlainString().replace('.', ',')
                    + ") é pequeno demais para " + quantidade + " parcelas. Use menos parcelas.");
        }
        List<Parcela> parcelas = new ArrayList<>();
        for (int k = 1; k <= quantidade; k++) {
            // Sempre a partir da primeira: 31/01, 28/02, 31/03 (nunca 31/01, 28/02, 28/03).
            LocalDate vencimento = primeiro.plusMonths(k - 1L);
            UUID id = UuidV7.gerar();
            jdbc.update("""
                    INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                        total_parcelas, vencimento, valor)
                    VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, ?, ?, ?, ?)""", id, clienteId, vendaId, vendaId, k, quantidade,
                    vencimento, valores.get(k - 1));
            parcelas.add(new Parcela(id, k, quantidade, vencimento, valores.get(k - 1)));
        }
        return parcelas;
    }

    // --- recebimento posterior ---

    /**
     * Registra o recebimento do cliente depois da venda e o distribui pelas parcelas abertas, da que vence primeiro para
     * a que vence depois (empate pela parcela). Trava a venda e depois as parcelas (por id): nunca a maleta, porque a
     * parcela vive meses depois do acerto. {@code vendedorId} é quem recebe (a venda tem de ser dele); {@code quando}
     * nulo é agora.
     */
    @Transactional
    public Resultado receber(UUID vendaId, UUID vendedorId, UUID usuarioId, UUID aparelho, Recebimento pedido, Instant quando) {
        if (pedido == null) {
            throw Validacoes.recusar("recebimento", "Informe o recebimento.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.valor() == null || pedido.valor().signum() <= 0) {
            throw Validacoes.recusar("valor", "Informe o valor recebido, maior que zero.");
        }
        if (pedido.valor().stripTrailingZeros().scale() > Dinheiro.ESCALA
                || pedido.valor().compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw Validacoes.recusar("valor", "Use um valor em reais com no máximo duas casas depois da vírgula.");
        }
        BigDecimal valor = pedido.valor().setScale(Dinheiro.ESCALA);
        if (pedido.forma() == null || !FORMAS_DO_RECEBIMENTO.contains(pedido.forma())) {
            throw Validacoes.recusar("forma", "O recebimento é só em dinheiro ou transferência.");
        }
        Map<String, Object> venda = trancarVenda(vendaId, vendedorId);

        // Idempotência: o mesmo id, a mesma venda, o mesmo valor e a mesma forma é o mesmo recebimento.
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT venda_id, tipo, forma, valor, posterior FROM corona.pagamentos_cliente WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            if (vendaId.equals(e.get("venda_id")) && "PAGAMENTO".equals(e.get("tipo")) && Boolean.TRUE.equals(e.get("posterior"))
                    && pedido.forma().equals(e.get("forma")) && valor.compareTo((BigDecimal) e.get("valor")) == 0) {
                return new Resultado(id, vendaId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_REGISTRADO_DIFERENTE);
        }
        if ("ESTORNADA".equals(venda.get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        br.com.coronasemijoias.api.vendedor.VendedorAtivo.exigir(jdbc, (UUID) venda.get("vendedor_id"));

        List<Conta> abertas = trancarContas(vendaId).stream().filter(c -> c.emAberto().signum() > 0).toList();
        BigDecimal emAberto = abertas.stream().map(Conta::emAberto).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (emAberto.signum() == 0) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_SEM_VALOR_EM_ABERTO);
        }
        if (valor.compareTo(emAberto) > 0) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_ACIMA_DO_EM_ABERTO,
                    "O valor passa do que falta pagar nesta venda (R$ " + emAberto.toPlainString().replace('.', ',')
                            + "). Confira o valor.");
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        Instant hora = quando == null ? relogio.instant() : quando;
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior)
                VALUES (?, ?, 'PAGAMENTO', ?, ?, ?, true)""", id, vendaId, pedido.forma(), valor, Timestamp.from(hora));
        List<String> distribuicao = new ArrayList<>();
        BigDecimal resta = valor;
        for (Conta c : abertas.stream().sorted(Comparator.comparing(Conta::vencimento).thenComparing(Conta::parcela)).toList()) {
            if (resta.signum() == 0) {
                break;
            }
            BigDecimal parte = resta.min(c.emAberto());
            jdbc.update("""
                    INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, pagamento_cliente_id)
                    VALUES (?, ?, 'PAGAMENTO', ?, ?, ?)""", UuidV7.gerar(), c.id(), parte, Timestamp.from(hora), id);
            atualizarAtalhos(c, c.pago().add(parte), c.cancelado());
            distribuicao.add(c.parcela() + ": R$ " + parte.toPlainString());
            resta = resta.subtract(parte);
        }
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_id", vendaId.toString());
        depois.put("valor", valor.toPlainString());
        depois.put("forma", pedido.forma());
        depois.put("parcelas", distribuicao);
        auditoria.registrar(Ator.usuario(usuarioId), "RECEBIMENTO_CLIENTE_REGISTRADO", "pagamentos_cliente", id, null, depois);
        return new Resultado(id, vendaId, false);
    }

    /**
     * Desfaz um recebimento posterior: um {@code ESTORNO_PAGAMENTO} negativo e uma baixa negativa para cada baixa do
     * original; os atalhos voltam. O pagamento da hora da venda não passa por aqui (corrige-se estornando a venda).
     * {@code vendedorId} nulo é a dona (sem limite de dia); o vendedor só estorna o que recebeu no mesmo dia (Brasília).
     */
    @Transactional
    public Resultado estornarRecebimento(UUID pagamentoId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoEstorno,
            String motivo, Instant momento) {
        UUID estornoId = Validacoes.idDoPedido(idDoEstorno);
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o recebimento.");
        }
        if (limpo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT p.venda_id, p.tipo, p.posterior, p.forma, p.valor, p.pago_em, v.vendedor_id
                  FROM corona.pagamentos_cliente p JOIN corona.vendas v ON v.id = p.venda_id
                 WHERE p.id = ? AND p.deleted_at IS NULL""", pagamentoId);
        if (achado.isEmpty() || !"PAGAMENTO".equals(achado.get(0).get("tipo"))
                || (vendedorId != null && !vendedorId.equals(achado.get(0).get("vendedor_id")))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.RECEBIMENTO_NAO_ENCONTRADO);
        }
        Map<String, Object> original = achado.get(0);
        if (!Boolean.TRUE.equals(original.get("posterior"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA);
        }
        UUID vendaId = (UUID) original.get("venda_id");
        Map<String, Object> venda = trancarVenda(vendaId, null);

        List<Map<String, Object>> jaEstornado = jdbc.queryForList(
                "SELECT id FROM corona.pagamentos_cliente WHERE estorna_id = ?", pagamentoId);
        if (!jaEstornado.isEmpty()) {
            if (estornoId.equals(jaEstornado.get(0).get("id"))) {
                return new Resultado(estornoId, vendaId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_ESTORNADO);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.pagamentos_cliente WHERE id = ?)",
                Boolean.class, estornoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECEBIMENTO_JA_REGISTRADO_DIFERENTE);
        }
        if ("ESTORNADA".equals(venda.get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        // Venda com devolução não cancelada não volta a dever: o dinheiro já voltou ao cliente pela devolução. A devolução
        // trava a maleta e a venda antes de gravar; esta rota já travou a venda: as duas nunca passam juntas.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.devolucoes WHERE venda_id = ? AND situacao <> 'CANCELADA')", Boolean.class,
                vendaId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_COM_DEVOLUCAO);
        }
        Instant hora = momento == null ? relogio.instant() : momento;
        if (vendedorId != null) {
            LocalDate diaDoRecebimento = LocalDate.ofInstant(((Timestamp) original.get("pago_em")).toInstant(), BRASILIA);
            if (!diaDoRecebimento.equals(LocalDate.ofInstant(hora, BRASILIA))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ESTORNO_DE_RECEBIMENTO_SO_DA_DONA);
            }
        }

        Map<UUID, Conta> contas = new LinkedHashMap<>();
        trancarContas(vendaId).forEach(c -> contas.put(c.id(), c));
        List<Map<String, Object>> baixas = jdbc.queryForList("""
                SELECT id, conta_id, valor FROM corona.baixas_receber
                 WHERE pagamento_cliente_id = ? AND tipo = 'PAGAMENTO' AND estorna_id IS NULL ORDER BY conta_id, id""", pagamentoId);
        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        BigDecimal valor = (BigDecimal) original.get("valor");
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior, estorna_id)
                VALUES (?, ?, 'ESTORNO_PAGAMENTO', ?, ?, ?, true, ?)""", estornoId, vendaId, original.get("forma"), valor.negate(),
                Timestamp.from(hora), pagamentoId);
        for (Map<String, Object> b : baixas) {
            UUID contaId = (UUID) b.get("conta_id");
            BigDecimal parte = (BigDecimal) b.get("valor");
            jdbc.update("""
                    INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, pagamento_cliente_id, estorna_id)
                    VALUES (?, ?, 'PAGAMENTO', ?, ?, ?, ?)""", UuidV7.gerar(), contaId, parte.negate(), Timestamp.from(hora),
                    estornoId, b.get("id"));
            Conta atual = contas.get(contaId);
            contas.put(contaId, atualizarAtalhos(atual, atual.pago().subtract(parte), atual.cancelado()));
        }
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_id", vendaId.toString());
        depois.put("recebimento_id", pagamentoId.toString());
        depois.put("valor", valor.negate().toPlainString());
        depois.put("motivo", limpo);
        auditoria.registrar(Ator.usuario(usuarioId), "RECEBIMENTO_CLIENTE_ESTORNADO", "pagamentos_cliente", estornoId, null, depois);
        return new Resultado(estornoId, vendaId, false);
    }

    // --- estorno da venda ---

    /**
     * Cancela o que está em aberto de cada parcela da venda, da última para a primeira, por baixa {@code CANCELAMENTO}
     * (motivo {@code ESTORNO_VENDA}); o que já foi pago continua pago (o dinheiro volta pelo reembolso da venda). Chamado
     * pelo estorno da venda, que já travou a remessa e a venda (ordem de travas: remessa, venda, créditos, parcelas).
     * Devolve o valor cancelado.
     */
    @Transactional
    public BigDecimal cancelarEmAberto(UUID vendaId, Instant momento) {
        Instant hora = momento == null ? relogio.instant() : momento;
        BigDecimal cancelado = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        for (Conta c : trancarContas(vendaId).stream().sorted(Comparator.comparing(Conta::parcela).reversed()).toList()) {
            BigDecimal aberto = c.emAberto();
            if (aberto.signum() <= 0) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, motivo_cancelamento)
                    VALUES (?, ?, 'CANCELAMENTO', ?, ?, 'ESTORNO_VENDA')""", UuidV7.gerar(), c.id(), aberto, Timestamp.from(hora));
            atualizarAtalhos(c, c.pago(), c.cancelado().add(aberto));
            cancelado = cancelado.add(aberto);
        }
        return cancelado;
    }

    // --- devolução (tarefa 6.6) ---

    /**
     * O que o cliente ainda deve nas parcelas da venda (valor menos pago menos cancelado). Leitura: quem chama já travou
     * a venda, e todo escritor de parcelas trava a venda antes, então o número é estável até o fim da transação.
     */
    public BigDecimal emAberto(UUID vendaId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(valor - valor_pago - valor_cancelado), 0) FROM corona.contas_receber WHERE venda_id = ?""",
                BigDecimal.class, vendaId).setScale(Dinheiro.ESCALA);
    }

    /**
     * A devolução abate o que o cliente devia: baixa {@code CANCELAMENTO} (motivo {@code DEVOLUCAO}) nas parcelas em
     * aberto, da última para a primeira, até {@code valor} (que nunca passa do em aberto). A devolução já está gravada
     * (a baixa a referencia). Ordem de travas: venda (quem chama), créditos, parcelas por id, por último. Devolve o valor
     * abatido de fato.
     */
    @Transactional
    public BigDecimal abaterNaDevolucao(UUID vendaId, UUID devolucaoId, BigDecimal valor, Instant momento) {
        BigDecimal resta = valor;
        BigDecimal abatido = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        if (resta.signum() <= 0) {
            return abatido;
        }
        Instant hora = momento == null ? relogio.instant() : momento;
        for (Conta c : trancarContas(vendaId).stream().sorted(Comparator.comparing(Conta::parcela).reversed()).toList()) {
            if (resta.signum() == 0) {
                break;
            }
            BigDecimal parte = resta.min(c.emAberto());
            if (parte.signum() <= 0) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, motivo_cancelamento, devolucao_id)
                    VALUES (?, ?, 'CANCELAMENTO', ?, ?, 'DEVOLUCAO', ?)""", UuidV7.gerar(), c.id(), parte, Timestamp.from(hora),
                    devolucaoId);
            atualizarAtalhos(c, c.pago(), c.cancelado().add(parte));
            resta = resta.subtract(parte);
            abatido = abatido.add(parte);
        }
        return abatido;
    }

    /**
     * "Cancelar devolução": desfaz o abatimento com uma baixa negativa (estorno) para cada cancelamento ainda ativo da
     * devolução; as parcelas voltam a ter o que tinham (aberta ou parcial). Nunca UPDATE de baixa. Chamado pelo cancelar,
     * que já travou maleta, venda, devolução e crédito (as parcelas ficam por último). Devolve o valor desfeito.
     */
    @Transactional
    public BigDecimal desfazerAbatimento(UUID devolucaoId, Instant momento) {
        BigDecimal desfeito = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        List<Map<String, Object>> ativas = jdbc.queryForList("""
                SELECT b.id, b.conta_id, b.valor FROM corona.baixas_receber b
                 WHERE b.devolucao_id = ? AND b.tipo = 'CANCELAMENTO' AND b.estorna_id IS NULL
                   AND NOT EXISTS (SELECT 1 FROM corona.baixas_receber e WHERE e.estorna_id = b.id)
                 ORDER BY b.id""", devolucaoId);
        if (ativas.isEmpty()) {
            return desfeito;
        }
        UUID vendaId = jdbc.queryForObject("SELECT venda_id FROM corona.devolucoes WHERE id = ?", UUID.class, devolucaoId);
        Instant hora = momento == null ? relogio.instant() : momento;
        Map<UUID, Conta> contas = new LinkedHashMap<>();
        trancarContas(vendaId).forEach(c -> contas.put(c.id(), c));
        for (Map<String, Object> b : ativas) {
            UUID contaId = (UUID) b.get("conta_id");
            BigDecimal parte = (BigDecimal) b.get("valor");
            jdbc.update("""
                    INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, motivo_cancelamento, devolucao_id,
                        estorna_id)
                    VALUES (?, ?, 'CANCELAMENTO', ?, ?, 'DEVOLUCAO', ?, ?)""", UuidV7.gerar(), contaId, parte.negate(),
                    Timestamp.from(hora), devolucaoId, b.get("id"));
            Conta atual = contas.get(contaId);
            contas.put(contaId, atualizarAtalhos(atual, atual.pago(), atual.cancelado().subtract(parte)));
            desfeito = desfeito.add(parte);
        }
        return desfeito;
    }

    // --- internos ---

    /** Uma parcela travada, com o que já foi pago e cancelado. */
    private record Conta(UUID id, int parcela, LocalDate vencimento, BigDecimal valor, BigDecimal pago, BigDecimal cancelado) {

        BigDecimal emAberto() {
            return valor.subtract(pago).subtract(cancelado);
        }
    }

    /** Trava a venda (404 se não existe ou não é do vendedor; {@code vendedorId} nulo é a dona) e devolve a situação dela. */
    private Map<String, Object> trancarVenda(UUID vendaId, UUID vendedorId) {
        List<Map<String, Object>> v = jdbc.queryForList(
                "SELECT vendedor_id, status FROM corona.vendas WHERE id = ? AND deleted_at IS NULL FOR UPDATE", vendaId);
        if (v.isEmpty() || (vendedorId != null && !vendedorId.equals(v.get(0).get("vendedor_id")))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        return v.get(0);
    }

    /** As parcelas da venda, travadas em ordem de id (a ordem única de travas; ADR-0012, item 13). */
    private List<Conta> trancarContas(UUID vendaId) {
        return jdbc.query("""
                SELECT id, parcela, vencimento, valor, valor_pago, valor_cancelado FROM corona.contas_receber
                 WHERE venda_id = ? ORDER BY id FOR UPDATE""", (rs, n) -> new Conta(rs.getObject("id", UUID.class),
                rs.getInt("parcela"), rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"),
                rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_cancelado")), vendaId);
    }

    /**
     * O único UPDATE de {@code contas_receber}: os atalhos e o status, depois que a baixa já foi gravada. O gatilho do
     * banco confere que batem com a soma das baixas.
     */
    private Conta atualizarAtalhos(Conta c, BigDecimal pago, BigDecimal cancelado) {
        String status;
        if (cancelado.signum() > 0 && pago.add(cancelado).compareTo(c.valor()) == 0) {
            status = "CANCELADA";
        } else if (pago.signum() == 0 && cancelado.signum() == 0) {
            status = "ABERTA";
        } else if (pago.compareTo(c.valor()) == 0) {
            status = "PAGA";
        } else {
            status = "PARCIAL";
        }
        jdbc.update("UPDATE corona.contas_receber SET valor_pago = ?, valor_cancelado = ?, status = ? WHERE id = ?", pago, cancelado,
                status, c.id());
        return new Conta(c.id(), c.parcela(), c.vencimento(), c.valor(), pago, cancelado);
    }
}
