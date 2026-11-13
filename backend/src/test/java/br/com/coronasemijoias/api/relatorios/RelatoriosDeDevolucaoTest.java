package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes.PedidoDeAutorizacao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Relatórios de devolução e troca da dona e do vendedor, e as conferências do §13 (tarefa 6.10; ADR-0014, itens 9 a 12;
 * §12 e §13). Massa conhecida, com o resultado calculado à mão, em março e abril de 1991 (antes de qualquer cotação
 * dos outros testes, que usam datas de 1990 ou de hoje); as cotações de 1991 são as do teste:
 *
 * <pre>
 * 04/03/1991: US$ 5,00 e Gs. 0,001 real (R$ 1 = Gs. 1.000)    05/03/1991 em diante: US$ 4,00 e Gs. 0,002 real (R$ 1 = Gs. 500)
 *
 * Vendedor V1 (peças P1 100, P2 80, P3 130, P4 50), vendas em 04/03 ao meio-dia:
 *   venda 1 (cliente A): 3 x P1 = 300, dinheiro     venda 2 (cliente A): 2 x P2 = 160, Pix     venda 3 (cliente B): 1 x P1 = 100, Pix
 * Em 05/03:
 *   Dev1: venda 1, desistência, 1 x P1 boa = 100, dinheiro       Dev2: venda 2, defeito, 1 x P2 com defeito = 80, Pix
 *   Dev3: venda 1, não serviu, 1 x P1 = 100, dinheiro, CANCELADA no mesmo dia
 *   Troca 1: venda 3 (P1, 100) por P3 (130): diferença +30 no Pix, crédito de 100 usado inteiro
 *   Troca 2: venda 2 (1 x P2, 80, não serviu) por P4 (50): crédito de 80, usa 50, sobram 30 em aberto
 * Vendedor V2 (peça P5 70): vendas 4 (2 x P5 = 140) e 5 (1 x P5 = 70) em 04/03; em 20/04 pede autorização para a venda 4
 * (fica esperando) e para a venda 5 (a dona aprova).
 * </pre>
 *
 * Os testes dos relatórios usam só estes lançamentos (filtro de vendedor e de período); as conferências olham o banco
 * inteiro, e cada teste que mexe nelas desfaz o que fez no fim.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RelatoriosDeDevolucaoTest extends ApoioDasTrocas {

    private static final String REL = "/api/dona/relatorios/devolucoes/";
    private static final String PERIODO_DE_MARCO = "de=1991-03-01&ate=1991-03-31";

    private static final Instant D4 = brasilia("1991-03-04T12:00:00");
    private static final Instant D5_MANHA = brasilia("1991-03-05T10:00:00");
    private static final Instant D5_11H = brasilia("1991-03-05T11:00:00");
    private static final Instant D5_14H = brasilia("1991-03-05T14:00:00");
    private static final Instant D5_16H = brasilia("1991-03-05T16:00:00");
    private static final Instant D5_17H = brasilia("1991-03-05T17:00:00");
    private static final Instant D5_18H = brasilia("1991-03-05T18:00:00");
    private static final Instant D20_ABRIL = brasilia("1991-04-20T12:00:00");

    @Autowired
    private ServicoDeAutorizacoes autorizacoes;

    private UsuarioDeTeste v1;
    private UsuarioDeTeste v2;
    private UUID clienteA;
    private UUID clienteB;
    private UUID p2;
    private UUID p1;
    private UUID maletaV1;
    private UUID venda1;
    private UUID venda2;
    private UUID venda3;
    private UUID dev1;
    private UUID dev2;
    private UUID dev3;
    private UUID troca1;
    private UUID troca2;
    private UUID credito1;
    private UUID credito2;
    private UUID maletaV2;
    private UUID venda4;
    private UUID venda5;
    private UUID pedidoEsperando;
    private UUID pedidoAprovado;

    @BeforeAll
    void massaConhecida() throws Exception {
        cotacao("1991-03-04", "5.00000000", "0.00100000");
        cotacao("1991-03-05", "4.00000000", "0.00200000");

        v1 = usuarios.criarVendedor();
        clienteA = cliente(v1);
        clienteB = cliente(v1);
        p1 = peca("100.00", 20);
        p2 = peca("80.00", 20);
        UUID p3 = peca("130.00", 20);
        UUID p4 = peca("50.00", 20);
        maletaV1 = maleta(v1, p1, 8, p2, 8, p3, 8, p4, 8);

        venda1 = vendaEm(v1, maletaV1, clienteA, p1, 3, "300.00", "DINHEIRO", D4);
        venda2 = vendaEm(v1, maletaV1, clienteA, p2, 2, "160.00", "PIX", D4);
        venda3 = vendaEm(v1, maletaV1, clienteB, p1, 1, "100.00", "PIX", D4);

        dev1 = devolver(v1, venda1, "ARREPENDIMENTO", p1, "BOA", "DINHEIRO", D5_MANHA);
        dev2 = devolver(v1, venda2, "DEFEITO", p2, "DEFEITO", "PIX", D5_11H);
        dev3 = devolver(v1, venda1, "TAMANHO", p1, "BOA", "DINHEIRO", D5_11H);
        devolucoes.cancelar(dev3, v1.vendedorId(), v1.id(), usuarios.aparelhoDe(v1), UUID.randomUUID(),
                "O cliente voltou atrás", D5_18H);

        var t1 = pedidoDoServico(venda3, "ARREPENDIMENTO", List.of(new PecaDoPedido(p1, 1, "BOA")), null, maletaV1,
                List.of(new LinhaDoPedido(p3, 1, null)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal("30.00"), "PIX")));
        troca1 = trocarPeloServico(v1, t1, D5_14H, ModoDaDevolucao.ONLINE).trocaId();
        credito1 = t1.creditoId();
        var t2 = pedidoDoServico(venda2, "TAMANHO", List.of(new PecaDoPedido(p2, 1, "BOA")), null, maletaV1,
                List.of(new LinhaDoPedido(p4, 1, null)), List.of());
        troca2 = trocarPeloServico(v1, t2, D5_16H, ModoDaDevolucao.ONLINE).trocaId();
        credito2 = t2.creditoId();

        v2 = usuarios.criarVendedor();
        UUID clienteC = cliente(v2);
        UUID p5 = peca("70.00", 20);
        maletaV2 = maleta(v2, p5, 8);
        venda4 = vendaEm(v2, maletaV2, clienteC, p5, 2, "140.00", "DINHEIRO", D4);
        venda5 = vendaEm(v2, maletaV2, clienteC, p5, 1, "70.00", "DINHEIRO", D4);
        pedidoEsperando = pedirAutorizacao(v2, venda4, p5);
        pedidoAprovado = pedirAutorizacao(v2, venda5, p5);
        autorizacoes.aprovar(pedidoAprovado, usuarios.dona().id());
    }

    // --- apoio ---

    private void cotacao(String dia, String usd, String pyg) {
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
                VALUES (uuidv7(), ?::date, ?::numeric, ?::numeric, 1, 1, ?::numeric, ?::numeric, 1, 1, now(), 'teste')
                ON CONFLICT ON CONSTRAINT cotacoes_data_unica DO NOTHING""", dia, usd, usd, pyg, pyg);
    }

    private UUID vendaEm(UsuarioDeTeste v, UUID maleta, UUID cliente, UUID produto, int quantidade, String total, String forma,
            Instant quando) {
        UUID id = UUID.randomUUID();
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(id, maleta, cliente,
                List.of(new LinhaDoPedido(produto, quantidade, null)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal(total), forma)), null), quando, false);
        return id;
    }

    private UUID devolver(UsuarioDeTeste v, UUID venda, String motivo, UUID produto, String estado, String forma, Instant quando) {
        UUID id = UUID.randomUUID();
        devolucoes.devolver(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDaDevolucaoDoCliente(id, venda, motivo, null,
                forma, List.of(new PecaDoPedido(produto, 1, estado)), null), quando, false, ModoDaDevolucao.ONLINE);
        return id;
    }

    private UUID pedirAutorizacao(UsuarioDeTeste v, UUID venda, UUID produto) {
        UUID id = UUID.randomUUID();
        autorizacoes.pedir(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeAutorizacao(id, venda, "ARREPENDIMENTO", null,
                List.of(new PecaDoPedido(produto, 1, "BOA"))), D20_ABRIL, false, ModoDaDevolucao.ONLINE);
        return id;
    }

    private JsonNode relatorio(String nome, UsuarioDeTeste vendedor, String filtros) throws Exception {
        String url = REL + nome + "?" + (vendedor == null ? "" : "vendedorId=" + vendedor.vendedorId() + "&") + filtros;
        return corpoDe(ler(url, dona()).andExpect(status().isOk()));
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

    private static JsonNode linhaDe(JsonNode relatorio, String campo, UUID valor) {
        for (JsonNode l : relatorio.get("itens")) {
            if (l.get(campo).asString().equals(valor.toString())) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + campo + "=" + valor + " em " + relatorio.get("itens"));
    }

    // --- 1. devoluções por vendedor e motivo ---

    @Test
    void devolucoesPorVendedorEMotivoComOsTotaisCalculadosAMao() throws Exception {
        JsonNode r = relatorio("devolucoes", v1, PERIODO_DE_MARCO);
        // Feitas (sem a cancelada): Dev1 100, Dev2 80 e as devoluções das duas trocas, 100 e 80 = 360,00; 4 peças.
        assertThat(r.get("total").asLong()).isEqualTo(4);
        assertThat(r.get("itens")).hasSize(4);
        JsonNode geral = total(r, "geral");
        confere(geral, 4, "360.00", "90.00", "180000");
        assertThat(geral.get("pecas").asLong()).isEqualTo(4);
        confere(total(r, "arrependimento"), 2, "200.00", "50.00", "100000");
        confere(total(r, "defeito"), 1, "80.00", "20.00", "40000");
        confere(total(r, "tamanho"), 1, "80.00", "20.00", "40000");
        confere(total(r, "outro"), 0, "0.00", "0.00", "0");
        confere(total(r, "em-dinheiro"), 2, "180.00", "45.00", "90000");
        confere(total(r, "em-credito"), 2, "180.00", "45.00", "90000");
        confere(total(r, "de-troca"), 2, "180.00", "45.00", "90000");
        confere(total(r, "canceladas"), 0, "0.00", "0.00", "0");

        JsonNode d1 = linhaDe(r, "id", dev1);
        assertThat(d1.get("vendedor").asString()).isEqualTo("Vendedor de " + v1.login());
        assertThat(d1.get("motivo").asString()).isEqualTo("ARREPENDIMENTO");
        assertThat(d1.get("tipo").asString()).isEqualTo("DEVOLUCAO");
        assertThat(d1.get("situacao").asString()).isEqualTo("ACEITA");
        assertThat(dec(d1.get("valorDevolvido"))).isEqualByComparingTo("100.00");
        assertThat(dec(d1.get("valorDevolvidoEmDolar"))).isEqualByComparingTo("25.00");
        assertThat(dec(d1.get("valorDevolvidoEmGuarani"))).isEqualByComparingTo("50000");
        assertThat(dec(d1.get("valorReembolsado"))).isEqualByComparingTo("100.00");
        assertThat(dec(d1.get("valorEmCredito"))).isEqualByComparingTo("0.00");
        assertThat(d1.get("dataDaCotacao").asString()).isEqualTo("1991-03-05");
        JsonNode d2 = linhaDe(r, "id", dev2);
        assertThat(d2.get("pecasComDefeito").asInt()).isEqualTo(1);
        assertThat(d2.get("formaReembolso").asString()).isEqualTo("PIX");
        // As duas devoluções de troca: o tipo mostra TROCA e tudo voltou em crédito.
        assertThat(r.get("itens").findValues("tipo").stream().filter(t -> t.asString().equals("TROCA"))).hasSize(2);
    }

    @Test
    void canceladaFicaForaDoTotalEOsFiltrosEOPeriodoValem() throws Exception {
        JsonNode todas = relatorio("devolucoes", v1, PERIODO_DE_MARCO + "&situacao=TODAS");
        assertThat(todas.get("total").asLong()).isEqualTo(5);
        confere(total(todas, "geral"), 4, "360.00", "90.00", "180000");
        confere(total(todas, "canceladas"), 1, "100.00", "25.00", "50000");
        JsonNode cancelada = linhaDe(todas, "id", dev3);
        assertThat(cancelada.get("situacao").asString()).isEqualTo("CANCELADA");
        assertThat(cancelada.get("cancelada").asBoolean()).isTrue();

        JsonNode soCanceladas = relatorio("devolucoes", v1, PERIODO_DE_MARCO + "&situacao=CANCELADAS");
        assertThat(soCanceladas.get("itens")).hasSize(1);
        // O detalhamento de um total mostra só as linhas que o formam.
        JsonNode defeito = relatorio("devolucoes", v1, PERIODO_DE_MARCO + "&recorte=defeito");
        assertThat(defeito.get("itens")).hasSize(1);
        assertThat(defeito.get("itens").get(0).get("id").asString()).isEqualTo(dev2.toString());
        assertThat(defeito.get("recorte").asString()).isEqualTo("defeito");

        assertThat(relatorio("devolucoes", v1, PERIODO_DE_MARCO + "&motivo=DEFEITO").get("itens")).hasSize(1);
        assertThat(relatorio("devolucoes", v1, PERIODO_DE_MARCO + "&situacao=EM_REVISAO").get("itens")).isEmpty();
        // Período: o dia 05/03 em Brasília inclui tudo; o dia 04/03 não tem devolução.
        assertThat(relatorio("devolucoes", v1, "de=1991-03-05&ate=1991-03-05").get("itens")).hasSize(4);
        assertThat(relatorio("devolucoes", v1, "de=1991-03-04&ate=1991-03-04").get("itens")).isEmpty();
        // Outro vendedor não aparece.
        assertThat(relatorio("devolucoes", v2, PERIODO_DE_MARCO).get("itens")).isEmpty();
    }

    @Test
    void periodoContaOsDiasDeBrasiliaEPeriodoInvertidoEOutrosPedidosMalFeitosRecebem400() throws Exception {
        // 05/03 às 23:30 em Brasília já é 06/03 em UTC: o dia do relatório é o de Brasília.
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("90.00", 5);
        UUID maleta = maleta(v, peca, 3);
        UUID venda = vendaEm(v, maleta, cliente, peca, 2, "180.00", "DINHEIRO", D4);
        devolver(v, venda, "ARREPENDIMENTO", peca, "BOA", "DINHEIRO", brasilia("1991-03-05T23:30:00"));
        assertThat(relatorio("devolucoes", v, "de=1991-03-05&ate=1991-03-05").get("itens")).hasSize(1);
        assertThat(relatorio("devolucoes", v, "de=1991-03-06&ate=1991-03-06").get("itens")).isEmpty();

        // Só devolução no dia, nenhuma venda: a porcentagem fica vazia e o vendedor aparece acima do limite.
        JsonNode soDevolucao = linhaDe(relatorio("por-vendedor", v, "de=1991-03-05&ate=1991-03-05"), "vendedorId", v.vendedorId());
        assertThat(soDevolucao.get("vendas").asLong()).isZero();
        assertThat(dec(soDevolucao.get("devolvido"))).isEqualByComparingTo("90.00");
        assertThat(soDevolucao.get("porcentagemDevolvida").isNull()).isTrue();
        assertThat(soDevolucao.get("acimaDoLimite").asBoolean()).isTrue();

        ler(REL + "devolucoes?de=1991-03-10&ate=1991-03-01", dona()).andExpect(status().isBadRequest());
        ler(REL + "devolucoes?motivo=QUALQUER", dona()).andExpect(status().isBadRequest());
        ler(REL + "devolucoes?situacao=QUALQUER", dona()).andExpect(status().isBadRequest());
        ler(REL + "devolucoes?vendedorId=nao-e-uuid", dona()).andExpect(status().isBadRequest());
        ler(REL + "devolucoes?recorte=nao-existe", dona()).andExpect(status().isBadRequest());
        ler(REL + "devolucoes?tamanho=101", dona()).andExpect(status().isBadRequest());
    }

    // --- 2. taxa de devolução ---

    @Test
    void taxaDeDevolucaoPorVendedorComTudoCalculadoAMao() throws Exception {
        JsonNode todos = relatorio("por-vendedor", null, PERIODO_DE_MARCO);
        JsonNode b = linhaDe(todos, "vendedorId", v2.vendedorId());
        JsonNode r = relatorio("por-vendedor", v1, PERIODO_DE_MARCO);
        assertThat(r.get("itens")).hasSize(1);
        JsonNode a = linhaDe(r, "vendedorId", v1.vendedorId());
        assertThat(a.get("vendas").asLong()).isEqualTo(5);
        // 300 + 160 + 100 em 04/03 (US$ 60 + 32 + 20, Gs. 560.000) e 130 + 50 em 05/03 (US$ 32,50 + 12,50, Gs. 90.000).
        assertThat(dec(a.get("vendido"))).isEqualByComparingTo("740.00");
        assertThat(dec(a.get("vendidoEmDolar"))).isEqualByComparingTo("157.00");
        assertThat(dec(a.get("vendidoEmGuarani"))).isEqualByComparingTo("650000");
        assertThat(a.get("devolucoes").asLong()).isEqualTo(4);
        assertThat(a.get("trocas").asLong()).isEqualTo(2);
        assertThat(dec(a.get("devolvido"))).isEqualByComparingTo("360.00");
        assertThat(dec(a.get("devolvidoEmDolar"))).isEqualByComparingTo("90.00");
        assertThat(dec(a.get("devolvidoEmGuarani"))).isEqualByComparingTo("180000");
        assertThat(a.get("porDesistencia").asLong()).isEqualTo(2);
        assertThat(a.get("porDefeito").asLong()).isEqualTo(1);
        assertThat(a.get("porTamanho").asLong()).isEqualTo(1);
        assertThat(a.get("porOutro").asLong()).isZero();
        // 360 / 740 = 48,648...%: duas casas, meio para cima.
        assertThat(dec(a.get("porcentagemDevolvida"))).isEqualByComparingTo("48.65");
        assertThat(a.get("acimaDoLimite").asBoolean()).isTrue();

        assertThat(b.get("vendas").asLong()).isEqualTo(2);
        assertThat(dec(b.get("vendido"))).isEqualByComparingTo("210.00");
        assertThat(b.get("devolucoes").asLong()).isZero();
        assertThat(dec(b.get("porcentagemDevolvida"))).isEqualByComparingTo("0.00");
        assertThat(b.get("acimaDoLimite").asBoolean()).isFalse();

        // O rodapé do filtro de V1: uma linha, com os mesmos números.
        confere(total(r, "vendido"), 1, "740.00", "157.00", "650000");
        confere(total(r, "devolvido"), 1, "360.00", "90.00", "180000");
        assertThat(total(r, "acima").get("quantidade").asLong()).isEqualTo(1);
        assertThat(total(r, "com-devolucao").get("quantidade").asLong()).isEqualTo(1);
    }

    @Test
    void limiteDaTaxaSegueOQueADonaConfigurouEDiaSemLancamentoNaoMostraOVendedor() throws Exception {
        // 48,65% passa de 20% e de 40%, mas não de 50% (a conta é exata: 36.000 contra 50 x 740 = 37.000).
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 50 WHERE chave = 'percentual_alerta_devolucao'");
        try {
            assertThat(linhaDe(relatorio("por-vendedor", null, PERIODO_DE_MARCO), "vendedorId", v1.vendedorId())
                    .get("acimaDoLimite").asBoolean()).isFalse();
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 48 WHERE chave = 'percentual_alerta_devolucao'");
            assertThat(linhaDe(relatorio("por-vendedor", null, PERIODO_DE_MARCO), "vendedorId", v1.vendedorId())
                    .get("acimaDoLimite").asBoolean()).isTrue();
        } finally {
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 20 WHERE chave = 'percentual_alerta_devolucao'");
        }
        // Dia 05/03: só as duas vendas das trocas (130 e 50); sem nenhum lançamento depois, o vendedor nem aparece.
        JsonNode dia5 = linhaDe(relatorio("por-vendedor", v1, "de=1991-03-05&ate=1991-03-05"), "vendedorId", v1.vendedorId());
        assertThat(dia5.get("vendas").asLong()).isEqualTo(2);
        assertThat(dec(dia5.get("vendido"))).isEqualByComparingTo("180.00");
        assertThat(relatorio("por-vendedor", v1, "de=1991-03-06&ate=1991-03-31").get("itens")).isEmpty();
    }

    // --- 3. peças com defeito ---

    @Test
    void pecasComDefeitoUmaLinhaPorPecaComFotoCodigoEValor() throws Exception {
        JsonNode r = relatorio("pecas-com-defeito", v1, PERIODO_DE_MARCO);
        assertThat(r.get("itens")).hasSize(1);
        JsonNode l = linhaDe(r, "produtoId", p2);
        assertThat(l.get("pecas").asLong()).isEqualTo(1);
        assertThat(l.get("devolucoes").asLong()).isEqualTo(1);
        assertThat(l.get("vendedores").asLong()).isEqualTo(1);
        assertThat(l.get("nome").asString()).startsWith("Colar");
        assertThat(l.get("codigo").asString()).startsWith("TR-");
        assertThat(l.has("fotoPrincipalHash")).isTrue();
        assertThat(dec(l.get("valor"))).isEqualByComparingTo("80.00");
        assertThat(dec(l.get("valorEmDolar"))).isEqualByComparingTo("20.00");
        assertThat(dec(l.get("valorEmGuarani"))).isEqualByComparingTo("40000");
        JsonNode geral = total(r, "geral");
        confere(geral, 1, "80.00", "20.00", "40000");
        assertThat(geral.get("pecas").asLong()).isEqualTo(1);
        // A peça boa nunca entra, nem a devolução por defeito cancelada.
        assertThat(relatorio("pecas-com-defeito", v2, PERIODO_DE_MARCO).get("itens")).isEmpty();
        assertThat(relatorio("pecas-com-defeito", v1, "produtoId=" + p1 + "&" + PERIODO_DE_MARCO).get("itens")).isEmpty();
    }

    // --- 4. trocas ---

    @Test
    void trocasComADiferencaAPagarEOCreditoQueSobrou() throws Exception {
        JsonNode r = relatorio("trocas", v1, PERIODO_DE_MARCO);
        assertThat(r.get("itens")).hasSize(2);
        JsonNode t1 = linhaDe(r, "id", troca1);
        assertThat(dec(t1.get("valorDevolvido"))).isEqualByComparingTo("100.00");
        assertThat(dec(t1.get("totalDaVendaNova"))).isEqualByComparingTo("130.00");
        assertThat(dec(t1.get("diferenca"))).isEqualByComparingTo("30.00");
        assertThat(dec(t1.get("diferencaEmDolar"))).isEqualByComparingTo("7.50");
        assertThat(dec(t1.get("diferencaEmGuarani"))).isEqualByComparingTo("15000");
        assertThat(t1.get("situacao").asString()).isEqualTo("ATIVA");
        JsonNode t2 = linhaDe(r, "id", troca2);
        assertThat(dec(t2.get("diferenca"))).isEqualByComparingTo("-30.00");
        assertThat(dec(t2.get("diferencaEmDolar"))).isEqualByComparingTo("-7.50");
        confere(total(r, "geral"), 2, "180.00", "45.00", "90000");
        confere(total(r, "a-pagar"), 1, "30.00", "7.50", "15000");
        confere(total(r, "credito"), 1, "30.00", "7.50", "15000");
        confere(total(r, "canceladas"), 0, "0.00", "0.00", "0");
        assertThat(relatorio("trocas", v2, PERIODO_DE_MARCO).get("itens")).isEmpty();
    }

    // --- 5. créditos ---

    @Test
    void creditosEmAbertoEUsadosFechamNoRodape() throws Exception {
        JsonNode r = relatorio("creditos", v1, PERIODO_DE_MARCO);
        assertThat(r.get("itens")).hasSize(2);
        JsonNode c1 = linhaDe(r, "id", credito1);
        assertThat(dec(c1.get("valor"))).isEqualByComparingTo("100.00");
        assertThat(dec(c1.get("utilizado"))).isEqualByComparingTo("100.00");
        assertThat(dec(c1.get("saldo"))).isEqualByComparingTo("0.00");
        assertThat(c1.get("situacao").asString()).isEqualTo("UTILIZADO");
        assertThat(c1.get("origem").asString()).isEqualTo("TROCA");
        JsonNode c2 = linhaDe(r, "id", credito2);
        assertThat(dec(c2.get("valor"))).isEqualByComparingTo("80.00");
        assertThat(dec(c2.get("utilizado"))).isEqualByComparingTo("50.00");
        assertThat(dec(c2.get("saldo"))).isEqualByComparingTo("30.00");
        assertThat(dec(c2.get("saldoEmDolar"))).isEqualByComparingTo("7.50");
        assertThat(dec(c2.get("saldoEmGuarani"))).isEqualByComparingTo("15000");
        confere(total(r, "gerados"), 2, "180.00", "45.00", "90000");
        confere(total(r, "usados"), 2, "150.00", "37.50", "75000");
        confere(total(r, "em-aberto"), 1, "30.00", "7.50", "15000");
        // Gerados menos usados é igual ao que está em aberto, em Real, em Dólar e em Guarani.
        assertThat(dec(total(r, "gerados").get("valorEmReais")).subtract(dec(total(r, "usados").get("valorEmReais"))))
                .isEqualByComparingTo(dec(total(r, "em-aberto").get("valorEmReais")));

        assertThat(relatorio("creditos", v1, PERIODO_DE_MARCO + "&situacao=ABERTOS").get("itens")).hasSize(1);
        assertThat(relatorio("creditos", v1, PERIODO_DE_MARCO + "&situacao=UTILIZADOS").get("itens")).hasSize(1);
        assertThat(relatorio("creditos", v1, PERIODO_DE_MARCO + "&situacao=CANCELADOS").get("itens")).isEmpty();
        assertThat(relatorio("creditos", v2, PERIODO_DE_MARCO).get("itens")).isEmpty();
    }

    // --- 6. dinheiro devolvido ---

    @Test
    void dinheiroDevolvidoSoOsReembolsosEmDinheiroComOCancelamentoNegativo() throws Exception {
        JsonNode r = relatorio("reembolsos", v1, PERIODO_DE_MARCO);
        // Dev1 100 (dinheiro), Dev2 80 (Pix), Dev3 100 (dinheiro) e o cancelamento de Dev3, -100: o crédito não entra.
        assertThat(r.get("itens")).hasSize(4);
        confere(total(r, "geral"), 4, "180.00", "45.00", "90000");
        confere(total(r, "dinheiro"), 3, "100.00", "25.00", "50000");
        confere(total(r, "pix"), 1, "80.00", "20.00", "40000");
        confere(total(r, "cartao"), 0, "0.00", "0.00", "0");
        confere(total(r, "desfeitos"), 1, "-100.00", "-25.00", "-50000");
        assertThat(r.get("itens").findValues("forma").stream().map(JsonNode::asString)).doesNotContain("CREDITO");
        long negativos = 0;
        for (JsonNode l : r.get("itens")) {
            if (dec(l.get("valor")).signum() < 0) {
                negativos++;
                assertThat(l.get("tipo").asString()).isEqualTo("CANCELAMENTO");
                assertThat(l.get("devolucaoId").asString()).isEqualTo(dev3.toString());
            }
        }
        assertThat(negativos).isEqualTo(1);
        assertThat(relatorio("reembolsos", v1, PERIODO_DE_MARCO + "&forma=PIX").get("itens")).hasSize(1);
        assertThat(relatorio("reembolsos", v2, PERIODO_DE_MARCO).get("itens")).isEmpty();
        ler(REL + "reembolsos?forma=CREDITO", dona()).andExpect(status().isBadRequest());
    }

    // --- 7. pedidos de autorização ---

    @Test
    void pedidosDeAutorizacaoPorSituacaoComOValorPelaCotacaoDoDiaDoPedido() throws Exception {
        String abril = "de=1991-04-01&ate=1991-04-30";
        JsonNode r = relatorio("autorizacoes", v2, abril);
        assertThat(r.get("itens")).hasSize(2);
        JsonNode esperando = linhaDe(r, "id", pedidoEsperando);
        assertThat(esperando.get("situacao").asString()).isEqualTo("PEDIDA");
        assertThat(esperando.get("tipo").asString()).isEqualTo("FORA_DO_PRAZO");
        assertThat(dec(esperando.get("valorPedido"))).isEqualByComparingTo("70.00");
        // O último dia com cotação até 20/04/1991 é 05/03/1991: US$ 4,00 e Gs. 0,002.
        assertThat(dec(esperando.get("valorPedidoEmDolar"))).isEqualByComparingTo("17.50");
        assertThat(dec(esperando.get("valorPedidoEmGuarani"))).isEqualByComparingTo("35000");
        assertThat(linhaDe(r, "id", pedidoAprovado).get("situacao").asString()).isEqualTo("APROVADA");
        confere(total(r, "geral"), 2, "140.00", "35.00", "70000");
        confere(total(r, "esperando"), 1, "70.00", "17.50", "35000");
        confere(total(r, "aprovados"), 1, "70.00", "17.50", "35000");
        confere(total(r, "usados"), 0, "0.00", "0.00", "0");
        confere(total(r, "negados"), 0, "0.00", "0.00", "0");
        assertThat(relatorio("autorizacoes", v2, abril + "&situacao=PEDIDA").get("itens")).hasSize(1);
        assertThat(relatorio("autorizacoes", v2, "de=1991-03-01&ate=1991-03-31").get("itens")).isEmpty();
        assertThat(relatorio("autorizacoes", v1, abril).get("itens")).isEmpty();
    }

    // --- 8. o vendedor: só o que é dele ---

    @Test
    void relatoriosDoVendedorSoComOsDadosDeleSemCusto() throws Exception {
        RequestPostProcessor quem = como(v1);
        JsonNode d = corpoDe(ler("/api/vendedor/relatorios/devolucoes?" + PERIODO_DE_MARCO, quem).andExpect(status().isOk()));
        assertThat(d.get("linhas")).hasSize(3);
        JsonNode arrependimento = null;
        for (JsonNode l : d.get("linhas")) {
            if (l.get("motivo").asString().equals("ARREPENDIMENTO")) {
                arrependimento = l;
            }
        }
        assertThat(arrependimento).isNotNull();
        assertThat(arrependimento.get("devolucoes").asLong()).isEqualTo(2);
        assertThat(dec(arrependimento.get("valor"))).isEqualByComparingTo("200.00");
        assertThat(dec(arrependimento.get("valorEmDolar"))).isEqualByComparingTo("50.00");
        JsonNode totais = d.get("totais");
        assertThat(totais.get("devolucoes").asLong()).isEqualTo(2);
        assertThat(totais.get("trocas").asLong()).isEqualTo(2);
        assertThat(dec(totais.get("valorDevolvido"))).isEqualByComparingTo("360.00");
        assertThat(dec(totais.get("valorDevolvidoEmDolar"))).isEqualByComparingTo("90.00");
        assertThat(dec(totais.get("valorDevolvidoEmGuarani"))).isEqualByComparingTo("180000");
        assertThat(dec(totais.get("emDinheiro"))).isEqualByComparingTo("180.00");
        assertThat(dec(totais.get("emDinheiroEmDolar"))).isEqualByComparingTo("45.00");
        assertThat(dec(totais.get("emCredito"))).isEqualByComparingTo("180.00");
        assertThat(dec(totais.get("emCreditoEmGuarani"))).isEqualByComparingTo("90000");

        JsonNode c = corpoDe(ler("/api/vendedor/relatorios/creditos", quem).andExpect(status().isOk()));
        assertThat(c.get("clientes")).hasSize(2);
        for (JsonNode l : c.get("clientes")) {
            if (l.get("clienteId").asString().equals(clienteA.toString())) {
                assertThat(dec(l.get("gerado"))).isEqualByComparingTo("80.00");
                assertThat(dec(l.get("usado"))).isEqualByComparingTo("50.00");
                assertThat(dec(l.get("emAberto"))).isEqualByComparingTo("30.00");
                assertThat(dec(l.get("emAbertoEmDolar"))).isEqualByComparingTo("7.50");
            } else {
                assertThat(l.get("clienteId").asString()).isEqualTo(clienteB.toString());
                assertThat(dec(l.get("emAberto"))).isEqualByComparingTo("0.00");
            }
        }
        assertThat(dec(c.get("totais").get("gerado"))).isEqualByComparingTo("180.00");
        assertThat(dec(c.get("totais").get("usado"))).isEqualByComparingTo("150.00");
        assertThat(dec(c.get("totais").get("emAberto"))).isEqualByComparingTo("30.00");
        assertThat(dec(c.get("totais").get("emAbertoEmDolar"))).isEqualByComparingTo("7.50");
        assertThat(dec(c.get("totais").get("emAbertoEmGuarani"))).isEqualByComparingTo("15000");

        // Nenhum custo, nenhum dado de outro vendedor.
        String texto = d.toString() + c;
        assertThat(texto).doesNotContainIgnoringCase("custo").doesNotContain(v2.vendedorId().toString());
        JsonNode doOutro = corpoDe(ler("/api/vendedor/relatorios/devolucoes?" + PERIODO_DE_MARCO, como(v2)).andExpect(status().isOk()));
        assertThat(doOutro.get("linhas")).isEmpty();
        assertThat(doOutro.get("totais").get("devolucoes").asLong()).isZero();
        assertThat(corpoDe(ler("/api/vendedor/relatorios/creditos", como(v2)).andExpect(status().isOk())).get("clientes")).isEmpty();
    }

    @Test
    void recebimentosDoVendedorSeparamOCreditoDoDinheiro() throws Exception {
        JsonNode r = corpoDe(ler("/api/vendedor/relatorios/recebimentos?" + PERIODO_DE_MARCO, como(v1)).andExpect(status().isOk()));
        assertThat(r.get("linhas")).hasSize(2);
        for (JsonNode l : r.get("linhas")) {
            String forma = l.get("nome").asString();
            assertThat(forma).isNotEqualTo("CREDITO");
            if (forma.equals("DINHEIRO")) {
                // 300 - 100 - 100 + 100 (a devolução cancelada devolve o dinheiro à venda): US$ 60 - 25 - 25 + 25.
                assertThat(dec(l.get("total"))).isEqualByComparingTo("200.00");
                assertThat(dec(l.get("totalEmDolar"))).isEqualByComparingTo("35.00");
                assertThat(dec(l.get("totalEmGuarani"))).isEqualByComparingTo("250000");
                assertThat(l.get("quantidade").asLong()).isEqualTo(1);
            } else {
                // 160 + 100 - 80 + 30: US$ 52 - 20 + 7,50.
                assertThat(forma).isEqualTo("PIX");
                assertThat(dec(l.get("total"))).isEqualByComparingTo("210.00");
                assertThat(dec(l.get("totalEmDolar"))).isEqualByComparingTo("39.50");
                assertThat(dec(l.get("totalEmGuarani"))).isEqualByComparingTo("235000");
                assertThat(l.get("quantidade").asLong()).isEqualTo(3);
            }
        }
        assertThat(dec(r.get("totais").get("total"))).isEqualByComparingTo("410.00");
        assertThat(dec(r.get("totais").get("totalEmDolar"))).isEqualByComparingTo("74.50");
        assertThat(dec(r.get("totais").get("totalEmGuarani"))).isEqualByComparingTo("485000");
    }

    // --- 9. permissões ---

    @Test
    void permissoesDosRelatoriosDeDevolucao() throws Exception {
        for (String nome : List.of("devolucoes", "por-vendedor", "pecas-com-defeito", "trocas", "creditos", "reembolsos",
                "autorizacoes", "conferencia")) {
            ler(REL + nome, como(v1)).andExpect(status().isForbidden());
            ler(REL + nome, dona()).andExpect(status().isOk());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(REL + "devolucoes"))
                .andExpect(status().isUnauthorized());
        ler("/api/vendedor/relatorios/devolucoes", dona()).andExpect(status().isForbidden());
        ler("/api/vendedor/relatorios/creditos", dona()).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/vendedor/relatorios/creditos"))
                .andExpect(status().isUnauthorized());
    }

    // --- 10. as conferências ---

    @Test
    void conferenciasDoBancoInteiroVemVaziasComAMassaConhecida() throws Exception {
        JsonNode r = corpoDe(ler(REL + "conferencia", dona()).andExpect(status().isOk()));
        assertThat(r.get("tudoCerto").asBoolean()).isTrue();
        List<String> chaves = new ArrayList<>();
        for (JsonNode c : r.get("conferencias")) {
            chaves.add(c.get("chave").asString());
            assertThat(c.get("divergencias")).as("divergências de %s", c.get("chave")).isEmpty();
            assertThat(c.get("conferidos").asLong()).as("conferidos de %s", c.get("chave")).isPositive();
            assertThat(c.get("titulo").asString()).isNotBlank();
        }
        assertThat(chaves).containsExactly("maleta", "devolvido-por-peca", "creditos", "dinheiro-devolvido", "contas-da-venda");
        assertThat(r.get("geradaEm").asString()).isNotBlank();
    }

    @Test
    void maletaComMovimentoDeDevolucaoQueNaoExisteEApanhada() throws Exception {
        UUID movimento = UUID.randomUUID();
        // Dev2 é de peça com defeito: a peça vai para a loja e nunca entra na maleta.
        corromper(List.of("movimentos_remessa"), """
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, devolucao_id, sync_seq, sync_xid)
                VALUES ('%s', '%s', '%s', 'DEVOLUCAO_CLIENTE', 1, '%s', '%s', nextval('corona.sync_seq_global'), pg_current_xact_id())""".formatted(movimento, maletaV1, p2, venda2, dev2),
                "DELETE FROM corona.movimentos_remessa WHERE id = '%s'".formatted(movimento), () -> {
                    JsonNode c = conferencia("maleta");
                    assertThat(c.get("divergencias")).hasSize(1);
                    assertThat(c.get("divergencias").get(0).get("referencia").asString()).contains(dev2.toString());
                    assertThat(c.get("divergencias").get(0).get("descricao").asString()).isNotBlank();
                    assertThat(corpoDe(ler(REL + "conferencia", dona())).get("tudoCerto").asBoolean()).isFalse();
                });
        assertThat(conferencia("maleta").get("divergencias")).isEmpty();
    }

    @Test
    void devolvidoAcimaDoVendidoEApanhado() throws Exception {
        UUID item = jdbc.queryForObject("SELECT id FROM corona.itens_devolucao WHERE devolucao_id = ?", UUID.class, dev1);
        corromper(List.of("itens_devolucao"), "UPDATE corona.itens_devolucao SET quantidade = quantidade + 5 WHERE id = '%s'"
                .formatted(item), "UPDATE corona.itens_devolucao SET quantidade = quantidade - 5 WHERE id = '%s'".formatted(item), () -> {
                    JsonNode c = conferencia("devolvido-por-peca");
                    assertThat(c.get("divergencias")).hasSize(1);
                    assertThat(c.get("divergencias").get(0).get("descricao").asString()).isNotBlank();
                });
        assertThat(conferencia("devolvido-por-peca").get("divergencias")).isEmpty();
    }

    @Test
    void creditoQueNaoBateComOsUsosEApanhado() throws Exception {
        corromper(List.of("creditos_cliente"), "UPDATE corona.creditos_cliente SET valor_utilizado = 40.00 WHERE id = '%s'"
                .formatted(credito2), "UPDATE corona.creditos_cliente SET valor_utilizado = 50.00 WHERE id = '%s'".formatted(credito2), () -> {
                    JsonNode c = conferencia("creditos");
                    assertThat(c.get("divergencias").size()).isPositive();
                    assertThat(c.get("divergencias").findValues("referencia").stream().map(JsonNode::asString))
                            .anyMatch(s -> s.contains(credito2.toString()));
                });
        assertThat(conferencia("creditos").get("divergencias")).isEmpty();
    }

    @Test
    void reembolsoAcimaDoPagoEApanhadoMasNaoQuandoADonaJaFoiAvisada() throws Exception {
        UUID extra = UUID.randomUUID();
        // Um reembolso solto de 500 na venda 3 (paga com 100): sai mais do que entrou, e nenhuma devolução em revisão explica.
        corromper(List.of("pagamentos_cliente"), """
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, sync_seq, sync_xid)
                VALUES ('%s', '%s', 'REEMBOLSO', 'DINHEIRO', -500.00, now(), nextval('corona.sync_seq_global'), pg_current_xact_id())""".formatted(extra, venda3),
                "DELETE FROM corona.pagamentos_cliente WHERE id = '%s'".formatted(extra), () -> {
                    JsonNode c = conferencia("dinheiro-devolvido");
                    assertThat(c.get("divergencias")).hasSize(1);
                    assertThat(c.get("divergencias").get(0).get("referencia").asString()).contains(venda3.toString());
                    // E a conta da venda também não fecha.
                    assertThat(conferencia("contas-da-venda").get("divergencias").findValues("referencia").stream()
                            .map(JsonNode::asString)).anyMatch(s -> s.contains(venda3.toString()));
                });
        assertThat(conferencia("dinheiro-devolvido").get("divergencias")).isEmpty();
        assertThat(conferencia("contas-da-venda").get("divergencias")).isEmpty();
    }

    @Test
    void pagamentoAMaisNaVendaQuebraSoAsContasDaVenda() throws Exception {
        UUID extra = UUID.randomUUID();
        corromper(List.of("pagamentos_cliente"), """
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, sync_seq, sync_xid)
                VALUES ('%s', '%s', 'PAGAMENTO', 'PIX', 10.00, now(), nextval('corona.sync_seq_global'), pg_current_xact_id())""".formatted(extra, venda2),
                "DELETE FROM corona.pagamentos_cliente WHERE id = '%s'".formatted(extra), () -> {
                    JsonNode c = conferencia("contas-da-venda");
                    assertThat(c.get("divergencias")).hasSize(1);
                    assertThat(c.get("divergencias").get(0).get("referencia").asString()).contains(venda2.toString());
                    assertThat(conferencia("dinheiro-devolvido").get("divergencias")).isEmpty();
                });
        assertThat(conferencia("contas-da-venda").get("divergencias")).isEmpty();
    }

    // --- apoio das conferências ---

    private JsonNode conferencia(String chave) throws Exception {
        JsonNode r = corpoDe(ler(REL + "conferencia", dona()).andExpect(status().isOk()));
        for (JsonNode c : r.get("conferencias")) {
            if (c.get("chave").asString().equals(chave)) {
                return c;
            }
        }
        throw new AssertionError("Sem a conferência " + chave);
    }

    /**
     * Grava um dado torto por fora das regras (o dono das tabelas, com os gatilhos de usuário desligados), roda a conferência
     * e, SEMPRE, desfaz: os testes dividem o banco, e uma sobra aqui quebraria a conciliação dos outros.
     */
    private void corromper(List<String> tabelas, String corromper, String desfazer, ComConferencia teste) throws Exception {
        executarComoDono(tabelas, corromper);
        try {
            teste.rodar();
        } finally {
            executarComoDono(tabelas, desfazer);
        }
    }

    private static void executarComoDono(List<String> tabelas, String sql) throws SQLException {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            for (String t : tabelas) {
                s.execute("ALTER TABLE corona." + t + " DISABLE TRIGGER USER");
            }
            try {
                s.execute(sql);
            } finally {
                for (String t : tabelas) {
                    s.execute("ALTER TABLE corona." + t + " ENABLE TRIGGER USER");
                }
            }
            con.commit();
        }
    }

    @FunctionalInterface
    private interface ComConferencia {
        void rodar() throws Exception;
    }
}
