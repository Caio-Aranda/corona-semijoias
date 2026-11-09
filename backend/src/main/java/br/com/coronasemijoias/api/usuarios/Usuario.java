package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.comum.persistencia.EntidadeDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Quem entra nos aplicativos (tabela só do servidor {@code usuarios}). O perfil é texto (código da
 * tabela {@code perfis}), nunca um enum: perfil novo é dado, não classe (ADR-0004, item 9).
 * Ninguém é apagado: quem sai fica com {@code ativo = false}.
 */
@Entity
@Table(name = "usuarios", schema = "corona")
public class Usuario extends EntidadeDoServidor {

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "login", nullable = false)
    private String login;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Column(name = "perfil", nullable = false)
    private String perfil;

    @Column(name = "ativo", nullable = false)
    private boolean ativo;

    /** Sem chave estrangeira até a Fase 1 (a tabela {@code vendedores} ainda não existe). */
    @Column(name = "vendedor_id")
    private UUID vendedorId;

    protected Usuario() {
        // Hibernate
    }

    private Usuario(String nome, String login, String senhaHash, String perfil, UUID vendedorId) {
        this.nome = nome;
        this.login = Logins.normalizar(login);
        this.senhaHash = senhaHash;
        this.perfil = perfil;
        this.vendedorId = vendedorId;
        this.ativo = true;
    }

    /** Usuário novo e ativo. O login é guardado normalizado. */
    public static Usuario novo(String nome, String login, String senhaHash, String perfil, UUID vendedorId) {
        return new Usuario(nome, login, senhaHash, perfil, vendedorId);
    }

    /** Troca o hash da senha (por exemplo, ao subir o custo do BCrypt no login). */
    public void trocarHashDaSenha(String novoHash) {
        this.senhaHash = novoHash;
    }

    public String getNome() {
        return nome;
    }

    public String getLogin() {
        return login;
    }

    public String getSenhaHash() {
        return senhaHash;
    }

    public String getPerfil() {
        return perfil;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public UUID getVendedorId() {
        return vendedorId;
    }

    /** Sem o hash da senha: ele nunca sai do servidor nem vai para o log. */
    @Override
    public String toString() {
        return "Usuario[id=" + getId() + ", login=" + login + ", perfil=" + perfil + ", ativo=" + ativo + "]";
    }
}
