package br.com.coronasemijoias.api.contaspagar;

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
 * ADR-0012, itens 11, 12 e 17 (tarefa 7.7): cada tabela nova tem um escritor só. Só o {@code ServicoDeContasAPagar} grava em
 * {@code contas_pagar} e {@code baixas_pagar}; só o {@code ServicoDeCategoriasDoCaixa} grava em {@code categorias_caixa}; só o
 * {@code ServicoDeSemanas} grava em {@code fechamentos_semana}. Nenhum código faz UPDATE ou DELETE em {@code baixas_pagar} nem em
 * {@code fechamentos_semana} (imutáveis; a correção é estorno), nem DELETE em tabela nenhuma delas, e nenhum código reabre semana.
 */
class ArquiteturaDasContasAPagarTest {

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
    void soOServicoDeContasAPagarGravaContasEBaixas() throws IOException {
        for (String tabela : List.of("contas_pagar", "baixas_pagar")) {
            assertThat(quemFaz(escreve("INSERT", tabela), "ServicoDeContasAPagar")).as("INSERT em " + tabela + " fora do serviço").isEmpty();
        }
        assertThat(quemFaz(escreve("UPDATE", "contas_pagar"), "ServicoDeContasAPagar")).as("UPDATE em contas_pagar fora do serviço").isEmpty();
    }

    @Test
    void soOServicoDeCategoriasGravaCategoriasDoCaixa() throws IOException {
        assertThat(quemFaz(escreve("INSERT", "categorias_caixa"), "ServicoDeCategoriasDoCaixa")).as("INSERT em categorias_caixa").isEmpty();
        assertThat(quemFaz(escreve("UPDATE", "categorias_caixa"), "ServicoDeCategoriasDoCaixa")).as("UPDATE em categorias_caixa").isEmpty();
    }

    @Test
    void soOServicoDeSemanasGravaFechamentos() throws IOException {
        assertThat(quemFaz(escreve("INSERT", "fechamentos_semana"), "ServicoDeSemanas")).as("INSERT em fechamentos_semana").isEmpty();
    }

    @Test
    void nadaAlteraNemApagaBaixasNemFechamentosENadaApagaContasNemCategorias() throws IOException {
        for (String tabela : List.of("baixas_pagar", "fechamentos_semana")) {
            assertThat(quemFaz(escreve("UPDATE", tabela), "")).as("UPDATE em " + tabela).isEmpty();
        }
        for (String tabela : List.of("baixas_pagar", "fechamentos_semana", "contas_pagar", "categorias_caixa")) {
            assertThat(quemFaz(escreve("DELETE", tabela), "")).as("DELETE em " + tabela).isEmpty();
        }
    }

    @Test
    void nenhumCodigoReabreSemana() throws IOException {
        // A semana só vai para FECHADA: nenhum comando manda a situação voltar para ABERTA por SQL.
        assertThat(quemFaz("UPDATE\\s+(corona\\.)?semanas\\s+SET[^;\"]*situacao\\s*=\\s*'ABERTA'", "")).as("UPDATE reabrindo semana").isEmpty();
    }

    @Test
    void oUpdateDasContasMudaSoOsAtalhos() throws IOException {
        String fonte = Files.readString(RAIZ_JAVA.resolve("br/com/coronasemijoias/api/contaspagar/ServicoDeContasAPagar.java"),
                StandardCharsets.UTF_8).replaceAll("\"\\s*\\+\\s*\"", "");
        var achados = Pattern.compile("UPDATE\\s+corona\\.contas_pagar\\s+SET[^\"]*", Pattern.CASE_INSENSITIVE).matcher(fonte).results().toList();
        assertThat(achados).hasSize(1);
        assertThat(achados.get(0).group()).contains("valor_pago").contains("valor_cancelado").contains("status").doesNotContain("valor = ")
                .doesNotContain("vencimento").doesNotContain("compra_id");
    }
}
