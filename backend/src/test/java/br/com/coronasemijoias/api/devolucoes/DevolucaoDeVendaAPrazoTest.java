package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDaVendaAPrazo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A devolução de venda a prazo (tarefa 6.6; ADR-0014, item 10; ADR-0012, item 7): primeiro abate o que o cliente ainda
 * deve (cancela as parcelas em aberto, da última para a primeira), depois volta o que foi pago em crédito e só então o
 * dinheiro. Massa base: R$ 100,00 em 3 peças (33,33, 33,33 e 33,34). Escrito antes do código; a C1 ampliada e a C5 rodam
 * depois de cada teste.
 */
class DevolucaoDeVendaAPrazoTest extends ApoioDaVendaAPrazo {

    @Test
    void semPagamentoADevolucaoTotalCancelaAsTresParcelasDaUltimaParaAPrimeiraSemDinheiro() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        assertThat(situacoes(c.venda())).containsExactly("ABERTA", "ABERTA", "ABERTA");
        JsonNode d = devolverAPrazoOk(c, 3);
        assertThat(situacoes(c.venda())).containsExactly("CANCELADA", "CANCELADA", "CANCELADA");
        Map<String, Object> g = devolucaoGravada(UUID.fromString(d.get("id").asString()));
        igual((BigDecimal) g.get("valor_devolvido"), "100.00");
        igual((BigDecimal) g.get("valor_abatido"), "100.00");
        igual((BigDecimal) g.get("valor_reembolsado"), "0.00");
        igual((BigDecimal) g.get("valor_em_credito"), "0.00");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'", c.venda()))
                .isZero();
        List<Integer> ordem = jdbc.queryForList("""
                SELECT c.parcela FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id
                 WHERE c.venda_id = ? AND b.tipo = 'CANCELAMENTO' AND b.motivo_cancelamento = 'DEVOLUCAO' ORDER BY b.id""",
                Integer.class, c.venda());
        assertThat(ordem).containsExactly(3, 2, 1);
        igual(emAberto(c.venda()), "0.00");
    }

    @Test
    void semPagamentoADevolucaoDeUmaPecaDeixaAUltimaParcelaParcial() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        devolverAPrazoOk(c, 1);
        assertThat(situacoes(c.venda())).containsExactly("ABERTA", "ABERTA", "PARCIAL");
        igual(canceladoDaParcela(c.venda(), 3), "33.33");
        igual(canceladoDaParcela(c.venda(), 1), "0.00");
        igual(emAberto(c.venda()), "66.67");
        assertThat(dinheiroDevolvido(c.venda())).isEqualByComparingTo("0.00");
    }

    @Test
    void pagoEmParteAbateOQueFaltaEDevolveSoOQueFoiPago() throws Exception {
        // 40,00 na hora e 60,00 em 2 x 30,00.
        APrazo c = vendaAPrazo("40.00", 2);
        JsonNode d = devolverAPrazoOk(c, 3);
        Map<String, Object> g = devolucaoGravada(UUID.fromString(d.get("id").asString()));
        igual((BigDecimal) g.get("valor_abatido"), "60.00");
        igual((BigDecimal) g.get("valor_reembolsado"), "40.00");
        igual(dinheiroDevolvido(c.venda()), "40.00");
        assertThat(situacoes(c.venda())).containsExactly("CANCELADA", "CANCELADA");

        // Uma peça só: 30,00 da parcela 2 e 3,33 da parcela 1, nenhum dinheiro.
        APrazo outra = vendaAPrazo("40.00", 2);
        JsonNode um = devolverAPrazoOk(outra, 1);
        Map<String, Object> g1 = devolucaoGravada(UUID.fromString(um.get("id").asString()));
        igual((BigDecimal) g1.get("valor_abatido"), "33.33");
        igual((BigDecimal) g1.get("valor_reembolsado"), "0.00");
        igual(canceladoDaParcela(outra.venda(), 2), "30.00");
        igual(canceladoDaParcela(outra.venda(), 1), "3.33");
        assertThat(dinheiroDevolvido(outra.venda())).isEqualByComparingTo("0.00");
    }

    @Test
    void vendaPagaTodaDepoisDevolveEmDinheiroSemBaixaDeCancelamento() throws Exception {
        APrazo c = vendaAPrazo(null, 2);
        receber(c, UUID.randomUUID(), "100.00").andExpect(status().isCreated());
        devolverAPrazoOk(c, 1);
        igual(dinheiroDevolvido(c.venda()), "33.33");
        assertThat(baixasDeCancelamento(c.venda(), "DEVOLUCAO")).isZero();
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PAGA");
    }

    @Test
    void tresDevolucoesDeUmaPecaFechamAsParcelasSemCentavoSobrandoEATerceiraSemDinheiro() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        devolverAPrazoOk(c, 1);
        devolverAPrazoOk(c, 1);
        JsonNode terceira = devolverAPrazoOk(c, 1);
        assertThat(situacoes(c.venda())).containsExactly("CANCELADA", "CANCELADA", "CANCELADA");
        Map<String, Object> g = devolucaoGravada(UUID.fromString(terceira.get("id").asString()));
        igual((BigDecimal) g.get("valor_abatido"), "33.34");
        igual((BigDecimal) g.get("valor_reembolsado"), "0.00");
        igual(emAberto(c.venda()), "0.00");
    }

    @Test
    void recebimentoDepoisDaDevolucaoParcialEntraSoAteOQueFalta() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        devolverAPrazoOk(c, 1);
        receber(c, UUID.randomUUID(), "66.68").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECEBIMENTO_ACIMA_DO_EM_ABERTO"));
        receber(c, UUID.randomUUID(), "66.67").andExpect(status().isCreated());
        // A parcela 3 (33,34) tinha 33,33 abatidos: o 0,01 recebido a fecha.
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PAGA", "CANCELADA");
        igual(emAberto(c.venda()), "0.00");
    }

    @Test
    void estornoDoRecebimentoComDevolucaoAtivaContinuaRecusado() throws Exception {
        APrazo c = vendaAPrazo(null, 2);
        UUID recebimento = UUID.randomUUID();
        receber(c, recebimento, "50.00").andExpect(status().isCreated());
        devolverAPrazoOk(c, 1);
        postar("/api/vendedor/recebimentos-de-cliente/" + recebimento + "/estorno", como(c.v()),
                mapa("id", UUID.randomUUID(), "motivo", "Erro")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("VENDA_COM_DEVOLUCAO"));
    }

    @Test
    void vendaPagaEmParteComCreditoVoltaPrimeiroOAbatidoDepoisOCredito() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "50.00", "20.00");
        UUID a = peca("33.34", 20);
        UUID maleta = maleta(v, a, 20);
        limiteDeDesconto(5);
        UUID venda = UUID.randomUUID();
        Map<String, Object> corpo = pedidoDeVenda(venda, maleta, cliente, List.of(linhaComDesconto(a, 3, "0.02")),
                List.of(pagamentoComCredito("30.00", credito)));
        postar(VENDAS, como(v), corpo).andExpect(status().isCreated());
        vendasDoTeste.add(venda);
        APrazo c = new APrazo(v, cliente, a, maleta, venda);
        // 70,00 a prazo e 30,00 pagos em crédito.
        JsonNode d = devolverAPrazoOk(c, 3);
        Map<String, Object> g = devolucaoGravada(UUID.fromString(d.get("id").asString()));
        igual((BigDecimal) g.get("valor_abatido"), "70.00");
        igual((BigDecimal) g.get("valor_em_credito"), "30.00");
        igual((BigDecimal) g.get("valor_reembolsado"), "0.00");
        assertThat(contar("SELECT count(*) FROM corona.creditos_cliente WHERE devolucao_id = ? AND origem = 'DEVOLUCAO'",
                UUID.fromString(d.get("id").asString()))).isEqualTo(1);
    }

    @Test
    void cancelarADevolucaoDesfazOAbatimentoEOReenvioDevolve200() throws Exception {
        APrazo c = vendaAPrazo("40.00", 2);
        JsonNode d = devolverAPrazoOk(c, 3);
        UUID id = UUID.fromString(d.get("id").asString());
        UUID cancelamento = UUID.randomUUID();
        cancelarDevolucao(c, id, cancelamento).andExpect(status().isCreated());
        assertThat(situacoes(c.venda())).containsExactly("ABERTA", "ABERTA");
        igual(emAberto(c.venda()), "60.00");
        igual(dinheiroDevolvido(c.venda()), "0.00");
        Map<String, Object> g = devolucaoGravada(id);
        assertThat(g.get("situacao")).isEqualTo("CANCELADA");
        igual((BigDecimal) g.get("valor_abatido"), "60.00");
        int baixas = contar("SELECT count(*) FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id WHERE c.venda_id = ?",
                c.venda());
        cancelarDevolucao(c, id, cancelamento).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id WHERE c.venda_id = ?",
                c.venda())).isEqualTo(baixas);
        // Depois de cancelar, a dívida volta a poder ser recebida.
        receber(c, UUID.randomUUID(), "60.00").andExpect(status().isCreated());
    }

    @Test
    void cancelarComRecebimentoPosteriorAConciliacaoContinuaFechando() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        JsonNode d = devolverAPrazoOk(c, 1);
        receber(c, UUID.randomUUID(), "20.00").andExpect(status().isCreated());
        cancelarDevolucao(c, UUID.fromString(d.get("id").asString()), UUID.randomUUID()).andExpect(status().isCreated());
        igual(emAberto(c.venda()), "80.00");
    }

    @Test
    void estornoDaVendaContinuaRecusadoComDevolucaoAtivaEDepoisDeCancelarCancelaOEmAberto() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        JsonNode d = devolverAPrazoOk(c, 1);
        postar(VENDAS + "/" + c.venda() + "/estorno", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "Engano"))
                .andExpect(status().isConflict());
        cancelarDevolucao(c, UUID.fromString(d.get("id").asString()), UUID.randomUUID()).andExpect(status().isCreated());
        postar(VENDAS + "/" + c.venda() + "/estorno", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "Engano"))
                .andExpect(status().isCreated());
        assertThat(situacoes(c.venda())).containsExactly("CANCELADA", "CANCELADA", "CANCELADA");
        assertThat(baixasDeCancelamento(c.venda(), "ESTORNO_VENDA")).isEqualTo(3);
    }

    @Test
    void antifraudeOlhaSoODinheiroQueSaiu() throws Exception {
        valorDeAprovacao("20.00");
        // Sem pagamento: devolução de 33,33 não tem dinheiro, então não precisa de autorização.
        APrazo semPagar = vendaAPrazo(null, 3);
        JsonNode aceita = devolverAPrazoOk(semPagar, 1);
        assertThat(devolucaoGravada(UUID.fromString(aceita.get("id").asString())).get("situacao")).isEqualTo("ACEITA");
        // Com 40,00 pagos, a devolução total devolve 40,00 em dinheiro: acima de 20,00 sem autorização.
        APrazo paga = vendaAPrazo("40.00", 2);
        devolverAPrazo(paga, 3, "DINHEIRO").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEVOLUCAO_PRECISA_DE_AUTORIZACAO"));
        igual(emAberto(paga.venda()), "60.00");
    }

    @Test
    void odonaDevolveAVendaAPrazoComOMesmoAbatimento() throws Exception {
        APrazo c = vendaAPrazo("40.00", 2);
        postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", c.venda(), "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", List.of(devolvida(c.peca(), 3, "BOA")))).andExpect(status().isCreated());
        igual(emAberto(c.venda()), "0.00");
        igual(dinheiroDevolvido(c.venda()), "40.00");
    }

    @Test
    void maletaEmAcertoRecusaESemMexerNasParcelas() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO' WHERE id = ?", c.maleta());
        devolverAPrazo(c, 1, "DINHEIRO").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("MALETA_EM_ACERTO"));
        igual(emAberto(c.venda()), "100.00");
        jdbc.update("UPDATE corona.remessas SET status = 'CONFERIDA' WHERE id = ?", c.maleta());
    }

    @Test
    void mesmoIdEMesmoConteudoNaoAbateDeNovoEConteudoDiferenteDa409() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        UUID id = UUID.randomUUID();
        Map<String, Object> pedido = mapa("id", id, "vendaId", c.venda(), "motivo", "ARREPENDIMENTO", "formaReembolso", "DINHEIRO",
                "itens", List.of(devolvida(c.peca(), 1, "BOA")));
        postar(DEVOLUCOES, como(c.v()), pedido).andExpect(status().isCreated());
        int baixas = baixasDeCancelamento(c.venda(), "DEVOLUCAO");
        postar(DEVOLUCOES, como(c.v()), pedido).andExpect(status().isOk());
        assertThat(baixasDeCancelamento(c.venda(), "DEVOLUCAO")).isEqualTo(baixas);
        Map<String, Object> diferente = mapa("id", id, "vendaId", c.venda(), "motivo", "ARREPENDIMENTO", "formaReembolso",
                "DINHEIRO", "itens", List.of(devolvida(c.peca(), 2, "BOA")));
        postar(DEVOLUCOES, como(c.v()), diferente).andExpect(status().isConflict());
        igual(emAberto(c.venda()), "66.67");
    }

    @Test
    void vendedorDeOutraVendaVe404AdminEVendedorTemOs403() throws Exception {
        APrazo c = vendaAPrazo(null, 3);
        UsuarioDeTeste outro = usuarios.criarVendedor();
        postar(DEVOLUCOES, como(outro), mapa("id", UUID.randomUUID(), "vendaId", c.venda(), "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", List.of(devolvida(c.peca(), 1, "BOA")))).andExpect(status().isNotFound());
        igual(emAberto(c.venda()), "100.00");
        postar(DEVOLUCOES, dona(), mapa("id", UUID.randomUUID(), "vendaId", c.venda(), "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", List.of(devolvida(c.peca(), 1, "BOA")))).andExpect(status().isForbidden());
        postar("/api/dona/devolucoes", como(c.v()), mapa("id", UUID.randomUUID(), "vendaId", c.venda(), "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", List.of(devolvida(c.peca(), 1, "BOA")))).andExpect(status().isForbidden());
    }

    @Test
    void aListaDeVendasComAPecaMostraAVendaAPrazoEOEmAberto() throws Exception {
        APrazo c = vendaAPrazo("40.00", 2);
        String codigo = jdbc.queryForObject("SELECT codigo FROM corona.produto_codigos WHERE produto_id = ? LIMIT 1", String.class,
                c.peca());
        JsonNode lista = corpoDe(ler(DEVOLUCOES + "/vendas-com-a-peca?codigo=" + codigo, como(c.v())).andExpect(status().isOk()));
        JsonNode venda = lista.get("vendas").get(0);
        assertThat(venda.get("aPrazo").asBoolean()).isTrue();
        assertThat(new BigDecimal(venda.get("emAberto").asString())).isEqualByComparingTo("60.00");
        JsonNode d = devolverAPrazoOk(c, 1);
        assertThat(new BigDecimal(corpoDe(ler(DEVOLUCOES + "/" + d.get("id").asString(), como(c.v())).andExpect(status().isOk()))
                .get("valorAbatido").asString())).isEqualByComparingTo("33.33");
    }

    @Test
    void oCodigoAntigoFicaNoCatalogoParaAppAntigoMasADevolucaoDeVendaAPrazoNaoOUsaMais() throws Exception {
        assertThat(br.com.coronasemijoias.api.comum.erros.CodigoErro.valueOf("VENDA_A_PRAZO_SEM_DEVOLUCAO")).isNotNull();
        APrazo c = vendaAPrazo("40.00", 2);
        devolverAPrazo(c, 1, "DINHEIRO").andExpect(status().isCreated());
    }
}
