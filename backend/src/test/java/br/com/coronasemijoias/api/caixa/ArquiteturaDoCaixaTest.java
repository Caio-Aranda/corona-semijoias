package br.com.coronasemijoias.api.caixa;

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
 * ADR-0012, itens 7 e 11 (tarefa 7.6): cada tabela do dinheiro do vendedor tem um escritor só. Só o {@code ServicoDeCaixa}
 * grava em {@code lancamentos_caixa}; só o {@code ServicoDeRecebimentosDoVendedor} grava em {@code recebimentos_vendedor}; só o
 * {@code ServicoDeContasAReceber} grava em {@code contas_receber} e {@code baixas_receber}. Nenhum código faz UPDATE ou DELETE
 * em {@code lancamentos_caixa} ou {@code recebimentos_vendedor} (imutáveis; a correção é estorno).
 */
class ArquiteturaDoCaixaTest {

    private static final Path RAIZ_JAVA = achar("src/main/java");

    private static Path achar(String relativo) {
        for (String prefixo : List.of("", "backend/", "../")) {
            Path caminho = Path.of(prefixo + relativo);
            if (Files.exists(caminho)) {
                return caminho.toAbsolutePath();
            }
        }
        throw new IllegalStateException("Não achei " + relativo);
    }

    private static List<String> quemFaz(String verbo, String tabela, String escritorPermitido) throws IOException {
        Pattern padrao = Pattern.compile(verbo + "\\s+(INTO\\s+|FROM\\s+)?(corona\\.)?" + tabela + "\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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

    @Test
    void soOServicoDeCaixaGravaEmLancamentosCaixa() throws IOException {
        assertThat(quemFaz("INSERT", "lancamentos_caixa", "ServicoDeCaixa")).as("INSERT fora do ServicoDeCaixa").isEmpty();
        assertThat(Files.exists(RAIZ_JAVA.resolve("br/com/coronasemijoias/api/caixa/ServicoDeCaixa.java"))).isTrue();
    }

    @Test
    void soOServicoDeRecebimentosGravaEmRecebimentosVendedor() throws IOException {
        assertThat(quemFaz("INSERT", "recebimentos_vendedor", "ServicoDeRecebimentosDoVendedor"))
                .as("INSERT fora do ServicoDeRecebimentosDoVendedor").isEmpty();
    }

    @Test
    void soOServicoDeContasGravaContasEBaixas() throws IOException {
        assertThat(quemFaz("INSERT", "contas_receber", "ServicoDeContasAReceber")).as("INSERT em contas_receber fora do serviço").isEmpty();
        assertThat(quemFaz("INSERT", "baixas_receber", "ServicoDeContasAReceber")).as("INSERT em baixas_receber fora do serviço").isEmpty();
    }

    @Test
    void nadaAlteraNemApagaCaixaERecebimentos() throws IOException {
        for (String tabela : List.of("lancamentos_caixa", "recebimentos_vendedor")) {
            assertThat(quemFaz("UPDATE", tabela, "")).as("UPDATE em " + tabela).isEmpty();
            assertThat(quemFaz("DELETE", tabela, "")).as("DELETE em " + tabela).isEmpty();
        }
    }
}
