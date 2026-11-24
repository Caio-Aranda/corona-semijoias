package br.com.coronasemijoias.api.contaspagar;

import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.cotacoes.ContasEmTresMoedas;
import br.com.coronasemijoias.api.cotacoes.ContasEmTresMoedas.Moedas;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura das contas a pagar (tarefa 7.7; ADR-0012, item 12): só a dona. Tudo em Real. A situação {@code VENCIDA} é calculada
 * pela data de hoje em Brasília (parcela em aberto ou parcial que venceu antes de hoje); nunca é gravada.
 */
@Component
public class ConsultasDeContasAPagar {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final Set<String> SITUACOES = Set.of("ABERTA", "PARCIAL", "PAGA", "CANCELADA", "VENCIDA");

    /** Uma baixa da parcela: o pagamento (positivo), o estorno dele (negativo) ou o cancelamento (compra desfeita). */
    public record Baixa(UUID id, String tipo, BigDecimal valor, String forma, LocalDate dataPagamento, String motivo, UUID estornaId,
            Instant registradaEm) {
    }

    /** Uma parcela a pagar, com o que falta ({@code saldo}) e, no detalhe, as baixas. */
    public record Conta(UUID id, UUID compraId, String numeroDaCompra, UUID fornecedorId, String fornecedorNome, int parcela,
            int totalParcelas, LocalDate vencimento, BigDecimal valor, BigDecimal valorPago, BigDecimal valorCancelado, BigDecimal saldo,
            String situacao, List<Baixa> baixas, BigDecimal valorEmDolar, BigDecimal valorEmGuarani, BigDecimal valorPagoEmDolar,
            BigDecimal valorPagoEmGuarani) {

        Conta comMoedas(Moedas doValor, Moedas doPago) {
            return new Conta(id, compraId, numeroDaCompra, fornecedorId, fornecedorNome, parcela, totalParcelas, vencimento, valor,
                    valorPago, valorCancelado, saldo, situacao, baixas, doValor.dolar(), doValor.guarani(), doPago.dolar(),
                    doPago.guarani());
        }
    }

    /** Os filtros da lista: fornecedor, situação (com VENCIDA) e vencimento de/até (dias de Brasília, inclusive). */
    public record Filtro(UUID fornecedorId, String situacao, LocalDate vencimentoDe, LocalDate vencimentoAte) {
    }

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    ConsultasDeContasAPagar(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }

    public List<Conta> daCompra(UUID compraId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.compras WHERE id = ?)", Boolean.class, compraId))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.COMPRA_NAO_ENCONTRADA);
        }
        return contas("WHERE c.compra_id = ?", " ORDER BY c.parcela", false, List.of(compraId));
    }

    public Conta ver(UUID id) {
        List<Conta> achadas = contas("WHERE c.id = ?", "", true, List.of(id));
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CONTA_A_PAGAR_NAO_ENCONTRADA);
        }
        return achadas.get(0);
    }

    /** A conta de uma baixa (usada para responder ao estorno do pagamento). */
    public UUID contaDaBaixa(UUID baixaId) {
        return jdbc.queryForObject("SELECT conta_id FROM corona.baixas_pagar WHERE id = ?", UUID.class, baixaId);
    }

    public Pagina<Conta> listar(Filtro filtro, Integer pagina, Integer tamanho) {
        int p = pagina == null ? 0 : pagina;
        int t = tamanho == null ? ServicoDeCadastro.TAMANHO_PADRAO : tamanho;
        if (p < 0) {
            throw Validacoes.recusar("pagina", "A página não pode ser negativa.");
        }
        if (t < 1 || t > ServicoDeCadastro.TAMANHO_MAXIMO) {
            throw Validacoes.recusar("tamanho", "Escolha de 1 a " + ServicoDeCadastro.TAMANHO_MAXIMO + " itens por página.");
        }
        if (filtro.vencimentoDe() != null && filtro.vencimentoAte() != null && filtro.vencimentoAte().isBefore(filtro.vencimentoDe())) {
            throw Validacoes.recusar("vencimentoAte", "A data final não pode ser antes da inicial.");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder("WHERE c.deleted_at IS NULL");
        if (filtro.fornecedorId() != null) {
            onde.append(" AND c.fornecedor_id = ?");
            args.add(filtro.fornecedorId());
        }
        if (filtro.situacao() != null) {
            String s = filtro.situacao().toUpperCase(Locale.ROOT);
            if (!SITUACOES.contains(s)) {
                throw Validacoes.recusar("situacao", "Escolha uma situação da lista.");
            }
            if ("VENCIDA".equals(s)) {
                onde.append(" AND c.status IN ('ABERTA', 'PARCIAL') AND c.vencimento < ?");
                args.add(hoje());
            } else if ("ABERTA".equals(s) || "PARCIAL".equals(s)) {
                // Em aberto e vencida são situações diferentes na tela: a vencida não conta como "aberta".
                onde.append(" AND c.status = ? AND c.vencimento >= ?");
                args.add(s);
                args.add(hoje());
            } else {
                onde.append(" AND c.status = ?");
                args.add(s);
            }
        }
        if (filtro.vencimentoDe() != null) {
            onde.append(" AND c.vencimento >= ?");
            args.add(filtro.vencimentoDe());
        }
        if (filtro.vencimentoAte() != null) {
            onde.append(" AND c.vencimento <= ?");
            args.add(filtro.vencimentoAte());
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM corona.contas_pagar c " + onde, Long.class, args.toArray());
        List<Object> comPagina = new ArrayList<>(args);
        comPagina.add(t);
        comPagina.add((long) p * t);
        List<Conta> itens = contas(onde.toString(), " ORDER BY c.vencimento, c.compra_id, c.parcela LIMIT ? OFFSET ?", false, comPagina);
        return new Pagina<>(itens, total, p, t);
    }

    private List<Conta> contas(String onde, String ordem, boolean comBaixas, List<Object> args) {
        List<Conta> contas = contasEmReal(onde, ordem, comBaixas, args);
        if (contas.isEmpty()) {
            return contas;
        }
        String ids = ContasEmTresMoedas.lista(contas.stream().map(Conta::id).toList());
        // O valor da parcela pela cotação do dia da compra; cada pagamento pela do dia dele (o estorno, pela do pagamento que
        // desfaz, para os dois se anularem também em Dólar e Guarani). Onde só há a data, o fim do dia em Brasília.
        Map<UUID, Moedas> doValor = ContasEmTresMoedas.somar(jdbc, "SELECT c.id, c.valor, ct.usd_venda, ct.pyg_venda"
                + " FROM corona.contas_pagar c JOIN corona.compras k ON k.id = c.compra_id "
                + CotacaoVigente.joinDoMomento(CotacaoVigente.fimDoDiaSql("k.data_compra"), "c.usd_venda, c.pyg_venda")
                + " WHERE c.id = ANY (?::uuid[])", ids);
        Map<UUID, Moedas> doPago = ContasEmTresMoedas.somar(jdbc, "SELECT b.conta_id, b.valor, ct.usd_venda, ct.pyg_venda"
                + " FROM corona.baixas_pagar b LEFT JOIN corona.baixas_pagar o ON o.id = b.estorna_id "
                + CotacaoVigente.joinDoMomento(CotacaoVigente.fimDoDiaSql("coalesce(o.data_pagamento, b.data_pagamento)"),
                        "c.usd_venda, c.pyg_venda")
                + " WHERE b.tipo = 'PAGAMENTO' AND b.conta_id = ANY (?::uuid[])", ids);
        return contas.stream().map(c -> c.comMoedas(doValor.getOrDefault(c.id(), Moedas.SEM_COTACAO),
                doPago.getOrDefault(c.id(), Moedas.ZERO))).toList();
    }

    private List<Conta> contasEmReal(String onde, String ordem, boolean comBaixas, List<Object> args) {
        LocalDate hoje = hoje();
        return jdbc.query("""
                SELECT c.id, c.compra_id, k.numero, c.fornecedor_id, f.razao_social, c.parcela, c.total_parcelas, c.vencimento, c.valor,
                       c.valor_pago, c.valor_cancelado, c.status
                  FROM corona.contas_pagar c JOIN corona.compras k ON k.id = c.compra_id
                  JOIN corona.fornecedores f ON f.id = c.fornecedor_id""" + " " + onde + ordem, (rs, n) -> {
                    BigDecimal valor = rs.getBigDecimal("valor");
                    BigDecimal pago = rs.getBigDecimal("valor_pago");
                    BigDecimal cancelado = rs.getBigDecimal("valor_cancelado");
                    String status = rs.getString("status");
                    LocalDate vencimento = rs.getObject("vencimento", LocalDate.class);
                    boolean vencida = ("ABERTA".equals(status) || "PARCIAL".equals(status)) && vencimento.isBefore(hoje);
                    UUID id = rs.getObject("id", UUID.class);
                    return new Conta(id, rs.getObject("compra_id", UUID.class), rs.getString("numero"),
                            rs.getObject("fornecedor_id", UUID.class), rs.getString("razao_social"), rs.getInt("parcela"),
                            rs.getInt("total_parcelas"), vencimento, valor, pago, cancelado, valor.subtract(pago).subtract(cancelado),
                            vencida ? "VENCIDA" : status, comBaixas ? baixas(id) : List.of(), null, null, null, null);
                }, args.toArray());
    }

    private List<Baixa> baixas(UUID contaId) {
        return jdbc.query("""
                SELECT id, tipo, valor, forma, data_pagamento, motivo, estorna_id, registrada_em FROM corona.baixas_pagar
                 WHERE conta_id = ? ORDER BY registrada_em, id""", (rs, n) -> new Baixa(rs.getObject("id", UUID.class), rs.getString("tipo"),
                rs.getBigDecimal("valor"), rs.getString("forma"), rs.getObject("data_pagamento", LocalDate.class), rs.getString("motivo"),
                rs.getObject("estorna_id", UUID.class), rs.getTimestamp("registrada_em").toInstant()), contaId);
    }
}
