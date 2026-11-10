package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Leitura estrita do arquivo de fechamento do BCB (ADR-0006, item 2). Arquivos de exemplo em
 * {@code src/test/resources/cotacoes}: o real de 06/11/2026 e um para cada regra quebrada. Sem rede.
 */
class LeitorDoArquivoDeFechamentoTest {

    static final LocalDate SEXTA = LocalDate.of(2026, 11, 6);

    static String arquivo(String nome) {
        try {
            return Files.readString(Path.of("src", "test", "resources", "cotacoes", nome), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private static void recusa(String nomeDoArquivo, String trechoDoMotivo) {
        recusaTexto(arquivo(nomeDoArquivo), trechoDoMotivo);
    }

    private static void recusaTexto(String texto, String trechoDoMotivo) {
        assertThatThrownBy(() -> LeitorDoArquivoDeFechamento.ler(SEXTA, texto))
                .isInstanceOf(ArquivoForaDaRegraException.class)
                .hasMessageContaining(trechoDoMotivo);
    }

    /** O arquivo real com a linha do USD (48) ou do PYG (72) trocada. */
    private static String comLinha(int numero, String nova) {
        String[] linhas = arquivo("20261106.csv").split("\r?\n");
        linhas[numero - 1] = nova;
        return String.join("\r\n", linhas) + "\r\n";
    }

    @Test
    void leOArquivoRealDeSeisDeNovembroDeDoisMilEVinteESeis() {
        TaxasDoDia taxas = LeitorDoArquivoDeFechamento.ler(SEXTA, arquivo("20261106.csv"));

        assertThat(taxas.usdCompra()).isEqualTo(d("5.50070000"));
        assertThat(taxas.usdVenda()).isEqualTo(d("5.50130000"));
        assertThat(taxas.usdParidadeCompra()).isEqualTo(d("1.00000000"));
        assertThat(taxas.usdParidadeVenda()).isEqualTo(d("1.00000000"));
        assertThat(taxas.pygCompra()).isEqualTo(d("0.00086560"));
        assertThat(taxas.pygVenda()).isEqualTo(d("0.00089500"));
        assertThat(taxas.pygParidadeCompra()).isEqualTo(d("6147.00000000"));
        assertThat(taxas.pygParidadeVenda()).isEqualTo(d("6355.00000000"));
    }

    @Test
    void ignoraLinhasVaziasEAceitaFimDeLinhaSemRetorno() {
        String semRetorno = arquivo("20261106.csv").replace("\r\n", "\n") + "\n\n";
        assertThat(LeitorDoArquivoDeFechamento.ler(SEXTA, semRetorno).usdVenda()).isEqualTo(d("5.50130000"));
    }

    @Test
    void recusaColunaAMais() {
        recusa("coluna-a-mais.csv", "8 colunas");
    }

    @Test
    void recusaColunaAMenos() {
        recusaTexto(comLinha(10, "06/11/2026;220;A;USD;5,50070000;5,50130000;1,00000000"), "8 colunas");
    }

    @Test
    void recusaDataTrocadaEmQualquerLinha() {
        recusa("data-trocada.csv", "data");
    }

    @Test
    void recusaArquivoDeOutroDiaInteiro() {
        assertThatThrownBy(() -> LeitorDoArquivoDeFechamento.ler(LocalDate.of(2026, 11, 9), arquivo("20261106.csv")))
                .isInstanceOf(ArquivoForaDaRegraException.class)
                .hasMessageContaining("data");
    }

    @Test
    void recusaPygAusente() {
        recusa("pyg-ausente.csv", "PYG");
    }

    @Test
    void recusaPygDuplicado() {
        recusa("pyg-duplicado.csv", "PYG");
    }

    @Test
    void recusaUsdAusenteEDuplicado() {
        recusaTexto(comLinha(48, "06/11/2026;221;A;USD;5,50070000;5,50130000;1,00000000;1,00000000"), "USD");
        recusaTexto(comLinha(10, "06/11/2026;220;A;USD;5,50070000;5,50130000;1,00000000;1,00000000"), "USD");
    }

    @Test
    void recusaArquivoVazio() {
        recusaTexto("", "USD");
        recusaTexto("\r\n\r\n", "USD");
    }

    @Test
    void recusaPontoDecimal() {
        recusa("ponto-decimal.csv", "número");
    }

    @Test
    void recusaSeparadorDeMilharSinalEEspaco() {
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;0,00086560;0,00089500;6.147,00000000;6355,00000000"), "número");
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;-0,00086560;0,00089500;6147,00000000;6355,00000000"), "número");
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG; 0,00086560;0,00089500;6147,00000000;6355,00000000"), "número");
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;0,00086560;0,00089500;6147;6355,00000000"), "número");
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;0,000865600;0,00089500;6147,00000000;6355,00000000"), "número");
    }

    @Test
    void recusaZero() {
        recusa("zero.csv", "maior que zero");
    }

    @Test
    void recusaZeroNoPygTambem() {
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;0,00086560;0,00089500;0,00000000;6355,00000000"), "maior que zero");
    }

    @Test
    void recusaCompraMaiorQueVenda() {
        recusa("compra-maior-que-venda.csv", "compra");
    }

    @Test
    void recusaCompraMaiorQueVendaNasParidadesEEmOutraMoeda() {
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;0,00086560;0,00089500;6355,00000000;6147,00000000"), "compra");
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;0,00089600;0,00089500;6147,00000000;6355,00000000"), "compra");
    }

    @Test
    void aceitaCompraIgualAVenda() {
        TaxasDoDia taxas = LeitorDoArquivoDeFechamento.ler(SEXTA,
                comLinha(48, "06/11/2026;220;A;USD;5,50130000;5,50130000;1,00000000;1,00000000"));
        assertThat(taxas.usdCompra()).isEqualTo(taxas.usdVenda());
    }

    @Test
    void recusaParidadeDoDolarDiferenteDeUm() {
        recusaTexto(comLinha(48, "06/11/2026;220;A;USD;5,50070000;5,50130000;1,00000000;1,01000000"), "paridade");
        recusaTexto(comLinha(48, "06/11/2026;220;A;USD;5,50070000;5,50130000;0,99000000;1,00000000"), "paridade");
    }

    @Test
    void numeroComDezDigitosInteirosCabeEComOnzeNao() {
        recusaTexto(comLinha(72, "06/11/2026;450;A;PYG;0,00086560;0,00089500;6147,00000000;12345678901,00000000"), "número");
    }
}
