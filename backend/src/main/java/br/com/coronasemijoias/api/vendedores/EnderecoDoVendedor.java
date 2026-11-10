package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Aba "Endereço" do vendedor: onde mora, ponto de referência e a moradia (própria, alugada) com o valor. */
@Entity
@Table(name = "vendedores_enderecos", schema = "corona")
public class EnderecoDoVendedor extends SecaoDoVendedor {

    /** O que a tela manda e recebe. {@code version} é 0 enquanto a aba nunca foi gravada. */
    public record Dados(
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String logradouro,
            @Size(max = 20, message = Mensagens.LIMITE_CURTO) String numero,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String complemento,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String bairro,
            @Size(max = 20, message = Mensagens.LIMITE_CURTO) String cep,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String cidade,
            @Size(max = 20, message = Mensagens.LIMITE_CURTO) String uf,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String localReferencia,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String residencia,
            @DecimalMin(value = "0", message = Mensagens.VALOR_NEGATIVO) BigDecimal valorResidencia,
            Integer version) {
    }

    @Column(name = "logradouro")
    private String logradouro;
    @Column(name = "numero")
    private String numero;
    @Column(name = "complemento")
    private String complemento;
    @Column(name = "bairro")
    private String bairro;
    @Column(name = "cep")
    private String cep;
    @Column(name = "cidade")
    private String cidade;
    @Column(name = "uf")
    private String uf;
    @Column(name = "local_referencia")
    private String localReferencia;
    @Column(name = "residencia")
    private String residencia;
    @Column(name = "valor_residencia")
    private BigDecimal valorResidencia;

    protected EnderecoDoVendedor() {
        // Hibernate
    }

    public EnderecoDoVendedor(UUID vendedorId) {
        super(vendedorId);
    }

    public void aplicar(Dados d) {
        this.logradouro = SecoesDoVendedor.limpar(d.logradouro());
        this.numero = SecoesDoVendedor.limpar(d.numero());
        this.complemento = SecoesDoVendedor.limpar(d.complemento());
        this.bairro = SecoesDoVendedor.limpar(d.bairro());
        this.cep = SecoesDoVendedor.limpar(d.cep());
        this.cidade = SecoesDoVendedor.limpar(d.cidade());
        this.uf = SecoesDoVendedor.limpar(d.uf());
        this.localReferencia = SecoesDoVendedor.limpar(d.localReferencia());
        this.residencia = SecoesDoVendedor.limpar(d.residencia());
        this.valorResidencia = SecoesDoVendedor.dinheiro(d.valorResidencia());
    }

    public Dados dados() {
        return new Dados(logradouro, numero, complemento, bairro, cep, cidade, uf, localReferencia, residencia,
                valorResidencia, getVersion());
    }

    /** A aba ainda não gravada: tudo vazio e versão 0. */
    public static Dados vazia() {
        return new Dados(null, null, null, null, null, null, null, null, null, null, 0);
    }
}
