package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.RodadasDeTeste;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Fechamento da Fase 8 (tarefa 8.13): a semana inteira, de ponta a ponta, pela API. A massa é a do painel (quatro vendedores, em
 * julho de 2293, semana nº 9104; conta feita à mão em {@link PainelSemanalTest}) e aqui se confere que o painel, o resultado do
 * período (DRE), o lucro, a curva ABC e os relatórios de vendas contam a mesma semana com os mesmos números, e que a conferência
 * diária fica sem diferença sobre ela e, depois de um dado estragado de propósito, avisa. Nenhuma regra nova: só leitura.
 *
 * <pre>
 * Vendido líquido da semana = 615,00 (Ana 340,00 + Bia 100,00 + Cris 75,00 + Dani 100,00); vendido 715,00 menos devolvido 100,00.
 * Peça A (50,00, custo 10,00): 11 vendidas, 1 devolvida: 500,00 líquido, custo 100,00.
 * Peça B (25,00, custo 5,00): 7 vendidas (2 com 10,00 de desconto), 2 devolvidas na troca: 115,00 líquido, custo 25,00.
 * Custo das peças vendidas 125,00 (as duas sem compra: custo do cadastro); lucro bruto 490,00.
 * Comissões 166,00 (Ana 136,00 e Bia 30,00; Cris e Dani ainda sem acerto fechado); lucro depois das comissões 324,00.
 * Perdas 5,00 (a falta de 1 x B da Ana, pelo custo) e cobrado do vendedor 25,00 (o preço dessa falta).
 * Resultado = 490,00 - 166,00 - 5,00 + 25,00 = 344,00.
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FechamentoDaSemanaTest extends MassaDoPainel {

    private static final String FIN = "/api/dona/relatorios/financeiro";
    private static final String VENDAS = "/api/dona/relatorios/vendas";
    private static final String CURVA = "/api/dona/relatorios/estoque/curva-abc";
    private static final String ROTA_DA_CONFERENCIA = "/api/dona/conferencias";

    private String mesTodo;

    @BeforeAll
    void massaConhecida() throws Exception {
        montarAMassa("2293-07", 9104);
        mesTodo = "de=" + d(1) + "&ate=" + d(31);
    }

    @AfterAll
    void tirarAMassa() throws SQLException {
        restaurarAComissao();
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
        // A última rodada tem de ficar limpa para os outros testes (o aviso da tela inicial olha só a última).
        RodadasDeTeste.limpa(jdbc, java.time.Instant.now().plusMillis(5));
    }

    // --- apoio ---

    private JsonNode painel() throws Exception {
        return corpoDe(ler("/api/dona/painel-semanal?semana=" + semana, dona()).andExpect(status().isOk()));
    }

    private JsonNode relatorio(String rota) throws Exception {
        return corpoDe(ler(rota + (rota.contains("?") ? "&" : "?") + "tamanho=100", dona()).andExpect(status().isOk()));
    }

    private JsonNode dre() throws Exception {
        return relatorio(FIN + "/resultado?" + mesTodo);
    }

    private static JsonNode linhaDoDre(JsonNode dre, String chave) {
        for (JsonNode l : dre.get("linhas")) {
            if (l.get("chave").asString().equals(chave)) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + chave + " em " + dre.get("linhas"));
    }

    private static BigDecimal doDre(JsonNode dre, String chave) {
        return dec(linhaDoDre(dre, chave).get("valorEmReais"));
    }

    private static JsonNode rodape(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (t.get("chave").asString().equals(chave)) {
                return t;
            }
        }
        throw new AssertionError("Sem o total " + chave + " em " + relatorio.get("totais"));
    }

    private static BigDecimal reaisDoRodape(JsonNode relatorio, String chave) {
        return dec(rodape(relatorio, chave).get("valorEmReais"));
    }

    private static JsonNode linhaDe(JsonNode relatorio, String campo, Object valor) {
        for (JsonNode l : relatorio.get("itens")) {
            if (l.get(campo).asString().equals(valor.toString())) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + campo + "=" + valor + " em " + relatorio.get("itens"));
    }

    private Set<String> idsDaSemana() {
        Set<String> ids = new HashSet<>();
        for (UUID id : List.of(pecaA, pecaB, m1, r1, m2, m3, m4, s1, s2, acertoAna, acertoBia, acertoDani)) {
            ids.add(id.toString());
        }
        for (var v : List.of(ana, bia, cris, dani)) {
            ids.add(v.vendedorId().toString());
        }
        ids.addAll(jdbc.queryForList("SELECT id::text FROM corona.vendas WHERE vendedor_id IN (?, ?, ?, ?)", String.class,
                ana.vendedorId(), bia.vendedorId(), cris.vendedorId(), dani.vendedorId()));
        return ids;
    }

    /** Pede "Conferir agora" pela rota da dona e devolve a resposta. */
    private JsonNode conferirAgora() throws Exception {
        return corpoDe(postar(ROTA_DA_CONFERENCIA, dona(), mapa("id", UUID.randomUUID())).andExpect(status().isCreated()));
    }

    private List<JsonNode> diferencasDaSemana(JsonNode resposta) {
        Set<String> meus = idsDaSemana();
        return resposta.get("grupos").valueStream().flatMap(g -> g.get("diferencas").valueStream())
                .filter(d -> !d.get("referencia").isNull() && meus.contains(d.get("referencia").asString())).toList();
    }

    private void mexer(String comando) throws Exception {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            s.execute("ALTER TABLE corona.itens_venda DISABLE TRIGGER USER");
            s.execute(comando);
            s.execute("ALTER TABLE corona.itens_venda ENABLE TRIGGER USER");
            con.commit();
        }
    }

    // --- a semana bate consigo mesma ---

    @Test
    void oResultadoDoMesBateComAContaFeitaAMao() throws Exception {
        JsonNode dre = dre();
        assertThat(doDre(dre, "VENDAS")).isEqualByComparingTo("715.00");
        assertThat(doDre(dre, "DEVOLUCOES")).isEqualByComparingTo("100.00");
        assertThat(doDre(dre, "RECEITA_LIQUIDA")).isEqualByComparingTo("615.00");
        assertThat(doDre(dre, "CUSTO")).isEqualByComparingTo("125.00");
        assertThat(doDre(dre, "LUCRO_BRUTO")).isEqualByComparingTo("490.00");
        assertThat(doDre(dre, "COMISSOES")).isEqualByComparingTo("166.00");
        assertThat(doDre(dre, "PERDAS")).isEqualByComparingTo("5.00");
        assertThat(doDre(dre, "COBRADO")).isEqualByComparingTo("25.00");
        assertThat(doDre(dre, "RESULTADO")).isEqualByComparingTo("344.00");
    }

    @Test
    void oPainelOResultadoACurvaEAsVendasContamOMesmoVendidoLiquido() throws Exception {
        JsonNode p = painel();
        BigDecimal dasLinhas = BigDecimal.ZERO;
        for (JsonNode l : p.get("pedidos")) {
            dasLinhas = dasLinhas.add(dec(l.get("vendidoLiquido").get("emReais")));
        }
        BigDecimal blocoDeVendas = dec(p.get("totais").get("vendas").get("liquido").get("emReais"));
        BigDecimal receita = doDre(dre(), "RECEITA_LIQUIDA");
        JsonNode porProduto = relatorio(VENDAS + "/por-produto?" + mesTodo);
        JsonNode curva = relatorio(CURVA + "?" + mesTodo);
        BigDecimal somaDaCurva = BigDecimal.ZERO;
        for (JsonNode l : curva.get("itens")) {
            somaDaCurva = somaDaCurva.add(dec(l.get("liquido")));
        }
        JsonNode vendas = relatorio(VENDAS + "/vendas?" + mesTodo);

        assertThat(dasLinhas).as("soma das linhas do painel").isEqualByComparingTo("615.00");
        assertThat(blocoDeVendas).as("bloco Vendas do painel").isEqualByComparingTo(dasLinhas);
        assertThat(receita).as("receita líquida do resultado").isEqualByComparingTo(dasLinhas);
        assertThat(somaDaCurva).as("soma da curva ABC").isEqualByComparingTo(dasLinhas);
        assertThat(reaisDoRodape(porProduto, "liquido")).as("vendas por produto").isEqualByComparingTo(dasLinhas);
        assertThat(reaisDoRodape(vendas, "liquido")).as("relatório de vendas").isEqualByComparingTo(dasLinhas);
        assertThat(rodape(vendas, "liquido").get("quantidade").asLong()).isEqualTo(7);
        assertThat(p.get("totais").get("vendas").get("quantidade").asLong()).isEqualTo(7);
    }

    @Test
    void cadaPecaTemOMesmoLiquidoNaCurvaNoLucroENasVendasPorProduto() throws Exception {
        JsonNode curva = relatorio(CURVA + "?" + mesTodo);
        JsonNode lucro = relatorio(FIN + "/lucro-por-produto?" + mesTodo);
        JsonNode porProduto = relatorio(VENDAS + "/por-produto?" + mesTodo);
        assertThat(curva.get("itens")).hasSize(2);
        // A peça A tem 500,00 de 615,00 (81,30%): classe A; a peça B fica na B (cruza os 95%: 100% e antes 81,30% >= 80%).
        assertThat(curva.get("itens").get(0).get("produtoId").asString()).isEqualTo(pecaA.toString());
        assertThat(curva.get("itens").get(0).get("classe").asString()).isEqualTo("A");
        assertThat(curva.get("itens").get(1).get("produtoId").asString()).isEqualTo(pecaB.toString());
        assertThat(curva.get("itens").get(1).get("classe").asString()).isEqualTo("B");
        for (var par : List.of(new Object[] {pecaA, "500.00", "100.00"}, new Object[] {pecaB, "115.00", "25.00"})) {
            UUID peca = (UUID) par[0];
            assertThat(dec(linhaDe(curva, "produtoId", peca).get("liquido"))).as("curva").isEqualByComparingTo((String) par[1]);
            JsonNode doLucro = linhaDe(lucro, "produtoId", peca);
            assertThat(dec(doLucro.get("receita"))).as("receita no lucro").isEqualByComparingTo((String) par[1]);
            assertThat(dec(doLucro.get("custo"))).as("custo no lucro").isEqualByComparingTo((String) par[2]);
            assertThat(dec(linhaDe(porProduto, "chave", peca).get("liquido"))).as("vendas por produto")
                    .isEqualByComparingTo((String) par[1]);
        }
    }

    @Test
    void oLucroPorProdutoEPorVendedorSomamOQueODreMostra() throws Exception {
        JsonNode dre = dre();
        BigDecimal lucroBruto = doDre(dre, "LUCRO_BRUTO");
        BigDecimal comissoes = doDre(dre, "COMISSOES");
        JsonNode porProduto = relatorio(FIN + "/lucro-por-produto?" + mesTodo);
        JsonNode porVendedor = relatorio(FIN + "/lucro-por-vendedor?" + mesTodo);
        assertThat(reaisDoRodape(porProduto, "lucro")).isEqualByComparingTo(lucroBruto);
        assertThat(reaisDoRodape(porVendedor, "lucroBruto")).isEqualByComparingTo(lucroBruto);
        assertThat(reaisDoRodape(porVendedor, "comissao")).isEqualByComparingTo(comissoes);
        assertThat(reaisDoRodape(porVendedor, "lucro")).isEqualByComparingTo("324.00");
        assertThat(reaisDoRodape(porVendedor, "lucro")).isEqualByComparingTo(lucroBruto.subtract(comissoes));
        JsonNode daAna = linhaDe(porVendedor, "vendedorId", ana.vendedorId());
        assertThat(dec(daAna.get("receita"))).isEqualByComparingTo("340.00");
        assertThat(dec(daAna.get("comissao"))).isEqualByComparingTo("136.00");
        JsonNode daBia = linhaDe(porVendedor, "vendedorId", bia.vendedorId());
        assertThat(dec(daBia.get("receita"))).isEqualByComparingTo("100.00");
        assertThat(dec(daBia.get("comissao"))).isEqualByComparingTo("30.00");
    }

    @Test
    void cadaVendedorTemOMesmoVendidoNoPainelENoRankingDeVendas() throws Exception {
        JsonNode p = painel();
        JsonNode ranking = relatorio(VENDAS + "/ranking-de-vendedores?" + mesTodo);
        for (var v : List.of(ana, bia, cris, dani)) {
            BigDecimal doPainel = BigDecimal.ZERO;
            for (JsonNode l : p.get("pedidos")) {
                if (l.get("vendedorId").asString().equals(v.vendedorId().toString())) {
                    doPainel = doPainel.add(dec(l.get("vendidoLiquido").get("emReais")));
                }
            }
            assertThat(dec(linhaDe(ranking, "chave", v.vendedorId()).get("liquido"))).as(v.nome()).isEqualByComparingTo(doPainel);
        }
        // Ticket médio: líquido dividido pelo número de vendas, arredondado só em Dinheiro (Ana: 340,00 em 2 vendas).
        JsonNode daAna = linhaDe(ranking, "chave", ana.vendedorId());
        assertThat(daAna.get("vendas").asLong()).isEqualTo(2);
        assertThat(dec(daAna.get("ticketMedio"))).isEqualByComparingTo("170.00");
    }

    @Test
    void aComissaoDoPainelEAMesmaDoResultadoEDoRelatorioDeComissoes() throws Exception {
        BigDecimal acertadas = BigDecimal.ZERO;
        for (JsonNode l : painel().get("pedidos")) {
            if (l.get("situacao").asString().equals("ACERTADO")) {
                acertadas = acertadas.add(dec(l.get("comissao").get("emReais")));
            }
        }
        assertThat(acertadas).isEqualByComparingTo(doDre(dre(), "COMISSOES"));
        assertThat(reaisDoRodape(relatorio(FIN + "/comissoes?" + mesTodo), "comissao")).isEqualByComparingTo(acertadas);
    }

    @Test
    void oVendedorNuncaVeOPainelNemOResultadoNemACurvaNemAConferencia() throws Exception {
        for (String rota : List.of("/api/dona/painel-semanal?semana=" + semana, FIN + "/resultado?" + mesTodo,
                CURVA + "?" + mesTodo, VENDAS + "/ranking-de-vendedores?" + mesTodo, ROTA_DA_CONFERENCIA)) {
            ler(rota, como(ana)).andExpect(status().isForbidden());
        }
    }

    // --- a conferência diária sobre a semana ---

    @Test
    void aRodadaDaConferenciaFicaSemDiferencaEDepoisAvisaUmDadoEstragado() throws Exception {
        // 1. A semana inteira confere: nenhuma das treze conferências acha diferença nos registros desta semana.
        JsonNode limpa = conferirAgora();
        assertThat(limpa.get("rodada").get("chavesConferidas").size()).isEqualTo(13);
        assertThat(limpa.get("rodada").get("chavesComFalha")).isEmpty();
        assertThat(diferencasDaSemana(limpa)).as("nada da semana fora do lugar").isEmpty();
        int totalLimpa = limpa.get("rodada").get("totalDiferencas").asInt();
        assertThat(limpa.get("rodada").get("situacao").asString()).isEqualTo(totalLimpa == 0 ? "SEM_DIFERENCA" : "COM_DIFERENCA");

        // 2. Estraga de propósito: a venda S1 passa a ter 1 x A a mais do que saiu do estoque.
        mexer("UPDATE corona.itens_venda SET quantidade = quantidade + 1 WHERE venda_id = '" + s1 + "' AND produto_id = '" + pecaA + "'");
        try {
            JsonNode suja = conferirAgora();
            assertThat(suja.get("rodada").get("situacao").asString()).isEqualTo("COM_DIFERENCA");
            assertThat(suja.get("rodada").get("chavesComFalha")).isEmpty();
            assertThat(diferencasDaSemana(suja)).as("a rodada achou o dado estragado").isNotEmpty();
            assertThat(suja.get("rodada").get("totalDiferencas").asInt()).isGreaterThan(totalLimpa);

            // 3. O aviso da tela inicial fala da última rodada.
            JsonNode avisos = corpoDe(ler("/api/dona/avisos", dona()).andExpect(status().isOk()));
            JsonNode aviso = avisos.get("avisos").valueStream().filter(a -> "CONFERENCIA_COM_DIFERENCA".equals(a.get("tipo").asString()))
                    .findFirst().orElseThrow(() -> new AssertionError("Sem o aviso da conferência em " + avisos));
            assertThat(aviso.get("titulo").asString()).contains("conferência");
        } finally {
            mexer("UPDATE corona.itens_venda SET quantidade = quantidade - 1 WHERE venda_id = '" + s1 + "' AND produto_id = '" + pecaA
                    + "'");
        }

        // 4. Corrigido o dado, a rodada seguinte volta a ficar sem diferença nesta semana e o aviso some.
        JsonNode volta = conferirAgora();
        assertThat(diferencasDaSemana(volta)).isEmpty();
        if (volta.get("rodada").get("totalDiferencas").asInt() == 0) {
            JsonNode avisos = corpoDe(ler("/api/dona/avisos", dona()).andExpect(status().isOk()));
            assertThat(avisos.get("avisos").valueStream().map(a -> a.get("tipo").asString()))
                    .doesNotContain("CONFERENCIA_COM_DIFERENCA");
        }
    }
}
