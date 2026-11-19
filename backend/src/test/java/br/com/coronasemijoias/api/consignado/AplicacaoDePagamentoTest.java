package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Aplicacao;
import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Debito;
import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Resultado;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Pagamento aplicado do débito mais antigo para o mais novo (§23.8; ADR-0016, item 10). Sem banco. */
class AplicacaoDePagamentoTest {

    private static final JsonNode EXEMPLOS = ExemplosDoConsignado.ler();

    private static BigDecimal v(String s) {
        return new BigDecimal(s);
    }

    private static List<Debito> debitos(JsonNode saldos) {
        List<Debito> lista = new ArrayList<>();
        saldos.forEach(s -> lista.add(new Debito(UUID.randomUUID(), ExemplosDoConsignado.valor(s))));
        return lista;
    }

    @Test
    void tresMomentosDaEspecificacao() {
        JsonNode m = EXEMPLOS.get("tresMomentos");
        // Semana 1: bruto 400, pago 300 -> deve 100.
        Debito d1 = new Debito(UUID.randomUUID(), v(m.get("semana1").get("bruto").asString()));
        Resultado r1 = AplicacaoDePagamento.aplicar(List.of(d1), v(m.get("semana1").get("pago").asString()));
        BigDecimal saldo1 = d1.saldo().subtract(r1.aplicacoes().get(0).valor());
        assertThat(saldo1).isEqualTo(v(m.get("semana1").get("deviaDepois").asString()));

        // Semana 2: bruto 500 + 100 = 600, pago 550 -> débito 1 quitado, débito 2 com 50.
        Debito antigo = new Debito(d1.id(), saldo1);
        Debito novo = new Debito(UUID.randomUUID(), v(m.get("semana2").get("bruto").asString()));
        Resultado r2 = AplicacaoDePagamento.aplicar(List.of(antigo, novo), v(m.get("semana2").get("pago").asString()));
        assertThat(r2.aplicacoes()).extracting(Aplicacao::debitoId).containsExactly(antigo.id(), novo.id());
        assertThat(antigo.saldo().subtract(r2.aplicacoes().get(0).valor()))
                .isEqualTo(v(m.get("semana2").get("debito1DepoisDoPagamento").asString()));
        BigDecimal saldoNovo = novo.saldo().subtract(r2.aplicacoes().get(1).valor());
        assertThat(saldoNovo).isEqualTo(v(m.get("semana2").get("debito2DepoisDoPagamento").asString()));

        // Pagamento lançado de 50 -> deve 0.
        Resultado r3 = AplicacaoDePagamento.aplicar(List.of(new Debito(novo.id(), saldoNovo)),
                v(m.get("pagamentoLancado").get("valor").asString()));
        assertThat(saldoNovo.subtract(r3.aplicacoes().get(0).valor()))
                .isEqualTo(v(m.get("pagamentoLancado").get("deviaDepois").asString()));
        assertThat(r3.sobra()).isEqualTo(v("0.00"));
    }

    @Test
    void tresDebitosComPagamentoParcial() {
        JsonNode caso = EXEMPLOS.get("tresDebitos");
        Resultado r = AplicacaoDePagamento.aplicar(debitos(caso.get("saldos")), ExemplosDoConsignado.valor(caso.get("pago")));
        List<BigDecimal> esperado = new ArrayList<>();
        caso.get("aplicacoes").forEach(a -> esperado.add(ExemplosDoConsignado.valor(a)));
        assertThat(r.aplicacoes()).extracting(Aplicacao::valor).containsExactlyElementsOf(esperado);
        assertThat(r.sobra()).isEqualTo(ExemplosDoConsignado.valor(caso.get("sobra")));
    }

    @Test
    void pagamentoAcimaDoDevidoDeixaSobraAAplicar() {
        JsonNode caso = EXEMPLOS.get("pagamentoAcimaDoDevido");
        Resultado r = AplicacaoDePagamento.aplicar(debitos(caso.get("saldos")), ExemplosDoConsignado.valor(caso.get("pago")));
        assertThat(r.aplicacoes()).extracting(Aplicacao::valor).containsExactly(v("100.00"), v("50.00"));
        assertThat(r.sobra()).isEqualTo(v("30.00"));
    }

    @Test
    void debitoQuitadoNaoRecebeAplicacaoVazia() {
        Resultado r = AplicacaoDePagamento.aplicar(
                List.of(new Debito(UUID.randomUUID(), v("0.00")), new Debito(UUID.randomUUID(), v("10.00"))), v("5.00"));
        assertThat(r.aplicacoes()).hasSize(1);
        assertThat(r.aplicacoes().get(0).valor()).isEqualTo(v("5.00"));
    }

    @Test
    void valorNaoPositivoOuComMaisDeDuasCasasERecusado() {
        assertThatThrownBy(() -> AplicacaoDePagamento.aplicar(List.of(), v("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AplicacaoDePagamento.aplicar(List.of(), v("1.005"))).isInstanceOf(IllegalArgumentException.class);
    }
}
