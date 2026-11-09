package br.com.coronasemijoias.api.comum.identificadores;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Gera UUID versão 7 (RFC 9562, seção 5.7) para o que nasce no servidor (ADR-0002, item 1).
 *
 * <p>Formato: 48 bits com os milissegundos desde 1970 (UTC), 4 bits da versão (7), 12 bits
 * aleatórios, 2 bits da variante RFC e 62 bits aleatórios. São 74 bits de {@link SecureRandom}
 * por milissegundo, então a unicidade não depende do relógio. A ordem entre UUIDs do mesmo
 * milissegundo não é garantida nem necessária: a ordem da sincronização vem do {@code sync_seq}.
 *
 * <p>Implementação própria (sem biblioteca) porque a especificação é curta e o código cabe aqui;
 * os testes conferem a versão, a variante, o horário e a unicidade.
 */
public final class UuidV7 {

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final long LIMITE_DO_HORARIO = 1L << 48;

    private UuidV7() {
    }

    public static UUID gerar() {
        return gerar(System.currentTimeMillis());
    }

    static UUID gerar(long milissegundos) {
        if (milissegundos < 0 || milissegundos >= LIMITE_DO_HORARIO) {
            throw new IllegalArgumentException("Horário fora do que o UUID v7 representa: " + milissegundos);
        }
        long aleatorioA = ALEATORIO.nextLong();
        long aleatorioB = ALEATORIO.nextLong();
        long altos = (milissegundos << 16) | 0x7000L | (aleatorioA & 0x0FFFL);
        long baixos = (aleatorioB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(altos, baixos);
    }

    /** Milissegundos desde 1970 (UTC) gravados num UUID v7. */
    public static long milissegundosDe(UUID uuid) {
        if (uuid.version() != 7 || uuid.variant() != 2) {
            throw new IllegalArgumentException("Não é um UUID v7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
