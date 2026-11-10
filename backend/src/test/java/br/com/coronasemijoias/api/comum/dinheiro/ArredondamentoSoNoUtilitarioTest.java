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
 * ADR-0002 (R6) e ADR-0006 (item 9): o arredondamento só existe em {@link Dinheiro} (dinheiro em Real)
 * e em {@link ConversaoDeMoeda} (Real para Dólar e Guarani, só para a tela). Este teste varre o código
 * de produção e reprova qualquer uso de {@code RoundingMode} fora dessas duas classes.
 */
class ArredondamentoSoNoUtilitarioTest {

    private static final Path CODIGO = Path.of("src", "main", "java");
    private static final Path PASTA_DO_DINHEIRO =
            CODIGO.resolve(Path.of("br", "com", "coronasemijoias", "api", "comum", "dinheiro"));
    private static final List<Path> PERMITIDOS = List.of(
            PASTA_DO_DINHEIRO.resolve("Dinheiro.java"),
            PASTA_DO_DINHEIRO.resolve("ConversaoDeMoeda.java"));

    @Test
    void roundingModeSoApareceNosUtilitariosDeDinheiro() throws IOException {
        PERMITIDOS.forEach(permitido -> assertThat(permitido).exists());
        List<Path> violacoes;
        try (Stream<Path> arquivos = Files.walk(CODIGO)) {
            violacoes = arquivos
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> PERMITIDOS.stream().noneMatch(permitido -> permitido.normalize().equals(p.normalize())))
                    .filter(ArredondamentoSoNoUtilitarioTest::usaRoundingMode)
                    .toList();
        }
        assertThat(violacoes)
                .as("Arredondamento fora de Dinheiro e ConversaoDeMoeda: use Dinheiro.arredondar (ADR-0002)")
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
