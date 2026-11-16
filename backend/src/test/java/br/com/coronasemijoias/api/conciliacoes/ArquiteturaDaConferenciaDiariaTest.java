package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tarefa 8.4: só o {@code ServicoDaConferenciaDiaria} escreve em {@code rodadas_conferencia} e {@code diferencas_conferencia}, e só
 * com INSERT. Nenhum serviço de negócio grava nelas, e nenhum código as altera ou apaga (as tabelas são imutáveis; o banco também recusa).
 */
class ArquiteturaDaConferenciaDiariaTest {

    private static final Path RAIZ_JAVA = achar("src/main/java");
    private static final List<String> TABELAS = List.of("rodadas_conferencia", "diferencas_conferencia");

    private static Path achar(String relativo) {
        for (String prefixo : List.of("", "backend/", "../")) {
            Path caminho = Path.of(prefixo + relativo);
            if (Files.exists(caminho)) {
                return caminho.toAbsolutePath();
            }
        }
        throw new IllegalStateException("Não achei " + relativo);
    }

    private static List<String> quemFaz(String regex, String escritorPermitido) throws IOException {
        Pattern padrao = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        List<String> culpados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ_JAVA)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                if (arquivo.getFileName().toString().equals(escritorPermitido + ".java")) {
                    continue;
                }
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8).replaceAll("\"\\s*\\+\\s*\"", "");
                if (padrao.matcher(fonte).find()) {
                    culpados.add(RAIZ_JAVA.relativize(arquivo).toString());
                }
            }
        }
        return culpados;
    }

    private static String escreve(String verbo, String tabela) {
        return verbo + "\\s+(INTO\\s+|FROM\\s+)?(corona\\.)?" + tabela + "\\b";
    }

    @Test
    void soOServicoDaConferenciaDiariaGravaAsTabelasDaRodada() throws IOException {
        for (String tabela : TABELAS) {
            assertThat(quemFaz(escreve("INSERT", tabela), "ServicoDaConferenciaDiaria")).as("INSERT em " + tabela + " fora do serviço")
                    .isEmpty();
        }
        // E ele grava mesmo (o teste não passa por falta de padrão).
        assertThat(quemFaz(escreve("INSERT", "rodadas_conferencia"), "")).containsExactly(
                Path.of("br/com/coronasemijoias/api/conciliacoes/ServicoDaConferenciaDiaria.java").toString());
    }

    @Test
    void nadaAlteraNemApagaAsTabelasDaRodada() throws IOException {
        for (String tabela : TABELAS) {
            for (String verbo : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(quemFaz(escreve(verbo, tabela), "")).as(verbo + " em " + tabela).isEmpty();
            }
        }
        assertThat(quemFaz("TRUNCATE\\s+(TABLE\\s+)?(corona\\.)?(rodadas|diferencas)_conferencia", "")).isEmpty();
    }
}
