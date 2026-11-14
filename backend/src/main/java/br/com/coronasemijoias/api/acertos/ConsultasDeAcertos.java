package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.remessas.SqlDaMaleta;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura dos acertos pela dona e pelo vendedor (tarefa 7.4; ADR-0012, itens 14 e 16). O vendedor só vê os dele (404 para o
 * de outro, sem dizer de quem é) e nunca recebe custo nem dado da dona. Nesta tarefa não há esperado: a ficha mostra a
 * contagem e o aviso de vendas que chegaram depois do início (a tela da 7.9 avisa "Chegou venda nova: confira a contagem").
 */
@Component
public class ConsultasDeAcertos {

    private static final int LIMITE = 500;
    private static final Set<String> SITUACOES = Set.of("EM_ACERTO", "ACERTADO", "DESISTIDO");

    /** Uma maleta ligada ao acerto, com o status que ela tinha antes. */
    public record Maleta(UUID id, String numero, String tipo, String status, String statusAntes) {
    }

    /** A peça como a contagem mostra: nome, código da loja e foto (a falta de foto nunca trava). */
    public record Produto(UUID id, String nome, String codigo, String fotoHash) {
    }

    /** A soma das leituras de uma peça, em um estado, no acerto (nunca negativa). */
    public record Contagem(Produto produto, String estadoPeca, int quantidade) {
    }

    /** Uma leitura como o vendedor a vê (sem o nome de quem leu). */
    public record Leitura(UUID id, UUID produtoId, int quantidade, String estadoPeca, Instant lidaEm, boolean peloVendedor,
            UUID dispositivoId) {
    }

    /** Uma leitura como a dona a vê: com quem leu. */
    public record LeituraDaDona(UUID id, UUID produtoId, int quantidade, String estadoPeca, Instant lidaEm, boolean peloVendedor,
            UUID dispositivoId, String lidaPorNome) {
    }

    /** Uma peça do fechamento: esperado x contado de uma maleta (sem maleta = peças para a loja). O preço é o de venda, nunca o custo. */
    public record ItemFechado(Produto produto, UUID remessaId, BigDecimal precoRemessa, int esperado, int contadoBoa, int contadoDefeito,
            int falta, int defeitoAprovado) {
    }

    public record EventoFechado(String tipo, UUID origemId, UUID vendaId, BigDecimal valor) {
    }

    public record ComissaoFechada(UUID vendaId, String tipo, BigDecimal base, BigDecimal percentual, BigDecimal valor) {
    }

    public record PendenciaFechada(UUID id, String tipo, Produto produto, Integer quantidade, BigDecimal valor, String status,
            UUID cobradaNoAcertoId) {
    }

    /** Um recebimento que pagou o acerto ou uma conta antiga no fechamento (sem quem registrou e sem o motivo do estorno). */
    public record RecebimentoDoAcerto(UUID id, String forma, BigDecimal valor, String aplicadoEm, Instant recebidoEm, UUID estornaId,
            boolean estornado) {
    }

    /** Uma conta a receber do vendedor que o fechamento gerou (o que ficou devendo, o boleto e as parcelas). */
    public record ContaDoAcerto(UUID id, int numero, int totalDeParcelas, LocalDate vencimento, BigDecimal valor, BigDecimal valorPago,
            String status, String formaPrevista) {
    }

    /**
     * O que o fechamento gravou (acerto ACERTADO), tudo em Real; a cotação só para mostrar Dólar e Guarani. A soma da meta em
     * Guarani é a única conversão gravada. Não traz quem fechou nem o resumo das decisões.
     */
    public record Fechamento(Instant fechadoEm, UUID cotacaoId, LocalDate cotacaoData, BigDecimal usdVenda, BigDecimal pygVenda,
            int metaGuarani, BigDecimal percentualComissao, long vendidoParaMetaGuarani, BigDecimal vendidoBruto, BigDecimal descontos,
            BigDecimal devolvido, BigDecimal vendidoLiquido, BigDecimal comissao, BigDecimal faltasCobradas, BigDecimal liquido,
            BigDecimal recebidoNoAcerto, BigDecimal ficouDevendo, BigDecimal pagoAoVendedor, BigDecimal creditoUsado,
            List<ItemFechado> itens, List<EventoFechado> eventos, List<ComissaoFechada> comissoes, List<PendenciaFechada> pendencias,
            List<RecebimentoDoAcerto> recebimentos, List<ContaDoAcerto> contas) {
    }

    /** A linha da lista de acertos da dona. */
    public record Resumo(UUID id, UUID vendedorId, String vendedorNome, UUID semanaId, int semanaNumero, String status,
            boolean pedidoPeloVendedor, Instant iniciadoEm, Instant desistidoEm, int maletas) {
    }

    /** A linha da lista de acertos do vendedor. */
    public record ResumoDoVendedor(UUID id, UUID semanaId, int semanaNumero, String status, boolean pedidoPeloVendedor,
            Instant iniciadoEm, Instant desistidoEm, int maletas) {
    }

    /** O acerto como o vendedor vê. */
    public record FichaDoVendedor(UUID id, UUID vendedorId, UUID semanaId, int semanaNumero, String status,
            boolean pedidoPeloVendedor, Instant iniciadoEm, Instant desistidoEm, String motivoDesistencia, List<Maleta> maletas,
            List<Contagem> contagem, List<Leitura> leituras, Fechamento fechamento) {
    }

    /**
     * O acerto como a dona vê. {@code vendasQueChegaramDepoisDoInicio}: vendas feitas sem internet antes do início que o servidor
     * recebeu depois (a contagem já bate; a tela avisa). {@code enviosAguardando}: o último número que o computador do vendedor
     * informou (vazio se nunca informou).
     */
    public record FichaDaDona(UUID id, UUID vendedorId, String vendedorNome, UUID semanaId, int semanaNumero, String status,
            boolean pedidoPeloVendedor, Instant iniciadoEm, Instant desistidoEm, String motivoDesistencia, List<Maleta> maletas,
            List<Contagem> contagem, List<LeituraDaDona> leituras, int vendasQueChegaramDepoisDoInicio, Integer enviosAguardando,
            Fechamento fechamento) {
    }

    private final JdbcTemplate jdbc;

    ConsultasDeAcertos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- dona ---

    public List<Resumo> daDona(UUID vendedorId, UUID semanaId, String situacao) {
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder("a.deleted_at IS NULL");
        if (vendedorId != null) {
            onde.append(" AND a.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (semanaId != null) {
            onde.append(" AND a.semana_id = ?");
            args.add(semanaId);
        }
        if (situacao != null && !situacao.isBlank()) {
            if (!SITUACOES.contains(situacao)) {
                throw Validacoes.recusar("situacao", "Escolha uma situação da lista.");
            }
            onde.append(" AND a.status = ?");
            args.add(situacao);
        }
        return jdbc.query("""
                SELECT a.id, a.vendedor_id, v.nome AS vendedor, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor,
                       a.iniciado_em, a.desistido_em,
                       (SELECT count(*) FROM corona.remessas r WHERE r.acerto_id = a.id) AS maletas
                  FROM corona.acertos a JOIN corona.vendedores v ON v.id = a.vendedor_id
                  JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE\s""" + onde + " ORDER BY a.iniciado_em DESC, a.id DESC LIMIT " + LIMITE,
                (rs, n) -> new Resumo(rs.getObject("id", UUID.class), rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"),
                        rs.getObject("semana_id", UUID.class), rs.getInt("semana"), rs.getString("status"),
                        rs.getBoolean("pedido_pelo_vendedor"), instante(rs.getTimestamp("iniciado_em")),
                        instante(rs.getTimestamp("desistido_em")), rs.getInt("maletas")), args.toArray());
    }

    public FichaDaDona fichaDaDona(UUID id) {
        return jdbc.query("""
                SELECT a.id, a.vendedor_id, v.nome AS vendedor, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor,
                       a.iniciado_em, a.desistido_em, a.motivo_desistencia
                  FROM corona.acertos a JOIN corona.vendedores v ON v.id = a.vendedor_id
                  JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE a.id = ? AND a.deleted_at IS NULL""", rs -> {
                    if (!rs.next()) {
                        throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
                    }
                    UUID vendedor = rs.getObject("vendedor_id", UUID.class);
                    return new FichaDaDona(id, vendedor, rs.getString("vendedor"), rs.getObject("semana_id", UUID.class),
                            rs.getInt("semana"), rs.getString("status"), rs.getBoolean("pedido_pelo_vendedor"),
                            instante(rs.getTimestamp("iniciado_em")), instante(rs.getTimestamp("desistido_em")),
                            rs.getString("motivo_desistencia"), maletas(id), contagem(id), leiturasDaDona(id),
                            vendasDepoisDoInicio(id), enviosAguardando(vendedor), fechamento(id));
                }, id);
    }

    // --- vendedor ---

    public List<ResumoDoVendedor> doVendedor(UUID vendedorId) {
        return jdbc.query("""
                SELECT a.id, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor, a.iniciado_em, a.desistido_em,
                       (SELECT count(*) FROM corona.remessas r WHERE r.acerto_id = a.id) AS maletas
                  FROM corona.acertos a JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE a.vendedor_id = ? AND a.deleted_at IS NULL ORDER BY a.iniciado_em DESC, a.id DESC LIMIT\s""" + LIMITE,
                (rs, n) -> new ResumoDoVendedor(rs.getObject("id", UUID.class), rs.getObject("semana_id", UUID.class),
                        rs.getInt("semana"), rs.getString("status"), rs.getBoolean("pedido_pelo_vendedor"),
                        instante(rs.getTimestamp("iniciado_em")), instante(rs.getTimestamp("desistido_em")), rs.getInt("maletas")),
                vendedorId);
    }

    /** O acerto do vendedor; 404 se não existe ou é de outro (sem dizer de quem é). */
    public FichaDoVendedor fichaDoVendedor(UUID id, UUID vendedorId) {
        return jdbc.query("""
                SELECT a.id, a.vendedor_id, a.semana_id, s.numero AS semana, a.status, a.pedido_pelo_vendedor, a.iniciado_em,
                       a.desistido_em, a.motivo_desistencia
                  FROM corona.acertos a JOIN corona.semanas s ON s.id = a.semana_id
                 WHERE a.id = ? AND a.vendedor_id = ? AND a.deleted_at IS NULL""", rs -> {
                    if (!rs.next()) {
                        throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
                    }
                    return new FichaDoVendedor(id, vendedorId, rs.getObject("semana_id", UUID.class), rs.getInt("semana"),
                            rs.getString("status"), rs.getBoolean("pedido_pelo_vendedor"), instante(rs.getTimestamp("iniciado_em")),
                            instante(rs.getTimestamp("desistido_em")), rs.getString("motivo_desistencia"), maletas(id),
                            contagem(id), leiturasDoVendedor(id), fechamento(id));
                }, id, vendedorId);
    }

    // --- o fechamento ---

    /** O fechamento gravado, ou nulo se o acerto não está ACERTADO. */
    public Fechamento fechamento(UUID acerto) {
        return jdbc.query("""
                SELECT a.fechado_em, a.cotacao_id, c.data_cotacao, c.usd_venda, c.pyg_venda, a.meta_guarani, a.percentual_comissao,
                       a.vendido_para_meta_guarani, a.vendido_bruto, a.descontos, a.devolvido, a.vendido_liquido, a.comissao,
                       a.faltas_cobradas, a.liquido, a.recebido_no_acerto, a.ficou_devendo, a.pago_ao_vendedor, a.credito_usado
                  FROM corona.acertos a JOIN corona.cotacoes c ON c.id = a.cotacao_id WHERE a.id = ? AND a.status = 'ACERTADO'""", rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new Fechamento(instante(rs.getTimestamp(1)), rs.getObject(2, UUID.class), rs.getObject(3, LocalDate.class),
                            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6), rs.getBigDecimal(7), rs.getLong(8), rs.getBigDecimal(9),
                            rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14),
                            rs.getBigDecimal(15), rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getBigDecimal(18), rs.getBigDecimal(19),
                            itensFechados(acerto), eventosFechados(acerto), comissoesFechadas(acerto), pendenciasFechadas(acerto),
                            recebimentosDoAcerto(acerto), contasDoAcerto(acerto));
                }, acerto);
    }

    private static final String PRODUTO = """
            p.id AS produto_id, p.nome, p.foto_principal_hash,
            (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.ativo AND c.deleted_at IS NULL
              ORDER BY c.principal DESC, c.codigo LIMIT 1) AS codigo""";

    private List<ItemFechado> itensFechados(UUID acerto) {
        return jdbc.query("SELECT i.remessa_id, i.preco_remessa, i.esperado, i.contado_boa, i.contado_defeito, i.falta, i.defeito_aprovado, "
                + PRODUTO + " FROM corona.itens_acerto i JOIN corona.produtos p ON p.id = i.produto_id WHERE i.acerto_id = ? "
                + "ORDER BY p.nome, i.produto_id, i.remessa_id NULLS FIRST",
                (rs, n) -> new ItemFechado(new Produto(rs.getObject("produto_id", UUID.class), rs.getString("nome"), rs.getString("codigo"),
                        rs.getString("foto_principal_hash")), rs.getObject("remessa_id", UUID.class), rs.getBigDecimal("preco_remessa"),
                        rs.getInt("esperado"), rs.getInt("contado_boa"), rs.getInt("contado_defeito"), rs.getInt("falta"),
                        rs.getInt("defeito_aprovado")), acerto);
    }

    private List<EventoFechado> eventosFechados(UUID acerto) {
        return jdbc.query("SELECT origem_tipo, origem_id, venda_id, valor FROM corona.acerto_eventos WHERE acerto_id = ? ORDER BY sync_seq",
                (rs, n) -> new EventoFechado(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getBigDecimal(4)),
                acerto);
    }

    private List<ComissaoFechada> comissoesFechadas(UUID acerto) {
        return jdbc.query("SELECT venda_id, tipo, base, percentual, valor FROM corona.comissoes WHERE acerto_id = ? ORDER BY sync_seq",
                (rs, n) -> new ComissaoFechada(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5)), acerto);
    }

    private List<PendenciaFechada> pendenciasFechadas(UUID acerto) {
        return jdbc.query("SELECT x.id, x.tipo, x.quantidade, x.valor, x.status, x.cobrada_no_acerto_id, " + PRODUTO
                + " FROM corona.pendencias_acerto x LEFT JOIN corona.produtos p ON p.id = x.produto_id "
                + "WHERE x.acerto_id = ? OR x.cobrada_no_acerto_id = ? ORDER BY x.created_at, x.id",
                (rs, n) -> new PendenciaFechada(rs.getObject("id", UUID.class), rs.getString("tipo"),
                        rs.getObject("produto_id", UUID.class) == null ? null : new Produto(rs.getObject("produto_id", UUID.class),
                                rs.getString("nome"), rs.getString("codigo"), rs.getString("foto_principal_hash")),
                        (Integer) rs.getObject("quantidade"), rs.getBigDecimal("valor"), rs.getString("status"),
                        rs.getObject("cobrada_no_acerto_id", UUID.class)), acerto, acerto);
    }

    private List<RecebimentoDoAcerto> recebimentosDoAcerto(UUID acerto) {
        return jdbc.query("""
                SELECT r.id, r.forma, r.valor, r.aplicado_em, r.recebido_em, r.estorna_id,
                       EXISTS (SELECT 1 FROM corona.recebimentos_vendedor e WHERE e.estorna_id = r.id) AS estornado
                  FROM corona.recebimentos_vendedor r WHERE r.acerto_id = ? AND r.deleted_at IS NULL ORDER BY r.recebido_em, r.id""",
                (rs, n) -> new RecebimentoDoAcerto(rs.getObject("id", UUID.class), rs.getString("forma"), rs.getBigDecimal("valor"),
                        rs.getString("aplicado_em"), instante(rs.getTimestamp("recebido_em")), rs.getObject("estorna_id", UUID.class),
                        rs.getBoolean("estornado")), acerto);
    }

    private List<ContaDoAcerto> contasDoAcerto(UUID acerto) {
        return jdbc.query("""
                SELECT id, parcela, total_parcelas, vencimento, valor, valor_pago, status, forma_prevista FROM corona.contas_receber
                 WHERE devedor_tipo = 'VENDEDOR' AND origem_tipo = 'ACERTO' AND origem_id = ? AND deleted_at IS NULL ORDER BY parcela""",
                (rs, n) -> new ContaDoAcerto(rs.getObject("id", UUID.class), rs.getInt("parcela"), rs.getInt("total_parcelas"),
                        rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"), rs.getBigDecimal("valor_pago"),
                        rs.getString("status"), rs.getString("forma_prevista")), acerto);
    }

    // --- pedaços ---

    private List<Maleta> maletas(UUID acerto) {
        return jdbc.query("""
                SELECT r.id, """ + SqlDaMaleta.NUMERO + " AS numero, " + """
                       r.tipo, r.status, r.status_antes_do_acerto
                  FROM corona.remessas r JOIN corona.semanas s ON s.id = r.semana_id
                  JOIN corona.vendedores v ON v.id = r.vendedor_id
                 WHERE r.acerto_id = ? ORDER BY r.enviada_em, r.id""",
                (rs, n) -> new Maleta(rs.getObject("id", UUID.class), rs.getString("numero"), rs.getString("tipo"),
                        rs.getString("status"), rs.getString("status_antes_do_acerto")), acerto);
    }

    private List<Contagem> contagem(UUID acerto) {
        return jdbc.query("""
                SELECT l.produto_id, l.estado_peca, sum(l.quantidade) AS quantidade, p.nome, p.foto_principal_hash,
                       (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.ativo
                           AND c.deleted_at IS NULL ORDER BY c.principal DESC, c.codigo LIMIT 1) AS codigo
                  FROM corona.leituras_acerto l JOIN corona.produtos p ON p.id = l.produto_id
                 WHERE l.acerto_id = ?
                 GROUP BY l.produto_id, l.estado_peca, p.id, p.nome, p.foto_principal_hash
                HAVING sum(l.quantidade) > 0 ORDER BY p.nome, l.produto_id, l.estado_peca""",
                (rs, n) -> new Contagem(new Produto(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                        rs.getString("codigo"), rs.getString("foto_principal_hash")), rs.getString("estado_peca"),
                        rs.getInt("quantidade")), acerto);
    }

    private List<Leitura> leiturasDoVendedor(UUID acerto) {
        return jdbc.query("""
                SELECT id, produto_id, quantidade, estado_peca, lida_em, pelo_vendedor, dispositivo_id
                  FROM corona.leituras_acerto WHERE acerto_id = ? ORDER BY lida_em, id""",
                (rs, n) -> new Leitura(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class), rs.getInt("quantidade"),
                        rs.getString("estado_peca"), instante(rs.getTimestamp("lida_em")), rs.getBoolean("pelo_vendedor"),
                        rs.getObject("dispositivo_id", UUID.class)), acerto);
    }

    private List<LeituraDaDona> leiturasDaDona(UUID acerto) {
        return jdbc.query("""
                SELECT l.id, l.produto_id, l.quantidade, l.estado_peca, l.lida_em, l.pelo_vendedor, l.dispositivo_id, u.nome
                  FROM corona.leituras_acerto l JOIN corona.usuarios u ON u.id = l.lida_por
                 WHERE l.acerto_id = ? ORDER BY l.lida_em, l.id""",
                (rs, n) -> new LeituraDaDona(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class),
                        rs.getInt("quantidade"), rs.getString("estado_peca"), instante(rs.getTimestamp("lida_em")),
                        rs.getBoolean("pelo_vendedor"), rs.getObject("dispositivo_id", UUID.class), rs.getString("nome")), acerto);
    }

    private int vendasDepoisDoInicio(UUID acerto) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM corona.vendas v JOIN corona.remessas r ON r.id = v.remessa_id
                  JOIN corona.acertos a ON a.id = r.acerto_id
                 WHERE a.id = ? AND v.created_at >= a.iniciado_em""", Integer.class, acerto);
    }

    private Integer enviosAguardando(UUID vendedorId) {
        List<Integer> achado = jdbc.query("""
                SELECT d.aguardando_envio FROM corona.dispositivos d JOIN corona.usuarios u ON u.id = d.usuario_id
                 WHERE u.vendedor_id = ? AND d.situacao = 'ATIVO' AND d.aguardando_envio IS NOT NULL""",
                (rs, n) -> rs.getInt(1), vendedorId);
        return achado.isEmpty() ? null : achado.get(0);
    }

    private static Instant instante(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
