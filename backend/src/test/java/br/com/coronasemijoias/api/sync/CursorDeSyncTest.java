package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorDeSyncTest {

    @Test
    void leEEscreveOTextoOpaco() {
        CursorDeSync cursor = CursorDeSync.ler("3:12345:678");
        assertThat(cursor).isEqualTo(new CursorDeSync(3, 12345, 678));
        assertThat(cursor.texto()).isEqualTo("3:12345:678");
        assertThat(CursorDeSync.inicio(2).texto()).isEqualTo("2:0:0");
    }

    @Test
    void xidUsaOs64BitsSemSinal() {
        CursorDeSync cursor = CursorDeSync.ler("1:18446744073709551615:0");
        assertThat(cursor.xid()).isEqualTo(-1L);
        assertThat(cursor.texto()).isEqualTo("1:18446744073709551615:0");
        assertThat(CursorDeSync.compararPosicao(-1L, 0, Long.MAX_VALUE, 0)).isPositive();
    }

    @Test
    void comparaPrimeiroPeloXidEDepoisPeloSeq() {
        assertThat(CursorDeSync.compararPosicao(10, 99, 11, 1)).isNegative();
        assertThat(CursorDeSync.compararPosicao(11, 1, 11, 2)).isNegative();
        assertThat(CursorDeSync.compararPosicao(11, 2, 11, 2)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "abc", "1:2", "1:2:3:4", "1:-1:0", "-1:0:0", "0:0:0", "1:0:-1", " 1:0:0", "1:0:0 ",
        "1:18446744073709551616:0", "99999999999999999999:0:0", "1:0:99999999999999999999", "1;0;0"
    })
    void textoForaDoFormatoECursorInvalido(String texto) {
        assertThatThrownBy(() -> CursorDeSync.ler(texto))
                .isInstanceOf(CursorInvalidoException.class)
                .hasMessageContaining("refaça a carga completa");
    }

    @Test
    void nuloECursorInvalido() {
        assertThatThrownBy(() -> CursorDeSync.ler(null)).isInstanceOf(CursorInvalidoException.class);
    }
}
