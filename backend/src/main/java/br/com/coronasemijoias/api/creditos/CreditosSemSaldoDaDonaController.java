package br.com.coronasemijoias.api.creditos;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.creditos.ServicoDeCreditos.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * As vendas pagas pelo envio do computador com crédito do cliente acima do saldo (tarefa 6.7; ADR-0014, item 9, linha
 * de 13/11/2026; dúvida S2): a dona lista, mantém ou não aceita. Só ADMIN. Sem tela ainda (6.9 ou depois).
 */
@RestController
@RequestMapping("/api/dona/creditos-sem-saldo")
@ExigePerfil("ADMIN")
public class CreditosSemSaldoDaDonaController {

    private static final Set<String> SITUACOES = Set.of("EM_REVISAO", "MANTIDA", "NAO_ACEITA");

    /** {@code valorPedido}: o que o computador pediu em crédito; {@code valorUsado}: o que o crédito ainda tinha. */
    public record CreditoSemSaldo(UUID id, UUID vendaId, UUID pagamentoId, UUID creditoId, UUID vendedorId, UUID clienteId,
            BigDecimal valorPedido, BigDecimal valorUsado, String situacao, Instant registradaEm, Instant revisadaEm,
            String observacaoRevisao) {
    }

    public record PedidoDeRevisao(String observacao) {
    }

    private final ServicoDeCreditos servico;
    private final JdbcTemplate jdbc;

    CreditosSemSaldoDaDonaController(ServicoDeCreditos servico, JdbcTemplate jdbc) {
        this.servico = servico;
        this.jdbc = jdbc;
    }

    @Operation(operationId = "listarCreditosSemSaldo", summary = "Vendas pagas com crédito que já tinha acabado",
            description = "As vendas que chegaram do computador do vendedor pagas com crédito do cliente acima do saldo, da "
                    + "mais nova para a mais antiga (no máximo 500), pela situação (padrão: EM_REVISAO, para revisar). A venda "
                    + "entrou; o crédito pagou só o que tinha e o resto ficou a receber do cliente.")
    @GetMapping
    public List<CreditoSemSaldo> listar(@RequestParam(required = false) String situacao) {
        String s = situacao == null ? "EM_REVISAO" : situacao;
        if (!SITUACOES.contains(s)) {
            throw Validacoes.recusar("situacao", "Escolha a situação: para revisar, mantida ou não aceita.");
        }
        return consultar("cs.situacao = ?", s);
    }

    @Operation(operationId = "manterCreditoSemSaldo", summary = "Manter a venda com crédito que já tinha acabado",
            description = "O cliente continua devendo o resto. Nada muda além da revisão. Repetir devolve 200; já não aceita, 409.")
    @PostMapping("/{id}/manter")
    public ResponseEntity<CreditoSemSaldo> manter(@PathVariable UUID id, @RequestBody(required = false) PedidoDeRevisao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.manter(id, quem.id(), pedido == null ? null : pedido.observacao());
        return ResponseEntity.status(HttpStatus.OK).body(ver(r.creditoId()));
    }

    @Operation(operationId = "naoAceitarCreditoSemSaldo", summary = "Não aceitar a venda com crédito que já tinha acabado",
            description = "O valor que o crédito não pagou fica como pendência do vendedor, cobrada no acerto. Nenhum pagamento "
                    + "ou valor muda. Observação obrigatória. Repetir devolve 200; já mantida, 409.")
    @PostMapping("/{id}/nao-aceitar")
    public ResponseEntity<CreditoSemSaldo> naoAceitar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeRevisao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.naoAceitar(id, quem.id(), pedido == null ? null : pedido.observacao());
        return ResponseEntity.status(HttpStatus.OK).body(ver(r.creditoId()));
    }

    private CreditoSemSaldo ver(UUID id) {
        List<CreditoSemSaldo> achados = consultar("cs.id = ?", id);
        if (achados.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CREDITO_SEM_SALDO_NAO_ENCONTRADO);
        }
        return achados.get(0);
    }

    private List<CreditoSemSaldo> consultar(String onde, Object arg) {
        return jdbc.query("""
                SELECT cs.id, cs.venda_id, cs.pagamento_id, cs.credito_id, v.vendedor_id, v.cliente_id, cs.valor_pedido,
                       cs.valor_usado, cs.situacao, cs.registrada_em, cs.revisada_em, cs.observacao_revisao
                  FROM corona.creditos_sem_saldo cs JOIN corona.vendas v ON v.id = cs.venda_id
                 WHERE cs.deleted_at IS NULL AND %s
                 ORDER BY cs.registrada_em DESC, cs.id LIMIT 500""".formatted(onde),
                (rs, n) -> new CreditoSemSaldo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getObject(6, UUID.class), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9),
                        instante(rs.getTimestamp(10)), instante(rs.getTimestamp(11)), rs.getString(12)), arg);
    }

    private static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
