package br.com.coronasemijoias.api.aparelhos;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rotas do vendedor ({@code /api/vendedor/**}) só para o computador em uso (tarefa 5.3; ADR-0013, item 13). Roda depois
 * do {@code InterceptadorDePerfil}, antes de ler o corpo:
 *
 * <ul>
 *   <li>{@code ATIVO}: passa;</li>
 *   <li>{@code AGUARDANDO_LIBERACAO} (ou computador sem cadastro): 403 {@code APARELHO_NAO_LIBERADO};</li>
 *   <li>{@code SUBSTITUIDO}: 403 {@code APARELHO_SUBSTITUIDO}, exceto nas rotas marcadas com
 *       {@link AceitaComputadorTrocado} (o envio das vendas guardadas, tarefa 5.5);</li>
 *   <li>{@code BLOQUEADO}: 403 {@code APARELHO_BLOQUEADO} com a ordem de apagar os dados.</li>
 * </ul>
 *
 * O login, a renovação, a troca de senha, "quem sou eu" e a saída ficam fora ({@code /api/auth/**}): o computador que
 * espera a dona entra e mostra "Este computador ainda não foi liberado pela dona.".
 */
@Component
public class InterceptadorDoAparelho implements HandlerInterceptor {

    public static final String ROTAS = "/api/vendedor/**";

    private final ServicoDeAparelhos aparelhos;

    public InterceptadorDoAparelho(ServicoDeAparelhos aparelhos) {
        this.aparelhos = aparelhos;
    }

    @Override
    public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object handler) {
        if (requisicao.getDispatcherType() == DispatcherType.ERROR || !(handler instanceof HandlerMethod metodo)) {
            return true;
        }
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario)
                || !"VENDEDOR".equals(usuario.perfil())) {
            return true; // o InterceptadorDePerfil já respondeu (ou vai responder) o 401/403
        }
        SituacaoDoAparelho situacao = aparelhos.situacao(usuario.dispositivoId(), usuario.id())
                .orElse(SituacaoDoAparelho.AGUARDANDO_LIBERACAO);
        switch (situacao) {
            case ATIVO -> {
                return true;
            }
            case BLOQUEADO -> throw ErroDaApi.comOrdemDeApagar(CodigoErro.APARELHO_BLOQUEADO);
            case SUBSTITUIDO -> {
                if (metodo.hasMethodAnnotation(AceitaComputadorTrocado.class)) {
                    return true;
                }
                throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.APARELHO_SUBSTITUIDO);
            }
            default -> throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.APARELHO_NAO_LIBERADO);
        }
    }
}
