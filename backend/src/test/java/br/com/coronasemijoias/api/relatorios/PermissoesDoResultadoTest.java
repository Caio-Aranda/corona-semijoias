package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Quem pode ver o resultado do período, o lucro por produto e o lucro por vendedor (tarefa 8.6): só a dona (ADMIN); sem login é
 * 401 e o vendedor recebe 403 (a regra é da API, não só da tela). E nenhuma rota do vendedor devolve custo nem lucro: o custo
 * nunca chega ao Corona Vendas (CLAUDE.md, regras inegociáveis).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDoResultadoTest extends ApoioDasTrocas {

    private static final String FIN = "/api/dona/relatorios/financeiro";
    private static final String PERIODO = "de=2292-04-01&ate=2292-04-30";
    private static final List<String> ROTAS = List.of("/resultado?" + PERIODO, "/resultado/detalhamento?" + PERIODO + "&linha=VENDAS",
            "/resultado/detalhamento?" + PERIODO + "&linha=CUSTO", "/lucro-por-produto?" + PERIODO, "/lucro-por-vendedor?" + PERIODO);

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapeamentos;

    private UsuarioDeTeste vendedor;

    @BeforeAll
    void vendedorDeTeste() {
        vendedor = usuarios.criarVendedor();
    }

    @Test
    void soAdminLeSemLoginRecebe401EVendedorRecebe403() throws Exception {
        for (String rota : ROTAS) {
            mvc.perform(get(FIN + rota)).andExpect(status().isUnauthorized());
            ler(FIN + rota, como(vendedor)).andExpect(status().isForbidden());
            ler(FIN + rota, dona()).andExpect(status().isOk());
        }
    }

    @Test
    void nenhumaRotaNovaFicaEmApiVendedor() {
        mapeamentos.getHandlerMethods().forEach((info, metodo) -> {
            var caminhos = info.getPathPatternsCondition() == null ? java.util.Set.<String>of()
                    : info.getPathPatternsCondition().getPatternValues();
            boolean doResultado = metodo.getBeanType().equals(RelatoriosFinanceirosController.class)
                    && List.of("resultado", "detalhamentoDoResultado", "lucroPorProduto", "lucroPorVendedor").contains(metodo.getMethod().getName());
            if (doResultado) {
                assertThat(caminhos).as(metodo.getMethod().getName()).allMatch(c -> c.startsWith("/api/dona/"));
            }
        });
        // O controlador do resultado só tem rotas da dona.
        mapeamentos.getHandlerMethods().forEach((info, metodo) -> {
            if (metodo.getBeanType().equals(RelatoriosFinanceirosController.class)) {
                assertThat(info.getPathPatternsCondition().getPatternValues()).allMatch(c -> c.startsWith("/api/dona/"));
            }
        });
    }

    @Test
    void nenhumaRotaDoVendedorDevolveCustoNemLucro() throws Exception {
        List<String> rotas = new ArrayList<>();
        mapeamentos.getHandlerMethods().forEach((info, metodo) -> {
            if (info.getMethodsCondition().getMethods().contains(org.springframework.web.bind.annotation.RequestMethod.GET)
                    && info.getPathPatternsCondition() != null) {
                for (String c : info.getPathPatternsCondition().getPatternValues()) {
                    if (c.startsWith("/api/vendedor/") && !c.contains("{")) {
                        rotas.add(c);
                    }
                }
            }
        });
        assertThat(rotas).isNotEmpty();
        for (String rota : rotas) {
            var resposta = mvc.perform(get(rota).with(como(vendedor))).andReturn().getResponse();
            assertThat(resposta.getStatus()).as(rota).isLessThan(500);
            String corpo = resposta.getContentAsString(StandardCharsets.UTF_8).toLowerCase();
            assertThat(corpo).as(rota).doesNotContain("custo").doesNotContain("lucro").doesNotContain("precocusto");
        }
    }
}
