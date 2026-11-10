package br.com.coronasemijoias.api.cadastros;

/**
 * CPF e CNPJ: normalização (só os caracteres do número, sem pontos, traço e barra) e dígitos
 * verificadores. O servidor é quem valida; a tela só ajuda.
 *
 * <p>O CNPJ aceita letras nas 12 primeiras posições (CNPJ alfanumérico, a partir de julho de 2026);
 * os dois últimos caracteres são sempre dígitos. O cálculo é o mesmo do CNPJ numérico, com o valor de
 * cada caractere igual ao código ASCII menos 48, então os CNPJ antigos continuam valendo.
 */
public final class Documentos {

    private static final int[] PESOS_DO_CNPJ = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Documentos() {
    }

    /** O CPF só com os dígitos, ou {@code null} se vier vazio. Não confere os dígitos verificadores. */
    public static String normalizarCpf(String texto) {
        if (texto == null) {
            return null;
        }
        String digitos = texto.replaceAll("[^0-9]", "");
        return digitos.isEmpty() ? null : digitos;
    }

    /** O CNPJ em maiúsculas e sem pontos, barra e traço, ou {@code null} se vier vazio. */
    public static String normalizarCnpj(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.toUpperCase(java.util.Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        return limpo.isEmpty() ? null : limpo;
    }

    /** CPF normalizado: 11 dígitos, não todos iguais, com os dois dígitos verificadores certos. */
    public static boolean cpfValido(String cpf) {
        if (cpf == null || !cpf.matches("[0-9]{11}") || cpf.chars().distinct().count() == 1) {
            return false;
        }
        return digitoDoCpf(cpf, 9) == cpf.charAt(9) - '0' && digitoDoCpf(cpf, 10) == cpf.charAt(10) - '0';
    }

    private static int digitoDoCpf(String cpf, int quantos) {
        int soma = 0;
        for (int i = 0; i < quantos; i++) {
            soma += (cpf.charAt(i) - '0') * (quantos + 1 - i);
        }
        int resto = soma * 10 % 11;
        return resto == 10 ? 0 : resto;
    }

    /** CNPJ normalizado: 12 letras ou números e 2 dígitos verificadores certos; não todos iguais. */
    public static boolean cnpjValido(String cnpj) {
        if (cnpj == null || !cnpj.matches("[0-9A-Z]{12}[0-9]{2}") || cnpj.chars().distinct().count() == 1) {
            return false;
        }
        return digitoDoCnpj(cnpj, 12) == cnpj.charAt(12) - '0' && digitoDoCnpj(cnpj, 13) == cnpj.charAt(13) - '0';
    }

    private static int digitoDoCnpj(String cnpj, int quantos) {
        int soma = 0;
        int primeiroPeso = PESOS_DO_CNPJ.length - quantos;
        for (int i = 0; i < quantos; i++) {
            soma += (cnpj.charAt(i) - '0') * PESOS_DO_CNPJ[primeiroPeso + i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
