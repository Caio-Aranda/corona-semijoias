package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;

import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A consulta única do <b>vendido líquido</b> (§13, item 2; ADR-0015, itens 3 e 4): uma linha por peça de cada venda, com o
 * vendido (preço x quantidade - desconto da linha), o devolvido (devoluções e trocas não canceladas, pelo preço que o cliente
 * pagou) e o líquido (vendido - devolvido). Os relatórios de vendas, a curva ABC e, nas tarefas seguintes, o resultado do
 * período e o painel da semana agrupam ESTA consulta e só ela: o mesmo número em dois lugares sai da mesma conta.
 *
 * <p>O Dólar e o Guarani de cada linha são calculados pela cotação do dia da venda (a que ficou na venda; vazio se não havia
 * cotação), linha a linha; o total em Dólar ou em Guarani é a soma das linhas já convertidas (§12). Nada convertido é gravado.
 * A venda estornada aparece com {@code status = 'ESTORNADA'}: quem soma o vendido líquido usa só as {@code 'CONCLUIDA'}.
 * Nenhum custo passa por aqui. O dia da venda é o dia de Brasília.
 */
final class VendidoLiquido {

    /** As linhas do vendido líquido, para usar como {@code FROM (...) l}. */
    static final String FONTE = """
            SELECT iv.id AS item_id, v.id AS venda_id, v.vendedor_id, vd.nome AS vendedor, vd.praca_id, pc.nome AS praca,
                   v.cliente_id, cl.nome AS cliente, iv.produto_id, p.nome AS produto, p.referencia, p.categoria_id,
                   ca.nome AS categoria, p.foto_principal_hash,
                   (SELECT cd.codigo FROM corona.produto_codigos cd WHERE cd.produto_id = p.id AND cd.deleted_at IS NULL
                     ORDER BY cd.principal DESC, cd.created_at LIMIT 1) AS codigo,
                   v.status, v.vendida_em, (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
                   v.estornada_em, ue.nome AS estornada_por, v.motivo_estorno,
                   iv.quantidade AS qtd_vendida,
                   iv.preco_unitario * iv.quantidade - iv.desconto AS bruto, iv.desconto AS desconto,
                   coalesce(dv.pecas, 0) AS qtd_devolvida, coalesce(dv.valor, 0) AS devolvido,
                   iv.preco_unitario * iv.quantidade - iv.desconto - coalesce(dv.valor, 0) AS liquido,
                   iv.quantidade - coalesce(dv.pecas, 0) AS pecas,
                   ct.data_cotacao, ct.usd_venda, ct.pyg_venda
              FROM corona.itens_venda iv
              JOIN corona.vendas v ON v.id = iv.venda_id AND v.deleted_at IS NULL
              JOIN corona.vendedores vd ON vd.id = v.vendedor_id
              LEFT JOIN corona.pracas pc ON pc.id = vd.praca_id
              JOIN corona.clientes cl ON cl.id = v.cliente_id
              JOIN corona.produtos p ON p.id = iv.produto_id
              JOIN corona.categorias ca ON ca.id = p.categoria_id
              LEFT JOIN corona.cotacoes ct ON ct.id = v.cotacao_id
              LEFT JOIN corona.usuarios ue ON ue.id = v.estornada_por
              LEFT JOIN LATERAL (SELECT sum(i.quantidade) AS pecas, sum(i.valor) AS valor
                                   FROM corona.itens_devolucao i JOIN corona.devolucoes d ON d.id = i.devolucao_id
                                  WHERE i.item_venda_id = iv.id AND d.situacao <> 'CANCELADA' AND d.deleted_at IS NULL) dv ON true
             WHERE iv.deleted_at IS NULL""";

    static final String CONCLUIDA = "l.status = 'CONCLUIDA'";

    private VendidoLiquido() {
    }

    /** O pedido da pessoa: os filtros que valem para qualquer relatório feito sobre o vendido líquido. */
    record Recorte(String busca, String vendedorId, String pracaId, String clienteId, String produtoId, String categoriaId,
            LocalDate de, LocalDate ate) {
    }

    /**
     * As condições do pedido, sobre as linhas ({@code l}). O período conta dias de Brasília, inclusive nas duas pontas, sobre a
     * data da venda. Devolve uma consulta só com condições e parâmetros, para juntar à que agrupa a fonte.
     */
    static Consulta filtrar(Recorte r) {
        MotorDeRelatorio.conferirPeriodo(r.de(), r.ate());
        Consulta c = new Consulta("", "", "", List.of());
        uuid(c, r.vendedorId(), "vendedor", "l.vendedor_id = :vendedor");
        uuid(c, r.pracaId(), "praca", "l.praca_id = :praca");
        uuid(c, r.clienteId(), "cliente", "l.cliente_id = :cliente");
        uuid(c, r.produtoId(), "produto", "l.produto_id = :produto");
        uuid(c, r.categoriaId(), "categoria", "l.categoria_id = :categoria");
        if (r.de() != null) {
            c.onde("l.dia >= :periodoDe", "periodoDe", r.de());
        }
        if (r.ate() != null) {
            c.onde("l.dia <= :periodoAte", "periodoAte", r.ate());
        }
        c.busca(r.busca(), "l.vendedor", "l.cliente", "l.produto", "l.referencia", "l.codigo");
        return c;
    }

    private static void uuid(Consulta c, String valor, String campo, String condicao) {
        UUID id = idOpcional(valor, campo);
        if (id != null) {
            c.onde(condicao, campo, id);
        }
    }

    /**
     * A soma, nas linhas de um grupo, de um valor em Real convertido para Dólar ({@code usd_venda}, 2 casas) ou Guarani
     * ({@code pyg_venda}, sem casas), linha a linha. Vazio se alguma linha do grupo não tinha cotação.
     */
    private static String somaEm(String valor, String cotacao, int casas) {
        return "CASE WHEN count(*) FILTER (WHERE l." + cotacao + " IS NULL) > 0 THEN NULL ELSE "
                + "coalesce(sum(round((" + valor + ") / l." + cotacao + ", " + casas + ")), 0) END";
    }

    static String somaEmDolar(String valor) {
        return somaEm(valor, "usd_venda", 2);
    }

    static String somaEmGuarani(String valor) {
        return somaEm(valor, "pyg_venda", 0);
    }
}
