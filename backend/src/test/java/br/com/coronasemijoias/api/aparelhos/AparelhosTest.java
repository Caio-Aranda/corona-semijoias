package br.com.coronasemijoias.api.aparelhos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Computadores do vendedor (tarefa 5.3; ADR-0013, itens 13 e 14): primeiro e segundo computador, troca pela dona,
 * bloqueio com a ordem de apagar, vendedor desativado, computador de outra pessoa, 403 cruzado, vender só depois de
 * conferir e a chave estrangeira da origem. Escritos antes do código.
 */
class AparelhosTest extends TesteDeIntegracao {

    private static final AtomicInteger SEQUENCIA = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ServicoDeEstoque estoque;

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

    private ResultActions postar(String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
        return mvc.perform(post(caminho).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo == null ? mapa() : corpo)));
    }

    private ResultActions ler(String caminho, RequestPostProcessor quem) throws Exception {
        return mvc.perform(get(caminho).with(quem));
    }

    /** Login de verdade pelo Corona Vendas num computador escolhido; devolve a resposta. */
    private ResultActions entrar(UsuarioDeTeste vendedor, UUID aparelho) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("login", vendedor.login(), "senha", vendedor.senha(),
                        "dispositivoId", aparelho))));
    }

    private record Login(String acesso, String renovacao) {
    }

    private Login entrou(UsuarioDeTeste vendedor, UUID aparelho) throws Exception {
        JsonNode r = corpoDe(entrar(vendedor, aparelho).andExpect(status().isOk()));
        return new Login(r.get("tokenDeAcesso").asString(), r.get("tokenDeRenovacao").asString());
    }

    private ResultActions renovar(String renovacao, UUID aparelho) throws Exception {
        return mvc.perform(post("/api/auth/renovar").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("tokenDeRenovacao", renovacao, "dispositivoId", aparelho))));
    }

    private String situacao(UUID aparelho) {
        return jdbc.queryForObject("SELECT situacao FROM corona.dispositivos WHERE id = ?", String.class, aparelho);
    }

    private RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    private record Peca(UUID id) {
    }

    private Peca peca(String preco, int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + sufixo))
                .andExpect(status().isCreated())).get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Anel " + sufixo,
                "categoriaId", categoria, "precoVenda", preco, "precoCusto", "10.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "A-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        return new Peca(id);
    }

    private UUID maleta(UsuarioDeTeste vendedor, Peca peca, int quantidade) throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(2600, 1, 1).plusDays(10L * n);
        UUID semana = UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 95_000 + n,
                "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated()))
                .get("id").asString());
        JsonNode r = corpoDe(postar("/api/dona/remessas", dona(), mapa("id", UUID.randomUUID(), "vendedorId",
                vendedor.vendedorId(), "semanaId", semana, "tipo", "NOVA")).andExpect(status().isCreated()));
        String id = r.get("id").asString();
        mvc.perform(put("/api/dona/remessas/" + id + "/itens/" + peca.id()).with(dona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", quantidade, "version", r.get("version").asInt()))))
                .andExpect(status().isOk());
        postar("/api/dona/remessas/" + id + "/enviar", dona(), null).andExpect(status().isCreated());
        return UUID.fromString(id);
    }

    private UUID cliente() throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/clientes", dona(), mapa("nome", "Cliente " + UUID.randomUUID()))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private ResultActions conferir(RequestPostProcessor quem, UUID maleta, Peca peca, int contada) throws Exception {
        return postar("/api/vendedor/maletas/" + maleta + "/conferencia", quem,
                mapa("id", UUID.randomUUID(), "itens", List.of(mapa("produtoId", peca.id(), "contada", contada))));
    }

    private ResultActions vender(RequestPostProcessor quem, UUID maleta, UUID cliente, Peca peca) throws Exception {
        return postar("/api/vendedor/vendas", quem, mapa("id", UUID.randomUUID(), "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", peca.id(), "quantidade", 1)), "pagamentos", List.of()));
    }

    // --- primeiro e segundo computador ---

    @Test
    void primeiroComputadorFicaEmUsoESegundoEsperaADona() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID primeiro = UUID.randomUUID();
        UUID segundo = UUID.randomUUID();

        Login noPrimeiro = entrou(v, primeiro);
        assertThat(situacao(primeiro)).isEqualTo("ATIVO");
        // O segundo entra (o login funciona), mas espera a dona.
        Login noSegundo = entrou(v, segundo);
        assertThat(situacao(segundo)).isEqualTo("AGUARDANDO_LIBERACAO");

        ler("/api/vendedor/maletas", UsuariosDeTeste.comToken(noPrimeiro.acesso())).andExpect(status().isOk());
        ler("/api/vendedor/maletas", UsuariosDeTeste.comToken(noSegundo.acesso())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APARELHO_NAO_LIBERADO"))
                .andExpect(jsonPath("$.title").value("Este computador ainda não foi liberado pela dona."))
                .andExpect(jsonPath("$.acao").doesNotExist());
        // "Quem sou eu" e a saída continuam: a tela mostra o aviso e deixa sair.
        ler("/api/auth/eu", UsuariosDeTeste.comToken(noSegundo.acesso())).andExpect(status().isOk());

        // Entrar de novo no primeiro não muda nada; o contato fica guardado.
        entrou(v, primeiro);
        assertThat(situacao(primeiro)).isEqualTo("ATIVO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.dispositivos WHERE usuario_id = ?", Long.class, v.id()))
                .isEqualTo(2);
    }

    @Test
    void computadorDeOutraPessoaNaoEntra() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID computador = UUID.randomUUID();
        entrou(a, computador);

        entrar(b, computador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APARELHO_DE_OUTRA_PESSOA"))
                .andExpect(jsonPath("$.tokenDeAcesso").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT usuario_id FROM corona.dispositivos WHERE id = ?", UUID.class, computador))
                .isEqualTo(a.id());
        assertThat(jdbc.queryForObject("SELECT resultado FROM corona.tentativas_login WHERE login = ? ORDER BY ocorrida_em DESC LIMIT 1",
                String.class, b.login())).isEqualTo("APARELHO_DE_OUTRA_PESSOA");
    }

    // --- trocar computador ---

    @Test
    void trocarComputadorPassaAsMaletasEOAntigoSoEnvia() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID antigo = UUID.randomUUID();
        UUID novo = UUID.randomUUID();
        Peca anel = peca("30.00", 10);
        UUID conferida = maleta(v, anel, 3);
        UUID semConferir = maleta(v, anel, 2);
        UUID cliente = cliente();

        RequestPostProcessor noAntigo = UsuariosDeTeste.comToken(entrou(v, antigo).acesso());
        conferir(noAntigo, conferida, anel, 3).andExpect(status().isCreated());
        RequestPostProcessor noNovo = UsuariosDeTeste.comToken(entrou(v, novo).acesso());
        vender(noNovo, conferida, cliente, anel).andExpect(status().isForbidden());

        JsonNode liberado = corpoDe(postar("/api/dona/aparelhos/" + novo + "/liberar", dona(), null).andExpect(status().isOk()));
        assertThat(liberado.get("situacao").asString()).isEqualTo("ATIVO");
        assertThat(situacao(antigo)).isEqualTo("SUBSTITUIDO");
        assertThat(jdbc.queryForObject("SELECT substituido_por FROM corona.dispositivos WHERE id = ?", UUID.class, antigo))
                .isEqualTo(novo);
        // A maleta conferida no antigo passa para o novo; a que ainda não foi conferida fica livre para o novo conferir.
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, conferida))
                .isEqualTo(novo);
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, semConferir))
                .isNull();

        vender(noNovo, conferida, cliente, anel).andExpect(status().isCreated());
        // O antigo não vende nem recebe dados: só enviará o que guardou (tarefa 5.5).
        vender(noAntigo, conferida, cliente, anel).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APARELHO_SUBSTITUIDO"))
                .andExpect(jsonPath("$.acao").doesNotExist());
        ler("/api/vendedor/maletas", noAntigo).andExpect(status().isForbidden());

        // Auditoria das duas pontas.
        assertThat(jdbc.queryForList("SELECT acao FROM corona.auditoria WHERE tabela = 'dispositivos' AND registro_id IN (?, ?)",
                String.class, antigo, novo)).contains("APARELHO_LIBERADO", "APARELHO_SUBSTITUIDO");
        // Liberar de novo o que já está em uso não muda nada; o trocado não volta.
        postar("/api/dona/aparelhos/" + novo + "/liberar", dona(), null).andExpect(status().isOk());
        postar("/api/dona/aparelhos/" + antigo + "/liberar", dona(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("APARELHO_NAO_PODE_SER_LIBERADO"));
        postar("/api/dona/aparelhos/" + UUID.randomUUID() + "/liberar", dona(), null).andExpect(status().isNotFound());
    }

    @Test
    void aDonaVeOsComputadoresDoVendedorEOVendedorNaoMexe() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        Login login = entrou(v, a1);
        entrou(v, a2);
        entrou(outro, UUID.randomUUID());

        JsonNode lista = corpoDe(ler("/api/dona/vendedores/" + v.vendedorId() + "/aparelhos", dona()).andExpect(status().isOk()));
        assertThat(lista.size()).isEqualTo(2);
        assertThat(lista.findValuesAsString("id")).containsExactlyInAnyOrder(a1.toString(), a2.toString());
        assertThat(lista.get(0).get("ultimoContatoEm").isNull()).isFalse();
        ler("/api/dona/vendedores/" + UUID.randomUUID() + "/aparelhos", dona()).andExpect(status().isNotFound());

        // O vendedor nunca usa as rotas da dona (nem para se liberar).
        RequestPostProcessor vendedor = UsuariosDeTeste.comToken(login.acesso());
        ler("/api/dona/vendedores/" + v.vendedorId() + "/aparelhos", vendedor).andExpect(status().isForbidden());
        postar("/api/dona/aparelhos/" + a2 + "/liberar", vendedor, null).andExpect(status().isForbidden());
        postar("/api/dona/aparelhos/" + a1 + "/bloquear", vendedor, null).andExpect(status().isForbidden());
        assertThat(situacao(a2)).isEqualTo("AGUARDANDO_LIBERACAO");
    }

    // --- bloqueio e desativação: a ordem de apagar ---

    @Test
    void computadorBloqueadoRecebeAOrdemDeApagarEmTodaChamada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID computador = UUID.randomUUID();
        Login login = entrou(v, computador);

        JsonNode bloqueado = corpoDe(postar("/api/dona/aparelhos/" + computador + "/bloquear", dona(),
                mapa("motivo", "Roubado na feira")).andExpect(status().isOk()));
        assertThat(bloqueado.get("situacao").asString()).isEqualTo("BLOQUEADO");
        assertThat(bloqueado.get("motivoBloqueio").asString()).isEqualTo("Roubado na feira");
        assertThat(jdbc.queryForList("SELECT DISTINCT motivo_da_revogacao FROM corona.sessoes WHERE dispositivo_id = ?",
                String.class, computador)).containsExactly("APARELHO_BLOQUEADO");

        // Token de acesso, renovação e novo login: 403 com a ordem de apagar.
        ler("/api/vendedor/maletas", UsuariosDeTeste.comToken(login.acesso())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APARELHO_BLOQUEADO"))
                .andExpect(jsonPath("$.acao").value("APAGAR_DADOS"));
        ler("/api/auth/eu", UsuariosDeTeste.comToken(login.acesso())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.acao").value("APAGAR_DADOS"));
        renovar(login.renovacao(), computador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APARELHO_BLOQUEADO"))
                .andExpect(jsonPath("$.acao").value("APAGAR_DADOS"));
        entrar(v, computador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APARELHO_BLOQUEADO"))
                .andExpect(jsonPath("$.acao").value("APAGAR_DADOS"))
                .andExpect(jsonPath("$.tokenDeAcesso").doesNotExist());
        // Quem copiou o token para outro computador não recebe a ordem (nem um token novo).
        renovar(login.renovacao(), UUID.randomUUID()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.acao").doesNotExist());

        // Bloquear de novo não muda nada; bloqueado não volta.
        postar("/api/dona/aparelhos/" + computador + "/bloquear", dona(), null).andExpect(status().isOk());
        postar("/api/dona/aparelhos/" + computador + "/liberar", dona(), null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.auditoria WHERE tabela = 'dispositivos' AND registro_id = ? AND acao = 'APARELHO_BLOQUEADO'",
                Long.class, computador)).isEqualTo(1);
    }

    @Test
    void vendedorDesativadoRecebeAOrdemDeApagarEADonaNao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID computador = UUID.randomUUID();
        Login login = entrou(v, computador);
        postar("/api/dona/usuarios/" + v.id() + "/desativar", dona(), null).andExpect(status().is2xxSuccessful());

        ler("/api/vendedor/maletas", UsuariosDeTeste.comToken(login.acesso())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"))
                .andExpect(jsonPath("$.acao").value("APAGAR_DADOS"));
        renovar(login.renovacao(), computador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"))
                .andExpect(jsonPath("$.acao").value("APAGAR_DADOS"));
        entrar(v, computador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"))
                .andExpect(jsonPath("$.acao").value("APAGAR_DADOS"));

        // A dona desativada (Corona Gestão, sem dados no computador) continua no 401 de sempre, sem ordem.
        UsuarioDeTeste outraDona = usuarios.criarDona();
        String tokenDaDona = usuarios.token(outraDona);
        usuarios.desativar(outraDona);
        ler("/api/auth/eu", UsuariosDeTeste.comToken(tokenDaDona)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("USUARIO_DESATIVADO"))
                .andExpect(jsonPath("$.acao").doesNotExist());
    }

    @Test
    void reusoDoTokenSoPedeParaEntrarDeNovo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID computador = UUID.randomUUID();
        Login login = entrou(v, computador);
        postar("/api/auth/sair", UsuariosDeTeste.comToken(login.acesso()), null).andExpect(status().isNoContent());
        renovar(login.renovacao(), computador).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SESSAO_TERMINADA"))
                .andExpect(jsonPath("$.acao").doesNotExist());
        assertThat(situacao(computador)).isEqualTo("ATIVO");
    }

    // --- vender só depois de conferir ---

    @Test
    void vendaAntesDeConferirEhRecusadaEAConferenciaLigaOComputador() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        Peca anel = peca("25.00", 5);
        UUID maleta = maleta(v, anel, 2);
        UUID cliente = cliente();
        RequestPostProcessor quem = UsuariosDeTeste.comToken(usuarios.token(v));

        vender(quem, maleta, cliente, anel).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("MALETA_NAO_CONFERIDA"))
                .andExpect(jsonPath("$.title").value("Confira a maleta antes de vender."));
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, maleta)).isNull();

        conferir(quem, maleta, anel, 2).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, maleta))
                .isEqualTo(usuarios.aparelhoDe(v));
        vender(quem, maleta, cliente, anel).andExpect(status().isCreated());
    }

    // --- chave estrangeira da origem ---

    @Test
    void origemDeComputadorSemCadastroEhRecusadaPeloBanco() {
        UUID desconhecido = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.execute((java.sql.Connection c) -> {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                // set_config(..., true) vale só na transação: as duas linhas precisam estar na mesma.
                s.execute("SELECT set_config('corona.origem_dispositivo', '" + desconhecido + "', true)");
                s.execute("INSERT INTO corona.categorias (id, nome) VALUES ('" + UUID.randomUUID() + "', 'Cat " + desconhecido + "')");
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }
}
