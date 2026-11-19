package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A semana do representante (ADR-0016, item 2; ESPECIFICACAO §23.2). Nova semana é uma operação do computador
 * ({@code SEMANA_NOVA}): grava a nova {@code ABERTA} e fecha a anterior na MESMA transação. Uma semana aberta por
 * representante; o número é sequencial por representante e nasce no computador. Não confundir com {@code corona.semanas}
 * (a semana da dona, §7.4).
 */
@Service
public class ServicoDeSemanasDoVendedor {

    /** O que o computador manda: o id e o número (que ele gerou) e a hora em que abriu. */
    public record SemanaNova(UUID id, Integer numero, Instant abertaEm) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;

    public ServicoDeSemanasDoVendedor(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
    }

    /** @return {@code true} se a semana já existia com os mesmos dados (reenvio inofensivo) */
    @Transactional
    public boolean abrir(ContextoDoConsignado ctx, SemanaNova pedido) {
        if (pedido == null || pedido.numero() == null || pedido.numero() < 1) {
            throw Validacoes.recusar("numero", "Informe o número da semana.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        VendedorAtivo.exigir(jdbc, ctx.vendedorId());
        // Trava o vendedor: duas semanas novas ao mesmo tempo nunca deixam duas abertas.
        jdbc.queryForList("SELECT id FROM corona.vendedores WHERE id = ? FOR UPDATE", UUID.class, ctx.vendedorId());

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT vendedor_id, numero FROM corona.semanas_vendedor WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            if (!ctx.vendedorId().equals(e.get("vendedor_id")) || !pedido.numero().equals(e.get("numero"))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONSIGNADO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        if (jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.semanas_vendedor WHERE vendedor_id = ? AND numero = ?)",
                Boolean.class, ctx.vendedorId(), pedido.numero())) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NUMERO_REPETIDO);
        }
        Instant abertaEm = ctx.quando();
        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        jdbc.update("UPDATE corona.semanas_vendedor SET situacao = 'FECHADA', fechada_em = ? WHERE vendedor_id = ? AND situacao = 'ABERTA'",
                Timestamp.from(abertaEm), ctx.vendedorId());
        jdbc.update("""
                INSERT INTO corona.semanas_vendedor (id, vendedor_id, numero, aberta_em, situacao, dispositivo_id)
                VALUES (?, ?, ?, ?, 'ABERTA', ?)""", id, ctx.vendedorId(), pedido.numero(), Timestamp.from(abertaEm), ctx.aparelho());
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "SEMANA_DO_VENDEDOR_ABERTA", "semanas_vendedor", id, null,
                Map.of("vendedor_id", ctx.vendedorId().toString(), "numero", pedido.numero()));
        return false;
    }
}
