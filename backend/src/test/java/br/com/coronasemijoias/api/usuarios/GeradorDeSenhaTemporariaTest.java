package br.com.coronasemijoias.api.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A senha temporária é aleatória, legível e já obedece às regras da senha (ADR-0004, item 6). */
class GeradorDeSenhaTemporariaTest {

    private static final String CONFUSOS = "0O1lIo";

    private final GeradorDeSenhaTemporaria gerador = new GeradorDeSenhaTemporaria();

    @Test
    void alfabetoNaoTemCaracteresQueSeConfundem() {
        for (char confuso : CONFUSOS.toCharArray()) {
            assertThat(GeradorDeSenhaTemporaria.ALFABETO).doesNotContain(String.valueOf(confuso));
        }
        assertThat(GeradorDeSenhaTemporaria.ALFABETO).hasSize(56);
    }

    @Test
    void milSenhasObedecemAsRegrasENaoSeRepetem() {
        Set<String> vistas = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String senha = gerador.gerar();
            assertThat(senha).hasSize(GeradorDeSenhaTemporaria.TAMANHO);
            assertThat(RegrasDaSenha.problema(senha)).as(senha).isEmpty();
            assertThat(senha).matches("[A-Za-z0-9]+");
            for (char confuso : CONFUSOS.toCharArray()) {
                assertThat(senha).doesNotContain(String.valueOf(confuso));
            }
            vistas.add(senha);
        }
        assertThat(vistas).hasSize(1000);
    }

    @Test
    void sempreTemLetraENumeroMesmoQuandoOSorteioNaoTrazNumero() {
        // Sorteio que só devolve índices de letras nas primeiras rodadas: o gerador repete até servir.
        java.security.SecureRandom viciado = new java.security.SecureRandom() {
            private static final long serialVersionUID = 1L;
            private int chamadas;

            @Override
            public int nextInt(int limite) {
                // 12 primeiras chamadas: sempre a letra "A"; depois, sorteio normal.
                return chamadas++ < GeradorDeSenhaTemporaria.TAMANHO ? 0 : super.nextInt(limite);
            }
        };
        String senha = new GeradorDeSenhaTemporaria(viciado).gerar();
        assertThat(RegrasDaSenha.serve(senha)).isTrue();
    }
}
