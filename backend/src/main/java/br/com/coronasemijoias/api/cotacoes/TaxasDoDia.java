package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * As oito taxas lidas do arquivo de fechamento de um dia (ADR-0006, item 4), como vieram, com 8 casas
 * ({@code NUMERIC(18,8)}). Só existe inteira: não há cotação parcial.
 */
public record TaxasDoDia(
        BigDecimal usdCompra, BigDecimal usdVenda, BigDecimal usdParidadeCompra, BigDecimal usdParidadeVenda,
        BigDecimal pygCompra, BigDecimal pygVenda, BigDecimal pygParidadeCompra, BigDecimal pygParidadeVenda) {

    /** Casas das taxas no banco. */
    public static final int ESCALA = 8;

    public TaxasDoDia {
        Objects.requireNonNull(usdCompra, "usdCompra");
        Objects.requireNonNull(usdVenda, "usdVenda");
        Objects.requireNonNull(usdParidadeCompra, "usdParidadeCompra");
        Objects.requireNonNull(usdParidadeVenda, "usdParidadeVenda");
        Objects.requireNonNull(pygCompra, "pygCompra");
        Objects.requireNonNull(pygVenda, "pygVenda");
        Objects.requireNonNull(pygParidadeCompra, "pygParidadeCompra");
        Objects.requireNonNull(pygParidadeVenda, "pygParidadeVenda");
    }

    /**
     * As taxas da cotação da dona (ADR-0006, item 15): uma por moeda, a mesma para compra e venda. O Guarani é
     * 1 ÷ guaranis por real (8 casas, HALF_UP) e a paridade do Guarani é guaranis por dólar, como o BCB publica.
     */
    public static TaxasDoDia daDona(BigDecimal usd, BigDecimal guaranisPorReal) {
        BigDecimal dolar = ConversaoDeMoeda.taxaComOitoCasas(usd);
        BigDecimal pyg = ConversaoDeMoeda.taxaDoGuarani(guaranisPorReal);
        BigDecimal um = BigDecimal.ONE.setScale(ESCALA);
        BigDecimal paridadePyg = ConversaoDeMoeda.taxaComOitoCasas(dolar.multiply(guaranisPorReal));
        return new TaxasDoDia(dolar, dolar, um, um, pyg, pyg, paridadePyg, paridadePyg);
    }
}
