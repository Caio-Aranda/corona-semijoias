package br.com.coronasemijoias.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class JsonTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Test
    void dataSaiEmIso8601ComZ() throws Exception {
        mvc.perform(get("/api/dona/teste/tempo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instante").value("2026-11-09T12:00:00Z"));
    }

    @Test
    void dinheiroSaiComoTexto() throws Exception {
        mvc.perform(get("/api/dona/teste/dinheiro"))
                .andExpect(status().isOk())
                // Texto, não número: o JSON tem as aspas.
                .andExpect(content().string("{\"valor\":\"123.45\"}"));
    }

    @Test
    void dinheiroSaiComDuasCasas() throws Exception {
        mvc.perform(get("/api/vendedor/teste/dinheiro"))
                .andExpect(content().string("{\"valor\":\"7.50\"}"));
    }

    @Test
    void dinheiroEntraComoTexto() throws Exception {
        mvc.perform(post("/api/dona/teste/dinheiro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"123.45\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"valor\":\"123.45\"}"));
    }

    @Test
    void dinheiroComoNumeroJsonEhRecusado() throws Exception {
        mvc.perform(post("/api/dona/teste/dinheiro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":123.45}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("valor"));
    }

    @Test
    void dinheiroComMaisDeDuasCasasOuNotacaoCientificaEhRecusado() throws Exception {
        for (String invalido : new String[] {"1.234", "1E+2", "abc", "", "12,50"}) {
            mvc.perform(post("/api/dona/teste/dinheiro")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"valor\":\"" + invalido + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
        }
    }
}
