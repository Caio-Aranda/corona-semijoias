package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.persistencia.CadastroDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Região ou rota atendida pelo vendedor (tabela só do servidor {@code pracas}). */
@Entity
@Table(name = "pracas", schema = "corona")
public class Praca extends CadastroDoServidor implements Ativavel {

    @Column(name = "nome", nullable = false)
    private String nome;

    protected Praca() {
        // Hibernate
    }

    public Praca(String nome) {
        this.nome = nome;
    }

    public void alterar(String nome) {
        this.nome = nome;
    }

    public String getNome() {
        return nome;
    }

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    @Override
    public boolean isAtivo() {
        return ativo;
    }

    @Override
    public void definirAtivo(boolean ativo) {
        this.ativo = ativo;
    }
}
