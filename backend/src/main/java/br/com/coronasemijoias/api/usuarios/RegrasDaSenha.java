package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Regras da senha nova (ADR-0004, item 6), conferidas no servidor: no máximo 72 bytes (o BCrypt só lê
 * esses), pelo menos 8 caracteres, com letras e números. A regra "diferente da temporária" depende do
 * hash guardado e fica no serviço de troca de senha.
 *
 * <p>A ordem importa e é fixa: primeiro o tamanho em bytes, depois a força.
 */
public final class RegrasDaSenha {

    public static final int MINIMO_DE_CARACTERES = 8;

    private RegrasDaSenha() {
    }

    /** O primeiro problema da senha, ou vazio se ela serve. */
    public static Optional<CodigoErro> problema(String senha) {
        if (senha == null || senha.isEmpty()) {
            return Optional.of(CodigoErro.SENHA_FRACA);
        }
        if (senha.getBytes(StandardCharsets.UTF_8).length > ConferenciaDeSenha.LIMITE_DE_BYTES) {
            return Optional.of(CodigoErro.SENHA_LONGA_DEMAIS);
        }
        boolean curta = senha.codePointCount(0, senha.length()) < MINIMO_DE_CARACTERES;
        boolean temLetra = senha.codePoints().anyMatch(Character::isLetter);
        boolean temNumero = senha.codePoints().anyMatch(c -> c >= '0' && c <= '9');
        return curta || !temLetra || !temNumero ? Optional.of(CodigoErro.SENHA_FRACA) : Optional.empty();
    }

    public static boolean serve(String senha) {
        return problema(senha).isEmpty();
    }
}
