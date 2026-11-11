package br.com.coronasemijoias.api.testes;

import java.util.concurrent.ThreadLocalRandom;

/**
 * CPFs válidos e diferentes a cada chamada. O CPF é único entre os clientes (V030) e o banco dos testes é
 * compartilhado entre as classes, então um teste nunca pode usar um CPF fixo que outro também usa.
 */
public final class CpfsDeTeste {

    private CpfsDeTeste() {
    }

    /** Onze dígitos, com os dois dígitos verificadores certos (sem máscara). */
    public static String novo() {
        int[] n = new int[11];
        do {
            for (int i = 0; i < 9; i++) {
                n[i] = ThreadLocalRandom.current().nextInt(10);
            }
        } while (todosIguais(n));
        n[9] = digito(n, 9, 10);
        n[10] = digito(n, 10, 11);
        StringBuilder s = new StringBuilder();
        for (int d : n) {
            s.append(d);
        }
        return s.toString();
    }

    /** O mesmo CPF com a máscara (000.000.000-00). */
    public static String comMascara(String cpf) {
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);
    }

    private static boolean todosIguais(int[] n) {
        for (int i = 1; i < 9; i++) {
            if (n[i] != n[0]) {
                return false;
            }
        }
        return true;
    }

    private static int digito(int[] n, int quantos, int pesoInicial) {
        int soma = 0;
        for (int i = 0; i < quantos; i++) {
            soma += n[i] * (pesoInicial - i);
        }
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }
}
