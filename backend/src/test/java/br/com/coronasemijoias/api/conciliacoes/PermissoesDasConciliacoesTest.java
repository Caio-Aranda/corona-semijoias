package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** As permissões da rota das conciliações (tarefa 7.8): só a dona; o vendedor nunca recebe conciliação. */
class PermissoesDasConciliacoesTest extends ApoioDasConciliacoes {

    private static final String ROTA = "/api/dona/conciliacoes";

    @Test
    void aDonaLeAsNoveEUmaSo() throws Exception {
        JsonNode todas = corpoDe(ler(ROTA, dona()).andExpect(status().isOk()));
        assertThat(todas.get("conferencias").size()).isEqualTo(9);
        assertThat(todas.has("tudoCerto")).isTrue();
        assertThat(todas.has("geradaEm")).isTrue();
        JsonNode uma = corpoDe(ler(ROTA + "?chave=C4", dona()).andExpect(status().isOk()));
        assertThat(uma.get("conferencias").size()).isEqualTo(1);
        assertThat(uma.get("conferencias").get(0).get("chave").asString()).isEqualTo("C4");
        ler(ROTA + "?chave=c10", dona()).andExpect(status().isOk());
    }

    @Test
    void oVendedorLevaRecusaESemSessaoLevaNaoAutorizado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        ler(ROTA, como(v)).andExpect(status().isForbidden());
        ler(ROTA + "?chave=C1", como(v)).andExpect(status().isForbidden());
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROTA + "?chave=C1")).andExpect(status().isUnauthorized());
    }

    @Test
    void chaveVagaOuInventadaDa400EmPortugues() throws Exception {
        for (String chave : new String[] {"C6", "C11", "XYZ"}) {
            ler(ROTA + "?chave=" + chave, dona()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campos[0].campo").value("chave"))
                    .andExpect(jsonPath("$.campos[0].mensagem").value(org.hamcrest.Matchers.containsString("conferência")));
        }
    }

    @Test
    void naoExisteConciliacaoNaRotaDoVendedor() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        int status = ler("/api/vendedor/conciliacoes", como(v)).andReturn().getResponse().getStatus();
        assertThat(status).isIn(403, 404);
        assertThat(ler("/api/vendedor/conciliacoes?chave=C1", como(v)).andReturn().getResponse().getStatus()).isIn(403, 404);
    }
}
