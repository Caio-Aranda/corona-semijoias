package br.com.coronasemijoias.api.sync;

import java.util.List;

/**
 * Resultado de um pull. Sem linhas novas, {@code proximoCursor} é o mesmo cursor recebido.
 *
 * @param haMais pode haver mais linhas já liberadas: o app pede de novo com o próximo cursor
 */
public record LoteDoPull(List<LinhaDoPull> linhas, String proximoCursor, boolean haMais) {

    public LoteDoPull {
        linhas = List.copyOf(linhas);
    }
}
