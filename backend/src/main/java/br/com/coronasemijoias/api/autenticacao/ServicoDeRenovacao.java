package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.aparelhos.ServicoDeAparelhos;
import br.com.coronasemijoias.api.aparelhos.SituacaoDoAparelho;
import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin.ResultadoDoLogin;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.ConteudoDoToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken.TokenEmitido;
import br.com.coronasemijoias.api.sessoes.MotivoDaRevogacao;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Recusada;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Renovacao;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Renovada;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.SessaoAberta;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Renovação da sessão (ADR-0004, item 2): troca o token de renovação por um novo (rotação) e emite um
 * token de acesso novo da mesma família. A rotação é a sua própria transação: a revogação de uma família
 * suspeita fica gravada mesmo com a resposta de "sessão terminada".
 *
 * <p>Tarefa 5.3 (ADR-0013, item 14): a família revogada porque a dona bloqueou o computador, ou desativou o vendedor,
 * responde 403 com a ordem de apagar os dados; as outras revogações (reuso do token, saída, senha trocada) continuam no
 * 401 de "entre de novo".
 */
@Service
public class ServicoDeRenovacao {

    private final ServicoDeSessoes sessoes;
    private final UsuarioRepositorio usuarios;
    private final EmissorDeToken emissor;
    private final ServicoDeAparelhos aparelhos;

    public ServicoDeRenovacao(ServicoDeSessoes sessoes, UsuarioRepositorio usuarios, EmissorDeToken emissor,
            ServicoDeAparelhos aparelhos) {
        this.sessoes = sessoes;
        this.usuarios = usuarios;
        this.emissor = emissor;
        this.aparelhos = aparelhos;
    }

    public ResultadoDoLogin renovar(String tokenDeRenovacao, UUID dispositivoId) {
        return renovar(tokenDeRenovacao, dispositivoId, null);
    }

    public ResultadoDoLogin renovar(String tokenDeRenovacao, UUID dispositivoId, String versaoDoApp) {
        Renovacao renovacao = sessoes.renovar(tokenDeRenovacao, dispositivoId);
        if (renovacao instanceof Recusada recusada) {
            MotivoDaRevogacao motivo = recusada.jaRevogadaPor();
            // Só o próprio computador da família recebe a ordem (quem só copiou o token de outro computador, não). O
            // vendedor desativado e depois reativado só entra de novo: a ordem vale enquanto ele estiver desativado.
            if (motivo != null && motivo.apagaOsDados() && recusada.dispositivoId() != null
                    && recusada.dispositivoId().equals(dispositivoId)) {
                if (motivo == MotivoDaRevogacao.APARELHO_BLOQUEADO) {
                    throw ErroDaApi.comOrdemDeApagar(CodigoErro.APARELHO_BLOQUEADO);
                }
                boolean continuaDesativado = usuarios.findById(recusada.usuarioId()).map(u -> !u.isAtivo()).orElse(false);
                if (continuaDesativado) {
                    throw ErroDaApi.comOrdemDeApagar(CodigoErro.USUARIO_DESATIVADO);
                }
            }
            throw new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA);
        }
        Renovada renovada = (Renovada) renovacao;
        Usuario usuario = usuarios.findById(renovada.usuarioId())
                .orElseThrow(() -> new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA));
        if (!usuario.isAtivo()) {
            // Como no login com a senha certa: 403 e o texto de acesso desativado; no Corona Vendas, com a ordem de apagar.
            if (renovada.dispositivoId() != null) {
                throw ErroDaApi.comOrdemDeApagar(CodigoErro.USUARIO_DESATIVADO);
            }
            throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.USUARIO_DESATIVADO);
        }
        if (usuario.isTrocaDeSenhaObrigatoria()) {
            // A redefinição já revoga as famílias; isto só protege de uma marca posta por outro caminho.
            throw new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA);
        }
        if (renovada.dispositivoId() != null) {
            // Segunda barreira (o bloqueio já revoga as famílias): computador bloqueado nunca renova.
            if (Objects.equals(aparelhos.situacao(renovada.dispositivoId(), usuario.getId()).orElse(null),
                    SituacaoDoAparelho.BLOQUEADO)) {
                throw ErroDaApi.comOrdemDeApagar(CodigoErro.APARELHO_BLOQUEADO);
            }
            aparelhos.registrarContato(renovada.dispositivoId(), versaoDoApp);
        }
        TokenEmitido token = emissor.emitir(ConteudoDoToken.de(
                usuario.getId(), usuario.getPerfil(), usuario.getVendedorId(), renovada.sid(), renovada.dispositivoId()));
        return new ResultadoDoLogin(token, new SessaoAberta(renovada.sid(), renovada.dispositivoId(),
                renovada.tokenDeRenovacao(), renovada.renovacaoExpiraEm()), false);
    }
}
