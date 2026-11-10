package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Aba "Trabalho": onde o vendedor trabalha e quanto ganha (a dona usa como garantia). */
@Entity
@Table(name = "vendedores_trabalhos", schema = "corona")
public class TrabalhoDoVendedor extends SecaoDoVendedor {

    public record Dados(
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String empresa,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String funcao,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String telefone,
            @Size(max = 300, message = Mensagens.LIMITE_LONGO) String endereco,
            LocalDate dataAdmissao,
            @DecimalMin(value = "0", message = Mensagens.VALOR_NEGATIVO) BigDecimal renda,
            Integer version) {
    }

    @Column(name = "empresa")
    private String empresa;
    @Column(name = "funcao")
    private String funcao;
    @Column(name = "telefone")
    private String telefone;
    @Column(name = "endereco")
    private String endereco;
    @Column(name = "data_admissao")
    private LocalDate dataAdmissao;
    @Column(name = "renda")
    private BigDecimal renda;

    protected TrabalhoDoVendedor() {
        // Hibernate
    }

    public TrabalhoDoVendedor(UUID vendedorId) {
        super(vendedorId);
    }

    public void aplicar(Dados d) {
        this.empresa = SecoesDoVendedor.limpar(d.empresa());
        this.funcao = SecoesDoVendedor.limpar(d.funcao());
        this.telefone = SecoesDoVendedor.limpar(d.telefone());
        this.endereco = SecoesDoVendedor.limpar(d.endereco());
        this.dataAdmissao = d.dataAdmissao();
        this.renda = SecoesDoVendedor.dinheiro(d.renda());
    }

    public Dados dados() {
        return new Dados(empresa, funcao, telefone, endereco, dataAdmissao, renda, getVersion());
    }

    public static Dados vazia() {
        return new Dados(null, null, null, null, null, null, 0);
    }
}
