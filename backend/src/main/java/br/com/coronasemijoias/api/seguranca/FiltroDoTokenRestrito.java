package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.seguranca.RotasPublicas.RotaPublica;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Garante no servidor que o token restrito da troca de senha ({@code escopo=TROCA_SENHA}, ADR-0004,
 * item 6) só abre {@link #ROTAS_PERMITIDAS}. Qualquer outra requisição, de rota que existe ou não,
 * recebe 403 {@code TROCA_DE_SENHA_OBRIGATORIA}: a regra é "negar tudo, menos a lista", então uma rota
 * nova nasce barrada. Não é um bean: entra só na cadeia de segurança (um filtro-bean seria registrado
 * duas vezes pelo servlet).
 */
public class FiltroDoTokenRestrito extends OncePerRequestFilter {

    /** As únicas rotas que o token restrito abre. A saída (logout) entra aqui na tarefa 11. */
    public static final List<RotaPublica> ROTAS_PERMITIDAS = List.of(
            new RotaPublica(HttpMethod.POST, "/api/auth/trocar-senha"));

    private final RespostasDeSeguranca respostas;
    private final RequestMatcher permitidas = new OrRequestMatcher(ROTAS_PERMITIDAS.stream()
            .map(r -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(r.metodo(), r.padrao()))
            .toList());

    public FiltroDoTokenRestrito(RespostasDeSeguranca respostas) {
        this.respostas = respostas;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario
                && ConteudoDoToken.ESCOPO_TROCA_SENHA.equals(usuario.escopo())
                && !permitidas.matches(requisicao)) {
            respostas.trocaDeSenhaObrigatoria(requisicao, resposta);
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
