package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.consignado.CalculoDoAcertoDaNota.LinhaDaNota;
import br.com.coronasemijoias.api.consignado.CalculoDoAcertoDaNota.Resultado;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A conta do acerto (§23.8), sem banco. Os números vêm do arquivo de exemplos, o mesmo do Corona Vendas. */
class CalculoDoAcertoDaNotaTest {

    private static final JsonNode EXEMPLOS = ExemplosDoConsignado.ler();

    private static BigDecimal v(String s) {
        return new BigDecimal(s);
    }

    @Test
    void notaDeExemploDaEspecificacao() {
        JsonNode nota = EXEMPLOS.get("notaDeExemplo");
        List<LinhaDaNota> linhas = new ArrayList<>();
        nota.get("linhas").forEach(l -> linhas.add(new LinhaDaNota(l.get("dx").asInt(), l.get("dv").asInt(),
                ExemplosDoConsignado.valor(l.get("precoUnitario")))));
        BigDecimal pago = BigDecimal.ZERO;
        for (JsonNode forma : nota.get("pago")) {
            pago = pago.add(ExemplosDoConsignado.valor(forma));
        }

        Resultado r = CalculoDoAcertoDaNota.calcular(linhas, ExemplosDoConsignado.valor(nota.get("comissaoPercentual")),
                ExemplosDoConsignado.valor(nota.get("pendenciasAnteriores")), pago);

        JsonNode e = nota.get("esperado");
        assertThat(r.itensDx()).isEqualTo(e.get("itensDx").asInt());
        assertThat(r.itensDv()).isEqualTo(e.get("itensDv").asInt());
        assertThat(r.itensVd()).isEqualTo(e.get("itensVd").asInt());
        assertThat(r.valorDx()).isEqualTo(v(e.get("valorDx").asString()));
        assertThat(r.valorDv()).isEqualTo(v(e.get("valorDv").asString()));
        assertThat(r.valorVd()).isEqualTo(v(e.get("valorVd").asString()));
        assertThat(r.vendaPercentual()).isEqualTo(v(e.get("vendaPercentual").asString()));
        assertThat(r.comissao()).isEqualTo(v(e.get("comissao").asString()));
        assertThat(r.bruto()).isEqualTo(v(e.get("bruto").asString()));
        assertThat(r.pendenciasAnteriores()).isEqualTo(v(nota.get("pendenciasAnteriores").asString()));
        assertThat(r.totalAReceber()).isEqualTo(v(e.get("totalAReceber").asString()));
        assertThat(r.totalPago()).isEqualTo(v(e.get("totalPago").asString()));
        assertThat(r.ficouDevendo()).isEqualTo(v(e.get("ficouDevendo").asString()));
    }

    @Test
    void comissaoNosLimitesEArredondamentoHalfUp() {
        for (JsonNode caso : EXEMPLOS.get("casosDeComissao")) {
            BigDecimal vd = ExemplosDoConsignado.valor(caso.get("valorVd"));
            // Uma linha só: DX = VD (nada devolvido), preço = VD.
            Resultado r = CalculoDoAcertoDaNota.calcular(List.of(new LinhaDaNota(1, 0, vd)),
                    ExemplosDoConsignado.valor(caso.get("percentual")), BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(r.comissao()).as(caso.get("nome").asString()).isEqualTo(v(caso.get("comissao").asString()));
            assertThat(r.bruto()).as(caso.get("nome").asString()).isEqualTo(v(caso.get("bruto").asString()));
        }
    }

    @Test
    void comissaoForaDeZeroAQuarentaERecusada() {
        for (JsonNode p : EXEMPLOS.get("comissoesRecusadas")) {
            assertThatThrownBy(() -> CalculoDoAcertoDaNota.calcular(List.of(new LinhaDaNota(1, 0, v("10.00"))),
                    v(p.asString()), BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void comissaoEIncidenteSobreOTotalDaNotaENaoPorLinha() {
        // Duas linhas de 0,05 a 30%: por linha daria 0,02 + 0,02 = 0,04; sobre o total (0,10) dá 0,03.
        Resultado r = CalculoDoAcertoDaNota.calcular(
                List.of(new LinhaDaNota(1, 0, v("0.05")), new LinhaDaNota(1, 0, v("0.05"))), v("30"), BigDecimal.ZERO,
                BigDecimal.ZERO);
        assertThat(r.comissao()).isEqualTo(v("0.03"));
    }

    @Test
    void vendaPercentualComDxZeroMostraZeroSemDividirPorZero() {
        Resultado r = CalculoDoAcertoDaNota.calcular(List.of(new LinhaDaNota(0, 0, v("10.00"))), v("30"), BigDecimal.ZERO,
                BigDecimal.ZERO);
        assertThat(r.vendaPercentual())
                .isEqualTo(v(EXEMPLOS.get("vendaPercentualComDxZero").get("vendaPercentual").asString()));
    }

    @Test
    void devolverMaisQueOLevadoERecusado() {
        assertThatThrownBy(() -> CalculoDoAcertoDaNota.calcular(List.of(new LinhaDaNota(2, 3, v("10.00"))), v("30"),
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ficouDevendoNuncaENegativo() {
        Resultado r = CalculoDoAcertoDaNota.calcular(List.of(new LinhaDaNota(1, 0, v("10.00"))), v("0"), BigDecimal.ZERO,
                v("10.00"));
        assertThat(r.ficouDevendo()).isEqualTo(v("0.00"));
    }
}
