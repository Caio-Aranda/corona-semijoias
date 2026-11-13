package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Apoio dos testes do envio das operações da Fase 6 (tarefa 6.7): o lote do push como o Corona Vendas manda, os
 * resultados e os pedidos de cada tipo novo. Os cadastros, maletas, vendas e trocas vêm do apoio da 6.5.
 */
public abstract class ApoioDoEnvioDaFase6 extends ApoioDasTrocas {

    protected static final String PUSH = "/api/vendedor/sync/push";

    /** Palavras que nunca aparecem numa resposta do envio (ADR-0013; CLAUDE.md: nada de custo nem de outro vendedor). */
    protected static final List<String> PROIBIDAS = List.of("custo", "fornecedor", "registrada_por", "decidida_por",
            "revisada_por", "credito_origem_id", "credito_destino_id");

    protected static Map<String, Object> op(UUID id, String tipo, Map<String, Object> dados) {
        return mapa("operacaoId", id, "tipo", tipo, "formato", 1, "criadoEmLocal", Instant.now().toString(), "dados", dados);
    }

    /** O lote com o relógio do computador certo; devolve a lista de resultados. */
    protected JsonNode enviar(UsuarioDeTeste v, List<?> operacoes) throws Exception {
        return enviar(v, relogio.instant(), operacoes);
    }

    /** O lote com a hora local que o computador diz ter (a diferença para o servidor corrige as horas declaradas). */
    protected JsonNode enviar(UsuarioDeTeste v, Instant horaLocal, List<?> operacoes) throws Exception {
        return enviar(como(v), horaLocal, operacoes);
    }

    /** O lote com um token escolhido (por exemplo, um novo depois de o relógio do servidor andar). */
    protected JsonNode enviar(org.springframework.test.web.servlet.request.RequestPostProcessor quem, Instant horaLocal,
            List<?> operacoes) throws Exception {
        String corpo = mvc.perform(post(PUSH).with(quem).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        mapa("horaLocal", horaLocal.toString(), "aguardandoDepoisDeste", 0, "operacoes", operacoes))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String proibida : PROIBIDAS) {
            assertThat(corpo.toLowerCase()).as("a resposta do envio não leva %s", proibida).doesNotContain(proibida);
        }
        return json.readTree(corpo).get("resultados");
    }

    protected static String resultado(JsonNode r, int i) {
        return r.get(i).get("resultado").asString();
    }

    protected static String codigo(JsonNode r, int i) {
        JsonNode c = r.get(i).get("codigo");
        return c == null || c.isNull() ? null : c.asString();
    }

    // --- os tipos da Fase 6, formato 1 ---

    protected static Map<String, Object> vendaPeloEnvio(UUID id, UUID maleta, UUID cliente, List<Map<String, Object>> itens,
            List<Map<String, Object>> pagamentos, Instant vendidaEm) {
        Map<String, Object> dados = pedidoDeVenda(id, maleta, cliente, itens, pagamentos);
        dados.put("vendidaEm", vendidaEm == null ? null : vendidaEm.toString());
        return op(id, "VENDA", dados);
    }

    protected static Map<String, Object> devolucaoPeloEnvio(UUID id, UUID venda, String motivo, String forma,
            List<Map<String, Object>> itens, UUID autorizacao, Instant devolvidaEm) {
        Map<String, Object> dados = mapa("id", id, "vendaId", venda, "motivo", motivo, "formaReembolso", forma, "itens", itens,
                "autorizacaoId", autorizacao, "devolvidaEm", devolvidaEm == null ? null : devolvidaEm.toString());
        return op(id, "DEVOLUCAO", dados);
    }

    protected static Map<String, Object> pedidoPeloEnvio(UUID id, UUID venda, String motivo, List<Map<String, Object>> itens,
            Instant pedidaEm) {
        return op(id, "PEDIDO_AUTORIZACAO", mapa("id", id, "vendaId", venda, "motivo", motivo, "itens", itens,
                "pedidaEm", pedidaEm == null ? null : pedidaEm.toString()));
    }

    /** O pedido da troca (como a rota) com a hora da troca; a operação tem o id da troca. */
    protected static Map<String, Object> trocaPeloEnvio(Map<String, Object> pedidoDeTroca, Instant trocadaEm) {
        Map<String, Object> dados = new LinkedHashMap<>(pedidoDeTroca);
        dados.put("trocadaEm", trocadaEm == null ? null : trocadaEm.toString());
        return op((UUID) pedidoDeTroca.get("id"), "TROCA", dados);
    }

    protected static Map<String, Object> cancelarDevolucaoPeloEnvio(UUID id, UUID devolucao, Instant canceladaEm) {
        return op(id, "CANCELAR_DEVOLUCAO", mapa("id", id, "devolucaoId", devolucao, "motivo", "Lançada errada",
                "canceladaEm", canceladaEm == null ? null : canceladaEm.toString()));
    }

    protected static Map<String, Object> cancelarTrocaPeloEnvio(UUID id, UUID troca, Instant canceladaEm) {
        return op(id, "CANCELAR_TROCA", mapa("id", id, "trocaId", troca, "motivo", "Lançada errada",
                "canceladaEm", canceladaEm == null ? null : canceladaEm.toString()));
    }

    /** A linha do sync_log da operação: resultado, código e se guardou o JSON. */
    protected Map<String, Object> registroDoEnvio(UUID operacao) {
        return jdbc.queryForMap("SELECT resultado, codigo, dados IS NOT NULL AS com_json, hora_corrigida FROM corona.sync_log WHERE id = ?",
                operacao);
    }

    /** O que a venda ainda tem a receber do cliente: total − devolvido − pagos (a conta de sempre, nada guardado). */
    protected java.math.BigDecimal aReceber(UUID venda) {
        return valor("""
                SELECT v.total
                       - coalesce((SELECT sum(d.valor_devolvido) FROM corona.devolucoes d
                                    WHERE d.venda_id = v.id AND d.situacao <> 'CANCELADA'), 0)
                       - coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p
                                    WHERE p.venda_id = v.id AND p.tipo = 'PAGAMENTO'), 0)
                  FROM corona.vendas v WHERE v.id = ?""", venda);
    }
}
