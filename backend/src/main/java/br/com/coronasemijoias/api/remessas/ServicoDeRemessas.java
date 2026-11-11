package br.com.coronasemijoias.api.remessas;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Remessas (maletas) da dona (tarefa 3.2; ADR-0010). O rascunho não mexe no estoque. O envio, numa transação só:
 * confere vendedor, peças e limite, congela o preço de venda, baixa o estoque central pelo {@link ServicoDeEstoque}
 * (tudo ou nada) e marca a remessa como enviada. O servidor recalcula tudo; o aplicativo manda só produto e quantidade.
 *
 * <p>Decisões: uma linha por produto e remessa; o limite do vendedor é obrigatório (valor em reais, onde zero vale zero, ou "Sem limite"; V026)
 * (o banco guarda 0 por padrão); não há movimento de "saldo inicial" na maleta, pois o saldo é {@code qtd_enviada}
 * mais os movimentos (§7.1); dois envios do mesmo vendedor esperam um pelo outro (trava por vendedor), para o limite
 * não ser furado.
 */
@Service
public class ServicoDeRemessas {

    private static final Set<String> TIPOS = Set.of("NOVA", "REPOSICAO");
    private static final int LIMITE_DA_QUANTIDADE = 100_000;
    private static final int LIMITE_DE_ITENS = 500;

    public record PedidoDeRascunho(UUID id, UUID vendedorId, UUID semanaId, UUID pracaId, String tipo) {
    }

    /** {@code jaExistia}: o mesmo pedido já tinha sido gravado (200); senão, primeira vez (201). */
    public record Resultado(UUID remessaId, boolean jaExistia) {
    }

    private record Peca(UUID id, String nome, BigDecimal preco, boolean ativa, int quantidade) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeEstoque estoque;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;
    private final ConsultasDaMaleta maleta;

    ServicoDeRemessas(JdbcTemplate jdbc, ServicoDeEstoque estoque, ServicoDeAuditoria auditoria, Clock relogio,
            ConsultasDaMaleta maleta) {
        this.maleta = maleta;
        this.jdbc = jdbc;
        this.estoque = estoque;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    // --- rascunho ---

    @Transactional
    public Resultado criarRascunho(PedidoDeRascunho pedido, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        UUID id = Validacoes.idDoPedido(pedido.id());
        travar("remessa:" + id);
        if (pedido.vendedorId() == null) {
            throw Validacoes.recusar("vendedorId", "Escolha o vendedor da maleta.");
        }
        if (pedido.semanaId() == null) {
            throw Validacoes.recusar("semanaId", "Escolha a semana da maleta.");
        }
        if (pedido.tipo() == null || !TIPOS.contains(pedido.tipo())) {
            throw Validacoes.recusar("tipo", "Escolha se a maleta é nova ou uma reposição.");
        }

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT vendedor_id, semana_id, praca_id, tipo FROM corona.remessas WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> r = existente.get(0);
            boolean igual = pedido.vendedorId().equals(r.get("vendedor_id")) && pedido.semanaId().equals(r.get("semana_id"))
                    && pedido.tipo().equals(r.get("tipo"))
                    && (pedido.pracaId() == null || pedido.pracaId().equals(r.get("praca_id")));
            if (!igual) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.REMESSA_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(id, true);
        }

        Boolean semana = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.semanas WHERE id = ? AND deleted_at IS NULL)",
                Boolean.class, pedido.semanaId());
        if (!Boolean.TRUE.equals(semana)) {
            throw Validacoes.recusar("semanaId", "Esta semana não existe mais. Atualize a tela e escolha de novo.");
        }
        UUID praca = pedido.pracaId();
        if (praca != null) {
            Boolean existe = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.pracas WHERE id = ? AND deleted_at IS NULL)",
                    Boolean.class, praca);
            if (!Boolean.TRUE.equals(existe)) {
                throw Validacoes.recusar("pracaId", "Esta praça não existe mais. Atualize a tela e escolha de novo.");
            }
        }
        UUID pracaDoVendedor = conferirVendedor(pedido.vendedorId()).praca();
        jdbc.update("""
                INSERT INTO corona.remessas (id, vendedor_id, semana_id, praca_id, tipo, criada_por)
                VALUES (?, ?, ?, ?, ?, ?)""", id, pedido.vendedorId(), pedido.semanaId(), praca != null ? praca : pracaDoVendedor,
                pedido.tipo(), usuarioId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("vendedor_id", pedido.vendedorId().toString());
        depois.put("semana_id", pedido.semanaId().toString());
        depois.put("tipo", pedido.tipo());
        auditoria.registrar(Ator.usuario(usuarioId), "REMESSA_RASCUNHO_CRIADO", "remessas", id, null, depois);
        return new Resultado(id, false);
    }

    /** Põe a peça no rascunho com esta quantidade (se já estava, troca a quantidade). */
    @Transactional
    public UUID colocarItem(UUID remessaId, UUID produtoId, Integer quantidade, Integer versao, UUID usuarioId) {
        int versaoLida = Validacoes.versaoDoPedido(versao);
        if (quantidade == null || quantidade <= 0) {
            throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
        }
        if (quantidade > LIMITE_DA_QUANTIDADE) {
            throw Validacoes.recusar("quantidade", "A quantidade é grande demais. Confira o número.");
        }
        trancarRascunho(remessaId, versaoLida);
        Peca peca = pecas(List.of(produtoId), Map.of()).get(0);
        if (peca == null) {
            throw Validacoes.recusar("produtoId", "Esta peça não existe mais. Atualize a tela e escolha de novo.");
        }
        if (!peca.ativa()) {
            throw foraDaMaleta(peca.nome());
        }
        int atualizadas = jdbc.update("""
                UPDATE corona.itens_remessa SET qtd_enviada = ? WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL""",
                quantidade, remessaId, produtoId);
        if (atualizadas == 0) {
            Integer linhas = jdbc.queryForObject(
                    "SELECT count(*) FROM corona.itens_remessa WHERE remessa_id = ? AND deleted_at IS NULL", Integer.class, remessaId);
            if (linhas != null && linhas >= LIMITE_DE_ITENS) {
                throw Validacoes.recusar("produtoId", "Use no máximo " + LIMITE_DE_ITENS + " peças diferentes por maleta.");
            }
            jdbc.update("INSERT INTO corona.itens_remessa (id, remessa_id, produto_id, qtd_enviada) VALUES (?, ?, ?, ?)",
                    UuidV7.gerar(), remessaId, produtoId, quantidade);
        }
        recalcular(remessaId);
        return remessaId;
    }

    @Transactional
    public UUID tirarItem(UUID remessaId, UUID produtoId, Integer versao, UUID usuarioId) {
        trancarRascunho(remessaId, Validacoes.versaoDoPedido(versao));
        int tiradas = jdbc.update("""
                UPDATE corona.itens_remessa SET deleted_at = ? WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL""",
                Timestamp.from(relogio.instant()), remessaId, produtoId);
        if (tiradas == 0) {
            throw Validacoes.recusar("produtoId", "Esta peça não está na maleta. Atualize a tela.");
        }
        recalcular(remessaId);
        return remessaId;
    }

    /** Exclusão lógica do rascunho; só rascunho. */
    @Transactional
    public void excluirRascunho(UUID remessaId, Integer versao, UUID usuarioId) {
        trancarRascunho(remessaId, Validacoes.versaoDoPedido(versao));
        jdbc.update("UPDATE corona.itens_remessa SET deleted_at = ? WHERE remessa_id = ? AND deleted_at IS NULL",
                Timestamp.from(relogio.instant()), remessaId);
        jdbc.update("UPDATE corona.remessas SET deleted_at = ? WHERE id = ?", Timestamp.from(relogio.instant()), remessaId);
        auditoria.registrar(Ator.usuario(usuarioId), "REMESSA_RASCUNHO_EXCLUIDO", "remessas", remessaId,
                Map.of("status", "RASCUNHO"), Map.of("excluida", true));
    }

    // --- envio ---

    /**
     * RASCUNHO para ENVIADA. Reenviar uma remessa já enviada devolve o resultado sem baixar o estoque de novo.
     * Tudo ou nada: qualquer recusa desfaz a transação inteira.
     */
    @Transactional
    public Resultado enviar(UUID remessaId, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        List<UUID> vendedores = jdbc.query("SELECT vendedor_id FROM corona.remessas WHERE id = ? AND deleted_at IS NULL",
                (rs, n) -> rs.getObject(1, UUID.class), remessaId);
        if (vendedores.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        UUID vendedorId = vendedores.get(0);
        // Os envios do mesmo vendedor esperam um pelo outro: o limite é conferido com o envio anterior já gravado.
        travar("limite-do-vendedor:" + vendedorId);
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT status FROM corona.remessas WHERE id = ? AND deleted_at IS NULL FOR UPDATE", remessaId);
        if (linhas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        String status = (String) linhas.get(0).get("status");
        if ("ENVIADA".equals(status)) {
            return new Resultado(remessaId, true);
        }
        if (!"RASCUNHO".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.REMESSA_NAO_EDITAVEL);
        }

        VendedorDaMaleta vendedor = conferirVendedor(vendedorId);
        Map<UUID, Integer> quantidades = new LinkedHashMap<>();
        jdbc.query("SELECT produto_id, qtd_enviada FROM corona.itens_remessa WHERE remessa_id = ? AND deleted_at IS NULL "
                + "ORDER BY produto_id", rs -> {
                    quantidades.put(rs.getObject(1, UUID.class), rs.getInt(2));
                }, remessaId);
        if (quantidades.isEmpty()) {
            throw Validacoes.recusar("itens", "Coloque pelo menos uma peça na maleta antes de enviar.");
        }
        List<Peca> pecas = pecas(new ArrayList<>(quantidades.keySet()), quantidades);
        BigDecimal total = BigDecimal.ZERO;
        int totalPecas = 0;
        for (Peca p : pecas) {
            if (p == null || !p.ativa()) {
                throw foraDaMaleta(p == null ? "uma das peças" : p.nome());
            }
            total = total.add(Dinheiro.precoDaLinha(p.preco(), p.quantidade()));
            totalPecas += p.quantidade();
        }
        total = Dinheiro.arredondar(total);

        if (vendedor.limite() != null) {
            BigDecimal emAberto = maleta.valorEmAberto(vendedorId);
            if (emAberto.add(total).compareTo(vendedor.limite()) > 0) {
                BigDecimal falta = emAberto.add(total).subtract(vendedor.limite());
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.LIMITE_DE_CONSIGNACAO_EXCEDIDO,
                        "O valor desta maleta passa o limite do vendedor. O limite é " + reais(vendedor.limite())
                                + ", ele já tem " + reais(emAberto) + " em maletas e esta maleta vale " + reais(total)
                                + ". Faltam " + reais(falta) + " de limite. Tire peças da maleta ou aumente o limite no cadastro.");
            }
        }

        // O serviço de estoque trava os produtos em ordem de id e recusa a remessa inteira se faltar peça.
        List<Pedido> baixas = new ArrayList<>();
        for (Peca p : pecas) {
            baixas.add(new Pedido(UuidV7.gerar(), p.id(), TipoDeMovimento.REMESSA, p.quantidade(), null,
                    OrigemDoMovimento.MALETA, remessaId));
        }
        estoque.registrar(baixas, usuarioId);

        for (Peca p : pecas) {
            jdbc.update("UPDATE corona.itens_remessa SET preco_remessa = ? WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL",
                    p.preco(), remessaId, p.id());
        }
        jdbc.update("""
                UPDATE corona.remessas SET status = 'ENVIADA', total_pecas = ?, total_valor = ?, enviada_em = ?, enviada_por = ?
                 WHERE id = ?""", totalPecas, total, Timestamp.from(relogio.instant()), usuarioId, remessaId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("status", "ENVIADA");
        depois.put("vendedor_id", vendedorId.toString());
        depois.put("total_pecas", totalPecas);
        depois.put("total_valor", total.toPlainString());
        auditoria.registrar(Ator.usuario(usuarioId), "REMESSA_ENVIADA", "remessas", remessaId, Map.of("status", "RASCUNHO"), depois);
        return new Resultado(remessaId, false);
    }

    // --- internos ---

    /** {@code limite} vazio só quando o vendedor está "sem limite" (sem limite definido nem chega aqui). */
    private record VendedorDaMaleta(BigDecimal limite, UUID praca) {
    }

    /** Vendedor existente, ativo e sem restrição que impede montar maleta (na criação do rascunho e no envio). */
    private VendedorDaMaleta conferirVendedor(UUID vendedorId) {
        List<Map<String, Object>> achados = jdbc.queryForList("""
                SELECT v.nome, v.ativo, v.limite_consignacao, v.limite_consignacao_ilimitado, v.praca_id, r.descricao AS restricao,
                       coalesce(r.bloqueia_remessa, false) AS impede
                  FROM corona.vendedores v LEFT JOIN corona.restricoes r ON r.id = v.restricao_id AND r.deleted_at IS NULL
                 WHERE v.id = ? AND v.deleted_at IS NULL""", vendedorId);
        if (achados.isEmpty()) {
            throw Validacoes.recusar("vendedorId", "Este vendedor não existe mais. Atualize a tela e escolha de novo.");
        }
        Map<String, Object> v = achados.get(0);
        if (!Boolean.TRUE.equals(v.get("ativo"))) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.VENDEDOR_NAO_RECEBE_MALETA,
                    "Este vendedor está inativo e não pode receber maleta. Ative o cadastro dele antes de montar a maleta.");
        }
        if (Boolean.TRUE.equals(v.get("impede"))) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.VENDEDOR_NAO_RECEBE_MALETA,
                    "Este vendedor tem uma restrição que impede montar maleta (" + v.get("restricao")
                            + "). Resolva a restrição no cadastro dele antes de montar a maleta.");
        }
        BigDecimal limite = (BigDecimal) v.get("limite_consignacao");
        if (limite == null && !Boolean.TRUE.equals(v.get("limite_consignacao_ilimitado"))) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.LIMITE_DE_CONSIGNACAO_NAO_DEFINIDO,
                    "Defina o limite deste vendedor antes de montar a maleta. No cadastro dele, preencha o limite de "
                            + "consignação ou marque \"Sem limite\".");
        }
        return new VendedorDaMaleta(limite, (UUID) v.get("praca_id"));
    }

    /** Uma {@link Peca} por id pedido, na mesma ordem; {@code null} onde o produto não existe (ou foi excluído). */
    private List<Peca> pecas(List<UUID> ids, Map<UUID, Integer> quantidades) {
        List<Peca> resultado = new ArrayList<>();
        for (UUID id : ids) {
            Optional<Peca> p = jdbc.query("SELECT id, nome, preco_venda, ativo FROM corona.produtos WHERE id = ? AND deleted_at IS NULL",
                    (rs, n) -> new Peca(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4),
                            quantidades.getOrDefault(id, 0)), id).stream().findFirst();
            resultado.add(p.orElse(null));
        }
        return resultado;
    }

    /** Trava a remessa e confere que ainda é rascunho e que a versão lida é a atual. */
    private void trancarRascunho(UUID remessaId, int versaoLida) {
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT status, version FROM corona.remessas WHERE id = ? AND deleted_at IS NULL FOR UPDATE", remessaId);
        if (linhas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        if (!"RASCUNHO".equals(linhas.get(0).get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.REMESSA_NAO_EDITAVEL);
        }
        if (((Number) linhas.get(0).get("version")).intValue() != versaoLida) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
    }

    /** Totais do rascunho a preço de venda de hoje; o UPDATE também sobe a versão. */
    private void recalcular(UUID remessaId) {
        jdbc.update("""
                UPDATE corona.remessas r SET
                       total_pecas = coalesce((SELECT sum(i.qtd_enviada) FROM corona.itens_remessa i
                                                WHERE i.remessa_id = r.id AND i.deleted_at IS NULL), 0),
                       total_valor = coalesce((SELECT sum(i.qtd_enviada * p.preco_venda) FROM corona.itens_remessa i
                                                 JOIN corona.produtos p ON p.id = i.produto_id
                                                WHERE i.remessa_id = r.id AND i.deleted_at IS NULL), 0)
                 WHERE r.id = ?""", remessaId);
    }

    private void travar(String chave) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, chave);
    }

    private static ErroDaApi foraDaMaleta(String nome) {
        return ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.PECA_FORA_DA_MALETA,
                "A peça \"" + nome + "\" está desativada ou foi excluída e não pode ir para a maleta. Tire essa peça da maleta.");
    }

    static String reais(BigDecimal valor) {
        return "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", valor);
    }
}
