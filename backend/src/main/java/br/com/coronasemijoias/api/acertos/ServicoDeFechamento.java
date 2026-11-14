package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.Antiga;
import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.Evento;
import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.Montagem;
import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.NaoAceita;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.erros.Problemas;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O fechamento do acerto (tarefa 7.5; ADR-0012, itens 3.6, 5, 6, 8, 13 e 14): uma transação só, tudo ou nada. Único lugar que
 * escreve em {@code itens_acerto}, {@code acerto_eventos} e {@code comissoes} (e, com o {@code ServicoDePendencias}, em
 * {@code pendencias_acerto}); a transição do acerto para {@code ACERTADO} e das maletas para {@code ACERTADA} também é daqui.
 *
 * <p>Ordem das travas: vendedor ({@code FOR NO KEY UPDATE}), a trava consultiva da devolução e da troca do vendedor, o acerto,
 * as maletas do acerto por id, as pendências antigas por id e os produtos por id (dentro do {@code ServicoDeEstoque}, numa
 * chamada só com todas as peças).
 */
@Service
public class ServicoDeFechamento {

    static final int LIMITE_DO_MOTIVO = 500;
    private static final Set<String> DECISOES_DE_COBRANCA = Set.of("COBRAR_NO_ACERTO", "COBRAR_DEPOIS", "LOJA_ASSUME");

    public record DecisaoDeFalta(UUID produtoId, String decisao, String motivo) {
    }

    public record DecisaoDeDefeito(UUID produtoId, Integer aprovadas) {
    }

    public record DecisaoDeCobranca(UUID origemId, String decisao, String motivo) {
    }

    public record DecisaoDePendenciaAntiga(UUID pendenciaId, String decisao) {
    }

    /** O pedido de "fechar". {@code liquidoMostrado} é só conferência: nunca entra no cálculo nem é gravado. */
    public record PedidoDeFechamento(UUID id, List<DecisaoDeFalta> faltas, List<DecisaoDeDefeito> defeitos,
            List<DecisaoDeCobranca> cobrancas, List<DecisaoDePendenciaAntiga> pendenciasAntigas, BigDecimal liquidoMostrado) {
    }

    /** As decisões já conferidas na forma. */
    record Decisoes(Map<UUID, DecisaoDeFalta> faltas, Map<UUID, Integer> aprovadas, Map<UUID, DecisaoDeCobranca> cobrancas,
            Map<UUID, String> antigas) {
    }

    private record PendenciaNova(UUID id, String tipo, UUID produtoId, UUID remessaId, Integer quantidade, BigDecimal valor,
            String origemTipo, UUID origemId, String status, String motivo) {
    }

    /** O que as decisões produzem. */
    private record Calculo(List<PendenciaNova> pendencias, List<UUID> antigasCobradas, BigDecimal faltasCobradas, BigDecimal liquido) {
    }

    public record Resultado(UUID acertoId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final MontagemDoFechamento montagem;
    private final ServicoDeEstoque estoque;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;
    private volatile Runnable ganchoDeTeste = () -> { };

    ServicoDeFechamento(JdbcTemplate jdbc, MontagemDoFechamento montagem, ServicoDeEstoque estoque, ServicoDeAuditoria auditoria,
            Clock relogio) {
        this.jdbc = jdbc;
        this.montagem = montagem;
        this.estoque = estoque;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** Só para os testes: roda depois dos movimentos de maleta e estoque, para provar o "tudo ou nada". */
    public void definirGanchoDeTeste(Runnable gancho) {
        this.ganchoDeTeste = gancho == null ? () -> { } : gancho;
    }

    // --- a prévia ---

    /** A prévia: o mesmo cálculo do fechamento, nada é gravado. Sem decisões, assume cobrar tudo no acerto e não aprovar defeito. */
    @Transactional(readOnly = true)
    public Map<String, Object> previa(UUID acertoId, PedidoDeFechamento pedido) {
        UUID vendedorId = vendedorDoAcerto(acertoId);
        String status = jdbc.queryForObject("SELECT status FROM corona.acertos WHERE id = ?", String.class, acertoId);
        if ("ACERTADO".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_JA_FECHADO);
        }
        if (!"EM_ACERTO".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_NAO_ESTA_EM_ANDAMENTO);
        }
        return previaDe(acertoId, vendedorId, pedido);
    }

    private Map<String, Object> previaDe(UUID acertoId, UUID vendedorId, PedidoDeFechamento pedido) {
        Montagem m = montagem.montar(acertoId, vendedorId);
        Decisoes d = pedido == null ? padrao(m) : decisoesDe(pedido, m, false);
        m = m.comAprovadas(d.aprovadas());
        return montarPrevia(m, d, calcular(m, d, acertoId));
    }

    private Decisoes padrao(Montagem m) {
        Map<UUID, DecisaoDeFalta> faltas = new LinkedHashMap<>();
        m.esperadoEContado().itens().stream().filter(i -> i.falta() > 0)
                .forEach(i -> faltas.put(i.produtoId(), new DecisaoDeFalta(i.produtoId(), "COBRAR_NO_ACERTO", null)));
        Map<UUID, DecisaoDeCobranca> cobrancas = new LinkedHashMap<>();
        m.naoAceitas().forEach(n -> cobrancas.put(n.origemId(), new DecisaoDeCobranca(n.origemId(), "COBRAR_NO_ACERTO", null)));
        Map<UUID, Integer> aprovadas = new LinkedHashMap<>();
        m.defeitosContados().keySet().forEach(p -> aprovadas.put(p, 0));
        return new Decisoes(faltas, aprovadas, cobrancas, Map.of());
    }

    // --- fechar ---

    /**
     * Fecha o acerto. Mesmo id e mesmas decisões devolve 200 sem efeito novo; outras decisões, 409. As decisões e o líquido
     * mostrado têm de bater com o cálculo de agora; senão 409 com a prévia nova.
     */
    @Transactional
    public Resultado fechar(UUID usuarioId, UUID acertoId, PedidoDeFechamento pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("fechamento", "Informe o fechamento.");
        }
        UUID comando = Validacoes.idDoPedido(pedido.id());
        UUID vendedorId = vendedorDoAcerto(acertoId);
        ServicoDeAcertos.trancarVendedor(jdbc, vendedorId);
        ServicoDeDevolucoes.trancarVendedor(jdbc, vendedorId);
        Map<String, Object> acerto = jdbc.queryForMap("SELECT status, fechamento_id, conteudo_do_fechamento FROM corona.acertos WHERE id = ? FOR UPDATE",
                acertoId);
        String status = (String) acerto.get("status");
        String resumo = resumoDe(pedido);
        if ("ACERTADO".equals(status)) {
            if (comando.equals(acerto.get("fechamento_id"))) {
                if (resumo.equals(acerto.get("conteudo_do_fechamento"))) {
                    return new Resultado(acertoId, true);
                }
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_JA_REGISTRADO_DIFERENTE);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_JA_FECHADO);
        }
        if (!"EM_ACERTO".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_NAO_ESTA_EM_ANDAMENTO);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.acertos WHERE fechamento_id = ?)", Boolean.class, comando))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_JA_REGISTRADO_DIFERENTE);
        }
        jdbc.queryForList("SELECT id FROM corona.remessas WHERE acerto_id = ? ORDER BY id FOR UPDATE", UUID.class, acertoId);
        jdbc.queryForList("SELECT id FROM corona.pendencias_acerto WHERE vendedor_id = ? AND status = 'ABERTA' ORDER BY id FOR UPDATE",
                UUID.class, vendedorId);

        Montagem m = montagem.montar(acertoId, vendedorId);
        if (!m.revisoes().isEmpty()) {
            ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, CodigoErro.REVISAO_PENDENTE_NO_ACERTO);
            corpo.setProperty("revisoes", m.revisoes());
            throw ErroDaApi.comCorpo(HttpStatus.CONFLICT, CodigoErro.REVISAO_PENDENTE_NO_ACERTO, corpo);
        }
        if (!m.esperadoEContado().sobras().isEmpty()) {
            ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, CodigoErro.SOBRA_NO_ACERTO);
            corpo.setProperty("sobras", m.esperadoEContado().sobras());
            throw ErroDaApi.comCorpo(HttpStatus.CONFLICT, CodigoErro.SOBRA_NO_ACERTO, corpo);
        }
        Decisoes d = decisoesDe(pedido, m, true);
        m = m.comAprovadas(d.aprovadas());
        Calculo c = calcular(m, d, acertoId);
        if (pedido.liquidoMostrado() == null) {
            throw Validacoes.recusar("liquidoMostrado", "Confira o acerto antes de fechar.");
        }
        if (pedido.liquidoMostrado().compareTo(c.liquido()) != 0) {
            throw desatualizado(montarPrevia(m, d, c));
        }

        gravar(usuarioId, acertoId, comando, resumo, m, d, c);
        return new Resultado(acertoId, false);
    }

    private ErroDaApi desatualizado(Map<String, Object> previa) {
        ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_DESATUALIZADO);
        corpo.setProperty("previa", previa);
        return ErroDaApi.comCorpo(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_DESATUALIZADO, corpo);
    }

    private UUID vendedorDoAcerto(UUID acertoId) {
        UUID vendedor = jdbc.query("SELECT vendedor_id FROM corona.acertos WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, acertoId);
        if (vendedor == null) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
        }
        return vendedor;
    }

    // --- decisões ---

    /** Confere a forma (400) e, quando {@code exata}, que as decisões são exatamente as do cálculo (409 com a prévia nova). */
    private Decisoes decisoesDe(PedidoDeFechamento pedido, Montagem m, boolean exata) {
        Map<UUID, DecisaoDeFalta> faltas = new LinkedHashMap<>();
        for (DecisaoDeFalta f : lista(pedido.faltas())) {
            if (f == null || f.produtoId() == null || f.decisao() == null || !DECISOES_DE_COBRANCA.contains(f.decisao())) {
                throw Validacoes.recusar("faltas", "Escolha o que fazer com cada peça que faltou.");
            }
            exigirMotivo(f.decisao(), f.motivo());
            faltas.put(f.produtoId(), f);
        }
        Map<UUID, Integer> aprovadas = new LinkedHashMap<>();
        for (DecisaoDeDefeito x : lista(pedido.defeitos())) {
            if (x == null || x.produtoId() == null || x.aprovadas() == null || x.aprovadas() < 0) {
                throw Validacoes.recusar("defeitos", "Diga quantas peças com defeito você aprova.");
            }
            Integer contado = m.defeitosContados().get(x.produtoId());
            if (contado != null && x.aprovadas() > contado) {
                throw Validacoes.recusar("defeitos", "Você aprovou mais peças com defeito do que foram contadas.");
            }
            aprovadas.put(x.produtoId(), x.aprovadas());
        }
        Map<UUID, DecisaoDeCobranca> cobrancas = new LinkedHashMap<>();
        for (DecisaoDeCobranca x : lista(pedido.cobrancas())) {
            if (x == null || x.origemId() == null || x.decisao() == null || !DECISOES_DE_COBRANCA.contains(x.decisao())) {
                throw Validacoes.recusar("cobrancas", "Escolha o que fazer com cada devolução que você não aceitou.");
            }
            exigirMotivo(x.decisao(), x.motivo());
            cobrancas.put(x.origemId(), x);
        }
        Map<UUID, String> antigas = new LinkedHashMap<>();
        for (DecisaoDePendenciaAntiga x : lista(pedido.pendenciasAntigas())) {
            if (x == null || x.pendenciaId() == null || x.decisao() == null
                    || !Set.of("COBRAR_NO_ACERTO", "MANTER").contains(x.decisao())) {
                throw Validacoes.recusar("pendenciasAntigas", "Escolha cobrar agora ou manter cada pendência antiga.");
            }
            antigas.put(x.pendenciaId(), x.decisao());
        }
        if (exata) {
            Set<UUID> comFalta = new TreeSet<>();
            m.esperadoEContado().itens().stream().filter(i -> i.falta() > 0).forEach(i -> comFalta.add(i.produtoId()));
            Set<UUID> origens = new TreeSet<>();
            m.naoAceitas().forEach(n -> origens.add(n.origemId()));
            Set<UUID> idsAntigas = new TreeSet<>();
            m.antigas().forEach(a -> idsAntigas.add(a.id()));
            boolean igual = new TreeSet<>(faltas.keySet()).equals(comFalta) && new TreeSet<>(aprovadas.keySet()).equals(
                    new TreeSet<>(m.defeitosContados().keySet())) && new TreeSet<>(cobrancas.keySet()).equals(origens)
                    && idsAntigas.containsAll(antigas.keySet());
            if (!igual) {
                Decisoes padrao = padrao(m);
                throw desatualizado(montarPrevia(m, padrao, calcular(m, padrao, m.acertoId())));
            }
        }
        return new Decisoes(faltas, aprovadas, cobrancas, antigas);
    }

    private static void exigirMotivo(String decisao, String motivo) {
        String limpo = Validacoes.limpar(motivo);
        if ("LOJA_ASSUME".equals(decisao) && limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de a loja assumir.");
        }
        if (limpo != null && limpo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
    }

    private static <T> List<T> lista(List<T> l) {
        return l == null ? List.of() : l;
    }

    /** O resumo (SHA-256) das decisões, na ordem canônica: o líquido mostrado não entra. */
    private static String resumoDe(PedidoDeFechamento p) {
        StringBuilder s = new StringBuilder();
        new TreeMap<String, String>(porChave(lista(p.faltas()), f -> f == null ? "" : f.produtoId() + "",
                f -> f == null ? "" : f.decisao() + "|" + Validacoes.limpar(f.motivo()))).forEach((k, v) -> s.append("F").append(k).append("=").append(v).append(";"));
        new TreeMap<String, String>(porChave(lista(p.defeitos()), f -> f == null ? "" : f.produtoId() + "", f -> f == null ? "" : f.aprovadas() + ""))
                .forEach((k, v) -> s.append("D").append(k).append("=").append(v).append(";"));
        new TreeMap<String, String>(porChave(lista(p.cobrancas()), f -> f == null ? "" : f.origemId() + "",
                f -> f == null ? "" : f.decisao() + "|" + Validacoes.limpar(f.motivo()))).forEach((k, v) -> s.append("C").append(k).append("=").append(v).append(";"));
        new TreeMap<String, String>(porChave(lista(p.pendenciasAntigas()), f -> f == null ? "" : f.pendenciaId() + "", f -> f == null ? "" : f.decisao() + ""))
                .forEach((k, v) -> s.append("A").append(k).append("=").append(v).append(";"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> Map<String, String> porChave(List<T> itens, java.util.function.Function<T, String> chave,
            java.util.function.Function<T, String> valor) {
        Map<String, String> mapa = new HashMap<>();
        itens.forEach(i -> mapa.put(chave.apply(i), valor.apply(i)));
        return mapa;
    }

    // --- o cálculo das pendências e do líquido ---

    private Calculo calcular(Montagem m, Decisoes d, UUID acertoId) {
        List<PendenciaNova> pendencias = new ArrayList<>();
        List<BigDecimal> cobradas = new ArrayList<>();
        for (EsperadoEContado.Item i : m.esperadoEContado().itens()) {
            if (i.falta() > 0) {
                DecisaoDeFalta f = d.faltas().getOrDefault(i.produtoId(), new DecisaoDeFalta(i.produtoId(), "COBRAR_DEPOIS", null));
                String status = statusDe(f.decisao());
                pendencias.add(new PendenciaNova(UuidV7.gerar(), "FALTA", i.produtoId(), i.remessaId(), i.falta(), i.valorDaFalta(),
                        null, null, status, "LOJA_ASSUME".equals(f.decisao()) ? Validacoes.limpar(f.motivo()) : null));
                if ("COBRADA".equals(status)) {
                    cobradas.add(i.valorDaFalta());
                }
            }
        }
        Map<UUID, Integer> restoAprovado = new HashMap<>(d.aprovadas());
        for (Map.Entry<UUID, Integer> contado : m.defeitosContados().entrySet()) {
            int aprovadas = Math.min(restoAprovado.getOrDefault(contado.getKey(), 0), contado.getValue());
            int recusadas = contado.getValue() - aprovadas;
            UUID remessa = m.esperadoEContado().itens().stream().filter(i -> i.produtoId().equals(contado.getKey()) && i.remessaId() != null)
                    .map(EsperadoEContado.Item::remessaId).findFirst().orElse(null);
            if (aprovadas > 0) {
                pendencias.add(new PendenciaNova(UuidV7.gerar(), "DEFEITO", contado.getKey(), remessa, aprovadas, Dinheiro.somar(List.of()),
                        null, null, "APROVADA", null));
            }
            if (recusadas > 0) {
                pendencias.add(new PendenciaNova(UuidV7.gerar(), "DEFEITO", contado.getKey(), remessa, recusadas, Dinheiro.somar(List.of()),
                        null, null, "RECUSADA", null));
            }
        }
        for (NaoAceita n : m.naoAceitas()) {
            DecisaoDeCobranca x = d.cobrancas().getOrDefault(n.origemId(), new DecisaoDeCobranca(n.origemId(), "COBRAR_DEPOIS", null));
            String status = statusDe(x.decisao());
            pendencias.add(new PendenciaNova(UuidV7.gerar(), n.tipo(), null, null, null, n.valor(), n.origemTipo(), n.origemId(), status,
                    "LOJA_ASSUME".equals(x.decisao()) ? Validacoes.limpar(x.motivo()) : null));
            if ("COBRADA".equals(status)) {
                cobradas.add(n.valor());
            }
        }
        List<UUID> antigasCobradas = new ArrayList<>();
        for (Antiga a : m.antigas()) {
            if ("COBRAR_NO_ACERTO".equals(d.antigas().get(a.id()))) {
                antigasCobradas.add(a.id());
                cobradas.add(a.valor());
            }
        }
        BigDecimal faltas = Dinheiro.somar(cobradas);
        BigDecimal liquido = Dinheiro.somar(List.of(Dinheiro.subtrair(m.vendidoLiquido(), m.comissao().comissao()), faltas));
        return new Calculo(pendencias, antigasCobradas, faltas, liquido);
    }

    private static String statusDe(String decisao) {
        return switch (decisao) {
            case "COBRAR_NO_ACERTO" -> "COBRADA";
            case "COBRAR_DEPOIS" -> "ABERTA";
            default -> "ASSUMIDA_PELA_LOJA";
        };
    }

    // --- a gravação ---

    private void gravar(UUID usuarioId, UUID acertoId, UUID comando, String resumo, Montagem m, Decisoes d, Calculo c) {
        Timestamp agora = Timestamp.from(relogio.instant());
        for (Evento e : m.eventos()) {
            jdbc.update("""
                    INSERT INTO corona.acerto_eventos (id, acerto_id, vendedor_id, origem_tipo, origem_id, venda_id, valor)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", e.id(), acertoId, m.vendedorId(), e.tipo(), e.origemId(), e.vendaId(), e.valor());
        }
        Map<UUID, UUID> comissaoDaVenda = new HashMap<>();
        for (CalculoDaComissao.Linha l : m.comissao().linhas()) {
            UUID id = UuidV7.gerar();
            UUID estorna = null;
            if (l.tipo() == CalculoDaComissao.TipoDaLinha.COMISSAO) {
                comissaoDaVenda.put(l.vendaId(), id);
            } else {
                estorna = comissaoDaVenda.get(l.vendaId());
                if (estorna == null) {
                    estorna = jdbc.queryForObject("SELECT id FROM corona.comissoes WHERE venda_id = ? AND tipo = 'COMISSAO'", UUID.class, l.vendaId());
                }
            }
            jdbc.update("""
                    INSERT INTO corona.comissoes (id, vendedor_id, acerto_id, venda_id, evento_id, tipo, base, percentual, valor, estorna_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, m.vendedorId(), acertoId, l.vendaId(), l.eventoId(), l.tipo().name(), l.base(),
                    l.percentual(), l.valor(), estorna);
        }
        for (EsperadoEContado.Item i : m.esperadoEContado().itens()) {
            jdbc.update("""
                    INSERT INTO corona.itens_acerto (id, acerto_id, produto_id, remessa_id, preco_remessa, esperado, contado_boa,
                        contado_defeito, falta, defeito_aprovado) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), acertoId,
                    i.produtoId(), i.remessaId(), i.precoRemessa(), i.esperado(), i.contadoBoa(), i.contadoDefeito(), i.falta(),
                    i.defeitoAprovado());
        }
        for (PendenciaNova p : c.pendencias()) {
            boolean cobrada = "COBRADA".equals(p.status());
            boolean assumida = "ASSUMIDA_PELA_LOJA".equals(p.status());
            jdbc.update("""
                    INSERT INTO corona.pendencias_acerto (id, acerto_id, vendedor_id, tipo, produto_id, remessa_id, quantidade, valor,
                        origem_tipo, origem_id, status, cobrada_no_acerto_id, resolvida_em, resolvida_por, motivo)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", p.id(), acertoId, m.vendedorId(), p.tipo(), p.produtoId(),
                    p.remessaId(), p.quantidade(), p.valor(), p.origemTipo(), p.origemId(), p.status(), cobrada ? acertoId : null,
                    assumida ? agora : null, assumida ? usuarioId : null, p.motivo());
            if (assumida) {
                auditoria.registrar(Ator.usuario(usuarioId), "PENDENCIA_DECIDIDA", "pendencias_acerto", p.id(), null,
                        Map.of("status", p.status(), "motivo", String.valueOf(p.motivo())));
            }
        }
        for (UUID antiga : c.antigasCobradas()) {
            jdbc.update("UPDATE corona.pendencias_acerto SET status = 'COBRADA', cobrada_no_acerto_id = ? WHERE id = ?", acertoId, antiga);
            auditoria.registrar(Ator.usuario(usuarioId), "PENDENCIA_DECIDIDA", "pendencias_acerto", antiga, Map.of("status", "ABERTA"),
                    Map.of("status", "COBRADA", "acerto", acertoId.toString()));
        }

        // As maletas e o estoque, numa chamada só ao estoque (ordem global por produto).
        List<Pedido> central = new ArrayList<>();
        for (EsperadoEContado.Item i : m.esperadoEContado().itens()) {
            int contado = i.contadoBoa() + i.contadoDefeito();
            if (i.remessaId() != null) {
                if (i.falta() > 0) {
                    jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (?, ?, ?, 'PERDA', ?)",
                            UuidV7.gerar(), i.remessaId(), i.produtoId(), -i.falta());
                }
                if (contado > 0) {
                    jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (?, ?, ?, 'DEVOLUCAO_DONA', ?)",
                            UuidV7.gerar(), i.remessaId(), i.produtoId(), -contado);
                }
            }
            if (contado > 0) {
                central.add(new Pedido(UuidV7.gerar(), i.produtoId(), TipoDeMovimento.DEVOLUCAO, contado, null,
                        i.remessaId() != null ? OrigemDoMovimento.MALETA : OrigemDoMovimento.ACERTO,
                        i.remessaId() != null ? i.remessaId() : acertoId));
            }
            if (i.defeitoAprovado() > 0) {
                central.add(new Pedido(UuidV7.gerar(), i.produtoId(), TipoDeMovimento.AVARIA, i.defeitoAprovado(), null,
                        OrigemDoMovimento.ACERTO, acertoId));
            }
        }
        if (!central.isEmpty()) {
            estoque.registrar(central, usuarioId);
        }
        ganchoDeTeste.run();

        for (MontagemDoFechamento.Maleta maleta : m.maletas()) {
            jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", maleta.id());
        }
        jdbc.update("""
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = ?, fechado_em = ?, fechado_por = ?,
                       cotacao_id = ?, meta_guarani = ?, percentual_comissao = ?, vendido_para_meta_guarani = ?, vendido_bruto = ?,
                       descontos = ?, devolvido = ?, vendido_liquido = ?, comissao = ?, faltas_cobradas = ?, liquido = ?,
                       recebido_no_acerto = 0, ficou_devendo = ? WHERE id = ?""", comando, resumo, agora, usuarioId, m.cotacao().id(),
                (int) m.metaGuarani(), m.comissao().percentual(), m.comissao().vendidoParaMetaGuarani(), m.vendidoBruto(), m.descontos(),
                m.devolvido(), m.vendidoLiquido(), m.comissao().comissao(), c.faltasCobradas(), c.liquido(), c.liquido(), acertoId);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("vendido_bruto", m.vendidoBruto().toPlainString());
        depois.put("descontos", m.descontos().toPlainString());
        depois.put("devolvido", m.devolvido().toPlainString());
        depois.put("vendido_liquido", m.vendidoLiquido().toPlainString());
        depois.put("comissao", m.comissao().comissao().toPlainString());
        depois.put("faltas_cobradas", c.faltasCobradas().toPlainString());
        depois.put("liquido", c.liquido().toPlainString());
        depois.put("percentual_comissao", m.comissao().percentual().toPlainString());
        depois.put("meta_guarani", m.metaGuarani());
        depois.put("vendido_para_meta_guarani", m.comissao().vendidoParaMetaGuarani());
        depois.put("maletas", m.maletas().stream().map(x -> x.id().toString()).toList());
        depois.put("pendencias", c.pendencias().stream().map(p -> p.tipo() + ":" + p.status()).toList());
        auditoria.registrar(Ator.usuario(usuarioId), "ACERTO_FECHADO", "acertos", acertoId, Map.of("status", "EM_ACERTO"), depois);
    }

    // --- a prévia (JSON) ---

    private Map<String, Object> montarPrevia(Montagem m, Decisoes d, Calculo c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("acertoId", m.acertoId());
        List<Map<String, Object>> itens = new ArrayList<>();
        for (EsperadoEContado.Item i : m.esperadoEContado().itens()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("produto", m.produtos().get(i.produtoId()));
            l.put("remessaId", i.remessaId());
            l.put("esperado", i.esperado());
            l.put("contadoBoa", i.contadoBoa());
            l.put("contadoDefeito", i.contadoDefeito());
            l.put("falta", i.falta());
            l.put("valorDaFalta", i.valorDaFalta());
            itens.add(l);
        }
        r.put("itens", itens);
        r.put("sobras", m.esperadoEContado().sobras());
        r.put("faltas", c.pendencias().stream().filter(p -> p.tipo().equals("FALTA")).map(p -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("produto", m.produtos().get(p.produtoId()));
            l.put("remessaId", p.remessaId());
            l.put("quantidade", p.quantidade());
            l.put("valor", p.valor());
            l.put("status", p.status());
            return l;
        }).toList());
        List<Map<String, Object>> defeitos = new ArrayList<>();
        m.defeitosContados().forEach((p, q) -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("produto", m.produtos().get(p));
            l.put("contadoDefeito", q);
            defeitos.add(l);
        });
        r.put("defeitos", defeitos);
        r.put("eventos", m.eventos().stream().map(e -> Map.of("tipo", e.tipo(), "origemId", e.origemId(), "vendaId", e.vendaId(),
                "valor", e.valor())).toList());
        r.put("revisoesPendentes", m.revisoes());
        r.put("naoAceitas", m.naoAceitas());
        r.put("pendenciasAntigas", m.antigas());
        r.put("comissoes", m.comissao().linhas());
        r.put("metaGuarani", m.metaGuarani());
        r.put("vendidoParaMetaGuarani", m.comissao().vendidoParaMetaGuarani());
        r.put("percentualComissao", m.comissao().percentual());
        r.put("vendidoBruto", m.vendidoBruto());
        r.put("descontos", m.descontos());
        r.put("devolvido", m.devolvido());
        r.put("vendidoLiquido", m.vendidoLiquido());
        r.put("comissao", m.comissao().comissao());
        r.put("faltasCobradas", c.faltasCobradas());
        r.put("liquido", c.liquido());
        r.put("recebidoNoAcerto", Dinheiro.somar(List.of()));
        r.put("ficouDevendo", c.liquido());
        r.put("cotacaoId", m.cotacao().id());
        r.put("cotacaoData", m.cotacao().dataCotacao());
        r.put("podeFechar", m.revisoes().isEmpty() && m.esperadoEContado().sobras().isEmpty());
        return r;
    }
}
