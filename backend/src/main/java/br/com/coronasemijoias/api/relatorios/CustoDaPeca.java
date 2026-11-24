package br.com.coronasemijoias.api.relatorios;

/**
 * O custo da peça numa data (ADR-0015, item 1; tarefa 8.6): UMA fórmula, usada pelo custo das peças vendidas, pelas faltas e
 * pelas avarias. Só fragmentos de SQL, como o {@link VendidoLiquido}: nenhuma tabela nova, nada gravado, nada convertido.
 *
 * <p>O custo unitário do produto no dia {@code d} (dia de Brasília) é o do <b>último custo de compra até {@code d}</b>: a compra
 * do mesmo dia vale (até a data, inclusive); duas compras no mesmo dia, vale a de {@code ordem} maior (a última registrada); a
 * compra desfeita (estornada) não conta, porque a compra desfeita não aconteceu. Sem compra válida até {@code d}, vale o
 * {@code preco_custo} do cadastro de hoje ({@code produtos_custos}) e a linha sai marcada <b>custo do cadastro</b> (esse custo segue o cadastro, por isso
 * a tela avisa). O custo da linha é o custo unitário vezes a quantidade (NUMERIC(14,2) por inteiro: exato, sem arredondar).
 *
 * <p>Só a dona vê custo: nada daqui vai ao Corona Vendas, e o {@code VendidoLiquido} continua sem custo.
 */
final class CustoDaPeca {

    private CustoDaPeca() {
    }

    /**
     * O trecho {@code JOIN LATERAL} que dá, para cada linha, {@code <alias>.custo} (o custo unitário) e
     * {@code <alias>.cadastro} (verdadeiro quando o custo é o do cadastro). {@code produto} e {@code dia} são expressões
     * SQL da linha de fora (por exemplo {@code l.produto_id} e {@code l.dia}).
     */
    static String juntar(String alias, String produto, String dia) {
        return " JOIN LATERAL (SELECT coalesce(h.custo, pc.preco_custo, 0) AS custo, (h.custo IS NULL) AS cadastro "
                + "FROM (SELECT " + produto + " AS produto_id) px "
                + "LEFT JOIN corona.produtos_custos pc ON pc.produto_id = px.produto_id "
                + "LEFT JOIN LATERAL (SELECT hc.custo FROM corona.historico_custo hc "
                + "JOIN corona.compras cp ON cp.id = hc.compra_id AND cp.situacao = 'REGISTRADA' "
                + "WHERE hc.produto_id = px.produto_id AND hc.data_compra <= " + dia + " "
                + "ORDER BY hc.data_compra DESC, hc.ordem DESC LIMIT 1) h ON true) " + alias + " ON true ";
    }
}
