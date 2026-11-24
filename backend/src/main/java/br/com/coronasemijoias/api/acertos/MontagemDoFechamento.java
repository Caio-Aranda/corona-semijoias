package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Estorno;
import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Original;
import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Venda;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.Contagem;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.PecasParaALoja;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.SaldoDeMaleta;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.cotacoes.Cotacao;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O cálculo do fechamento, que só LÊ (tarefa 7.5; ADR-0012, itens 3.6, 5 e 8): a prévia e o fechamento usam este mesmo código
 * (o fechamento o chama depois de travar). Junta os eventos sem acerto do vendedor, o esperado x contado, a comissão por meta e
 * as pendências que impedem ou que serão cobradas. Nada é gravado aqui.
 */
@Component
class MontagemDoFechamento {

    /** Um evento que entra no acerto. {@code id} é o do registro novo em {@code acerto_eventos}. */
    record Evento(UUID id, String tipo, UUID origemId, UUID vendaId, BigDecimal valor, Instant quando) {
    }

    record VendaLida(UUID id, UUID eventoId, BigDecimal subtotal, BigDecimal desconto, BigDecimal total, BigDecimal taxa) {
    }

    record NaoAceita(String tipo, String origemTipo, UUID origemId, BigDecimal valor) {
    }

    record Antiga(UUID id, String tipo, UUID produtoId, Integer quantidade, BigDecimal valor) {
    }

    record Revisao(String tipo, UUID id) {
    }

    record Produto(UUID id, String nome, String codigo, String fotoHash) {
    }

    record Maleta(UUID id, Instant enviadaEm) {
    }

    /** Tudo o que o fechamento sabe antes das decisões da dona. */
    record Montagem(UUID acertoId, UUID vendedorId, List<Maleta> maletas, List<Evento> eventos, List<VendaLida> vendas,
            EsperadoEContado.Resultado esperadoEContado, List<Revisao> revisoes, List<NaoAceita> naoAceitas, List<Antiga> antigas,
            Cotacao cotacao, long metaGuarani, CalculoDaComissao.Resultado comissao, BigDecimal vendidoBruto, BigDecimal descontos,
            BigDecimal devolvido, BigDecimal vendidoLiquido, Map<UUID, Produto> produtos, Map<UUID, Integer> defeitosContados,
            List<SaldoDeMaleta> saldos, List<PecasParaALoja> loja, List<Contagem> contagens) {

        /** O mesmo esperado x contado com as aprovações da dona (quantos defeitos de cada peça ela aprovou). */
        Montagem comAprovadas(Map<UUID, Integer> aprovadas) {
            return new Montagem(acertoId, vendedorId, maletas, eventos, vendas, EsperadoEContado.calcular(saldos, loja, contagens, aprovadas),
                    revisoes, naoAceitas, antigas, cotacao, metaGuarani, comissao, vendidoBruto, descontos, devolvido, vendidoLiquido,
                    produtos, defeitosContados, saldos, loja, contagens);
        }
    }

    private final JdbcTemplate jdbc;
    private final Configuracoes configuracoes;
    private final CotacaoVigente cotacaoVigente;

    private final java.time.Clock relogio;

    MontagemDoFechamento(JdbcTemplate jdbc, Configuracoes configuracoes, CotacaoVigente cotacaoVigente, java.time.Clock relogio) {
        this.relogio = relogio;
        this.jdbc = jdbc;
        this.configuracoes = configuracoes;
        this.cotacaoVigente = cotacaoVigente;
    }

    Montagem montar(UUID acertoId, UUID vendedorId) {
        Cotacao cotacao = cotacaoVigente.vigenteEm(relogio.instant())
                .orElseThrow(() -> new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEM_COTACAO_PARA_O_ACERTO));

        // --- os eventos do vendedor ainda sem acerto ---
        List<Evento> eventos = new ArrayList<>();
        List<VendaLida> vendas = new ArrayList<>();
        BigDecimal[] taxaDeReserva = new BigDecimal[1];
        jdbc.query("""
                SELECT v.id, v.subtotal, v.desconto, v.total, v.vendida_em, c.pyg_venda
                  FROM corona.vendas v LEFT JOIN corona.cotacoes c ON c.id = v.cotacao_id
                 WHERE v.vendedor_id = ? AND v.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'VENDA' AND e.origem_id = v.id)
                 ORDER BY v.vendida_em, v.id""", rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    Instant vendidaEm = rs.getTimestamp("vendida_em").toInstant();
                    BigDecimal taxa = rs.getBigDecimal("pyg_venda");
                    if (taxa == null) {
                        taxa = cotacaoVigente.vigenteEm(vendidaEm).map(c -> c.taxas().pygVenda()).orElseGet(() -> {
                            if (taxaDeReserva[0] == null) {
                                List<BigDecimal> antiga = jdbc.queryForList(
                                        "SELECT pyg_venda FROM corona.cotacoes ORDER BY vigente_desde, id LIMIT 1", BigDecimal.class);
                                taxaDeReserva[0] = antiga.isEmpty() ? cotacao.taxas().pygVenda() : antiga.get(0);
                            }
                            return taxaDeReserva[0];
                        });
                    }
                    UUID evento = UuidV7.gerar();
                    BigDecimal total = rs.getBigDecimal("total");
                    vendas.add(new VendaLida(id, evento, rs.getBigDecimal("subtotal"), rs.getBigDecimal("desconto"), total, taxa));
                    eventos.add(new Evento(evento, "VENDA", id, id, total, vendidaEm));
                }, vendedorId);
        jdbc.query("""
                SELECT v.id, v.total, v.estornada_em FROM corona.vendas v
                 WHERE v.vendedor_id = ? AND v.deleted_at IS NULL AND v.status = 'ESTORNADA'
                   AND NOT EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'ESTORNO_VENDA' AND e.origem_id = v.id)
                 ORDER BY v.estornada_em, v.id""", rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    eventos.add(new Evento(UuidV7.gerar(), "ESTORNO_VENDA", id, id, rs.getBigDecimal("total").negate(),
                            rs.getTimestamp("estornada_em").toInstant()));
                }, vendedorId);
        List<UUID> devolucoes = new ArrayList<>();
        List<UUID> naoAceitasDevolucoes = new ArrayList<>();
        jdbc.query("""
                SELECT d.id, d.venda_id, d.valor_devolvido, d.devolvida_em, d.situacao FROM corona.devolucoes d
                 WHERE d.vendedor_id = ? AND d.deleted_at IS NULL AND d.situacao NOT IN ('CANCELADA', 'EM_REVISAO')
                   AND NOT EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'DEVOLUCAO' AND e.origem_id = d.id)
                 ORDER BY d.devolvida_em, d.id""", rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    devolucoes.add(id);
                    if ("NAO_ACEITA".equals(rs.getString("situacao"))) {
                        naoAceitasDevolucoes.add(id);
                    }
                    eventos.add(new Evento(UuidV7.gerar(), "DEVOLUCAO", id, rs.getObject("venda_id", UUID.class),
                            rs.getBigDecimal("valor_devolvido").negate(), rs.getTimestamp("devolvida_em").toInstant()));
                }, vendedorId);

        // --- esperado x contado ---
        List<Maleta> maletas = jdbc.query("SELECT id, enviada_em FROM corona.remessas WHERE acerto_id = ? ORDER BY id",
                (rs, n) -> new Maleta(rs.getObject(1, UUID.class), rs.getTimestamp(2) == null ? Instant.EPOCH : rs.getTimestamp(2).toInstant()),
                acertoId);
        List<SaldoDeMaleta> saldos = jdbc.query("""
                SELECT s.remessa_id, r.enviada_em, s.produto_id, s.saldo, coalesce(i.preco_remessa, p.preco_venda) AS preco
                  FROM (SELECT remessa_id, produto_id, sum(q) AS saldo FROM (
                            SELECT i2.remessa_id, i2.produto_id, i2.qtd_enviada AS q FROM corona.itens_remessa i2
                              JOIN corona.remessas r2 ON r2.id = i2.remessa_id WHERE r2.acerto_id = ? AND i2.deleted_at IS NULL
                            UNION ALL
                            SELECT m.remessa_id, m.produto_id, m.quantidade FROM corona.movimentos_remessa m
                              JOIN corona.remessas r2 ON r2.id = m.remessa_id WHERE r2.acerto_id = ?) x
                         GROUP BY remessa_id, produto_id) s
                  JOIN corona.remessas r ON r.id = s.remessa_id JOIN corona.produtos p ON p.id = s.produto_id
                  LEFT JOIN corona.itens_remessa i ON i.remessa_id = s.remessa_id AND i.produto_id = s.produto_id AND i.deleted_at IS NULL
                 WHERE s.saldo <> 0""", (rs, n) -> new SaldoDeMaleta(rs.getObject("remessa_id", UUID.class),
                rs.getTimestamp("enviada_em") == null ? Instant.EPOCH : rs.getTimestamp("enviada_em").toInstant(),
                rs.getObject("produto_id", UUID.class), rs.getInt("saldo"), rs.getBigDecimal("preco")), acertoId, acertoId);
        List<PecasParaALoja> loja = devolucoes.isEmpty() ? List.of() : jdbc.query("""
                SELECT iv.produto_id, sum(idv.quantidade) AS quantidade, sum(idv.valor) AS valor
                  FROM corona.itens_devolucao idv JOIN corona.itens_venda iv ON iv.id = idv.item_venda_id
                 WHERE idv.destino_peca = 'LOJA' AND idv.devolucao_id IN (""" + marcadores(devolucoes.size()) + """
                 ) GROUP BY iv.produto_id""", (rs, n) -> new PecasParaALoja(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3)),
                devolucoes.toArray());
        List<Contagem> contagens = jdbc.query("""
                SELECT produto_id, coalesce(sum(quantidade) FILTER (WHERE estado_peca = 'BOA'), 0) AS boa,
                       coalesce(sum(quantidade) FILTER (WHERE estado_peca = 'DEFEITO'), 0) AS defeito
                  FROM corona.leituras_acerto WHERE acerto_id = ? GROUP BY produto_id""",
                (rs, n) -> new Contagem(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3)), acertoId);
        Map<UUID, Integer> defeitos = new LinkedHashMap<>();
        for (Contagem c : contagens) {
            if (c.defeito() > 0) {
                defeitos.put(c.produtoId(), c.defeito());
            }
        }
        EsperadoEContado.Resultado esperado = EsperadoEContado.calcular(saldos, loja, contagens, Map.of());

        // --- o que impede e o que será cobrado ---
        List<Revisao> revisoes = new ArrayList<>();
        jdbc.query("SELECT id FROM corona.devolucoes WHERE vendedor_id = ? AND deleted_at IS NULL AND situacao = 'EM_REVISAO' ORDER BY id",
                rs -> {
                    revisoes.add(new Revisao("DEVOLUCAO", rs.getObject(1, UUID.class)));
                }, vendedorId);
        jdbc.query("""
                SELECT c.id FROM corona.creditos_sem_saldo c JOIN corona.vendas v ON v.id = c.venda_id
                 WHERE v.vendedor_id = ? AND c.situacao = 'EM_REVISAO' ORDER BY c.id""", rs -> {
                    revisoes.add(new Revisao("CREDITO_SEM_SALDO", rs.getObject(1, UUID.class)));
                }, vendedorId);
        List<NaoAceita> naoAceitas = new ArrayList<>();
        for (UUID d : naoAceitasDevolucoes) {
            BigDecimal valor = jdbc.queryForObject("SELECT valor_devolvido FROM corona.devolucoes WHERE id = ?", BigDecimal.class, d);
            naoAceitas.add(new NaoAceita("DEVOLUCAO_NAO_ACEITA", "DEVOLUCAO", d, valor));
        }
        jdbc.query("""
                SELECT c.id, c.valor_pedido, c.valor_usado FROM corona.creditos_sem_saldo c JOIN corona.vendas v ON v.id = c.venda_id
                 WHERE v.vendedor_id = ? AND c.situacao = 'NAO_ACEITA'
                   AND NOT EXISTS (SELECT 1 FROM corona.pendencias_acerto p WHERE p.origem_tipo = 'CREDITO_SEM_SALDO' AND p.origem_id = c.id)
                 ORDER BY c.id""", rs -> {
                    naoAceitas.add(new NaoAceita("CREDITO_SEM_SALDO_NAO_ACEITO", "CREDITO_SEM_SALDO", rs.getObject(1, UUID.class),
                            Dinheiro.subtrair(rs.getBigDecimal(2), rs.getBigDecimal(3))));
                }, vendedorId);
        List<Antiga> antigas = jdbc.query("""
                SELECT id, tipo, produto_id, quantidade, valor FROM corona.pendencias_acerto
                 WHERE vendedor_id = ? AND status = 'ABERTA' AND acerto_id <> ? ORDER BY id""",
                (rs, n) -> new Antiga(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        (Integer) rs.getObject(4), rs.getBigDecimal(5)), vendedorId, acertoId);

        // --- a comissão ---
        List<Evento> deEstorno = eventos.stream().filter(e -> !e.tipo().equals("VENDA"))
                .sorted(Comparator.comparing(Evento::quando).thenComparing(Evento::origemId)).toList();
        List<Estorno> estornos = new ArrayList<>();
        Map<UUID, Original> originais = new HashMap<>();
        Map<UUID, VendaLida> daVendaPorId = new HashMap<>();
        vendas.forEach(v -> daVendaPorId.put(v.id(), v));
        for (Evento e : deEstorno) {
            estornos.add(new Estorno(e.id(), e.vendaId(), e.valor().negate()));
            if (!daVendaPorId.containsKey(e.vendaId()) && !originais.containsKey(e.vendaId())) {
                List<Map<String, Object>> linha = jdbc.queryForList(
                        "SELECT base, valor, percentual FROM corona.comissoes WHERE venda_id = ? AND tipo = 'COMISSAO'", e.vendaId());
                if (linha.isEmpty()) {
                    throw new IllegalStateException("A venda " + e.vendaId() + " tem estorno mas não tem comissão gravada.");
                }
                List<BigDecimal> antes = jdbc.query("""
                        SELECT -valor FROM corona.acerto_eventos WHERE venda_id = ? AND origem_tipo <> 'VENDA' ORDER BY sync_seq""",
                        (rs, n) -> rs.getBigDecimal(1), e.vendaId());
                Map<String, Object> l = linha.get(0);
                originais.put(e.vendaId(), new Original((BigDecimal) l.get("base"), (BigDecimal) l.get("valor"),
                        (BigDecimal) l.get("percentual"), antes));
            }
        }
        long meta = configuracoes.inteiro(ChaveDeConfiguracao.COMISSAO_META_GUARANI);
        CalculoDaComissao.Resultado comissao = CalculoDaComissao.calcular(meta,
                BigDecimal.valueOf(configuracoes.inteiro(ChaveDeConfiguracao.COMISSAO_PERCENTUAL_ABAIXO_DA_META)),
                BigDecimal.valueOf(configuracoes.inteiro(ChaveDeConfiguracao.COMISSAO_PERCENTUAL_NA_META)),
                vendas.stream().map(v -> new Venda(v.id(), v.eventoId(), v.total(), v.taxa())).toList(), estornos, originais);

        // --- os totais do item 8 ---
        BigDecimal bruto = Dinheiro.somar(vendas.stream().map(VendaLida::subtotal).toList());
        BigDecimal descontos = Dinheiro.somar(vendas.stream().map(VendaLida::desconto).toList());
        BigDecimal devolvido = Dinheiro.somar(deEstorno.stream().map(e -> e.valor().negate()).toList());
        BigDecimal liquido = Dinheiro.subtrair(Dinheiro.subtrair(bruto, descontos), devolvido);

        Map<UUID, Produto> produtos = produtosDe(esperado, contagens, antigas);
        return new Montagem(acertoId, vendedorId, maletas, List.copyOf(eventos), List.copyOf(vendas), esperado, revisoes, naoAceitas,
                antigas, cotacao, meta, comissao, bruto, descontos, devolvido, liquido, produtos, defeitos, saldos, loja, contagens);
    }

    private Map<UUID, Produto> produtosDe(EsperadoEContado.Resultado r, List<Contagem> contagens, List<Antiga> antigas) {
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        r.itens().forEach(i -> ids.add(i.produtoId()));
        r.sobras().forEach(s -> ids.add(s.produtoId()));
        contagens.forEach(c -> ids.add(c.produtoId()));
        antigas.stream().filter(a -> a.produtoId() != null).forEach(a -> ids.add(a.produtoId()));
        Map<UUID, Produto> mapa = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return mapa;
        }
        jdbc.query("""
                SELECT p.id, p.nome, p.foto_principal_hash,
                       (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.ativo AND c.deleted_at IS NULL
                         ORDER BY c.principal DESC, c.codigo LIMIT 1) AS codigo
                  FROM corona.produtos p WHERE p.id IN (""" + marcadores(ids.size()) + ")", rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    mapa.put(id, new Produto(id, rs.getString(2), rs.getString(4), rs.getString(3)));
                }, ids.toArray());
        return mapa;
    }

    static String marcadores(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
