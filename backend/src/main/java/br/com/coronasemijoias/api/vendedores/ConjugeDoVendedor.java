package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Aba "Cônjuge". O CPF é conferido pelo servidor e guardado só com os dígitos. */
@Entity
@Table(name = "vendedores_conjuges", schema = "corona")
public class ConjugeDoVendedor extends SecaoDoVendedor {

    public record Dados(
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String nome,
            @Size(max = 20, message = Mensagens.LIMITE_CURTO) String cpf,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String rg,
            LocalDate dataNascimento,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String telefone,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String profissao,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String empresa,
            @DecimalMin(value = "0", message = Mensagens.VALOR_NEGATIVO) BigDecimal renda,
            Integer version) {
    }

    @Column(name = "nome")
    private String nome;
    @Column(name = "cpf")
    private String cpf;
    @Column(name = "rg")
    private String rg;
    @Column(name = "data_nascimento")
    private LocalDate dataNascimento;
    @Column(name = "telefone")
    private String telefone;
    @Column(name = "profissao")
    private String profissao;
    @Column(name = "empresa")
    private String empresa;
    @Column(name = "renda")
    private BigDecimal renda;

    protected ConjugeDoVendedor() {
        // Hibernate
    }

    public ConjugeDoVendedor(UUID vendedorId) {
        super(vendedorId);
    }

    public void aplicar(Dados d) {
        this.nome = SecoesDoVendedor.limpar(d.nome());
        this.cpf = Validacoes.cpfOpcional(d.cpf());
        this.rg = SecoesDoVendedor.limpar(d.rg());
        this.dataNascimento = d.dataNascimento();
        this.telefone = SecoesDoVendedor.limpar(d.telefone());
        this.profissao = SecoesDoVendedor.limpar(d.profissao());
        this.empresa = SecoesDoVendedor.limpar(d.empresa());
        this.renda = SecoesDoVendedor.dinheiro(d.renda());
    }

    public Dados dados() {
        return new Dados(nome, cpf, rg, dataNascimento, telefone, profissao, empresa, renda, getVersion());
    }

    public static Dados vazia() {
        return new Dados(null, null, null, null, null, null, null, null, 0);
    }
}
