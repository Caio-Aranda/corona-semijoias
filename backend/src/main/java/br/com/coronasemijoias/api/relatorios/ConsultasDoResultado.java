package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.emTresMoedas;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;
import static br.com.coronasemijoias.api.relatorios.VendidoLiquido.CONCLUIDA;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal;
import br.com.coronasemijoias.api.relatorios.VendidoLiquido.Recorte;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O resultado do período (DRE simplificado), o lucro por produto e o lucro por vendedor da dona (tarefa 8.6; ADR-0015, itens 1
 * a 3; §12). Só leitura, só para a dona. Receita, devolução e peças saem SÓ do {@link VendidoLiquido} (a mesma consulta dos
 * relatórios de vendas e da curva ABC), o custo SÓ do {@link CustoDaPeca}, e a comissão do DRE e do lucro por vendedor sai da
 * mesma subconsulta ({@link #COMISSOES}): uma fórmula só, sem SQL copiado. Toda soma é feita no banco, em NUMERIC; o único
 * arredondamento daqui é a margem, em {@link Dinheiro}. A devolução abate o dia da VENDA (como na 8.5).
 *
 * <p>Tudo é gravado em Real. O Dólar e o Guarani de cada linha de origem são calculados na leitura pela cotação do dia dela
 * (a da venda; a do fechamento do acerto, para comissão e cobrado; a do dia, para o caixa e as perdas) e o total em Dólar ou
 * Guarani é a soma das linhas já convertidas; vazio se alguma linha de origem não tem cotação. Nada convertido é gravado.
 * O custo só existe aqui, na Gestão: nada disto vai ao Corona Vendas.
 */
@Service
public class ConsultasDoResultado {

    private static final String DIA_DE_BRASILIA = "(%s AT TIME ZONE 'America/Sao_Paulo')::date";
    private static final String NO_PERIODO = " BETWEEN :de AND :ate";

    /** As linhas do resultado, pela data do fechamento do acerto: a comissão paga, com os estornos. */
    private static final String COMISSOES = "corona.comissoes c JOIN corona.acertos a ON a.id = c.acerto_id AND a.status = 'ACERTADO' "
            + "LEFT JOIN corona.cotacoes ct ON ct.id = a.cotacao_id WHERE " + dia("a.fechado_em") + NO_PERIODO;

    /** Uma peça perdida: falta no acerto, falta encontrada depois, avaria do estoque e inventário com menos peças. */
    private static final String PERDAS = "SELECT p.id, 'FALTA' AS tipo, 'Falta no acerto' AS descricao, p.vendedor_id, p.produto_id, "
            + "p.quantidade AS qtd, " + dia("a.fechado_em") + " AS dia, " + dia("a.fechado_em") + " AS dia_do_custo "
            + "FROM corona.pendencias_acerto p JOIN corona.acertos a ON a.id = p.acerto_id AND a.status = 'ACERTADO' "
            + "WHERE p.tipo = 'FALTA' "
            + "UNION ALL SELECT p.id, 'FALTA_ENCONTRADA', 'Falta encontrada depois', p.vendedor_id, p.produto_id, -p.quantidade, "
            + dia("p.resolvida_em") + ", " + dia("a.fechado_em")
            + " FROM corona.pendencias_acerto p JOIN corona.acertos a ON a.id = p.acerto_id AND a.status = 'ACERTADO' "
            + "WHERE p.tipo = 'FALTA' AND p.status = 'ENCONTRADA' "
            + "UNION ALL SELECT m.id, 'AVARIA', CASE WHEN m.estorna_id IS NULL THEN 'Avaria no estoque' ELSE 'Avaria desfeita' END, "
            + "NULL::uuid, m.produto_id, -m.quantidade, " + dia("m.ocorrida_em") + ", " + dia("m.ocorrida_em")
            + " FROM corona.movimentacoes_estoque m WHERE m.tipo = 'AVARIA' "
            + "UNION ALL SELECT m.id, 'INVENTARIO', CASE WHEN m.estorna_id IS NULL THEN 'Inventário com menos peças' "
            + "ELSE 'Inventário desfeito' END, NULL::uuid, m.produto_id, -m.quantidade, " + dia("m.ocorrida_em") + ", "
            + dia("m.ocorrida_em") + " FROM corona.movimentacoes_estoque m "
            + "WHERE (m.tipo = 'AJUSTE' AND m.origem_tipo = 'INVENTARIO' AND m.quantidade < 0) "
            + "OR m.estorna_id IN (SELECT o.id FROM corona.movimentacoes_estoque o "
            + "WHERE o.tipo = 'AJUSTE' AND o.origem_tipo = 'INVENTARIO' AND o.quantidade < 0)";

    private static final String COTACAO_DO_DIA = " LEFT JOIN LATERAL (SELECT c.usd_venda, c.pyg_venda FROM corona.cotacoes c "
            + "WHERE c.data_cotacao <= %s ORDER BY c.data_cotacao DESC LIMIT 1) ct ON true ";

    /** Os filtros de lucro por produto e por vendedor (o resultado usa só o período). */
    public record Filtros(String busca, String vendedorId, String pracaId, String produtoId, String categoriaId, LocalDate de,
            LocalDate ate, Integer pagina, Integer tamanho) {
    }

    /** Uma linha do resultado. {@code operacao}: SOMA, SUBTRAI ou TOTAL; {@code categoriaId} só nas linhas do caixa. */
    public record LinhaDoResultado(String chave, String titulo, String operacao, UUID categoriaId, BigDecimal valorEmReais,
            BigDecimal valorEmDolar, BigDecimal valorEmGuarani) {
    }

    /** O resultado de um período, com quantas peças vendidas estão com o custo do cadastro (estimativa). */
    public record Resultado(LocalDate de, LocalDate ate, long pecasComCustoDoCadastro, List<LinhaDoResultado> linhas) {
    }

    /** Um registro que forma uma linha do resultado (venda, devolução, comissão, perda, cobrança ou lançamento do caixa). */
    public record RegistroDoResultado(UUID id, LocalDate data, String descricao, String vendedor, String produto,
            Integer quantidade, BigDecimal custoUnitario, boolean custoDoCadastro, BigDecimal valorEmReais,
            BigDecimal valorEmDolar, BigDecimal valorEmGuarani) {
    }

    public record LinhaDeLucroDoProduto(long posicao, UUID produtoId, String nome, String codigo, String categoria,
            String fotoPrincipalHash, long pecas, BigDecimal receita, BigDecimal receitaEmDolar, BigDecimal receitaEmGuarani,
            BigDecimal custo, BigDecimal custoEmDolar, BigDecimal custoEmGuarani, BigDecimal lucro, BigDecimal lucroEmDolar,
            BigDecimal lucroEmGuarani, BigDecimal margem, boolean custoDoCadastro) {
    }

    public record LinhaDeLucroDoVendedor(UUID vendedorId, String vendedor, String praca, BigDecimal receita,
            BigDecimal receitaEmDolar, BigDecimal receitaEmGuarani, BigDecimal custo, BigDecimal custoEmDolar,
            BigDecimal custoEmGuarani, BigDecimal lucroBruto, BigDecimal lucroBrutoEmDolar, BigDecimal lucroBrutoEmGuarani,
            BigDecimal comissao, BigDecimal comissaoEmDolar, BigDecimal comissaoEmGuarani, BigDecimal lucro,
            BigDecimal lucroEmDolar, BigDecimal lucroEmGuarani, BigDecimal margem, boolean custoDoCadastro) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MotorDeRelatorio motor;

    ConsultasDoResultado(NamedParameterJdbcTemplate jdbc, MotorDeRelatorio motor) {
        this.jdbc = jdbc;
        this.motor = motor;
    }

    // --- pedaços de SQL ---

    private static String dia(String instante) {
        return DIA_DE_BRASILIA.formatted(instante);
    }

    /** As duas colunas de Dólar e Guarani (soma linha a linha; vazias se alguma linha não tem cotação), com o prefixo dado. */
    private static String moedas(String prefixo, String valor, String cotacao) {
        return semCotacao(cotacao, "usd_venda", "sum(round((" + valor + ") / " + cotacao + ".usd_venda, 2))") + " AS " + prefixo
                + "_d, " + semCotacao(cotacao, "pyg_venda", "sum(round((" + valor + ") / " + cotacao + ".pyg_venda, 0))") + " AS "
                + prefixo + "_g";
    }

    private static String semCotacao(String cotacao, String coluna, String soma) {
        return "CASE WHEN count(*) FILTER (WHERE " + cotacao + "." + coluna + " IS NULL) > 0 THEN NULL ELSE coalesce(" + soma
                + ", 0) END";
    }

    /** O Real e as duas moedas de uma soma. */
    private static String trio(String prefixo, String valor, String cotacao) {
        return "coalesce(sum(" + valor + "), 0) AS " + prefixo + "_r, " + moedas(prefixo, valor, cotacao);
    }

    private static void exigirPeriodo(LocalDate de, LocalDate ate) {
        if (de == null || ate == null) {
            throw Validacoes.recusar("periodo", "Escolha o período: de que dia até que dia.");
        }
        MotorDeRelatorio.conferirPeriodo(de, ate);
    }

    // --- o resultado do período ---

    @Transactional(readOnly = true)
    public Resultado resultado(LocalDate de, LocalDate ate) {
        exigirPeriodo(de, ate);
        String vendido = "SELECT " + trio("vendas", "l.bruto", "l") + ", " + trio("dev", "l.devolvido", "l") + ", "
                + trio("liq", "l.liquido", "l") + ", " + trio("custo", "l.pecas * cu.custo", "l")
                + ", coalesce(sum(l.pecas) FILTER (WHERE cu.cadastro), 0) AS pecas_cadastro FROM (" + VendidoLiquido.FONTE + ") l"
                + CustoDaPeca.juntar("cu", "l.produto_id", "l.dia") + "WHERE " + CONCLUIDA + " AND l.dia" + NO_PERIODO;
        String comissoes = "SELECT " + trio("comissoes", "c.valor", "ct") + " FROM " + COMISSOES;
        String cobrado = "SELECT " + trio("cobrado", "a.faltas_cobradas", "ct") + " FROM corona.acertos a "
                + "LEFT JOIN corona.cotacoes ct ON ct.id = a.cotacao_id WHERE a.status = 'ACERTADO' AND " + dia("a.fechado_em")
                + NO_PERIODO;
        String perdas = "SELECT " + trio("perdas", "x.qtd * cu.custo", "ct") + " FROM (" + PERDAS + ") x"
                + CustoDaPeca.juntar("cu", "x.produto_id", "x.dia_do_custo") + COTACAO_DO_DIA.formatted("x.dia")
                + "WHERE x.dia" + NO_PERIODO;
        String caixa = "SELECT " + trio("caixa", "l.valor", "ct") + " FROM corona.lancamentos_caixa l"
                + COTACAO_DO_DIA.formatted("l.data_lancamento") + "WHERE l.origem_tipo = 'MANUAL' AND l.data_lancamento"
                + NO_PERIODO;
        String soma = "SELECT v.*, c.*, b.*, p.*, x.*, "
                + conta("bruto", "v.liq_%s - v.custo_%s") + ", "
                + conta("resultado", "(v.liq_%s - v.custo_%s) - c.comissoes_%s - p.perdas_%s + b.cobrado_%s + x.caixa_%s")
                + " FROM (" + vendido + ") v CROSS JOIN (" + comissoes + ") c CROSS JOIN (" + cobrado + ") b CROSS JOIN ("
                + perdas + ") p CROSS JOIN (" + caixa + ") x";
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("de", de).addValue("ate", ate);
        var linhas = new ArrayList<LinhaDoResultado>();
        var fechamento = new ArrayList<LinhaDoResultado>();
        long pecasDoCadastro = jdbc.queryForObject(soma, p, (rs, n) -> {
            linhas.add(linha(rs, "VENDAS", "Vendas", "SOMA", "vendas"));
            linhas.add(linha(rs, "DEVOLUCOES", "Devoluções e trocas", "SUBTRAI", "dev"));
            linhas.add(linha(rs, "RECEITA_LIQUIDA", "Receita líquida", "TOTAL", "liq"));
            linhas.add(linha(rs, "CUSTO", "Custo das peças vendidas", "SUBTRAI", "custo"));
            linhas.add(linha(rs, "LUCRO_BRUTO", "Lucro bruto", "TOTAL", "bruto"));
            linhas.add(linha(rs, "COMISSOES", "Comissões", "SUBTRAI", "comissoes"));
            linhas.add(linha(rs, "PERDAS", "Perdas", "SUBTRAI", "perdas"));
            linhas.add(linha(rs, "COBRADO", "Cobrado do vendedor", "SOMA", "cobrado"));
            fechamento.add(linha(rs, "RESULTADO", "Resultado do período", "TOTAL", "resultado"));
            return rs.getLong("pecas_cadastro");
        });
        // As linhas do caixa ficam entre o cobrado e o resultado, que fecha a lista.
        linhas.addAll(linhasDoCaixa(p));
        linhas.addAll(fechamento);
        return new Resultado(de, ate, pecasDoCadastro, linhas);
    }

    /** A conta de uma linha nas três moedas: o modelo usa {@code %s} para r, d e g. */
    private static String conta(String prefixo, String modelo) {
        List<String> partes = new ArrayList<>();
        for (String m : List.of("r", "d", "g")) {
            partes.add("(" + modelo.replace("%s", m) + ") AS " + prefixo + "_" + m);
        }
        return String.join(", ", partes);
    }

    private static LinhaDoResultado linha(java.sql.ResultSet rs, String chave, String titulo, String operacao, String prefixo)
            throws java.sql.SQLException {
        return new LinhaDoResultado(chave, titulo, operacao, null, MotorDeRelatorio.reais(rs, prefixo + "_r"),
                rs.getBigDecimal(prefixo + "_d"), rs.getBigDecimal(prefixo + "_g"));
    }

    private List<LinhaDoResultado> linhasDoCaixa(MapSqlParameterSource p) {
        String sql = "SELECT cat.id AS categoria_id, cat.nome, " + trio("v", "l.valor", "ct") + " FROM corona.lancamentos_caixa l "
                + "JOIN corona.categorias_caixa cat ON cat.id = l.categoria_id" + COTACAO_DO_DIA.formatted("l.data_lancamento")
                + "WHERE l.origem_tipo = 'MANUAL' AND l.data_lancamento" + NO_PERIODO
                + " GROUP BY cat.id, cat.nome ORDER BY lower(cat.nome), cat.id";
        return jdbc.query(sql, p, (rs, n) -> new LinhaDoResultado("CAIXA", rs.getString("nome"), "SOMA",
                rs.getObject("categoria_id", UUID.class), MotorDeRelatorio.reais(rs, "v_r"), rs.getBigDecimal("v_d"),
                rs.getBigDecimal("v_g")));
    }

    // --- o detalhamento, até o registro ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<RegistroDoResultado> detalhamento(LocalDate de, LocalDate ate, String linha, String categoriaId,
            Integer pagina, Integer tamanho) {
        exigirPeriodo(de, ate);
        String qual = linha == null ? "" : linha.strip().toUpperCase();
        UUID categoria = idOpcional(categoriaId, "categoria");
        String registros = switch (qual) {
            case "VENDAS" -> registrosDeVendas("l.bruto", "'Venda'", "l.qtd_vendida", "true");
            case "DEVOLUCOES" -> registrosDeVendas("l.devolvido", "'Devolução ou troca (abate o mês da venda)'",
                    "l.qtd_devolvida", "l.devolvido <> 0");
            case "CUSTO" -> registrosDeCusto();
            case "COMISSOES" -> registrosDeComissao();
            case "PERDAS" -> registrosDePerda();
            case "COBRADO" -> registrosDeCobranca();
            case "CAIXA" -> registrosDoCaixa(categoria != null);
            default -> throw Validacoes.recusar("linha",
                    "Escolha uma das linhas do resultado: vendas, devoluções, custo, comissões, perdas, cobrado ou caixa.");
        };
        List<DefinicaoDeTotal> totais = List.of(emTresMoedas("total", "Total da linha", null, null, "x.valor", "x.dolar",
                "x.guarani"));
        Consulta c = new Consulta("x.*", "(" + registros + ") x", "x.data, x.descricao, x.id", totais);
        c.parametros.addValue("de", de).addValue("ate", ate);
        if (categoria != null && "CAIXA".equals(qual)) {
            c.parametros.addValue("categoria", categoria);
        }
        return motor.executar(c, null, pagina, tamanho, (rs, n) -> new RegistroDoResultado(rs.getObject("id", UUID.class),
                rs.getObject("data", LocalDate.class), rs.getString("descricao"), rs.getString("vendedor"), rs.getString("produto"),
                (Integer) rs.getObject("quantidade"), rs.getBigDecimal("custo_unitario"), rs.getBoolean("cadastro"),
                MotorDeRelatorio.reais(rs, "valor"), rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani")));
    }

    private static String colunasDeConversao(String valor, String cotacao) {
        return "round((" + valor + ") / " + cotacao + ".usd_venda, 2) AS dolar, round((" + valor + ") / " + cotacao
                + ".pyg_venda, 0) AS guarani";
    }

    private static String registrosDeVendas(String valor, String descricao, String quantidade, String condicao) {
        return "SELECT l.item_id AS id, l.dia AS data, " + descricao + " AS descricao, l.vendedor, l.produto, " + quantidade
                + "::int AS quantidade, NULL::numeric AS custo_unitario, false AS cadastro, " + valor + " AS valor, "
                + colunasDeConversao(valor, "l") + " FROM (" + VendidoLiquido.FONTE + ") l WHERE " + CONCLUIDA + " AND "
                + condicao + " AND l.dia" + NO_PERIODO;
    }

    private static String registrosDeCusto() {
        return "SELECT l.item_id AS id, l.dia AS data, 'Custo da peça vendida' AS descricao, l.vendedor, l.produto, "
                + "l.pecas::int AS quantidade, cu.custo AS custo_unitario, cu.cadastro AS cadastro, l.pecas * cu.custo AS valor, "
                + colunasDeConversao("l.pecas * cu.custo", "l") + " FROM (" + VendidoLiquido.FONTE + ") l"
                + CustoDaPeca.juntar("cu", "l.produto_id", "l.dia") + "WHERE " + CONCLUIDA + " AND l.pecas <> 0 AND l.dia"
                + NO_PERIODO;
    }

    private static String registrosDeComissao() {
        return "SELECT c.id, " + dia("a.fechado_em") + " AS data, CASE c.tipo WHEN 'COMISSAO' THEN 'Comissão da venda' "
                + "ELSE 'Estorno da comissão' END AS descricao, vd.nome AS vendedor, NULL::text AS produto, NULL::int AS quantidade, "
                + "NULL::numeric AS custo_unitario, false AS cadastro, c.valor AS valor, " + colunasDeConversao("c.valor", "ct")
                + " FROM " + COMISSOES.replace("WHERE", "JOIN corona.vendedores vd ON vd.id = c.vendedor_id WHERE");
    }

    private static String registrosDePerda() {
        return "SELECT x.id, x.dia AS data, x.descricao, vd.nome AS vendedor, p.nome AS produto, x.qtd::int AS quantidade, "
                + "cu.custo AS custo_unitario, cu.cadastro AS cadastro, x.qtd * cu.custo AS valor, "
                + colunasDeConversao("x.qtd * cu.custo", "ct") + " FROM (" + PERDAS + ") x JOIN corona.produtos p ON p.id = x.produto_id "
                + "LEFT JOIN corona.vendedores vd ON vd.id = x.vendedor_id" + CustoDaPeca.juntar("cu", "x.produto_id", "x.dia_do_custo")
                + COTACAO_DO_DIA.formatted("x.dia") + "WHERE x.dia" + NO_PERIODO;
    }

    private static String registrosDeCobranca() {
        return "SELECT a.id, " + dia("a.fechado_em") + " AS data, 'Falta cobrada no acerto' AS descricao, vd.nome AS vendedor, "
                + "NULL::text AS produto, NULL::int AS quantidade, NULL::numeric AS custo_unitario, false AS cadastro, "
                + "a.faltas_cobradas AS valor, " + colunasDeConversao("a.faltas_cobradas", "ct")
                + " FROM corona.acertos a JOIN corona.vendedores vd ON vd.id = a.vendedor_id "
                + "LEFT JOIN corona.cotacoes ct ON ct.id = a.cotacao_id WHERE a.status = 'ACERTADO' AND a.faltas_cobradas <> 0 AND "
                + dia("a.fechado_em") + NO_PERIODO;
    }

    private static String registrosDoCaixa(boolean daCategoria) {
        return "SELECT l.id, l.data_lancamento AS data, cat.nome || CASE WHEN l.estorna_id IS NULL THEN '' ELSE ' (estorno)' END "
                + "|| coalesce(': ' || l.descricao, '') AS descricao, NULL::text AS vendedor, NULL::text AS produto, "
                + "NULL::int AS quantidade, NULL::numeric AS custo_unitario, false AS cadastro, l.valor AS valor, "
                + colunasDeConversao("l.valor", "ct") + " FROM corona.lancamentos_caixa l "
                + "JOIN corona.categorias_caixa cat ON cat.id = l.categoria_id" + COTACAO_DO_DIA.formatted("l.data_lancamento")
                + "WHERE l.origem_tipo = 'MANUAL' AND l.data_lancamento" + NO_PERIODO
                + (daCategoria ? " AND l.categoria_id = :categoria" : "");
    }

    // --- lucro por produto ---

    /** Receita líquida menos o custo das peças vendidas, por produto (o custo do dia da venda; ADR-0015, item 3). */
    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeLucroDoProduto> lucroPorProduto(Filtros f) {
        exigirPeriodo(f.de(), f.ate());
        Consulta filtros = VendidoLiquido.filtrar(new Recorte(f.busca(), f.vendedorId(), f.pracaId(), null, f.produtoId(),
                f.categoriaId(), f.de(), f.ate()));
        String lucro = "l.liquido - l.pecas * cu.custo";
        String agrupado = "SELECT l.produto_id::text AS chave, l.produto_id, l.produto AS nome, max(l.codigo) AS codigo, "
                + "max(l.categoria) AS categoria, max(l.foto_principal_hash) AS foto, coalesce(sum(l.pecas), 0) AS pecas, "
                + trio("receita", "l.liquido", "l") + ", " + trio("custo", "l.pecas * cu.custo", "l") + ", "
                + trio("lucro", lucro, "l") + ", coalesce(bool_or(cu.cadastro AND l.pecas <> 0), false) AS cadastro, "
                + "row_number() OVER (ORDER BY sum(" + lucro + ") DESC, l.produto, l.produto_id) AS posicao FROM ("
                + VendidoLiquido.FONTE + ") l" + CustoDaPeca.juntar("cu", "l.produto_id", "l.dia") + "WHERE " + CONCLUIDA
                + (filtros.condicoes.isEmpty() ? "" : " AND " + String.join(" AND ", filtros.condicoes))
                + " GROUP BY l.produto_id, l.produto";
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("receita", "Receita líquida", null, "x.pecas", "x.receita_r", "x.receita_d", "x.receita_g"),
                emTresMoedas("custo", "Custo das peças vendidas", null, null, "x.custo_r", "x.custo_d", "x.custo_g"),
                emTresMoedas("lucro", "Lucro", null, null, "x.lucro_r", "x.lucro_d", "x.lucro_g"));
        Consulta c = new Consulta("x.*", "(" + agrupado + ") x", "x.posicao", totais);
        c.parametros.addValues(filtros.parametros.getValues());
        return motor.executar(c, null, f.pagina(), f.tamanho(), (rs, n) -> {
            BigDecimal receita = MotorDeRelatorio.reais(rs, "receita_r");
            BigDecimal lucroDaLinha = MotorDeRelatorio.reais(rs, "lucro_r");
            return new LinhaDeLucroDoProduto(rs.getLong("posicao"), rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                    rs.getString("codigo"), rs.getString("categoria"), rs.getString("foto"), rs.getLong("pecas"), receita,
                    rs.getBigDecimal("receita_d"), rs.getBigDecimal("receita_g"), MotorDeRelatorio.reais(rs, "custo_r"),
                    rs.getBigDecimal("custo_d"), rs.getBigDecimal("custo_g"), lucroDaLinha, rs.getBigDecimal("lucro_d"),
                    rs.getBigDecimal("lucro_g"), margem(lucroDaLinha, receita), rs.getBoolean("cadastro"));
        });
    }

    private static BigDecimal margem(BigDecimal lucro, BigDecimal receita) {
        return receita.signum() > 0 ? Dinheiro.percentualDe(lucro, receita) : null;
    }

    // --- lucro por vendedor ---

    /**
     * Receita líquida menos o custo menos a comissão, por vendedor. A comissão é a do DRE ({@link #COMISSOES}), agrupada por
     * vendedor: quem só tem comissão no período (estorno de uma devolução, por exemplo) também aparece.
     */
    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeLucroDoVendedor> lucroPorVendedor(Filtros f) {
        exigirPeriodo(f.de(), f.ate());
        List<String> condicoes = new ArrayList<>();
        MapSqlParameterSource parametros = new MapSqlParameterSource().addValue("de", f.de()).addValue("ate", f.ate());
        UUID vendedor = idOpcional(f.vendedorId(), "vendedor");
        if (vendedor != null) {
            condicoes.add("vd.id = :vendedor");
            parametros.addValue("vendedor", vendedor);
        }
        UUID praca = idOpcional(f.pracaId(), "praca");
        if (praca != null) {
            condicoes.add("vd.praca_id = :praca");
            parametros.addValue("praca", praca);
        }
        String busca = MotorDeRelatorio.limparBusca(f.busca());
        if (!busca.isEmpty()) {
            condicoes.add("lower(vd.nome) LIKE :texto");
            parametros.addValue("texto", "%" + busca + "%");
        }
        String vendas = "SELECT l.vendedor_id, " + trio("rec", "l.liquido", "l") + ", " + trio("cus", "l.pecas * cu.custo", "l")
                + ", coalesce(bool_or(cu.cadastro AND l.pecas <> 0), false) AS cadastro FROM (" + VendidoLiquido.FONTE + ") l"
                + CustoDaPeca.juntar("cu", "l.produto_id", "l.dia") + "WHERE " + CONCLUIDA + " AND l.dia" + NO_PERIODO
                + " GROUP BY l.vendedor_id";
        String comissoes = "SELECT c.vendedor_id, " + trio("com", "c.valor", "ct") + " FROM " + COMISSOES + " GROUP BY c.vendedor_id";
        // As colunas derivadas (lucro bruto e lucro) saem da conta feita sobre as colunas já escolhidas.
        String base = "SELECT vd.id AS vendedor_id, vd.nome, pc.nome AS praca, coalesce(s.cadastro, false) AS cadastro, "
                + par("rec", "s") + ", " + par("cus", "s") + ", " + par("com", "m") + " FROM corona.vendedores vd "
                + "LEFT JOIN corona.pracas pc ON pc.id = vd.praca_id LEFT JOIN (" + vendas + ") s ON s.vendedor_id = vd.id "
                + "LEFT JOIN (" + comissoes + ") m ON m.vendedor_id = vd.id WHERE (s.vendedor_id IS NOT NULL OR m.vendedor_id IS NOT NULL)"
                + (condicoes.isEmpty() ? "" : " AND " + String.join(" AND ", condicoes));
        String completo = "SELECT b.*, " + conta("bruto", "b.rec_%s - b.cus_%s") + ", "
                + conta("lucro", "b.rec_%s - b.cus_%s - b.com_%s") + ", row_number() OVER (ORDER BY lower(b.nome), b.vendedor_id) AS posicao "
                + "FROM (" + base + ") b";
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("receita", "Receita líquida", null, null, "x.rec_r", "x.rec_d", "x.rec_g"),
                emTresMoedas("custo", "Custo das peças vendidas", null, null, "x.cus_r", "x.cus_d", "x.cus_g"),
                emTresMoedas("lucroBruto", "Lucro bruto", null, null, "x.bruto_r", "x.bruto_d", "x.bruto_g"),
                emTresMoedas("comissao", "Comissão", null, null, "x.com_r", "x.com_d", "x.com_g"),
                emTresMoedas("lucro", "Lucro", null, null, "x.lucro_r", "x.lucro_d", "x.lucro_g"));
        Consulta consulta = new Consulta("x.*", "(" + completo + ") x", "x.posicao", totais);
        consulta.parametros.addValues(parametros.getValues());
        return motor.executar(consulta, null, f.pagina(), f.tamanho(), (rs, n) -> {
            BigDecimal receita = MotorDeRelatorio.reais(rs, "rec_r");
            BigDecimal lucro = MotorDeRelatorio.reais(rs, "lucro_r");
            return new LinhaDeLucroDoVendedor(rs.getObject("vendedor_id", UUID.class), rs.getString("nome"), rs.getString("praca"),
                    receita, rs.getBigDecimal("rec_d"), rs.getBigDecimal("rec_g"), MotorDeRelatorio.reais(rs, "cus_r"),
                    rs.getBigDecimal("cus_d"), rs.getBigDecimal("cus_g"), MotorDeRelatorio.reais(rs, "bruto_r"),
                    rs.getBigDecimal("bruto_d"), rs.getBigDecimal("bruto_g"), MotorDeRelatorio.reais(rs, "com_r"),
                    rs.getBigDecimal("com_d"), rs.getBigDecimal("com_g"), lucro, rs.getBigDecimal("lucro_d"),
                    rs.getBigDecimal("lucro_g"), margem(lucro, receita), rs.getBoolean("cadastro"));
        });
    }

    /**
     * As três colunas (Real, Dólar e Guarani) de uma soma que pode faltar para o vendedor: sem linha, o Real é zero e o Dólar e o
     * Guarani também (nada a converter); com linha sem cotação, o Dólar e o Guarani ficam vazios.
     */
    private static String par(String prefixo, String alias) {
        return "coalesce(" + alias + "." + prefixo + "_r, 0) AS " + prefixo + "_r, CASE WHEN " + alias + ".vendedor_id IS NULL THEN 0 "
                + "ELSE " + alias + "." + prefixo + "_d END AS " + prefixo + "_d, CASE WHEN " + alias + ".vendedor_id IS NULL THEN 0 "
                + "ELSE " + alias + "." + prefixo + "_g END AS " + prefixo + "_g";
    }
}
