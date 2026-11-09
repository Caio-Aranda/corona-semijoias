package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.ConteudoDoToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken.TokenEmitido;
import br.com.coronasemijoias.api.usuarios.ConferenciaDeSenha;
import br.com.coronasemijoias.api.usuarios.Logins;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login (ADR-0004, itens 1 e 6). Login inexistente, senha errada e usuário inativo com senha errada dão
 * a MESMA resposta ({@code LOGIN_INVALIDO}), para não revelar quais logins existem. Só depois de a
 * senha conferir a API diz que o usuário está desativado. Login vazio, com espaço no meio ou com
 * caractere de controle também é {@code LOGIN_INVALIDO}, com a mesma conferência BCrypt.
 */
@Service
public class ServicoDeLogin {

    private final UsuarioRepositorio usuarios;
    private final ConferenciaDeSenha conferencia;
    private final EmissorDeToken emissor;

    public ServicoDeLogin(UsuarioRepositorio usuarios, ConferenciaDeSenha conferencia, EmissorDeToken emissor) {
        this.usuarios = usuarios;
        this.conferencia = conferencia;
        this.emissor = emissor;
    }

    @Transactional
    public TokenEmitido entrar(String login, String senha) {
        String normalizado = Logins.normalizar(login);
        // Login que não pode existir (vazio, com espaço no meio ou caractere de controle) nem é
        // procurado, mas passa pela mesma conferência BCrypt (contra o hash fixo): mesma resposta e
        // mesmo tempo da senha errada.
        Optional<Usuario> encontrado = Logins.podeExistir(normalizado)
                ? usuarios.findByLogin(normalizado)
                : Optional.empty();
        String hash = encontrado.map(Usuario::getSenhaHash).orElse(null);
        if (!conferencia.confere(senha, hash)) {
            throw new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.LOGIN_INVALIDO);
        }
        Usuario usuario = encontrado.orElseThrow();
        if (!usuario.isAtivo()) {
            throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.USUARIO_DESATIVADO);
        }
        if (conferencia.precisaRegravar(hash)) {
            usuario.trocarHashDaSenha(conferencia.gerarHash(senha));
        }
        return emissor.emitir(ConteudoDoToken.de(usuario.getId(), usuario.getPerfil(), usuario.getVendedorId()));
    }
}
