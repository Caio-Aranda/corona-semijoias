package br.com.coronasemijoias.api.vendas;

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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Venda do vendedor e estorno (tarefa 4.2; ADR-0011). Regras que valem aqui:
 * <ul>
 *   <li>O app manda produto, quantidade, desconto e pagamentos. Preço, subtotal e total saem do servidor, do preço
 *       congelado da remessa ({@code preco_remessa}).</li>
 *   <li>Toda operação trava a linha da remessa primeiro; o saldo da peça é {@code qtd_enviada} mais os movimentos da
 *       maleta (nunca um número guardado) e não passa de zero.</li>
 *   <li>Venda, itens, pagamentos e movimentos são imutáveis. Corrigir é estornar a venda inteira.</li>
 *   <li>Idempotência pelo id do aplicativo: mesmo id e mesmo conteúdo é "já gravado" (200); conteúdo diferente, 409.</li>
 * </ul>
 */
@Service
public class ServicoDeVendas {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final int LIMITE_DE_LINHAS = 200;
    private static final int LIMITE_DE_PAGAMENTOS = 10;
    private static final int LIMITE_DA_QUANTIDADE = 100_000;
    private static final int LIMITE_DO_MOTIVO = 400;
    private static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX", "CARTAO", "CHEQUE");

    public record LinhaDoPedido(UUID produtoId, Integer quantidade, BigDecimal desconto) {
    }

    public record PagamentoDoPedido(UUID id, BigDecimal valor, String forma) {
    }

    /** {@code totalConferido} é o total que a tela mostrou: só serve para avisar divergência (nunca vale). */
    public record PedidoDeVenda(UUID id, UUID remessaId, UUID clienteId, List<LinhaDoPedido> itens,
            List<PagamentoDoPedido> pagamentos, BigDecimal totalConferido) {
    }

    /** {@code jaExistia}: a venda (ou o estorno) já estava gravada (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID vendaId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Configuracoes configuracoes;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;

    ServicoDeVendas(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, Configuracoes configuracoes,
            OrigemDoDispositivo origem, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.configuracoes = configuracoes;
        this.origem = origem;
        this.relogio = relogio;
    }

    // --- venda ---

    private record Linha(UUID produtoId, int quantidade, BigDecimal desconto) {
    }

    private record Pagamento(UUID id, BigDecimal valor, String forma) {
    }

    private record Peca(String nome, int saldo, BigDecimal preco) {
    }

    @Transactional
    public Resultado vender(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeVenda pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("venda", "Informe a venda.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.remessaId() == null) {
            throw Validacoes.recusar("remessaId", "Escolha a maleta da venda.");
        }
        if (pedido.clienteId() == null) {
            throw Validacoes.recusar("clienteId", "Escolha o cliente da venda.");
        }
        List<Linha> linhas = conferirLinhas(pedido.itens());
        List<Pagamento> pagamentos = conferirPagamentos(pedido.pagamentos());
        if (pedido.totalConferido() != null && pedido.totalConferido().signum() < 0) {
            throw Validacoes.recusar("totalConferido", "O total conferido não pode ser negativo.");
        }

        // Trava a maleta (e confere que é do vendedor): duas vendas na mesma maleta esperam uma pela outra.
        Map<String, Object> remessa = trancarMaleta(pedido.remessaId(), vendedorId);

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT vendedor_id, cliente_id, remessa_id FROM corona.vendas WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (!mesmaVenda(id, vendedorId, pedido, linhas, pagamentos, existente.get(0))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(id, true);
        }

        br.com.coronasemijoias.api.vendedor.VendedorAtivo.exigir(jdbc, vendedorId);
        String status = (String) remessa.get("status");
        if (!"ENVIADA".equals(status) && !"CONFERIDA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_ESTA_COM_VOCE);
        }
        UUID dono = (UUID) remessa.get("dispositivo_id");
        if (dono != null && !dono.equals(aparelho)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
        }
        conferirCliente(pedido.clienteId(), vendedorId);
        if (dono == null && aparelho != null) {
            // A primeira venda liga a maleta a este aparelho (um aparelho por maleta; ADR-0011, item 11).
            origem.definirNaTransacaoAtual(aparelho);
            jdbc.update("UPDATE corona.remessas SET dispositivo_id = ? WHERE id = ?", aparelho, pedido.remessaId());
        }

        // Preço, saldo e totais: tudo do servidor, em ordem estável de produto.
        List<Linha> ordenadas = new ArrayList<>(linhas);
        ordenadas.sort(Comparator.comparing(Linha::produtoId, OrdemDoBanco.UUIDS));
        Map<UUID, Peca> pecas = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        BigDecimal desconto = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        for (Linha l : ordenadas) {
            Peca p = peca(pedido.remessaId(), l.produtoId());
            if (p == null) {
                throw Validacoes.recusar("produtoId", "Esta peça não consta na sua maleta.");
            }
            if (l.quantidade() > p.saldo()) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.VENDA_SEM_SALDO, p.saldo() == 0
                        ? "Não há mais peças de \"" + p.nome() + "\" na sua maleta."
                        : "Na sua maleta há só " + p.saldo() + " peça(s) de \"" + p.nome() + "\" e você informou "
                                + l.quantidade() + ". Confira a quantidade.");
            }
            BigDecimal linha = Dinheiro.precoDaLinha(p.preco(), l.quantidade());
            if (l.desconto().compareTo(linha) > 0) {
                throw Validacoes.recusar("desconto", "O desconto de \"" + p.nome() + "\" é maior que o valor da peça.");
            }
            pecas.put(l.produtoId(), p);
            subtotal = subtotal.add(linha);
            desconto = desconto.add(l.desconto());
        }
        int limite = configuracoes.inteiro(ChaveDeConfiguracao.LIMITE_DESCONTO_PERCENTUAL);
        BigDecimal maximo = Dinheiro.aplicarPorcentagem(subtotal, BigDecimal.valueOf(limite));
        if (desconto.compareTo(maximo) > 0) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.DESCONTO_ACIMA_DO_LIMITE, limite == 0
                    ? "A dona não liberou desconto nas vendas. Tire o desconto e tente de novo."
                    : "O desconto passa do limite que a dona definiu (" + limite + "% do valor, no máximo R$ "
                            + maximo.toPlainString().replace('.', ',') + "). Diminua o desconto.");
        }
        BigDecimal total = subtotal.subtract(desconto);
        BigDecimal pago = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        for (Pagamento p : pagamentos) {
            pago = pago.add(p.valor());
        }
        if (pago.compareTo(total) > 0) {
            throw Validacoes.recusar("pagamentos", "O valor pago passa do total da venda (R$ "
                    + total.toPlainString().replace('.', ',') + "). Confira os pagamentos.");
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        UUID cotacao = cotacaoDoDia();
        jdbc.update("""
                INSERT INTO corona.vendas (id, vendedor_id, cliente_id, remessa_id, subtotal, desconto, total, cotacao_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, vendedorId, pedido.clienteId(), pedido.remessaId(), subtotal, desconto,
                total, cotacao);
        for (Linha l : ordenadas) {
            Peca p = pecas.get(l.produtoId());
            jdbc.update("""
                    INSERT INTO corona.itens_venda (id, venda_id, produto_id, quantidade, preco_unitario, desconto)
                    VALUES (?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), id, l.produtoId(), l.quantidade(), p.preco(), l.desconto());
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, dispositivo_id)
                    VALUES (?, ?, ?, 'VENDA', ?, ?, ?)""", UuidV7.gerar(), pedido.remessaId(), l.produtoId(), -l.quantidade(), id,
                    aparelho);
        }
        for (Pagamento p : pagamentos) {
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor) VALUES (?, ?, 'PAGAMENTO', ?, ?)""",
                    p.id(), id, p.forma(), p.valor());
        }

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("remessa_id", pedido.remessaId().toString());
        depois.put("cliente_id", pedido.clienteId().toString());
        depois.put("subtotal", subtotal.toPlainString());
        depois.put("desconto", desconto.toPlainString());
        depois.put("total", total.toPlainString());
        depois.put("pago", pago.toPlainString());
        if (pedido.totalConferido() != null && pedido.totalConferido().compareTo(total) != 0) {
            // Divergência é alerta, não rejeição: vale o total do servidor (ADR-0011, item 4).
            depois.put("alerta_total_do_app", pedido.totalConferido().toPlainString());
        }
        auditoria.registrar(Ator.usuario(usuarioId), "VENDA_REGISTRADA", "vendas", id, null, depois);
        return new Resultado(id, false);
    }

    // --- estorno ---

    /**
     * Estorna a venda inteira. {@code vendedorId} nulo é a dona (sem limite de prazo); o vendedor só estorna a própria
     * venda e só no mesmo dia (ADR-0011, item 8). O reenvio com o mesmo id do estorno devolve 200.
     */
    @Transactional
    public Resultado estornar(UUID vendaId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoEstorno, String motivo) {
        UUID estornoId = Validacoes.idDoPedido(idDoEstorno);
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar a venda.");
        }
        if (limpo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT vendedor_id, remessa_id FROM corona.vendas WHERE id = ? AND deleted_at IS NULL", vendaId);
        if (achada.isEmpty() || (vendedorId != null && !vendedorId.equals(achada.get(0).get("vendedor_id")))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        UUID remessaId = (UUID) achada.get(0).get("remessa_id");
        Map<String, Object> remessa = trancarMaleta(remessaId, (UUID) achada.get(0).get("vendedor_id"));

        Map<String, Object> venda = jdbc.queryForMap(
                "SELECT status, estorno_id FROM corona.vendas WHERE id = ? FOR UPDATE", vendaId);
        if ("ESTORNADA".equals(venda.get("status"))) {
            if (estornoId.equals(venda.get("estorno_id"))) {
                return new Resultado(vendaId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_ESTORNADA);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.vendas WHERE estorno_id = ?)", Boolean.class, estornoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDA_JA_REGISTRADA_DIFERENTE);
        }
        String statusDaMaleta = (String) remessa.get("status");
        if (!"ENVIADA".equals(statusDaMaleta) && !"CONFERIDA".equals(statusDaMaleta)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_ESTA_COM_VOCE);
        }
        if (vendedorId != null) {
            UUID dono = (UUID) remessa.get("dispositivo_id");
            if (dono != null && !dono.equals(aparelho)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
            }
            LocalDate diaDaVenda = jdbc.queryForObject(
                    "SELECT (vendida_em AT TIME ZONE 'America/Sao_Paulo')::date FROM corona.vendas WHERE id = ?", LocalDate.class, vendaId);
            if (!diaDaVenda.equals(LocalDate.now(relogio.withZone(BRASILIA)))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ESTORNO_SO_DA_DONA);
            }
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        List<Map<String, Object>> baixas = jdbc.queryForList("""
                SELECT id, produto_id, quantidade FROM corona.movimentos_remessa
                 WHERE venda_id = ? AND tipo = 'VENDA' AND estorna_id IS NULL ORDER BY produto_id, id""", vendaId);
        for (Map<String, Object> b : baixas) {
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, dispositivo_id, estorna_id)
                    VALUES (?, ?, ?, 'ESTORNO_VENDA', ?, ?, ?, ?)""", UuidV7.gerar(), remessaId, b.get("produto_id"),
                    -((Number) b.get("quantidade")).intValue(), vendaId, aparelho, b.get("id"));
        }
        // O que o cliente pagou volta como reembolso (registro negativo, uma linha por pagamento, mesma forma).
        List<Map<String, Object>> pagos = jdbc.queryForList("""
                SELECT forma, valor FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO' ORDER BY pago_em, id""",
                vendaId);
        for (Map<String, Object> p : pagos) {
            jdbc.update("""
                    INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor) VALUES (?, ?, 'REEMBOLSO', ?, ?)""",
                    UuidV7.gerar(), vendaId, p.get("forma"), ((BigDecimal) p.get("valor")).negate());
        }
        jdbc.update("""
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = now(), estornada_por = ?,
                       motivo_estorno = ? WHERE id = ?""", estornoId, usuarioId, limpo, vendaId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("status", "ESTORNADA");
        depois.put("motivo", limpo);
        auditoria.registrar(Ator.usuario(usuarioId), "VENDA_ESTORNADA", "vendas", vendaId, Map.of("status", "CONCLUIDA"), depois);
        return new Resultado(vendaId, false);
    }

    // --- internos ---

    private static List<Linha> conferirLinhas(List<LinhaDoPedido> pedidas) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw Validacoes.recusar("itens", "Informe pelo menos uma peça na venda.");
        }
        if (pedidas.size() > LIMITE_DE_LINHAS) {
            throw Validacoes.recusar("itens", "Venda no máximo " + LIMITE_DE_LINHAS + " peças diferentes por vez.");
        }
        List<Linha> linhas = new ArrayList<>();
        Set<UUID> produtos = new HashSet<>();
        for (LinhaDoPedido l : pedidas) {
            if (l == null || l.produtoId() == null) {
                throw Validacoes.recusar("produtoId", "Escolha a peça vendida.");
            }
            if (l.quantidade() == null || l.quantidade() <= 0) {
                throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
            }
            if (l.quantidade() > LIMITE_DA_QUANTIDADE) {
                throw Validacoes.recusar("quantidade", "A quantidade é grande demais. Confira o número.");
            }
            if (!produtos.add(l.produtoId())) {
                throw Validacoes.recusar("itens", "A mesma peça aparece mais de uma vez. Junte as quantidades numa linha só.");
            }
            BigDecimal desconto = l.desconto() == null ? BigDecimal.ZERO : l.desconto();
            if (desconto.signum() < 0) {
                throw Validacoes.recusar("desconto", "O desconto não pode ser negativo.");
            }
            linhas.add(new Linha(l.produtoId(), l.quantidade(), dinheiro(desconto, "desconto")));
        }
        return linhas;
    }

    private static List<Pagamento> conferirPagamentos(List<PagamentoDoPedido> pedidos) {
        List<Pagamento> pagamentos = new ArrayList<>();
        if (pedidos == null) {
            return pagamentos;
        }
        if (pedidos.size() > LIMITE_DE_PAGAMENTOS) {
            throw Validacoes.recusar("pagamentos", "Informe no máximo " + LIMITE_DE_PAGAMENTOS + " pagamentos.");
        }
        Set<UUID> ids = new HashSet<>();
        for (PagamentoDoPedido p : pedidos) {
            if (p == null || p.valor() == null || p.valor().signum() <= 0) {
                throw Validacoes.recusar("pagamentos", "Informe o valor pago, maior que zero.");
            }
            if (p.forma() == null || !FORMAS.contains(p.forma())) {
                throw Validacoes.recusar("forma", "Escolha como o cliente pagou: dinheiro, Pix, cartão ou cheque.");
            }
            UUID id = Validacoes.idDoPedido(p.id());
            if (!ids.add(id)) {
                throw Validacoes.recusar("pagamentos", "O mesmo pagamento aparece mais de uma vez.");
            }
            pagamentos.add(new Pagamento(id, dinheiro(p.valor(), "valor"), p.forma()));
        }
        return pagamentos;
    }

    /** Valor em Real com no máximo duas casas; mais que isso é recusado (nunca arredondado em silêncio). */
    private static BigDecimal dinheiro(BigDecimal valor, String campo) {
        if (valor.stripTrailingZeros().scale() > Dinheiro.ESCALA || valor.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw Validacoes.recusar(campo, "Use um valor em reais com no máximo duas casas depois da vírgula.");
        }
        return valor.setScale(Dinheiro.ESCALA);
    }

    /** Trava a linha da maleta e confere que é do vendedor (404 se não for, sem dizer de quem é). */
    private Map<String, Object> trancarMaleta(UUID remessaId, UUID vendedorId) {
        List<Map<String, Object>> r = jdbc.queryForList("""
                SELECT status, dispositivo_id FROM corona.remessas
                 WHERE id = ? AND vendedor_id = ? AND deleted_at IS NULL FOR UPDATE""", remessaId, vendedorId);
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        return r.get(0);
    }

    private void conferirCliente(UUID clienteId, UUID vendedorId) {
        boolean meu = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.clientes WHERE id = ? AND vendedor_responsavel_id = ?
                                AND deleted_at IS NULL AND ativo)""", Boolean.class, clienteId, vendedorId));
        if (!meu) {
            throw Validacoes.recusar("clienteId", "Este cliente não está na sua lista. Escolha ou cadastre o cliente.");
        }
    }

    /** Saldo atual da peça na maleta (qtd_enviada mais os movimentos) e o preço congelado. Vazio se a peça não está. */
    private Peca peca(UUID remessaId, UUID produtoId) {
        return jdbc.query("""
                SELECT p.nome, i.preco_remessa,
                       i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                               WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) AS saldo
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                (rs, n) -> new Peca(rs.getString(1), rs.getInt(3), rs.getBigDecimal(2)), remessaId, produtoId)
                .stream().findFirst().orElse(null);
    }

    /** A cotação mais recente até hoje (horário de Brasília), ou nenhuma. */
    private UUID cotacaoDoDia() {
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), LocalDate.now(relogio.withZone(BRASILIA))).stream().findFirst().orElse(null);
    }

    /** O pedido repetido é a mesma venda? Compara dono, cliente, maleta, peças (com desconto) e pagamentos. */
    private boolean mesmaVenda(UUID id, UUID vendedorId, PedidoDeVenda pedido, List<Linha> linhas, List<Pagamento> pagamentos,
            Map<String, Object> gravada) {
        if (!vendedorId.equals(gravada.get("vendedor_id")) || !pedido.clienteId().equals(gravada.get("cliente_id"))
                || !pedido.remessaId().equals(gravada.get("remessa_id"))) {
            return false;
        }
        Map<UUID, Linha> pedidas = new LinkedHashMap<>();
        linhas.forEach(l -> pedidas.put(l.produtoId(), l));
        List<Map<String, Object>> itens = jdbc.queryForList(
                "SELECT produto_id, quantidade, desconto FROM corona.itens_venda WHERE venda_id = ?", id);
        if (itens.size() != pedidas.size()) {
            return false;
        }
        for (Map<String, Object> i : itens) {
            Linha l = pedidas.get(i.get("produto_id"));
            if (l == null || l.quantidade() != ((Number) i.get("quantidade")).intValue()
                    || l.desconto().compareTo((BigDecimal) i.get("desconto")) != 0) {
                return false;
            }
        }
        Map<UUID, Pagamento> pagos = new LinkedHashMap<>();
        pagamentos.forEach(p -> pagos.put(p.id(), p));
        List<Map<String, Object>> gravados = jdbc.queryForList(
                "SELECT id, forma, valor FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO'", id);
        if (gravados.size() != pagos.size()) {
            return false;
        }
        for (Map<String, Object> g : gravados) {
            Pagamento p = pagos.get(g.get("id"));
            if (p == null || !p.forma().equals(g.get("forma")) || p.valor().compareTo((BigDecimal) g.get("valor")) != 0) {
                return false;
            }
        }
        return true;
    }
}
