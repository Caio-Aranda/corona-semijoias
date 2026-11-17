package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Quem pode ver os relatórios de Controle (tarefa 8.9): só a dona (ADMIN). Sem login é 401 e o vendedor recebe 403 em cada rota:
 * a regra é da API, não só da tela. Nenhuma rota do controle fica em /api/vendedor.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDoControleTest extends ApoioDoControle {

    private static final List<String> ROTAS = List.of("/auditoria", "/computadores", "/acessos");

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapeamentos;

    UsuarioDeTeste vendedor;

    @BeforeAll
    void vendedorDeTeste() {
        vendedor = usuarios.criarVendedor();
    }

    @Test
    void semLoginRecebe401VendedorRecebe403DonaRecebe200() throws Exception {
        for (String rota : ROTAS) {
            mvc.perform(get(CONTROLE + rota)).andExpect(status().isUnauthorized());
            mvc.perform(get(CONTROLE + rota).with(como(vendedor))).andExpect(status().isForbidden());
            chamar(rota).andExpect(status().isOk());
        }
    }

    @Test
    void oControladorSoTemRotasDaDona() {
        mapeamentos.getHandlerMethods().forEach((info, metodo) -> {
            if (metodo.getBeanType().equals(RelatoriosDeControleController.class)) {
                assertThat(info.getPathPatternsCondition().getPatternValues()).allMatch(c -> c.startsWith("/api/dona/"));
            }
        });
    }
}
