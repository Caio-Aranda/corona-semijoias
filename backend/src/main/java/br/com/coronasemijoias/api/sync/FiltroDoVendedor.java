package br.com.coronasemijoias.api.sync;

import java.util.List;
import java.util.Objects;

/**
 * Condição "só os dados deste vendedor", tabela por tabela, que o pull acrescenta ao
 * {@code WHERE}. A regra por vendedor (vinda do token) é da Fase 5; aqui ela já é um parâmetro.
 *
 * <p>Quem implementar precisa devolver uma condição para <strong>toda</strong> tabela registrada
 * (na dúvida, {@link CondicaoSql#NENHUMA_LINHA}). Atenção para a Fase 5: uma linha que passa a
 * pertencer ao vendedor sem ser alterada (por exemplo, um produto que entra numa maleta nova)
 * não volta sozinha no pull, porque o cursor já passou por ela.
 */
@FunctionalInterface
public interface FiltroDoVendedor {

    /** Sem filtro: todas as linhas (Corona Gestão e testes). */
    FiltroDoVendedor SEM_FILTRO = tabela -> CondicaoSql.TODAS_AS_LINHAS;

    CondicaoSql condicaoPara(TabelaSincronizavel tabela);

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
