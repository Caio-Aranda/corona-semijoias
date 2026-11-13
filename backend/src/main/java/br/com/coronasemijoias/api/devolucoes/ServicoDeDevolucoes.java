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
 *       a revisão da dona pegam só maleta e venda, na mesma ordem, então a ordem nunca se inverte.</li>
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

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Configuracoes configuracoes;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;

    ServicoDeDevolucoes(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, Configuracoes configuracoes,
            OrigemDoDispositivo origem, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.configuracoes = configuracoes;
        this.origem = origem;
        this.relogio = relogio;
    }

    // --- devolução ---

    /**
     * Registra a devolução. {@code momento} é a hora da devolução (a do envio já corrigida pelo servidor, ADR-0013, item
     * 11); nulo = agora (a rota online). {@code horaAjustada} marca a hora corrigida em mais de 5 minutos.
     */
    @Transactional
    public Resultado devolver(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDaDevolucaoDoCliente pedido, Instant momento,
            boolean horaAjustada, ModoDaDevolucao modo) {
        if (pedido == null) {
            throw Validacoes.recusar("devolucao", "Informe a devolução.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.vendaId() == null) {
            throw Validacoes.recusar("vendaId", "Escolha a venda da devolução.");
        }
        String motivo = RegrasDaDevolucao.conferirMotivo(pedido.motivo());
        String observacao = RegrasDaDevolucao.conferirObservacao(pedido.observacao(), motivo);
        String forma = pedido.formaReembolso();
        if (forma == null || !FORMAS.contains(forma)) {
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
        // Travas: o vendedor, a maleta, a venda e (mais abaixo) a autorização. Ver a ordem no comentário da classe.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "corona.devolucoes.vendedor:" + vendedorId);
        Map<String, Object> remessa = jdbc.queryForMap(
                "SELECT status, dispositivo_id FROM corona.remessas WHERE id = ? FOR UPDATE", remessaId);
        Map<String, Object> venda = jdbc.queryForMap(
                "SELECT status, cliente_id, total, vendida_em FROM corona.vendas WHERE id = ? FOR UPDATE", pedido.vendaId());

        List<Map<String, Object>> existente = jdbc.queryForList("""
                SELECT venda_id, vendedor_id, motivo, observacao, forma_reembolso, autorizacao_id, revisao_autorizacao_invalida
                  FROM corona.devolucoes WHERE id = ?""", id);
        if (!existente.isEmpty()) {
            if (!mesmaDevolucao(id, vendedorId, pedido.vendaId(), motivo, observacao, forma, pecas, pedido.autorizacaoId(),
                    existente.get(0))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(id, true);
        }

        VendedorAtivo.exigir(jdbc, vendedorId);
        if ("ESTORNADA".equals(venda.get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        BigDecimal total = (BigDecimal) venda.get("total");
        Map<String, Object> dinheiro = jdbc.queryForMap("""
                SELECT coalesce(sum(valor) FILTER (WHERE tipo = 'PAGAMENTO'), 0) AS pago,
                       coalesce(-sum(valor) FILTER (WHERE tipo IN ('REEMBOLSO', 'ESTORNO_REEMBOLSO')), 0) AS reembolsado
                  FROM corona.pagamentos_cliente WHERE venda_id = ?""", pedido.vendaId());
        BigDecimal pago = (BigDecimal) dinheiro.get("pago");
        BigDecimal jaReembolsado = (BigDecimal) dinheiro.get("reembolsado");
        if (pago.compareTo(total) < 0) {
            // Venda a prazo: a devolução dela depende das parcelas (tarefa 6.6; dúvida R5).
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_A_PRAZO_SEM_DEVOLUCAO);
        }
        String statusDaMaleta = (String) remessa.get("status");
        if ("EM_ACERTO".equals(statusDaMaleta)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_ACERTO);
        }
        boolean maletaComOVendedor = MALETA_COM_O_VENDEDOR.contains(statusDaMaleta);
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
        // Valor que a dona aprova antes (vazio = nunca; "acima" é estrito: no valor exato passa).
        Optional<BigDecimal> valorDeAprovacao = configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.VALOR_APROVACAO_REEMBOLSO);
        if (!autorizada && valorDeAprovacao.isPresent() && valorDevolvido.compareTo(valorDeAprovacao.get()) > 0) {
            if (modo != ModoDaDevolucao.ENVIO) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_PRECISA_DE_AUTORIZACAO);
            }
            motivos.add(MotivoDaRevisao.ACIMA_DO_VALOR_SEM_AUTORIZACAO);
        }
        // Limite da semana (vazio = nunca): revisão nos dois modos, mesmo com autorização (dúvidas P3 a P6).
        Optional<BigDecimal> limite = configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.LIMITE_REEMBOLSO_SEMANAL);
        if (limite.isPresent() && reembolsadoNaSemana(vendedorId, dia).add(valorDevolvido).compareTo(limite.get()) > 0) {
            motivos.add(MotivoDaRevisao.ACIMA_DO_LIMITE_SEMANAL);
        }
        if (autorizacaoInvalida) {
            motivos.add(MotivoDaRevisao.AUTORIZACAO_INVALIDA);
        }
        // Defesa: com as travas acima só acontece com dado torto (reembolso avulso gravado por fora).
        if (jaReembolsado.add(valorDevolvido).compareTo(pago) > 0) {
            motivos.add(MotivoDaRevisao.REEMBOLSO_MAIOR_QUE_O_PAGO);
        }
        motivos.sort(null);
        String situacao = motivos.isEmpty() ? "ACEITA" : "EM_REVISAO";

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        UUID cotacao = RegrasDaDevolucao.cotacaoDoDia(jdbc, dia);
        jdbc.update("""
                INSERT INTO corona.devolucoes (id, venda_id, cliente_id, vendedor_id, remessa_id, motivo, observacao, destino,
                    dentro_garantia, situacao, autorizacao_id, valor_devolvido, valor_reembolsado, forma_reembolso, cotacao_id,
                    devolvida_em, hora_ajustada, registrada_por, revisao_fora_do_prazo, revisao_acima_do_limite_semanal,
                    revisao_acima_do_valor_de_aprovacao, revisao_autorizacao_invalida, revisao_reembolso_maior_que_pago)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REEMBOLSO', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, pedido.vendaId(), venda.get("cliente_id"), vendedorId, remessaId, motivo, observacao, dentroDaGarantia,
                situacao, autorizacaoUsada, valorDevolvido, valorDevolvido, forma, cotacao, Timestamp.from(quando), horaAjustada,
                usuarioId, motivos.contains(MotivoDaRevisao.FORA_DO_PRAZO),
                motivos.contains(MotivoDaRevisao.ACIMA_DO_LIMITE_SEMANAL),
                motivos.contains(MotivoDaRevisao.ACIMA_DO_VALOR_SEM_AUTORIZACAO),
                motivos.contains(MotivoDaRevisao.AUTORIZACAO_INVALIDA),
                motivos.contains(MotivoDaRevisao.REEMBOLSO_MAIOR_QUE_O_PAGO));
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
                        VALUES (?, ?, ?, 'DEVOLUCAO_CLIENTE', ?, ?, ?, ?)""", UuidV7.gerar(), remessaId, item.linha().produtoId(),
                        item.quantidade(), pedido.vendaId(), aparelho, id);
            }
        }
        if (valorDevolvido.signum() > 0) {
            // Venda à vista: o reembolso é o valor devolvido inteiro, na forma escolhida (ADR-0014, item 10).
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, devolucao_id)
                    VALUES (?, ?, 'REEMBOLSO', ?, ?, ?, ?)""", UuidV7.gerar(), pedido.vendaId(), forma, valorDevolvido.negate(),
                    Timestamp.from(quando), id);
        }
        if (autorizada) {
            // A devolução já está gravada com a autorização: o banco confere isso antes de aceitar a usada.
            jdbc.update("UPDATE corona.autorizacoes_excecao SET situacao = 'USADA' WHERE id = ?", autorizacaoUsada);
        }

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_id", pedido.vendaId().toString());
        depois.put("motivo", motivo);
        depois.put("situacao", situacao);
        depois.put("dentro_garantia", dentroDaGarantia);
        depois.put("valor_devolvido", valorDevolvido.toPlainString());
        depois.put("forma_reembolso", forma);
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
        return new Resultado(id, false);
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
                "SELECT situacao, cancelamento_id, devolvida_em FROM corona.devolucoes WHERE id = ? FOR UPDATE", devolucaoId);

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
                SELECT id, produto_id, quantidade FROM corona.movimentos_remessa
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

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        Timestamp hora = momento == null ? null : Timestamp.from(momento);
        for (Map<String, Object> m : movimentos) {
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, dispositivo_id,
                        estorna_id, devolucao_id)
                    VALUES (?, ?, ?, 'DEVOLUCAO_CLIENTE', ?, ?, ?, ?, ?)""", UuidV7.gerar(), remessaId, m.get("produto_id"),
                    -((Number) m.get("quantidade")).intValue(), vendaId, aparelho, m.get("id"), devolucaoId);
        }
        List<Map<String, Object>> reembolsos = jdbc.queryForList("""
                SELECT id, forma, valor FROM corona.pagamentos_cliente
                 WHERE devolucao_id = ? AND tipo = 'REEMBOLSO' ORDER BY pago_em, id""", devolucaoId);
        for (Map<String, Object> r : reembolsos) {
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, devolucao_id, estorna_id)
                    VALUES (?, ?, 'ESTORNO_REEMBOLSO', ?, ?, coalesce(?, now()), ?, ?)""", UuidV7.gerar(), vendaId,
                    r.get("forma"), ((BigDecimal) r.get("valor")).negate(), hora, devolucaoId, r.get("id"));
        }
        jdbc.update("""
                UPDATE corona.devolucoes SET situacao = 'CANCELADA', cancelamento_id = ?, cancelada_em = coalesce(?, now()),
                       cancelada_por = ?, motivo_cancelamento = ?
                 WHERE id = ?""", cancelamentoId, hora, usuarioId, limpo, devolucaoId);
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
