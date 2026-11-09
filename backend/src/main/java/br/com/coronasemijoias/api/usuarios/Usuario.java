package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.comum.persistencia.EntidadeDoServidor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
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

    /** A senha atual é temporária: só o token restrito até a pessoa criar a senha nova. */
    @Column(name = "troca_de_senha_obrigatoria", nullable = false)
    private boolean trocaDeSenhaObrigatoria;

    /** Vencimento da senha temporária; nulo com a troca obrigatória = sem vencimento (primeiro administrador). */
    @Column(name = "senha_temporaria_vence_em")
    private Instant senhaTemporariaVenceEm;

    /** Última redefinição ou troca de senha; token emitido antes dela é recusado. */
    @Column(name = "credencial_alterada_em")
    private Instant credencialAlteradaEm;

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

    /** Usuário novo e ativo com senha temporária, que vence em {@code venceEm}. */
    public static Usuario novoComSenhaTemporaria(
            String nome, String login, String senhaHash, String perfil, UUID vendedorId, Instant venceEm) {
        Usuario usuario = new Usuario(nome, login, senhaHash, perfil, vendedorId);
        usuario.trocaDeSenhaObrigatoria = true;
        usuario.senhaTemporariaVenceEm = venceEm;
        return usuario;
    }

    /** Primeiro administrador: troca obrigatória, mas sem vencimento (a senha veio do .env, não foi gerada). */
    public static Usuario novoComSenhaSemVencimento(
            String nome, String login, String senhaHash, String perfil, UUID vendedorId) {
        return novoComSenhaTemporaria(nome, login, senhaHash, perfil, vendedorId, null);
    }

    /** Redefinição: senha temporária nova, prazo novo, troca obrigatória, e as sessões antigas deixam de valer. */
    public void redefinirSenha(String novoHash, Instant venceEm, Instant agora) {
        this.senhaHash = novoHash;
        this.trocaDeSenhaObrigatoria = true;
        this.senhaTemporariaVenceEm = venceEm;
        this.credencialAlteradaEm = agora;
    }

    /** A pessoa criou a senha nova: tira a marca e o vencimento. */
    public void trocarSenha(String novoHash, Instant agora) {
        this.senhaHash = novoHash;
        this.trocaDeSenhaObrigatoria = false;
        this.senhaTemporariaVenceEm = null;
        this.credencialAlteradaEm = agora;
    }

    /** Se a senha temporária já venceu em {@code agora} (o instante do vencimento já conta como vencido). */
    public boolean senhaTemporariaVencida(Instant agora) {
        return trocaDeSenhaObrigatoria && senhaTemporariaVenceEm != null && !agora.isBefore(senhaTemporariaVenceEm);
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

    public boolean isTrocaDeSenhaObrigatoria() {
        return trocaDeSenhaObrigatoria;
    }

    public Instant getSenhaTemporariaVenceEm() {
        return senhaTemporariaVenceEm;
    }

    public Instant getCredencialAlteradaEm() {
        return credencialAlteradaEm;
    }

    /** Sem o hash da senha: ele nunca sai do servidor nem vai para o log. */
    @Override
    public String toString() {
        return "Usuario[id=" + getId() + ", login=" + login + ", perfil=" + perfil + ", ativo=" + ativo + "]";
    }
}
