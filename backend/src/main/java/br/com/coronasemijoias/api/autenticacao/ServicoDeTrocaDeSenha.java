package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.ConteudoDoToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken.TokenEmitido;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.usuarios.ConferenciaDeSenha;
import br.com.coronasemijoias.api.usuarios.RegrasDaSenha;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Troca de senha obrigatória (ADR-0004, item 6): com o token restrito, a pessoa cria a senha nova e
 * recebe o token de acesso normal. As regras valem aqui, no servidor; a tela só ajuda. A troca
 * voluntária, por quem já tem senha definitiva, fica para outra tarefa.
 */
@Service
public class ServicoDeTrocaDeSenha {

    private final UsuarioRepositorio usuarios;
    private final ConferenciaDeSenha conferencia;
    private final EmissorDeToken emissor;
    private final Clock relogio;

    public ServicoDeTrocaDeSenha(UsuarioRepositorio usuarios, ConferenciaDeSenha conferencia, EmissorDeToken emissor,
            Clock relogio) {
        this.usuarios = usuarios;
        this.conferencia = conferencia;
        this.emissor = emissor;
        this.relogio = relogio;
    }

    @Transactional
    public TokenEmitido trocar(UsuarioAutenticado autenticado, String senhaNova) {
        if (!ConteudoDoToken.ESCOPO_TROCA_SENHA.equals(autenticado.escopo())) {
            // Token comum não troca senha por esta rota (a troca voluntária não existe ainda).
            throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.SEM_PERMISSAO);
        }
        Usuario usuario = usuarios.findById(autenticado.id())
                .orElseThrow(() -> new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA));
        RegrasDaSenha.problema(senhaNova).ifPresent(codigo -> {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, codigo);
        });
        if (conferencia.confere(senhaNova, usuario.getSenhaHash())) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.SENHA_IGUAL_A_TEMPORARIA);
        }
        usuario.trocarSenha(conferencia.gerarHash(senhaNova), relogio.instant().truncatedTo(ChronoUnit.SECONDS));
        usuarios.saveAndFlush(usuario);
        return emissor.emitir(ConteudoDoToken.de(usuario.getId(), usuario.getPerfil(), usuario.getVendedorId()));
    }
}
