package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.persistencia.CadastroDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Restrição que a dona põe num vendedor; {@code bloqueiaRemessa} impede montar maleta (tabela {@code restricoes}). */
@Entity
@Table(name = "restricoes", schema = "corona")
public class Restricao extends CadastroDoServidor implements Ativavel {

    @Column(name = "codigo", nullable = false)
    private String codigo;

    @Column(name = "descricao", nullable = false)
    private String descricao;

    @Column(name = "bloqueia_remessa", nullable = false)
    private boolean bloqueiaRemessa;

    protected Restricao() {
        // Hibernate
    }

    public Restricao(String codigo, String descricao, boolean bloqueiaRemessa) {
        alterar(codigo, descricao, bloqueiaRemessa);
    }

    public void alterar(String codigo, String descricao, boolean bloqueiaRemessa) {
        this.codigo = codigo;
        this.descricao = descricao;
        this.bloqueiaRemessa = bloqueiaRemessa;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescricao() {
        return descricao;
    }

    public boolean isBloqueiaRemessa() {
        return bloqueiaRemessa;
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
