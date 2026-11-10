package br.com.coronasemijoias.api.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.TokensDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listar, ver, editar, desativar e reativar usuários (tarefa 1.4): permissão, regras da dona,
 * revogação das sessões na hora e auditoria.
 */
class GestaoDeUsuariosTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GestaoDeUsuarios gestao;

    private static final String BASE = "/api/dona/usuarios";

    // ---- ajudantes ------------------------------------------------------------------------------------

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions enviar(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
            RequestPostProcessor quem, Object corpo) throws Exception {
        if (corpo != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
        }
        return mvc.perform(req.with(quem));
    }

    private int versaoDe(UsuarioDeTeste u) {
        return jdbc.queryForObject("SELECT version FROM corona.usuarios WHERE id = ?", Integer.class, u.id());
    }

    private Map<String, Object> edicao(UsuarioDeTeste u, String nome, String perfil, UUID vendedorId) {
        Map<String, Object> m = new HashMap<>();
        m.put("nome", nome);
        m.put("perfil", perfil);
        m.put("vendedorId", vendedorId);
        m.put("version", versaoDe(u));
        return m;
    }

    /** Outra dona (ativa), com a qual a dona do teste pode mexer sem derrubar a dona do contexto. */
    private UsuarioDeTeste outraDona() {
        return usuarios.criarDona();
    }

    private RequestPostProcessor como(UsuarioDeTeste u) {
        return UsuariosDeTeste.comToken(usuarios.token(u));
    }

    private List<Map<String, Object>> auditoriaDe(UUID registro) {
        return jdbc.queryForList("""
                SELECT ator_usuario_id, origem, acao, tabela, antes::text AS antes, depois::text AS depois
                  FROM corona.auditoria WHERE registro_id = ? ORDER BY ocorrida_em, id""", registro);
    }

    // ---- permissão --------------------------------------------------------------------------------------

    @Test
    void vendedorRecebe403EmTodasAsRotasNovas() throws Exception {
        UUID id = usuarios.vendedor().id();
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        enviar(get(BASE), vendedor, null).andExpect(status().isForbidden());
        enviar(get(BASE + "/" + id), vendedor, null).andExpect(status().isForbidden());
        enviar(put(BASE + "/" + id), vendedor, Map.of("nome", "X", "perfil", "ADMIN", "version", 0))
                .andExpect(status().isForbidden());
        enviar(post(BASE + "/" + id + "/desativar"), vendedor, null).andExpect(status().isForbidden());
        enviar(post(BASE + "/" + id + "/reativar"), vendedor, null).andExpect(status().isForbidden());
    }

    @Test
    void semTokenRecebe401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/desativar")).andExpect(status().isUnauthorized());
    }

    // ---- listar e ver -----------------------------------------------------------------------------------

    @Test
    void listaComBuscaFiltrosEPaginacaoSemMostrarHash() throws Exception {
        String marca = "zz" + UUID.randomUUID().toString().substring(0, 8);
        UsuarioDeTeste a = usuarios.criar("ADMIN", null, true, "senha-boa-123", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4));
        UsuarioDeTeste b = usuarios.criar("VENDEDOR", UUID.randomUUID(), true, "senha-boa-123", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4));
        UsuarioDeTeste c = usuarios.criar("VENDEDOR", UUID.randomUUID(), false, "senha-boa-123", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4));
        for (UsuarioDeTeste u : List.of(a, b, c)) {
            jdbc.update("UPDATE corona.usuarios SET nome = ? WHERE id = ?", "Pessoa " + marca + " " + u.perfil().toLowerCase(), u.id());
        }
        RequestPostProcessor dona = usuarios.comoDona();

        ResultActions todos = enviar(get(BASE + "?busca=" + marca), dona, null).andExpect(status().isOk());
        JsonNode lista = corpo(todos);
        assertThat(lista.get("total").asLong()).isEqualTo(3);
        assertThat(lista.toString()).doesNotContain("senhaHash").doesNotContain("$2");

        assertThat(corpo(enviar(get(BASE + "?busca=" + marca + "&perfil=VENDEDOR"), dona, null)).get("total").asLong()).isEqualTo(2);
        assertThat(corpo(enviar(get(BASE + "?busca=" + marca + "&ativo=false"), dona, null)).get("total").asLong()).isEqualTo(1);
        assertThat(corpo(enviar(get(BASE + "?busca=" + marca + "&ativo=true&perfil=ADMIN"), dona, null)).get("total").asLong()).isEqualTo(1);
        // busca pelo usuário (login)
        assertThat(corpo(enviar(get(BASE + "?busca=" + a.login()), dona, null)).get("total").asLong()).isEqualTo(1);

        JsonNode pagina = corpo(enviar(get(BASE + "?busca=" + marca + "&tamanho=2&pagina=1"), dona, null));
        assertThat(pagina.get("itens")).hasSize(1);
        assertThat(pagina.get("total").asLong()).isEqualTo(3);
        enviar(get(BASE + "?tamanho=101"), dona, null).andExpect(status().isBadRequest());
        enviar(get(BASE + "?pagina=-1"), dona, null).andExpect(status().isBadRequest());
    }

    @Test
    void verUmUsuarioEUsuarioQueNaoExiste() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        enviar(get(BASE + "/" + v.id()), usuarios.comoDona(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value(v.login()))
                .andExpect(jsonPath("$.perfil").value("VENDEDOR"))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.senhaHash").doesNotExist());
        enviar(get(BASE + "/" + UUID.randomUUID()), usuarios.comoDona(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NAO_ENCONTRADO"));
    }

    // ---- editar -----------------------------------------------------------------------------------------

    @Test
    void editaONomeSemMexerNasSessoesEAudita() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        String token = usuarios.token(v);
        enviar(put(BASE + "/" + v.id()), usuarios.comoDona(), edicao(v, "Nome Novo da Silva", "VENDEDOR", v.vendedorId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Nome Novo da Silva"));
        // o nome não derruba a sessão
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(token))).andExpect(status().isOk());
        List<Map<String, Object>> trilha = auditoriaDe(v.id());
        assertThat(trilha).hasSize(1);
        assertThat(trilha.get(0).get("acao")).isEqualTo("USUARIO_EDITADO");
        assertThat(trilha.get(0).get("origem")).isEqualTo("API");
        assertThat(trilha.get(0).get("ator_usuario_id")).isEqualTo(usuarios.dona().id());
        assertThat((String) trilha.get(0).get("antes")).contains("Pessoa de Teste");
        assertThat((String) trilha.get(0).get("depois")).contains("Nome Novo da Silva").doesNotContain("senha");
    }

    @Test
    void versaoVelhaDa409() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        Map<String, Object> pedido = edicao(v, "Outro Nome", "VENDEDOR", v.vendedorId());
        pedido.put("version", versaoDe(v) + 5);
        enviar(put(BASE + "/" + v.id()), usuarios.comoDona(), pedido).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_ALTERADO"));
    }

    @Test
    void vendedorPrecisaDeCadastroEAdminNaoTem() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste d = outraDona();
        RequestPostProcessor dona = usuarios.comoDona();
        // VENDEDOR sem cadastro
        enviar(put(BASE + "/" + v.id()), dona, edicao(v, "X Y", "VENDEDOR", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("vendedorId"));
        // VENDEDOR com cadastro que não existe
        enviar(put(BASE + "/" + v.id()), dona, edicao(v, "X Y", "VENDEDOR", UUID.randomUUID())).andExpect(status().isBadRequest());
        // ADMIN com cadastro de vendedor
        enviar(put(BASE + "/" + d.id()), dona, edicao(d, "X Y", "ADMIN", usuarios.criarCadastroDeVendedor()))
                .andExpect(status().isBadRequest());
        // perfil que não existe
        enviar(put(BASE + "/" + v.id()), dona, edicao(v, "X Y", "CHEFE", v.vendedorId())).andExpect(status().isBadRequest());
        // nome em branco
        enviar(put(BASE + "/" + v.id()), dona, edicao(v, "  ", "VENDEDOR", v.vendedorId())).andExpect(status().isBadRequest());
    }

    @Test
    void mudarOTipoDeAcessoEncerraAsSessoes() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        String token = usuarios.token(v);
        enviar(put(BASE + "/" + v.id()), usuarios.comoDona(), edicao(v, "Agora Dona", "ADMIN", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.perfil").value("ADMIN"))
                .andExpect(jsonPath("$.vendedorId").doesNotExist());
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(token))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.sessoes WHERE usuario_id = ? AND motivo_da_revogacao = 'ACESSO_ALTERADO'",
                Integer.class, v.id())).isEqualTo(1);
    }

    @Test
    void trocarOVendedorDoUsuarioTambemEncerraAsSessoes() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        String token = usuarios.token(v);
        enviar(put(BASE + "/" + v.id()), usuarios.comoDona(), edicao(v, "Mesmo Nome", "VENDEDOR", usuarios.criarCadastroDeVendedor()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void aDonaNaoMudaOProprioTipoDeAcesso() throws Exception {
        UsuarioDeTeste eu = outraDona();
        enviar(put(BASE + "/" + eu.id()), como(eu), edicao(eu, "Eu Mesma", "VENDEDOR", usuarios.criarCadastroDeVendedor()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("NAO_MUDAR_O_PROPRIO_ACESSO"));
        // mudar só o próprio nome pode
        enviar(put(BASE + "/" + eu.id()), como(eu), edicao(eu, "Eu Mesma Renomeada", "ADMIN", null)).andExpect(status().isOk());
    }

    @Test
    void aDonaRebaixaOutraDonaQuandoSobraAlguemAtiva() throws Exception {
        UsuarioDeTeste alvo = outraDona();
        enviar(put(BASE + "/" + alvo.id()), usuarios.comoDona(), edicao(alvo, "Alvo", "VENDEDOR", usuarios.criarCadastroDeVendedor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.perfil").value("VENDEDOR"));
    }

    @Test
    void oSistemaNuncaFicaSemAdministradoraAtiva() {
        // Pedido de quem não está entre as ativas (só o servico, sem rota): o alvo é a única ativa.
        jdbc.update("UPDATE corona.usuarios SET ativo = false WHERE perfil = 'ADMIN'");
        try {
            UsuarioDeTeste unica = outraDona();
            UUID quem = UUID.randomUUID();
            ErroDaApi aoDesativar = org.junit.jupiter.api.Assertions.assertThrows(ErroDaApi.class, () -> gestao.desativar(unica.id(), quem));
            assertThat(aoDesativar.getBody().getProperties()).containsEntry("codigo", "ULTIMA_ADMINISTRADORA");
            ErroDaApi aoRebaixar = org.junit.jupiter.api.Assertions.assertThrows(ErroDaApi.class,
                    () -> gestao.editar(unica.id(), quem, versaoDe(unica), "Unica", "VENDEDOR", usuarios.criarCadastroDeVendedor()));
            assertThat(aoRebaixar.getBody().getProperties()).containsEntry("codigo", "ULTIMA_ADMINISTRADORA");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.usuarios WHERE perfil = 'ADMIN' AND ativo", Integer.class))
                    .isEqualTo(1);
        } finally {
            jdbc.update("UPDATE corona.usuarios SET ativo = true WHERE id = ?", usuarios.dona().id());
        }
    }

    // ---- desativar e reativar ---------------------------------------------------------------------------

    @Test
    void desativarEncerraAsSessoesNaHoraEBloqueiaLoginERenovacao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        // Entra de verdade (login), para ter token de acesso e de renovação.
        JsonNode login = corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(v.login(), v.senha())))).andExpect(status().isOk()));
        String acesso = login.get("tokenDeAcesso").asString();
        String renovacao = login.get("tokenDeRenovacao").asString();
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(acesso))).andExpect(status().isOk());

        enviar(post(BASE + "/" + v.id() + "/desativar"), usuarios.comoDona(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));

        // o token de acesso que ainda não venceu para de valer
        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(acesso))).andExpect(status().isUnauthorized());
        // o token de renovação antigo falha
        Map<String, Object> pedidoDeRenovacao = new HashMap<>();
        pedidoDeRenovacao.put("tokenDeRenovacao", renovacao);
        pedidoDeRenovacao.put("dispositivoId", UsuariosDeTeste.APARELHO);
        mvc.perform(post("/api/auth/renovar").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(pedidoDeRenovacao))).andExpect(status().is4xxClientError());
        // o login com a senha certa é recusado
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(v.login(), v.senha()))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.sessoes WHERE usuario_id = ? AND revogada_em IS NULL",
                Integer.class, v.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.sessoes WHERE usuario_id = ? AND motivo_da_revogacao = 'USUARIO_DESATIVADO'",
                Integer.class, v.id())).isPositive();
    }

    @Test
    void reativarDeixaEntrarDeNovoMasNaoRessuscitaAsSessoesAntigas() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        JsonNode login = corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(v.login(), v.senha())))).andExpect(status().isOk()));
        String acessoAntigo = login.get("tokenDeAcesso").asString();
        String renovacaoAntiga = login.get("tokenDeRenovacao").asString();

        enviar(post(BASE + "/" + v.id() + "/desativar"), usuarios.comoDona(), null).andExpect(status().isOk());
        enviar(post(BASE + "/" + v.id() + "/reativar"), usuarios.comoDona(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true));

        mvc.perform(get("/api/auth/eu").with(UsuariosDeTeste.comToken(acessoAntigo))).andExpect(status().isUnauthorized());
        Map<String, Object> pedido = new HashMap<>();
        pedido.put("tokenDeRenovacao", renovacaoAntiga);
        pedido.put("dispositivoId", UsuariosDeTeste.APARELHO);
        mvc.perform(post("/api/auth/renovar").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(pedido))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(v.login(), v.senha())))).andExpect(status().isOk());

        List<Map<String, Object>> trilha = auditoriaDe(v.id());
        assertThat(trilha).extracting(l -> l.get("acao")).containsExactly("USUARIO_DESATIVADO", "USUARIO_REATIVADO");
    }

    @Test
    void desativarDeNovoNaoMudaNadaENaoAudita() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        enviar(post(BASE + "/" + v.id() + "/desativar"), usuarios.comoDona(), null).andExpect(status().isOk());
        enviar(post(BASE + "/" + v.id() + "/desativar"), usuarios.comoDona(), null).andExpect(status().isOk());
        enviar(post(BASE + "/" + v.id() + "/reativar"), usuarios.comoDona(), null).andExpect(status().isOk());
        enviar(post(BASE + "/" + v.id() + "/reativar"), usuarios.comoDona(), null).andExpect(status().isOk());
        assertThat(auditoriaDe(v.id())).hasSize(2);
    }

    @Test
    void usuarioQueNaoExisteDa404() throws Exception {
        enviar(post(BASE + "/" + UUID.randomUUID() + "/desativar"), usuarios.comoDona(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NAO_ENCONTRADO"));
        enviar(post(BASE + "/" + UUID.randomUUID() + "/reativar"), usuarios.comoDona(), null).andExpect(status().isNotFound());
    }

    @Test
    void aDonaNaoDesativaASiMesma() throws Exception {
        UsuarioDeTeste eu = outraDona();
        enviar(post(BASE + "/" + eu.id() + "/desativar"), como(eu), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NAO_DESATIVAR_A_SI_MESMA"));
        assertThat(jdbc.queryForObject("SELECT ativo FROM corona.usuarios WHERE id = ?", Boolean.class, eu.id())).isTrue();
    }

    @Test
    void duasDonasQueSeDesativamAoMesmoTempoNuncaDeixamOSistemaSemAdministradora() throws Exception {
        for (int rodada = 0; rodada < 6; rodada++) {
            // só estas duas ficam ativas
            jdbc.update("UPDATE corona.usuarios SET ativo = false WHERE perfil = 'ADMIN'");
            UsuarioDeTeste a = outraDona();
            UsuarioDeTeste b = outraDona();
            RequestPostProcessor comoA = como(a);
            RequestPostProcessor comoB = como(b);
            CountDownLatch largada = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> aDesativaB = () -> {
                    largada.await();
                    return enviar(post(BASE + "/" + b.id() + "/desativar"), comoA, null).andReturn().getResponse().getStatus();
                };
                Callable<Integer> bDesativaA = () -> {
                    largada.await();
                    return enviar(post(BASE + "/" + a.id() + "/desativar"), comoB, null).andReturn().getResponse().getStatus();
                };
                Future<Integer> f1 = pool.submit(aDesativaB);
                Future<Integer> f2 = pool.submit(bDesativaA);
                largada.countDown();
                List<Integer> respostas = List.of(f1.get(), f2.get());
                assertThat(respostas).contains(200);
                assertThat(respostas.stream().filter(c -> c == 200).count()).isEqualTo(1);
                assertThat(respostas).allMatch(c -> c == 200 || c == 401 || c == 409);
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.usuarios WHERE perfil = 'ADMIN' AND ativo", Integer.class))
                    .isEqualTo(1);
        }
        // restaura uma dona ativa para os demais testes do contexto
        jdbc.update("UPDATE corona.usuarios SET ativo = true WHERE id = ?", usuarios.dona().id());
    }

    // ---- auditoria das rotas já existentes --------------------------------------------------------------

    @Test
    void criarERedefinirSenhaPelaRotaFicamNaAuditoriaSemSenhaNemHash() throws Exception {
        Map<String, Object> pedido = new HashMap<>();
        pedido.put("nome", "Pessoa Auditada");
        pedido.put("login", "audit." + UUID.randomUUID().toString().substring(0, 8));
        pedido.put("perfil", "ADMIN");
        JsonNode criado = corpo(enviar(post(BASE), usuarios.comoDona(), pedido).andExpect(status().isCreated()));
        UUID id = UUID.fromString(criado.get("id").asString());
        String senha = criado.get("senhaTemporaria").asString();
        enviar(post(BASE + "/" + id + "/redefinir-senha"), usuarios.comoDona(), null).andExpect(status().isOk());

        List<Map<String, Object>> trilha = auditoriaDe(id);
        assertThat(trilha).extracting(l -> l.get("acao")).containsExactly("USUARIO_CRIADO", "SENHA_REDEFINIDA");
        assertThat(trilha).allSatisfy(l -> {
            assertThat(l.get("origem")).isEqualTo("API");
            assertThat(l.get("ator_usuario_id")).isEqualTo(usuarios.dona().id());
            assertThat(String.valueOf(l.get("antes")) + l.get("depois")).doesNotContain(senha).doesNotContain("$2");
        });
    }

    @Test
    void auditoriaNaoSeAlteraNemSeApaga() {
        UsuarioDeTeste v = usuarios.criarVendedor();
        jdbc.update("""
                INSERT INTO corona.auditoria (id, ator_usuario_id, origem, acao, tabela, registro_id, ocorrida_em)
                VALUES (?, ?, 'API', 'TESTE_FEITO', 'usuarios', ?, now())""", UUID.randomUUID(), usuarios.dona().id(), v.id());
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE corona.auditoria SET acao = 'OUTRA' WHERE registro_id = ?", v.id()));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> jdbc.update("DELETE FROM corona.auditoria WHERE registro_id = ?", v.id()));
    }

    @Test
    void tokenRestritoNaoAbreAsRotasNovas() throws Exception {
        UsuarioDeTeste d = outraDona();
        usuarios.marcarTrocaObrigatoria(d, null);
        String restrito = usuarios.tokenRestrito(d);
        mvc.perform(get(BASE).with(UsuariosDeTeste.comToken(restrito))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("TROCA_DE_SENHA_OBRIGATORIA"));
        assertThat(TokensDeTeste.parte(restrito, 1)).isNotBlank();
    }
}
