package br.com.coronasemijoias.api.comum.configuracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.PlaceholderResolutionException;

class AnalisadorVariavelAusenteTest {

    private final AnalisadorVariavelAusente analisador = new AnalisadorVariavelAusente();

    private PlaceholderResolutionException falta(String variavel) {
        StandardEnvironment ambiente = new StandardEnvironment();
        Throwable[] capturado = new Throwable[1];
        assertThatThrownBy(() -> ambiente.resolveRequiredPlaceholders("${" + variavel + "}"))
                .isInstanceOf(PlaceholderResolutionException.class)
                .satisfies(e -> capturado[0] = e);
        return (PlaceholderResolutionException) capturado[0];
    }

    @Test
    void explicaEmPortuguesQuandoFaltaVariavelDoBanco() {
        FailureAnalysis analise = analisador.analyze(falta("DB_APP_USER"));

        assertThat(analise).isNotNull();
        assertThat(analise.getDescription()).contains("DB_APP_USER", "banco de dados");
        assertThat(analise.getAction()).contains(".env.example", ".env", "npm run db:up");
    }

    @Test
    void ignoraPlaceholdersQueNaoSaoDoBanco() {
        assertThat(analisador.analyze(falta("OUTRA_COISA"))).isNull();
    }
}
