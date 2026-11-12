package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas;
import br.com.coronasemijoias.api.testes.CpfsDeTeste;
import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
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
 * O push do vendedor (tarefa 5.5; ADR-0013, itens 2, 8, 11 e 13): cada resultado, reenvio do lote inteiro e de parte,
 * JSON equivalente, dois envios ao mesmo tempo, dependência, saldo, maleta em outro computador e maleta fechada, relógio
 * errado e o "mesmo dia" do estorno, cliente campo a campo, CPF repetido, computador trocado, envios com problema da dona
 * e a retenção de 90 dias. Escritos antes do código.
 */
class PushDoVendedorTest extends TesteDeIntegracao {

    private static final String PUSH = "/api/vendedor/sync/push";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    ServicoDeEstoque estoque;

    @Autowired
    ServicoDeRemessas remessas;

    @Autowired
    ServicoDeConferencia conferencias;

    @Autowired
    ServicoDeVendas vendas;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    EsvaziamentoDoRegistroDeEnvios esvaziamento;

    @AfterEach
    void reiniciarORelogio() {
        relogio.reiniciar();
    }

    private CenarioDeSync cenario() {
        return new CenarioDeSync(jdbc, usuarios, estoque, remessas, conferencias, vendas);
    }

    private static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], pares[i + 1]);
        }
        return m;
    }

    private Map<String, Object> op(UUID id, String tipo, Map<String, Object> dados) {
        return mapa("operacaoId", id, "tipo", tipo, "formato", 1, "criadoEmLocal", Instant.now().toString(), "dados", dados);
    }

    private Map<String, Object> venda(UUID id, UUID maleta, UUID cliente, UUID peca, int quantidade, Instant quando) {
        return op(id, "VENDA", mapa("id", id, "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", peca, "quantidade", quantidade)), "pagamentos", List.of(),
                "vendidaEm", quando == null ? null : quando.toString()));
    }

    private ResultActions enviar(RequestPostProcessor quem, Instant horaLocal, Integer aguardando, List<?> operacoes)
            throws Exception {
        return mvc.perform(post(PUSH).with(quem).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                mapa("horaLocal", horaLocal == null ? null : horaLocal.toString(), "aguardandoDepoisDeste", aguardando,
                        "operacoes", operacoes))));
    }

    private JsonNode resultados(RequestPostProcessor quem, List<?> operacoes) throws Exception {
        return resultados(quem, Instant.now(), 0, operacoes);
    }

    private JsonNode resultados(RequestPostProcessor quem, Instant horaLocal, Integer aguardando, List<?> operacoes)
            throws Exception {
        String corpo = enviar(quem, horaLocal, aguardando, operacoes).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(corpo.toLowerCase()).doesNotContain("custo");
        return json.readTree(corpo).get("resultados");
    }

    private static String resultado(JsonNode r, int i) {
        return r.get(i).get("resultado").asString();
    }

    private RequestPostProcessor como(UsuarioDeTeste v) {
        return UsuariosDeTeste.comToken(usuarios.token(v));
    }

    private record Montado(CenarioDeSync c, UsuarioDeTeste v, UUID peca, UUID maleta, UUID cliente) {
    }

    /** Vendedor com maleta conferida (3 peças de 30,00) e um cliente dele. */
    private Montado montar() {
        CenarioDeSync c = cenario();
        UsuarioDeTeste v = usuarios.criarVendedor();
        usuarios.token(v);
        UUID peca = c.peca(new BigDecimal("30.00"), 10);
        UUID maleta = c.maleta(v, Map.of(peca, 3));
        c.conferir(v, maleta);
        return new Montado(c, v, peca, maleta, c.cliente(v.vendedorId()));
    }

    // --- cada resultado ---

    @Test
    void aceitoJaRecebidoEConflitoNoReenvioEJsonEquivalenteDaJaRecebido() throws Exception {
        Montado m = montar();
        RequestPostProcessor quem = como(m.v());
        UUID id = UUID.randomUUID();
        Map<String, Object> venda = venda(id, m.maleta(), m.cliente(), m.peca(), 1, Instant.now());
        assertThat(resultado(resultados(quem, List.of(venda)), 0)).isEqualTo("ACEITO");
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(2);
        // A resposta se perdeu: o lote inteiro volta. Nada duplica.
        assertThat(resultado(resultados(quem, List.of(venda)), 0)).isEqualTo("JA_RECEBIDO");
        // O mesmo conteúdo com os campos em outra ordem e o dinheiro escrito diferente: o mesmo resumo.
        @SuppressWarnings("unchecked")
        Map<String, Object> dados = (Map<String, Object>) venda.get("dados");
        Map<String, Object> outraOrdem = new LinkedHashMap<>();
        List<String> chaves = new ArrayList<>(dados.keySet());
        java.util.Collections.reverse(chaves);
        chaves.forEach(k -> outraOrdem.put(k, dados.get(k)));
        outraOrdem.put("totalConferido", null);
        assertThat(resultado(resultados(quem, List.of(op(id, "VENDA", outraOrdem))), 0)).isEqualTo("JA_RECEBIDO");
        // Mesmo id, outro conteúdo: conflito, nada é sobrescrito.
        JsonNode conflito = resultados(quem, List.of(venda(id, m.maleta(), m.cliente(), m.peca(), 2, Instant.now())));
        assertThat(resultado(conflito, 0)).isEqualTo("CONFLITO");
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.vendas WHERE id = ?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resultado FROM corona.sync_log WHERE id = ?", String.class, id)).isEqualTo("ACEITO");
        // O computador registrou o envio completo.
        Map<String, Object> d = jdbc.queryForMap("""
                SELECT ultimo_push_em IS NOT NULL AS push, aguardando_envio, ultimo_envio_completo_em IS NOT NULL AS completo
                  FROM corona.dispositivos WHERE id = ?""", usuarios.aparelhoDe(m.v()));
        assertThat(d).containsEntry("push", true).containsEntry("aguardando_envio", 0).containsEntry("completo", true);
    }

    @Test
    void recusadoFicaParaADonaComOJsonESaldoNuncaFicaNegativo() throws Exception {
        Montado m = montar();
        RequestPostProcessor quem = como(m.v());
        UUID semSaldo = UUID.randomUUID();
        JsonNode r = resultados(quem, List.of(venda(semSaldo, m.maleta(), m.cliente(), m.peca(), 5, Instant.now())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(r.get(0).get("codigo").asString()).isEqualTo("VENDA_SEM_SALDO");
        assertThat(r.get(0).get("mensagem").asString()).contains("Confira a quantidade");
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(3);
        Map<String, Object> log = jdbc.queryForMap("SELECT resultado, codigo, dados IS NOT NULL AS tem_dados FROM corona.sync_log WHERE id = ?",
                semSaldo);
        assertThat(log).containsEntry("resultado", "RECUSADO").containsEntry("tem_dados", true);
        // Reenviar a recusada devolve a mesma recusa (o app já sabe).
        assertThat(resultado(resultados(quem, List.of(venda(semSaldo, m.maleta(), m.cliente(), m.peca(), 5, Instant.now()))), 0))
                .isIn("RECUSADO", "CONFLITO");

        // A dona vê e descarta (com motivo).
        JsonNode lista = json.readTree(mvc.perform(get("/api/dona/envios-com-problema").with(usuarios.comoDona()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(lista.findValuesAsString("id")).contains(semSaldo.toString());
        mvc.perform(post("/api/dona/envios-com-problema/" + semSaldo + "/descartar").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/dona/envios-com-problema/" + semSaldo + "/descartar").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Venda lançada errado\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT resolucao FROM corona.sync_log WHERE id = ?", String.class, semSaldo))
                .isEqualTo("DESCARTADO");
        mvc.perform(post("/api/dona/envios-com-problema/" + semSaldo + "/descartar").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"de novo\"}")).andExpect(status().isConflict());
        // O vendedor nunca usa as rotas da dona.
        mvc.perform(get("/api/dona/envios-com-problema").with(quem)).andExpect(status().isForbidden());
    }

    @Test
    void aDonaTentaDeNovoDepoisDeCorrigirACausa() throws Exception {
        CenarioDeSync c = cenario();
        UsuarioDeTeste v = usuarios.criarVendedor();
        RequestPostProcessor quem = como(v);
        UUID peca = c.peca(new BigDecimal("20.00"), 5);
        UUID maleta = c.maleta(v, Map.of(peca, 2)); // enviada, ainda sem conferência
        UUID id = UUID.randomUUID();
        JsonNode r = resultados(quem, List.of(venda(id, maleta, c.cliente(null), peca, 1, Instant.now())));
        assertThat(r.get(0).get("codigo").asString()).isEqualTo("MALETA_NAO_CONFERIDA");
        // Tentar de novo sem corrigir: o erro volta e o envio continua esperando.
        mvc.perform(post("/api/dona/envios-com-problema/" + id + "/tentar-de-novo").with(usuarios.comoDona()))
                .andExpect(status().isConflict());
        c.conferir(v, maleta);
        mvc.perform(post("/api/dona/envios-com-problema/" + id + "/tentar-de-novo").with(usuarios.comoDona()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT resultado, resolucao FROM corona.sync_log WHERE id = ?", id))
                .containsEntry("resultado", "ACEITO").containsEntry("resolucao", "TENTADO_DE_NOVO");
        assertThat(c.saldo(maleta, peca)).isEqualTo(1);
        // O app reenvia (não sabia): já recebido.
        assertThat(resultado(resultados(quem, List.of(venda(id, maleta, c.cliente(null), peca, 1, null))), 0)).isIn("JA_RECEBIDO",
                "CONFLITO");
    }

    @Test
    void dependenciaRecusadaEMaletaFechadaOuDeOutroVendedor() throws Exception {
        Montado m = montar();
        RequestPostProcessor quem = como(m.v());
        UUID clienteRuim = UUID.randomUUID();
        UUID venda = UUID.randomUUID();
        JsonNode r = resultados(quem, List.of(
                op(clienteRuim, "CLIENTE_NOVO", mapa("id", clienteRuim, "nome", "  ")),
                venda(venda, m.maleta(), clienteRuim, m.peca(), 1, Instant.now())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(resultado(r, 1)).isEqualTo("RECUSADO");
        assertThat(r.get(1).get("codigo").asString()).isEqualTo("DEPENDE_DE_ENVIO_RECUSADO");

        // Maleta cancelada (fechada) e maleta de outro vendedor: recusadas, sem dizer nada do outro.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID deOutro = m.c().maleta(outro, Map.of(m.peca(), 1));
        UUID cancelada = m.c().maleta(m.v(), Map.of(m.peca(), 1));
        mvc.perform(post("/api/dona/remessas/" + cancelada + "/cancelar").with(usuarios.comoDona()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"teste\"}")).andExpect(status().isOk());
        JsonNode r2 = resultados(quem, List.of(venda(UUID.randomUUID(), cancelada, m.cliente(), m.peca(), 1, Instant.now()),
                venda(UUID.randomUUID(), deOutro, m.cliente(), m.peca(), 1, Instant.now())));
        assertThat(resultado(r2, 0)).isEqualTo("RECUSADO");
        assertThat(r2.get(0).get("codigo").asString()).isIn("MALETA_NAO_ESTA_COM_VOCE", "MALETA_NAO_CONFERIDA");
        assertThat(resultado(r2, 1)).isEqualTo("RECUSADO");
        assertThat(r2.get(1).get("codigo").asString()).isEqualTo("REMESSA_NAO_ENCONTRADA");
        assertThat(r2.toString()).doesNotContain(outro.vendedorId().toString());
    }

    @Test
    void maletaEmOutroComputadorERotaFechadaParaQuemNaoE() throws Exception {
        Montado m = montar();
        String noSegundo = usuarios.tokenNoAparelho(m.v(), UUID.randomUUID());
        // Segundo computador ainda sem liberação: nem envia.
        enviar(UsuariosDeTeste.comToken(noSegundo), Instant.now(), 0, List.of()).andExpect(status().isForbidden());
        enviar(usuarios.comoDona(), Instant.now(), 0, List.of()).andExpect(status().isForbidden());
        // Lote grande demais: 413, o app manda a metade.
        List<Map<String, Object>> muitas = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            UUID id = UUID.randomUUID();
            muitas.add(op(id, "CLIENTE_NOVO", mapa("id", id, "nome", "Cliente " + i)));
        }
        enviar(como(m.v()), Instant.now(), 0, muitas).andExpect(status().is(413));
    }

    // --- relógio e datas (ADR-0013, item 11) ---

    @Test
    void relogioAdiantadoOuAtrasadoTemAHoraCorrigidaEMarcada() throws Exception {
        Montado m = montar();
        // A maleta foi enviada ontem: as vendas de "há uma hora" são possíveis.
        jdbc.update("UPDATE corona.remessas SET enviada_em = now() - interval '1 day' WHERE id = ?", m.maleta());
        RequestPostProcessor quem = como(m.v());
        Instant agora = Instant.now();
        // Computador 3 dias adiantado: vendeu "há uma hora" no relógio dele.
        UUID adiantada = UUID.randomUUID();
        Instant horaLocal = agora.plus(Duration.ofDays(3));
        assertThat(resultado(resultados(quem, horaLocal, 1, List.of(venda(adiantada, m.maleta(), m.cliente(), m.peca(), 1,
                horaLocal.minus(Duration.ofHours(1))))), 0)).isEqualTo("ACEITO");
        // Computador 2 dias atrasado: vendeu "há 10 minutos" no relógio dele.
        UUID atrasada = UUID.randomUUID();
        Instant horaAtrasada = agora.minus(Duration.ofDays(2));
        assertThat(resultado(resultados(quem, horaAtrasada, 0, List.of(venda(atrasada, m.maleta(), m.cliente(), m.peca(), 1,
                horaAtrasada.minus(Duration.ofMinutes(10))))), 0)).isEqualTo("ACEITO");
        assertThat(Duration.between(vendidaEm(adiantada), agora.minus(Duration.ofHours(1))).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(Duration.between(vendidaEm(atrasada), agora.minus(Duration.ofMinutes(10))).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(jdbc.queryForObject("SELECT hora_ajustada FROM corona.vendas WHERE id = ?", Boolean.class, adiantada)).isTrue();
        assertThat(jdbc.queryForObject("SELECT hora_ajustada FROM corona.vendas WHERE id = ?", Boolean.class, atrasada)).isTrue();
        Map<String, Object> log = jdbc.queryForMap("SELECT hora_declarada, diferenca_do_relogio_ms FROM corona.sync_log WHERE id = ?",
                adiantada);
        assertThat(((Number) log.get("diferenca_do_relogio_ms")).longValue()).isBetween(Duration.ofDays(3).minusMinutes(1).toMillis(),
                Duration.ofDays(3).plusMinutes(1).toMillis());
        // Relógio certo: hora como veio, sem marca.
        UUID certa = UUID.randomUUID();
        resultados(quem, Instant.now(), 0, List.of(venda(certa, m.maleta(), m.cliente(), m.peca(), 1, Instant.now().minusSeconds(30))));
        assertThat(jdbc.queryForObject("SELECT hora_ajustada FROM corona.vendas WHERE id = ?", Boolean.class, certa)).isFalse();
    }

    @Test
    void horaImpossivelFicaNoLimiteEOEstornoNoMesmoDiaUsaOMesmoRelogio() throws Exception {
        // Servidor às 23h30 de Brasília (02h30 UTC do dia seguinte).
        relogio.fixar(Instant.parse("2026-11-13T02:30:00Z"));
        Montado m = montar();
        RequestPostProcessor quem = como(m.v());
        Instant enviada = jdbc.queryForObject("SELECT enviada_em FROM corona.remessas WHERE id = ?", OffsetDateTime.class, m.maleta())
                .toInstant();
        // Venda "antes do envio da maleta" (impossível): fica no envio da maleta.
        UUID antiga = UUID.randomUUID();
        resultados(quem, relogio.instant(), 0, List.of(venda(antiga, m.maleta(), m.cliente(), m.peca(), 1,
                relogio.instant().minus(Duration.ofDays(30)))));
        assertThat(vendidaEm(antiga)).isEqualTo(enviada.truncatedTo(java.time.temporal.ChronoUnit.MICROS));

        // Venda às 23h20 e estorno às 23h25 (Brasília), com o computador 2 dias atrasado: mesmo dia, o vendedor estorna.
        Instant horaLocal = relogio.instant().minus(Duration.ofDays(2));
        UUID venda = UUID.randomUUID();
        UUID estorno = UUID.randomUUID();
        JsonNode r = resultados(quem, horaLocal, 0, List.of(
                venda(venda, m.maleta(), m.cliente(), m.peca(), 1, horaLocal.minus(Duration.ofMinutes(10))),
                op(estorno, "ESTORNO_VENDA", mapa("id", estorno, "vendaId", venda, "motivo", "desistiu",
                        "estornadoEm", horaLocal.minus(Duration.ofMinutes(5)).toString()))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        // Estorno declarado para depois da meia-noite de Brasília (o servidor também já passou): só a dona.
        UUID outra = UUID.randomUUID();
        resultados(quem, relogio.instant(), 0, List.of(venda(outra, m.maleta(), m.cliente(), m.peca(), 1,
                relogio.instant().minus(Duration.ofMinutes(5)))));
        relogio.fixar(Instant.parse("2026-11-13T03:10:00Z")); // 00h10 em Brasília
        UUID tarde = UUID.randomUUID();
        JsonNode r2 = resultados(como(m.v()), relogio.instant(), 0, List.of(op(tarde, "ESTORNO_VENDA", mapa("id", tarde, "vendaId", outra,
                "motivo", "desistiu", "estornadoEm", relogio.instant().toString()))));
        assertThat(resultado(r2, 0)).isEqualTo("RECUSADO");
        assertThat(r2.get(0).get("codigo").asString()).isEqualTo("ESTORNO_SO_DA_DONA");
    }

    private Instant vendidaEm(UUID venda) {
        return jdbc.queryForObject("SELECT vendida_em FROM corona.vendas WHERE id = ?", OffsetDateTime.class, venda).toInstant();
    }

    // --- concorrência ---

    @Test
    void doisEnviosDoMesmoComputadorAoMesmoTempoNaoDuplicamNada() throws Exception {
        Montado m = montar();
        String token = usuarios.token(m.v());
        List<Map<String, Object>> lote = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            lote.add(venda(UUID.randomUUID(), m.maleta(), m.cliente(), m.peca(), 1, Instant.now()));
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Integer> envio = () -> enviar(UsuariosDeTeste.comToken(token), Instant.now(), 0, lote).andReturn().getResponse()
                .getStatus();
        List<Future<Integer>> f = List.of(executor.submit(envio), executor.submit(envio));
        for (Future<Integer> x : f) {
            assertThat(x.get()).isIn(200, 429);
        }
        executor.shutdown();
        assertThat(m.c().saldo(m.maleta(), m.peca())).isZero();
        for (Map<String, Object> o : lote) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.vendas WHERE id = ?", Long.class, o.get("operacaoId")))
                    .isEqualTo(1);
        }
    }

    // --- clientes (ADR-0013, item 8) ---

    @Test
    void clienteCampoACampoComOValorSubstituidoNaAuditoriaECpfRepetidoEmConflito() throws Exception {
        Montado m = montar();
        RequestPostProcessor quem = como(m.v());
        UUID cliente = m.cliente();
        // A dona muda o telefone enquanto o computador estava sem internet.
        jdbc.update("UPDATE corona.clientes SET telefone = '(19) 4000-0000' WHERE id = ?", cliente);
        UUID alteracao = UUID.randomUUID();
        JsonNode r = resultados(quem, List.of(op(alteracao, "CLIENTE_ALTERADO", mapa("clienteId", cliente, "versaoBase", 1,
                "campos", mapa("telefone", mapa("antes", "(19) 3000-0001", "depois", "(19) 5000-0000"),
                        "observacoes", mapa("antes", "Só paga em dinheiro", "depois", "Paga no Pix"),
                        "vendedorResponsavelId", mapa("antes", null, "depois", UUID.randomUUID().toString()))))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        Map<String, Object> c = jdbc.queryForMap("SELECT telefone, observacoes, vendedor_responsavel_id FROM corona.clientes WHERE id = ?",
                cliente);
        assertThat(c).containsEntry("telefone", "(19) 5000-0000").containsEntry("observacoes", "Paga no Pix")
                .containsEntry("vendedor_responsavel_id", m.v().vendedorId());
        assertThat(jdbc.queryForObject("""
                SELECT antes->>'telefone' FROM corona.auditoria WHERE registro_id = ? AND acao = 'CLIENTE_VALOR_SUBSTITUIDO'""",
                String.class, cliente)).isEqualTo("(19) 4000-0000");

        // Cliente atendido por outro: recusado.
        UUID deOutro = m.c().cliente(usuarios.criarVendedor().vendedorId());
        UUID alt2 = UUID.randomUUID();
        JsonNode r2 = resultados(quem, List.of(op(alt2, "CLIENTE_ALTERADO", mapa("clienteId", deOutro, "versaoBase", 1,
                "campos", mapa("nome", mapa("antes", "x", "depois", "Outro nome"))))));
        assertThat(r2.get(0).get("codigo").asString()).isEqualTo("CLIENTE_DE_OUTRO_VENDEDOR");

        // CPF que já existe (dois cadastros feitos sem internet): conflito para a dona juntar.
        String cpf = CpfsDeTeste.novo();
        UUID primeiro = UUID.randomUUID();
        UUID segundo = UUID.randomUUID();
        JsonNode r3 = resultados(quem, List.of(op(primeiro, "CLIENTE_NOVO", mapa("id", primeiro, "nome", "Ana", "cpf", cpf)),
                op(segundo, "CLIENTE_NOVO", mapa("id", segundo, "nome", "Ana Maria", "cpf", cpf))));
        assertThat(resultado(r3, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r3, 1)).isEqualTo("CONFLITO");
        assertThat(r3.get(1).get("codigo").asString()).isEqualTo("CLIENTE_CPF_JA_CADASTRADO");
    }

    // --- computador trocado (ADR-0013, item 13) ---

    @Test
    void computadorTrocadoEnviaOQueGuardouEDepoisRecebeAOrdemDeApagar() throws Exception {
        Montado m = montar();
        String noAntigo = usuarios.token(m.v());
        UUID novo = UUID.randomUUID();
        usuarios.tokenNoAparelho(m.v(), novo); // aguardando liberação
        mvc.perform(post("/api/dona/aparelhos/" + novo + "/liberar").with(usuarios.comoDona())).andExpect(status().isOk());
        // A maleta passou para o novo; a venda feita no antigo antes da troca é real e entra.
        JsonNode r = json.readTree(enviar(UsuariosDeTeste.comToken(noAntigo), Instant.now(), 1,
                List.of(venda(UUID.randomUUID(), m.maleta(), m.cliente(), m.peca(), 1, Instant.now().minusSeconds(3600))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(resultado(r.get("resultados"), 0)).isEqualTo("ACEITO");
        assertThat(r.get("apagarDados").asBoolean()).isFalse(); // ainda aguarda 1
        JsonNode fim = json.readTree(enviar(UsuariosDeTeste.comToken(noAntigo), Instant.now(), 0,
                List.of(venda(UUID.randomUUID(), m.maleta(), m.cliente(), m.peca(), 1, Instant.now().minusSeconds(1800))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(fim.get("apagarDados").asBoolean()).isTrue();
        assertThat(m.c().saldo(m.maleta(), m.peca())).isEqualTo(1);
        // E ele não recebe dados.
        mvc.perform(get("/api/vendedor/sync/pull").with(UsuariosDeTeste.comToken(noAntigo))).andExpect(status().isForbidden());
    }

    // --- retenção (decisão de 11/11/2026) ---

    @Test
    void depoisDe90DiasFicaSoOResumo() throws Exception {
        Montado m = montar();
        UUID id = UUID.randomUUID();
        resultados(como(m.v()), List.of(op(id, "CLIENTE_NOVO", mapa("id", id, "nome", "Cliente antigo"))));
        jdbc.update("UPDATE corona.sync_log SET recebido_em = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(91))), id);
        assertThat(esvaziamento.esvaziar()).isPositive();
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT dados, dados_esvaziados_em IS NOT NULL AS esvaziado, resumo, resultado, tipo FROM corona.sync_log WHERE id = ?", id);
        assertThat(log.get("dados")).isNull();
        assertThat(log).containsEntry("esvaziado", true).containsEntry("resultado", "ACEITO").containsEntry("tipo", "CLIENTE_NOVO");
        assertThat((String) log.get("resumo")).hasSize(64);
        assertThat(esvaziamento.esvaziar()).isZero();
    }
}
