package br.com.coronasemijoias.api.cotacoes;

/**
 * O arquivo do BCB não segue a regra estrita do ADR-0006 (item 2). A leitura é recusada e nada é
 * gravado; a mensagem diz qual regra quebrou (vai para o registro do servidor, não para a tela).
 */
public class ArquivoForaDaRegraException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ArquivoForaDaRegraException(String mensagem) {
        super(mensagem);
    }
}
