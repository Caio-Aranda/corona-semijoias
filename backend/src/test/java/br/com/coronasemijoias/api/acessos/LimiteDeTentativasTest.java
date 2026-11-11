package br.com.coronasemijoias.api.acessos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin;
import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin.PedidoDeEntrada;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.testes.IpsDeTeste;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Limite de tentativas e registro de acessos (ADR-0004, item 7): bloqueio de login + IP depois de 5
 * erros seguidos, limite só por IP, login inexistente, senha certa durante o bloqueio, IP real atrás
 * do proxy e o registro de cada tentativa. Cada teste usa um IP próprio.
 */
class LimiteDeTentativasTest extends TesteDeIntegracao {

    private static final String BLOQUEADO_15 = "Acesso bloqueado por alguns minutos. Você errou a senha várias vezes. "
            + "Por segurança, espere 15 minutos e tente de novo.";

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
    ServicoDeLogin servicoDeLogin;

    @Autowired
    TravaPorIp travas;

    @AfterEach
    void reiniciarORelogio() {
        relogio.reiniciar();
    }

    private ResultActions entrar(String login, String senha, RequestPostProcessor origem) throws Exception {
        return mvc.perform(post("/api/auth/login").with(origem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(usuarios.corpoDoLogin(login, senha))));
    }

    private ResultActions entrar(UsuarioDeTeste usuario, String senha, String ip) throws Exception {
        return entrar(usuario.login(), senha, IpsDeTeste.de(ip));
    }

    private void errar(UsuarioDeTeste usuario, String ip, int vezes) throws Exception {
        for (int i = 0; i < vezes; i++) {
            entrar(usuario, "senha-errada-1", ip).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
        }
    }

    private long retryAfter(ResultActions resultado) {
        return Long.parseLong(resultado.andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER));
    }

    private List<String> resultados(String login) {
        return jdbc.queryForList("SELECT resultado FROM corona.tentativas_login WHERE login = ? ORDER BY ocorrida_em, id",
                String.class, login);
    }

    // --- Login + IP ---

    @Test
    void cincoErrosSeguidosBloqueiamESoASenhaCertaDepoisDe15MinutosEntra() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        String ip = IpsDeTeste.aleatorio();
        errar(vendedor, ip, 5);

        // Bloqueado: nem a senha certa entra.
        ResultActions bloqueado = entrar(vendedor, vendedor.senha(), ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("ACESSO_BLOQUEADO"))
                .andExpect(jsonPath("$.title").value("Acesso bloqueado por alguns minutos."))
                .andExpect(jsonPath("$.detail").value(BLOQUEADO_15))
                .andExpect(jsonPath("$.tokenDeAcesso").doesNotExist());
        assertThat(retryAfter(bloqueado)).isBetween(890L, 900L);

        // 10 minutos depois, o texto diz o que falta.
        relogio.avancar(Duration.ofMinutes(10));
        ResultActions faltam5 = entrar(vendedor, vendedor.senha(), ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(BLOQUEADO_15.replace("15 minutos", "5 minutos")));
        assertThat(retryAfter(faltam5)).isBetween(290L, 300L);
        relogio.avancar(Duration.ofMinutes(4).plusSeconds(30));
        entrar(vendedor, vendedor.senha(), ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(BLOQUEADO_15.replace("15 minutos", "1 minuto")));

        // Passados os 15 minutos do último erro, entra.
        relogio.avancar(Duration.ofSeconds(31));
        entrar(vendedor, vendedor.senha(), ip).andExpect(status().isOk());

        assertThat(resultados(vendedor.login())).containsExactly("SENHA_INCORRETA", "SENHA_INCORRETA",
                "SENHA_INCORRETA", "SENHA_INCORRETA", "SENHA_INCORRETA", "BLOQUEADO", "BLOQUEADO", "BLOQUEADO", "SUCESSO");
        // O fim do bloqueio fica na linha do 5º erro; IP, aparelho e hora em cada linha.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM corona.tentativas_login
                 WHERE login = ? AND bloqueio_ate IS NOT NULL AND resultado = 'SENHA_INCORRETA'""",
                Long.class, vendedor.login())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM corona.tentativas_login
                 WHERE login = ? AND ip = ?::inet AND dispositivo_id = ? AND ocorrida_em IS NOT NULL""",
                Long.class, vendedor.login(), ip, usuarios.aparelhoDe(vendedor))).isEqualTo(9);
    }

    @Test
    void quatroErrosNaoBloqueiamEUmSucessoNoMeioZeraAContagem() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String ip = IpsDeTeste.aleatorio();
        errar(dona, ip, 4);
        entrar(dona, dona.senha(), ip).andExpect(status().isOk());
        errar(dona, ip, 4);
        entrar(dona, dona.senha(), ip).andExpect(status().isOk());
    }

    @Test
    void oBloqueioEhSoDaquelaCombinacaoDeLoginEIp() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        UsuarioDeTeste colega = usuarios.criarVendedor();
        String ip = IpsDeTeste.aleatorio();
        errar(vendedor, ip, 5);
        entrar(vendedor, vendedor.senha(), ip).andExpect(status().isTooManyRequests());

        // Do outro computador, o mesmo vendedor entra; do mesmo computador, o colega entra.
        entrar(vendedor, vendedor.senha(), IpsDeTeste.aleatorio()).andExpect(status().isOk());
        entrar(colega, colega.senha(), ip).andExpect(status().isOk());
    }

    @Test
    void loginQueNaoExisteEhContadoDoMesmoJeito() throws Exception {
        String ninguem = "ninguem." + UUID.randomUUID().toString().substring(0, 8);
        String ip = IpsDeTeste.aleatorio();
        for (int i = 0; i < 5; i++) {
            entrar(ninguem, "senha-1", IpsDeTeste.de(ip)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("LOGIN_INVALIDO"));
        }
        entrar(ninguem, "senha-1", IpsDeTeste.de(ip)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("ACESSO_BLOQUEADO"));
        assertThat(resultados(ninguem)).containsExactly("SENHA_INCORRETA", "SENHA_INCORRETA", "SENHA_INCORRETA",
                "SENHA_INCORRETA", "SENHA_INCORRETA", "BLOQUEADO");
    }

    @Test
    void senhaTemporariaVencidaNaoContaComoErro() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(vendedor, Instant.now().minus(Duration.ofDays(1)));
        String ip = IpsDeTeste.aleatorio();
        for (int i = 0; i < 6; i++) {
            entrar(vendedor, vendedor.senha(), ip).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("SENHA_TEMPORARIA_VENCIDA"));
        }
        assertThat(resultados(vendedor.login())).hasSize(6).containsOnly("SENHA_TEMPORARIA_VENCIDA");
        // E não zera a sequência de erros: 4 erros antes + 1 depois das vencidas ainda bloqueiam.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        errar(outro, ip, 4);
        usuarios.marcarTrocaObrigatoria(outro, Instant.now().minus(Duration.ofDays(1)));
        entrar(outro, outro.senha(), ip).andExpect(status().isUnauthorized());
        errar(outro, ip, 1);
        entrar(outro, outro.senha(), ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void usuarioDesativadoComASenhaCertaEhGravadoENaoConta() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        usuarios.desativar(vendedor);
        String ip = IpsDeTeste.aleatorio();
        entrar(vendedor, vendedor.senha(), ip).andExpect(status().isForbidden());
        assertThat(resultados(vendedor.login())).containsExactly("USUARIO_DESATIVADO");
    }

    // --- Só IP ---

    @Test
    void muitosErrosComLoginsVariadosBloqueiamOIp() throws Exception {
        String ip = IpsDeTeste.aleatorio();
        for (int i = 0; i < LIMITE_POR_IP; i++) {
            entrar("tentativa." + i + "." + UUID.randomUUID(), "senha-1", IpsDeTeste.de(ip))
                    .andExpect(status().isUnauthorized());
        }
        UsuarioDeTeste dona = usuarios.criarDona();
        ResultActions bloqueado = entrar(dona, dona.senha(), ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(BLOQUEADO_15));
        assertThat(retryAfter(bloqueado)).isBetween(890L, 900L);
        // Outro IP não é afetado, e o bloqueio do IP acaba sozinho.
        entrar(dona, dona.senha(), IpsDeTeste.aleatorio()).andExpect(status().isOk());
        relogio.avancar(Duration.ofMinutes(15).plusSeconds(1));
        entrar(dona, dona.senha(), ip).andExpect(status().isOk());
    }

    @Test
    void errosForaDaJanelaDe15MinutosNaoContamParaOIp() throws Exception {
        String ip = IpsDeTeste.aleatorio();
        for (int i = 0; i < LIMITE_POR_IP - 1; i++) {
            entrar("janela." + i + "." + UUID.randomUUID(), "senha-1", IpsDeTeste.de(ip)).andExpect(status().isUnauthorized());
        }
        relogio.avancar(Duration.ofMinutes(16));
        entrar("janela.ultimo." + UUID.randomUUID(), "senha-1", IpsDeTeste.de(ip)).andExpect(status().isUnauthorized());
        UsuarioDeTeste dona = usuarios.criarDona();
        entrar(dona, dona.senha(), ip).andExpect(status().isOk());
    }

    // --- Pedidos em paralelo ---

    @Test
    void pedidosErradosEmParaleloDoMesmoIpNaoPassamDoLimite() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        String ip = IpsDeTeste.aleatorio();
        int quantos = 10;
        ExecutorService executor = Executors.newFixedThreadPool(quantos);
        List<Integer> status = new ArrayList<>();
        try {
            CyclicBarrier largada = new CyclicBarrier(quantos);
            List<Future<Integer>> chamadas = new ArrayList<>();
            for (int i = 0; i < quantos; i++) {
                chamadas.add(executor.submit(() -> {
                    largada.await(10, TimeUnit.SECONDS);
                    try {
                        servicoDeLogin.entrar(new PedidoDeEntrada(vendedor.login(), "senha-errada-1",
                                usuarios.aparelhoDe(vendedor), ip, br.com.coronasemijoias.api.aplicativo.TipoDeApp.VENDAS));
                        return 200;
                    } catch (ErroDaApi erro) {
                        return erro.getStatusCode().value();
                    }
                }));
            }
            for (Future<Integer> chamada : chamadas) {
                status.add(chamada.get(60, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(status.stream().filter(s -> s == 401).count()).isEqualTo(5);
        assertThat(status.stream().filter(s -> s == 429).count()).isEqualTo(quantos - 5);
        assertThat(resultados(vendedor.login()).stream().filter("SENHA_INCORRETA"::equals).count()).isEqualTo(5);
    }

    @Test
    void quemEsperaAVezDoIpAlemDoLimiteRecebe429Curto() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String ip = IpsDeTeste.aleatorio();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TravaPorIp.Vez ocupada = travas.tentar(ip, Duration.ofSeconds(1)).orElseThrow()) {
            // Outro pedido do mesmo IP segura a vez: este espera 2 s (configuração dos testes) e desiste.
            Future<ErroDaApi> pedido = executor.submit(() -> {
                try {
                    servicoDeLogin.entrar(new PedidoDeEntrada(dona.login(), dona.senha(), null, ip,
                            br.com.coronasemijoias.api.aplicativo.TipoDeApp.GESTAO));
                    return null;
                } catch (ErroDaApi erro) {
                    return erro;
                }
            });
            ErroDaApi erro = pedido.get(30, TimeUnit.SECONDS);
            assertThat(erro).isNotNull();
            assertThat(erro.getStatusCode().value()).isEqualTo(429);
            assertThat(erro.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
            assertThat(erro.getBody().getDetail()).isEqualTo(BLOQUEADO_15.replace("15 minutos", "1 minuto"));
        } finally {
            executor.shutdownNow();
        }
        assertThat(resultados(dona.login())).containsExactly("BLOQUEADO");
        entrar(dona, dona.senha(), ip).andExpect(status().isOk());
    }

    // --- IP real ---

    @Test
    void doProxyConfiavelValeOIpEncaminhado() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String cliente = "203.0.113." + (1 + (int) (Math.random() * 250));
        RequestPostProcessor peloProxy = requisicao -> {
            requisicao.setRemoteAddr(PROXY_CONFIAVEL);
            // A entrada da esquerda foi escrita pelo cliente: não vale.
            requisicao.addHeader("X-Forwarded-For", "1.2.3.4, " + cliente);
            return requisicao;
        };
        entrar(dona.login(), "senha-errada-1", peloProxy).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT host(ip) FROM corona.tentativas_login WHERE login = ?", String.class,
                dona.login())).isEqualTo(cliente);
    }

    @Test
    void deForaDoProxyOCabecalhoEncaminhadoEhIgnorado() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        String conexao = IpsDeTeste.aleatorio();
        RequestPostProcessor direto = requisicao -> {
            requisicao.setRemoteAddr(conexao);
            requisicao.addHeader("X-Forwarded-For", "203.0.113.99");
            return requisicao;
        };
        // Quem tenta trocar de IP pelo cabeçalho continua sendo contado pelo endereço da conexão.
        for (int i = 0; i < 5; i++) {
            entrar(dona.login(), "senha-errada-1", direto).andExpect(status().isUnauthorized());
        }
        entrar(dona.login(), dona.senha(), direto).andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForList("SELECT DISTINCT host(ip) FROM corona.tentativas_login WHERE login = ?",
                String.class, dona.login())).containsExactly(conexao);
    }

    @Test
    void loginEnormeNemEhTentativa() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", "x".repeat(201), "senha", "senha-1"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }
}
