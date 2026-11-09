package br.com.coronasemijoias.api.seguranca;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Percorre TODAS as rotas registradas (todos os mapeamentos do Spring MVC, inclusive os do Actuator e
 * da documentação) e devolve as violações da regra de permissão (ADR-0004, item 9):
 *
 * <ul>
 *   <li>rota fora da lista pública sem {@link ExigePerfil} (no método ou no controlador);</li>
 *   <li>anotação com perfil que não existe na tabela {@code perfis};</li>
 *   <li>anotação do método que amplia a do controlador;</li>
 *   <li>anotação com perfil fora do grupo do prefixo ({@code /api/dona/**}, {@code /api/vendedor/**}).</li>
 * </ul>
 *
 * A única rota ignorada é a página de erro do servidor ({@link ErrorController}): ela só atende o
 * despacho de erro do contêiner, e a chamada direta é negada (sem anotação → 403).
 */
public final class VerificadorDeRotas {

    private VerificadorDeRotas() {
    }

    /** Todos os mapeamentos do contexto. */
    public static Map<RequestMappingInfo, HandlerMethod> rotasDo(ApplicationContext contexto) {
        Map<RequestMappingInfo, HandlerMethod> todas = new LinkedHashMap<>();
        contexto.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()
                .forEach(mapeamento -> todas.putAll(mapeamento.getHandlerMethods()));
        return todas;
    }

    public static List<String> violacoes(Map<RequestMappingInfo, HandlerMethod> rotas, RotasPublicas publicas,
            Map<String, List<String>> grupos, Set<String> perfisDoBanco) {
        Set<String> violacoes = new TreeSet<>();
        rotas.forEach((info, metodo) -> {
            if (ErrorController.class.isAssignableFrom(metodo.getBeanType())) {
                return;
            }
            Set<String> padroes = info.getPatternValues();
            Set<RequestMethod> metodosHttp = info.getMethodsCondition().getMethods();
            String nome = metodosHttp + " " + padroes + " (" + metodo.getShortLogMessage() + ")";
            if (!metodosHttp.isEmpty() && padroes.stream().allMatch(padrao -> metodosHttp.stream()
                    .allMatch(m -> publicas.ehPublica(m.name(), padrao)))) {
                return;
            }
            ExigePerfil anotacao = ExigePerfil.Leitura.de(metodo);
            if (anotacao == null) {
                violacoes.add(nome + ": rota sem @ExigePerfil fora da lista pública");
                return;
            }
            Set<String> perfis = ExigePerfil.Leitura.perfis(anotacao);
            for (String perfil : perfis) {
                if (!perfisDoBanco.contains(perfil)) {
                    violacoes.add(nome + ": perfil " + perfil + " não existe na tabela perfis");
                }
            }
            ExigePerfil doControlador = ExigePerfil.Leitura.doControlador(metodo);
            if (doControlador != null && !ExigePerfil.Leitura.perfis(doControlador).containsAll(perfis)) {
                violacoes.add(nome + ": a anotação do método amplia a do controlador");
            }
            for (String padrao : padroes) {
                grupos.forEach((grupo, perfisDoGrupo) -> {
                    if (padrao.startsWith("/api/" + grupo + "/") && !perfisDoGrupo.containsAll(perfis)) {
                        violacoes.add(nome + ": perfil fora do grupo " + grupo + " " + perfisDoGrupo);
                    }
                });
            }
        });
        return new ArrayList<>(violacoes);
    }
}
