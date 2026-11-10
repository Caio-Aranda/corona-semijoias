package br.com.coronasemijoias.api.estoque;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ADR-0008: só o serviço de estoque muda produtos.estoque_atual; o vendedor nunca recebe o estoque central. */
class ArquiteturaDoEstoqueTest {

    private static final Path RAIZ_JAVA = achar("src/main/java");
    private static final Path RAIZ_SQL = achar("src/main/resources/db/migration");

    // UPDATE ... corona.produtos ... SET ... estoque_atual (em qualquer lugar do comando, mesmo em várias linhas)
    private static final Pattern ALTERA_SALDO = Pattern.compile(
            "UPDATE\\s+(corona\\.)?produtos\\b[^;]*?\\bSET\\b[^;]*?estoque_atual", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
    void soOServicoDeEstoqueAlteraOSaldoDoProduto() throws IOException {
        List<String> culpados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ_JAVA)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                if (arquivo.getFileName().toString().equals("ServicoDeEstoque.java")) {
                    continue;
                }
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8).replaceAll("\"\\s*\\+\\s*\"", "");
                if (ALTERA_SALDO.matcher(fonte).find()) {
                    culpados.add(RAIZ_JAVA.relativize(arquivo).toString());
                }
            }
        }
        assertThat(culpados).as("Código que faz UPDATE em produtos.estoque_atual fora do ServicoDeEstoque").isEmpty();
    }

    @Test
    void nenhumaMigracaoPosteriorAlteraOSaldoPorFora() throws IOException {
        List<String> culpados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ_SQL)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(arquivo, StandardCharsets.UTF_8);
                if (ALTERA_SALDO.matcher(sql).find()) {
                    culpados.add(arquivo.getFileName().toString());
                }
            }
        }
        assertThat(culpados).as("Migração que faz UPDATE em produtos.estoque_atual").isEmpty();
    }

    @Test
    void oVendedorNaoRecebeEstoqueNemMovimentos() {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        var produtos = registro.sincronizaveis().stream().filter(t -> t.nome().equals("produtos")).findFirst().orElseThrow();
        assertThat(produtos.colunas()).doesNotContain("estoque_atual", "estoque_minimo");
        assertThat(registro.sincronizaveis()).extracting(t -> t.nome()).doesNotContain("movimentacoes_estoque");
        assertThat(registro.soDoServidor()).extracting(t -> t.nome()).contains("movimentacoes_estoque");
    }
}
