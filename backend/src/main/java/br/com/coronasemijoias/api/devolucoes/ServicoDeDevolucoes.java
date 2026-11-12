package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.OrdemDoBanco;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devolução do cliente de venda à vista e "Cancelar devolução" (tarefa 6.3; ADR-0014, itens 1 a 7 e 11). Regras:
 * <ul>
 *   <li>A venda não muda. O app manda a venda, as peças (pelo produto), quantidade, estado, motivo e a forma do
 *       reembolso; o servidor calcula o prazo, o valor (preço pago, {@link ValorDaDevolucao}) e o destino da peça.</li>
 *   <li>Travas na mesma ordem do estorno da venda (ADR-0008): a maleta da venda e depois a venda. Devolução e estorno da
 *       mesma venda nunca se travam em ordem oposta; o banco é a segunda trava (V036).</li>
 *   <li>Peça boa de maleta ainda com o vendedor volta ao saldo (movimento {@code DEVOLUCAO_CLIENTE}); peça boa de maleta
 *       acertada e toda peça com defeito são "peça para a loja", sem movimento.</li>
 *   <li>Fora do prazo: na rota online, 409; pelo envio do computador ({@link ModoDaDevolucao#ENVIO}), aceita para revisão da dona
 *       (dúvida R2): o que já aconteceu no balcão nunca é recusado em silêncio.</li>
 *   <li>Idempotência pelo id do aplicativo: mesmo id e mesmo conteúdo, 200; conteúdo diferente, 409.</li>
 * </ul>
 */
@Service
public class ServicoDeDevolucoes {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final int LIMITE_DE_LINHAS = 200;
    private static final int LIMITE_DA_QUANTIDADE = 100_000;
    private static final int LIMITE_DO_TEXTO = 400;
    private static final Set<String> MOTIVOS = Set.of("ARREPENDIMENTO", "DEFEITO", "TAMANHO", "OUTRO");
    private static final Set<String> ESTADOS = Set.of("BOA", "DEFEITO");
    private static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX", "CARTAO", "CHEQUE");
    private static final Set<String> MALETA_COM_O_VENDEDOR = Set.of("ENVIADA", "CONFERIDA");

    /** De onde veio a devolução: a rota online (fora do prazo = 409) ou o envio do computador (fora do prazo = revisão). */
    public enum ModoDaDevolucao {
        ONLINE,
        ENVIO
    }

    public record PecaDoPedido(UUID produtoId, Integer quantidade, String estado) {
    }

    /** {@code valorConferido} é o valor que a tela mostrou: só serve para avisar divergência (nunca vale). */
    public record PedidoDaDevolucaoDoCliente(UUID id, UUID vendaId, String motivo, String observacao, String formaReembolso,
            List<PecaDoPedido> itens, BigDecimal valorConferido) {
    }

    /** {@code jaExistia}: a devolução (ou o cancelamento) já estava gravada (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID devolucaoId, boolean jaExistia) {
    }

    private record Peca(UUID produtoId, int quantidade, String estado) {
    }

    private record LinhaDaVenda(UUID id, UUID produtoId, String nome, int quantidade, BigDecimal total) {
    }

    private record ItemNovo(LinhaDaVenda linha, int quantidade, String estado, String destino, BigDecimal valor) {
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
        String motivo = pedido.motivo();
        if (motivo == null || !MOTIVOS.contains(motivo)) {
            throw Validacoes.recusar("motivo", "Escolha o motivo da devolução: desistência, defeito, não serviu ou outro.");
        }
        String observacao = Validacoes.limpar(pedido.observacao());
        if (observacao != null && observacao.length() > LIMITE_DO_TEXTO) {
            throw Validacoes.recusar("observacao", "Use no máximo " + LIMITE_DO_TEXTO + " caracteres.");
        }
        if ("OUTRO".equals(motivo) && observacao == null) {
            throw Validacoes.recusar("observacao", "Conte o motivo da devolução no campo de observação.");
        }
        String forma = pedido.formaReembolso();
        if (forma == null || !FORMAS.contains(forma)) {
            throw Validacoes.recusar("formaReembolso", "Escolha como o dinheiro foi devolvido: dinheiro, Pix, cartão ou cheque.");
        }
        List<Peca> pecas = conferirPecas(pedido.itens(), motivo);
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
        // Travas: a maleta e depois a venda (a mesma ordem do estorno).
        Map<String, Object> remessa = jdbc.queryForMap(
                "SELECT status, dispositivo_id FROM corona.remessas WHERE id = ? FOR UPDATE", remessaId);
        Map<String, Object> venda = jdbc.queryForMap(
                "SELECT status, cliente_id, total, vendida_em FROM corona.vendas WHERE id = ? FOR UPDATE", pedido.vendaId());

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT venda_id, vendedor_id, motivo, observacao, forma_reembolso FROM corona.devolucoes WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (!mesmaDevolucao(id, vendedorId, pedido.vendaId(), motivo, observacao, forma, pecas, existente.get(0))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(id, true);
        }

        VendedorAtivo.exigir(jdbc, vendedorId);
        if ("ESTORNADA".equals(venda.get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        BigDecimal total = (BigDecimal) venda.get("total");
        BigDecimal pago = jdbc.queryForObject("""
                SELECT coalesce(sum(valor), 0) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO'""",
                BigDecimal.class, pedido.vendaId());
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

        // As peças contra as linhas da venda (uma linha por produto) e contra o que ainda pode voltar.
        Map<UUID, LinhaDaVenda> linhas = new LinkedHashMap<>();
        jdbc.query("""
                SELECT i.id, i.produto_id, p.nome, i.quantidade, i.preco_unitario * i.quantidade - i.desconto AS total
                  FROM corona.itens_venda i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.venda_id = ?""", rs -> {
                    UUID produto = rs.getObject("produto_id", UUID.class);
                    linhas.put(produto, new LinhaDaVenda(rs.getObject("id", UUID.class), produto, rs.getString("nome"),
                            rs.getInt("quantidade"), rs.getBigDecimal("total")));
                }, pedido.vendaId());
        for (Peca p : pecas) {
            if (!linhas.containsKey(p.produtoId())) {
                throw Validacoes.recusar("produtoId", "Esta peça não consta nesta venda.");
            }
        }
        List<ItemNovo> itens = new ArrayList<>();
        BigDecimal valorDevolvido = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        for (Map.Entry<UUID, List<Peca>> doProduto : porProduto(pecas).entrySet()) {
            LinhaDaVenda linha = linhas.get(doProduto.getKey());
            Map<String, Object> ja = jdbc.queryForMap("""
                    SELECT coalesce(sum(i.quantidade), 0) AS quantidade, coalesce(sum(i.valor), 0) AS valor
                      FROM corona.itens_devolucao i JOIN corona.devolucoes d ON d.id = i.devolucao_id
                     WHERE i.item_venda_id = ? AND d.situacao <> 'CANCELADA'""", linha.id());
            int jaDevolvidas = ((Number) ja.get("quantidade")).intValue();
            BigDecimal valorJa = Dinheiro.somar(List.of((BigDecimal) ja.get("valor")));
            int pedidas = doProduto.getValue().stream().mapToInt(Peca::quantidade).sum();
            int podem = linha.quantidade() - jaDevolvidas;
            if (pedidas > podem) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_ACIMA_DO_VENDIDO, podem <= 0
                        ? "Todas as peças de \"" + linha.nome() + "\" desta venda já foram devolvidas."
                        : "Desta venda ainda podem voltar só " + podem + " peça(s) de \"" + linha.nome()
                                + "\". Confira a quantidade.");
            }
            for (Peca p : doProduto.getValue()) {
                BigDecimal valor = ValorDaDevolucao.calcular(linha.total(), linha.quantidade(), jaDevolvidas, valorJa,
                        p.quantidade());
                String destino = "BOA".equals(p.estado()) && maletaComOVendedor ? "MALETA" : "LOJA";
                itens.add(new ItemNovo(linha, p.quantidade(), p.estado(), destino, valor));
                jaDevolvidas += p.quantidade();
                valorJa = valorJa.add(valor);
                valorDevolvido = valorDevolvido.add(valor);
            }
        }

        // Prazo da garantia em dias do calendário de Brasília, pelo motivo (ADR-0014, item 4). Lido a cada devolução.
        Instant quando = momento == null ? relogio.instant() : momento;
        int prazo = configuracoes.inteiro("DEFEITO".equals(motivo) ? ChaveDeConfiguracao.PRAZO_DEFEITO_DIAS
                : ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS);
        Instant vendidaEm = ((Timestamp) venda.get("vendida_em")).toInstant();
        long dias = ChronoUnit.DAYS.between(LocalDate.ofInstant(vendidaEm, BRASILIA), LocalDate.ofInstant(quando, BRASILIA));
        boolean dentroDaGarantia = dias <= prazo;
        String situacao = "ACEITA";
        if (!dentroDaGarantia) {
            if (modo != ModoDaDevolucao.ENVIO) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.GARANTIA_VENCIDA);
            }
            situacao = "EM_REVISAO";
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        UUID cotacao = cotacaoDoDia(LocalDate.ofInstant(quando, BRASILIA));
        jdbc.update("""
                INSERT INTO corona.devolucoes (id, venda_id, cliente_id, vendedor_id, remessa_id, motivo, observacao, destino,
                    dentro_garantia, situacao, valor_devolvido, valor_reembolsado, forma_reembolso, cotacao_id, devolvida_em,
                    hora_ajustada, registrada_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REEMBOLSO', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, pedido.vendaId(), venda.get("cliente_id"), vendedorId, remessaId, motivo, observacao, dentroDaGarantia,
                situacao, valorDevolvido, valorDevolvido, forma, cotacao, Timestamp.from(quando), horaAjustada, usuarioId);
        for (ItemNovo item : itens) {
            jdbc.update("""
                    INSERT INTO corona.itens_devolucao (id, devolucao_id, item_venda_id, quantidade, valor, estado_peca, destino_peca)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), id, item.linha().id(), item.quantidade(), item.valor(),
                    item.estado(), item.destino());
            if ("MALETA".equals(item.destino())) {
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

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_id", pedido.vendaId().toString());
        depois.put("motivo", motivo);
        depois.put("situacao", situacao);
        depois.put("dentro_garantia", dentroDaGarantia);
        depois.put("valor_devolvido", valorDevolvido.toPlainString());
        depois.put("forma_reembolso", forma);
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

    // --- internos ---

    private static List<Peca> conferirPecas(List<PecaDoPedido> pedidas, String motivo) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw Validacoes.recusar("itens", "Informe pelo menos uma peça devolvida.");
        }
        if (pedidas.size() > LIMITE_DE_LINHAS) {
            throw Validacoes.recusar("itens", "Devolva no máximo " + LIMITE_DE_LINHAS + " peças diferentes por vez.");
        }
        List<Peca> pecas = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (PecaDoPedido p : pedidas) {
            if (p == null || p.produtoId() == null) {
                throw Validacoes.recusar("produtoId", "Escolha a peça devolvida.");
            }
            if (p.quantidade() == null || p.quantidade() <= 0) {
                throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
            }
            if (p.quantidade() > LIMITE_DA_QUANTIDADE) {
                throw Validacoes.recusar("quantidade", "A quantidade é grande demais. Confira o número.");
            }
            if (p.estado() == null || !ESTADOS.contains(p.estado())) {
                throw Validacoes.recusar("estado", "Diga se a peça voltou boa ou com defeito.");
            }
            if ("DEFEITO".equals(motivo) && !"DEFEITO".equals(p.estado())) {
                // O prazo maior do defeito só vale para peça com defeito (dúvida Q3).
                throw Validacoes.recusar("estado", "Com o motivo defeito, marque as peças como com defeito.");
            }
            if (!vistas.add(p.produtoId() + "/" + p.estado())) {
                throw Validacoes.recusar("itens",
                        "A mesma peça aparece mais de uma vez com o mesmo estado. Junte as quantidades numa linha só.");
            }
            pecas.add(new Peca(p.produtoId(), p.quantidade(), p.estado()));
        }
        return pecas;
    }

    /** As peças agrupadas por produto, em ordem estável (produto na ordem do banco; boa antes de com defeito). */
    private static Map<UUID, List<Peca>> porProduto(List<Peca> pecas) {
        List<Peca> ordenadas = new ArrayList<>(pecas);
        ordenadas.sort(Comparator.comparing(Peca::produtoId, OrdemDoBanco.UUIDS).thenComparing(Peca::estado));
        Map<UUID, List<Peca>> grupos = new LinkedHashMap<>();
        for (Peca p : ordenadas) {
            grupos.computeIfAbsent(p.produtoId(), k -> new ArrayList<>()).add(p);
        }
        return grupos;
    }

    /** A cotação mais recente até o dia (horário de Brasília), ou nenhuma. */
    private UUID cotacaoDoDia(LocalDate dia) {
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), dia).stream().findFirst().orElse(null);
    }

    /** O pedido repetido é a mesma devolução? Compara dono, venda, motivo, observação, forma, peças, quantidades e estados. */
    private boolean mesmaDevolucao(UUID id, UUID vendedorId, UUID vendaId, String motivo, String observacao, String forma,
            List<Peca> pecas, Map<String, Object> gravada) {
        if (!vendedorId.equals(gravada.get("vendedor_id")) || !vendaId.equals(gravada.get("venda_id"))
                || !motivo.equals(gravada.get("motivo")) || !forma.equals(gravada.get("forma_reembolso"))
                || !java.util.Objects.equals(observacao, gravada.get("observacao"))) {
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
