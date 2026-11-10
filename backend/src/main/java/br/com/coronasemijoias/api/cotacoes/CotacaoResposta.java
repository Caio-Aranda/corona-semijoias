package br.com.coronasemijoias.api.cotacoes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cotação como sai da API (ADR-0006, item 8): as quatro colunas de cada moeda, como texto de 8 casas.
 * Ainda sem rota (a tela da dona é da Fase 1 e o vendedor recebe pela API na Fase 4).
 *
 * @param dataCotacao dia do arquivo do BCB ({@code AAAA-MM-DD})
 * @param buscadaEm   hora da leitura, em UTC
 */
public record CotacaoResposta(UUID id, LocalDate dataCotacao, TaxasDaMoeda usd, TaxasDaMoeda pyg, Instant buscadaEm) {

    public record TaxasDaMoeda(
            TaxaDeCotacao compra, TaxaDeCotacao venda, TaxaDeCotacao paridadeCompra, TaxaDeCotacao paridadeVenda) {
    }

    public static CotacaoResposta de(Cotacao cotacao) {
        TaxasDoDia t = cotacao.taxas();
        return new CotacaoResposta(
                cotacao.id(),
                cotacao.dataCotacao(),
                new TaxasDaMoeda(taxa(t.usdCompra()), taxa(t.usdVenda()), taxa(t.usdParidadeCompra()), taxa(t.usdParidadeVenda())),
                new TaxasDaMoeda(taxa(t.pygCompra()), taxa(t.pygVenda()), taxa(t.pygParidadeCompra()), taxa(t.pygParidadeVenda())),
                cotacao.buscadaEm());
    }

    private static TaxaDeCotacao taxa(java.math.BigDecimal valor) {
        return new TaxaDeCotacao(valor);
    }
}
