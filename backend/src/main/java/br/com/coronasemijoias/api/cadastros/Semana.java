package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.persistencia.EntidadeSincronizavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Semana de controle (tabela sincronizável {@code semanas}): número único e datas sem sobreposição; as duas pontas contam. */
@Entity
@Table(name = "semanas", schema = "corona")
public class Semana extends EntidadeSincronizavel {

    @Column(name = "numero", nullable = false)
    private int numero;

    @Column(name = "inicio", nullable = false)
    private LocalDate inicio;

    @Column(name = "fim", nullable = false)
    private LocalDate fim;

    @Enumerated(EnumType.STRING)
    @Column(name = "situacao", nullable = false)
    private SituacaoDaSemana situacao;

    protected Semana() {
        // Hibernate
    }

    public Semana(UUID id, int numero, LocalDate inicio, LocalDate fim, SituacaoDaSemana situacao) {
        super(id);
        alterar(numero, inicio, fim, situacao);
    }

    public void alterar(int numero, LocalDate inicio, LocalDate fim, SituacaoDaSemana situacao) {
        this.numero = numero;
        this.inicio = inicio;
        this.fim = fim;
        this.situacao = situacao;
    }

    public int getNumero() {
        return numero;
    }

    public LocalDate getInicio() {
        return inicio;
    }

    public LocalDate getFim() {
        return fim;
    }

    public SituacaoDaSemana getSituacao() {
        return situacao;
    }
}
