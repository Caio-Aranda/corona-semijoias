package br.com.coronasemijoias.api.comum.configuracao;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.util.PlaceholderResolutionException;

/**
 * Troca o erro técnico de "placeholder não resolvido" por uma explicação em português quando
 * falta uma variável do banco (em geral porque o arquivo .env não existe).
 */
public class AnalisadorVariavelAusente extends AbstractFailureAnalyzer<PlaceholderResolutionException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, PlaceholderResolutionException causa) {
        String variavel = causa.getPlaceholder();
        if (variavel == null || !variavel.startsWith("DB_")) {
            return null;
        }
        return new FailureAnalysis(montarDescricao(variavel), montarAcao(), causa);
    }

    static String montarDescricao(String variavel) {
        return "A API não conseguiu começar porque falta configurar o banco de dados: a variável "
                + variavel + " não foi encontrada.";
    }

    static String montarAcao() {
        return "Copie o arquivo .env.example para .env na pasta principal do projeto, troque os valores "
                + "\"troque-isto\" pelos reais e rode de novo. Confira também se o banco está ligado "
                + "(npm run db:up).";
    }
}
