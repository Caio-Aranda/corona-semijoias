package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Contas a pagar em Dólar e Guarani (revisão de 23/11/2026, achado M3; ADR-0006, itens 7 e 10): o "Valor" pela cotação do dia da
 * compra e o "Já pago" pela cotação de cada pagamento, somados. O "Falta" a tela mostra pela cotação de hoje. Datas de 2293, que
 * nenhum outro teste usa, para as cotações não se misturarem.
 */
class ContasAPagarEmTresMoedasTest extends ApoioDasContasAPagar {

    /** As cotações de 2293 e as da dona saem no fim, como nos outros testes que gravam cotação (o teste da busca conta as linhas). */
    @AfterEach
    void tirarAsCotacoes() throws SQLException {
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }


    @Test
    void valorPelaCotacaoDoDiaDaCompraEJaPagoPelaDeCadaPagamento() throws Exception {
        // Banco Central do dia da compra: 1 dólar = R$ 5,00; 1 real = Gs. 2.000. A dona muda no dia do pagamento: R$ 4,00 e Gs. 1.000.
        CotacoesDaMassa.gravar(jdbc, "2293-03-01", "5.00000000", "0.00050000");
        CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), Instant.parse("2293-03-10T14:00:00Z"), "4.00", "1000");
        relogio.fixar(Instant.parse("2293-03-10T15:00:00Z"));

        UUID compra = compra(fornecedor(), "100.00", "2293-03-01");
        List<UUID> contas = contasDe(compra, 2, LocalDate.of(2293, 4, 10));
        pagar(contas.get(0), UUID.randomUUID(), "20.00", "PIX").andExpect(status().isCreated());

        JsonNode detalhe = corpoDe(ler(CONTAS + "/" + contas.get(0)).andExpect(status().isOk()));
        JsonNode daLista = null;
        for (JsonNode c : corpoDe(ler(CONTAS + "?tamanho=100&vencimentoDe=2293-04-10&vencimentoAte=2293-04-10").andExpect(status().isOk()))
                .get("itens")) {
            if (c.get("id").asString().equals(contas.get(0).toString())) {
                daLista = c;
            }
        }
        assertThat(daLista).isNotNull();
        for (JsonNode c : List.of(detalhe, daLista)) {
            // Parcela de R$ 50,00 pela cotação da compra.
            assertThat(new BigDecimal(c.get("valorEmDolar").asString())).isEqualByComparingTo("10.00");
            assertThat(new BigDecimal(c.get("valorEmGuarani").asString())).isEqualByComparingTo("100000");
            // R$ 20,00 pagos pela cotação da dona, que valia na hora do pagamento.
            assertThat(new BigDecimal(c.get("valorPagoEmDolar").asString())).isEqualByComparingTo("5.00");
            assertThat(new BigDecimal(c.get("valorPagoEmGuarani").asString())).isEqualByComparingTo("20000");
        }

        // Sem pagamento, o "Já pago" é zero nas três moedas.
        JsonNode outra = corpoDe(ler(CONTAS + "/" + contas.get(1)).andExpect(status().isOk()));
        assertThat(new BigDecimal(outra.get("valorPagoEmDolar").asString())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(outra.get("valorPagoEmGuarani").asString())).isEqualByComparingTo("0");
    }
}
