package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.Antiga;
import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.Evento;
import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.Montagem;
import br.com.coronasemijoias.api.acertos.MontagemDoFechamento.NaoAceita;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.caixa.ServicoDeCaixa;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Parcelamento;
import br.com.coronasemijoias.api.contasreceber.ServicoDeRecebimentosDoVendedor;
import br.com.coronasemijoias.api.contasreceber.ServicoDeRecebimentosDoVendedor.ValorRecebido;
import br.com.coronasemijoias.api.cotacoes.Cotacao;
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
import java.time.LocalDate;
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

    /**
     * Um recebimento do acerto (dinheiro ou Transferência): o id vem do aplicativo (idempotência). {@code moeda} (padrão REAL): em
     * DOLAR ou GUARANI vem só o {@code valorNaMoeda} e o servidor converte pela cotação do acerto (23/11/2026).
     */
    public record RecebimentoNoAcerto(UUID id, String forma, BigDecimal valor, String moeda, BigDecimal valorNaMoeda) {

        public RecebimentoNoAcerto(UUID id, String forma, BigDecimal valor) {
            this(id, forma, valor, null, null);
        }
    }

    /** O boleto do fechamento: vira uma conta a receber do vendedor, no vencimento dele (e não toca o caixa até ser pago). */
    public record BoletoDoAcerto(BigDecimal valor, LocalDate vencimento) {
    }

    /** O que ficou devendo (fora o boleto): quantas parcelas mensais e quando vence a primeira. */
    public record RestanteDoAcerto(Integer parcelas, LocalDate primeiroVencimento) {
    }

    /** Uma conta antiga do vendedor paga junto com o acerto (não entra nos totais do acerto). */
    public record ContaAntigaPaga(UUID id, UUID contaId, String forma, BigDecimal valor, String moeda, BigDecimal valorNaMoeda) {

        public ContaAntigaPaga(UUID id, UUID contaId, String forma, BigDecimal valor) {
            this(id, contaId, forma, valor, null, null);
        }
    }

    /**
     * Como o acerto foi pago (tarefa 7.6; ADR-0012, itens 7, 8 e 9). {@code aFavorDoVendedor}: CREDITO (padrão) ou PAGAR_NA_HORA
     * (só o valor inteiro, na {@code formaDoPagamento}: dinheiro ou Transferência). O servidor calcula tudo o mais.
     */
    public record PagamentoDoAcerto(List<RecebimentoNoAcerto> recebimentos, BoletoDoAcerto boleto, RestanteDoAcerto restante,
            List<ContaAntigaPaga> contasAntigas, String aFavorDoVendedor, String formaDoPagamento) {
    }

    /** O pedido de "fechar". {@code liquidoMostrado} é só conferência: nunca entra no cálculo nem é gravado. */
    public record PedidoDeFechamento(UUID id, List<DecisaoDeFalta> faltas, List<DecisaoDeDefeito> defeitos,
            List<DecisaoDeCobranca> cobrancas, List<DecisaoDePendenciaAntiga> pendenciasAntigas, BigDecimal liquidoMostrado,
            PagamentoDoAcerto pagamento, UUID cotacaoId) {
    }

    /** O dinheiro do acerto, calculado pelo servidor. {@code restanteSemData}: sobra valor sem data escolhida (só na prévia). */
    private record Financeiro(BigDecimal creditoDisponivel, UUID creditoDoAcertoId, BigDecimal creditoUsado, BigDecimal aPagar,
            BigDecimal recebido, BigDecimal boleto, BigDecimal restante, BigDecimal ficouDevendo, BigDecimal pagoAoVendedor,
            boolean restanteSemData) {
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
    private final ServicoDeRecebimentosDoVendedor recebimentos;
    private final ServicoDeContasAReceber contas;
    private final ServicoDeCaixa caixa;
    private final Clock relogio;
    private volatile Runnable ganchoDeTeste = () -> { };

    ServicoDeFechamento(JdbcTemplate jdbc, MontagemDoFechamento montagem, ServicoDeEstoque estoque, ServicoDeAuditoria auditoria,
            ServicoDeRecebimentosDoVendedor recebimentos, ServicoDeContasAReceber contas, ServicoDeCaixa caixa, Clock relogio) {
        this.jdbc = jdbc;
        this.montagem = montagem;
        this.estoque = estoque;
        this.auditoria = auditoria;
        this.recebimentos = recebimentos;
        this.contas = contas;
        this.caixa = caixa;
        this.relogio = relogio;
    }

    /** Só para os testes: roda depois dos movimentos de maleta e estoque, para provar o "tudo ou nada". */
    public void definirGanchoDeTeste(Runnable gancho) {
        this.ganchoDeTeste = gancho == null ? () -> { } : gancho;
    }

    // --- a prévia ---

    /** A prévia: o mesmo cálculo do fechamento, nada é gravado. Sem decisões, assume cobrar tudo no acerto e não aprovar defeito. */
    @Transactional(readOnly = true)
    public PreviaDoFechamento previa(UUID acertoId, PedidoDeFechamento pedido) {
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

    /** Os números de uma prévia, tipados (tarefa 8.7): o que o painel da semana mostra, sem o JSON da tela do acerto. */
    public record ResumoDaPrevia(BigDecimal vendidoBruto, BigDecimal descontos, BigDecimal devolvido, BigDecimal vendidoLiquido,
            BigDecimal percentualComissao, BigDecimal comissao, BigDecimal faltasCobradas, BigDecimal liquido, LocalDate cotacaoData) {
    }

    /**
     * A prévia só dos números, para o painel da semana. Só leitura: nada é gravado. Com o acerto {@code EM_ACERTO} do vendedor, é a
     * mesma conta da prévia da dona sem decisões (faltas cobradas no acerto); sem acerto, é a mesma montagem da prévia da comissão do
     * vendedor, com as faltas zeradas (ainda não contou). Sem cotação cadastrada, o erro é o mesmo do acerto (409).
     */
    @Transactional(readOnly = true)
    public ResumoDaPrevia resumoDaPrevia(UUID vendedorId, UUID acertoId) {
        if (acertoId == null) {
            Montagem m = montagem.montar(UUID.randomUUID(), vendedorId);
            return resumoDe(m, BigDecimal.ZERO.setScale(2), liquidoDoAcerto(m.vendidoLiquido(), m.comissao().comissao(),
                    BigDecimal.ZERO.setScale(2)));
        }
        Montagem m = montagem.montar(acertoId, vendedorId);
        Decisoes d = padrao(m);
        m = m.comAprovadas(d.aprovadas());
        Calculo c = calcular(m, d, acertoId);
        return resumoDe(m, c.faltasCobradas(), c.liquido());
    }

    private static ResumoDaPrevia resumoDe(Montagem m, BigDecimal faltas, BigDecimal liquido) {
        return new ResumoDaPrevia(m.vendidoBruto(), m.descontos(), m.devolvido(), m.vendidoLiquido(), m.comissao().percentual(),
                m.comissao().comissao(), faltas, liquido, m.cotacao().dataCotacao());
    }

    private PreviaDoFechamento previaDe(UUID acertoId, UUID vendedorId, PedidoDeFechamento pedido) {
        Montagem m = montagem.montar(acertoId, vendedorId);
        Decisoes d = pedido == null ? padrao(m) : decisoesDe(pedido, m, false);
        m = m.comAprovadas(d.aprovadas());
        Calculo c = calcular(m, d, acertoId);
        Pagamento p = pagamentoDe(pedido == null ? null : pedido.pagamento(), m.cotacao());
        return montarPrevia(m, d, c, financeiro(m, c, p, false), p);
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
            throw desatualizado(montarPrevia(m, d, c, financeiro(m, c, Pagamento.VAZIO, false), Pagamento.VAZIO));
        }

        Pagamento pagamento = pagamentoDe(pedido.pagamento(), m.cotacao());
        // A cotação mudou depois da prévia (a dona lançou outra): se há recebimento em Dólar ou Guarani, o valor em reais que ela
        // conferiu já não vale. Sem recebimento em outra moeda, fecha normal com a vigente (só muda o que se mostra em Dólar e Guarani).
        if (pedido.cotacaoId() != null && !pedido.cotacaoId().equals(m.cotacao().id()) && temOutraMoeda(pagamento)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COTACAO_DO_ACERTO_MUDOU);
        }
        Financeiro f = financeiro(m, c, pagamento, true);
        gravar(usuarioId, acertoId, comando, resumo, m, d, c, f, pagamento);
        return new Resultado(acertoId, false);
    }

    private static boolean temOutraMoeda(Pagamento p) {
        return p.recebimentos().stream().anyMatch(r -> r.moeda() != null && !ServicoDeRecebimentosDoVendedor.REAL.equals(r.moeda()))
                || p.contasAntigas().stream().anyMatch(a -> a.moeda() != null && !ServicoDeRecebimentosDoVendedor.REAL.equals(a.moeda()));
    }

    private ErroDaApi desatualizado(PreviaDoFechamento previa) {
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
                Calculo cp = calcular(m, padrao, m.acertoId());
                throw desatualizado(montarPrevia(m, padrao, cp, financeiro(m, cp, Pagamento.VAZIO, false), Pagamento.VAZIO));
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
        s.append(resumoDoPagamento(p.pagamento()));
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
        BigDecimal liquido = liquidoDoAcerto(m.vendidoLiquido(), m.comissao().comissao(), faltas);
        return new Calculo(pendencias, antigasCobradas, faltas, liquido);
    }

    /**
     * A conta do valor do acerto, uma só para o fechamento e para a prévia do painel da semana (tarefa 8.7): vendido líquido, menos a
     * comissão, mais as faltas cobradas no acerto.
     */
    public static BigDecimal liquidoDoAcerto(BigDecimal vendidoLiquido, BigDecimal comissao, BigDecimal faltasCobradas) {
        return Dinheiro.somar(List.of(Dinheiro.subtrair(vendidoLiquido, comissao), faltasCobradas));
    }

    private static String statusDe(String decisao) {
        return switch (decisao) {
            case "COBRAR_NO_ACERTO" -> "COBRADA";
            case "COBRAR_DEPOIS" -> "ABERTA";
            default -> "ASSUMIDA_PELA_LOJA";
        };
    }

    // --- o pagamento do acerto (tarefa 7.6) ---

    /**
     * O pagamento já conferido na forma. Os recebimentos e as contas antigas já vêm com o {@code valor} em Real (convertido pela
     * cotação do acerto quando a dona digitou em Dólar ou Guarani) e, aí, com a moeda, o valor digitado e {@code cotacaoId}.
     */
    private record Pagamento(List<RecebimentoNoAcerto> recebimentos, BoletoDoAcerto boleto, RestanteDoAcerto restante,
            List<ContaAntigaPaga> contasAntigas, String aFavorDoVendedor, String formaDoPagamento, UUID cotacaoId) {

        static final Pagamento VAZIO = new Pagamento(List.of(), null, null, List.of(), null, null, null);

        ValorRecebido valorDe(BigDecimal valor, String moeda, BigDecimal valorNaMoeda) {
            return ServicoDeRecebimentosDoVendedor.REAL.equals(moeda) ? ValorRecebido.emReais(valor)
                    : new ValorRecebido(valor, moeda, valorNaMoeda, cotacaoId);
        }
    }

    /**
     * O valor de um recebimento do acerto em Real (400 fora da forma). Em Dólar ou Guarani, convertido pela cotação do acerto, a mesma
     * que converte todo o resto do acerto (ADR-0006, item 11).
     */
    private static ValorRecebido valorDoRecebimento(BigDecimal valor, String moeda, BigDecimal valorNaMoeda, Cotacao cotacao) {
        String m = ServicoDeRecebimentosDoVendedor.moedaDoRecebimento(moeda, valor, valorNaMoeda);
        if (ServicoDeRecebimentosDoVendedor.REAL.equals(m)) {
            return ValorRecebido.emReais(ServicoDeRecebimentosDoVendedor.valorEmReais(valor));
        }
        return ServicoDeRecebimentosDoVendedor.converter(m, ServicoDeRecebimentosDoVendedor.valorNaMoeda(m, valorNaMoeda), cotacao);
    }

    /** Confere a forma do pagamento (400): só dinheiro e Transferência no acerto; valores em Real; ids do aplicativo. */
    private Pagamento pagamentoDe(PagamentoDoAcerto p, Cotacao cotacao) {
        if (p == null) {
            return Pagamento.VAZIO;
        }
        Set<UUID> ids = new java.util.HashSet<>();
        List<RecebimentoNoAcerto> recebimentosConferidos = new ArrayList<>();
        for (RecebimentoNoAcerto r : lista(p.recebimentos())) {
            if (r == null) {
                throw Validacoes.recusar("recebimentos", "Informe cada recebimento com o valor e a forma.");
            }
            UUID id = Validacoes.idDoPedido(r.id());
            if (!ids.add(id)) {
                throw Validacoes.recusar("recebimentos", "O mesmo recebimento foi informado duas vezes.");
            }
            String forma = ServicoDeRecebimentosDoVendedor.formaDoRepasse(r.forma(), false);
            ValorRecebido v = valorDoRecebimento(r.valor(), r.moeda(), r.valorNaMoeda(), cotacao);
            recebimentosConferidos.add(new RecebimentoNoAcerto(id, forma, v.valor(), v.moeda(), v.valorNaMoeda()));
        }
        BoletoDoAcerto boleto = null;
        if (p.boleto() != null) {
            boleto = new BoletoDoAcerto(ServicoDeRecebimentosDoVendedor.valorEmReais(p.boleto().valor()), p.boleto().vencimento());
            if (boleto.vencimento() == null) {
                throw Validacoes.recusar("boleto", "Informe o vencimento do boleto.");
            }
        }
        List<ContaAntigaPaga> antigas = new ArrayList<>();
        for (ContaAntigaPaga a : lista(p.contasAntigas())) {
            if (a == null || a.contaId() == null) {
                throw Validacoes.recusar("contasAntigas", "Escolha a conta que o representante está pagando.");
            }
            UUID id = Validacoes.idDoPedido(a.id());
            if (!ids.add(id)) {
                throw Validacoes.recusar("contasAntigas", "O mesmo recebimento foi informado duas vezes.");
            }
            String formaDaConta = ServicoDeRecebimentosDoVendedor.formaDoRepasse(a.forma(), false);
            ValorRecebido v = valorDoRecebimento(a.valor(), a.moeda(), a.valorNaMoeda(), cotacao);
            antigas.add(new ContaAntigaPaga(id, a.contaId(), formaDaConta, v.valor(), v.moeda(), v.valorNaMoeda()));
        }
        if (p.aFavorDoVendedor() != null && !Set.of("CREDITO", "PAGAR_NA_HORA").contains(p.aFavorDoVendedor())) {
            throw Validacoes.recusar("aFavorDoVendedor", "Escolha deixar de crédito para o próximo acerto ou pagar na hora.");
        }
        String forma = p.formaDoPagamento() == null ? null : ServicoDeRecebimentosDoVendedor.formaDoRepasse(p.formaDoPagamento(), false);
        if ("PAGAR_NA_HORA".equals(p.aFavorDoVendedor()) && forma == null) {
            throw Validacoes.recusar("formaDoPagamento", "Escolha se o pagamento ao representante é em dinheiro ou Transferência (PIX).");
        }
        if (p.restante() != null && (p.restante().parcelas() == null || p.restante().primeiroVencimento() == null)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DATA_DO_RESTANTE_OBRIGATORIA);
        }
        return new Pagamento(recebimentosConferidos, boleto, p.restante(), antigas, p.aFavorDoVendedor(), forma, cotacao.id());
    }

    private static String numero(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /** No resumo, a moeda só aparece fora do Real: o resumo dos fechamentos em Real continua o mesmo de antes. */
    private static String moedaNoResumo(String moeda, BigDecimal valorNaMoeda) {
        return moeda == null || ServicoDeRecebimentosDoVendedor.REAL.equals(moeda) && valorNaMoeda == null ? ""
                : "|" + moeda + "|" + numero(valorNaMoeda);
    }

    /** O resumo do pagamento para o 409 de "mesmo id, outras decisões": canônico, na ordem dos ids. */
    private static String resumoDoPagamento(PagamentoDoAcerto p) {
        if (p == null) {
            return "";
        }
        StringBuilder s = new StringBuilder("|PAGAMENTO;");
        new TreeMap<String, String>(porChave(lista(p.recebimentos()), r -> r == null ? "" : r.id() + "",
                r -> r == null ? "" : r.forma() + "|" + numero(r.valor()) + moedaNoResumo(r.moeda(), r.valorNaMoeda())))
                .forEach((k, v) -> s.append("R").append(k).append("=").append(v).append(";"));
        if (p.boleto() != null) {
            s.append("B=").append(numero(p.boleto().valor())).append("|").append(p.boleto().vencimento()).append(";");
        }
        if (p.restante() != null) {
            s.append("T=").append(p.restante().parcelas()).append("|").append(p.restante().primeiroVencimento()).append(";");
        }
        new TreeMap<String, String>(porChave(lista(p.contasAntigas()), r -> r == null ? "" : r.id() + "",
                r -> r == null ? "" : r.contaId() + "|" + r.forma() + "|" + numero(r.valor()) + moedaNoResumo(r.moeda(), r.valorNaMoeda())))
                .forEach((k, v) -> s.append("O").append(k).append("=").append(v).append(";"));
        s.append("V=").append(p.aFavorDoVendedor()).append("|").append(p.formaDoPagamento()).append(";");
        return s.toString();
    }

    /**
     * O dinheiro do acerto, só pelo servidor: o crédito de um acerto anterior abate o líquido (inteiro e automático); o que sobra
     * a pagar é recebido em dinheiro ou Transferência, vira boleto (conta) ou fica devendo (contas); a favor do vendedor, vira
     * crédito para o próximo acerto ou saída de caixa. {@code completo}: no "fechar", o que ficou devendo sem data é 409.
     */
    private Financeiro financeiro(Montagem m, Calculo c, Pagamento p, boolean completo) {
        BigDecimal zero = Dinheiro.somar(List.of());
        UUID creditoDe = null;
        BigDecimal credito = zero;
        List<Map<String, Object>> anterior = jdbc.queryForList("""
                SELECT a.id, a.ficou_devendo FROM corona.acertos a
                 WHERE a.vendedor_id = ? AND a.status = 'ACERTADO' AND a.ficou_devendo < 0
                   AND NOT EXISTS (SELECT 1 FROM corona.acertos y WHERE y.credito_do_acerto_id = a.id)
                 ORDER BY a.fechado_em DESC, a.id DESC LIMIT 1""", m.vendedorId());
        if (!anterior.isEmpty()) {
            creditoDe = (UUID) anterior.get(0).get("id");
            credito = ((BigDecimal) anterior.get(0).get("ficou_devendo")).negate();
        }
        BigDecimal aPagar = Dinheiro.subtrair(c.liquido(), credito);
        BigDecimal recebido = Dinheiro.somar(p.recebimentos().stream().map(RecebimentoNoAcerto::valor).toList());
        BigDecimal boleto = p.boleto() == null ? zero : p.boleto().valor();
        BigDecimal ficou = zero;
        BigDecimal restante = zero;
        BigDecimal pago = zero;
        boolean semData = false;
        if (aPagar.signum() > 0) {
            if (p.aFavorDoVendedor() != null) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_AO_VENDEDOR_INVALIDO);
            }
            if (Dinheiro.somar(List.of(recebido, boleto)).compareTo(aPagar) > 0) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO);
            }
            ficou = Dinheiro.subtrair(aPagar, recebido);
            restante = Dinheiro.subtrair(ficou, boleto);
            if (restante.signum() > 0 && p.restante() == null) {
                if (completo) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DATA_DO_RESTANTE_OBRIGATORIA);
                }
                semData = true;
            }
            if (restante.signum() == 0 && p.restante() != null) {
                throw Validacoes.recusar("restante", "Não há valor para parcelar.");
            }
        } else if (aPagar.signum() == 0) {
            if (recebido.signum() > 0 || boleto.signum() > 0 || p.restante() != null) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO);
            }
            if (p.aFavorDoVendedor() != null) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_AO_VENDEDOR_INVALIDO);
            }
        } else {
            if (recebido.signum() > 0 || boleto.signum() > 0 || p.restante() != null) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_AO_VENDEDOR_INVALIDO);
            }
            if ("PAGAR_NA_HORA".equals(p.aFavorDoVendedor())) {
                pago = aPagar.negate();
            } else {
                ficou = aPagar;
            }
        }
        return new Financeiro(credito, creditoDe, credito, aPagar, recebido, boleto, restante, ficou, pago, semData);
    }

    // --- a gravação ---

    private void gravar(UUID usuarioId, UUID acertoId, UUID comando, String resumo, Montagem m, Decisoes d, Calculo c, Financeiro f,
            Pagamento pagamento) {
        Timestamp agora = Timestamp.from(relogio.instant());
        // Os produtos primeiro, por id (a ordem do ADR-0012, item 13): as chaves estrangeiras dos itens e das pendências pegam
        // FOR KEY SHARE neles, e o estoque pega FOR UPDATE depois; sem travar antes, dois fechamentos em ordens cruzadas se esperariam.
        List<UUID> produtos = new ArrayList<>(new TreeSet<>(m.esperadoEContado().itens().stream()
                .map(EsperadoEContado.Item::produtoId).toList()));
        if (!produtos.isEmpty()) {
            jdbc.queryForList("SELECT id FROM corona.produtos WHERE id IN (" + MontagemDoFechamento.marcadores(produtos.size())
                    + ") ORDER BY id FOR UPDATE", UUID.class, produtos.toArray());
        }
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

        gravarODinheiro(usuarioId, acertoId, m.vendedorId(), f, pagamento, agora.toInstant());

        for (MontagemDoFechamento.Maleta maleta : m.maletas()) {
            jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", maleta.id());
        }
        jdbc.update("""
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = ?, fechado_em = ?, fechado_por = ?,
                       cotacao_id = ?, meta_guarani = ?, percentual_comissao = ?, vendido_para_meta_guarani = ?, vendido_bruto = ?,
                       descontos = ?, devolvido = ?, vendido_liquido = ?, comissao = ?, faltas_cobradas = ?, liquido = ?,
                       recebido_no_acerto = ?, ficou_devendo = ?, pago_ao_vendedor = ?, credito_usado = ?, credito_do_acerto_id = ?
                 WHERE id = ?""", comando, resumo, agora, usuarioId, m.cotacao().id(),
                (int) m.metaGuarani(), m.comissao().percentual(), m.comissao().vendidoParaMetaGuarani(), m.vendidoBruto(), m.descontos(),
                m.devolvido(), m.vendidoLiquido(), m.comissao().comissao(), c.faltasCobradas(), c.liquido(), f.recebido(),
                f.ficouDevendo(), f.pagoAoVendedor(), f.creditoUsado(), f.creditoDoAcertoId(), acertoId);

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
        depois.put("credito_usado", f.creditoUsado().toPlainString());
        depois.put("recebido_no_acerto", f.recebido().toPlainString());
        depois.put("ficou_devendo", f.ficouDevendo().toPlainString());
        depois.put("pago_ao_vendedor", f.pagoAoVendedor().toPlainString());
        depois.put("boleto", f.boleto().toPlainString());
        auditoria.registrar(Ator.usuario(usuarioId), "ACERTO_FECHADO", "acertos", acertoId, Map.of("status", "EM_ACERTO"), depois);
    }

    /**
     * O dinheiro do fechamento, na ordem do ADR-0012 (item 3.6): os recebimentos do acerto e seus lançamentos de caixa; as contas
     * do vendedor (boleto e restante); as contas antigas pagas, por id (as contas ficam por último na ordem de travas); e, a favor
     * do vendedor pago na hora, a saída de caixa. O acerto ganha os valores logo depois, no mesmo comando de fechar.
     */
    private void gravarODinheiro(UUID usuarioId, UUID acertoId, UUID vendedorId, Financeiro f, Pagamento pagamento, java.time.Instant hora) {
        for (RecebimentoNoAcerto r : pagamento.recebimentos()) {
            recebimentos.registrarNoFechamento(usuarioId, acertoId, vendedorId, r.id(), r.forma(), pagamento.valorDe(r.valor(), r.moeda(),
                    r.valorNaMoeda()), false, null, hora);
        }
        if (f.ficouDevendo().signum() > 0) {
            Parcelamento parcelamento = pagamento.restante() == null ? null
                    : new Parcelamento(pagamento.restante().parcelas(), pagamento.restante().primeiroVencimento());
            contas.gerarContasDoAcerto(vendedorId, acertoId, pagamento.boleto() == null ? null : pagamento.boleto().valor(),
                    pagamento.boleto() == null ? null : pagamento.boleto().vencimento(), f.restante(), parcelamento);
        }
        for (ContaAntigaPaga a : pagamento.contasAntigas().stream().sorted(java.util.Comparator.comparing(ContaAntigaPaga::contaId)).toList()) {
            recebimentos.registrarNoFechamento(usuarioId, acertoId, vendedorId, a.id(), a.forma(), pagamento.valorDe(a.valor(), a.moeda(),
                    a.valorNaMoeda()), true, a.contaId(), hora);
        }
        if (f.pagoAoVendedor().signum() > 0) {
            caixa.lancarPagamentoAoVendedor(usuarioId, acertoId, pagamento.formaDoPagamento(), f.pagoAoVendedor());
        }
    }

    // --- a prévia (JSON) ---

    private PreviaDoFechamento montarPrevia(Montagem m, Decisoes d, Calculo c, Financeiro f, Pagamento pag) {
        List<PreviaDoFechamento.PecaDaPreviaDoFechamento> itens = new ArrayList<>();
        for (EsperadoEContado.Item i : m.esperadoEContado().itens()) {
            itens.add(new PreviaDoFechamento.PecaDaPreviaDoFechamento(produtoDaPrevia(m, i.produtoId()), i.remessaId(), i.esperado(),
                    i.contadoBoa(), i.contadoDefeito(), i.falta(), i.valorDaFalta()));
        }
        List<PreviaDoFechamento.FaltaDaPreviaDoFechamento> faltas = c.pendencias().stream().filter(p -> p.tipo().equals("FALTA"))
                .map(p -> new PreviaDoFechamento.FaltaDaPreviaDoFechamento(produtoDaPrevia(m, p.produtoId()), p.remessaId(),
                        p.quantidade(), p.valor(), p.status()))
                .toList();
        List<PreviaDoFechamento.DefeitoDaPreviaDoFechamento> defeitos = new ArrayList<>();
        m.defeitosContados().forEach((p, q) -> defeitos.add(new PreviaDoFechamento.DefeitoDaPreviaDoFechamento(produtoDaPrevia(m, p), q)));
        var eventos = m.eventos().stream()
                .map(e -> new PreviaDoFechamento.EventoDaPreviaDoFechamento(e.tipo(), e.origemId(), e.vendaId(), e.valor())).toList();
        var revisoes = m.revisoes().stream().map(x -> new PreviaDoFechamento.RevisaoDaPreviaDoFechamento(x.tipo(), x.id())).toList();
        var naoAceitas = m.naoAceitas().stream().map(x -> new PreviaDoFechamento.NaoAceitaDaPreviaDoFechamento(x.tipo(), x.origemTipo(),
                x.origemId(), x.valor())).toList();
        var antigas = m.antigas().stream().map(x -> new PreviaDoFechamento.AntigaDaPreviaDoFechamento(x.id(), x.tipo(), x.produtoId(),
                x.quantidade(), x.valor())).toList();
        long vendidoParaMeta = m.comissao().vendidoParaMetaGuarani();
        ServicoDaComissao.FaltaParaMeta falta = ServicoDaComissao.faltaParaMeta(m.metaGuarani(), vendidoParaMeta,
                m.cotacao().taxas().pygVenda());
        return new PreviaDoFechamento(m.acertoId(), itens, m.esperadoEContado().sobras(), faltas, defeitos, eventos, revisoes, naoAceitas,
                antigas, m.comissao().linhas(), m.metaGuarani(), vendidoParaMeta, falta.guarani(), falta.reais(), m.comissao().percentual(),
                m.vendidoBruto(), m.descontos(), m.devolvido(), m.vendidoLiquido(), m.comissao().comissao(), c.faltasCobradas(), c.liquido(),
                f.creditoDisponivel(), f.creditoUsado(), f.aPagar(), f.recebido(), f.boleto(), f.ficouDevendo(), f.pagoAoVendedor(),
                f.restanteSemData(), parcelasDoRestante(f, pag), contasAbertasDoVendedor(m.vendedorId()), m.cotacao().id(),
                m.cotacao().dataCotacao(), m.revisoes().isEmpty() && m.esperadoEContado().sobras().isEmpty());
    }

    private static PreviaDoFechamento.ProdutoDaPreviaDoFechamento produtoDaPrevia(Montagem m, UUID produtoId) {
        MontagemDoFechamento.Produto p = m.produtos().get(produtoId);
        return p == null ? null : new PreviaDoFechamento.ProdutoDaPreviaDoFechamento(p.id(), p.nome(), p.codigo(), p.fotoHash());
    }

    /** As parcelas que o restante geraria (só para mostrar na prévia; o valor de cada uma sai de Dinheiro.dividirEmParcelas). */
    private List<PreviaDoFechamento.ParcelaDoRestanteDaPrevia> parcelasDoRestante(Financeiro f, Pagamento p) {
        List<PreviaDoFechamento.ParcelaDoRestanteDaPrevia> r = new ArrayList<>();
        if (f.restante().signum() <= 0 || p.restante() == null) {
            return r;
        }
        try {
            List<BigDecimal> valores = Dinheiro.dividirEmParcelas(f.restante(), p.restante().parcelas());
            for (int k = 0; k < valores.size(); k++) {
                r.add(new PreviaDoFechamento.ParcelaDoRestanteDaPrevia(k + 1 + (f.boleto().signum() > 0 ? 1 : 0),
                        p.restante().primeiroVencimento().plusMonths(k), valores.get(k)));
            }
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar("restante", "O valor que ficou devendo é pequeno demais para tantas parcelas. Use menos parcelas.");
        }
        return r;
    }

    /** As contas em aberto que o vendedor já devia à loja (nível 2), da que vence primeiro para a que vence depois. */
    private List<PreviaDoFechamento.ContaAbertaNaPrevia> contasAbertasDoVendedor(UUID vendedorId) {
        return jdbc.query("""
                SELECT id, origem_tipo, parcela, total_parcelas, vencimento, valor, valor - valor_pago - valor_cancelado AS em_aberto,
                       forma_prevista
                  FROM corona.contas_receber
                 WHERE devedor_tipo = 'VENDEDOR' AND vendedor_id = ? AND deleted_at IS NULL AND status IN ('ABERTA', 'PARCIAL')
                 ORDER BY vencimento, parcela, id""", (rs, n) -> new PreviaDoFechamento.ContaAbertaNaPrevia(rs.getObject("id", UUID.class), rs.getString("origem_tipo"),
                        rs.getInt("parcela"), rs.getInt("total_parcelas"), rs.getObject("vencimento", LocalDate.class),
                        rs.getBigDecimal("valor"), rs.getBigDecimal("em_aberto"), rs.getString("forma_prevista")), vendedorId);
    }
}
