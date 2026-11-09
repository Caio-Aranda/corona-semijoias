package br.com.coronasemijoias.api.comum.configuracao;

/**
 * Configuração que impede a API de subir (chave do token, primeiro administrador...). A descrição e
 * a ação são em português e aparecem no lugar do erro técnico ({@link AnalisadorConfiguracaoInvalida}).
 * Nunca levam o valor de um segredo, só o nome da variável.
 */
public class ConfiguracaoInvalidaException extends RuntimeException {

    private final String acao;

    public ConfiguracaoInvalidaException(String descricao, String acao) {
        super(descricao);
        this.acao = acao;
    }

    public String descricao() {
        return getMessage();
    }

    public String acao() {
        return acao;
    }
}
