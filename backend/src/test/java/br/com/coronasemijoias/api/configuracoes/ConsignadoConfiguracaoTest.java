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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A meta, os percentuais e os dias do retorno do consignado (plano acerto-45-dias, D5 e D6; especificação §23.13):
 * padrões, limites recusados, auditoria com o valor antigo, só a dona muda e os quatro campos nos {@code parametros} do pull.
 */
class ConsignadoConfiguracaoTest extends TesteDeIntegracao {

    private static final String ROTA = "/api/dona/configuracoes/consignado";

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

    @Autowired
    ServicoDoConsignado servicoDoConsignado;

    /** Volta pelo serviço (com auditoria): a conciliação K9 lê o histórico da auditoria, que precisa continuar coerente. */
    @AfterEach
    void voltarAosPadroes() {
        servicoDoConsignado.alterar(new ServicoDoConsignado.Consignado(700_000, 30, 40, 45), usuarios.dona().id());
    }

    private static Map<String, Object> pedido(Object meta, Object abaixo, Object naMeta, Object dias) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("metaGuarani", meta);
        corpo.put("percentualAbaixoDaMeta", abaixo);
        corpo.put("percentualNaMeta", naMeta);
        corpo.put("diasDoRetorno", dias);
        return corpo;
    }

    private ResultActions alterar(RequestPostProcessor quem, Map<String, Object> corpo) throws Exception {
        return mvc.perform(put(ROTA).with(quem).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)));
    }

    private ResultActions alterar(Map<String, Object> corpo) throws Exception {
        return alterar(usuarios.comoDona(), corpo);
    }

    private static RequestPostProcessor como(UsuariosDeTeste usuarios, UsuarioDeTeste quem) {
        return UsuariosDeTeste.comToken(usuarios.token(quem));
    }

    private void confereIntacta() throws Exception {
        mvc.perform(get(ROTA).with(usuarios.comoDona())).andExpect(status().isOk()).andExpect(jsonPath("$.metaGuarani").value(700000))
                .andExpect(jsonPath("$.percentualAbaixoDaMeta").value(30)).andExpect(jsonPath("$.percentualNaMeta").value(40))
                .andExpect(jsonPath("$.diasDoRetorno").value(45));
    }

    @Test
    void osPadroesSaoOsDoPlano() throws Exception {
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_META_GUARANI)).isEqualTo(700000);
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_COMISSAO_ABAIXO_DA_META)).isEqualTo(30);
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_COMISSAO_NA_META)).isEqualTo(40);
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_DIAS_DO_RETORNO)).isEqualTo(45);
        confereIntacta();
    }

    @Test
    void limitesAceitosELimitesRecusadosSemMudarNada() throws Exception {
        alterar(pedido(500000, 25, 35, 30)).andExpect(status().isOk()).andExpect(jsonPath("$.metaGuarani").value(500000))
                .andExpect(jsonPath("$.diasDoRetorno").value(30));
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_DIAS_DO_RETORNO)).isEqualTo(30);
        alterar(pedido(1, 0, 0, 1)).andExpect(status().isOk());
        alterar(pedido(1_000_000_000, 40, 40, 365)).andExpect(status().isOk());
        // Recusados: o último valor aceito (acima) continua.
        alterar(pedido(700000, 30, 41, 45)).andExpect(status().isBadRequest());
        alterar(pedido(700000, 41, 41, 45)).andExpect(status().isBadRequest());
        alterar(pedido(700000, -1, 40, 45)).andExpect(status().isBadRequest());
        alterar(pedido(700000, 35, 30, 45)).andExpect(status().isBadRequest());
        alterar(pedido(700000, 30, 40, 0)).andExpect(status().isBadRequest());
        alterar(pedido(700000, 30, 40, 366)).andExpect(status().isBadRequest());
        alterar(pedido(0, 30, 40, 45)).andExpect(status().isBadRequest());
        alterar(pedido(1_000_000_001, 30, 40, 45)).andExpect(status().isBadRequest());
        mvc.perform(get(ROTA).with(usuarios.comoDona())).andExpect(jsonPath("$.metaGuarani").value(1_000_000_000))
                .andExpect(jsonPath("$.diasDoRetorno").value(365));
    }

    @Test
    void oBancoTambemRecusaForaDaFaixa() {
        for (String comando : List.of(
                "UPDATE corona.configuracoes SET valor_inteiro = 41 WHERE chave = 'consignado_comissao_na_meta'",
                "UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'consignado_dias_do_retorno'",
                "UPDATE corona.configuracoes SET valor_inteiro = 366 WHERE chave = 'consignado_dias_do_retorno'",
                "UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'consignado_meta_guarani'")) {
            assertThatThrownBy(() -> jdbc.update(comando)).as(comando).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void mudancaAuditadaComOValorAntigoESoDoQueMudou() throws Exception {
        UsuarioDeTeste dona = usuarios.criarDona();
        RequestPostProcessor quem = como(usuarios, dona);
        alterar(quem, pedido(700000, 30, 40, 50)).andExpect(status().isOk());
        alterar(quem, pedido(600000, 30, 40, 50)).andExpect(status().isOk());
        alterar(quem, pedido(600000, 30, 40, 50)).andExpect(status().isOk()); // igual: nada novo
        List<Map<String, Object>> registros = jdbc.queryForList("SELECT antes::text AS antes, depois::text AS depois FROM corona.auditoria "
                + "WHERE acao = 'COMISSAO_ALTERADA' AND ator_usuario_id = ? ORDER BY ocorrida_em, id", dona.id());
        assertThat(registros).hasSize(2);
        assertThat(registros.get(0).get("antes").toString()).contains("consignado_dias_do_retorno").contains("45");
        assertThat(registros.get(0).get("depois").toString()).contains("50");
        assertThat(registros.get(1).get("antes").toString()).contains("consignado_meta_guarani").contains("700000");
        assertThat(registros.get(1).get("depois").toString()).contains("600000");
    }

    @Test
    void soAdminVeEMudaOVendedorEOAnonimoNao() throws Exception {
        RequestPostProcessor vendedor = como(usuarios, usuarios.criarVendedor());
        mvc.perform(get(ROTA).with(vendedor)).andExpect(status().isForbidden());
        alterar(vendedor, pedido(1, 1, 1, 1)).andExpect(status().isForbidden());
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
        confereIntacta();
    }

    @Test
    void oVendedorRecebeOsQuatroNosParametrosDoPullSoAcrescentandoCampos() throws Exception {
        alterar(pedido(800000, 20, 35, 60)).andExpect(status().isOk());
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        JsonNode r = json.readTree(mvc.perform(get("/api/vendedor/sync/pull").with(como(usuarios, vendedor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode p = r.get("parametros");
        assertThat(p.get("consignadoMetaGuarani").asInt()).isEqualTo(800000);
        assertThat(p.get("consignadoComissaoAbaixoDaMeta").asInt()).isEqualTo(20);
        assertThat(p.get("consignadoComissaoNaMeta").asInt()).isEqualTo(35);
        assertThat(p.get("consignadoDiasDoRetorno").asInt()).isEqualTo(60);
        // O app 1.2.0 lê só os campos antigos e ignora o resto: eles continuam, só se acrescentou.
        assertThat(p.propertyNames().stream().sorted().toList()).containsExactly("consignadoComissaoAbaixoDaMeta",
                "consignadoComissaoNaMeta", "consignadoDiasDoRetorno", "consignadoMetaGuarani", "limiteDescontoPercentual",
                "prazoArrependimentoDias", "prazoDefeitoDias", "situacaoDoAparelho", "tempoMaximoOfflineDias");
    }
}
