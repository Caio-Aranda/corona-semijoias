package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber;
import br.com.coronasemijoias.api.creditos.ServicoDeCreditos;
import br.com.coronasemijoias.api.devolucoes.RegrasDaDevolucao.Peca;
import br.com.coronasemijoias.api.devolucoes.RegrasDaDevolucao.PecaCalculada;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devolução do cliente de venda à vista, "Cancelar devolução" (tarefa 6.3; ADR-0014, itens 1 a 7 e 11) e a revisão e o
 * antifraude (tarefa 6.4; ADR-0014, itens 5, 6, 10 e 12). Regras:
 * <ul>
 *   <li>A venda não muda. O app manda a venda, as peças (pelo produto), quantidade, estado, motivo, a forma do reembolso
 *       e, se houver, a autorização da dona; o servidor calcula o prazo, o valor (preço pago, {@link ValorDaDevolucao}),
 *       o destino da peça e se a devolução vai para revisão.</li>
 *   <li><strong>Ordem das travas</strong> (aprovada no PR #126): primeiro a trava do vendedor
 *       ({@code pg_advisory_xact_lock} de {@code corona.devolucoes.vendedor:<id>}), depois a maleta da venda, a venda e,
 *       por último, a autorização. A trava do vendedor faz duas devoluções do mesmo vendedor (em vendas diferentes)
 *       somarem o limite da semana uma depois da outra. Só a devolução pega essa trava: o estorno da venda, o cancelar e
 *       a revisão da dona pegam só maleta e venda, na mesma ordem, então a ordem nunca se inverte. Com a troca e o
 *       crédito (tarefa 6.5, PR #129): vendedor → maletas em ordem de id → vendas em ordem de id → devolução, troca e
 *       autorização → créditos em ordem de id; o crédito é sempre o último.</li>
 *   <li>Dinheiro e crédito separados (tarefa 6.5): {@code valor_reembolsado} é só o dinheiro; {@code valor_em_credito}, o
 *       que voltou como crédito do cliente. Na venda paga (em parte) com crédito, o crédito volta primeiro (dúvida T9). O
 *       limite da semana, o valor de aprovação e o "reembolso maior que o pago" olham só o dinheiro. A devolução de uma
 *       troca ({@code destino = TROCA}) só se desfaz pelo "Cancelar troca".</li>
 *   <li>Venda a prazo (tarefa 6.6; ADR-0014, item 10): a devolução primeiro <strong>abate</strong> o que o cliente ainda deve
 *       (baixa de cancelamento nas parcelas em aberto, da última para a primeira), depois volta o que foi pago em crédito
 *       e só então o dinheiro. {@code valor_abatido + valor_em_credito + valor_reembolsado = valor_devolvido}. As parcelas
 *       são a última trava (depois dos créditos); o "Cancelar devolução" desfaz o abatimento por estorno das baixas.</li>
 *   <li>Peça boa de maleta ainda com o vendedor volta ao saldo (movimento {@code DEVOLUCAO_CLIENTE}); peça boa de maleta
 *       acertada e toda peça com defeito são "peça para a loja", sem movimento.</li>
 *   <li>Autorização aprovada e válida (mesma venda, mesmo motivo, no máximo as peças pedidas) dispensa o prazo e o valor
 *       de aprovação, fica ligada à devolução e passa a usada. Inválida: na rota online, 409; pelo envio, revisão (e não
 *       é consumida). De outro vendedor ou inexistente: 404 nos dois.</li>
 *   <li>Fora do prazo ou acima do valor de aprovação sem autorização: na rota online, 409; pelo envio do computador
 *       ({@link ModoDaDevolucao#ENVIO}), revisão. Acima do limite da semana ou reembolso maior que o pago: revisão nos
 *       dois (o balcão não trava). Em revisão, o movimento e o reembolso são gravados igual: o dinheiro já saiu.</li>
 *   <li>Idempotência pelo id do aplicativo: mesmo id e mesmo conteúdo, 200; conteúdo diferente, 409.</li>
 * </ul>
 */
@Service
public class ServicoDeDevolucoes {

    private static final ZoneId BRASILIA = RegrasDaDevolucao.BRASILIA;
    private static final int LIMITE_DO_TEXTO = RegrasDaDevolucao.LIMITE_DO_TEXTO;
    private static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX", "CARTAO", "CHEQUE");
    private static final Set<String> MALETA_COM_O_VENDEDOR = Set.of("ENVIADA", "CONFERIDA");

    /** De onde veio a devolução: a rota online (fora do prazo = 409) ou o envio do computador (fora do prazo = revisão). */
    public enum ModoDaDevolucao {
        ONLINE,
        ENVIO
    }

    /** Por que a devolução está para revisão, na ordem fixa em que a API mostra. */
    public enum MotivoDaRevisao {
        FORA_DO_PRAZO("revisao_fora_do_prazo"),
        ACIMA_DO_LIMITE_SEMANAL("revisao_acima_do_limite_semanal"),
        ACIMA_DO_VALOR_SEM_AUTORIZACAO("revisao_acima_do_valor_de_aprovacao"),
        AUTORIZACAO_INVALIDA("revisao_autorizacao_invalida"),
        REEMBOLSO_MAIOR_QUE_O_PAGO("revisao_reembolso_maior_que_pago");

        private final String coluna;

        MotivoDaRevisao(String coluna) {
            this.coluna = coluna;
        }

        public String coluna() {
            return coluna;
        }
    }

    public record PecaDoPedido(UUID produtoId, Integer quantidade, String estado) {
    }

    /**
     * {@code valorConferido} é o valor que a tela mostrou: só serve para avisar divergência (nunca vale).
     * {@code autorizacaoId} é o pedido de autorização aprovado pela dona, quando há.
     */
    public record PedidoDaDevolucaoDoCliente(UUID id, UUID vendaId, String motivo, String observacao, String formaReembolso,
            List<PecaDoPedido> itens, BigDecimal valorConferido, UUID autorizacaoId) {

        /** Sem autorização (a devolução da tarefa 6.3). */
        public PedidoDaDevolucaoDoCliente(UUID id, UUID vendaId, String motivo, String observacao, String formaReembolso,
                List<PecaDoPedido> itens, BigDecimal valorConferido) {
            this(id, vendaId, motivo, observacao, formaReembolso, itens, valorConferido, null);
        }
    }

    /** {@code jaExistia}: a devolução (ou o cancelamento, ou a revisão) já estava gravada (200); senão, algo novo (201). */
    public record Resultado(UUID devolucaoId, boolean jaExistia) {
    }

    /**
     * A devolução de A gravada pela troca: o valor devolvido, o que vira crédito para pagar B (o que passou do que A
     * ainda devia; zero se A não tinha nada pago) e o cliente da venda A.
     */
    public record DevolucaoDaTrocaGravada(UUID devolucaoId, BigDecimal valorDevolvido, BigDecimal valorEmCredito, UUID clienteId) {
    }

    /** A troca da devolução: o id da troca e o do crédito que ela gera (os dois do aplicativo). */
    private record DaTroca(UUID trocaId, UUID creditoId) {
    }

    private record Gravada(Resultado resultado, BigDecimal valorDevolvido, BigDecimal valorEmCredito, UUID clienteId) {
    }

    /** A chave da trava do vendedor (a mesma na devolução e na troca). */
    private static final String TRAVA_DO_VENDEDOR = "corona.devolucoes.vendedor:";

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Configuracoes configuracoes;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;
    private final ServicoDeCreditos creditos;
    private final ServicoDeContasAReceber contas;

    ServicoDeDevolucoes(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, Configuracoes configuracoes,
            OrigemDoDispositivo origem, Clock relogio, ServicoDeCreditos creditos, ServicoDeContasAReceber contas) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.configuracoes = configuracoes;
        this.origem = origem;
        this.relogio = relogio;
        this.creditos = creditos;
        this.contas = contas;
    }

    /**
     * A trava do vendedor ({@code pg_advisory_xact_lock}), a primeira de todas na devolução e na troca (ver a ordem no
     * comentário da classe). Pegar de novo na mesma transação não espera.
     */
    public static void trancarVendedor(JdbcTemplate jdbc, UUID vendedorId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, TRAVA_DO_VENDEDOR + vendedorId);
    }

    // --- devolução ---

    /**
     * Registra a devolução. {@code momento} é a hora da devolução (a do envio já corrigida pelo servidor, ADR-0013, item
     * 11); nulo = agora (a rota online). {@code horaAjustada} marca a hora corrigida em mais de 5 minutos.
     */
    @Transactional
    public Resultado devolver(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDaDevolucaoDoCliente pedido, Instant momento,
            boolean horaAjustada, ModoDaDevolucao modo) {
        return registrar(vendedorId, usuarioId, aparelho, pedido, momento, horaAjustada, modo, null, false).resultado();
    }

    /**
     * A dona registra a devolução de uma venda de qualquer vendedor pelo Corona Gestão (tarefa 6.9; ADR-0014, item 13).
     * A peça vai sempre para a loja (nada entra na maleta do vendedor); o prazo da garantia e a maleta em acerto valem
     * como no vendedor; o limite da semana e o valor de aprovação não valem para a dona; a venda de um vendedor
     * desativado também pode ser devolvida. O vendedor da devolução é o da venda; nenhum computador entra na conta.
     */
    @Transactional
    public Resultado devolverPelaDona(UUID usuarioId, PedidoDaDevolucaoDoCliente pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("devolucao", "Informe a devolução.");
        }
        if (pedido.vendaId() == null) {
            throw Validacoes.recusar("vendaId", "Escolha a venda da devolução.");
        }
        List<UUID> donos = jdbc.queryForList("SELECT vendedor_id FROM corona.vendas WHERE id = ? AND deleted_at IS NULL", UUID.class,
                pedido.vendaId());
        if (donos.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        return registrar(donos.get(0), usuarioId, null, pedido, null, false, ModoDaDevolucao.ONLINE, null, true).resultado();
    }

    /**
     * A devolução de A pela troca (tarefa 6.5; ADR-0014, item 8), na transação da troca: destino {@code TROCA}, nenhum
     * dinheiro sai ({@code valor_reembolsado} zero e o valor inteiro em crédito: reembolso {@code CREDITO} na venda A e o
     * crédito {@code creditoId} nasce), a peça boa de maleta com o vendedor volta como {@code TROCA_ENTRADA}, e não passa
     * pelo limite da semana nem pelo valor de aprovação (dúvida T2). Prazo, autorização, maleta em acerto e computador,
     * iguais à devolução. A forma do reembolso do pedido é ignorada.
     */
    @Transactional
    public DevolucaoDaTrocaGravada devolverNaTroca(UUID vendedorId, UUID usuarioId, UUID aparelho,
            PedidoDaDevolucaoDoCliente pedido, Instant momento, boolean horaAjustada, ModoDaDevolucao modo, UUID trocaId,
            UUID creditoId) {
        Gravada g = registrar(vendedorId, usuarioId, aparelho, pedido, momento, horaAjustada, modo,
                new DaTroca(trocaId, creditoId), false);
        return new DevolucaoDaTrocaGravada(g.resultado().devolucaoId(), g.valorDevolvido(), g.valorEmCredito(), g.clienteId());
    }

    private Gravada registrar(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDaDevolucaoDoCliente pedido, Instant momento,
            boolean horaAjustada, ModoDaDevolucao modo, DaTroca troca, boolean pelaDona) {
        if (pedido == null) {
            throw Validacoes.recusar("devolucao", "Informe a devolução.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.vendaId() == null) {
            throw Validacoes.recusar("vendaId", "Escolha a venda da devolução.");
        }
        String motivo = RegrasDaDevolucao.conferirMotivo(pedido.motivo());
        String observacao = RegrasDaDevolucao.conferirObservacao(pedido.observacao(), motivo);
        String forma = troca == null ? pedido.formaReembolso() : null;
        if (troca == null && (forma == null || !FORMAS.contains(forma))) {
            throw Validacoes.recusar("formaReembolso", "Escolha como o dinheiro foi devolvido: dinheiro, Pix, cartão ou cheque.");
        }
        List<Peca> pecas = RegrasDaDevolucao.conferirPecas(pedido.itens(), motivo);
        if (pedido.valorConferido() != null && pedido.valorConferido().signum() < 0) {
            throw Validacoes.recusar("valorConferido", "O valor conferido não pode ser negativo.");
        }

        // A venda, sem trava (o vendedor e a maleta dela nunca mudam). De outro vendedor: 404, sem dizer que existe.
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT vendedor_id, remessa_id FROM corona.vendas WHERE id = ? AND deleted_at IS NULL", pedido.vendaId());
        if (achada.isEmpty() || !achada.get(0).get("vendedor_id").equals(vendedorId)) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        UUID remessaId = (UUID) achada.get(0).get("remessa_id");
        // Travas: o vendedor, a maleta, a venda e (mais abaixo) a autorização e o crédito. Ver a ordem no comentário da classe.
        trancarVendedor(jdbc, vendedorId);
        Map<String, Object> remessa = jdbc.queryForMap(
                "SELECT status, dispositivo_id FROM corona.remessas WHERE id = ? FOR UPDATE", remessaId);
        Map<String, Object> venda = jdbc.queryForMap(
                "SELECT status, cliente_id, total, vendida_em FROM corona.vendas WHERE id = ? FOR UPDATE", pedido.vendaId());

        List<Map<String, Object>> existente = jdbc.queryForList("""
                SELECT venda_id, vendedor_id, motivo, observacao, forma_reembolso, autorizacao_id, revisao_autorizacao_invalida,
                       destino
                  FROM corona.devolucoes WHERE id = ?""", id);
        if (!existente.isEmpty()) {
            // A troca confere os próprios ids antes: a devolução dela nunca chega aqui já gravada.
            if (troca != null || "TROCA".equals(existente.get(0).get("destino"))
                    || !mesmaDevolucao(id, vendedorId, pedido.vendaId(), motivo, observacao, forma, pecas, pedido.autorizacaoId(),
                    existente.get(0))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, troca != null ? CodigoErro.TROCA_JA_REGISTRADA_DIFERENTE
                        : CodigoErro.DEVOLUCAO_JA_REGISTRADA_DIFERENTE);
            }
            return new Gravada(new Resultado(id, true), null, null, (UUID) venda.get("cliente_id"));
        }

        if (!pelaDona) {
            VendedorAtivo.exigir(jdbc, vendedorId);
        }
        if ("ESTORNADA".equals(venda.get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        BigDecimal total = (BigDecimal) venda.get("total");
        // O dinheiro e o crédito da venda, separados (tarefa 6.5): o crédito usado não é dinheiro.
        Map<String, Object> dinheiro = jdbc.queryForMap("""
                SELECT coalesce(sum(valor) FILTER (WHERE tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO')), 0) AS pago,
                       coalesce(sum(valor) FILTER (WHERE tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO') AND forma <> 'CREDITO'), 0) AS pago_em_dinheiro,
                       coalesce(-sum(valor) FILTER (WHERE tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO') AND forma <> 'CREDITO'), 0)
                           AS reembolsado_em_dinheiro,
                       coalesce(sum(valor) FILTER (WHERE forma = 'CREDITO'), 0) AS credito_liquido
                  FROM corona.pagamentos_cliente WHERE venda_id = ?""", pedido.vendaId());
        BigDecimal pago = (BigDecimal) dinheiro.get("pago");
        BigDecimal pagoEmDinheiro = (BigDecimal) dinheiro.get("pago_em_dinheiro");
        BigDecimal jaReembolsado = (BigDecimal) dinheiro.get("reembolsado_em_dinheiro");
        BigDecimal creditoLiquido = (BigDecimal) dinheiro.get("credito_liquido");
        String statusDaMaleta = (String) remessa.get("status");
        if ("EM_ACERTO".equals(statusDaMaleta)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_ACERTO);
        }
        // A peça volta à maleta só quando ela está com o vendedor e foi ele quem devolveu; a dona recebe a peça na loja.
        boolean maletaComOVendedor = MALETA_COM_O_VENDEDOR.contains(statusDaMaleta) && !pelaDona;
        if (maletaComOVendedor) {
            UUID dono = (UUID) remessa.get("dispositivo_id");
            if (dono != null && !dono.equals(aparelho)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
            }
        }

        Instant quando = momento == null ? relogio.instant() : momento;
        LocalDate dia = RegrasDaDevolucao.dia(quando);

        // A autorização (se veio): a última trava. Inexistente ou de outro vendedor: 404 nos dois modos (ADR-0014, item 5).
        UUID autorizacaoUsada = null;
        boolean autorizacaoInvalida = false;
        if (pedido.autorizacaoId() != null) {
            List<Map<String, Object>> autorizacao = jdbc.queryForList("""
                    SELECT venda_id, vendedor_id, motivo, situacao, valida_ate FROM corona.autorizacoes_excecao
                     WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", pedido.autorizacaoId());
            if (autorizacao.isEmpty() || !vendedorId.equals(autorizacao.get(0).get("vendedor_id"))) {
                throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.AUTORIZACAO_NAO_ENCONTRADA);
            }
            CodigoErro problema = problemaDaAutorizacao(pedido.autorizacaoId(), autorizacao.get(0), pedido.vendaId(), motivo,
                    pecas, dia);
            if (problema == null) {
                autorizacaoUsada = pedido.autorizacaoId();
            } else if (modo != ModoDaDevolucao.ENVIO) {
                throw new ErroDaApi(HttpStatus.CONFLICT, problema);
            } else {
                autorizacaoInvalida = true;
            }
        }
        boolean autorizada = autorizacaoUsada != null;

        List<PecaCalculada> calculadas = RegrasDaDevolucao.calcular(jdbc, pedido.vendaId(), pecas);
        BigDecimal valorDevolvido = RegrasDaDevolucao.somar(calculadas);
        // A divisão do valor devolvido (tarefa 6.6; ADR-0014, item 10): primeiro abate o que o cliente ainda deve nas
        // parcelas (a venda já está travada, então o em aberto é estável); do resto, o crédito líquido da venda volta
        // primeiro como crédito (dúvidas T1 e T9; na troca, o resto todo é o crédito que paga B) e só o que sobrar em
        // dinheiro. Só somas e mínimos de valores com duas casas: nenhum arredondamento novo.
        BigDecimal zero = BigDecimal.ZERO.setScale(valorDevolvido.scale());
        BigDecimal valorAbatido = valorDevolvido.min(contas.emAberto(pedido.vendaId()).max(zero));
        BigDecimal resto = valorDevolvido.subtract(valorAbatido);
        BigDecimal valorEmCredito = troca != null ? resto : resto.min(creditoLiquido.max(zero));
        BigDecimal valorEmDinheiro = resto.subtract(valorEmCredito);

        // Prazo da garantia em dias do calendário de Brasília, pelo motivo (ADR-0014, item 4). Lido a cada devolução.
        Instant vendidaEm = ((Timestamp) venda.get("vendida_em")).toInstant();
        boolean dentroDaGarantia = RegrasDaDevolucao.diasDesdeAVenda(vendidaEm, quando)
                <= RegrasDaDevolucao.prazo(configuracoes, motivo);
        List<MotivoDaRevisao> motivos = new ArrayList<>();
        if (!dentroDaGarantia && !autorizada) {
            if (modo != ModoDaDevolucao.ENVIO) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.GARANTIA_VENCIDA);
            }
            motivos.add(MotivoDaRevisao.FORA_DO_PRAZO);
        }
        // O limite da semana e o valor de aprovação olham só o dinheiro; a troca fica fora dos dois (nenhum dinheiro sai,
        // dúvida T2). Para a venda sem crédito, o dinheiro é o valor devolvido inteiro (igual à tarefa 6.4).
        if (troca == null && !pelaDona) {
            // Valor que a dona aprova antes (vazio = nunca; "acima" é estrito: no valor exato passa).
            Optional<BigDecimal> valorDeAprovacao = configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.VALOR_APROVACAO_REEMBOLSO);
            if (!autorizada && valorDeAprovacao.isPresent() && valorEmDinheiro.compareTo(valorDeAprovacao.get()) > 0) {
                if (modo != ModoDaDevolucao.ENVIO) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_PRECISA_DE_AUTORIZACAO);
                }
                motivos.add(MotivoDaRevisao.ACIMA_DO_VALOR_SEM_AUTORIZACAO);
            }
            // Limite da semana (vazio = nunca): revisão nos dois modos, mesmo com autorização (dúvidas P3 a P6).
            Optional<BigDecimal> limite = configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.LIMITE_REEMBOLSO_SEMANAL);
            if (limite.isPresent() && reembolsadoNaSemana(vendedorId, dia).add(valorEmDinheiro).compareTo(limite.get()) > 0) {
                motivos.add(MotivoDaRevisao.ACIMA_DO_LIMITE_SEMANAL);
            }
        }
        if (autorizacaoInvalida) {
            motivos.add(MotivoDaRevisao.AUTORIZACAO_INVALIDA);
        }
        // Defesa: com as travas acima só acontece com dado torto (reembolso avulso gravado por fora). Só o dinheiro.
        if (jaReembolsado.add(valorEmDinheiro).compareTo(pagoEmDinheiro) > 0) {
            motivos.add(MotivoDaRevisao.REEMBOLSO_MAIOR_QUE_O_PAGO);
        }
        motivos.sort(null);
        String situacao = motivos.isEmpty() ? "ACEITA" : "EM_REVISAO";

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        UUID cotacao = RegrasDaDevolucao.cotacaoDoDia(jdbc, dia);
        UUID clienteId = (UUID) venda.get("cliente_id");
        jdbc.update("""
                INSERT INTO corona.devolucoes (id, venda_id, cliente_id, vendedor_id, remessa_id, motivo, observacao, destino,
                    dentro_garantia, situacao, autorizacao_id, valor_devolvido, valor_reembolsado, valor_em_credito,
                    valor_abatido, forma_reembolso, cotacao_id, devolvida_em, hora_ajustada, registrada_por, revisao_fora_do_prazo,
                    revisao_acima_do_limite_semanal, revisao_acima_do_valor_de_aprovacao, revisao_autorizacao_invalida,
                    revisao_reembolso_maior_que_pago)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, pedido.vendaId(), clienteId, vendedorId, remessaId, motivo, observacao, troca == null ? "REEMBOLSO" : "TROCA",
                dentroDaGarantia, situacao, autorizacaoUsada, valorDevolvido, valorEmDinheiro, valorEmCredito, valorAbatido, forma,
                cotacao,
                Timestamp.from(quando), horaAjustada, usuarioId, motivos.contains(MotivoDaRevisao.FORA_DO_PRAZO),
                motivos.contains(MotivoDaRevisao.ACIMA_DO_LIMITE_SEMANAL),
                motivos.contains(MotivoDaRevisao.ACIMA_DO_VALOR_SEM_AUTORIZACAO),
                motivos.contains(MotivoDaRevisao.AUTORIZACAO_INVALIDA),
                motivos.contains(MotivoDaRevisao.REEMBOLSO_MAIOR_QUE_O_PAGO));
        // Na troca, a peça boa volta à maleta como TROCA_ENTRADA (ADR-0014, item 8): mesmo sinal e mesmo saldo.
        String tipoDoMovimento = troca == null ? "DEVOLUCAO_CLIENTE" : "TROCA_ENTRADA";
        for (PecaCalculada item : calculadas) {
            String destino = "BOA".equals(item.estado()) && maletaComOVendedor ? "MALETA" : "LOJA";
            jdbc.update("""
                    INSERT INTO corona.itens_devolucao (id, devolucao_id, item_venda_id, quantidade, valor, estado_peca, destino_peca)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), id, item.linha().id(), item.quantidade(), item.valor(),
                    item.estado(), destino);
            if ("MALETA".equals(destino)) {
                jdbc.update("""
                        INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, dispositivo_id,
                            devolucao_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), remessaId, item.linha().produtoId(), tipoDoMovimento,
                        item.quantidade(), pedido.vendaId(), aparelho, id);
            }
        }
        UUID creditoGerado = null;
        if (valorEmCredito.signum() > 0) {
            // O que volta em crédito: o crédito do cliente nasce (o da troca com o id do aplicativo; o da devolução com um
            // id do servidor) e a venda ganha o reembolso na forma CREDITO, ligado a ele.
            creditoGerado = troca != null ? troca.creditoId() : UuidV7.gerar();
            creditos.gerar(creditoGerado, troca != null ? "TROCA" : "DEVOLUCAO", clienteId, vendedorId,
                    troca != null ? troca.trocaId() : null, id, valorEmCredito, quando, horaAjustada, usuarioId);
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, devolucao_id, credito_id)
                    VALUES (?, ?, 'REEMBOLSO', 'CREDITO', ?, ?, ?, ?)""", UuidV7.gerar(), pedido.vendaId(),
                    valorEmCredito.negate(), Timestamp.from(quando), id, creditoGerado);
        }
        if (valorEmDinheiro.signum() > 0) {
            // Venda à vista: o dinheiro volta inteiro na forma escolhida (ADR-0014, item 10).
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, devolucao_id)
                    VALUES (?, ?, 'REEMBOLSO', ?, ?, ?, ?)""", UuidV7.gerar(), pedido.vendaId(), forma, valorEmDinheiro.negate(),
                    Timestamp.from(quando), id);
        }
        if (autorizada) {
            // A devolução já está gravada com a autorização: o banco confere isso antes de aceitar a usada.
            jdbc.update("UPDATE corona.autorizacoes_excecao SET situacao = 'USADA' WHERE id = ?", autorizacaoUsada);
        }
        // O abatimento das parcelas: a última trava (as parcelas vêm depois dos créditos).
        contas.abaterNaDevolucao(pedido.vendaId(), id, valorAbatido, quando);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_id", pedido.vendaId().toString());
        depois.put("motivo", motivo);
        depois.put("destino", troca == null ? "REEMBOLSO" : "TROCA");
        depois.put("situacao", situacao);
        depois.put("dentro_garantia", dentroDaGarantia);
        depois.put("valor_devolvido", valorDevolvido.toPlainString());
        depois.put("valor_reembolsado", valorEmDinheiro.toPlainString());
        depois.put("valor_em_credito", valorEmCredito.toPlainString());
        depois.put("valor_abatido", valorAbatido.toPlainString());
        if (creditoGerado != null) {
            depois.put("credito_id", creditoGerado.toString());
        }
        depois.put("forma_reembolso", forma);
        if (pelaDona) {
            depois.put("registrada_pela_dona", true);
        }
        if (!motivos.isEmpty()) {
            depois.put("motivos_revisao", motivos.stream().map(Enum::name).toList());
        }
        if (autorizada) {
            depois.put("autorizacao_id", autorizacaoUsada.toString());
        }
        if (autorizacaoInvalida) {
            // A autorização que o computador mandou e não valeu (não foi consumida).
            depois.put("autorizacao_invalida_id", pedido.autorizacaoId().toString());
        }
        if (pedido.valorConferido() != null && pedido.valorConferido().compareTo(valorDevolvido) != 0) {
            // Divergência é alerta, não rejeição: vale o valor do servidor.
            depois.put("alerta_valor_do_app", pedido.valorConferido().toPlainString());
        }
        auditoria.registrar(Ator.usuario(usuarioId), "DEVOLUCAO_REGISTRADA", "devolucoes", id, null, depois);
        return new Gravada(new Resultado(id, false), valorDevolvido, valorEmCredito, clienteId);
    }

    // --- cancelar ---

    /**
     * Cancela a devolução: estorno de cada movimento da maleta, {@code ESTORNO_REEMBOLSO} de cada reembolso e a situação
     * {@code CANCELADA}. {@code vendedorId} nulo é a dona (a qualquer hora); o vendedor só cancela a dele e só no dia da
     * devolução (em Brasília). {@code momento} nulo = agora. O reenvio com o mesmo id do cancelamento devolve 200.
     */
    @Transactional
    public Resultado cancelar(UUID devolucaoId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoCancelamento,
            String motivo, Instant momento) {
        return cancelar(devolucaoId, vendedorId, usuarioId, aparelho, idDoCancelamento, motivo, momento, false);
    }

    /**
     * Cancela a devolução de A pelo "Cancelar troca" (tarefa 6.5), na transação dele: a troca já está cancelada e o
     * crédito da troca já teve o uso na venda nova estornado. As regras do cancelar valem iguais.
     */
    @Transactional
    public Resultado cancelarNaTroca(UUID devolucaoId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoCancelamento,
            String motivo, Instant momento) {
        return cancelar(devolucaoId, vendedorId, usuarioId, aparelho, idDoCancelamento, motivo, momento, true);
    }

    private Resultado cancelar(UUID devolucaoId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoCancelamento,
            String motivo, Instant momento, boolean pelaTroca) {
        UUID cancelamentoId = Validacoes.idDoPedido(idDoCancelamento);
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de cancelar a devolução.");
        }
        if (limpo.length() > LIMITE_DO_TEXTO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_TEXTO + " caracteres.");
        }
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT vendedor_id, venda_id, remessa_id FROM corona.devolucoes WHERE id = ? AND deleted_at IS NULL", devolucaoId);
        if (achada.isEmpty() || (vendedorId != null && !vendedorId.equals(achada.get(0).get("vendedor_id")))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.DEVOLUCAO_NAO_ENCONTRADA);
        }
        UUID vendaId = (UUID) achada.get(0).get("venda_id");
        UUID remessaId = (UUID) achada.get(0).get("remessa_id");
        Map<String, Object> remessa = jdbc.queryForMap(
                "SELECT status, dispositivo_id FROM corona.remessas WHERE id = ? FOR UPDATE", remessaId);
        jdbc.queryForList("SELECT id FROM corona.vendas WHERE id = ? FOR UPDATE", vendaId);
        Map<String, Object> devolucao = jdbc.queryForMap(
                "SELECT situacao, cancelamento_id, devolvida_em, destino FROM corona.devolucoes WHERE id = ? FOR UPDATE", devolucaoId);
        if ("TROCA".equals(devolucao.get("destino")) && !pelaTroca) {
            // A devolução de uma troca só se desfaz pelo "Cancelar troca" (dúvida T8); o banco é a segunda trava.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_DE_TROCA);
        }

        String situacao = (String) devolucao.get("situacao");
        if ("CANCELADA".equals(situacao)) {
            if (cancelamentoId.equals(devolucao.get("cancelamento_id"))) {
                return new Resultado(devolucaoId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_JA_CANCELADA);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.devolucoes WHERE cancelamento_id = ?)", Boolean.class, cancelamentoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_JA_REGISTRADA_DIFERENTE);
        }
        if (!"ACEITA".equals(situacao) && !"EM_REVISAO".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_NAO_PODE_SER_CANCELADA);
        }
        Instant quando = momento == null ? relogio.instant() : momento;
        if (vendedorId != null) {
            VendedorAtivo.exigir(jdbc, vendedorId);
            Instant devolvidaEm = ((Timestamp) devolucao.get("devolvida_em")).toInstant();
            if (!LocalDate.ofInstant(devolvidaEm, BRASILIA).equals(LocalDate.ofInstant(quando, BRASILIA))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CANCELAR_DEVOLUCAO_SO_DA_DONA);
            }
        }

        List<Map<String, Object>> movimentos = jdbc.queryForList("""
                SELECT id, produto_id, quantidade, tipo FROM corona.movimentos_remessa
                 WHERE devolucao_id = ? AND estorna_id IS NULL ORDER BY produto_id, id""", devolucaoId);
        if (!movimentos.isEmpty()) {
            // Nenhuma maleta fechada recebe movimento: a peça que voltou só sai da maleta enquanto ela está com o vendedor.
            if (!MALETA_COM_O_VENDEDOR.contains((String) remessa.get("status"))) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_NAO_PODE_SER_CANCELADA,
                        "A maleta desta devolução já não está com o vendedor. Fale com a dona.");
            }
            if (vendedorId != null) {
                UUID dono = (UUID) remessa.get("dispositivo_id");
                if (dono != null && !dono.equals(aparelho)) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
                }
            }
            for (Map<String, Object> m : movimentos) {
                int saldo = jdbc.queryForObject("""
                        SELECT coalesce((SELECT i.qtd_enviada FROM corona.itens_remessa i
                                          WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL), 0)
                               + coalesce((SELECT sum(x.quantidade) FROM corona.movimentos_remessa x
                                            WHERE x.remessa_id = ? AND x.produto_id = ?), 0)""", Integer.class,
                        remessaId, m.get("produto_id"), remessaId, m.get("produto_id"));
                if (saldo < ((Number) m.get("quantidade")).intValue()) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PECA_DA_DEVOLUCAO_JA_VENDIDA);
                }
            }
        }

        // O crédito que a devolução gerou (troca, ou venda paga com crédito): a última trava; já usado, 409.
        UUID credito = jdbc.query("SELECT id FROM corona.creditos_cliente WHERE devolucao_id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), devolucaoId).stream().findFirst().orElse(null);
        if (credito != null) {
            creditos.exigirSemUso(credito);
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        Timestamp hora = momento == null ? null : Timestamp.from(momento);
        for (Map<String, Object> m : movimentos) {
            // O estorno tem o mesmo tipo do movimento (DEVOLUCAO_CLIENTE ou TROCA_ENTRADA) e o sinal oposto.
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, dispositivo_id,
                        estorna_id, devolucao_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), remessaId, m.get("produto_id"), m.get("tipo"),
                    -((Number) m.get("quantidade")).intValue(), vendaId, aparelho, m.get("id"), devolucaoId);
        }
        List<Map<String, Object>> reembolsos = jdbc.queryForList("""
                SELECT id, forma, valor, credito_id FROM corona.pagamentos_cliente
                 WHERE devolucao_id = ? AND tipo = 'REEMBOLSO' ORDER BY pago_em, id""", devolucaoId);
        for (Map<String, Object> r : reembolsos) {
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, devolucao_id, estorna_id,
                        credito_id)
                    VALUES (?, ?, 'ESTORNO_REEMBOLSO', ?, ?, coalesce(?, now()), ?, ?, ?)""", UuidV7.gerar(), vendaId,
                    r.get("forma"), ((BigDecimal) r.get("valor")).negate(), hora, devolucaoId, r.get("id"), r.get("credito_id"));
        }
        jdbc.update("""
                UPDATE corona.devolucoes SET situacao = 'CANCELADA', cancelamento_id = ?, cancelada_em = coalesce(?, now()),
                       cancelada_por = ?, motivo_cancelamento = ?
                 WHERE id = ?""", cancelamentoId, hora, usuarioId, limpo, devolucaoId);
        if (credito != null) {
            creditos.cancelar(credito, usuarioId, momento);
        }
        // O que a devolução abateu das parcelas volta a ser devido (estorno das baixas); as parcelas são a última trava.
        contas.desfazerAbatimento(devolucaoId, momento);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", "CANCELADA");
        depois.put("motivo", limpo);
        auditoria.registrar(Ator.usuario(usuarioId), "DEVOLUCAO_CANCELADA", "devolucoes", devolucaoId,
                Map.of("situacao", situacao), depois);
        return new Resultado(devolucaoId, false);
    }

    // --- revisão da dona ---

    /**
     * "Manter" a devolução para revisão (tarefa 6.4; ADR-0014, item 5): ela vale como está. Nada muda além da situação e
     * dos dados da revisão. Repetir devolve 200; a outra decisão já tomada, 409. Observação opcional.
     */
    @Transactional
    public Resultado manter(UUID devolucaoId, UUID usuarioId, String observacao) {
        return revisar(devolucaoId, usuarioId, observacao, "MANTIDA");
    }

    /**
     * "Não aceitar" a devolução para revisão: nenhum movimento, pagamento ou valor muda (o dinheiro já saiu e a peça já
     * voltou); o valor devolvido fica como pendência do vendedor, que o acerto (tarefa 7.5) passa a cobrar. Observação
     * obrigatória.
     */
    @Transactional
    public Resultado naoAceitar(UUID devolucaoId, UUID usuarioId, String observacao) {
        if (Validacoes.limpar(observacao) == null) {
            throw Validacoes.recusar("observacao", "Diga por que a devolução não foi aceita.");
        }
        return revisar(devolucaoId, usuarioId, observacao, "NAO_ACEITA");
    }

    private Resultado revisar(UUID devolucaoId, UUID usuarioId, String observacao, String decisao) {
        String limpa = Validacoes.limpar(observacao);
        if (limpa != null && limpa.length() > LIMITE_DO_TEXTO) {
            throw Validacoes.recusar("observacao", "Use no máximo " + LIMITE_DO_TEXTO + " caracteres.");
        }
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT venda_id, remessa_id FROM corona.devolucoes WHERE id = ? AND deleted_at IS NULL", devolucaoId);
        if (achada.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.DEVOLUCAO_NAO_ENCONTRADA);
        }
        // Travas na ordem do cancelar: a maleta, a venda e a devolução.
        jdbc.queryForList("SELECT id FROM corona.remessas WHERE id = ? FOR UPDATE", achada.get(0).get("remessa_id"));
        jdbc.queryForList("SELECT id FROM corona.vendas WHERE id = ? FOR UPDATE", achada.get(0).get("venda_id"));
        String situacao = jdbc.queryForObject("SELECT situacao FROM corona.devolucoes WHERE id = ? FOR UPDATE", String.class,
                devolucaoId);
        if (decisao.equals(situacao)) {
            return new Resultado(devolucaoId, true);
        }
        if ("MANTIDA".equals(situacao) || "NAO_ACEITA".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_JA_REVISADA);
        }
        if (!"EM_REVISAO".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_NAO_ESTA_EM_REVISAO);
        }
        jdbc.update("""
                UPDATE corona.devolucoes SET situacao = ?, revisada_em = ?, revisada_por = ?, observacao_revisao = ?
                 WHERE id = ?""", decisao, Timestamp.from(relogio.instant()), usuarioId, limpa, devolucaoId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", decisao);
        if (limpa != null) {
            depois.put("observacao", limpa);
        }
        auditoria.registrar(Ator.usuario(usuarioId), "MANTIDA".equals(decisao) ? "DEVOLUCAO_MANTIDA" : "DEVOLUCAO_NAO_ACEITA",
                "devolucoes", devolucaoId, Map.of("situacao", situacao), depois);
        return new Resultado(devolucaoId, false);
    }

    // --- internos ---

    /**
     * O que impede a autorização de valer nesta devolução (dúvidas P6 e P7), ou nulo se vale: precisa estar aprovada, no
     * prazo dela (o último dia de Brasília; a regra olha a data, nunca depende da agenda das vencidas) e ser da mesma
     * venda, do mesmo motivo e com no máximo as peças pedidas (menos pode).
     */
    private CodigoErro problemaDaAutorizacao(UUID autorizacaoId, Map<String, Object> autorizacao, UUID vendaId, String motivo,
            List<Peca> pecas, LocalDate dia) {
        String situacao = (String) autorizacao.get("situacao");
        if ("PEDIDA".equals(situacao) || "NEGADA".equals(situacao)) {
            return CodigoErro.AUTORIZACAO_NAO_APROVADA;
        }
        if ("USADA".equals(situacao)) {
            return CodigoErro.AUTORIZACAO_JA_USADA;
        }
        Object validaAte = autorizacao.get("valida_ate");
        LocalDate ate = validaAte instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) validaAte;
        if ("VENCIDA".equals(situacao) || ate == null || ate.isBefore(dia)) {
            return CodigoErro.AUTORIZACAO_VENCIDA;
        }
        if (!vendaId.equals(autorizacao.get("venda_id")) || !motivo.equals(autorizacao.get("motivo"))) {
            return CodigoErro.AUTORIZACAO_NAO_CONFERE;
        }
        Map<UUID, Integer> autorizadas = new LinkedHashMap<>();
        jdbc.query("""
                SELECT iv.produto_id, a.quantidade FROM corona.itens_autorizacao a
                  JOIN corona.itens_venda iv ON iv.id = a.item_venda_id
                 WHERE a.autorizacao_id = ?""", rs -> {
                    autorizadas.put(rs.getObject(1, UUID.class), rs.getInt(2));
                }, autorizacaoId);
        for (Map.Entry<UUID, Integer> pedida : RegrasDaDevolucao.quantidadePorProduto(pecas).entrySet()) {
            Integer podem = autorizadas.get(pedida.getKey());
            if (podem == null || pedida.getValue() > podem) {
                return CodigoErro.AUTORIZACAO_NAO_CONFERE;
            }
        }
        return null;
    }

    /**
     * O reembolso das devoluções não canceladas do vendedor (também as em revisão e as não aceitas; dúvida P4) na semana
     * do dia: a semana cadastrada que contém o dia; sem semana cadastrada, de segunda a domingo de Brasília (dúvida P3).
     */
    private BigDecimal reembolsadoNaSemana(UUID vendedorId, LocalDate dia) {
        List<LocalDate[]> cadastrada = jdbc.query(
                "SELECT inicio, fim FROM corona.semanas WHERE inicio <= ? AND fim >= ? AND deleted_at IS NULL LIMIT 1",
                (rs, n) -> new LocalDate[] {rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)}, dia, dia);
        LocalDate inicio = cadastrada.isEmpty() ? dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : cadastrada.get(0)[0];
        LocalDate fim = cadastrada.isEmpty() ? inicio.plusDays(6) : cadastrada.get(0)[1];
        return jdbc.queryForObject("""
                SELECT coalesce(sum(valor_reembolsado), 0) FROM corona.devolucoes
                 WHERE vendedor_id = ? AND situacao <> 'CANCELADA' AND deleted_at IS NULL
                   AND devolvida_em >= ? AND devolvida_em < ?""", BigDecimal.class, vendedorId,
                Timestamp.from(inicio.atStartOfDay(BRASILIA).toInstant()),
                Timestamp.from(fim.plusDays(1).atStartOfDay(BRASILIA).toInstant()));
    }

    /**
     * O pedido repetido é a mesma devolução? Compara dono, venda, motivo, observação, forma, peças, quantidades, estados e
     * a autorização. A autorização que não valeu no envio não fica gravada: o reenvio precisa trazer uma autorização.
     */
    private boolean mesmaDevolucao(UUID id, UUID vendedorId, UUID vendaId, String motivo, String observacao, String forma,
            List<Peca> pecas, UUID autorizacaoId, Map<String, Object> gravada) {
        if (!vendedorId.equals(gravada.get("vendedor_id")) || !vendaId.equals(gravada.get("venda_id"))
                || !motivo.equals(gravada.get("motivo")) || !forma.equals(gravada.get("forma_reembolso"))
                || !Objects.equals(observacao, gravada.get("observacao"))) {
            return false;
        }
        UUID autorizacaoGravada = (UUID) gravada.get("autorizacao_id");
        boolean autorizacaoQueNaoValeu = Boolean.TRUE.equals(gravada.get("revisao_autorizacao_invalida"));
        if (autorizacaoGravada != null ? !autorizacaoGravada.equals(autorizacaoId)
                : autorizacaoQueNaoValeu != (autorizacaoId != null)) {
            return false;
        }
        Map<String, Integer> pedidas = new LinkedHashMap<>();
        pecas.forEach(p -> pedidas.put(p.produtoId() + "/" + p.estado(), p.quantidade()));
        Map<String, Integer> gravadas = new LinkedHashMap<>();
        jdbc.query("""
                SELECT iv.produto_id, i.estado_peca, i.quantidade
                  FROM corona.itens_devolucao i JOIN corona.itens_venda iv ON iv.id = i.item_venda_id
                 WHERE i.devolucao_id = ?""", rs -> {
                    gravadas.put(rs.getObject(1, UUID.class) + "/" + rs.getString(2), rs.getInt(3));
                }, id);
        return pedidas.equals(gravadas);
    }
}
