package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Aba "MEI": a empresa do vendedor, quando ele é microempreendedor. O CNPJ é conferido pelo servidor. */
@Entity
@Table(name = "vendedores_mei", schema = "corona")
public class MeiDoVendedor extends SecaoDoVendedor {

    public record Dados(
            @Size(max = 20, message = Mensagens.LIMITE_CURTO) String cnpj,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String razaoSocial,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String nomeFantasia,
            LocalDate dataAbertura,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String atividade,
            Integer version) {
    }

    @Column(name = "cnpj")
    private String cnpj;
    @Column(name = "razao_social")
    private String razaoSocial;
    @Column(name = "nome_fantasia")
    private String nomeFantasia;
    @Column(name = "data_abertura")
    private LocalDate dataAbertura;
    @Column(name = "atividade")
    private String atividade;

    protected MeiDoVendedor() {
        // Hibernate
    }

    public MeiDoVendedor(UUID vendedorId) {
        super(vendedorId);
    }

    public void aplicar(Dados d) {
        this.cnpj = Validacoes.cnpjOpcional(d.cnpj());
        this.razaoSocial = SecoesDoVendedor.limpar(d.razaoSocial());
        this.nomeFantasia = SecoesDoVendedor.limpar(d.nomeFantasia());
        this.dataAbertura = d.dataAbertura();
        this.atividade = SecoesDoVendedor.limpar(d.atividade());
    }

    public Dados dados() {
        return new Dados(cnpj, razaoSocial, nomeFantasia, dataAbertura, atividade, getVersion());
    }

    public static Dados vazia() {
        return new Dados(null, null, null, null, null, 0);
    }
}
