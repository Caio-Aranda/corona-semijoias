package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.persistencia.EntidadeSincronizavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cliente de quem vende (tabela sincronizável {@code clientes}). CPF só com dígitos. O vendedor
 * responsável aponta para um vendedor (chave estrangeira desde a tarefa 1.3).
 */
@Entity
@Table(name = "clientes", schema = "corona")
public class Cliente extends EntidadeSincronizavel implements Ativavel {

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "cpf")
    private String cpf;

    @Column(name = "data_nascimento")
    private LocalDate dataNascimento;

    @Column(name = "telefone")
    private String telefone;

    @Column(name = "whatsapp")
    private String whatsapp;

    private Endereco endereco;

    @Column(name = "observacoes")
    private String observacoes;

    @Column(name = "vendedor_responsavel_id")
    private UUID vendedorResponsavelId;

    /** Documento livre do cliente (CPF, RG, CI paraguaia...) e o tipo dele: CPF, RG, CI ou OUTRO (ADR-0016, item 4). */
    @Column(name = "documento")
    private String documento;

    @Column(name = "documento_tipo")
    private String documentoTipo;

    /** Quem indicou: um cliente já cadastrado OU um nome livre, nunca os dois (restrição do banco). */
    @Column(name = "indicado_por_cliente_id")
    private UUID indicadoPorClienteId;

    @Column(name = "indicado_por_nome")
    private String indicadoPorNome;

    @Column(name = "como_conheceu")
    private String comoConheceu;

    /** Comissão do cliente no acerto, de 0 a 40 (padrão 30). */
    @Column(name = "comissao_percentual", nullable = false)
    private BigDecimal comissaoPercentual = new BigDecimal("30.00");

    protected Cliente() {
        // Hibernate
    }

    public Cliente(UUID id, String nome, String cpf, LocalDate dataNascimento, String telefone, String whatsapp, Endereco endereco,
            String observacoes, UUID vendedorResponsavelId) {
        super(id);
        alterar(nome, cpf, dataNascimento, telefone, whatsapp, endereco, observacoes, vendedorResponsavelId);
    }

    public void alterar(String nome, String cpf, LocalDate dataNascimento, String telefone, String whatsapp, Endereco endereco,
            String observacoes, UUID vendedorResponsavelId) {
        this.nome = nome;
        this.cpf = cpf;
        this.dataNascimento = dataNascimento;
        this.telefone = telefone;
        this.whatsapp = whatsapp;
        this.endereco = endereco;
        this.observacoes = observacoes;
        this.vendedorResponsavelId = vendedorResponsavelId;
    }

    /** Os campos do consignado (ADR-0016): o que a dona preenche além do cadastro de sempre. Comissão vazia mantém a que já existe. */
    public void alterarConsignado(String documento, String documentoTipo, UUID indicadoPorClienteId, String indicadoPorNome,
            String comoConheceu, BigDecimal comissaoPercentual) {
        this.documento = documento;
        this.documentoTipo = documentoTipo;
        this.indicadoPorClienteId = indicadoPorClienteId;
        this.indicadoPorNome = indicadoPorNome;
        this.comoConheceu = comoConheceu;
        if (comissaoPercentual != null) {
            this.comissaoPercentual = comissaoPercentual;
        }
    }

    public String getDocumento() {
        return documento;
    }

    public String getDocumentoTipo() {
        return documentoTipo;
    }

    public UUID getIndicadoPorClienteId() {
        return indicadoPorClienteId;
    }

    public String getIndicadoPorNome() {
        return indicadoPorNome;
    }

    public String getComoConheceu() {
        return comoConheceu;
    }

    public BigDecimal getComissaoPercentual() {
        return comissaoPercentual;
    }

    public String getNome() {
        return nome;
    }

    public String getCpf() {
        return cpf;
    }

    public LocalDate getDataNascimento() {
        return dataNascimento;
    }

    public String getTelefone() {
        return telefone;
    }

    public String getWhatsapp() {
        return whatsapp;
    }

    public Endereco getEndereco() {
        return endereco;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public UUID getVendedorResponsavelId() {
        return vendedorResponsavelId;
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
