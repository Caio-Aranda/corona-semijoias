package br.com.coronasemijoias.api.estoque;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.produtos.CodigosDosProdutos;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
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
 * Contagem do estoque, o "inventário" do ADR-0008 (tarefa 2.4). Ao começar, o servidor congela o esperado de cada
 * peça da seleção. As leituras só mexem no "contado". Ao fechar, UMA transação pede ao {@link ServicoDeEstoque} um
 * AJUSTE por peça com diferença ({@code contado - esperado congelado}), na ordem de id; entradas e saídas feitas
 * durante a contagem continuam valendo. Este serviço nunca altera {@code estoque_atual}.
 *
 * <p>Decisões (documentadas em docs/progresso/fase-2.md):
 * <ul>
 * <li>Uma peça não pode estar em duas contagens em andamento. Comparar "seleções" não basta (categoria e fornecedor se
 * cruzam), então a conferência é por peça: se alguma peça da nova seleção já está numa contagem aberta, é recusada.</li>
 * <li>Cada leitura SOMA ao contado (o leitor lê uma peça por vez); a correção é "definir o contado", que SUBSTITUI.
 * Ambas são idempotentes pelo UUID da leitura.</li>
 * <li>Peça sem leitura só vira zero se a dona confirmar ao fechar; senão fica como está, sem ajuste.</li>
 * </ul>
 */
@Service
public class ServicoDeInventario {

    public enum Escopo {
        TODOS, CATEGORIA, FORNECEDOR
    }

    public enum Modo {
        SOMAR, DEFINIR
    }

    public static final String MOTIVO_DO_AJUSTE = "Contagem do estoque";
    private static final int LIMITE_DA_QUANTIDADE = 1_000_000;

    public record Resultado(UUID inventarioId, boolean jaExistia) {
    }

    public record ResultadoDaLeitura(UUID inventarioId, UUID produtoId, boolean jaExistia) {
    }

    private record ItemParaFechar(UUID produtoId, String nome, int esperado, Integer contado) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeEstoque estoque;
    private final CodigosDosProdutos codigos;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    ServicoDeInventario(JdbcTemplate jdbc, ServicoDeEstoque estoque, CodigosDosProdutos codigos, ServicoDeAuditoria auditoria,
            Clock relogio) {
        this.jdbc = jdbc;
        this.estoque = estoque;
        this.codigos = codigos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    // --- começar ---

    @Transactional
    public Resultado iniciar(UUID id, Escopo escopo, UUID escopoId, String observacoes, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        UUID idDaContagem = Validacoes.idDoPedido(id);
        if (escopo == null) {
            throw Validacoes.recusar("escopo", "Escolha o que vai ser contado: todas as peças, uma categoria ou um fornecedor.");
        }
        UUID idDoEscopo = escopoId;
        if (escopo == Escopo.TODOS && idDoEscopo != null) {
            throw Validacoes.recusar("escopoId", "Para contar todas as peças, não escolha categoria nem fornecedor.");
        }
        if (escopo != Escopo.TODOS && idDoEscopo == null) {
            throw Validacoes.recusar("escopoId", escopo == Escopo.CATEGORIA ? "Escolha a categoria." : "Escolha o fornecedor.");
        }
        String obs = Validacoes.limpar(observacoes);
        if (obs != null && obs.length() > 1000) {
            throw Validacoes.recusar("observacoes", "Use no máximo 1000 caracteres nas observações.");
        }
        // Começar contagens é raro: uma por vez, para a conferência de peças em comum ser exata.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('corona.inventarios.iniciar', 0))", rs -> {
        });

        Optional<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT escopo, escopo_id, observacoes FROM corona.inventarios WHERE id = ?", idDaContagem).stream().findFirst();
        if (existente.isPresent()) {
            Map<String, Object> g = existente.get();
            if (!escopo.name().equals(g.get("escopo")) || !Objects.equals(idDoEscopo, g.get("escopo_id"))
                    || !Objects.equals(obs, g.get("observacoes"))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONTAGEM_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(idDaContagem, true);
        }
        if (escopo != Escopo.TODOS) {
            String tabela = escopo == Escopo.CATEGORIA ? "categorias" : "fornecedores";
            Boolean existe = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona." + tabela + " WHERE id = ? AND deleted_at IS NULL)",
                    Boolean.class, idDoEscopo);
            if (!Boolean.TRUE.equals(existe)) {
                throw Validacoes.recusar("escopoId", escopo == Escopo.CATEGORIA
                        ? "Esta categoria não existe mais. Atualize a tela e escolha de novo."
                        : "Este fornecedor não existe mais. Atualize a tela e escolha de novo.");
            }
        }
        String filtro = switch (escopo) {
            case TODOS -> "";
            case CATEGORIA -> " AND categoria_id = ?";
            case FORNECEDOR -> " AND fornecedor_id = ?";
        };
        Object[] args = escopo == Escopo.TODOS ? new Object[0] : new Object[] {idDoEscopo};

        Boolean emUso = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.itens_inventario i JOIN corona.inventarios v ON v.id = i.inventario_id
                                WHERE v.situacao = 'EM_ANDAMENTO'
                                  AND i.produto_id IN (SELECT id FROM corona.produtos WHERE deleted_at IS NULL""" + filtro + "))",
                Boolean.class, args);
        if (Boolean.TRUE.equals(emUso)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONTAGEM_JA_EM_ANDAMENTO);
        }
        // FOR SHARE: espera os lançamentos em curso e segura os novos até o fim desta transação, então o esperado é
        // exatamente o saldo de um instante entre dois lançamentos. Em ordem de id, como o serviço de estoque.
        List<Map<String, Object>> pecas = jdbc.queryForList(
                "SELECT id, estoque_atual FROM corona.produtos WHERE deleted_at IS NULL" + filtro + " ORDER BY id FOR SHARE", args);
        if (pecas.isEmpty()) {
            throw Validacoes.recusar(escopo == Escopo.TODOS ? "escopo" : "escopoId",
                    "Não há peças nesta seleção para contar. Escolha outra.");
        }
        jdbc.update("""
                INSERT INTO corona.inventarios (id, escopo, escopo_id, situacao, iniciado_em, iniciado_por, observacoes)
                VALUES (?, ?, ?, 'EM_ANDAMENTO', ?, ?, ?)""", idDaContagem, escopo.name(), idDoEscopo,
                Timestamp.from(relogio.instant()), usuarioId, obs);
        List<Object[]> linhas = new ArrayList<>();
        for (Map<String, Object> peca : pecas) {
            linhas.add(new Object[] {UuidV7.gerar(), idDaContagem, peca.get("id"), peca.get("estoque_atual")});
        }
        jdbc.batchUpdate("INSERT INTO corona.itens_inventario (id, inventario_id, produto_id, esperado) VALUES (?, ?, ?, ?)", linhas);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("escopo", escopo.name());
        depois.put("escopo_id", idDoEscopo == null ? null : idDoEscopo.toString());
        depois.put("pecas", pecas.size());
        auditoria.registrar(Ator.usuario(usuarioId), "INVENTARIO_INICIADO", "inventarios", idDaContagem, null, depois);
        return new Resultado(idDaContagem, false);
    }

    // --- contar ---

    /** Uma leitura: SOMA a quantidade ao contado (padrão: 1 peça). */
    @Transactional
    public ResultadoDaLeitura ler(UUID inventarioId, UUID leituraId, UUID produtoId, String codigo, Integer quantidade, UUID usuarioId) {
        return aplicar(inventarioId, leituraId, produtoId, codigo, Modo.SOMAR, quantidade == null ? 1 : quantidade, usuarioId);
    }

    /** Correção: o contado passa a ser exatamente este valor (zero é válido). */
    @Transactional
    public ResultadoDaLeitura definir(UUID inventarioId, UUID leituraId, UUID produtoId, String codigo, Integer contado, UUID usuarioId) {
        if (contado == null) {
            throw Validacoes.recusar("contado", "Informe quantas peças você contou.");
        }
        return aplicar(inventarioId, leituraId, produtoId, codigo, Modo.DEFINIR, contado, usuarioId);
    }

    private ResultadoDaLeitura aplicar(UUID inventarioId, UUID leituraId, UUID produtoId, String codigo, Modo modo, int quantidade,
            UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        UUID id = Validacoes.idDoPedido(leituraId);
        // Trava a contagem: leituras e fechamento esperam uns pelos outros, e o mesmo id enviado junto é visto uma vez.
        String situacao = situacaoTravada(inventarioId);
        UUID peca = resolverPeca(produtoId, codigo);

        Optional<Map<String, Object>> ja = jdbc.queryForList(
                "SELECT inventario_id, produto_id, modo, quantidade FROM corona.leituras_inventario WHERE id = ?", id).stream().findFirst();
        if (ja.isPresent()) {
            Map<String, Object> g = ja.get();
            if (!inventarioId.equals(g.get("inventario_id")) || !peca.equals(g.get("produto_id")) || !modo.name().equals(g.get("modo"))
                    || quantidade != ((Number) g.get("quantidade")).intValue()) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LEITURA_JA_REGISTRADA_DIFERENTE);
            }
            return new ResultadoDaLeitura(inventarioId, peca, true);
        }
        if (!"EM_ANDAMENTO".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONTAGEM_ENCERRADA);
        }
        if (modo == Modo.SOMAR ? quantidade <= 0 : quantidade < 0) {
            throw Validacoes.recusar("quantidade", modo == Modo.SOMAR ? "Informe uma quantidade maior que zero."
                    : "A quantidade contada não pode ser menor que zero.");
        }
        if (quantidade > LIMITE_DA_QUANTIDADE) {
            throw Validacoes.recusar("quantidade", "A quantidade é grande demais. Confira o número.");
        }
        List<Integer> atual = jdbc.query(
                "SELECT coalesce(contado, 0) FROM corona.itens_inventario WHERE inventario_id = ? AND produto_id = ?",
                (rs, n) -> rs.getInt(1), inventarioId, peca);
        if (atual.isEmpty()) {
            throw Validacoes.recusar(codigo != null ? "codigo" : "produtoId", "Esta peça não faz parte desta contagem.");
        }
        long novo = modo == Modo.SOMAR ? (long) atual.get(0) + quantidade : quantidade;
        if (novo > LIMITE_DA_QUANTIDADE) {
            throw Validacoes.recusar("quantidade", "A quantidade contada é grande demais. Confira o número.");
        }
        jdbc.update("""
                INSERT INTO corona.leituras_inventario (id, inventario_id, produto_id, modo, quantidade, usuario_id, lida_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, inventarioId, peca, modo.name(), quantidade, usuarioId,
                Timestamp.from(relogio.instant()));
        jdbc.update("UPDATE corona.itens_inventario SET contado = ? WHERE inventario_id = ? AND produto_id = ?", (int) novo,
                inventarioId, peca);
        return new ResultadoDaLeitura(inventarioId, peca, false);
    }

    private UUID resolverPeca(UUID produtoId, String digitado) {
        String codigo = Validacoes.limpar(digitado);
        if ((produtoId == null) == (codigo == null)) {
            throw Validacoes.recusar(produtoId == null ? "codigo" : "produtoId",
                    "Informe a peça pelo código ou escolhendo o produto, só um dos dois.");
        }
        if (produtoId != null) {
            return produtoId;
        }
        return codigos.produtoDoCodigo(codigo).orElseThrow(() -> Validacoes.recusar("codigo",
                "Não encontramos nenhuma peça com o código " + codigo + ". Confira o código ou cadastre a peça."));
    }

    // --- fechar ---

    /**
     * Fecha a contagem: um AJUSTE por peça com diferença, tudo numa transação. Fechar de novo devolve o já fechado.
     * Se algum ajuste deixaria a peça abaixo de zero, nada é gravado e a contagem continua aberta.
     */
    @Transactional
    public Resultado fechar(UUID inventarioId, boolean zerarNaoLidos, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        String situacao = situacaoTravada(inventarioId);
        if ("FECHADO".equals(situacao)) {
            return new Resultado(inventarioId, true);
        }
        if ("CANCELADO".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONTAGEM_ENCERRADA);
        }
        List<ItemParaFechar> itens = jdbc.query("""
                SELECT i.produto_id, p.nome, i.esperado, i.contado FROM corona.itens_inventario i
                  JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.inventario_id = ? ORDER BY i.produto_id""",
                (rs, n) -> new ItemParaFechar(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        (Integer) rs.getObject(4)), inventarioId);

        List<Pedido> ajustes = new ArrayList<>();
        Map<UUID, ItemParaFechar> porProduto = new LinkedHashMap<>();
        Map<UUID, UUID> movimentoDoProduto = new LinkedHashMap<>();
        Map<UUID, Integer> diferenca = new LinkedHashMap<>();
        for (ItemParaFechar item : itens) {
            Integer efetivo = item.contado() != null ? item.contado() : (zerarNaoLidos ? Integer.valueOf(0) : null);
            if (efetivo == null) {
                continue;
            }
            if (item.contado() == null) {
                jdbc.update("UPDATE corona.itens_inventario SET contado = 0 WHERE inventario_id = ? AND produto_id = ?",
                        inventarioId, item.produtoId());
            }
            int dif = efetivo - item.esperado();
            if (dif == 0) {
                continue;
            }
            UUID movimento = UuidV7.gerar();
            ajustes.add(new Pedido(movimento, item.produtoId(), TipoDeMovimento.AJUSTE, dif, MOTIVO_DO_AJUSTE,
                    OrigemDoMovimento.INVENTARIO, inventarioId));
            porProduto.put(item.produtoId(), item);
            movimentoDoProduto.put(item.produtoId(), movimento);
            diferenca.put(item.produtoId(), dif);
        }
        if (!ajustes.isEmpty()) {
            try {
                estoque.registrar(ajustes, usuarioId);
            } catch (ErroDaApi e) {
                if (e.codigo() == CodigoErro.ESTOQUE_INSUFICIENTE) {
                    throw explicar(porProduto, diferenca, e);
                }
                throw e;
            }
            for (Map.Entry<UUID, UUID> m : movimentoDoProduto.entrySet()) {
                jdbc.update("UPDATE corona.itens_inventario SET movimento_id = ? WHERE inventario_id = ? AND produto_id = ?",
                        m.getValue(), inventarioId, m.getKey());
            }
        }
        jdbc.update("""
                UPDATE corona.inventarios SET situacao = 'FECHADO', fechado_em = ?, fechado_por = ?, zerou_nao_lidos = ?
                 WHERE id = ?""", Timestamp.from(relogio.instant()), usuarioId, zerarNaoLidos, inventarioId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", "FECHADO");
        depois.put("ajustes", ajustes.size());
        depois.put("zerou_nao_lidos", zerarNaoLidos);
        auditoria.registrar(Ator.usuario(usuarioId), "INVENTARIO_FECHADO", "inventarios", inventarioId,
                Map.of("situacao", "EM_ANDAMENTO"), depois);
        return new Resultado(inventarioId, false);
    }

    /** Diz qual peça impediu o fechamento (a primeira, em ordem de id, cujo ajuste passaria de zero). */
    private ErroDaApi explicar(Map<UUID, ItemParaFechar> porProduto, Map<UUID, Integer> diferenca, ErroDaApi original) {
        for (Map.Entry<UUID, ItemParaFechar> e : porProduto.entrySet()) {
            int ha = jdbc.queryForObject("SELECT estoque_atual FROM corona.produtos WHERE id = ?", Integer.class, e.getKey());
            int dif = diferenca.get(e.getKey());
            if (ha + dif < 0) {
                return ErroDaApi.contagemNaoPodeFechar(e.getValue().nome(), ha, dif);
            }
        }
        return original;
    }

    // --- cancelar ---

    @Transactional
    public Resultado cancelar(UUID inventarioId, String motivo, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        String situacao = situacaoTravada(inventarioId);
        if ("CANCELADO".equals(situacao)) {
            return new Resultado(inventarioId, true);
        }
        if ("FECHADO".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONTAGEM_ENCERRADA);
        }
        String motivoLimpo = Validacoes.limpar(motivo);
        if (motivoLimpo != null && motivoLimpo.length() > 500) {
            throw Validacoes.recusar("motivo", "Use no máximo 500 caracteres.");
        }
        jdbc.update("""
                UPDATE corona.inventarios SET situacao = 'CANCELADO', cancelado_em = ?, cancelado_por = ?, motivo_cancelamento = ?
                 WHERE id = ?""", Timestamp.from(relogio.instant()), usuarioId, motivoLimpo, inventarioId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", "CANCELADO");
        depois.put("motivo", motivoLimpo);
        auditoria.registrar(Ator.usuario(usuarioId), "INVENTARIO_CANCELADO", "inventarios", inventarioId,
                Map.of("situacao", "EM_ANDAMENTO"), depois);
        return new Resultado(inventarioId, false);
    }

    /** Trava a linha da contagem e devolve a situação (404 se não existe). */
    private String situacaoTravada(UUID inventarioId) {
        return jdbc.query("SELECT situacao FROM corona.inventarios WHERE id = ? FOR UPDATE", (rs, n) -> rs.getString(1), inventarioId)
                .stream().findFirst().orElseThrow(() -> new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CONTAGEM_NAO_ENCONTRADA));
    }
}
