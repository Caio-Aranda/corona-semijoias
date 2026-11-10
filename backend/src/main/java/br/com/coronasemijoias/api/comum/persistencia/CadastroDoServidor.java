package br.com.coronasemijoias.api.comum.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Objects;

/** Base das entidades só do servidor, editáveis e com exclusão lógica ({@code deleted_at} declarado na tabela). */
@MappedSuperclass
public abstract class CadastroDoServidor extends EntidadeDoServidor implements Excluivel {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Override
    public void marcarComoExcluida(Instant quando) {
        this.deletedAt = Objects.requireNonNull(quando, "quando");
    }

    @Override
    public boolean estaExcluida() {
        return deletedAt != null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
