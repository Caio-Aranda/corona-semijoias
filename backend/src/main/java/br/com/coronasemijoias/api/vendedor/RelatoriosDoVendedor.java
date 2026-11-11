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

    /** O que o vendedor recebeu no período, por forma de pagamento (pagamentos menos reembolsos). */
    public Relatorio recebimentos(UUID vendedorId, LocalDate de, LocalDate ate) {
        List<Lancamento> lancamentos = jdbc.query("""
                SELECT p.forma AS chave, p.forma AS nome, CASE WHEN p.tipo = 'PAGAMENTO' THEN 1 ELSE 0 END AS quantidade,
                       p.valor AS valor, 0.00::numeric(14, 2) AS desconto, ct.usd_venda, ct.pyg_venda
                  FROM corona.pagamentos_cliente p JOIN corona.vendas v ON v.id = p.venda_id
                """ + COTACAO_DO_PAGAMENTO + """
                 WHERE v.vendedor_id = ? AND v.deleted_at IS NULL
                   AND (?::date IS NULL OR (p.pago_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?::date)
                   AND (?::date IS NULL OR (p.pago_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?::date)
                 ORDER BY p.forma, p.pago_em, p.id""", (rs, n) -> lancamento(rs), vendedorId, de, de, ate, ate);
        return relatorio(lancamentos);
    }

    /** Valores a receber dos clientes do vendedor: vendas (não estornadas) menos o pago, só quem deve. */
    public RelatorioAReceber aReceber(UUID vendedorId) {
        Map<UUID, String> nomes = new LinkedHashMap<>();
        Map<UUID, Soma> vendido = new LinkedHashMap<>();
        Map<UUID, Soma> pago = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.id, c.nome, v.total, ct.usd_venda, ct.pyg_venda
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

    /** A cotação vigente no dia do pagamento (horário de Brasília): a de maior data até aquele dia. Apelido {@code ct}. */
    private static final String COTACAO_DO_PAGAMENTO = """
              LEFT JOIN LATERAL (SELECT c.usd_venda, c.pyg_venda FROM corona.cotacoes c
                                  WHERE c.data_cotacao <= (p.pago_em AT TIME ZONE 'America/Sao_Paulo')::date
                                  ORDER BY c.data_cotacao DESC LIMIT 1) ct ON true
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
