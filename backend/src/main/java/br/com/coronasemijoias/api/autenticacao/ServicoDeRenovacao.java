package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin.ResultadoDoLogin;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.ConteudoDoToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken.TokenEmitido;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Recusada;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Renovacao;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Renovada;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.SessaoAberta;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Renovação da sessão (ADR-0004, item 2): troca o token de renovação por um novo (rotação) e emite um
 * token de acesso novo da mesma família. A rotação é a sua própria transação: a revogação de uma família
 * suspeita fica gravada mesmo com a resposta de "sessão terminada".
 */
@Service
public class ServicoDeRenovacao {

    private final ServicoDeSessoes sessoes;
    private final UsuarioRepositorio usuarios;
    private final EmissorDeToken emissor;

    public ServicoDeRenovacao(ServicoDeSessoes sessoes, UsuarioRepositorio usuarios, EmissorDeToken emissor) {
        this.sessoes = sessoes;
        this.usuarios = usuarios;
        this.emissor = emissor;
    }

    public ResultadoDoLogin renovar(String tokenDeRenovacao, UUID dispositivoId) {
        Renovacao renovacao = sessoes.renovar(tokenDeRenovacao, dispositivoId);
        if (renovacao instanceof Recusada) {
            throw new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA);
        }
        Renovada renovada = (Renovada) renovacao;
        Usuario usuario = usuarios.findById(renovada.usuarioId())
                .orElseThrow(() -> new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA));
        if (!usuario.isAtivo()) {
            // Como no login com a senha certa: 403 e o texto de acesso desativado.
            throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.USUARIO_DESATIVADO);
        }
        if (usuario.isTrocaDeSenhaObrigatoria()) {
            // A redefinição já revoga as famílias; isto só protege de uma marca posta por outro caminho.
            throw new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA);
        }
        TokenEmitido token = emissor.emitir(ConteudoDoToken.de(
                usuario.getId(), usuario.getPerfil(), usuario.getVendedorId(), renovada.sid(), renovada.dispositivoId()));
        return new ResultadoDoLogin(token, new SessaoAberta(renovada.sid(), renovada.dispositivoId(),
                renovada.tokenDeRenovacao(), renovada.renovacaoExpiraEm()), false);
    }
}
