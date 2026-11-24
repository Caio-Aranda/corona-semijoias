package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A conta do que falta para a meta (tarefa 8.11; ADR-0012, item 5), uma só para a prévia do vendedor e a da dona. Feita à mão:
 * falta = máximo entre 0 e (meta - soma); o Real é o Guarani que falta vezes a taxa de venda do Guarani da cotação (0,0009 aqui).
 */
class FaltaParaMetaTest {

    private static final BigDecimal TAXA = new BigDecimal("0.00090000");

    @Test
    void abaixoDaMetaFaltaADiferencaEORealEoGuaraniVezesATaxa() {
        // 700.000 - 650.000 = 50.000 Gs; 50.000 x 0,0009 = R$ 45,00.
        var falta = ServicoDaComissao.faltaParaMeta(700_000, 650_000, TAXA);
        assertThat(falta.guarani()).isEqualTo(50_000L);
        assertThat(falta.reais()).isEqualByComparingTo("45.00");
        assertThat(falta.reais().scale()).isEqualTo(2);
    }

    @Test
    void naMetaNaoFaltaNada() {
        var falta = ServicoDaComissao.faltaParaMeta(700_000, 700_000, TAXA);
        assertThat(falta.guarani()).isZero();
        assertThat(falta.reais()).isEqualByComparingTo("0.00");
    }

    @Test
    void acimaDaMetaNuncaFicaNegativo() {
        var falta = ServicoDaComissao.faltaParaMeta(700_000, 900_000, TAXA);
        assertThat(falta.guarani()).isZero();
        assertThat(falta.reais()).isEqualByComparingTo("0.00");
        assertThat(falta.reais().signum()).isZero();
    }

    @Test
    void semVendaFaltaAMetaInteira() {
        // 700.000 x 0,0009 = R$ 630,00.
        var falta = ServicoDaComissao.faltaParaMeta(700_000, 0, TAXA);
        assertThat(falta.guarani()).isEqualTo(700_000L);
        assertThat(falta.reais()).isEqualByComparingTo("630.00");
    }

    @Test
    void metaMaximaSemVenda() {
        // 1.000.000.000 x 0,0009 = R$ 900.000,00.
        var falta = ServicoDaComissao.faltaParaMeta(ServicoDaComissao.META_MAXIMA, 0, TAXA);
        assertThat(falta.guarani()).isEqualTo(1_000_000_000L);
        assertThat(falta.reais()).isEqualByComparingTo("900000.00");
    }
}
