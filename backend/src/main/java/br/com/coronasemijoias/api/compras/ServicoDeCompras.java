package br.com.coronasemijoias.api.compras;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CampoInvalido;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.produtos.CodigosDosProdutos;
import br.com.coronasemijoias.api.produtos.CustosDosProdutos;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compras de mercadoria (tarefa 2.3). Uma compra é gravada de uma vez, numa transação só: compra, itens, entrada no
 * estoque (SOMENTE pelo {@link ServicoDeEstoque}), custo do produto e histórico do custo. O servidor recalcula
 * tudo. Desfazer a compra desfaz as entradas pelo mesmo serviço.
 *
 * <p>Decisões: o mesmo produto não pode aparecer duas vezes na mesma compra (a dona soma a quantidade numa linha);
 * o custo do produto passa a ser o da compra gravada agora; ao desfazer, o custo só volta se esta compra ainda era a
 * que dava o custo atual (ver {@link #devolverCusto}).
 */
@Service
public class ServicoDeCompras {

    public static final int LIMITE_DE_ITENS = 500;
    private static final int LIMITE_DA_QUANTIDADE = 1_000_000;
    private static final BigDecimal LIMITE_DO_VALOR = new BigDecimal("999999999999.99");

    /** Uma linha pedida: o produto OU o código (um dos dois). */
    public record ItemPedido(UUID produtoId, String codigo, Integer quantidade, BigDecimal custoUnitario) {
    }

    public record PedidoDeCompra(UUID id, UUID fornecedorId, LocalDate dataCompra, String numero, String observacoes,
            List<ItemPedido> itens) {
    }

    public record Resultado(UUID compraId, boolean jaExistia) {
    }

    private record Resolvido(UUID produtoId, int quantidade, BigDecimal custo, BigDecimal total) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeEstoque estoque;
    private final CodigosDosProdutos codigos;
    private final CustosDosProdutos custos;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    ServicoDeCompras(JdbcTemplate jdbc, ServicoDeEstoque estoque, CodigosDosProdutos codigos, CustosDosProdutos custos,
            ServicoDeAuditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.estoque = estoque;
        this.codigos = codigos;
        this.custos = custos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    // --- registrar ---

    @Transactional
    public Resultado registrar(PedidoDeCompra pedido, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        UUID id = Validacoes.idDoPedido(pedido.id());
        // Dois envios do mesmo id ao mesmo tempo esperam um pelo outro; o segundo vê a compra do primeiro.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, id.toString());

        String numero = Validacoes.limpar(pedido.numero());
        String observacoes = Validacoes.limpar(pedido.observacoes());
        conferirCabecalho(pedido, numero, observacoes);
        List<Resolvido> itens = resolver(pedido.itens());

        Optional<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT fornecedor_id, data_compra, numero, observacoes FROM corona.compras WHERE id = ?", id).stream().findFirst();
        if (existente.isPresent()) {
            if (!igual(id, existente.get(), pedido, numero, observacoes, itens)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COMPRA_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(id, true);
        }

        BigDecimal total = Dinheiro.somar(itens.stream().map(Resolvido::total).toList());
        if (total.compareTo(LIMITE_DO_VALOR) > 0) {
            throw Validacoes.recusar("itens", "O total da compra é grande demais. Confira as quantidades e os custos.");
        }
        jdbc.update("""
                INSERT INTO corona.compras (id, fornecedor_id, data_compra, numero, observacoes, total, usuario_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, pedido.fornecedorId(), pedido.dataCompra(), numero, observacoes, total, usuarioId);

        List<UUID> movimentoIds = new ArrayList<>();
        List<Pedido> entradas = new ArrayList<>();
        for (Resolvido item : itens) {
            UUID movimento = UuidV7.gerar();
            movimentoIds.add(movimento);
            entradas.add(new Pedido(movimento, item.produtoId(), TipoDeMovimento.ENTRADA, item.quantidade(), null,
                    OrigemDoMovimento.COMPRA, id));
        }
        // O serviço trava os produtos em ordem de id; daqui em diante eles estão travados.
        estoque.registrar(entradas, usuarioId);

        for (int i = 0; i < itens.size(); i++) {
            Resolvido item = itens.get(i);
            jdbc.update("""
                    INSERT INTO corona.itens_compra (id, compra_id, produto_id, quantidade, custo_unitario, total, movimento_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), id, item.produtoId(), item.quantidade(), item.custo(),
                    item.total(), movimentoIds.get(i));
            BigDecimal anterior = custos.obter(item.produtoId()).orElse(null);
            jdbc.update("""
                    INSERT INTO corona.historico_custo (id, produto_id, compra_id, custo, custo_anterior, data_compra)
                    VALUES (?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), item.produtoId(), id, item.custo(), anterior,
                    pedido.dataCompra());
            custos.gravar(item.produtoId(), item.custo());
        }
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("fornecedor_id", pedido.fornecedorId().toString());
        depois.put("data_compra", pedido.dataCompra().toString());
        depois.put("total", total.toPlainString());
        depois.put("itens", itens.size());
        auditoria.registrar(Ator.usuario(usuarioId), "COMPRA_REGISTRADA", "compras", id, null, depois);
        return new Resultado(id, false);
    }

    private void conferirCabecalho(PedidoDeCompra p, String numero, String observacoes) {
        if (p.fornecedorId() == null) {
            throw Validacoes.recusar("fornecedorId", "Escolha o fornecedor da compra.");
        }
        Boolean existe = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.fornecedores WHERE id = ? AND deleted_at IS NULL)", Boolean.class,
                p.fornecedorId());
        if (!Boolean.TRUE.equals(existe)) {
            throw Validacoes.recusar("fornecedorId", "Este fornecedor não existe mais. Atualize a tela e escolha de novo.");
        }
        if (p.dataCompra() == null) {
            throw Validacoes.recusar("dataCompra", "Informe a data da compra.");
        }
        if (p.dataCompra().isAfter(LocalDate.now(relogio.withZone(ZoneOffset.UTC)).plusDays(1))) {
            throw Validacoes.recusar("dataCompra", "A data da compra não pode ser no futuro.");
        }
        if (numero != null && numero.length() > 60) {
            throw Validacoes.recusar("numero", "Use no máximo 60 caracteres no número da nota.");
        }
        if (observacoes != null && observacoes.length() > 1000) {
            throw Validacoes.recusar("observacoes", "Use no máximo 1000 caracteres nas observações.");
        }
    }

    /** Resolve cada linha (produto ou código), confere tudo e devolve na ordem estável de produto. Erros vêm por linha. */
    private List<Resolvido> resolver(List<ItemPedido> pedidos) {
        if (pedidos == null || pedidos.isEmpty()) {
            throw Validacoes.recusar("itens", "Informe pelo menos uma peça na compra.");
        }
        if (pedidos.size() > LIMITE_DE_ITENS) {
            throw Validacoes.recusar("itens", "Use no máximo " + LIMITE_DE_ITENS + " peças por compra.");
        }
        List<CampoInvalido> erros = new ArrayList<>();
        Map<UUID, Resolvido> porProduto = new LinkedHashMap<>();
        for (int i = 0; i < pedidos.size(); i++) {
            ItemPedido it = pedidos.get(i);
            String campo = "itens[" + i + "]";
            if (it == null) {
                erros.add(new CampoInvalido(campo, "Preencha a linha ou tire ela da compra."));
                continue;
            }
            String codigo = Validacoes.limpar(it.codigo());
            if ((it.produtoId() == null) == (codigo == null)) {
                erros.add(new CampoInvalido(campo, "Informe a peça pelo código ou escolhendo o produto, só um dos dois."));
                continue;
            }
            UUID produtoId = it.produtoId();
            String nome = null;
            if (codigo != null) {
                Optional<UUID> achado = codigos.produtoDoCodigo(codigo);
                if (achado.isEmpty()) {
                    erros.add(new CampoInvalido(campo + ".codigo", "Não encontramos nenhuma peça com o código " + codigo
                            + ". Cadastre a peça antes de receber a compra."));
                    continue;
                }
                produtoId = achado.get();
            }
            List<String> nomes = jdbc.query("SELECT nome FROM corona.produtos WHERE id = ? AND deleted_at IS NULL",
                    (rs, n) -> rs.getString(1), produtoId);
            if (nomes.isEmpty()) {
                erros.add(new CampoInvalido(campo + ".produtoId", "Esta peça não existe mais. Atualize a tela e escolha de novo."));
                continue;
            }
            nome = nomes.get(0);
            if (it.quantidade() == null || it.quantidade() <= 0) {
                erros.add(new CampoInvalido(campo + ".quantidade", "Informe uma quantidade maior que zero."));
                continue;
            }
            if (it.quantidade() > LIMITE_DA_QUANTIDADE) {
                erros.add(new CampoInvalido(campo + ".quantidade", "A quantidade é grande demais. Confira o número."));
                continue;
            }
            if (it.custoUnitario() == null || it.custoUnitario().signum() < 0) {
                erros.add(new CampoInvalido(campo + ".custoUnitario", "Informe o custo da peça. Pode ser zero, nunca menor."));
                continue;
            }
            BigDecimal custo = Dinheiro.arredondar(it.custoUnitario());
            BigDecimal total = Dinheiro.precoDaLinha(custo, it.quantidade());
            if (total.compareTo(LIMITE_DO_VALOR) > 0) {
                erros.add(new CampoInvalido(campo + ".custoUnitario", "O custo é grande demais. Confira o valor."));
                continue;
            }
            if (porProduto.containsKey(produtoId)) {
                erros.add(new CampoInvalido(campo, "A peça \"" + nome + "\" aparece mais de uma vez nesta compra. "
                        + "Some as quantidades numa linha só."));
                continue;
            }
            porProduto.put(produtoId, new Resolvido(produtoId, it.quantidade(), custo, total));
        }
        if (!erros.isEmpty()) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.DADOS_INVALIDOS, erros);
        }
        List<Resolvido> ordenados = new ArrayList<>(porProduto.values());
        ordenados.sort(Comparator.comparing(Resolvido::produtoId));
        return ordenados;
    }

    private boolean igual(UUID id, Map<String, Object> gravada, PedidoDeCompra pedido, String numero, String observacoes,
            List<Resolvido> itens) {
        if (!pedido.fornecedorId().equals(gravada.get("fornecedor_id"))
                || !pedido.dataCompra().equals(((java.sql.Date) gravada.get("data_compra")).toLocalDate())
                || !Objects.equals(numero, gravada.get("numero")) || !Objects.equals(observacoes, gravada.get("observacoes"))) {
            return false;
        }
        Map<UUID, String> gravados = new HashMap<>();
        jdbc.query("SELECT produto_id, quantidade, custo_unitario FROM corona.itens_compra WHERE compra_id = ?", rs -> {
            gravados.put(rs.getObject(1, UUID.class), rs.getInt(2) + "|" + rs.getBigDecimal(3).toPlainString());
        }, id);
        if (gravados.size() != itens.size()) {
            return false;
        }
        for (Resolvido r : itens) {
            if (!(r.quantidade() + "|" + r.custo().toPlainString()).equals(gravados.get(r.produtoId()))) {
                return false;
            }
        }
        return true;
    }

    // --- desfazer ---

    /**
     * Desfaz a compra: cada entrada é desfeita pelo serviço de estoque (produtos em ordem de id). Recusa, sem mexer
     * em nada, se já saíram peças. Reenviar o mesmo {@code id} do pedido devolve a compra já desfeita.
     */
    @Transactional
    public Resultado desfazer(UUID compraId, UUID id, String motivo, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        UUID idDoPedido = Validacoes.idDoPedido(id);
        String motivoLimpo = Validacoes.limpar(motivo);
        if (motivoLimpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de desfazer esta compra.");
        }
        if (motivoLimpo.length() > 500) {
            throw Validacoes.recusar("motivo", "Use no máximo 500 caracteres.");
        }
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT situacao, estorno_id, estorno_motivo FROM corona.compras WHERE id = ? FOR UPDATE", compraId);
        if (linhas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.COMPRA_NAO_ENCONTRADA);
        }
        Map<String, Object> compra = linhas.get(0);
        if ("ESTORNADA".equals(compra.get("situacao"))) {
            if (idDoPedido.equals(compra.get("estorno_id")) && motivoLimpo.equals(compra.get("estorno_motivo"))) {
                return new Resultado(compraId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COMPRA_JA_DESFEITA);
        }
        record Item(UUID produtoId, UUID movimentoId) {
        }
        List<Item> itens = jdbc.query(
                "SELECT produto_id, movimento_id FROM corona.itens_compra WHERE compra_id = ? ORDER BY produto_id",
                (rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), compraId);
        for (Item item : itens) {
            try {
                estoque.estornar(UuidV7.gerar(), item.movimentoId(), motivoLimpo, usuarioId);
            } catch (ErroDaApi e) {
                if (e.codigo() == CodigoErro.ESTOQUE_INSUFICIENTE) {
                    Map<String, Object> p = jdbc.queryForMap("SELECT nome, estoque_atual FROM corona.produtos WHERE id = ?",
                            item.produtoId());
                    throw ErroDaApi.compraNaoPodeSerDesfeita((String) p.get("nome"), ((Number) p.get("estoque_atual")).intValue());
                }
                throw e;
            }
            devolverCusto(item.produtoId(), compraId);
        }
        jdbc.update("""
                UPDATE corona.compras SET situacao = 'ESTORNADA', estorno_id = ?, estorno_motivo = ?, estornada_em = ?,
                       estornada_por = ? WHERE id = ?""", idDoPedido, motivoLimpo, Timestamp.from(relogio.instant()), usuarioId,
                compraId);
        auditoria.registrar(Ator.usuario(usuarioId), "COMPRA_ESTORNADA", "compras", compraId,
                Map.of("situacao", "REGISTRADA"), Map.of("situacao", "ESTORNADA", "motivo", motivoLimpo));
        return new Resultado(compraId, false);
    }

    /**
     * O custo do produto só volta se esta compra era a mais recente entre as não desfeitas E o custo atual ainda é o
     * dela (se a dona mudou o custo à mão depois, fica como está). Volta para o custo da compra anterior que continua
     * valendo; se não há, para o custo que o produto tinha antes da primeira compra. O histórico nunca é apagado.
     * O produto já está travado (o desfazer do estoque travou).
     */
    private void devolverCusto(UUID produtoId, UUID compraId) {
        List<Map<String, Object>> validos = jdbc.queryForList("""
                SELECT h.compra_id, h.custo, h.ordem FROM corona.historico_custo h
                  JOIN corona.compras c ON c.id = h.compra_id
                 WHERE h.produto_id = ? AND c.situacao = 'REGISTRADA' ORDER BY h.ordem DESC""", produtoId);
        if (validos.isEmpty() || !compraId.equals(validos.get(0).get("compra_id"))) {
            return;
        }
        BigDecimal dela = (BigDecimal) validos.get(0).get("custo");
        Optional<BigDecimal> atual = custos.obter(produtoId);
        if (atual.isEmpty() || atual.get().compareTo(dela) != 0) {
            return;
        }
        BigDecimal volta;
        if (validos.size() > 1) {
            volta = (BigDecimal) validos.get(1).get("custo");
        } else {
            List<BigDecimal> base = jdbc.query(
                    "SELECT custo_anterior FROM corona.historico_custo WHERE produto_id = ? ORDER BY ordem ASC LIMIT 1",
                    (rs, n) -> rs.getBigDecimal(1), produtoId);
            volta = base.isEmpty() ? null : base.get(0);
        }
        if (volta != null) {
            custos.gravar(produtoId, volta);
        }
    }

}
