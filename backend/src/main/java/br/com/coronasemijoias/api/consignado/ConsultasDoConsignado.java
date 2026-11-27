package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.FormaPaga;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As leituras da dona sobre o consignado ao cliente (ADR-0016, itens 11 e 14; plano, seção 3): semanas dos representantes,
 * notas, detalhe da nota (itens, acerto, recibos), situação do cliente e alertas. Só leitura, em Real; Dólar e Guarani saem
 * dos mesmos valores pela cotação de cada lançamento (a tela faz a conversão). O número da nota é {@code semana-sequência},
 * calculado aqui na leitura (nunca gravado como texto).
 */
@Service
@Transactional(readOnly = true)
public class ConsultasDoConsignado {

    public record SemanaDoRepresentante(UUID id, UUID vendedorId, int numero, Instant abertaEm, Instant fechadaEm, String situacao,
            int notas, BigDecimal valorDx, BigDecimal valorVd, BigDecimal comissao, BigDecimal recebido) {
    }

    public record NotaResumo(UUID id, String numero, UUID vendedorId, UUID semanaId, int semana, int sequencia, UUID clienteId,
            String cliente, Instant saidaEm, LocalDate dataCobranca, String situacao, int itensDx, BigDecimal valorDx,
            String regraDaComissao, Long metaGuarani, Long vdGuarani, Boolean bateuMeta) {
    }

    public record LinhaDaNotaLida(UUID produtoId, String produto, UUID remessaId, int dx, BigDecimal precoUnitario) {
    }

    public record AcertoLido(UUID id, int semana, BigDecimal comissaoPercentual, int itensDx, int itensDv, int itensVd,
            BigDecimal valorDx, BigDecimal valorDv, BigDecimal valorVd, BigDecimal comissao, BigDecimal bruto,
            BigDecimal pendenciasAnteriores, BigDecimal totalAReceber, BigDecimal totalPago, BigDecimal ficouDevendo,
            BigDecimal saldoDoDebito, Instant acertadoEm, boolean estornado, String motivoDoEstorno,
            String regraDaComissao, Long metaGuarani, Long vdGuarani, Boolean bateuMeta) {
    }

    public record ReciboLido(UUID id, int semana, int sequencia, UUID acertoId, Instant pagoEm, BigDecimal total, boolean estornado,
            BigDecimal aAplicar, List<FormaPaga> formas) {
    }

    public record DetalheDaNota(NotaResumo nota, List<LinhaDaNotaLida> linhas, List<AcertoLido> acertos, List<ReciboLido> recibos) {
    }

    public record PecaComClienteLida(UUID remessaId, UUID produtoId, String produto, UUID notaId, int quantidade) {
    }

    /**
     * O cadastro do cliente para a dona (só leitura, §23.5): documento, quem indicou, como conheceu, comissão e se as três fotos
     * já subiram (o hash fica no servidor; a foto sai pela rota da dona).
     */
    public record CadastroDoCliente(String telefone, String whatsapp, String endereco, String documento, String documentoTipo,
            String indicadoPor, String comoConheceu, BigDecimal comissaoPercentual, String representante, boolean temFoto,
            boolean temFotoDoDocumentoFrente, boolean temFotoDoDocumentoVerso) {
    }

    public record SituacaoDoCliente(UUID clienteId, String nome, BigDecimal pendencia, List<NotaResumo> notas,
            List<PecaComClienteLida> pecasComEle, CadastroDoCliente cadastro) {
    }

    public record AlertaLido(UUID id, String tipo, UUID vendedorId, String registroTabela, UUID registroId, String detalhe,
            Instant criadoEm, Instant vistoEm) {
    }

    private static final String NOTAS = """
            SELECT n.id, lpad(s.numero::text, 7, '0') || '-' || lpad(n.sequencia::text, 2, '0') AS numero, n.vendedor_id, n.semana_id,
                   s.numero AS semana, n.sequencia, n.cliente_id, c.nome AS cliente, n.saida_em, n.data_cobranca, n.situacao,
                   coalesce((SELECT sum(i.quantidade) FROM corona.itens_nota_consignado i WHERE i.nota_id = n.id), 0) AS itens_dx,
                   coalesce((SELECT sum(i.quantidade * i.preco_unitario) FROM corona.itens_nota_consignado i WHERE i.nota_id = n.id), 0) AS valor_dx,
                   ua.regra_da_comissao, ua.meta_guarani, ua.vd_guarani,
                   CASE WHEN ua.regra_da_comissao = 'META' THEN coalesce(ua.vd_guarani >= ua.meta_guarani, false) END AS bateu_meta
              FROM corona.notas_consignado n
              LEFT JOIN LATERAL (SELECT a.regra_da_comissao, a.meta_guarani, a.vd_guarani FROM corona.acertos_nota a
                                  WHERE a.nota_id = n.id AND a.estornado_em IS NULL
                                  ORDER BY a.registrado_em DESC, a.id LIMIT 1) ua ON true
              JOIN corona.semanas_vendedor s ON s.id = n.semana_id
              JOIN corona.clientes c ON c.id = n.cliente_id
             WHERE n.deleted_at IS NULL""";

    private final JdbcTemplate jdbc;
    private final DebitosDoCliente debitos;

    public ConsultasDoConsignado(JdbcTemplate jdbc, DebitosDoCliente debitos) {
        this.jdbc = jdbc;
        this.debitos = debitos;
    }

    private static Instant instante(Object o) {
        return o == null ? null : ((Timestamp) o).toInstant();
    }

    private static NotaResumo nota(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NotaResumo(rs.getObject("id", UUID.class), rs.getString("numero"), rs.getObject("vendedor_id", UUID.class),
                rs.getObject("semana_id", UUID.class), rs.getInt("semana"), rs.getInt("sequencia"), rs.getObject("cliente_id", UUID.class),
                rs.getString("cliente"), rs.getTimestamp("saida_em").toInstant(), rs.getDate("data_cobranca").toLocalDate(),
                rs.getString("situacao"), rs.getInt("itens_dx"), rs.getBigDecimal("valor_dx"),
                rs.getString("regra_da_comissao"), longOuNulo(rs, "meta_guarani"), longOuNulo(rs, "vd_guarani"),
                (Boolean) rs.getObject("bateu_meta"));
    }

    private static Long longOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        long v = rs.getLong(coluna);
        return rs.wasNull() ? null : v;
    }

    private static Long longOuNulo(java.sql.ResultSet rs, int coluna) throws java.sql.SQLException {
        long v = rs.getLong(coluna);
        return rs.wasNull() ? null : v;
    }

    /** As semanas do representante, da mais nova para a mais antiga, com os totais (a aba Totais). */
    public List<SemanaDoRepresentante> semanasDoRepresentante(UUID vendedorId) {
        return jdbc.query("SELECT id FROM corona.semanas_vendedor WHERE vendedor_id = ? AND deleted_at IS NULL ORDER BY numero DESC",
                (rs, i) -> rs.getObject(1, UUID.class), vendedorId).stream().map(this::semana).toList();
    }

    public SemanaDoRepresentante semana(UUID semanaId) {
        List<SemanaDoRepresentante> r = jdbc.query("""
                SELECT s.id, s.vendedor_id, s.numero, s.aberta_em, s.fechada_em, s.situacao,
                       (SELECT count(*) FROM corona.notas_consignado n WHERE n.semana_id = s.id) AS notas,
                       coalesce((SELECT sum(i.quantidade * i.preco_unitario) FROM corona.itens_nota_consignado i
                                  JOIN corona.notas_consignado n ON n.id = i.nota_id WHERE n.semana_id = s.id), 0) AS valor_dx,
                       coalesce((SELECT sum(a.valor_vd) FROM corona.acertos_nota a WHERE a.semana_id = s.id AND a.estornado_em IS NULL), 0) AS vd,
                       coalesce((SELECT sum(a.comissao) FROM corona.acertos_nota a WHERE a.semana_id = s.id AND a.estornado_em IS NULL), 0) AS comissao,
                       coalesce((SELECT sum(r.total) FROM corona.recibos_consignado r WHERE r.semana_id = s.id AND r.estornado_em IS NULL), 0) AS recebido
                  FROM corona.semanas_vendedor s WHERE s.id = ? AND s.deleted_at IS NULL""",
                (rs, i) -> new SemanaDoRepresentante(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        instante(rs.getTimestamp(4)), instante(rs.getTimestamp(5)), rs.getString(6), rs.getInt(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11)), semanaId);
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.SEMANA_NAO_ENCONTRADA);
        }
        return r.get(0);
    }

    /** As notas, com filtros opcionais; mais novas primeiro (até 200). */
    public List<NotaResumo> notas(UUID vendedorId, UUID semanaId, String situacao, UUID clienteId, String texto) {
        StringBuilder sql = new StringBuilder(NOTAS);
        List<Object> args = new ArrayList<>();
        if (vendedorId != null) {
            sql.append(" AND n.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (semanaId != null) {
            sql.append(" AND n.semana_id = ?");
            args.add(semanaId);
        }
        if (situacao != null && !situacao.isBlank()) {
            sql.append(" AND n.situacao = ?");
            args.add(situacao.strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (clienteId != null) {
            sql.append(" AND n.cliente_id = ?");
            args.add(clienteId);
        }
        if (texto != null && !texto.isBlank()) {
            sql.append(" AND (lower(c.nome) LIKE '%' || lower(?) || '%' OR (lpad(s.numero::text, 7, '0') || '-' || lpad(n.sequencia::text, 2, '0')) LIKE '%' || ? || '%')");
            args.add(texto.strip());
            args.add(texto.strip());
        }
        sql.append(" ORDER BY n.saida_em DESC, n.id LIMIT 200");
        return jdbc.query(sql.toString(), (rs, i) -> nota(rs), args.toArray());
    }

    public DetalheDaNota detalhe(UUID notaId) {
        List<NotaResumo> n = jdbc.query(NOTAS + " AND n.id = ?", (rs, i) -> nota(rs), notaId);
        if (n.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.NOTA_NAO_ENCONTRADA);
        }
        List<LinhaDaNotaLida> linhas = jdbc.query("""
                SELECT i.produto_id, p.nome, i.remessa_id, sum(i.quantidade) AS dx, max(i.preco_unitario) AS preco
                  FROM corona.itens_nota_consignado i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.nota_id = ? GROUP BY i.produto_id, p.nome, i.remessa_id ORDER BY p.nome, i.remessa_id""",
                (rs, i) -> new LinhaDaNotaLida(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                        rs.getBigDecimal(5)), notaId);
        List<AcertoLido> acertos = jdbc.query("""
                SELECT a.id, s.numero, a.comissao_percentual, a.itens_dx, a.itens_dv, a.itens_vd, a.valor_dx, a.valor_dv, a.valor_vd,
                       a.comissao, a.bruto, a.pendencias_anteriores, a.total_a_receber, a.total_pago, a.ficou_devendo,
                       a.bruto - coalesce((SELECT sum(ap.valor) FROM corona.aplicacoes_recibo ap WHERE ap.acerto_id = a.id), 0) AS saldo,
                       a.acertado_em, a.estornado_em IS NOT NULL, a.motivo_estorno,
                       a.regra_da_comissao, a.meta_guarani, a.vd_guarani,
                       CASE WHEN a.regra_da_comissao = 'META' THEN coalesce(a.vd_guarani >= a.meta_guarani, false) END
                  FROM corona.acertos_nota a JOIN corona.semanas_vendedor s ON s.id = a.semana_id
                 WHERE a.nota_id = ? ORDER BY a.registrado_em, a.id""",
                (rs, i) -> new AcertoLido(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                        rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14), rs.getBigDecimal(15),
                        rs.getBigDecimal(16), rs.getTimestamp(17).toInstant(), rs.getBoolean(18), rs.getString(19), rs.getString(20), longOuNulo(rs, 21),
                        longOuNulo(rs, 22), (Boolean) rs.getObject(23)), notaId);
        // Os recibos que quitaram (ou foram do acerto de) esta nota.
        List<UUID> reciboIds = jdbc.queryForList("""
                SELECT DISTINCT r.id FROM corona.recibos_consignado r
                 WHERE r.acerto_id IN (SELECT id FROM corona.acertos_nota WHERE nota_id = ?)
                    OR r.id IN (SELECT ap.recibo_id FROM corona.aplicacoes_recibo ap JOIN corona.acertos_nota a ON a.id = ap.acerto_id
                                 WHERE a.nota_id = ?)""", UUID.class, notaId, notaId);
        List<ReciboLido> recibos = new ArrayList<>();
        for (UUID id : reciboIds) {
            recibos.add(recibo(id));
        }
        return new DetalheDaNota(n.get(0), linhas, acertos, recibos);
    }

    private ReciboLido recibo(UUID id) {
        return jdbc.query("""
                SELECT r.id, s.numero, r.sequencia, r.acerto_id, r.pago_em, r.total, r.estornado_em IS NOT NULL
                  FROM corona.recibos_consignado r JOIN corona.semanas_vendedor s ON s.id = r.semana_id WHERE r.id = ?""",
                (rs, i) -> new ReciboLido(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3), rs.getObject(4, UUID.class),
                        rs.getTimestamp(5).toInstant(), rs.getBigDecimal(6), rs.getBoolean(7), debitos.aAplicar(id),
                        jdbc.query("SELECT forma, valor FROM corona.formas_recibo_consignado WHERE recibo_id = ? ORDER BY forma",
                                (r2, k) -> new FormaPaga(r2.getString(1), r2.getBigDecimal(2)), id)), id).get(0);
    }

    /** A situação do cliente: o que deve, as notas dele e as peças que estão com ele agora (notas sem acerto). */
    public SituacaoDoCliente situacaoDoCliente(UUID clienteId) {
        List<String> nome = jdbc.queryForList("SELECT nome FROM corona.clientes WHERE id = ? AND deleted_at IS NULL", String.class, clienteId);
        if (nome.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        List<PecaComClienteLida> pecas = jdbc.query("""
                SELECT i.remessa_id, i.produto_id, p.nome, n.id, sum(i.quantidade) AS qtd
                  FROM corona.itens_nota_consignado i JOIN corona.notas_consignado n ON n.id = i.nota_id
                  JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE n.cliente_id = ? AND NOT EXISTS (SELECT 1 FROM corona.acertos_nota a WHERE a.nota_id = n.id AND a.estornado_em IS NULL)
                 GROUP BY i.remessa_id, i.produto_id, p.nome, n.id HAVING sum(i.quantidade) > 0 ORDER BY p.nome, n.id""",
                (rs, i) -> new PecaComClienteLida(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getInt(5)), clienteId);
        return new SituacaoDoCliente(clienteId, nome.get(0), debitos.pendenciaDoCliente(clienteId),
                notas(null, null, null, clienteId, null), pecas, cadastroDoCliente(clienteId));
    }

    private CadastroDoCliente cadastroDoCliente(UUID clienteId) {
        return jdbc.query("""
                SELECT c.telefone, c.whatsapp, c.endereco_logradouro, c.endereco_numero, c.endereco_bairro, c.endereco_cidade,
                       c.endereco_uf, c.documento, c.documento_tipo, coalesce(i.nome, c.indicado_por_nome), c.como_conheceu,
                       c.comissao_percentual, v.nome, c.foto_hash IS NOT NULL, c.foto_documento_frente_hash IS NOT NULL,
                       c.foto_documento_verso_hash IS NOT NULL
                  FROM corona.clientes c
                  LEFT JOIN corona.clientes i ON i.id = c.indicado_por_cliente_id
                  LEFT JOIN corona.vendedores v ON v.id = c.vendedor_responsavel_id
                 WHERE c.id = ?""",
                (rs, k) -> new CadastroDoCliente(rs.getString(1), rs.getString(2), enderecoEmTexto(rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getBigDecimal(12), rs.getString(13), rs.getBoolean(14), rs.getBoolean(15),
                        rs.getBoolean(16)), clienteId).stream().findFirst().orElse(null);
    }

    /** "Rua, número, bairro, cidade - UF", só com o que existe. */
    private static String enderecoEmTexto(String rua, String numero, String bairro, String cidade, String uf) {
        String cidadeEUf = cidade == null || cidade.isBlank() ? (uf == null ? "" : uf)
                : cidade + (uf == null || uf.isBlank() ? "" : " - " + uf);
        List<String> partes = new ArrayList<>();
        for (String p : new String[] {rua, numero, bairro, cidadeEUf}) {
            if (p != null && !p.isBlank()) {
                partes.add(p.trim());
            }
        }
        return String.join(", ", partes);
    }

    // --- alertas ---

    public List<AlertaLido> alertas(boolean soNaoVistos) {
        return jdbc.query("""
                SELECT id, tipo, vendedor_id, registro_tabela, registro_id, detalhe::text, created_at, visto_em
                  FROM corona.alertas_consignado""" + (soNaoVistos ? " WHERE visto_em IS NULL" : "") + " ORDER BY created_at DESC LIMIT 200",
                (rs, i) -> new AlertaLido(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getTimestamp(7).toInstant(), instante(rs.getTimestamp(8))));
    }

    @Transactional
    public void marcarVisto(UUID alertaId) {
        int n = jdbc.update("UPDATE corona.alertas_consignado SET visto_em = now() WHERE id = ? AND visto_em IS NULL", alertaId);
        if (n == 0 && jdbc.queryForObject("SELECT count(*) FROM corona.alertas_consignado WHERE id = ?", Integer.class, alertaId) == 0) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ROTA_NAO_ENCONTRADA);
        }
    }
}
