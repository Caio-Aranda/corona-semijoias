package br.com.coronasemijoias.api.comum.persistencia;

import br.com.coronasemijoias.api.comum.identificadores.Identificadores;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Base das entidades de tabelas sincronizáveis (ADR-0002, item 3). A tabela é criada com
 * {@code corona.aplicar_convencoes_sincronizavel} e registrada em {@code RegistroDeTabelas}.
 *
 * <p>O {@code id} vem de quem cria o registro (o app, ou {@code UuidV7} para o que nasce no
 * servidor). Os campos que o gatilho preenche são <strong>só leitura</strong> aqui: com
 * {@link Generated}, o Hibernate não os envia no INSERT nem no UPDATE e relê o valor gravado pelo
 * gatilho logo depois. O {@code version} continua sendo a versão da edição otimista: o Hibernate
 * manda {@code WHERE version = <a versão que leu>} e, se outra transação gravou antes, a
 * gravação falha com {@code OptimisticLockException}.
 */
@MappedSuperclass
public abstract class EntidadeSincronizavel {

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

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "origem_dispositivo")
    private UUID origemDispositivo;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "sync_seq", nullable = false)
    private Long syncSeq;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Convert(converter = XidComoNumero.class)
    @Column(name = "sync_xid", nullable = false)
    private Long syncXid;

    /** Só para o Hibernate. */
    protected EntidadeSincronizavel() {
    }

    protected EntidadeSincronizavel(UUID id) {
        if (!Identificadores.aceito(id)) {
            throw new IllegalArgumentException("Identificador fora da regra (só UUID v4 ou v7): " + id);
        }
        this.id = id;
    }

    /** Exclusão lógica: é um update que preenche {@code deleted_at} (o registro volta no pull como lápide). */
    public void marcarComoExcluida(Instant quando) {
        this.deletedAt = Objects.requireNonNull(quando, "quando");
    }

    public boolean estaExcluida() {
        return deletedAt != null;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getOrigemDispositivo() {
        return origemDispositivo;
    }

    public Long getSyncSeq() {
        return syncSeq;
    }

    public Long getSyncXid() {
        return syncXid;
    }
}
