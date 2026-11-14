package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * As parcelas da venda a prazo (tarefa 7.3; ADR-0012, item 7; dúvidas 10 e 17): o servidor divide o que falta pagar, em
 * parcelas mensais, sem valor de parcela vindo do app. Escrito antes do código.
 */
class ParcelasDaVendaTest extends ApoioDasContas {

    private UsuarioDeTeste vendedor;
    private UUID cliente;

    private void cenario() throws Exception {
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
    }

    private static Map<String, Object> parcelamento(int quantidade, LocalDate primeiro) {
        return mapa("quantidade", quantidade, "primeiroVencimento", primeiro == null ? null : primeiro.toString());
    }

    /** Peça de {@code preco}, uma maleta e a venda de uma unidade com o pago na hora informado (nulo = nada). */
    private org.springframework.test.web.servlet.ResultActions pedir(String preco, String pago, Map<String, Object> parcelamento,
            UUID id) throws Exception {
        UUID peca = peca(preco, 3);
        UUID maleta = maleta(vendedor, peca, 3);
        Map<String, Object> corpo = mapa("id", id, "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(linha(peca, 1)),
                "pagamentos", pago == null ? List.of() : List.of(pagamento(pago, "DINHEIRO")));
        if (parcelamento != null) {
            corpo.put("parcelamento", parcelamento);
        }
        vendasDoTeste.add(id);
        return postar(VENDAS, como(vendedor), corpo);
    }

    private static List<String> valores(List<Map<String, Object>> parcelas) {
        return parcelas.stream().map(p -> ((BigDecimal) p.get("valor")).toPlainString()).toList();
    }

    @Test
    void cemEmTresViraTrintaETresTrintaETresETrintaEQuatro() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        LocalDate primeiro = hoje().plusDays(10);
        pedir("100.00", null, parcelamento(3, primeiro), venda).andExpect(status().isCreated());
        List<Map<String, Object>> p = parcelas(venda);
        assertThat(valores(p)).containsExactly("33.33", "33.33", "33.34");
        assertThat(p).extracting(x -> x.get("vencimento")).containsExactly(primeiro.toString(), primeiro.plusMonths(1).toString(),
                primeiro.plusMonths(2).toString());
        assertThat(p).allSatisfy(x -> {
            assertThat(((Number) x.get("total_parcelas")).intValue()).isEqualTo(3);
            assertThat(x.get("status")).isEqualTo("ABERTA");
            assertThat(x.get("cliente_id")).isEqualTo(cliente);
        });
    }

    @Test
    void oPagoNaHoraSaiDoQueSeDivide() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        pedir("100.00", "40.00", parcelamento(2, hoje()), venda).andExpect(status().isCreated());
        assertThat(valores(parcelas(venda))).containsExactly("30.00", "30.00");
    }

    @Test
    void semParcelamentoViraUmaParcelaUmMesDepoisDoDiaDaVenda() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        pedir("100.00", "40.00", null, venda).andExpect(status().isCreated());
        List<Map<String, Object>> p = parcelas(venda);
        assertThat(valores(p)).containsExactly("60.00");
        assertThat(p.get(0).get("vencimento")).isEqualTo(hoje().plusMonths(1).toString());
    }

    @Test
    void vendaPagaNaHoraSemParcelamentoNaoGeraParcela() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        pedir("100.00", "100.00", null, venda).andExpect(status().isCreated());
        assertThat(parcelas(venda)).isEmpty();
    }

    @Test
    void parcelamentoEmVendaJaPagaEhRecusado() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        pedir("100.00", "100.00", parcelamento(2, hoje()), venda).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].mensagem").value("Esta venda já está paga. Não há parcelas."));
        assertThat(contar("SELECT count(*) FROM corona.vendas WHERE id = ?", venda)).isZero();
    }

    @Test
    void valorPequenoDemaisParaAsParcelasEhRecusadoENadaFicaGravado() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        pedir("0.18", null, parcelamento(12, hoje()), venda).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].mensagem").value("O valor a prazo (R$ 0,18) é pequeno demais para 12 parcelas. Use menos parcelas."));
        assertThat(contar("SELECT count(*) FROM corona.vendas WHERE id = ?", venda)).isZero();
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ?", venda)).isZero();
        assertThat(parcelas(venda)).isEmpty();
    }

    @Test
    void quantidadeForaDe1a24ESemQuantidadeSaoRecusadas() throws Exception {
        cenario();
        for (Integer quantidade : new Integer[] {0, 25, -1, null}) {
            UUID venda = UUID.randomUUID();
            Map<String, Object> parcelamento = mapa("quantidade", quantidade, "primeiroVencimento", hoje().toString());
            pedir("100.00", null, parcelamento, venda).andExpect(status().isBadRequest());
            assertThat(contar("SELECT count(*) FROM corona.vendas WHERE id = ?", venda)).isZero();
        }
        UUID venda24 = UUID.randomUUID();
        pedir("100.00", null, parcelamento(24, hoje()), venda24).andExpect(status().isCreated());
        assertThat(parcelas(venda24)).hasSize(24);
    }

    @Test
    void primeiroVencimentoAntesDoDiaDaVendaOuLongeDemaisEhRecusado() throws Exception {
        cenario();
        pedir("100.00", null, parcelamento(2, hoje().minusDays(1)), UUID.randomUUID()).andExpect(status().isBadRequest());
        pedir("100.00", null, parcelamento(2, hoje().plusYears(1).plusDays(1)), UUID.randomUUID()).andExpect(status().isBadRequest());
        pedir("100.00", null, parcelamento(2, hoje().plusYears(1)), UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void ovencimentoContaSempreAPartirDaPrimeiraParcela() throws Exception {
        cenario();
        LocalDate janeiro = LocalDate.of(hoje().getYear(), 1, 31);
        if (janeiro.isBefore(hoje())) {
            janeiro = janeiro.plusYears(1);
        }
        UUID venda = UUID.randomUUID();
        pedir("100.00", null, parcelamento(3, janeiro), venda).andExpect(status().isCreated());
        List<Map<String, Object>> p = parcelas(venda);
        assertThat(p).extracting(x -> x.get("vencimento")).containsExactly(janeiro.toString(),
                janeiro.plusMonths(1).toString(), janeiro.plusMonths(2).toString());
        // Fevereiro tem menos dias, mas março volta ao dia 31: a conta é sempre a partir da primeira.
        assertThat(janeiro.plusMonths(1).getDayOfMonth()).isEqualTo(janeiro.plusMonths(1).lengthOfMonth());
        assertThat(janeiro.plusMonths(2).getDayOfMonth()).isEqualTo(31);
    }

    @Test
    void asParcelasSempreSomamOQueFaltaPagar() throws Exception {
        cenario();
        Random sorteio = new Random(7);
        List<String> precos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            precos.add((50 + sorteio.nextInt(900)) + "." + String.format("%02d", sorteio.nextInt(100)));
        }
        for (String preco : precos) {
            int quantidade = 1 + sorteio.nextInt(12);
            UUID venda = UUID.randomUUID();
            pedir(preco, "10.00", parcelamento(quantidade, hoje()), venda).andExpect(status().isCreated());
            BigDecimal soma = parcelas(venda).stream().map(x -> (BigDecimal) x.get("valor")).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(soma).isEqualByComparingTo(new BigDecimal(preco).subtract(new BigDecimal("10.00")));
            assertThat(parcelas(venda)).hasSize(quantidade);
        }
    }

    @Test
    void reenvioIgualNaoGeraParcelaNovaEParcelamentoDiferenteDa409() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        UUID peca = peca("100.00", 3);
        UUID maleta = maleta(vendedor, peca, 3);
        LocalDate primeiro = hoje().plusDays(5);
        Map<String, Object> corpo = mapa("id", venda, "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(linha(peca, 1)), "pagamentos", List.of(), "parcelamento", parcelamento(3, primeiro));
        vendasDoTeste.add(venda);
        postar(VENDAS, como(vendedor), corpo).andExpect(status().isCreated());
        postar(VENDAS, como(vendedor), corpo).andExpect(status().isOk());
        assertThat(parcelas(venda)).hasSize(3);
        corpo.put("parcelamento", parcelamento(2, primeiro));
        postar(VENDAS, como(vendedor), corpo).andExpect(status().isConflict());
        corpo.put("parcelamento", parcelamento(3, primeiro.plusDays(1)));
        postar(VENDAS, como(vendedor), corpo).andExpect(status().isConflict());
        assertThat(parcelas(venda)).hasSize(3);
    }

    @Test
    void oAppNuncaMandaValorDeParcela() throws Exception {
        cenario();
        UUID venda = UUID.randomUUID();
        // Campo desconhecido no parcelamento é ignorado: o valor continua sendo o do servidor.
        Map<String, Object> pedido = parcelamento(2, hoje());
        pedido.put("valor", "1.00");
        pedir("100.00", null, pedido, venda).andExpect(status().isCreated());
        assertThat(valores(parcelas(venda))).containsExactly("50.00", "50.00");
    }
}
