package br.com.coronasemijoias.api.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prova de que a regra funciona: com uma rota SEM {@link ExigePerfil} no contexto, o verificador de
 * {@code TodasAsRotasTest} a acusa, e a rota é negada mesmo para a dona na rota da dona.
 */
@Import(RotaSemPerfilTest.RotaSemAnotacao.class)
class RotaSemPerfilTest extends TesteDeIntegracao {

    @TestComponent
    @RestController
    static class RotaSemAnotacao {

        @GetMapping("/api/dona/teste/sem-perfil")
        public String semPerfil() {
            return "não devia responder";
        }
    }

    @Autowired
    ApplicationContext contexto;

    @Autowired
    RotasPublicas publicas;

    @Autowired
    PropriedadesDeSeguranca propriedades;

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Test
    void verificadorAcusaARotaSemAnotacao() {
        assertThat(VerificadorDeRotas.violacoes(
                        VerificadorDeRotas.rotasDo(contexto), publicas, propriedades.grupos(), Set.of("ADMIN", "VENDEDOR")))
                .singleElement().asString()
                .contains("/api/dona/teste/sem-perfil")
                .contains("rota sem @ExigePerfil");
    }

    @Test
    void rotaSemAnotacaoEhNegadaAteParaOPerfilDoGrupo() throws Exception {
        mvc.perform(get("/api/dona/teste/sem-perfil").with(usuarios.comoDona()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
    }
}
