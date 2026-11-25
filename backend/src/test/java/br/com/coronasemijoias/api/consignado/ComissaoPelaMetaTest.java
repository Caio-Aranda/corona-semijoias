package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.consignado.CalculoDoAcertoDaNota.LinhaDaNota;
import br.com.coronasemijoias.api.consignado.CalculoDoAcertoDaNota.Resultado;
import br.com.coronasemijoias.api.consignado.ComissaoPelaMeta.Decisao;
import br.com.coronasemijoias.api.consignado.ComissaoPelaMeta.Parametros;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A comissão da revendedora pela meta (§23.13), sem banco. Os números vêm do arquivo de exemplos, o mesmo do Corona Vendas. */
class ComissaoPelaMetaTest {

    private static final JsonNode META = ExemplosDoConsignado.ler().get("comissaoPelaMeta");

    private static BigDecimal v(JsonNode no) {
        return no == null || no.isNull() ? null : new BigDecimal(no.asString());
    }

    private static Parametros parametros(JsonNode p) {
        return new Parametros(v(p.get("metaEmGuarani")), v(p.get("percentualAbaixo")), v(p.get("percentualNaMeta")));
    }

    @Test
    void casosDoArquivoDeExemplos() {
        Parametros parametros = parametros(META.get("parametros"));
        for (JsonNode c : META.get("casos")) {
            Decisao d = ComissaoPelaMeta.decidir(v(c.get("valorVd")), v(c.get("taxaGuarani")), parametros);
            String nome = c.get("nome").asString();
            assertThat(d.percentual()).as(nome).isEqualByComparingTo(v(c.get("percentual")));
            assertThat(d.bateuAMeta()).as(nome).isEqualTo(c.get("bateuAMeta").asBoolean());
            if (c.get("vdEmGuarani").isNull()) {
                assertThat(d.vdEmGuarani()).as(nome).isNull();
            } else {
                assertThat(d.vdEmGuarani()).as(nome).isEqualByComparingTo(v(c.get("vdEmGuarani")));
            }
        }
    }

    @Test
    void metaEPercentuaisVemDosParametrosNaoDeConstantes() {
        for (JsonNode c : META.get("outrosParametros")) {
            Decisao d = ComissaoPelaMeta.decidir(v(c.get("valorVd")), v(c.get("taxaGuarani")), parametros(c));
            assertThat(d.percentual()).as(c.get("nome").asString()).isEqualByComparingTo(v(c.get("percentual")));
        }
    }

    @Test
    void parametrosInvalidosSaoRecusados() {
        for (JsonNode c : META.get("parametrosRecusados")) {
            Parametros p = new Parametros(v(c.get("metaEmGuarani")), v(c.get("percentualAbaixo")), v(c.get("percentualNaMeta")));
            assertThat(p.valido()).as(c.get("nome").asString()).isFalse();
            assertThatThrownBy(() -> ComissaoPelaMeta.decidir(new BigDecimal("100.00"), new BigDecimal("0.001"), p))
                    .as(c.get("nome").asString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(parametros(META.get("parametros")).valido()).isTrue();
    }

    @Test
    void notaDeExemploComARegraMetaComCotacaoFixa() {
        JsonNode nota = ExemplosDoConsignado.ler().get("notaDeExemplo");
        JsonNode caso = META.get("notaComAMeta");
        List<LinhaDaNota> linhas = new ArrayList<>();
        nota.get("linhas").forEach(l -> linhas.add(new LinhaDaNota(l.get("dx").asInt(), l.get("dv").asInt(),
                new BigDecimal(l.get("precoUnitario").asString()))));
        BigDecimal vd = CalculoDoAcertoDaNota
                .calcular(linhas, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).valorVd();

        for (String nome : List.of("bateu", "naoBateu")) {
            JsonNode c = caso.get(nome);
            Decisao d = ComissaoPelaMeta.decidir(vd, v(c.get("taxaGuarani")), parametros(META.get("parametros")));
            assertThat(d.vdEmGuarani()).as(nome).isEqualByComparingTo(v(c.get("vdEmGuarani")));
            assertThat(d.percentual()).as(nome).isEqualByComparingTo(v(c.get("percentual")));

            Resultado r = CalculoDoAcertoDaNota.calcular(linhas, d.percentual(), v(caso.get("pendenciasAnteriores")),
                    v(caso.get("pago")));
            JsonNode e = c.get("esperado");
            assertThat(r.comissao()).as(nome).isEqualTo(v(e.get("comissao")));
            assertThat(r.bruto()).as(nome).isEqualTo(v(e.get("bruto")));
            assertThat(r.totalAReceber()).as(nome).isEqualTo(v(e.get("totalAReceber")));
            assertThat(r.ficouDevendo()).as(nome).isEqualTo(v(e.get("ficouDevendo")));
        }
    }
}
