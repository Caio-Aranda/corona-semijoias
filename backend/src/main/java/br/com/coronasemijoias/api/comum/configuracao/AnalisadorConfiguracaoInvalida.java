package br.com.coronasemijoias.api.comum.configuracao;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Mostra, ao subir, a explicação em português de uma {@link ConfiguracaoInvalidaException}. */
public class AnalisadorConfiguracaoInvalida extends AbstractFailureAnalyzer<ConfiguracaoInvalidaException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfiguracaoInvalidaException causa) {
        return new FailureAnalysis(causa.descricao(), causa.acao(), causa);
    }
}
