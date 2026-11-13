package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.OrdemDoBanco;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A conferência do pedido e o cálculo do valor que a devolução (tarefa 6.3) e o pedido de autorização (tarefa 6.4)
 * dividem: motivo, observação, peças, as linhas da venda, o que ainda pode voltar, o preço pago e o prazo da garantia em
 * dias de Brasília. Mesmas regras e mesmos textos nos dois caminhos.
 */
final class RegrasDaDevolucao {

    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    static final int LIMITE_DO_TEXTO = 400;
    private static final int LIMITE_DE_LINHAS = 200;
    private static final int LIMITE_DA_QUANTIDADE = 100_000;
    private static final Set<String> MOTIVOS = Set.of("ARREPENDIMENTO", "DEFEITO", "TAMANHO", "OUTRO");
    private static final Set<String> ESTADOS = Set.of("BOA", "DEFEITO");

    record Peca(UUID produtoId, int quantidade, String estado) {
    }

    record LinhaDaVenda(UUID id, UUID produtoId, String nome, int quantidade, BigDecimal total) {
    }

    /** Uma peça do pedido já com a linha da venda e o preço pago das unidades (o que já voltou descontado). */
    record PecaCalculada(LinhaDaVenda linha, int quantidade, String estado, BigDecimal valor) {
    }

    private RegrasDaDevolucao() {
    }

    static String conferirMotivo(String motivo) {
        if (motivo == null || !MOTIVOS.contains(motivo)) {
            throw Validacoes.recusar("motivo", "Escolha o motivo da devolução: desistência, defeito, não serviu ou outro.");
        }
        return motivo;
    }

    /** A observação limpa (vazia = nula), no máximo 400 caracteres, obrigatória com o motivo "outro". */
    static String conferirObservacao(String observacao, String motivo) {
        String limpa = Validacoes.limpar(observacao);
        if (limpa != null && limpa.length() > LIMITE_DO_TEXTO) {
            throw Validacoes.recusar("observacao", "Use no máximo " + LIMITE_DO_TEXTO + " caracteres.");
        }
        if ("OUTRO".equals(motivo) && limpa == null) {
            throw Validacoes.recusar("observacao", "Conte o motivo da devolução no campo de observação.");
        }
        return limpa;
    }

    static List<Peca> conferirPecas(List<PecaDoPedido> pedidas, String motivo) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw Validacoes.recusar("itens", "Informe pelo menos uma peça devolvida.");
        }
        if (pedidas.size() > LIMITE_DE_LINHAS) {
            throw Validacoes.recusar("itens", "Devolva no máximo " + LIMITE_DE_LINHAS + " peças diferentes por vez.");
        }
        List<Peca> pecas = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (PecaDoPedido p : pedidas) {
            if (p == null || p.produtoId() == null) {
                throw Validacoes.recusar("produtoId", "Escolha a peça devolvida.");
            }
            if (p.quantidade() == null || p.quantidade() <= 0) {
                throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
            }
            if (p.quantidade() > LIMITE_DA_QUANTIDADE) {
                throw Validacoes.recusar("quantidade", "A quantidade é grande demais. Confira o número.");
            }
            if (p.estado() == null || !ESTADOS.contains(p.estado())) {
                throw Validacoes.recusar("estado", "Diga se a peça voltou boa ou com defeito.");
            }
            if ("DEFEITO".equals(motivo) && !"DEFEITO".equals(p.estado())) {
                // O prazo maior do defeito só vale para peça com defeito (dúvida Q3 da 6.3).
                throw Validacoes.recusar("estado", "Com o motivo defeito, marque as peças como com defeito.");
            }
            if (!vistas.add(p.produtoId() + "/" + p.estado())) {
                throw Validacoes.recusar("itens",
                        "A mesma peça aparece mais de uma vez com o mesmo estado. Junte as quantidades numa linha só.");
            }
            pecas.add(new Peca(p.produtoId(), p.quantidade(), p.estado()));
        }
        return pecas;
    }

    /** As peças agrupadas por produto, em ordem estável (produto na ordem do banco; boa antes de com defeito). */
    static Map<UUID, List<Peca>> porProduto(List<Peca> pecas) {
        List<Peca> ordenadas = new ArrayList<>(pecas);
        ordenadas.sort(Comparator.comparing(Peca::produtoId, OrdemDoBanco.UUIDS).thenComparing(Peca::estado));
        Map<UUID, List<Peca>> grupos = new LinkedHashMap<>();
        for (Peca p : ordenadas) {
            grupos.computeIfAbsent(p.produtoId(), k -> new ArrayList<>()).add(p);
        }
        return grupos;
    }

    /** A quantidade pedida de cada produto (somando os estados). */
    static Map<UUID, Integer> quantidadePorProduto(List<Peca> pecas) {
        Map<UUID, Integer> total = new LinkedHashMap<>();
        porProduto(pecas).forEach((produto, doProduto) ->
                total.put(produto, doProduto.stream().mapToInt(Peca::quantidade).sum()));
        return total;
    }

    /**
     * As peças contra as linhas da venda (uma linha por produto) e contra o que ainda pode voltar, com o preço pago de
     * cada uma ({@link ValorDaDevolucao}). Peça fora da venda: 400; acima do que pode voltar: 409. Quem chama já travou a
     * venda.
     */
    static List<PecaCalculada> calcular(JdbcTemplate jdbc, UUID vendaId, List<Peca> pecas) {
        Map<UUID, LinhaDaVenda> linhas = new LinkedHashMap<>();
        jdbc.query("""
                SELECT i.id, i.produto_id, p.nome, i.quantidade, i.preco_unitario * i.quantidade - i.desconto AS total
                  FROM corona.itens_venda i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.venda_id = ?""", rs -> {
                    UUID produto = rs.getObject("produto_id", UUID.class);
                    linhas.put(produto, new LinhaDaVenda(rs.getObject("id", UUID.class), produto, rs.getString("nome"),
                            rs.getInt("quantidade"), rs.getBigDecimal("total")));
                }, vendaId);
        for (Peca p : pecas) {
            if (!linhas.containsKey(p.produtoId())) {
                throw Validacoes.recusar("produtoId", "Esta peça não consta nesta venda.");
            }
        }
        List<PecaCalculada> calculadas = new ArrayList<>();
        for (Map.Entry<UUID, List<Peca>> doProduto : porProduto(pecas).entrySet()) {
            LinhaDaVenda linha = linhas.get(doProduto.getKey());
            Map<String, Object> ja = jdbc.queryForMap("""
                    SELECT coalesce(sum(i.quantidade), 0) AS quantidade, coalesce(sum(i.valor), 0) AS valor
                      FROM corona.itens_devolucao i JOIN corona.devolucoes d ON d.id = i.devolucao_id
                     WHERE i.item_venda_id = ? AND d.situacao <> 'CANCELADA'""", linha.id());
            int jaDevolvidas = ((Number) ja.get("quantidade")).intValue();
            BigDecimal valorJa = Dinheiro.somar(List.of((BigDecimal) ja.get("valor")));
            int pedidas = doProduto.getValue().stream().mapToInt(Peca::quantidade).sum();
            int podem = linha.quantidade() - jaDevolvidas;
            if (pedidas > podem) {
                throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.DEVOLUCAO_ACIMA_DO_VENDIDO, podem <= 0
                        ? "Todas as peças de \"" + linha.nome() + "\" desta venda já foram devolvidas."
                        : "Desta venda ainda podem voltar só " + podem + " peça(s) de \"" + linha.nome()
                                + "\". Confira a quantidade.");
            }
            for (Peca p : doProduto.getValue()) {
                BigDecimal valor = ValorDaDevolucao.calcular(linha.total(), linha.quantidade(), jaDevolvidas, valorJa,
                        p.quantidade());
                calculadas.add(new PecaCalculada(linha, p.quantidade(), p.estado(), valor));
                jaDevolvidas += p.quantidade();
                valorJa = valorJa.add(valor);
            }
        }
        return calculadas;
    }

    static BigDecimal somar(List<PecaCalculada> pecas) {
        BigDecimal total = BigDecimal.ZERO.setScale(Dinheiro.ESCALA);
        for (PecaCalculada p : pecas) {
            total = total.add(p.valor());
        }
        return total;
    }

    /** O prazo da garantia pelo motivo (ADR-0014, item 4), lido a cada uso. */
    static int prazo(Configuracoes configuracoes, String motivo) {
        return configuracoes.inteiro("DEFEITO".equals(motivo) ? ChaveDeConfiguracao.PRAZO_DEFEITO_DIAS
                : ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS);
    }

    /** Dias do calendário de Brasília entre a venda e o momento. */
    static long diasDesdeAVenda(Instant vendidaEm, Instant quando) {
        return ChronoUnit.DAYS.between(dia(vendidaEm), dia(quando));
    }

    static LocalDate dia(Instant instante) {
        return LocalDate.ofInstant(instante, BRASILIA);
    }

    /** A cotação mais recente até o dia (horário de Brasília), ou nenhuma. */
    static UUID cotacaoDoDia(JdbcTemplate jdbc, LocalDate dia) {
        return jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), dia).stream().findFirst().orElse(null);
    }
}
