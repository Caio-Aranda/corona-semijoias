package br.com.coronasemijoias.api.relatorios;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resposta de todo relatório de cadastro (§12): as linhas da página, o total do que foi filtrado, o
 * rodapé e, quando a pessoa clicou num total, qual (recorte).
 *
 * @param itens   as linhas desta página
 * @param total   quantas linhas o relatório tem (com o recorte, se houver), em todas as páginas
 * @param totais  o rodapé: sempre calculado sobre o filtro inteiro, sem o recorte
 * @param recorte a chave do total que está sendo detalhado, ou vazio
 */
public record RelatorioDeCadastro<L>(List<L> itens, long total, int pagina, int tamanho, List<Total> totais,
        String recorte) {

    /**
     * Um número do rodapé. {@code quantidade} são as linhas que o formam (o detalhamento mostra essas
     * linhas); {@code valorEmReais}, quando existe, é a soma de um valor dessas linhas, em Real; {@code pecas},
     * quando existe, é a soma de peças (estoque e compras); {@code valorEmDolar} e {@code valorEmGuarani}, quando existem
     * (relatórios da maleta), somam os valores já convertidos linha a linha pela cotação do dia de cada uma (§12).
     */
    public record Total(String chave, String rotulo, long quantidade, BigDecimal valorEmReais, Long pecas,
            BigDecimal valorEmDolar, BigDecimal valorEmGuarani) {

        public Total(String chave, String rotulo, long quantidade, BigDecimal valorEmReais) {
            this(chave, rotulo, quantidade, valorEmReais, null, null, null);
        }

        public Total(String chave, String rotulo, long quantidade, BigDecimal valorEmReais, Long pecas) {
            this(chave, rotulo, quantidade, valorEmReais, pecas, null, null);
        }
    }
}
