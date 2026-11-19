package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Conferencia;
import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Diferenca;
import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Unidade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * As conciliações do consignado ao cliente (ADR-0016, item 17; §13): rede de segurança, só leitura, num retrato só do banco, na
 * forma das da Fase 7 (a mesma {@link Conferencia} e {@link Diferenca}). Nunca corrige nada. K1 situação gravada da nota = a
 * recalculada; K2 totais do acerto = os recalculados dos itens; K3 aplicações ativas de cada débito dentro de 0 e o bruto; K4
 * recibo: total = formas e aplicações dentro do total; K5 disponível da maleta não negativo (ou alerta já registrado); K6 cada
 * devolução à maleta tem o DV do acerto; K7 uma semana aberta por representante.
 */
@Service
public class ConciliacoesDoConsignado {

    public static final List<String> CHAVES = List.of("K1", "K2", "K3", "K4", "K5", "K6", "K7");

    public record Resultado(Instant geradaEm, boolean tudoCerto, List<Conferencia> conferencias) {
    }

    private final JdbcTemplate jdbc;

    public ConciliacoesDoConsignado(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = RuntimeException.class)
    public Resultado conferir() {
        return resultado(CHAVES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = RuntimeException.class)
    public Resultado conferir(String chave) {
        String limpa = chave == null ? "" : chave.strip().toUpperCase(Locale.ROOT);
        if (!CHAVES.contains(limpa)) {
            throw Validacoes.recusar("chave", "Escolha uma das conferências da lista: K1, K2, K3, K4, K5, K6 ou K7.");
        }
        return resultado(List.of(limpa));
    }

    private Resultado resultado(List<String> chaves) {
        List<Conferencia> lista = new ArrayList<>();
        for (String c : chaves) {
            lista.add(switch (c) {
                case "K1" -> k1();
                case "K2" -> k2();
                case "K3" -> k3();
                case "K4" -> k4();
                case "K5" -> k5();
                case "K6" -> k6();
                default -> k7();
            });
        }
        return new Resultado(Instant.now(), lista.stream().allMatch(x -> x.divergencias().isEmpty()), lista);
    }

    private Conferencia k1() {
        List<Diferenca> d = jdbc.query("""
                WITH calc AS (
                  SELECT n.id, n.situacao AS gravada,
                         CASE WHEN a.id IS NULL THEN CASE WHEN EXISTS (SELECT 1 FROM corona.remarcacoes_nota r WHERE r.nota_id = n.id)
                                                          THEN 'REMARCADO' ELSE 'EM_ABERTO' END
                              WHEN a.bruto - coalesce((SELECT sum(ap.valor) FROM corona.aplicacoes_recibo ap WHERE ap.acerto_id = a.id), 0) > 0
                                   THEN 'PENDENTE' ELSE 'FECHADO' END AS esperada
                    FROM corona.notas_consignado n
                    LEFT JOIN corona.acertos_nota a ON a.nota_id = n.id AND a.estornado_em IS NULL)
                SELECT id, gravada, esperada FROM calc WHERE gravada <> esperada""",
                (rs, i) -> new Diferenca("NOTA", rs.getObject(1, UUID.class),
                        "Situação gravada " + rs.getString(2) + ", recalculada " + rs.getString(3), BigDecimal.ZERO, BigDecimal.ONE,
                        Unidade.REGISTROS));
        return conferencia("K1", "Situação da nota", "A situação gravada de cada nota é a que se recalcula dos fatos.",
                contar("corona.notas_consignado"), d);
    }

    private Conferencia k2() {
        List<Diferenca> d = jdbc.query("""
                SELECT a.id, a.valor_dx, x.dx, a.valor_dv, x.dv, a.itens_dx, x.idx, a.itens_dv, x.idv
                  FROM corona.acertos_nota a
                  JOIN (SELECT acerto_id, sum(qtd_dx * preco_unitario) AS dx, sum(qtd_dv * preco_unitario) AS dv, sum(qtd_dx) AS idx,
                               sum(qtd_dv) AS idv FROM corona.itens_acerto_nota GROUP BY acerto_id) x ON x.acerto_id = a.id
                 WHERE a.valor_dx <> x.dx OR a.valor_dv <> x.dv OR a.itens_dx <> x.idx OR a.itens_dv <> x.idv""",
                (rs, i) -> new Diferenca("ACERTO", rs.getObject(1, UUID.class), "Totais do acerto diferentes dos itens",
                        rs.getBigDecimal(3), rs.getBigDecimal(2), Unidade.REAIS));
        d.addAll(jdbc.query("""
                SELECT a.id FROM corona.acertos_nota a
                 WHERE NOT EXISTS (SELECT 1 FROM corona.itens_acerto_nota i WHERE i.acerto_id = a.id)""",
                (rs, i) -> new Diferenca("ACERTO", rs.getObject(1, UUID.class), "Acerto sem itens", BigDecimal.ONE, BigDecimal.ZERO,
                        Unidade.REGISTROS)));
        return conferencia("K2", "Totais do acerto", "Os totais gravados do acerto batem com os itens dele.",
                contar("corona.acertos_nota"), d);
    }

    private Conferencia k3() {
        List<Diferenca> d = jdbc.query("""
                SELECT a.id, a.bruto, coalesce(s.soma, 0) FROM corona.acertos_nota a
                  LEFT JOIN (SELECT acerto_id, sum(valor) AS soma FROM corona.aplicacoes_recibo GROUP BY acerto_id) s ON s.acerto_id = a.id
                 WHERE coalesce(s.soma, 0) > a.bruto OR coalesce(s.soma, 0) < 0""",
                (rs, i) -> new Diferenca("DEBITO", rs.getObject(1, UUID.class), "Aplicações do débito fora de 0 e do bruto",
                        rs.getBigDecimal(2), rs.getBigDecimal(3), Unidade.REAIS));
        return conferencia("K3", "Aplicações dos débitos", "A soma das aplicações de cada débito fica entre zero e o bruto do acerto.",
                contar("corona.acertos_nota"), d);
    }

    private Conferencia k4() {
        List<Diferenca> d = jdbc.query("""
                SELECT r.id, r.total, coalesce(f.soma, 0) FROM corona.recibos_consignado r
                  LEFT JOIN (SELECT recibo_id, sum(valor) AS soma FROM corona.formas_recibo_consignado GROUP BY recibo_id) f ON f.recibo_id = r.id
                 WHERE r.total <> coalesce(f.soma, 0)""",
                (rs, i) -> new Diferenca("RECIBO", rs.getObject(1, UUID.class), "Total do recibo diferente da soma das formas",
                        rs.getBigDecimal(2), rs.getBigDecimal(3), Unidade.REAIS));
        d.addAll(jdbc.query("""
                SELECT r.id, r.total, coalesce(a.soma, 0), r.estornado_em IS NOT NULL FROM corona.recibos_consignado r
                  LEFT JOIN (SELECT recibo_id, sum(valor) AS soma FROM corona.aplicacoes_recibo GROUP BY recibo_id) a ON a.recibo_id = r.id
                 WHERE (r.estornado_em IS NULL AND (coalesce(a.soma, 0) > r.total OR coalesce(a.soma, 0) < 0))
                    OR (r.estornado_em IS NOT NULL AND coalesce(a.soma, 0) <> 0)""",
                (rs, i) -> new Diferenca("RECIBO", rs.getObject(1, UUID.class),
                        rs.getBoolean(4) ? "Recibo estornado com aplicações ativas" : "Aplicações do recibo passam do total",
                        rs.getBoolean(4) ? BigDecimal.ZERO : rs.getBigDecimal(2), rs.getBigDecimal(3), Unidade.REAIS)));
        return conferencia("K4", "Recibos", "O total do recibo é a soma das formas, e as aplicações cabem no total (a sobra é o \"a aplicar\").",
                contar("corona.recibos_consignado"), d);
    }

    private Conferencia k5() {
        List<Diferenca> d = jdbc.query("""
                SELECT i.remessa_id, i.produto_id,
                       i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) AS disponivel
                  FROM corona.itens_remessa i JOIN corona.remessas r ON r.id = i.remessa_id
                 WHERE i.deleted_at IS NULL AND r.status IN ('ENVIADA', 'CONFERIDA', 'FECHADA')
                   AND i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) < 0
                   AND NOT EXISTS (SELECT 1 FROM corona.alertas_consignado a
                                    WHERE a.tipo = 'PECA_SEM_SALDO_NA_MALETA' AND a.detalhe ->> 'remessa_id' = i.remessa_id::text
                                      AND a.detalhe ->> 'produto_id' = i.produto_id::text)""",
                (rs, i) -> new Diferenca("MALETA", rs.getObject(1, UUID.class),
                        "A peça " + rs.getObject(2, UUID.class) + " está negativa na maleta e sem alerta", BigDecimal.ZERO,
                        BigDecimal.valueOf(rs.getLong(3)), Unidade.PECAS));
        return conferencia("K5", "Saldo das maletas", "Nenhuma peça fica negativa na maleta sem um alerta registrado.",
                contar("corona.itens_remessa"), d);
    }

    private Conferencia k6() {
        List<Diferenca> d = jdbc.query("""
                SELECT a.id, i.produto_id, i.qtd_dv, coalesce(m.soma, 0)
                  FROM corona.itens_acerto_nota i JOIN corona.acertos_nota a ON a.id = i.acerto_id AND a.estornado_em IS NULL
                  LEFT JOIN (SELECT acerto_nota_id, remessa_id, produto_id, sum(quantidade) AS soma FROM corona.movimentos_remessa
                              WHERE acerto_nota_id IS NOT NULL OR estorna_id IS NOT NULL GROUP BY acerto_nota_id, remessa_id, produto_id) m
                         ON m.acerto_nota_id = a.id AND m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id
                 WHERE i.qtd_dv <> coalesce(m.soma, 0)""",
                (rs, n) -> new Diferenca("ACERTO", rs.getObject(1, UUID.class),
                        "A devolução da peça " + rs.getObject(2, UUID.class) + " não bate com o que voltou à maleta",
                        BigDecimal.valueOf(rs.getLong(3)), BigDecimal.valueOf(rs.getLong(4)), Unidade.PECAS));
        d.addAll(jdbc.query("""
                SELECT m.id, m.quantidade FROM corona.movimentos_remessa m LEFT JOIN corona.acertos_nota a ON a.id = m.acerto_nota_id
                 WHERE m.acerto_nota_id IS NOT NULL AND (a.id IS NULL OR a.estornado_em IS NOT NULL)
                   AND NOT EXISTS (SELECT 1 FROM corona.movimentos_remessa x WHERE x.estorna_id = m.id)""",
                (rs, n) -> new Diferenca("MOVIMENTO", rs.getObject(1, UUID.class), "Devolução de um acerto estornado que não foi desfeita",
                        BigDecimal.ZERO, BigDecimal.valueOf(rs.getLong(2)), Unidade.PECAS)));
        return conferencia("K6", "Devoluções à maleta", "Cada peça que voltou à maleta no acerto tem o DV do acerto ativo.",
                contar("corona.itens_acerto_nota"), d);
    }

    private Conferencia k7() {
        List<Diferenca> d = jdbc.query("""
                SELECT vendedor_id, count(*) FROM corona.semanas_vendedor WHERE situacao = 'ABERTA' AND deleted_at IS NULL
                 GROUP BY vendedor_id HAVING count(*) > 1""",
                (rs, i) -> new Diferenca("VENDEDOR", rs.getObject(1, UUID.class), "Mais de uma semana aberta", BigDecimal.ONE,
                        BigDecimal.valueOf(rs.getLong(2)), Unidade.REGISTROS));
        return conferencia("K7", "Semana aberta", "Cada representante tem no máximo uma semana aberta.",
                contar("corona.semanas_vendedor"), d);
    }

    private long contar(String tabela) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + tabela, Long.class);
        return n == null ? 0 : n;
    }

    private static Conferencia conferencia(String chave, String titulo, String explicacao, long conferidos, List<Diferenca> d) {
        return new Conferencia(chave, titulo, explicacao, conferidos, d);
    }
}
