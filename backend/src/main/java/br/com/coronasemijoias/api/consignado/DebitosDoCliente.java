package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Aplicacao;
import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Debito;
import br.com.coronasemijoias.api.consignado.AplicacaoDePagamento.Resultado;
import br.com.coronasemijoias.api.consignado.SituacaoDaNota.Situacao;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Os débitos do cliente e o que se faz com eles (ADR-0016, item 10): o débito é o acerto (valor = bruto); o saldo é o bruto
 * menos as aplicações de recibo; a pendência do cliente é a soma dos saldos. Aqui moram a leitura dos débitos na ordem do
 * mais antigo para o mais novo, a gravação das aplicações, o "a aplicar" dos recibos e a situação gravada de cada nota. O
 * cliente é TRAVADO por quem chama ({@link #travarCliente}) antes de ler os débitos.
 */
@Component
public class DebitosDoCliente {

    /** Um débito com o saldo e a nota de origem. */
    public record DebitoDaNota(UUID acertoId, UUID notaId, BigDecimal bruto, BigDecimal saldo) {
    }

    private final JdbcTemplate jdbc;

    public DebitosDoCliente(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Trava a linha do cliente: nenhum outro envio mexe nos débitos dele até o fim da transação. */
    public void travarCliente(UUID clienteId) {
        jdbc.queryForList("SELECT id FROM corona.clientes WHERE id = ? FOR UPDATE", UUID.class, clienteId);
    }

    /** Os débitos com saldo, do mais antigo (acerto, depois semana e sequência da nota) para o mais novo. */
    public List<DebitoDaNota> comSaldo(UUID clienteId) {
        return jdbc.query("""
                SELECT a.id, a.nota_id, a.bruto,
                       a.bruto - coalesce((SELECT sum(ap.valor) FROM corona.aplicacoes_recibo ap WHERE ap.acerto_id = a.id), 0) AS saldo
                  FROM corona.acertos_nota a
                  JOIN corona.notas_consignado n ON n.id = a.nota_id
                  JOIN corona.semanas_vendedor s ON s.id = n.semana_id
                 WHERE n.cliente_id = ? AND a.estornado_em IS NULL
                 ORDER BY a.acertado_em, s.numero, n.sequencia, a.id""",
                (rs, i) -> new DebitoDaNota(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)), clienteId).stream().filter(d -> d.saldo().signum() > 0).toList();
    }

    /** A pendência do cliente: a soma dos saldos dos débitos. */
    public BigDecimal pendenciaDoCliente(UUID clienteId) {
        return Dinheiro.somar(comSaldo(clienteId).stream().map(DebitoDaNota::saldo).toList());
    }

    /** O saldo de um débito (bruto − aplicações), mesmo que zero. */
    public BigDecimal saldoDoDebito(UUID acertoId) {
        return jdbc.queryForObject("""
                SELECT a.bruto - coalesce((SELECT sum(ap.valor) FROM corona.aplicacoes_recibo ap WHERE ap.acerto_id = a.id), 0)
                  FROM corona.acertos_nota a WHERE a.id = ?""", BigDecimal.class, acertoId);
    }

    /** Aplica um valor do recibo nos débitos do cliente, do mais antigo ao mais novo; devolve as notas tocadas e a sobra. */
    public Resultado aplicar(UUID reciboId, UUID clienteId, BigDecimal valor, List<UUID> notasTocadas) {
        List<DebitoDaNota> lista = comSaldo(clienteId);
        List<Debito> debitos = lista.stream().map(d -> new Debito(d.acertoId(), d.saldo())).toList();
        Resultado r = AplicacaoDePagamento.aplicar(debitos, valor);
        for (Aplicacao a : r.aplicacoes()) {
            jdbc.update("INSERT INTO corona.aplicacoes_recibo (id, recibo_id, acerto_id, valor) VALUES (?, ?, ?, ?)", UuidV7.gerar(),
                    reciboId, a.debitoId(), a.valor());
            lista.stream().filter(d -> d.acertoId().equals(a.debitoId())).findFirst().ifPresent(d -> notasTocadas.add(d.notaId()));
        }
        return r;
    }

    /** O que o recibo ainda não aplicou: total − Σ aplicações (o "a aplicar"). Recibo estornado não tem. */
    public BigDecimal aAplicar(UUID reciboId) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN r.estornado_em IS NOT NULL THEN 0
                            ELSE r.total - coalesce((SELECT sum(ap.valor) FROM corona.aplicacoes_recibo ap WHERE ap.recibo_id = r.id), 0) END
                  FROM corona.recibos_consignado r WHERE r.id = ?""", BigDecimal.class, reciboId);
    }

    /**
     * Todo "a aplicar" de recibos do cliente entra no débito mais antigo com saldo (ADR-0016, item 10; dúvida D6): chamado no
     * fim de cada acerto, pagamento e estorno. Os recibos mais antigos primeiro.
     */
    public void reaplicarSobras(UUID clienteId, List<UUID> notasTocadas) {
        List<UUID> recibos = jdbc.queryForList(
                "SELECT id FROM corona.recibos_consignado WHERE cliente_id = ? AND estornado_em IS NULL ORDER BY pago_em, id",
                UUID.class, clienteId);
        for (UUID recibo : recibos) {
            BigDecimal sobra = aAplicar(recibo);
            if (sobra.signum() > 0 && !comSaldo(clienteId).isEmpty()) {
                aplicar(recibo, clienteId, sobra, notasTocadas);
            }
        }
    }

    /** Desfaz (com aplicações negativas) as aplicações ativas do recibo. Devolve as notas dos débitos que voltam a dever. */
    public void estornarAplicacoesDoRecibo(UUID reciboId, List<UUID> notasTocadas) {
        negar("""
                SELECT ap.id, ap.recibo_id, ap.acerto_id, ap.valor, a.nota_id FROM corona.aplicacoes_recibo ap
                  JOIN corona.acertos_nota a ON a.id = ap.acerto_id
                 WHERE ap.recibo_id = ? AND ap.valor > 0
                   AND NOT EXISTS (SELECT 1 FROM corona.aplicacoes_recibo x WHERE x.estorna_id = ap.id)""", reciboId, notasTocadas);
    }

    /** Desfaz as aplicações de qualquer recibo no débito do acerto (o dinheiro volta ao "a aplicar" do recibo de origem). */
    public void estornarAplicacoesNoDebito(UUID acertoId, List<UUID> notasTocadas) {
        negar("""
                SELECT ap.id, ap.recibo_id, ap.acerto_id, ap.valor, a.nota_id FROM corona.aplicacoes_recibo ap
                  JOIN corona.acertos_nota a ON a.id = ap.acerto_id
                 WHERE ap.acerto_id = ? AND ap.valor > 0
                   AND NOT EXISTS (SELECT 1 FROM corona.aplicacoes_recibo x WHERE x.estorna_id = ap.id)""", acertoId, notasTocadas);
    }

    private void negar(String sql, UUID chave, List<UUID> notasTocadas) {
        record Linha(UUID id, UUID recibo, UUID acerto, BigDecimal valor, UUID nota) {
        }
        List<Linha> linhas = jdbc.query(sql, (rs, i) -> new Linha(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBigDecimal(4), rs.getObject(5, UUID.class)), chave);
        for (Linha l : linhas) {
            jdbc.update("INSERT INTO corona.aplicacoes_recibo (id, recibo_id, acerto_id, valor, estorna_id) VALUES (?, ?, ?, ?, ?)",
                    UuidV7.gerar(), l.recibo(), l.acerto(), l.valor().negate(), l.id());
            notasTocadas.add(l.nota());
        }
    }

    /** Grava a situação (recalculada dos fatos) de cada nota, se mudou. */
    public void reavaliar(List<UUID> notas) {
        for (UUID nota : new java.util.LinkedHashSet<>(notas)) {
            reavaliar(nota);
        }
    }

    public Situacao reavaliar(UUID notaId) {
        UUID acerto = jdbc.queryForList("SELECT id FROM corona.acertos_nota WHERE nota_id = ? AND estornado_em IS NULL", UUID.class,
                notaId).stream().findFirst().orElse(null);
        boolean remarcada = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.remarcacoes_nota WHERE nota_id = ?)", Boolean.class, notaId));
        Situacao situacao = SituacaoDaNota.calcular(acerto != null, remarcada, acerto == null ? null : saldoDoDebito(acerto));
        jdbc.update("UPDATE corona.notas_consignado SET situacao = ? WHERE id = ? AND situacao <> ?", situacao.name(), notaId,
                situacao.name());
        return situacao;
    }

    /** Todas as notas do cliente (para reavaliar tudo depois de um estorno). */
    public List<UUID> notasDoCliente(UUID clienteId) {
        return new ArrayList<>(jdbc.queryForList("SELECT id FROM corona.notas_consignado WHERE cliente_id = ?", UUID.class, clienteId));
    }
}
