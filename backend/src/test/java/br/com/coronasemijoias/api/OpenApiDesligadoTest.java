package br.com.coronasemijoias.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Sem o perfil "dev" (como em produção), a documentação não existe. */
class OpenApiDesligadoTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Test
    void documentacaoFicaDesligadaForaDoPerfilDeDesenvolvimento() throws Exception {
        for (String caminho : new String[] {
                "/v3/api-docs", "/v3/api-docs/dona", "/v3/api-docs/vendedor",
                "/v3/api-docs/swagger-config", "/swagger-ui/index.html", "/swagger-ui.html"}) {
            // Fora do perfil dev a documentação não é pública: sem token, 401; com token, não existe.
            mvc.perform(get(caminho))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(caminho).with(usuarios.comoDona()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("ROTA_NAO_ENCONTRADA"));
        }
    }
}
