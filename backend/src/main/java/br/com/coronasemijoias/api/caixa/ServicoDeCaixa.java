package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * O caixa (tarefas 7.6 e 7.7; ADR-0012, item 11): único lugar que grava em {@code lancamentos_caixa}. Só é chamado de dentro da
 * transação de quem gerou o dinheiro (recebimento do vendedor, pagamento ao vendedor, pagamento a fornecedor) ou do lançamento
 * manual da dona, nunca sozinho ({@code MANDATORY}). O lançamento é imutável; o estorno é um lançamento de sinal oposto ligado ao
 * original. O saldo é sempre a soma e nunca bloqueia nada (o aviso de saldo negativo é do {@code FonteDeAvisosDoCaixa}). Tudo em
 * Real; a data do lançamento automático é o dia do calendário de Brasília; a do manual e a do pagamento a fornecedor são da dona
 * (nunca no futuro) e não podem cair numa semana fechada.
 */
@Service
public class ServicoDeCaixa {

    static final String CATEGORIA_DO_REPASSE = "Repasse do representante";
    static final String CATEGORIA_DO_PAGAMENTO_AO_VENDEDOR = "Pagamento ao representante";
    static final String CATEGORIA_DO_PAGAMENTO_A_FORNECEDOR = "Pagamento a fornecedor";
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
        return gravar(UuidV7.gerar(), "ENTRADA", valor, forma, categoria(CATEGORIA_DO_REPASSE), "RECEBIMENTO_VENDEDOR", recebimentoId,
                hoje(), null, usuarioId, "Recebimento do representante", null, null);
    }

    /**
     * O estorno do lançamento de um recebimento: o lançamento original é achado pela origem (o recebimento estornado) e o novo
     * tem o sinal oposto, ligado a ele. Um lançamento só pode ser estornado uma vez (o banco garante).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID estornarRecebimento(UUID usuarioId, UUID recebimentoOriginalId, UUID estornoDoRecebimentoId) {
        return estornarPelaOrigem(usuarioId, "RECEBIMENTO_VENDEDOR", recebimentoOriginalId, estornoDoRecebimentoId,
                "Estorno de recebimento do representante", null);
    }

    /** A saída do que o acerto pagou ao vendedor na hora (acerto a favor dele): valor positivo, gravado negativo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID lancarPagamentoAoVendedor(UUID usuarioId, UUID acertoId, String forma, BigDecimal valor) {
        exigirPositivo(valor);
        return gravar(UuidV7.gerar(), "SAIDA", valor.negate(), forma, categoria(CATEGORIA_DO_PAGAMENTO_AO_VENDEDOR),
                "ACERTO_A_FAVOR_DO_VENDEDOR", acertoId, hoje(), null, usuarioId, "Pagamento ao representante no acerto", null, null);
    }

    /**
     * A saída de um pagamento a fornecedor (tarefa 7.7; C4): a origem é a baixa da conta a pagar; o mesmo valor e a mesma forma;
     * a data é a do pagamento (nunca numa semana fechada).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID lancarPagamentoAFornecedor(UUID usuarioId, UUID baixaId, String forma, BigDecimal valor, LocalDate data) {
        exigirPositivo(valor);
        return gravar(UuidV7.gerar(), "SAIDA", valor.negate(), forma, categoria(CATEGORIA_DO_PAGAMENTO_A_FORNECEDOR),
                "PAGAMENTO_FORNECEDOR", baixaId, data, null, usuarioId, "Pagamento a fornecedor", null, null);
    }

    /** O estorno do lançamento de um pagamento a fornecedor, com a data de hoje: o sinal oposto, ligado ao original. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID estornarPagamentoAFornecedor(UUID usuarioId, UUID baixaOriginalId, UUID baixaDoEstornoId) {
        return estornarPelaOrigem(usuarioId, "PAGAMENTO_FORNECEDOR", baixaOriginalId, baixaDoEstornoId,
                "Estorno de pagamento a fornecedor", null);
    }

    /**
     * O lançamento manual da dona: entrada (positiva) ou saída (negativa gravada com o sinal), com categoria do usuário do mesmo
     * tipo. O lançamento é a própria origem. O {@code id} é o do comando (do aplicativo). O banco confere a categoria.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID lancarManual(UUID usuarioId, UUID id, String tipo, BigDecimal valor, String forma, UUID categoriaId, LocalDate data,
            String descricao, String conteudo) {
        exigirPositivo(valor);
        return gravar(id, tipo, "SAIDA".equals(tipo) ? valor.negate() : valor, forma, categoriaId, "MANUAL", id, data, null, usuarioId,
                descricao, null, conteudo);
    }

    /** O estorno de um lançamento manual, com a data de hoje e o motivo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID estornarManual(UUID usuarioId, UUID originalId, UUID estornoId, String motivo, String conteudo) {
        Map<String, Object> o = jdbc.queryForMap("""
                SELECT tipo, valor, forma, categoria_id FROM corona.lancamentos_caixa WHERE id = ? AND origem_tipo = 'MANUAL'""", originalId);
        return gravar(estornoId, (String) o.get("tipo"), ((BigDecimal) o.get("valor")).negate(), (String) o.get("forma"),
                (UUID) o.get("categoria_id"), "MANUAL", estornoId, hoje(), originalId, usuarioId, "Estorno de lançamento", motivo, conteudo);
    }

    /** O saldo do caixa: sempre a soma de todos os lançamentos (sem atalho gravado). */
    public BigDecimal saldo() {
        return jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa", BigDecimal.class);
    }

    /** O dia do calendário de Brasília de agora (a data dos lançamentos automáticos). */
    public LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }

    private UUID estornarPelaOrigem(UUID usuarioId, String origemTipo, UUID origemOriginalId, UUID origemDoEstornoId, String descricao,
            String motivo) {
        List<Map<String, Object>> original = jdbc.queryForList("""
                SELECT id, tipo, valor, forma, categoria_id FROM corona.lancamentos_caixa
                 WHERE origem_tipo = ? AND origem_id = ?""", origemTipo, origemOriginalId);
        if (original.isEmpty()) {
            throw new IllegalStateException("A origem " + origemOriginalId + " não tem lançamento de caixa.");
        }
        Map<String, Object> o = original.get(0);
        return gravar(UuidV7.gerar(), (String) o.get("tipo"), ((BigDecimal) o.get("valor")).negate(), (String) o.get("forma"),
                (UUID) o.get("categoria_id"), origemTipo, origemDoEstornoId, hoje(), (UUID) o.get("id"), usuarioId, descricao, motivo, null);
    }

    private UUID categoria(String nome) {
        return jdbc.queryForObject("SELECT id FROM corona.categorias_caixa WHERE nome = ? AND sistema", UUID.class, nome);
    }

    private UUID gravar(UUID id, String tipo, BigDecimal valor, String forma, UUID categoriaId, String origemTipo, UUID origemId,
            LocalDate data, UUID estorna, UUID usuarioId, String descricao, String motivo, String conteudo) {
        exigirSemanaAberta(data);
        jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento,
                    lancado_em, descricao, estorna_id, registrado_por, motivo, conteudo)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, tipo, valor, forma, categoriaId, origemTipo, origemId, data,
                Timestamp.from(relogio.instant()), descricao, estorna, usuarioId, motivo, conteudo);
        return id;
    }

    /**
     * Quem grava com data numa semana lê a semana com {@code FOR SHARE}, como última trava: o fechar semana trava com
     * {@code FOR UPDATE}, então um dos dois espera o outro e nunca entra lançamento em semana que acabou de fechar.
     */
    private void exigirSemanaAberta(LocalDate data) {
        List<String> situacao = jdbc.queryForList("""
                SELECT situacao FROM corona.semanas WHERE deleted_at IS NULL AND ? BETWEEN inicio AND fim FOR SHARE""", String.class, data);
        if (!situacao.isEmpty() && "FECHADA".equals(situacao.get(0))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DATA_EM_SEMANA_FECHADA);
        }
    }

    private static void exigirPositivo(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0 || valor.scale() > Dinheiro.ESCALA) {
            throw new IllegalArgumentException("O valor do lançamento de caixa precisa ser positivo, em reais, com até duas casas.");
        }
    }
}
