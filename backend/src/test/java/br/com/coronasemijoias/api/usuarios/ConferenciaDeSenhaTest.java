package br.com.coronasemijoias.api.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** BCrypt sempre roda, para o tempo de resposta não revelar quais logins existem (ADR-0004, item 6). */
class ConferenciaDeSenhaTest {

    private final BCryptPasswordEncoder bcrypt = spy(new BCryptPasswordEncoder(4));
    private final ConferenciaDeSenha conferencia = new ConferenciaDeSenha(bcrypt);
    private final String hashDaSenha = new BCryptPasswordEncoder(4).encode("senha-certa-1");

    @Test
    void senhaCertaConfere() {
        assertThat(conferencia.confere("senha-certa-1", hashDaSenha)).isTrue();
        assertThat(conferencia.confere("senha-errada-1", hashDaSenha)).isFalse();
    }

    @Test
    void loginInexistenteTambemPassaPeloBcrypt() {
        clearInvocations(bcrypt);
        assertThat(conferencia.confere("qualquer-senha", null)).isFalse();
        verify(bcrypt, times(1)).matches(eq("qualquer-senha"), anyString());
    }

    @Test
    void senhaComMaisDe72BytesNuncaConfereMasPassaPeloBcrypt() {
        String senha72 = "a".repeat(72);
        String hash72 = new BCryptPasswordEncoder(4).encode(senha72);
        assertThat(conferencia.confere(senha72, hash72)).isTrue();

        clearInvocations(bcrypt);
        // Sem a recusa, o BCrypt diria que confere (ele ignora o que passa de 72 bytes).
        assertThat(conferencia.confere(senha72 + "b", hash72)).isFalse();
        assertThat(conferencia.confere("é".repeat(37), null)).isFalse();
        verify(bcrypt, times(2)).matches(any(), anyString());
        verify(bcrypt, never()).matches(eq(senha72 + "b"), anyString());
    }

    @Test
    void hashDeCustoMenorPrecisaSerRegravado() {
        ConferenciaDeSenha custo12 = new ConferenciaDeSenha(new BCryptPasswordEncoder(12));
        assertThat(custo12.precisaRegravar(new BCryptPasswordEncoder(10).encode("x"))).isTrue();
        assertThat(conferencia.precisaRegravar(hashDaSenha)).isFalse();
    }

    @Test
    void naoGeraHashDeSenhaLongaDemais() {
        assertThatThrownBy(() -> conferencia.gerarHash("a".repeat(73))).isInstanceOf(IllegalArgumentException.class);
        assertThat(conferencia.gerarHash("a".repeat(72))).startsWith("$2a$04$");
    }
}
