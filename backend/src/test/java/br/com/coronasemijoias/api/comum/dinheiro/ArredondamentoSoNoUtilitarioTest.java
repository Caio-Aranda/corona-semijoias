package br.com.coronasemijoias.api.comum.dinheiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0002 (R6): o utilitário de dinheiro é o único lugar com arredondamento. Este teste varre o
 * código de produção e reprova qualquer uso de {@code RoundingMode} fora de {@link Dinheiro}.
 */
class ArredondamentoSoNoUtilitarioTest {

    private static final Path CODIGO = Path.of("src", "main", "java");
    private static final Path UTILITARIO =
            CODIGO.resolve(Path.of("br", "com", "coronasemijoias", "api", "comum", "dinheiro", "Dinheiro.java"));

    @Test
    void roundingModeSoApareceNoUtilitarioDeDinheiro() throws IOException {
        assertThat(UTILITARIO).exists();
        List<Path> violacoes;
        try (Stream<Path> arquivos = Files.walk(CODIGO)) {
            violacoes = arquivos
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.normalize().equals(UTILITARIO.normalize()))
                    .filter(ArredondamentoSoNoUtilitarioTest::usaRoundingMode)
                    .toList();
        }
        assertThat(violacoes)
                .as("Arredondamento fora do utilitário de dinheiro: use Dinheiro.arredondar (ADR-0002)")
                .isEmpty();
    }

    private static boolean usaRoundingMode(Path arquivo) {
        try {
            return Files.readString(arquivo).contains("RoundingMode");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
