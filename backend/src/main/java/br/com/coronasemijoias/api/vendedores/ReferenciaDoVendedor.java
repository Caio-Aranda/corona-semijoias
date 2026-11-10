package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.comum.persistencia.CadastroDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Aba "Referências": pessoas que conhecem o vendedor. Várias por vendedor, com exclusão lógica. */
@Entity
@Table(name = "vendedores_referencias", schema = "corona")
public class ReferenciaDoVendedor extends CadastroDoServidor {

    /** Pedido de criação e de edição ({@code version} só na edição). */
    public record Pedido(
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 200, message = Mensagens.LIMITE_LONGO) String nome,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String parentesco,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String telefone,
            @Size(max = 1000, message = "Use no máximo {max} caracteres.") String observacao,
            Integer version) {
    }

    public record Resposta(UUID id, int version, String nome, String parentesco, String telefone, String observacao) {

        static Resposta de(ReferenciaDoVendedor r) {
            return new Resposta(r.getId(), r.getVersion(), r.nome, r.parentesco, r.telefone, r.observacao);
        }
    }

    @Column(name = "vendedor_id", nullable = false, updatable = false)
    private UUID vendedorId;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "parentesco")
    private String parentesco;

    @Column(name = "telefone")
    private String telefone;

    @Column(name = "observacao")
    private String observacao;

    protected ReferenciaDoVendedor() {
        // Hibernate
    }

    public ReferenciaDoVendedor(UUID vendedorId, Pedido p) {
        this.vendedorId = vendedorId;
        alterar(p);
    }

    public void alterar(Pedido p) {
        this.nome = p.nome().strip();
        this.parentesco = SecoesDoVendedor.limpar(p.parentesco());
        this.telefone = SecoesDoVendedor.limpar(p.telefone());
        this.observacao = SecoesDoVendedor.limpar(p.observacao());
    }

    public UUID getVendedorId() {
        return vendedorId;
    }

    public Resposta resposta() {
        return Resposta.de(this);
    }
}
