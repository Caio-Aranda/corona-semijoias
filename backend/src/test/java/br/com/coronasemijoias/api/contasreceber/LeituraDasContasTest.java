package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A leitura das contas a receber (tarefa 7.3): a ficha da venda mostra as parcelas, o vendedor vê só as dele, a dona vê
 * tudo com filtros, "vencida" é calculada pela data de hoje em Brasília (nunca gravada) e nada de custo. Escrito antes
 * do código.
 */
class LeituraDasContasTest extends ApoioDasContas {

    @Autowired
    ServicoDeVendas vendas;

    private List<String> ids(JsonNode lista) {
        List<String> ids = new ArrayList<>();
        lista.forEach(c -> ids.add(c.get("id").asString()));
        return ids;
    }

    private JsonNode doVendedor(APrazo c, String consulta) throws Exception {
        return corpoDe(ler("/api/vendedor/contas-receber" + consulta, como(c.vendedor())).andExpect(status().isOk()));
    }

    @Test
    void aFichaDaVendaMostraAsParcelasEOQueFalta() throws Exception {
        APrazo c = vendaDeCemEmTres();
        receberOk(c, "50.00");
        for (JsonNode ficha : List.of(
                corpoDe(ler("/api/vendedor/vendas/" + c.venda(), como(c.vendedor())).andExpect(status().isOk())),
                corpoDe(ler("/api/dona/vendas/" + c.venda(), dona()).andExpect(status().isOk())))) {
            JsonNode parcelas = ficha.get("parcelas");
            assertThat(parcelas).hasSize(3);
            assertThat(parcelas.get(0).get("numero").asInt()).isEqualTo(1);
            assertThat(parcelas.get(0).get("totalDeParcelas").asInt()).isEqualTo(3);
            assertThat(parcelas.get(0).get("status").asString()).isEqualTo("PAGA");
            assertThat(new BigDecimal(parcelas.get(1).get("emAberto").asString())).isEqualByComparingTo("16.66");
            assertThat(new BigDecimal(parcelas.get(1).get("valorPago").asString())).isEqualByComparingTo("16.67");
            assertThat(parcelas.get(2).get("vencida").asBoolean()).isFalse();
            assertThat(new BigDecimal(ficha.get("aReceber").asString())).isEqualByComparingTo("50.00");
        }
    }

    @Test
    void aFichaMarcaOsPagamentosPosterioresParaOAppSaberQuaisPodemSerEstornados() throws Exception {
        APrazo c = vendaAPrazo("100.00", "10.00", 2);
        receberOk(c, "20.00");
        JsonNode ficha = corpoDe(ler("/api/vendedor/vendas/" + c.venda(), como(c.vendedor())).andExpect(status().isOk()));
        List<Boolean> posteriores = new ArrayList<>();
        ficha.get("pagamentos").forEach(p -> posteriores.add(p.get("posterior").asBoolean()));
        assertThat(posteriores).containsExactly(false, true);
    }

    @Test
    void oVendedorVeSoAsParcelasDeleEFiltraPorSituacaoClienteEVenda() throws Exception {
        APrazo c = vendaDeCemEmTres();
        APrazo outra = vendaAPrazo("80.00", null, 2);
        receberOk(c, "40.00");
        JsonNode todas = doVendedor(c, "");
        assertThat(todas).hasSize(3);
        assertThat(todas).allSatisfy(x -> assertThat(x.get("vendaId").asString()).isEqualTo(c.venda().toString()));
        assertThat(doVendedor(c, "?situacao=PAGA")).hasSize(1);
        assertThat(doVendedor(c, "?situacao=EM_ABERTO")).hasSize(2);
        assertThat(doVendedor(c, "?situacao=CANCELADA")).isEmpty();
        assertThat(doVendedor(c, "?vendaId=" + c.venda())).hasSize(3);
        assertThat(doVendedor(c, "?clienteId=" + c.cliente())).hasSize(3);
        assertThat(doVendedor(c, "?clienteId=" + outra.cliente())).isEmpty();
        assertThat(doVendedor(c, "?vendaId=" + outra.venda())).isEmpty();
        assertThat(doVendedor(outra, "")).hasSize(2);
        ler("/api/vendedor/contas-receber?situacao=QUALQUER", como(c.vendedor())).andExpect(status().isBadRequest());
    }

    @Test
    void aRespostaDoVendedorNaoTemCustoNemDadosDeOutraPessoa() throws Exception {
        APrazo c = vendaDeCemEmTres();
        String texto = ler("/api/vendedor/contas-receber", como(c.vendedor())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().toLowerCase();
        assertThat(texto).doesNotContain("custo").doesNotContain("fornecedor").doesNotContain("vendedornome");
        String ficha = ler("/api/vendedor/vendas/" + c.venda(), como(c.vendedor())).andReturn().getResponse().getContentAsString()
                .toLowerCase();
        assertThat(ficha).doesNotContain("custo").doesNotContain("fornecedor");
    }

    @Test
    void vencidaECalculadaPelaDataDeHojeENuncaGravada() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UsuariosDeTeste.UsuarioDeTeste v = c.vendedor();
        // Uma venda antiga (pelo serviço, com a hora escolhida): a primeira parcela venceu há 20 dias e a segunda ainda não.
        UUID cliente = cliente(v);
        UUID peca = peca("60.00", 2);
        UUID maleta = maleta(v, peca, 2);
        UUID velha = UUID.randomUUID();
        vendasDoTeste.add(velha);
        Instant ha30dias = diasAtras(30);
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(velha, maleta, cliente,
                List.of(new LinhaDoPedido(peca, 1, null)), List.of(), null,
                new ServicoDeContasAReceber.Parcelamento(2, LocalDate.ofInstant(ha30dias, BRASILIA).plusDays(10))), ha30dias, false);
        JsonNode lista = doVendedor(c, "?vendaId=" + velha);
        assertThat(lista).hasSize(2);
        assertThat(lista.get(0).get("vencida").asBoolean()).isTrue();
        assertThat(lista.get(0).get("status").asString()).isEqualTo("ABERTA");
        assertThat(lista.get(1).get("vencida").asBoolean()).isFalse();
        assertThat(doVendedor(c, "?situacao=VENCIDA&vendaId=" + velha)).hasSize(1);
        assertThat(doVendedor(c, "?situacao=VENCIDA&vendaId=" + c.venda())).isEmpty();
        // O banco nunca guarda "vencida".
        assertThat(contar("SELECT count(*) FROM corona.contas_receber WHERE status NOT IN ('ABERTA', 'PARCIAL', 'PAGA', 'CANCELADA')"))
                .isZero();
        // Parcela vencida paga deixa de ser vencida.
        postar(RECEBIMENTOS.formatted(velha), como(v), mapa("id", UUID.randomUUID(), "valor", "30.00", "forma", "DINHEIRO"))
                .andExpect(status().isCreated());
        assertThat(doVendedor(c, "?situacao=VENCIDA&vendaId=" + velha)).isEmpty();
    }

    @Test
    void aDonaVeTudoComFiltrosPorVendedorClienteESituacao() throws Exception {
        APrazo a = vendaDeCemEmTres();
        APrazo b = vendaAPrazo("70.00", null, 2);
        receberOk(a, "100.00");
        JsonNode todas = corpoDe(ler("/api/dona/contas-receber", dona()).andExpect(status().isOk()));
        assertThat(ids(todas)).hasSizeGreaterThanOrEqualTo(5);
        JsonNode doA = corpoDe(ler("/api/dona/contas-receber?vendedorId=" + a.vendedor().vendedorId(), dona()).andExpect(status().isOk()));
        assertThat(doA).hasSize(3);
        assertThat(doA.get(0).get("vendedorNome").asString()).isNotBlank();
        assertThat(doA.get(0).get("clienteNome").asString()).isNotBlank();
        assertThat(corpoDe(ler("/api/dona/contas-receber?vendedorId=" + a.vendedor().vendedorId() + "&situacao=PAGA", dona())))
                .hasSize(3);
        assertThat(corpoDe(ler("/api/dona/contas-receber?clienteId=" + b.cliente() + "&situacao=EM_ABERTO", dona()))).hasSize(2);
        assertThat(corpoDe(ler("/api/dona/contas-receber?clienteId=" + b.cliente() + "&situacao=PAGA", dona()))).isEmpty();
        ler("/api/dona/contas-receber?situacao=QUALQUER", dona()).andExpect(status().isBadRequest());
    }

    @Test
    void parcelaDeVendaEstornadaApareceCanceladaENaoVencida() throws Exception {
        APrazo c = vendaDeCemEmTres();
        postar(VENDAS + "/" + c.venda() + "/estorno", como(c.vendedor()), mapa("id", UUID.randomUUID(), "motivo", "Desistiu"))
                .andExpect(status().isCreated());
        JsonNode lista = doVendedor(c, "?vendaId=" + c.venda());
        assertThat(lista).allSatisfy(x -> {
            assertThat(x.get("status").asString()).isEqualTo("CANCELADA");
            assertThat(x.get("vencida").asBoolean()).isFalse();
        });
        assertThat(doVendedor(c, "?situacao=EM_ABERTO&vendaId=" + c.venda())).isEmpty();
    }
}
