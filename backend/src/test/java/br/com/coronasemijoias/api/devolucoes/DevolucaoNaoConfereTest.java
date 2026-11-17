package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber;
import br.com.coronasemijoias.api.trocas.ApoioDaVendaAPrazo;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Issue 208 (ADR-0014, item 10): o que foi abatido de fato nas parcelas tem de ser igual ao calculado. Se uma baixa
 * devolver 0,01 a menos, a devolução inteira é desfeita (409 {@code DEVOLUCAO_NAO_CONFERE}), sem gravar nada. Escrito antes
 * do código.
 */
class DevolucaoNaoConfereTest extends ApoioDaVendaAPrazo {

    @MockitoSpyBean
    private ServicoDeContasAReceber contas;

    @AfterEach
    void soltarOEspiao() {
        reset(contas);
    }

    @Test
    void abatimentoQueNaoFechaDesfazTudoEDa409() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        doAnswer(i -> ((BigDecimal) i.callRealMethod()).subtract(new BigDecimal("0.01")))
                .when(contas).abaterNaDevolucao(any(), any(), any(), any());
        devolverAPrazo(c, 1, "DINHEIRO").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEVOLUCAO_NAO_CONFERE"));
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", c.venda())).isZero();
        assertThat(baixasDeCancelamento(c.venda(), "DEVOLUCAO")).isZero();
        igual(emAberto(c.venda()), "100.00");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", c.venda()))
                .isZero();
    }
}
