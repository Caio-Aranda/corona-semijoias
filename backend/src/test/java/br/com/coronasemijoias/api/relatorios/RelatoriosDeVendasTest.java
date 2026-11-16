package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Relatórios de vendas da dona e curva ABC (tarefa 8.5; §12; ADR-0015, item 4). Massa conhecida, com tudo calculado à mão,
 * em março de 2292 (nenhum outro teste usa esse mês); as cotações de 2292 são as do teste:
 *
 * <pre>
 * 04/03/2292: US$ 5,00 e Gs. 0,001 real (R$ 1 = Gs. 1.000)    05/03/2292: US$ 4,00 e Gs. 0,002 real (R$ 1 = Gs. 500)
 *
 * Peças: P1 100 e P2 80 (categoria X); P3 130, P4 50 e P5 20 (categoria Y).
 * Vendedor V1 (praça PA), clientes A e B:
 *   venda 1 (04/03, cliente A): 3 x P1 = 300            venda 2 (04/03, cliente A): 2 x P2 + 1 x P5 = 160 + 20 = 180
 *   venda 3 (05/03, cliente B): 1 x P3 = 130
 *   devolução 1 (05/03): venda 1, 1 x P1 = 100          devolução 3 (05/03): venda 2, 1 x P2 = 80, CANCELADA (não conta)
 *   troca (05/03): venda 3 (P3, 130) por 1 x P4 = 50 (venda 6): a devolução da troca é 130 e a venda 6 vale 50
 * Vendedor V2 (praça PB), cliente C:
 *   venda 4 (04/03): 2 x P1 + 2 x P4 com R$ 10,00 de desconto na linha de P4 = 200 + 90 = 290
 *   devolução 2 (05/03): venda 4, 1 x P4 = 45 (metade de 90: o preço pago, não o de tabela)
 *   venda 5 (05/03): 1 x P1 = 100, ESTORNADA no mesmo dia (fora de todos os totais, mas aparece em "canceladas")
 *
 * Vendido líquido de cada linha de peça = vendido (preço x quantidade - desconto) - devolvido (devoluções não canceladas):
 *   v1/P1 300-100 = 200   v2/P2 160   v2/P5 20   v3/P3 130-130 = 0   v6/P4 50   v4/P1 200   v4/P4 90-45 = 45
 *   total: vendido 950, devolvido 275, líquido 675.   Dólar e Guarani pela cotação do dia da venda, linha a linha.
 * </pre>
 *
 * Todas as chamadas filtram o mês da massa; o banco guarda lançamentos de outros testes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RelatoriosDeVendasTest extends ApoioDasTrocas {

    private static final String REL = "/api/dona/relatorios/vendas/";
    private static final String CURVA = "/api/dona/relatorios/estoque/curva-abc";
    private static final String MES = "de=2292-03-01&ate=2292-03-31";

    private static final Instant D4 = brasilia("2292-03-04T12:00:00");
    private static final Instant D5_10H = brasilia("2292-03-05T10:00:00");
    private static final Instant D5_11H = brasilia("2292-03-05T11:00:00");
    private static final Instant D5_14H = brasilia("2292-03-05T14:00:00");
    private static final Instant D5_16H = brasilia("2292-03-05T16:00:00");

    private static final List<String> ROTAS = List.of("vendas", "canceladas", "por-vendedor", "por-praca", "por-cliente",
            "por-produto", "por-categoria", "por-periodo", "ranking-de-vendedores", "ranking-de-pecas", "ticket-medio");

    private UsuarioDeTeste v1;
    private UsuarioDeTeste v2;
    private UUID pracaA;
    private UUID pracaB;
    private UUID clienteA;
    private UUID clienteB;
    private UUID clienteC;
    private UUID categoriaX;
    private UUID categoriaY;
    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID p4;
    private UUID p5;
    private UUID venda1;
    private UUID venda2;
    private UUID venda3;
    private UUID venda4;
    private UUID venda5;
    private UUID venda6;

    @BeforeAll
    void massaConhecida() throws Exception {
        cotacao("2292-03-04", "5.00000000", "0.00100000");
        cotacao("2292-03-05", "4.00000000", "0.00200000");

        categoriaX = categoria();
        categoriaY = categoria();
        p1 = peca(categoriaX, "100.00");
        p2 = peca(categoriaX, "80.00");
        p3 = peca(categoriaY, "130.00");
        p4 = peca(categoriaY, "50.00");
        p5 = peca(categoriaY, "20.00");
        pracaA = praca();
        pracaB = praca();

        v1 = usuarios.criarVendedor();
        jdbc.update("UPDATE corona.vendedores SET praca_id = ? WHERE id = ?", pracaA, v1.vendedorId());
        clienteA = cliente(v1);
        clienteB = cliente(v1);
        UUID maletaV1 = maleta(v1, p1, 8, p2, 8, p3, 8, p4, 8, p5, 8);
        venda1 = vender(v1, maletaV1, clienteA, D4, "300.00", new LinhaDoPedido(p1, 3, null));
        venda2 = vender(v1, maletaV1, clienteA, D4, "180.00", new LinhaDoPedido(p2, 2, null), new LinhaDoPedido(p5, 1, null));
        venda3 = vender(v1, maletaV1, clienteB, D4.plusSeconds(86_400), "130.00", new LinhaDoPedido(p3, 1, null));
        UUID dev1 = devolver(v1, venda1, "ARREPENDIMENTO", p1, D5_10H);
        UUID dev3 = devolver(v1, venda2, "TAMANHO", p2, D5_11H);
        devolucoes.cancelar(dev3, v1.vendedorId(), v1.id(), usuarios.aparelhoDe(v1), UUID.randomUUID(), "O cliente voltou atrás",
                D5_16H);
        assertThat(dev1).isNotNull();
        var troca = pedidoDoServico(venda3, "ARREPENDIMENTO", List.of(new PecaDoPedido(p3, 1, "BOA")), null, maletaV1,
                List.of(new LinhaDoPedido(p4, 1, null)), List.of());
        venda6 = vendaNovaDaTroca(troca);
        trocarPeloServico(v1, troca, D5_14H, ModoDaDevolucao.ONLINE);

        v2 = usuarios.criarVendedor();
        jdbc.update("UPDATE corona.vendedores SET praca_id = ? WHERE id = ?", pracaB, v2.vendedorId());
        clienteC = cliente(v2);
        UUID maletaV2 = maleta(v2, p1, 8, p4, 8);
        limiteDeDesconto(10);
        venda4 = vender(v2, maletaV2, clienteC, D4, "290.00", new LinhaDoPedido(p1, 2, null),
                new LinhaDoPedido(p4, 2, new BigDecimal("10.00")));
        limiteDeDesconto(0);
        devolver(v2, venda4, "ARREPENDIMENTO", p4, D5_11H);
        venda5 = vender(v2, maletaV2, clienteC, D5_10H, "100.00", new LinhaDoPedido(p1, 1, null));
        vendas.estornar(venda5, v2.vendedorId(), v2.id(), usuarios.aparelhoDe(v2), UUID.randomUUID(), "O cliente desistiu na hora",
                D5_16H);
    }

    /** Como no teste de devolução: a tabela de cotações é imutável, então as de 2292 saem no fim, pelo dono das tabelas. */
    @AfterAll
    void tirarAsCotacoesDaMassa() throws SQLException {
        List<String> tabelas = List.of("vendas", "devolucoes", "autorizacoes_excecao", "trocas", "creditos_cliente", "cotacoes");
        String minhas = "(SELECT id FROM corona.cotacoes WHERE data_cotacao IN ('2292-03-04', '2292-03-05'))";
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            for (String t : tabelas) {
                s.execute("ALTER TABLE corona." + t + " DISABLE TRIGGER USER");
            }
            try {
                for (String t : tabelas.subList(0, tabelas.size() - 1)) {
                    s.execute("UPDATE corona." + t + " SET cotacao_id = NULL WHERE cotacao_id IN " + minhas);
                }
                s.execute("DELETE FROM corona.cotacoes WHERE data_cotacao IN ('2292-03-04', '2292-03-05')");
            } finally {
                for (String t : tabelas) {
                    s.execute("ALTER TABLE corona." + t + " ENABLE TRIGGER USER");
                }
            }
            con.commit();
        }
    }

    // --- apoio ---

    private void cotacao(String dia, String usd, String pyg) {
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
                VALUES (uuidv7(), ?::date, ?::numeric, ?::numeric, 1, 1, ?::numeric, ?::numeric, 1, 1, now(), 'teste')
                ON CONFLICT ON CONSTRAINT cotacoes_data_unica DO NOTHING""", dia, usd, usd, pyg, pyg);
    }

    private UUID categoria() throws Exception {
        return id(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + UUID.randomUUID()))
                .andExpect(status().isCreated())));
    }

    private UUID praca() throws Exception {
        return id(corpoDe(postar("/api/dona/pracas", dona(), mapa("nome", "Praça " + UUID.randomUUID()))
                .andExpect(status().isCreated())));
    }

    private UUID peca(UUID categoria, String preco) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID id = id(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Colar " + sufixo, "categoriaId", categoria,
                "precoVenda", preco, "precoCusto", "1.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "VD-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())));
        estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, 20, null, OrigemDoMovimento.MANUAL, null),
                usuarios.dona().id());
        return id;
    }

    private UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, Instant quando, String total, LinhaDoPedido... linhas) {
        UUID id = UUID.randomUUID();
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(id, maleta, cliente, List.of(linhas),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal(total), "DINHEIRO")), null), quando, false);
        return id;
    }

    private UUID devolver(UsuarioDeTeste v, UUID venda, String motivo, UUID produto, Instant quando) {
        UUID id = UUID.randomUUID();
        devolucoes.devolver(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDaDevolucaoDoCliente(id, venda, motivo, null,
                "DINHEIRO", List.of(new PecaDoPedido(produto, 1, "BOA")), null), quando, false, ModoDaDevolucao.ONLINE);
        return id;
    }

    private UUID vendaNovaDaTroca(br.com.coronasemijoias.api.trocas.ServicoDeTrocas.PedidoDeTroca pedido) {
        return pedido.venda().id();
    }

    private JsonNode relatorio(String rota, String filtros) throws Exception {
        return corpoDe(ler(REL + rota + "?" + filtros, dona()).andExpect(status().isOk()));
    }

    private JsonNode curva(String filtros) throws Exception {
        return corpoDe(ler(CURVA + "?" + filtros, dona()).andExpect(status().isOk()));
    }

    private static JsonNode total(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (t.get("chave").asString().equals(chave)) {
                return t;
            }
        }
        throw new AssertionError("Sem o total " + chave + " em " + relatorio.get("totais"));
    }

    private static void confere(JsonNode t, long quantidade, String reais, String dolar, String guarani) {
        assertThat(t.get("quantidade").asLong()).as("quantidade de %s", t.get("chave")).isEqualTo(quantidade);
        assertThat(dec(t.get("valorEmReais"))).as("reais de %s", t.get("chave")).isEqualByComparingTo(reais);
        assertThat(dec(t.get("valorEmDolar"))).as("dólar de %s", t.get("chave")).isEqualByComparingTo(dolar);
        assertThat(dec(t.get("valorEmGuarani"))).as("guarani de %s", t.get("chave")).isEqualByComparingTo(guarani);
    }

    private static JsonNode linhaDe(JsonNode relatorio, String campo, Object valor) {
        for (JsonNode l : relatorio.get("itens")) {
            if (l.get(campo).asString().equals(valor.toString())) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + campo + "=" + valor + " em " + relatorio.get("itens"));
    }

    private static void moedas(JsonNode linha, String prefixo, String reais, String dolar, String guarani) {
        assertThat(dec(linha.get(prefixo))).as("%s em reais", prefixo).isEqualByComparingTo(reais);
        assertThat(dec(linha.get(prefixo + "EmDolar"))).as("%s em dólar", prefixo).isEqualByComparingTo(dolar);
        assertThat(dec(linha.get(prefixo + "EmGuarani"))).as("%s em guarani", prefixo).isEqualByComparingTo(guarani);
    }

    /** vendas, peças, vendido, devolvido e líquido de uma linha agrupada, com Dólar e Guarani de cada um. */
    private static void linhaAgrupada(JsonNode l, long vendas, long pecas, String[] vendido, String[] devolvido, String[] liquido,
            String ticketMedio) {
        assertThat(l.get("vendas").asLong()).as("vendas de %s", l.get("nome")).isEqualTo(vendas);
        assertThat(l.get("pecas").asLong()).as("peças de %s", l.get("nome")).isEqualTo(pecas);
        moedas(l, "vendido", vendido[0], vendido[1], vendido[2]);
        moedas(l, "devolvido", devolvido[0], devolvido[1], devolvido[2]);
        moedas(l, "liquido", liquido[0], liquido[1], liquido[2]);
        assertThat(dec(l.get("ticketMedio"))).as("ticket médio de %s", l.get("nome")).isEqualByComparingTo(ticketMedio);
    }

    private static String[] v(String reais, String dolar, String guarani) {
        return new String[] {reais, dolar, guarani};
    }

    // --- por vendedor, praça, cliente, produto, categoria e período ---

    @Test
    void porVendedorComTudoCalculadoAMao() throws Exception {
        JsonNode r = relatorio("por-vendedor", MES);
        assertThat(r.get("itens")).hasSize(2);
        // V1: vendas 1, 2, 3 e 6 (4 vendas); peças líquidas 2 + 2 + 1 + 0 + 1 = 6.
        //   vendido 300 + 160 + 20 + 130 + 50 = 660 (US$ 60 + 32 + 4 + 32,50 + 12,50 = 141; Gs 300000 + 160000 + 20000 + 65000 + 25000 = 570000)
        //   devolvido 100 + 130 = 230 (US$ 20 + 32,50 = 52,50; Gs 100000 + 65000 = 165000); líquido 430 (US$ 88,50; Gs 405000)
        //   ticket médio 430 / 4 = 107,50
        JsonNode a = linhaDe(r, "chave", v1.vendedorId());
        assertThat(a.get("nome").asString()).isEqualTo("Vendedor de " + v1.login());
        linhaAgrupada(a, 4, 6, v("660.00", "141.00", "570000"), v("230.00", "52.50", "165000"), v("430.00", "88.50", "405000"),
                "107.50");
        // V2: só a venda 4 (a 5 foi estornada); peças 2 + 1 = 3; vendido 290 (US$ 58; Gs 290000); devolvido 45 (US$ 9; Gs 45000);
        //   líquido 245 (US$ 49; Gs 245000); ticket médio 245,00.
        JsonNode b = linhaDe(r, "chave", v2.vendedorId());
        linhaAgrupada(b, 1, 3, v("290.00", "58.00", "290000"), v("45.00", "9.00", "45000"), v("245.00", "49.00", "245000"),
                "245.00");
        // Rodapé: a soma das linhas já convertidas.
        JsonNode liquido = total(r, "liquido");
        confere(liquido, 2, "675.00", "137.50", "650000");
        assertThat(liquido.get("pecas").asLong()).isEqualTo(9);
        confere(total(r, "vendido"), 2, "950.00", "199.00", "860000");
        confere(total(r, "devolvido"), 2, "275.00", "61.50", "210000");
    }

    @Test
    void porPracaPorClienteEPorCategoria() throws Exception {
        JsonNode pracas = relatorio("por-praca", MES);
        assertThat(pracas.get("itens")).hasSize(2);
        assertThat(linhaDe(pracas, "chave", pracaA).get("vendas").asLong()).isEqualTo(4);
        moedas(linhaDe(pracas, "chave", pracaA), "liquido", "430.00", "88.50", "405000");
        moedas(linhaDe(pracas, "chave", pracaB), "liquido", "245.00", "49.00", "245000");
        confere(total(pracas, "liquido"), 2, "675.00", "137.50", "650000");

        // Cliente A: vendas 1 e 2 (peças 2 + 3 = 5; vendido 300 + 180 = 480; devolvido 100; líquido 380; ticket 380 / 2 = 190).
        // Cliente B: vendas 3 e 6 (peças 0 + 1; vendido 130 + 50 = 180; devolvido 130; líquido 50; ticket 25).
        // Cliente C: venda 4 (peças 3; vendido 290; devolvido 45; líquido 245; ticket 245).
        JsonNode clientes = relatorio("por-cliente", MES);
        assertThat(clientes.get("itens")).hasSize(3);
        linhaAgrupada(linhaDe(clientes, "chave", clienteA), 2, 5, v("480.00", "92.00", "480000"), v("100.00", "20.00", "100000"),
                v("380.00", "72.00", "380000"), "190.00");
        linhaAgrupada(linhaDe(clientes, "chave", clienteB), 2, 1, v("180.00", "45.00", "90000"), v("130.00", "32.50", "65000"),
                v("50.00", "12.50", "25000"), "25.00");
        linhaAgrupada(linhaDe(clientes, "chave", clienteC), 1, 3, v("290.00", "58.00", "290000"), v("45.00", "9.00", "45000"),
                v("245.00", "49.00", "245000"), "245.00");

        // Categoria X (P1 e P2): vendas 1, 4 e 2 (3 vendas); peças 2 + 2 + 2 = 6; vendido 300 + 200 + 160 = 660; devolvido 100; líquido 560.
        // Categoria Y (P3, P4 e P5): vendas 3, 6, 4 e 2 (4 vendas); peças 0 + 1 + 1 + 1 = 3; vendido 130 + 50 + 90 + 20 = 290;
        //   devolvido 130 + 45 = 175; líquido 115. A soma dos líquidos é 675.
        JsonNode categorias = relatorio("por-categoria", MES);
        assertThat(categorias.get("itens")).hasSize(2);
        JsonNode x = linhaDe(categorias, "chave", categoriaX);
        assertThat(x.get("vendas").asLong()).isEqualTo(3);
        assertThat(x.get("pecas").asLong()).isEqualTo(6);
        moedas(x, "vendido", "660.00", "136.00", "660000");
        moedas(x, "devolvido", "100.00", "20.00", "100000");
        moedas(x, "liquido", "560.00", "112.00", "560000");
        JsonNode y = linhaDe(categorias, "chave", categoriaY);
        assertThat(y.get("vendas").asLong()).isEqualTo(4);
        assertThat(y.get("pecas").asLong()).isEqualTo(3);
        moedas(y, "liquido", "115.00", "25.50", "90000");
        confere(total(categorias, "liquido"), 2, "675.00", "137.50", "650000");
    }

    @Test
    void porProdutoETotalPorPeriodo() throws Exception {
        JsonNode produtos = relatorio("por-produto", MES);
        assertThat(produtos.get("itens")).hasSize(5);
        // P1: vendas 1 e 4; peças 2 + 2; vendido 500; devolvido 100; líquido 400; ticket 200. P2: 160. P3: vendida e devolvida, líquido 0.
        // P4: vendas 6 e 4; peças 2; vendido 50 + 90 = 140; devolvido 45; líquido 95; ticket 47,50. P5: 20.
        linhaAgrupada(linhaDe(produtos, "chave", p1), 2, 4, v("500.00", "100.00", "500000"), v("100.00", "20.00", "100000"),
                v("400.00", "80.00", "400000"), "200.00");
        linhaAgrupada(linhaDe(produtos, "chave", p2), 1, 2, v("160.00", "32.00", "160000"), v("0.00", "0.00", "0"),
                v("160.00", "32.00", "160000"), "160.00");
        linhaAgrupada(linhaDe(produtos, "chave", p3), 1, 0, v("130.00", "32.50", "65000"), v("130.00", "32.50", "65000"),
                v("0.00", "0.00", "0"), "0.00");
        linhaAgrupada(linhaDe(produtos, "chave", p4), 2, 2, v("140.00", "30.50", "115000"), v("45.00", "9.00", "45000"),
                v("95.00", "21.50", "70000"), "47.50");
        linhaAgrupada(linhaDe(produtos, "chave", p5), 1, 1, v("20.00", "4.00", "20000"), v("0.00", "0.00", "0"),
                v("20.00", "4.00", "20000"), "20.00");
        assertThat(linhaDe(produtos, "chave", p1).get("codigo").asString()).startsWith("VD-");
        confere(total(produtos, "liquido"), 5, "675.00", "137.50", "650000");

        // Por dia (Brasília): 04/03 vendas 1, 2 e 4 = líquido 200 + 180 + 245 = 625 (3 vendas); 05/03 vendas 3 e 6 = 0 + 50 (2 vendas).
        JsonNode dias = relatorio("por-periodo", MES);
        assertThat(dias.get("itens")).hasSize(2);
        linhaAgrupada(linhaDe(dias, "chave", "2292-03-04"), 3, 8, v("770.00", "154.00", "770000"), v("145.00", "29.00", "145000"),
                v("625.00", "125.00", "625000"), "208.33");
        linhaAgrupada(linhaDe(dias, "chave", "2292-03-05"), 2, 1, v("180.00", "45.00", "90000"), v("130.00", "32.50", "65000"),
                v("50.00", "12.50", "25000"), "25.00");
    }

    @Test
    void rankingsEmOrdemDoMaiorParaOMenorETicketMedio() throws Exception {
        JsonNode vendedores = relatorio("ranking-de-vendedores", MES);
        assertThat(vendedores.get("itens").get(0).get("chave").asString()).isEqualTo(v1.vendedorId().toString());
        assertThat(vendedores.get("itens").get(0).get("posicao").asLong()).isEqualTo(1);
        assertThat(vendedores.get("itens").get(1).get("chave").asString()).isEqualTo(v2.vendedorId().toString());
        assertThat(vendedores.get("itens").get(1).get("posicao").asLong()).isEqualTo(2);

        // Peças: P1 400, P2 160, P4 95, P5 20, P3 0.
        JsonNode pecas = relatorio("ranking-de-pecas", MES);
        assertThat(pecas.get("itens").findValues("chave").stream().map(JsonNode::asString).toList())
                .containsExactly(p1.toString(), p2.toString(), p4.toString(), p5.toString(), p3.toString());
        assertThat(pecas.get("itens").findValues("posicao").stream().map(JsonNode::asLong).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        // O ranking por peça também mostra a foto (a falta de foto não trava: o campo vem vazio).
        assertThat(pecas.get("itens").get(0).has("fotoPrincipalHash")).isTrue();

        // Ticket médio: V2 245,00 antes de V1 107,50.
        JsonNode ticket = relatorio("ticket-medio", MES);
        assertThat(ticket.get("itens").get(0).get("chave").asString()).isEqualTo(v2.vendedorId().toString());
        assertThat(dec(ticket.get("itens").get(0).get("ticketMedio"))).isEqualByComparingTo("245.00");
        assertThat(dec(ticket.get("itens").get(1).get("ticketMedio"))).isEqualByComparingTo("107.50");
    }

    @Test
    void filtrosDePecaCategoriaPracaClienteEDia() throws Exception {
        // Só P1: V1 (venda 1: 300 - 100 = 200) e V2 (venda 4: 200).
        JsonNode soP1 = relatorio("por-vendedor", MES + "&produtoId=" + p1);
        assertThat(dec(linhaDe(soP1, "chave", v1.vendedorId()).get("liquido"))).isEqualByComparingTo("200.00");
        assertThat(dec(linhaDe(soP1, "chave", v2.vendedorId()).get("liquido"))).isEqualByComparingTo("200.00");
        // Categoria X: V1 200 + 160 = 360 e V2 200.
        JsonNode soX = relatorio("por-vendedor", MES + "&categoriaId=" + categoriaX);
        assertThat(dec(linhaDe(soX, "chave", v1.vendedorId()).get("liquido"))).isEqualByComparingTo("360.00");
        assertThat(dec(linhaDe(soX, "chave", v2.vendedorId()).get("liquido"))).isEqualByComparingTo("200.00");
        // Praça PA: só V1. Cliente C: só V2.
        assertThat(relatorio("por-vendedor", MES + "&pracaId=" + pracaA).get("itens")).hasSize(1);
        assertThat(relatorio("por-vendedor", MES + "&clienteId=" + clienteC).get("itens").get(0).get("chave").asString())
                .isEqualTo(v2.vendedorId().toString());
        // Vendedor: só as peças de V2 no por-produto (P1 e P4).
        assertThat(relatorio("por-produto", MES + "&vendedorId=" + v2.vendedorId()).get("itens")).hasSize(2);
        // Só o dia 05/03: V1 tem as vendas 3 e 6 (líquido 50); V2 não tem venda (a 5 foi estornada).
        JsonNode dia5 = relatorio("por-vendedor", "de=2292-03-05&ate=2292-03-05");
        assertThat(dia5.get("itens")).hasSize(1);
        linhaAgrupada(dia5.get("itens").get(0), 2, 1, v("180.00", "45.00", "90000"), v("130.00", "32.50", "65000"),
                v("50.00", "12.50", "25000"), "25.00");
        // O dia 04/03 não tem a venda 3 (fica no 05/03).
        assertThat(relatorio("por-vendedor", "de=2292-03-04&ate=2292-03-04&vendedorId=" + v1.vendedorId()).get("itens").get(0)
                .get("vendas").asLong()).isEqualTo(2);
        // Busca pelo nome.
        assertThat(relatorio("por-vendedor", MES + "&busca=" + v2.login()).get("itens")).hasSize(1);
    }

    // --- uma venda por linha, canceladas ---

    @Test
    void umaLinhaPorVendaComOsTotaisEAVendaEstornadaFicandoFora() throws Exception {
        JsonNode r = relatorio("vendas", MES);
        // Vendas 1, 2, 3, 4 e 6 (a estornada fica de fora por padrão).
        assertThat(r.get("itens")).hasSize(5);
        assertThat(r.get("total").asLong()).isEqualTo(5);
        confere(total(r, "liquido"), 5, "675.00", "137.50", "650000");
        confere(total(r, "vendido"), 5, "950.00", "199.00", "860000");
        confere(total(r, "devolvido"), 3, "275.00", "61.50", "210000");
        confere(total(r, "canceladas"), 1, "100.00", "25.00", "50000");

        JsonNode quatro = linhaDe(r, "vendaId", venda4);
        assertThat(quatro.get("pecas").asLong()).isEqualTo(3);
        moedas(quatro, "vendido", "290.00", "58.00", "290000");
        moedas(quatro, "devolvido", "45.00", "9.00", "45000");
        moedas(quatro, "liquido", "245.00", "49.00", "245000");
        assertThat(quatro.get("dataDaCotacao").asString()).isEqualTo("2292-03-04");
        assertThat(quatro.get("situacao").asString()).isEqualTo("CONCLUIDA");
        assertThat(quatro.get("cancelada").asBoolean()).isFalse();
        assertThat(quatro.get("praca").asString()).isNotBlank();
        // A venda da troca vale 50 e a venda trocada fica em 0 (130 - 130).
        assertThat(dec(linhaDe(r, "vendaId", venda6).get("liquido"))).isEqualByComparingTo("50.00");
        assertThat(dec(linhaDe(r, "vendaId", venda3).get("liquido"))).isEqualByComparingTo("0.00");
        assertThat(linhaDe(r, "vendaId", venda6).get("dataDaCotacao").asString()).isEqualTo("2292-03-05");

        // Todas: a estornada aparece marcada e continua fora do total; só as canceladas: ela sozinha, com quem estornou e o motivo.
        JsonNode todas = relatorio("vendas", MES + "&situacao=TODAS");
        assertThat(todas.get("itens")).hasSize(6);
        confere(total(todas, "liquido"), 5, "675.00", "137.50", "650000");
        JsonNode cinco = linhaDe(todas, "vendaId", venda5);
        assertThat(cinco.get("cancelada").asBoolean()).isTrue();
        assertThat(cinco.get("situacao").asString()).isEqualTo("ESTORNADA");
        assertThat(dec(cinco.get("liquido"))).isEqualByComparingTo("0.00");

        JsonNode canceladas = relatorio("canceladas", MES);
        assertThat(canceladas.get("itens")).hasSize(1);
        JsonNode c = canceladas.get("itens").get(0);
        assertThat(c.get("vendaId").asString()).isEqualTo(venda5.toString());
        assertThat(c.get("motivoDoEstorno").asString()).isEqualTo("O cliente desistiu na hora");
        assertThat(c.get("estornadaPor").asString()).isNotBlank();
        assertThat(c.get("estornadaEm").isNull()).isFalse();
        moedas(c, "vendido", "100.00", "25.00", "50000");
        confere(total(canceladas, "canceladas"), 1, "100.00", "25.00", "50000");
        assertThat(canceladas.get("itens").get(0).get("vendedor").asString()).isEqualTo("Vendedor de " + v2.login());
    }

    @Test
    void detalhamentoDeUmTotalMostraSoAsLinhasQueOFormam() throws Exception {
        JsonNode devolvidas = relatorio("vendas", MES + "&recorte=devolvido");
        assertThat(devolvidas.get("itens").findValues("vendaId").stream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(venda1.toString(), venda3.toString(), venda4.toString());
        assertThat(devolvidas.get("recorte").asString()).isEqualTo("devolvido");
        JsonNode soCanceladas = relatorio("vendas", MES + "&situacao=TODAS&recorte=canceladas");
        assertThat(soCanceladas.get("itens")).hasSize(1);
        // Em um relatório agrupado o detalhamento mostra os grupos que formam o total.
        JsonNode comDevolucao = relatorio("por-vendedor", MES + "&recorte=devolvido");
        assertThat(comDevolucao.get("itens")).hasSize(2);
    }

    // --- curva ABC ---

    @Test
    void curvaAbcComAsFaixasDeOitentaENoventaECincoCalculadasAMao() throws Exception {
        JsonNode r = curva(MES);
        // Total 675. P3 (líquido 0) fica fora. P1 400 (59,26%) A; P2 160: antes 59,26% < 80%, acumulado 560 = 82,96% -> A (cruza a fronteira,
        // fica na classe de cima); P4 95: antes 82,96% >= 80% -> B, acumulado 655 = 97,04%; P5 20: antes 97,04% >= 95% -> C, acumulado 100%.
        assertThat(r.get("itens")).hasSize(4);
        String[][] esperado = {
                {"1", "400.00", "59.26", "59.26", "A"},
                {"2", "160.00", "23.70", "82.96", "A"},
                {"3", "95.00", "14.07", "97.04", "B"},
                {"4", "20.00", "2.96", "100.00", "C"}};
        UUID[] pecas = {p1, p2, p4, p5};
        for (int i = 0; i < 4; i++) {
            JsonNode l = r.get("itens").get(i);
            assertThat(l.get("produtoId").asString()).isEqualTo(pecas[i].toString());
            assertThat(l.get("posicao").asLong()).isEqualTo(Long.parseLong(esperado[i][0]));
            assertThat(dec(l.get("liquido"))).isEqualByComparingTo(esperado[i][1]);
            assertThat(dec(l.get("percentual"))).isEqualByComparingTo(esperado[i][2]);
            assertThat(dec(l.get("percentualAcumulado"))).isEqualByComparingTo(esperado[i][3]);
            assertThat(l.get("classe").asString()).isEqualTo(esperado[i][4]);
            assertThat(l.has("fotoPrincipalHash")).isTrue();
            assertThat(l.get("codigo").asString()).startsWith("VD-");
        }
        // Rodapé: a classe A (P1 e P2) US$ 80 + 32 = 112, Gs 560000; B (P4) US$ 12,50 + 9 = 21,50, Gs 70000; C (P5) US$ 4, Gs 20000.
        confere(total(r, "liquido"), 4, "675.00", "137.50", "650000");
        confere(total(r, "A"), 2, "560.00", "112.00", "560000");
        confere(total(r, "B"), 1, "95.00", "21.50", "70000");
        confere(total(r, "C"), 1, "20.00", "4.00", "20000");
        // O detalhamento da classe A.
        JsonNode soA = curva(MES + "&recorte=A");
        assertThat(soA.get("itens").findValues("produtoId").stream().map(JsonNode::asString).toList())
                .containsExactly(p1.toString(), p2.toString());
    }

    @Test
    void curvaAbcPartePeloFiltroEPeloPeriodo() throws Exception {
        // Só a categoria Y: P4 95 e P5 20, total 115. P4: antes 0 -> A (82,61%); P5: antes 82,61% >= 80% e < 95% -> B (100%).
        JsonNode y = curva(MES + "&categoriaId=" + categoriaY);
        assertThat(y.get("itens")).hasSize(2);
        assertThat(y.get("itens").get(0).get("classe").asString()).isEqualTo("A");
        assertThat(dec(y.get("itens").get(0).get("percentual"))).isEqualByComparingTo("82.61");
        assertThat(y.get("itens").get(1).get("classe").asString()).isEqualTo("B");
        assertThat(dec(y.get("itens").get(1).get("percentualAcumulado"))).isEqualByComparingTo("100.00");
        // Só o dia 05/03: só P4 vendida (venda 6, 50): fica sozinha na classe A. Sem venda no período: curva vazia.
        JsonNode dia5 = curva("de=2292-03-05&ate=2292-03-05");
        assertThat(dia5.get("itens")).hasSize(1);
        assertThat(dia5.get("itens").get(0).get("classe").asString()).isEqualTo("A");
        assertThat(dec(dia5.get("itens").get(0).get("percentual"))).isEqualByComparingTo("100.00");
        JsonNode vazia = curva("de=2292-04-01&ate=2292-04-30");
        assertThat(vazia.get("itens")).isEmpty();
        confere(total(vazia, "liquido"), 0, "0.00", "0.00", "0");
    }

    // --- uma fórmula só ---

    @Test
    void curvaAbcERelatorioDeVendasSaemDoMesmoVendidoLiquido() throws Exception {
        JsonNode curva = curva(MES);
        JsonNode porProduto = relatorio("por-produto", MES);
        // Peça a peça: o líquido, o Dólar e o Guarani da curva são os do relatório de vendas por produto.
        for (JsonNode l : curva.get("itens")) {
            JsonNode igual = linhaDe(porProduto, "chave", l.get("produtoId").asString());
            for (String campo : List.of("liquido", "liquidoEmDolar", "liquidoEmGuarani")) {
                assertThat(dec(l.get(campo))).as("%s de %s", campo, l.get("nome")).isEqualByComparingTo(dec(igual.get(campo)));
            }
            assertThat(l.get("pecas").asLong()).isEqualTo(igual.get("pecas").asLong());
        }
        // Total a total: curva, por produto, por vendedor, por praça, por cliente, por categoria, por dia e uma linha por venda.
        BigDecimal esperado = dec(total(curva, "liquido").get("valorEmReais"));
        assertThat(esperado).isEqualByComparingTo("675.00");
        for (String rota : List.of("por-produto", "por-vendedor", "por-praca", "por-cliente", "por-categoria", "por-periodo",
                "vendas", "ranking-de-vendedores", "ranking-de-pecas", "ticket-medio")) {
            JsonNode t = total(relatorio(rota, MES), "liquido");
            assertThat(dec(t.get("valorEmReais"))).as("líquido de %s", rota).isEqualByComparingTo(esperado);
            assertThat(dec(t.get("valorEmDolar"))).as("dólar de %s", rota).isEqualByComparingTo("137.50");
            assertThat(dec(t.get("valorEmGuarani"))).as("guarani de %s", rota).isEqualByComparingTo("650000");
        }
        // E o líquido da massa inteira é o das vendas concluídas menos as devoluções não canceladas, direto no banco.
        BigDecimal dobanco = valor("""
                SELECT (SELECT coalesce(sum(v.total), 0) FROM corona.vendas v
                         WHERE v.status = 'CONCLUIDA' AND v.deleted_at IS NULL AND v.vendida_em >= '2292-03-01'
                           AND v.vendida_em < '2292-04-01')
                     - (SELECT coalesce(sum(d.valor_devolvido), 0) FROM corona.devolucoes d JOIN corona.vendas v ON v.id = d.venda_id
                         WHERE d.situacao <> 'CANCELADA' AND v.status = 'CONCLUIDA' AND v.vendida_em >= '2292-03-01'
                           AND v.vendida_em < '2292-04-01')""");
        assertThat(dobanco).isEqualByComparingTo(esperado);
    }

    // --- permissões e pedidos mal feitos ---

    @Test
    void soAdminLeSemLoginRecebe401EVendedorRecebe403() throws Exception {
        for (String rota : ROTAS) {
            ler(REL + rota + "?" + MES, como(v1)).andExpect(status().isForbidden());
            mvc.perform(get(REL + rota + "?" + MES)).andExpect(status().isUnauthorized());
            ler(REL + rota + "?" + MES, dona()).andExpect(status().isOk());
        }
        ler(CURVA + "?" + MES, como(v1)).andExpect(status().isForbidden());
        mvc.perform(get(CURVA + "?" + MES)).andExpect(status().isUnauthorized());
        ler(CURVA + "?" + MES, dona()).andExpect(status().isOk());
    }

    @Test
    void nenhumaRespostaTrazCustoNemLucro() throws Exception {
        for (String rota : ROTAS) {
            String corpo = ler(REL + rota + "?" + MES, dona()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(corpo.toLowerCase()).as(rota).doesNotContain("custo").doesNotContain("lucro");
        }
        String curva = ler(CURVA + "?" + MES, dona()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(curva.toLowerCase()).doesNotContain("custo").doesNotContain("lucro");
    }

    @Test
    void pedidosMalFeitosRecebem400() throws Exception {
        ler(REL + "por-vendedor?de=2292-03-10&ate=2292-03-01", dona()).andExpect(status().isBadRequest());
        ler(REL + "por-vendedor?vendedorId=nao-e-uuid", dona()).andExpect(status().isBadRequest());
        ler(REL + "por-produto?produtoId=nao-e-uuid", dona()).andExpect(status().isBadRequest());
        ler(REL + "vendas?situacao=QUALQUER", dona()).andExpect(status().isBadRequest());
        ler(REL + "por-vendedor?recorte=nao-existe", dona()).andExpect(status().isBadRequest());
        ler(REL + "por-vendedor?tamanho=101", dona()).andExpect(status().isBadRequest());
        ler(REL + "por-vendedor?pagina=-1", dona()).andExpect(status().isBadRequest());
        ler(CURVA + "?de=2292-03-10&ate=2292-03-01", dona()).andExpect(status().isBadRequest());
        ler(CURVA + "?categoriaId=nao-e-uuid", dona()).andExpect(status().isBadRequest());
        ler(CURVA + "?recorte=D", dona()).andExpect(status().isBadRequest());
    }

    @Test
    void paginaEmPedacosEOsTotaisNaoMudam() throws Exception {
        JsonNode pagina0 = relatorio("por-produto", MES + "&tamanho=2&pagina=0");
        JsonNode pagina2 = relatorio("por-produto", MES + "&tamanho=2&pagina=2");
        assertThat(pagina0.get("itens")).hasSize(2);
        assertThat(pagina2.get("itens")).hasSize(1);
        assertThat(pagina0.get("total").asLong()).isEqualTo(5);
        confere(total(pagina2, "liquido"), 5, "675.00", "137.50", "650000");
    }
}
