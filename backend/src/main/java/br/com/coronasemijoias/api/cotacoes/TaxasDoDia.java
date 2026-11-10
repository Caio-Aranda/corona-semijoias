package br.com.coronasemijoias.api.cotacoes;

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
}
