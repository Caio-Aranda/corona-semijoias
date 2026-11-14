package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Desfazer a compra cancela o que está em aberto das contas a pagar (tarefa 7.7; ADR-0012, item 12): a parcela paga continua paga,
 * a parcial cancela o saldo, a aberta fica cancelada, o caixa não muda e nada se duplica no reenvio.
 */
class CompraDesfeitaCancelaContasTest extends ApoioDasContasAPagar {

    private BigDecimal caixa() {
        return jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa", BigDecimal.class);
    }

    @Test
    void pagaFicaPagaParcialCancelaOSaldoEAbertaFicaCancelada() throws Exception {
        UUID compra = compra(fornecedor(), "90.00");
        List<UUID> c = contasDe(compra, 3, LocalDate.of(2027, 6, 10));
        pagar(c.get(0), UUID.randomUUID(), "30.00", "DINHEIRO").andExpect(status().isCreated());
        pagar(c.get(1), UUID.randomUUID(), "10.00", "PIX").andExpect(status().isCreated());
        BigDecimal caixaAntes = caixa();

        UUID id = UUID.randomUUID();
        postar(COMPRAS + "/" + compra + "/desfazer", mapa("id", id, "motivo", "Veio errada")).andExpect(status().isCreated());

        assertThat(situacaoDaConta(c.get(0))).isEqualTo("PAGA:30.00:0.00");
        assertThat(situacaoDaConta(c.get(1))).isEqualTo("CANCELADA:10.00:20.00");
        assertThat(situacaoDaConta(c.get(2))).isEqualTo("CANCELADA:0.00:30.00");
        assertThat(caixa()).isEqualByComparingTo(caixaAntes);
        assertThat(contar("SELECT count(*) FROM corona.baixas_pagar WHERE tipo = 'CANCELAMENTO' AND conta_id IN (?, ?, ?)", c.get(0), c.get(1), c.get(2)))
                .isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'CONTAS_A_PAGAR_CANCELADAS' AND registro_id = ?", compra)).isEqualTo(1);

        // Reenvio do desfazer: sem cancelamento duplicado.
        postar(COMPRAS + "/" + compra + "/desfazer", mapa("id", id, "motivo", "Veio errada")).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.baixas_pagar WHERE tipo = 'CANCELAMENTO' AND conta_id IN (?, ?, ?)", c.get(0), c.get(1), c.get(2)))
                .isEqualTo(2);
    }

    @Test
    void parcelaCanceladaNaoPodeSerPaga() throws Exception {
        UUID compra = compra(fornecedor(), "40.00");
        List<UUID> c = contasDe(compra, 2, LocalDate.of(2027, 6, 10));
        postar(COMPRAS + "/" + compra + "/desfazer", mapa("id", UUID.randomUUID(), "motivo", "Veio errada")).andExpect(status().isCreated());
        pagar(c.get(0), UUID.randomUUID(), "20.00", "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONTA_A_PAGAR_CANCELADA"));
    }

    @Test
    void estornarUmPagamentoDeCompraDesfeitaCancelaOQueVoltouAFicarEmAberto() throws Exception {
        UUID compra = compra(fornecedor(), "50.00");
        UUID conta = contasDe(compra, 1, LocalDate.of(2027, 6, 10)).get(0);
        UUID pagamento = UUID.randomUUID();
        pagar(conta, pagamento, "50.00", "PIX").andExpect(status().isCreated());
        postar(COMPRAS + "/" + compra + "/desfazer", mapa("id", UUID.randomUUID(), "motivo", "Devolvida")).andExpect(status().isCreated());
        assertThat(situacaoDaConta(conta)).isEqualTo("PAGA:50.00:0.00");

        postar(CONTAS + "/pagamentos/" + pagamento + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "O fornecedor devolveu o dinheiro"))
                .andExpect(status().isCreated());
        assertThat(situacaoDaConta(conta)).isEqualTo("CANCELADA:0.00:50.00");
    }

    @Test
    void compraSemContasDesfazComoSempre() throws Exception {
        UUID compra = compra(fornecedor(), "10.00");
        postar(COMPRAS + "/" + compra + "/desfazer", mapa("id", UUID.randomUUID(), "motivo", "Teste")).andExpect(status().isCreated());
        assertThat(contar("SELECT count(*) FROM corona.contas_pagar WHERE compra_id = ?", compra)).isZero();
    }
}
