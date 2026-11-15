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
 * As conciliações da Fase 7 (tarefa 7.8; ADR-0012, item 17): C1 a C5 e C7 a C10 (a C6, de cheque, fica vaga e não aparece).
 * Cada uma é uma consulta única sobre o banco, em Real, com os valores gravados (Dólar e Guarani nunca entram), e devolve cada
 * linha que não bate com o que era esperado e o que está gravado. Só leitura, num retrato só do banco (uma transação de leitura
 * com isolamento repetível), e nunca corrige nada: quem acha a diferença avisa o suporte. A única conta de dinheiro feita aqui é a
 * da comissão (C3), e só pelo utilitário {@link Dinheiro}. Os testes usam esta mesma classe, sobre um banco estragado de propósito.
 */
@Service
public class ConciliacoesDaFase7 {

    public static final List<String> CHAVES = List.of("C1", "C2", "C3", "C4", "C5", "C7", "C8", "C9", "C10");

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

    /** As nove, num retrato só do banco. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Resultado conferir() {
        return resultado(CHAVES);
    }

    /** Uma só (chave desconhecida, inclusive a C6 que está vaga: recusa em português). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Resultado conferir(String chave) {
        String limpa = chave == null ? "" : chave.strip().toUpperCase(Locale.ROOT);
        if (!CHAVES.contains(limpa)) {
            throw Validacoes.recusar("chave", "Escolha uma das conferências da lista: C1, C2, C3, C4, C5, C7, C8, C9 ou C10.");
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
                default -> c10();
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
                         WHERE (CASE WHEN c.valor_cancelado > 0 THEN 'CANCELADA' WHEN c.valor_pago = c.valor THEN 'PAGA'
                                     WHEN c.valor_pago > 0 THEN 'PARCIAL' ELSE 'ABERTA' END) <> c.status
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
                regra(Unidade.PECAS, "MALETA", "r.id", "Esta maleta já foi acertada, mas ainda sobra peça nela.", "0", """
                        (SELECT coalesce(sum(t.q), 0) FROM (
                            SELECT i.qtd_enviada AS q FROM corona.itens_remessa i WHERE i.remessa_id = r.id AND i.deleted_at IS NULL
                            UNION ALL SELECT m.quantidade FROM corona.movimentos_remessa m WHERE m.remessa_id = r.id) t)""",
                        "corona.remessas r", "r.status = 'ACERTADA'"),
                regra(Unidade.PECAS, "PENDENCIA", "p.id", "Esta falta não tem a perda correspondente na maleta.", "p.quantidade", """
                        coalesce((SELECT -sum(m.quantidade) FROM corona.movimentos_remessa m
                                   WHERE m.remessa_id = p.remessa_id AND m.produto_id = p.produto_id AND m.tipo = 'PERDA'), 0)""",
                        "corona.pendencias_acerto p", "p.tipo = 'FALTA' AND p.remessa_id IS NOT NULL"));
        return new Conferencia("C8", "Maletas acertadas e faltas",
                "Toda maleta acertada fica com saldo zero, e cada falta registrada no acerto tem a perda da mesma quantidade na maleta.",
                contar("SELECT (SELECT count(*) FROM corona.remessas WHERE status = 'ACERTADA')"
                        + " + (SELECT count(*) FROM corona.pendencias_acerto WHERE tipo = 'FALTA')"), d);
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
}
