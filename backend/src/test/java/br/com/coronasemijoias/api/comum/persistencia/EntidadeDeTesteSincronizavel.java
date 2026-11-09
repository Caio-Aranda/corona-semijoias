package br.com.coronasemijoias.api.comum.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** Entidade só de teste, sobre a tabela {@code teste_sync_a} (migração V9001). */
@Entity
@Table(name = "teste_sync_a")
public class EntidadeDeTesteSincronizavel extends EntidadeSincronizavel {

    @Column(name = "texto", nullable = false)
    private String texto;

    @Column(name = "valor", nullable = false)
    private BigDecimal valor = new BigDecimal("0.00");

    @Column(name = "quantidade", nullable = false)
    private int quantidade;

    protected EntidadeDeTesteSincronizavel() {
    }

    public EntidadeDeTesteSincronizavel(UUID id, String texto) {
        super(id);
        this.texto = texto;
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }
}
