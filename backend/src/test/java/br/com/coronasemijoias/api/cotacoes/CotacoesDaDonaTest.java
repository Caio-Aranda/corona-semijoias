package br.com.coronasemijoias.api.cotacoes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.RelogioDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rotas da dona para a cotação do dia, a conversão e os avisos (tarefa 1.7). Taxas do arquivo real de
 * 06/11/2026 (sexta): Dólar de venda 5,5013 e Guarani de venda 0,000895. Cada teste roda numa transação
 * desfeita no fim (a tabela de cotações é imutável) e com o relógio parado.
 */
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CotacoesDaDonaTest extends TesteDeIntegracao {

    private static final LocalDate SEXTA = LocalDate.of(2026, 11, 6);
    private static final String SEXTA_TARDE = "2026-11-06T20:00:00Z";
    private static final String SEGUNDA_MANHA = "2026-11-09T12:00:00Z";
    private static final String COMPLEMENTO =
            "O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    RepositorioDeCotacoes repositorio;

    @Autowired
    CalendarioBancario calendario;

    @Autowired
    RelogioDeTeste relogio;

    /** Cria a dona e o vendedor de teste fora da transação dos testes (que é desfeita e levaria os usuários junto). */
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

    private void gravar(LocalDate dia) {
        TaxasDoDia taxas = LeitorDoArquivoDeFechamento.ler(
                SEXTA, LeitorDoArquivoDeFechamentoTest.arquivo(TesteDeCotacoes.ARQUIVO_REAL));
        repositorio.gravar(dia, taxas, Instant.parse("2026-01-01T00:00:00Z"), "https://exemplo.invalido/arquivo.csv");
    }

    /** Todo dia útil dos 12 dias até o fim (inclusive) com cotação: nenhum aviso. */
    private void gravarUteisAte(LocalDate fim) {
        for (LocalDate dia = fim.minusDays(12); !dia.isAfter(fim); dia = dia.plusDays(1)) {
            if (calendario.ehDiaUtil(dia)) {
                gravar(dia);
            }
        }
    }

    private ResultActions converter(Object corpo) throws Exception {
        return mvc.perform(post("/api/dona/cotacoes/converter").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)));
    }

    private ResultActions hoje() throws Exception {
        return mvc.perform(get("/api/dona/cotacoes/hoje").with(usuarios.comoDona()));
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcDoAviso;

    private ResultActions avisos() throws Exception {
        // O aviso de estoque baixo (tarefa 2.4) também sai desta rota; produtos de outros testes não podem entrar aqui.
        jdbcDoAviso.update("UPDATE corona.produtos SET estoque_minimo = 0 WHERE estoque_minimo <> 0");
        return mvc.perform(get("/api/dona/avisos").with(usuarios.comoDona()));
    }

    // --- permissão ---

    @Test
    void soAdminEntra_vendedor403_semToken401() throws Exception {
        for (String caminho : new String[] {"/api/dona/cotacoes/hoje", "/api/dona/avisos"}) {
            mvc.perform(get(caminho).with(usuarios.comoVendedor())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
            mvc.perform(get(caminho)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/dona/cotacoes/converter").with(usuarios.comoVendedor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorEmReais\":\"1.00\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/dona/cotacoes/converter").contentType(MediaType.APPLICATION_JSON)
                .content("{\"valorEmReais\":\"1.00\"}")).andExpect(status().isUnauthorized());
    }

    // --- cotação de hoje ---

    @Test
    void semNenhumaCotacaoResponde200ComIndisponivel() throws Exception {
        agora(SEGUNDA_MANHA);
        hoje().andExpect(status().isOk())
                .andExpect(jsonPath("$.disponivel").value(false))
                .andExpect(jsonPath("$.dataDeHoje").value("2026-11-09"))
                .andExpect(jsonPath("$.cotacao").doesNotExist())
                .andExpect(jsonPath("$.mensagem").value(
                        "Ainda não temos a cotação do Dólar e do Guarani. Os valores aparecem só em reais por enquanto."));
    }

    @Test
    void cotacaoDeHojeVemComAsTaxasEmTextoDeOitoCasas() throws Exception {
        gravar(SEXTA);
        agora(SEXTA_TARDE);
        hoje().andExpect(status().isOk())
                .andExpect(jsonPath("$.disponivel").value(true))
                .andExpect(jsonPath("$.cotacao.dataCotacao").value("2026-11-06"))
                .andExpect(jsonPath("$.cotacao.dolar").value("5.50130000"))
                .andExpect(jsonPath("$.cotacao.guarani").value("0.00089500"))
                .andExpect(jsonPath("$.cotacao.deDiaAnterior").value(false))
                .andExpect(jsonPath("$.cotacao.fonte").value("Banco Central do Brasil"))
                .andExpect(jsonPath("$.cotacao.linha").value("Cotação do Banco Central de 06/11/2026"));
    }

    @Test
    void segundaAntesDaBuscaUsaACotacaoDeSextaMarcadaComoDeDiaAnterior() throws Exception {
        gravar(SEXTA);
        agora(SEGUNDA_MANHA);
        hoje().andExpect(jsonPath("$.disponivel").value(true))
                .andExpect(jsonPath("$.cotacao.dataCotacao").value("2026-11-06"))
                .andExpect(jsonPath("$.cotacao.deDiaAnterior").value(true));
    }

    @Test
    void oDiaDeHojeEhoDeBrasilia() throws Exception {
        gravar(SEXTA);
        // 02:00 UTC de sábado = 23:00 de sexta em Brasília.
        agora("2026-11-07T02:00:00Z");
        hoje().andExpect(jsonPath("$.dataDeHoje").value("2026-11-06"))
                .andExpect(jsonPath("$.cotacao.deDiaAnterior").value(false));
    }

    // --- conversão ---

    @Test
    void converteReaisParaDolarComDuasCasasEGuaraniSemCasas() throws Exception {
        gravar(SEXTA);
        agora(SEXTA_TARDE);
        converter(Map.of("valorEmReais", "100.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponivel").value(true))
                .andExpect(jsonPath("$.valorEmReais").value("100.00"))
                .andExpect(jsonPath("$.dolar").value("18.18"))
                .andExpect(jsonPath("$.guarani").value("111732"))
                .andExpect(jsonPath("$.dataDaCotacao").value("2026-11-06"));
        converter(Map.of("valorEmReais", "10.5")).andExpect(jsonPath("$.valorEmReais").value("10.50"))
                .andExpect(jsonPath("$.dolar").value("1.91")).andExpect(jsonPath("$.guarani").value("11732"));
        converter(Map.of("valorEmReais", "0")).andExpect(jsonPath("$.dolar").value("0.00"))
                .andExpect(jsonPath("$.guarani").value("0"));
    }

    @Test
    void valorNegativoDeEstornoConverteComSinal() throws Exception {
        gravar(SEXTA);
        agora(SEXTA_TARDE);
        converter(Map.of("valorEmReais", "-100.00")).andExpect(jsonPath("$.dolar").value("-18.18"))
                .andExpect(jsonPath("$.guarani").value("-111732"));
    }

    @Test
    void valorMaximoDoBancoConverteSemEstourar() throws Exception {
        gravar(SEXTA);
        agora(SEXTA_TARDE);
        converter(Map.of("valorEmReais", "999999999999.99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dolar").value("181775216766.94"));
    }

    @Test
    void conversaoPelaDataPedidaUsaACotacaoDaquelaData() throws Exception {
        gravar(SEXTA);
        agora("2026-11-10T12:00:00Z");
        // Sábado cai na cotação de sexta (último dia útil).
        converter(Map.of("valorEmReais", "100.00", "data", "2026-11-07")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataDaCotacao").value("2026-11-06"))
                .andExpect(jsonPath("$.dolar").value("18.18"));
        // Antes da primeira cotação: indisponível, não erro.
        converter(Map.of("valorEmReais", "100.00", "data", "2026-11-05")).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponivel").value(false))
                .andExpect(jsonPath("$.dolar").doesNotExist());
    }

    @Test
    void semCotacaoAConversaoResponde200ComIndisponivel() throws Exception {
        agora(SEGUNDA_MANHA);
        converter(Map.of("valorEmReais", "100.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponivel").value(false))
                .andExpect(jsonPath("$.valorEmReais").value("100.00"))
                .andExpect(jsonPath("$.dolar").doesNotExist())
                .andExpect(jsonPath("$.guarani").doesNotExist());
    }

    @Test
    void valorMalEscritoRecebe400EmPortugues() throws Exception {
        gravar(SEXTA);
        agora(SEXTA_TARDE);
        for (String ruim : new String[] {"10,50", "1.234", "abc", "", "1e3", "1.", "1000000000000.00"}) {
            converter(Map.of("valorEmReais", ruim)).andExpect(status().isBadRequest());
        }
        // Número JSON no lugar do texto também é recusado (nunca float).
        mvc.perform(post("/api/dona/cotacoes/converter").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorEmReais\":10.5}"))
                .andExpect(status().isBadRequest());
        // Sem o valor.
        converter(Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("valorEmReais"));
    }

    // --- avisos ---

    @Test
    void semAvisoQuandoTudoEstaEmDia() throws Exception {
        agora(SEXTA_TARDE);
        gravarUteisAte(SEXTA);
        avisos().andExpect(status().isOk()).andExpect(jsonPath("$.avisos.length()").value(0));
    }

    @Test
    void cotacaoDeHojeQueNaoChegouGeraOAvisoDeHojeESomeQuandoChega() throws Exception {
        gravarUteisAte(LocalDate.of(2026, 11, 5));
        // Sexta, 21:30 UTC: passou da última tentativa e a de hoje não chegou.
        agora("2026-11-06T21:30:00Z");
        avisos().andExpect(status().isOk())
                .andExpect(jsonPath("$.avisos.length()").value(1))
                .andExpect(jsonPath("$.avisos[0].tipo").value("COTACAO_NAO_CHEGOU"))
                .andExpect(jsonPath("$.avisos[0].titulo").value("A cotação de hoje ainda não chegou."))
                .andExpect(jsonPath("$.avisos[0].texto").value(
                        "Os valores em Dólar e Guarani estão usando a cotação de 05/11/2026. " + COMPLEMENTO))
                .andExpect(jsonPath("$.avisos[0].dias[0]").value("2026-11-06"));

        gravar(SEXTA);
        avisos().andExpect(jsonPath("$.avisos.length()").value(0));
    }

    @Test
    void diaAnteriorSemCotacaoGeraAvisoComAData() throws Exception {
        gravarUteisAte(LocalDate.of(2026, 11, 5));
        agora(SEGUNDA_MANHA); // sexta ficou sem cotação
        avisos().andExpect(jsonPath("$.avisos.length()").value(1))
                .andExpect(jsonPath("$.avisos[0].titulo").value("A cotação de 06/11/2026 não chegou."))
                .andExpect(jsonPath("$.avisos[0].texto").value(
                        "Os valores em Dólar e Guarani desse dia estão usando a cotação de 05/11/2026. "
                                + COMPLEMENTO));
    }

    @Test
    void fimDeSemanaNaoGeraAviso() throws Exception {
        gravarUteisAte(SEXTA);
        agora("2026-11-08T15:00:00Z"); // domingo
        avisos().andExpect(jsonPath("$.avisos.length()").value(0));
    }

    @Test
    void variosDiasSemCotacaoViramUmAvisoSoComTodosOsDias() throws Exception {
        gravarUteisAte(LocalDate.of(2026, 11, 4));
        agora(SEGUNDA_MANHA); // quinta 05 e sexta 06 sem cotação
        avisos().andExpect(jsonPath("$.avisos.length()").value(1))
                .andExpect(jsonPath("$.avisos[0].dias.length()").value(2))
                .andExpect(jsonPath("$.avisos[0].titulo").value("As cotações de 05/11/2026 e 06/11/2026 não chegaram."))
                .andExpect(jsonPath("$.avisos[0].texto").value(
                        "Os valores em Dólar e Guarani desses dias estão usando a cotação de 04/11/2026. "
                                + COMPLEMENTO));
    }

    @Test
    void semNenhumaCotacaoOAvisoNaoCitaCotacaoEmUso() throws Exception {
        agora(SEGUNDA_MANHA);
        avisos().andExpect(jsonPath("$.avisos.length()").value(1))
                .andExpect(jsonPath("$.avisos[0].tipo").value("COTACAO_NAO_CHEGOU"))
                .andExpect(jsonPath("$.avisos[0].texto").value(
                        "Os valores aparecem só em reais por enquanto. " + COMPLEMENTO));
    }
}
