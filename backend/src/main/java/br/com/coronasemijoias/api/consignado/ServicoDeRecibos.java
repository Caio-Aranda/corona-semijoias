package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Resultado;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recibos do consignado (ADR-0016, itens 10 e 12): pagamento lançado à parte ({@code PAGAMENTO_CONSIGNADO}) e estorno de
 * recibo. O servidor aplica o valor nos débitos do cliente do mais antigo para o mais novo (o cliente é travado antes); o que
 * passar do devido (só por divergência sem internet) é ACEITO, fica "a aplicar" no recibo e entra no próximo débito, com
 * alerta para a dona. Recibo não se edita: só se estorna (registro novo, com motivo).
 */
@Service
public class ServicoDeRecibos {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX", "DEBITO", "CREDITO");

    public record FormaPaga(String forma, BigDecimal valor) {
    }

    public record NovoPagamento(UUID id, UUID clienteId, UUID semanaId, Integer sequencia, List<FormaPaga> formas,
            BigDecimal pendenciaVista) {
    }

    public record EstornoDoRecibo(UUID id, UUID reciboId, String motivo) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;
    private final ServicoDeAlertasDoConsignado alertas;
    private final DebitosDoCliente debitos;
    private final ServicoDeNotas notas;

    public ServicoDeRecibos(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem,
            ServicoDeAlertasDoConsignado alertas, DebitosDoCliente debitos, ServicoDeNotas notas) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
        this.alertas = alertas;
        this.debitos = debitos;
        this.notas = notas;
    }

    /** As formas conferidas (sem repetir forma, valor positivo com duas casas); devolve o total. */
    static BigDecimal totalDasFormas(List<FormaPaga> formas) {
        if (formas == null || formas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PAGAMENTO_INVALIDO);
        }
        Set<String> vistas = new HashSet<>();
        List<BigDecimal> valores = new ArrayList<>();
        for (FormaPaga f : formas) {
            if (f == null || f.forma() == null || !FORMAS.contains(f.forma()) || !vistas.add(f.forma()) || f.valor() == null
                    || f.valor().signum() <= 0 || f.valor().stripTrailingZeros().scale() > Dinheiro.ESCALA) {
                throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PAGAMENTO_INVALIDO);
            }
            valores.add(f.valor());
        }
        return Dinheiro.somar(valores);
    }

    // --- Lançar pagamento ---

    @Transactional
    public boolean lancar(ContextoDoConsignado ctx, NovoPagamento pedido) {
        if (pedido == null || pedido.clienteId() == null || pedido.semanaId() == null || pedido.sequencia() == null
                || pedido.sequencia() < 1) {
            throw Validacoes.recusar("pagamento", "Informe o cliente, a semana e o número do recibo.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        BigDecimal total = totalDasFormas(pedido.formas());
        VendedorAtivo.exigir(jdbc, ctx.vendedorId());
        List<UUID> dono = jdbc.queryForList("SELECT vendedor_responsavel_id FROM corona.clientes WHERE id = ? AND deleted_at IS NULL",
                UUID.class, pedido.clienteId());
        if (dono.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        if (!ctx.vendedorId().equals(dono.get(0))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CLIENTE_DE_OUTRO_VENDEDOR);
        }
        debitos.travarCliente(pedido.clienteId());
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT cliente_id, total, vendedor_id FROM corona.recibos_consignado WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            if (!pedido.clienteId().equals(e.get("cliente_id")) || total.compareTo((BigDecimal) e.get("total")) != 0
                    || !ctx.vendedorId().equals(e.get("vendedor_id"))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONSIGNADO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        semanaDoVendedor(ctx, pedido.semanaId());
        notas.reservarNumero(ctx, pedido.semanaId(), pedido.sequencia(), "RECIBO", id);
        List<UUID> tocadas = new ArrayList<>();
        debitos.reaplicarSobras(pedido.clienteId(), tocadas);
        BigDecimal pendencia = debitos.pendenciaDoCliente(pedido.clienteId());

        gravarRecibo(ctx, id, pedido.clienteId(), pedido.semanaId(), pedido.sequencia(), null, total, pedido.formas());
        Resultado r = debitos.aplicar(id, pedido.clienteId(), total, tocadas);
        if (r.sobra().signum() > 0) {
            alertas.registrar("PAGAMENTO_ACIMA_DO_DEVIDO", ctx.vendedorId(), "recibos_consignado", id,
                    Map.of("total", total.toPlainString(), "pendencia", pendencia.toPlainString(), "sobra", r.sobra().toPlainString()));
        }
        debitos.reavaliar(tocadas);
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "PAGAMENTO_CONSIGNADO_LANCADO", "recibos_consignado", id, null,
                Map.of("cliente_id", pedido.clienteId().toString(), "total", total.toPlainString()));
        return false;
    }

    /** Grava o recibo e as formas (usado também pelo acerto). O cliente já está travado. */
    void gravarRecibo(ContextoDoConsignado ctx, UUID id, UUID clienteId, UUID semanaId, int sequencia, UUID acertoId,
            BigDecimal total, List<FormaPaga> formas) {
        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        jdbc.update("""
                INSERT INTO corona.recibos_consignado (id, vendedor_id, cliente_id, semana_id, sequencia, acerto_id, pago_em, total,
                    cotacao_id, dispositivo_id, hora_ajustada) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, ctx.vendedorId(),
                clienteId, semanaId, sequencia, acertoId, Timestamp.from(ctx.quando()), total, cotacaoDoDia(ctx.quando()),
                ctx.aparelho(), ctx.horaAjustada());
        for (FormaPaga f : formas) {
            jdbc.update("INSERT INTO corona.formas_recibo_consignado (id, recibo_id, forma, valor) VALUES (?, ?, ?, ?)", UuidV7.gerar(),
                    id, f.forma(), f.valor());
        }
    }

    // --- estorno do recibo ---

    @Transactional
    public boolean estornar(ContextoDoConsignado ctx, EstornoDoRecibo pedido) {
        if (pedido == null || pedido.reciboId() == null) {
            throw Validacoes.recusar("reciboId", "Informe o recibo.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.MOTIVO_OBRIGATORIO);
        }
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT cliente_id, acerto_id, estornado_em, estorno_id FROM corona.recibos_consignado WHERE id = ? AND vendedor_id = ?",
                pedido.reciboId(), ctx.vendedorId());
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.RECIBO_NAO_ENCONTRADO);
        }
        UUID cliente = (UUID) r.get(0).get("cliente_id");
        debitos.travarCliente(cliente);
        // Relê com o cliente travado: dois estornos juntos nunca aplicam duas vezes.
        r = jdbc.queryForList("SELECT acerto_id, estornado_em, estorno_id FROM corona.recibos_consignado WHERE id = ?", pedido.reciboId());
        if (r.get(0).get("estornado_em") != null) {
            if (id.equals(r.get(0).get("estorno_id"))) {
                return true;
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECIBO_JA_ESTORNADO_DO_CONSIGNADO);
        }
        if (r.get(0).get("acerto_id") != null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.RECIBO_DO_ACERTO);
        }
        List<UUID> tocadas = new ArrayList<>();
        estornarRecibo(pedido.reciboId(), id, motivo, ctx.quando(), tocadas);
        debitos.reaplicarSobras(cliente, tocadas);
        debitos.reavaliar(tocadas);
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "RECIBO_CONSIGNADO_ESTORNADO", "recibos_consignado", pedido.reciboId(), null,
                Map.of("estorno_id", id.toString(), "motivo", motivo));
        return false;
    }

    /** Marca o recibo como estornado e anula as aplicações dele (o cliente já está travado). */
    void estornarRecibo(UUID reciboId, UUID estornoId, String motivo, Instant quando, List<UUID> tocadas) {
        debitos.estornarAplicacoesDoRecibo(reciboId, tocadas);
        jdbc.update("UPDATE corona.recibos_consignado SET estornado_em = ?, estorno_id = ?, motivo_estorno = ? WHERE id = ?",
                Timestamp.from(quando), estornoId, motivo, reciboId);
    }

    private void semanaDoVendedor(ContextoDoConsignado ctx, UUID semanaId) {
        Boolean existe = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.semanas_vendedor WHERE id = ? AND vendedor_id = ?)",
                Boolean.class, semanaId, ctx.vendedorId());
        if (!Boolean.TRUE.equals(existe)) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.SEMANA_NAO_ENCONTRADA);
        }
    }

    private UUID cotacaoDoDia(Instant quando) {
        LocalDate dia = LocalDate.ofInstant(quando, BRASILIA);
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), dia).stream().findFirst().orElse(null);
    }
}
