package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.contagem;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.pecas;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.somaDe;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.TODOS;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.reais;

import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatórios de estoque e de compras da dona (tarefa 2.5, §12): posição atual, movimentações, estoque mínimo,
 * produtos parados, inventário e divergências, conciliação, compras, compras por fornecedor, itens de uma
 * compra e histórico de custo. Só leitura, no mesmo motor dos relatórios de cadastro (linhas, rodapé e
 * detalhamento saem das mesmas condições). Custo e compras só existem aqui, na API da dona. Datas em UTC
 * (de e ate inclusive); a data da compra é uma data de calendário, comparada como está.
 */
@Service
public class ConsultasDeEstoqueECompras {

    /** Filtros de todos os relatórios; cada um usa só os que fazem sentido para ele. */
    public record Filtros(String busca, String categoriaId, String fornecedorId, String produtoId, String situacao,
            String tipo, String origem, String usuarioId, LocalDate de, LocalDate ate, Integer dias,
            String inventarioId, String divergencia, String recorte, Integer pagina, Integer tamanho) {
    }

    public record LinhaDePosicao(UUID produtoId, String nome, String referencia, String codigo, String fotoPrincipalHash,
            String categoria, String fornecedor, boolean ativo, int saldo, int estoqueMinimo, BigDecimal custoUnitario,
            BigDecimal valorACusto) {
    }

    public record LinhaDeMovimento(UUID id, UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, String categoria, String fornecedor, String tipo, int quantidade, int saldoDepois,
            String origem, UUID origemId, String motivo, UUID usuarioId, String usuario, Instant ocorridaEm,
            boolean estorno, boolean desfeito) {
    }

    public record LinhaAbaixoDoMinimo(UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, String categoria, String fornecedor, boolean ativo, int saldo, int minimo,
            int falta) {
    }

    public record LinhaDeProdutoParado(UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, String categoria, String fornecedor, boolean ativo, int saldo,
            Instant ultimaSaidaEm, Long diasSemSair, BigDecimal valorACusto) {
    }

    public record LinhaDeInventario(UUID inventarioId, String situacao, String escopo, String escopoNome,
            Instant iniciadoEm, Instant fechadoEm, UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, String categoria, String fornecedor, int esperado, Integer contado,
            Integer diferenca, Integer ajuste) {
    }

    public record LinhaDeConciliacao(UUID produtoId, String nome, String fotoPrincipalHash, int saldoGuardado,
            long somaDosMovimentos, long diferenca, long nasMaletas, long diferencaComMaletas) {
    }

    public record LinhaDeCompra(UUID id, LocalDate dataCompra, String numero, UUID fornecedorId, String fornecedor,
            long itens, long pecas, BigDecimal total, String situacao, boolean estornada, String motivoDoEstorno,
            Instant estornadaEm, String usuario) {
    }

    public record LinhaDeFornecedorNasCompras(UUID fornecedorId, String fornecedor, long compras, long pecas,
            BigDecimal total, long comprasEstornadas, BigDecimal totalEstornado) {
    }

    public record LinhaDeItemDeCompra(UUID itemId, UUID compraId, LocalDate dataCompra, String numero, String fornecedor,
            String situacaoDaCompra, UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, int quantidade, BigDecimal custoUnitario, BigDecimal total) {
    }

    public record LinhaDeHistoricoDeCusto(UUID id, UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, UUID compraId, String numeroDaCompra, LocalDate dataCompra, String fornecedor,
            boolean compraEstornada, BigDecimal custo, BigDecimal custoAnterior, BigDecimal variacao,
            BigDecimal variacaoPercentual) {
    }

    private static final String CODIGO = """
            (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.deleted_at IS NULL
             ORDER BY c.principal DESC, c.created_at LIMIT 1)""";

    private static final String BUSCA_DE_PRODUTO = """
            (lower(p.nome) LIKE :texto OR lower(coalesce(p.referencia, '')) LIKE :texto
             OR EXISTS (SELECT 1 FROM corona.produto_codigos cb WHERE cb.produto_id = p.id AND cb.deleted_at IS NULL
                        AND lower(cb.codigo) LIKE :texto))""";

    /** Colunas e junções do produto, iguais em todo relatório que mostra uma peça (com a foto). */
    private static final String PRODUTO_COLUNAS = "p.id AS produto_id, p.nome AS produto_nome, p.referencia, "
            + "p.foto_principal_hash, " + CODIGO + " AS codigo, cat.nome AS categoria, forn.razao_social AS fornecedor, "
            + "p.ativo";
    private static final String PRODUTO_JUNCOES = " JOIN corona.categorias cat ON cat.id = p.categoria_id "
            + "LEFT JOIN corona.fornecedores forn ON forn.id = p.fornecedor_id";

    private static final String VALOR_A_CUSTO = "(p.estoque_atual * coalesce(pc.preco_custo, 0))";
    private static final String COM_CUSTO = " LEFT JOIN corona.produtos_custos pc ON pc.produto_id = p.id";

    private final MotorDeRelatorio motor;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock relogio;

    ConsultasDeEstoqueECompras(MotorDeRelatorio motor, NamedParameterJdbcTemplate jdbc, Clock relogio) {
        this.motor = motor;
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    // --- filtros comuns ---

    private static boolean preenchido(String v) {
        return v != null && !v.isBlank();
    }

    private static String escolha(String pedida, String padrao, List<String> validas, String campo, String mensagem) {
        String s = preenchido(pedida) ? pedida.strip().toUpperCase() : padrao;
        if (!validas.contains(s)) {
            throw Validacoes.recusar(campo, mensagem);
        }
        return s;
    }

    private static void situacaoDoProduto(Consulta c, String pedida, String padrao) {
        switch (escolha(pedida, padrao, List.of("ATIVOS", "INATIVOS", "TODOS"), "situacao",
                "Escolha ativos, inativos ou todos.")) {
            case "ATIVOS" -> c.onde("p.ativo = true");
            case "INATIVOS" -> c.onde("p.ativo = false");
            default -> {
            }
        }
    }

    private static void doProduto(Consulta c, Filtros f) {
        String limpo = MotorDeRelatorio.limparBusca(f.busca());
        if (!limpo.isEmpty()) {
            c.onde(BUSCA_DE_PRODUTO, "texto", "%" + limpo + "%");
        }
        uuid(c, f.categoriaId(), "categoria", "p.categoria_id = :categoria");
        uuid(c, f.fornecedorId(), "fornecedor", "p.fornecedor_id = :fornecedor");
        uuid(c, f.produtoId(), "produto", "p.id = :produto");
    }

    private static void uuid(Consulta c, String valor, String campo, String condicao) {
        UUID id = idOpcional(valor, campo);
        if (id != null) {
            c.onde(condicao, campo, id);
        }
    }

    private static void periodoEmDatas(Consulta c, String coluna, Filtros f) {
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        if (f.de() != null) {
            c.onde(coluna + " >= :dataDe", "dataDe", f.de());
        }
        if (f.ate() != null) {
            c.onde(coluna + " <= :dataAte", "dataAte", f.ate());
        }
    }

    private static Instant instante(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        OffsetDateTime v = rs.getObject(coluna, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static Integer inteiroOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        int v = rs.getInt(coluna);
        return rs.wasNull() ? null : v;
    }

    private static <E extends Enum<E>> String enumerado(Class<E> tipo, String valor, String campo, String mensagem) {
        try {
            return Enum.valueOf(tipo, valor.strip().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar(campo, mensagem);
        }
    }

    // --- 1. posição atual ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDePosicao> posicaoAtual(Filtros f) {
        Consulta c = new Consulta(PRODUTO_COLUNAS + ", p.estoque_atual, p.estoque_minimo, pc.preco_custo, "
                + VALOR_A_CUSTO + " AS valor_a_custo", "corona.produtos p" + PRODUTO_JUNCOES + COM_CUSTO,
                "lower(p.nome), p.id", List.of(
                        contagem(TODOS, "Produtos", null),
                        pecas("valor-a-custo", "Peças em estoque e valor a custo", null, "p.estoque_atual", VALOR_A_CUSTO),
                        contagem("com-saldo", "Com estoque", "p.estoque_atual > 0"),
                        contagem("zerados", "Sem estoque", "p.estoque_atual = 0"),
                        contagem("sem-custo", "Com estoque e sem custo informado",
                                "p.estoque_atual > 0 AND pc.preco_custo IS NULL")));
        c.onde("p.deleted_at IS NULL");
        situacaoDoProduto(c, f.situacao(), "ATIVOS");
        doProduto(c, f);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDePosicao(rs.getObject("produto_id", UUID.class), rs.getString("produto_nome"),
                        rs.getString("referencia"), rs.getString("codigo"), rs.getString("foto_principal_hash"),
                        rs.getString("categoria"), rs.getString("fornecedor"), rs.getBoolean("ativo"),
                        rs.getInt("estoque_atual"), rs.getInt("estoque_minimo"), reais(rs, "preco_custo"),
                        reais(rs, "valor_a_custo")));
    }

    // --- 2. movimentações por período ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeMovimento> movimentacoes(Filtros f) {
        Consulta c = new Consulta(PRODUTO_COLUNAS + """
                , m.id, m.tipo, m.quantidade, m.saldo_depois, m.origem_tipo, m.origem_id, m.motivo, m.usuario_id,
                u.nome AS usuario_nome, m.ocorrida_em, m.estorna_id,
                EXISTS (SELECT 1 FROM corona.movimentacoes_estoque d WHERE d.estorna_id = m.id) AS desfeito""",
                "corona.movimentacoes_estoque m JOIN corona.produtos p ON p.id = m.produto_id" + PRODUTO_JUNCOES
                        + " JOIN corona.usuarios u ON u.id = m.usuario_id",
                "m.ocorrida_em DESC, m.id DESC", List.of(
                        contagem(TODOS, "Movimentos", null),
                        pecas("entradas", "Entradas (peças)", "m.quantidade > 0", "m.quantidade", null),
                        pecas("saidas", "Saídas (peças)", "m.quantidade < 0", "-m.quantidade", null),
                        pecas("tipo-entrada", "Entradas e compras", "m.tipo = 'ENTRADA'", "m.quantidade", null),
                        pecas("tipo-saida", "Saídas", "m.tipo = 'SAIDA'", "m.quantidade", null),
                        pecas("tipo-ajuste", "Ajustes", "m.tipo = 'AJUSTE'", "m.quantidade", null),
                        pecas("tipo-remessa", "Remessas", "m.tipo = 'REMESSA'", "m.quantidade", null),
                        pecas("tipo-devolucao", "Devoluções", "m.tipo = 'DEVOLUCAO'", "m.quantidade", null),
                        pecas("tipo-avaria", "Perdas e avarias", "m.tipo = 'AVARIA'", "m.quantidade", null)));
        situacaoDoProduto(c, f.situacao(), "TODOS");
        doProduto(c, f);
        if (preenchido(f.tipo())) {
            c.onde("m.tipo = :tipo", "tipo",
                    enumerado(TipoDeMovimento.class, f.tipo(), "tipo", "Escolha um tipo de movimento da lista."));
        }
        if (preenchido(f.origem())) {
            c.onde("m.origem_tipo = :origem", "origem",
                    enumerado(OrigemDoMovimento.class, f.origem(), "origem", "Escolha uma origem da lista."));
        }
        uuid(c, f.usuarioId(), "usuario", "m.usuario_id = :usuario");
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.periodo("m.ocorrida_em", f.de(), f.ate());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeMovimento(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class),
                        rs.getString("produto_nome"), rs.getString("referencia"), rs.getString("codigo"),
                        rs.getString("foto_principal_hash"), rs.getString("categoria"), rs.getString("fornecedor"),
                        rs.getString("tipo"), rs.getInt("quantidade"), rs.getInt("saldo_depois"),
                        rs.getString("origem_tipo"), rs.getObject("origem_id", UUID.class), rs.getString("motivo"),
                        rs.getObject("usuario_id", UUID.class), rs.getString("usuario_nome"), instante(rs, "ocorrida_em"),
                        rs.getObject("estorna_id") != null, rs.getBoolean("desfeito")));
    }

    // --- 3. estoque mínimo ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaAbaixoDoMinimo> abaixoDoMinimo(Filtros f) {
        String falta = "greatest(p.estoque_minimo - p.estoque_atual, 0)";
        Consulta c = new Consulta(PRODUTO_COLUNAS + ", p.estoque_atual, p.estoque_minimo, " + falta + " AS falta",
                "corona.produtos p" + PRODUTO_JUNCOES, "p.estoque_atual - p.estoque_minimo, lower(p.nome), p.id", List.of(
                        contagem(TODOS, "Produtos no mínimo ou abaixo", null),
                        contagem("zerados", "Sem estoque", "p.estoque_atual = 0"),
                        pecas("falta", "Peças que faltam para chegar ao mínimo", null, falta, null)));
        // Mesma regra do aviso da 2.4: só quem tem mínimo definido, no mínimo ou abaixo.
        c.onde("p.deleted_at IS NULL");
        c.onde("p.estoque_minimo > 0 AND p.estoque_atual <= p.estoque_minimo");
        situacaoDoProduto(c, f.situacao(), "ATIVOS");
        doProduto(c, f);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaAbaixoDoMinimo(rs.getObject("produto_id", UUID.class), rs.getString("produto_nome"),
                        rs.getString("referencia"), rs.getString("codigo"), rs.getString("foto_principal_hash"),
                        rs.getString("categoria"), rs.getString("fornecedor"), rs.getBoolean("ativo"),
                        rs.getInt("estoque_atual"), rs.getInt("estoque_minimo"), rs.getInt("falta")));
    }

    // --- 4. produtos parados ---

    /**
     * Parado = tem estoque e nenhuma saída (SAIDA ou REMESSA, não desfeita) nos últimos {@code dias} dias; produto
     * cadastrado há menos que isso ainda não conta como parado. Perda, ajuste e devolução não são saída.
     */
    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeProdutoParado> parados(Filtros f) {
        int dias = f.dias() == null ? 30 : f.dias();
        if (dias < 1 || dias > 3650) {
            throw Validacoes.recusar("dias", "Escolha de 1 a 3650 dias.");
        }
        Instant agora = relogio.instant();
        OffsetDateTime limite = OffsetDateTime.ofInstant(agora.minus(dias, ChronoUnit.DAYS), ZoneOffset.UTC);
        String saida = "SELECT 1 FROM corona.movimentacoes_estoque s WHERE s.produto_id = p.id AND s.quantidade < 0 "
                + "AND s.tipo IN ('SAIDA', 'REMESSA') AND s.estorna_id IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM corona.movimentacoes_estoque d WHERE d.estorna_id = s.id)";
        String ultima = "(SELECT max(s.ocorrida_em) FROM corona.movimentacoes_estoque s WHERE s.produto_id = p.id "
                + "AND s.quantidade < 0 AND s.tipo IN ('SAIDA', 'REMESSA') AND s.estorna_id IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM corona.movimentacoes_estoque d WHERE d.estorna_id = s.id))";
        Consulta c = new Consulta(PRODUTO_COLUNAS + ", p.estoque_atual, " + ultima + " AS ultima_saida, "
                + VALOR_A_CUSTO + " AS valor_a_custo", "corona.produtos p" + PRODUTO_JUNCOES + COM_CUSTO,
                VALOR_A_CUSTO + " DESC, lower(p.nome), p.id", List.of(
                        contagem(TODOS, "Produtos parados", null),
                        contagem("nunca-saiu", "Nunca tiveram saída", "NOT EXISTS (" + saida + ")"),
                        pecas("valor-a-custo", "Peças paradas e valor a custo", null, "p.estoque_atual", VALOR_A_CUSTO)));
        c.onde("p.deleted_at IS NULL");
        c.onde("p.estoque_atual > 0");
        c.onde("p.created_at < :semSaidaDesde", "semSaidaDesde", limite);
        c.onde("NOT EXISTS (" + saida + " AND s.ocorrida_em >= :semSaidaDesde)");
        situacaoDoProduto(c, f.situacao(), "ATIVOS");
        doProduto(c, f);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            Instant ultimaSaida = instante(rs, "ultima_saida");
            return new LinhaDeProdutoParado(rs.getObject("produto_id", UUID.class), rs.getString("produto_nome"),
                    rs.getString("referencia"), rs.getString("codigo"), rs.getString("foto_principal_hash"),
                    rs.getString("categoria"), rs.getString("fornecedor"), rs.getBoolean("ativo"),
                    rs.getInt("estoque_atual"), ultimaSaida,
                    ultimaSaida == null ? null : ChronoUnit.DAYS.between(ultimaSaida, agora), reais(rs, "valor_a_custo"));
        });
    }

    // --- 5. inventário e divergências ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeInventario> inventarios(Filtros f) {
        Consulta c = new Consulta(PRODUTO_COLUNAS + """
                , i.id AS inventario_id, i.situacao, i.escopo, i.iniciado_em, i.fechado_em,
                (CASE i.escopo WHEN 'CATEGORIA' THEN (SELECT x.nome FROM corona.categorias x WHERE x.id = i.escopo_id)
                               WHEN 'FORNECEDOR' THEN (SELECT x.razao_social FROM corona.fornecedores x WHERE x.id = i.escopo_id)
                               ELSE 'Todos os produtos' END) AS escopo_nome,
                ii.esperado, ii.contado, (ii.contado - ii.esperado) AS diferenca, mv.quantidade AS ajuste""",
                "corona.itens_inventario ii JOIN corona.inventarios i ON i.id = ii.inventario_id "
                        + "JOIN corona.produtos p ON p.id = ii.produto_id" + PRODUTO_JUNCOES
                        + " LEFT JOIN corona.movimentacoes_estoque mv ON mv.id = ii.movimento_id",
                "i.iniciado_em DESC, i.id, lower(p.nome), p.id", List.of(
                        contagem(TODOS, "Itens contados", null),
                        contagem("com-divergencia", "Com divergência",
                                "ii.contado IS NOT NULL AND ii.contado <> ii.esperado"),
                        contagem("nao-lidos", "Ainda não lidos", "ii.contado IS NULL"),
                        pecas("sobras", "Sobras (peças a mais)", "ii.contado > ii.esperado", "ii.contado - ii.esperado", null),
                        pecas("faltas", "Faltas (peças a menos)", "ii.contado < ii.esperado", "ii.esperado - ii.contado", null)));
        // Aqui "situação" é a do inventário (o produto não tem filtro de situação neste relatório).
        String situacao = escolha(f.situacao(), "TODOS", List.of("TODOS", "EM_ANDAMENTO", "FECHADO", "CANCELADO"),
                "situacao", "Escolha em andamento, fechado, cancelado ou todos.");
        if (!"TODOS".equals(situacao)) {
            c.onde("i.situacao = :situacaoDoInventario", "situacaoDoInventario", situacao);
        }
        doProduto(c, f);
        uuid(c, f.inventarioId(), "inventario", "i.id = :inventario");
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.periodo("i.iniciado_em", f.de(), f.ate());
        if (preenchido(f.divergencia())) {
            switch (f.divergencia().strip().toUpperCase()) {
                case "SIM" -> c.onde("ii.contado IS NOT NULL AND ii.contado <> ii.esperado");
                case "NAO" -> c.onde("(ii.contado IS NULL OR ii.contado = ii.esperado)");
                default -> throw Validacoes.recusar("divergencia", "Escolha sim ou não.");
            }
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeInventario(rs.getObject("inventario_id", UUID.class), rs.getString("situacao"),
                        rs.getString("escopo"), rs.getString("escopo_nome"), instante(rs, "iniciado_em"),
                        instante(rs, "fechado_em"), rs.getObject("produto_id", UUID.class), rs.getString("produto_nome"),
                        rs.getString("referencia"), rs.getString("codigo"), rs.getString("foto_principal_hash"),
                        rs.getString("categoria"), rs.getString("fornecedor"), rs.getInt("esperado"),
                        inteiroOuNulo(rs, "contado"), inteiroOuNulo(rs, "diferenca"), inteiroOuNulo(rs, "ajuste")));
    }

    // --- 6. conciliação (mesma conta do serviço de estoque da 2.2: deve vir vazia) ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeConciliacao> conciliacao(Filtros f) {
        Consulta c = new Consulta("c.produto_id, c.nome, c.foto_principal_hash, c.saldo, c.soma, c.nas_maletas, c.fora", """
                (SELECT p.id AS produto_id, p.nome, p.foto_principal_hash, p.estoque_atual AS saldo,
                        coalesce((SELECT sum(m.quantidade) FROM corona.movimentacoes_estoque m WHERE m.produto_id = p.id), 0) AS soma,
                        coalesce((SELECT sum(i.qtd_enviada + coalesce(mm.soma, 0)) FROM corona.remessas r
                                    JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL
                                    LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma
                                                 FROM corona.movimentos_remessa GROUP BY remessa_id, produto_id) mm
                                           ON mm.remessa_id = i.remessa_id AND mm.produto_id = i.produto_id
                                   WHERE i.produto_id = p.id AND r.deleted_at IS NULL
                                     AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO')), 0) AS nas_maletas,
                        coalesce((SELECT sum(me.quantidade) FROM corona.movimentacoes_estoque me
                                    LEFT JOIN corona.movimentacoes_estoque original ON original.id = me.estorna_id
                                   WHERE me.produto_id = p.id AND me.origem_tipo <> 'MALETA'
                                     AND coalesce(original.origem_tipo, '') <> 'MALETA'), 0)
                        + coalesce((SELECT sum(mv.quantidade) FROM corona.movimentos_remessa mv
                                     WHERE mv.produto_id = p.id AND mv.tipo IN ('VENDA', 'ESTORNO_VENDA')), 0) AS fora
                   FROM corona.produtos p) c""", "lower(c.nome), c.produto_id", List.of(
                contagem(TODOS, "Produtos com diferença", null)));
        c.onde("(c.saldo <> c.soma OR c.saldo + c.nas_maletas <> c.fora)");
        c.busca(f.busca(), "c.nome");
        uuid(c, f.produtoId(), "produto", "c.produto_id = :produto");
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeConciliacao(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                        rs.getString("foto_principal_hash"), rs.getInt("saldo"), rs.getLong("soma"),
                        rs.getLong("soma") - rs.getInt("saldo"), rs.getLong("nas_maletas"),
                        rs.getLong("fora") - rs.getInt("saldo") - rs.getLong("nas_maletas")));
    }

    // --- 7. compras (uma linha por compra) ---

    private static String situacaoDaCompra(Filtros f) {
        return escolha(f.situacao(), "REGISTRADAS", List.of("REGISTRADAS", "ESTORNADAS", "TODAS"), "situacao",
                "Escolha registradas, estornadas ou todas.");
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeCompra> compras(Filtros f) {
        Consulta c = new Consulta("""
                c.id, c.data_compra, c.numero, c.fornecedor_id, forn.razao_social AS fornecedor, it.itens, it.pecas,
                c.total, c.situacao, c.estorno_motivo, c.estornada_em, u.nome AS usuario""", """
                corona.compras c
                JOIN corona.fornecedores forn ON forn.id = c.fornecedor_id
                JOIN corona.usuarios u ON u.id = c.usuario_id
                LEFT JOIN LATERAL (SELECT count(*) AS itens, coalesce(sum(i.quantidade), 0) AS pecas
                                     FROM corona.itens_compra i WHERE i.compra_id = c.id) it ON true""",
                "c.data_compra DESC, c.id DESC", List.of(
                        contagem(TODOS, "Compras listadas", null),
                        pecas("registradas", "Compras registradas (total geral)", "c.situacao = 'REGISTRADA'", "it.pecas",
                                "c.total"),
                        pecas("estornadas", "Compras estornadas (fora do total)", "c.situacao = 'ESTORNADA'", "it.pecas",
                                "c.total")));
        switch (situacaoDaCompra(f)) {
            case "REGISTRADAS" -> c.onde("c.situacao = 'REGISTRADA'");
            case "ESTORNADAS" -> c.onde("c.situacao = 'ESTORNADA'");
            default -> {
            }
        }
        uuid(c, f.fornecedorId(), "fornecedor", "c.fornecedor_id = :fornecedor");
        uuid(c, f.usuarioId(), "usuario", "c.usuario_id = :usuario");
        periodoEmDatas(c, "c.data_compra", f);
        String limpo = MotorDeRelatorio.limparBusca(f.busca());
        if (!limpo.isEmpty()) {
            c.onde("(lower(coalesce(c.numero, '')) LIKE :texto OR lower(forn.razao_social) LIKE :texto OR EXISTS ("
                    + "SELECT 1 FROM corona.itens_compra ib JOIN corona.produtos p ON p.id = ib.produto_id "
                    + "WHERE ib.compra_id = c.id AND " + BUSCA_DE_PRODUTO + "))", "texto", "%" + limpo + "%");
        }
        UUID produto = idOpcional(f.produtoId(), "produto");
        if (produto != null) {
            c.onde("EXISTS (SELECT 1 FROM corona.itens_compra ip WHERE ip.compra_id = c.id AND ip.produto_id = :produto)",
                    "produto", produto);
        }
        UUID categoria = idOpcional(f.categoriaId(), "categoria");
        if (categoria != null) {
            c.onde("EXISTS (SELECT 1 FROM corona.itens_compra ic JOIN corona.produtos pc ON pc.id = ic.produto_id "
                    + "WHERE ic.compra_id = c.id AND pc.categoria_id = :categoria)", "categoria", categoria);
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeCompra(rs.getObject("id", UUID.class), rs.getObject("data_compra", LocalDate.class),
                        rs.getString("numero"), rs.getObject("fornecedor_id", UUID.class), rs.getString("fornecedor"),
                        rs.getLong("itens"), rs.getLong("pecas"), reais(rs, "total"), rs.getString("situacao"),
                        "ESTORNADA".equals(rs.getString("situacao")), rs.getString("estorno_motivo"),
                        instante(rs, "estornada_em"), rs.getString("usuario")));
    }

    // --- 8. compras por fornecedor (total por fornecedor e geral) ---

    /**
     * Uma linha por fornecedor, somada dos ITENS das compras (por isso os filtros de categoria e de produto
     * valem aqui item a item). As compras estornadas ficam em colunas próprias e FORA do total.
     */
    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeFornecedorNasCompras> comprasPorFornecedor(Filtros f) {
        Consulta base = new Consulta("", "", "", List.of());
        switch (situacaoDaCompra(f)) {
            case "REGISTRADAS" -> base.onde("c.situacao = 'REGISTRADA'");
            case "ESTORNADAS" -> base.onde("c.situacao = 'ESTORNADA'");
            default -> {
            }
        }
        uuid(base, f.fornecedorId(), "fornecedor", "c.fornecedor_id = :fornecedor");
        uuid(base, f.usuarioId(), "usuario", "c.usuario_id = :usuario");
        uuid(base, f.categoriaId(), "categoria", "p.categoria_id = :categoria");
        uuid(base, f.produtoId(), "produto", "p.id = :produto");
        periodoEmDatas(base, "c.data_compra", f);
        String limpo = MotorDeRelatorio.limparBusca(f.busca());
        if (!limpo.isEmpty()) {
            base.onde("(lower(forn.razao_social) LIKE :texto OR lower(coalesce(c.numero, '')) LIKE :texto OR "
                    + BUSCA_DE_PRODUTO + ")", "texto", "%" + limpo + "%");
        }
        String registrada = "c.situacao = 'REGISTRADA'";
        String estornada = "c.situacao = 'ESTORNADA'";
        String de = """
                (SELECT forn.id AS fornecedor_id, forn.razao_social AS fornecedor,
                        count(DISTINCT c.id) FILTER (WHERE %1$s) AS compras,
                        coalesce(sum(i.quantidade) FILTER (WHERE %1$s), 0) AS pecas,
                        coalesce(sum(i.total) FILTER (WHERE %1$s), 0) AS total,
                        count(DISTINCT c.id) FILTER (WHERE %2$s) AS compras_estornadas,
                        coalesce(sum(i.total) FILTER (WHERE %2$s), 0) AS total_estornado
                   FROM corona.itens_compra i
                   JOIN corona.compras c ON c.id = i.compra_id
                   JOIN corona.fornecedores forn ON forn.id = c.fornecedor_id
                   JOIN corona.produtos p ON p.id = i.produto_id%3$s
                  GROUP BY forn.id, forn.razao_social) g""".formatted(registrada, estornada, base.clausulaWhere());
        Consulta c = new Consulta("g.fornecedor_id, g.fornecedor, g.compras, g.pecas, g.total, g.compras_estornadas, "
                + "g.total_estornado", de, "lower(g.fornecedor), g.fornecedor_id", List.of(
                        contagem(TODOS, "Fornecedores", null),
                        pecas("geral", "Total geral das compras registradas", null, "g.pecas", "g.total"),
                        somaDe("estornadas", "Compras estornadas (fora do total)", "g.compras_estornadas > 0",
                                "g.total_estornado")));
        c.parametros.addValues(base.parametros.getValues());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeFornecedorNasCompras(rs.getObject("fornecedor_id", UUID.class),
                        rs.getString("fornecedor"), rs.getLong("compras"), rs.getLong("pecas"), reais(rs, "total"),
                        rs.getLong("compras_estornadas"), reais(rs, "total_estornado")));
    }

    // --- 9. itens de uma compra ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeItemDeCompra> itensDaCompra(UUID compraId, Filtros f) {
        Integer existe = jdbc.queryForObject("SELECT count(*) FROM corona.compras WHERE id = :id",
                new MapSqlParameterSource("id", compraId), Integer.class);
        if (existe == null || existe == 0) {
            throw ServicoDeCadastro.naoEncontrado();
        }
        Consulta c = new Consulta(PRODUTO_COLUNAS + """
                , i.id AS item_id, i.compra_id, c.data_compra, c.numero, c.situacao, fc.razao_social AS fornecedor_da_compra,
                i.quantidade, i.custo_unitario, i.total""",
                "corona.itens_compra i JOIN corona.compras c ON c.id = i.compra_id "
                        + "JOIN corona.fornecedores fc ON fc.id = c.fornecedor_id "
                        + "JOIN corona.produtos p ON p.id = i.produto_id" + PRODUTO_JUNCOES,
                "lower(p.nome), p.id", List.of(
                        contagem(TODOS, "Itens", null),
                        pecas("total", "Peças e total da compra", null, "i.quantidade", "i.total")));
        c.onde("i.compra_id = :compra", "compra", compraId);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeItemDeCompra(rs.getObject("item_id", UUID.class), rs.getObject("compra_id", UUID.class),
                        rs.getObject("data_compra", LocalDate.class), rs.getString("numero"),
                        rs.getString("fornecedor_da_compra"), rs.getString("situacao"),
                        rs.getObject("produto_id", UUID.class), rs.getString("produto_nome"), rs.getString("referencia"),
                        rs.getString("codigo"), rs.getString("foto_principal_hash"), rs.getInt("quantidade"),
                        reais(rs, "custo_unitario"), reais(rs, "total")));
    }

    // --- 10. histórico de custo ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeHistoricoDeCusto> historicoDeCusto(Filtros f) {
        Consulta c = new Consulta(PRODUTO_COLUNAS + """
                , h.id, h.compra_id, cp.numero, h.data_compra, fc.razao_social AS fornecedor_da_compra,
                (cp.situacao = 'ESTORNADA') AS estornada, h.custo, h.custo_anterior,
                (h.custo - h.custo_anterior) AS variacao,
                (CASE WHEN h.custo_anterior > 0 THEN round((h.custo - h.custo_anterior) * 100 / h.custo_anterior, 2) END)
                    AS variacao_percentual""",
                "corona.historico_custo h JOIN corona.compras cp ON cp.id = h.compra_id "
                        + "JOIN corona.fornecedores fc ON fc.id = cp.fornecedor_id "
                        + "JOIN corona.produtos p ON p.id = h.produto_id" + PRODUTO_JUNCOES,
                "lower(p.nome), p.id, h.ordem DESC", List.of(
                        contagem(TODOS, "Custos registrados", null),
                        contagem("aumentos", "Aumentos de custo", "h.custo > h.custo_anterior"),
                        contagem("reducoes", "Reduções de custo", "h.custo < h.custo_anterior"),
                        contagem("primeiros", "Primeiro custo do produto", "h.custo_anterior IS NULL")));
        situacaoDoProduto(c, f.situacao(), "TODOS");
        doProduto(c, f);
        uuid(c, f.usuarioId(), "usuario", "cp.usuario_id = :usuario");
        periodoEmDatas(c, "h.data_compra", f);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeHistoricoDeCusto(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class),
                        rs.getString("produto_nome"), rs.getString("referencia"), rs.getString("codigo"),
                        rs.getString("foto_principal_hash"), rs.getObject("compra_id", UUID.class), rs.getString("numero"),
                        rs.getObject("data_compra", LocalDate.class), rs.getString("fornecedor_da_compra"),
                        rs.getBoolean("estornada"), reais(rs, "custo"), reais(rs, "custo_anterior"),
                        reais(rs, "variacao"), reais(rs, "variacao_percentual")));
    }
}
