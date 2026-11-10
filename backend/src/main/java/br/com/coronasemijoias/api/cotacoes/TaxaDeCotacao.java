package br.com.coronasemijoias.api.cotacoes;

import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Uma taxa ou paridade de cotação no JSON: texto decimal com ponto e exatamente 8 casas
 * ({@code "5.50130000"}), nunca número JSON (ADR-0006, item 8). Tipo próprio porque o módulo do
 * dinheiro escreve todo {@code BigDecimal} com duas casas e o padrão de dinheiro recusaria a cotação.
 * A cotação só sai do servidor: não há leitura (desserialização) deste tipo.
 */
public record TaxaDeCotacao(BigDecimal valor) {

    /** O padrão publicado no OpenAPI: até 10 dígitos antes do ponto e 8 depois. */
    public static final String PADRAO = "^\\d{1,10}\\.\\d{8}$";

    public TaxaDeCotacao {
        Objects.requireNonNull(valor, "valor");
    }

    @JsonValue
    public String texto() {
        // setScale(8) sem arredondar: o banco já guarda 8 casas, então só completa zeros se faltar.
        return valor.setScale(TaxasDoDia.ESCALA).toPlainString();
    }
}
