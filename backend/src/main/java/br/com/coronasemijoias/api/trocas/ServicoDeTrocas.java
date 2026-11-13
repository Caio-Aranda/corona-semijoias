package br.com.coronasemijoias.api.trocas;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.OrdemDoBanco;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.DevolucaoDaTrocaGravada;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.CreditoDaTroca;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.VendaDaTrocaGravada;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Troca e "Cancelar troca" (tarefa 6.5; ADR-0014, itens 8, 9, 11 e 12; plano aprovado no PR #129). A troca é uma
 * operação só, tudo ou nada: a devolução de A ({@link ServicoDeDevolucoes#devolverNaTroca}, destino {@code TROCA}), a
 * venda nova B ({@link ServicoDeVendas#venderNaTroca}) e a linha de {@code trocas}. O dinheiro passa pelo crédito do
 * cliente: o valor de A vira um reembolso {@code CREDITO} na venda A e um crédito novo, que paga B na mesma hora (o
 * menor entre A e B); o resto de B o cliente paga, ou a sobra fica de crédito em aberto.
 *
 * <p>O app manda os ids (troca, crédito, pagamento do crédito, devolução e venda nova), a devolução (venda A, motivo,
 * peças pelo produto com estado, autorização) e a venda nova (maleta, peças com desconto, pagamentos do resto). Nunca
 * valor de A, diferença nem valor do crédito: tudo é do servidor.
 *
 * <p><strong>Ordem das travas</strong> (dúvida T5): o vendedor ({@code pg_advisory_xact_lock}, a mesma trava da
 * devolução) → as maletas de A e de B em ordem de id → as vendas em ordem de id → a devolução, a troca e a autorização →
 * os créditos em ordem de id. A troca é a única operação que trava duas maletas, e sempre depois da trava do vendedor.
 */
@Service
public class ServicoDeTrocas {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final int LIMITE_DO_TEXTO = 400;
    private static final Set<String> MALETA_COM_O_VENDEDOR = Set.of("ENVIADA", "CONFERIDA");
    /** As conferências do fim da transação da troca, feitas antes do fim para o erro virar 409 (pedido 2 do PR #129). */
    private static final String CONFERENCIAS_ADIADAS = "corona.trocas_coerente, corona.vendas_da_troca_conferida, "
            + "corona.devolucoes_da_troca_conferida, corona.vendas_troca_fk, corona.creditos_cliente_troca_fk, "
            + "corona.usos_credito_conferem_o_credito";

    /** A devolução de A: a venda, o motivo, a observação, as peças (pelo produto, com estado) e a autorização. */
    public record DevolucaoDaTroca(UUID id, UUID vendaId, String motivo, String observacao, List<PecaDoPedido> itens,
            UUID autorizacaoId) {
    }

    /** A venda nova B: a maleta, as peças com desconto e os pagamentos do resto (o crédito da troca entra sozinho). */
    public record VendaDaTroca(UUID id, UUID remessaId, List<LinhaDoPedido> itens, List<PagamentoDoPedido> pagamentos,
            BigDecimal totalConferido) {
    }

    public record PedidoDeTroca(UUID id, UUID creditoId, UUID pagamentoDoCreditoId, DevolucaoDaTroca devolucao,
            VendaDaTroca venda) {
    }

    /** {@code jaExistia}: a troca (ou o cancelamento) já estava gravada (200); senão, algo novo (201). */
    public record Resultado(UUID trocaId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final ServicoDeDevolucoes devolucoes;
    private final ServicoDeVendas vendas;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;

    ServicoDeTrocas(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, ServicoDeDevolucoes devolucoes, ServicoDeVendas vendas,
            OrigemDoDispositivo origem, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.devolucoes = devolucoes;
        this.vendas = vendas;
        this.origem = origem;
        this.relogio = relogio;
    }

    // --- troca ---

    /**
     * Registra a troca. {@code momento} é a hora da troca (a do envio já corrigida pelo servidor); nulo = agora (a rota
     * online). {@code modo}: na rota online, fora do prazo é 409; pelo envio do computador (tarefa 6.7), a devolução de A
     * entra para revisão e a troca é gravada.
     */
    @Transactional
    public Resultado trocar(UUID vendedorId, UUID usuarioId, UUID aparelho, PedidoDeTroca pedido, Instant momento,
            boolean horaAjustada, ModoDaDevolucao modo) {
        if (pedido == null) {
            throw Validacoes.recusar("troca", "Informe a troca.");
        }
        DevolucaoDaTroca devolucao = pedido.devolucao();
        VendaDaTroca venda = pedido.venda();
        if (devolucao == null || devolucao.itens() == null || devolucao.itens().isEmpty()) {
            throw Validacoes.recusar("devolucao", "Informe as peças que o cliente devolveu.");
        }
        if (venda == null || venda.itens() == null || venda.itens().isEmpty()) {
            throw Validacoes.recusar("venda", "Informe as peças que o cliente levou.");
        }
        if (devolucao.vendaId() == null) {
            throw Validacoes.recusar("vendaId", "Escolha a venda da devolução.");
        }
        if (venda.remessaId() == null) {
            throw Validacoes.recusar("remessaId", "Escolha a maleta da venda.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        UUID creditoId = Validacoes.idDoPedido(pedido.creditoId());
        UUID pagamentoDoCredito = Validacoes.idDoPedido(pedido.pagamentoDoCreditoId());
        UUID devolucaoId = Validacoes.idDoPedido(devolucao.id());
        UUID vendaNovaId = Validacoes.idDoPedido(venda.id());

        // A venda A, sem trava (o vendedor e a maleta dela nunca mudam). De outro vendedor: 404, sem dizer que existe.
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT vendedor_id, remessa_id FROM corona.vendas WHERE id = ? AND deleted_at IS NULL", devolucao.vendaId());
        if (achada.isEmpty() || !vendedorId.equals(achada.get(0).get("vendedor_id"))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.VENDA_NAO_ENCONTRADA);
        }
        UUID maletaA = (UUID) achada.get(0).get("remessa_id");

        // Travas: o vendedor, as duas maletas em ordem de id, a venda A (ver a ordem no comentário da classe).
        ServicoDeDevolucoes.trancarVendedor(jdbc, vendedorId);
        TreeSet<UUID> maletas = new TreeSet<>(OrdemDoBanco.UUIDS);
        maletas.add(maletaA);
        maletas.add(venda.remessaId());
        for (UUID m : maletas) {
            List<UUID> dono = jdbc.query("SELECT vendedor_id FROM corona.remessas WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                    (rs, n) -> rs.getObject(1, UUID.class), m);
            if (dono.isEmpty() || !vendedorId.equals(dono.get(0))) {
                throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
            }
        }
        jdbc.queryForList("SELECT id FROM corona.vendas WHERE id = ? FOR UPDATE", devolucao.vendaId());

        // Idempotência pelo id da troca; um id da troca já usado em outra coisa é o mesmo caso do 409.
        List<Map<String, Object>> existente = jdbc.queryForList("""
                SELECT vendedor_id, venda_origem_id, devolucao_id, venda_nova_id FROM corona.trocas WHERE id = ?""", id);
        if (!existente.isEmpty()) {
            if (!mesmaTroca(id, vendedorId, creditoId, pagamentoDoCredito, devolucaoId, vendaNovaId, devolucao, venda,
                    existente.get(0))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.TROCA_JA_REGISTRADA_DIFERENTE);
            }
            return new Resultado(id, true);
        }
        if (existe("devolucoes", devolucaoId) || existe("vendas", vendaNovaId) || existe("creditos_cliente", creditoId)
                || existe("pagamentos_cliente", pagamentoDoCredito)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.TROCA_JA_REGISTRADA_DIFERENTE);
        }
        VendedorAtivo.exigir(jdbc, vendedorId);

        Instant quando = momento == null ? relogio.instant() : momento;
        DevolucaoDaTrocaGravada a = devolucoes.devolverNaTroca(vendedorId, usuarioId, aparelho,
                new PedidoDaDevolucaoDoCliente(devolucaoId, devolucao.vendaId(), devolucao.motivo(), devolucao.observacao(), null,
                        devolucao.itens(), null, devolucao.autorizacaoId()), quando, horaAjustada, modo, id, creditoId);
        VendaDaTrocaGravada b = vendas.venderNaTroca(vendedorId, usuarioId, aparelho,
                new PedidoDeVenda(vendaNovaId, venda.remessaId(), a.clienteId(), venda.itens(), venda.pagamentos(),
                        venda.totalConferido()), quando, horaAjustada,
                new CreditoDaTroca(id, creditoId, pagamentoDoCredito, a.valorDevolvido()));

        BigDecimal diferenca = b.total().subtract(a.valorDevolvido());
        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        jdbc.update("""
                INSERT INTO corona.trocas (id, venda_origem_id, devolucao_id, venda_nova_id, cliente_id, vendedor_id, diferenca,
                    cotacao_id, trocada_em, hora_ajustada, registrada_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, devolucao.vendaId(), devolucaoId, vendaNovaId, a.clienteId(),
                vendedorId, diferenca, cotacaoDoDia(LocalDate.ofInstant(quando, BRASILIA)), Timestamp.from(quando), horaAjustada,
                usuarioId);
        conferirAgora();

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("venda_origem_id", devolucao.vendaId().toString());
        depois.put("devolucao_id", devolucaoId.toString());
        depois.put("venda_nova_id", vendaNovaId.toString());
        depois.put("valor_devolvido", a.valorDevolvido().toPlainString());
        depois.put("total_da_venda_nova", b.total().toPlainString());
        depois.put("diferenca", diferenca.toPlainString());
        depois.put("credito_em_aberto", a.valorDevolvido().subtract(b.creditoUsado()).toPlainString());
        auditoria.registrar(Ator.usuario(usuarioId), "TROCA_REGISTRADA", "trocas", id, null, depois);
        return new Resultado(id, false);
    }

    /**
     * As conferências que o banco faria no COMMIT (a troca fecha, a venda nova e a devolução apontam para ela, o atalho
     * do crédito bate com os usos), feitas agora: se algo não fechar, o erro vem aqui, dentro do serviço, e vira 409
     * {@code TROCA_NAO_CONFERE} com tudo desfeito, nunca um 500 no fim da transação. Depois voltam a ser adiadas (a
     * tarefa 6.7 pode gravar mais de uma troca na mesma transação).
     */
    private void conferirAgora() {
        try {
            jdbc.execute("SET CONSTRAINTS " + CONFERENCIAS_ADIADAS + " IMMEDIATE");
        } catch (DataAccessException naoFechou) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.TROCA_NAO_CONFERE);
        }
        jdbc.execute("SET CONSTRAINTS " + CONFERENCIAS_ADIADAS + " DEFERRED");
    }

    // --- cancelar ---

    /**
     * "Cancelar troca" (ADR-0014, item 11; dúvida T10): tudo volta como era, com registros de sinal oposto. A troca fica
     * cancelada primeiro (o banco só deixa desfazer a venda nova e a devolução de uma troca cancelada); a venda nova é
     * estornada (B volta à maleta, o uso do crédito é estornado e o resto pago volta na forma em que foi pago); a
     * devolução de A é cancelada (A sai da maleta de novo e o reembolso em crédito é estornado) e o crédito fica
     * cancelado. {@code vendedorId} nulo é a dona (a qualquer hora); o vendedor só a dele e só no dia da troca (Brasília).
     * Recusado se a sobra do crédito já foi usada, se a peça A já foi vendida de novo, se uma das maletas não está mais
     * com o vendedor, se B já foi devolvida ou se a dona já decidiu a devolução. O reenvio com o mesmo id devolve 200.
     */
    @Transactional
    public Resultado cancelar(UUID trocaId, UUID vendedorId, UUID usuarioId, UUID aparelho, UUID idDoCancelamento, String motivo,
            Instant momento) {
        UUID cancelamentoId = Validacoes.idDoPedido(idDoCancelamento);
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de cancelar a troca.");
        }
        if (limpo.length() > LIMITE_DO_TEXTO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_TEXTO + " caracteres.");
        }
        List<Map<String, Object>> achada = jdbc.queryForList("""
                SELECT vendedor_id, venda_origem_id, venda_nova_id, devolucao_id FROM corona.trocas
                 WHERE id = ? AND deleted_at IS NULL""", trocaId);
        if (achada.isEmpty() || (vendedorId != null && !vendedorId.equals(achada.get(0).get("vendedor_id")))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.TROCA_NAO_ENCONTRADA);
        }
        UUID vendaA = (UUID) achada.get(0).get("venda_origem_id");
        UUID vendaB = (UUID) achada.get(0).get("venda_nova_id");
        UUID devolucaoId = (UUID) achada.get(0).get("devolucao_id");
        UUID maletaA = jdbc.queryForObject("SELECT remessa_id FROM corona.vendas WHERE id = ?", UUID.class, vendaA);
        UUID maletaB = jdbc.queryForObject("SELECT remessa_id FROM corona.vendas WHERE id = ?", UUID.class, vendaB);

        // Travas: as maletas e as vendas em ordem de id, a devolução, a troca e, por último, o crédito.
        TreeSet<UUID> maletas = new TreeSet<>(OrdemDoBanco.UUIDS);
        maletas.add(maletaA);
        maletas.add(maletaB);
        Map<UUID, Map<String, Object>> situacaoDasMaletas = new LinkedHashMap<>();
        for (UUID m : maletas) {
            situacaoDasMaletas.put(m, jdbc.queryForMap(
                    "SELECT status, dispositivo_id FROM corona.remessas WHERE id = ? FOR UPDATE", m));
        }
        TreeSet<UUID> asVendas = new TreeSet<>(OrdemDoBanco.UUIDS);
        asVendas.add(vendaA);
        asVendas.add(vendaB);
        for (UUID v : asVendas) {
            jdbc.queryForList("SELECT id FROM corona.vendas WHERE id = ? FOR UPDATE", v);
        }
        String situacaoDaDevolucao = jdbc.queryForObject(
                "SELECT situacao FROM corona.devolucoes WHERE id = ? FOR UPDATE", String.class, devolucaoId);
        Map<String, Object> troca = jdbc.queryForMap(
                "SELECT situacao, cancelamento_id, trocada_em FROM corona.trocas WHERE id = ? FOR UPDATE", trocaId);

        if ("CANCELADA".equals(troca.get("situacao"))) {
            if (cancelamentoId.equals(troca.get("cancelamento_id"))) {
                return new Resultado(trocaId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.TROCA_JA_CANCELADA);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.trocas WHERE cancelamento_id = ?)", Boolean.class, cancelamentoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.TROCA_JA_REGISTRADA_DIFERENTE);
        }
        Instant quando = momento == null ? relogio.instant() : momento;
        if (vendedorId != null) {
            VendedorAtivo.exigir(jdbc, vendedorId);
            Instant trocadaEm = ((Timestamp) troca.get("trocada_em")).toInstant();
            if (!LocalDate.ofInstant(trocadaEm, BRASILIA).equals(LocalDate.ofInstant(quando, BRASILIA))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CANCELAR_TROCA_SO_DA_DONA);
            }
        }
        if (!"ACEITA".equals(situacaoDaDevolucao) && !"EM_REVISAO".equals(situacaoDaDevolucao)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.TROCA_NAO_PODE_SER_CANCELADA);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.devolucoes WHERE venda_id = ? AND situacao <> 'CANCELADA')""",
                Boolean.class, vendaB))) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.TROCA_NAO_PODE_SER_CANCELADA,
                    "A peça que o cliente levou nesta troca já foi devolvida. Cancele essa devolução antes.");
        }

        // A peça que voltou (A) sai de novo da maleta dela; B volta à maleta dela. Nenhuma maleta fechada recebe movimento.
        List<Map<String, Object>> voltaram = jdbc.queryForList("""
                SELECT produto_id, quantidade FROM corona.movimentos_remessa
                 WHERE devolucao_id = ? AND estorna_id IS NULL AND NOT EXISTS (
                       SELECT 1 FROM corona.movimentos_remessa e WHERE e.estorna_id = movimentos_remessa.id)""", devolucaoId);
        if (!MALETA_COM_O_VENDEDOR.contains((String) situacaoDasMaletas.get(maletaB).get("status"))
                || (!voltaram.isEmpty() && !MALETA_COM_O_VENDEDOR.contains((String) situacaoDasMaletas.get(maletaA).get("status")))) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.TROCA_NAO_PODE_SER_CANCELADA,
                    "Uma das maletas desta troca já não está com o vendedor. Fale com a dona.");
        }
        if (vendedorId != null) {
            for (UUID m : List.of(maletaA, maletaB)) {
                UUID dono = (UUID) situacaoDasMaletas.get(m).get("dispositivo_id");
                if (dono != null && !dono.equals(aparelho) && (m.equals(maletaB) || !voltaram.isEmpty())) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
                }
            }
        }
        for (Map<String, Object> v : voltaram) {
            UUID produto = (UUID) v.get("produto_id");
            int saldo = jdbc.queryForObject("""
                    SELECT coalesce((SELECT i.qtd_enviada FROM corona.itens_remessa i
                                      WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL), 0)
                           + coalesce((SELECT sum(x.quantidade) FROM corona.movimentos_remessa x
                                        WHERE x.remessa_id = ? AND x.produto_id = ?), 0)""", Integer.class,
                    maletaA, produto, maletaA, produto);
            if (maletaA.equals(maletaB)) {
                // A mesma peça como B na mesma maleta: ela volta com o estorno de B antes de A sair.
                saldo += jdbc.queryForObject("""
                        SELECT coalesce(-sum(quantidade), 0) FROM corona.movimentos_remessa
                         WHERE venda_id = ? AND produto_id = ? AND tipo = 'TROCA_SAIDA'""", Integer.class, vendaB, produto);
            }
            if (saldo < ((Number) v.get("quantidade")).intValue()) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.TROCA_NAO_PODE_SER_CANCELADA,
                        "A peça que voltou nesta troca já foi vendida de novo e a troca não pode ser cancelada. Fale com a dona.");
            }
        }

        // O crédito da troca (a última trava): a sobra usada fora da própria troca impede cancelar.
        UUID credito = jdbc.query("SELECT id FROM corona.creditos_cliente WHERE troca_id = ? FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), trocaId).stream().findFirst().orElse(null);
        if (credito != null) {
            BigDecimal foraDaTroca = jdbc.queryForObject("""
                    SELECT coalesce(sum(valor), 0) FROM corona.usos_credito WHERE credito_id = ? AND venda_id IS DISTINCT FROM ?""",
                    BigDecimal.class, credito, vendaB);
            if (foraDaTroca.signum() != 0) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CREDITO_DA_TROCA_JA_USADO);
            }
        }

        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        jdbc.update("""
                UPDATE corona.trocas SET situacao = 'CANCELADA', cancelamento_id = ?, cancelada_em = coalesce(?, now()),
                       cancelada_por = ?, motivo_cancelamento = ?
                 WHERE id = ?""", cancelamentoId, momento == null ? null : Timestamp.from(momento), usuarioId, limpo, trocaId);
        vendas.estornarNaTroca(vendaB, vendedorId, usuarioId, aparelho, UuidV7.gerar(), limpo, momento);
        devolucoes.cancelarNaTroca(devolucaoId, vendedorId, usuarioId, aparelho, UuidV7.gerar(), limpo, momento);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("situacao", "CANCELADA");
        depois.put("motivo", limpo);
        auditoria.registrar(Ator.usuario(usuarioId), "TROCA_CANCELADA", "trocas", trocaId, Map.of("situacao", "ATIVA"), depois);
        return new Resultado(trocaId, false);
    }

    // --- internos ---

    private boolean existe(String tabela, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona." + tabela + " WHERE id = ?)", Boolean.class, id));
    }

    /**
     * O pedido repetido é a mesma troca? Compara o vendedor, os ids (devolução, venda nova, crédito e pagamento do
     * crédito), a devolução (venda, motivo, observação, autorização e peças com estado) e a venda nova (maleta, peças com
     * desconto e os pagamentos do resto).
     */
    private boolean mesmaTroca(UUID id, UUID vendedorId, UUID creditoId, UUID pagamentoDoCredito, UUID devolucaoId,
            UUID vendaNovaId, DevolucaoDaTroca devolucao, VendaDaTroca venda, Map<String, Object> gravada) {
        if (!vendedorId.equals(gravada.get("vendedor_id")) || !devolucao.vendaId().equals(gravada.get("venda_origem_id"))
                || !devolucaoId.equals(gravada.get("devolucao_id")) || !vendaNovaId.equals(gravada.get("venda_nova_id"))) {
            return false;
        }
        UUID creditoGravado = jdbc.query("SELECT id FROM corona.creditos_cliente WHERE troca_id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), id).stream().findFirst().orElse(null);
        if (creditoGravado != null && !creditoGravado.equals(creditoId)) {
            return false;
        }
        Map<String, Object> d = jdbc.queryForMap(
                "SELECT motivo, observacao, autorizacao_id FROM corona.devolucoes WHERE id = ?", devolucaoId);
        if (!Objects.equals(devolucao.motivo(), d.get("motivo"))
                || !Objects.equals(Validacoes.limpar(devolucao.observacao()), d.get("observacao"))
                || !Objects.equals(devolucao.autorizacaoId(), d.get("autorizacao_id"))) {
            return false;
        }
        Map<String, Integer> pedidas = new LinkedHashMap<>();
        for (PecaDoPedido p : devolucao.itens()) {
            if (p == null) {
                return false;
            }
            pedidas.merge(p.produtoId() + "/" + p.estado(), p.quantidade() == null ? 0 : p.quantidade(), Integer::sum);
        }
        Map<String, Integer> devolvidas = new LinkedHashMap<>();
        jdbc.query("""
                SELECT iv.produto_id, i.estado_peca, i.quantidade
                  FROM corona.itens_devolucao i JOIN corona.itens_venda iv ON iv.id = i.item_venda_id
                 WHERE i.devolucao_id = ?""", rs -> {
                    devolvidas.put(rs.getObject(1, UUID.class) + "/" + rs.getString(2), rs.getInt(3));
                }, devolucaoId);
        if (!pedidas.equals(devolvidas)) {
            return false;
        }
        UUID maleta = jdbc.queryForObject("SELECT remessa_id FROM corona.vendas WHERE id = ?", UUID.class, vendaNovaId);
        if (!venda.remessaId().equals(maleta)) {
            return false;
        }
        Map<UUID, String> levadas = new LinkedHashMap<>();
        for (LinhaDoPedido l : venda.itens()) {
            if (l == null) {
                return false;
            }
            BigDecimal desconto = l.desconto() == null ? BigDecimal.ZERO : l.desconto();
            levadas.put(l.produtoId(), l.quantidade() + "/" + desconto.stripTrailingZeros().toPlainString());
        }
        Map<UUID, String> vendidas = new LinkedHashMap<>();
        jdbc.query("SELECT produto_id, quantidade, desconto FROM corona.itens_venda WHERE venda_id = ?", rs -> {
            vendidas.put(rs.getObject(1, UUID.class), rs.getInt(2) + "/" + rs.getBigDecimal(3).stripTrailingZeros().toPlainString());
        }, vendaNovaId);
        if (!levadas.equals(vendidas)) {
            return false;
        }
        Map<UUID, String> resto = new LinkedHashMap<>();
        for (PagamentoDoPedido p : venda.pagamentos() == null ? List.<PagamentoDoPedido>of() : venda.pagamentos()) {
            if (p == null || p.id() == null || p.valor() == null) {
                return false;
            }
            resto.put(p.id(), p.forma() + "/" + p.valor().stripTrailingZeros().toPlainString() + "/" + p.creditoId());
        }
        Map<UUID, String> pagos = new LinkedHashMap<>();
        List<UUID> doCredito = new ArrayList<>();
        jdbc.query("SELECT id, forma, valor, credito_id FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO'",
                rs -> {
                    UUID pagamento = rs.getObject(1, UUID.class);
                    UUID credito = rs.getObject(4, UUID.class);
                    if (creditoGravado != null && creditoGravado.equals(credito)) {
                        doCredito.add(pagamento);
                    } else {
                        pagos.put(pagamento, rs.getString(2) + "/" + rs.getBigDecimal(3).stripTrailingZeros().toPlainString()
                                + "/" + credito);
                    }
                }, vendaNovaId);
        return resto.equals(pagos) && (doCredito.isEmpty() || doCredito.equals(List.of(pagamentoDoCredito)));
    }

    /** A cotação mais recente até o dia (horário de Brasília), ou nenhuma. */
    private UUID cotacaoDoDia(LocalDate dia) {
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), dia).stream().findFirst().orElse(null);
    }
}
