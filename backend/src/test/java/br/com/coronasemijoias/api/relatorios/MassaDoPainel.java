package br.com.coronasemijoias.api.relatorios;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.acertos.ServicoDeAcertos;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.PedidoDeInicio;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.PedidoDeLeitura;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.Quem;
import br.com.coronasemijoias.api.acertos.ServicoDeFechamento;
import br.com.coronasemijoias.api.acertos.ServicoDeFechamento.DecisaoDeFalta;
import br.com.coronasemijoias.api.acertos.ServicoDeFechamento.PagamentoDoAcerto;
import br.com.coronasemijoias.api.acertos.ServicoDeFechamento.PedidoDeFechamento;
import br.com.coronasemijoias.api.acertos.ServicoDeFechamento.RecebimentoNoAcerto;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * A massa do painel semanal (tarefa 8.7): uma semana com quatro vendedores, montada pelos serviços de verdade (maleta pela API,
 * venda, devolução, troca, acerto e fechamento pelos serviços, com o relógio parado no dia de cada passo), tudo de 2292 e com
 * a conta feita à mão em {@link PainelSemanalTest}. Cada classe de teste usa um mês e um número de semana próprios (a semana não
 * pode se sobrepor à de outra classe) e as mesmas contas.
 */
abstract class MassaDoPainel extends MassaDoResultado {

    @Autowired
    protected ServicoDeAcertos servicoDeAcertos;

    @Autowired
    protected ServicoDeFechamento servicoDeFechamento;

    protected String mes;
    protected UUID semana;
    protected UUID pracaNorte;
    protected UUID pracaSul;
    protected UUID pecaA;
    protected UUID pecaB;
    protected UsuarioDeTeste ana;
    protected UsuarioDeTeste bia;
    protected UsuarioDeTeste cris;
    protected UsuarioDeTeste dani;
    protected UUID m1;
    protected UUID r1;
    protected UUID m2;
    protected UUID m3;
    protected UUID m4;
    protected UUID s1;
    protected UUID s2;
    protected UUID acertoAna;
    protected UUID acertoBia;
    protected UUID acertoDani;
    protected UUID cotacaoDaMassa;

    protected String d(int dia) {
        return mes + "-" + (dia < 10 ? "0" + dia : String.valueOf(dia));
    }

    /**
     * Monta a semana: de dia 04 a dia 10 do mês, Guarani a R$ 0,001 e Dólar a R$ 5,00, meta de 300.000 Gs. com 30% e 40%.
     * Peças: A a 50,00 e B a 25,00.
     */
    protected void montarAMassa(String anoMes, int numeroDaSemana) throws Exception {
        this.mes = anoMes;
        cotacao(d(4), "5.00000000", "0.00100000");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 300000 WHERE chave = 'comissao_meta_guarani'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 30 WHERE chave = 'comissao_percentual_abaixo_da_meta'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 40 WHERE chave = 'comissao_percentual_na_meta'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 90 WHERE chave = 'prazo_arrependimento_dias'");

        semana = criarSemana(numeroDaSemana, d(4), d(10));
        UUID categoria = categoria();
        pecaA = pecaComCusto(categoria, "50.00", "10.00");
        pecaB = pecaComCusto(categoria, "25.00", "5.00");
        entrada(pecaA, 60);
        entrada(pecaB, 60);

        pracaNorte = praca();
        pracaSul = praca();
        ana = vendedorNaPraca(pracaNorte);
        bia = vendedorNaPraca(pracaSul);
        cris = vendedorNaPraca(pracaNorte);
        dani = vendedorNaPraca(pracaSul);
        UUID clienteAna = cliente(ana);
        UUID clienteBia = cliente(bia);
        UUID clienteCris = cliente(cris);
        UUID clienteDani = cliente(dani);

        // As maletas (enviadas no dia, com a cotação do dia do envio).
        m1 = maletaDaSemana(ana, pracaNorte, "NOVA", 4, semana, pecaA, 10);
        r1 = maletaDaSemana(ana, pracaNorte, "REPOSICAO", 5, semana, pecaB, 4);
        m2 = maletaDaSemana(bia, pracaSul, "NOVA", 4, semana, pecaA, 5);
        m4 = maletaDaSemana(dani, pracaSul, "NOVA", 5, semana, pecaA, 4);
        m3 = maletaDaSemana(cris, pracaNorte, "NOVA", 6, semana, pecaB, 6);

        // Ana: 6 x A (300,00) e 2 x B com desconto de 10,00 (40,00).
        s1 = vender(ana, m1, clienteAna, d(6), "300.00", new LinhaDoPedido(pecaA, 6, null));
        limiteDeDesconto(20);
        s2 = vender(ana, r1, clienteAna, d(7), "40.00", new LinhaDoPedido(pecaB, 2, new BigDecimal("10.00")));
        limiteDeDesconto(0);

        // Bia: 1 x A (50,00) e 2 x A (100,00), com 1 x A devolvida pelo cliente (50,00).
        vender(bia, m2, clienteBia, d(6), "50.00", new LinhaDoPedido(pecaA, 1, null));
        UUID s4 = vender(bia, m2, clienteBia, d(6), "100.00", new LinhaDoPedido(pecaA, 2, null));
        devolverPeca(bia, s4, pecaA, 1, d(7));

        // Cris (maleta em aberto): 4 x B (100,00); troca de 2 x B por 1 x B (S6, 25,00): sobram 25,00 de crédito para o cliente.
        UUID s5 = vender(cris, m3, clienteCris, d(7), "100.00", new LinhaDoPedido(pecaB, 4, null));
        trocarPeloServico(cris, pedidoDoServico(s5, "ARREPENDIMENTO", List.of(new PecaDoPedido(pecaB, 2, "BOA")), null, m3,
                List.of(new LinhaDoPedido(pecaB, 1, null)), List.of()), dia(d(8)), ModoDaDevolucao.ONLINE);

        // Dani: 2 x A (100,00); o acerto começa no dia 10 e a contagem acha 1 x A (falta de 1).
        vender(dani, m4, clienteDani, d(7), "100.00", new LinhaDoPedido(pecaA, 2, null));

        // Acerto de Ana (dia 09): contou 4 x A e 1 x B (falta de 1 x B, cobrada). Recebeu 150,00 em dinheiro e 79,00 por Transferência.
        acertoAna = iniciarContando(ana, d(9), 11, List.of(pecaA, 4, pecaB, 1));
        fecharAcerto(acertoAna, d(9), 12, "229.00", List.of(new DecisaoDeFalta(pecaB, "COBRAR_NO_ACERTO", null)),
                List.of(new RecebimentoNoAcerto(UUID.randomUUID(), "DINHEIRO", new BigDecimal("150.00")),
                        new RecebimentoNoAcerto(UUID.randomUUID(), "PIX", new BigDecimal("79.00"))));
        // Acerto de Bia (dia 10): contou 3 x A. Recebeu 70,00 por Transferência (o boleto do fechamento é conta a receber).
        acertoBia = iniciarContando(bia, d(10), 11, List.of(pecaA, 3));
        fecharAcerto(acertoBia, d(10), 12, "70.00", List.of(), List.of(new RecebimentoNoAcerto(UUID.randomUUID(), "PIX",
                new BigDecimal("70.00"))));
        // Acerto de Dani (dia 10): em andamento, com 1 x A contado.
        acertoDani = iniciarContando(dani, d(10), 13, List.of(pecaA, 1));
    }

    /** Uma semana de 7 dias, criada pela rota da dona. */
    protected UUID criarSemana(int numero, String inicio, String fim) throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", numero, "inicio", inicio, "fim", fim))
                .andExpect(status().isCreated())).get("id").asString());
    }

    protected void entrada(UUID produto, int quantidade) {
        estoque.registrar(new Pedido(null, produto, TipoDeMovimento.ENTRADA, quantidade, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
    }

    /** Maleta da semana: montada e enviada pela dona, com a data do envio posta no dia, e conferida pelo vendedor. */
    protected UUID maletaDaSemana(UsuarioDeTeste v, UUID praca, String tipo, int diaDoEnvio, UUID daSemana, Object... pares)
            throws Exception {
        JsonNode r = corpoDe(postar(REMESSAS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", v.vendedorId(), "semanaId", daSemana,
                "tipo", tipo, "pracaId", praca)).andExpect(status().isCreated()));
        for (int i = 0; i < pares.length; i += 2) {
            r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + pares[i]).with(dona())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapa("quantidade", pares[i + 1], "version", r.get("version").asInt()))))
                    .andExpect(status().isOk()));
        }
        UUID id = UUID.fromString(r.get("id").asString());
        postar(REMESSAS + "/" + id + "/enviar", dona(), null).andExpect(status().isCreated());
        jdbc.update("UPDATE corona.remessas SET enviada_em = ? WHERE id = ?", Timestamp.from(dia(d(diaDoEnvio))), id);
        List<Map<String, Object>> contadas = new ArrayList<>();
        for (int i = 0; i < pares.length; i += 2) {
            contadas.add(mapa("produtoId", pares[i], "contada", pares[i + 1]));
        }
        postar("/api/vendedor/maletas/" + id + "/conferencia", como(v), mapa("id", UUID.randomUUID(), "itens", contadas))
                .andExpect(status().isCreated());
        return id;
    }

    /** Inicia o acerto da dona na hora dada e conta as peças (produto, quantidade boa), com o relógio parado. */
    protected UUID iniciarContando(UsuarioDeTeste v, String dia, int hora, List<Object> contagem) {
        UUID acerto = UUID.randomUUID();
        Quem dona = new Quem(usuarios.dona().id(), null, null);
        parar(dia, hora);
        try {
            servicoDeAcertos.iniciar(dona, new PedidoDeInicio(acerto, v.vendedorId(), semana));
            for (int i = 0; i < contagem.size(); i += 2) {
                servicoDeAcertos.registrarLeitura(dona, acerto, new PedidoDeLeitura(UUID.randomUUID(), (UUID) contagem.get(i),
                        (Integer) contagem.get(i + 1), "BOA"));
            }
        } finally {
            relogio.reiniciar();
        }
        return acerto;
    }

    protected void fecharAcerto(UUID acerto, String dia, int hora, String liquidoMostrado, List<DecisaoDeFalta> faltas,
            List<RecebimentoNoAcerto> recebimentos) {
        parar(dia, hora);
        try {
            servicoDeFechamento.fechar(usuarios.dona().id(), acerto, new PedidoDeFechamento(UUID.randomUUID(), faltas, List.of(),
                    List.of(), List.of(), new BigDecimal(liquidoMostrado),
                    new PagamentoDoAcerto(recebimentos, null, null, List.of(), null, null)));
        } finally {
            relogio.reiniciar();
        }
    }

    private void parar(String dia, int hora) {
        relogio.fixar(brasilia(dia + "T" + (hora < 10 ? "0" + hora : String.valueOf(hora)) + ":00:00"));
    }

    protected void restaurarAComissao() {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 700000 WHERE chave = 'comissao_meta_guarani'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 30 WHERE chave = 'comissao_percentual_abaixo_da_meta'");
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 40 WHERE chave = 'comissao_percentual_na_meta'");
    }
}
