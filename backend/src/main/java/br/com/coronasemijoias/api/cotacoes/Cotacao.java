package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma linha de {@code cotacoes}: o Dólar e o Guarani de um dia útil do BCB (ADR-0006, item 4) ou o que a dona
 * digitou durante o dia (item 15; {@code lancadaPor} e {@code guaranisPorReal} só nela). Imutável. A tela usa as
 * taxas de venda (item 5).
 */
public record Cotacao(
        UUID id, LocalDate dataCotacao, TaxasDoDia taxas, Instant buscadaEm, String fonte, String origem,
        Instant vigenteDesde, UUID lancadaPor, BigDecimal guaranisPorReal) {

    public static final String BANCO_CENTRAL = "BANCO_CENTRAL";
    public static final String DONA = "DONA";

    /** {@code true} quando foi a dona que digitou. */
    public boolean daDona() {
        return DONA.equals(origem);
    }

    /** US$ do valor em reais, só para mostrar (nunca gravar). */
    public BigDecimal emDolar(BigDecimal reais) {
        return ConversaoDeMoeda.paraDolar(reais, taxas.usdVenda());
    }

    /** Gs. do valor em reais, só para mostrar (nunca gravar). */
    public BigDecimal emGuarani(BigDecimal reais) {
        return ConversaoDeMoeda.paraGuarani(reais, taxas.pygVenda());
    }
}
