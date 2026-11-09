package br.com.coronasemijoias.api.comum.persistencia;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Lê o {@code sync_xid} (tipo {@code xid8} do PostgreSQL, sem tipo JDBC próprio) como número.
 * O {@code xid8} é um inteiro de 64 bits sem sinal; o texto é lido sem sinal. A coluna nunca é
 * gravada pelo Java (quem grava é o gatilho).
 */
@Converter
public class XidComoNumero implements AttributeConverter<Long, String> {

    @Override
    public String convertToDatabaseColumn(Long xid) {
        return xid == null ? null : Long.toUnsignedString(xid);
    }

    @Override
    public Long convertToEntityAttribute(String texto) {
        return texto == null ? null : Long.parseUnsignedLong(texto);
    }
}
