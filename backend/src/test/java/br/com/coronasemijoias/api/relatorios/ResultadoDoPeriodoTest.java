package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Resultado do período (DRE), lucro por produto e lucro por vendedor (tarefa 8.6; ADR-0015, itens 1 a 3). Massa conhecida, com
 * tudo calculado à mão, em abril de 2292 (nenhum outro teste usa esse mês). Cotações: até o dia 12, US$ 1 = R$ 5,00 e Gs. 1 =
 * R$ 0,001; do dia 15 em diante, US$ 1 = R$ 4,00 e Gs. 1 = R$ 0,002.
 *
 * <pre>
 * Peça A (preço 50,00): compra 1 em 01/04 a 10,00; compra 2 em 10/04 a 12,00 (o cadastro passa a 12,00).
 * Peça B (preço 30,00): sem compra, cadastro 7,50.   Vendedor V1 (percentual 10%), vendedor V2 (5%).
 *   S1 05/04 V1: 2 x A = 100,00 (custo do dia 05/04: 10,00)        S2 15/04 V1: 1 x A, desconto 5,00 = 45,00 (custo 12,00)
 *   S3 12/04 V2: 3 x B = 90,00 (custo do cadastro 7,50)            S4 08/04 V2: 1 x A, ESTORNADA (fora de tudo)
 *   Devolução de 1 x A da S1 em 20/04: 50,00 (a peça boa volta; o custo dela, 10,00, volta junto)
 *   Acerto V1 fechado 18/04: comissão S1 10,00 + S2 4,50 = 14,50.  Acerto seguinte de V1 fechado 29/04: estorno da comissão da
 *     devolução -5,00; a loja assumiu a falta de 1 x A (custo em 29/04: 12,00); acerto a favor de V1 (paga 45,00 em dinheiro).
 *   Acerto V2 fechado 25/04: comissão S3 4,50; falta de 1 x B cobrada a 30,00 (custo 7,50).
 *   Avaria do estoque: 1 x A em 03/04 (custo 10,00). Inventário para menos: 2 x B em 26/04 (custo 15,00).
 *   Caixa manual: Aluguel saída 300,00 (05/04); Outras entradas 20,00 (07/04); Material saída 40,00 (09/04) estornada em 11/04.
 *   O caixa automático (os 45,00 do acerto a favor de V1) fica fora do resultado.
 *
 * Vendas                         235,00   (100,00 + 45,00 + 90,00; a S4 está fora)
 * (-) Devoluções e trocas         50,00
 * = Receita líquida              185,00
 * (-) Custo das peças vendidas    44,50   (20,00 + 12,00 + 22,50 - 10,00)
 * = Lucro bruto                  140,50
 * (-) Comissões                   14,00   (14,50 - 5,00 + 4,50)
 * (-) Perdas                      44,50   (faltas 12,00 + 7,50; avaria 10,00; inventário 15,00)
 * (+) Cobrado do vendedor         30,00
 * (+/-) Aluguel                 -300,00 · Material 0,00 (-40,00 + 40,00) · Outras entradas 20,00
 * = Resultado                   -168,00
 *
 * Lucro por produto: A = 95,00 - 22,00 = 73,00; B = 90,00 - 22,50 = 67,50 (soma 140,50 = lucro bruto).
 * Lucro por vendedor: V1 = 95,00 - 22,00 - 9,50 = 63,50; V2 = 90,00 - 22,50 - 4,50 = 63,00 (soma 126,50 = 140,50 - 14,00).
 * </pre>
 *
 * Os outros meses (junho a setembro) trazem os casos que só mudam a data: a devolução que chega depois e a falta encontrada
 * depois. Todas as chamadas filtram o período; o banco guarda lançamentos de outros testes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultadoDoPeriodoTest extends MassaDoResultado {

    private static final String FIN = "/api/dona/relatorios/financeiro";
    private static final String ABRIL = "de=2292-04-01&ate=2292-04-30";

    private UsuarioDeTeste v1;
    private UsuarioDeTeste v2;
    private UUID pracaA;
    private UUID categoriaA;
    private UUID categoriaB;
    private UUID pecaA;
    private UUID pecaB;
    private UUID aluguel;
    private UUID material;
    private UUID outrasEntradas;
    private UUID acertoV1;
    private UUID acertoV1Seguinte;
    private UUID acertoV2;

    // massa dos casos de outros meses
    private String mesDaDevolucaoTardia = "de=2292-06-01&ate=2292-06-30";
    private String mesDaFaltaEncontrada = "de=2292-08-01&ate=2292-08-31";
    private String mesSeguinteDaFalta = "de=2292-09-01&ate=2292-09-30";
    private UUID pecaC;
    private UUID outrasSemCotacao;

    @BeforeAll
    void massaConhecida() throws Exception {
        cotacoesDe("2292-04", 1, 3, 5, 7, 8, 9, 10, 11, 12, 15, 18, 20, 25, 26, 29);
        cotacoesDe("2292-06", 10);
        cotacoesDe("2292-07", 5);
        cotacoesDe("2292-08", 10);
        cotacoesDe("2292-09", 20);
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 90 WHERE chave = 'prazo_arrependimento_dias'");

        categoriaA = categoria();
        categoriaB = categoria();
        pecaA = pecaComCusto(categoriaA, "50.00", "10.00");
        pecaB = pecaComCusto(categoriaB, "30.00", "7.50");
        UUID fornecedor = fornecedor();
        comprarEm(fornecedor, "2292-04-01", pecaA, 20, "10.00");
        comprarEm(fornecedor, "2292-04-10", pecaA, 10, "12.00");
        estoque.registrar(new br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido(null, pecaB,
                br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento.ENTRADA, 20, null,
                br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento.MANUAL, null), usuarios.dona().id());

        pracaA = praca();
        UUID pracaB = praca();
        v1 = vendedorNaPraca(pracaA);
        v2 = vendedorNaPraca(pracaB);
        UUID clienteDeV1 = cliente(v1);
        UUID clienteDeV2 = cliente(v2);
        UUID maletaV1 = maleta(v1, pecaA, 8);
        UUID maletaV2 = maleta(v2, pecaA, 8, pecaB, 8);

        UUID s1 = vender(v1, maletaV1, clienteDeV1, "2292-04-05", "100.00", new LinhaDoPedido(pecaA, 2, null));
        limiteDeDesconto(10);
        UUID s2 = vender(v1, maletaV1, clienteDeV1, "2292-04-15", "45.00", new LinhaDoPedido(pecaA, 1, new BigDecimal("5.00")));
        limiteDeDesconto(0);
        UUID s3 = vender(v2, maletaV2, clienteDeV2, "2292-04-12", "90.00", new LinhaDoPedido(pecaB, 3, null));
        UUID s4 = vender(v2, maletaV2, clienteDeV2, "2292-04-08", "50.00", new LinhaDoPedido(pecaA, 1, null));
        estornarVenda(v2, s4, "2292-04-08");
        UUID devolucao = devolverPeca(v1, s1, pecaA, 1, "2292-04-20");

        avariar(pecaA, 1, "2292-04-03");
        inventarioParaMenos(pecaB, -2, "2292-04-26");

        aluguel = categoriaDoCaixa("Aluguel 2292", "SAIDA");
        material = categoriaDoCaixa("Material 2292", "SAIDA");
        outrasEntradas = categoriaDoCaixa("Outras entradas 2292", "ENTRADA");
        lancarNoCaixa(aluguel, "SAIDA", "300.00", "2292-04-05", "Aluguel de abril");
        lancarNoCaixa(outrasEntradas, "ENTRADA", "20.00", "2292-04-07", "Venda de sobras");
        UUID material40 = lancarNoCaixa(material, "SAIDA", "40.00", "2292-04-09", "Sacolas");
        estornarLancamento(material40, "2292-04-11");

        UUID semana1 = semana();
        UUID semana2 = semana();
        UUID semana3 = semana();
        acertoV1 = acertoFechado(v1, semana1, "2292-04-18", "10", List.of(s1, s2), List.of(), "14.50", "0.00", "0.00", null);
        acertoV1Seguinte = acertoFechado(v1, semana3, "2292-04-29", "10", List.of(), List.of(devolucao), "-5.00", "0.00", "45.00",
                acerto -> pendenciaDeFalta(acerto, v1, pecaA, 1, "50.00", "ASSUMIDA_PELA_LOJA", "2292-04-29"));
        acertoV2 = acertoFechado(v2, semana2, "2292-04-25", "5", List.of(s3), List.of(), "4.50", "30.00", "0.00",
                acerto -> pendenciaDeFalta(acerto, v2, pecaB, 1, "30.00", "COBRADA", null));

        massaDosOutrosMeses(fornecedor);
    }

    /** Devolução que chega no mês seguinte (junho/julho) e falta encontrada depois (agosto/setembro). */
    private void massaDosOutrosMeses(UUID fornecedor) throws Exception {
        pecaC = pecaComCusto(categoriaB, "80.00", "20.00");
        estoque.registrar(new br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido(null, pecaC,
                br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento.ENTRADA, 10, null,
                br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento.MANUAL, null), usuarios.dona().id());
        UsuarioDeTeste v3 = vendedorNaPraca(pracaA);
        UUID clienteDeV3 = cliente(v3);
        UUID maletaV3 = maleta(v3, pecaC, 6);
        UUID venda = vender(v3, maletaV3, clienteDeV3, "2292-06-10", "80.00", new LinhaDoPedido(pecaC, 1, null));
        devolverPeca(v3, venda, pecaC, 1, "2292-07-05");

        UUID semana = semana();
        UUID[] falta = new UUID[1];
        acertoFechado(v3, semana, "2292-08-10", "10", List.of(), List.of(), "0.00", "0.00", "0.00",
                acerto -> falta[0] = pendenciaDeFalta(acerto, v3, pecaC, 1, "80.00", "ABERTA", null));
        faltaEncontrada(falta[0], "2292-09-20");

        // O dia sem cotação nenhuma: o Dólar e o Guarani ficam vazios ("sem cotação"). Antes do ano 2000 nunca há cotação.
        outrasSemCotacao = categoriaDoCaixa("Outras sem cotação 2292", "ENTRADA");
        lancarNoCaixa(outrasSemCotacao, "ENTRADA", "10.00", "1999-12-15", "Entrada antiga");
    }

    /** As cotações da massa saem no fim, com os acertos fechados e o que é deles (a tabela é imutável). */
    @AfterAll
    void tirarAMassa() throws SQLException {
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
        // O caixa manual da massa também sai: o saldo do caixa do banco inteiro não pode ficar negativo por causa dela.
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            s.execute("ALTER TABLE corona.lancamentos_caixa DISABLE TRIGGER USER");
            try {
                String ids = lancamentosDaMassa.stream().map(i -> "'" + i + "'::uuid").collect(Collectors.joining(", "));
                s.execute("DELETE FROM corona.lancamentos_caixa WHERE estorna_id IN (" + ids + ")");
                s.execute("DELETE FROM corona.lancamentos_caixa WHERE id IN (" + ids + ")");
            } finally {
                s.execute("ALTER TABLE corona.lancamentos_caixa ENABLE TRIGGER USER");
            }
            con.commit();
        }
    }

    // --- apoio ---

    private JsonNode resultado(String periodo) throws Exception {
        return corpoDe(ler(FIN + "/resultado?" + periodo, dona()).andExpect(status().isOk()));
    }

    private JsonNode linha(JsonNode dre, String chave) {
        for (JsonNode l : dre.get("linhas")) {
            if (l.get("chave").asString().equals(chave)) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + chave + " em " + dre.get("linhas"));
    }

    private JsonNode linhaDoCaixa(JsonNode dre, String categoria) {
        for (JsonNode l : dre.get("linhas")) {
            if (l.get("chave").asString().equals("CAIXA") && l.get("titulo").asString().equals(categoria)) {
                return l;
            }
        }
        throw new AssertionError("Sem a categoria " + categoria + " em " + dre.get("linhas"));
    }

    private static void reais(JsonNode linha, String esperado) {
        assertThat(dec(linha.get("valorEmReais"))).as("reais de %s", linha.get("titulo")).isEqualByComparingTo(esperado);
    }

    private static void moedas(JsonNode linha, String reais, String dolar, String guarani) {
        reais(linha, reais);
        assertThat(dec(linha.get("valorEmDolar"))).as("dólar de %s", linha.get("titulo")).isEqualByComparingTo(dolar);
        assertThat(dec(linha.get("valorEmGuarani"))).as("guarani de %s", linha.get("titulo")).isEqualByComparingTo(guarani);
    }

    private JsonNode detalhe(String linha, String extra) throws Exception {
        return corpoDe(ler(FIN + "/resultado/detalhamento?" + ABRIL + "&linha=" + linha + "&tamanho=100" + extra, dona())
                .andExpect(status().isOk()));
    }

    private static BigDecimal totalDoDetalhe(JsonNode detalhe) {
        for (JsonNode t : detalhe.get("totais")) {
            if (t.get("chave").asString().equals("total")) {
                return dec(t.get("valorEmReais"));
            }
        }
        throw new AssertionError("Sem o total em " + detalhe.get("totais"));
    }

    private static JsonNode total(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (t.get("chave").asString().equals(chave)) {
                return t;
            }
        }
        throw new AssertionError("Sem o total " + chave + " em " + relatorio.get("totais"));
    }

    private static JsonNode linhaDe(JsonNode relatorio, String campo, Object valor) {
        for (JsonNode l : relatorio.get("itens")) {
            if (l.get(campo).asString().equals(valor.toString())) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + campo + "=" + valor + " em " + relatorio.get("itens"));
    }

    // --- o resultado, linha a linha ---

    @Test
    void cadaLinhaDoResultadoBateComAContaFeitaAMao() throws Exception {
        JsonNode dre = resultado(ABRIL);
        reais(linha(dre, "VENDAS"), "235.00");
        reais(linha(dre, "DEVOLUCOES"), "50.00");
        reais(linha(dre, "RECEITA_LIQUIDA"), "185.00");
        reais(linha(dre, "CUSTO"), "44.50");
        reais(linha(dre, "LUCRO_BRUTO"), "140.50");
        reais(linha(dre, "COMISSOES"), "14.00");
        reais(linha(dre, "PERDAS"), "44.50");
        reais(linha(dre, "COBRADO"), "30.00");
        reais(linhaDoCaixa(dre, "Aluguel 2292"), "-300.00");
        reais(linhaDoCaixa(dre, "Material 2292"), "0.00");
        reais(linhaDoCaixa(dre, "Outras entradas 2292"), "20.00");
        reais(linha(dre, "RESULTADO"), "-168.00");
        // Só há linha de caixa das categorias com lançamento no período.
        assertThat(dre.get("linhas").findValues("chave").stream().filter(c -> c.asString().equals("CAIXA")).count()).isEqualTo(3);
        // A S3 vendeu 3 peças com o custo do cadastro; as peças da peça A têm compra.
        assertThat(dre.get("pecasComCustoDoCadastro").asLong()).isEqualTo(3);
        assertThat(linha(dre, "VENDAS").get("operacao").asString()).isEqualTo("SOMA");
        assertThat(linha(dre, "DEVOLUCOES").get("operacao").asString()).isEqualTo("SUBTRAI");
        assertThat(linha(dre, "RESULTADO").get("operacao").asString()).isEqualTo("TOTAL");
    }

    @Test
    void asTresMoedasSaoAsLinhasConvertidasCadaUmaPelaCotacaoDoSeuDia() throws Exception {
        JsonNode dre = resultado(ABRIL);
        // Venda e devolução pela cotação da venda (S1 e S3 até o dia 12; S2 no dia 15).
        moedas(linha(dre, "VENDAS"), "235.00", "49.25", "212500");
        moedas(linha(dre, "DEVOLUCOES"), "50.00", "10.00", "50000");
        moedas(linha(dre, "RECEITA_LIQUIDA"), "185.00", "39.25", "162500");
        moedas(linha(dre, "CUSTO"), "44.50", "9.50", "38500");
        moedas(linha(dre, "LUCRO_BRUTO"), "140.50", "29.75", "124000");
        // Comissão e cobrado pela cotação do fechamento do acerto (dias 18, 25 e 29: US$ 4,00 e Gs. 0,002), linha a linha:
        // 10,00 -> 2,50; 4,50 -> 1,13; -5,00 -> -1,25; 4,50 -> 1,13.
        moedas(linha(dre, "COMISSOES"), "14.00", "3.51", "7000");
        moedas(linha(dre, "COBRADO"), "30.00", "7.50", "15000");
        // Perdas pela cotação do dia do movimento: 12,00 (29/04) 3,00; 7,50 (25/04) 1,88; 10,00 (03/04) 2,00; 15,00 (26/04) 3,75.
        moedas(linha(dre, "PERDAS"), "44.50", "10.63", "27250");
        moedas(linhaDoCaixa(dre, "Aluguel 2292"), "-300.00", "-60.00", "-300000");
        moedas(linhaDoCaixa(dre, "Material 2292"), "0.00", "0.00", "0");
        moedas(linhaDoCaixa(dre, "Outras entradas 2292"), "20.00", "4.00", "20000");
        // O resultado é a soma das linhas já convertidas.
        moedas(linha(dre, "RESULTADO"), "-168.00", "-32.89", "-175250");
    }

    @Test
    void semCotacaoODolarEOGuaraniFicamVaziosEOsReaisContinuam() throws Exception {
        JsonNode dre = resultado("de=1999-12-01&ate=1999-12-31");
        JsonNode l = linhaDoCaixa(dre, "Outras sem cotação 2292");
        reais(l, "10.00");
        assertThat(l.get("valorEmDolar").isNull()).isTrue();
        assertThat(l.get("valorEmGuarani").isNull()).isTrue();
        reais(linha(dre, "RESULTADO"), "10.00");
        assertThat(linha(dre, "RESULTADO").get("valorEmDolar").isNull()).isTrue();
        assertThat(linha(dre, "RESULTADO").get("valorEmGuarani").isNull()).isTrue();
        // Um período sem nada devolve as linhas zeradas, nunca erro.
        JsonNode vazio = resultado("de=2200-01-01&ate=2200-01-31");
        reais(linha(vazio, "RESULTADO"), "0.00");
    }

    // --- o detalhamento, até o registro ---

    @Test
    void cadaLinhaAbreOsRegistrosQueAFormam() throws Exception {
        JsonNode vendas = detalhe("VENDAS", "");
        assertThat(vendas.get("itens")).hasSize(3);
        assertThat(totalDoDetalhe(vendas)).isEqualByComparingTo("235.00");
        assertThat(vendas.get("itens").findValues("data").stream().map(JsonNode::asString).sorted().toList())
                .containsExactly("2292-04-05", "2292-04-12", "2292-04-15");

        JsonNode devolucoes = detalhe("DEVOLUCOES", "");
        assertThat(devolucoes.get("itens")).hasSize(1);
        assertThat(totalDoDetalhe(devolucoes)).isEqualByComparingTo("50.00");
        assertThat(devolucoes.get("itens").get(0).get("data").asString()).isEqualTo("2292-04-05");

        JsonNode custo = detalhe("CUSTO", "");
        assertThat(custo.get("itens")).hasSize(3);
        assertThat(totalDoDetalhe(custo)).isEqualByComparingTo("44.50");
        JsonNode cadastro = custo.get("itens").findParents("custoDoCadastro").stream()
                .filter(i -> i.get("custoDoCadastro").asBoolean()).findFirst().orElseThrow();
        assertThat(cadastro.get("quantidade").asInt()).isEqualTo(3);
        assertThat(dec(cadastro.get("custoUnitario"))).isEqualByComparingTo("7.50");
        assertThat(custo.get("itens").findParents("custoDoCadastro").stream()
                .filter(i -> !i.get("custoDoCadastro").asBoolean()).map(i -> dec(i.get("custoUnitario")).toPlainString()).sorted()
                .toList()).containsExactly("10.00", "12.00");

        JsonNode comissoes = detalhe("COMISSOES", "");
        assertThat(comissoes.get("itens")).hasSize(4);
        assertThat(totalDoDetalhe(comissoes)).isEqualByComparingTo("14.00");

        JsonNode perdas = detalhe("PERDAS", "");
        assertThat(perdas.get("itens")).hasSize(4);
        assertThat(totalDoDetalhe(perdas)).isEqualByComparingTo("44.50");
        assertThat(perdas.get("itens").findValues("descricao").stream().map(JsonNode::asString).sorted().toList())
                .containsExactly("Avaria no estoque", "Falta no acerto", "Falta no acerto", "Inventário com menos peças");

        JsonNode cobrado = detalhe("COBRADO", "");
        assertThat(cobrado.get("itens")).hasSize(1);
        assertThat(totalDoDetalhe(cobrado)).isEqualByComparingTo("30.00");

        JsonNode materiais = detalhe("CAIXA", "&categoriaId=" + material);
        assertThat(materiais.get("itens")).hasSize(2);
        assertThat(totalDoDetalhe(materiais)).isEqualByComparingTo("0.00");
        assertThat(totalDoDetalhe(detalhe("CAIXA", "&categoriaId=" + aluguel))).isEqualByComparingTo("-300.00");
        assertThat(totalDoDetalhe(detalhe("CAIXA", ""))).isEqualByComparingTo("-280.00");
    }

    // --- lucro por produto e por vendedor ---

    @Test
    void lucroPorProdutoComOCustoDoDiaDaVendaEAMargem() throws Exception {
        JsonNode r = corpoDe(ler(FIN + "/lucro-por-produto?" + ABRIL, dona()).andExpect(status().isOk()));
        assertThat(r.get("itens")).hasSize(2);
        JsonNode a = linhaDe(r, "produtoId", pecaA);
        assertThat(a.get("pecas").asLong()).isEqualTo(2);
        assertThat(dec(a.get("receita"))).isEqualByComparingTo("95.00");
        assertThat(dec(a.get("custo"))).isEqualByComparingTo("22.00");
        assertThat(dec(a.get("lucro"))).isEqualByComparingTo("73.00");
        assertThat(dec(a.get("margem"))).isEqualByComparingTo("76.84");
        assertThat(a.get("custoDoCadastro").asBoolean()).isFalse();
        assertThat(a.get("fotoPrincipalHash").isNull()).isTrue();
        JsonNode b = linhaDe(r, "produtoId", pecaB);
        assertThat(dec(b.get("receita"))).isEqualByComparingTo("90.00");
        assertThat(dec(b.get("custo"))).isEqualByComparingTo("22.50");
        assertThat(dec(b.get("lucro"))).isEqualByComparingTo("67.50");
        assertThat(dec(b.get("margem"))).isEqualByComparingTo("75.00");
        assertThat(b.get("custoDoCadastro").asBoolean()).isTrue();
        assertThat(dec(total(r, "receita").get("valorEmReais"))).isEqualByComparingTo("185.00");
        assertThat(dec(total(r, "custo").get("valorEmReais"))).isEqualByComparingTo("44.50");
        assertThat(dec(total(r, "lucro").get("valorEmReais"))).isEqualByComparingTo("140.50");
        assertThat(dec(total(r, "lucro").get("valorEmDolar"))).isEqualByComparingTo("29.75");
        assertThat(dec(total(r, "lucro").get("valorEmGuarani"))).isEqualByComparingTo("124000");
    }

    @Test
    void lucroPorProdutoRespeitaOsFiltros() throws Exception {
        JsonNode soA = corpoDe(ler(FIN + "/lucro-por-produto?" + ABRIL + "&categoriaId=" + categoriaA, dona()).andExpect(status().isOk()));
        assertThat(soA.get("itens")).hasSize(1);
        assertThat(dec(total(soA, "lucro").get("valorEmReais"))).isEqualByComparingTo("73.00");
        JsonNode doV2 = corpoDe(ler(FIN + "/lucro-por-produto?" + ABRIL + "&vendedorId=" + v2.vendedorId(), dona()).andExpect(status().isOk()));
        assertThat(doV2.get("itens")).hasSize(1);
        assertThat(dec(total(doV2, "lucro").get("valorEmReais"))).isEqualByComparingTo("67.50");
        JsonNode umProduto = corpoDe(ler(FIN + "/lucro-por-produto?" + ABRIL + "&produtoId=" + pecaB, dona()).andExpect(status().isOk()));
        assertThat(umProduto.get("itens")).hasSize(1);
        JsonNode busca = corpoDe(ler(FIN + "/lucro-por-produto?" + ABRIL + "&busca=" + categoriaDoNome(pecaA), dona()).andExpect(status().isOk()));
        assertThat(busca.get("itens")).hasSize(1);
    }

    private String categoriaDoNome(UUID produto) {
        return jdbc.queryForObject("SELECT p.nome FROM corona.produtos p WHERE p.id = ?", String.class, produto).substring(6);
    }

    @Test
    void lucroPorVendedorTiraAComissaoDoAcerto() throws Exception {
        JsonNode r = corpoDe(ler(FIN + "/lucro-por-vendedor?" + ABRIL, dona()).andExpect(status().isOk()));
        assertThat(r.get("itens")).hasSize(2);
        JsonNode a = linhaDe(r, "vendedorId", v1.vendedorId());
        assertThat(dec(a.get("receita"))).isEqualByComparingTo("95.00");
        assertThat(dec(a.get("custo"))).isEqualByComparingTo("22.00");
        assertThat(dec(a.get("lucroBruto"))).isEqualByComparingTo("73.00");
        assertThat(dec(a.get("comissao"))).isEqualByComparingTo("9.50");
        assertThat(dec(a.get("lucro"))).isEqualByComparingTo("63.50");
        assertThat(a.get("praca").asString()).isNotBlank();
        JsonNode b = linhaDe(r, "vendedorId", v2.vendedorId());
        assertThat(dec(b.get("receita"))).isEqualByComparingTo("90.00");
        assertThat(dec(b.get("custo"))).isEqualByComparingTo("22.50");
        assertThat(dec(b.get("comissao"))).isEqualByComparingTo("4.50");
        assertThat(dec(b.get("lucro"))).isEqualByComparingTo("63.00");
        assertThat(dec(total(r, "receita").get("valorEmReais"))).isEqualByComparingTo("185.00");
        assertThat(dec(total(r, "custo").get("valorEmReais"))).isEqualByComparingTo("44.50");
        assertThat(dec(total(r, "lucroBruto").get("valorEmReais"))).isEqualByComparingTo("140.50");
        assertThat(dec(total(r, "comissao").get("valorEmReais"))).isEqualByComparingTo("14.00");
        assertThat(dec(total(r, "lucro").get("valorEmReais"))).isEqualByComparingTo("126.50");
        // Filtros: por vendedor e por praça.
        JsonNode soV2 = corpoDe(ler(FIN + "/lucro-por-vendedor?" + ABRIL + "&vendedorId=" + v2.vendedorId(), dona()).andExpect(status().isOk()));
        assertThat(soV2.get("itens")).hasSize(1);
        JsonNode daPraca = corpoDe(ler(FIN + "/lucro-por-vendedor?" + ABRIL + "&pracaId=" + pracaA, dona()).andExpect(status().isOk()));
        assertThat(daPraca.get("itens")).hasSize(1);
        assertThat(dec(total(daPraca, "lucro").get("valorEmReais"))).isEqualByComparingTo("63.50");
    }

    // --- uma fórmula só ---

    @Test
    void oResultadoBateComOsRelatoriosDeVendasDeComissoesDeAcertosEDeCaixa() throws Exception {
        JsonNode dre = resultado(ABRIL);
        BigDecimal receita = dec(linha(dre, "RECEITA_LIQUIDA").get("valorEmReais"));
        // Relatório de vendas (8.5) e curva ABC: o mesmo vendido líquido.
        JsonNode porProduto = corpoDe(ler("/api/dona/relatorios/vendas/por-produto?" + ABRIL, dona()).andExpect(status().isOk()));
        assertThat(dec(total(porProduto, "liquido").get("valorEmReais"))).isEqualByComparingTo(receita);
        assertThat(dec(total(porProduto, "vendido").get("valorEmReais"))).isEqualByComparingTo(dec(linha(dre, "VENDAS").get("valorEmReais")));
        assertThat(dec(total(porProduto, "devolvido").get("valorEmReais")))
                .isEqualByComparingTo(dec(linha(dre, "DEVOLUCOES").get("valorEmReais")));
        JsonNode curva = corpoDe(ler("/api/dona/relatorios/estoque/curva-abc?" + ABRIL, dona()).andExpect(status().isOk()));
        BigDecimal somaDaCurva = BigDecimal.ZERO;
        for (JsonNode l : curva.get("itens")) {
            somaDaCurva = somaDaCurva.add(dec(l.get("liquido")));
        }
        assertThat(somaDaCurva).isEqualByComparingTo(receita);
        // O lucro por produto e por vendedor saem da mesma consulta.
        BigDecimal lucroBruto = dec(linha(dre, "LUCRO_BRUTO").get("valorEmReais"));
        JsonNode lp = corpoDe(ler(FIN + "/lucro-por-produto?" + ABRIL, dona()).andExpect(status().isOk()));
        assertThat(dec(total(lp, "lucro").get("valorEmReais"))).isEqualByComparingTo(lucroBruto);
        BigDecimal comissoes = dec(linha(dre, "COMISSOES").get("valorEmReais"));
        JsonNode lv = corpoDe(ler(FIN + "/lucro-por-vendedor?" + ABRIL, dona()).andExpect(status().isOk()));
        assertThat(dec(total(lv, "lucro").get("valorEmReais"))).isEqualByComparingTo(lucroBruto.subtract(comissoes));
        // A comissão do resultado é a do relatório de comissões (7.12) e o cobrado é o das faltas do relatório de acertos.
        JsonNode rc = corpoDe(ler(FIN + "/comissoes?" + ABRIL, dona()).andExpect(status().isOk()));
        assertThat(dec(total(rc, "comissao").get("valorEmReais"))).isEqualByComparingTo(comissoes);
        JsonNode ra = corpoDe(ler(FIN + "/acertos?" + ABRIL, dona()).andExpect(status().isOk()));
        assertThat(dec(total(ra, "faltas").get("valorEmReais"))).isEqualByComparingTo(dec(linha(dre, "COBRADO").get("valorEmReais")));
        // Cada categoria do caixa é o fluxo de caixa só dos lançamentos manuais daquela categoria.
        for (String categoria : List.of("Aluguel 2292", "Material 2292", "Outras entradas 2292")) {
            JsonNode fluxo = corpoDe(ler(FIN + "/fluxo-de-caixa?" + ABRIL + "&origem=MANUAL&busca=" + categoria.split(" ")[0], dona())
                    .andExpect(status().isOk()));
            assertThat(dec(total(fluxo, "saldo").get("valorEmReais"))).as(categoria)
                    .isEqualByComparingTo(dec(linhaDoCaixa(dre, categoria).get("valorEmReais")));
        }
    }

    @Test
    void oDetalhamentoDeCadaLinhaFechaComAPropriaLinha() throws Exception {
        JsonNode dre = resultado(ABRIL);
        for (String chave : List.of("VENDAS", "DEVOLUCOES", "CUSTO", "COMISSOES", "PERDAS", "COBRADO")) {
            assertThat(totalDoDetalhe(detalhe(chave, ""))).as(chave).isEqualByComparingTo(dec(linha(dre, chave).get("valorEmReais")));
        }
    }

    // --- datas: a devolução e a falta que chegam depois ---

    @Test
    void devolucaoQueChegaDepoisAbateOMesDaVendaEOMesSeguinteNaoGanhaLinha() throws Exception {
        // Venda de 80,00 em 10/06; devolvida inteira em 05/07. O custo da peça devolvida volta junto (a peça boa volta ao estoque).
        JsonNode junho = resultado(mesDaDevolucaoTardia);
        reais(linha(junho, "VENDAS"), "80.00");
        reais(linha(junho, "DEVOLUCOES"), "80.00");
        reais(linha(junho, "RECEITA_LIQUIDA"), "0.00");
        reais(linha(junho, "CUSTO"), "0.00");
        reais(linha(junho, "LUCRO_BRUTO"), "0.00");
        JsonNode julho = resultado("de=2292-07-01&ate=2292-07-31");
        reais(linha(julho, "VENDAS"), "0.00");
        reais(linha(julho, "DEVOLUCOES"), "0.00");
        reais(linha(julho, "RESULTADO"), "0.00");
        // O relatório de vendas de junho mostra o mesmo número.
        JsonNode vendasDeJunho = corpoDe(ler("/api/dona/relatorios/vendas/por-produto?" + mesDaDevolucaoTardia + "&produtoId=" + pecaC, dona())
                .andExpect(status().isOk()));
        assertThat(dec(total(vendasDeJunho, "liquido").get("valorEmReais"))).isEqualByComparingTo("0.00");
        assertThat(dec(total(vendasDeJunho, "devolvido").get("valorEmReais"))).isEqualByComparingTo("80.00");
    }

    @Test
    void faltaEncontradaDepoisEntraNoMesEmQueFoiEncontradaSemMudarOAnterior() throws Exception {
        // A falta de 1 x C (custo do cadastro 20,00) no acerto de 10/08 e encontrada em 20/09.
        reais(linha(resultado(mesDaFaltaEncontrada), "PERDAS"), "20.00");
        reais(linha(resultado(mesSeguinteDaFalta), "PERDAS"), "-20.00");
        reais(linha(resultado(mesSeguinteDaFalta), "RESULTADO"), "20.00");
    }

    // --- pedidos mal feitos e só leitura ---

    @Test
    void periodoEObrigatorioEAsLinhasDesconhecidasRecebem400() throws Exception {
        for (String rota : List.of("/resultado", "/resultado/detalhamento?linha=VENDAS", "/lucro-por-produto", "/lucro-por-vendedor")) {
            String ligacao = rota.contains("?") ? "&" : "?";
            ler(FIN + rota, dona()).andExpect(status().isBadRequest());
            ler(FIN + rota + ligacao + "de=2292-04-01", dona()).andExpect(status().isBadRequest());
            ler(FIN + rota + ligacao + "ate=2292-04-30", dona()).andExpect(status().isBadRequest());
            ler(FIN + rota + ligacao + "de=2292-04-30&ate=2292-04-01", dona()).andExpect(status().isBadRequest());
        }
        String mensagem = ler(FIN + "/resultado", dona()).andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(mensagem).contains("Escolha o período");
        ler(FIN + "/resultado/detalhamento?" + ABRIL + "&linha=NAO_EXISTE", dona()).andExpect(status().isBadRequest());
        ler(FIN + "/resultado/detalhamento?" + ABRIL + "&linha=CAIXA&categoriaId=nao-e-uuid", dona()).andExpect(status().isBadRequest());
        ler(FIN + "/lucro-por-produto?" + ABRIL + "&produtoId=nao-e-uuid", dona()).andExpect(status().isBadRequest());
        ler(FIN + "/lucro-por-produto?" + ABRIL + "&tamanho=101", dona()).andExpect(status().isBadRequest());
        ler(FIN + "/lucro-por-vendedor?" + ABRIL + "&vendedorId=nao-e-uuid", dona()).andExpect(status().isBadRequest());
    }

    @Test
    void asQuatroRotasNaoGravamNada() throws Exception {
        List<String> tabelas = List.of("vendas", "itens_venda", "devolucoes", "itens_devolucao", "acertos", "acerto_eventos", "comissoes",
                "pendencias_acerto", "movimentacoes_estoque", "lancamentos_caixa", "compras", "historico_custo", "produtos",
                "produtos_custos", "contas_receber");
        List<String> antes = retrato(tabelas);
        resultado(ABRIL);
        detalhe("PERDAS", "");
        ler(FIN + "/lucro-por-produto?" + ABRIL, dona()).andExpect(status().isOk());
        ler(FIN + "/lucro-por-vendedor?" + ABRIL, dona()).andExpect(status().isOk());
        assertThat(retrato(tabelas)).isEqualTo(antes);
    }

    private List<String> retrato(List<String> tabelas) {
        List<String> r = new ArrayList<>();
        for (String t : tabelas) {
            r.add(t + ": " + jdbc.queryForObject("SELECT count(*) || ' / ' || coalesce(max(created_at)::text, '-') FROM corona." + t,
                    String.class));
        }
        return r;
    }
}
