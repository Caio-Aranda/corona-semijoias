package br.com.coronasemijoias.api.comum.erros;

/** Um campo que não passou na conferência: o nome do campo (para o programa) e o texto para a pessoa. */
public record CampoInvalido(String campo, String mensagem) {
}
