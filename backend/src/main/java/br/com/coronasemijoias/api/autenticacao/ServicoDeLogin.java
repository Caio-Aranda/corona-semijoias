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
import java.time.Clock;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login (ADR-0004, itens 1 e 6). Login inexistente, senha errada e usuário inativo com senha errada dão
 * a MESMA resposta ({@code LOGIN_INVALIDO}), para não revelar quais logins existem. Só depois de a
 * senha conferir a API diz que o usuário está desativado. Login vazio, com espaço no meio ou com
 * caractere de controle também é {@code LOGIN_INVALIDO}, com a mesma conferência BCrypt.
 *
 * <p>Senha temporária (ADR-0004, item 6): no prazo, devolve só o token restrito; vencida, recusa com
 * {@code SENHA_TEMPORARIA_VENCIDA} e sem token, e isso só depois de a senha conferir.
 */
@Service
public class ServicoDeLogin {

    private final UsuarioRepositorio usuarios;
    private final ConferenciaDeSenha conferencia;
    private final EmissorDeToken emissor;
    private final Clock relogio;

    public ServicoDeLogin(UsuarioRepositorio usuarios, ConferenciaDeSenha conferencia, EmissorDeToken emissor,
            Clock relogio) {
        this.usuarios = usuarios;
        this.conferencia = conferencia;
        this.emissor = emissor;
        this.relogio = relogio;
    }

    /**
     * O token do login e se ele é o restrito da troca de senha obrigatória (senha temporária certa e
     * dentro do prazo): nesse caso o aplicativo só mostra a tela de criar a senha nova.
     */
    public record ResultadoDoLogin(TokenEmitido token, boolean trocaDeSenhaObrigatoria) {
    }

    @Transactional
    public ResultadoDoLogin entrar(String login, String senha) {
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
        // Só depois de a senha conferir (e o usuário estar ativo): não revela que o login existe.
        if (usuario.senhaTemporariaVencida(relogio.instant())) {
            throw new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SENHA_TEMPORARIA_VENCIDA);
        }
        if (conferencia.precisaRegravar(hash)) {
            usuario.trocarHashDaSenha(conferencia.gerarHash(senha));
        }
        if (usuario.isTrocaDeSenhaObrigatoria()) {
            // Senha temporária no prazo: só o token restrito, que abre a troca de senha e mais nada.
            return new ResultadoDoLogin(emissor.emitir(ConteudoDoToken.restritoParaTrocaDeSenha(
                    usuario.getId(), usuario.getPerfil(), usuario.getVendedorId())), true);
        }
        return new ResultadoDoLogin(emissor.emitir(
                ConteudoDoToken.de(usuario.getId(), usuario.getPerfil(), usuario.getVendedorId())), false);
    }
}
