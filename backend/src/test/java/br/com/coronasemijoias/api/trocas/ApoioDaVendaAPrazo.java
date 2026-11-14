package br.com.coronasemijoias.api.trocas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Apoio dos testes da devolução e da troca de venda a prazo (tarefa 6.6). A massa base: três peças A, total de R$ 100,00
 * (preço 33,34, desconto de 0,02), que a devolução vê como 33,33, 33,33 e 33,34. A conciliação C1 ampliada (venda com
 * devolução) e a C5 rodam depois de cada teste.
 */
public abstract class ApoioDaVendaAPrazo extends ApoioDasTrocas {

    /** A venda a prazo pronta: vendedor, cliente, peça A (3 unidades), maleta e venda. */
    public record APrazo(UsuarioDeTeste v, UUID cliente, UUID peca, UUID maleta, UUID venda) {
    }

    /** As vendas deste teste: a conciliação só olha estas (o banco é dividido entre os testes). */
    protected final List<UUID> vendasDoTeste = new ArrayList<>();

    /** Falso nos testes que estragam um dado de propósito. */
    protected boolean conciliar = true;

    @AfterEach
    void conciliarAVendaAPrazo() {
        if (conciliar) {
            assertThat(diferencasDaConciliacao()).as("C1 ampliada e C5").isEmpty();
        }
    }

    /**
     * C1 (por venda concluída: total menos o devolvido ativo = pagamentos líquidos, inclusive crédito e estornos, mais o
     * em aberto das parcelas; venda estornada: em aberto zero) e C5 (atalhos da parcela = soma das baixas).
     */
    protected List<String> diferencasDaConciliacao() {
        String ids = vendasDoTeste.isEmpty() ? "NULL::uuid"
                : String.join(",", vendasDoTeste.stream().map(u -> "'" + u + "'::uuid").toList());
        List<String> achadas = new ArrayList<>(jdbc.queryForList("""
                SELECT 'C1 ' || v.id || ' esperado ' || (v.total - coalesce(d.devolvido, 0)) || ' achado '
                       || (coalesce(p.liquido, 0) + coalesce(a.aberto, 0))
                  FROM corona.vendas v
                  LEFT JOIN (SELECT venda_id, sum(valor - valor_pago - valor_cancelado) AS aberto
                               FROM corona.contas_receber GROUP BY venda_id) a ON a.venda_id = v.id
                  LEFT JOIN (SELECT venda_id, sum(valor) AS liquido FROM corona.pagamentos_cliente
                              WHERE tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO', 'REEMBOLSO', 'ESTORNO_REEMBOLSO') GROUP BY venda_id) p
                         ON p.venda_id = v.id
                  LEFT JOIN (SELECT venda_id, sum(valor_devolvido) AS devolvido FROM corona.devolucoes
                              WHERE situacao <> 'CANCELADA' GROUP BY venda_id) d ON d.venda_id = v.id
                 WHERE v.id IN (%s) AND v.status = 'CONCLUIDA'
                   AND v.total - coalesce(d.devolvido, 0) <> coalesce(p.liquido, 0) + coalesce(a.aberto, 0)""".formatted(ids),
                String.class));
        achadas.addAll(jdbc.queryForList("""
                SELECT 'C1 estornada ' || v.id FROM corona.vendas v
                  JOIN corona.contas_receber c ON c.venda_id = v.id
                 WHERE v.id IN (%s) AND v.status = 'ESTORNADA' GROUP BY v.id
                HAVING sum(c.valor - c.valor_pago - c.valor_cancelado) <> 0""".formatted(ids), String.class));
        achadas.addAll(jdbc.queryForList("""
                SELECT 'C5 ' || c.id FROM corona.contas_receber c
                 WHERE c.venda_id IN (%s) AND (c.valor_pago <> coalesce((SELECT sum(valor) FROM corona.baixas_receber b
                                                  WHERE b.conta_id = c.id AND b.tipo = 'PAGAMENTO'), 0)
                    OR c.valor_cancelado <> coalesce((SELECT sum(valor) FROM corona.baixas_receber b
                                                       WHERE b.conta_id = c.id AND b.tipo = 'CANCELAMENTO'), 0))""".formatted(ids),
                String.class));
        // O abatido gravado na devolução = o líquido das baixas de cancelamento dela (invariante 4).
        achadas.addAll(jdbc.queryForList("""
                SELECT 'abatido ' || d.id FROM corona.devolucoes d
                 WHERE d.venda_id IN (%s)
                   AND (CASE WHEN d.situacao = 'CANCELADA' THEN 0 ELSE d.valor_abatido END)
                       <> coalesce((SELECT sum(b.valor) FROM corona.baixas_receber b WHERE b.devolucao_id = d.id), 0)""".formatted(ids),
                String.class));
        achadas.addAll(jdbc.queryForList("""
                SELECT 'divisao ' || d.id FROM corona.devolucoes d
                 WHERE d.venda_id IN (%s)
                   AND d.valor_reembolsado + d.valor_em_credito + d.valor_abatido <> d.valor_devolvido""".formatted(ids), String.class));
        return achadas;
    }

    protected java.time.LocalDate hoje() {
        return java.time.LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }

    // --- a massa ---

    /**
     * A venda de R$ 100,00 (3 peças A) com {@code pagoNaHora} em dinheiro (nulo = nada) e o resto em {@code parcelas}
     * parcelas mensais.
     */
    protected APrazo vendaAPrazo(String pagoNaHora, int parcelas) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("33.34", 20);
        UUID maleta = maleta(v, a, 20);
        return vendaAPrazoDe(v, cliente, a, maleta, pagoNaHora, parcelas);
    }

    protected APrazo vendaAPrazoDe(UsuarioDeTeste v, UUID cliente, UUID a, UUID maleta, String pagoNaHora, int parcelas)
            throws Exception {
        limiteDeDesconto(5);
        UUID id = UUID.randomUUID();
        Map<String, Object> corpo = pedidoDeVenda(id, maleta, cliente, List.of(linhaComDesconto(a, 3, "0.02")),
                pagoNaHora == null ? List.of() : List.of(pagamento(pagoNaHora, "DINHEIRO")));
        if (parcelas > 0) {
            corpo.put("parcelamento", mapa("quantidade", parcelas,
                    "primeiroVencimento", java.time.LocalDate.ofInstant(relogio.instant(), BRASILIA).plusDays(1).toString()));
        }
        postar(VENDAS, como(v), corpo).andExpect(status().isCreated());
        vendasDoTeste.add(id);
        return new APrazo(v, cliente, a, maleta, id);
    }

    // --- a devolução ---

    protected ResultActions devolverAPrazo(APrazo c, int quantidade, String forma) throws Exception {
        return postar(DEVOLUCOES, como(c.v()), mapa("id", UUID.randomUUID(), "vendaId", c.venda(), "motivo", "ARREPENDIMENTO",
                "formaReembolso", forma, "itens", List.of(devolvida(c.peca(), quantidade, "BOA"))));
    }

    protected JsonNode devolverAPrazoOk(APrazo c, int quantidade) throws Exception {
        return corpoDe(devolverAPrazo(c, quantidade, "DINHEIRO").andExpect(status().isCreated()));
    }

    protected ResultActions cancelarDevolucao(APrazo c, UUID devolucao, UUID idDoCancelamento) throws Exception {
        return postar(DEVOLUCOES + "/" + devolucao + "/cancelar", como(c.v()), mapa("id", idDoCancelamento, "motivo", "Foi engano"));
    }

    protected ResultActions receber(APrazo c, UUID id, String valor) throws Exception {
        return postar(VENDAS + "/" + c.venda() + "/recebimentos", como(c.v()), mapa("id", id, "valor", valor, "forma", "PIX"));
    }

    // --- o que ficou gravado ---

    protected List<Map<String, Object>> parcelas(UUID venda) {
        return jdbc.queryForList("""
                SELECT id, parcela, valor, status, valor_pago, valor_cancelado FROM corona.contas_receber
                 WHERE venda_id = ? ORDER BY parcela""", venda);
    }

    protected List<String> situacoes(UUID venda) {
        return parcelas(venda).stream().map(p -> (String) p.get("status")).toList();
    }

    protected BigDecimal emAberto(UUID venda) {
        return valor("SELECT coalesce(sum(valor - valor_pago - valor_cancelado), 0) FROM corona.contas_receber WHERE venda_id = ?",
                venda);
    }

    protected BigDecimal canceladoDaParcela(UUID venda, int parcela) {
        return valor("SELECT valor_cancelado FROM corona.contas_receber WHERE venda_id = ? AND parcela = ?", venda, parcela);
    }

    protected BigDecimal dinheiroDevolvido(UUID venda) {
        return valor("""
                SELECT coalesce(-sum(valor), 0) FROM corona.pagamentos_cliente
                 WHERE venda_id = ? AND tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO') AND forma <> 'CREDITO'""", venda);
    }

    protected Map<String, Object> devolucaoGravada(UUID id) {
        return jdbc.queryForMap("""
                SELECT situacao, valor_devolvido, valor_reembolsado, valor_em_credito, valor_abatido
                  FROM corona.devolucoes WHERE id = ?""", id);
    }

    protected int baixasDeCancelamento(UUID venda, String motivo) {
        return contar("""
                SELECT count(*) FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id
                 WHERE c.venda_id = ? AND b.tipo = 'CANCELAMENTO' AND b.motivo_cancelamento = ?""", venda, motivo);
    }

    protected static void igual(BigDecimal achado, String esperado) {
        assertThat(achado).isEqualByComparingTo(esperado);
    }
}
