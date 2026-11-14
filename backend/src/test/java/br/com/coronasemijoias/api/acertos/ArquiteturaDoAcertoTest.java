package br.com.coronasemijoias.api.acertos;

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
 * ADR-0012, item 13 (tarefa 7.4): só o {@code ServicoDeAcertos} liga e desliga a maleta ao acerto (muda
 * {@code remessas.acerto_id} e {@code status_antes_do_acerto}) e escreve em {@code acertos} e {@code leituras_acerto}. Nenhum
 * outro código Java faz isso.
 */
class ArquiteturaDoAcertoTest {

    private static final Path RAIZ_JAVA = achar("src/main/java");

    private static final Pattern LIGA_A_MALETA = Pattern.compile(
            "UPDATE\\s+(corona\\.)?remessas\\b[^;]*?\\b(acerto_id|status_antes_do_acerto|status\\s*=\\s*'EM_ACERTO')",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ESCREVE_NO_ACERTO = Pattern.compile(
            "(UPDATE\\s+(corona\\.)?(acertos|leituras_acerto)\\b|INSERT\\s+INTO\\s+(corona\\.)?(acertos|leituras_acerto)\\b)",
            Pattern.CASE_INSENSITIVE);

    private static Path achar(String relativo) {
        for (String prefixo : List.of("", "backend/", "../")) {
            Path caminho = Path.of(prefixo + relativo);
            if (Files.exists(caminho)) {
                return caminho.toAbsolutePath();
            }
        }
        throw new IllegalStateException("Não achei " + relativo);
    }

    private List<String> culpados(Pattern padrao) throws IOException {
        List<String> culpados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ_JAVA)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                if (arquivo.getFileName().toString().equals("ServicoDeAcertos.java")) {
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

    @Test
    void soOServicoDeAcertosLigaEDesligaAMaleta() throws IOException {
        assertThat(culpados(LIGA_A_MALETA)).as("Código que liga ou desliga maleta do acerto fora do ServicoDeAcertos").isEmpty();
    }

    @Test
    void soOServicoDeAcertosEscreveNasTabelasDoAcerto() throws IOException {
        assertThat(culpados(ESCREVE_NO_ACERTO)).as("Código que escreve em acertos ou leituras_acerto fora do ServicoDeAcertos").isEmpty();
    }
}
