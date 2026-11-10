package br.com.coronasemijoias.api.produtos;

/**
 * Dígito verificador do EAN-13 (ADR-0007): só se confere quando o tipo do código é EAN13 e ele tem
 * 13 dígitos. O código continua sendo texto (os zeros à esquerda não se perdem).
 */
public final class Ean13 {

    private Ean13() {
    }

    public static boolean temTrezeDigitos(String codigo) {
        if (codigo == null || codigo.length() != 13) {
            return false;
        }
        for (int i = 0; i < 13; i++) {
            char c = codigo.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** O 13º dígito confere com os 12 primeiros (pesos 1 e 3, alternados). */
    public static boolean digitoConfere(String codigo) {
        if (!temTrezeDigitos(codigo)) {
            return false;
        }
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            int digito = codigo.charAt(i) - '0';
            soma += (i % 2 == 0) ? digito : digito * 3;
        }
        int esperado = (10 - soma % 10) % 10;
        return esperado == codigo.charAt(12) - '0';
    }
}
