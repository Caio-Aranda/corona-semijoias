package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.comum.persistencia.CadastroDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Documento do vendedor (RG, comprovante de endereço...). O banco guarda só os dados do arquivo e a
 * {@code chave} dele no armazenamento; o conteúdo nunca vai para o PostgreSQL. A chave não sai da API.
 */
@Entity
@Table(name = "vendedores_documentos", schema = "corona")
public class DocumentoDoVendedor extends CadastroDoServidor {

    /** O que a tela vê de um documento (sem a chave do arquivo). */
    public record Resposta(UUID id, int version, String descricao, String nomeOriginal, String tipoConteudo,
            long tamanhoBytes, String hashSha256, java.time.Instant enviadoEm) {

        static Resposta de(DocumentoDoVendedor d) {
            return new Resposta(d.getId(), d.getVersion(), d.descricao, d.nomeOriginal, d.tipoConteudo, d.tamanhoBytes,
                    d.hashSha256, d.getCreatedAt());
        }
    }

    @Column(name = "vendedor_id", nullable = false, updatable = false)
    private UUID vendedorId;

    @Column(name = "descricao", nullable = false)
    private String descricao;

    @Column(name = "nome_original", nullable = false)
    private String nomeOriginal;

    @Column(name = "tipo_conteudo", nullable = false)
    private String tipoConteudo;

    @Column(name = "tamanho_bytes", nullable = false)
    private long tamanhoBytes;

    @Column(name = "hash_sha256", nullable = false)
    private String hashSha256;

    @Column(name = "chave_arquivo", nullable = false, updatable = false)
    private String chaveArquivo;

    @Column(name = "enviado_por", updatable = false)
    private UUID enviadoPor;

    protected DocumentoDoVendedor() {
        // Hibernate
    }

    public DocumentoDoVendedor(UUID vendedorId, String descricao, String nomeOriginal, String tipoConteudo,
            long tamanhoBytes, String hashSha256, String chaveArquivo, UUID enviadoPor) {
        this.vendedorId = vendedorId;
        this.descricao = descricao;
        this.nomeOriginal = nomeOriginal;
        this.tipoConteudo = tipoConteudo;
        this.tamanhoBytes = tamanhoBytes;
        this.hashSha256 = hashSha256;
        this.chaveArquivo = chaveArquivo;
        this.enviadoPor = enviadoPor;
    }

    public UUID getVendedorId() {
        return vendedorId;
    }

    public String getNomeOriginal() {
        return nomeOriginal;
    }

    public String getTipoConteudo() {
        return tipoConteudo;
    }

    public String getChaveArquivo() {
        return chaveArquivo;
    }

    public Resposta resposta() {
        return Resposta.de(this);
    }
}
