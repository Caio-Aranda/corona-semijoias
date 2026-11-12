package br.com.coronasemijoias.api.configuracoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * A garantia e os limites de devolução (tarefa 6.2; ADR-0014, itens 4 e 10; plano da Fase 6): padrões, faixa recusada,
 * mudança auditada com o valor antigo, o bloco {@code parametros} do pull só com as chaves combinadas e o 403 do
 * vendedor. Escritos antes do código.
 */
class GarantiaTest extends TesteDeIntegracao {

    private static final String ROTA = "/api/dona/configuracoes/garantia";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Configuracoes configuracoes;

    /** Os testes dividem o banco: cada um devolve a garantia aos padrões. */
    @AfterEach
    void voltarAosPadroes() {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 7 WHERE chave = 'prazo_arrependimento_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 30 WHERE chave = 'prazo_defeito_dias'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 20 WHERE chave = 'percentual_alerta_devolucao'");
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = NULL WHERE tipo = 'DINHEIRO'");
    }

    private static Map<String, Object> pedido(Object arrependimento, Object defeito, Object limite, Object aprovacao,
            Object percentual) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("prazoArrependimentoDias", arrependimento);
        corpo.put("prazoDefeitoDias", defeito);
        corpo.put("limiteReembolsoSemanal", limite);
        corpo.put("valorAprovacaoReembolso", aprovacao);
        corpo.put("percentualAlertaDevolucao", percentual);
        return corpo;
    }

    private static RequestPostProcessor como(UsuariosDeTeste usuarios, UsuarioDeTeste quem) {
        return UsuariosDeTeste.comToken(usuarios.token(quem));
    }

    private ResultActions alterar(RequestPostProcessor quem, Map<String, Object> corpo) throws Exception {
        return mvc.perform(put(ROTA).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    /** A auditoria é imutável e dividida entre os testes: cada um conta só o que ele mesmo gerou. */
    private int auditadas() {
        return jdbc.queryForObject("SELECT count(*) FROM corona.auditoria WHERE acao = 'GARANTIA_ALTERADA'", Integer.class);
    }

    private ResultActions alterar(Map<String, Object> corpo) throws Exception {
        return alterar(usuarios.comoDona(), corpo);
    }

    @Test
    void osPadroesSegurosAteADonaResponder() throws Exception {
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS)).isEqualTo(7);
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_DEFEITO_DIAS)).isEqualTo(30);
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.PERCENTUAL_ALERTA_DEVOLUCAO)).isEqualTo(20);
        assertThat(configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.LIMITE_REEMBOLSO_SEMANAL)).isEmpty();
        assertThat(configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.VALOR_APROVACAO_REEMBOLSO)).isEmpty();

        mvc.perform(get(ROTA).with(usuarios.comoDona())).andExpect(status().isOk())
                .andExpect(jsonPath("$.prazoArrependimentoDias").value(7))
                .andExpect(jsonPath("$.prazoDefeitoDias").value(30))
                .andExpect(jsonPath("$.percentualAlertaDevolucao").value(20))
                .andExpect(jsonPath("$.limiteReembolsoSemanal").doesNotExist())
                .andExpect(jsonPath("$.valorAprovacaoReembolso").doesNotExist());
    }

    @Test
    void aDonaMudaEFicaGravadoComDinheiroEmTextoComDuasCasas() throws Exception {
        alterar(pedido(10, 45, "1500.5", "300.00", 15)).andExpect(status().isOk())
                .andExpect(jsonPath("$.prazoArrependimentoDias").value(10))
                .andExpect(jsonPath("$.prazoDefeitoDias").value(45))
                .andExpect(jsonPath("$.limiteReembolsoSemanal").value("1500.50"))
                .andExpect(jsonPath("$.valorAprovacaoReembolso").value("300.00"))
                .andExpect(jsonPath("$.percentualAlertaDevolucao").value(15));

        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS)).isEqualTo(10);
        assertThat(configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.LIMITE_REEMBOLSO_SEMANAL))
                .contains(new BigDecimal("1500.50"));

        // Tirar o limite (vazio) volta a "sem limite".
        alterar(pedido(10, 45, null, null, 15)).andExpect(status().isOk());
        assertThat(configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.LIMITE_REEMBOLSO_SEMANAL)).isEmpty();
    }

    @Test
    void faixaRecusadaNaApiENoBancoSemMudarNada() throws Exception {
        List<Map<String, Object>> recusados = List.of(
                pedido(0, 30, null, null, 20), pedido(366, 30, null, null, 20), pedido(7, 0, null, null, 20),
                pedido(7, 366, null, null, 20), pedido(7, 30, null, null, 0), pedido(7, 30, null, null, 101),
                pedido(7, 30, "0.00", null, 20), pedido(7, 30, null, "-1.00", 20), pedido(7, 30, "10.001", null, 20),
                pedido(null, 30, null, null, 20), pedido(7, 30, 100, null, 20));
        int auditadasAntes = auditadas();
        RequestPostProcessor dona = usuarios.comoDona();
        for (Map<String, Object> corpo : recusados) {
            alterar(dona, corpo).andExpect(status().isBadRequest());
        }
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS)).isEqualTo(7);
        assertThat(auditadas()).isEqualTo(auditadasAntes);

        // O banco é a segunda trava: a aplicação, mesmo pulando a API, não passa da faixa.
        try (Connection conexao = conectarComoAplicacao()) {
            for (String comando : List.of(
                    "UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'prazo_arrependimento_dias'",
                    "UPDATE corona.configuracoes SET valor_inteiro = 366 WHERE chave = 'prazo_defeito_dias'",
                    "UPDATE corona.configuracoes SET valor_inteiro = 101 WHERE chave = 'percentual_alerta_devolucao'",
                    "UPDATE corona.configuracoes SET valor_dinheiro = 0 WHERE chave = 'limite_reembolso_semanal'",
                    "UPDATE corona.configuracoes SET valor_dinheiro = -5 WHERE chave = 'valor_aprovacao_reembolso'",
                    "UPDATE corona.configuracoes SET valor_dinheiro = 10 WHERE chave = 'prazo_defeito_dias'",
                    "UPDATE corona.configuracoes SET valor_inteiro = 5 WHERE chave = 'limite_reembolso_semanal'")) {
                assertThatThrownBy(() -> conexao.createStatement().execute(comando)).as(comando)
                        .isInstanceOf(SQLException.class);
                conexao.rollback();
            }
        }
    }

    @Test
    void mudancaAuditadaComOValorAntigoESoDoQueMudou() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        RequestPostProcessor quem = como(usuarios, dona);
        alterar(quem, pedido(10, 30, "200.00", null, 20)).andExpect(status().isOk());

        List<Map<String, Object>> registros = jdbc.queryForList(
                "SELECT antes::text AS antes, depois::text AS depois, ator_usuario_id, tabela FROM corona.auditoria "
                        + "WHERE acao = 'GARANTIA_ALTERADA' AND ator_usuario_id = ? ORDER BY ocorrida_em, id", dona.id());
        assertThat(registros).hasSize(2); // prazo de arrependimento e limite semanal; o resto não mudou
        assertThat(registros).allSatisfy(r -> {
            assertThat(r.get("tabela")).isEqualTo("configuracoes");
            assertThat(r.get("ator_usuario_id")).isEqualTo(dona.id());
        });
        Map<String, Object> deArrependimento = registros.stream()
                .filter(r -> r.get("depois").toString().contains("prazo_arrependimento_dias")).findFirst().orElseThrow();
        assertThat(json.readTree(deArrependimento.get("antes").toString()).get("valor").asInt()).isEqualTo(7);
        assertThat(json.readTree(deArrependimento.get("depois").toString()).get("valor").asInt()).isEqualTo(10);
        Map<String, Object> deLimite = registros.stream()
                .filter(r -> r.get("depois").toString().contains("limite_reembolso_semanal")).findFirst().orElseThrow();
        assertThat(json.readTree(deLimite.get("antes").toString()).get("valor").isNull()).isTrue();
        assertThat(json.readTree(deLimite.get("depois").toString()).get("valor").asString()).isEqualTo("200.00");

        // Repetir o mesmo pedido não muda nada e não gera registro novo.
        alterar(quem, pedido(10, 30, "200.00", null, 20)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM corona.auditoria WHERE acao = 'GARANTIA_ALTERADA' AND ator_usuario_id = ?",
                Integer.class, dona.id())).isEqualTo(2);
    }

    @Test
    void oVendedorEstaForaDaRotaDaDonaESemLoginTambem() throws Exception {
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        mvc.perform(get(ROTA).with(vendedor)).andExpect(status().isForbidden());
        alterar(vendedor, pedido(1, 1, null, null, 1)).andExpect(status().isForbidden());
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS)).isEqualTo(7);
    }

    @Test
    void oPullTrazSoOsDoisPrazosAMaisSemLimitesNemValoresDeAprovacao() throws Exception {
        alterar(pedido(9, 40, "100.00", "50.00", 25)).andExpect(status().isOk());
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        JsonNode r = json.readTree(mvc.perform(get("/api/vendedor/sync/pull").with(como(usuarios, vendedor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode parametros = r.get("parametros");
        assertThat(parametros.get("prazoArrependimentoDias").asInt()).isEqualTo(9);
        assertThat(parametros.get("prazoDefeitoDias").asInt()).isEqualTo(40);
        List<String> nomes = parametros.propertyNames().stream().sorted().toList();
        assertThat(nomes).containsExactly("limiteDescontoPercentual", "prazoArrependimentoDias", "prazoDefeitoDias",
                "situacaoDoAparelho", "tempoMaximoOfflineDias");
    }
}
