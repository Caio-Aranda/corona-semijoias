package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A taxa de devolução por vendedor (tarefa 6.4; ADR-0014, item 12; dúvida P9), calculada na hora: nos últimos 28 dias de
 * Brasília até hoje, o devolvido (devoluções não canceladas, pelo dia delas) sobre o vendido (vendas concluídas, pelo dia
 * delas). Passa do percentual quando {@code devolvido × 100 > percentual × vendido} (conta exata, sem arredondar), ou
 * quando há devolução sem venda na janela. Só vendedores ativos.
 */
@Component
public class TaxaDeDevolucao {

    /** Dias da janela, contando hoje ("últimas 4 semanas"). */
    static final int DIAS_DA_JANELA = 28;

    /** {@code percentual}: o devolvido sobre o vendido arredondado para a tela (vazio quando nada foi vendido). */
    public record VendedorAcima(UUID vendedorId, String nome, BigDecimal devolvido, BigDecimal vendido, Integer percentual) {
    }

    private final JdbcTemplate jdbc;
    private final Configuracoes configuracoes;
    private final Clock relogio;

    TaxaDeDevolucao(JdbcTemplate jdbc, Configuracoes configuracoes, Clock relogio) {
        this.jdbc = jdbc;
        this.configuracoes = configuracoes;
        this.relogio = relogio;
    }

    /**
     * A porcentagem inteira para a tela, arredondada (meio para cima): (devolvido × 200 + vendido) ÷ (vendido × 2), parte
     * inteira. Só para mostrar; a decisão de avisar usa a conta exata.
     */
    static int porcentagemNaTela(BigDecimal devolvido, BigDecimal vendido) {
        BigDecimal dois = BigDecimal.valueOf(2);
        return devolvido.multiply(BigDecimal.valueOf(200)).add(vendido).divideToIntegralValue(vendido.multiply(dois))
                .intValueExact();
    }

    /** Os vendedores ativos acima do percentual da dona (lido a cada chamada), em ordem de nome. */
    public List<VendedorAcima> acimaDoPercentual() {
        int percentual = configuracoes.inteiro(ChaveDeConfiguracao.PERCENTUAL_ALERTA_DEVOLUCAO);
        LocalDate hoje = RegrasDaDevolucao.dia(relogio.instant());
        Timestamp de = Timestamp.from(hoje.minusDays(DIAS_DA_JANELA - 1L).atStartOfDay(RegrasDaDevolucao.BRASILIA).toInstant());
        Timestamp ate = Timestamp.from(hoje.plusDays(1).atStartOfDay(RegrasDaDevolucao.BRASILIA).toInstant());
        BigDecimal cem = BigDecimal.valueOf(100);
        BigDecimal limite = BigDecimal.valueOf(percentual);
        return jdbc.query("""
                SELECT t.id, t.nome, t.devolvido, t.vendido FROM (
                    SELECT vd.id, vd.nome,
                           (SELECT coalesce(sum(d.valor_devolvido), 0) FROM corona.devolucoes d
                             WHERE d.vendedor_id = vd.id AND d.situacao <> 'CANCELADA' AND d.deleted_at IS NULL
                               AND d.devolvida_em >= ? AND d.devolvida_em < ?) AS devolvido,
                           (SELECT coalesce(sum(v.total), 0) FROM corona.vendas v
                             WHERE v.vendedor_id = vd.id AND v.status = 'CONCLUIDA' AND v.deleted_at IS NULL
                               AND v.vendida_em >= ? AND v.vendida_em < ?) AS vendido
                      FROM corona.vendedores vd
                     WHERE vd.ativo AND vd.deleted_at IS NULL) t
                 WHERE t.devolvido > 0
                 ORDER BY lower(t.nome), t.id""", (rs, n) -> {
                    BigDecimal devolvido = rs.getBigDecimal("devolvido");
                    BigDecimal vendido = rs.getBigDecimal("vendido");
                    Integer naTela = vendido.signum() == 0 ? null : porcentagemNaTela(devolvido, vendido);
                    return new VendedorAcima(rs.getObject("id", UUID.class), rs.getString("nome"), devolvido, vendido, naTela);
                }, de, ate, de, ate).stream()
                .filter(v -> v.vendido().signum() == 0 || v.devolvido().multiply(cem).compareTo(limite.multiply(v.vendido())) > 0)
                .toList();
    }
}
