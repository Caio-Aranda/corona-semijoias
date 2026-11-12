package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.avisos.AvisosDaDonaController;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * As rotas da dona para as vendas de computador bloqueado (quarentena), os valores de cliente substituídos e os avisos
 * correspondentes (tarefa 5.10; ADR-0013, item 14; §19.5). Escritos antes do código, com PostgreSQL de verdade.
 */
class QuarentenaDaDonaTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ServicoDeAuditoria auditoria;

    @Autowired
    PlatformTransactionManager transacoes;

    @Autowired
    AvisosDaDonaController avisosDaDona;

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return corpo;
    }

    private JsonNode corpoDe(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Um computador bloqueado que deixou um cliente novo guardado em quarentena. */
    private record Guardado(UsuarioDeTeste vendedor, UUID computador, UUID envio) {
    }

    private Guardado guardado(String nome) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID computador = UUID.randomUUID();
        JsonNode login = corpoDe(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("login", v.login(), "senha", v.senha(), "dispositivoId", computador))))
                .andExpect(status().isOk()));
        mvc.perform(post("/api/dona/aparelhos/" + computador + "/bloquear").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Roubado\"}")).andExpect(status().isOk());
        UUID envio = UUID.randomUUID();
        mvc.perform(post("/api/vendedor/sync/quarentena").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("tokenDeRenovacao", login.get("tokenDeRenovacao").asString(),
                        "dispositivoId", computador, "horaLocal", Instant.now().toString(), "operacoes",
                        List.of(mapa("operacaoId", envio, "tipo", "CLIENTE_NOVO", "formato", 1, "criadoEmLocal",
                                Instant.now().toString(), "dados", mapa("id", envio, "nome", nome)))))))
                .andExpect(status().isOk());
        return new Guardado(v, computador, envio);
    }

    @Test
    void listaOQueEstaGuardadoComVendedorETipo() throws Exception {
        Guardado g = guardado("Ana da Quarentena");
        mvc.perform(get("/api/dona/quarentena").with(usuarios.comoDona())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + g.envio() + "')].tipo").value("CLIENTE_NOVO"))
                .andExpect(jsonPath("$[?(@.id=='" + g.envio() + "')].vendedorNome").value(jdbc.queryForObject("SELECT nome FROM corona.vendedores WHERE id = ?", String.class, g.vendedor().vendedorId())))
                .andExpect(jsonPath("$[?(@.id=='" + g.envio() + "')].dispositivoId").value(g.computador().toString()));
    }

    @Test
    void aprovarAplicaEFicaFora() throws Exception {
        Guardado g = guardado("Bia");
        mvc.perform(post("/api/dona/quarentena/" + g.envio() + "/aprovar").with(usuarios.comoDona()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultado").value("ACEITO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.clientes WHERE id = ?", Long.class, g.envio())).isEqualTo(1);
        mvc.perform(get("/api/dona/quarentena").with(usuarios.comoDona()))
                .andExpect(jsonPath("$[?(@.id=='" + g.envio() + "')]").isEmpty());
        // Decidido uma vez só.
        mvc.perform(post("/api/dona/quarentena/" + g.envio() + "/aprovar").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void descartarExigeMotivoENaoAplicaNada() throws Exception {
        Guardado g = guardado("Cida");
        mvc.perform(post("/api/dona/quarentena/" + g.envio() + "/descartar").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/dona/quarentena/" + g.envio() + "/descartar").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Venda duvidosa\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.clientes WHERE id = ?", Long.class, g.envio())).isZero();
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.envios_em_quarentena WHERE id = ?", String.class,
                g.envio())).isEqualTo("DESCARTADO");
    }

    @Test
    void vendedorNaoAcessaNenhumaRotaNova() throws Exception {
        UUID qualquer = UUID.randomUUID();
        mvc.perform(get("/api/dona/quarentena").with(usuarios.comoVendedor())).andExpect(status().isForbidden());
        mvc.perform(post("/api/dona/quarentena/" + qualquer + "/aprovar").with(usuarios.comoVendedor()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/dona/quarentena/" + qualquer + "/descartar").with(usuarios.comoVendedor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/dona/clientes/valores-substituidos").with(usuarios.comoVendedor()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/dona/envios-com-problema").with(usuarios.comoVendedor())).andExpect(status().isForbidden());
        mvc.perform(post("/api/dona/envios-com-problema/" + qualquer + "/tentar-de-novo").with(usuarios.comoVendedor()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/dona/aparelhos/" + qualquer + "/bloquear").with(usuarios.comoVendedor())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void valoresSubstituidosVemDaAuditoria() throws Exception {
        Guardado g = guardado("Dora");
        mvc.perform(post("/api/dona/quarentena/" + g.envio() + "/aprovar").with(usuarios.comoDona())).andExpect(status().isOk());
        new TransactionTemplate(transacoes).executeWithoutResult(t -> auditoria.registrar(Ator.usuario(g.vendedor().id()),
                "CLIENTE_VALOR_SUBSTITUIDO", "clientes", g.envio(), Map.of("telefone", "11 1111-1111"),
                Map.of("telefone", "22 2222-2222")));
        mvc.perform(get("/api/dona/clientes/valores-substituidos").with(usuarios.comoDona())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.clienteId=='" + g.envio() + "')].clienteNome").value("Dora"))
                .andExpect(jsonPath("$[?(@.clienteId=='" + g.envio() + "')].campos[0].campo").value("telefone"))
                .andExpect(jsonPath("$[?(@.clienteId=='" + g.envio() + "')].campos[0].valorAnterior").value("11 1111-1111"))
                .andExpect(jsonPath("$[?(@.clienteId=='" + g.envio() + "')].campos[0].valorNovo").value("22 2222-2222"));
    }

    @Test
    void vendasDeComputadorBloqueadoViramAviso() throws Exception {
        guardado("Eva");
        assertThat(avisosDaDona.listar().avisos()).anyMatch(a -> a.tipo().equals("VENDAS_DE_COMPUTADOR_BLOQUEADO"));
    }
}
