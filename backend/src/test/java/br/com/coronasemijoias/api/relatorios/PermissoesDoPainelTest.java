package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Quem pode ver o painel semanal (tarefa 8.7): só a dona (ADMIN). Sem login é 401 e o vendedor recebe 403 (a regra é da API, não
 * só da tela). Nenhuma rota do painel fica em {@code /api/vendedor/...}: o painel não vai ao Corona Vendas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDoPainelTest extends ApoioDasTrocas {

    private static final String ROTA = "/api/dona/painel-semanal";

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapeamentos;

    private UsuarioDeTeste vendedor;
    private UUID semana;

    @BeforeAll
    void umaSemana() throws Exception {
        vendedor = usuarios.criarVendedor();
        semana = semana();
    }

    @Test
    void semLoginRecebe401EOVendedorRecebe403() throws Exception {
        mvc.perform(get(ROTA + "?semana=" + semana)).andExpect(status().isUnauthorized());
        ler(ROTA + "?semana=" + semana, como(vendedor)).andExpect(status().isForbidden());
        ler(ROTA + "?semana=" + semana, dona()).andExpect(status().isOk());
    }

    @Test
    void oVendedorTambemNaoVeSemASemana() throws Exception {
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
        ler(ROTA, como(vendedor)).andExpect(status().isForbidden());
    }

    @Test
    void asRotasDoPainelSoFicamEmApiDona() {
        int[] achadas = {0};
        mapeamentos.getHandlerMethods().forEach((info, metodo) -> {
            if (metodo.getBeanType().equals(PainelSemanalController.class)) {
                achadas[0]++;
                assertThat(info.getPathPatternsCondition().getPatternValues()).allMatch(c -> c.startsWith("/api/dona/"));
            }
        });
        assertThat(achadas[0]).isGreaterThan(0);
        mapeamentos.getHandlerMethods().forEach((info, metodo) -> {
            if (info.getPathPatternsCondition() != null) {
                assertThat(info.getPathPatternsCondition().getPatternValues())
                        .noneMatch(c -> c.startsWith("/api/vendedor/") && c.contains("painel"));
            }
        });
    }
}
