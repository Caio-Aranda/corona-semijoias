package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Teste de arquitetura (ADR-0006, item 15): a cotação de um lançamento é escolhida pelo MOMENTO ({@code vigente_desde}), nunca
 * pelo dia. Nenhum arquivo de produção fora do pacote {@code cotacoes} compara {@code data_cotacao} com um dia.
 */
class CotacaoSoPeloMomentoTest {

    private static final Path CODIGO = Path.of("src", "main", "java");
    private static final Path PACOTE_DAS_COTACOES =
            CODIGO.resolve(Path.of("br", "com", "coronasemijoias", "api", "cotacoes")).normalize();
    private static final Pattern COMPARA_PELO_DIA = Pattern.compile("data_cotacao\s*(<=|>=|<|>)");

    @Test
    void nenhumaConsultaForaDoPacoteDasCotacoesEscolhePeloDia() throws IOException {
        List<Path> violacoes;
        try (Stream<Path> arquivos = Files.walk(CODIGO)) {
            violacoes = arquivos
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.normalize().startsWith(PACOTE_DAS_COTACOES))
                    .filter(CotacaoSoPeloMomentoTest::comparaPeloDia)
                    .toList();
        }
        assertThat(violacoes)
                .as("Cotação escolhida pelo dia: use CotacaoVigente (vigente_desde <= momento) em vez de data_cotacao <= dia")
                .isEmpty();
    }

    private static boolean comparaPeloDia(Path arquivo) {
        try {
            return COMPARA_PELO_DIA.matcher(Files.readString(arquivo)).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
