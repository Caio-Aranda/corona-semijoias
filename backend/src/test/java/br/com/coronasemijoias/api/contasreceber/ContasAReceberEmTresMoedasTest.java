package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Contas a receber da dona em Dólar e Guarani (revisão de 23/11/2026, achado M3; ADR-0006, itens 7 e 10): o "Valor" pela cotação
 * da venda e o "Já pago" pela cotação de cada recebimento, somados. Datas de 2293, que nenhum outro teste usa.
 */
class ContasAReceberEmTresMoedasTest extends ApoioDasContas {

    /** As cotações de 2293 e as da dona saem no fim, como nos outros testes que gravam cotação (o teste da busca conta as linhas). */
    @AfterEach
    void tirarAsCotacoes() throws SQLException {
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }


    @Test
    void valorPelaCotacaoDaVendaEJaPagoPelaDeCadaRecebimento() throws Exception {
        CotacoesDaMassa.gravar(jdbc, "2293-05-01", "5.00000000", "0.00050000");
        relogio.fixar(Instant.parse("2293-05-10T14:00:00Z"));
        APrazo c = vendaDeCemEmTres();
        CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), Instant.parse("2293-05-10T14:02:00Z"), "4.00", "1000");
        relogio.fixar(Instant.parse("2293-05-10T14:04:00Z"));
        receberOk(c, "50.00");

        JsonNode lista = corpoDe(ler("/api/dona/contas-receber?vendedorId=" + c.vendedor().vendedorId(), dona())
                .andExpect(status().isOk()));
        Map<Integer, JsonNode> porParcela = new HashMap<>();
        lista.forEach(x -> porParcela.put(x.get("numero").asInt(), x));
        assertThat(porParcela).hasSize(3);
        // 33,33 pela cotação da venda (R$ 5,00 e Gs. 2.000).
        assertThat(new BigDecimal(porParcela.get(1).get("valorEmDolar").asString())).isEqualByComparingTo("6.67");
        assertThat(new BigDecimal(porParcela.get(1).get("valorEmGuarani").asString())).isEqualByComparingTo("66660");
        // Pagos 33,33 e 16,67 pela cotação da dona na hora do recebimento (R$ 4,00 e Gs. 1.000).
        assertThat(new BigDecimal(porParcela.get(1).get("valorPagoEmDolar").asString())).isEqualByComparingTo("8.33");
        assertThat(new BigDecimal(porParcela.get(1).get("valorPagoEmGuarani").asString())).isEqualByComparingTo("33330");
        assertThat(new BigDecimal(porParcela.get(2).get("valorPagoEmDolar").asString())).isEqualByComparingTo("4.17");
        assertThat(new BigDecimal(porParcela.get(2).get("valorPagoEmGuarani").asString())).isEqualByComparingTo("16670");
        assertThat(new BigDecimal(porParcela.get(3).get("valorPagoEmDolar").asString())).isEqualByComparingTo("0.00");
    }
}
