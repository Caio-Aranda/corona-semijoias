package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As pendências do acerto depois do fechamento (tarefa 7.5; ADR-0012, item 6): a dona resolve, com motivo, uma pendência ainda
 * aberta, assumindo pela loja ou marcando a falta como encontrada (a peça volta ao estoque central, origem ACERTO). Cobrar uma
 * pendência aberta acontece no próximo fechamento. Trava: a pendência, depois os produtos.
 */
@Service
public class ServicoDePendencias {

    private static final Set<String> ACOES = Set.of("LOJA_ASSUME", "ENCONTRADA");

    public record PedidoDeResolucao(UUID id, String acao, String motivo) {
    }

    public record Pendencia(UUID id, UUID acertoId, UUID vendedorId, String vendedorNome, String tipo, UUID produtoId, String produtoNome,
            Integer quantidade, BigDecimal valor, String status, UUID cobradaNoAcertoId, Instant resolvidaEm, String motivo) {
    }

    public record Resultado(UUID id, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeEstoque estoque;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    ServicoDePendencias(JdbcTemplate jdbc, ServicoDeEstoque estoque, ServicoDeAuditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.estoque = estoque;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public List<Pendencia> listar(UUID vendedorId, String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder("p.deleted_at IS NULL");
        if (vendedorId != null) {
            onde.append(" AND p.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (status != null && !status.isBlank()) {
            if (!Set.of("ABERTA", "COBRADA", "ASSUMIDA_PELA_LOJA", "ENCONTRADA", "APROVADA", "RECUSADA").contains(status)) {
                throw Validacoes.recusar("status", "Escolha uma situação da lista.");
            }
            onde.append(" AND p.status = ?");
            args.add(status);
        }
        return jdbc.query("""
                SELECT p.id, p.acerto_id, p.vendedor_id, v.nome AS vendedor, p.tipo, p.produto_id, pr.nome AS produto, p.quantidade,
                       p.valor, p.status, p.cobrada_no_acerto_id, p.resolvida_em, p.motivo
                  FROM corona.pendencias_acerto p JOIN corona.vendedores v ON v.id = p.vendedor_id
                  LEFT JOIN corona.produtos pr ON pr.id = p.produto_id
                 WHERE\s""" + onde + " ORDER BY p.created_at DESC, p.id DESC LIMIT 500",
                (rs, n) -> new Pendencia(rs.getObject("id", UUID.class), rs.getObject("acerto_id", UUID.class),
                        rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getString("tipo"),
                        rs.getObject("produto_id", UUID.class), rs.getString("produto"), (Integer) rs.getObject("quantidade"),
                        rs.getBigDecimal("valor"), rs.getString("status"), rs.getObject("cobrada_no_acerto_id", UUID.class),
                        rs.getTimestamp("resolvida_em") == null ? null : rs.getTimestamp("resolvida_em").toInstant(),
                        rs.getString("motivo")), args.toArray());
    }

    @Transactional(readOnly = true)
    public Pendencia ver(UUID id) {
        return jdbc.query("""
                SELECT p.id, p.acerto_id, p.vendedor_id, v.nome AS vendedor, p.tipo, p.produto_id, pr.nome AS produto, p.quantidade,
                       p.valor, p.status, p.cobrada_no_acerto_id, p.resolvida_em, p.motivo
                  FROM corona.pendencias_acerto p JOIN corona.vendedores v ON v.id = p.vendedor_id
                  LEFT JOIN corona.produtos pr ON pr.id = p.produto_id WHERE p.id = ?""",
                (rs, n) -> new Pendencia(rs.getObject("id", UUID.class), rs.getObject("acerto_id", UUID.class),
                        rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor"), rs.getString("tipo"),
                        rs.getObject("produto_id", UUID.class), rs.getString("produto"), (Integer) rs.getObject("quantidade"),
                        rs.getBigDecimal("valor"), rs.getString("status"), rs.getObject("cobrada_no_acerto_id", UUID.class),
                        rs.getTimestamp("resolvida_em") == null ? null : rs.getTimestamp("resolvida_em").toInstant(),
                        rs.getString("motivo")), id).stream().findFirst()
                .orElseThrow(() -> new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.PENDENCIA_NAO_ENCONTRADA));
    }

    @Transactional
    public Resultado resolver(UUID usuarioId, UUID pendenciaId, PedidoDeResolucao pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("resolucao", "Informe o que fazer com a pendência.");
        }
        UUID comando = Validacoes.idDoPedido(pedido.id());
        if (pedido.acao() == null || !ACOES.contains(pedido.acao())) {
            throw Validacoes.recusar("acao", "Escolha se a loja assume ou se a peça foi encontrada.");
        }
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo.");
        }
        if (motivo.length() > ServicoDeFechamento.LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + ServicoDeFechamento.LIMITE_DO_MOTIVO + " caracteres.");
        }
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT tipo, status, produto_id, quantidade, resolucao_id, motivo FROM corona.pendencias_acerto
                 WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", pendenciaId);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.PENDENCIA_NAO_ENCONTRADA);
        }
        Map<String, Object> p = achado.get(0);
        String status = (String) p.get("status");
        String esperado = "LOJA_ASSUME".equals(pedido.acao()) ? "ASSUMIDA_PELA_LOJA" : "ENCONTRADA";
        if (comando.equals(p.get("resolucao_id"))) {
            if (esperado.equals(status) && motivo.equals(p.get("motivo"))) {
                return new Resultado(pendenciaId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PENDENCIA_RESOLUCAO_DIFERENTE);
        }
        if (!"ABERTA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PENDENCIA_JA_RESOLVIDA);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.pendencias_acerto WHERE resolucao_id = ?)",
                Boolean.class, comando))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PENDENCIA_RESOLUCAO_DIFERENTE);
        }
        if ("ENCONTRADA".equals(pedido.acao())) {
            if (!"FALTA".equals(p.get("tipo"))) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.PENDENCIA_JA_RESOLVIDA,
                        "Só uma peça que faltou pode ser marcada como encontrada.");
            }
            estoque.registrar(new Pedido(UuidV7.gerar(), (UUID) p.get("produto_id"), TipoDeMovimento.DEVOLUCAO,
                    ((Number) p.get("quantidade")).intValue(), null, OrigemDoMovimento.ACERTO, pendenciaId), usuarioId);
        }
        jdbc.update("""
                UPDATE corona.pendencias_acerto SET status = ?, resolucao_id = ?, resolvida_em = ?, resolvida_por = ?, motivo = ?
                 WHERE id = ?""", esperado, comando, Timestamp.from(relogio.instant()), usuarioId, motivo, pendenciaId);
        auditoria.registrar(Ator.usuario(usuarioId), "PENDENCIA_DECIDIDA", "pendencias_acerto", pendenciaId, Map.of("status", "ABERTA"),
                Map.of("status", esperado, "motivo", motivo));
        return new Resultado(pendenciaId, false);
    }
}
