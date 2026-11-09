package br.com.coronasemijoias.api.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Formato do login ao CRIAR usuário: 3 a 40 caracteres, letras sem acento, números e ponto. */
class LoginsTest {

    @Test
    void aceitaOFormatoCerto() {
        for (String login : new String[] {"ana", "maria.silva", "joao2", "a1b", "x".repeat(40),
                "123", "m.s.a.1"}) {
            assertThat(Logins.temFormatoParaCriar(login)).as(login).isTrue();
        }
    }

    @Test
    void recusaOQueNaoSegueOFormato() {
        for (String login : new String[] {"", "ab", "x".repeat(41), "maria silva", "joão", "Maria", "maria!", ".maria",
                "maria.", "-maria", "maria_", "maria-silva", "maria_silva", "maria@loja", "maria\tsilva", "m​aria"}) {
            assertThat(Logins.temFormatoParaCriar(login)).as(login).isFalse();
        }
        assertThat(Logins.temFormatoParaCriar(null)).isFalse();
    }

    @Test
    void oFormatoValeDepoisDeNormalizar() {
        assertThat(Logins.temFormatoParaCriar(Logins.normalizar("  Maria.Silva \t"))).isTrue();
    }
}
