package br.com.coronasemijoias.api.conciliacoes;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;

/**
 * A massa completa das conciliações (tarefa 7.8), montada UMA vez por classe: dois vendedores acima e abaixo da meta (40% e 30%),
 * venda estornada, falta cobrada, devolução atrasada que entra no acerto seguinte, falta que a loja assume e defeito aprovado,
 * recebimentos do vendedor (um avulso e estornado, um boleto pago depois, o do acerto estornado), acerto a favor do vendedor pago
 * na hora e outro como crédito, venda a prazo com parcela paga em duas vezes, venda a prazo estornada, compras com contas a pagar
 * pagas em parcelas, pagamento a fornecedor estornado, compra desfeita com parcela cancelada, lançamento manual e o estorno dele.
 */
abstract class MassaDaFase7 extends ApoioDasConciliacoes {

    UsuarioDeTeste acima;
    UsuarioDeTeste abaixo;
    UsuarioDeTeste x;
    UsuarioDeTeste y;
    UsuarioDeTeste z;
    UsuarioDeTeste w;

    UUID acertoAcima;
    UUID acertoAbaixoSeguinte;
    UUID vendaDe100;
    UUID vendaEstornada;
    UUID vendaDoAbaixo;
    UUID devolucaoDoAbaixo;
    UUID acertoDoW;
    UUID vendaAPrazoPaga;
    UUID vendaAPrazoAberta;
    UUID vendaAPrazoEstornada;
    UUID compraDasParcelas;
    UUID compraDesfeita;
    UUID contaAbertaDaCompra;
    UUID pagamentoEstornado;
    UUID lancamentoManual;

    @BeforeAll
    void montarAMassa() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        montarOsAcertos();
        montarODinheiroDoVendedor();
        montarAsVendasAPrazo();
        montarAsContasAPagar();
    }

    private void montarOsAcertos() throws Exception {
        acima = novoVendedor();
        abaixo = novoVendedor();
        UUID peca = peca("50.00", 80);

        // Acima da meta (40%): venda de 100, venda estornada, falta cobrada no acerto.
        configurarComissao(1, 30, 40);
        UUID c1 = cliente(acima);
        UUID m1 = maletaConferida(acima, semana(), peca, 5);
        vendaDe100 = vender(acima, m1, c1, peca, 2, "100.00");
        vendaEstornada = vender(acima, m1, c1, peca, 1, "50.00");
        postarDona("/api/dona/vendas/" + vendaEstornada + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "erro"))
                .andExpect(status().isCreated());
        acertoAcima = iniciar(acima);
        bipar(acertoAcima, peca, 2, "BOA");
        fechar(acertoAcima, pedidoDeFechamento(List.of(decisaoDeFalta(peca, "COBRAR_NO_ACERTO", null)), List.of(), "110.00"))
                .andExpect(status().isCreated());

        // Abaixo da meta (30%): uma venda e uma devolução atrasada, que entra no acerto seguinte.
        configurarComissao(700_000, 30, 40);
        UUID c2 = cliente(abaixo);
        UUID m2 = maletaConferida(abaixo, semana(), peca, 4);
        vendaDoAbaixo = vender(abaixo, m2, c2, peca, 2, "100.00");
        UUID acerto2 = iniciar(abaixo);
        bipar(acerto2, peca, 2, "BOA");
        fechar(acerto2, pedidoDeFechamento(List.of(), List.of(), "70.00")).andExpect(status().isCreated());
        devolucaoDoAbaixo = UUID.randomUUID();
        postarDona("/api/dona/devolucoes", mapa("id", devolucaoDoAbaixo, "vendaId", vendaDoAbaixo, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "DINHEIRO", "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isCreated());
        maletaConferida(abaixo, semana(), peca, 2);
        acertoAbaixoSeguinte = iniciar(abaixo);
        bipar(acertoAbaixoSeguinte, peca, 3, "BOA");
        fechar(acertoAbaixoSeguinte, pedidoDeFechamento(List.of(), List.of(), "-35.00")).andExpect(status().isCreated());

        // Falta que a loja assume e defeito aprovado.
        w = novoVendedor();
        UUID pecaDoW = peca("50.00", 20);
        UUID cw = cliente(w);
        UUID mw = maletaConferida(w, semana(), pecaDoW, 6);
        vender(w, mw, cw, pecaDoW, 1, "50.00");
        acertoDoW = iniciar(w);
        bipar(acertoDoW, pecaDoW, 3, "BOA");
        bipar(acertoDoW, pecaDoW, 1, "DEFEITO");
        fechar(acertoDoW, pedidoDeFechamento(List.of(decisaoDeFalta(pecaDoW, "LOJA_ASSUME", "perda da loja")),
                List.of(defeitoAprovado(pecaDoW, 1)), "35.00")).andExpect(status().isCreated());
    }

    private UUID acertoDe35(UsuarioDeTeste v, UUID cliente, Map<String, Object> pagamento) throws Exception {
        UUID peca = peca("50.00", 30);
        UUID maleta = maletaConferida(v, semana(), peca, 4);
        vender(v, maleta, cliente, peca, 1, "50.00");
        UUID acerto = iniciar(v);
        bipar(acerto, peca, 3, "BOA");
        var pedido = pedidoDeFechamento(List.of(), List.of(), "35.00");
        pedido.remove("pagamento");
        if (pagamento != null) {
            pedido.put("pagamento", pagamento);
        }
        fechar(acerto, pedido).andExpect(status().isCreated());
        return acerto;
    }

    private void montarODinheiroDoVendedor() throws Exception {
        x = novoVendedor();
        y = novoVendedor();
        UUID cx = cliente(x);
        var restante = mapa("parcelas", 1, "primeiroVencimento", hoje().plusDays(30).toString());
        UUID acerto1 = acertoDe35(x, cx, mapa(
                "recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "valor", "10.00"),
                        mapa("id", UUID.randomUUID(), "forma", "PIX", "valor", "5.00")),
                "boleto", mapa("valor", "10.00", "vencimento", hoje().plusDays(10).toString()), "restante", restante));
        UUID boleto = umId("SELECT id FROM corona.contas_receber WHERE origem_id = ? AND forma_prevista = 'BOLETO'", acerto1);
        UUID comum = umId("SELECT id FROM corona.contas_receber WHERE origem_id = ? AND forma_prevista IS NULL", acerto1);
        UUID avulso = UUID.randomUUID();
        postarDona("/api/dona/recebimentos-vendedor", mapa("id", avulso, "vendedorId", x.vendedorId(), "forma", "DINHEIRO",
                "valor", "4.00", "contaId", comum)).andExpect(status().isCreated());
        postarDona("/api/dona/recebimentos-vendedor/" + avulso + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "Lancei errado"))
                .andExpect(status().isCreated());
        postarDona("/api/dona/recebimentos-vendedor", mapa("id", UUID.randomUUID(), "vendedorId", x.vendedorId(), "forma", "BOLETO",
                "valor", "10.00", "contaId", boleto)).andExpect(status().isCreated());
        UUID dinheiroDoAcerto2 = UUID.randomUUID();
        acertoDe35(x, cx, mapa("recebimentos", List.of(mapa("id", dinheiroDoAcerto2, "forma", "DINHEIRO", "valor", "35.00")),
                "contasAntigas", List.of(mapa("id", UUID.randomUUID(), "contaId", comum, "forma", "PIX", "valor", "3.00"))));
        postarDona("/api/dona/recebimentos-vendedor/" + dinheiroDoAcerto2 + "/estorno",
                mapa("id", UUID.randomUUID(), "motivo", "Foi engano")).andExpect(status().isCreated());

        // Y: acerto a favor pago na hora e, depois, um acerto a favor como crédito.
        UUID cy = cliente(y);
        UUID peca = peca("50.00", 30);
        UUID maleta = maletaConferida(y, semana(), peca, 3);
        UUID venda = vender(y, maleta, cy, peca, 1, "50.00");
        UUID a0 = iniciar(y);
        bipar(a0, peca, 2, "BOA");
        fechar(a0, pedidoDeFechamento(List.of(), List.of(), "35.00")).andExpect(status().isCreated());
        postarDona("/api/dona/devolucoes", mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "DINHEIRO", "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isCreated());
        UUID a1 = iniciar(y, semana());
        bipar(a1, peca, 1, "BOA");
        var pedido = pedidoDeFechamento(List.of(), List.of(), "-35.00");
        pedido.put("pagamento", mapa("aFavorDoVendedor", "PAGAR_NA_HORA", "formaDoPagamento", "DINHEIRO"));
        fechar(a1, pedido).andExpect(status().isCreated());
    }

    private void montarAsVendasAPrazo() throws Exception {
        z = novoVendedor();
        UUID cz = cliente(z);
        UUID peca = peca("100.00", 30);
        UUID maleta = maletaConferida(z, semana(), peca, 10);
        vendaAPrazoPaga = venderAPrazo(z, maleta, cz, peca, 1, null, 3);
        for (String valor : List.of("40.00", "30.00")) {
            postar("/api/vendedor/vendas/" + vendaAPrazoPaga + "/recebimentos", como(z),
                    mapa("id", UUID.randomUUID(), "valor", valor, "forma", "PIX")).andExpect(status().isCreated());
        }
        vendaAPrazoAberta = venderAPrazo(z, maleta, cz, peca, 1, "10.00", 2);
        vendaAPrazoEstornada = venderAPrazo(z, maleta, cz, peca, 1, null, 2);
        postarDona("/api/dona/vendas/" + vendaAPrazoEstornada + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "Desistiu"))
                .andExpect(status().isCreated());
    }

    private void montarAsContasAPagar() throws Exception {
        UUID fornecedor = fornecedor();
        compraDasParcelas = compra(fornecedor, "300.00");
        List<UUID> c = contasDe(compraDasParcelas, 3);
        pagar(c.get(0), "100.00", "PIX");
        pagar(c.get(1), "50.00", "PIX");
        pagamentoEstornado = pagar(c.get(1), "50.00", "DINHEIRO");
        postarDona(CONTAS + "/pagamentos/" + pagamentoEstornado + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "Paguei errado"))
                .andExpect(status().isCreated());
        contaAbertaDaCompra = c.get(2);

        compraDesfeita = compra(fornecedor, "40.00");
        List<UUID> d = contasDe(compraDesfeita, 2);
        pagar(d.get(0), "10.00", "DINHEIRO");
        postarDona(COMPRAS + "/" + compraDesfeita + "/desfazer", mapa("id", UUID.randomUUID(), "motivo", "Veio errada"))
                .andExpect(status().isCreated());

        UUID categoria = UUID.randomUUID();
        postarDona(CAIXA + "/categorias", mapa("id", categoria, "nome", "Cat " + categoria, "tipo", "SAIDA")).andExpect(status().isCreated());
        lancamentoManual = UUID.randomUUID();
        lancamentosManuais.add(lancamentoManual);
        postarDona(CAIXA + "/lancamentos", mapa("id", lancamentoManual, "tipo", "SAIDA", "valor", "12.50", "forma", "DINHEIRO",
                "categoriaId", categoria, "data", "2020-01-15", "descricao", "Teste")).andExpect(status().isCreated());
        UUID estorno = UUID.randomUUID();
        lancamentosManuais.add(estorno);
        postarDona(CAIXA + "/lancamentos/" + lancamentoManual + "/estorno", mapa("id", estorno, "motivo", "Lancei duas vezes"))
                .andExpect(status().isCreated());
    }
}
