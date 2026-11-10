package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma linha de {@code cotacoes}: o Dólar e o Guarani de um dia útil do BCB (ADR-0006, item 4).
 * Imutável. A tela usa as taxas de venda (item 5).
 */
public record Cotacao(UUID id, LocalDate dataCotacao, TaxasDoDia taxas, Instant buscadaEm, String fonte) {

    /** US$ do valor em reais, só para mostrar (nunca gravar). */
    public BigDecimal emDolar(BigDecimal reais) {
        return ConversaoDeMoeda.paraDolar(reais, taxas.usdVenda());
    }

    /** Gs. do valor em reais, só para mostrar (nunca gravar). */
    public BigDecimal emGuarani(BigDecimal reais) {
        return ConversaoDeMoeda.paraGuarani(reais, taxas.pygVenda());
    }
}
