package br.com.coronasemijoias.api.vendas;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.OrdemDoBanco;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Parcela;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Parcelamento;
import br.com.coronasemijoias.api.creditos.ServicoDeCreditos;
import java.math.BigDecimal;
import java.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 * Venda do vendedor e estorno (tarefa 4.2; ADR-0011). Regras que valem aqui:
 * <ul>
 *   <li>O app manda produto, quantidade, desconto e pagamentos. Preço, subtotal e total saem do servidor, do preço
 *       congelado da remessa ({@code preco_remessa}).</li>
 *   <li>Toda operação trava a linha da remessa primeiro; o saldo da peça é {@code qtd_enviada} mais os movimentos da
 *       maleta (nunca um número guardado) e não passa de zero.</li>
 *   <li>Venda, itens, pagamentos e movimentos são imutáveis. Corrigir é estornar a venda inteira.</li>
 *   <li>Idempotência pelo id do aplicativo: mesmo id e mesmo conteúdo é "já gravado" (200); conteúdo diferente, 409.</li>
 *   <li>Crédito do cliente (tarefa 6.5): a forma {@code CREDITO} leva o crédito; ele é travado depois da maleta, em ordem
 *       de id, e conferido por {@link ServicoDeCreditos} (do vendedor, do cliente da venda, com saldo). O estorno devolve
 *       o crédito. A venda nova de uma troca ({@code troca_id}) só se desfaz pelo "Cancelar troca".</li>
 * </ul>
 */
@Service
public class ServicoDeVendas {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final int LIMITE_DE_LINHAS = 200;
    private static final int LIMITE_DE_PAGAMENTOS = 10;
    private static final int LIMITE_DA_QUANTIDADE = 100_000;
    private static final int LIMITE_DO_MOTIVO = 400;
    private static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX", "CARTAO", "CHEQUE", "CREDITO");
    private static final String CREDITO = "CREDITO";

    public record LinhaDoPedido(UUID produtoId, Integer quantidade, BigDecimal desconto) {
    }

    /** {@code creditoId}: o crédito do cliente usado, obrigatório na forma {@code CREDITO} e proibido nas outras (tarefa 6.5). */
    public record PagamentoDoPedido(UUID id, BigDecimal valor, String forma, UUID creditoId) {

        /** Sem crédito (as formas da Fase 4 e o formato 1 do envio). */
        public PagamentoDoPedido(UUID id, BigDecimal valor, String forma) {
            this(id, valor, forma, null);
        }
    }

    /**
     * O crédito da troca que paga a venda nova (tarefa 6.5): o servidor calcula o pagamento {@code CREDITO} de
     * {@code min(disponivel, total)} com o id {@code pagamentoId} do aplicativo. {@code disponivel} é o valor devolvido de A.
     */
    public record CreditoDaTroca(UUID trocaId, UUID creditoId, UUID pagamentoId, BigDecimal disponivel) {
    }

    /** A venda nova da troca gravada: o total e quanto do crédito da troca ela usou. */
    public record VendaDaTrocaGravada(UUID vendaId, BigDecimal total, BigDecimal creditoUsado) {
    }

    /**
     * {@code totalConferido} é o total que a tela mostrou: só serve para avisar divergência (nunca vale).
     * {@code parcelamento} é opcional (tarefa 7.3): quantas parcelas e o primeiro vencimento do que falta pagar; o servidor
     * calcula os valores. Sem ele, o que falta vira uma parcela um mês depois da venda.
     */
    public record PedidoDeVenda(UUID id, UUID remessaId, UUID clienteId, List<LinhaDoPedido> itens,
            List<PagamentoDoPedido> pagamentos, BigDecimal totalConferido, Parcelamento parcelamento) {

        /** Sem parcelamento (as vendas da Fase 4 e o formato 1 do envio). */
        public PedidoDeVenda(UUID id, UUID remessaId, UUID clienteId, List<LinhaDoPedido> itens,
                List<PagamentoDoPedido> pagamentos, BigDecimal totalConferido) {
            this(id, remessaId, clienteId, itens, pagamentos, totalConferido, null);
        }
    }

    /** {@code jaExistia}: a venda (ou o estorno) já estava gravada (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID vendaId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Configuracoes configuracoes;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;
    private final ServicoDeCreditos creditos;
    private final ServicoDeContasAReceber contas;

    ServicoDeVendas(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, Configuracoes configuracoes,
            OrigemDoDispositivo origem, Clock relogio, ServicoDeCreditos creditos, ServicoDeContasAReceber contas) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.configuracoes = configuracoes;
        this.origem = origem;
        this.relogio = relogio;
        this.creditos = creditos;
        this.contas = contas;
    }

    // --- venda ---

    private record Linha(UUID produtoId, int quantidade, BigDecimal desconto) {
    }

    private record Pagamento(UUID id, BigDecimal valor, String forma, UUID creditoId) {
    }

    private record Peca(String nome, int saldo, BigDecimal preco) {
    }

    @Transactional
    public Resultado vender(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeVenda pedido) {
        return vender(vendedorId, usuarioId, aparelho, pedido, null, false);
    }

    /**
     * A venda que chega pelo envio do Corona Vendas (tarefa 5.5): {@code vendidaEm} é a hora já corrigida pelo servidor
     * (ADR-0013, item 11) e decide o dia da cotação; {@code horaAjustada} marca a venda cuja hora foi corrigida em mais
     * de 5 minutos. Nulo = agora (a rota online).
     */
    @Transactional
    public Resultado vender(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeVenda pedido, Instant vendidaEm,
            boolean horaAjustada) {
        return gravar(vendedorId, usuarioId, aparelho, pedido, vendidaEm, horaAjustada, null, false).resultado();
    }

    /**
     * A venda que chega pelo envio do computador (tarefa 6.7; ADR-0014, item 9, linha de 13/11/2026): como
     * {@link #vender(UUID, UUID, UUID, PedidoDeVenda, Instant, boolean)}, mas o crédito do cliente acima do saldo não
     * recusa: o pagamento em crédito fica limitado ao saldo que havia (nenhuma linha quando era zero), o resto fica a
     * receber e a dona revisa ({@code creditos_sem_saldo}). Crédito inexistente, de outro vendedor ou de outro cliente
     * continua recusado.
     */
    @Transactional
    public Resultado venderPeloEnvio(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeVenda pedido, Instant vendidaEm,
            boolean horaAjustada) {
        return gravar(vendedorId, usuarioId, aparelho, pedido, vendidaEm, horaAjustada, null, true).resultado();
    }

    /**
     * A venda nova da troca (tarefa 6.5), na transação da troca: grava {@code troca_id}, tira as peças da maleta como
     * {@code TROCA_SAIDA} e paga com o crédito da troca o menor entre o valor devolvido e o total; o resto vem nos
     * pagamentos do pedido (à vista, a prazo ou com outro crédito do cliente). Com B mais barata (ou igual), pagamento do
     * resto é recusado. {@code vendidaEm} é a hora da troca.
     */
    @Transactional
    public VendaDaTrocaGravada venderNaTroca(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeVenda pedido,
            Instant vendidaEm, boolean horaAjustada, CreditoDaTroca troca) {
        return venderNaTroca(vendedorId, usuarioId, aparelho, pedido, vendidaEm, horaAjustada, troca, false);
    }

    /** {@code peloEnvio}: a troca que chegou pelo envio do computador (o crédito do resto acima do saldo vira revisão). */
    @Transactional
    public VendaDaTrocaGravada venderNaTroca(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeVenda pedido,
            Instant vendidaEm, boolean horaAjustada, CreditoDaTroca troca, boolean peloEnvio) {
        Gravada g = gravar(vendedorId, usuarioId, aparelho, pedido, vendidaEm, horaAjustada, troca, peloEnvio);
        return new VendaDaTrocaGravada(g.resultado().vendaId(), g.total(), g.creditoDaTroca());
    }

    private record Gravada(Resultado resultado, BigDecimal total, BigDecimal creditoDaTroca) {
    }

    /** Um pagamento em crédito que o envio pediu acima do saldo: o pedido e o que o crédito ainda tinha. */
    private record SemSaldo(UUID pagamentoId, UUID creditoId, BigDecimal pedido, BigDecimal usado) {
    }

    private Gravada gravar(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeVenda pedido, Instant vendidaEm,
            boolean horaAjustada, CreditoDaTroca troca, boolean peloEnvio) {
        if (pedido == null) {
            throw Validacoes.recusar("venda", "Informe a venda.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.remessaId() == null) {
            throw Validacoes.recusar("remessaId", "Escolha a maleta da venda.");
        }
        if (pedido.clienteId() == null) {
            throw Validacoes.recusar("clienteId", "Escolha o cliente da venda.");
        }
        List<Linha> linhas = conferirLinhas(pedido.itens());
        List<Pagamento> pagamentos = conferirPagamentos(pedido.pagamentos());
        if (troca != null) {
            for (Pagamento p : pagamentos) {
                if (troca.creditoId().equals(p.creditoId()) || p.id().equals(troca.pagamentoId())) {
                    throw Validacoes.recusar("pagamentos", "O crédito da troca não é escolhido na venda: ele entra sozinho.");
                }
            }
        }
        if (pedido.totalConferido() != null && pedido.totalConferido().signum() < 0) {
            throw Validacoes.recusar("totalConferido", "O total conferido não pode ser negativo.");
        }

        // Trava a maleta (e confere que é do vendedor): duas vendas na mesma maleta esperam uma pela outra.
        Map<String, Object> remessa = trancarMaleta(pedido.remessaId(), vendedorId);

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT vendedor_id, cliente_id, remessa_id, troca_id FROM corona.vendas WHERE id = ?", id);
        if (!existente.isEmpty()) {
            // A troca confere os próprios ids antes: a venda nova nunca chega aqui já gravada.
            if (troca != null || existente.get(0).get("troca_id") != null
                    || !mesmaVenda(id, vendedorId, pedido, linhas, pagamentos, existente.get(0))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, troca != null ? CodigoErro.TROCA_JA_REGISTRADA_DIFERENTE
                        : CodigoErro.VENDA_JA_REGISTRADA_DIFERENTE);
            }
            return new Gravada(new Resultado(id, true), null, null);
        }
        // O id do pagamento também vem do aplicativo: já usado em outra venda é o mesmo caso do 409, não erro de banco.
        List<UUID> idsDosPagamentos = new ArrayList<>(pagamentos.stream().map(Pagamento::id).toList());
        if (troca != null) {
            idsDosPagamentos.add(troca.pagamentoId());
        }
        for (UUID pagamentoId : idsDosPagamentos) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM corona.pagamentos_cliente WHERE id = ?)
                        OR EXISTS (SELECT 1 FROM corona.creditos_sem_saldo WHERE pagamento_id = ?)""", Boolean.class, pagamentoId,
                    pagamentoId))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, troca != null ? CodigoErro.TROCA_JA_REGISTRADA_DIFERENTE
                        : CodigoErro.VENDA_JA_REGISTRADA_DIFERENTE);
            }
        }

        br.com.coronasemijoias.api.vendedor.VendedorAtivo.exigir(jdbc, vendedorId);
        String status = (String) remessa.get("status");
        if ("EM_ACERTO".equals(status)) {
            // A maleta está no acerto (ADR-0012): nada novo entra nela, nem a peça nova de uma troca (tarefa 6.7).
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_ACERTO);
        }
        if ("ENVIADA".equals(status)) {
            // Vender só depois de conferir (decisão de 11/11/2026; ADR-0011, dúvida 5): a conferência liga a maleta ao computador.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_CONFERIDA);
        }
        if (!"CONFERIDA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_ESTA_COM_VOCE);
        }
        UUID dono = (UUID) remessa.get("dispositivo_id");
        if (dono != null && !dono.equals(aparelho)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
        }
        conferirCliente(pedido.clienteId(), vendedorId);
        if (dono == null && aparelho != null) {
            // Maleta conferida sem computador (só a de antes da tarefa 5.3): a venda liga a maleta a este computador.
            origem.definirNaTransacaoAtual(aparelho);
            jdbc.update("UPDATE corona.remessas SET dispositivo_id = ? WHERE id = ?", aparelho, pedido.remessaId());
        }

        // Preço, saldo e totais: tudo do servidor, em ordem estável de produto.
        List<Linha> ordenadas = new ArrayList<>(linhas);
        ordenadas.sort(Comparator.comparing(Linha::produtoId, OrdemDoBanco.UUIDS));
        Map<UUID, Peca> pecas = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        BigDecimal desconto = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        for (Linha l : ordenadas) {
            Peca p = peca(pedido.remessaId(), l.produtoId());
            if (p == null) {
                throw Validacoes.recusar("produtoId", "Esta peça não consta na sua maleta.");
            }
            if (l.quantidade() > p.saldo()) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.VENDA_SEM_SALDO, p.saldo() == 0
                        ? "Não há mais peças de \"" + p.nome() + "\" na sua maleta."
                        : "Na sua maleta há só " + p.saldo() + " peça(s) de \"" + p.nome() + "\" e você informou "
                                + l.quantidade() + ". Confira a quantidade.");
            }
            BigDecimal linha = Dinheiro.precoDaLinha(p.preco(), l.quantidade());
            if (l.desconto().compareTo(linha) > 0) {
                throw Validacoes.recusar("desconto", "O desconto de \"" + p.nome() + "\" é maior que o valor da peça.");
            }
            pecas.put(l.produtoId(), p);
            subtotal = subtotal.add(linha);
            desconto = desconto.add(l.desconto());
        }
        int limite = configuracoes.inteiro(ChaveDeConfiguracao.LIMITE_DESCONTO_PERCENTUAL);
        BigDecimal maximo = Dinheiro.aplicarPorcentagem(subtotal, BigDecimal.valueOf(limite));
        if (desconto.compareTo(maximo) > 0) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.DESCONTO_ACIMA_DO_LIMITE, limite == 0
                    ? "A dona não liberou desconto nas vendas. Tire o desconto e tente de novo."
                    : "O desconto passa do limite que a dona definiu (" + limite + "% do valor, no máximo R$ "
                            + maximo.toPlainString().replace('.', ',') + "). Diminua o desconto.");
        }
        BigDecimal total = subtotal.subtract(desconto);
        BigDecimal creditoDaTroca = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        if (troca != null) {
            // O crédito da troca paga o menor entre o valor devolvido de A e o total de B (um caminho só, dúvida T1).
            creditoDaTroca = troca.disponivel().min(total);
            if (troca.disponivel().compareTo(total) >= 0 && !pagamentos.isEmpty()) {
                throw Validacoes.recusar("pagamentos",
                        "A peça nova vale menos que a devolvida: não há diferença a pagar. A sobra fica de crédito.");
            }
            if (creditoDaTroca.signum() > 0) {
                pagamentos.add(0, new Pagamento(troca.pagamentoId(), creditoDaTroca, CREDITO, troca.creditoId()));
            }
        }
        BigDecimal pago = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        for (Pagamento p : pagamentos) {
            pago = pago.add(p.valor());
        }
        if (pago.compareTo(total) > 0) {
            throw Validacoes.recusar("pagamentos", "O valor pago passa do total da venda (R$ "
                    + total.toPlainString().replace('.', ',') + "). Confira os pagamentos.");
        }
        // Os créditos usados: travados e conferidos em ordem de id, depois da maleta (ver ServicoDeCreditos).
        List<Pagamento> emCredito = new ArrayList<>(pagamentos.stream().filter(p -> p.creditoId() != null).toList());
        emCredito.sort(Comparator.comparing(Pagamento::creditoId, OrdemDoBanco.UUIDS));
        List<SemSaldo> semSaldo = new ArrayList<>();
        if (!peloEnvio) {
            for (Pagamento p : emCredito) {
                creditos.conferirUso(p.creditoId(), vendedorId, pedido.clienteId(), p.valor());
            }
        } else {
            // Pelo envio, o crédito acima do saldo não recusa (o que aconteceu no balcão): paga só o saldo que havia.
            List<Pagamento> limitados = new ArrayList<>();
            for (Pagamento p : emCredito) {
                BigDecimal usado = creditos.usarAteOSaldo(p.creditoId(), vendedorId, pedido.clienteId(), p.valor());
                if (usado.compareTo(p.valor()) < 0) {
                    semSaldo.add(new SemSaldo(p.id(), p.creditoId(), p.valor(), usado));
                    int onde = pagamentos.indexOf(p);
                    if (usado.signum() > 0) {
                        Pagamento menor = new Pagamento(p.id(), usado, p.forma(), p.creditoId());
                        pagamentos.set(onde, menor);
                        limitados.add(menor);
                    } else {
                        pagamentos.remove(onde);
                    }
                    pago = pago.subtract(p.valor()).add(usado);
                } else {
                    limitados.add(p);
                }
            }
            emCredito = limitados;
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        Instant quando = vendidaEm == null ? relogio.instant() : vendidaEm;
        UUID cotacao = cotacaoDoDia(LocalDate.ofInstant(quando, BRASILIA));
        jdbc.update("""
                INSERT INTO corona.vendas (id, vendedor_id, cliente_id, remessa_id, subtotal, desconto, total, cotacao_id,
                    vendida_em, hora_ajustada, troca_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, now()), ?, ?)""", id, vendedorId, pedido.clienteId(),
                pedido.remessaId(), subtotal, desconto, total, cotacao, vendidaEm == null ? null : Timestamp.from(vendidaEm),
                horaAjustada, troca == null ? null : troca.trocaId());
        // Na troca, a peça B sai da maleta como TROCA_SAIDA (ADR-0014, item 8): mesmo sinal e mesmo saldo da venda.
        String tipoDoMovimento = troca == null ? "VENDA" : "TROCA_SAIDA";
        for (Linha l : ordenadas) {
            Peca p = pecas.get(l.produtoId());
            jdbc.update("""
                    INSERT INTO corona.itens_venda (id, venda_id, produto_id, quantidade, preco_unitario, desconto)
                    VALUES (?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), id, l.produtoId(), l.quantidade(), p.preco(), l.desconto());
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, dispositivo_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), pedido.remessaId(), l.produtoId(), tipoDoMovimento,
                    -l.quantidade(), id, aparelho);
        }
        for (Pagamento p : pagamentos) {
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, credito_id)
                    VALUES (?, ?, 'PAGAMENTO', ?, ?, coalesce(?, now()), ?)""",
                    p.id(), id, p.forma(), p.valor(), vendidaEm == null ? null : Timestamp.from(vendidaEm), p.creditoId());
        }
        Instant horaDoUso = vendidaEm == null ? relogio.instant() : vendidaEm;
        for (Pagamento p : emCredito) {
            creditos.registrarUso(p.creditoId(), id, p.id(), p.valor(), horaDoUso);
        }
        for (SemSaldo f : semSaldo) {
            creditos.registrarSemSaldo(f.pagamentoId(), id, f.creditoId(), f.pedido(), f.usado(), horaDoUso, usuarioId);
        }

        // As parcelas do que falta pagar (tarefa 7.3), na mesma transação da venda.
        List<Parcela> parcelas = contas.gerarParcelas(id, pedido.clienteId(), total.subtract(pago), pedido.parcelamento(),
                LocalDate.ofInstant(quando, BRASILIA), vendidaEm == null);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("remessa_id", pedido.remessaId().toString());
        depois.put("cliente_id", pedido.clienteId().toString());
        depois.put("subtotal", subtotal.toPlainString());
        depois.put("desconto", desconto.toPlainString());
        depois.put("total", total.toPlainString());
        depois.put("pago", pago.toPlainString());
        if (!semSaldo.isEmpty()) {
            depois.put("credito_sem_saldo", semSaldo.stream().map(f -> f.pedido().subtract(f.usado()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString());
        }
        if (!emCredito.isEmpty()) {
            depois.put("pago_em_credito", emCredito.stream().map(Pagamento::valor).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .toPlainString());
        }
        if (!parcelas.isEmpty()) {
            depois.put("parcelas", parcelas.stream().map(q -> q.numero() + "/" + q.total() + " " + q.vencimento() + " R$ "
                    + q.valor().toPlainString()).toList());
        }
        if (troca != null) {
            depois.put("troca_id", troca.trocaId().toString());
        }
        if (pedido.totalConferido() != null && pedido.totalConferido().compareTo(total) != 0) {
            // Divergência é alerta, não rejeição: vale o total do servidor (ADR-0011, item 4).
            depois.put("alerta_total_do_app", pedido.totalConferido().toPlainString());
        }
        auditoria.registrar(Ator.usuario(usuarioId), "VENDA_REGISTRADA", "vendas", id, null, depois);
        return new Gravada(new Resultado(id, false), total, creditoDaTroca);
    }

    // --- estorno ---

    /**
     * Estorna a venda inteira. {@code vendedorId} nulo é a dona (sem limite de prazo); o vendedor só estorna a própria
     * venda e só no mesmo dia (ADR-0011, item 8). O reenvio com o mesmo id do estorno devolve 200.
     */
    @Transactional
    public Resultado estornar(UUID vendaId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoEstorno, String motivo) {
        return estornar(vendaId, vendedorId, usuarioId, aparelho, idDoEstorno, motivo, null);
    }

    /**
     * O estorno que chega pelo envio do Corona Vendas (tarefa 5.5): {@code momento} é a hora já corrigida pelo servidor
     * e decide o "mesmo dia" (em Brasília) do vendedor. Nulo = agora (a rota online).
     */
    @Transactional
    public Resultado estornar(UUID vendaId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoEstorno, String motivo,
            Instant momento) {
        return estornar(vendaId, vendedorId, usuarioId, aparelho, idDoEstorno, motivo, momento, false);
    }

    /**
     * O estorno da venda nova pelo "Cancelar troca" (tarefa 6.5), na transação dele: a troca já está cancelada. As regras
     * do estorno valem iguais (o vendedor só no dia da venda, que é o dia da troca).
     */
    @Transactional
    public Resultado estornarNaTroca(UUID vendaId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoEstorno,
            String motivo, Instant momento) {
        return estornar(vendaId, vendedorId, usuarioId, aparelho, idDoEstorno, motivo, momento, true);
    }

    private Resultado estornar(UUID vendaId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoEstorno, String motivo,
            Instant momento, boolean pelaTroca) {
        UUID estornoId = Validacoes.idDoPedido(idDoEstorno);
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar a venda.");
        }
        if (limpo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT vendedor_id, remessa_id FROM corona.vendas WHERE id = ? AND deleted_at IS NULL", vendaId);
        if (achada.isEmpty() || (vendedorId != null && !vendedorId.equals(achada.get(0).get("vendedor_id")))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        UUID remessaId = (UUID) achada.get(0).get("remessa_id");
        Map<String, Object> remessa = trancarMaleta(remessaId, (UUID) achada.get(0).get("vendedor_id"));

        Map<String, Object> venda = jdbc.queryForMap(
                "SELECT status, estorno_id, troca_id FROM corona.vendas WHERE id = ? FOR UPDATE", vendaId);
        if (venda.get("troca_id") != null && !pelaTroca) {
            // A venda nova de uma troca só se desfaz pelo "Cancelar troca" (dúvida T8); o banco é a segunda trava.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_DE_TROCA);
        }
        if ("ESTORNADA".equals(venda.get("status"))) {
            if (estornoId.equals(venda.get("estorno_id"))) {
                return new Resultado(vendaId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.vendas WHERE estorno_id = ?)", Boolean.class, estornoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_REGISTRADA_DIFERENTE);
        }
        // Venda com devolução não cancelada não é estornada (ADR-0014, item 1; dúvida R6). A venda já está travada, e a
        // devolução trava a mesma maleta e a mesma venda antes de gravar: as duas nunca passam juntas. O banco é a segunda trava.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.devolucoes WHERE venda_id = ? AND situacao <> 'CANCELADA')",
                Boolean.class, vendaId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_COM_DEVOLUCAO);
        }
        String statusDaMaleta = (String) remessa.get("status");
        if (!"ENVIADA".equals(statusDaMaleta) && !"CONFERIDA".equals(statusDaMaleta)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_ESTA_COM_VOCE);
        }
        if (vendedorId != null) {
            UUID dono = (UUID) remessa.get("dispositivo_id");
            if (dono != null && !dono.equals(aparelho)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
            }
            LocalDate diaDaVenda = jdbc.queryForObject(
                    "SELECT (vendida_em AT TIME ZONE 'America/Sao_Paulo')::date FROM corona.vendas WHERE id = ?", LocalDate.class, vendaId);
            Instant quando = momento == null ? relogio.instant() : momento;
            if (!diaDaVenda.equals(LocalDate.ofInstant(quando, BRASILIA))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ESTORNO_SO_DA_DONA);
            }
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        // A venda volta à maleta: VENDA com ESTORNO_VENDA (como sempre) e TROCA_SAIDA com o estorno do mesmo tipo.
        List<Map<String, Object>> baixas = jdbc.queryForList("""
                SELECT id, produto_id, quantidade, tipo FROM corona.movimentos_remessa
                 WHERE venda_id = ? AND tipo IN ('VENDA', 'TROCA_SAIDA') AND estorna_id IS NULL ORDER BY produto_id, id""", vendaId);
        for (Map<String, Object> b : baixas) {
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, dispositivo_id, estorna_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), remessaId, b.get("produto_id"),
                    "VENDA".equals(b.get("tipo")) ? "ESTORNO_VENDA" : "TROCA_SAIDA",
                    -((Number) b.get("quantidade")).intValue(), vendaId, aparelho, b.get("id"));
        }
        // O que o cliente pagou volta como reembolso (registro negativo, uma linha por pagamento, mesma forma). O pago em
        // crédito volta como reembolso em crédito e o uso é estornado: o crédito volta a ficar em aberto (dúvida T8). Os
        // créditos são travados por último, em ordem de id.
        List<Map<String, Object>> pagos = new ArrayList<>(jdbc.queryForList("""
                SELECT id, forma, valor, credito_id FROM corona.pagamentos_cliente p
                 WHERE venda_id = ? AND tipo = 'PAGAMENTO'
                   AND NOT EXISTS (SELECT 1 FROM corona.pagamentos_cliente e WHERE e.estorna_id = p.id)
                 ORDER BY pago_em, id""", vendaId));
        List<Map<String, Object>> emCredito = new ArrayList<>(pagos.stream().filter(p -> p.get("credito_id") != null).toList());
        emCredito.sort(Comparator.comparing(p -> (UUID) p.get("credito_id"), OrdemDoBanco.UUIDS));
        Instant horaDoEstorno = momento == null ? relogio.instant() : momento;
        for (Map<String, Object> p : pagos) {
            if (p.get("credito_id") != null) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor) VALUES (?, ?, 'REEMBOLSO', ?, ?)""",
                    UuidV7.gerar(), vendaId, p.get("forma"), ((BigDecimal) p.get("valor")).negate());
        }
        for (Map<String, Object> p : emCredito) {
            UUID reembolso = UuidV7.gerar();
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, credito_id)
                    VALUES (?, ?, 'REEMBOLSO', ?, ?, ?)""", reembolso, vendaId, p.get("forma"),
                    ((BigDecimal) p.get("valor")).negate(), p.get("credito_id"));
            creditos.estornarUso((UUID) p.get("id"), reembolso, horaDoEstorno);
        }
        // As parcelas em aberto são canceladas (tarefa 7.3): o que já foi pago continua pago e volta pelo reembolso acima.
        BigDecimal canceladoAReceber = contas.cancelarEmAberto(vendaId, momento);
        jdbc.update("""
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = coalesce(?, now()), estornada_por = ?,
                       motivo_estorno = ? WHERE id = ?""", estornoId, momento == null ? null : Timestamp.from(momento), usuarioId,
                limpo, vendaId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("status", "ESTORNADA");
        depois.put("motivo", limpo);
        if (canceladoAReceber.signum() > 0) {
            depois.put("cancelado_a_receber", canceladoAReceber.toPlainString());
        }
        auditoria.registrar(Ator.usuario(usuarioId), "VENDA_ESTORNADA", "vendas", vendaId, Map.of("status", "CONCLUIDA"), depois);
        return new Resultado(vendaId, false);
    }

    // --- internos ---

    private static List<Linha> conferirLinhas(List<LinhaDoPedido> pedidas) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw Validacoes.recusar("itens", "Informe pelo menos uma peça na venda.");
        }
        if (pedidas.size() > LIMITE_DE_LINHAS) {
            throw Validacoes.recusar("itens", "Venda no máximo " + LIMITE_DE_LINHAS + " peças diferentes por vez.");
        }
        List<Linha> linhas = new ArrayList<>();
        Set<UUID> produtos = new HashSet<>();
        for (LinhaDoPedido l : pedidas) {
            if (l == null || l.produtoId() == null) {
                throw Validacoes.recusar("produtoId", "Escolha a peça vendida.");
            }
            if (l.quantidade() == null || l.quantidade() <= 0) {
                throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
            }
            if (l.quantidade() > LIMITE_DA_QUANTIDADE) {
                throw Validacoes.recusar("quantidade", "A quantidade é grande demais. Confira o número.");
            }
            if (!produtos.add(l.produtoId())) {
                throw Validacoes.recusar("itens", "A mesma peça aparece mais de uma vez. Junte as quantidades numa linha só.");
            }
            BigDecimal desconto = l.desconto() == null ? BigDecimal.ZERO : l.desconto();
            if (desconto.signum() < 0) {
                throw Validacoes.recusar("desconto", "O desconto não pode ser negativo.");
            }
            linhas.add(new Linha(l.produtoId(), l.quantidade(), dinheiro(desconto, "desconto")));
        }
        return linhas;
    }

    private static List<Pagamento> conferirPagamentos(List<PagamentoDoPedido> pedidos) {
        List<Pagamento> pagamentos = new ArrayList<>();
        if (pedidos == null) {
            return pagamentos;
        }
        if (pedidos.size() > LIMITE_DE_PAGAMENTOS) {
            throw Validacoes.recusar("pagamentos", "Informe no máximo " + LIMITE_DE_PAGAMENTOS + " pagamentos.");
        }
        Set<UUID> ids = new HashSet<>();
        Set<UUID> creditosUsados = new HashSet<>();
        for (PagamentoDoPedido p : pedidos) {
            if (p == null || p.valor() == null || p.valor().signum() <= 0) {
                throw Validacoes.recusar("pagamentos", "Informe o valor pago, maior que zero.");
            }
            if (p.forma() == null || !FORMAS.contains(p.forma())) {
                throw Validacoes.recusar("forma", "Escolha como o cliente pagou: dinheiro, Pix, cartão, cheque ou crédito do cliente.");
            }
            if (CREDITO.equals(p.forma()) && p.creditoId() == null) {
                throw Validacoes.recusar("creditoId", "Escolha o crédito do cliente usado no pagamento.");
            }
            if (!CREDITO.equals(p.forma()) && p.creditoId() != null) {
                throw Validacoes.recusar("creditoId", "O crédito só vale com a forma de pagamento crédito do cliente.");
            }
            if (p.creditoId() != null && !creditosUsados.add(p.creditoId())) {
                throw Validacoes.recusar("pagamentos", "O mesmo crédito aparece mais de uma vez. Use o crédito uma vez só.");
            }
            UUID id = Validacoes.idDoPedido(p.id());
            if (!ids.add(id)) {
                throw Validacoes.recusar("pagamentos", "O mesmo pagamento aparece mais de uma vez.");
            }
            pagamentos.add(new Pagamento(id, dinheiro(p.valor(), "valor"), p.forma(), p.creditoId()));
        }
        return pagamentos;
    }

    /** Valor em Real com no máximo duas casas; mais que isso é recusado (nunca arredondado em silêncio). */
    private static BigDecimal dinheiro(BigDecimal valor, String campo) {
        if (valor.stripTrailingZeros().scale() > Dinheiro.ESCALA || valor.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw Validacoes.recusar(campo, "Use um valor em reais com no máximo duas casas depois da vírgula.");
        }
        return valor.setScale(Dinheiro.ESCALA);
    }

    /** Trava a linha da maleta e confere que é do vendedor (404 se não for, sem dizer de quem é). */
    private Map<String, Object> trancarMaleta(UUID remessaId, UUID vendedorId) {
        List<Map<String, Object>> r = jdbc.queryForList("""
                SELECT status, dispositivo_id FROM corona.remessas
                 WHERE id = ? AND vendedor_id = ? AND deleted_at IS NULL FOR UPDATE""", remessaId, vendedorId);
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        return r.get(0);
    }

    /** O cliente é da loja: qualquer cliente ativo serve (11/11/2026); o vendedor não entra na conta. */
    private void conferirCliente(UUID clienteId, UUID vendedorId) {
        boolean meu = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.clientes WHERE id = ? AND deleted_at IS NULL AND ativo)""",
                Boolean.class, clienteId));
        if (!meu) {
            throw Validacoes.recusar("clienteId", "Este cliente não está na lista da loja. Escolha ou cadastre o cliente.");
        }
    }

    /** Saldo atual da peça na maleta (qtd_enviada mais os movimentos) e o preço congelado. Vazio se a peça não está. */
    private Peca peca(UUID remessaId, UUID produtoId) {
        return jdbc.query("""
                SELECT p.nome, i.preco_remessa,
                       i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                               WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) AS saldo
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                (rs, n) -> new Peca(rs.getString(1), rs.getInt(3), rs.getBigDecimal(2)), remessaId, produtoId)
                .stream().findFirst().orElse(null);
    }

    /** A cotação mais recente até hoje (horário de Brasília), ou nenhuma. */
    private UUID cotacaoDoDia(LocalDate dia) {
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), dia).stream().findFirst().orElse(null);
    }

    /** O parcelamento do pedido bate com as parcelas gravadas (sem parcelamento no pedido, vale o que foi gravado). */
    private boolean mesmoParcelamento(UUID vendaId, Parcelamento pedido) {
        if (pedido == null) {
            return true;
        }
        List<Map<String, Object>> gravadas = jdbc.queryForList(
                "SELECT vencimento FROM corona.contas_receber WHERE venda_id = ? ORDER BY parcela", vendaId);
        if (pedido.quantidade() == null || gravadas.size() != pedido.quantidade()) {
            return false;
        }
        return pedido.primeiroVencimento() == null
                || pedido.primeiroVencimento().equals(((java.sql.Date) gravadas.get(0).get("vencimento")).toLocalDate());
    }

    /** O pedido repetido é a mesma venda? Compara dono, cliente, maleta, peças (com desconto) e pagamentos. */
    private boolean mesmaVenda(UUID id, UUID vendedorId, PedidoDeVenda pedido, List<Linha> linhas, List<Pagamento> pagamentos,
            Map<String, Object> gravada) {
        if (!vendedorId.equals(gravada.get("vendedor_id")) || !pedido.clienteId().equals(gravada.get("cliente_id"))
                || !pedido.remessaId().equals(gravada.get("remessa_id"))) {
            return false;
        }
        Map<UUID, Linha> pedidas = new LinkedHashMap<>();
        linhas.forEach(l -> pedidas.put(l.produtoId(), l));
        List<Map<String, Object>> itens = jdbc.queryForList(
                "SELECT produto_id, quantidade, desconto FROM corona.itens_venda WHERE venda_id = ?", id);
        if (itens.size() != pedidas.size()) {
            return false;
        }
        for (Map<String, Object> i : itens) {
            Linha l = pedidas.get(i.get("produto_id"));
            if (l == null || l.quantidade() != ((Number) i.get("quantidade")).intValue()
                    || l.desconto().compareTo((BigDecimal) i.get("desconto")) != 0) {
                return false;
            }
        }
        if (!mesmoParcelamento(id, pedido.parcelamento())) {
            return false;
        }
        // O pagamento em crédito limitado pelo envio (tarefa 6.7): o pedido está em creditos_sem_saldo, e o gravado (se
        // houver) tem o valor que o crédito ainda tinha.
        Map<UUID, Map<String, Object>> semSaldo = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT pagamento_id, credito_id, valor_pedido, valor_usado FROM corona.creditos_sem_saldo WHERE venda_id = ?""", id)
                .forEach(f -> semSaldo.put((UUID) f.get("pagamento_id"), f));
        Map<UUID, Pagamento> pagos = new LinkedHashMap<>();
        for (Pagamento p : pagamentos) {
            Map<String, Object> f = semSaldo.remove(p.id());
            if (f == null) {
                pagos.put(p.id(), p);
                continue;
            }
            if (!java.util.Objects.equals(p.creditoId(), f.get("credito_id"))
                    || p.valor().compareTo((BigDecimal) f.get("valor_pedido")) != 0) {
                return false;
            }
            BigDecimal usado = (BigDecimal) f.get("valor_usado");
            if (usado.signum() > 0) {
                pagos.put(p.id(), new Pagamento(p.id(), usado, p.forma(), p.creditoId()));
            }
        }
        if (!semSaldo.isEmpty()) {
            return false;
        }
        // Os recebimentos posteriores (tarefa 7.3) não fazem parte do pedido da venda: o reenvio depois deles é o mesmo.
        List<Map<String, Object>> gravados = jdbc.queryForList(
                "SELECT id, forma, valor, credito_id FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO' AND NOT posterior", id);
        if (gravados.size() != pagos.size()) {
            return false;
        }
        for (Map<String, Object> g : gravados) {
            Pagamento p = pagos.get(g.get("id"));
            if (p == null || !p.forma().equals(g.get("forma")) || p.valor().compareTo((BigDecimal) g.get("valor")) != 0
                    || !java.util.Objects.equals(p.creditoId(), g.get("credito_id"))) {
                return false;
            }
        }
        return true;
    }
}
