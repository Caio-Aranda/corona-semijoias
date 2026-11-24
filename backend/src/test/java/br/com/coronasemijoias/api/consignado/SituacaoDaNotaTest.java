package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.consignado.SituacaoDaNota.Situacao;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** As quatro situações da nota e as passagens entre elas (ADR-0016, item 10). Sem banco. */
class SituacaoDaNotaTest {

    private static BigDecimal v(String s) {
        return new BigDecimal(s);
    }

    @Test
    void semAcertoEEmAbertoOuRemarcado() {
        assertThat(SituacaoDaNota.calcular(false, false, null)).isEqualTo(Situacao.EM_ABERTO);
        assertThat(SituacaoDaNota.calcular(false, true, null)).isEqualTo(Situacao.REMARCADO);
    }

    @Test
    void comAcertoEPendenteEnquantoHouverSaldoEFechadoQuandoZera() {
        assertThat(SituacaoDaNota.calcular(true, false, v("345.10"))).isEqualTo(Situacao.PENDENTE);
        assertThat(SituacaoDaNota.calcular(true, false, v("0.00"))).isEqualTo(Situacao.FECHADO);
    }

    @Test
    void remarcadaEDepoisAcertadaViraFechadoOuPendente() {
        assertThat(SituacaoDaNota.calcular(true, true, v("0.00"))).isEqualTo(Situacao.FECHADO);
        assertThat(SituacaoDaNota.calcular(true, true, v("1.00"))).isEqualTo(Situacao.PENDENTE);
    }

    @Test
    void estornoDoAcertoVoltaAEmAbertoOuRemarcado() {
        // Sem acerto ativo outra vez: o saldo não conta mais.
        assertThat(SituacaoDaNota.calcular(false, true, v("100.00"))).isEqualTo(Situacao.REMARCADO);
        assertThat(SituacaoDaNota.calcular(false, false, v("100.00"))).isEqualTo(Situacao.EM_ABERTO);
    }

    @Test
    void pendenteViraFechadoSozinhaQuandoOutroPagamentoQuitaOSaldo() {
        assertThat(SituacaoDaNota.calcular(true, false, v("50.00"))).isEqualTo(Situacao.PENDENTE);
        assertThat(SituacaoDaNota.calcular(true, false, v("0.00"))).isEqualTo(Situacao.FECHADO);
    }
}
