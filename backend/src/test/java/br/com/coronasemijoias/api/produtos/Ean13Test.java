package br.com.coronasemijoias.api.produtos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Dígito verificador do EAN-13 (ADR-0007): só vale para o tipo EAN13 com 13 dígitos. */
class Ean13Test {

    @Test
    void aceitaEanValido() {
        assertThat(Ean13.digitoConfere("7891000100103")).isTrue();
        assertThat(Ean13.digitoConfere("4006381333931")).isTrue();
    }

    @Test
    void recusaEanComDigitoErrado() {
        assertThat(Ean13.digitoConfere("7891000100104")).isFalse();
        assertThat(Ean13.digitoConfere("4006381333930")).isFalse();
    }

    @Test
    void preservaZerosAEsquerda() {
        assertThat(Ean13.digitoConfere("0012345678905")).isTrue();
        assertThat(Ean13.digitoConfere("0012345678906")).isFalse();
    }

    @Test
    void soSeAplicaATrezeDigitos() {
        assertThat(Ean13.temTrezeDigitos("7891000100103")).isTrue();
        assertThat(Ean13.temTrezeDigitos("789100010010")).isFalse();
        assertThat(Ean13.temTrezeDigitos("78910001001034")).isFalse();
        assertThat(Ean13.temTrezeDigitos("78910001001A3")).isFalse();
    }
}
