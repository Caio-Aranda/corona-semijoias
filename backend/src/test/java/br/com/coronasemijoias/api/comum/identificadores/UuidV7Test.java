package br.com.coronasemijoias.api.comum.identificadores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void temVersao7EVarianteDaRfc() {
        UUID id = UuidV7.gerar();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(Identificadores.aceito(id)).isTrue();
    }

    @Test
    void guardaOHorarioDaGeracao() {
        long antes = System.currentTimeMillis();
        UUID id = UuidV7.gerar();
        long depois = System.currentTimeMillis();
        assertThat(UuidV7.milissegundosDe(id)).isBetween(antes, depois);
    }

    @Test
    void leOHorarioDoExemploDaRfc9562() {
        // Exemplo do apêndice A.6 da RFC 9562: 0x017F22E279B0 ms = 2022-02-22T19:22:22Z.
        UUID exemplo = UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f");
        assertThat(UuidV7.milissegundosDe(exemplo)).isEqualTo(0x017F22E279B0L);
    }

    @Test
    void milissegundosDiferentesFicamEmOrdem() {
        UUID primeiro = UuidV7.gerar(1_000_000L);
        UUID segundo = UuidV7.gerar(1_000_001L);
        // A ordem do texto (e do índice uuid do PostgreSQL) é a ordem dos bytes.
        assertThat(primeiro.toString()).isLessThan(segundo.toString());
    }

    @Test
    void cemMilSemRepetir() {
        Set<UUID> vistos = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(vistos.add(UuidV7.gerar())).isTrue();
        }
    }

    @Test
    void recusaHorarioForaDaFaixa() {
        assertThatThrownBy(() -> UuidV7.gerar(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UuidV7.gerar(1L << 48)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UuidV7.milissegundosDe(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
