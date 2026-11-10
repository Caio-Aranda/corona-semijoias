package br.com.coronasemijoias.api.cadastros;

/** Textos de conferência dos campos dos cadastros (glossário: o que está errado e o que fazer). */
public final class Mensagens {

    public static final String OBRIGATORIO = "Preencha este campo.";
    public static final String LIMITE_CURTO = "Use no máximo {max} caracteres.";
    public static final String LIMITE_LONGO = "Use no máximo {max} caracteres.";
    public static final String VALOR_NEGATIVO = "O valor não pode ser menor que zero.";
    public static final String OPCAO_INEXISTENTE = "Esta opção não existe mais. Atualize a tela e escolha de novo.";

    private Mensagens() {
    }
}
