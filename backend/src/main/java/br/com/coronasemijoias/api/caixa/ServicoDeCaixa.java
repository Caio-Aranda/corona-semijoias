package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * O caixa automático (tarefa 7.6; ADR-0012, item 11): único lugar que grava em {@code lancamentos_caixa}. Só é chamado de dentro
 * da transação de quem gerou o dinheiro (recebimento do vendedor ou pagamento ao vendedor), nunca sozinho ({@code MANDATORY}).
 * O lançamento é imutável; o estorno é um lançamento de sinal oposto ligado ao original. O saldo é sempre a soma. Sem bloqueio
 * por saldo negativo (o aviso é da tarefa 7.7). Tudo em Real; a data do lançamento é o dia do calendário de Brasília.
 */
@Service
public class ServicoDeCaixa {

    static final String CATEGORIA_DO_REPASSE = "Repasse do vendedor";
    static final String CATEGORIA_DO_PAGAMENTO_AO_VENDEDOR = "Pagamento ao vendedor";
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    ServicoDeCaixa(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    /** Entrada de um recebimento confirmado do vendedor: o mesmo valor (positivo) e a mesma forma do recebimento. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID lancarRecebimento(UUID usuarioId, UUID recebimentoId, String forma, BigDecimal valor) {
        exigirPositivo(valor);
        return gravar("ENTRADA", valor, forma, categoria(CATEGORIA_DO_REPASSE), "RECEBIMENTO_VENDEDOR", recebimentoId, null, usuarioId,
                "Recebimento do vendedor");
    }

    /**
     * O estorno do lançamento de um recebimento: o lançamento original é achado pela origem (o recebimento estornado) e o novo
     * tem o sinal oposto, ligado a ele. Um lançamento só pode ser estornado uma vez (o banco garante).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID estornarRecebimento(UUID usuarioId, UUID recebimentoOriginalId, UUID estornoDoRecebimentoId) {
        List<Map<String, Object>> original = jdbc.queryForList("""
                SELECT id, tipo, valor, forma, categoria_id FROM corona.lancamentos_caixa
                 WHERE origem_tipo = 'RECEBIMENTO_VENDEDOR' AND origem_id = ?""", recebimentoOriginalId);
        if (original.isEmpty()) {
            throw new IllegalStateException("O recebimento " + recebimentoOriginalId + " não tem lançamento de caixa.");
        }
        Map<String, Object> o = original.get(0);
        return gravar((String) o.get("tipo"), ((BigDecimal) o.get("valor")).negate(), (String) o.get("forma"), (UUID) o.get("categoria_id"),
                "RECEBIMENTO_VENDEDOR", estornoDoRecebimentoId, (UUID) o.get("id"), usuarioId, "Estorno de recebimento do vendedor");
    }

    /** A saída do que o acerto pagou ao vendedor na hora (acerto a favor dele): valor positivo, gravado negativo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID lancarPagamentoAoVendedor(UUID usuarioId, UUID acertoId, String forma, BigDecimal valor) {
        exigirPositivo(valor);
        return gravar("SAIDA", valor.negate(), forma, categoria(CATEGORIA_DO_PAGAMENTO_AO_VENDEDOR), "ACERTO_A_FAVOR_DO_VENDEDOR", acertoId,
                null, usuarioId, "Pagamento ao vendedor no acerto");
    }

    /** O dia do calendário de Brasília de agora (a data dos lançamentos automáticos). */
    public LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }

    private UUID categoria(String nome) {
        return jdbc.queryForObject("SELECT id FROM corona.categorias_caixa WHERE nome = ? AND sistema", UUID.class, nome);
    }

    private UUID gravar(String tipo, BigDecimal valor, String forma, UUID categoriaId, String origemTipo, UUID origemId, UUID estorna,
            UUID usuarioId, String descricao) {
        UUID id = UuidV7.gerar();
        jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento,
                    lancado_em, descricao, estorna_id, registrado_por) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, tipo, valor, forma,
                categoriaId, origemTipo, origemId, hoje(), Timestamp.from(relogio.instant()), descricao, estorna, usuarioId);
        return id;
    }

    private static void exigirPositivo(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0 || valor.scale() > Dinheiro.ESCALA) {
            throw new IllegalArgumentException("O valor do lançamento de caixa precisa ser positivo, em reais, com até duas casas.");
        }
    }
}
