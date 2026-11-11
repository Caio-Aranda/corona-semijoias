package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.emTresMoedas;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.COM_O_VENDEDOR;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.COTACAO_DO_ENVIO;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.NUMERO;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.dolar;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.guarani;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatórios da maleta da dona (tarefa 3.4, §12 e ADR-0010, itens 13 e 15): remessas por vendedor e semana,
 * reposições, saldo atual por maleta (peça a peça), mercadoria em maletas por vendedor e maletas em aberto e
 * atrasadas. Só leitura, no motor dos demais relatórios: linhas, rodapé e detalhamento saem das mesmas condições.
 *
 * <p>Rascunho nunca entra (não mexeu no estoque nem foi para o vendedor). Os valores são os congelados no envio;
 * cada linha leva o Dólar e o Guarani pela cotação do dia do ENVIO daquela maleta (nunca a de hoje) e o rodapé soma
 * as linhas já convertidas. Maleta enviada antes da primeira cotação do sistema fica sem Dólar e sem Guarani. Nenhum
 * custo aparece aqui.
 */
@Service
public class ConsultasDeMaleta {

    /** Depois de quantos dias do envio a maleta conta como atrasada, se a pessoa não escolher outro prazo. */
    public static final int DIAS_DE_ATRASO_PADRAO = 7;

    public record Filtros(String busca, String vendedorId, String pracaId, String semanaId, String produtoId,
            String categoriaId, String remessaId, String tipo, String situacao, LocalDate de, LocalDate ate,
            Integer diasDeAtraso, String recorte, Integer pagina, Integer tamanho) {
    }

    public record LinhaDeRemessa(UUID id, String numero, UUID vendedorId, String vendedor, UUID semanaId, int semana,
            UUID pracaId, String praca, String tipo, String status, boolean cancelada, String motivoDoCancelamento,
            Instant enviadaEm, int pecas, BigDecimal valorEmReais, BigDecimal valorEmDolar, BigDecimal valorEmGuarani,
            LocalDate dataDaCotacao) {
    }

    public record LinhaDeSaldo(UUID remessaId, String numero, UUID vendedorId, String vendedor, int semana, String praca,
            String tipo, String status, Instant enviadaEm, UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, int enviada, int devolvida, int saldo, BigDecimal precoCongelado,
            BigDecimal valorEmReais, BigDecimal valorEmDolar, BigDecimal valorEmGuarani, LocalDate dataDaCotacao) {
    }

    public record LinhaDeVendedor(UUID vendedorId, String vendedor, long maletasEmAberto, long maletasAtrasadas,
            long pecasEmAberto, BigDecimal valorEmReais, BigDecimal valorEmDolar, BigDecimal valorEmGuarani,
            BigDecimal limite, BigDecimal sobraDoLimite) {
    }

    public record LinhaDeMaletaEmAberto(UUID remessaId, String numero, UUID vendedorId, String vendedor, int semana,
            String praca, String tipo, String status, Instant enviadaEm, int diasDesdeOEnvio, boolean atrasada,
            int pecasEmAberto, BigDecimal valorEmReais, BigDecimal valorEmDolar, BigDecimal valorEmGuarani,
            LocalDate dataDaCotacao) {
    }

    private static final String DE_REMESSA = "corona.remessas r JOIN corona.vendedores v ON v.id = r.vendedor_id "
            + "JOIN corona.semanas s ON s.id = r.semana_id LEFT JOIN corona.pracas pr ON pr.id = r.praca_id "
            + COTACAO_DO_ENVIO;

    private static final String PECAS_DA_REMESSA = "r.total_pecas";
    private static final String VALOR_DA_REMESSA = "r.total_valor";

    private static final String MOVIMENTOS = """
            LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma,
                              coalesce(-sum(quantidade) FILTER (WHERE tipo = 'DEVOLUCAO_DONA'), 0) AS devolvida
                         FROM corona.movimentos_remessa GROUP BY remessa_id, produto_id) m
                   ON m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id""";

    private static final String SALDO = "(i.qtd_enviada + coalesce(m.soma, 0))";

    private static final String CODIGO = """
            (SELECT cd.codigo FROM corona.produto_codigos cd WHERE cd.produto_id = p.id AND cd.deleted_at IS NULL
              ORDER BY cd.principal DESC, cd.created_at LIMIT 1)""";

    private static final String BUSCA_DE_PRODUTO = """
            (lower(p.nome) LIKE :texto OR lower(coalesce(p.referencia, '')) LIKE :texto
             OR EXISTS (SELECT 1 FROM corona.produto_codigos cb WHERE cb.produto_id = p.id AND cb.deleted_at IS NULL
                        AND lower(cb.codigo) LIKE :texto))""";

    private final MotorDeRelatorio motor;
    private final Clock relogio;

    ConsultasDeMaleta(MotorDeRelatorio motor, Clock relogio) {
        this.motor = motor;
        this.relogio = relogio;
    }

    // --- filtros comuns ---

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

    /** Vendedor, praça da maleta, semana, período do envio e tipo (apelidos r, v, pr); rascunho nunca entra. */
    private static void daRemessa(Consulta c, Filtros f, boolean comBuscaDeVendedor) {
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.onde("r.deleted_at IS NULL").onde("r.status <> 'RASCUNHO'");
        uuid(c, f.vendedorId(), "vendedor", "r.vendedor_id = :vendedor");
        uuid(c, f.pracaId(), "praca", "r.praca_id = :praca");
        uuid(c, f.semanaId(), "semana", "r.semana_id = :semana");
        uuid(c, f.remessaId(), "remessa", "r.id = :remessa");
        c.periodo("r.enviada_em", f.de(), f.ate());
        if (preenchido(f.tipo())) {
            String tipo = escolha(f.tipo(), null, List.of("NOVA", "REPOSICAO"), "tipo", "Escolha nova ou reposição.");
            c.onde("r.tipo = :tipo", "tipo", tipo);
        }
        if (comBuscaDeVendedor) {
            c.busca(f.busca(), "v.nome", "pr.nome");
        }
    }

    /** Peça ou categoria pedida: a maleta só entra se tiver a peça (apelido r). */
    private static void comAPeca(Consulta c, Filtros f) {
        UUID produto = idOpcional(f.produtoId(), "produto");
        UUID categoria = idOpcional(f.categoriaId(), "categoria");
        if (produto != null || categoria != null) {
            StringBuilder e = new StringBuilder("EXISTS (SELECT 1 FROM corona.itens_remessa ip JOIN corona.produtos pp "
                    + "ON pp.id = ip.produto_id WHERE ip.remessa_id = r.id AND ip.deleted_at IS NULL");
            if (produto != null) {
                e.append(" AND pp.id = :produto");
                c.parametros.addValue("produto", produto);
            }
            if (categoria != null) {
                e.append(" AND pp.categoria_id = :categoria");
                c.parametros.addValue("categoria", categoria);
            }
            c.onde(e.append(")").toString());
        }
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime v = rs.getObject(coluna, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    /** O banco já arredondou (2 casas no Dólar, nenhuma no Guarani, meio para cima): aqui só se lê. */
    private static BigDecimal guaraniDe(ResultSet rs, String coluna) throws SQLException {
        return rs.getBigDecimal(coluna);
    }

    private static BigDecimal dolarDe(ResultSet rs, String coluna) throws SQLException {
        return rs.getBigDecimal(coluna);
    }

    // --- (a) remessas por vendedor e semana e (e) reposições ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeRemessa> remessas(Filtros f) {
        return remessasDoTipo(f, null);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeRemessa> reposicoes(Filtros f) {
        return remessasDoTipo(f, "REPOSICAO");
    }

    private RelatorioDeCadastro<LinhaDeRemessa> remessasDoTipo(Filtros f, String tipoFixo) {
        String d = dolar(VALOR_DA_REMESSA);
        String g = guarani(VALOR_DA_REMESSA);
        List<MotorDeRelatorio.DefinicaoDeTotal> totais = List.of(
                emTresMoedas("geral", "Maletas enviadas (valem no total)", "r.status <> 'CANCELADA'", PECAS_DA_REMESSA,
                        VALOR_DA_REMESSA, d, g),
                emTresMoedas("novas", "Maletas novas", "r.status <> 'CANCELADA' AND r.tipo = 'NOVA'", PECAS_DA_REMESSA,
                        VALOR_DA_REMESSA, d, g),
                emTresMoedas("reposicoes", "Reposições", "r.status <> 'CANCELADA' AND r.tipo = 'REPOSICAO'",
                        PECAS_DA_REMESSA, VALOR_DA_REMESSA, d, g),
                emTresMoedas("canceladas", "Maletas canceladas (fora do total)", "r.status = 'CANCELADA'",
                        PECAS_DA_REMESSA, VALOR_DA_REMESSA, d, g));
        Consulta c = new Consulta("r.id, " + NUMERO + " AS numero, r.vendedor_id, v.nome AS vendedor, r.semana_id, "
                + "s.numero AS semana, r.praca_id, pr.nome AS praca, r.tipo, r.status, r.motivo_cancelamento, "
                + "r.enviada_em, r.total_pecas AS pecas, r.total_valor AS valor, " + d + " AS dolar, " + g
                + " AS guarani, ct.data_cotacao", DE_REMESSA,
                "s.numero DESC, lower(v.nome), r.enviada_em DESC NULLS LAST, r.id", totais);
        daRemessa(c, f, true);
        comAPeca(c, f);
        if (tipoFixo != null) {
            c.onde("r.tipo = '" + tipoFixo + "'");
        }
        String situacao = escolha(f.situacao(), "REGISTRADAS", List.of("REGISTRADAS", "CANCELADAS", "TODAS", "ENVIADA",
                "CONFERIDA", "EM_ACERTO", "ACERTADA"), "situacao", "Escolha enviadas, canceladas ou todas.");
        switch (situacao) {
            case "REGISTRADAS" -> c.onde("r.status <> 'CANCELADA'");
            case "CANCELADAS" -> c.onde("r.status = 'CANCELADA'");
            case "TODAS" -> {
            }
            default -> c.onde("r.status = :status", "status", situacao);
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeRemessa(
                rs.getObject("id", UUID.class), rs.getString("numero"), rs.getObject("vendedor_id", UUID.class),
                rs.getString("vendedor"), rs.getObject("semana_id", UUID.class), rs.getInt("semana"),
                rs.getObject("praca_id", UUID.class), rs.getString("praca"), rs.getString("tipo"), rs.getString("status"),
                "CANCELADA".equals(rs.getString("status")), rs.getString("motivo_cancelamento"), instante(rs, "enviada_em"),
                rs.getInt("pecas"), MotorDeRelatorio.reais(rs, "valor"), dolarDe(rs, "dolar"), guaraniDe(rs, "guarani"),
                rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- (b) saldo atual por maleta, peça a peça ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeSaldo> saldoPorMaleta(Filtros f) {
        String valor = SALDO + " * i.preco_remessa";
        String valorEnviado = "i.qtd_enviada * i.preco_remessa";
        List<MotorDeRelatorio.DefinicaoDeTotal> totais = List.of(
                emTresMoedas("enviadas", "Peças enviadas", null, "i.qtd_enviada", valorEnviado, dolar(valorEnviado),
                        guarani(valorEnviado)),
                emTresMoedas("devolvidas", "Peças devolvidas à dona", "coalesce(m.devolvida, 0) <> 0",
                        "coalesce(m.devolvida, 0)", null, null, null),
                emTresMoedas("saldo", "Peças ainda com o vendedor", SALDO + " > 0", SALDO, valor, dolar(valor),
                        guarani(valor)));
        Consulta c = new Consulta("r.id AS remessa_id, " + NUMERO + " AS numero, r.vendedor_id, v.nome AS vendedor, "
                + "s.numero AS semana, pr.nome AS praca, r.tipo, r.status, r.enviada_em, p.id AS produto_id, p.nome, "
                + "p.referencia, p.foto_principal_hash, " + CODIGO + " AS codigo, i.qtd_enviada AS enviada, "
                + "coalesce(m.devolvida, 0) AS devolvida, " + SALDO + " AS saldo, i.preco_remessa, " + valor + " AS valor, "
                + dolar(valor) + " AS dolar, " + guarani(valor) + " AS guarani, ct.data_cotacao",
                "corona.itens_remessa i JOIN corona.remessas r ON r.id = i.remessa_id JOIN corona.vendedores v "
                        + "ON v.id = r.vendedor_id JOIN corona.semanas s ON s.id = r.semana_id LEFT JOIN corona.pracas pr "
                        + "ON pr.id = r.praca_id JOIN corona.produtos p ON p.id = i.produto_id " + MOVIMENTOS + " "
                        + COTACAO_DO_ENVIO,
                "s.numero DESC, lower(v.nome), r.enviada_em, r.id, lower(p.nome), p.id", totais);
        daRemessa(c, f, false);
        c.onde("i.deleted_at IS NULL").onde("r.status IN " + COM_O_VENDEDOR);
        uuid(c, f.produtoId(), "produto", "p.id = :produto");
        uuid(c, f.categoriaId(), "categoria", "p.categoria_id = :categoria");
        String limpo = MotorDeRelatorio.limparBusca(f.busca());
        if (!limpo.isEmpty()) {
            c.onde(BUSCA_DE_PRODUTO, "texto", "%" + limpo + "%");
        }
        switch (escolha(f.situacao(), "COM_SALDO", List.of("COM_SALDO", "ZERADAS", "TODAS"), "situacao",
                "Escolha com saldo, zeradas ou todas.")) {
            case "COM_SALDO" -> c.onde(SALDO + " > 0");
            case "ZERADAS" -> c.onde(SALDO + " = 0");
            default -> {
            }
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeSaldo(
                rs.getObject("remessa_id", UUID.class), rs.getString("numero"), rs.getObject("vendedor_id", UUID.class),
                rs.getString("vendedor"), rs.getInt("semana"), rs.getString("praca"), rs.getString("tipo"),
                rs.getString("status"), instante(rs, "enviada_em"), rs.getObject("produto_id", UUID.class),
                rs.getString("nome"), rs.getString("referencia"), rs.getString("codigo"),
                rs.getString("foto_principal_hash"), rs.getInt("enviada"), rs.getInt("devolvida"), rs.getInt("saldo"),
                MotorDeRelatorio.reais(rs, "preco_remessa"), MotorDeRelatorio.reais(rs, "valor"), dolarDe(rs, "dolar"),
                guaraniDe(rs, "guarani"), rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- o que está em aberto, por maleta (base de (c) e (d)) ---

    /**
     * Uma linha por maleta enviada com peça ainda com o vendedor: peças e valor em aberto a preço congelado, Dólar e
     * Guarani pela cotação do envio, dias desde o envio (calendário de Brasília) e se está atrasada.
     */
    private String emAbertoPorMaleta(Filtros f, Consulta dentro, int diasDeAtraso) {
        daRemessa(dentro, f, true);
        dentro.onde("r.status IN " + COM_O_VENDEDOR).onde("i.deleted_at IS NULL").onde(SALDO + " > 0");
        uuid(dentro, f.produtoId(), "produto", "p.id = :produto");
        uuid(dentro, f.categoriaId(), "categoria", "p.categoria_id = :categoria");
        LocalDate hoje = LocalDate.now(relogio.withZone(CotacaoVigente.BRASILIA));
        dentro.parametros.addValue("hoje", hoje).addValue("diasDeAtraso", diasDeAtraso);
        String valor = "sum(" + SALDO + " * i.preco_remessa)";
        String dias = "(:hoje - (r.enviada_em AT TIME ZONE 'America/Sao_Paulo')::date)";
        return "SELECT r.id AS remessa_id, " + NUMERO + " AS numero, r.vendedor_id, v.nome AS vendedor, "
                + "v.limite_consignacao, s.numero AS semana, pr.nome AS praca, r.tipo, r.status, r.enviada_em, "
                + dias + " AS dias, " + dias + " > :diasDeAtraso AS atrasada, sum(" + SALDO + ") AS pecas, " + valor
                + " AS valor, " + dolar(valor) + " AS dolar, " + guarani(valor) + " AS guarani, ct.data_cotacao "
                + "FROM corona.itens_remessa i JOIN corona.remessas r ON r.id = i.remessa_id "
                + "JOIN corona.vendedores v ON v.id = r.vendedor_id JOIN corona.semanas s ON s.id = r.semana_id "
                + "LEFT JOIN corona.pracas pr ON pr.id = r.praca_id JOIN corona.produtos p ON p.id = i.produto_id "
                + MOVIMENTOS + " " + COTACAO_DO_ENVIO + dentro.clausulaWhere() + " GROUP BY r.id, s.numero, v.id, pr.nome, "
                + "ct.data_cotacao, ct.usd_venda, ct.pyg_venda";
    }

    private static int diasDeAtraso(Integer pedido) {
        int d = pedido == null ? DIAS_DE_ATRASO_PADRAO : pedido;
        if (d < 0 || d > 365) {
            throw Validacoes.recusar("diasDeAtraso", "Escolha de 0 a 365 dias.");
        }
        return d;
    }

    // --- (d) maletas em aberto e atrasadas ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeMaletaEmAberto> maletasEmAberto(Filtros f) {
        List<MotorDeRelatorio.DefinicaoDeTotal> totais = List.of(
                emTresMoedas("emAberto", "Maletas em aberto", null, "x.pecas", "x.valor", "x.dolar", "x.guarani"),
                emTresMoedas("atrasadas", "Maletas atrasadas", "x.atrasada", "x.pecas", "x.valor", "x.dolar", "x.guarani"),
                emTresMoedas("noPrazo", "Maletas no prazo", "NOT x.atrasada", "x.pecas", "x.valor", "x.dolar",
                        "x.guarani"));
        Consulta dentro = new Consulta("", "", "", List.of());
        String base = emAbertoPorMaleta(f, dentro, diasDeAtraso(f.diasDeAtraso()));
        Consulta c = new Consulta("x.*", "(" + base + ") x", "x.atrasada DESC, x.enviada_em, x.remessa_id", totais);
        c.parametros.addValues(dentro.parametros.getValues());
        switch (escolha(f.situacao(), "TODAS", List.of("TODAS", "ATRASADAS", "NO_PRAZO"), "situacao",
                "Escolha todas, atrasadas ou no prazo.")) {
            case "ATRASADAS" -> c.onde("x.atrasada");
            case "NO_PRAZO" -> c.onde("NOT x.atrasada");
            default -> {
            }
        }
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeMaletaEmAberto(
                rs.getObject("remessa_id", UUID.class), rs.getString("numero"), rs.getObject("vendedor_id", UUID.class),
                rs.getString("vendedor"), rs.getInt("semana"), rs.getString("praca"), rs.getString("tipo"),
                rs.getString("status"), instante(rs, "enviada_em"), rs.getInt("dias"), rs.getBoolean("atrasada"),
                rs.getInt("pecas"), MotorDeRelatorio.reais(rs, "valor"), dolarDe(rs, "dolar"), guaraniDe(rs, "guarani"),
                rs.getObject("data_cotacao", LocalDate.class)));
    }

    // --- (c) mercadoria em maletas por vendedor ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendedor> mercadoriaPorVendedor(Filtros f) {
        List<MotorDeRelatorio.DefinicaoDeTotal> totais = List.of(
                emTresMoedas("emAberto", "Mercadoria em maletas", null, "y.pecas", "y.valor", "y.dolar", "y.guarani"),
                emTresMoedas("comAtraso", "Vendedores com maleta atrasada", "y.atrasadas > 0", "y.pecas", "y.valor",
                        "y.dolar", "y.guarani"),
                emTresMoedas("acimaDoLimite", "Vendedores acima do limite",
                        "y.limite IS NOT NULL AND y.valor_total > y.limite", "y.pecas", "y.valor", "y.dolar", "y.guarani"));
        Consulta dentro = new Consulta("", "", "", List.of());
        String porMaleta = emAbertoPorMaleta(f, dentro, diasDeAtraso(f.diasDeAtraso()));
        // O limite que sobra conta TUDO o que o vendedor tem em aberto, não só o recorte pedido nos filtros.
        String base = "SELECT w.vendedor_id, w.vendedor, count(*) AS maletas, count(*) FILTER (WHERE w.atrasada) AS atrasadas, "
                + "sum(w.pecas) AS pecas, sum(w.valor) AS valor, sum(w.dolar) AS dolar, sum(w.guarani) AS guarani, "
                + "w.limite_consignacao AS limite, "
                + "(SELECT coalesce(sum((i2.qtd_enviada + coalesce(m2.soma, 0)) * i2.preco_remessa), 0) "
                + "FROM corona.remessas r2 JOIN corona.itens_remessa i2 ON i2.remessa_id = r2.id AND i2.deleted_at IS NULL "
                + "LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma FROM corona.movimentos_remessa "
                + "GROUP BY remessa_id, produto_id) m2 ON m2.remessa_id = i2.remessa_id AND m2.produto_id = i2.produto_id "
                + "WHERE r2.vendedor_id = w.vendedor_id AND r2.deleted_at IS NULL AND r2.status IN " + COM_O_VENDEDOR
                + ") AS valor_total FROM (" + porMaleta + ") w GROUP BY w.vendedor_id, w.vendedor, w.limite_consignacao";
        Consulta c = new Consulta("y.*", "(" + base + ") y", "lower(y.vendedor), y.vendedor_id", totais);
        c.parametros.addValues(dentro.parametros.getValues());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            BigDecimal limite = MotorDeRelatorio.reais(rs, "limite");
            BigDecimal total = MotorDeRelatorio.reais(rs, "valor_total");
            return new LinhaDeVendedor(rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"),
                    rs.getLong("maletas"), rs.getLong("atrasadas"), rs.getLong("pecas"), MotorDeRelatorio.reais(rs, "valor"),
                    dolarDe(rs, "dolar"), guaraniDe(rs, "guarani"), limite,
                    limite == null ? null : limite.subtract(total).max(BigDecimal.ZERO.setScale(2)));
        });
    }
}
