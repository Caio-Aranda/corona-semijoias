package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.remessas.ServicoDaMaleta;
import br.com.coronasemijoias.api.remessas.ServicoDaMaleta.LinhaDeDevolucao;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * O Fechamento da maleta (ADR-0016, item 14; ESPECIFICACAO §23.10). Quando a maleta volta, a dona bipa as peças no Corona
 * Gestão. O servidor guarda os bipes, mostra a leitura (enviadas, devolvidas pelos clientes, vendidas, ainda com clientes,
 * esperado na maleta, bipadas e DIFERENÇA) e, ao concluir, devolve ao estoque as peças bipadas (até o que a maleta devia
 * ter) e passa a maleta a {@code FECHADA}. A diferença nunca é corrigida sozinha: fica na leitura para a dona decidir.
 * Comissão, recebido e pendência são da nota, não da maleta: a leitura os divide pelo vendido (VD) de cada maleta dentro da
 * nota, e a última maleta da nota leva a diferença de arredondamento (dúvida D3).
 */
@Service
public class ServicoDoFechamentoDaMaleta {

    public record Leitura(UUID id, UUID produtoId, Integer quantidade) {
    }

    /** Uma peça com cliente: quem, qual nota e quando cobrar. */
    public record PecaComCliente(UUID clienteId, String cliente, UUID notaId, int semana, int sequencia, java.time.LocalDate dataCobranca,
            int quantidade) {
    }

    public record LinhaDaPeca(UUID produtoId, String nome, int enviadas, int devolvidasPelosClientes, int vendidas, int comClientes,
            int esperadoNaMaleta, int bipadas, int diferenca, int devolvidasALoja, List<PecaComCliente> aindaComClientes) {
    }

    /** Os valores da maleta (rateados pelo VD de cada maleta dentro da nota). */
    public record Valores(BigDecimal vd, BigDecimal comissaoDosClientes, BigDecimal recebido, BigDecimal pendenciasAReceber) {
    }

    public record LeituraDoFechamento(UUID remessaId, String situacaoDoFechamento, Instant iniciadoEm, Instant concluidoEm,
            List<LinhaDaPeca> pecas, Valores valores) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final ServicoDaMaleta maleta;
    private final Clock relogio;

    public ServicoDoFechamentoDaMaleta(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, ServicoDaMaleta maleta, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.maleta = maleta;
        this.relogio = relogio;
    }

    /** Começa o fechamento. Só maleta que está com o representante (enviada ou conferida); um em andamento por maleta. */
    @Transactional
    public UUID iniciar(UUID remessaId, UUID usuarioId) {
        String status = trancar(remessaId);
        if ("FECHADA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_JA_FECHADA);
        }
        if (!"ENVIADA".equals(status) && !"CONFERIDA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_PODE_FECHAR);
        }
        if (emAndamento(remessaId) != null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_JA_EM_ANDAMENTO);
        }
        UUID id = UuidV7.gerar();
        jdbc.update("INSERT INTO corona.fechamentos_maleta (id, remessa_id, iniciado_em, iniciado_por) VALUES (?, ?, ?, ?)", id, remessaId,
                Timestamp.from(relogio.instant()), usuarioId);
        auditoria.registrar(Ator.usuario(usuarioId), "FECHAMENTO_DA_MALETA_INICIADO", "fechamentos_maleta", id, null,
                Map.of("remessa_id", remessaId.toString()));
        return id;
    }

    /** Guarda os bipes (idempotentes pelo id). Peça fora da maleta é recusada. */
    @Transactional
    public int registrarLeituras(UUID remessaId, List<Leitura> leituras, UUID usuarioId) {
        if (leituras == null || leituras.isEmpty()) {
            throw Validacoes.recusar("leituras", "Informe pelo menos uma peça lida na volta.");
        }
        trancar(remessaId);
        UUID fechamento = emAndamento(remessaId);
        if (fechamento == null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_NAO_ENCONTRADO);
        }
        int novas = 0;
        Set<UUID> ids = new HashSet<>();
        for (Leitura l : leituras) {
            if (l == null || l.produtoId() == null || l.quantidade() == null || l.quantidade() < 1 || l.quantidade() > 100_000) {
                throw Validacoes.recusar("leituras", "Confira a peça e a quantidade lidas na volta.");
            }
            UUID id = Validacoes.idDoPedido(l.id());
            if (!ids.add(id)) {
                throw Validacoes.recusar("leituras", "A mesma leitura aparece duas vezes.");
            }
            List<Map<String, Object>> ja = jdbc.queryForList(
                    "SELECT fechamento_id, produto_id, quantidade FROM corona.leituras_fechamento_maleta WHERE id = ?", id);
            if (!ja.isEmpty()) {
                Map<String, Object> g = ja.get(0);
                if (!fechamento.equals(g.get("fechamento_id")) || !l.produtoId().equals(g.get("produto_id"))
                        || l.quantidade() != ((Number) g.get("quantidade")).intValue()) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
                }
                continue;
            }
            Boolean daMaleta = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM corona.itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL)",
                    Boolean.class, remessaId, l.produtoId());
            if (!Boolean.TRUE.equals(daMaleta)) {
                throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PECA_FORA_DA_MALETA);
            }
            jdbc.update("INSERT INTO corona.leituras_fechamento_maleta (id, fechamento_id, produto_id, quantidade, lida_em) VALUES (?, ?, ?, ?, ?)",
                    id, fechamento, l.produtoId(), l.quantidade(), Timestamp.from(relogio.instant()));
            novas++;
        }
        return novas;
    }

    /**
     * Conclui: devolve ao estoque as peças bipadas (no máximo o que a maleta devia ter) e fecha a maleta. A diferença
     * (bipadas − esperado) NÃO é corrigida aqui. Concluir de novo uma maleta já fechada recusa.
     */
    @Transactional
    public void concluir(UUID remessaId, UUID usuarioId) {
        String status = trancar(remessaId);
        UUID fechamento = emAndamento(remessaId);
        if (fechamento == null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, "FECHADA".equals(status) ? CodigoErro.MALETA_JA_FECHADA
                    : CodigoErro.FECHAMENTO_NAO_ENCONTRADO);
        }
        List<LinhaDeDevolucao> devolver = new ArrayList<>();
        for (Map<String, Object> b : jdbc.queryForList("""
                SELECT l.produto_id, sum(l.quantidade) AS bipadas FROM corona.leituras_fechamento_maleta l
                 WHERE l.fechamento_id = ? GROUP BY l.produto_id ORDER BY l.produto_id""", fechamento)) {
            UUID produto = (UUID) b.get("produto_id");
            int bipadas = ((Number) b.get("bipadas")).intValue();
            int esperado = esperadoNaMaleta(remessaId, produto);
            int volta = Math.min(bipadas, esperado);
            if (volta > 0) {
                devolver.add(new LinhaDeDevolucao(UuidV7.gerar(), produto, volta));
            }
        }
        if (!devolver.isEmpty()) {
            maleta.devolver(remessaId, devolver, usuarioId);
        }
        jdbc.update("UPDATE corona.remessas SET status = 'FECHADA' WHERE id = ?", remessaId);
        jdbc.update("UPDATE corona.fechamentos_maleta SET situacao = 'CONCLUIDO', concluido_em = ?, concluido_por = ? WHERE id = ?",
                Timestamp.from(relogio.instant()), usuarioId, fechamento);
        auditoria.registrar(Ator.usuario(usuarioId), "FECHAMENTO_DA_MALETA_CONCLUIDO", "fechamentos_maleta", fechamento, null,
                Map.of("remessa_id", remessaId.toString(), "pecas_devolvidas", devolver.size()));
    }

    // --- a leitura (a mesma do relatório) ---

    @Transactional(readOnly = true)
    public LeituraDoFechamento ler(UUID remessaId) {
        Map<String, Object> remessa = remessa(remessaId);
        // O fechamento em andamento, ou o último concluído.
        List<Map<String, Object>> f = jdbc.queryForList("""
                SELECT id, situacao, iniciado_em, concluido_em FROM corona.fechamentos_maleta WHERE remessa_id = ?
                 ORDER BY iniciado_em DESC LIMIT 1""", remessaId);
        if (f.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.FECHAMENTO_NAO_ENCONTRADO);
        }
        UUID fechamento = (UUID) f.get(0).get("id");
        List<LinhaDaPeca> pecas = new ArrayList<>();
        List<Map<String, Object>> linhas = jdbc.queryForList("""
                SELECT i.produto_id, p.nome, i.qtd_enviada,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo = 'CONSIGNADO_RETORNO'), 0) AS retornos,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo = 'CONSIGNADO_SAIDA'), 0) AS saidas,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo <> 'DEVOLUCAO_DONA'), 0) AS sem_devolucao,
                       coalesce((SELECT -sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo = 'DEVOLUCAO_DONA'), 0) AS devolvidas,
                       coalesce((SELECT sum(a.qtd_dx - a.qtd_dv) FROM corona.itens_acerto_nota a
                                  JOIN corona.acertos_nota z ON z.id = a.acerto_id AND z.estornado_em IS NULL
                                 WHERE a.remessa_id = i.remessa_id AND a.produto_id = i.produto_id), 0) AS vendidas,
                       coalesce((SELECT sum(l.quantidade) FROM corona.leituras_fechamento_maleta l
                                  WHERE l.fechamento_id = ? AND l.produto_id = i.produto_id), 0) AS bipadas
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, i.produto_id""", fechamento, remessaId);
        for (Map<String, Object> l : linhas) {
            UUID produto = (UUID) l.get("produto_id");
            int enviadas = ((Number) l.get("qtd_enviada")).intValue();
            int retornos = ((Number) l.get("retornos")).intValue();
            int saidas = ((Number) l.get("saidas")).intValue();
            int vendidas = ((Number) l.get("vendidas")).intValue();
            int comClientes = -(saidas + retornos) - vendidas;
            int esperado = enviadas + ((Number) l.get("sem_devolucao")).intValue();
            int bipadas = ((Number) l.get("bipadas")).intValue();
            pecas.add(new LinhaDaPeca(produto, (String) l.get("nome"), enviadas, retornos, vendidas, comClientes, esperado, bipadas,
                    bipadas - esperado, ((Number) l.get("devolvidas")).intValue(), pecasComClientes(remessaId, produto)));
        }
        return new LeituraDoFechamento(remessaId, (String) f.get(0).get("situacao"), instante(f.get(0).get("iniciado_em")),
                instante(f.get(0).get("concluido_em")), pecas, valores(remessaId));
    }

    private static Instant instante(Object o) {
        return o == null ? null : ((Timestamp) o).toInstant();
    }

    /** Peças ainda com clientes: itens de notas SEM acerto ativo (DX da linha). */
    private List<PecaComCliente> pecasComClientes(UUID remessaId, UUID produtoId) {
        return jdbc.query("""
                SELECT c.id, c.nome, n.id AS nota_id, s.numero, n.sequencia, n.data_cobranca, sum(i.quantidade) AS qtd
                  FROM corona.itens_nota_consignado i
                  JOIN corona.notas_consignado n ON n.id = i.nota_id
                  JOIN corona.semanas_vendedor s ON s.id = n.semana_id
                  JOIN corona.clientes c ON c.id = n.cliente_id
                 WHERE i.remessa_id = ? AND i.produto_id = ?
                   AND NOT EXISTS (SELECT 1 FROM corona.acertos_nota a WHERE a.nota_id = n.id AND a.estornado_em IS NULL)
                 GROUP BY c.id, c.nome, n.id, s.numero, n.sequencia, n.data_cobranca
                HAVING sum(i.quantidade) > 0 ORDER BY n.data_cobranca, c.nome, n.id""",
                (rs, k) -> new PecaComCliente(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                        rs.getInt(5), rs.getDate(6).toLocalDate(), rs.getInt(7)), remessaId, produtoId);
    }

    /**
     * VD, comissão, recebido e pendência da maleta: cada nota acertada é dividida pelo VD de cada maleta dentro dela, e a
     * última maleta (na ordem do id) leva a diferença de arredondamento.
     */
    private Valores valores(UUID remessaId) {
        BigDecimal vd = zero();
        BigDecimal comissao = zero();
        BigDecimal recebido = zero();
        BigDecimal pendencia = zero();
        List<Map<String, Object>> acertos = jdbc.queryForList("""
                SELECT DISTINCT a.id, a.valor_vd, a.comissao, a.bruto,
                       coalesce((SELECT sum(ap.valor) FROM corona.aplicacoes_recibo ap WHERE ap.acerto_id = a.id), 0) AS aplicado
                  FROM corona.acertos_nota a JOIN corona.itens_acerto_nota i ON i.acerto_id = a.id
                 WHERE i.remessa_id = ? AND a.estornado_em IS NULL""", remessaId);
        for (Map<String, Object> a : acertos) {
            UUID acerto = (UUID) a.get("id");
            // O VD de cada maleta dentro do acerto, na ordem do id da maleta.
            Map<UUID, BigDecimal> vdPorMaleta = new LinkedHashMap<>();
            for (Map<String, Object> m : jdbc.queryForList("""
                    SELECT remessa_id, sum((qtd_dx - qtd_dv) * preco_unitario) AS vd FROM corona.itens_acerto_nota
                     WHERE acerto_id = ? GROUP BY remessa_id ORDER BY remessa_id""", acerto)) {
                vdPorMaleta.put((UUID) m.get("remessa_id"), (BigDecimal) m.get("vd"));
            }
            BigDecimal vdDoAcerto = (BigDecimal) a.get("valor_vd");
            BigDecimal aplicado = (BigDecimal) a.get("aplicado");
            BigDecimal saldo = Dinheiro.subtrair((BigDecimal) a.get("bruto"), aplicado);
            vd = vd.add(vdPorMaleta.getOrDefault(remessaId, zero()));
            comissao = comissao.add(rateio((BigDecimal) a.get("comissao"), vdPorMaleta, vdDoAcerto, remessaId));
            recebido = recebido.add(rateio(aplicado, vdPorMaleta, vdDoAcerto, remessaId));
            pendencia = pendencia.add(rateio(saldo, vdPorMaleta, vdDoAcerto, remessaId));
        }
        return new Valores(vd, comissao, recebido, pendencia);
    }

    /** A parte da maleta no total da nota, proporcional ao VD dela; a última maleta fecha a conta. */
    private static BigDecimal rateio(BigDecimal total, Map<UUID, BigDecimal> vdPorMaleta, BigDecimal vdTotal, UUID remessaId) {
        if (total.signum() == 0 || !vdPorMaleta.containsKey(remessaId)) {
            return zero();
        }
        long todo = vdTotal.movePointRight(2).longValueExact();
        List<UUID> ordem = new ArrayList<>(vdPorMaleta.keySet());
        if (todo == 0) {
            // Sem VD para dividir: tudo vai para a primeira maleta da nota.
            return remessaId.equals(ordem.get(0)) ? total : zero();
        }
        BigDecimal dado = zero();
        for (UUID m : ordem) {
            boolean ultima = m.equals(ordem.get(ordem.size() - 1));
            BigDecimal parte;
            if (ultima) {
                parte = Dinheiro.subtrair(total, dado);
            } else {
                parte = Dinheiro.parteProporcional(total, vdPorMaleta.get(m).movePointRight(2).longValueExact(), todo);
            }
            if (m.equals(remessaId)) {
                return parte;
            }
            dado = dado.add(parte);
        }
        return zero();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    // --- internos ---

    private int esperadoNaMaleta(UUID remessaId, UUID produtoId) {
        Integer v = jdbc.queryForObject("""
                SELECT coalesce((SELECT i.qtd_enviada FROM corona.itens_remessa i
                                  WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL), 0)
                       + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m WHERE m.remessa_id = ? AND m.produto_id = ?), 0)""",
                Integer.class, remessaId, produtoId, remessaId, produtoId);
        return v == null ? 0 : v;
    }

    private UUID emAndamento(UUID remessaId) {
        return jdbc.queryForList("SELECT id FROM corona.fechamentos_maleta WHERE remessa_id = ? AND situacao = 'EM_ANDAMENTO'", UUID.class,
                remessaId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> remessa(UUID remessaId) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT status FROM corona.remessas WHERE id = ? AND deleted_at IS NULL", remessaId);
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ROTA_NAO_ENCONTRADA);
        }
        return r.get(0);
    }

    /** Trava a remessa e devolve o status. */
    private String trancar(UUID remessaId) {
        List<String> s = jdbc.queryForList("SELECT status FROM corona.remessas WHERE id = ? AND deleted_at IS NULL FOR UPDATE", String.class,
                remessaId);
        if (s.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ROTA_NAO_ENCONTRADA);
        }
        return s.get(0);
    }
}
