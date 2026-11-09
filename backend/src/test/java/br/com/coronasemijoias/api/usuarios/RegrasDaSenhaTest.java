package br.com.coronasemijoias.api.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import org.junit.jupiter.api.Test;

/** Regras da senha nova (ADR-0004, item 6): 8 caracteres, letras e números, no máximo 72 bytes. */
class RegrasDaSenhaTest {

    @Test
    void senhaBoaServe() {
        assertThat(RegrasDaSenha.problema("abcdefg1")).isEmpty();
        assertThat(RegrasDaSenha.problema("Senha-Nova-2026")).isEmpty();
        assertThat(RegrasDaSenha.problema("açúcar123")).isEmpty();
    }

    @Test
    void menosDeOitoCaracteresEhFraca() {
        assertThat(RegrasDaSenha.problema("abcdef1")).contains(CodigoErro.SENHA_FRACA);
        assertThat(RegrasDaSenha.problema("abcdefg1")).isEmpty();
    }

    @Test
    void oitoCaracteresContamPorCaractereENaoPorByte() {
        // 7 letras com acento (14 bytes) mais 1 número = 8 caracteres.
        assertThat(RegrasDaSenha.problema("ççççççç1")).isEmpty();
        assertThat(RegrasDaSenha.problema("çççççç1")).contains(CodigoErro.SENHA_FRACA);
    }

    @Test
    void precisaDeLetrasENumeros() {
        assertThat(RegrasDaSenha.problema("somenteletras")).contains(CodigoErro.SENHA_FRACA);
        assertThat(RegrasDaSenha.problema("1234567890")).contains(CodigoErro.SENHA_FRACA);
        assertThat(RegrasDaSenha.problema("!!!!!!!!!!11")).contains(CodigoErro.SENHA_FRACA);
        assertThat(RegrasDaSenha.problema("        ")).contains(CodigoErro.SENHA_FRACA);
    }

    @Test
    void vaziaOuNulaEhFraca() {
        assertThat(RegrasDaSenha.problema("")).contains(CodigoErro.SENHA_FRACA);
        assertThat(RegrasDaSenha.problema(null)).contains(CodigoErro.SENHA_FRACA);
    }

    @Test
    void ate72BytesServeEMaisDisso0EhLongaDemais() {
        String com72 = "a1" + "x".repeat(70);
        assertThat(com72.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(RegrasDaSenha.problema(com72)).isEmpty();
        assertThat(RegrasDaSenha.problema(com72 + "x")).contains(CodigoErro.SENHA_LONGA_DEMAIS);
    }

    @Test
    void oLimiteEEmBytesNaoEmCaracteres() {
        // 35 letras com acento (70 bytes) e um número = 71 bytes serve; com mais uma letra são 73.
        assertThat(RegrasDaSenha.problema("ç".repeat(35) + "1")).isEmpty();
        assertThat(RegrasDaSenha.problema("ç".repeat(35) + "1ç")).contains(CodigoErro.SENHA_LONGA_DEMAIS);
        assertThat(RegrasDaSenha.problema("ç".repeat(36) + "1")).contains(CodigoErro.SENHA_LONGA_DEMAIS);
    }

    @Test
    void tamanhoVemAntesDaForca() {
        // Longa demais e também sem número: o primeiro problema é o tamanho.
        assertThat(RegrasDaSenha.problema("x".repeat(80))).contains(CodigoErro.SENHA_LONGA_DEMAIS);
    }
}
