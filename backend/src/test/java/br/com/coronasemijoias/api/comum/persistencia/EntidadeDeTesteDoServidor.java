package br.com.coronasemijoias.api.comum.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Entidade só de teste, sobre a tabela {@code teste_servidor} (migração V9001). */
@Entity
@Table(name = "teste_servidor")
public class EntidadeDeTesteDoServidor extends EntidadeDoServidor {

    @Column(name = "descricao", nullable = false)
    private String descricao;

    protected EntidadeDeTesteDoServidor() {
    }

    public EntidadeDeTesteDoServidor(String descricao) {
        this.descricao = descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }
}
