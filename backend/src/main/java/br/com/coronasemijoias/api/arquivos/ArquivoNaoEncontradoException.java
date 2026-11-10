package br.com.coronasemijoias.api.arquivos;

/** A chave existe no banco, mas o arquivo não está no armazenamento (problema do servidor, não da pessoa). */
public class ArquivoNaoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ArquivoNaoEncontradoException(String chave) {
        super("Arquivo não encontrado no armazenamento: " + chave);
    }
}
