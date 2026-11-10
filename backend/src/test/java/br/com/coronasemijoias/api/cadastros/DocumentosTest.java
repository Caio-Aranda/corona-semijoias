package br.com.coronasemijoias.api.cadastros;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CPF e CNPJ: normalização e dígitos verificadores (quem valida é o servidor). */
class DocumentosTest {

    @Test
    void cpfValidoComOuSemMascara() {
        assertThat(Documentos.normalizarCpf("529.982.247-25")).isEqualTo("52998224725");
        assertThat(Documentos.normalizarCpf(" 52998224725 ")).isEqualTo("52998224725");
        assertThat(Documentos.cpfValido("52998224725")).isTrue();
    }

    @Test
    void cpfComDigitoErradoOuTamanhoErradoOuRepetidoNaoVale() {
        assertThat(Documentos.cpfValido("52998224724")).isFalse();
        assertThat(Documentos.cpfValido("5299822472")).isFalse();
        assertThat(Documentos.cpfValido("11111111111")).isFalse();
        assertThat(Documentos.cpfValido("00000000000")).isFalse();
        assertThat(Documentos.cpfValido("5299822472a")).isFalse();
        assertThat(Documentos.cpfValido(null)).isFalse();
    }

    @Test
    void cnpjValidoComOuSemMascara() {
        assertThat(Documentos.normalizarCnpj("11.222.333/0001-81")).isEqualTo("11222333000181");
        assertThat(Documentos.cnpjValido("11222333000181")).isTrue();
    }

    @Test
    void cnpjAlfanumericoDoExemploOficialVale() {
        assertThat(Documentos.normalizarCnpj("12.abc.345/01de-35")).isEqualTo("12ABC34501DE35");
        assertThat(Documentos.cnpjValido("12ABC34501DE35")).isTrue();
        assertThat(Documentos.cnpjValido("12ABC34501DE36")).isFalse();
    }

    @Test
    void cnpjErradoNaoVale() {
        assertThat(Documentos.cnpjValido("11222333000182")).isFalse();
        assertThat(Documentos.cnpjValido("1122233300018")).isFalse();
        assertThat(Documentos.cnpjValido("00000000000000")).isFalse();
        assertThat(Documentos.cnpjValido("11222333000A81")).isFalse(); // letra nos dígitos verificadores
        assertThat(Documentos.cnpjValido(null)).isFalse();
    }

    @Test
    void vazioViraNulo() {
        assertThat(Documentos.normalizarCpf("  ")).isNull();
        assertThat(Documentos.normalizarCpf(null)).isNull();
        assertThat(Documentos.normalizarCnpj("")).isNull();
    }
}
