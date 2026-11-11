package br.com.coronasemijoias.api.arquivos;

/**
 * Onde ficam os arquivos que a dona envia (documentos do vendedor, fotos de produto): fora do
 * PostgreSQL, que guarda só a {@code chave} devolvida aqui. Hoje é uma pasta do servidor
 * ({@link ArmazenamentoEmDisco}); um bucket S3 ou Backblaze entra trocando a implementação.
 *
 * <p>A chave é gerada aqui (nunca digitada por alguém) e não muda. A exclusão do
 * cadastro é lógica e o arquivo fica para o backup e para a auditoria; só a limpeza de fotos trocadas apaga
 * arquivo, depois de uma semana.
 */
public interface ArmazenamentoDeArquivos {

    /**
     * Grava o conteúdo e devolve a chave dele.
     *
     * @param area     agrupamento do arquivo (por exemplo {@code vendedores}); só minúsculas, números e hífen
     * @param extensao extensão sem ponto (por exemplo {@code pdf})
     */
    String guardar(String area, byte[] conteudo, String extensao);

    /** O conteúdo guardado com a chave; {@link ArquivoNaoEncontradoException} se não existe. */
    byte[] ler(String chave);

    boolean existe(String chave);

    /** Apaga o arquivo da chave; não é erro se ele já não existe. Só a limpeza de fotos trocadas usa. */
    void apagar(String chave);
}
