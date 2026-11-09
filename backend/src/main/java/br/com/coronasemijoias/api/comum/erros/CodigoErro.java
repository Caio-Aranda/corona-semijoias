package br.com.coronasemijoias.api.comum.erros;

/**
 * Códigos de erro para o programa (campo {@code codigo} da resposta) e os textos em português para
 * a pessoa. Os textos vêm de docs/glossario-interface.md ("Erros gerais da API"): para mudar um
 * texto, mude o glossário primeiro.
 */
public enum CodigoErro {

    ERRO_INTERNO(
            "Algo deu errado agora.",
            "Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema."),

    ROTA_NAO_ENCONTRADA(
            "Não encontramos o que você pediu.",
            "Não encontramos o que você pediu. Atualize o aplicativo e tente de novo. "
                    + "Se continuar, fale com o suporte do sistema."),

    DADOS_INVALIDOS(
            "Alguns dados não estão corretos.",
            "Alguns dados não estão corretos. Confira o que você preencheu e tente de novo."),

    PEDIDO_INVALIDO(
            "Não foi possível entender o pedido.",
            "Não foi possível entender o pedido. Atualize o aplicativo e tente de novo. "
                    + "Se continuar, fale com o suporte do sistema.");

    private final String titulo;
    private final String mensagem;

    CodigoErro(String titulo, String mensagem) {
        this.titulo = titulo;
        this.mensagem = mensagem;
    }

    public String titulo() {
        return titulo;
    }

    public String mensagem() {
        return mensagem;
    }
}
