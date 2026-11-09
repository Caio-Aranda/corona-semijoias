package br.com.coronasemijoias.api.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * A lista FECHADA das rotas que respondem sem token. Tudo o que não está aqui exige token e perfil
 * declarado ({@link ExigePerfil}). Acrescentar uma rota pública é decisão de segurança: só com
 * aprovação e com teste.
 *
 * <ul>
 *   <li>{@code POST /api/auth/login};</li>
 *   <li>{@code GET /actuator/health} (só a saúde, sem detalhes; os subcaminhos são do mesmo endpoint);</li>
 *   <li>a documentação do OpenAPI, só quando ela está ligada (perfil {@code dev}).</li>
 * </ul>
 */
@Component
public class RotasPublicas {

    /** Uma rota pública: método HTTP e padrão de caminho. */
    public record RotaPublica(HttpMethod metodo, String padrao) {
    }

    private final List<RotaPublica> rotas;
    private final RequestMatcher comparador;

    public RotasPublicas(Environment ambiente) {
        List<RotaPublica> lista = new ArrayList<>(List.of(
                new RotaPublica(HttpMethod.POST, "/api/auth/login"),
                new RotaPublica(HttpMethod.GET, "/actuator/health"),
                new RotaPublica(HttpMethod.GET, "/actuator/health/**")));
        if (ambiente.getProperty("springdoc.api-docs.enabled", Boolean.class, true)) {
            lista.add(new RotaPublica(HttpMethod.GET, "/v3/api-docs"));
            lista.add(new RotaPublica(HttpMethod.GET, "/v3/api-docs/**"));
            lista.add(new RotaPublica(HttpMethod.GET, "/v3/api-docs.yaml"));
            lista.add(new RotaPublica(HttpMethod.GET, "/v3/api-docs.yaml/**"));
            lista.add(new RotaPublica(HttpMethod.GET, "/swagger-ui.html"));
            lista.add(new RotaPublica(HttpMethod.GET, "/swagger-ui/**"));
        }
        this.rotas = List.copyOf(lista);
        this.comparador = new OrRequestMatcher(rotas.stream()
                .map(r -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(r.metodo(), r.padrao()))
                .toList());
    }

    public List<RotaPublica> rotas() {
        return rotas;
    }

    public RequestMatcher comparador() {
        return comparador;
    }

    public boolean ehPublica(HttpServletRequest requisicao) {
        return comparador.matches(requisicao);
    }

    /**
     * Se uma rota registrada (método e padrão, como no mapeamento do Spring) está na lista. Usado pelo
     * teste que percorre todas as rotas.
     */
    public boolean ehPublica(String metodo, String padraoDaRota) {
        for (RotaPublica rota : rotas) {
            PathPattern padrao = PathPatternParser.defaultInstance.parse(rota.padrao());
            if (rota.metodo().name().equals(metodo) && padrao.matches(PathContainer.parsePath(padraoDaRota))) {
                return true;
            }
        }
        return false;
    }
}
