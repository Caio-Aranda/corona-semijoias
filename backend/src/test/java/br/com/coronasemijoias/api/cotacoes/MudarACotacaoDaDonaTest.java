package br.com.coronasemijoias.api.cotacoes;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * A dona muda a cotação do Dólar e do Guarani durante o dia (ADR-0006, item 15): rotas de gravar, de prévia e do histórico
 * do dia. Relógio parado; cada teste roda numa transação desfeita no fim (a tabela é imutável). O Banco Central do dia é o
 * arquivo real de 06/11/2026: Dólar 5,5013 e Guarani 0,000895 (1 real = Gs. 1.117,32).
 */
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MudarACotacaoDaDonaTest extends TesteDeIntegracao {

    private static final LocalDate SEXTA = LocalDate.of(2026, 11, 6);
    private static final String SEXTA_10H = "2026-11-06T13:00:00Z";

    @Autowired
    MockMvc mvc;
    @Autowired
    UsuariosDeTeste usuarios;
    @Autowired
    JsonMapper json;
    @Autowired
    RepositorioDeCotacoes repositorio;
    @Autowired
    RelogioDeTeste relogio;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    void prepararUsuarios() {
        usuarios.dona();
        usuarios.vendedor();
    }

    @AfterEach
    void soltarRelogio() {
        relogio.reiniciar();
    }

    private void agora(String instanteUtc) {
        relogio.fixar(Instant.parse(instanteUtc));
    }

    private void bancoCentralDeSexta() {
        TaxasDoDia taxas = LeitorDoArquivoDeFechamento.ler(
                SEXTA, LeitorDoArquivoDeFechamentoTest.arquivo(TesteDeCotacoes.ARQUIVO_REAL));
        repositorio.gravar(SEXTA, taxas, Instant.parse("2026-01-01T00:00:00Z"), "teste");
    }

    private static Map<String, Object> pedido(String usd, String gs) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("usd", usd);
        corpo.put("guaranisPorReal", gs);
        return corpo;
    }

    private ResultActions enviar(String caminho, Object corpo, boolean comoDona) throws Exception {
        var req = post("/api/dona/cotacoes" + caminho)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
        return mvc.perform(comoDona ? req.with(usuarios.comoDona()) : req.with(usuarios.comoVendedor()));
    }

    // --- permissão ---

    @Test
    void soAAdminGrava_vendedor403_semToken401() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("", pedido("5.80", "1350"), false).andExpect(status().isForbidden());
        enviar("/previa", pedido("5.80", "1350"), false).andExpect(status().isForbidden());
        mvc.perform(get("/api/dona/cotacoes").param("data", "2026-11-06").with(usuarios.comoVendedor()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/dona/cotacoes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usd\":\"5.80\",\"guaranisPorReal\":\"1350\"}")).andExpect(status().isUnauthorized());
        assertThat(repositorio.doDia(SEXTA)).extracting(Cotacao::origem).containsOnly("BANCO_CENTRAL");
    }

    // --- gravar ---

    @Test
    void gravaUmaLinhaNovaDaDonaQueValeAPartirDeAgoraEAhistoriaMostra() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);

        enviar("", pedido("5.80", "1117"), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cotacao.dolar").value("5.80000000"))
                .andExpect(jsonPath("$.cotacao.guarani").value("0.00089526"))
                .andExpect(jsonPath("$.cotacao.guaranisPorReal").value("1117.00000000"))
                .andExpect(jsonPath("$.cotacao.origem").value("DONA"))
                .andExpect(jsonPath("$.cotacao.linha").value("Cotação da loja de 06/11/2026 às 10:00"))
                .andExpect(jsonPath("$.avisoDeDiferenca").value(false));

        mvc.perform(get("/api/dona/cotacoes/hoje").with(usuarios.comoDona()))
                .andExpect(jsonPath("$.cotacao.origem").value("DONA"))
                .andExpect(jsonPath("$.cotacao.dolar").value("5.80000000"));
        mvc.perform(get("/api/dona/cotacoes").param("data", "2026-11-06").with(usuarios.comoDona()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cotacoes", hasSize(2)))
                .andExpect(jsonPath("$.cotacoes[0].origem").value("BANCO_CENTRAL"))
                .andExpect(jsonPath("$.cotacoes[0].valeAgora").value(false))
                .andExpect(jsonPath("$.cotacoes[1].origem").value("DONA"))
                .andExpect(jsonPath("$.cotacoes[1].valeAgora").value(true))
                .andExpect(jsonPath("$.cotacoes[1].lancadaPor").exists());
    }

    @Test
    void oBancoCentralDoDiaContinuaGravandoDepoisDaDonaEOAvisoDeBuscaFalhadaNaoSome() throws Exception {
        agora(SEXTA_10H);
        enviar("", pedido("5.80", "1350"), true).andExpect(status().isCreated());
        // A dela não conta como "dia com cotação do Banco Central": a busca ainda grava o dia.
        assertThat(repositorio.diasComCotacao(SEXTA, SEXTA)).isEmpty();
        bancoCentralDeSexta();
        assertThat(repositorio.diasComCotacao(SEXTA, SEXTA)).containsExactly(SEXTA);
        // E o dia do Banco Central não passa por cima da dona (vale desde 00:00, a dela desde 10:00).
        mvc.perform(get("/api/dona/cotacoes/hoje").with(usuarios.comoDona()))
                .andExpect(jsonPath("$.cotacao.origem").value("DONA"));
    }

    @Test
    void reenvioComOMesmoIdENasMesmasTaxasDevolve200ComAMesmaLinhaEComOutrasTaxasDa409() throws Exception {
        agora(SEXTA_10H);
        UUID id = UUID.randomUUID();
        Map<String, Object> corpo = pedido("5.80", "1350");
        corpo.put("id", id.toString());

        String primeira = enviar("", corpo, true).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        agora("2026-11-06T13:05:00Z");
        String segunda = enviar("", corpo, true).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(segunda).get("cotacao").get("id").asString())
                .isEqualTo(json.readTree(primeira).get("cotacao").get("id").asString());
        assertThat(repositorio.doDia(SEXTA)).hasSize(1);

        corpo.put("usd", "5.90");
        enviar("", corpo, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("COTACAO_JA_GRAVADA_DIFERENTE"));
        assertThat(repositorio.doDia(SEXTA)).hasSize(1);
    }

    @Test
    void auditaComAsDuasTaxasAComparacaoEOAviso() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("", pedido("6.40", "1350"), true).andExpect(status().isCreated());

        Map<String, Object> linha = jdbc.queryForMap(
                "SELECT acao, depois::text AS depois FROM corona.auditoria WHERE acao = 'COTACAO_LANCADA_PELA_DONA'");
        assertThat(linha.get("depois").toString()).contains("6.40", "1350", "banco_central_dolar", "aviso_de_diferenca");
    }

    // --- só uma moeda (pedido da dona, 24/11/2026: ela pode mudar só o Dólar ou só o Guarani) ---

    @Test
    void soODolar_oGuaraniFicaExatamenteOQueValia() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("", pedido("5.80", null), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cotacao.origem").value("DONA"))
                .andExpect(jsonPath("$.cotacao.dolar").value("5.80000000"))
                // A taxa do Guarani do Banco Central, sem arredondar para "1 real = Gs. 1.117".
                .andExpect(jsonPath("$.cotacao.guarani").value("0.00089500"))
                .andExpect(jsonPath("$.cotacao.guaranisPorReal").value("1117.31843575"));
    }

    @Test
    void soOGuarani_oDolarFicaExatamenteOQueValia() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("", pedido(null, "1350"), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cotacao.dolar").value("5.50130000"))
                .andExpect(jsonPath("$.cotacao.guaranisPorReal").value("1350.00000000"));
    }

    @Test
    void umaMoedaDeCadaVez_aOutraSegueAUltimaDaDona() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("", pedido("5.80", "1350"), true).andExpect(status().isCreated());
        agora("2026-11-06T14:00:00Z");
        enviar("", pedido(null, "1400"), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cotacao.dolar").value("5.80000000"))
                .andExpect(jsonPath("$.cotacao.guaranisPorReal").value("1400.00000000"));
        agora("2026-11-06T15:00:00Z");
        enviar("", pedido("5.90", ""), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cotacao.dolar").value("5.90000000"))
                .andExpect(jsonPath("$.cotacao.guaranisPorReal").value("1400.00000000"));
        assertThat(repositorio.doDia(SEXTA)).hasSize(4);
    }

    @Test
    void nenhumaDasDuas_ouSemCotacaoParaCopiar_400() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("", pedido(null, null), true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
        enviar("/previa", pedido("", " "), true).andExpect(status().isBadRequest());
        assertThat(repositorio.doDia(SEXTA)).hasSize(1);
    }

    @Test
    void previaComSoUmaMoeda() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("/previa", pedido("5.80", null), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.avisoDeDiferenca").value(false));
        enviar("/previa", pedido(null, "2000"), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.avisoDeDiferenca").value(true))
                .andExpect(jsonPath("$.moedaDoAviso").value("GUARANI"));
    }

    // --- validação ---

    @Test
    void recusaValoresForaDosLimitesComMensagemSimples() throws Exception {
        agora(SEXTA_10H);
        for (String[] ruim : new String[][] {
            {"0", "1350"}, {"0.00", "1350"}, {"1000", "1350"}, {"5.80001", "1350"}, {"-5", "1350"},
            {"5.80", "0"}, {"5.80", "0.5"}, {"5.80", "100000"}, {"5.80", "1350.123"}}) {
            enviar("", pedido(ruim[0], ruim[1]), true).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
        }
        enviar("", Map.of("usd", "5.80"), true).andExpect(status().isBadRequest());
        enviar("", pedido("5.8000", "1350.25"), true).andExpect(status().isCreated());
        assertThat(repositorio.doDia(SEXTA)).hasSize(1);
    }

    // --- prévia e comparação com o Banco Central (15%) ---

    @Test
    void previaCompara_5_45PorCentoNaoAvisa_16_3PorCentoAvisaEGravaMesmoAssim() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);

        enviar("/previa", pedido("5.80", "1117"), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.bancoCentral.dolar").value("5.50130000"))
                .andExpect(jsonPath("$.bancoCentral.dataCotacao").value("2026-11-06"))
                .andExpect(jsonPath("$.diferencaDolarPorCento").value("5.43"))
                .andExpect(jsonPath("$.avisoDeDiferenca").value(false))
                .andExpect(jsonPath("$.aviso").doesNotExist());

        enviar("/previa", pedido("6.40", "1117"), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.diferencaDolarPorCento").value("16.34"))
                .andExpect(jsonPath("$.avisoDeDiferenca").value(true))
                .andExpect(jsonPath("$.moedaDoAviso").value("DOLAR"))
                .andExpect(jsonPath("$.aviso").value(
                        "Este valor está bem diferente do Banco Central (R$ 5,50). Confira antes de gravar."));
        assertThat(repositorio.doDia(SEXTA)).hasSize(1); // a prévia nunca grava

        enviar("", pedido("6.40", "1117"), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.avisoDeDiferenca").value(true));
        assertThat(repositorio.doDia(SEXTA)).hasSize(2);
    }

    @Test
    void oGuaraniTambemDisparaOAviso() throws Exception {
        bancoCentralDeSexta();
        agora(SEXTA_10H);
        enviar("/previa", pedido("5.50", "1400"), true).andExpect(jsonPath("$.avisoDeDiferenca").value(true))
                .andExpect(jsonPath("$.moedaDoAviso").value("GUARANI"))
                .andExpect(jsonPath("$.aviso").value(
                        "Este valor está bem diferente do Banco Central (Gs. 1.117). Confira antes de gravar."));
    }

    @Test
    void semNenhumDoBancoCentralNaoHaComparacaoNemAviso() throws Exception {
        agora(SEXTA_10H);
        enviar("/previa", pedido("9.99", "100"), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.bancoCentral").doesNotExist())
                .andExpect(jsonPath("$.avisoDeDiferenca").value(false));
    }

    @Test
    void historicoDeOutroDiaMostraSoAsDaqueleDia() throws Exception {
        agora(SEXTA_10H);
        enviar("", pedido("5.80", "1350"), true).andExpect(status().isCreated());
        mvc.perform(get("/api/dona/cotacoes").param("data", "2026-11-05").with(usuarios.comoDona()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cotacoes", hasSize(0)));
    }

    @Test
    void umaTaxaDoBancoCentralQueDaZeroGuaranisPorRealNaoQuebraAGravacaoNemAvisa() throws Exception {
        // Taxa absurda (1 Gs. = R$ 7.000): 1 ÷ 7000 dá 0,00 guarani por real. Sem referência, sem comparação do Guarani.
        TaxasDoDia taxas = new TaxasDoDia(
                new java.math.BigDecimal("5.00000000"), new java.math.BigDecimal("5.00000000"), java.math.BigDecimal.ONE.setScale(8),
                java.math.BigDecimal.ONE.setScale(8), new java.math.BigDecimal("7000.00000000"), new java.math.BigDecimal("7000.00000000"),
                java.math.BigDecimal.ONE.setScale(8), java.math.BigDecimal.ONE.setScale(8));
        repositorio.gravar(SEXTA, taxas, Instant.parse("2026-01-01T00:00:00Z"), "teste");
        agora(SEXTA_10H);
        enviar("/previa", pedido("5.00", "1350"), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.avisoDeDiferenca").value(false));
        enviar("", pedido("5.00", "1350"), true).andExpect(status().isCreated());
    }
}
