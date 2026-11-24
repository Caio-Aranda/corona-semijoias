package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Relatórios do vendedor (§12, "App do vendedor"; tarefa 4.3): só dados dele, sem custo. Venda estornada não conta.
 *
 * <p>Dólar e Guarani só para mostrar, pela cotação do dia de CADA lançamento (CLAUDE.md; ADR-0006, itens 7 e 10): a venda
 * pela cotação gravada nela (ADR-0011, item 10) e o pagamento pela cotação vigente no dia em que foi pago (horário de
 * Brasília). O valor em Dólar ou Guarani de uma linha e do total é a SOMA dos lançamentos já convertidos, nunca a
 * conversão do total em reais. Se algum lançamento da linha não tem cotação, a linha (e o total) fica só em Real.
 */
@Component
public class RelatoriosDoVendedor {

    /**
     * Uma linha do relatório: {@code id} e {@code nome} da chave (cliente, peça, forma ou dia). Os valores em Dólar e
     * Guarani são a soma dos lançamentos da linha, cada um pela sua cotação; vazios se faltou cotação.
     */
    public record Linha(String id, String nome, long quantidade, BigDecimal total, BigDecimal desconto,
            BigDecimal totalEmDolar, BigDecimal totalEmGuarani, BigDecimal descontoEmDolar, BigDecimal descontoEmGuarani) {
    }

    /**
     * Totais: Dólar e Guarani são a soma das linhas já convertidas (vazios se alguma linha ficou sem cotação).
     * {@code dataDaCotacao} vem sempre vazia: cada lançamento tem a sua cotação, não existe uma data só.
     */
    public record Totais(BigDecimal total, BigDecimal totalEmDolar, BigDecimal totalEmGuarani, LocalDate dataDaCotacao) {
    }

    public record Relatorio(List<Linha> linhas, Totais totais) {
    }

    public record Devedor(UUID clienteId, String clienteNome, BigDecimal vendido, BigDecimal pago, BigDecimal aReceber,
            BigDecimal vendidoEmDolar, BigDecimal vendidoEmGuarani, BigDecimal pagoEmDolar, BigDecimal pagoEmGuarani,
            BigDecimal aReceberEmDolar, BigDecimal aReceberEmGuarani) {
    }

    public record RelatorioAReceber(List<Devedor> clientes, Totais totais) {
    }

    /** Um lançamento lido do banco: a chave da linha, a quantidade que ele soma, o valor, o desconto e a cotação. */
    private record Lancamento(String chave, String nome, long quantidade, BigDecimal valor, BigDecimal desconto,
            BigDecimal usd, BigDecimal pyg) {
    }

    private final JdbcTemplate jdbc;

    RelatoriosDoVendedor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Vendas por cliente, por peça ou por dia, no período (datas em horário de Brasília). */
    public Relatorio vendas(UUID vendedorId, LocalDate de, LocalDate ate, String agrupar) {
        String grupo = agrupar == null ? "cliente" : agrupar;
        String sql = switch (grupo) {
            case "cliente" -> """
                    SELECT c.id::text AS chave, c.nome AS nome, 1 AS quantidade, v.total AS valor, v.desconto AS desconto,
                           ct.usd_venda, ct.pyg_venda
                      FROM corona.vendas v JOIN corona.clientes c ON c.id = v.cliente_id
                      LEFT JOIN corona.cotacoes ct ON ct.id = v.cotacao_id
                     WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND\s""" + periodo()
                    + " ORDER BY lower(c.nome), c.id, v.vendida_em, v.id";
            case "peca" -> """
                    SELECT p.id::text AS chave, p.nome AS nome, i.quantidade AS quantidade,
                           i.preco_unitario * i.quantidade - i.desconto AS valor, i.desconto AS desconto,
                           ct.usd_venda, ct.pyg_venda
                      FROM corona.vendas v JOIN corona.itens_venda i ON i.venda_id = v.id JOIN corona.produtos p ON p.id = i.produto_id
                      LEFT JOIN corona.cotacoes ct ON ct.id = v.cotacao_id
                     WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND\s""" + periodo()
                    + " ORDER BY lower(p.nome), p.id, v.vendida_em, i.id";
            case "dia" -> """
                    SELECT to_char((v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date, 'YYYY-MM-DD') AS chave,
                           to_char((v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date, 'DD/MM/YYYY') AS nome,
                           1 AS quantidade, v.total AS valor, v.desconto AS desconto, ct.usd_venda, ct.pyg_venda
                      FROM corona.vendas v
                      LEFT JOIN corona.cotacoes ct ON ct.id = v.cotacao_id
                     WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND\s""" + periodo()
                    + " ORDER BY 1, v.vendida_em, v.id";
            default -> throw Validacoes.recusar("agrupar", "Escolha agrupar por cliente, peça ou dia.");
        };
        List<Lancamento> lancamentos = jdbc.query(sql, (rs, n) -> lancamento(rs), vendedorId, de, de, ate, ate);
        return relatorio(lancamentos);
    }

    /**
     * O que o vendedor recebeu no período, por forma de pagamento (pagamentos menos reembolsos). O crédito do cliente
     * (forma CREDITO) não é dinheiro recebido e fica de fora (tarefa 6.10).
     */
    public Relatorio recebimentos(UUID vendedorId, LocalDate de, LocalDate ate) {
        List<Lancamento> lancamentos = jdbc.query("""
                SELECT p.forma AS chave, p.forma AS nome, CASE WHEN p.tipo = 'PAGAMENTO' THEN 1 ELSE 0 END AS quantidade,
                       p.valor AS valor, 0.00::numeric(14, 2) AS desconto, ct.usd_venda, ct.pyg_venda
                  FROM corona.pagamentos_cliente p JOIN corona.vendas v ON v.id = p.venda_id
                """ + COTACAO_DO_PAGAMENTO + """
                 WHERE v.vendedor_id = ? AND v.deleted_at IS NULL AND p.forma <> 'CREDITO'
                   AND (?::date IS NULL OR (p.pago_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?::date)
                   AND (?::date IS NULL OR (p.pago_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?::date)
                 ORDER BY p.forma, p.pago_em, p.id""", (rs, n) -> lancamento(rs), vendedorId, de, de, ate, ate);
        return relatorio(lancamentos);
    }

    /** Uma linha de "Devoluções e trocas": por motivo, o que voltou (devoluções e trocas juntas), cada uma pela sua cotação. */
    public record LinhaDeDevolucao(String motivo, long devolucoes, BigDecimal valor, BigDecimal valorEmDolar,
            BigDecimal valorEmGuarani) {
    }

    public record TotaisDeDevolucao(long devolucoes, long trocas, BigDecimal valorDevolvido, BigDecimal valorDevolvidoEmDolar,
            BigDecimal valorDevolvidoEmGuarani, BigDecimal emDinheiro, BigDecimal emDinheiroEmDolar,
            BigDecimal emDinheiroEmGuarani, BigDecimal emCredito, BigDecimal emCreditoEmDolar, BigDecimal emCreditoEmGuarani) {
    }

    public record RelatorioDeDevolucoes(List<LinhaDeDevolucao> linhas, TotaisDeDevolucao totais) {
    }

    /** Um cliente com crédito: o gerado, o usado e o que ainda está em aberto (cada crédito pela cotação do dia em que nasceu). */
    public record ClienteComCredito(UUID clienteId, String clienteNome, BigDecimal gerado, BigDecimal geradoEmDolar,
            BigDecimal geradoEmGuarani, BigDecimal usado, BigDecimal usadoEmDolar, BigDecimal usadoEmGuarani,
            BigDecimal emAberto, BigDecimal emAbertoEmDolar, BigDecimal emAbertoEmGuarani) {
    }

    public record TotaisDeCredito(BigDecimal gerado, BigDecimal geradoEmDolar, BigDecimal geradoEmGuarani, BigDecimal usado,
            BigDecimal usadoEmDolar, BigDecimal usadoEmGuarani, BigDecimal emAberto, BigDecimal emAbertoEmDolar,
            BigDecimal emAbertoEmGuarani) {
    }

    public record RelatorioDeCreditos(List<ClienteComCredito> clientes, TotaisDeCredito totais) {
    }

    /**
     * Minhas devoluções e trocas (tarefa 6.10): só as do vendedor, sem as canceladas, por motivo, no período (dias de
     * Brasília). A troca conta como uma devolução que voltou em crédito; o dinheiro é só o que voltou ao cliente.
     */
    public RelatorioDeDevolucoes devolucoes(UUID vendedorId, LocalDate de, LocalDate ate) {
        Map<String, Long> quantidades = new LinkedHashMap<>();
        Map<String, Soma> valores = new LinkedHashMap<>();
        Soma devolvido = new Soma();
        Soma dinheiro = new Soma();
        Soma credito = new Soma();
        long[] contas = new long[2];
        jdbc.query("""
                SELECT d.motivo, d.destino, d.valor_devolvido, d.valor_reembolsado, d.valor_em_credito, ct.usd_venda, ct.pyg_venda
                  FROM corona.devolucoes d LEFT JOIN corona.cotacoes ct ON ct.id = d.cotacao_id
                 WHERE d.vendedor_id = ? AND d.deleted_at IS NULL AND d.situacao <> 'CANCELADA'
                   AND (?::date IS NULL OR (d.devolvida_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?::date)
                   AND (?::date IS NULL OR (d.devolvida_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?::date)
                 ORDER BY d.devolvida_em, d.id""", rs -> {
                    String motivo = rs.getString("motivo");
                    BigDecimal usd = rs.getBigDecimal("usd_venda");
                    BigDecimal pyg = rs.getBigDecimal("pyg_venda");
                    quantidades.merge(motivo, 1L, Long::sum);
                    valores.computeIfAbsent(motivo, k -> new Soma()).somar(rs.getBigDecimal("valor_devolvido"), usd, pyg);
                    devolvido.somar(rs.getBigDecimal("valor_devolvido"), usd, pyg);
                    dinheiro.somar(rs.getBigDecimal("valor_reembolsado"), usd, pyg);
                    credito.somar(rs.getBigDecimal("valor_em_credito"), usd, pyg);
                    contas["TROCA".equals(rs.getString("destino")) ? 1 : 0]++;
                }, vendedorId, de, de, ate, ate);
        List<LinhaDeDevolucao> linhas = new ArrayList<>();
        for (Map.Entry<String, Soma> e : valores.entrySet()) {
            Soma v = e.getValue();
            linhas.add(new LinhaDeDevolucao(e.getKey(), quantidades.get(e.getKey()), v.reais(), v.dolar(), v.guarani()));
        }
        return new RelatorioDeDevolucoes(linhas, new TotaisDeDevolucao(contas[0], contas[1], devolvido.reais(), devolvido.dolar(),
                devolvido.guarani(), dinheiro.reais(), dinheiro.dolar(), dinheiro.guarani(), credito.reais(), credito.dolar(),
                credito.guarani()));
    }

    /** O crédito dos clientes do vendedor (os que ele pode usar): gerado, usado e em aberto, sem os cancelados. */
    public RelatorioDeCreditos creditos(UUID vendedorId) {
        Map<UUID, String> nomes = new LinkedHashMap<>();
        Map<UUID, Soma[]> somas = new LinkedHashMap<>();
        Soma[] total = {new Soma(), new Soma(), new Soma()};
        jdbc.query("""
                SELECT cr.cliente_id, c.nome, cr.valor, cr.valor_utilizado, ct.usd_venda, ct.pyg_venda
                  FROM corona.creditos_cliente cr JOIN corona.clientes c ON c.id = cr.cliente_id
                  LEFT JOIN corona.cotacoes ct ON ct.id = cr.cotacao_id
                 WHERE cr.vendedor_id = ? AND cr.deleted_at IS NULL AND cr.situacao <> 'CANCELADO'
                 ORDER BY lower(c.nome), c.id, cr.gerado_em, cr.id""", rs -> {
                    UUID cliente = rs.getObject("cliente_id", UUID.class);
                    nomes.putIfAbsent(cliente, rs.getString("nome"));
                    BigDecimal valor = rs.getBigDecimal("valor");
                    BigDecimal usado = rs.getBigDecimal("valor_utilizado");
                    BigDecimal usd = rs.getBigDecimal("usd_venda");
                    BigDecimal pyg = rs.getBigDecimal("pyg_venda");
                    Soma[] linha = somas.computeIfAbsent(cliente, k -> new Soma[] {new Soma(), new Soma(), new Soma()});
                    for (Soma[] alvo : new Soma[][] {linha, total}) {
                        alvo[0].somar(valor, usd, pyg);
                        alvo[1].somar(usado, usd, pyg);
                        alvo[2].somar(valor.subtract(usado), usd, pyg);
                    }
                }, vendedorId);
        List<ClienteComCredito> clientes = new ArrayList<>();
        for (Map.Entry<UUID, String> n : nomes.entrySet()) {
            Soma[] s = somas.get(n.getKey());
            clientes.add(new ClienteComCredito(n.getKey(), n.getValue(), s[0].reais(), s[0].dolar(), s[0].guarani(), s[1].reais(),
                    s[1].dolar(), s[1].guarani(), s[2].reais(), s[2].dolar(), s[2].guarani()));
        }
        return new RelatorioDeCreditos(clientes, new TotaisDeCredito(total[0].reais(), total[0].dolar(), total[0].guarani(),
                total[1].reais(), total[1].dolar(), total[1].guarani(), total[2].reais(), total[2].dolar(), total[2].guarani()));
    }

    /**
     * Valores a receber dos clientes do vendedor: vendas (não estornadas) menos o devolvido (devoluções não canceladas,
     * tarefa 6.3) menos o pago, só quem deve.
     */
    public RelatorioAReceber aReceber(UUID vendedorId) {
        Map<UUID, String> nomes = new LinkedHashMap<>();
        Map<UUID, Soma> vendido = new LinkedHashMap<>();
        Map<UUID, Soma> pago = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.id, c.nome,
                       v.total - coalesce((SELECT sum(d.valor_devolvido) FROM corona.devolucoes d
                                            WHERE d.venda_id = v.id AND d.situacao <> 'CANCELADA' AND d.deleted_at IS NULL), 0)
                           AS total,
                       ct.usd_venda, ct.pyg_venda
                  FROM corona.vendas v JOIN corona.clientes c ON c.id = v.cliente_id
                  LEFT JOIN corona.cotacoes ct ON ct.id = v.cotacao_id
                 WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL
                 ORDER BY lower(c.nome), c.id, v.vendida_em, v.id""", rs -> {
                    UUID cliente = rs.getObject("id", UUID.class);
                    nomes.putIfAbsent(cliente, rs.getString("nome"));
                    vendido.computeIfAbsent(cliente, k -> new Soma()).somar(rs.getBigDecimal("total"),
                            rs.getBigDecimal("usd_venda"), rs.getBigDecimal("pyg_venda"));
                }, vendedorId);
        jdbc.query("""
                SELECT v.cliente_id, p.valor, ct.usd_venda, ct.pyg_venda
                  FROM corona.pagamentos_cliente p JOIN corona.vendas v ON v.id = p.venda_id
                """ + COTACAO_DO_PAGAMENTO + """
                 WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL
                 ORDER BY p.pago_em, p.id""", rs -> {
                    pago.computeIfAbsent(rs.getObject("cliente_id", UUID.class), k -> new Soma()).somar(rs.getBigDecimal("valor"),
                            rs.getBigDecimal("usd_venda"), rs.getBigDecimal("pyg_venda"));
                }, vendedorId);

        List<Devedor> clientes = new ArrayList<>();
        Soma total = new Soma();
        for (Map.Entry<UUID, String> c : nomes.entrySet()) {
            Soma v = vendido.get(c.getKey());
            Soma p = pago.getOrDefault(c.getKey(), new Soma());
            Soma devendo = v.menos(p);
            if (devendo.reais.signum() <= 0) {
                continue;
            }
            clientes.add(new Devedor(c.getKey(), c.getValue(), v.reais(), p.reais(), devendo.reais(), v.dolar(), v.guarani(),
                    p.dolar(), p.guarani(), devendo.dolar(), devendo.guarani()));
            total = total.mais(devendo);
        }
        return new RelatorioAReceber(clientes, total.totais());
    }

    // --- internos ---

    /** A cotação vigente no momento do pagamento (ADR-0006, item 15). Apelido {@code ct}. */
    private static final String COTACAO_DO_PAGAMENTO = """
              LEFT JOIN LATERAL (SELECT c.usd_venda, c.pyg_venda FROM corona.cotacoes c
                                  WHERE c.vigente_desde <= p.pago_em
                                  ORDER BY c.vigente_desde DESC, c.id DESC LIMIT 1) ct ON true
            """;

    private static String periodo() {
        return "(?::date IS NULL OR (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?::date) "
                + "AND (?::date IS NULL OR (v.vendida_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?::date)";
    }

    private static Lancamento lancamento(ResultSet rs) throws SQLException {
        return new Lancamento(rs.getString("chave"), rs.getString("nome"), rs.getLong("quantidade"), rs.getBigDecimal("valor"),
                rs.getBigDecimal("desconto"), rs.getBigDecimal("usd_venda"), rs.getBigDecimal("pyg_venda"));
    }

    /** Junta os lançamentos (já em ordem) em linhas pela chave, somando os valores convertidos um a um. */
    private static Relatorio relatorio(List<Lancamento> lancamentos) {
        Map<String, String> nomes = new LinkedHashMap<>();
        Map<String, Long> quantidades = new LinkedHashMap<>();
        Map<String, Soma> valores = new LinkedHashMap<>();
        Map<String, Soma> descontos = new LinkedHashMap<>();
        for (Lancamento l : lancamentos) {
            nomes.putIfAbsent(l.chave(), l.nome());
            quantidades.merge(l.chave(), l.quantidade(), Long::sum);
            valores.computeIfAbsent(l.chave(), k -> new Soma()).somar(l.valor(), l.usd(), l.pyg());
            descontos.computeIfAbsent(l.chave(), k -> new Soma()).somar(l.desconto(), l.usd(), l.pyg());
        }
        List<Linha> linhas = new ArrayList<>();
        Soma total = new Soma();
        for (Map.Entry<String, String> n : nomes.entrySet()) {
            Soma valor = valores.get(n.getKey());
            Soma desconto = descontos.get(n.getKey());
            linhas.add(new Linha(n.getKey(), n.getValue(), quantidades.get(n.getKey()), valor.reais(), desconto.reais(),
                    valor.dolar(), valor.guarani(), desconto.dolar(), desconto.guarani()));
            total = total.mais(valor);
        }
        return new Relatorio(linhas, total.totais());
    }

    /**
     * Soma de lançamentos em Real e, convertido cada um pela sua cotação, em Dólar e Guarani. Um lançamento sem cotação
     * deixa a soma "sem cotação": só vale o Real.
     */
    private static final class Soma {

        private BigDecimal reais = BigDecimal.ZERO.setScale(2);
        private BigDecimal dolar = BigDecimal.ZERO.setScale(ConversaoDeMoeda.ESCALA_DOLAR);
        private BigDecimal guarani = BigDecimal.ZERO.setScale(ConversaoDeMoeda.ESCALA_GUARANI);
        private boolean semCotacao;

        void somar(BigDecimal valor, BigDecimal usd, BigDecimal pyg) {
            reais = reais.add(valor);
            if (usd == null || pyg == null) {
                semCotacao = true;
                return;
            }
            dolar = dolar.add(ConversaoDeMoeda.paraDolar(valor, usd));
            guarani = guarani.add(ConversaoDeMoeda.paraGuarani(valor, pyg));
        }

        Soma mais(Soma outra) {
            Soma s = new Soma();
            s.reais = reais.add(outra.reais);
            s.dolar = dolar.add(outra.dolar);
            s.guarani = guarani.add(outra.guarani);
            s.semCotacao = semCotacao || outra.semCotacao;
            return s;
        }

        Soma menos(Soma outra) {
            Soma s = new Soma();
            s.reais = reais.subtract(outra.reais);
            s.dolar = dolar.subtract(outra.dolar);
            s.guarani = guarani.subtract(outra.guarani);
            s.semCotacao = semCotacao || outra.semCotacao;
            return s;
        }

        BigDecimal reais() {
            return reais.setScale(2);
        }

        BigDecimal dolar() {
            return semCotacao ? null : dolar;
        }

        BigDecimal guarani() {
            return semCotacao ? null : guarani;
        }

        Totais totais() {
            return new Totais(reais(), dolar(), guarani(), null);
        }
    }
}
