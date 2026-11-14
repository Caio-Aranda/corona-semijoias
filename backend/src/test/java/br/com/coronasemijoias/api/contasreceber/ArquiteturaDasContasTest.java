package br.com.coronasemijoias.api.contasreceber;

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
 * ADR-0012, item 7 (tarefa 7.3): só o {@code ServicoDeContasAReceber} muda os atalhos da parcela (valor_pago,
 * valor_cancelado e status). Nenhum outro código Java, nenhuma migração depois da V041, nenhum INSERT que escolha o status.
 */
class ArquiteturaDasContasTest {

    private static final Path RAIZ_JAVA = achar("src/main/java");
    private static final Path RAIZ_SQL = achar("src/main/resources/db/migration");

    // UPDATE ... corona.contas_receber ... SET (em qualquer lugar do comando, mesmo em várias linhas)
    private static final Pattern ALTERA_CONTA = Pattern.compile(
            "UPDATE\\s+(corona\\.)?contas_receber\\b[^;]*?\\bSET\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static Path achar(String relativo) {
        for (String prefixo : List.of("", "backend/", "../")) {
            Path caminho = Path.of(prefixo + relativo);
            if (Files.exists(caminho)) {
                return caminho.toAbsolutePath();
            }
        }
        throw new IllegalStateException("Não achei " + relativo);
    }

    @Test
    void soOServicoDeContasAReceberFazUpdateEmContasReceber() throws IOException {
        List<String> culpados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ_JAVA)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                if (arquivo.getFileName().toString().equals("ServicoDeContasAReceber.java")) {
                    continue;
                }
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8).replaceAll("\"\\s*\\+\\s*\"", "");
                if (ALTERA_CONTA.matcher(fonte).find()) {
                    culpados.add(RAIZ_JAVA.relativize(arquivo).toString());
                }
            }
        }
        assertThat(culpados).as("Código que faz UPDATE em contas_receber fora do ServicoDeContasAReceber").isEmpty();
    }

    @Test
    void oServicoTemUmSoUpdateEEleEDosAtalhos() throws IOException {
        String fonte = Files.readString(RAIZ_JAVA.resolve("br/com/coronasemijoias/api/contasreceber/ServicoDeContasAReceber.java"),
                StandardCharsets.UTF_8).replaceAll("\"\\s*\\+\\s*\"", "");
        var achados = ALTERA_CONTA.matcher(fonte).results().toList();
        assertThat(achados).hasSize(1);
        // O comando inteiro, até o fim do texto em que ele está escrito.
        String comando = fonte.substring(achados.get(0).start(), fonte.indexOf('"', achados.get(0).end()));
        assertThat(comando).contains("valor_pago").contains("valor_cancelado").contains("status")
                .doesNotContain("valor = ").doesNotContain("vencimento").doesNotContain("cliente_id").doesNotContain("venda_id");
    }

    @Test
    void nenhumaMigracaoAlteraContasReceberPorFora() throws IOException {
        List<String> culpados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ_SQL)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(arquivo, StandardCharsets.UTF_8);
                if (ALTERA_CONTA.matcher(sql).find()) {
                    culpados.add(arquivo.getFileName().toString());
                }
            }
        }
        assertThat(culpados).as("Migração que faz UPDATE em contas_receber").isEmpty();
    }

    @Test
    void nenhumCodigoEscolheOStatusNoInsertDaParcela() throws IOException {
        Pattern insereComStatus = Pattern.compile(
                "INSERT\\s+INTO\\s+(corona\\.)?contas_receber\\s*\\([^)]*\\bstatus\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        List<String> culpados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ_JAVA)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8).replaceAll("\"\\s*\\+\\s*\"", "");
                if (insereComStatus.matcher(fonte).find()) {
                    culpados.add(RAIZ_JAVA.relativize(arquivo).toString());
                }
            }
        }
        assertThat(culpados).as("INSERT em contas_receber escolhendo o status").isEmpty();
    }
}
