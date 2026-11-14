package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Recebimento;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * O estorno do recebimento posterior (tarefa 7.3): volta os atalhos com registros negativos, o vendedor só no mesmo dia,
 * a dona sempre, e os leitores de pagamento passam a contar o estorno (a devolução não devolve dinheiro que não ficou).
 * Escrito antes do código.
 */
class EstornoDoRecebimentoTest extends ApoioDasContas {

    @Autowired
    ServicoDeContasAReceber contas;

    // --- o estorno em si ---

    @Test
    void estornoVoltaOsAtalhosEGravaRegistrosNegativosLigadosAosOriginais() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID pagamento = receberOk(c, "50.00");
        UUID estorno = UUID.randomUUID();
        JsonNode venda = corpoDe(estornarRecebimento(c, pagamento, estorno, "Digitei o valor errado")
                .andExpect(status().isCreated()));
        assertThat(situacoes(c.venda())).containsExactly("ABERTA", "ABERTA", "ABERTA");
        assertThat(pagos(c.venda())).containsExactly("0.00", "0.00", "0.00");
        assertThat(baixas(c.venda())).isEqualTo(4);
        assertThat(contar("""
                SELECT count(*) FROM corona.baixas_receber n JOIN corona.baixas_receber o ON o.id = n.estorna_id
                 WHERE n.valor = -o.valor AND n.conta_id = o.conta_id""")).isGreaterThanOrEqualTo(2);
        Map<String, Object> linha = jdbc.queryForMap(
                "SELECT tipo, valor, estorna_id, posterior, forma FROM corona.pagamentos_cliente WHERE id = ?", estorno);
        assertThat(linha.get("tipo")).isEqualTo("ESTORNO_PAGAMENTO");
        assertThat((BigDecimal) linha.get("valor")).isEqualByComparingTo("-50.00");
        assertThat(linha.get("estorna_id")).isEqualTo(pagamento);
        assertThat(linha.get("posterior")).isEqualTo(true);
        assertThat(linha.get("forma")).isEqualTo("PIX");
        // O recebimento original continua como estava.
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ? AND tipo = 'PAGAMENTO' AND valor = 50.00",
                pagamento)).isEqualTo(1);
        assertThat(new BigDecimal(venda.get("pago").asString())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(venda.get("aReceber").asString())).isEqualByComparingTo("100.00");
        // Depois do estorno, o valor pode ser recebido de novo.
        receberOk(c, "100.00");
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PAGA", "PAGA");
    }

    @Test
    void estornoDeEstornoEhRecusado() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID pagamento = receberOk(c, "10.00");
        UUID estorno = UUID.randomUUID();
        estornarRecebimento(c, pagamento, estorno, "Erro").andExpect(status().isCreated());
        estornarRecebimento(c, estorno, UUID.randomUUID(), "Erro de novo").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RECEBIMENTO_NAO_ENCONTRADO"));
        assertThat(pagos(c.venda())).containsExactly("0.00", "0.00", "0.00");
    }

    @Test
    void reenvioComOMesmoIdDevolve200EOutroIdNumRecebimentoJaEstornadoDa409() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID pagamento = receberOk(c, "10.00");
        UUID estorno = UUID.randomUUID();
        estornarRecebimento(c, pagamento, estorno, "Erro").andExpect(status().isCreated());
        estornarRecebimento(c, pagamento, estorno, "Erro").andExpect(status().isOk());
        assertThat(baixas(c.venda())).isEqualTo(2);
        estornarRecebimento(c, pagamento, UUID.randomUUID(), "Erro").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECEBIMENTO_JA_ESTORNADO"));
        // O id do estorno também não pode ser o de outro pagamento.
        UUID outro = receberOk(c, "10.00");
        estornarRecebimento(c, outro, pagamento, "Erro").andExpect(status().isConflict());
        assertThat(baixas(c.venda())).isEqualTo(3);
    }

    @Test
    void oPagamentoDaHoraDaVendaSoSeCorrigePeloEstornoDaVenda() throws Exception {
        APrazo c = vendaAPrazo("100.00", "40.00", 2);
        UUID pagamentoDaVenda = jdbc.queryForObject("SELECT id FROM corona.pagamentos_cliente WHERE venda_id = ?", UUID.class,
                c.venda());
        estornarRecebimento(c, pagamentoDaVenda, UUID.randomUUID(), "Erro").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA"));
    }

    @Test
    void motivoEObrigatorio() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID pagamento = receberOk(c, "10.00");
        estornarRecebimento(c, pagamento, UUID.randomUUID(), "  ").andExpect(status().isBadRequest());
        estornarRecebimento(c, pagamento, UUID.randomUUID(), "x".repeat(401)).andExpect(status().isBadRequest());
        assertThat(pagos(c.venda())).containsExactly("10.00", "0.00", "0.00");
    }

    // --- quem pode e quando ---

    @Test
    void vendedorSoEstornaNoMesmoDiaDeBrasiliaEADonaSempre() throws Exception {
        APrazo c = vendaDeCemEmTres();
        var v = c.vendedor();
        Instant recebidoEm = brasilia("2030-03-10T10:00:00");
        UUID pagamento = UUID.randomUUID();
        contas.receber(c.venda(), v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new Recebimento(pagamento, v("10.00"), "PIX"),
                recebidoEm);
        // 00:01 do dia seguinte (Brasília): só a dona.
        assertThatThrownBy(() -> contas.estornarRecebimento(pagamento, v.vendedorId(), v.id(), usuarios.aparelhoDe(v),
                UUID.randomUUID(), "Erro", brasilia("2030-03-11T00:01:00")))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("ESTORNO_DE_RECEBIMENTO_SO_DA_DONA"));
        assertThat(pagos(c.venda())).containsExactly("10.00", "0.00", "0.00");
        // 23:59 do mesmo dia (já é o dia seguinte em UTC): o vendedor consegue.
        UUID estorno = UUID.randomUUID();
        var r = contas.estornarRecebimento(pagamento, v.vendedorId(), v.id(), usuarios.aparelhoDe(v), estorno, "Erro",
                brasilia("2030-03-10T23:59:00"));
        assertThat(r.jaExistia()).isFalse();
        assertThat(pagos(c.venda())).containsExactly("0.00", "0.00", "0.00");
    }

    @Test
    void aDonaEstornaDiasDepois() throws Exception {
        APrazo c = vendaDeCemEmTres();
        var v = c.vendedor();
        UUID pagamento = UUID.randomUUID();
        contas.receber(c.venda(), v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new Recebimento(pagamento, v("10.00"), "PIX"),
                brasilia("2030-03-10T10:00:00"));
        var r = contas.estornarRecebimento(pagamento, null, usuarios.dona().id(), null, UUID.randomUUID(), "Erro de lançamento",
                brasilia("2030-03-20T09:00:00"));
        assertThat(r.jaExistia()).isFalse();
        assertThat(pagos(c.venda())).containsExactly("0.00", "0.00", "0.00");
        // E pela rota da dona (sem limite de dia).
        UUID outro = receberOk(c, "5.00");
        postar(ESTORNO_DA_DONA.formatted(outro), dona(), mapa("id", UUID.randomUUID(), "motivo", "Erro")).andExpect(status().isCreated());
    }

    @Test
    void recebimentoDeOutroVendedorDa404ParaOVendedor() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID pagamento = receberOk(c, "10.00");
        var intruso = usuarios.criarVendedor();
        postar(ESTORNO_DO_VENDEDOR.formatted(pagamento), como(intruso), mapa("id", UUID.randomUUID(), "motivo", "Erro"))
                .andExpect(status().isNotFound());
        postar(ESTORNO_DO_VENDEDOR.formatted(pagamento), dona(), mapa("id", UUID.randomUUID(), "motivo", "Erro"))
                .andExpect(status().isForbidden());
        postar(ESTORNO_DA_DONA.formatted(pagamento), como(c.vendedor()), mapa("id", UUID.randomUUID(), "motivo", "Erro"))
                .andExpect(status().isForbidden());
    }

    // --- os leitores de pagamento passam a contar o estorno (um teste por leitor) ---

    /** Venda de 100,00 em duas parcelas, recebida inteira e depois estornada: o cliente continua devendo tudo. */
    private APrazo vendaQueVoltouADever() throws Exception {
        APrazo c = vendaAPrazo("100.00", null, 2);
        UUID pagamento = receberOk(c, "100.00");
        assertThat(situacoes(c.venda())).containsExactly("PAGA", "PAGA");
        estornarRecebimento(c, pagamento, UUID.randomUUID(), "Erro").andExpect(status().isCreated());
        return c;
    }

    @Test
    void leitorDaDevolucaoRecusaVendaQueVoltouADever() throws Exception {
        APrazo c = vendaQueVoltouADever();
        postar("/api/vendedor/devolucoes", como(c.vendedor()), mapa("id", UUID.randomUUID(), "vendaId", c.venda(),
                "motivo", "ARREPENDIMENTO", "formaReembolso", "PIX",
                "itens", List.of(mapa("produtoId", c.peca(), "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("VENDA_A_PRAZO_SEM_DEVOLUCAO"));
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", c.venda())).isZero();
    }

    @Test
    void leitorDaAutorizacaoRecusaVendaQueVoltouADever() throws Exception {
        APrazo c = vendaQueVoltouADever();
        postar("/api/vendedor/autorizacoes", como(c.vendedor()), mapa("id", UUID.randomUUID(), "vendaId", c.venda(),
                "motivo", "ARREPENDIMENTO",
                "itens", List.of(mapa("produtoId", c.peca(), "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("VENDA_A_PRAZO_SEM_DEVOLUCAO"));
    }

    @Test
    void leitorDaListaDeVendasComAPecaMarcaAVendaAPrazoDepoisDoEstorno() throws Exception {
        APrazo c = vendaAPrazo("100.00", null, 2);
        UUID pagamento = receberOk(c, "100.00");
        assertThat(aPrazoNaLista(c)).isFalse();
        estornarRecebimento(c, pagamento, UUID.randomUUID(), "Erro").andExpect(status().isCreated());
        assertThat(aPrazoNaLista(c)).isTrue();
    }

    private boolean aPrazoNaLista(APrazo c) throws Exception {
        JsonNode lista = corpoDe(ler("/api/vendedor/devolucoes/vendas-com-a-peca?produtoId=" + c.peca(), como(c.vendedor()))
                .andExpect(status().isOk()));
        for (JsonNode venda : lista.get("vendas")) {
            if (venda.get("vendaId").asString().equals(c.venda().toString())) {
                return venda.get("aPrazo").asBoolean();
            }
        }
        throw new AssertionError("a venda não apareceu na lista");
    }

    @Test
    void vendaAPrazoJaQuitadaPodeSerDevolvidaEAConferenciaNaoAcusaNada() throws Exception {
        APrazo c = vendaAPrazo("100.00", null, 2);
        receberOk(c, "100.00");
        postar("/api/vendedor/devolucoes", como(c.vendedor()), mapa("id", UUID.randomUUID(), "vendaId", c.venda(),
                "motivo", "ARREPENDIMENTO", "formaReembolso", "PIX",
                "itens", List.of(mapa("produtoId", c.peca(), "quantidade", 1, "estado", "BOA")))).andExpect(status().isCreated());
        JsonNode conferencia = corpoDe(ler("/api/dona/relatorios/devolucoes/conferencia", dona()).andExpect(status().isOk()));
        for (JsonNode cada : conferencia.get("conferencias")) {
            for (JsonNode divergencia : cada.get("divergencias")) {
                assertThat(divergencia.get("referencia").asString()).as(cada.get("chave").asString()).doesNotContain(c.venda().toString());
            }
        }
    }

    @Test
    void leitorDoRelatorioDeRecebimentosSomaOEstornoComoValorNegativo() throws Exception {
        APrazo c = vendaAPrazo("100.00", null, 2);
        UUID pagamento = receberOk(c, "30.00");
        BigDecimal antes = totalDoPix(c);
        estornarRecebimento(c, pagamento, UUID.randomUUID(), "Erro").andExpect(status().isCreated());
        assertThat(totalDoPix(c)).isEqualByComparingTo(antes.subtract(new BigDecimal("30.00")));
    }

    private BigDecimal totalDoPix(APrazo c) throws Exception {
        JsonNode r = corpoDe(ler("/api/vendedor/relatorios/recebimentos", como(c.vendedor())).andExpect(status().isOk()));
        for (JsonNode linha : r.get("linhas")) {
            if ("PIX".equals(linha.get("id").asString())) {
                return new BigDecimal(linha.get("total").asString());
            }
        }
        return BigDecimal.ZERO;
    }
}
