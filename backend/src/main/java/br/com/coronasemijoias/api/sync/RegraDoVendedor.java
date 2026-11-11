package br.com.coronasemijoias.api.sync;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * O que o vendedor V recebe de uma tabela sincronizável (ADR-0013, itens 4 e 5). Toda sincronizável da aplicação
 * declara a sua no {@link RegistroDeTabelas}; o teste de convenções reprova a que ficar {@link #NAO_DECLARADA} (e o
 * filtro, na dúvida, não entrega nada dela).
 *
 * @param condicao    trecho SQL sobre a linha {@code t}; cada {@code ?} recebe o {@code vendedor_id} do token
 * @param expansoes   o que vai junto quando a linha é entregue (regra 1 do item 5: "quem decide a pertença, expande")
 * @param diretorio   colunas que todo vendedor recebe; vazio = todas. As outras só saem quando
 *                    {@code colunaDoDono} = V (os clientes da loja: o diretório para todos, o resto só para quem
 *                    atende; linha datada de 11/11/2026)
 * @param colunaDoDono a coluna que diz de quem são as colunas fora do diretório
 * @param pertencaMuda a pertença da linha pode mudar depois de entregue (pediria a tabela de saídas, regra 2 do item
 *                    5); hoje nenhuma muda, e os gatilhos da V032 garantem
 */
public record RegraDoVendedor(String condicao, List<Expansao> expansoes, Set<String> diretorio, String colunaDoDono,
        boolean pertencaMuda) {

    /** Sem regra: o filtro não entrega nada e o teste de convenções reprova (só tabelas de teste podem ficar assim). */
    public static final RegraDoVendedor NAO_DECLARADA = new RegraDoVendedor("FALSE", List.of(), Set.of(), null, false);

    /**
     * Quando uma linha é entregue, as linhas de {@code tabela} cuja {@code colunaNaTabela} é igual à
     * {@code colunaNaLinha} da linha entregue vão junto, no estado atual e na mesma fotografia do banco.
     */
    public record Expansao(String tabela, String colunaNaTabela, String colunaNaLinha) {

        public Expansao {
            TabelaSincronizavel.identificador(tabela, "tabela da expansão");
            TabelaSincronizavel.identificador(colunaNaTabela, "coluna da expansão");
            TabelaSincronizavel.identificador(colunaNaLinha, "coluna da expansão");
        }
    }

    public RegraDoVendedor {
        Objects.requireNonNull(condicao, "condicao");
        expansoes = List.copyOf(expansoes);
        diretorio = Set.copyOf(diretorio);
        if (!diretorio.isEmpty()) {
            TabelaSincronizavel.identificador(colunaDoDono, "coluna do dono");
        }
    }

    /** Dado da loja, igual para todos os vendedores (cotações, categorias, semanas). */
    public static RegraDoVendedor daLoja() {
        return new RegraDoVendedor("TRUE", List.of(), Set.of(), null, false);
    }

    /** Todas as linhas, mas só o diretório para quem não é o dono da linha. */
    public static RegraDoVendedor diretorioDaLoja(Set<String> diretorio, String colunaDoDono) {
        return new RegraDoVendedor("TRUE", List.of(), diretorio, colunaDoDono, false);
    }

    /** Só as linhas que passam na condição (com o vendedor no lugar de cada {@code ?}). */
    public static RegraDoVendedor doVendedor(String condicao, Expansao... expansoes) {
        return new RegraDoVendedor(condicao, List.of(expansoes), Set.of(), null, false);
    }

    public boolean declarada() {
        return this != NAO_DECLARADA;
    }

    /** Quantos {@code ?} a condição tem (todos recebem o vendedor). */
    public int parametros() {
        return (int) condicao.chars().filter(c -> c == '?').count();
    }
}
