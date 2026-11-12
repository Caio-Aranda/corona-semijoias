package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quarentena dos envios de um computador bloqueado (tarefa 5.8; ADR-0013, item 14). Escritos antes do código, com
 * PostgreSQL de verdade. A rota não usa o token de acesso: quem prova que é aquele computador é o token de renovação já
 * revogado (por bloqueio ou desativação, há no máximo 30 dias) mais o identificador do computador.
 */
class QuarentenaTest extends TesteDeIntegracao {

    private static final String ROTA = "/api/vendedor/sync/quarentena";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    ServicoDeQuarentena quarentena;

    @AfterEach
    void voltarRelogio() {
        relogio.reiniciar();
    }

    // --- apoio ---

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

    private record Computador(UsuarioDeTeste vendedor, UUID id, String acesso, String renovacao) {
    }

    private Computador entrou() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID id = UUID.randomUUID();
        JsonNode r = corpoDe(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("login", v.login(), "senha", v.senha(), "dispositivoId", id))))
                .andExpect(status().isOk()));
        return new Computador(v, id, r.get("tokenDeAcesso").asString(), r.get("tokenDeRenovacao").asString());
    }

    private Computador bloqueado() throws Exception {
        Computador c = entrou();
        mvc.perform(post("/api/dona/aparelhos/" + c.id() + "/bloquear").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Roubado\"}")).andExpect(status().isOk());
        return c;
    }

    private static Map<String, Object> clienteNovo(UUID id, String nome) {
        return mapa("operacaoId", id, "tipo", "CLIENTE_NOVO", "formato", 1, "criadoEmLocal", Instant.now().toString(),
                "dados", mapa("id", id, "nome", nome));
    }

    private ResultActions enviar(String renovacao, UUID computador, List<Map<String, Object>> operacoes) throws Exception {
        return mvc.perform(post(ROTA).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                mapa("tokenDeRenovacao", renovacao, "dispositivoId", computador, "horaLocal", Instant.now().toString(),
                        "operacoes", operacoes))));
    }

    private long contar(String tabela, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM corona." + tabela + " WHERE id = ?", Long.class, id);
    }

    // --- o que a rota guarda ---

    @Test
    void computadorBloqueadoGuardaOsEnviosSemAplicarNada() throws Exception {
        Computador c = bloqueado();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // Sem token de acesso nenhum: quem prova é a renovação revogada e o identificador do computador.
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Ana"), clienteNovo(b, "Bia"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("GUARDADO"))
                .andExpect(jsonPath("$.resultados[1].resultado").value("GUARDADO"))
                .andExpect(jsonPath("$.resultados[0].operacaoId").value(a.toString()));

        assertThat(contar("envios_em_quarentena", a)).isEqualTo(1);
        // Nada foi aplicado: nem cliente, nem linha no registro dos envios.
        assertThat(contar("clientes", a)).isZero();
        assertThat(contar("sync_log", a)).isZero();
        Map<String, Object> linha = jdbc.queryForMap("SELECT * FROM corona.envios_em_quarentena WHERE id = ?", a);
        assertThat(linha.get("situacao")).isEqualTo("GUARDADO");
        assertThat(linha.get("dispositivo_id")).isEqualTo(c.id());
        assertThat(linha.get("vendedor_id")).isEqualTo(c.vendedor().vendedorId());
        assertThat(linha.get("tipo")).isEqualTo("CLIENTE_NOVO");
    }

    @Test
    void reenvioDoMesmoConteudoEhJaGuardadoEOutroConteudoNaoSobrescreve() throws Exception {
        Computador c = bloqueado();
        UUID a = UUID.randomUUID();
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isOk());
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("JA_GUARDADO"));
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Outra pessoa"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("RECUSADO"));
        assertThat(contar("envios_em_quarentena", a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT dados->>'nome' FROM corona.envios_em_quarentena WHERE id = ?", String.class, a))
                .isEqualTo("Ana");
    }

    @Test
    void vendedorDesativadoTambemPodeGuardar() throws Exception {
        Computador c = entrou();
        mvc.perform(post("/api/dona/usuarios/" + c.vendedor().id() + "/desativar").with(usuarios.comoDona()))
                .andExpect(status().is2xxSuccessful());
        UUID a = UUID.randomUUID();
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("GUARDADO"));
    }

    @Test
    void loteVazioRespondeSemGuardarNada() throws Exception {
        Computador c = bloqueado();
        enviar(c.renovacao(), c.id(), List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.resultados").isEmpty());
    }

    @Test
    void loteGrandeDemaisEhRecusado() throws Exception {
        Computador c = bloqueado();
        List<Map<String, Object>> muitas = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            muitas.add(clienteNovo(UUID.randomUUID(), "Cliente " + i));
        }
        enviar(c.renovacao(), c.id(), muitas).andExpect(status().is(413));
    }

    // --- quem pode usar a rota ---

    @Test
    void quemNaoProvaSerOComputadorBloqueadoNaoGuardaNadaENadaEhRevelado() throws Exception {
        UUID a = UUID.randomUUID();

        // Token de renovação vivo (computador não bloqueado).
        Computador vivo = entrou();
        enviar(vivo.renovacao(), vivo.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isUnauthorized());

        // Token de acesso no lugar do de renovação; lixo; sem token.
        Computador c = bloqueado();
        enviar(c.acesso(), c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isUnauthorized());
        enviar("lixo", c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isUnauthorized());
        mvc.perform(post(ROTA).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());

        // Outro identificador de computador (token copiado).
        enviar(c.renovacao(), UUID.randomUUID(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isUnauthorized());

        // Revogação por outro motivo (saída): só pede para entrar de novo, não abre a quarentena.
        Computador saiu = entrou();
        mvc.perform(post("/api/auth/sair").with(UsuariosDeTeste.comToken(saiu.acesso())).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
        enviar(saiu.renovacao(), saiu.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isUnauthorized());

        assertThat(contar("envios_em_quarentena", a)).isZero();
    }

    @Test
    void todasAsNegativasSaoIguaisParaNaoRevelarNada() throws Exception {
        Computador vivo = entrou();
        String semToken = corpoDe(mvc.perform(post(ROTA).contentType(MediaType.APPLICATION_JSON).content("{}")
                .accept(MediaType.APPLICATION_JSON))).toString();
        String tokenVivo = corpoDe(enviar(vivo.renovacao(), vivo.id(), List.of())).toString();
        assertThat(tokenVivo).isEqualTo(corpoDe(enviar("lixo", vivo.id(), List.of())).toString());
        assertThat(semToken).isNotBlank();
    }

    @Test
    void revogacaoComAte30DiasAindaAceitaEDepoisDissoNao() throws Exception {
        Computador noLimite = bloqueado();
        Computador alemDoLimite = bloqueado();
        relogio.avancar(Duration.ofDays(29).plusHours(23));
        UUID a = UUID.randomUUID();
        enviar(noLimite.renovacao(), noLimite.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].resultado").value("GUARDADO"));

        relogio.avancar(Duration.ofHours(2)); // 30 dias e 1 hora depois do bloqueio
        UUID b = UUID.randomUUID();
        enviar(alemDoLimite.renovacao(), alemDoLimite.id(), List.of(clienteNovo(b, "Bia"))).andExpect(status().isUnauthorized());
        assertThat(contar("envios_em_quarentena", b)).isZero();
    }

    // --- o que a dona faz depois ---

    @Test
    void aprovarPassaPeloMesmoServicoDoEnvioEDeixaAuditoria() throws Exception {
        Computador c = bloqueado();
        UUID a = UUID.randomUUID();
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isOk());

        UUID dona = usuarios.dona().id();
        quarentena.aprovar(a, dona);

        assertThat(contar("clientes", a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resultado FROM corona.sync_log WHERE id = ?", String.class, a)).isEqualTo("ACEITO");
        Map<String, Object> linha = jdbc.queryForMap("SELECT situacao, decidido_por FROM corona.envios_em_quarentena WHERE id = ?", a);
        assertThat(linha.get("situacao")).isEqualTo("APROVADO");
        assertThat(linha.get("decidido_por")).isEqualTo(dona);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM corona.auditoria WHERE tabela = 'envios_em_quarentena' AND registro_id = ? AND acao = 'QUARENTENA_APROVADA'",
                Long.class, a)).isEqualTo(1);
        // Decidido uma vez só.
        assertThatThrownBy(() -> quarentena.aprovar(a, dona)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> quarentena.descartar(a, dona, "tarde demais")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void descartarNaoAplicaNada() throws Exception {
        Computador c = bloqueado();
        UUID a = UUID.randomUUID();
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isOk());
        UUID dona = usuarios.dona().id();
        quarentena.descartar(a, dona, "Venda duvidosa");
        assertThat(contar("clientes", a)).isZero();
        assertThat(contar("sync_log", a)).isZero();
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.envios_em_quarentena WHERE id = ?", String.class, a))
                .isEqualTo("DESCARTADO");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM corona.auditoria WHERE tabela = 'envios_em_quarentena' AND registro_id = ? AND acao = 'QUARENTENA_DESCARTADA'",
                Long.class, a)).isEqualTo(1);
    }

    @Test
    void nadaApagaLinhaDaQuarentena() throws Exception {
        Computador c = bloqueado();
        UUID a = UUID.randomUUID();
        enviar(c.renovacao(), c.id(), List.of(clienteNovo(a, "Ana"))).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.envios_em_quarentena WHERE id = ?", a))
                .isInstanceOf(Exception.class);
    }
}
