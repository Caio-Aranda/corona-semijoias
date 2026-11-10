package br.com.coronasemijoias.api.cadastros;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Uma página de uma lista de cadastros: {@code pagina} começa em 0. */
public record Pagina<T>(List<T> itens, long total, int pagina, int tamanho) {

    public static <E, T> Pagina<T> de(Page<E> pagina, Function<E, T> conversor) {
        return new Pagina<>(pagina.getContent().stream().map(conversor).toList(), pagina.getTotalElements(),
                pagina.getNumber(), pagina.getSize());
    }
}
