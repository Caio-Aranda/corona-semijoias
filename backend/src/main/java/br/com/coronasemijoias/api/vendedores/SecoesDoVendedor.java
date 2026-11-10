package br.com.coronasemijoias.api.vendedores;

import java.math.BigDecimal;

/** Pequenas limpezas que todas as abas do vendedor usam. */
final class SecoesDoVendedor {

    private SecoesDoVendedor() {
    }

    /** Texto sem espaços nas pontas; vazio vira {@code null}. */
    static String limpar(String texto) {
        return br.com.coronasemijoias.api.cadastros.Validacoes.limpar(texto);
    }

    /** Dinheiro com duas casas (a tela só manda até duas); {@code null} continua {@code null}. */
    static BigDecimal dinheiro(BigDecimal valor) {
        return valor == null ? null : valor.setScale(2);
    }
}
