package br.com.coronasemijoias.api.sync;

import java.util.List;
import java.util.Objects;

/**
 * Condição "só os dados deste vendedor", tabela por tabela, que o pull acrescenta ao {@code WHERE}. A implementação do
 * vendedor do token é {@link FiltroPorVendedor} (tarefa 5.4); {@link #SEM_FILTRO} é a da dona e dos testes.
 *
 * <p>Quem implementar precisa devolver uma condição para <strong>toda</strong> tabela registrada (na dúvida,
 * {@link CondicaoSql#NENHUMA_LINHA}). Uma linha que passa a pertencer ao vendedor sem ser alterada não volta sozinha no
 * pull, porque o cursor já passou por ela: por isso o filtro do vendedor também {@linkplain #expande() expande}
 * (ADR-0013, item 5, regra 1).
 */
@FunctionalInterface
public interface FiltroDoVendedor {

    /** Sem filtro: todas as linhas (Corona Gestão e testes). */
    FiltroDoVendedor SEM_FILTRO = tabela -> CondicaoSql.TODAS_AS_LINHAS;

    CondicaoSql condicaoPara(TabelaSincronizavel tabela);

    /**
     * A expressão SQL que entrega a coluna (sempre sobre {@code t}). Padrão: a própria coluna. O filtro do vendedor troca
     * por {@code NULL} as colunas fora do diretório dos clientes que não são dele.
     */
    default String expressao(TabelaSincronizavel tabela, String coluna) {
        return "t.\"" + coluna + '"';
    }

    /** Se o pull entrega as expansões declaradas nas regras (ADR-0013, item 5, regra 1). */
    default boolean expande() {
        return false;
    }

    /** Trecho SQL com {@code ?} e os parâmetros na mesma ordem. */
    record CondicaoSql(String sql, List<Object> parametros) {

        public static final CondicaoSql TODAS_AS_LINHAS = new CondicaoSql("TRUE", List.of());
        public static final CondicaoSql NENHUMA_LINHA = new CondicaoSql("FALSE", List.of());

        public CondicaoSql {
            Objects.requireNonNull(sql, "sql");
            parametros = List.copyOf(parametros);
        }
    }
}
