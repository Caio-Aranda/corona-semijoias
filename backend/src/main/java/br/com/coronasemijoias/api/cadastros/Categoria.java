package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.persistencia.EntidadeSincronizavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Categoria de produto (tabela sincronizável {@code categorias}). */
@Entity
@Table(name = "categorias", schema = "corona")
public class Categoria extends EntidadeSincronizavel implements Ativavel {

    @Column(name = "nome", nullable = false)
    private String nome;

    protected Categoria() {
        // Hibernate
    }

    public Categoria(UUID id, String nome) {
        super(id);
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
