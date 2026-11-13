package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.MotivoDaRevisao;
import br.com.coronasemijoias.api.produtos.CodigosDosProdutos;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Cotacao;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Peca;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura das devoluções do cliente (tarefa 6.3). Nada de custo nem de fornecedor: só o preço pago, as peças (nome e
 * foto) e os valores. Dólar e Guarani são calculados na leitura pela cotação do dia da devolução (ADR-0006).
 */
@Component
public class ConsultasDeDevolucoes {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final int LIMITE_DE_VENDAS = 20;
    private static final Set<String> SITUACOES = Set.of("ACEITA", "EM_REVISAO", "MANTIDA", "NAO_ACEITA", "CANCELADA");

    public record ItemDaDevolucao(UUID id, UUID itemVendaId, Peca produto, int quantidade, BigDecimal valor, String estado,
            String destino) {
    }

    /**
     * {@code motivosRevisao}: por que a devolução está (ou esteve) para revisão, na ordem fixa de
     * {@link MotivoDaRevisao}; vazio na aceita. {@code valorReembolsado} é só o dinheiro; {@code valorEmCredito}, o que voltou
     * como crédito do cliente (tarefa 6.5). {@code pendenciaDoVendedor}: o valor que o vendedor deve quando a dona
     * não aceitou (tarefa 6.4; o acerto passa a cobrar na tarefa 7.5); vazio nas outras situações.
     */
    public record Devolucao(UUID id, UUID vendaId, UUID vendedorId, UUID clienteId, String clienteNome, UUID remessaId,
            String motivo, String observacao, String destino, boolean dentroGarantia, String situacao,
            BigDecimal valorDevolvido, BigDecimal valorDevolvidoDolar, BigDecimal valorDevolvidoGuarani,
            BigDecimal valorReembolsado, BigDecimal valorEmCredito, String formaReembolso, Cotacao cotacao, Instant devolvidaEm,
            boolean horaAjustada,
            Instant canceladaEm, String motivoCancelamento, UUID autorizacaoId, List<String> motivosRevisao,
            Instant revisadaEm, String observacaoRevisao, BigDecimal pendenciaDoVendedor, List<ItemDaDevolucao> itens) {
    }

    /** Uma linha da venda achada pela peça lida: vendidas, já devolvidas (não canceladas) e as que ainda podem voltar. */
    public record LinhaDaVendaComAPeca(UUID itemVendaId, Peca produto, int vendidas, int devolvidas, int podemVoltar,
            BigDecimal totalDaLinha) {
    }

    /**
     * Uma venda do vendedor com a peça lida. {@code aPrazo}: ainda não pode ser devolvida (tarefa 6.6). Os prazos são o
     * último dia (em Brasília) em que vale a garantia por desistência (também "não serviu" e "outro") e por defeito.
     */
    public record VendaComAPeca(UUID vendaId, UUID clienteId, String clienteNome, Instant vendidaEm, BigDecimal total,
            boolean aPrazo, LocalDate prazoArrependimentoAte, LocalDate prazoDefeitoAte, List<LinhaDaVendaComAPeca> itens) {
    }

    /** {@code peca} é a peça do código lido (vazia se o código não é de nenhuma peça). */
    public record VendasComAPeca(Peca peca, List<VendaComAPeca> vendas) {
    }

    private final JdbcTemplate jdbc;
    private final CodigosDosProdutos codigos;
    private final Configuracoes configuracoes;

    ConsultasDeDevolucoes(JdbcTemplate jdbc, CodigosDosProdutos codigos, Configuracoes configuracoes) {
        this.jdbc = jdbc;
        this.codigos = codigos;
        this.configuracoes = configuracoes;
    }

    /** {@code vendedorId} nulo é a dona (vê qualquer devolução); o vendedor só vê as dele (404 nas outras). */
    public Devolucao ver(UUID id, UUID vendedorId) {
        List<Devolucao> achadas = consultar("d.id = ?" + (vendedorId == null ? "" : " AND d.vendedor_id = ?"),
                vendedorId == null ? new Object[] {id} : new Object[] {id, vendedorId});
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.DEVOLUCAO_NAO_ENCONTRADA);
        }
        return achadas.get(0);
    }

    /** As devoluções do vendedor, da mais nova para a mais antiga, com filtros opcionais de dia (Brasília) e venda. */
    public List<Devolucao> listar(UUID vendedorId, LocalDate de, LocalDate ate, UUID vendaId) {
        StringBuilder onde = new StringBuilder("d.vendedor_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(vendedorId);
        if (de != null) {
            onde.append(" AND (d.devolvida_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            args.add(de);
        }
        if (ate != null) {
            onde.append(" AND (d.devolvida_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            args.add(ate);
        }
        if (vendaId != null) {
            onde.append(" AND d.venda_id = ?");
            args.add(vendaId);
        }
        return consultar(onde.toString(), args.toArray());
    }

    /**
     * A lista da dona (tarefa 6.4): pela situação (padrão: para revisão) e, se vier, pelo vendedor; da mais nova para a
     * mais antiga, no máximo 500.
     */
    public List<Devolucao> listarDaDona(String situacao, UUID vendedorId) {
        String filtro = situacao == null ? "EM_REVISAO" : situacao;
        if (!SITUACOES.contains(filtro)) {
            throw Validacoes.recusar("situacao", "Escolha a situação: aceita, para revisão, mantida, não aceita ou cancelada.");
        }
        StringBuilder onde = new StringBuilder("d.situacao = ?");
        List<Object> args = new ArrayList<>();
        args.add(filtro);
        if (vendedorId != null) {
            onde.append(" AND d.vendedor_id = ?");
            args.add(vendedorId);
        }
        return consultarOrdenado(onde.toString() + " ORDER BY d.devolvida_em DESC, d.id DESC LIMIT 500", args.toArray());
    }

    /**
     * As vendas concluídas do vendedor com a peça (§8: o leitor localiza a venda original), da mais nova para a mais
     * antiga, no máximo 20. Pelo código lido (sem diferenciar maiúsculas, sem espaços nas pontas) ou pelo produto.
     */
    public VendasComAPeca vendasComAPeca(UUID vendedorId, String codigo, UUID produtoId) {
        Optional<UUID> produto = produtoId != null ? Optional.of(produtoId) : codigos.produtoDoCodigo(codigo);
        if (produto.isEmpty()) {
            return new VendasComAPeca(null, List.of());
        }
        Peca peca = jdbc.query("SELECT id, nome, foto_principal_hash FROM corona.produtos WHERE id = ? AND deleted_at IS NULL",
                (rs, n) -> new Peca(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), produto.get())
                .stream().findFirst().orElse(null);
        if (peca == null) {
            return new VendasComAPeca(null, List.of());
        }
        int prazoArrependimento = configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS);
        int prazoDefeito = configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_DEFEITO_DIAS);
        List<VendaComAPeca> vendas = jdbc.query("""
                SELECT v.id, v.cliente_id, c.nome, v.vendida_em, v.total,
                       coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p
                                  WHERE p.venda_id = v.id AND p.tipo = 'PAGAMENTO'), 0) AS pago
                  FROM corona.vendas v JOIN corona.clientes c ON c.id = v.cliente_id
                 WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL
                   AND EXISTS (SELECT 1 FROM corona.itens_venda i WHERE i.venda_id = v.id AND i.produto_id = ?)
                 ORDER BY v.vendida_em DESC, v.id DESC
                 LIMIT ?""", (rs, n) -> {
                    Instant vendidaEm = rs.getTimestamp("vendida_em").toInstant();
                    LocalDate dia = LocalDate.ofInstant(vendidaEm, BRASILIA);
                    BigDecimal total = rs.getBigDecimal("total");
                    return new VendaComAPeca(rs.getObject("id", UUID.class), rs.getObject("cliente_id", UUID.class),
                            rs.getString("nome"), vendidaEm, total, rs.getBigDecimal("pago").compareTo(total) < 0,
                            dia.plusDays(prazoArrependimento), dia.plusDays(prazoDefeito), new ArrayList<>());
                }, vendedorId, peca.id(), LIMITE_DE_VENDAS);
        for (VendaComAPeca v : vendas) {
            v.itens().addAll(jdbc.query("""
                    SELECT i.id, i.produto_id, p.nome, p.foto_principal_hash, i.quantidade,
                           i.preco_unitario * i.quantidade - i.desconto AS total,
                           coalesce((SELECT sum(x.quantidade) FROM corona.itens_devolucao x
                                       JOIN corona.devolucoes d ON d.id = x.devolucao_id
                                      WHERE x.item_venda_id = i.id AND d.situacao <> 'CANCELADA'), 0) AS devolvidas
                      FROM corona.itens_venda i JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE i.venda_id = ?
                     ORDER BY (i.produto_id = ?) DESC, p.nome, i.produto_id""", (rs, n) -> {
                        int vendidas = rs.getInt("quantidade");
                        int devolvidas = rs.getInt("devolvidas");
                        return new LinhaDaVendaComAPeca(rs.getObject("id", UUID.class),
                                new Peca(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                                        rs.getString("foto_principal_hash")),
                                vendidas, devolvidas, Math.max(0, vendidas - devolvidas), rs.getBigDecimal("total"));
                    }, v.vendaId(), peca.id()));
        }
        return new VendasComAPeca(peca, vendas);
    }

    private List<Devolucao> consultar(String onde, Object[] args) {
        return consultarOrdenado(onde + " ORDER BY d.devolvida_em DESC, d.id DESC", args);
    }

    private List<Devolucao> consultarOrdenado(String ondeEOrdem, Object[] args) {
        List<Devolucao> cabecas = jdbc.query("""
                SELECT d.id, d.venda_id, d.vendedor_id, d.cliente_id, c.nome, d.remessa_id, d.motivo, d.observacao, d.destino,
                       d.dentro_garantia, d.situacao, d.valor_devolvido, d.valor_reembolsado, d.valor_em_credito, d.forma_reembolso,
                       d.devolvida_em, d.hora_ajustada, d.cancelada_em, d.motivo_cancelamento, d.autorizacao_id,
                       d.revisao_fora_do_prazo, d.revisao_acima_do_limite_semanal, d.revisao_acima_do_valor_de_aprovacao,
                       d.revisao_autorizacao_invalida, d.revisao_reembolso_maior_que_pago, d.revisada_em, d.observacao_revisao,
                       ct.data_cotacao, ct.usd_venda, ct.pyg_venda
                  FROM corona.devolucoes d JOIN corona.clientes c ON c.id = d.cliente_id
                  LEFT JOIN corona.cotacoes ct ON ct.id = d.cotacao_id
                 WHERE d.deleted_at IS NULL AND
                       """ + ondeEOrdem, (rs, n) -> {
                    BigDecimal devolvido = rs.getBigDecimal("valor_devolvido");
                    BigDecimal usd = rs.getBigDecimal("usd_venda");
                    BigDecimal pyg = rs.getBigDecimal("pyg_venda");
                    Cotacao cotacao = usd == null ? null : new Cotacao(rs.getObject("data_cotacao", LocalDate.class), usd, pyg);
                    List<String> motivos = new ArrayList<>();
                    for (MotivoDaRevisao m : MotivoDaRevisao.values()) {
                        if (rs.getBoolean(m.coluna())) {
                            motivos.add(m.name());
                        }
                    }
                    String situacao = rs.getString("situacao");
                    return new Devolucao(rs.getObject("id", UUID.class), rs.getObject("venda_id", UUID.class),
                            rs.getObject("vendedor_id", UUID.class), rs.getObject("cliente_id", UUID.class), rs.getString("nome"),
                            rs.getObject("remessa_id", UUID.class), rs.getString("motivo"), rs.getString("observacao"),
                            rs.getString("destino"), rs.getBoolean("dentro_garantia"), situacao, devolvido,
                            usd == null ? null : ConversaoDeMoeda.paraDolar(devolvido, usd),
                            pyg == null ? null : ConversaoDeMoeda.paraGuarani(devolvido, pyg),
                            rs.getBigDecimal("valor_reembolsado"), rs.getBigDecimal("valor_em_credito"), rs.getString("forma_reembolso"), cotacao,
                            rs.getTimestamp("devolvida_em").toInstant(), rs.getBoolean("hora_ajustada"),
                            rs.getTimestamp("cancelada_em") == null ? null : rs.getTimestamp("cancelada_em").toInstant(),
                            rs.getString("motivo_cancelamento"), rs.getObject("autorizacao_id", UUID.class), List.copyOf(motivos),
                            rs.getTimestamp("revisada_em") == null ? null : rs.getTimestamp("revisada_em").toInstant(),
                            rs.getString("observacao_revisao"), "NAO_ACEITA".equals(situacao) ? devolvido : null,
                            new ArrayList<>());
                }, args);
        for (Devolucao d : cabecas) {
            d.itens().addAll(jdbc.query("""
                    SELECT x.id, x.item_venda_id, i.produto_id, p.nome, p.foto_principal_hash, x.quantidade, x.valor,
                           x.estado_peca, x.destino_peca
                      FROM corona.itens_devolucao x JOIN corona.itens_venda i ON i.id = x.item_venda_id
                      JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE x.devolucao_id = ? ORDER BY p.nome, i.produto_id, x.estado_peca""", (rs, n) -> new ItemDaDevolucao(
                            rs.getObject("id", UUID.class), rs.getObject("item_venda_id", UUID.class),
                            new Peca(rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                                    rs.getString("foto_principal_hash")),
                            rs.getInt("quantidade"), rs.getBigDecimal("valor"), rs.getString("estado_peca"),
                            rs.getString("destino_peca")), d.id()));
        }
        return cabecas;
    }
}
