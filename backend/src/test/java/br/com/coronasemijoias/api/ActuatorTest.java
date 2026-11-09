package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class ActuatorTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Test
    void saudeRespondeUpSemDetalhes() throws Exception {
        String corpo = mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("components").doesNotContain("details").doesNotContain("postgres");
    }

    @Test
    void outrosEndpointsNaoSaoExpostos() throws Exception {
        for (String caminho : new String[] {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/info"}) {
            // Sem token: nada além da saúde responde. Com token: o endereço não existe.
            mvc.perform(get(caminho)).andExpect(status().isUnauthorized());
            mvc.perform(get(caminho).with(usuarios.comoDona())).andExpect(status().isNotFound());
        }
    }
}
