package br.com.coronasemijoias.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ErrosTest extends TesteDeIntegracao {

    /** Trechos que nunca podem aparecer numa resposta de erro. */
    private static final List<String> PROIBIDOS = List.of(
            "Exception", "exception", "at br.com", ".java", "org.springframework", "java.lang",
            "Not Found", "Bad Request", "Internal Server Error", "Method Not Allowed",
            "error", "timestamp", "trace", "stack");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Test
    void rotaInexistenteDevolveProblemaEmPortugues() throws Exception {
        MvcResult resultado = mvc.perform(get("/rota/que/nao/existe").with(usuarios.comoDona()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("ROTA_NAO_ENCONTRADA"))
                .andExpect(jsonPath("$.detail").value(
                        "Não encontramos o que você pediu. Atualize o aplicativo e tente de novo. "
                                + "Se continuar, fale com o suporte do sistema."))
                .andReturn();
        assertThat(resultado.getResponse().getContentType()).startsWith("application/problem+json");
        semNadaTecnico(resultado);
    }

    @Test
    void semTokenAteRotaInexistenteDevolveSessaoTerminada() throws Exception {
        for (String caminho : new String[] {"/rota/que/nao/existe", "/api/dona/teste/tempo"}) {
            MvcResult resultado = mvc.perform(get(caminho))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"))
                    .andExpect(jsonPath("$.detail").value(
                            "Sua sessão terminou. Entre de novo com seu usuário e sua senha para continuar."))
                    .andReturn();
            assertThat(resultado.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(resultado.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            semNadaTecnico(resultado);
        }
    }

    @Test
    void paginaDeErroDoServidorNaoAtendeChamadaDireta() throws Exception {
        // Ela só serve ao despacho de erro do contêiner; não tem @ExigePerfil, então é negada.
        mvc.perform(get("/error")).andExpect(status().isUnauthorized());
        mvc.perform(get("/error").with(usuarios.comoDona()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
    }

    @Test
    void perfilErradoDevolveSemPermissao() throws Exception {
        MvcResult resultado = mvc.perform(get("/api/dona/teste/tempo").with(usuarios.comoVendedor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"))
                .andExpect(jsonPath("$.title").value("Você não tem acesso a esta parte do sistema."))
                .andReturn();
        semNadaTecnico(resultado);
    }

    @Test
    void metodoNaoPermitidoDevolvePedidoInvalido() throws Exception {
        MvcResult resultado = mvc.perform(post("/api/dona/teste/tempo").with(usuarios.comoDona()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.codigo").value("PEDIDO_INVALIDO"))
                .andReturn();
        semNadaTecnico(resultado);
    }

    @Test
    void jsonQuebradoDevolvePedidoInvalido() throws Exception {
        MvcResult resultado = mvc.perform(post("/api/dona/teste/validar").with(usuarios.comoDona())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{nome:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PEDIDO_INVALIDO"))
                .andReturn();
        semNadaTecnico(resultado);
    }

    @Test
    void dadosInvalidosListamOsCampos() throws Exception {
        MvcResult resultado = mvc.perform(post("/api/dona/teste/validar").with(usuarios.comoDona())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("nome"))
                .andReturn();
        semNadaTecnico(resultado);
    }

    @Test
    void erroInternoNaoVazaDetalheMasRegistraNoLog(CapturedOutput saida) throws Exception {
        MvcResult resultado = mvc.perform(get("/api/dona/teste/falha").with(usuarios.comoDona()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("ERRO_INTERNO"))
                .andExpect(jsonPath("$.detail").value(
                        "Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema."))
                .andReturn();
        semNadaTecnico(resultado);
        assertThat(resultado.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("segredo-interno-que-nao-pode-vazar");
        // O detalhe fica só no log.
        assertThat(saida.getAll()).contains("segredo-interno-que-nao-pode-vazar");
    }

    private static void semNadaTecnico(MvcResult resultado) throws Exception {
        String corpo = resultado.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(corpo).isNotBlank();
        // O número do status vai no campo "status"; os textos para a pessoa não têm número de erro.
        JsonNode problema = JSON.readTree(corpo);
        assertThat(problema.get("title").asString()).doesNotContainPattern("\\d");
        assertThat(problema.get("detail").asString()).doesNotContainPattern("\\d");
        for (String proibido : PROIBIDOS) {
            assertThat(corpo).as("o corpo não pode conter \"%s\": %s", proibido, corpo).doesNotContain(proibido);
        }
    }
}
