package br.com.coronasemijoias.api.usuarios;

import java.util.Locale;

/** Regra única do login normalizado (ADR-0004, item 7): sem espaços nas pontas e em minúsculas. */
public final class Logins {

    private Logins() {
    }

    public static String normalizar(String login) {
        return login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Se o login já normalizado pode existir: não vazio, sem espaço no meio e sem caractere de
     * controle. O que não pode existir nem é procurado no banco (o login responde como senha errada).
     * As regras de formato para CRIAR usuário são da tarefa 10.
     */
    public static boolean podeExistir(String normalizado) {
        if (normalizado == null || normalizado.isEmpty()) {
            return false;
        }
        return normalizado.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }
}
