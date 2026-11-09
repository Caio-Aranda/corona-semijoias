package br.com.coronasemijoias.api.usuarios;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Gera a senha temporária (ADR-0004, item 6): aleatória ({@link SecureRandom}), sem caracteres que se
 * confundem ao ser lida ou copiada ({@code 0 O o 1 l I}) e sempre dentro das {@link RegrasDaSenha}.
 * 12 caracteres de um alfabeto de 56: cerca de 70 bits.
 */
@Component
public class GeradorDeSenhaTemporaria {

    public static final int TAMANHO = 12;

    /** Maiúsculas (sem I e O), minúsculas (sem l e o) e números (sem 0 e 1). */
    static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final SecureRandom sorteio;

    public GeradorDeSenhaTemporaria() {
        this(new SecureRandom());
    }

    GeradorDeSenhaTemporaria(SecureRandom sorteio) {
        this.sorteio = sorteio;
    }

    public String gerar() {
        while (true) {
            char[] caracteres = new char[TAMANHO];
            for (int i = 0; i < TAMANHO; i++) {
                // nextInt(limite) é uniforme: sem viés por causa do resto da divisão.
                caracteres[i] = ALFABETO.charAt(sorteio.nextInt(ALFABETO.length()));
            }
            String senha = new String(caracteres);
            if (RegrasDaSenha.serve(senha)) {
                return senha;
            }
        }
    }
}
