package br.com.coronasemijoias.api.importacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.fotos.ImagensDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resultado das importações (tarefa 1.13, §12): o lote da planilha e o das fotos ficam gravados no servidor
 * (quem, quando, o que aconteceu com cada linha) e saem no relatório "Resultado das importações".
 */
class ImportacoesTest extends TesteDeIntegracao {

    private static final String RELATORIO = "/api/dona/relatorios/cadastros/resultado-das-importacoes";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static String marca() {
        return "Zi" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> linha(String ref, String resultado, String motivo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("referencia", ref);
        m.put("resultado", resultado);
        if (motivo != null) {
            m.put("motivo", motivo);
        }
        return m;
    }

    private JsonNode ler(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions enviar(UUID id, String tipo, List<Map<String, Object>> linhas) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("tipo", tipo);
        corpo.put("arquivoNome", "produtos.xlsx");
        corpo.put("linhas", linhas);
        return mvc.perform(post("/api/dona/importacoes/" + id + "/linhas").with(usuarios.comoDona())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)));
    }

    private JsonNode relatorio(String... consulta) throws Exception {
        MockHttpServletRequestBuilder pedido = get(RELATORIO).with(usuarios.comoDona());
        for (int i = 0; i < consulta.length; i += 2) {
            pedido.param(consulta[i], consulta[i + 1]);
        }
        return ler(mvc.perform(pedido).andExpect(status().isOk()));
    }

    private static JsonNode total(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (chave.equals(t.get("chave").asString())) {
                return t;
            }
        }
        throw new AssertionError("Sem o total " + chave);
    }

    @Test
    void planilhaGravadaApareceNoRelatorioComQuemEQuando() throws Exception {
        String m = marca();
        UUID lote = UUID.randomUUID();
        enviar(lote, "PLANILHA", List.of(linha("Linha 2 " + m, "CRIADO", null), linha("Linha 3 " + m, "ATUALIZADO", null),
                linha("Linha 4 " + m, "RECUSADO", "Preço de venda inválido"))).andExpect(status().isNoContent());

        JsonNode r = relatorio("busca", m);
        assertThat(r.get("total").asLong()).isEqualTo(3);
        assertThat(total(r, "criados").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(r, "atualizados").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(r, "recusados").get("quantidade").asLong()).isEqualTo(1);
        JsonNode recusada = relatorio("busca", m, "resultado", "RECUSADO").get("itens").get(0);
        assertThat(recusada.get("motivo").asString()).isEqualTo("Preço de venda inválido");
        assertThat(recusada.get("tipoNaTela").asString()).isEqualTo("Planilha de produtos");
        assertThat(recusada.get("quem").asString()).isNotBlank();
        assertThat(recusada.get("quando").asString()).isNotBlank();
        assertThat(relatorio("busca", m, "tipo", "FOTOS").get("total").asLong()).isZero();
        assertThat(relatorio("busca", m, "resultado", "CRIADO", "tipo", "PLANILHA").get("total").asLong()).isEqualTo(1);
    }

    @Test
    void repetirOMesmoEnvioNaoDuplicaEMandarEmPartesJunta() throws Exception {
        String m = marca();
        UUID lote = UUID.randomUUID();
        enviar(lote, "PLANILHA", List.of(linha("A " + m, "CRIADO", null))).andExpect(status().isNoContent());
        enviar(lote, "PLANILHA", List.of(linha("B " + m, "CRIADO", null))).andExpect(status().isNoContent());
        assertThat(relatorio("busca", m).get("total").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.importacoes WHERE id = ?::uuid", Long.class,
                lote.toString())).isEqualTo(1);
        // Outro tipo no mesmo lote não vale.
        enviar(lote, "FOTOS", List.of(linha("C " + m, "CRIADO", null))).andExpect(status().isBadRequest());
    }

    @Test
    void recusaDadoInvalidoEQuemNaoEDona() throws Exception {
        UUID lote = UUID.randomUUID();
        enviar(lote, "OUTRA", List.of(linha("x", "CRIADO", null))).andExpect(status().isBadRequest());
        enviar(lote, "PLANILHA", List.of(linha("x", "TALVEZ", null))).andExpect(status().isBadRequest());
        enviar(lote, "PLANILHA", List.of()).andExpect(status().isBadRequest());
        List<Map<String, Object>> demais = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            demais.add(linha("L" + i, "CRIADO", null));
        }
        enviar(lote, "PLANILHA", demais).andExpect(status().isBadRequest());
        mvc.perform(post("/api/dona/importacoes/" + lote + "/linhas").with(usuarios.comoVendedor())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"PLANILHA\",\"linhas\":[{\"referencia\":\"x\",\"resultado\":\"CRIADO\"}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(RELATORIO).with(usuarios.comoVendedor())).andExpect(status().isForbidden());
        mvc.perform(get(RELATORIO).param("resultado", "TALVEZ").with(usuarios.comoDona()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(RELATORIO).param("tipo", "TALVEZ").with(usuarios.comoDona())).andExpect(status().isBadRequest());
    }

    @Test
    void importacaoDeFotosGravaOResultadoNoServidor() throws Exception {
        String m = marca();
        UUID lote = UUID.randomUUID();
        MockMultipartFile foto = new MockMultipartFile("arquivos", "sem-peca-" + m + ".jpg", "application/octet-stream",
                ImagensDeTeste.jpeg(100, 100, 7));
        mvc.perform(multipart("/api/dona/produtos/fotos/importacao/confirmar").file(foto)
                .param("importacaoId", lote.toString()).with(usuarios.comoDona())).andExpect(status().isOk());

        JsonNode r = relatorio("busca", m, "tipo", "FOTOS");
        assertThat(r.get("total").asLong()).isEqualTo(1);
        JsonNode linha = r.get("itens").get(0);
        assertThat(linha.get("resultado").asString()).isEqualTo("RECUSADO");
        assertThat(linha.get("motivo").asString()).contains("Nenhuma peça");
        assertThat(linha.get("tipoNaTela").asString()).isEqualTo("Fotos em massa");
    }

    @Test
    void rodapeEDetalhamentoConciliam() throws Exception {
        String m = marca();
        UUID lote = UUID.randomUUID();
        enviar(lote, "PLANILHA", List.of(linha("1 " + m, "CRIADO", null), linha("2 " + m, "CRIADO", null),
                linha("3 " + m, "JA_EXISTIA", null), linha("4 " + m, "RECUSADO", "Motivo"))).andExpect(status().isNoContent());
        JsonNode r = relatorio("busca", m, "tamanho", "2");
        assertThat(r.get("total").asLong()).isEqualTo(4);
        assertThat(total(r, "todos").get("quantidade").asLong()).isEqualTo(4);
        for (JsonNode t : r.get("totais")) {
            JsonNode detalhe = relatorio("busca", m, "recorte", t.get("chave").asString());
            assertThat(detalhe.get("total").asLong()).isEqualTo(t.get("quantidade").asLong());
        }
        assertThat(total(r, "ja-existiam").get("quantidade").asLong()).isEqualTo(1);
    }
}
