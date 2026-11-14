package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.testes.AcertosDeTeste;
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
 * O recebimento posterior do cliente (tarefa 7.3; ADR-0012, item 7): o servidor distribui por vencimento, nunca aceita
 * acima do que falta, não olha a maleta e é idempotente pelo id do aplicativo. Escrito antes do código.
 */
class RecebimentoDoClienteTest extends ApoioDasContas {

    @Test
    void recebimentoParcialFicaNaPrimeiraParcela() throws Exception {
        APrazo c = vendaDeCemEmTres();
        JsonNode venda = corpoDe(receber(c, UUID.randomUUID(), "20.00", "DINHEIRO").andExpect(status().isCreated()));
        assertThat(situacoes(c.venda())).containsExactly("PARCIAL", "ABERTA", "ABERTA");
        assertThat(pagos(c.venda())).containsExactly("20.00", "0.00", "0.00");
        assertThat(baixas(c.venda())).isEqualTo(1);
        assertThat(new BigDecimal(venda.get("pago").asString())).isEqualByComparingTo("20.00");
        assertThat(new BigDecimal(venda.get("aReceber").asString())).isEqualByComparingTo("80.00");
    }

    @Test
    void valorQueCobreAPrimeiraEParteDaSegundaGravaDuasBaixas() throws Exception {
        APrazo c = vendaDeCemEmTres();
        receberOk(c, "50.00");
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PARCIAL", "ABERTA");
        assertThat(pagos(c.venda())).containsExactly("33.33", "16.67", "0.00");
        assertThat(baixas(c.venda())).isEqualTo(2);
    }

    @Test
    void recebimentosSucessivosCompletamAsParcelasPorOrdemDeVencimento() throws Exception {
        APrazo c = vendaDeCemEmTres();
        receberOk(c, "40.00");
        receberOk(c, "30.00");
        assertThat(pagos(c.venda())).containsExactly("33.33", "33.33", "3.34");
        receberOk(c, "30.00");
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PAGA", "PAGA");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND posterior AND tipo = 'PAGAMENTO'",
                c.venda())).isEqualTo(3);
    }

    @Test
    void pagarTudoDeUmaVezQuitaAVenda() throws Exception {
        APrazo c = vendaDeCemEmTres();
        JsonNode venda = corpoDe(receber(c, UUID.randomUUID(), "100.00", "PIX").andExpect(status().isCreated()));
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PAGA", "PAGA");
        assertThat(new BigDecimal(venda.get("aReceber").asString())).isEqualByComparingTo("0.00");
    }

    @Test
    void acimaDoQueFaltaEhRecusadoENadaFicaGravado() throws Exception {
        APrazo c = vendaAPrazo("100.00", "60.00", 2);
        receberOk(c, "10.00");
        int pagamentos = contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ?", c.venda());
        receber(c, UUID.randomUUID(), "30.01", "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECEBIMENTO_ACIMA_DO_EM_ABERTO"))
                .andExpect(jsonPath("$.detail").value("O valor passa do que falta pagar nesta venda (R$ 30,00). Confira o valor."));
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ?", c.venda())).isEqualTo(pagamentos);
        assertThat(baixas(c.venda())).isEqualTo(1);
        assertThat(pagos(c.venda())).containsExactly("10.00", "0.00");
    }

    @Test
    void vendaPagaNaHoraNaoTemNadaAReceber() throws Exception {
        APrazo c = vendaAPrazo("100.00", "100.00", 0);
        receber(c, UUID.randomUUID(), "10.00", "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("VENDA_SEM_VALOR_EM_ABERTO"));
    }

    @Test
    void soDinheiroOuTransferenciaEValorValido() throws Exception {
        APrazo c = vendaDeCemEmTres();
        for (String forma : List.of("CARTAO", "CHEQUE", "CREDITO", "BOLETO")) {
            receber(c, UUID.randomUUID(), "10.00", forma).andExpect(status().isBadRequest());
        }
        receber(c, UUID.randomUUID(), "0.00", "PIX").andExpect(status().isBadRequest());
        receber(c, UUID.randomUUID(), "-5.00", "PIX").andExpect(status().isBadRequest());
        receber(c, UUID.randomUUID(), "10.005", "PIX").andExpect(status().isBadRequest());
        assertThat(baixas(c.venda())).isZero();
        receber(c, UUID.randomUUID(), "10.00", "DINHEIRO").andExpect(status().isCreated());
    }

    @Test
    void maletaEmAcertoOuAcertadaNaoBloqueiaORecebimento() throws Exception {
        APrazo c = vendaDeCemEmTres();
        // A maleta acertada fica acertada (desde a V044 ela não volta): sem sobra, a conciliação que os testes dividem não muda.
        AcertosDeTeste.emAcerto(jdbc, c.maleta());
        receberOk(c, "10.00");
        AcertosDeTeste.acertada(jdbc, c.maleta());
        receberOk(c, "10.00");
        assertThat(pagos(c.venda())).containsExactly("20.00", "0.00", "0.00");
    }

    @Test
    void reenvioIgualDevolve200SemBaixaNovaEOutrosDadosDao409() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID id = UUID.randomUUID();
        receber(c, id, "20.00", "PIX").andExpect(status().isCreated());
        receber(c, id, "20.00", "PIX").andExpect(status().isOk());
        assertThat(baixas(c.venda())).isEqualTo(1);
        receber(c, id, "25.00", "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECEBIMENTO_JA_REGISTRADO_DIFERENTE"));
        receber(c, id, "20.00", "DINHEIRO").andExpect(status().isConflict());
        // O id de um pagamento qualquer (o da venda, por exemplo) também não serve.
        APrazo outra = vendaAPrazo("100.00", "10.00", 2);
        UUID pagamentoDaVenda = jdbc.queryForObject("SELECT id FROM corona.pagamentos_cliente WHERE venda_id = ?", UUID.class,
                outra.venda());
        receber(outra, pagamentoDaVenda, "10.00", "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECEBIMENTO_JA_REGISTRADO_DIFERENTE"));
        assertThat(pagos(c.venda())).containsExactly("20.00", "0.00", "0.00");
    }

    @Test
    void reenvioDaVendaDepoisDeUmRecebimentoContinuaSendoOMesmoPedido() throws Exception {
        UsuarioEPedido pedido = pedidoDeVenda();
        postar(VENDAS, como(pedido.vendedor()), pedido.corpo()).andExpect(status().isCreated());
        APrazo c = new APrazo(pedido.vendedor(), pedido.cliente(), null, null, pedido.venda());
        receberOk(c, "30.00");
        postar(VENDAS, como(pedido.vendedor()), pedido.corpo()).andExpect(status().isOk());
        assertThat(parcelas(pedido.venda())).hasSize(2);
        assertThat(pagos(pedido.venda())).containsExactly("30.00", "0.00");
    }

    private record UsuarioEPedido(br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste vendedor, UUID cliente, UUID venda,
            Map<String, Object> corpo) {
    }

    private UsuarioEPedido pedidoDeVenda() throws Exception {
        var vendedor = usuarios.criarVendedor();
        UUID cliente = cliente(vendedor);
        UUID peca = peca("100.00", 3);
        UUID maleta = maleta(vendedor, peca, 3);
        UUID venda = UUID.randomUUID();
        vendasDoTeste.add(venda);
        Map<String, Object> corpo = mapa("id", venda, "remessaId", maleta, "clienteId", cliente, "itens", List.of(linha(peca, 1)),
                "pagamentos", List.of(pagamento("10.00", "DINHEIRO")),
                "parcelamento", mapa("quantidade", 2, "primeiroVencimento", hoje().plusDays(2).toString()));
        return new UsuarioEPedido(vendedor, cliente, venda, corpo);
    }

    @Test
    void vendaDeOutroVendedorDa404() throws Exception {
        APrazo c = vendaDeCemEmTres();
        var intruso = usuarios.criarVendedor();
        postar(RECEBIMENTOS.formatted(c.venda()), como(intruso), mapa("id", UUID.randomUUID(), "valor", "10.00", "forma", "PIX"))
                .andExpect(status().isNotFound());
        postar(RECEBIMENTOS.formatted(UUID.randomUUID()), como(c.vendedor()),
                mapa("id", UUID.randomUUID(), "valor", "10.00", "forma", "PIX")).andExpect(status().isNotFound());
        assertThat(baixas(c.venda())).isZero();
    }

    @Test
    void aDonaNaoRegistraRecebimentoPelaRotaDoVendedor() throws Exception {
        APrazo c = vendaDeCemEmTres();
        postar(RECEBIMENTOS.formatted(c.venda()), dona(), mapa("id", UUID.randomUUID(), "valor", "10.00", "forma", "PIX"))
                .andExpect(status().isForbidden());
    }
}
