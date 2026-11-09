package br.com.coronasemijoias.api.comum.persistencia;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Base das entidades de tabelas só do servidor <strong>editáveis</strong>, criadas com
 * {@code corona.aplicar_convencoes_do_servidor(..., editavel => true)} (ADR-0002, item 3). Não tem
 * campos de sincronização. O {@code id} nasce aqui, como UUID v7.
 *
 * <p>{@code deleted_at} só existe nas tabelas em que a exclusão faz sentido; a subclasse declara.
 * Tabelas de histórico (só inserção, sem {@code updated_at} nem {@code version}) não usam esta base.
 *
 * <p>Os campos do gatilho são só leitura, como em {@link EntidadeSincronizavel}.
 */
@MappedSuperclass
public abstract class EntidadeDoServidor {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Version
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "version", nullable = false)
    private Integer version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Novo registro, com {@code id} UUID v7 gerado no servidor (também usado pelo Hibernate ao ler). */
    protected EntidadeDoServidor() {
        this.id = UuidV7.gerar();
    }

    public UUID getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
