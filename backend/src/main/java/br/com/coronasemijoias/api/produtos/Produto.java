package br.com.coronasemijoias.api.produtos;

import br.com.coronasemijoias.api.comum.persistencia.EntidadeSincronizavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Produto (tabela sincronizável {@code produtos}). NÃO leva o preço de custo (ele fica em
 * {@code produtos_custos}, só do servidor). O estoque atual é somente leitura aqui: quem o muda são
 * as movimentações de estoque (Fase 2).
 */
@Entity
@Table(name = "produtos", schema = "corona")
public class Produto extends EntidadeSincronizavel {

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "referencia")
    private String referencia;

    /** Tamanho ou tom da peça (texto curto, opcional). Sai nas notas e nos relatórios do consignado. */
    @Column(name = "tam_ton")
    private String tamTon;

    @Column(name = "categoria_id", nullable = false)
    private UUID categoriaId;

    @Column(name = "fornecedor_id")
    private UUID fornecedorId;

    @Column(name = "preco_venda", nullable = false)
    private BigDecimal precoVenda;

    @Column(name = "estoque_minimo", nullable = false)
    private int estoqueMinimo;

    @Column(name = "estoque_atual", nullable = false, insertable = false, updatable = false)
    private int estoqueAtual;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    /** Hash da foto principal: somente leitura aqui, quem o muda é o serviço de fotos (tarefa 1.6). */
    @Column(name = "foto_principal_hash", insertable = false, updatable = false)
    private String fotoPrincipalHash;

    protected Produto() {
        // Hibernate
    }

    public Produto(UUID id, String nome, String referencia, UUID categoriaId, UUID fornecedorId,
            BigDecimal precoVenda, int estoqueMinimo) {
        super(id);
        alterar(nome, referencia, categoriaId, fornecedorId, precoVenda, estoqueMinimo);
    }

    public void alterar(String nome, String referencia, UUID categoriaId, UUID fornecedorId,
            BigDecimal precoVenda, int estoqueMinimo) {
        this.nome = nome;
        this.referencia = referencia;
        this.categoriaId = categoriaId;
        this.fornecedorId = fornecedorId;
        this.precoVenda = precoVenda;
        this.estoqueMinimo = estoqueMinimo;
    }

    public void definirTamTon(String tamTon) {
        this.tamTon = tamTon;
    }

    public void definirEstoqueMinimo(int estoqueMinimo) {
        this.estoqueMinimo = estoqueMinimo;
    }

    public void definirAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public String getNome() {
        return nome;
    }

    public String getReferencia() {
        return referencia;
    }

    public String getTamTon() {
        return tamTon;
    }

    public UUID getCategoriaId() {
        return categoriaId;
    }

    public UUID getFornecedorId() {
        return fornecedorId;
    }

    public BigDecimal getPrecoVenda() {
        return precoVenda;
    }

    public int getEstoqueMinimo() {
        return estoqueMinimo;
    }

    public int getEstoqueAtual() {
        return estoqueAtual;
    }

    public String getFotoPrincipalHash() {
        return fotoPrincipalHash;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
