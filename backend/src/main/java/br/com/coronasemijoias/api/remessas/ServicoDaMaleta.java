package br.com.coronasemijoias.api.remessas;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.OrdemDoBanco;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
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
 * Movimentos da maleta já enviada (tarefa 3.3; ADR-0010, itens 4 e 5): devolução à dona, cancelamento (estorno do
 * envio) e estorno de devolução. Toda operação trava a linha da remessa primeiro e os produtos depois (em ordem de id,
 * pelo {@link ServicoDeEstoque}); assim duas operações na mesma maleta esperam uma pela outra e o saldo da maleta
 * (qtd_enviada mais os movimentos) nunca fica negativo. O movimento da maleta e o do estoque central saem na MESMA
 * transação e com o MESMO id (o do aplicativo), o que torna o reenvio idempotente nos dois lados.
 */
@Service
public class ServicoDaMaleta {

    private static final int LIMITE_DA_QUANTIDADE = 100_000;
    private static final int LIMITE_DE_LINHAS = 500;
    private static final int LIMITE_DO_MOTIVO = 400;

    public record LinhaDeDevolucao(UUID id, UUID produtoId, Integer quantidade) {
    }

    /** {@code jaExistia}: tudo o que veio já estava gravado (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID remessaId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeEstoque estoque;
    private final ServicoDeAuditoria auditoria;

    ServicoDaMaleta(JdbcTemplate jdbc, ServicoDeEstoque estoque, ServicoDeAuditoria auditoria) {
        this.jdbc = jdbc;
        this.estoque = estoque;
        this.auditoria = auditoria;
    }

    // --- devolução à dona ---

    @Transactional
    public Resultado devolver(UUID remessaId, List<LinhaDeDevolucao> pedidas, UUID usuarioId) {
        return devolver(remessaId, pedidas, usuarioId, false);
    }

    /**
     * A peça que a cliente devolveu depois de a maleta ser fechada e que a dona leu (§23.10, item 3): a mesma devolução à dona,
     * aceita também na maleta FECHADA (que continua fechada).
     */
    @Transactional
    public Resultado devolverDaMaletaFechada(UUID remessaId, List<LinhaDeDevolucao> pedidas, UUID usuarioId) {
        return devolver(remessaId, pedidas, usuarioId, true);
    }

    private Resultado devolver(UUID remessaId, List<LinhaDeDevolucao> pedidas, UUID usuarioId, boolean aceitaFechada) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw Validacoes.recusar("itens", "Informe pelo menos uma peça para devolver.");
        }
        if (pedidas.size() > LIMITE_DE_LINHAS) {
            throw Validacoes.recusar("itens", "Devolva no máximo " + LIMITE_DE_LINHAS + " peças diferentes por vez.");
        }
        List<LinhaDeDevolucao> linhas = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        Set<UUID> produtos = new HashSet<>();
        for (LinhaDeDevolucao l : pedidas) {
            if (l == null || l.produtoId() == null) {
                throw Validacoes.recusar("produtoId", "Escolha a peça que está voltando.");
            }
            if (l.quantidade() == null || l.quantidade() <= 0) {
                throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
            }
            if (l.quantidade() > LIMITE_DA_QUANTIDADE) {
                throw Validacoes.recusar("quantidade", "A quantidade é grande demais. Confira o número.");
            }
            UUID id = Validacoes.idDoPedido(l.id());
            if (!ids.add(id) || !produtos.add(l.produtoId())) {
                throw Validacoes.recusar("itens", "A mesma peça aparece mais de uma vez. Junte as quantidades numa linha só.");
            }
            linhas.add(new LinhaDeDevolucao(id, l.produtoId(), l.quantidade()));
        }
        linhas.sort(Comparator.comparing(LinhaDeDevolucao::produtoId, OrdemDoBanco.UUIDS));

        String status = trancar(remessaId);
        List<LinhaDeDevolucao> novas = new ArrayList<>();
        for (LinhaDeDevolucao l : linhas) {
            List<Map<String, Object>> ja = jdbc.queryForList(
                    "SELECT remessa_id, produto_id, tipo, quantidade, estorna_id FROM corona.movimentos_remessa WHERE id = ?", l.id());
            if (ja.isEmpty()) {
                novas.add(l);
                continue;
            }
            Map<String, Object> m = ja.get(0);
            boolean igual = remessaId.equals(m.get("remessa_id")) && l.produtoId().equals(m.get("produto_id"))
                    && "DEVOLUCAO_DONA".equals(m.get("tipo")) && m.get("estorna_id") == null
                    && ((Number) m.get("quantidade")).intValue() == -l.quantidade();
            if (!igual) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
            }
        }
        if (novas.isEmpty()) {
            return new Resultado(remessaId, true);
        }
        if (!"ENVIADA".equals(status) && !"CONFERIDA".equals(status) && !(aceitaFechada && "FECHADA".equals(status))) {
            throw naoDevolve(status);
        }

        List<Pedido> volta = new ArrayList<>();
        Map<String, Object> depois = new LinkedHashMap<>();
        for (LinhaDeDevolucao l : novas) {
            Saldo s = saldo(remessaId, l.produtoId());
            if (s == null) {
                throw Validacoes.recusar("produtoId", "Esta peça não está nesta maleta. Atualize a tela e confira.");
            }
            if (l.quantidade() > s.saldo()) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_MAIOR_QUE_O_SALDO,
                        "Na maleta há só " + s.saldo() + " peça(s) de \"" + s.nome() + "\" e você informou " + l.quantidade()
                                + ". Confira a quantidade devolvida.");
            }
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade)
                    VALUES (?, ?, ?, 'DEVOLUCAO_DONA', ?)""", l.id(), remessaId, l.produtoId(), -l.quantidade());
            volta.add(new Pedido(l.id(), l.produtoId(), TipoDeMovimento.DEVOLUCAO, l.quantidade(), null,
                    OrigemDoMovimento.MALETA, remessaId));
            depois.put(l.produtoId().toString(), l.quantidade());
        }
        // Tudo ou nada: se o estoque recusar, a transação inteira (inclusive os movimentos da maleta) é desfeita.
        estoque.registrar(volta, usuarioId);
        auditoria.registrar(Ator.usuario(usuarioId), "MALETA_DEVOLUCAO_REGISTRADA", "remessas", remessaId, null, depois);
        return new Resultado(remessaId, false);
    }

    // --- cancelamento ---

    /** RASCUNHO ou ENVIADA sem movimento para CANCELADA. Cancelar de novo não faz nada (200). */
    @Transactional
    public void cancelar(UUID remessaId, String motivo, UUID usuarioId) {
        String limpo = motivoObrigatorio(motivo, "Diga o motivo de cancelar a maleta.");
        String status = trancar(remessaId);
        if ("CANCELADA".equals(status)) {
            return;
        }
        if ("ENVIADA".equals(status)) {
            Integer ativos = jdbc.queryForObject("""
                    SELECT count(*) FROM corona.movimentos_remessa m
                     WHERE m.remessa_id = ? AND m.estorna_id IS NULL
                       AND NOT EXISTS (SELECT 1 FROM corona.movimentos_remessa e WHERE e.estorna_id = m.id)""", Integer.class, remessaId);
            if (ativos != null && ativos > 0) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_JA_MOVIMENTADA);
            }
            // Estorna a baixa do envio (só soma ao estoque central, então nunca falta saldo), em ordem estável de produto.
            List<UUID> baixas = jdbc.query("""
                    SELECT e.id FROM corona.movimentacoes_estoque e
                     WHERE e.origem_tipo = 'MALETA' AND e.origem_id = ? AND e.tipo = 'REMESSA' AND e.estorna_id IS NULL
                       AND NOT EXISTS (SELECT 1 FROM corona.movimentacoes_estoque x WHERE x.estorna_id = e.id)
                     ORDER BY e.produto_id, e.id""", (rs, n) -> rs.getObject(1, UUID.class), remessaId);
            for (UUID baixa : baixas) {
                estoque.estornar(UuidV7.gerar(), baixa, "Maleta cancelada: " + limpo, usuarioId);
            }
        } else if ("EM_ACERTO".equals(status)) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_ACERTO,
                    "Esta maleta está em acerto e não pode ser cancelada. Termine ou desfaça o acerto antes.");
        } else if (!"RASCUNHO".equals(status)) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.REMESSA_NAO_EDITAVEL,
                    "Esta maleta já foi conferida ou acertada e não pode ser cancelada.");
        }
        jdbc.update("UPDATE corona.remessas SET status = 'CANCELADA', motivo_cancelamento = ? WHERE id = ?", limpo, remessaId);
        auditoria.registrar(Ator.usuario(usuarioId), "REMESSA_CANCELADA", "remessas", remessaId, Map.of("status", status),
                Map.of("status", "CANCELADA", "motivo", limpo));
    }

    // --- estorno de uma devolução ---

    /**
     * Desfaz uma devolução: movimento novo de sinal oposto, ligado ao original, e o estoque central volta a baixar
     * (recusado, com texto simples, se o central não tiver as peças).
     */
    @Transactional
    public Resultado desfazerDevolucao(UUID remessaId, UUID devolucaoId, UUID idDoEstorno, String motivo, UUID usuarioId) {
        UUID estornoId = Validacoes.idDoPedido(idDoEstorno);
        String limpo = motivoObrigatorio(motivo, "Diga o motivo de desfazer esta devolução.");
        String textoDoEstoque = "Devolução desfeita: " + limpo;
        String status = trancar(remessaId);

        List<Map<String, Object>> achado = jdbc.queryForList(
                "SELECT remessa_id, produto_id, tipo, quantidade, estorna_id FROM corona.movimentos_remessa WHERE id = ?", devolucaoId);
        if (achado.isEmpty() || !remessaId.equals(achado.get(0).get("remessa_id"))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.LANCAMENTO_NAO_ENCONTRADO);
        }
        Map<String, Object> original = achado.get(0);

        List<Map<String, Object>> jaFeito = jdbc.queryForList("""
                SELECT m.remessa_id, m.estorna_id, e.motivo FROM corona.movimentos_remessa m
                  LEFT JOIN corona.movimentacoes_estoque e ON e.id = m.id WHERE m.id = ?""", estornoId);
        if (!jaFeito.isEmpty()) {
            Map<String, Object> j = jaFeito.get(0);
            if (!remessaId.equals(j.get("remessa_id")) || !devolucaoId.equals(j.get("estorna_id"))
                    || !textoDoEstoque.equals(j.get("motivo"))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
            }
            return new Resultado(remessaId, true);
        }
        if (!"DEVOLUCAO_DONA".equals(original.get("tipo")) || original.get("estorna_id") != null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_NAO_PODE_SER_DESFEITO);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.movimentos_remessa WHERE estorna_id = ?)",
                Boolean.class, devolucaoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_DESFEITO);
        }
        if (!"ENVIADA".equals(status) && !"CONFERIDA".equals(status)) {
            throw naoDevolve(status);
        }

        UUID produtoId = (UUID) original.get("produto_id");
        int quantidade = -((Number) original.get("quantidade")).intValue();
        try {
            // O movimento do estoque central tem o mesmo id da devolução; desfazer baixa o central de novo.
            estoque.estornar(estornoId, devolucaoId, textoDoEstoque, usuarioId);
        } catch (ErroDaApi e) {
            if (e.codigo() == CodigoErro.ESTOQUE_INSUFICIENTE) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_NAO_PODE_SER_DESFEITA,
                        "Não é possível desfazer esta devolução: as peças que voltaram já saíram do estoque da loja. "
                                + "Dê entrada nessas peças no estoque e tente de novo.");
            }
            throw e;
        }
        jdbc.update("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, estorna_id)
                VALUES (?, ?, ?, 'DEVOLUCAO_DONA', ?, ?)""", estornoId, remessaId, produtoId, quantidade, devolucaoId);
        auditoria.registrar(Ator.usuario(usuarioId), "MALETA_DEVOLUCAO_DESFEITA", "remessas", remessaId, null,
                Map.of("devolucao_id", devolucaoId.toString(), "produto_id", produtoId.toString(), "quantidade", quantidade,
                        "motivo", limpo));
        return new Resultado(remessaId, false);
    }

    // --- internos ---

    private record Saldo(String nome, int saldo) {
    }

    /** Trava a linha da remessa (toda operação de movimento da maleta começa aqui) e devolve o status. */
    private String trancar(UUID remessaId) {
        List<String> status = jdbc.query("SELECT status FROM corona.remessas WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                (rs, n) -> rs.getString(1), remessaId);
        if (status.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        return status.get(0);
    }

    /** Saldo atual da peça na maleta: qtd_enviada mais a soma dos movimentos (nunca um número guardado). Vazio se a peça não está. */
    private Saldo saldo(UUID remessaId, UUID produtoId) {
        return jdbc.query("""
                SELECT p.nome, i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) AS saldo
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                (rs, n) -> new Saldo(rs.getString(1), rs.getInt(2)), remessaId, produtoId).stream().findFirst().orElse(null);
    }

    private static String motivoObrigatorio(String motivo, String mensagem) {
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", mensagem);
        }
        if (limpo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        return limpo;
    }

    private static ErroDaApi naoDevolve(String status) {
        if ("EM_ACERTO".equals(status)) {
            return ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_ACERTO,
                    "Esta maleta está em acerto. Termine o acerto antes de devolver peças.");
        }
        String situacao = switch (status) {
            case "RASCUNHO" -> "ainda está sendo montada";
            case "CANCELADA" -> "foi cancelada";
            default -> "já foi acertada";
        };
        return ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.REMESSA_NAO_EDITAVEL,
                "Só dá para devolver peças de uma maleta enviada, e esta maleta " + situacao + ".");
    }
}
