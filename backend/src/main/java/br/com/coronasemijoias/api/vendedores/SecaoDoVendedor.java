package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.comum.persistencia.EntidadeDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;

/**
 * Base das abas de uma linha só por vendedor (endereço, contato, trabalho, cônjuge, MEI e
 * observações). A aba nasce na primeira gravação e depois é regravada no lugar, com a versão que a
 * tela leu.
 */
@MappedSuperclass
public abstract class SecaoDoVendedor extends EntidadeDoServidor {

    @Column(name = "vendedor_id", nullable = false, updatable = false)
    private UUID vendedorId;

    protected SecaoDoVendedor() {
        // Hibernate
    }

    protected SecaoDoVendedor(UUID vendedorId) {
        this.vendedorId = vendedorId;
    }

    public UUID getVendedorId() {
        return vendedorId;
    }
}
