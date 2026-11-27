package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
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
 * As conciliações da Fase 7 (tarefa 7.8; ADR-0012, item 17): C1 a C5 e C7 a C10 (a C6, de cheque, fica vaga e não aparece), mais a C11 e a C12 do estoque completo (tarefa 8.3): as onze.
 * Cada uma é uma consulta única sobre o banco, em Real, com os valores gravados (Dólar e Guarani nunca entram), e devolve cada
 * linha que não bate com o que era esperado e o que está gravado. Só leitura, num retrato só do banco (uma transação de leitura
 * com isolamento repetível), e nunca corrige nada: quem acha a diferença avisa o suporte. A única conta de dinheiro feita aqui é a
 * da comissão (C3), e só pelo utilitário {@link Dinheiro}. Os testes usam esta mesma classe, sobre um banco estragado de propósito.
 */
@Service
public class ConciliacoesDaFase7 {

    public static final List<String> CHAVES = List.of("C1", "C2", "C3", "C4", "C5", "C7", "C8", "C9", "C10", "C11", "C12");

    public enum Unidade {
        REAIS, PECAS, REGISTROS, PERCENTUAL
    }

    /** Uma linha que não bate: o que é (tipo e id), o que devia ser e o que está gravado. */
    public record Diferenca(String tipo, UUID referencia, String descricao, BigDecimal esperado, BigDecimal encontrado,
            Unidade unidade) {
    }

    /** Uma conferência: quantos registros olhou e o que não bate (deve vir vazio). */
    public record Conferencia(String chave, String titulo, String explicacao, long conferidos, List<Diferenca> divergencias) {
    }

    public record Resultado(Instant geradaEm, boolean tudoCerto, List<Conferencia> conferencias) {
    }

    private final JdbcTemplate jdbc;

    public ConciliacoesDaFase7(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** As onze, num retrato só do banco. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = RuntimeException.class)
    public Resultado conferir() {
        return resultado(CHAVES);
    }

    /** Uma só (chave desconhecida, inclusive a C6 que está vaga: recusa em português). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = RuntimeException.class)
    public Resultado conferir(String chave) {
        String limpa = chave == null ? "" : chave.strip().toUpperCase(Locale.ROOT);
        if (!CHAVES.contains(limpa)) {
            throw Validacoes.recusar("chave", "Escolha uma das conferências da lista: C1, C2, C3, C4, C5, C7, C8, C9, C10, C11 ou C12.");
        }
        return resultado(List.of(limpa));
    }

    private Resultado resultado(List<String> chaves) {
        List<Conferencia> conferencias = new ArrayList<>();
        for (String chave : chaves) {
            conferencias.add(switch (chave) {
                case "C1" -> c1();
                case "C2" -> c2();
                case "C3" -> c3();
                case "C4" -> c4();
                case "C5" -> c5();
                case "C7" -> c7();
                case "C8" -> c8();
                case "C9" -> c9();
                case "C10" -> c10();
                case "C11" -> c11();
                default -> c12();
            });
        }
        boolean certo = conferencias.stream().allMatch(c -> c.divergencias().isEmpty());
        return new Resultado(Instant.now(), certo, conferencias);
    }

    // --- ajudantes ---

    private long contar(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /** A consulta devolve, nesta ordem: tipo, id da referência, descrição, esperado e encontrado. */
    private List<Diferenca> achar(String sql, Unidade unidade) {
        return jdbc.query(sql, (rs, i) -> new Diferenca(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), unidade));
    }

    /** Uma regra "esperado = encontrado" sobre as linhas de {@code de} que passam em {@code onde}. */
    private List<Diferenca> regra(Unidade unidade, String tipo, String ref, String descricao, String esperado, String encontrado,
            String de, String onde) {
        return achar("SELECT '" + tipo + "', " + ref + ", '" + descricao + "', " + esperado + ", " + encontrado + " FROM " + de
                + " WHERE " + onde + " AND (" + esperado + ") <> (" + encontrado + ")", unidade);
    }

    /**
     * O saldo pelos movimentos (a fórmula do gatilho da V024: enviado mais os movimentos da maleta), em peças. Com {@code produto}
     * vazio é o da maleta inteira (C8); com uma peça, o daquela linha da maleta (C12). Uma expressão só para as duas.
     */
    private static String saldoDaMaleta(String maleta, String produto) {
        String doItem = produto.isEmpty() ? "" : " AND ix.produto_id = " + produto;
        String doMovimento = produto.isEmpty() ? "" : " AND m.produto_id = " + produto;
        return "(SELECT coalesce(sum(t.q), 0) FROM (SELECT ix.qtd_enviada AS q FROM corona.itens_remessa ix WHERE ix.remessa_id = " + maleta
                + " AND ix.deleted_at IS NULL" + doItem + " UNION ALL SELECT m.quantidade FROM corona.movimentos_remessa m WHERE m.remessa_id = "
                + maleta + doMovimento + ") t)";
    }

    /** Um movimento do estoque visto pelo que ele é de verdade: o estorno vale pelo tipo e pela origem do original. */
    private static final String MOVIMENTO_DO_ESTOQUE =
            "corona.movimentacoes_estoque me LEFT JOIN corona.movimentacoes_estoque o ON o.id = me.estorna_id";

    @SafeVarargs
    private static List<Diferenca> juntar(List<Diferenca>... listas) {
        List<Diferenca> todas = new ArrayList<>();
        for (List<Diferenca> l : listas) {
            todas.addAll(l);
        }
        return todas;
    }

    // --- C1: o que falta receber de cada venda ---

    private Conferencia c1() {
        String parcelas = "(SELECT venda_id, sum(valor - valor_pago - valor_cancelado) AS aberto FROM corona.contas_receber GROUP BY venda_id)";
        List<Diferenca> d = juntar(
                achar("""
                        SELECT 'VENDA', v.id, 'O que falta receber desta venda não bate com as parcelas em aberto.',
                               v.total - coalesce(dv.devolvido, 0) - coalesce(p.liquido, 0), coalesce(a.aberto, 0)
                          FROM corona.vendas v
                          LEFT JOIN %s a ON a.venda_id = v.id
                          LEFT JOIN (SELECT venda_id, sum(valor) AS liquido FROM corona.pagamentos_cliente
                                      WHERE tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO', 'REEMBOLSO', 'ESTORNO_REEMBOLSO')
                                      GROUP BY venda_id) p ON p.venda_id = v.id
                          LEFT JOIN (SELECT venda_id, sum(valor_devolvido) AS devolvido FROM corona.devolucoes
                                      WHERE situacao <> 'CANCELADA' GROUP BY venda_id) dv ON dv.venda_id = v.id
                         WHERE v.deleted_at IS NULL AND v.status = 'CONCLUIDA'
                           AND v.total - coalesce(dv.devolvido, 0) - coalesce(p.liquido, 0) <> coalesce(a.aberto, 0)
                         ORDER BY v.id""".formatted(parcelas), Unidade.REAIS),
                achar("""
                        SELECT 'VENDA', v.id, 'Esta venda foi desfeita, mas ainda tem parcela em aberto.', 0, sum(c.valor - c.valor_pago - c.valor_cancelado)
                          FROM corona.vendas v JOIN corona.contas_receber c ON c.venda_id = v.id
                         WHERE v.deleted_at IS NULL AND v.status = 'ESTORNADA'
                         GROUP BY v.id HAVING sum(c.valor - c.valor_pago - c.valor_cancelado) <> 0
                         ORDER BY v.id""", Unidade.REAIS));
        return new Conferencia("C1", "Vendas e o que falta receber",
                "Para cada venda: o valor da venda, menos o que foi devolvido, é igual ao que o cliente já pagou mais o que ainda falta "
                        + "nas parcelas. Venda desfeita não pode ter parcela em aberto.",
                contar("SELECT count(*) FROM corona.vendas WHERE deleted_at IS NULL AND status IN ('CONCLUIDA', 'ESTORNADA')"), d);
    }

    // --- C2: o acerto fecha ---

    private Conferencia c2() {
        String de = "corona.acertos a";
        String onde = "a.status = 'ACERTADO'";
        List<Diferenca> d = juntar(
                regra(Unidade.REAIS, "ACERTO", "a.id", "O líquido do acerto não bate com vendido, descontos, devolvido, comissão e faltas.",
                        "a.vendido_bruto - a.descontos - a.devolvido - a.comissao + a.faltas_cobradas", "a.liquido", de, onde),
                regra(Unidade.REAIS, "ACERTO", "a.id", "O vendido do acerto não bate com a soma das vendas, estornos e devoluções dele.",
                        "a.vendido_liquido", "(SELECT coalesce(sum(e.valor), 0) FROM corona.acerto_eventos e WHERE e.acerto_id = a.id)",
                        de, onde),
                regra(Unidade.REAIS, "ACERTO", "a.id", "O que o vendedor pagou, ficou devendo ou recebeu no acerto não fecha com o líquido.",
                        "a.liquido - a.credito_usado", "a.recebido_no_acerto - a.pago_ao_vendedor + a.ficou_devendo", de, onde),
                regra(Unidade.REAIS, "ACERTO", "a.id", "O recebido no acerto não bate com os recebimentos do acerto.",
                        "a.recebido_no_acerto", """
                                (SELECT coalesce(sum(r.valor), 0) FROM corona.recebimentos_vendedor r
                                  WHERE r.acerto_id = a.id AND r.aplicado_em = 'ACERTO' AND r.estorna_id IS NULL)""", de, onde),
                regra(Unidade.REAIS, "ACERTO", "a.id", "O que o vendedor ficou devendo não bate com as contas a receber dele.",
                        "greatest(a.ficou_devendo, 0)", """
                                (SELECT coalesce(sum(c.valor), 0) FROM corona.contas_receber c
                                  WHERE c.devedor_tipo = 'VENDEDOR' AND c.origem_tipo = 'ACERTO' AND c.origem_id = a.id)""", de, onde),
                regra(Unidade.REAIS, "ACERTO", "a.id", "O que foi pago ao vendedor no acerto não bate com a saída do caixa.",
                        "a.pago_ao_vendedor", """
                                (SELECT coalesce(-sum(l.valor), 0) FROM corona.lancamentos_caixa l
                                  WHERE l.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND l.origem_id = a.id)""", de, onde));
        return new Conferencia("C2", "Acerto do vendedor",
                "Para cada acerto fechado: vendido, descontos, devolvido, comissão e faltas dão o líquido; o vendido é a soma das vendas "
                        + "do acerto; e o que foi recebido, ficou devendo ou foi pago ao vendedor confere com os recebimentos, as contas e o caixa.",
                contar("SELECT count(*) FROM corona.acertos WHERE status = 'ACERTADO'"), d);
    }

    // --- C3: a comissão ---

    private Conferencia c3() {
        List<Diferenca> linhas = new ArrayList<>();
        jdbc.query("""
                SELECT c.id, c.base, c.percentual, c.valor FROM corona.comissoes c WHERE c.tipo = 'COMISSAO' ORDER BY c.id""", rs -> {
                    BigDecimal certo = Dinheiro.aplicarPorcentagem(rs.getBigDecimal("base"), rs.getBigDecimal("percentual"));
                    if (certo.compareTo(rs.getBigDecimal("valor")) != 0) {
                        linhas.add(new Diferenca("COMISSAO", rs.getObject("id", UUID.class),
                                "O valor desta comissão não é a porcentagem sobre a venda.", certo, rs.getBigDecimal("valor"),
                                Unidade.REAIS));
                    }
                });
        List<Diferenca> d = juntar(
                regra(Unidade.REAIS, "VENDEDOR", "v.id", "A soma das comissões do vendedor não bate com a dos acertos fechados dele.",
                        "coalesce((SELECT sum(a.comissao) FROM corona.acertos a WHERE a.vendedor_id = v.id AND a.status = 'ACERTADO'), 0)",
                        "coalesce((SELECT sum(c.valor) FROM corona.comissoes c WHERE c.vendedor_id = v.id), 0)", "corona.vendedores v",
                        "true"),
                regra(Unidade.PERCENTUAL, "COMISSAO", "c.id", "A porcentagem desta comissão não é a decidida no acerto.",
                        "a.percentual_comissao", "c.percentual", "corona.comissoes c JOIN corona.acertos a ON a.id = c.acerto_id",
                        "c.tipo = 'COMISSAO'"),
                linhas,
                achar("""
                        SELECT 'VENDA', c.venda_id, 'Os estornos de comissão passaram do valor da comissão da venda.', 0, sum(c.valor)
                          FROM corona.comissoes c GROUP BY c.venda_id HAVING sum(c.valor) < 0 ORDER BY c.venda_id""", Unidade.REAIS));
        return new Conferencia("C3", "Comissão dos vendedores",
                "A soma das comissões de cada vendedor é a soma das comissões dos acertos dele; cada comissão é a porcentagem decidida no "
                        + "acerto sobre o valor da venda; e os estornos nunca passam do que foi pago de comissão.",
                contar("SELECT count(*) FROM corona.comissoes"), d);
    }

    // --- C4: o caixa ---

    private Conferencia c4() {
        List<Diferenca> d = juntar(
                regra(Unidade.REGISTROS, "RECEBIMENTO", "r.id", "Este recebimento do vendedor não tem exatamente um lançamento no caixa com o mesmo valor.",
                        "1", """
                                (SELECT count(*) FROM corona.lancamentos_caixa l WHERE l.origem_tipo = 'RECEBIMENTO_VENDEDOR'
                                    AND l.origem_id = r.id AND l.valor = r.valor)""", "corona.recebimentos_vendedor r", "true"),
                regra(Unidade.REGISTROS, "PAGAMENTO_A_FORNECEDOR", "b.id", "Este pagamento a fornecedor não tem exatamente uma saída no caixa com o mesmo valor e a mesma forma.",
                        "1", """
                                (SELECT count(*) FROM corona.lancamentos_caixa l WHERE l.origem_tipo = 'PAGAMENTO_FORNECEDOR'
                                    AND l.origem_id = b.id AND l.valor = -b.valor AND l.forma = b.forma)""", "corona.baixas_pagar b",
                        "b.tipo = 'PAGAMENTO'"),
                regra(Unidade.REGISTROS, "ACERTO", "a.id", "O que foi pago ao vendedor neste acerto não tem exatamente uma saída no caixa com o mesmo valor.",
                        "1", """
                                (SELECT count(*) FROM corona.lancamentos_caixa l WHERE l.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR'
                                    AND l.origem_id = a.id AND l.valor = -a.pago_ao_vendedor)""", "corona.acertos a", "a.pago_ao_vendedor > 0"),
                regra(Unidade.REGISTROS, "LANCAMENTO", "l.id", "Este lançamento do caixa não tem a origem que ele diz ter.",
                        "1", """
                                CASE WHEN l.origem_tipo = 'RECEBIMENTO_VENDEDOR' THEN (SELECT count(*) FROM corona.recebimentos_vendedor x WHERE x.id = l.origem_id)
                                     WHEN l.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' THEN (SELECT count(*) FROM corona.acertos x WHERE x.id = l.origem_id)
                                     WHEN l.origem_tipo = 'PAGAMENTO_FORNECEDOR' THEN (SELECT count(*) FROM corona.baixas_pagar x WHERE x.id = l.origem_id)
                                     ELSE (SELECT count(*) FROM corona.lancamentos_caixa x WHERE x.id = l.origem_id AND x.id = l.id) END""",
                        "corona.lancamentos_caixa l", "true"),
                regra(Unidade.REAIS, "LANCAMENTO", "l.id", "O sinal deste lançamento não combina com entrada ou saída (a saída diminui o caixa e a entrada aumenta).",
                        "CASE WHEN (l.tipo = 'ENTRADA') = (l.estorna_id IS NULL) THEN abs(l.valor) ELSE -abs(l.valor) END", "l.valor",
                        "corona.lancamentos_caixa l", "true"));
        return new Conferencia("C4", "Caixa",
                "Cada recebimento do vendedor, cada pagamento a fornecedor e cada pagamento ao vendedor no acerto tem exatamente um lançamento "
                        + "no caixa, do mesmo valor; nenhum lançamento automático existe sem a origem dele; e entrada soma e saída diminui o saldo.",
                contar("""
                        SELECT (SELECT count(*) FROM corona.lancamentos_caixa) + (SELECT count(*) FROM corona.recebimentos_vendedor)
                             + (SELECT count(*) FROM corona.baixas_pagar WHERE tipo = 'PAGAMENTO')"""), d);
    }

    // --- C5: as contas a receber e a pagar ---

    private List<Diferenca> contas(String tipo, String tabela, String baixas) {
        String de = tabela + " c";
        return juntar(
                regra(Unidade.REAIS, tipo, "c.id", "O valor pago desta conta não é a soma dos pagamentos dela.",
                        "coalesce((SELECT sum(b.valor) FROM " + baixas + " b WHERE b.conta_id = c.id AND b.tipo = 'PAGAMENTO'), 0)",
                        "c.valor_pago", de, "true"),
                regra(Unidade.REAIS, tipo, "c.id", "O valor cancelado desta conta não é a soma dos cancelamentos dela.",
                        "coalesce((SELECT sum(b.valor) FROM " + baixas + " b WHERE b.conta_id = c.id AND b.tipo = 'CANCELAMENTO'), 0)",
                        "c.valor_cancelado", de, "true"),
                achar("SELECT '" + tipo + "', c.id, 'Esta conta tem mais pago e cancelado do que o valor dela.', c.valor, c.valor_pago + c.valor_cancelado FROM "
                        + de + " WHERE c.valor_pago + c.valor_cancelado > c.valor ORDER BY c.id", Unidade.REAIS),
                achar("SELECT '" + tipo + "', c.id, 'A situação desta conta não combina com o que foi pago e cancelado.', c.valor, "
                        + "c.valor_pago + c.valor_cancelado FROM " + de + " " + """
                         WHERE c.valor_pago + c.valor_cancelado <= c.valor
                           AND (CASE WHEN c.valor_pago + c.valor_cancelado = c.valor THEN
                                          (CASE WHEN c.valor_cancelado > 0 THEN 'CANCELADA' ELSE 'PAGA' END)
                                     WHEN c.valor_pago + c.valor_cancelado > 0 THEN 'PARCIAL' ELSE 'ABERTA' END) <> c.status
                         ORDER BY c.id""", Unidade.REAIS));
    }

    private Conferencia c5() {
        List<Diferenca> d = juntar(contas("CONTA_A_RECEBER", "corona.contas_receber", "corona.baixas_receber"),
                contas("CONTA_A_PAGAR", "corona.contas_pagar", "corona.baixas_pagar"));
        return new Conferencia("C5", "Contas a receber e a pagar",
                "Em cada conta (do cliente, do vendedor e de fornecedor): o valor pago e o cancelado são a soma das baixas, nunca passam do "
                        + "valor da conta, e a situação (aberta, parcial, paga ou cancelada) combina com eles.",
                contar("SELECT (SELECT count(*) FROM corona.contas_receber) + (SELECT count(*) FROM corona.contas_pagar)"), d);
    }

    // --- C7: o estoque ---

    private Conferencia c7() {
        List<Diferenca> d = juntar(
                regra(Unidade.PECAS, "PRODUTO", "p.id", "O estoque guardado desta peça não é a soma dos lançamentos do estoque.",
                        "coalesce((SELECT sum(m.quantidade) FROM corona.movimentacoes_estoque m WHERE m.produto_id = p.id), 0)",
                        "p.estoque_atual", "corona.produtos p", "true"),
                jdbc.query(ServicoDeEstoque.SQL_DIFERENCAS_COM_MALETAS,
                        (rs, i) -> new Diferenca("PRODUTO", rs.getObject("id", UUID.class),
                                "O estoque da loja mais as peças nas maletas não bate com o que entrou e saiu.",
                                rs.getBigDecimal("esperado"), BigDecimal.valueOf(rs.getLong("estoque_atual") + rs.getLong("nas_maletas")),
                                Unidade.PECAS)));
        return new Conferencia("C7", "Estoque da loja e maletas",
                "Para cada peça: o estoque guardado é a soma dos lançamentos, e o estoque da loja mais as peças que estão nas maletas "
                        + "abertas é tudo o que entrou menos tudo o que saiu, foi vendido, perdido ou estragou.",
                contar("SELECT count(*) FROM corona.produtos"), d);
    }

    // --- C8: maleta acertada e faltas ---

    private Conferencia c8() {
        List<Diferenca> d = juntar(
                regra(Unidade.PECAS, "MALETA", "r.id", "Esta maleta já foi acertada, mas ainda sobra peça nela.", "0",
                        saldoDaMaleta("r.id", ""),
                        "corona.remessas r", "r.status = 'ACERTADA'"),
                regra(Unidade.PECAS, "PENDENCIA", "p.id", "Esta falta não tem a perda correspondente na maleta.", "p.quantidade", """
                        coalesce((SELECT -sum(m.quantidade) FROM corona.movimentos_remessa m
                                   WHERE m.remessa_id = p.remessa_id AND m.produto_id = p.produto_id AND m.tipo = 'PERDA'), 0)""",
                        "corona.pendencias_acerto p", "p.tipo = 'FALTA' AND p.remessa_id IS NOT NULL"),
                regra(Unidade.PECAS, "PENDENCIA_DA_MALETA", "pm.id", "Esta falta do fechamento da maleta não tem a perda correspondente.",
                        "pm.quantidade", """
                        coalesce((SELECT -m.quantidade FROM corona.movimentos_remessa m
                                   WHERE m.id = pm.movimento_id AND m.tipo = 'PERDA' AND m.remessa_id = pm.remessa_id
                                     AND m.produto_id = pm.produto_id), 0)""",
                        "corona.pendencias_da_maleta pm", "true"));
        return new Conferencia("C8", "Maletas acertadas e faltas",
                "Toda maleta acertada fica com saldo zero, e cada falta registrada no acerto ou no fechamento da maleta tem a perda da "
                        + "mesma quantidade na maleta.",
                contar("SELECT (SELECT count(*) FROM corona.remessas WHERE status = 'ACERTADA')"
                        + " + (SELECT count(*) FROM corona.pendencias_acerto WHERE tipo = 'FALTA')"
                        + " + (SELECT count(*) FROM corona.pendencias_da_maleta)"), d);
    }

    // --- C9: cada evento em um acerto só e nenhum de fora ---

    private Conferencia c9() {
        String ultimoAcerto = "(SELECT max(a.fechado_em) FROM corona.acertos a WHERE a.vendedor_id = %s.vendedor_id AND a.status = 'ACERTADO')";
        List<Diferenca> d = juntar(
                achar("""
                        SELECT e.origem_tipo, e.origem_id, 'Este item entrou em mais de um acerto.', 1, count(*)
                          FROM corona.acerto_eventos e GROUP BY e.origem_tipo, e.origem_id HAVING count(*) > 1
                         ORDER BY e.origem_id""", Unidade.REGISTROS),
                achar("""
                        SELECT 'VENDA', v.id, 'Esta venda é de uma maleta acertada, mas não entrou em nenhum acerto.', 1, 0
                          FROM corona.vendas v JOIN corona.remessas r ON r.id = v.remessa_id
                         WHERE v.deleted_at IS NULL AND r.status = 'ACERTADA'
                           AND NOT EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'VENDA' AND e.origem_id = v.id)
                         ORDER BY v.id""", Unidade.REGISTROS),
                achar("""
                        SELECT 'ESTORNO_VENDA', v.id, 'Esta venda foi desfeita antes do último acerto, mas o estorno não entrou em acerto nenhum.', 1, 0
                          FROM corona.vendas v
                         WHERE v.deleted_at IS NULL AND v.status = 'ESTORNADA' AND v.estornada_em < """ + ultimoAcerto.formatted("v") + """

                           AND NOT EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'ESTORNO_VENDA' AND e.origem_id = v.id)
                         ORDER BY v.id""", Unidade.REGISTROS),
                achar("""
                        SELECT 'DEVOLUCAO', dv.id, 'Esta devolução foi registrada antes do último acerto, mas não entrou em acerto nenhum.', 1, 0
                          FROM corona.devolucoes dv
                         WHERE dv.deleted_at IS NULL AND dv.situacao NOT IN ('CANCELADA', 'EM_REVISAO')
                           AND dv.updated_at < """ + ultimoAcerto.formatted("dv") + """

                           AND NOT EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'DEVOLUCAO' AND e.origem_id = dv.id)
                         ORDER BY dv.id""", Unidade.REGISTROS));
        return new Conferencia("C9", "Acertos: cada venda, estorno e devolução entra uma vez",
                "Nenhuma venda, estorno de venda ou devolução entra em mais de um acerto, e nenhuma de maleta já acertada fica de fora de "
                        + "todos os acertos.",
                contar("SELECT (SELECT count(*) FROM corona.acerto_eventos) + (SELECT count(*) FROM corona.vendas v"
                        + " JOIN corona.remessas r ON r.id = v.remessa_id WHERE r.status = 'ACERTADA' AND v.deleted_at IS NULL)"), d);
    }

    // --- C10: as parcelas de cada compra ---

    private Conferencia c10() {
        List<Diferenca> d = achar("""
                SELECT 'COMPRA', c.id, 'A soma das parcelas desta compra não é o total dela.', c.total, p.soma
                  FROM corona.compras c JOIN (SELECT compra_id, sum(valor) AS soma FROM corona.contas_pagar GROUP BY compra_id) p
                    ON p.compra_id = c.id
                 WHERE c.total <> p.soma ORDER BY c.id""", Unidade.REAIS);
        return new Conferencia("C10", "Compras e parcelas a pagar",
                "Para cada compra com contas a pagar, a soma das parcelas (mesmo as canceladas) é o total da compra.",
                contar("SELECT count(DISTINCT compra_id) FROM corona.contas_pagar"), d);
    }

    // --- C11: o estoque completo, peça por peça ---

    private static final String ESPERADO_DA_C11 = """
            coalesce(comprado.q, 0) + coalesce(dona.q, 0) - coalesce(vendido.q, 0) + coalesce(volta.q, 0)
                + coalesce(acerto.recebido, 0) - coalesce(acerto.falta, 0) - coalesce(acerto.aprovado, 0) + coalesce(achada.q, 0)
                + coalesce(consignado.q, 0) - coalesce(faltou_na_maleta.q, 0)""";

    private Conferencia c11() {
        String sql = """
                SELECT 'PRODUTO', p.id,
                       'O estoque da loja mais as maletas não bate com o que foi comprado, vendido e perdido.',
                       %1$s,
                       p.estoque_atual + coalesce(maletas.saldo, 0)
                  FROM corona.produtos p
                  LEFT JOIN (%2$s) maletas ON maletas.produto_id = p.id
                  LEFT JOIN (SELECT ic.produto_id, sum(ic.quantidade) AS q
                               FROM corona.itens_compra ic JOIN corona.compras c ON c.id = ic.compra_id
                              WHERE c.situacao = 'REGISTRADA'
                              GROUP BY ic.produto_id) comprado ON comprado.produto_id = p.id
                  LEFT JOIN (SELECT me.produto_id, sum(me.quantidade) AS q
                               FROM %3$s
                              WHERE coalesce(o.origem_tipo, me.origem_tipo) IN ('MANUAL', 'AJUSTE_MANUAL', 'INVENTARIO')
                              GROUP BY me.produto_id) dona ON dona.produto_id = p.id
                  LEFT JOIN (SELECT iv.produto_id, sum(iv.quantidade) AS q
                               FROM corona.itens_venda iv JOIN corona.vendas v ON v.id = iv.venda_id
                              WHERE v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND iv.deleted_at IS NULL
                              GROUP BY iv.produto_id) vendido ON vendido.produto_id = p.id
                  LEFT JOIN (SELECT iv.produto_id, sum(idv.quantidade) AS q
                               FROM corona.itens_devolucao idv
                               JOIN corona.devolucoes d ON d.id = idv.devolucao_id
                               JOIN corona.itens_venda iv ON iv.id = idv.item_venda_id
                              WHERE d.situacao <> 'CANCELADA' AND d.deleted_at IS NULL AND idv.deleted_at IS NULL
                                AND idv.destino_peca = 'MALETA'
                              GROUP BY iv.produto_id) volta ON volta.produto_id = p.id
                  LEFT JOIN (SELECT ia.produto_id,
                                    sum(CASE WHEN ia.remessa_id IS NULL THEN ia.contado_boa + ia.contado_defeito ELSE 0 END) AS recebido,
                                    sum(CASE WHEN ia.remessa_id IS NULL THEN 0 ELSE ia.falta END) AS falta,
                                    sum(ia.defeito_aprovado) AS aprovado
                               FROM corona.itens_acerto ia JOIN corona.acertos a ON a.id = ia.acerto_id
                              WHERE a.status = 'ACERTADO' AND ia.deleted_at IS NULL
                              GROUP BY ia.produto_id) acerto ON acerto.produto_id = p.id
                  LEFT JOIN (SELECT pa.produto_id, sum(pa.quantidade) AS q
                               FROM corona.pendencias_acerto pa
                              WHERE pa.tipo = 'FALTA' AND pa.status = 'ENCONTRADA' AND pa.deleted_at IS NULL
                              GROUP BY pa.produto_id) achada ON achada.produto_id = p.id
                  LEFT JOIN (SELECT mr.produto_id, sum(mr.quantidade) AS q
                               FROM corona.movimentos_remessa mr
                              WHERE mr.tipo IN ('CONSIGNADO_SAIDA', 'CONSIGNADO_RETORNO')
                              GROUP BY mr.produto_id) consignado ON consignado.produto_id = p.id
                  LEFT JOIN (SELECT pm.produto_id, sum(pm.quantidade) AS q
                               FROM corona.pendencias_da_maleta pm
                              GROUP BY pm.produto_id) faltou_na_maleta ON faltou_na_maleta.produto_id = p.id
                 WHERE %1$s
                       <> p.estoque_atual + coalesce(maletas.saldo, 0)
                 ORDER BY p.id""".formatted(ESPERADO_DA_C11, ServicoDeEstoque.SQL_SALDO_NAS_MALETAS_ABERTAS, MOVIMENTO_DO_ESTOQUE);
        return new Conferencia("C11", "Estoque completo, peça por peça",
                "Para cada peça: o que está na loja mais o que está nas maletas é o que foi comprado e lançado, menos o que foi vendido, "
                        + "o que faltou ou estragou no acerto e o que está com o cliente em nota de consignado, mais o que o cliente devolveu à "
                        + "maleta e o que chegou à loja pelo acerto.",
                contar("SELECT count(*) FROM corona.produtos"), achar(sql, Unidade.PECAS));
    }

    // --- C12: o saldo de toda maleta enviada ---

    private Conferencia c12() {
        // FECHADA (leva 9): a maleta que a dona bipou na volta continua conferida.
        String enviadas = "r.deleted_at IS NULL AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO', 'FECHADA', 'ACERTADA')";
        // A saída da nota acima do saldo é aceita com alerta (ADR-0016, dúvida D4): a peça negativa com alerta não é acusada,
        // como na K5 do consignado.
        String semAlerta = " AND NOT EXISTS (SELECT 1 FROM corona.alertas_consignado al WHERE al.tipo = 'PECA_SEM_SALDO_NA_MALETA'"
                + " AND al.detalhe ->> 'remessa_id' = r.id::text AND al.detalhe ->> 'produto_id' = i.produto_id::text)";
        String peca = "' || p.nome || '";
        String linhas = "corona.remessas r JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL"
                + " JOIN corona.produtos p ON p.id = i.produto_id";
        String movimentosDaMaleta = "(SELECT coalesce(sum(me.quantidade), 0) FROM " + MOVIMENTO_DO_ESTOQUE
                + " WHERE me.produto_id = i.produto_id AND coalesce(o.origem_tipo, me.origem_tipo) = 'MALETA'"
                + " AND coalesce(o.origem_id, me.origem_id) = r.id AND coalesce(o.tipo, me.tipo) = ";
        String enviado = "(-" + movimentosDaMaleta + "'REMESSA'))";
        String devolvidoADona = movimentosDaMaleta + "'DEVOLUCAO')";
        String vendido = """
                (SELECT coalesce(sum(iv.quantidade), 0) FROM corona.itens_venda iv JOIN corona.vendas v ON v.id = iv.venda_id
                  WHERE v.remessa_id = r.id AND iv.produto_id = i.produto_id AND v.status = 'CONCLUIDA'
                    AND v.deleted_at IS NULL AND iv.deleted_at IS NULL)""";
        String devolvidoPeloCliente = """
                (SELECT coalesce(sum(idv.quantidade), 0) FROM corona.itens_devolucao idv
                   JOIN corona.devolucoes d ON d.id = idv.devolucao_id JOIN corona.itens_venda iv ON iv.id = idv.item_venda_id
                  WHERE d.remessa_id = r.id AND iv.produto_id = i.produto_id AND idv.destino_peca = 'MALETA'
                    AND d.situacao <> 'CANCELADA' AND d.deleted_at IS NULL AND idv.deleted_at IS NULL)""";
        String falta = """
                ((SELECT coalesce(sum(ia.falta), 0) FROM corona.itens_acerto ia JOIN corona.acertos a ON a.id = ia.acerto_id
                  WHERE ia.remessa_id = r.id AND ia.produto_id = i.produto_id AND a.status = 'ACERTADO' AND ia.deleted_at IS NULL)
                 + (SELECT coalesce(sum(pm.quantidade), 0) FROM corona.pendencias_da_maleta pm
                     WHERE pm.remessa_id = r.id AND pm.produto_id = i.produto_id))""";
        // A peça que saiu na nota de consignado e está com o cliente (CONSIGNADO_SAIDA, negativo) e a que voltou no acerto da nota
        // ou na troca (CONSIGNADO_RETORNO, positivo), como na C7 (ADR-0016, item 6).
        String consignado = """
                (SELECT coalesce(sum(m.quantidade), 0) FROM corona.movimentos_remessa m
                  WHERE m.remessa_id = r.id AND m.produto_id = i.produto_id AND m.tipo IN ('CONSIGNADO_SAIDA', 'CONSIGNADO_RETORNO'))""";
        String saldo = saldoDaMaleta("r.id", "i.produto_id");
        String movimentosDaRemessa = "(SELECT coalesce(sum(abs(m.quantidade)), 0) FROM corona.movimentos_remessa m"
                + " WHERE m.remessa_id = r.id AND m.produto_id = i.produto_id)";
        String esperado = enviado + " - " + vendido + " + " + devolvidoPeloCliente + " - " + devolvidoADona + " - " + falta + " + "
                + consignado;
        List<Diferenca> d = juntar(
                achar("SELECT 'MALETA', r.id, 'A peça \"" + peca + "\" nesta maleta não bate com o que foi enviado, vendido, devolvido e perdido.', "
                        + esperado + ", " + saldo + " FROM " + linhas + " WHERE " + enviadas + " AND (" + esperado + ") <> " + saldo
                        + " ORDER BY r.id, p.nome", Unidade.PECAS),
                achar("SELECT 'MALETA', r.id, 'A peça \"" + peca + "\" ficou com saldo negativo nesta maleta.', 0, " + saldo + " FROM " + linhas
                        + " WHERE r.deleted_at IS NULL AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO', 'FECHADA', 'ACERTADA', 'CANCELADA')"
                        + " AND " + saldo + " < 0" + semAlerta + " ORDER BY r.id, p.nome", Unidade.PECAS),
                achar("SELECT 'MALETA', r.id, 'A peça \"" + peca + "\" ainda aparece nesta maleta, que foi cancelada.', 0, abs(" + enviado
                        + ") + " + movimentosDaRemessa + " FROM " + linhas + " WHERE r.deleted_at IS NULL AND r.status = 'CANCELADA' AND (abs("
                        + enviado + ") + " + movimentosDaRemessa + ") <> 0 ORDER BY r.id, p.nome", Unidade.PECAS));
        return new Conferencia("C12", "Cada maleta enviada, peça por peça",
                "Em toda maleta enviada, para cada peça: o que há na maleta é o que foi enviado, menos o que foi vendido, devolvido à dona, "
                        + "perdido e o que saiu em nota de consignado, mais o que o cliente devolveu e o que voltou da nota; nenhuma peça fica "
                        + "negativa (salvo com alerta do consignado) e a maleta cancelada não guarda peça.",
                contar("SELECT count(*) FROM corona.remessas r JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL"
                        + " WHERE r.deleted_at IS NULL AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO', 'FECHADA', 'ACERTADA', 'CANCELADA')"),
                d);
    }
}
