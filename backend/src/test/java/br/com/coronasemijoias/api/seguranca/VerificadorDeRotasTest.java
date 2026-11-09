package br.com.coronasemijoias.api.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/** As regras do verificador, com rotas montadas à mão (sem subir a aplicação). */
class VerificadorDeRotasTest {

    private static final Map<String, List<String>> GRUPOS = Map.of("dona", List.of("ADMIN"), "vendedor", List.of("VENDEDOR"));
    private static final Set<String> PERFIS = Set.of("ADMIN", "VENDEDOR");
    private static final RotasPublicas PUBLICAS =
            new RotasPublicas(new MockEnvironment().withProperty("springdoc.api-docs.enabled", "false"));

    static class SemNada {
        public void rota() {
        }
    }

    @ExigePerfil("ADMIN")
    static class SoDona {
        public void herda() {
        }

        @ExigePerfil({"ADMIN", "VENDEDOR"})
        public void amplia() {
        }
    }

    static class Avulsa {
        @ExigePerfil("GERENTE")
        public void perfilInexistente() {
        }

        @ExigePerfil("VENDEDOR")
        public void vendedor() {
        }

        @ExigePerfil({"ADMIN", "VENDEDOR"})
        public void osDois() {
        }
    }

    private static List<String> violacoes(String caminho, RequestMethod metodoHttp, Object controlador, String metodo)
            throws NoSuchMethodException {
        RequestMappingInfo info = RequestMappingInfo.paths(caminho).methods(metodoHttp).build();
        HandlerMethod handler = new HandlerMethod(controlador, controlador.getClass().getMethod(metodo));
        return VerificadorDeRotas.violacoes(Map.of(info, handler), PUBLICAS, GRUPOS, PERFIS);
    }

    @Test
    void rotaSemAnotacaoForaDaListaPublicaEhViolacao() throws Exception {
        assertThat(violacoes("/api/auth/nova", RequestMethod.GET, new SemNada(), "rota"))
                .singleElement().asString().contains("rota sem @ExigePerfil");
    }

    @Test
    void rotaPublicaNaoPrecisaDeAnotacao() throws Exception {
        assertThat(violacoes("/api/auth/login", RequestMethod.POST, new SemNada(), "rota")).isEmpty();
        assertThat(violacoes("/actuator/health", RequestMethod.GET, new SemNada(), "rota")).isEmpty();
        // Mesmo caminho com outro método não é público.
        assertThat(violacoes("/api/auth/login", RequestMethod.GET, new SemNada(), "rota")).hasSize(1);
    }

    @Test
    void anotacaoDoControladorVale() throws Exception {
        assertThat(violacoes("/api/dona/x", RequestMethod.GET, new SoDona(), "herda")).isEmpty();
    }

    @Test
    void metodoNaoPodeAmpliarOControlador() throws Exception {
        assertThat(violacoes("/api/auth/x", RequestMethod.GET, new SoDona(), "amplia"))
                .singleElement().asString().contains("amplia a do controlador");
    }

    @Test
    void perfilQueNaoExisteEhViolacao() throws Exception {
        assertThat(violacoes("/api/auth/x", RequestMethod.GET, new Avulsa(), "perfilInexistente"))
                .singleElement().asString().contains("GERENTE não existe");
    }

    @Test
    void perfilForaDoGrupoDoPrefixoEhViolacao() throws Exception {
        assertThat(violacoes("/api/dona/x", RequestMethod.GET, new Avulsa(), "vendedor"))
                .singleElement().asString().contains("fora do grupo dona");
        assertThat(violacoes("/api/vendedor/x", RequestMethod.GET, new Avulsa(), "osDois"))
                .singleElement().asString().contains("fora do grupo vendedor");
        assertThat(violacoes("/api/auth/x", RequestMethod.GET, new Avulsa(), "osDois")).isEmpty();
    }
}
