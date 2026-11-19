package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Resultado;
import br.com.coronasemijoias.api.consignado.CalculoDoAcertoDaNota.LinhaDaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.NotaLida;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.FormaPaga;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O acerto da nota e o estorno dele (ADR-0016, itens 8 e 12). O app manda só o que aconteceu no balcão (o que voltou, a
 * comissão deste acerto e o que o cliente pagou); o SERVIDOR recalcula tudo a partir dos itens da nota e das pendências que
 * ele conhece (nunca confia no app), grava o acerto (o débito do cliente), os itens, as devoluções à maleta, o recibo e as
 * aplicações, e reavalia a situação das notas tocadas. O cliente é travado antes de ler os débitos.
 */
@Service
public class ServicoDeAcertosDaNota {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    public record Devolvido(UUID itemAcertoId, UUID produtoId, UUID remessaId, int quantidade, UUID movimentoId) {
    }

    public record ReciboDoAcerto(UUID id, Integer sequencia, List<FormaPaga> formas) {
    }

    /** Os totais que o computador viu, só para comparar: vale sempre o do servidor. */
    public record Visto(BigDecimal valorVd, BigDecimal comissao, BigDecimal bruto, BigDecimal pendenciasAnteriores,
            BigDecimal totalAReceber, BigDecimal totalPago, BigDecimal ficouDevendo) {
    }

    public record NovoAcerto(UUID id, UUID notaId, UUID semanaId, BigDecimal comissaoPercentual, List<Devolvido> devolvidos,
            ReciboDoAcerto recibo, Visto visto) {
    }

    public record EstornoDoAcerto(UUID id, UUID acertoId, String motivo) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;
    private final ServicoDeAlertasDoConsignado alertas;
    private final DebitosDoCliente debitos;
    private final ServicoDeNotas notas;
    private final ServicoDeRecibos recibos;

    public ServicoDeAcertosDaNota(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem,
            ServicoDeAlertasDoConsignado alertas, DebitosDoCliente debitos, ServicoDeNotas notas, ServicoDeRecibos recibos) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
        this.alertas = alertas;
        this.debitos = debitos;
        this.notas = notas;
        this.recibos = recibos;
    }

    /** @return {@code true} se o acerto já existia com a mesma nota (reenvio inofensivo) */
    @Transactional
    public boolean acertar(ContextoDoConsignado ctx, NovoAcerto pedido) {
        if (pedido == null || pedido.notaId() == null || pedido.semanaId() == null) {
            throw Validacoes.recusar("acerto", "Informe a nota e a semana do acerto.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        VendedorAtivo.exigir(jdbc, ctx.vendedorId());
        NotaLida lida = notas.notaDoVendedor(ctx, pedido.notaId());
        UUID cliente = lida.clienteId();
        debitos.travarCliente(cliente);
        NotaLida nota = notas.travarNotaDoVendedor(ctx, pedido.notaId());

        List<Map<String, Object>> existente = jdbc.queryForList("SELECT nota_id, vendedor_id FROM corona.acertos_nota WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (!nota.id().equals(existente.get(0).get("nota_id")) || !ctx.vendedorId().equals(existente.get(0).get("vendedor_id"))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONSIGNADO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        Boolean temAcerto = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.acertos_nota WHERE nota_id = ? AND estornado_em IS NULL)", Boolean.class, nota.id());
        if (Boolean.TRUE.equals(temAcerto)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NOTA_JA_ACERTADA);
        }
        Boolean semanaDele = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.semanas_vendedor WHERE id = ? AND vendedor_id = ?)",
                Boolean.class, pedido.semanaId(), ctx.vendedorId());
        if (!Boolean.TRUE.equals(semanaDele)) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.SEMANA_NAO_ENCONTRADA);
        }
        if (!CalculoDoAcertoDaNota.comissaoValida(pedido.comissaoPercentual())) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.COMISSAO_FORA_DO_LIMITE);
        }
        BigDecimal pago = pedido.recibo() == null ? BigDecimal.ZERO.setScale(2) : ServicoDeRecibos.totalDasFormas(pedido.recibo().formas());
        if (pedido.recibo() != null && (pedido.recibo().sequencia() == null || pedido.recibo().sequencia() < 1)) {
            throw Validacoes.recusar("recibo", "Informe o número do recibo.");
        }

        // As linhas da nota (DX) e o que voltou (DV): o servidor completa as linhas sem devolução com DV 0.
        List<Map<String, Object>> linhas = notas.linhasDaNota(nota.id());
        Map<String, Integer> dvPorLinha = new LinkedHashMap<>();
        Map<String, Devolvido> devolucaoPorLinha = new LinkedHashMap<>();
        for (Devolvido d : pedido.devolvidos() == null ? List.<Devolvido>of() : pedido.devolvidos()) {
            if (d == null || d.produtoId() == null || d.remessaId() == null || d.quantidade() < 1 || d.movimentoId() == null) {
                throw Validacoes.recusar("devolvidos", "Confira as peças devolvidas.");
            }
            String chave = d.remessaId() + ":" + d.produtoId();
            dvPorLinha.merge(chave, d.quantidade(), Integer::sum);
            devolucaoPorLinha.putIfAbsent(chave, d);
        }
        List<LinhaDaNota> paraCalculo = new ArrayList<>();
        for (Map<String, Object> l : linhas) {
            String chave = l.get("remessa_id") + ":" + l.get("produto_id");
            int dx = ((Number) l.get("dx")).intValue();
            int dv = dvPorLinha.getOrDefault(chave, 0);
            if (dv > dx) {
                throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.DEVOLUCAO_MAIOR_QUE_O_LEVADO);
            }
            paraCalculo.add(new LinhaDaNota(dx, dv, (BigDecimal) l.get("preco")));
        }
        for (String chave : dvPorLinha.keySet()) {
            boolean naNota = linhas.stream().anyMatch(l -> chave.equals(l.get("remessa_id") + ":" + l.get("produto_id")));
            if (!naNota) {
                throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.DEVOLUCAO_MAIOR_QUE_O_LEVADO);
            }
        }

        List<UUID> tocadas = new ArrayList<>();
        debitos.reaplicarSobras(cliente, tocadas);
        BigDecimal pendencias = debitos.pendenciaDoCliente(cliente);
        var calculo = CalculoDoAcertoDaNota.calcular(paraCalculo, pedido.comissaoPercentual(), pendencias, pago);
        if (pedido.visto() != null && diverge(pedido.visto(), calculo)) {
            alertas.registrar("VALOR_DO_ACERTO_RECALCULADO", ctx.vendedorId(), "acertos_nota", id, Map.of(
                    "visto_vd", String.valueOf(pedido.visto().valorVd()), "servidor_vd", calculo.valorVd().toPlainString(),
                    "visto_total_a_receber", String.valueOf(pedido.visto().totalAReceber()),
                    "servidor_total_a_receber", calculo.totalAReceber().toPlainString()));
        }

        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        UUID reciboId = pedido.recibo() == null ? null : pedido.recibo().id();
        if (pedido.recibo() != null) {
            Validacoes.idDoPedido(reciboId);
        }
        jdbc.update("""
                INSERT INTO corona.acertos_nota (id, nota_id, vendedor_id, semana_id, acertado_em, comissao_percentual, itens_dx, itens_dv,
                    itens_vd, valor_dx, valor_dv, valor_vd, comissao, bruto, pendencias_anteriores, total_a_receber, total_pago,
                    ficou_devendo, recibo_id, cotacao_id, dispositivo_id, hora_ajustada)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, nota.id(), ctx.vendedorId(),
                pedido.semanaId(), Timestamp.from(ctx.quando()), pedido.comissaoPercentual(), calculo.itensDx(), calculo.itensDv(),
                calculo.itensVd(), calculo.valorDx(), calculo.valorDv(), calculo.valorVd(), calculo.comissao(), calculo.bruto(),
                calculo.pendenciasAnteriores(), calculo.totalAReceber(), calculo.totalPago(), calculo.ficouDevendo(), reciboId,
                cotacaoDoDia(ctx.quando()), ctx.aparelho(), ctx.horaAjustada());
        for (Map<String, Object> l : linhas) {
            String chave = l.get("remessa_id") + ":" + l.get("produto_id");
            int dx = ((Number) l.get("dx")).intValue();
            int dv = dvPorLinha.getOrDefault(chave, 0);
            Devolvido d = devolucaoPorLinha.get(chave);
            UUID itemId = d != null && d.itemAcertoId() != null ? d.itemAcertoId() : UuidV7.gerar();
            jdbc.update("""
                    INSERT INTO corona.itens_acerto_nota (id, acerto_id, produto_id, remessa_id, qtd_dx, qtd_dv, preco_unitario)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", itemId, id, l.get("produto_id"), l.get("remessa_id"), dx, dv, l.get("preco"));
            if (dv > 0) {
                // Um CONSIGNADO_RETORNO por linha com DV > 0: a peça volta à maleta de onde saiu.
                UUID movimento = d.movimentoId();
                jdbc.update("""
                        INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, dispositivo_id, nota_id,
                            acerto_nota_id) VALUES (?, ?, ?, 'CONSIGNADO_RETORNO', ?, ?, ?, ?)""", movimento, l.get("remessa_id"),
                        l.get("produto_id"), dv, ctx.aparelho(), nota.id(), id);
            }
        }
        if (pedido.recibo() != null) {
            notas.reservarNumero(ctx, pedido.semanaId(), pedido.recibo().sequencia(), "RECIBO", reciboId);
            recibos.gravarRecibo(ctx, reciboId, cliente, pedido.semanaId(), pedido.recibo().sequencia(), id, pago,
                    pedido.recibo().formas());
            Resultado r = debitos.aplicar(reciboId, cliente, pago, tocadas);
            if (r.sobra().signum() > 0) {
                alertas.registrar("PAGAMENTO_ACIMA_DO_DEVIDO", ctx.vendedorId(), "recibos_consignado", reciboId,
                        Map.of("total", pago.toPlainString(), "sobra", r.sobra().toPlainString()));
            }
        }
        debitos.reaplicarSobras(cliente, tocadas);
        tocadas.add(nota.id());
        debitos.reavaliar(tocadas);
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "ACERTO_NOTA_REGISTRADO", "acertos_nota", id, null,
                Map.of("nota_id", nota.id().toString(), "bruto", calculo.bruto().toPlainString(), "total_pago",
                        calculo.totalPago().toPlainString(), "ficou_devendo", calculo.ficouDevendo().toPlainString()));
        return false;
    }

    private static boolean diverge(Visto v, CalculoDoAcertoDaNota.Resultado c) {
        return !igual(v.valorVd(), c.valorVd()) || !igual(v.comissao(), c.comissao()) || !igual(v.bruto(), c.bruto())
                || !igual(v.pendenciasAnteriores(), c.pendenciasAnteriores()) || !igual(v.totalAReceber(), c.totalAReceber())
                || !igual(v.totalPago(), c.totalPago()) || !igual(v.ficouDevendo(), c.ficouDevendo());
    }

    private static boolean igual(BigDecimal visto, BigDecimal servidor) {
        return visto == null || visto.compareTo(servidor) == 0;
    }

    // --- estorno do acerto ---

    /** Estorna o acerto: desfaz os retornos à maleta, o recibo dele e as aplicações de outros recibos no débito dele. */
    @Transactional
    public boolean estornar(ContextoDoConsignado ctx, EstornoDoAcerto pedido) {
        if (pedido == null || pedido.acertoId() == null) {
            throw Validacoes.recusar("acertoId", "Informe o acerto.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.MOTIVO_OBRIGATORIO);
        }
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT a.nota_id, a.recibo_id, n.cliente_id FROM corona.acertos_nota a JOIN corona.notas_consignado n ON n.id = a.nota_id
                 WHERE a.id = ? AND a.vendedor_id = ?""", pedido.acertoId(), ctx.vendedorId());
        if (a.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
        }
        UUID cliente = (UUID) a.get(0).get("cliente_id");
        UUID notaId = (UUID) a.get(0).get("nota_id");
        UUID reciboId = (UUID) a.get(0).get("recibo_id");
        debitos.travarCliente(cliente);
        notas.travarNotaDoVendedor(ctx, notaId);
        Map<String, Object> atual = jdbc.queryForMap("SELECT estornado_em, estorno_id FROM corona.acertos_nota WHERE id = ?", pedido.acertoId());
        if (atual.get("estornado_em") != null) {
            if (id.equals(atual.get("estorno_id"))) {
                return true;
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_JA_ESTORNADO);
        }
        List<UUID> tocadas = new ArrayList<>();
        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        // 1. As peças voltam a ficar com o cliente: estorno de cada retorno do acerto.
        List<Map<String, Object>> retornos = jdbc.queryForList("""
                SELECT m.id, m.remessa_id, m.produto_id, m.quantidade FROM corona.movimentos_remessa m
                 WHERE m.acerto_nota_id = ? AND m.estorna_id IS NULL
                   AND NOT EXISTS (SELECT 1 FROM corona.movimentos_remessa x WHERE x.estorna_id = m.id)""", pedido.acertoId());
        for (Map<String, Object> m : retornos) {
            jdbc.update("""
                    INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, dispositivo_id, nota_id, estorna_id)
                    VALUES (?, ?, ?, 'CONSIGNADO_RETORNO', ?, ?, ?, ?)""", UuidV7.gerar(), m.get("remessa_id"), m.get("produto_id"),
                    -((Number) m.get("quantidade")).intValue(), ctx.aparelho(), notaId, m.get("id"));
        }
        // 2. O recibo do acerto e as aplicações dele (reabrem as pendências antigas que ele quitou).
        if (reciboId != null) {
            recibos.estornarRecibo(reciboId, id, motivo, ctx.quando(), tocadas);
        }
        // 3. As aplicações de outros recibos neste débito voltam ao "a aplicar" do recibo de origem.
        debitos.estornarAplicacoesNoDebito(pedido.acertoId(), tocadas);
        // 4. Marca o acerto.
        jdbc.update("UPDATE corona.acertos_nota SET estornado_em = ?, estorno_id = ?, motivo_estorno = ? WHERE id = ?",
                Timestamp.from(ctx.quando()), id, motivo, pedido.acertoId());
        // 5. O valor liberado entra no próximo débito; reavalia todas as notas do cliente.
        debitos.reaplicarSobras(cliente, tocadas);
        tocadas.addAll(debitos.notasDoCliente(cliente));
        debitos.reavaliar(tocadas);
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "ACERTO_NOTA_ESTORNADO", "acertos_nota", pedido.acertoId(), null,
                Map.of("estorno_id", id.toString(), "motivo", motivo));
        return false;
    }

    private UUID cotacaoDoDia(Instant quando) {
        LocalDate dia = LocalDate.ofInstant(quando, BRASILIA);
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), dia).stream().findFirst().orElse(null);
    }
}
