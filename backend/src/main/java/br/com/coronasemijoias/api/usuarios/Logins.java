package br.com.coronasemijoias.api.usuarios;

import java.util.Locale;
import java.util.regex.Pattern;

/** Regra única do login normalizado (ADR-0004, item 7): sem espaços nas pontas e em minúsculas. */
public final class Logins {

    /**
     * Formato do login ao CRIAR usuário (já normalizado, ou seja, em minúsculas): 3 a 40 caracteres, só
     * letras sem acento, números e ponto, começando e terminando com letra ou número.
     */
    private static final Pattern FORMATO_DE_CRIACAO = Pattern.compile("^[a-z0-9][a-z0-9.]{1,38}[a-z0-9]$");

    private Logins() {
    }

    public static String normalizar(String login) {
        return login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
    }

    /** Se o login já normalizado serve para criar um usuário novo (mais estrito que {@link #podeExistir}). */
    public static boolean temFormatoParaCriar(String normalizado) {
        return normalizado != null && FORMATO_DE_CRIACAO.matcher(normalizado).matches();
    }

    /**
     * Se o login já normalizado pode existir: não vazio, sem espaço no meio e sem caractere de
     * controle. O que não pode existir nem é procurado no banco (o login responde como senha errada).
     * O formato para CRIAR usuário é mais estrito: {@link #temFormatoParaCriar}.
     */
    public static boolean podeExistir(String normalizado) {
        if (normalizado == null || normalizado.isEmpty()) {
            return false;
        }
        return normalizado.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }
}
