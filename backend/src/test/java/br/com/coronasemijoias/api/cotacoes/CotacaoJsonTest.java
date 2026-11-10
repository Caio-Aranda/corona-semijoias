package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.comum.json.DinheiroJsonModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A cotação no JSON (ADR-0006, item 8): texto com ponto e 8 casas, nunca número, e sem passar pelo
 * módulo do dinheiro (que escreveria duas casas).
 */
class CotacaoJsonTest {

    // O mesmo mapper da API: com o módulo do dinheiro, que trata todo BigDecimal.
    private final JsonMapper json = JsonMapper.builder().addModule(new DinheiroJsonModule()).build();

    private static Cotacao cotacaoDeExemplo() {
        TaxasDoDia taxas = LeitorDoArquivoDeFechamento.ler(
                LocalDate.of(2026, 11, 6), LeitorDoArquivoDeFechamentoTest.arquivo("20261106.csv"));
        return new Cotacao(UUID.fromString("0192f3a0-0000-7000-8000-000000000001"), LocalDate.of(2026, 11, 6), taxas,
                Instant.parse("2026-11-06T17:00:00Z"), "https://exemplo/20261106.csv");
    }

    @Test
    void cadaTaxaSaiComoTextoDeOitoCasas() throws Exception {
        JsonNode raiz = json.readTree(json.writeValueAsString(CotacaoResposta.de(cotacaoDeExemplo())));

        assertThat(raiz.get("dataCotacao").asString()).isEqualTo("2026-11-06");
        assertThat(raiz.get("usd").get("venda").isString()).isTrue();
        assertThat(raiz.get("usd").get("venda").asString()).isEqualTo("5.50130000");
        assertThat(raiz.get("usd").get("compra").asString()).isEqualTo("5.50070000");
        assertThat(raiz.get("usd").get("paridadeCompra").asString()).isEqualTo("1.00000000");
        assertThat(raiz.get("pyg").get("compra").asString()).isEqualTo("0.00086560");
        assertThat(raiz.get("pyg").get("venda").asString()).isEqualTo("0.00089500");
        assertThat(raiz.get("pyg").get("paridadeCompra").asString()).isEqualTo("6147.00000000");
        assertThat(raiz.get("pyg").get("paridadeVenda").asString()).isEqualTo("6355.00000000");
    }

    @Test
    void todoValorDeTaxaObedeceAoPadraoPublicadoNoOpenApi() throws Exception {
        JsonNode raiz = json.readTree(json.writeValueAsString(CotacaoResposta.de(cotacaoDeExemplo())));
        Pattern padrao = Pattern.compile(TaxaDeCotacao.PADRAO);

        for (String moeda : new String[] {"usd", "pyg"}) {
            for (String campo : new String[] {"compra", "venda", "paridadeCompra", "paridadeVenda"}) {
                assertThat(raiz.get(moeda).get(campo).asString()).matches(padrao);
            }
        }
    }

    @Test
    void naoVirouNumeroJson() throws Exception {
        String texto = json.writeValueAsString(CotacaoResposta.de(cotacaoDeExemplo()));
        assertThat(texto).contains("\"venda\":\"5.50130000\"").doesNotContain("\"venda\":5");
    }

    @Test
    void oDinheiroContinuaComDuasCasas() throws Exception {
        // O tipo próprio não mexe no padrão de dinheiro: BigDecimal puro segue a regra do ADR-0002.
        assertThat(json.writeValueAsString(new BigDecimal("5.5"))).isEqualTo("\"5.50\"");
    }

    @Test
    void completaZerosSemArredondar() {
        assertThat(new TaxaDeCotacao(new BigDecimal("6147")).texto()).isEqualTo("6147.00000000");
        assertThat(new TaxaDeCotacao(new BigDecimal("0.0008656")).texto()).isEqualTo("0.00086560");
    }
}
