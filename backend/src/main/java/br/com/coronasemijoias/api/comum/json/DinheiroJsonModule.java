package br.com.coronasemijoias.api.comum.json;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Dinheiro no JSON é sempre texto decimal com ponto e até duas casas (ADR-0002, R6):
 * sai como {@code "123.45"} e só entra como texto. Número JSON é recusado.
 */
public final class DinheiroJsonModule extends SimpleModule {

    /** Mesmo padrão publicado no OpenAPI (NUMERIC(14,2): até 12 dígitos antes do ponto). */
    public static final String PADRAO = "^-?\\d{1,12}(\\.\\d{1,2})?$";

    private static final Pattern FORMATO = Pattern.compile(PADRAO);

    public DinheiroJsonModule() {
        super("DinheiroJsonModule");
        addSerializer(BigDecimal.class, new Serializador());
        addDeserializer(BigDecimal.class, new Desserializador());
    }

    static final class Serializador extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal valor, JsonGenerator gerador, SerializationContext contexto) {
            // Completa para duas casas sem arredondar (acrescentar zeros nunca muda o valor).
            BigDecimal exibido = valor.scale() < 2 ? valor.setScale(2) : valor;
            gerador.writeString(exibido.toPlainString());
        }
    }

    static final class Desserializador extends ValueDeserializer<BigDecimal> {
        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext contexto) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (BigDecimal) contexto.handleUnexpectedToken(BigDecimal.class, parser);
            }
            String texto = parser.getString().trim();
            if (!FORMATO.matcher(texto).matches()) {
                return (BigDecimal) contexto.handleWeirdStringValue(
                        BigDecimal.class, texto, "valor em dinheiro fora do formato");
            }
            return new BigDecimal(texto);
        }
    }
}
