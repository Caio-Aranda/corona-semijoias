package br.com.coronasemijoias.api.aplicativo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class VersaoDoAppTest {

    private static VersaoDoApp v(String texto) {
        return VersaoDoApp.ler(texto).orElseThrow();
    }

    @Test
    void comparaNumeroANumeroENaoComoTexto() {
        assertThat(v("1.10.0")).isGreaterThan(v("1.9.9"));
        assertThat(v("2.0.0")).isGreaterThan(v("1.99.99"));
        assertThat(v("1.2.10")).isGreaterThan(v("1.2.9"));
        assertThat(v("1.2.0")).isEqualByComparingTo(v("1.2.0"));
        assertThat(v("0.9.9")).isLessThan(v("1.0.0"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "1.2", "1.2.3.4", "a.b.c", "1.2.x", "-1.0.0", "1..3", "v1.2.3", "1.2.3-beta", "9999999.0.0"})
    void versaoIlegivelNaoLe(String texto) {
        assertThat(VersaoDoApp.ler(texto)).isEmpty();
    }

    @Test
    void aceitaEspacosNasPontas() {
        assertThat(VersaoDoApp.ler(" 1.4.2 ")).contains(new VersaoDoApp(1, 4, 2));
    }

    @Test
    void tipoSoAceitaOsDoisValoresExatos() {
        assertThat(TipoDeApp.de("gestao")).contains(TipoDeApp.GESTAO);
        assertThat(TipoDeApp.de("vendas")).contains(TipoDeApp.VENDAS);
        assertThat(TipoDeApp.de("Gestao")).isEmpty();
        assertThat(TipoDeApp.de("admin")).isEmpty();
        assertThat(TipoDeApp.de(null)).isEmpty();
    }
}
