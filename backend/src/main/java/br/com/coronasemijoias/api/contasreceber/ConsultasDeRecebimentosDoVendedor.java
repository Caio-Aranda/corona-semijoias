package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura dos recebimentos do vendedor (tarefa 7.6). A dona vê tudo (inclusive o motivo do estorno e quais contas cada
 * recebimento baixou); o vendedor vê só os dele, sem quem registrou, sem o motivo e sem caixa. Só valores em Real.
 */
@Component
public class ConsultasDeRecebimentosDoVendedor {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final int LIMITE = 1000;

    /** O que um recebimento baixou numa conta. */
    public record ContaBaixada(UUID contaId, BigDecimal valor) {
    }

    /** O recebimento como a dona o vê. {@code estornado}: já tem estorno; {@code estornaId}: este é o estorno de outro. */
    public record RecebimentoDaDona(UUID id, UUID vendedorId, String vendedorNome, String momento, UUID acertoId, String aplicadoEm,
            String forma, BigDecimal valor, Instant recebidoEm, UUID estornaId, String motivo, boolean estornado,
            List<ContaBaixada> contas) {
    }

    /** O recebimento como o vendedor o vê: só o dele, sem quem registrou e sem o motivo do estorno. */
    public record RecebimentoDoVendedor(UUID id, String momento, UUID acertoId, String aplicadoEm, String forma, BigDecimal valor,
            Instant recebidoEm, UUID estornaId, boolean estornado) {
    }

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    ConsultasDeRecebimentosDoVendedor(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    private static final String DA_DONA = """
            SELECT r.id, r.vendedor_id, ved.nome AS vendedor_nome, r.momento, r.acerto_id, r.aplicado_em, r.forma, r.valor, r.recebido_em,
                   r.estorna_id, r.motivo, EXISTS (SELECT 1 FROM corona.recebimentos_vendedor e WHERE e.estorna_id = r.id) AS estornado
              FROM corona.recebimentos_vendedor r JOIN corona.vendedores ved ON ved.id = r.vendedor_id
             WHERE r.deleted_at IS NULL""";

    /** Os recebimentos, do mais novo para o mais antigo. Filtros opcionais: vendedor e período (dias de Brasília, inclusive). */
    public List<RecebimentoDaDona> daDona(UUID vendedorId, LocalDate de, LocalDate ate) {
        List<Object> args = new ArrayList<>();
        StringBuilder onde = new StringBuilder();
        if (vendedorId != null) {
            onde.append(" AND r.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (de != null && ate != null && ate.isBefore(de)) {
            throw Validacoes.recusar("ate", "A data final não pode ser antes da inicial.");
        }
        if (de != null) {
            onde.append(" AND (r.recebido_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            args.add(de);
        }
        if (ate != null) {
            onde.append(" AND (r.recebido_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            args.add(ate);
        }
        List<RecebimentoDaDona> lista = jdbc.query(DA_DONA + onde + " ORDER BY r.recebido_em DESC, r.id LIMIT " + LIMITE,
                (rs, n) -> lerDaDona(rs, List.of()), args.toArray());
        return lista.stream().map(r -> comContas(r)).toList();
    }

    public RecebimentoDaDona verDaDona(UUID id) {
        List<RecebimentoDaDona> r = jdbc.query(DA_DONA + " AND r.id = ?", (rs, n) -> lerDaDona(rs, List.of()), id);
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.RECEBIMENTO_NAO_ENCONTRADO);
        }
        return comContas(r.get(0));
    }

    private RecebimentoDaDona comContas(RecebimentoDaDona r) {
        List<ContaBaixada> contas = jdbc.query("""
                SELECT conta_id, sum(valor) AS valor FROM corona.baixas_receber WHERE recebimento_vendedor_id = ? GROUP BY conta_id
                 ORDER BY conta_id""", (rs, n) -> new ContaBaixada(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), r.id());
        return new RecebimentoDaDona(r.id(), r.vendedorId(), r.vendedorNome(), r.momento(), r.acertoId(), r.aplicadoEm(), r.forma(), r.valor(),
                r.recebidoEm(), r.estornaId(), r.motivo(), r.estornado(), contas);
    }

    private static RecebimentoDaDona lerDaDona(java.sql.ResultSet rs, List<ContaBaixada> contas) throws java.sql.SQLException {
        return new RecebimentoDaDona(rs.getObject("id", UUID.class), rs.getObject("vendedor_id", UUID.class), rs.getString("vendedor_nome"),
                rs.getString("momento"), rs.getObject("acerto_id", UUID.class), rs.getString("aplicado_em"), rs.getString("forma"),
                rs.getBigDecimal("valor"), rs.getTimestamp("recebido_em").toInstant(), rs.getObject("estorna_id", UUID.class),
                rs.getString("motivo"), rs.getBoolean("estornado"), contas);
    }

    /** Os recebimentos do vendedor (só os dele), do mais novo para o mais antigo. */
    public List<RecebimentoDoVendedor> doVendedor(UUID vendedorId) {
        return jdbc.query("""
                SELECT r.id, r.momento, r.acerto_id, r.aplicado_em, r.forma, r.valor, r.recebido_em, r.estorna_id,
                       EXISTS (SELECT 1 FROM corona.recebimentos_vendedor e WHERE e.estorna_id = r.id) AS estornado
                  FROM corona.recebimentos_vendedor r WHERE r.vendedor_id = ? AND r.deleted_at IS NULL
                 ORDER BY r.recebido_em DESC, r.id""" + " LIMIT " + LIMITE, (rs, n) -> new RecebimentoDoVendedor(rs.getObject("id", UUID.class),
                rs.getString("momento"), rs.getObject("acerto_id", UUID.class), rs.getString("aplicado_em"), rs.getString("forma"),
                rs.getBigDecimal("valor"), rs.getTimestamp("recebido_em").toInstant(), rs.getObject("estorna_id", UUID.class),
                rs.getBoolean("estornado")), vendedorId);
    }

    /** O dia de hoje em Brasília (para quem monta a tela). */
    public LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }
}
