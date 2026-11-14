package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A devolução e a troca de venda a prazo pelo envio do computador, e o pull das colunas novas (tarefa 6.6): o mesmo
 * abatimento da rota online; o reenvio do lote não abate de novo; a venda a prazo e a devolução dela no mesmo lote; os
 * cancelamentos desfazem o abatimento; {@code valor_abatido} e {@code devolucao_id} chegam só ao vendedor da venda.
 * Escritos antes do código.
 */
class EnvioEPullDaDevolucaoAPrazoTest extends ApoioDoEnvioDaFase6 {

    @Autowired
    ServicoDePull pull;

    private record Cenario(UsuarioDeTeste v, UUID cliente, UUID peca, UUID maleta) {
    }

    private Cenario cenario() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("100.00", 10);
        UUID maleta = maleta(v, peca, 8);
        return new Cenario(v, cliente, peca, maleta);
    }

    private Map<String, Object> vendaAPrazo(Cenario c, UUID id) {
        return vendaPeloEnvio(id, c.maleta(), c.cliente(), List.of(linha(c.peca(), 1)), List.of(), relogio.instant().minusSeconds(60));
    }

    private Map<String, Object> devolucao(Cenario c, UUID id, UUID venda) {
        return devolucaoPeloEnvio(id, venda, "ARREPENDIMENTO", "DINHEIRO", List.of(devolvida(c.peca(), 1, "BOA")), null,
                relogio.instant().minusSeconds(30));
    }

    private List<String> situacoes(UUID venda) {
        return jdbc.queryForList("SELECT status FROM corona.contas_receber WHERE venda_id = ? ORDER BY parcela", String.class, venda);
    }

    private BigDecimal abatido(UUID devolucao) {
        return valor("SELECT valor_abatido FROM corona.devolucoes WHERE id = ?", devolucao);
    }

    private int baixasDeDevolucao(UUID devolucao) {
        return contar("SELECT count(*) FROM corona.baixas_receber WHERE devolucao_id = ?", devolucao);
    }

    @Test
    void devolucaoDeVendaAPrazoPeloEnvioAbateEOReenvioDoLoteNaoAbateDeNovo() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        assertThat(resultado(enviar(c.v(), List.of(vendaAPrazo(c, venda))), 0)).isEqualTo("ACEITO");
        UUID devolucao = UUID.randomUUID();
        Map<String, Object> envio = devolucao(c, devolucao, venda);
        assertThat(resultado(enviar(c.v(), List.of(envio)), 0)).isEqualTo("ACEITO");
        assertThat(abatido(devolucao)).isEqualByComparingTo("100.00");
        assertThat(situacoes(venda)).containsExactly("CANCELADA");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", venda)).isZero();
        int baixas = baixasDeDevolucao(devolucao);
        assertThat(resultado(enviar(c.v(), List.of(envio)), 0)).isEqualTo("JA_RECEBIDO");
        assertThat(baixasDeDevolucao(devolucao)).isEqualTo(baixas);
    }

    @Test
    void vendaAPrazoEADevolucaoDelaNoMesmoLoteDependenciaEntreAsOperacoes() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        UUID devolucao = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(vendaAPrazo(c, venda), devolucao(c, devolucao, venda)));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        assertThat(abatido(devolucao)).isEqualByComparingTo("100.00");
        assertThat(situacoes(venda)).containsExactly("CANCELADA");
    }

    @Test
    void cancelarADevolucaoPeloEnvioDesfazOAbatimento() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        UUID devolucao = UUID.randomUUID();
        enviar(c.v(), List.of(vendaAPrazo(c, venda), devolucao(c, devolucao, venda)));
        JsonNode r = enviar(c.v(), List.of(cancelarDevolucaoPeloEnvio(UUID.randomUUID(), devolucao, relogio.instant().minusSeconds(5))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(situacoes(venda)).containsExactly("ABERTA");
        assertThat(valor("SELECT coalesce(sum(valor - valor_pago - valor_cancelado), 0) FROM corona.contas_receber WHERE venda_id = ?",
                venda)).isEqualByComparingTo("100.00");
    }

    @Test
    void trocaComAAPrazoPeloEnvioECancelarTrocaPeloEnvio() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("100.00", 10);
        UUID b = peca("120.00", 10);
        UUID maleta = maleta(v, a, 5, b, 5);
        UUID vendaA = UUID.randomUUID();
        enviar(v, List.of(vendaPeloEnvio(vendaA, maleta, cliente, List.of(linha(a, 1)), List.of(), relogio.instant().minusSeconds(60))));
        Map<String, Object> pedido = pedidoDeTroca(vendaA, "ARREPENDIMENTO", List.of(devolvida(a, 1, "BOA")), maleta,
                List.of(linha(b, 1)), List.of(pagamento("120.00", "DINHEIRO")));
        JsonNode r = enviar(v, List.of(trocaPeloEnvio(pedido, relogio.instant().minusSeconds(20))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(situacoes(vendaA)).containsExactly("CANCELADA");
        assertThat(contar("SELECT count(*) FROM corona.creditos_cliente WHERE cliente_id = ?", cliente)).isZero();
        UUID troca = (UUID) pedido.get("id");
        assertThat(resultado(enviar(v, List.of(cancelarTrocaPeloEnvio(UUID.randomUUID(), troca, relogio.instant().minusSeconds(5)))), 0))
                .isEqualTo("ACEITO");
        assertThat(situacoes(vendaA)).containsExactly("ABERTA");
    }

    // --- o pull ---

    private Map<String, Map<UUID, LinhaDoPull>> puxarTudo(UsuarioDeTeste v) {
        Map<String, Map<UUID, LinhaDoPull>> copia = new HashMap<>();
        String cursor = null;
        LoteDoPull lote;
        int voltas = 0;
        do {
            lote = pull.puxar(cursor, 200, new FiltroPorVendedor(v.vendedorId()));
            for (LinhaDoPull l : lote.linhas()) {
                Map<UUID, LinhaDoPull> t = copia.computeIfAbsent(l.tabela(), x -> new HashMap<>());
                LinhaDoPull atual = t.get(l.id());
                if (atual == null || l.versao() > atual.versao()) {
                    t.put(l.id(), l);
                }
            }
            cursor = lote.proximoCursor();
        } while (lote.haMais() && ++voltas < 500);
        return copia;
    }

    @Test
    void oPullTrazOAbatidoEAReferenciaDaBaixaSoAoVendedorDaVenda() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        UUID vendaA = UUID.randomUUID();
        UUID devolucaoA = UUID.randomUUID();
        enviar(a.v(), List.of(vendaAPrazo(a, vendaA), devolucao(a, devolucaoA, vendaA)));
        UUID vendaB = UUID.randomUUID();
        UUID devolucaoB = UUID.randomUUID();
        enviar(b.v(), List.of(vendaAPrazo(b, vendaB), devolucao(b, devolucaoB, vendaB)));
        enviar(b.v(), List.of(cancelarDevolucaoPeloEnvio(UUID.randomUUID(), devolucaoB, relogio.instant().minusSeconds(5))));

        Map<String, Map<UUID, LinhaDoPull>> copiaA = puxarTudo(a.v());
        assertThat(copiaA.get("devolucoes").get(devolucaoA).colunas()).containsKey("valor_abatido");
        assertThat((BigDecimal) copiaA.get("devolucoes").get(devolucaoA).colunas().get("valor_abatido")).isEqualByComparingTo("100.00");
        assertThat(copiaA.get("baixas_receber").values())
                .allSatisfy(l -> assertThat(l.colunas()).containsKey("devolucao_id"));
        assertThat(copiaA.get("baixas_receber").values())
                .anySatisfy(l -> assertThat(l.colunas()).containsEntry("devolucao_id", devolucaoA));
        // Nada do outro vendedor: nem a devolução, nem as baixas e estornos dela.
        assertThat(copiaA.get("devolucoes").keySet()).doesNotContain(devolucaoB);
        assertThat(copiaA.get("baixas_receber").values())
                .noneSatisfy(l -> assertThat(l.colunas()).containsEntry("devolucao_id", devolucaoB));
        // O vendedor B recebe o estorno do abatimento (baixa negativa) com a mesma referência.
        Map<String, Map<UUID, LinhaDoPull>> copiaB = puxarTudo(b.v());
        assertThat(copiaB.get("baixas_receber").values())
                .anySatisfy(l -> assertThat(l.colunas()).containsEntry("devolucao_id", devolucaoB).containsKey("estorna_id"));
        assertThat(copiaB.get("baixas_receber").values()).extracting(l -> l.colunas().get("tipo")).contains("CANCELAMENTO");
    }

    @SuppressWarnings("unused")
    private Instant agora() {
        return relogio.instant();
    }
}
