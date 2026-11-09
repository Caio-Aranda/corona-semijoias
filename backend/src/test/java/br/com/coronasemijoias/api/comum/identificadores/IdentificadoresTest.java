package br.com.coronasemijoias.api.comum.identificadores;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Só v4 e v7 com a variante da RFC são aceitos (ADR-0002). Exemplos da RFC 9562, apêndices A e B. */
class IdentificadoresTest {

    @Test
    void aceitaV4EV7() {
        assertThat(Identificadores.aceito(UUID.randomUUID())).isTrue();
        assertThat(Identificadores.aceito(UUID.fromString("919108f7-52d1-4320-9bac-f847db4148a8"))).isTrue();
        assertThat(Identificadores.aceito(UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"))).isTrue();
        assertThat(Identificadores.aceito(UuidV7.gerar())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "c232ab00-9414-11ec-b3c8-9f6bdeced846", // v1
        "5df41881-3aed-3515-88a7-2f4a814cf09e", // v3
        "2ed6657d-e927-568b-95e1-2665a8aea6a2", // v5
        "1ec9414c-232a-6b00-b3c8-9f6bdeced846", // v6
        "2489e9ad-2ee2-8e00-8ec9-32d5f69181c0", // v8
        "00000000-0000-0000-0000-000000000000", // nulo
        "ffffffff-ffff-ffff-ffff-ffffffffffff", // máximo
        "919108f7-52d1-4320-cbac-f847db4148a8", // "v4" com a variante da Microsoft
        "017f22e2-79b0-7cc3-08c4-dc0c0c07398f", // "v7" com a variante antiga (NCS)
    })
    void recusaOutrasVersoesEVariantes(String texto) {
        assertThat(Identificadores.aceito(UUID.fromString(texto))).isFalse();
    }

    @Test
    void recusaV3GeradoPeloJavaENulo() {
        assertThat(Identificadores.aceito(UUID.nameUUIDFromBytes("corona".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(Identificadores.aceito(null)).isFalse();
    }
}
