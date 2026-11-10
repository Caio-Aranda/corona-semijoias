package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.persistencia.CadastroDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Fornecedor das peças (tabela só do servidor {@code fornecedores}; o vendedor nunca recebe). O CNPJ
 * é guardado só com os caracteres do número.
 */
@Entity
@Table(name = "fornecedores", schema = "corona")
public class Fornecedor extends CadastroDoServidor {

    @Column(name = "razao_social", nullable = false)
    private String razaoSocial;

    @Column(name = "cnpj")
    private String cnpj;

    @Column(name = "contato_nome")
    private String contatoNome;

    @Column(name = "telefone")
    private String telefone;

    @Column(name = "email")
    private String email;

    private Endereco endereco;

    protected Fornecedor() {
        // Hibernate
    }

    public Fornecedor(String razaoSocial, String cnpj, String contatoNome, String telefone, String email,
            Endereco endereco) {
        alterar(razaoSocial, cnpj, contatoNome, telefone, email, endereco);
    }

    public void alterar(String razaoSocial, String cnpj, String contatoNome, String telefone, String email,
            Endereco endereco) {
        this.razaoSocial = razaoSocial;
        this.cnpj = cnpj;
        this.contatoNome = contatoNome;
        this.telefone = telefone;
        this.email = email;
        this.endereco = endereco;
    }

    public String getRazaoSocial() {
        return razaoSocial;
    }

    public String getCnpj() {
        return cnpj;
    }

    public String getContatoNome() {
        return contatoNome;
    }

    public String getTelefone() {
        return telefone;
    }

    public String getEmail() {
        return email;
    }

    public Endereco getEndereco() {
        return endereco;
    }
}
