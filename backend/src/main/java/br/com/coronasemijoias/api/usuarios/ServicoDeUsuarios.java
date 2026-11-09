package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.comum.erros.CampoInvalido;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Criação de usuários e redefinição de senha (ADR-0004, itens 6 e 8). A senha temporária é gerada
 * aqui, gravada só como hash BCrypt e devolvida UMA vez a quem chamou: nunca vai para log. A
 * redefinição grava a hora da troca de credencial, que invalida os tokens anteriores.
 */
@Service
public class ServicoDeUsuarios {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDeUsuarios.class);

    static final String PERFIL_ADMIN = "ADMIN";

    /** Um usuário e a senha temporária que acabou de ganhar (a única vez em que ela existe em texto). */
    public record UsuarioComSenhaTemporaria(Usuario usuario, String senhaTemporaria, Instant venceEm) {

        @Override
        public String toString() {
            return "UsuarioComSenhaTemporaria[usuario=" + usuario + ", senhaTemporaria=(oculta), venceEm=" + venceEm + "]";
        }
    }

    /** Recusa do comando do servidor: a mensagem já está em português, para o terminal. */
    public static class RecusaDoComando extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RecusaDoComando(String mensagem) {
            super(mensagem);
        }
    }

    private final UsuarioRepositorio usuarios;
    private final ConferenciaDeSenha conferencia;
    private final GeradorDeSenhaTemporaria gerador;
    private final PropriedadesDaSenhaTemporaria propriedades;
    private final Clock relogio;
    private final JdbcTemplate jdbc;

    public ServicoDeUsuarios(UsuarioRepositorio usuarios, ConferenciaDeSenha conferencia,
            GeradorDeSenhaTemporaria gerador, PropriedadesDaSenhaTemporaria propriedades, Clock relogio,
            JdbcTemplate jdbc) {
        this.usuarios = usuarios;
        this.conferencia = conferencia;
        this.gerador = gerador;
        this.propriedades = propriedades;
        this.relogio = relogio;
        this.jdbc = jdbc;
    }

    @Transactional
    public UsuarioComSenhaTemporaria criar(String nome, String loginDigitado, String perfil, UUID vendedorId) {
        String login = Logins.normalizar(loginDigitado);
        if (!Logins.temFormatoParaCriar(login)) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.LOGIN_FORA_DO_FORMATO);
        }
        if (!perfilExiste(perfil)) {
            throw dadosInvalidos("perfil");
        }
        if (PERFIL_ADMIN.equals(perfil) && vendedorId != null) {
            throw dadosInvalidos("vendedorId");
        }
        if (usuarios.findByLogin(login).isPresent()) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LOGIN_JA_USADO);
        }
        String senha = gerador.gerar();
        Instant venceEm = venceEm(agora());
        Usuario usuario = Usuario.novoComSenhaTemporaria(
                nome.strip(), login, conferencia.gerarHash(senha), perfil, vendedorId, venceEm);
        try {
            usuarios.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            // Dois pedidos com o mesmo login ao mesmo tempo: o banco (login único) decide.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LOGIN_JA_USADO);
        }
        LOG.info("Usuário criado com senha temporária: login {}, perfil {}, vence em {}", login, perfil, venceEm);
        return new UsuarioComSenhaTemporaria(usuario, senha, venceEm);
    }

    /** Redefinição pela dona (rota). Ela não redefine a própria senha por aqui, para não se trancar fora. */
    @Transactional
    public UsuarioComSenhaTemporaria redefinirSenha(UUID id, UUID quemPediu) {
        if (id.equals(quemPediu)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NAO_REDEFINIR_A_PROPRIA_SENHA);
        }
        Usuario usuario = usuarios.findById(id)
                .orElseThrow(() -> new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.USUARIO_NAO_ENCONTRADO));
        UsuarioComSenhaTemporaria redefinido = redefinir(usuario);
        LOG.info("Senha temporária redefinida: login {}, vence em {}", usuario.getLogin(), redefinido.venceEm());
        return redefinido;
    }

    /**
     * Comando do servidor ({@code redefinir-senha-admin}, ADR-0004, item 8): só para administrador
     * ativo. Quem chama mostra a senha no terminal; aqui só o log, sem a senha.
     */
    @Transactional
    public UsuarioComSenhaTemporaria redefinirSenhaDeAdministradorPeloServidor(String loginDigitado) {
        String login = Logins.normalizar(loginDigitado);
        Usuario usuario = usuarios.findByLogin(login)
                .filter(u -> PERFIL_ADMIN.equals(u.getPerfil()))
                .orElseThrow(() -> new RecusaDoComando("Não existe nenhum administrador com o login \"" + login
                        + "\". Confira o login e rode de novo."));
        if (!usuario.isAtivo()) {
            throw new RecusaDoComando("O administrador \"" + login + "\" está desativado. "
                    + "Este comando não reativa ninguém: nada foi alterado.");
        }
        UsuarioComSenhaTemporaria redefinido = redefinir(usuario);
        LOG.info("Senha temporária do administrador {} redefinida pelo servidor; vence em {}",
                login, redefinido.venceEm());
        return redefinido;
    }

    private UsuarioComSenhaTemporaria redefinir(Usuario usuario) {
        String senha = gerador.gerar();
        Instant agora = agora();
        Instant venceEm = venceEm(agora);
        usuario.redefinirSenha(conferencia.gerarHash(senha), venceEm, agora);
        usuarios.saveAndFlush(usuario);
        return new UsuarioComSenhaTemporaria(usuario, senha, venceEm);
    }

    /** Segundos inteiros: é a precisão do {@code iat} do token, com que a hora da troca é comparada. */
    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private Instant venceEm(Instant agora) {
        return agora.plus(propriedades.validade());
    }

    private boolean perfilExiste(String perfil) {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM corona.perfis WHERE codigo = ?", Integer.class, perfil);
        return total != null && total > 0;
    }

    private static ErroDaApi dadosInvalidos(String campo) {
        return new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.DADOS_INVALIDOS,
                List.of(new CampoInvalido(campo, "Confira este campo e tente de novo.")));
    }
}
