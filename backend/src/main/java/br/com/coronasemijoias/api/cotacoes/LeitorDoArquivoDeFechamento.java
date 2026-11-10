package br.com.coronasemijoias.api.cotacoes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Leitura estrita do arquivo diário de fechamento do BCB (ADR-0006, item 2). O arquivo não tem
 * contrato com versão, então qualquer coisa fora do esperado é recusada, nunca adivinhada.
 *
 * <p>Para o dia pedido D, só é aceito se: toda linha não vazia tem exatamente 8 colunas e a data D;
 * existe exatamente uma linha 220/A/USD e exatamente uma 450/A/PYG; nessas duas, os quatro números
 * seguem {@code ^\d{1,10},\d{1,8}$} e são maiores que zero; compra ≤ venda nas taxas e nas
 * paridades; as duas paridades do USD são 1.
 */
public final class LeitorDoArquivoDeFechamento {

    private static final int COLUNAS = 8;
    private static final Pattern NUMERO = Pattern.compile("^\\d{1,10},\\d{1,8}$");
    private static final DateTimeFormatter DATA_DO_ARQUIVO = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    private LeitorDoArquivoDeFechamento() {
    }

    /**
     * @param dia   o dia pedido (o do endereço do arquivo)
     * @param texto o arquivo inteiro, linhas separadas por CRLF ou LF
     * @throws ArquivoForaDaRegraException se qualquer regra do item 2 do ADR for quebrada
     */
    public static TaxasDoDia ler(LocalDate dia, String texto) {
        String dataEsperada = DATA_DO_ARQUIVO.format(dia);
        List<String[]> usd = new ArrayList<>();
        List<String[]> pyg = new ArrayList<>();
        int numeroDaLinha = 0;
        for (String linha : texto.split("\r?\n", -1)) {
            numeroDaLinha++;
            if (linha.isBlank()) {
                continue;
            }
            String[] colunas = linha.split(";", -1);
            if (colunas.length != COLUNAS) {
                throw new ArquivoForaDaRegraException("A linha " + numeroDaLinha + " tem " + colunas.length
                        + " colunas; o esperado são " + COLUNAS + " colunas.");
            }
            if (!colunas[0].equals(dataEsperada)) {
                throw new ArquivoForaDaRegraException("A linha " + numeroDaLinha + " tem a data " + colunas[0]
                        + "; o esperado é " + dataEsperada + ".");
            }
            if (ehDaMoeda(colunas, "220", "USD")) {
                usd.add(colunas);
            } else if (ehDaMoeda(colunas, "450", "PYG")) {
                pyg.add(colunas);
            }
        }
        String[] linhaUsd = unica(usd, "USD");
        String[] linhaPyg = unica(pyg, "PYG");

        BigDecimal usdCompra = numero(linhaUsd, 4, "USD");
        BigDecimal usdVenda = numero(linhaUsd, 5, "USD");
        BigDecimal usdParidadeCompra = numero(linhaUsd, 6, "USD");
        BigDecimal usdParidadeVenda = numero(linhaUsd, 7, "USD");
        BigDecimal pygCompra = numero(linhaPyg, 4, "PYG");
        BigDecimal pygVenda = numero(linhaPyg, 5, "PYG");
        BigDecimal pygParidadeCompra = numero(linhaPyg, 6, "PYG");
        BigDecimal pygParidadeVenda = numero(linhaPyg, 7, "PYG");

        compraAteVenda(usdCompra, usdVenda, "a taxa do USD");
        compraAteVenda(usdParidadeCompra, usdParidadeVenda, "a paridade do USD");
        compraAteVenda(pygCompra, pygVenda, "a taxa do PYG");
        compraAteVenda(pygParidadeCompra, pygParidadeVenda, "a paridade do PYG");
        if (usdParidadeCompra.compareTo(BigDecimal.ONE) != 0 || usdParidadeVenda.compareTo(BigDecimal.ONE) != 0) {
            throw new ArquivoForaDaRegraException("As duas paridades do USD deveriam ser 1, vieram "
                    + usdParidadeCompra.toPlainString() + " e " + usdParidadeVenda.toPlainString() + ".");
        }
        return new TaxasDoDia(usdCompra, usdVenda, usdParidadeCompra, usdParidadeVenda,
                pygCompra, pygVenda, pygParidadeCompra, pygParidadeVenda);
    }

    private static boolean ehDaMoeda(String[] colunas, String codigo, String sigla) {
        return colunas[1].equals(codigo) && colunas[2].equals("A") && colunas[3].equals(sigla);
    }

    private static String[] unica(List<String[]> linhas, String sigla) {
        if (linhas.size() != 1) {
            throw new ArquivoForaDaRegraException("Deveria haver exatamente uma linha de " + sigla
                    + " (código " + (sigla.equals("USD") ? "220" : "450") + ", tipo A); há " + linhas.size() + ".");
        }
        return linhas.get(0);
    }

    private static BigDecimal numero(String[] linha, int coluna, String sigla) {
        String texto = linha[coluna];
        if (!NUMERO.matcher(texto).matches()) {
            throw new ArquivoForaDaRegraException("O número \"" + texto + "\" do " + sigla
                    + " está fora do padrão (vírgula decimal, sem milhar nem sinal, até 8 casas).");
        }
        BigDecimal valor = new BigDecimal(texto.replace(',', '.'));
        if (valor.signum() <= 0) {
            throw new ArquivoForaDaRegraException("O número \"" + texto + "\" do " + sigla
                    + " precisa ser maior que zero.");
        }
        return valor.setScale(TaxasDoDia.ESCALA);
    }

    private static void compraAteVenda(BigDecimal compra, BigDecimal venda, String oQue) {
        if (compra.compareTo(venda) > 0) {
            throw new ArquivoForaDaRegraException("Em " + oQue + ", a compra (" + compra.toPlainString()
                    + ") passa da venda (" + venda.toPlainString() + ").");
        }
    }
}
