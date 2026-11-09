package br.com.coronasemijoias.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Sem o perfil "dev" (como em produção), a documentação não existe. */
class OpenApiDesligadoTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Test
    void documentacaoFicaDesligadaForaDoPerfilDeDesenvolvimento() throws Exception {
        for (String caminho : new String[] {
                "/v3/api-docs", "/v3/api-docs/dona", "/v3/api-docs/vendedor",
                "/v3/api-docs/swagger-config", "/swagger-ui/index.html", "/swagger-ui.html"}) {
            mvc.perform(get(caminho))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("ROTA_NAO_ENCONTRADA"));
        }
    }
}
