package br.com.coronasemijoias.api.seguranca;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Permissão por perfil: grupo do prefixo (ADR-0003) e {@link ExigePerfil} (ADR-0004, item 9). */
class PermissaoTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Test
    void vendedorNaRotaDaDonaRecebe403() throws Exception {
        mvc.perform(get("/api/dona/teste/tempo").with(usuarios.comoVendedor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
    }

    @Test
    void donaNaRotaDoVendedorRecebe403() throws Exception {
        mvc.perform(get("/api/vendedor/teste/dinheiro").with(usuarios.comoDona()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
    }

    @Test
    void cadaUmNaSuaRotaEntra() throws Exception {
        mvc.perform(get("/api/dona/teste/tempo").with(usuarios.comoDona())).andExpect(status().isOk());
        mvc.perform(get("/api/vendedor/teste/dinheiro").with(usuarios.comoVendedor())).andExpect(status().isOk());
    }

    @Test
    void semTokenRecebe401NasDuasRotas() throws Exception {
        for (String caminho : new String[] {"/api/dona/teste/tempo", "/api/vendedor/teste/dinheiro", "/api/auth/eu"}) {
            mvc.perform(get(caminho))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"));
        }
    }

    @Test
    void metodoRestringeMaisQueOControlador() throws Exception {
        // A classe aceita os dois perfis; o método, só o vendedor.
        mvc.perform(get("/api/auth/teste/so-vendedor").with(usuarios.comoDona()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
        mvc.perform(get("/api/auth/teste/so-vendedor").with(usuarios.comoVendedor())).andExpect(status().isOk());
        // Sem anotação no método, vale a da classe.
        mvc.perform(get("/api/auth/teste").with(usuarios.comoDona())).andExpect(status().isOk());
        mvc.perform(get("/api/auth/teste").with(usuarios.comoVendedor())).andExpect(status().isOk());
    }

    @Test
    void perfilErradoRecebe403AntesDeOCorpoSerConferido() throws Exception {
        // Corpo inválido: o perfil certo receberia 400; o errado não pode descobrir isso.
        mvc.perform(post("/api/dona/teste/validar").with(usuarios.comoVendedor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/vendedor/teste/validar").with(usuarios.comoDona())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/vendedor/teste/validar").with(usuarios.comoVendedor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
