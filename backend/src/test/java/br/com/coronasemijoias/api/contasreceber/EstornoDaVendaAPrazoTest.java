package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * O estorno da venda a prazo (tarefa 7.3; ADR-0012, item 7): cancela o que está em aberto, da última parcela para a
 * primeira; o que já foi pago continua pago (o dinheiro volta pelo reembolso da venda); o recebimento já estornado não
 * é reembolsado de novo. Escrito antes do código.
 */
class EstornoDaVendaAPrazoTest extends ApoioDasContas {

    private JsonNode estornarVenda(APrazo c, UUID estorno) throws Exception {
        return corpoDe(postar(VENDAS + "/" + c.venda() + "/estorno", como(c.vendedor()), mapa("id", estorno, "motivo", "Cliente desistiu"))
                .andExpect(status().isCreated()));
    }

    private BigDecimal soma(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    @Test
    void parcelaPagaFicaPagaAParcialEAbertaSaoCanceladasEOReembolsoDevolveSoOQueFoiPago() throws Exception {
        APrazo c = vendaDeCemEmTres();
        receberOk(c, "50.00");
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PARCIAL", "ABERTA");
        JsonNode venda = estornarVenda(c, UUID.randomUUID());
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "CANCELADA", "CANCELADA");
        List<Map<String, Object>> p = parcelas(c.venda());
        assertThat((BigDecimal) p.get(0).get("valor_cancelado")).isEqualByComparingTo("0.00");
        // A parcial: 16,67 pagos e 16,66 cancelados fecham os 33,33 da parcela.
        assertThat((BigDecimal) p.get(1).get("valor_pago")).isEqualByComparingTo("16.67");
        assertThat((BigDecimal) p.get(1).get("valor_cancelado")).isEqualByComparingTo("16.66");
        assertThat((BigDecimal) p.get(2).get("valor_cancelado")).isEqualByComparingTo("33.34");
        assertThat(soma("SELECT sum(valor) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", c.venda()))
                .isEqualByComparingTo("-50.00");
        assertThat(soma("SELECT sum(valor_pago + valor_cancelado) FROM corona.contas_receber WHERE venda_id = ?", c.venda()))
                .isEqualByComparingTo("100.00");
        assertThat(venda.get("status").asString()).isEqualTo("ESTORNADA");
        assertThat(new BigDecimal(venda.get("aReceber").asString())).isEqualByComparingTo("0.00");
    }

    @Test
    void estornoComTodasAsParcelasAbertasCancelaDaUltimaParaAPrimeira() throws Exception {
        APrazo c = vendaDeCemEmTres();
        estornarVenda(c, UUID.randomUUID());
        assertThat(situacoes(c.venda())).containsExactly("CANCELADA", "CANCELADA", "CANCELADA");
        List<Integer> ordem = jdbc.queryForList("""
                SELECT c.parcela FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id
                 WHERE c.venda_id = ? AND b.tipo = 'CANCELAMENTO' AND b.motivo_cancelamento = 'ESTORNO_VENDA' ORDER BY b.id""",
                Integer.class, c.venda());
        assertThat(ordem).containsExactly(3, 2, 1);
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", c.venda()))
                .isZero();
    }

    @Test
    void oRecebimentoJaEstornadoNaoEReembolsadoDeNovo() throws Exception {
        APrazo c = vendaAPrazo("100.00", "40.00", 2);
        UUID errado = receberOk(c, "20.00");
        estornarRecebimento(c, errado, UUID.randomUUID(), "Erro").andExpect(status().isCreated());
        receberOk(c, "30.00");
        estornarVenda(c, UUID.randomUUID());
        // 40,00 pagos na hora e 30,00 recebidos depois; os 20,00 estornados não voltam outra vez.
        assertThat(soma("SELECT sum(valor) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", c.venda()))
                .isEqualByComparingTo("-70.00");
        assertThat(soma("""
                SELECT sum(valor) FROM corona.pagamentos_cliente
                 WHERE venda_id = ? AND tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO', 'REEMBOLSO')""", c.venda()))
                .isEqualByComparingTo("0.00");
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "CANCELADA");
    }

    @Test
    void depoisDoEstornoDaVendaNaoSeRecebeNemSeEstornaRecebimento() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID recebimento = receberOk(c, "10.00");
        estornarVenda(c, UUID.randomUUID());
        receber(c, UUID.randomUUID(), "10.00", "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("VENDA_JA_ESTORNADA"));
        estornarRecebimento(c, recebimento, UUID.randomUUID(), "Erro").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("VENDA_JA_ESTORNADA"));
        postar(ESTORNO_DA_DONA.formatted(recebimento), dona(), mapa("id", UUID.randomUUID(), "motivo", "Erro"))
                .andExpect(status().isConflict());
    }

    @Test
    void reenvioDoEstornoDevolve200SemMexerEmNada() throws Exception {
        APrazo c = vendaDeCemEmTres();
        receberOk(c, "10.00");
        UUID estorno = UUID.randomUUID();
        estornarVenda(c, estorno);
        int baixas = baixas(c.venda());
        postar(VENDAS + "/" + c.venda() + "/estorno", como(c.vendedor()), mapa("id", estorno, "motivo", "Cliente desistiu"))
                .andExpect(status().isOk());
        assertThat(baixas(c.venda())).isEqualTo(baixas);
    }

    @Test
    void aDonaEstornaAVendaAPrazoDiasDepoisEOMesmoCaminhoVale() throws Exception {
        APrazo c = vendaDeCemEmTres();
        receberOk(c, "70.00");
        postar("/api/dona/vendas/" + c.venda() + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Cliente desistiu"))
                .andExpect(status().isCreated());
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PAGA", "CANCELADA");
        assertThat(soma("SELECT sum(valor) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", c.venda()))
                .isEqualByComparingTo("-70.00");
    }

    @Test
    void vendaPagaNaHoraSemParcelasEstornaComoAntes() throws Exception {
        APrazo c = vendaAPrazo("100.00", "100.00", 0);
        estornarVenda(c, UUID.randomUUID());
        assertThat(parcelas(c.venda())).isEmpty();
        assertThat(soma("SELECT sum(valor) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", c.venda()))
                .isEqualByComparingTo("-100.00");
    }

    @Test
    void oEstornoFicaNaAuditoriaComOValorCancelado() throws Exception {
        APrazo c = vendaDeCemEmTres();
        receberOk(c, "40.00");
        estornarVenda(c, UUID.randomUUID());
        String depois = jdbc.queryForObject("""
                SELECT depois::text FROM corona.auditoria WHERE acao = 'VENDA_ESTORNADA' AND registro_id = ?""", String.class,
                c.venda());
        assertThat(depois).contains("cancelado_a_receber").contains("60.00");
    }
}
