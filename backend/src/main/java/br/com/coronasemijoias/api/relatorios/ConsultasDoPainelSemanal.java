package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.COTACAO_DO_ENVIO;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.NUMERO;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.dolar;
import static br.com.coronasemijoias.api.remessas.SqlDaMaleta.guarani;

import br.com.coronasemijoias.api.acertos.ServicoDeFechamento;
import br.com.coronasemijoias.api.acertos.ServicoDeFechamento.ResumoDaPrevia;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.cotacoes.Cotacao;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import br.com.coronasemijoias.api.relatorios.RelatorioDeCadastro.Total;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O painel semanal da dona, só a consulta (tarefa 8.7; ADR-0015, item 5; §12). Só leitura: junta, sem nenhuma fórmula nova, o que
 * já existe. Os <b>Pedidos</b> têm uma linha por vendedor na semana: o acerto gravado ({@code ACERTADO}, os mesmos números do
 * relatório de acertos), o acerto em andamento ({@code EM_ACERTO}, a prévia do fechamento da dona) ou as maletas ainda em aberto
 * (a prévia da comissão do vendedor); só estas duas últimas são "prévia", e a prévia é "de hoje": inclui o que o vendedor ainda
 * não acertou de semanas anteriores. Os <b>Totais</b> são os rodapés dos relatórios que já mostram cada número (maletas, acertos,
 * pendências, vendas, trocas, créditos e recebimentos), chamados com os mesmos filtros: o motor soma no banco. Em Java só
 * {@link Dinheiro} soma (a prévia e o Total) e só {@link ConversaoDeMoeda} converte. Nenhum custo, nenhuma gravação.
 */
@Service
public class ConsultasDoPainelSemanal {

    public static final String AVISO_DA_PREVIA = "A prévia é de hoje: inclui o que o vendedor ainda não acertou de semanas anteriores.";

    static final String ROTA_DE_ACERTOS = "/api/dona/relatorios/financeiro/acertos";
    static final String ROTA_DE_PENDENCIAS = "/api/dona/relatorios/financeiro/pendencias";
    static final String ROTA_DE_RECEBIMENTOS = "/api/dona/relatorios/financeiro/recebimentos-por-forma";
    static final String ROTA_DE_MALETAS = "/api/dona/relatorios/maleta/remessas";
    static final String ROTA_DE_VENDAS = "/api/dona/relatorios/vendas/vendas";
    static final String ROTA_DE_TROCAS = "/api/dona/relatorios/devolucoes/trocas";
    static final String ROTA_DE_CREDITOS = "/api/dona/relatorios/devolucoes/creditos";

    private static final List<String> SITUACOES = List.of("EM_ABERTO", "EM_ACERTO", "ACERTADO");

    // --- a resposta ---

    /** Um valor em Real, Dólar e Guarani (Dólar e Guarani vazios quando falta a cotação do dia). */
    public record ValorEmMoedas(BigDecimal emReais, BigDecimal emDolar, BigDecimal emGuarani) {

        static ValorEmMoedas de(BigDecimal reais, Cotacao cotacao) {
            if (reais == null) {
                return null;
            }
            return new ValorEmMoedas(reais, cotacao == null ? null : ConversaoDeMoeda.paraDolar(reais, cotacao.taxas().usdVenda()),
                    cotacao == null ? null : ConversaoDeMoeda.paraGuarani(reais, cotacao.taxas().pygVenda()));
        }

        static ValorEmMoedas de(Total t) {
            return new ValorEmMoedas(t.valorEmReais(), t.valorEmDolar(), t.valorEmGuarani());
        }

        /** A soma de valores já convertidos linha a linha; vazio em Dólar ou Guarani se alguma parcela estava vazia. */
        static ValorEmMoedas somar(List<ValorEmMoedas> valores) {
            List<BigDecimal> reais = new ArrayList<>();
            List<BigDecimal> dolares = new ArrayList<>();
            List<BigDecimal> guaranis = new ArrayList<>();
            boolean semDolar = false;
            boolean semGuarani = false;
            for (ValorEmMoedas v : valores) {
                reais.add(v.emReais());
                semDolar |= v.emDolar() == null;
                semGuarani |= v.emGuarani() == null;
                if (v.emDolar() != null) {
                    dolares.add(v.emDolar());
                }
                if (v.emGuarani() != null) {
                    guaranis.add(v.emGuarani());
                }
            }
            return new ValorEmMoedas(Dinheiro.somar(reais), semDolar ? null : Dinheiro.somar(dolares),
                    semGuarani ? null : Dinheiro.somar(guaranis).setScale(0));
        }

        static ValorEmMoedas zero() {
            return new ValorEmMoedas(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(0));
        }
    }

    public record SemanaDoPainel(UUID id, int numero, LocalDate inicio, LocalDate fim, String situacao) {
    }

    public record MaletaDaLinha(UUID id, String numero, String tipo, String situacao) {
    }

    /**
     * Uma linha por vendedor na semana. {@code previa}: a comissão e o líquido são previstos (nada foi gravado);
     * {@code semCotacao}: sem nenhuma cotação o sistema não calcula a prévia. {@code vendidasPecas} e {@code emFaltaPecas} só vêm
     * nas linhas acertadas (a conferência: enviado = vendido + devolvido + falta).
     */
    public record LinhaDoPedido(UUID acertoId, UUID vendedorId, String vendedor, UUID pracaId, String praca, String situacao,
            boolean previa, boolean semCotacao, Instant dataDoAcerto, LocalDate dataDaCotacao, List<MaletaDaLinha> maletas,
            long enviadoPecas, ValorEmMoedas enviado, long devolvidoPecas, ValorEmMoedas devolvido, ValorEmMoedas vendidoLiquido,
            BigDecimal percentualComissao, ValorEmMoedas comissao, ValorEmMoedas liquido, Long vendidasPecas, Long emFaltaPecas) {
    }

    /** De que relatório o número saiu e com que filtros: a tela abre os registros por lá. */
    public record OrigemDoNumero(String rota, Map<String, String> filtros) {
    }

    public record BlocoDeMaletasDoPainel(long quantidade, long pecas, ValorEmMoedas valor, OrigemDoNumero origem) {
    }

    public record BlocoDeAcerto(long quantidade, ValorEmMoedas vendido, ValorEmMoedas descontos, ValorEmMoedas devolvidoPeloCliente, ValorEmMoedas comissao,
            ValorEmMoedas faltasCobradas, ValorEmMoedas valorDoAcerto, ValorEmMoedas recebido, ValorEmMoedas ficouDevendo, long devolvidoALojaPecas,
            ValorEmMoedas devolvidoALoja, ValorEmMoedas pendenciasPagas, ValorEmMoedas pendenciasEmAberto, OrigemDoNumero origem, OrigemDoNumero origemDasPendencias) {
    }

    public record BlocoDaPrevia(long linhas, boolean semCotacao, ValorEmMoedas vendido, ValorEmMoedas comissaoPrevista, ValorEmMoedas liquidoPrevisto) {
    }

    public record BlocoDeVendas(long quantidade, ValorEmMoedas vendido, ValorEmMoedas descontos, ValorEmMoedas devolvido, ValorEmMoedas liquido, OrigemDoNumero origem) {
    }

    public record BlocoDeTrocas(long trocas, ValorEmMoedas valorDasTrocas, ValorEmMoedas diferencaAPagar, ValorEmMoedas ficouDeCredito, ValorEmMoedas creditosGerados,
            ValorEmMoedas creditosUsados, ValorEmMoedas creditosEmAberto, OrigemDoNumero origemDasTrocas, OrigemDoNumero origemDosCreditos) {
    }

    public record BlocoTotal(long maletas, long pecas, ValorEmMoedas enviado, long vendas, ValorEmMoedas vendido, ValorEmMoedas descontos, ValorEmMoedas liquido) {
    }

    public record BlocoRecebido(ValorEmMoedas dinheiro, ValorEmMoedas transferencia, ValorEmMoedas boleto, ValorEmMoedas total, OrigemDoNumero origem) {
    }

    public record TotaisDoPainel(BlocoDeMaletasDoPainel novasMaletas, BlocoDeMaletasDoPainel reposicoes, BlocoDeAcerto acerto, BlocoDaPrevia previa,
            BlocoDeVendas vendas, BlocoDeTrocas trocas, BlocoTotal total, BlocoRecebido recebidoNoAcerto) {
    }

    public record CotacaoDaPrevia(LocalDate data) {
    }

    public record PainelDaSemana(SemanaDoPainel semana, boolean previaIncluiSemanasAnteriores, String avisoDaPrevia,
            CotacaoDaPrevia cotacaoDaPrevia, List<LinhaDoPedido> pedidos, TotaisDoPainel totais) {
    }

    /** Os filtros do painel; tudo é opcional (sem semana, a que tem o dia de hoje em Brasília). */
    public record Filtros(String semanaId, String vendedorId, String pracaId, String situacao) {
    }

    // --- por dentro ---

    /** A linha antes de juntar as maletas e a prévia. */
    private record Base(UUID acertoId, UUID vendedorId, String vendedor, UUID pracaId, String praca, String situacao,
            Instant data, LocalDate dataDaCotacao, BigDecimal percentual, ValorEmMoedas vendidoLiquido, ValorEmMoedas comissao, ValorEmMoedas liquido) {
    }

    /** O que as maletas da linha somam (peças e valor enviados e devolvidos à loja; vendidas e em falta, em peças). */
    private record Soma(long maletas, long pecas, ValorEmMoedas valor, long devolvidasPecas, ValorEmMoedas devolvido, long vendidas, long faltas) {
    }

    private static final java.time.ZoneId BRASILIA = CotacaoVigente.BRASILIA;

    private static final String MOVIMENTOS = ConsultasDeMaleta.MOVIMENTOS;

    private static final String DEVOLVIDO_POR_MALETA = "LEFT JOIN (SELECT i.remessa_id, sum(m.devolvida) AS pecas, "
            + "sum(m.devolvida * i.preco_remessa) AS valor FROM corona.itens_remessa i " + MOVIMENTOS
            + " WHERE i.deleted_at IS NULL GROUP BY i.remessa_id) dv ON dv.remessa_id = r.id ";

    private static final String VENDIDO_E_FALTA_POR_MALETA = """
            LEFT JOIN (SELECT remessa_id,
                              coalesce(-sum(quantidade) FILTER (WHERE tipo IN ('VENDA', 'ESTORNO_VENDA', 'DEVOLUCAO_CLIENTE',
                                                                                'TROCA_ENTRADA', 'TROCA_SAIDA')), 0) AS vendidas,
                              coalesce(-sum(quantidade) FILTER (WHERE tipo IN ('PERDA', 'AVARIA', 'AJUSTE')), 0) AS faltas
                         FROM corona.movimentos_remessa GROUP BY remessa_id) mv ON mv.remessa_id = r.id
            """;

    private static final String DE_MALETA = "FROM corona.remessas r JOIN corona.vendedores v ON v.id = r.vendedor_id "
            + "JOIN corona.semanas s ON s.id = r.semana_id " + COTACAO_DO_ENVIO + " ";

    /** As maletas que estão abertas com o vendedor, sem acerto (a linha em aberto). */
    private static final String MALETA_EM_ABERTO = "r.acerto_id IS NULL AND r.status IN ('ENVIADA', 'CONFERIDA')";

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicoDeFechamento fechamento;
    private final CotacaoVigente cotacaoVigente;
    private final ConsultasFinanceiras financeiras;
    private final ConsultasDeMaleta maletas;
    private final ConsultasDeVendasDaDona vendas;
    private final ConsultasDeDevolucaoETroca devolucoes;
    private final Clock relogio;

    ConsultasDoPainelSemanal(NamedParameterJdbcTemplate jdbc, ServicoDeFechamento fechamento, CotacaoVigente cotacaoVigente,
            ConsultasFinanceiras financeiras, ConsultasDeMaleta maletas, ConsultasDeVendasDaDona vendas,
            ConsultasDeDevolucaoETroca devolucoes, Clock relogio) {
        this.jdbc = jdbc;
        this.fechamento = fechamento;
        this.cotacaoVigente = cotacaoVigente;
        this.financeiras = financeiras;
        this.maletas = maletas;
        this.vendas = vendas;
        this.devolucoes = devolucoes;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public PainelDaSemana painel(Filtros f) {
        UUID vendedor = idOpcional(f.vendedorId(), "vendedor");
        UUID praca = idOpcional(f.pracaId(), "praca");
        String situacao = situacaoPedida(f.situacao());
        SemanaDoPainel semana = semanaDe(f.semanaId());
        Optional<Cotacao> cotacao = cotacaoVigente.vigenteEm(relogio.instant());

        List<LinhaDoPedido> todas = linhas(semana, vendedor, praca, situacao, cotacao.orElse(null));
        List<LinhaDoPedido> visiveis = todas.stream().filter(l -> situacao == null || l.situacao().equals(situacao)).toList();
        return new PainelDaSemana(semana, true, AVISO_DA_PREVIA, cotacao.map(c -> new CotacaoDaPrevia(c.dataCotacao())).orElse(null),
                visiveis, totais(semana, vendedor, praca, todas, visiveis, cotacao.isEmpty()));
    }

    // --- semana e situação ---

    private static String situacaoPedida(String pedida) {
        if (pedida == null || pedida.isBlank()) {
            return null;
        }
        String s = pedida.strip().toUpperCase();
        if (!SITUACOES.contains(s)) {
            throw Validacoes.recusar("situacao", "Escolha em aberto, em acerto ou acertado.");
        }
        return s;
    }

    private SemanaDoPainel semanaDe(String semanaId) {
        UUID id = idOpcional(semanaId, "semana");
        List<SemanaDoPainel> achadas;
        if (id != null) {
            achadas = jdbc.query("SELECT id, numero, inicio, fim, situacao FROM corona.semanas WHERE id = :id AND deleted_at IS NULL",
                    new MapSqlParameterSource("id", id), (rs, n) -> semana(rs));
        } else {
            LocalDate hoje = LocalDate.ofInstant(relogio.instant(), BRASILIA);
            achadas = jdbc.query("SELECT id, numero, inicio, fim, situacao FROM corona.semanas WHERE deleted_at IS NULL "
                    + "AND :hoje BETWEEN inicio AND fim ORDER BY inicio DESC LIMIT 1", new MapSqlParameterSource("hoje", hoje),
                    (rs, n) -> semana(rs));
        }
        if (achadas.isEmpty()) {
            throw Validacoes.recusar("semana", "Escolha a semana.");
        }
        return achadas.get(0);
    }

    private static SemanaDoPainel semana(ResultSet rs) throws SQLException {
        return new SemanaDoPainel(rs.getObject("id", UUID.class), rs.getInt("numero"), rs.getObject("inicio", LocalDate.class),
                rs.getObject("fim", LocalDate.class), rs.getString("situacao"));
    }

    // --- os pedidos ---

    private List<LinhaDoPedido> linhas(SemanaDoPainel semana, UUID vendedor, UUID praca, String situacao, Cotacao cotacaoDaPrevia) {
        MapSqlParameterSource p = new MapSqlParameterSource("semana", semana.id());
        String filtros = "";
        if (vendedor != null) {
            p.addValue("vendedor", vendedor);
            filtros += " AND v.id = :vendedor";
        }
        if (praca != null) {
            p.addValue("praca", praca);
            filtros += " AND v.praca_id = :praca";
        }
        List<Base> bases = new ArrayList<>();
        bases.addAll(jdbc.query("SELECT a.id AS acerto_id, a.vendedor_id, v.nome AS vendedor, v.praca_id, pc.nome AS praca, a.status, "
                + "a.fechado_em, a.iniciado_em, a.percentual_comissao, ct.data_cotacao, "
                + ConsultasFinanceiras.colunasEmMoedas("a.vendido_liquido", "vendido") + ", "
                + ConsultasFinanceiras.colunasEmMoedas("a.comissao", "comissao") + ", "
                + ConsultasFinanceiras.colunasEmMoedas("a.liquido", "liquido") + " FROM " + ConsultasFinanceiras.DE_ACERTO
                + " LEFT JOIN corona.pracas pc ON pc.id = v.praca_id WHERE a.deleted_at IS NULL AND a.semana_id = :semana "
                + "AND a.status IN ('ACERTADO', 'EM_ACERTO')" + filtros, p, (rs, n) -> {
                    boolean acertado = "ACERTADO".equals(rs.getString("status"));
                    return new Base(rs.getObject("acerto_id", UUID.class), rs.getObject("vendedor_id", UUID.class),
                            rs.getString("vendedor"), rs.getObject("praca_id", UUID.class), rs.getString("praca"),
                            rs.getString("status"), instante(rs, acertado ? "fechado_em" : "iniciado_em"),
                            rs.getObject("data_cotacao", LocalDate.class), rs.getBigDecimal("percentual_comissao"),
                            acertado ? valorGravado(rs, "vendido_liquido", "vendido") : null,
                            acertado ? valorGravado(rs, "comissao", "comissao") : null,
                            acertado ? valorGravado(rs, "liquido", "liquido") : null);
                }));
        bases.addAll(jdbc.query("SELECT DISTINCT r.vendedor_id, v.nome AS vendedor, v.praca_id, pc.nome AS praca FROM corona.remessas r "
                + "JOIN corona.vendedores v ON v.id = r.vendedor_id LEFT JOIN corona.pracas pc ON pc.id = v.praca_id "
                + "WHERE r.deleted_at IS NULL AND r.semana_id = :semana AND " + MALETA_EM_ABERTO
                + " AND NOT EXISTS (SELECT 1 FROM corona.acertos a WHERE a.vendedor_id = r.vendedor_id AND a.semana_id = r.semana_id "
                + "AND a.status = 'EM_ACERTO' AND a.deleted_at IS NULL)" + filtros, p,
                (rs, n) -> new Base(null, rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"),
                        rs.getObject("praca_id", UUID.class), rs.getString("praca"), "EM_ABERTO", null, null, null, null, null,
                        null)));

        List<UUID> acertos = bases.stream().map(Base::acertoId).filter(java.util.Objects::nonNull).toList();
        List<UUID> emAberto = bases.stream().filter(b -> b.acertoId() == null).map(Base::vendedorId).toList();
        Map<UUID, Soma> somas = new HashMap<>(somasDasMaletas("r.acerto_id", "r.acerto_id IN (:chaves)", acertos));
        Map<UUID, Soma> somasEmAberto = somasDasMaletas("r.vendedor_id", MALETA_EM_ABERTO + " AND r.vendedor_id IN (:chaves)", emAberto);
        Map<UUID, List<MaletaDaLinha>> listaPorAcerto = listaDasMaletas("r.acerto_id", "r.acerto_id IN (:chaves)", acertos);
        Map<UUID, List<MaletaDaLinha>> listaEmAberto = listaDasMaletas("r.vendedor_id",
                MALETA_EM_ABERTO + " AND r.vendedor_id IN (:chaves)", emAberto);

        List<LinhaDoPedido> linhas = new ArrayList<>();
        for (Base b : bases) {
            boolean acertada = "ACERTADO".equals(b.situacao());
            UUID chave = b.acertoId() != null ? b.acertoId() : b.vendedorId();
            Soma soma = (b.acertoId() != null ? somas : somasEmAberto).getOrDefault(chave, semMaletas());
            List<MaletaDaLinha> lista = (b.acertoId() != null ? listaPorAcerto : listaEmAberto).getOrDefault(chave, List.of());
            if (acertada) {
                linhas.add(new LinhaDoPedido(b.acertoId(), b.vendedorId(), b.vendedor(), b.pracaId(), b.praca(), b.situacao(), false,
                        false, b.data(), b.dataDaCotacao(), lista, soma.pecas(), soma.valor(), soma.devolvidasPecas(), soma.devolvido(),
                        b.vendidoLiquido(), b.percentual(), b.comissao(), b.liquido(), soma.vendidas(), soma.faltas()));
                continue;
            }
            boolean aparece = situacao == null || situacao.equals(b.situacao());
            if (!aparece) {
                // A prévia custa algumas consultas: só se calcula para a linha que vai aparecer.
                continue;
            }
            if (cotacaoDaPrevia == null) {
                linhas.add(new LinhaDoPedido(b.acertoId(), b.vendedorId(), b.vendedor(), b.pracaId(), b.praca(), b.situacao(), true,
                        true, b.data(), null, lista, soma.pecas(), soma.valor(), soma.devolvidasPecas(), soma.devolvido(), null, null,
                        null, null, null, null));
                continue;
            }
            ResumoDaPrevia r = fechamento.resumoDaPrevia(b.vendedorId(), b.acertoId());
            linhas.add(new LinhaDoPedido(b.acertoId(), b.vendedorId(), b.vendedor(), b.pracaId(), b.praca(), b.situacao(), true, false,
                    b.data(), r.cotacaoData(), lista, soma.pecas(), soma.valor(), soma.devolvidasPecas(), soma.devolvido(),
                    ValorEmMoedas.de(r.vendidoLiquido(), cotacaoDaPrevia), r.percentualComissao(), ValorEmMoedas.de(r.comissao(), cotacaoDaPrevia),
                    ValorEmMoedas.de(r.liquido(), cotacaoDaPrevia), null, null));
        }
        linhas.sort(Comparator.comparing((LinhaDoPedido l) -> l.vendedor().toLowerCase()).thenComparing(l -> ordemDaSituacao(l.situacao()))
                .thenComparing(LinhaDoPedido::vendedorId));
        return linhas;
    }

    private static int ordemDaSituacao(String situacao) {
        return SITUACOES.indexOf(situacao);
    }

    private static Soma semMaletas() {
        return new Soma(0, 0, ValorEmMoedas.zero(), 0, ValorEmMoedas.zero(), 0, 0);
    }

    /** ValorEmMoedas gravado do acerto: Real do banco; Dólar e Guarani pela cotação gravada nele (colunas com sufixo _d e _g). */
    private static ValorEmMoedas valorGravado(ResultSet rs, String coluna, String apelido) throws SQLException {
        return new ValorEmMoedas(MotorDeRelatorio.reais(rs, coluna), rs.getBigDecimal(apelido + "_d"), rs.getBigDecimal(apelido + "_g"));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime v = rs.getObject(coluna, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static String somaEm(String expressao, String cotacao) {
        return "CASE WHEN count(*) FILTER (WHERE ct." + cotacao + " IS NULL) > 0 THEN NULL ELSE sum(" + expressao + ") END";
    }

    /** Peças e valores das maletas, somados no banco por linha: enviado e devolvido pelo preço congelado e a cotação do envio. */
    private Map<UUID, Soma> somasDasMaletas(String chave, String onde, List<UUID> chaves) {
        Map<UUID, Soma> somas = new LinkedHashMap<>();
        if (chaves.isEmpty()) {
            return somas;
        }
        String enviado = "r.total_valor";
        String devolvido = "coalesce(dv.valor, 0)";
        jdbc.query("SELECT " + chave + " AS chave, count(*) AS maletas, sum(r.total_pecas) AS pecas, sum(" + enviado + ") AS valor, "
                + somaEm(dolar(enviado), "usd_venda") + " AS dolar, " + somaEm(guarani(enviado), "pyg_venda") + " AS guarani, "
                + "coalesce(sum(dv.pecas), 0) AS dv_pecas, coalesce(sum(" + devolvido + "), 0) AS dv_valor, "
                + somaEm(dolar(devolvido), "usd_venda") + " AS dv_dolar, " + somaEm(guarani(devolvido), "pyg_venda")
                + " AS dv_guarani, coalesce(sum(mv.vendidas), 0) AS vendidas, coalesce(sum(mv.faltas), 0) AS faltas " + DE_MALETA
                + DEVOLVIDO_POR_MALETA + VENDIDO_E_FALTA_POR_MALETA + "WHERE r.deleted_at IS NULL AND " + onde + " GROUP BY " + chave,
                new MapSqlParameterSource("chaves", chaves), rs -> {
                    somas.put(rs.getObject("chave", UUID.class), new Soma(rs.getLong("maletas"), rs.getLong("pecas"),
                            new ValorEmMoedas(MotorDeRelatorio.reais(rs, "valor"), rs.getBigDecimal("dolar"), rs.getBigDecimal("guarani")),
                            rs.getLong("dv_pecas"), new ValorEmMoedas(MotorDeRelatorio.reais(rs, "dv_valor"), rs.getBigDecimal("dv_dolar"),
                                    rs.getBigDecimal("dv_guarani")), rs.getLong("vendidas"), rs.getLong("faltas")));
                });
        return somas;
    }

    private Map<UUID, List<MaletaDaLinha>> listaDasMaletas(String chave, String onde, List<UUID> chaves) {
        Map<UUID, List<MaletaDaLinha>> lista = new LinkedHashMap<>();
        if (chaves.isEmpty()) {
            return lista;
        }
        jdbc.query("SELECT " + chave + " AS chave, r.id, " + NUMERO + " AS numero, r.tipo, r.status " + DE_MALETA.replace(COTACAO_DO_ENVIO, "")
                + "WHERE r.deleted_at IS NULL AND " + onde + " ORDER BY r.enviada_em, r.id", new MapSqlParameterSource("chaves", chaves),
                rs -> {
                    lista.computeIfAbsent(rs.getObject("chave", UUID.class), k -> new ArrayList<>()).add(new MaletaDaLinha(
                            rs.getObject("id", UUID.class), rs.getString("numero"), rs.getString("tipo"), rs.getString("status")));
                });
        return lista;
    }

    // --- os totais ---

    private TotaisDoPainel totais(SemanaDoPainel semana, UUID vendedor, UUID praca, List<LinhaDoPedido> todas, List<LinhaDoPedido> visiveis,
            boolean semCotacao) {
        String semanaId = semana.id().toString();
        String de = semana.inicio().toString();
        String ate = semana.fim().toString();
        String vendedorId = vendedor == null ? null : vendedor.toString();
        String pracaId = praca == null ? null : praca.toString();

        var remessas = maletas.remessas(new ConsultasDeMaleta.Filtros(null, vendedorId, pracaId, semanaId, null, null, null, null,
                null, null, null, null, null, 0, 1));
        Map<String, String> filtrosDeMaletas = filtros("semanaId", semanaId, "vendedorId", vendedorId, "pracaId", pracaId);
        BlocoDeMaletasDoPainel novas = blocoDeMaletas(remessas, "novas", com(filtrosDeMaletas, "tipo", "NOVA"));
        BlocoDeMaletasDoPainel reposicoes = blocoDeMaletas(remessas, "reposicoes", com(filtrosDeMaletas, "tipo", "REPOSICAO"));

        var acertos = financeiras.acertos(new ConsultasFinanceiras.Filtros(null, vendedorId, semanaId, null, null, null, null, null,
                null, null, null, null, null, null, null, null, 0, 1, pracaId));
        var pendencias = financeiras.pendencias(new ConsultasFinanceiras.Filtros(null, vendedorId, semanaId, null, null, null, null,
                null, null, null, null, null, null, null, null, null, 0, 1, pracaId));
        List<LinhaDoPedido> acertadas = todas.stream().filter(l -> "ACERTADO".equals(l.situacao())).toList();
        ValorEmMoedas devolvidoALoja = acertadas.isEmpty() ? ValorEmMoedas.zero() : ValorEmMoedas.somar(acertadas.stream().map(LinhaDoPedido::devolvido).toList());
        Map<String, String> filtrosDeAcertos = filtros("semanaId", semanaId, "vendedorId", vendedorId, "pracaId", pracaId);
        BlocoDeAcerto acerto = new BlocoDeAcerto(acertos.total(), valor(acertos, "vendido"), valor(acertos, "descontos"),
                valor(acertos, "devolvido"), valor(acertos, "comissao"), valor(acertos, "faltas"), valor(acertos, "liquido"),
                valor(acertos, "recebido"), valor(acertos, "devendo"),
                acertadas.stream().mapToLong(LinhaDoPedido::devolvidoPecas).sum(), devolvidoALoja, valor(pendencias, "cobradas"),
                valor(pendencias, "abertas"), new OrigemDoNumero(ROTA_DE_ACERTOS, filtrosDeAcertos),
                new OrigemDoNumero(ROTA_DE_PENDENCIAS, filtrosDeAcertos));

        List<LinhaDoPedido> deHoje = visiveis.stream().filter(LinhaDoPedido::previa).toList();
        BlocoDaPrevia previa = blocoDaPrevia(deHoje, semCotacao);

        BlocoDeVendas blocoDeVendas = blocoDeVendas(de, ate, vendedorId, pracaId);

        var trocas = devolucoes.trocas(new ConsultasDeDevolucaoETroca.Filtros(null, vendedorId, null, null, null, null, null, null,
                semana.inicio(), semana.fim(), null, 0, 1, pracaId));
        var creditos = devolucoes.creditos(new ConsultasDeDevolucaoETroca.Filtros(null, vendedorId, null, null, null, null, null,
                null, semana.inicio(), semana.fim(), null, 0, 1, pracaId));
        Map<String, String> filtrosDoPeriodo = filtros("de", de, "ate", ate, "vendedorId", vendedorId, "pracaId", pracaId);
        BlocoDeTrocas blocoDeTrocas = new BlocoDeTrocas(quantidade(trocas, "geral"), valor(trocas, "geral"), valor(trocas, "a-pagar"),
                valor(trocas, "credito"), valor(creditos, "gerados"), valor(creditos, "usados"), valor(creditos, "em-aberto"),
                new OrigemDoNumero(ROTA_DE_TROCAS, filtrosDoPeriodo), new OrigemDoNumero(ROTA_DE_CREDITOS, filtrosDoPeriodo));

        var recebimentos = financeiras.recebimentosPorForma(new ConsultasFinanceiras.Filtros(null, vendedorId, null, null, null, null,
                null, null, null, null, null, null, "ACERTO", semana.inicio(), semana.fim(), null, 0, 1, pracaId));
        BlocoRecebido recebido = new BlocoRecebido(valor(recebimentos, "DINHEIRO"), valor(recebimentos, "PIX"),
                valor(recebimentos, "BOLETO"), valor(recebimentos, "total"),
                new OrigemDoNumero(ROTA_DE_RECEBIMENTOS, com(filtrosDoPeriodo, "momento", "ACERTO")));

        BlocoTotal total = new BlocoTotal(novas.quantidade() + reposicoes.quantidade(), novas.pecas() + reposicoes.pecas(),
                ValorEmMoedas.somar(List.of(novas.valor(), reposicoes.valor())), blocoDeVendas.quantidade(), blocoDeVendas.vendido(),
                blocoDeVendas.descontos(), blocoDeVendas.liquido());
        return new TotaisDoPainel(novas, reposicoes, acerto, previa, blocoDeVendas, blocoDeTrocas, total, recebido);
    }

    private static BlocoDeMaletasDoPainel blocoDeMaletas(RelatorioDeCadastro<?> r, String chave, Map<String, String> filtros) {
        Total t = total(r, chave);
        return new BlocoDeMaletasDoPainel(t.quantidade(), t.pecas() == null ? 0 : t.pecas(), ValorEmMoedas.de(t),
                new OrigemDoNumero(ROTA_DE_MALETAS, filtros));
    }

    /** A prévia somada: só as linhas em prévia que a tela mostra (o filtro de situação vale aqui). */
    private static BlocoDaPrevia blocoDaPrevia(List<LinhaDoPedido> linhas, boolean semCotacao) {
        List<LinhaDoPedido> calculadas = linhas.stream().filter(l -> !l.semCotacao() && l.vendidoLiquido() != null).toList();
        if (calculadas.isEmpty()) {
            return new BlocoDaPrevia(linhas.size(), semCotacao, ValorEmMoedas.zero(), ValorEmMoedas.zero(), ValorEmMoedas.zero());
        }
        return new BlocoDaPrevia(linhas.size(), semCotacao, ValorEmMoedas.somar(calculadas.stream().map(LinhaDoPedido::vendidoLiquido).toList()),
                ValorEmMoedas.somar(calculadas.stream().map(LinhaDoPedido::comissao).toList()),
                ValorEmMoedas.somar(calculadas.stream().map(LinhaDoPedido::liquido).toList()));
    }

    private BlocoDeVendas blocoDeVendas(String de, String ate, String vendedorId, String pracaId) {
        LocalDate inicio = LocalDate.parse(de);
        LocalDate fim = LocalDate.parse(ate);
        var r = vendas.vendas(new ConsultasDeVendasDaDona.Filtros(null, vendedorId, pracaId, null, null, null, null, inicio, fim, null,
                0, 1));
        Consulta filtros = VendidoLiquido.filtrar(new VendidoLiquido.Recorte(null, vendedorId, pracaId, null, null, null, inicio, fim));
        ValorEmMoedas descontos = jdbc.queryForObject("SELECT coalesce(sum(l.desconto), 0) AS valor, " + VendidoLiquido.somaEmDolar("l.desconto")
                + " AS dolar, " + VendidoLiquido.somaEmGuarani("l.desconto") + " AS guarani FROM (" + VendidoLiquido.FONTE + ") l WHERE "
                + VendidoLiquido.CONCLUIDA + (filtros.condicoes.isEmpty() ? "" : " AND " + String.join(" AND ", filtros.condicoes)),
                filtros.parametros, (rs, n) -> new ValorEmMoedas(Dinheiro.arredondar(rs.getBigDecimal("valor")), rs.getBigDecimal("dolar"),
                        rs.getBigDecimal("guarani")));
        return new BlocoDeVendas(quantidade(r, "liquido"), valor(r, "vendido"), descontos, valor(r, "devolvido"), valor(r, "liquido"),
                new OrigemDoNumero(ROTA_DE_VENDAS, filtros("de", de, "ate", ate, "vendedorId", vendedorId, "pracaId", pracaId)));
    }

    // --- pequenos apoios ---

    private static Total total(RelatorioDeCadastro<?> r, String chave) {
        return r.totais().stream().filter(t -> t.chave().equals(chave)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Sem o total " + chave + " no relatório."));
    }

    private static ValorEmMoedas valor(RelatorioDeCadastro<?> r, String chave) {
        return ValorEmMoedas.de(total(r, chave));
    }

    private static long quantidade(RelatorioDeCadastro<?> r, String chave) {
        return total(r, chave).quantidade();
    }

    /** Os filtros que valem, na ordem dada: os vazios ficam de fora. */
    private static Map<String, String> filtros(String... pares) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            if (pares[i + 1] != null) {
                m.put(pares[i], pares[i + 1]);
            }
        }
        return m;
    }

    private static Map<String, String> com(Map<String, String> filtros, String chave, String valor) {
        Map<String, String> m = new LinkedHashMap<>(filtros);
        m.put(chave, valor);
        return m;
    }
}
