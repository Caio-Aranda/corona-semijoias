package br.com.coronasemijoias.api.estoque;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.Identificadores;
import br.com.coronasemijoias.api.comum.identificadores.OrdemDoBanco;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O ÚNICO lugar do sistema que muda {@code produtos.estoque_atual} (ADR-0008). Grava o movimento e o saldo
 * na MESMA transação, com a linha do produto travada ({@code SELECT ... FOR UPDATE}) em ordem estável de id,
 * recusa saldo negativo, é idempotente pelo UUID do cliente e nunca confia no sinal que vem de fora.
 * Compras, inventário e maleta (tarefas 2.3, 2.4 e Fase 3) chamam este serviço; nenhum outro código faz
 * UPDATE no saldo (há um teste que varre o código para garantir).
 */
@Service
public class ServicoDeEstoque {

    /** Os seis tipos da §7.6. O sinal de cada um é definido aqui, nunca pelo aplicativo. */
    public enum TipoDeMovimento {
        ENTRADA(1), SAIDA(-1), AJUSTE(0), REMESSA(-1), DEVOLUCAO(1), AVARIA(-1);

        private final int sinal;

        TipoDeMovimento(int sinal) {
            this.sinal = sinal;
        }
    }

    public enum OrigemDoMovimento {
        MANUAL, AJUSTE_MANUAL, COMPRA, INVENTARIO, MALETA, ESTORNO
    }

    /**
     * O que se pede ao serviço. {@code quantidade} é sempre positiva, exceto no AJUSTE (a diferença, com sinal,
     * nunca zero). {@code id} vazio vira um UUID novo (quem quer idempotência manda o seu).
     */
    public record Pedido(UUID id, UUID produtoId, TipoDeMovimento tipo, int quantidade, String motivo,
            OrigemDoMovimento origem, UUID origemId) {
    }

    /** Um movimento gravado. {@code quantidade} tem sinal; {@code desfeitoPorId} é o estorno dele, se houver. */
    public record Movimento(UUID id, UUID produtoId, TipoDeMovimento tipo, int quantidade, int saldoDepois,
            OrigemDoMovimento origem, UUID origemId, String motivo, UUID usuarioId, Instant ocorridaEm, UUID estornaId) {
    }

    public record Resultado(Movimento movimento, boolean jaExistia) {
    }

    public record Divergencia(UUID produtoId, String nome, String fotoHash, int saldoGuardado, long somaDosMovimentos) {

        public long diferenca() {
            return saldoGuardado - somaDosMovimentos;
        }
    }

    /**
     * Estoque central mais o saldo das maletas enviadas contra o que entrou e saiu fora da maleta (entradas, saídas
     * manuais, perdas, ajustes e compras, com os estornos). Deve ser igual em todo produto (ADR-0010, item 12).
     */
    public record DiferencaComMaletas(UUID produtoId, String nome, String fotoHash, int estoqueCentral, long saldoNasMaletas,
            long esperado) {

        public long diferenca() {
            return estoqueCentral + saldoNasMaletas - esperado;
        }
    }

    public record Conciliacao(Instant geradaEm, long produtosConferidos, List<Divergencia> divergencias,
            List<DiferencaComMaletas> diferencasComMaletas) {
    }

    private record Bloqueado(UUID id, String nome, int saldo) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    ServicoDeEstoque(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    // --- registrar ---

    @Transactional
    public Resultado registrar(Pedido pedido, UUID usuarioId) {
        return registrar(List.of(pedido), usuarioId).get(0);
    }

    /** Tudo ou nada: se um item for recusado, nenhum é gravado. Vários produtos são travados em ordem de id. */
    @Transactional
    public List<Resultado> registrar(List<Pedido> pedidos, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        if (pedidos == null || pedidos.isEmpty()) {
            throw Validacoes.recusar("itens", "Informe pelo menos uma peça.");
        }
        List<Pedido> conferidos = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (Pedido p : pedidos) {
            Pedido c = conferir(p);
            if (!ids.add(c.id())) {
                throw Validacoes.recusar("id", "O mesmo lançamento aparece mais de uma vez.");
            }
            conferidos.add(c);
        }
        Map<UUID, Bloqueado> produtos = bloquear(conferidos.stream().map(Pedido::produtoId).toList());
        Map<UUID, Movimento> existentes = movimentosPorId(ids);
        Map<UUID, Integer> saldos = new LinkedHashMap<>();
        produtos.values().forEach(b -> saldos.put(b.id(), b.saldo()));

        List<Resultado> resultados = new ArrayList<>();
        Set<UUID> mudados = new TreeSet<>(OrdemDoBanco.UUIDS);
        for (Pedido p : conferidos) {
            Movimento ja = existentes.get(p.id());
            if (ja != null) {
                if (!igual(ja, p)) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
                }
                resultados.add(new Resultado(ja, true));
                continue;
            }
            int delta = p.tipo() == TipoDeMovimento.AJUSTE ? p.quantidade() : p.tipo().sinal * p.quantidade();
            int antes = saldos.get(p.produtoId());
            int depois = conferirSaldo(antes, delta, produtos.get(p.produtoId()));
            Movimento m = inserir(p.id(), p.produtoId(), p.tipo(), delta, depois, p.origem(), p.origemId(), p.motivo(),
                    usuarioId, null);
            saldos.put(p.produtoId(), depois);
            mudados.add(p.produtoId());
            if (p.origem() == OrigemDoMovimento.AJUSTE_MANUAL) {
                auditar("ESTOQUE_AJUSTADO", usuarioId, m, antes);
            }
            resultados.add(new Resultado(m, false));
        }
        for (UUID produto : mudados) {
            definirSaldo(produto, saldos.get(produto));
        }
        return resultados;
    }

    // --- ajuste manual por "nova quantidade" ---

    /** Ajuste da dona: leva o estoque a {@code novaQuantidade} (a diferença é calculada AQUI, com o produto travado). */
    @Transactional
    public Resultado ajustarPara(UUID id, UUID produtoId, int novaQuantidade, String motivo, UUID usuarioId) {
        UUID idDoLancamento = idDoPedido(id);
        Bloqueado produto = bloquear(List.of(produtoId)).get(produtoId);
        String motivoLimpo = motivoDoAjuste(motivo);
        Movimento ja = movimentosPorId(Set.of(idDoLancamento)).get(idDoLancamento);
        if (ja != null) {
            if (ja.tipo() != TipoDeMovimento.AJUSTE || !ja.produtoId().equals(produtoId) || ja.saldoDepois() != novaQuantidade
                    || !Objects.equals(ja.motivo(), motivoLimpo) || ja.origem() != OrigemDoMovimento.AJUSTE_MANUAL) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
            }
            return new Resultado(ja, true);
        }
        if (novaQuantidade < 0) {
            throw Validacoes.recusar("novaQuantidade", "A quantidade não pode ser menor que zero.");
        }
        int diferenca = novaQuantidade - produto.saldo();
        if (diferenca == 0) {
            throw Validacoes.recusar("novaQuantidade", "O estoque já está com essa quantidade. Informe outra.");
        }
        return registrar(new Pedido(idDoLancamento, produtoId, TipoDeMovimento.AJUSTE, diferenca, motivoLimpo,
                OrigemDoMovimento.AJUSTE_MANUAL, null), usuarioId);
    }

    /** Ajuste da dona por diferença (com sinal): precisa de motivo. */
    @Transactional
    public Resultado ajustarPorDiferenca(UUID id, UUID produtoId, int diferenca, String motivo, UUID usuarioId) {
        if (diferenca == 0) {
            throw Validacoes.recusar("diferenca", "Informe quantas peças a mais ou a menos. Não pode ser zero.");
        }
        return registrar(new Pedido(id, produtoId, TipoDeMovimento.AJUSTE, diferenca, motivoDoAjuste(motivo),
                OrigemDoMovimento.AJUSTE_MANUAL, null), usuarioId);
    }

    // --- estorno ---

    /**
     * Desfaz um lançamento: movimento novo, do mesmo tipo, de sinal oposto, ligado ao original. Recusa desfazer
     * duas vezes, desfazer um "desfazer" e o que deixaria o estoque abaixo de zero.
     */
    @Transactional
    public Resultado estornar(UUID id, UUID movimentoId, String motivo, UUID usuarioId) {
        Objects.requireNonNull(usuarioId, "usuarioId");
        UUID idDoEstorno = idDoPedido(id);
        String motivoLimpo = Validacoes.limpar(motivo);
        if (motivoLimpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de desfazer este lançamento.");
        }
        if (motivoLimpo.length() > Pedidos.LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + Pedidos.LIMITE_DO_MOTIVO + " caracteres.");
        }
        Movimento original = movimentosPorId(Set.of(movimentoId)).get(movimentoId);
        if (original == null) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.LANCAMENTO_NAO_ENCONTRADO);
        }
        Bloqueado produto = bloquear(List.of(original.produtoId())).get(original.produtoId());

        Movimento ja = movimentosPorId(Set.of(idDoEstorno)).get(idDoEstorno);
        if (ja != null) {
            if (!movimentoId.equals(ja.estornaId()) || !Objects.equals(ja.motivo(), motivoLimpo)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
            }
            return new Resultado(ja, true);
        }
        if (original.estornaId() != null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_NAO_PODE_SER_DESFEITO);
        }
        // Com o produto travado, ninguém mais consegue desfazer este lançamento ao mesmo tempo.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.movimentacoes_estoque WHERE estorna_id = ?)", Boolean.class, movimentoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_DESFEITO);
        }
        int delta = -original.quantidade();
        int depois = conferirSaldo(produto.saldo(), delta, produto);
        Movimento m = inserir(idDoEstorno, original.produtoId(), original.tipo(), delta, depois, OrigemDoMovimento.ESTORNO,
                movimentoId, motivoLimpo, usuarioId, movimentoId);
        definirSaldo(original.produtoId(), depois);
        auditar("MOVIMENTO_ESTORNADO", usuarioId, m, produto.saldo());
        return new Resultado(m, false);
    }

    // --- conciliação ---

    /** Para cada produto, soma dos movimentos = saldo guardado. A lista de divergências deve vir vazia. */
    @Transactional(readOnly = true)
    public Conciliacao conciliar() {
        List<Divergencia> divergencias = jdbc.query("""
                SELECT p.id, p.nome, p.foto_principal_hash, p.estoque_atual, coalesce(sum(m.quantidade), 0) AS soma
                  FROM corona.produtos p
                  LEFT JOIN corona.movimentacoes_estoque m ON m.produto_id = p.id
                 GROUP BY p.id, p.nome, p.foto_principal_hash, p.estoque_atual
                HAVING p.estoque_atual <> coalesce(sum(m.quantidade), 0)
                 ORDER BY p.nome, p.id""",
                (rs, i) -> new Divergencia(rs.getObject("id", UUID.class), rs.getString("nome"),
                        rs.getString("foto_principal_hash"), rs.getInt("estoque_atual"), rs.getLong("soma")));
        Long total = jdbc.queryForObject("SELECT count(*) FROM corona.produtos", Long.class);
        return new Conciliacao(relogio.instant(), total == null ? 0 : total, divergencias, diferencasComMaletas());
    }

    /**
     * Central + saldo das maletas (ENVIADA em diante, que é o que está com o vendedor) = tudo o que entrou e saiu do estoque
     * fora da maleta, menos o que foi vendido (venda líquida de estorno, Fase 4; ADR-0010, item 12). Os movimentos da maleta
     * (REMESSA, DEVOLUCAO e os estornos deles) não entram no lado direito: eles só trocam peça de lugar entre o central e as
     * maletas. A venda é a única saída definitiva da maleta; a devolução do cliente que volta à maleta (DEVOLUCAO_CLIENTE,
     * tarefa 6.3, e o estorno dela) desfaz parte da venda; na troca (tarefa 6.5), TROCA_SAIDA conta como venda e
     * TROCA_ENTRADA como devolução (e os estornos delas). A "peça para a loja" continua contada como vendida até o acerto
     * recebê-la (Fase 7; dúvida Q5). Num único SELECT, para olhar um retrato só do banco.
     */
    private List<DiferencaComMaletas> diferencasComMaletas() {
        return jdbc.query("""
                SELECT p.id, p.nome, p.foto_principal_hash, p.estoque_atual,
                       coalesce(maletas.saldo, 0) AS nas_maletas,
                       coalesce(fora.soma, 0) + coalesce(vendidas.soma, 0) AS esperado
                  FROM corona.produtos p
                  LEFT JOIN (SELECT me.produto_id, sum(me.quantidade) AS soma
                               FROM corona.movimentacoes_estoque me
                               LEFT JOIN corona.movimentacoes_estoque original ON original.id = me.estorna_id
                              WHERE me.origem_tipo <> 'MALETA' AND coalesce(original.origem_tipo, '') <> 'MALETA'
                              GROUP BY me.produto_id) fora ON fora.produto_id = p.id
                  LEFT JOIN (SELECT i.produto_id, sum(i.qtd_enviada + coalesce(m.soma, 0)) AS saldo
                               FROM corona.remessas r
                               JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL
                               LEFT JOIN (SELECT remessa_id, produto_id, sum(quantidade) AS soma
                                            FROM corona.movimentos_remessa GROUP BY remessa_id, produto_id) m
                                      ON m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id
                              WHERE r.deleted_at IS NULL AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO')
                              GROUP BY i.produto_id) maletas ON maletas.produto_id = p.id
                  LEFT JOIN (SELECT mv.produto_id, sum(mv.quantidade) AS soma
                               FROM corona.movimentos_remessa mv
                              WHERE mv.tipo IN ('VENDA', 'ESTORNO_VENDA', 'DEVOLUCAO_CLIENTE', 'TROCA_SAIDA', 'TROCA_ENTRADA')
                              GROUP BY mv.produto_id) vendidas ON vendidas.produto_id = p.id
                 WHERE p.estoque_atual + coalesce(maletas.saldo, 0) <> coalesce(fora.soma, 0) + coalesce(vendidas.soma, 0)
                 ORDER BY p.nome, p.id""",
                (rs, i) -> new DiferencaComMaletas(rs.getObject("id", UUID.class), rs.getString("nome"),
                        rs.getString("foto_principal_hash"), rs.getInt("estoque_atual"), rs.getLong("nas_maletas"),
                        rs.getLong("esperado")));
    }

    // --- internos ---

    private static UUID idDoPedido(UUID id) {
        return Validacoes.idDoPedido(id);
    }

    private static String motivoDoAjuste(String motivo) {
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo do ajuste. Isso ajuda a entender a mudança depois.");
        }
        return limpo;
    }

    private Pedido conferir(Pedido p) {
        if (p.produtoId() == null || p.tipo() == null || p.origem() == null) {
            throw Validacoes.recusar("produtoId", "Escolha o produto e o tipo do lançamento.");
        }
        if (p.origem() == OrigemDoMovimento.ESTORNO) {
            throw new IllegalArgumentException("Estorno só pelo método estornar.");
        }
        UUID id = p.id() == null ? UuidV7.gerar() : p.id();
        if (!Identificadores.aceito(id)) {
            throw Validacoes.recusar("id", "Este identificador não é válido. Atualize o aplicativo e tente de novo.");
        }
        if (p.tipo() == TipoDeMovimento.AJUSTE) {
            if (p.quantidade() == 0) {
                throw Validacoes.recusar("quantidade", "Informe quantas peças a mais ou a menos. Não pode ser zero.");
            }
        } else if (p.quantidade() <= 0) {
            throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
        }
        String motivo = Validacoes.limpar(p.motivo());
        if (p.tipo() == TipoDeMovimento.AJUSTE && motivo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo do ajuste. Isso ajuda a entender a mudança depois.");
        }
        if (motivo != null && motivo.length() > Pedidos.LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + Pedidos.LIMITE_DO_MOTIVO + " caracteres.");
        }
        return new Pedido(id, p.produtoId(), p.tipo(), p.quantidade(), motivo, p.origem(), p.origemId());
    }

    private static boolean igual(Movimento ja, Pedido p) {
        int delta = p.tipo() == TipoDeMovimento.AJUSTE ? p.quantidade() : p.tipo().sinal * p.quantidade();
        return ja.estornaId() == null && ja.produtoId().equals(p.produtoId()) && ja.tipo() == p.tipo()
                && ja.quantidade() == delta && Objects.equals(ja.motivo(), p.motivo())
                && ja.origem() == p.origem() && Objects.equals(ja.origemId(), p.origemId());
    }

    private static int conferirSaldo(int antes, int delta, Bloqueado produto) {
        long depois = (long) antes + delta;
        if (depois < 0) {
            throw ErroDaApi.estoqueInsuficiente(antes, produto.nome());
        }
        if (depois > Integer.MAX_VALUE) {
            throw Validacoes.recusar("quantidade", "A quantidade é grande demais.");
        }
        return (int) depois;
    }

    /**
     * Trava os produtos UM A UM na ordem do banco ({@code ORDER BY id}; ver {@link OrdemDoBanco}), a mesma dos caminhos
     * que travam peça por peça (estorno da compra, cancelamento da maleta, contagem): sem deadlock entre operações que se
     * cruzam. Excluído = não existe.
     */
    private Map<UUID, Bloqueado> bloquear(List<UUID> produtoIds) {
        Map<UUID, Bloqueado> travados = new LinkedHashMap<>();
        Set<UUID> emOrdem = new TreeSet<>(OrdemDoBanco.UUIDS);
        emOrdem.addAll(produtoIds);
        for (UUID id : emOrdem) {
            Optional<Bloqueado> achado = jdbc.query(
                    "SELECT id, nome, estoque_atual FROM corona.produtos WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                    (rs, i) -> new Bloqueado(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getInt("estoque_atual")),
                    id).stream().findFirst();
            travados.put(id, achado.orElseThrow(ServicoDeCadastro::naoEncontrado));
        }
        return travados;
    }

    private Map<UUID, Movimento> movimentosPorId(Set<UUID> ids) {
        Map<UUID, Movimento> achados = new LinkedHashMap<>();
        for (UUID id : ids) {
            jdbc.query("""
                    SELECT id, produto_id, tipo, quantidade, saldo_depois, origem_tipo, origem_id, motivo, usuario_id,
                           ocorrida_em, estorna_id
                      FROM corona.movimentacoes_estoque WHERE id = ?""",
                    (rs, i) -> new Movimento(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class),
                            TipoDeMovimento.valueOf(rs.getString("tipo")), rs.getInt("quantidade"), rs.getInt("saldo_depois"),
                            OrigemDoMovimento.valueOf(rs.getString("origem_tipo")), rs.getObject("origem_id", UUID.class),
                            rs.getString("motivo"), rs.getObject("usuario_id", UUID.class),
                            rs.getTimestamp("ocorrida_em").toInstant(), rs.getObject("estorna_id", UUID.class)),
                    id).forEach(m -> achados.put(m.id(), m));
        }
        return achados;
    }

    private Movimento inserir(UUID id, UUID produtoId, TipoDeMovimento tipo, int quantidade, int saldoDepois,
            OrigemDoMovimento origem, UUID origemId, String motivo, UUID usuarioId, UUID estornaId) {
        Instant agora = relogio.instant();
        try {
            jdbc.update("""
                    INSERT INTO corona.movimentacoes_estoque
                        (id, produto_id, tipo, quantidade, saldo_depois, origem_tipo, origem_id, motivo, usuario_id, ocorrida_em, estorna_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, produtoId, tipo.name(), quantidade, saldoDepois, origem.name(), origemId, motivo, usuarioId,
                    Timestamp.from(agora), estornaId);
        } catch (DataIntegrityViolationException e) {
            String texto = String.valueOf(e.getMostSpecificCause().getMessage());
            if (texto.contains("estorno_unico")) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_DESFEITO);
            }
            if (texto.contains("movimentacoes_estoque_pkey")) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
            }
            throw e;
        }
        return new Movimento(id, produtoId, tipo, quantidade, saldoDepois, origem, origemId, motivo, usuarioId, agora, estornaId);
    }

    /** O único UPDATE do saldo do sistema. */
    private void definirSaldo(UUID produtoId, int saldo) {
        jdbc.update("UPDATE corona.produtos SET estoque_atual = ? WHERE id = ?", saldo, produtoId);
    }

    private void auditar(String acao, UUID usuarioId, Movimento m, int saldoAntes) {
        Map<String, Object> antes = Map.of("saldo", saldoAntes);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("saldo", m.saldoDepois());
        depois.put("quantidade", m.quantidade());
        depois.put("tipo", m.tipo().name());
        depois.put("produto_id", m.produtoId().toString());
        depois.put("motivo", m.motivo());
        if (m.estornaId() != null) {
            depois.put("estorna_id", m.estornaId().toString());
        }
        auditoria.registrar(Ator.usuario(usuarioId), acao, "movimentacoes_estoque", m.id(), antes, depois);
    }

    /** Limites comuns dos pedidos. */
    static final class Pedidos {
        static final int LIMITE_DO_MOTIVO = 500;

        private Pedidos() {
        }
    }
}
