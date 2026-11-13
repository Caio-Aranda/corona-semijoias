package br.com.coronasemijoias.api.creditos;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crédito do cliente (tarefa 6.5; ADR-0014, item 9 e a linha datada de 13/11/2026). O crédito nasce da troca, da
 * devolução de venda paga com crédito ou da transferência pela dona; é usado como forma de pagamento {@code CREDITO},
 * só pelo vendedor dono dele e só na venda do mesmo cliente. Cada uso, estorno de uso e transferência é um registro
 * imutável de {@code usos_credito}; {@code valor_utilizado} e a situação são atalhos, sempre iguais à soma dos usos (o
 * banco confere).
 *
 * <p><strong>Ordem das travas</strong> (plano aprovado no PR #129): o crédito é sempre a última trava. A venda e o
 * estorno pegam a maleta e a venda antes; a devolução e a troca pegam o vendedor, as maletas, as vendas e a devolução
 * antes; a transferência pega só o crédito. Nenhum caminho pega um crédito antes de uma maleta ou venda, então a ordem
 * nunca se inverte. Vários créditos na mesma operação são travados em ordem de id.
 *
 * <p>Os métodos internos rodam na transação de quem chama (venda, devolução, troca); só a transferência abre a dela.
 */
@Service
public class ServicoDeCreditos {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** {@code jaExistia}: a transferência já estava gravada (200); senão, um crédito novo nasceu (201). */
    public record Resultado(UUID creditoId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    ServicoDeCreditos(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    // --- internos (na transação de quem chama) ---

    /**
     * O crédito que nasce da troca ({@code TROCA}, com a troca e a devolução) ou da devolução de venda paga com crédito
     * ({@code DEVOLUCAO}, só com a devolução). A devolução já está gravada com o mesmo valor em crédito.
     */
    public void gerar(UUID id, String origem, UUID clienteId, UUID vendedorId, UUID trocaId, UUID devolucaoId, BigDecimal valor,
            Instant quando, boolean horaAjustada, UUID usuarioId) {
        jdbc.update("""
                INSERT INTO corona.creditos_cliente (id, cliente_id, vendedor_id, origem, troca_id, devolucao_id, valor, cotacao_id,
                    gerado_em, hora_ajustada, registrado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, clienteId, vendedorId, origem, trocaId, devolucaoId, valor,
                cotacaoDoDia(LocalDate.ofInstant(quando, BRASILIA)), Timestamp.from(quando), horaAjustada, usuarioId);
    }

    /**
     * Trava o crédito e confere o uso, antes de gravar o pagamento: inexistente ou de outro vendedor, 404 (sem dizer que
     * existe); de outro cliente, 409; cancelado ou sem saldo, 409; acima do saldo, 409 (no valor exato passa).
     */
    public void conferirUso(UUID creditoId, UUID vendedorId, UUID clienteId, BigDecimal valor) {
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT vendedor_id, cliente_id, valor, valor_utilizado, situacao FROM corona.creditos_cliente
                 WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", creditoId);
        if (achado.isEmpty() || !vendedorId.equals(achado.get(0).get("vendedor_id"))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CREDITO_NAO_ENCONTRADO);
        }
        Map<String, Object> c = achado.get(0);
        if (!clienteId.equals(c.get("cliente_id"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_DE_OUTRO_CLIENTE);
        }
        if (!"ABERTO".equals(c.get("situacao"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_SEM_SALDO);
        }
        BigDecimal saldo = ((BigDecimal) c.get("valor")).subtract((BigDecimal) c.get("valor_utilizado"));
        if (valor.compareTo(saldo) > 0) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.CREDITO_ACIMA_DO_SALDO,
                    "Neste crédito sobram R$ " + saldo.toPlainString().replace('.', ',')
                            + ". Diminua o valor e pague o resto de outra forma.");
        }
    }

    /**
     * O uso pelo envio do computador (tarefa 6.7; ADR-0014, item 9, linha de 13/11/2026): trava o crédito e devolve quanto
     * dele pode pagar, sem recusar por saldo. Inexistente ou de outro vendedor, 404; de outro cliente, 409 (o impossível
     * continua recusado). Cancelado ou já usado, zero; acima do saldo, o saldo; senão, o pedido.
     */
    public BigDecimal usarAteOSaldo(UUID creditoId, UUID vendedorId, UUID clienteId, BigDecimal valor) {
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT vendedor_id, cliente_id, valor, valor_utilizado, situacao FROM corona.creditos_cliente
                 WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", creditoId);
        if (achado.isEmpty() || !vendedorId.equals(achado.get(0).get("vendedor_id"))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CREDITO_NAO_ENCONTRADO);
        }
        Map<String, Object> c = achado.get(0);
        if (!clienteId.equals(c.get("cliente_id"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_DE_OUTRO_CLIENTE);
        }
        if (!"ABERTO".equals(c.get("situacao"))) {
            return BigDecimal.ZERO.setScale(valor.scale());
        }
        BigDecimal saldo = ((BigDecimal) c.get("valor")).subtract((BigDecimal) c.get("valor_utilizado"));
        return valor.min(saldo.max(BigDecimal.ZERO)).setScale(valor.scale());
    }

    /**
     * Guarda, para a dona revisar, o pagamento em crédito que o envio pediu acima do saldo (a venda e o pagamento do que o
     * crédito ainda tinha já estão gravados). Nunca mexe em dinheiro. A linha tem id próprio; o pagamento que o
     * computador mandou fica em {@code pagamento_id}.
     */
    public void registrarSemSaldo(UUID pagamentoId, UUID vendaId, UUID creditoId, BigDecimal pedido, BigDecimal usado,
            Instant quando, UUID usuarioId) {
        UUID id = UuidV7.gerar();
        jdbc.update("""
                INSERT INTO corona.creditos_sem_saldo (id, pagamento_id, venda_id, credito_id, valor_pedido, valor_usado,
                    registrada_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, pagamentoId, vendaId, creditoId, pedido, usado, Timestamp.from(quando));
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_id", vendaId.toString());
        depois.put("credito_id", creditoId.toString());
        depois.put("valor_pedido", pedido.toPlainString());
        depois.put("valor_usado", usado.toPlainString());
        depois.put("pagamento_id", pagamentoId.toString());
        auditoria.registrar(Ator.usuario(usuarioId), "CREDITO_SEM_SALDO_NA_VENDA", "creditos_sem_saldo", id, null, depois);
    }

    // --- revisão da dona (tarefa 6.7) ---

    /** "Manter": o cliente continua devendo o resto. Nada muda além da revisão. Repetir, 200; já decidida de outro jeito, 409. */
    @Transactional
    public Resultado manter(UUID id, UUID usuarioId, String observacao) {
        return revisar(id, usuarioId, observacao, "MANTIDA");
    }

    /**
     * "Não aceitar": o valor que o crédito não pagou fica como pendência do vendedor, que o acerto (tarefa 7.5) passa a
     * cobrar. Nenhum pagamento, valor ou movimento muda. Observação obrigatória.
     */
    @Transactional
    public Resultado naoAceitar(UUID id, UUID usuarioId, String observacao) {
        if (Validacoes.limpar(observacao) == null) {
            throw Validacoes.recusar("observacao", "Diga por que a venda com crédito não foi aceita.");
        }
        return revisar(id, usuarioId, observacao, "NAO_ACEITA");
    }

    private Resultado revisar(UUID id, UUID usuarioId, String observacao, String decisao) {
        String limpa = Validacoes.limpar(observacao);
        if (limpa != null && limpa.length() > 400) {
            throw Validacoes.recusar("observacao", "Use no máximo 400 caracteres.");
        }
        List<UUID> venda = jdbc.query("SELECT venda_id FROM corona.creditos_sem_saldo WHERE id = ? AND deleted_at IS NULL",
                (rs, n) -> rs.getObject(1, UUID.class), id);
        if (venda.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CREDITO_SEM_SALDO_NAO_ENCONTRADO);
        }
        // Travas: a venda e depois a linha.
        jdbc.queryForList("SELECT id FROM corona.vendas WHERE id = ? FOR UPDATE", venda.get(0));
        String situacao = jdbc.queryForObject("SELECT situacao FROM corona.creditos_sem_saldo WHERE id = ? FOR UPDATE",
                String.class, id);
        if (decisao.equals(situacao)) {
            return new Resultado(id, true);
        }
        if (!"EM_REVISAO".equals(situacao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_SEM_SALDO_NAO_ESTA_PARA_REVISAO);
        }
        jdbc.update("""
                UPDATE corona.creditos_sem_saldo SET situacao = ?, revisada_em = ?, revisada_por = ?, observacao_revisao = ?
                 WHERE id = ?""", decisao, Timestamp.from(relogio.instant()), usuarioId, limpa, id);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", decisao);
        if (limpa != null) {
            depois.put("observacao", limpa);
        }
        auditoria.registrar(Ator.usuario(usuarioId), "MANTIDA".equals(decisao) ? "CREDITO_SEM_SALDO_MANTIDO"
                : "CREDITO_SEM_SALDO_NAO_ACEITO", "creditos_sem_saldo", id, Map.of("situacao", situacao), depois);
        return new Resultado(id, false);
    }

    /** Grava o uso do pagamento em crédito já gravado e atualiza o crédito. Quem chama já conferiu e travou o crédito. */
    public void registrarUso(UUID creditoId, UUID vendaId, UUID pagamentoId, BigDecimal valor, Instant quando) {
        jdbc.update("""
                INSERT INTO corona.usos_credito (id, credito_id, tipo, venda_id, pagamento_id, valor, usado_em)
                VALUES (?, ?, 'USO', ?, ?, ?, ?)""", UuidV7.gerar(), creditoId, vendaId, pagamentoId, valor,
                Timestamp.from(quando));
        atualizar(creditoId);
    }

    /**
     * O estorno da venda paga com crédito devolve o crédito (dúvida T8): o uso do pagamento {@code pagamentoId} ganha um
     * estorno negativo ligado ao reembolso em crédito {@code reembolsoId}, e o crédito volta a ficar em aberto.
     */
    public void estornarUso(UUID pagamentoId, UUID reembolsoId, Instant quando) {
        Map<String, Object> uso = jdbc.queryForMap("""
                SELECT id, credito_id, venda_id, valor FROM corona.usos_credito WHERE pagamento_id = ? AND tipo = 'USO'""",
                pagamentoId);
        UUID creditoId = (UUID) uso.get("credito_id");
        jdbc.queryForList("SELECT id FROM corona.creditos_cliente WHERE id = ? FOR UPDATE", creditoId);
        jdbc.update("""
                INSERT INTO corona.usos_credito (id, credito_id, tipo, venda_id, pagamento_id, valor, estorna_id, usado_em)
                VALUES (?, ?, 'ESTORNO', ?, ?, ?, ?, ?)""", UuidV7.gerar(), creditoId, uso.get("venda_id"), reembolsoId,
                ((BigDecimal) uso.get("valor")).negate(), uso.get("id"), Timestamp.from(quando));
        atualizar(creditoId);
    }

    /** Trava o crédito; já usado (no todo ou em parte), 409 {@code CREDITO_JA_USADO}. */
    public void exigirSemUso(UUID creditoId) {
        BigDecimal usado = jdbc.queryForObject(
                "SELECT valor_utilizado FROM corona.creditos_cliente WHERE id = ? FOR UPDATE", BigDecimal.class, creditoId);
        if (usado.signum() != 0) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_JA_USADO);
        }
    }

    /** Cancela o crédito da troca ou da devolução cancelada. Quem chama já conferiu que ele não tem uso. */
    public void cancelar(UUID creditoId, UUID usuarioId, Instant quando) {
        exigirSemUso(creditoId);
        jdbc.update("""
                UPDATE corona.creditos_cliente SET situacao = 'CANCELADO', cancelado_em = coalesce(?, now()), cancelado_por = ?
                 WHERE id = ?""", quando == null ? null : Timestamp.from(quando), usuarioId, creditoId);
    }

    // --- transferência pela dona ---

    /**
     * A dona passa o saldo inteiro do crédito para outro vendedor (dúvida T7): nasce um crédito novo ({@code
     * TRANSFERENCIA}, id do aplicativo) do outro vendedor e o original fica usado. Repetir com o mesmo id e o mesmo
     * destino devolve 200; com outro destino, 409. Sem saldo, 409; para o mesmo vendedor ou um vendedor desligado, 400.
     */
    @Transactional
    public Resultado transferir(UUID creditoId, UUID vendedorDestino, UUID idDoNovo, UUID usuarioId) {
        UUID id = Validacoes.idDoPedido(idDoNovo);
        if (vendedorDestino == null) {
            throw Validacoes.recusar("vendedorId", "Escolha o vendedor que vai receber o crédito.");
        }
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT cliente_id, vendedor_id, valor, valor_utilizado, situacao FROM corona.creditos_cliente
                 WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", creditoId);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CREDITO_NAO_ENCONTRADO);
        }
        Map<String, Object> c = achado.get(0);
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT origem, credito_origem_id, vendedor_id FROM corona.creditos_cliente WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            if ("TRANSFERENCIA".equals(e.get("origem")) && creditoId.equals(e.get("credito_origem_id"))
                    && vendedorDestino.equals(e.get("vendedor_id"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_JA_TRANSFERIDO_DIFERENTE);
        }
        if (vendedorDestino.equals(c.get("vendedor_id"))) {
            throw Validacoes.recusar("vendedorId", "O crédito já é deste vendedor. Escolha outro vendedor.");
        }
        Boolean ativo = jdbc.query("SELECT ativo FROM corona.vendedores WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, vendedorDestino);
        if (ativo == null) {
            throw Validacoes.recusar("vendedorId", "Escolha o vendedor que vai receber o crédito.");
        }
        if (!ativo) {
            throw Validacoes.recusar("vendedorId", "Este vendedor está desligado. Escolha um vendedor ativo.");
        }
        BigDecimal saldo = ((BigDecimal) c.get("valor")).subtract((BigDecimal) c.get("valor_utilizado"));
        if (!"ABERTO".equals(c.get("situacao")) || saldo.signum() <= 0) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_SEM_SALDO);
        }

        Instant agora = relogio.instant();
        jdbc.update("""
                INSERT INTO corona.creditos_cliente (id, cliente_id, vendedor_id, origem, credito_origem_id, valor, cotacao_id,
                    gerado_em, registrado_por)
                VALUES (?, ?, ?, 'TRANSFERENCIA', ?, ?, ?, ?, ?)""", id, c.get("cliente_id"), vendedorDestino, creditoId, saldo,
                cotacaoDoDia(LocalDate.ofInstant(agora, BRASILIA)), Timestamp.from(agora), usuarioId);
        jdbc.update("""
                INSERT INTO corona.usos_credito (id, credito_id, tipo, credito_destino_id, valor, usado_em)
                VALUES (?, ?, 'TRANSFERENCIA', ?, ?, ?)""", UuidV7.gerar(), creditoId, id, saldo, Timestamp.from(agora));
        atualizar(creditoId);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("credito_novo_id", id.toString());
        depois.put("vendedor_destino_id", vendedorDestino.toString());
        depois.put("valor", saldo.toPlainString());
        auditoria.registrar(Ator.usuario(usuarioId), "CREDITO_TRANSFERIDO", "creditos_cliente", creditoId,
                Map.of("vendedor_id", c.get("vendedor_id").toString(), "saldo", saldo.toPlainString()), depois);
        return new Resultado(id, false);
    }

    // --- apoio ---

    /** O atalho do crédito: a soma dos usos e a situação que ela dá (usado quando zera). */
    private void atualizar(UUID creditoId) {
        jdbc.update("""
                UPDATE corona.creditos_cliente c
                   SET valor_utilizado = s.usado, situacao = CASE WHEN s.usado = c.valor THEN 'UTILIZADO' ELSE 'ABERTO' END
                  FROM (SELECT coalesce(sum(valor), 0) AS usado FROM corona.usos_credito WHERE credito_id = ?) s
                 WHERE c.id = ?""", creditoId, creditoId);
    }

    /** A cotação mais recente até o dia (horário de Brasília), ou nenhuma. */
    private UUID cotacaoDoDia(LocalDate dia) {
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), dia).stream().findFirst().orElse(null);
    }
}
