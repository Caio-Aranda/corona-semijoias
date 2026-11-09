package br.com.coronasemijoias.api.aplicativo;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versão do aplicativo com três números (maior.menor.correção), comparada número a número. */
public record VersaoDoApp(int maior, int menor, int correcao) implements Comparable<VersaoDoApp> {

    private static final Pattern FORMATO = Pattern.compile("^(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$");

    /** Vazio quando o texto não tem exatamente três números separados por ponto. */
    public static Optional<VersaoDoApp> ler(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        Matcher m = FORMATO.matcher(texto.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new VersaoDoApp(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    @Override
    public int compareTo(VersaoDoApp outra) {
        int c = Integer.compare(maior, outra.maior);
        if (c == 0) {
            c = Integer.compare(menor, outra.menor);
        }
        return c != 0 ? c : Integer.compare(correcao, outra.correcao);
    }

    @Override
    public String toString() {
        return maior + "." + menor + "." + correcao;
    }
}
