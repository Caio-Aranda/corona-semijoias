package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Aba "Filiação / Contato": pais e uma pessoa para avisar. */
@Entity
@Table(name = "vendedores_contatos", schema = "corona")
public class ContatoDoVendedor extends SecaoDoVendedor {

    public record Dados(
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String nomePai,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String nomeMae,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String contatoNome,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String contatoParentesco,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String contatoTelefone,
            Integer version) {
    }

    @Column(name = "nome_pai")
    private String nomePai;
    @Column(name = "nome_mae")
    private String nomeMae;
    @Column(name = "contato_nome")
    private String contatoNome;
    @Column(name = "contato_parentesco")
    private String contatoParentesco;
    @Column(name = "contato_telefone")
    private String contatoTelefone;

    protected ContatoDoVendedor() {
        // Hibernate
    }

    public ContatoDoVendedor(UUID vendedorId) {
        super(vendedorId);
    }

    public void aplicar(Dados d) {
        this.nomePai = SecoesDoVendedor.limpar(d.nomePai());
        this.nomeMae = SecoesDoVendedor.limpar(d.nomeMae());
        this.contatoNome = SecoesDoVendedor.limpar(d.contatoNome());
        this.contatoParentesco = SecoesDoVendedor.limpar(d.contatoParentesco());
        this.contatoTelefone = SecoesDoVendedor.limpar(d.contatoTelefone());
    }

    public Dados dados() {
        return new Dados(nomePai, nomeMae, contatoNome, contatoParentesco, contatoTelefone, getVersion());
    }

    public static Dados vazia() {
        return new Dados(null, null, null, null, null, 0);
    }
}
