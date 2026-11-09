package br.com.coronasemijoias.api.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** O prazo da senha temporária, com relógio fixo (ADR-0004, item 6): vale 7 dias e vence no dia 8. */
class UsuarioTest {

    private static final Instant CRIACAO = Instant.parse("2026-11-09T12:00:00Z");

    private static Instant em(Duration depois) {
        return Clock.fixed(CRIACAO.plus(depois), ZoneOffset.UTC).instant();
    }

    private static Usuario comSenhaTemporaria() {
        return Usuario.novoComSenhaTemporaria("Maria", "maria", "$2a$04$hash", "VENDEDOR", null,
                CRIACAO.plus(Duration.ofDays(7)));
    }

    @Test
    void valeAteOFimDoSetimoDiaEVenceNoOitavo() {
        Usuario usuario = comSenhaTemporaria();
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ZERO))).isFalse();
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(6)))).isFalse();
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(7).minusSeconds(1)))).isFalse();
        // O instante do vencimento já conta como vencido.
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(7)))).isTrue();
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(8)))).isTrue();
    }

    @Test
    void semVencimentoNuncaVence() {
        Usuario admin = Usuario.novoComSenhaSemVencimento("Dona", "dona", "$2a$04$hash", "ADMIN", null);
        assertThat(admin.isTrocaDeSenhaObrigatoria()).isTrue();
        assertThat(admin.getSenhaTemporariaVenceEm()).isNull();
        assertThat(admin.senhaTemporariaVencida(em(Duration.ofDays(3650)))).isFalse();
    }

    @Test
    void senhaDefinitivaNaoExpira() {
        Usuario usuario = comSenhaTemporaria();
        usuario.trocarSenha("$2a$04$outro", em(Duration.ofDays(1)));
        assertThat(usuario.isTrocaDeSenhaObrigatoria()).isFalse();
        assertThat(usuario.getSenhaTemporariaVenceEm()).isNull();
        assertThat(usuario.getCredencialAlteradaEm()).isEqualTo(em(Duration.ofDays(1)));
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(400)))).isFalse();
    }

    @Test
    void redefinirComecaUmaContagemNova() {
        Usuario usuario = comSenhaTemporaria();
        Instant redefinicao = em(Duration.ofDays(10));
        usuario.redefinirSenha("$2a$04$novo", redefinicao.plus(Duration.ofDays(7)), redefinicao);
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(10)))).isFalse();
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(16)))).isFalse();
        assertThat(usuario.senhaTemporariaVencida(em(Duration.ofDays(17)))).isTrue();
        assertThat(usuario.getCredencialAlteradaEm()).isEqualTo(redefinicao);
    }

    @Test
    void naoMostraOHashNoToString() {
        assertThat(comSenhaTemporaria().toString()).doesNotContain("$2a$04$hash");
    }
}
