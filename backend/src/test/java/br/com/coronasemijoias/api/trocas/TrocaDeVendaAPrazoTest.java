package br.com.coronasemijoias.api.trocas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A troca com a peça A de uma venda a prazo (tarefa 6.6; ADR-0014, item 10): a devolução de A abate primeiro as parcelas
 * de A e só o resto vira crédito para pagar B (que também pode ser a prazo). A sem nada pago não gera crédito.
 * Escrito antes do código.
 */
class TrocaDeVendaAPrazoTest extends ApoioDaVendaAPrazo {

    /** A venda a prazo de A e uma peça B na mesma maleta. */
    private record Cena(APrazo a, UUID pecaB, UUID maleta) {
    }

    private Cena cena(String pagoNaHora, int parcelas, String precoB) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("33.34", 20);
        UUID b = peca(precoB, 20);
        UUID maleta = maleta(v, a, 20, b, 20);
        return new Cena(vendaAPrazoDe(v, cliente, a, maleta, pagoNaHora, parcelas), b, maleta);
    }

    private Map<String, Object> pedido(Cena c, int pecasDeA, List<Map<String, Object>> resto) {
        return pedidoDeTroca(c.a().venda(), "ARREPENDIMENTO", List.of(devolvida(c.a().peca(), pecasDeA, "BOA")), c.maleta(),
                List.of(linha(c.pecaB(), 1)), resto);
    }

    private Map<String, Object> diferencaDaTroca(JsonNode troca) {
        return jdbc.queryForMap("SELECT diferenca, devolucao_id, venda_nova_id FROM corona.trocas WHERE id = ?", id(troca));
    }

    @Test
    void aSemNadaPagoAbateAsParcelasENaoGeraCreditoOClientePagaTodaB() throws Exception {
        Cena c = cena(null, 3, "120.00");
        JsonNode troca = trocar(c.a().v(), pedido(c, 3, List.of(pagamento("120.00", "DINHEIRO"))));
        vendasDoTeste.add((UUID) diferencaDaTroca(troca).get("venda_nova_id"));
        assertThat(situacoes(c.a().venda())).containsExactly("CANCELADA", "CANCELADA", "CANCELADA");
        assertThat(contar("SELECT count(*) FROM corona.creditos_cliente WHERE cliente_id = ?", c.a().cliente())).isZero();
        assertThat(contar("SELECT count(*) FROM corona.usos_credito u JOIN corona.creditos_cliente k ON k.id = u.credito_id WHERE k.cliente_id = ?",
                c.a().cliente())).isZero();
        igual((BigDecimal) diferencaDaTroca(troca).get("diferenca"), "20.00");
        Map<String, Object> d = devolucaoGravada((UUID) diferencaDaTroca(troca).get("devolucao_id"));
        igual((BigDecimal) d.get("valor_abatido"), "100.00");
        igual((BigDecimal) d.get("valor_em_credito"), "0.00");
        igual((BigDecimal) d.get("valor_reembolsado"), "0.00");
    }

    @Test
    void aPagaEmParteAbateOQueFaltaEOResteViraCreditoQuePagaB() throws Exception {
        Cena c = cena("40.00", 2, "70.00");
        JsonNode troca = trocar(c.a().v(), pedido(c, 3, List.of(pagamento("30.00", "DINHEIRO"))));
        Map<String, Object> t = diferencaDaTroca(troca);
        vendasDoTeste.add((UUID) t.get("venda_nova_id"));
        Map<String, Object> d = devolucaoGravada((UUID) t.get("devolucao_id"));
        igual((BigDecimal) d.get("valor_abatido"), "60.00");
        igual((BigDecimal) d.get("valor_em_credito"), "40.00");
        igual((BigDecimal) d.get("valor_reembolsado"), "0.00");
        igual((BigDecimal) t.get("diferenca"), "-30.00");
        Map<String, Object> credito = credito(UUID.fromString(troca.get("creditoId").asString()));
        igual((BigDecimal) credito.get("valor"), "40.00");
        igual((BigDecimal) credito.get("valor_utilizado"), "40.00");
        assertThat(situacoes(c.a().venda())).containsExactly("CANCELADA", "CANCELADA");
    }

    @Test
    void bMaisBarataUsaOCreditoEASobraFicaEmAberto() throws Exception {
        Cena c = cena("40.00", 2, "30.00");
        JsonNode troca = trocar(c.a().v(), pedido(c, 3, List.of()));
        Map<String, Object> t = diferencaDaTroca(troca);
        vendasDoTeste.add((UUID) t.get("venda_nova_id"));
        Map<String, Object> credito = credito(UUID.fromString(troca.get("creditoId").asString()));
        igual((BigDecimal) credito.get("valor"), "40.00");
        igual((BigDecimal) credito.get("valor_utilizado"), "30.00");
        assertThat(credito.get("situacao")).isEqualTo("ABERTO");
    }

    @Test
    void bAPrazoComParcelamentoGeraAsParcelasSobreOQueFaltaDepoisDoCredito() throws Exception {
        Cena c = cena(null, 3, "120.00");
        Map<String, Object> pedido = pedido(c, 3, List.of());
        vendaDo(pedido).put("parcelamento", mapa("quantidade", 2,
                "primeiroVencimento", hoje().plusDays(5).toString()));
        JsonNode troca = trocar(c.a().v(), pedido);
        UUID vendaB = (UUID) diferencaDaTroca(troca).get("venda_nova_id");
        vendasDoTeste.add(vendaB);
        assertThat(parcelas(vendaB)).hasSize(2);
        igual(emAberto(vendaB), "120.00");
        assertThat(situacoes(c.a().venda())).containsExactly("CANCELADA", "CANCELADA", "CANCELADA");
    }

    @Test
    void cancelarATrocaFazAVoltarADeverEEstornaBComAsParcelasDela() throws Exception {
        Cena c = cena("40.00", 2, "70.00");
        JsonNode troca = trocar(c.a().v(), pedido(c, 3, List.of(pagamento("30.00", "DINHEIRO"))));
        UUID vendaB = (UUID) diferencaDaTroca(troca).get("venda_nova_id");
        vendasDoTeste.add(vendaB);
        postar(TROCAS + "/" + id(troca) + "/cancelar", como(c.a().v()), mapa("id", UUID.randomUUID(), "motivo", "Engano"))
                .andExpect(status().isCreated());
        assertThat(situacoes(c.a().venda())).containsExactly("ABERTA", "ABERTA");
        igual(emAberto(c.a().venda()), "60.00");
        assertThat(jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, vendaB)).isEqualTo("ESTORNADA");
        igual(emAberto(vendaB), "0.00");
    }

    @Test
    void trocaNaoMandaValorEForaDoPrazoOuSemCreditoContinuaTudoOuNada() throws Exception {
        Cena c = cena("40.00", 2, "70.00");
        // B custa 70,00 e o crédito de A é 40,00: faltando pagamento o resto vira parcela de B, nunca erro de troca.
        Map<String, Object> pedido = pedido(c, 3, List.of());
        JsonNode troca = trocar(c.a().v(), pedido);
        UUID vendaB = (UUID) diferencaDaTroca(troca).get("venda_nova_id");
        vendasDoTeste.add(vendaB);
        igual(emAberto(vendaB), "30.00");
    }
}
