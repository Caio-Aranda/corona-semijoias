package br.com.coronasemijoias.api.vendedores;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Aba "Observação": anotações livres da dona sobre o vendedor. */
@Entity
@Table(name = "vendedores_observacoes", schema = "corona")
public class ObservacaoDoVendedor extends SecaoDoVendedor {

    public record Dados(
            @Size(max = 4000, message = "Use no máximo {max} caracteres.") String texto,
            Integer version) {
    }

    @Column(name = "texto")
    private String texto;

    protected ObservacaoDoVendedor() {
        // Hibernate
    }

    public ObservacaoDoVendedor(UUID vendedorId) {
        super(vendedorId);
    }

    public void aplicar(Dados d) {
        this.texto = SecoesDoVendedor.limpar(d.texto());
    }

    public Dados dados() {
        return new Dados(texto, getVersion());
    }

    public static Dados vazia() {
        return new Dados(null, 0);
    }
}
