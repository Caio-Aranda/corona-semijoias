package br.com.coronasemijoias.api.comum.identificadores;

import java.util.Comparator;
import java.util.UUID;

/**
 * A ordem em que o PostgreSQL põe os {@code uuid} ({@code ORDER BY id}): byte a byte, sem sinal.
 *
 * <p>O {@link UUID#compareTo} do Java compara os dois {@code long} COM sinal, então um id que começa com 8 a f vem antes
 * de um que começa com 0 a 7, ao contrário do banco. Quem trava várias linhas (ADR-0008, item 7) precisa usar esta ordem,
 * a mesma dos caminhos que travam pelo {@code ORDER BY id}; senão duas operações com as mesmas linhas se esperam em volta.
 */
public final class OrdemDoBanco {

    /** Compara como o PostgreSQL compara {@code uuid}. */
    public static final Comparator<UUID> UUIDS = (a, b) -> {
        int comparacao = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return comparacao != 0 ? comparacao : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    private OrdemDoBanco() {
    }
}
