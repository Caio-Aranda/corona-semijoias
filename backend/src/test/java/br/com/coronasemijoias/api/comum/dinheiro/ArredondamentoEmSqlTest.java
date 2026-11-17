package br.com.coronasemijoias.api.comum.dinheiro;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.remessas.SqlDaMaleta;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auditoria M4 (ADR-0006, item 9): parte da conversão de moeda dos relatórios é feita em SQL ({@code round(...)} do
 * PostgreSQL). Este teste (1) prova que o SQL dá o mesmo resultado da {@link ConversaoDeMoeda} em valores de borda
 * (meio exato inclusive) e (2) reprova {@code round(} em SQL novo fora dos arquivos já conhecidos.
 */
class ArredondamentoEmSqlTest extends TesteDeIntegracao {

    /** Arquivos que já convertem moeda em SQL (exceção registrada no ADR-0006). Qualquer outro reprova. */
    private static final List<String> COM_ROUND_EM_SQL = List.of("SqlDaMaleta.java", "ConsultasDeDevolucaoETroca.java",
            "ConsultasDeEstoqueECompras.java", "ConsultasDoResultado.java", "VendidoLiquido.java");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void oRoundDoSqlDaMaletaDaOMesmoResultadoDaConversaoEmJava() {
        // Taxas reais do item 14 do ADR-0006 e valores de borda (meio exato e quase meio).
        List<String[]> taxas = List.of(new String[] {"5.42120000", "0.00092960"}, new String[] {"5.46210000", "0.00088770"},
                new String[] {"5.50130000", "0.00089500"}, new String[] {"2.00000000", "0.00050000"},
                new String[] {"4.00000000", "0.00040000"});
        List<String> valores = List.of("0.01", "0.50", "1.00", "1.01", "1.25", "1.50", "2.50", "9.99", "100.00", "1234.56",
                "250.00", "999999.99", "12345678.90");
        for (String[] taxa : taxas) {
            BigDecimal usd = new BigDecimal(taxa[0]);
            BigDecimal pyg = new BigDecimal(taxa[1]);
            for (String v : valores) {
                BigDecimal reais = new BigDecimal(v);
                Map<String, Object> sql = jdbc.queryForMap("SELECT " + SqlDaMaleta.dolar("?::numeric") + " AS d, "
                        + SqlDaMaleta.guarani("?::numeric") + " AS g FROM (SELECT ?::numeric AS usd_venda, ?::numeric AS pyg_venda) ct",
                        reais, reais, usd, pyg);
                assertThat((BigDecimal) sql.get("d")).as("dólar de %s a %s", v, taxa[0])
                        .isEqualByComparingTo(ConversaoDeMoeda.paraDolar(reais, usd));
                assertThat((BigDecimal) sql.get("g")).as("guarani de %s a %s", v, taxa[1])
                        .isEqualByComparingTo(ConversaoDeMoeda.paraGuarani(reais, pyg));
            }
        }
    }

    @Test
    void roundEmSqlSoApareceNosArquivosJaConhecidos() throws IOException {
        List<String> novos;
        try (Stream<Path> arquivos = Files.walk(Path.of("src", "main", "java"))) {
            novos = arquivos.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> COM_ROUND_EM_SQL.stream().noneMatch(p.getFileName().toString()::equals))
                    .filter(p -> {
                        try {
                            return java.util.regex.Pattern.compile("(?<![.\\w])round\\(").matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).map(p -> p.getFileName().toString()).toList();
        }
        assertThat(novos).as("round() em SQL fora dos arquivos conhecidos: converta no Java (ConversaoDeMoeda) ou registre a exceção no ADR-0006")
                .isEmpty();
    }
}
