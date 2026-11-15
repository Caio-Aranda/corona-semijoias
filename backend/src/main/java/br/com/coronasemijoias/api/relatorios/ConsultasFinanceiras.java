package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.emTresMoedas;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.dolar;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.guarani;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatórios financeiros da dona (tarefa 7.12, §12): acerto por vendedor e semana, faltas e pendências, esperado x contado,
 * contas a receber e a pagar, inadimplência, fluxo de caixa, recebimentos por forma e comissões. Só leitura, no motor dos
 * demais relatórios: linhas, rodapé e detalhamento saem das MESMAS condições, e a soma é feita no banco em NUMERIC.
 *
 * <p>Tudo é gravado em Real. Dólar e Guarani só aparecem na tela, pela cotação do dia de cada lançamento: a gravada no
 * fechamento, para o que nasce do acerto (ADR-0006, item 11), e a do dia em que o registro nasceu, para o resto. O rodapé
 * soma as linhas já convertidas. Nenhum custo, cheque, cartão ou entrada aparece aqui.
 */
@Service
public class ConsultasFinanceiras {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final String TODOS = "true";

    /** Um filtro só para todos os relatórios; cada relatório usa os campos que fazem sentido para ele. */
    public record Filtros(String busca, String vendedorId, String semanaId, String clienteId, String fornecedorId,
            String compraId, String produtoId, String tipo, String situacao, String devedor, String forma, String origem,
            String momento, LocalDate de, LocalDate ate, String recorte, Integer pagina, Integer tamanho) {
    }

    public record LinhaDeAcerto(UUID id, UUID vendedorId, String vendedor, UUID semanaId, int semana, Instant fechadoEm,
            BigDecimal vendidoBruto, BigDecimal descontos, BigDecimal devolvido, BigDecimal vendidoLiquido,
            BigDecimal comissao, BigDecimal percentual, BigDecimal faltasCobradas, BigDecimal liquido,
            BigDecimal recebidoNoAcerto, BigDecimal ficouDevendo, BigDecimal pagoAoVendedor, BigDecimal liquidoEmDolar,
            BigDecimal liquidoEmGuarani, LocalDate dataDaCotacao) {
    }

    public record LinhaDePendencia(UUID id, UUID acertoId, UUID vendedorId, String vendedor, int semana, String tipo,
            String situacao, String produto, Integer quantidade, BigDecimal valorEmReais, BigDecimal valorEmDolar,
            BigDecimal valorEmGuarani, LocalDate dataDaCotacao) {
    }

    public record LinhaDeEsperadoEContado(UUID id, UUID acertoId, UUID vendedorId, String vendedor, int semana,
            UUID produtoId, String produto, String referencia, int esperado, int contadoBoa, int contadoDefeito, int falta,
            int defeitoAprovado, BigDecimal valorDaFaltaEmReais, BigDecimal valorDaFaltaEmDolar,
            BigDecimal valorDaFaltaEmGuarani, LocalDate dataDaCotacao) {
    }

    public record LinhaDeConta(UUID id, String devedorTipo, String parte, UUID vendedorId, String vendedor, int parcela,
            int totalParcelas, LocalDate vencimento, String situacao, int diasDeAtraso, BigDecimal valorEmReais,
            BigDecimal pagoEmReais, BigDecimal canceladoEmReais, BigDecimal emAbertoEmReais, BigDecimal valorEmDolar,
            BigDecimal valorEmGuarani, LocalDate dataDaCotacao) {
    }

    public record LinhaDeCaixa(UUID id, LocalDate data, String tipo, String forma, String categoria, String origem,
            String descricao, boolean estorno, BigDecimal valorEmReais, BigDecimal valorEmDolar, BigDecimal valorEmGuarani,
            LocalDate dataDaCotacao) {
    }

    public record LinhaDeRecebimento(UUID id, UUID vendedorId, String vendedor, String forma, String momento,
            String aplicadoEm, Instant recebidoEm, boolean estorno, String motivo, BigDecimal valorEmReais,
            BigDecimal valorEmDolar, BigDecimal valorEmGuarani, LocalDate dataDaCotacao) {
    }

    public record LinhaDeComissao(UUID id, UUID vendedorId, String vendedor, UUID semanaId, int semana, Instant fechadoEm,
            Long vendidoEmGuarani, Integer metaEmGuarani, BigDecimal percentual, String situacao, BigDecimal base,
            BigDecimal comissaoEmReais, BigDecimal comissaoEmDolar, BigDecimal comissaoEmGuarani, LocalDate dataDaCotacao) {
    }

    // --- pedaços de SQL ---

    private static final String COTACAO_GRAVADA = "LEFT JOIN corona.cotacoes ct ON ct.id = a.cotacao_id";

    private static String cotacaoDoDia(String data) {
        return "LEFT JOIN LATERAL (SELECT c.data_cotacao, c.usd_venda, c.pyg_venda FROM corona.cotacoes c "
                + "WHERE c.data_cotacao <= " + data + " ORDER BY c.data_cotacao DESC LIMIT 1) ct ON true";
    }

    private static final String DIA_DE_CRIACAO = "(cr.created_at AT TIME ZONE 'America/Sao_Paulo')::date";

    private static final String DE_ACERTO = "corona.acertos a JOIN corona.vendedores v ON v.id = a.vendedor_id "
            + "JOIN corona.semanas s ON s.id = a.semana_id " + COTACAO_GRAVADA;

    private static final String DE_CONTA_A_RECEBER = "corona.contas_receber cr LEFT JOIN corona.vendas vd ON vd.id = cr.venda_id "
            + "LEFT JOIN corona.clientes cl ON cl.id = cr.cliente_id "
            + "LEFT JOIN corona.vendedores v ON v.id = coalesce(cr.vendedor_id, vd.vendedor_id) "
            + cotacaoDoDia(DIA_DE_CRIACAO);

    private static final String EM_ABERTO = "(cr.valor - cr.valor_pago - cr.valor_cancelado)";
    private static final String NAO_PAGA = "cr.status IN ('ABERTA', 'PARCIAL')";

    private final MotorDeRelatorio motor;
    private final Clock relogio;

    ConsultasFinanceiras(MotorDeRelatorio motor, Clock relogio) {
        this.motor = motor;
        this.relogio = relogio;
    }

    // --- filtros comuns ---

    private static boolean preenchido(String v) {
        return v != null && !v.isBlank();
    }

    private static String escolha(String pedida, List<String> validas, String campo, String mensagem) {
        if (!preenchido(pedida)) {
            return null;
        }
        String s = pedida.strip().toUpperCase();
        if (!validas.contains(s)) {
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

    private static void periodoEmDias(Consulta c, String coluna, LocalDate de, LocalDate ate) {
        MotorDeRelatorio.conferirPeriodo(de, ate);
        if (de != null) {
            c.onde(coluna + " >= :diaDe", "diaDe", de);
        }
        if (ate != null) {
            c.onde(coluna + " <= :diaAte", "diaAte", ate);
        }
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime v = rs.getObject(coluna, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static DefinicaoDeTotal soma(String chave, String rotulo, String predicado, String valor) {
        return emTresMoedas(chave, rotulo, predicado, null, valor, dolar(valor), guarani(valor));
    }

    private <L> RelatorioDeCadastro<L> executar(Consulta c, Filtros f, org.springframework.jdbc.core.RowMapper<L> linha) {
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), linha);
    }

    // --- 1. acerto por vendedor e semana ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeAcerto> acertos(Filtros f) {
        List<DefinicaoDeTotal> totais = List.of(
                soma("vendido", "Vendido", TODOS, "a.vendido_bruto"),
                soma("descontos", "Descontos concedidos", TODOS, "a.descontos"),
                soma("devolvido", "Devolvido", TODOS, "a.devolvido"),
                soma("comissao", "Comissão do vendedor", TODOS, "a.comissao"),
                soma("faltas", "Faltas cobradas", TODOS, "a.faltas_cobradas"),
                soma("liquido", "Valor do acerto", TODOS, "a.liquido"),
                soma("recebido", "Recebido no acerto", TODOS, "a.recebido_no_acerto"),
                soma("devendo", "Ficou devendo", TODOS, "a.ficou_devendo"));
        Consulta c = new Consulta("a.id, a.vendedor_id, v.nome AS vendedor, a.semana_id, s.numero AS semana, a.fechado_em, "
                + "a.vendido_bruto, a.descontos, a.devolvido, a.vendido_liquido, a.comissao, a.percentual_comissao, "
                + "a.faltas_cobradas, a.liquido, a.recebido_no_acerto, a.ficou_devendo, a.pago_ao_vendedor, "
                + dolar("a.liquido") + " AS dolar, " + guarani("a.liquido") + " AS guarani, ct.data_cotacao", DE_ACERTO,
                "a.fechado_em DESC, a.id", totais);
        c.onde("a.status = 'ACERTADO'");
        uuid(c, f.vendedorId(), "vendedor", "a.vendedor_id = :vendedor");
        uuid(c, f.semanaId(), "semana", "a.semana_id = :semana");
        c.periodo("a.fechado_em", f.de(), f.ate());
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.busca(f.busca(), "v.nome");
        return executar(c, f, (rs, n) -> new LinhaDeAcerto(rs.getObject("id", UUID.class),
                rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getObject("semana_id", UUID.class),
                rs.getInt("semana"), instante(rs, "fechado_em"), MotorDeRelatorio.reais(rs, "vendido_bruto"),
                MotorDeRelatorio.reais(rs, "descontos"), MotorDeRelatorio.reais(rs, "devolvido"),
                MotorDeRelatorio.reais(rs, "vendido_liquido"), MotorDeRelatorio.reais(rs, "comissao"),
                rs.getBigDecimal("percentual_comissao"), MotorDeRelatorio.reais(rs, "faltas_cobradas"),
                MotorDeRelatorio.reais(rs, "liquido"), MotorDeRelatorio.reais(rs, "recebido_no_acerto"),
                MotorDeRelatorio.reais(rs, "ficou_devendo"), MotorDeRelatorio.reais(rs, "pago_ao_vendedor"),
                rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"), rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- 2. faltas e pendências ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDePendencia> pendencias(Filtros f) {
        String valor = "p.valor";
        List<DefinicaoDeTotal> totais = List.of(
                soma("geral", "Todas as pendências", TODOS, valor),
                soma("abertas", "Ainda em aberto", "p.status = 'ABERTA'", valor),
                soma("cobradas", "Cobradas do vendedor", "p.status = 'COBRADA'", valor),
                soma("assumidas", "A loja assumiu", "p.status = 'ASSUMIDA_PELA_LOJA'", valor),
                soma("encontradas", "Peças encontradas", "p.status = 'ENCONTRADA'", valor));
        Consulta c = new Consulta("p.id, p.acerto_id, p.vendedor_id, v.nome AS vendedor, s.numero AS semana, p.tipo, "
                + "p.status, pr.nome AS produto, p.quantidade, p.valor, " + dolar(valor) + " AS dolar, " + guarani(valor)
                + " AS guarani, ct.data_cotacao",
                "corona.pendencias_acerto p JOIN corona.acertos a ON a.id = p.acerto_id "
                        + "JOIN corona.vendedores v ON v.id = p.vendedor_id JOIN corona.semanas s ON s.id = a.semana_id "
                        + "LEFT JOIN corona.produtos pr ON pr.id = p.produto_id " + COTACAO_GRAVADA,
                "a.fechado_em DESC NULLS LAST, p.id", totais);
        c.onde("a.status = 'ACERTADO'");
        uuid(c, f.vendedorId(), "vendedor", "p.vendedor_id = :vendedor");
        uuid(c, f.semanaId(), "semana", "a.semana_id = :semana");
        uuid(c, f.produtoId(), "produto", "p.produto_id = :produto");
        String tipo = escolha(f.tipo(), List.of("FALTA", "DEFEITO", "DEVOLUCAO_NAO_ACEITA", "CREDITO_SEM_SALDO_NAO_ACEITO"),
                "tipo", "Escolha falta, defeito, devolução não aceita ou crédito sem saldo.");
        if (tipo != null) {
            c.onde("p.tipo = :tipo", "tipo", tipo);
        }
        String situacao = escolha(f.situacao(), List.of("ABERTA", "COBRADA", "ASSUMIDA_PELA_LOJA", "ENCONTRADA", "APROVADA",
                "RECUSADA"), "situacao", "Escolha uma das situações da lista.");
        if (situacao != null) {
            c.onde("p.status = :situacao", "situacao", situacao);
        }
        c.periodo("a.fechado_em", f.de(), f.ate());
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.busca(f.busca(), "v.nome", "pr.nome");
        return executar(c, f, (rs, n) -> new LinhaDePendencia(rs.getObject("id", UUID.class),
                rs.getObject("acerto_id", UUID.class), rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"),
                rs.getInt("semana"), rs.getString("tipo"), rs.getString("status"), rs.getString("produto"),
                (Integer) rs.getObject("quantidade"), MotorDeRelatorio.reais(rs, "valor"), rs.getBigDecimal("dolar"),
                rs.getBigDecimal("guarani"), rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- 3. esperado x contado ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeEsperadoEContado> esperadoEContado(Filtros f) {
        String valorFalta = "(i.falta * coalesce(i.preco_remessa, 0))";
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("esperado", "Peças esperadas", TODOS, "i.esperado", null, null, null),
                emTresMoedas("contado", "Peças contadas", TODOS, "i.contado_boa + i.contado_defeito", null, null, null),
                emTresMoedas("faltas", "Peças que faltaram", "i.falta > 0", "i.falta", valorFalta, dolar(valorFalta),
                        guarani(valorFalta)));
        Consulta c = new Consulta("i.id, i.acerto_id, a.vendedor_id, v.nome AS vendedor, s.numero AS semana, i.produto_id, "
                + "p.nome AS produto, p.referencia, i.esperado, i.contado_boa, i.contado_defeito, i.falta, "
                + "i.defeito_aprovado, " + valorFalta + " AS valor_falta, " + dolar(valorFalta) + " AS dolar, "
                + guarani(valorFalta) + " AS guarani, ct.data_cotacao",
                "corona.itens_acerto i JOIN corona.acertos a ON a.id = i.acerto_id "
                        + "JOIN corona.vendedores v ON v.id = a.vendedor_id JOIN corona.semanas s ON s.id = a.semana_id "
                        + "JOIN corona.produtos p ON p.id = i.produto_id " + COTACAO_GRAVADA,
                "a.fechado_em DESC, lower(p.nome), i.id", totais);
        c.onde("a.status = 'ACERTADO'");
        uuid(c, f.vendedorId(), "vendedor", "a.vendedor_id = :vendedor");
        uuid(c, f.semanaId(), "semana", "a.semana_id = :semana");
        uuid(c, f.produtoId(), "produto", "i.produto_id = :produto");
        c.periodo("a.fechado_em", f.de(), f.ate());
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.busca(f.busca(), "v.nome", "p.nome", "p.referencia");
        return executar(c, f, (rs, n) -> new LinhaDeEsperadoEContado(rs.getObject("id", UUID.class),
                rs.getObject("acerto_id", UUID.class), rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"),
                rs.getInt("semana"), rs.getObject("produto_id", UUID.class), rs.getString("produto"),
                rs.getString("referencia"), rs.getInt("esperado"), rs.getInt("contado_boa"), rs.getInt("contado_defeito"),
                rs.getInt("falta"), rs.getInt("defeito_aprovado"), MotorDeRelatorio.reais(rs, "valor_falta"),
                rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"), rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- 4 e 5. contas a receber e inadimplência ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeConta> contasAReceber(Filtros f) {
        return contasAReceber(f, false);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeConta> inadimplencia(Filtros f) {
        return contasAReceber(f, true);
    }

    private RelatorioDeCadastro<LinhaDeConta> contasAReceber(Filtros f, boolean soVencidas) {
        List<DefinicaoDeTotal> totais = soVencidas
                ? List.of(
                        soma("vencido", "Total vencido", TODOS, EM_ABERTO),
                        soma("ate30", "Vencido há até 30 dias", "(:hoje - cr.vencimento) <= 30", EM_ABERTO),
                        soma("de31a60", "Vencido há 31 a 60 dias", "(:hoje - cr.vencimento) BETWEEN 31 AND 60", EM_ABERTO),
                        soma("mais60", "Vencido há mais de 60 dias", "(:hoje - cr.vencimento) > 60", EM_ABERTO))
                : List.of(
                        soma("valor", "Valor das parcelas", TODOS, "cr.valor"),
                        soma("pago", "Já recebido", "cr.valor_pago > 0", "cr.valor_pago"),
                        soma("cancelado", "Cancelado", "cr.valor_cancelado > 0", "cr.valor_cancelado"),
                        soma("aberto", "Falta receber", NAO_PAGA, EM_ABERTO),
                        soma("vencido", "Vencido", NAO_PAGA + " AND cr.vencimento < :hoje", EM_ABERTO));
        Consulta c = new Consulta("cr.id, cr.devedor_tipo, coalesce(cl.nome, vm.nome) AS parte, "
                + "coalesce(cr.vendedor_id, vd.vendedor_id) AS vendedor_id, v.nome AS vendedor, cr.parcela, "
                + "cr.total_parcelas, cr.vencimento, cr.status, cr.valor, cr.valor_pago, cr.valor_cancelado, "
                + EM_ABERTO + " AS em_aberto, " + dolar("cr.valor") + " AS dolar, " + guarani("cr.valor")
                + " AS guarani, ct.data_cotacao, (:hoje - cr.vencimento) AS atraso",
                DE_CONTA_A_RECEBER + " LEFT JOIN corona.vendedores vm ON vm.id = cr.vendedor_id",
                "cr.vencimento, cr.id", totais);
        c.parametros.addValue("hoje", hoje());
        c.onde("cr.deleted_at IS NULL");
        if (soVencidas) {
            c.onde(NAO_PAGA).onde("cr.vencimento < :hoje");
        }
        uuid(c, f.vendedorId(), "vendedor", "coalesce(cr.vendedor_id, vd.vendedor_id) = :vendedor");
        uuid(c, f.clienteId(), "cliente", "cr.cliente_id = :cliente");
        String devedor = escolha(f.devedor(), List.of("CLIENTE", "VENDEDOR"), "devedor", "Escolha cliente ou vendedor.");
        if (devedor != null) {
            c.onde("cr.devedor_tipo = :devedor", "devedor", devedor);
        }
        if (!soVencidas) {
            String situacao = escolha(f.situacao(), List.of("ABERTA", "PARCIAL", "PAGA", "CANCELADA", "VENCIDA"), "situacao",
                    "Escolha aberta, parcial, paga, cancelada ou vencida.");
            if ("VENCIDA".equals(situacao)) {
                c.onde(NAO_PAGA).onde("cr.vencimento < :hoje");
            } else if (situacao != null) {
                c.onde("cr.status = :situacao", "situacao", situacao);
            }
        }
        periodoEmDias(c, "cr.vencimento", f.de(), f.ate());
        c.busca(f.busca(), "cl.nome", "vm.nome", "v.nome");
        return executar(c, f, (rs, n) -> {
            String status = rs.getString("status");
            int atraso = rs.getInt("atraso");
            boolean aberta = "ABERTA".equals(status) || "PARCIAL".equals(status);
            return new LinhaDeConta(rs.getObject("id", UUID.class), rs.getString("devedor_tipo"), rs.getString("parte"),
                    rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getInt("parcela"),
                    rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class),
                    aberta && atraso > 0 ? "VENCIDA" : status, aberta ? Math.max(atraso, 0) : 0,
                    MotorDeRelatorio.reais(rs, "valor"), MotorDeRelatorio.reais(rs, "valor_pago"),
                    MotorDeRelatorio.reais(rs, "valor_cancelado"), MotorDeRelatorio.reais(rs, "em_aberto"),
                    rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"), rs.getObject("data_cotacao", LocalDate.class));
        });
    }

    // --- 6. contas a pagar ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeConta> contasAPagar(Filtros f) {
        String emAberto = "(cp.valor - cp.valor_pago - cp.valor_cancelado)";
        String naoPaga = "cp.status IN ('ABERTA', 'PARCIAL')";
        List<DefinicaoDeTotal> totais = List.of(
                soma("valor", "Valor das parcelas", TODOS, "cp.valor"),
                soma("pago", "Já pago", "cp.valor_pago > 0", "cp.valor_pago"),
                soma("cancelado", "Cancelado", "cp.valor_cancelado > 0", "cp.valor_cancelado"),
                soma("aberto", "Falta pagar", naoPaga, emAberto),
                soma("vencido", "Vencido", naoPaga + " AND cp.vencimento < :hoje", emAberto));
        Consulta c = new Consulta("cp.id, 'FORNECEDOR' AS devedor_tipo, f.razao_social AS parte, cp.parcela, "
                + "cp.total_parcelas, cp.vencimento, cp.status, cp.valor, cp.valor_pago, cp.valor_cancelado, " + emAberto
                + " AS em_aberto, " + dolar("cp.valor") + " AS dolar, " + guarani("cp.valor")
                + " AS guarani, ct.data_cotacao, (:hoje - cp.vencimento) AS atraso",
                "corona.contas_pagar cp JOIN corona.fornecedores f ON f.id = cp.fornecedor_id "
                        + cotacaoDoDia("(cp.criada_em AT TIME ZONE 'America/Sao_Paulo')::date"),
                "cp.vencimento, cp.id", totais);
        c.parametros.addValue("hoje", hoje());
        c.onde("cp.deleted_at IS NULL");
        uuid(c, f.fornecedorId(), "fornecedor", "cp.fornecedor_id = :fornecedor");
        uuid(c, f.compraId(), "compra", "cp.compra_id = :compra");
        String situacao = escolha(f.situacao(), List.of("ABERTA", "PARCIAL", "PAGA", "CANCELADA", "VENCIDA"), "situacao",
                "Escolha aberta, parcial, paga, cancelada ou vencida.");
        if ("VENCIDA".equals(situacao)) {
            c.onde(naoPaga).onde("cp.vencimento < :hoje");
        } else if (situacao != null) {
            c.onde("cp.status = :situacao", "situacao", situacao);
        }
        periodoEmDias(c, "cp.vencimento", f.de(), f.ate());
        c.busca(f.busca(), "f.razao_social");
        return executar(c, f, (rs, n) -> {
            String status = rs.getString("status");
            int atraso = rs.getInt("atraso");
            boolean aberta = "ABERTA".equals(status) || "PARCIAL".equals(status);
            return new LinhaDeConta(rs.getObject("id", UUID.class), rs.getString("devedor_tipo"), rs.getString("parte"),
                    null, null, rs.getInt("parcela"), rs.getInt("total_parcelas"),
                    rs.getObject("vencimento", LocalDate.class), aberta && atraso > 0 ? "VENCIDA" : status,
                    aberta ? Math.max(atraso, 0) : 0, MotorDeRelatorio.reais(rs, "valor"),
                    MotorDeRelatorio.reais(rs, "valor_pago"), MotorDeRelatorio.reais(rs, "valor_cancelado"),
                    MotorDeRelatorio.reais(rs, "em_aberto"), rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"),
                    rs.getObject("data_cotacao", LocalDate.class));
        });
    }

    // --- 7. fluxo de caixa ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeCaixa> fluxoDeCaixa(Filtros f) {
        String valor = "l.valor";
        List<DefinicaoDeTotal> totais = List.of(
                soma("entradas", "Entradas", "l.tipo = 'ENTRADA'", valor),
                soma("saidas", "Saídas", "l.tipo = 'SAIDA'", valor),
                soma("saldo", "Saldo do período", TODOS, valor));
        Consulta c = new Consulta("l.id, l.data_lancamento, l.tipo, l.forma, cat.nome AS categoria, l.origem_tipo, "
                + "l.descricao, (l.estorna_id IS NOT NULL) AS estorno, l.valor, " + dolar(valor) + " AS dolar, "
                + guarani(valor) + " AS guarani, ct.data_cotacao",
                "corona.lancamentos_caixa l JOIN corona.categorias_caixa cat ON cat.id = l.categoria_id "
                        + cotacaoDoDia("l.data_lancamento"),
                "l.data_lancamento DESC, l.lancado_em DESC, l.id", totais);
        String vendedor = idOpcional(f.vendedorId(), "vendedor") == null ? null : f.vendedorId().strip();
        if (vendedor != null) {
            c.onde("(EXISTS (SELECT 1 FROM corona.recebimentos_vendedor rv WHERE rv.id = l.origem_id AND rv.vendedor_id = :vendedor) "
                    + "OR EXISTS (SELECT 1 FROM corona.acertos ac WHERE ac.id = l.origem_id AND ac.vendedor_id = :vendedor))",
                    "vendedor", UUID.fromString(vendedor));
        }
        String tipo = escolha(f.tipo(), List.of("ENTRADA", "SAIDA"), "tipo", "Escolha entrada ou saída.");
        if (tipo != null) {
            c.onde("l.tipo = :tipo", "tipo", tipo);
        }
        String forma = escolha(f.forma(), List.of("DINHEIRO", "PIX", "BOLETO"), "forma",
                "Escolha dinheiro, transferência ou boleto.");
        if (forma != null) {
            c.onde("l.forma = :forma", "forma", forma);
        }
        if (preenchido(f.origem())) {
            c.onde("l.origem_tipo = :origem", "origem", f.origem().strip().toUpperCase());
        }
        periodoEmDias(c, "l.data_lancamento", f.de(), f.ate());
        c.busca(f.busca(), "l.descricao", "cat.nome");
        return executar(c, f, (rs, n) -> new LinhaDeCaixa(rs.getObject("id", UUID.class),
                rs.getObject("data_lancamento", LocalDate.class), rs.getString("tipo"), rs.getString("forma"),
                rs.getString("categoria"), rs.getString("origem_tipo"), rs.getString("descricao"),
                rs.getBoolean("estorno"), MotorDeRelatorio.reais(rs, "valor"), rs.getBigDecimal("dolar"),
                rs.getBigDecimal("guarani"), rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- 8. recebimentos por forma ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeRecebimento> recebimentosPorForma(Filtros f) {
        String valor = "rv.valor";
        List<DefinicaoDeTotal> totais = List.of(
                soma("DINHEIRO", "Dinheiro", "rv.forma = 'DINHEIRO'", valor),
                soma("PIX", "Transferência", "rv.forma = 'PIX'", valor),
                soma("BOLETO", "Boleto", "rv.forma = 'BOLETO'", valor),
                soma("total", "Total recebido", TODOS, valor));
        Consulta c = new Consulta("rv.id, rv.vendedor_id, v.nome AS vendedor, rv.forma, rv.momento, rv.aplicado_em, "
                + "rv.recebido_em, (rv.estorna_id IS NOT NULL) AS estorno, rv.motivo, rv.valor, " + dolar(valor)
                + " AS dolar, " + guarani(valor) + " AS guarani, ct.data_cotacao",
                "corona.recebimentos_vendedor rv JOIN corona.vendedores v ON v.id = rv.vendedor_id "
                        + cotacaoDoDia("(rv.recebido_em AT TIME ZONE 'America/Sao_Paulo')::date"),
                "rv.recebido_em DESC, rv.id", totais);
        uuid(c, f.vendedorId(), "vendedor", "rv.vendedor_id = :vendedor");
        String forma = escolha(f.forma(), List.of("DINHEIRO", "PIX", "BOLETO"), "forma",
                "Escolha dinheiro, transferência ou boleto.");
        if (forma != null) {
            c.onde("rv.forma = :forma", "forma", forma);
        }
        String momento = escolha(f.momento(), List.of("ACERTO", "AVULSO"), "momento", "Escolha no acerto ou depois do acerto.");
        if (momento != null) {
            c.onde("rv.momento = :momento", "momento", momento);
        }
        c.periodo("rv.recebido_em", f.de(), f.ate());
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.busca(f.busca(), "v.nome");
        return executar(c, f, (rs, n) -> new LinhaDeRecebimento(rs.getObject("id", UUID.class),
                rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getString("forma"),
                rs.getString("momento"), rs.getString("aplicado_em"), instante(rs, "recebido_em"), rs.getBoolean("estorno"),
                rs.getString("motivo"), MotorDeRelatorio.reais(rs, "valor"), rs.getBigDecimal("dolar"),
                rs.getBigDecimal("guarani"), rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- 9. comissões ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeComissao> comissoes(Filtros f) {
        String valor = "a.comissao";
        List<DefinicaoDeTotal> totais = List.of(
                soma("comissao", "Comissão paga (abatida no acerto)", TODOS, valor),
                soma("bateuAMeta", "Comissão dos acertos que bateram a meta",
                        "a.vendido_para_meta_guarani >= a.meta_guarani", valor));
        Consulta c = new Consulta("a.id, a.vendedor_id, v.nome AS vendedor, a.semana_id, s.numero AS semana, a.fechado_em, "
                + "a.vendido_para_meta_guarani, a.meta_guarani, a.percentual_comissao, a.vendido_liquido, a.comissao, "
                + dolar(valor) + " AS dolar, " + guarani(valor) + " AS guarani, ct.data_cotacao", DE_ACERTO,
                "a.fechado_em DESC, a.id", totais);
        c.onde("a.status = 'ACERTADO'");
        uuid(c, f.vendedorId(), "vendedor", "a.vendedor_id = :vendedor");
        uuid(c, f.semanaId(), "semana", "a.semana_id = :semana");
        c.periodo("a.fechado_em", f.de(), f.ate());
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.busca(f.busca(), "v.nome");
        return executar(c, f, (rs, n) -> new LinhaDeComissao(rs.getObject("id", UUID.class),
                rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getObject("semana_id", UUID.class),
                rs.getInt("semana"), instante(rs, "fechado_em"), (Long) rs.getObject("vendido_para_meta_guarani"),
                (Integer) rs.getObject("meta_guarani"), rs.getBigDecimal("percentual_comissao"), "PAGA",
                MotorDeRelatorio.reais(rs, "vendido_liquido"), MotorDeRelatorio.reais(rs, "comissao"),
                rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani"), rs.getObject("data_cotacao", LocalDate.class)));
    }
}
