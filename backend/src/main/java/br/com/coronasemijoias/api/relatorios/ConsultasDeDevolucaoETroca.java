package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.emTresMoedas;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatórios de devolução e troca da dona (tarefa 6.10; §12, "Devolução e troca"; ADR-0014): devoluções por vendedor e
 * motivo, taxa de devolução por vendedor, peças com defeito, trocas, créditos em aberto e usados, dinheiro devolvido e
 * pedidos de autorização. Só leitura, no motor dos demais relatórios (linhas, rodapé e detalhamento saem das mesmas
 * condições). Tudo é lido em Real; Dólar e Guarani são calculados na leitura pela cotação do dia de CADA lançamento
 * (da devolução, da troca, do dia em que o crédito nasceu, do dia em que o dinheiro voltou, do pedido: nunca a de hoje)
 * e o rodapé soma as linhas já convertidas. Lançamento de dia sem cotação fica sem Dólar e sem Guarani. O período
 * (de e até, inclusive) conta dias de Brasília. Nenhum custo aparece aqui.
 */
@Service
public class ConsultasDeDevolucaoETroca {

    private static final String BRASILIA = "America/Sao_Paulo";

    public record Filtros(String busca, String vendedorId, String produtoId, String categoriaId, String motivo,
            String situacao, String tipo, String forma, LocalDate de, LocalDate ate, String recorte, Integer pagina,
            Integer tamanho, String pracaId) {

        /** Sem praça (todos os relatórios que já existiam). */
        public Filtros(String busca, String vendedorId, String produtoId, String categoriaId, String motivo, String situacao,
                String tipo, String forma, LocalDate de, LocalDate ate, String recorte, Integer pagina, Integer tamanho) {
            this(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate, recorte, pagina, tamanho, null);
        }
    }

    public record LinhaDeDevolucao(UUID id, UUID vendaId, UUID vendedorId, String vendedor, UUID clienteId, String cliente,
            String motivo, String tipo, String situacao, boolean cancelada, boolean dentroDaGarantia, Instant devolvidaEm,
            int pecas, int pecasComDefeito, String formaReembolso, BigDecimal valorDevolvido,
            BigDecimal valorDevolvidoEmDolar, BigDecimal valorDevolvidoEmGuarani, BigDecimal valorReembolsado,
            BigDecimal valorReembolsadoEmDolar, BigDecimal valorReembolsadoEmGuarani, BigDecimal valorEmCredito,
            BigDecimal valorEmCreditoEmDolar, BigDecimal valorEmCreditoEmGuarani, String dataDaCotacao) {
    }

    public record LinhaPorVendedor(UUID vendedorId, String vendedor, long vendas, BigDecimal vendido,
            BigDecimal vendidoEmDolar, BigDecimal vendidoEmGuarani, long devolucoes, long trocas, BigDecimal devolvido,
            BigDecimal devolvidoEmDolar, BigDecimal devolvidoEmGuarani, long porDesistencia, long porDefeito,
            long porTamanho, long porOutro, BigDecimal porcentagemDevolvida, boolean acimaDoLimite) {
    }

    public record LinhaDePecaComDefeito(UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, long pecas, long devolucoes, long vendedores, BigDecimal valor, BigDecimal valorEmDolar,
            BigDecimal valorEmGuarani, Instant ultimaVez) {
    }

    public record LinhaDeTroca(UUID id, UUID vendaOrigemId, UUID vendaNovaId, UUID devolucaoId, UUID vendedorId, String vendedor,
            UUID clienteId, String cliente, String situacao, Instant trocadaEm, int pecasQueVoltaram, int pecasQueSairam,
            BigDecimal valorDevolvido, BigDecimal valorDevolvidoEmDolar, BigDecimal valorDevolvidoEmGuarani,
            BigDecimal totalDaVendaNova, BigDecimal totalDaVendaNovaEmDolar, BigDecimal totalDaVendaNovaEmGuarani,
            BigDecimal diferenca, BigDecimal diferencaEmDolar, BigDecimal diferencaEmGuarani, String dataDaCotacao) {
    }

    public record LinhaDeCredito(UUID id, UUID clienteId, String cliente, UUID vendedorId, String vendedor, String origem,
            String situacao, Instant geradoEm, BigDecimal valor, BigDecimal valorEmDolar, BigDecimal valorEmGuarani,
            BigDecimal utilizado, BigDecimal utilizadoEmDolar, BigDecimal utilizadoEmGuarani, BigDecimal saldo,
            BigDecimal saldoEmDolar, BigDecimal saldoEmGuarani, String dataDaCotacao) {
    }

    public record LinhaDeReembolso(UUID id, UUID devolucaoId, UUID vendaId, UUID vendedorId, String vendedor, UUID clienteId,
            String cliente, String forma, String tipo, String motivo, Instant pagoEm, BigDecimal valor, BigDecimal valorEmDolar,
            BigDecimal valorEmGuarani, String dataDaCotacao) {
    }

    public record LinhaDeAutorizacao(UUID id, UUID vendaId, UUID vendedorId, String vendedor, UUID clienteId, String cliente,
            String motivo, String tipo, String situacao, int pecas, Instant pedidaEm, Instant decididaEm, LocalDate validaAte,
            BigDecimal valorPedido, BigDecimal valorPedidoEmDolar, BigDecimal valorPedidoEmGuarani, String dataDaCotacao) {
    }

    private static final String PECAS_DA_DEVOLUCAO = "(SELECT coalesce(sum(i.quantidade), 0) FROM corona.itens_devolucao i "
            + "WHERE i.devolucao_id = d.id)";

    private static final String CODIGO = """
            (SELECT cd.codigo FROM corona.produto_codigos cd WHERE cd.produto_id = p.id AND cd.deleted_at IS NULL
              ORDER BY cd.principal DESC, cd.created_at LIMIT 1)""";

    private static final String BUSCA_DE_PRODUTO = """
            (lower(p.nome) LIKE :texto OR lower(coalesce(p.referencia, '')) LIKE :texto
             OR EXISTS (SELECT 1 FROM corona.produto_codigos cb WHERE cb.produto_id = p.id AND cb.deleted_at IS NULL
                        AND lower(cb.codigo) LIKE :texto))""";

    /** A cotação vigente no momento em que o dinheiro voltou ao cliente. Apelido {@code ct}; precisa de {@code p}. */
    private static final String COTACAO_DO_PAGAMENTO = """
            LEFT JOIN LATERAL (SELECT c2.data_cotacao, c2.origem, c2.vigente_desde, c2.usd_venda, c2.pyg_venda FROM corona.cotacoes c2
                                WHERE c2.vigente_desde <= p.pago_em
                                ORDER BY c2.vigente_desde DESC, c2.id DESC LIMIT 1) ct ON true""";

    private final MotorDeRelatorio motor;
    private final Configuracoes configuracoes;

    ConsultasDeDevolucaoETroca(MotorDeRelatorio motor, Configuracoes configuracoes) {
        this.motor = motor;
        this.configuracoes = configuracoes;
    }

    // --- apoio ---

    private static String dolar(String reais) {
        return "round((" + reais + ") / ct.usd_venda, 2)";
    }

    private static String guarani(String reais) {
        return "round((" + reais + ") / ct.pyg_venda, 0)";
    }

    /** Um total com valor em Real, em Dólar e em Guarani (cada um já convertido linha a linha). */
    private static DefinicaoDeTotal total(String chave, String rotulo, String predicado, String valor) {
        return emTresMoedas(chave, rotulo, predicado, null, valor, dolar(valor), guarani(valor));
    }

    private static boolean preenchido(String v) {
        return v != null && !v.isBlank();
    }

    private static String escolha(String pedida, String padrao, List<String> validas, String campo, String mensagem) {
        String s = preenchido(pedida) ? pedida.strip().toUpperCase() : padrao;
        if (s != null && !validas.contains(s)) {
            throw Validacoes.recusar(campo, mensagem);
        }
        return s;
    }

    private static void uuid(Consulta c, String valor, String campo, String condicao) {
        UUID id = idOpcional(valor, campo);
        if (id != null) {
            c.onde(condicao, campo, id);
        }
    }

    /** Período em dias de Brasília, inclusive nas duas pontas, sobre a coluna dada. */
    private static void periodo(Consulta c, String coluna, LocalDate de, LocalDate ate) {
        MotorDeRelatorio.conferirPeriodo(de, ate);
        String dia = "(" + coluna + " AT TIME ZONE '" + BRASILIA + "')::date";
        if (de != null) {
            c.onde(dia + " >= :periodoDe", "periodoDe", de);
        }
        if (ate != null) {
            c.onde(dia + " <= :periodoAte", "periodoAte", ate);
        }
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime v = rs.getObject(coluna, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static BigDecimal valor(ResultSet rs, String coluna) throws SQLException {
        return rs.getBigDecimal(coluna) == null ? null : MotorDeRelatorio.reais(rs, coluna);
    }

    private static final List<String> MOTIVOS = List.of("ARREPENDIMENTO", "DEFEITO", "TAMANHO", "OUTRO");

    private static void motivo(Consulta c, String pedido, String coluna) {
        if (preenchido(pedido)) {
            String m = escolha(pedido, null, MOTIVOS, "motivo", "Escolha desistência, defeito, não serviu ou outro.");
            c.onde(coluna + " = :motivo", "motivo", m);
        }
    }

    // --- 1. devoluções por vendedor e motivo ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeDevolucao> devolucoes(Filtros f) {
        String feita = "d.situacao <> 'CANCELADA'";
        String devolvido = "d.valor_devolvido";
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("geral", "Devoluções feitas (valem no total)", feita, PECAS_DA_DEVOLUCAO, devolvido,
                        dolar(devolvido), guarani(devolvido)),
                total("arrependimento", "Por desistência", feita + " AND d.motivo = 'ARREPENDIMENTO'", devolvido),
                total("defeito", "Por defeito", feita + " AND d.motivo = 'DEFEITO'", devolvido),
                total("tamanho", "Por não ter servido", feita + " AND d.motivo = 'TAMANHO'", devolvido),
                total("outro", "Por outro motivo", feita + " AND d.motivo = 'OUTRO'", devolvido),
                total("em-dinheiro", "Voltou em dinheiro", feita + " AND d.valor_reembolsado > 0", "d.valor_reembolsado"),
                total("em-credito", "Voltou em crédito", feita + " AND d.valor_em_credito > 0", "d.valor_em_credito"),
                total("para-revisao", "Para revisão", "d.situacao = 'EM_REVISAO'", devolvido),
                total("nao-aceitas", "Não aceitas", "d.situacao = 'NAO_ACEITA'", devolvido),
                total("de-troca", "De trocas", feita + " AND d.destino = 'TROCA'", devolvido),
                total("canceladas", "Devoluções canceladas (fora do total)", "d.situacao = 'CANCELADA'", devolvido));
        Consulta c = new Consulta("d.id, d.venda_id, d.vendedor_id, v.nome AS vendedor, d.cliente_id, cl.nome AS cliente, "
                + "d.motivo, d.destino, d.situacao, d.dentro_garantia, d.devolvida_em, d.forma_reembolso, "
                + PECAS_DA_DEVOLUCAO + " AS pecas, (SELECT coalesce(sum(i.quantidade), 0) FROM corona.itens_devolucao i "
                + "WHERE i.devolucao_id = d.id AND i.estado_peca = 'DEFEITO') AS pecas_com_defeito, d.valor_devolvido, "
                + dolar(devolvido) + " AS devolvido_dolar, " + guarani(devolvido) + " AS devolvido_guarani, "
                + "d.valor_reembolsado, " + dolar("d.valor_reembolsado") + " AS reembolsado_dolar, "
                + guarani("d.valor_reembolsado") + " AS reembolsado_guarani, d.valor_em_credito, "
                + dolar("d.valor_em_credito") + " AS credito_dolar, " + guarani("d.valor_em_credito")
                + " AS credito_guarani, " + CotacaoVigente.SQL_DATA_DA_COTACAO + "",
                "corona.devolucoes d JOIN corona.vendedores v ON v.id = d.vendedor_id JOIN corona.clientes cl "
                        + "ON cl.id = d.cliente_id LEFT JOIN corona.cotacoes ct ON ct.id = d.cotacao_id",
                "d.devolvida_em DESC, d.id", totais);
        c.onde("d.deleted_at IS NULL");
        uuid(c, f.vendedorId(), "vendedor", "d.vendedor_id = :vendedor");
        motivo(c, f.motivo(), "d.motivo");
        periodo(c, "d.devolvida_em", f.de(), f.ate());
        c.busca(f.busca(), "v.nome", "cl.nome");
        if (preenchido(f.tipo())) {
            String tipo = escolha(f.tipo(), null, List.of("DEVOLUCAO", "TROCA"), "tipo", "Escolha devolução ou troca.");
            c.onde("d.destino = '" + ("TROCA".equals(tipo) ? "TROCA" : "REEMBOLSO") + "'");
        }
        String situacao = escolha(f.situacao(), "REGISTRADAS", List.of("REGISTRADAS", "CANCELADAS", "TODAS", "ACEITA",
                "EM_REVISAO", "MANTIDA", "NAO_ACEITA"), "situacao", "Escolha feitas, canceladas ou todas.");
        switch (situacao) {
            case "REGISTRADAS" -> c.onde(feita);
            case "CANCELADAS" -> c.onde("d.situacao = 'CANCELADA'");
            case "TODAS" -> {
            }
            default -> c.onde("d.situacao = :situacao", "situacao", situacao);
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeDevolucao(
                rs.getObject("id", UUID.class), rs.getObject("venda_id", UUID.class), rs.getObject("vendedor_id", UUID.class),
                rs.getString("vendedor"), rs.getObject("cliente_id", UUID.class), rs.getString("cliente"),
                rs.getString("motivo"), "TROCA".equals(rs.getString("destino")) ? "TROCA" : "DEVOLUCAO",
                rs.getString("situacao"), "CANCELADA".equals(rs.getString("situacao")), rs.getBoolean("dentro_garantia"),
                instante(rs, "devolvida_em"), rs.getInt("pecas"), rs.getInt("pecas_com_defeito"),
                rs.getString("forma_reembolso"), valor(rs, "valor_devolvido"), rs.getBigDecimal("devolvido_dolar"),
                rs.getBigDecimal("devolvido_guarani"), valor(rs, "valor_reembolsado"), rs.getBigDecimal("reembolsado_dolar"),
                rs.getBigDecimal("reembolsado_guarani"), valor(rs, "valor_em_credito"), rs.getBigDecimal("credito_dolar"),
                rs.getBigDecimal("credito_guarani"), rs.getString("data_cotacao")));
    }

    // --- 2. taxa de devolução por vendedor ---

    /**
     * Uma linha por vendedor com lançamento no período: vendas concluídas, devoluções não canceladas (a troca conta como
     * uma devolução e uma venda) e a porcentagem devolvida, que acende "Acima do limite" pela MESMA conta do aviso da tela
     * inicial ({@code devolvido x 100 > percentual x vendido}, ou devolução sem venda no período).
     */
    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaPorVendedor> porVendedor(Filtros f) {
        String acima = "((x.vendido = 0 AND x.devolvido > 0) OR x.devolvido * 100 > :percentual * x.vendido)";
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("vendido", "Vendido", "x.vendas > 0", null, "x.vendido", "x.vendido_dolar", "x.vendido_guarani"),
                emTresMoedas("devolvido", "Devolvido", "x.devolucoes > 0", null, "x.devolvido", "x.devolvido_dolar",
                        "x.devolvido_guarani"),
                emTresMoedas("com-devolucao", "Vendedores com devolução", "x.devolucoes > 0", null, "x.devolvido",
                        "x.devolvido_dolar", "x.devolvido_guarani"),
                emTresMoedas("acima", "Vendedores acima do limite", acima, null, "x.devolvido", "x.devolvido_dolar",
                        "x.devolvido_guarani"));
        String lancamentos = """
                SELECT v.vendedor_id, 'VENDA' AS tipo, NULL AS motivo, v.total AS valor, ct.usd_venda AS usd,
                       ct.pyg_venda AS pyg, v.vendida_em AS quando
                  FROM corona.vendas v LEFT JOIN corona.cotacoes ct ON ct.id = v.cotacao_id
                 WHERE v.status = 'CONCLUIDA' AND v.deleted_at IS NULL
                UNION ALL
                SELECT d.vendedor_id, 'DEVOLUCAO', d.motivo, d.valor_devolvido, ct.usd_venda, ct.pyg_venda, d.devolvida_em
                  FROM corona.devolucoes d LEFT JOIN corona.cotacoes ct ON ct.id = d.cotacao_id
                 WHERE d.situacao <> 'CANCELADA' AND d.deleted_at IS NULL
                UNION ALL
                SELECT t.vendedor_id, 'TROCA', NULL, 0, NULL, NULL, t.trocada_em
                  FROM corona.trocas t WHERE t.situacao = 'ATIVA' AND t.deleted_at IS NULL""";
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        String dia = "(l.quando AT TIME ZONE '" + BRASILIA + "')::date";
        StringBuilder dentro = new StringBuilder(" WHERE true");
        Consulta c = new Consulta("x.*, v.nome AS vendedor, CASE WHEN x.vendido = 0 THEN NULL ELSE "
                + "round(x.devolvido * 100 / x.vendido, 2) END AS porcentagem, " + acima + " AS acima_do_limite", "", "", totais);
        if (f.de() != null) {
            dentro.append(" AND ").append(dia).append(" >= :periodoDe");
            c.parametros.addValue("periodoDe", f.de());
        }
        if (f.ate() != null) {
            dentro.append(" AND ").append(dia).append(" <= :periodoAte");
            c.parametros.addValue("periodoAte", f.ate());
        }
        String soma = "coalesce(sum(%s) FILTER (WHERE l.tipo = '%s'), 0)";
        String convertida = "CASE WHEN count(*) FILTER (WHERE l.tipo = '%1$s' AND l.usd IS NULL) > 0 THEN NULL ELSE "
                + "coalesce(sum(round(l.valor / l.%2$s, %3$d)) FILTER (WHERE l.tipo = '%1$s'), 0) END";
        String agrupado = "SELECT l.vendedor_id, count(*) FILTER (WHERE l.tipo = 'VENDA') AS vendas, "
                + soma.formatted("l.valor", "VENDA") + " AS vendido, " + convertida.formatted("VENDA", "usd", 2)
                + " AS vendido_dolar, " + convertida.formatted("VENDA", "pyg", 0) + " AS vendido_guarani, "
                + "count(*) FILTER (WHERE l.tipo = 'DEVOLUCAO') AS devolucoes, count(*) FILTER (WHERE l.tipo = 'TROCA') AS trocas, "
                + soma.formatted("l.valor", "DEVOLUCAO") + " AS devolvido, " + convertida.formatted("DEVOLUCAO", "usd", 2)
                + " AS devolvido_dolar, " + convertida.formatted("DEVOLUCAO", "pyg", 0) + " AS devolvido_guarani, "
                + "count(*) FILTER (WHERE l.tipo = 'DEVOLUCAO' AND l.motivo = 'ARREPENDIMENTO') AS por_desistencia, "
                + "count(*) FILTER (WHERE l.tipo = 'DEVOLUCAO' AND l.motivo = 'DEFEITO') AS por_defeito, "
                + "count(*) FILTER (WHERE l.tipo = 'DEVOLUCAO' AND l.motivo = 'TAMANHO') AS por_tamanho, "
                + "count(*) FILTER (WHERE l.tipo = 'DEVOLUCAO' AND l.motivo = 'OUTRO') AS por_outro "
                + "FROM (" + lancamentos + ") l" + dentro + " GROUP BY l.vendedor_id";
        Consulta consulta = new Consulta(c.colunas, "(" + agrupado + ") x JOIN corona.vendedores v ON v.id = x.vendedor_id",
                "lower(v.nome), x.vendedor_id", totais);
        consulta.parametros.addValues(c.parametros.getValues());
        consulta.parametros.addValue("percentual", configuracoes.inteiro(ChaveDeConfiguracao.PERCENTUAL_ALERTA_DEVOLUCAO));
        uuid(consulta, f.vendedorId(), "vendedor", "x.vendedor_id = :vendedor");
        consulta.busca(f.busca(), "v.nome");
        return motor.executar(consulta, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaPorVendedor(
                rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getLong("vendas"), valor(rs, "vendido"),
                rs.getBigDecimal("vendido_dolar"), rs.getBigDecimal("vendido_guarani"), rs.getLong("devolucoes"),
                rs.getLong("trocas"), valor(rs, "devolvido"), rs.getBigDecimal("devolvido_dolar"),
                rs.getBigDecimal("devolvido_guarani"), rs.getLong("por_desistencia"), rs.getLong("por_defeito"),
                rs.getLong("por_tamanho"), rs.getLong("por_outro"), rs.getBigDecimal("porcentagem"),
                rs.getBoolean("acima_do_limite")));
    }

    // --- 3. peças com defeito ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDePecaComDefeito> pecasComDefeito(Filtros f) {
        List<DefinicaoDeTotal> totais = List.of(emTresMoedas("geral", "Peças que voltaram com defeito", null, "x.pecas",
                "x.valor", "x.dolar", "x.guarani"));
        Consulta c = new Consulta("x.*, p.nome, p.referencia, p.foto_principal_hash, " + CODIGO + " AS codigo",
                "", "x.pecas DESC, lower(p.nome), p.id", totais);
        Consulta filtros = new Consulta("", "", "", List.of());
        filtros.onde("i.estado_peca = 'DEFEITO'").onde("d.situacao <> 'CANCELADA'").onde("d.deleted_at IS NULL");
        uuid(filtros, f.vendedorId(), "vendedor", "d.vendedor_id = :vendedor");
        uuid(filtros, f.produtoId(), "produto", "pr.id = :produto");
        uuid(filtros, f.categoriaId(), "categoria", "pr.categoria_id = :categoria");
        periodo(filtros, "d.devolvida_em", f.de(), f.ate());
        String agrupado = "SELECT pr.id AS produto_id, sum(i.quantidade) AS pecas, count(DISTINCT d.id) AS devolucoes, "
                + "count(DISTINCT d.vendedor_id) AS vendedores, sum(i.valor) AS valor, "
                + "CASE WHEN count(*) FILTER (WHERE ct.id IS NULL) > 0 THEN NULL ELSE sum(round(i.valor / ct.usd_venda, 2)) END AS dolar, "
                + "CASE WHEN count(*) FILTER (WHERE ct.id IS NULL) > 0 THEN NULL ELSE sum(round(i.valor / ct.pyg_venda, 0)) END AS guarani, "
                + "max(d.devolvida_em) AS ultima FROM corona.itens_devolucao i JOIN corona.devolucoes d ON d.id = i.devolucao_id "
                + "JOIN corona.itens_venda iv ON iv.id = i.item_venda_id JOIN corona.produtos pr ON pr.id = iv.produto_id "
                + "LEFT JOIN corona.cotacoes ct ON ct.id = d.cotacao_id" + filtros.clausulaWhere() + " GROUP BY pr.id";
        Consulta consulta = new Consulta(c.colunas, "(" + agrupado + ") x JOIN corona.produtos p ON p.id = x.produto_id",
                c.ordem, totais);
        consulta.parametros.addValues(filtros.parametros.getValues());
        String limpo = MotorDeRelatorio.limparBusca(f.busca());
        if (!limpo.isEmpty()) {
            consulta.onde(BUSCA_DE_PRODUTO, "texto", "%" + limpo + "%");
        }
        return motor.executar(consulta, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDePecaComDefeito(
                rs.getObject("produto_id", UUID.class), rs.getString("nome"), rs.getString("referencia"),
                rs.getString("codigo"), rs.getString("foto_principal_hash"), rs.getLong("pecas"), rs.getLong("devolucoes"),
                rs.getLong("vendedores"), valor(rs, "valor"), rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"),
                instante(rs, "ultima")));
    }

    // --- 4. trocas ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeTroca> trocas(Filtros f) {
        String feita = "t.situacao = 'ATIVA'";
        List<DefinicaoDeTotal> totais = List.of(
                total("geral", "Trocas feitas (valem no total)", feita, "d.valor_devolvido"),
                total("a-pagar", "Diferença a pagar", feita + " AND t.diferenca > 0", "t.diferenca"),
                total("credito", "Ficou de crédito", feita + " AND t.diferenca < 0", "-t.diferenca"),
                total("canceladas", "Trocas canceladas (fora do total)", "t.situacao = 'CANCELADA'", "d.valor_devolvido"));
        Consulta c = new Consulta("t.id, t.venda_origem_id, t.venda_nova_id, t.devolucao_id, t.vendedor_id, v.nome AS vendedor, "
                + "t.cliente_id, cl.nome AS cliente, t.situacao, t.trocada_em, " + PECAS_DA_DEVOLUCAO + " AS pecas_voltaram, "
                + "(SELECT coalesce(sum(iv.quantidade), 0) FROM corona.itens_venda iv WHERE iv.venda_id = t.venda_nova_id "
                + "AND iv.deleted_at IS NULL) AS pecas_sairam, d.valor_devolvido, " + dolar("d.valor_devolvido")
                + " AS devolvido_dolar, " + guarani("d.valor_devolvido") + " AS devolvido_guarani, vn.total AS total_nova, "
                + dolar("vn.total") + " AS nova_dolar, " + guarani("vn.total") + " AS nova_guarani, t.diferenca, "
                + dolar("t.diferenca") + " AS diferenca_dolar, " + guarani("t.diferenca") + " AS diferenca_guarani, "
                + "" + CotacaoVigente.SQL_DATA_DA_COTACAO + "",
                "corona.trocas t JOIN corona.vendedores v ON v.id = t.vendedor_id JOIN corona.clientes cl ON cl.id = t.cliente_id "
                        + "JOIN corona.devolucoes d ON d.id = t.devolucao_id JOIN corona.vendas vn ON vn.id = t.venda_nova_id "
                        + "LEFT JOIN corona.cotacoes ct ON ct.id = t.cotacao_id",
                "t.trocada_em DESC, t.id", totais);
        c.onde("t.deleted_at IS NULL");
        uuid(c, f.vendedorId(), "vendedor", "t.vendedor_id = :vendedor");
        uuid(c, f.pracaId(), "praca", "v.praca_id = :praca");
        periodo(c, "t.trocada_em", f.de(), f.ate());
        c.busca(f.busca(), "v.nome", "cl.nome");
        switch (escolha(f.situacao(), "REGISTRADAS", List.of("REGISTRADAS", "CANCELADAS", "TODAS"), "situacao",
                "Escolha feitas, canceladas ou todas.")) {
            case "REGISTRADAS" -> c.onde(feita);
            case "CANCELADAS" -> c.onde("t.situacao = 'CANCELADA'");
            default -> {
            }
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeTroca(
                rs.getObject("id", UUID.class), rs.getObject("venda_origem_id", UUID.class),
                rs.getObject("venda_nova_id", UUID.class), rs.getObject("devolucao_id", UUID.class),
                rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getObject("cliente_id", UUID.class),
                rs.getString("cliente"), rs.getString("situacao"), instante(rs, "trocada_em"), rs.getInt("pecas_voltaram"),
                rs.getInt("pecas_sairam"), valor(rs, "valor_devolvido"), rs.getBigDecimal("devolvido_dolar"),
                rs.getBigDecimal("devolvido_guarani"), valor(rs, "total_nova"), rs.getBigDecimal("nova_dolar"),
                rs.getBigDecimal("nova_guarani"), valor(rs, "diferenca"), rs.getBigDecimal("diferenca_dolar"),
                rs.getBigDecimal("diferenca_guarani"), rs.getString("data_cotacao")));
    }

    // --- 5. créditos em aberto e usados ---

    /** O saldo que ainda vale: o crédito cancelado não tem saldo. */
    private static final String SALDO_DO_CREDITO = "(CASE WHEN cr.situacao = 'CANCELADO' THEN 0 ELSE cr.valor - cr.valor_utilizado END)";

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeCredito> creditos(Filtros f) {
        String valido = "cr.situacao <> 'CANCELADO'";
        List<DefinicaoDeTotal> totais = List.of(
                total("gerados", "Créditos gerados (valem no total)", valido, "cr.valor"),
                total("usados", "Créditos já usados", valido + " AND cr.valor_utilizado > 0", "cr.valor_utilizado"),
                total("em-aberto", "Créditos em aberto", "cr.situacao = 'ABERTO'", SALDO_DO_CREDITO),
                total("cancelados", "Créditos cancelados (fora do total)", "cr.situacao = 'CANCELADO'", "cr.valor"));
        Consulta c = new Consulta("cr.id, cr.cliente_id, cl.nome AS cliente, cr.vendedor_id, v.nome AS vendedor, cr.origem, "
                + "cr.situacao, cr.gerado_em, cr.valor, " + dolar("cr.valor") + " AS valor_dolar, " + guarani("cr.valor")
                + " AS valor_guarani, cr.valor_utilizado, " + dolar("cr.valor_utilizado") + " AS utilizado_dolar, "
                + guarani("cr.valor_utilizado") + " AS utilizado_guarani, " + SALDO_DO_CREDITO + " AS saldo, "
                + dolar(SALDO_DO_CREDITO) + " AS saldo_dolar, " + guarani(SALDO_DO_CREDITO) + " AS saldo_guarani, "
                + "" + CotacaoVigente.SQL_DATA_DA_COTACAO + "",
                "corona.creditos_cliente cr JOIN corona.vendedores v ON v.id = cr.vendedor_id JOIN corona.clientes cl "
                        + "ON cl.id = cr.cliente_id LEFT JOIN corona.cotacoes ct ON ct.id = cr.cotacao_id",
                "cr.gerado_em DESC, cr.id", totais);
        c.onde("cr.deleted_at IS NULL");
        uuid(c, f.vendedorId(), "vendedor", "cr.vendedor_id = :vendedor");
        uuid(c, f.pracaId(), "praca", "v.praca_id = :praca");
        periodo(c, "cr.gerado_em", f.de(), f.ate());
        c.busca(f.busca(), "v.nome", "cl.nome");
        if (preenchido(f.tipo())) {
            String origem = escolha(f.tipo(), null, List.of("TROCA", "DEVOLUCAO", "TRANSFERENCIA"), "tipo",
                    "Escolha troca, devolução ou transferido pela dona.");
            c.onde("cr.origem = :origem", "origem", origem);
        }
        switch (escolha(f.situacao(), "REGISTRADOS", List.of("REGISTRADOS", "ABERTOS", "UTILIZADOS", "CANCELADOS", "TODOS"),
                "situacao", "Escolha em aberto e usados, só em aberto, só usados, cancelados ou todos.")) {
            case "REGISTRADOS" -> c.onde(valido);
            case "ABERTOS" -> c.onde("cr.situacao = 'ABERTO'");
            case "UTILIZADOS" -> c.onde("cr.situacao = 'UTILIZADO'");
            case "CANCELADOS" -> c.onde("cr.situacao = 'CANCELADO'");
            default -> {
            }
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeCredito(
                rs.getObject("id", UUID.class), rs.getObject("cliente_id", UUID.class), rs.getString("cliente"),
                rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getString("origem"),
                rs.getString("situacao"), instante(rs, "gerado_em"), valor(rs, "valor"), rs.getBigDecimal("valor_dolar"),
                rs.getBigDecimal("valor_guarani"), valor(rs, "valor_utilizado"), rs.getBigDecimal("utilizado_dolar"),
                rs.getBigDecimal("utilizado_guarani"), valor(rs, "saldo"), rs.getBigDecimal("saldo_dolar"),
                rs.getBigDecimal("saldo_guarani"), rs.getString("data_cotacao")));
    }

    // --- 6. dinheiro devolvido aos clientes ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeReembolso> reembolsos(Filtros f) {
        String valor = "(-p.valor)";
        List<DefinicaoDeTotal> totais = List.of(
                total("geral", "Dinheiro devolvido (já sem o que foi desfeito)", null, valor),
                total("dinheiro", "Em dinheiro", "p.forma = 'DINHEIRO'", valor),
                total("pix", "Em Pix", "p.forma = 'PIX'", valor),
                total("cartao", "No cartão", "p.forma = 'CARTAO'", valor),
                total("cheque", "Em cheque", "p.forma = 'CHEQUE'", valor),
                total("desfeitos", "Devoluções canceladas (o dinheiro voltou para a venda)",
                        "p.tipo = 'ESTORNO_REEMBOLSO'", valor));
        Consulta c = new Consulta("p.id, p.devolucao_id, p.venda_id, vd.vendedor_id, v.nome AS vendedor, vd.cliente_id, "
                + "cl.nome AS cliente, p.forma, p.tipo, d.motivo, p.pago_em, " + valor + " AS valor, " + dolar(valor)
                + " AS dolar, " + guarani(valor) + " AS guarani, " + CotacaoVigente.SQL_DATA_DA_COTACAO + "",
                "corona.pagamentos_cliente p JOIN corona.vendas vd ON vd.id = p.venda_id JOIN corona.vendedores v "
                        + "ON v.id = vd.vendedor_id JOIN corona.clientes cl ON cl.id = vd.cliente_id JOIN corona.devolucoes d "
                        + "ON d.id = p.devolucao_id " + COTACAO_DO_PAGAMENTO,
                "p.pago_em DESC, p.id", totais);
        c.onde("p.deleted_at IS NULL").onde("p.tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO')").onde("p.forma <> 'CREDITO'");
        uuid(c, f.vendedorId(), "vendedor", "vd.vendedor_id = :vendedor");
        periodo(c, "p.pago_em", f.de(), f.ate());
        c.busca(f.busca(), "v.nome", "cl.nome");
        motivo(c, f.motivo(), "d.motivo");
        if (preenchido(f.forma())) {
            String forma = escolha(f.forma(), null, List.of("DINHEIRO", "PIX", "CARTAO", "CHEQUE"), "forma",
                    "Escolha dinheiro, Pix, cartão ou cheque.");
            c.onde("p.forma = :forma", "forma", forma);
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeReembolso(
                rs.getObject("id", UUID.class), rs.getObject("devolucao_id", UUID.class), rs.getObject("venda_id", UUID.class),
                rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getObject("cliente_id", UUID.class),
                rs.getString("cliente"), rs.getString("forma"),
                "ESTORNO_REEMBOLSO".equals(rs.getString("tipo")) ? "CANCELAMENTO" : "REEMBOLSO", rs.getString("motivo"),
                instante(rs, "pago_em"), valor(rs, "valor"), rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"),
                rs.getString("data_cotacao")));
    }

    // --- 7. pedidos de autorização ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeAutorizacao> autorizacoes(Filtros f) {
        String valor = "a.valor_pedido";
        List<DefinicaoDeTotal> totais = List.of(
                total("geral", "Pedidos feitos", null, valor),
                total("esperando", "Esperando você", "a.situacao = 'PEDIDA'", valor),
                total("aprovados", "Aprovados (ainda valem)", "a.situacao = 'APROVADA'", valor),
                total("usados", "Usados", "a.situacao = 'USADA'", valor),
                total("negados", "Negados", "a.situacao = 'NEGADA'", valor),
                total("vencidos", "Vencidos", "a.situacao = 'VENCIDA'", valor));
        Consulta c = new Consulta("a.id, a.venda_id, a.vendedor_id, v.nome AS vendedor, a.cliente_id, cl.nome AS cliente, "
                + "a.motivo, a.tipo, a.situacao, (SELECT coalesce(sum(i.quantidade), 0) FROM corona.itens_autorizacao i "
                + "WHERE i.autorizacao_id = a.id) AS pecas, a.pedida_em, a.decidida_em, a.valida_ate, a.valor_pedido, "
                + dolar(valor) + " AS dolar, " + guarani(valor) + " AS guarani, " + CotacaoVigente.SQL_DATA_DA_COTACAO + "",
                "corona.autorizacoes_excecao a JOIN corona.vendedores v ON v.id = a.vendedor_id JOIN corona.clientes cl "
                        + "ON cl.id = a.cliente_id LEFT JOIN corona.cotacoes ct ON ct.id = a.cotacao_id",
                "a.pedida_em DESC, a.id", totais);
        c.onde("a.deleted_at IS NULL");
        uuid(c, f.vendedorId(), "vendedor", "a.vendedor_id = :vendedor");
        periodo(c, "a.pedida_em", f.de(), f.ate());
        c.busca(f.busca(), "v.nome", "cl.nome");
        motivo(c, f.motivo(), "a.motivo");
        if (preenchido(f.tipo())) {
            String tipo = escolha(f.tipo(), null, List.of("FORA_DO_PRAZO", "ACIMA_DO_VALOR"), "tipo",
                    "Escolha passou do prazo ou passou do valor.");
            c.onde("a.tipo = :tipo", "tipo", tipo);
        }
        String situacao = escolha(f.situacao(), "TODAS", List.of("TODAS", "PEDIDA", "APROVADA", "USADA", "NEGADA", "VENCIDA"),
                "situacao", "Escolha uma das situações da lista.");
        if (!"TODAS".equals(situacao)) {
            c.onde("a.situacao = :situacao", "situacao", situacao);
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeAutorizacao(
                rs.getObject("id", UUID.class), rs.getObject("venda_id", UUID.class), rs.getObject("vendedor_id", UUID.class),
                rs.getString("vendedor"), rs.getObject("cliente_id", UUID.class), rs.getString("cliente"),
                rs.getString("motivo"), rs.getString("tipo"), rs.getString("situacao"), rs.getInt("pecas"),
                instante(rs, "pedida_em"), instante(rs, "decidida_em"), rs.getObject("valida_ate", LocalDate.class),
                valor(rs, "valor_pedido"), rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"),
                rs.getString("data_cotacao")));
    }
}
