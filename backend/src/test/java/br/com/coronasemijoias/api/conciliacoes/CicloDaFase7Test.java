package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * O ciclo real da Fase 7 de ponta a ponta, pelas rotas (tarefa 7.13), numa história só, com os números feitos à mão:
 * a cotação do dia é 1 Gs = R$ 0,0009, então a meta de 700.000 Gs é R$ 630,00.
 *
 * <p>Vendedor A: vende 2 peças de R$ 315,00 à vista (630,00) e 1 a prazo (15,00 na hora e 3 parcelas de 100,00), recebe a
 * primeira parcela, o cliente devolve a venda a prazo (a devolução abate as duas parcelas que faltam e volta o que foi pago),
 * a dona inicia o acerto, conta, fecha com pagamento (vendido líquido 630,00 = exatamente a meta: 40% de comissão = 252,00 e
 * líquido 378,00), paga a fornecedor, lança no caixa e fecha uma semana. Vendedor B: R$ 629,99 (699.988,88 Gs, um pouco abaixo
 * da meta): 30%. No fim, as nove conciliações ficam zeradas.
 */
class CicloDaFase7Test extends ApoioDasConciliacoes {

    private BigDecimal saldoDoCaixa() throws Exception {
        return new BigDecimal(corpoDe(ler(CAIXA + "/saldo", dona()).andExpect(status().isOk())).get("saldo").asString());
    }

    private List<Map<String, Object>> parcelasDaVenda(UUID venda) {
        return jdbc.queryForList("SELECT parcela, valor, status, valor_pago, valor_cancelado FROM corona.contas_receber"
                + " WHERE venda_id = ? ORDER BY parcela", venda);
    }

    private static void igual(Object valor, String esperado) {
        assertThat((BigDecimal) valor).isEqualByComparingTo(esperado);
    }

    @Test
    void ocicloCompletoDaFase7FechaComOsNumerosCertosEAsConciliacoesZeradas() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        configurarComissao(700_000, 30, 40);
        BigDecimal caixaAntes = saldoDoCaixa();

        // 1. A maleta e as vendas: 630,00 à vista e 315,00 a prazo (15,00 na hora, 3 parcelas de 100,00).
        UsuarioDeTeste a = novoVendedor();
        UUID cliente = cliente(a);
        UUID peca = peca("315.00", 20);
        UUID maleta = maletaConferida(a, semana(), peca, 6);
        vender(a, maleta, cliente, peca, 2, "630.00");
        UUID prazo = venderAPrazo(a, maleta, cliente, peca, 1, "15.00", 3);
        assertThat(parcelasDaVenda(prazo)).hasSize(3).allSatisfy(p -> {
            igual(p.get("valor"), "100.00");
            assertThat(p.get("status")).isEqualTo("ABERTA");
        });

        // 2. O cliente paga a primeira parcela (o vendedor registra o recebimento).
        postar(VENDAS + "/" + prazo + "/recebimentos", como(a), mapa("id", UUID.randomUUID(), "valor", "100.00", "forma", "PIX"))
                .andExpect(status().isCreated());
        assertThat(parcelasDaVenda(prazo)).extracting(p -> p.get("status")).containsExactly("PAGA", "ABERTA", "ABERTA");

        // 3. Devolução da venda a prazo: abate as duas parcelas que faltam (200,00) e volta o que foi pago (115,00).
        UUID devolucao = UUID.randomUUID();
        postarDona("/api/dona/devolucoes", mapa("id", devolucao, "vendaId", prazo, "motivo", "ARREPENDIMENTO", "formaReembolso",
                "DINHEIRO", "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA")))).andExpect(status().isCreated());
        Map<String, Object> d = jdbc.queryForMap("SELECT valor_devolvido, valor_abatido, valor_reembolsado, valor_em_credito"
                + " FROM corona.devolucoes WHERE id = ?", devolucao);
        igual(d.get("valor_devolvido"), "315.00");
        igual(d.get("valor_abatido"), "200.00");
        igual(d.get("valor_reembolsado"), "115.00");
        igual(d.get("valor_em_credito"), "0.00");
        assertThat(parcelasDaVenda(prazo)).extracting(p -> p.get("status")).containsExactly("PAGA", "CANCELADA", "CANCELADA");

        // 4. Iniciar o acerto: a maleta trava; a dona conta o que o vendedor trouxe.
        UUID acerto = iniciar(a);
        // Esperado: 3 na maleta (6 menos 3 vendidas) e 1 "para a loja" (a peça que o cliente devolveu).
        bipar(acerto, peca, 4, "BOA");
        JsonNode previa = corpoDe(ler(ACERTOS + "/" + acerto + "/fechamento", dona()).andExpect(status().isOk()));
        assertThat(previa.get("faltas")).isEmpty();
        assertThat(previa.get("sobras")).isEmpty();

        // 5. A comissão por meta: vendido líquido 945,00 - 315,00 = 630,00 = 700.000 Gs, exatamente a meta, então 40%.
        assertThat(previa.get("vendidoParaMetaGuarani").asLong()).isEqualTo(700_000L);
        assertThat(previa.get("percentualComissao").asString()).isEqualTo("40.00");
        assertThat(previa.get("vendidoLiquido").asString()).isEqualTo("630.00");
        assertThat(previa.get("comissao").asString()).isEqualTo("252.00");
        assertThat(previa.get("liquido").asString()).isEqualTo("378.00");

        // 6. Fechar com pagamento: 200,00 em dinheiro, 100,00 por transferência e o resto (78,00) em 2 parcelas de 39,00.
        Map<String, Object> pedido = mapa("id", UUID.randomUUID(), "faltas", List.of(), "defeitos", List.of(), "cobrancas", List.of(),
                "pendenciasAntigas", List.of(), "liquidoMostrado", "378.00", "pagamento", mapa(
                        "recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "valor", "200.00"),
                                mapa("id", UUID.randomUUID(), "forma", "PIX", "valor", "100.00")),
                        "restante", mapa("parcelas", 2, "primeiroVencimento", hoje().plusDays(7).toString())));
        // Um valor mostrado diferente do que o servidor calcula é recusado: o servidor nunca confia no que a tela mandou.
        var errado = new java.util.LinkedHashMap<>(pedido);
        errado.put("id", UUID.randomUUID());
        errado.put("liquidoMostrado", "400.00");
        fechar(acerto, errado).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM corona.acertos WHERE id = ?", String.class, acerto)).isEqualTo("EM_ACERTO");
        fechar(acerto, pedido).andExpect(status().isCreated());
        fechar(acerto, pedido).andExpect(status().isOk());

        Map<String, Object> g = jdbc.queryForMap("SELECT status, percentual_comissao, comissao, liquido, recebido_no_acerto,"
                + " ficou_devendo FROM corona.acertos WHERE id = ?", acerto);
        assertThat(g.get("status")).isEqualTo("ACERTADO");
        igual(g.get("percentual_comissao"), "40");
        igual(g.get("comissao"), "252.00");
        igual(g.get("liquido"), "378.00");
        igual(g.get("recebido_no_acerto"), "300.00");
        igual(g.get("ficou_devendo"), "78.00");
        assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta)).isEqualTo("ACERTADA");
        List<Map<String, Object>> contas = jdbc.queryForList("SELECT parcela, valor, status FROM corona.contas_receber"
                + " WHERE devedor_tipo = 'VENDEDOR' AND origem_id = ? ORDER BY parcela", acerto);
        assertThat(contas).hasSize(2).allSatisfy(c -> igual(c.get("valor"), "39.00"));

        // O vendedor lê o acerto dele, sem custo.
        String ficha = ler("/api/vendedor/acertos/" + acerto, como(a)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(ficha).contains("378.00").doesNotContain("precoCusto").doesNotContain("custo");

        // 7. Caixa: o recebimento do acerto entrou sozinho (200,00 + 100,00). Pago a fornecedor: 100,00; lançamento manual: 20,00.
        assertThat(saldoDoCaixa().subtract(caixaAntes)).isEqualByComparingTo("300.00");
        UUID fornecedor = fornecedor();
        UUID compra = compra(fornecedor, "300.00");
        List<UUID> contasAPagar = contasDe(compra, 3);
        pagar(contasAPagar.get(0), "100.00", "PIX");
        UUID categoria = UUID.randomUUID();
        postarDona(CAIXA + "/categorias", mapa("id", categoria, "nome", "Cat " + categoria, "tipo", "SAIDA")).andExpect(status().isCreated());
        UUID manual = UUID.randomUUID();
        lancamentosManuais.add(manual);
        postarDona(CAIXA + "/lancamentos", mapa("id", manual, "tipo", "SAIDA", "valor", "20.00", "forma", "DINHEIRO",
                "categoriaId", categoria, "data", hoje().toString(), "descricao", "Embalagens")).andExpect(status().isCreated());
        assertThat(saldoDoCaixa().subtract(caixaAntes)).isEqualByComparingTo("180.00");

        // 8. Outro vendedor, um pouco abaixo da meta: R$ 629,99 = 699.988,88 Gs, então 30% (a comissão é 189,00 e o líquido 440,99).
        UsuarioDeTeste b = novoVendedor();
        UUID clienteB = cliente(b);
        UUID pecaB = peca("629.99", 5);
        UUID maletaB = maletaConferida(b, semana(), pecaB, 3);
        vender(b, maletaB, clienteB, pecaB, 1, "629.99");
        UUID acertoB = iniciar(b);
        bipar(acertoB, pecaB, 2, "BOA");
        JsonNode previaB = corpoDe(ler(ACERTOS + "/" + acertoB + "/fechamento", dona()).andExpect(status().isOk()));
        assertThat(previaB.get("vendidoParaMetaGuarani").asLong()).isLessThan(700_000L);
        assertThat(previaB.get("percentualComissao").asString()).isEqualTo("30.00");
        assertThat(previaB.get("comissao").asString()).isEqualTo("189.00");
        assertThat(previaB.get("liquido").asString()).isEqualTo("440.99");
        fechar(acertoB, pedidoDeFechamento(List.of(), List.of(), "440.99")).andExpect(status().isCreated());

        // 9. Fechar uma semana que já terminou: depois dela, lançar no caixa com data nela é recusado.
        LocalDate inicio = LocalDate.of(1991, 1, 1);
        UUID semanaVelha = UUID.fromString(corpoDe(postarDona("/api/dona/semanas", mapa("numero", 97_001, "inicio", inicio.toString(),
                "fim", inicio.plusDays(6).toString())).andExpect(status().isCreated())).get("id").asString());
        postarDona("/api/dona/semanas/" + semanaVelha + "/fechar", mapa("id", UUID.randomUUID())).andExpect(status().isCreated());
        postarDona(CAIXA + "/lancamentos", mapa("id", UUID.randomUUID(), "tipo", "SAIDA", "valor", "5.00", "forma", "DINHEIRO",
                "categoriaId", categoria, "data", inicio.plusDays(2).toString(), "descricao", "Teste")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DATA_EM_SEMANA_FECHADA"));

        // 10. As nove conciliações, sobre tudo o que este ciclo criou: nenhuma diferença.
        for (String chave : ConciliacoesDaFase7.CHAVES) {
            var conferencia = conciliacoes.conferir(chave).conferencias().get(0);
            assertThat(conferencia.conferidos()).as(chave).isPositive();
            assertThat(daMassa(conferencia.divergencias())).as(chave + " no ciclo").isEmpty();
        }
    }
}
