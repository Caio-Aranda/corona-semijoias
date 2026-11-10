package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.comum.persistencia.CadastroDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Cadastro do vendedor (tabela só do servidor {@code vendedores}). O vendedor nunca recebe estes dados:
 * limite de consignação, comissão e restrição são da dona (ESPECIFICACAO §4, §10). CPF ou CNPJ só com
 * os caracteres do número. As abas (endereço, trabalho, cônjuge...) ficam em tabelas filhas.
 */
@Entity
@Table(name = "vendedores", schema = "corona")
public class Vendedor extends CadastroDoServidor {

    /** Os campos do cadastro como a dona preencheu (já conferidos e limpos pelo serviço). */
    public record Dados(String nome, LocalDate dataNascimento, String sexo, boolean estrangeiro, String rgIe,
            String rgUf, String cpfCnpj, String naturalidade, String escolaridade, String telefone1, String telefone2,
            String whatsapp, String email, String facebook, String outrasMidias, BigDecimal limiteConsignacao,
            UUID restricaoId, BigDecimal comissaoPercentual, UUID pracaId) {
    }

    /** O banco dá o código na gravação (sequência); aqui só se lê. */
    @Generated(event = EventType.INSERT)
    @Column(name = "codigo", nullable = false, insertable = false, updatable = false)
    private Integer codigo;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Generated(event = EventType.INSERT)
    @Column(name = "data_cadastro", nullable = false, insertable = false, updatable = false)
    private LocalDate dataCadastro;

    @Column(name = "data_nascimento")
    private LocalDate dataNascimento;

    @Column(name = "sexo")
    private String sexo;

    @Column(name = "estrangeiro", nullable = false)
    private boolean estrangeiro;

    @Column(name = "rg_ie")
    private String rgIe;

    @Column(name = "rg_uf")
    private String rgUf;

    @Column(name = "cpf_cnpj")
    private String cpfCnpj;

    @Column(name = "naturalidade")
    private String naturalidade;

    @Column(name = "escolaridade")
    private String escolaridade;

    @Column(name = "telefone1")
    private String telefone1;

    @Column(name = "telefone2")
    private String telefone2;

    @Column(name = "whatsapp")
    private String whatsapp;

    @Column(name = "email")
    private String email;

    @Column(name = "facebook")
    private String facebook;

    @Column(name = "outras_midias")
    private String outrasMidias;

    @Column(name = "limite_consignacao", nullable = false)
    private BigDecimal limiteConsignacao;

    @Column(name = "restricao_id")
    private UUID restricaoId;

    @Column(name = "comissao_percentual", nullable = false)
    private BigDecimal comissaoPercentual;

    @Column(name = "praca_id")
    private UUID pracaId;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    @Column(name = "cadastrado_por", updatable = false)
    private UUID cadastradoPor;

    protected Vendedor() {
        // Hibernate
    }

    public Vendedor(Dados dados, UUID cadastradoPor) {
        alterar(dados);
        this.cadastradoPor = cadastradoPor;
    }

    public void alterar(Dados d) {
        this.nome = d.nome();
        this.dataNascimento = d.dataNascimento();
        this.sexo = d.sexo();
        this.estrangeiro = d.estrangeiro();
        this.rgIe = d.rgIe();
        this.rgUf = d.rgUf();
        this.cpfCnpj = d.cpfCnpj();
        this.naturalidade = d.naturalidade();
        this.escolaridade = d.escolaridade();
        this.telefone1 = d.telefone1();
        this.telefone2 = d.telefone2();
        this.whatsapp = d.whatsapp();
        this.email = d.email();
        this.facebook = d.facebook();
        this.outrasMidias = d.outrasMidias();
        this.limiteConsignacao = d.limiteConsignacao();
        this.restricaoId = d.restricaoId();
        this.comissaoPercentual = d.comissaoPercentual();
        this.pracaId = d.pracaId();
    }

    public void definirAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public Integer getCodigo() {
        return codigo;
    }

    public String getNome() {
        return nome;
    }

    public LocalDate getDataCadastro() {
        return dataCadastro;
    }

    public LocalDate getDataNascimento() {
        return dataNascimento;
    }

    public String getSexo() {
        return sexo;
    }

    public boolean isEstrangeiro() {
        return estrangeiro;
    }

    public String getRgIe() {
        return rgIe;
    }

    public String getRgUf() {
        return rgUf;
    }

    public String getCpfCnpj() {
        return cpfCnpj;
    }

    public String getNaturalidade() {
        return naturalidade;
    }

    public String getEscolaridade() {
        return escolaridade;
    }

    public String getTelefone1() {
        return telefone1;
    }

    public String getTelefone2() {
        return telefone2;
    }

    public String getWhatsapp() {
        return whatsapp;
    }

    public String getEmail() {
        return email;
    }

    public String getFacebook() {
        return facebook;
    }

    public String getOutrasMidias() {
        return outrasMidias;
    }

    public BigDecimal getLimiteConsignacao() {
        return limiteConsignacao;
    }

    public UUID getRestricaoId() {
        return restricaoId;
    }

    public BigDecimal getComissaoPercentual() {
        return comissaoPercentual;
    }

    public UUID getPracaId() {
        return pracaId;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public UUID getCadastradoPor() {
        return cadastradoPor;
    }
}
