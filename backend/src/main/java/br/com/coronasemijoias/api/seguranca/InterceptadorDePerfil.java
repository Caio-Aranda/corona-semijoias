package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Segunda camada da permissão (a primeira é o grupo do prefixo, em {@link ConfiguracaoDeSeguranca}):
 * confere a {@link ExigePerfil} da rota antes de ler o corpo e de validar os dados, então o perfil
 * errado recebe 403 e nunca um 400 que revele o formato da rota.
 *
 * <ul>
 *   <li>rota pública ou despacho de erro do servidor: passa;</li>
 *   <li>método de controlador sem {@link ExigePerfil}: 403 (negada por padrão);</li>
 *   <li>perfil fora da anotação: 403;</li>
 *   <li>o que não é método de controlador (arquivo estático, endereço inexistente): segue para o 404.</li>
 * </ul>
 */
@Component
public class InterceptadorDePerfil implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(InterceptadorDePerfil.class);

    private final RotasPublicas rotasPublicas;

    public InterceptadorDePerfil(RotasPublicas rotasPublicas) {
        this.rotasPublicas = rotasPublicas;
    }

    @Override
    public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object handler) {
        if (requisicao.getDispatcherType() == DispatcherType.ERROR || rotasPublicas.ehPublica(requisicao)) {
            return true;
        }
        if (!(handler instanceof HandlerMethod metodo)) {
            return true;
        }
        ExigePerfil anotacao = ExigePerfil.Leitura.de(metodo);
        if (anotacao == null) {
            LOG.warn("Rota sem @ExigePerfil negada: {} {} ({})",
                    requisicao.getMethod(), requisicao.getRequestURI(), metodo.getShortLogMessage());
            throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.SEM_PERMISSAO);
        }
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario)) {
            throw new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA);
        }
        if (!ExigePerfil.Leitura.perfis(anotacao).contains(usuario.perfil())) {
            throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.SEM_PERMISSAO);
        }
        return true;
    }
}
