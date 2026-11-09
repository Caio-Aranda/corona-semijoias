package br.com.coronasemijoias.api.comum.identificadores;

import java.util.UUID;

/**
 * Regra dos identificadores (ADR-0002, item 1): o servidor aceita só UUID versão 4 ou 7, com a
 * variante da RFC 9562, e recusa qualquer outra coisa. O banco tem a mesma regra num {@code CHECK}
 * criado pelas funções {@code corona.aplicar_convencoes_*}.
 */
public final class Identificadores {

    private static final int VARIANTE_RFC = 2;

    private Identificadores() {
    }

    public static boolean aceito(UUID id) {
        return id != null && id.variant() == VARIANTE_RFC && (id.version() == 4 || id.version() == 7);
    }
}
