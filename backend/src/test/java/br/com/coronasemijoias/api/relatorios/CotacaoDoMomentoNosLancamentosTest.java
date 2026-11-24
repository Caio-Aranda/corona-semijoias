package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes.PedidoDeAutorizacao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cada lançamento grava a cotação VIGENTE NO MOMENTO dele (ADR-0006, item 15; plano da cotação da dona, seção 2.1): no dia
 * 10/07/2289 o Banco Central vale desde 00:00 e a dona lançou a dela às 10:00. O que foi lançado às 09:30 fica com o Banco
 * Central; o das 11:30, com a da dona; e o que já estava gravado não muda quando a dona lança outra depois. Venda, devolução,
 * pedido de autorização, troca e crédito da troca.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CotacaoDoMomentoNosLancamentosTest extends ApoioDasTrocas {

    private static final String DIA = "2289-07-10";
    private static final Instant NOVE_E_MEIA = brasilia(DIA + "T09:30:00");
    private static final Instant DEZ_HORAS = brasilia(DIA + "T10:00:00");
    private static final Instant ONZE_E_MEIA = brasilia(DIA + "T11:30:00");
    private static final Instant TARDE = brasilia(DIA + "T16:00:00");

    @Autowired
    private ServicoDeAutorizacoes autorizacoes;

    private UUID bcb;
    private UUID dona;
    private UsuarioDeTeste v;
    private UUID maleta;
    private UUID peca;
    private UUID outra;
    private UUID cliente;

    @BeforeAll
    void massa() throws Exception {
        // O Banco Central do dia vale desde 00:00 (Brasília); a da dona, desde as 10:00.
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra, usd_paridade_venda,
                    pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte, vigente_desde)
                VALUES (uuidv7(), ?::date, 5, 5, 1, 1, 0.001, 0.001, 1, 1, now(), 'teste',
                    ?::date::timestamp AT TIME ZONE 'America/Sao_Paulo')""", DIA, DIA);
        bcb = jdbc.queryForObject("SELECT id FROM corona.cotacoes WHERE data_cotacao = ?::date", UUID.class, DIA);
        dona = CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), DEZ_HORAS, "5.80", "1350");

        v = usuarios.criarVendedor();
        cliente = cliente(v);
        peca = peca("100.00", 30);
        outra = peca("60.00", 30);
        maleta = maleta(v, peca, 10, outra, 10);
    }

    @AfterAll
    void tirarAsCotacoesDaMassa() throws SQLException {
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirarDias(con, List.of(DIA));
        }
    }

    private UUID vendaEm(Instant quando) {
        UUID id = UUID.randomUUID();
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(id, maleta, cliente,
                List.of(new LinhaDoPedido(peca, 1, null)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal("100.00"), "DINHEIRO")), null), quando, false);
        return id;
    }

    private UUID cotacaoDe(String tabela, UUID id) {
        return jdbc.queryForObject("SELECT cotacao_id FROM corona." + tabela + " WHERE id = ?", UUID.class, id);
    }

    @Test
    void vendaDevolucaoAutorizacaoTrocaECreditoGravamAVigenteNoMomento() throws Exception {
        UUID cedo = vendaEm(NOVE_E_MEIA);
        UUID tarde = vendaEm(ONZE_E_MEIA);
        assertThat(cotacaoDe("vendas", cedo)).as("venda das 09:30").isEqualTo(bcb);
        assertThat(cotacaoDe("vendas", tarde)).as("venda das 11:30").isEqualTo(dona);
        // Uma venda que chegou só à tarde (offline), mas foi feita às 09:30 (hora já corrigida), fica com o Banco Central.
        relogio.fixar(TARDE);
        UUID atrasada = vendaEm(NOVE_E_MEIA);
        relogio.reiniciar();
        assertThat(cotacaoDe("vendas", atrasada)).as("venda das 09:30 que chegou às 16:00").isEqualTo(bcb);

        // Devolução.
        UUID dev1 = UUID.randomUUID();
        devolucoes.devolver(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDaDevolucaoDoCliente(dev1, cedo,
                "ARREPENDIMENTO", null, "DINHEIRO", List.of(new PecaDoPedido(peca, 1, "BOA")), null), NOVE_E_MEIA, false,
                ModoDaDevolucao.ONLINE);
        UUID dev2 = UUID.randomUUID();
        devolucoes.devolver(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDaDevolucaoDoCliente(dev2, tarde,
                "ARREPENDIMENTO", null, "DINHEIRO", List.of(new PecaDoPedido(peca, 1, "BOA")), null), ONZE_E_MEIA, false,
                ModoDaDevolucao.ONLINE);
        assertThat(cotacaoDe("devolucoes", dev1)).as("devolução das 09:30").isEqualTo(bcb);
        assertThat(cotacaoDe("devolucoes", dev2)).as("devolução das 11:30").isEqualTo(dona);

        // Pedido de autorização (devolução fora do prazo da garantia: a venda é de 40 dias antes).
        Instant antiga = brasilia("2289-05-31T12:00:00");
        UUID vendaDaAutorizacao = vendaEm(antiga);
        UUID aut1 = UUID.randomUUID();
        autorizacoes.pedir(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeAutorizacao(aut1, vendaDaAutorizacao,
                "ARREPENDIMENTO", null, List.of(new PecaDoPedido(peca, 1, "BOA"))), NOVE_E_MEIA, false, ModoDaDevolucao.ONLINE);
        UUID vendaDaAutorizacao2 = vendaEm(antiga);
        UUID aut2 = UUID.randomUUID();
        autorizacoes.pedir(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeAutorizacao(aut2, vendaDaAutorizacao2,
                "ARREPENDIMENTO", null, List.of(new PecaDoPedido(peca, 1, "BOA"))), ONZE_E_MEIA, false, ModoDaDevolucao.ONLINE);
        assertThat(cotacaoDe("autorizacoes_excecao", aut1)).isEqualTo(bcb);
        assertThat(cotacaoDe("autorizacoes_excecao", aut2)).isEqualTo(dona);

        // Troca e o crédito dela.
        UUID vendaDaTroca1 = vendaEm(NOVE_E_MEIA);
        var t1 = pedidoDoServico(vendaDaTroca1, "ARREPENDIMENTO", List.of(new PecaDoPedido(peca, 1, "BOA")), null, maleta,
                List.of(new LinhaDoPedido(outra, 1, null)), List.of());
        UUID troca1 = trocarPeloServico(v, t1, NOVE_E_MEIA, ModoDaDevolucao.ONLINE).trocaId();
        UUID vendaDaTroca2 = vendaEm(ONZE_E_MEIA);
        var t2 = pedidoDoServico(vendaDaTroca2, "ARREPENDIMENTO", List.of(new PecaDoPedido(peca, 1, "BOA")), null, maleta,
                List.of(new LinhaDoPedido(outra, 1, null)), List.of());
        UUID troca2 = trocarPeloServico(v, t2, ONZE_E_MEIA, ModoDaDevolucao.ONLINE).trocaId();
        assertThat(cotacaoDe("trocas", troca1)).as("troca das 09:30").isEqualTo(bcb);
        assertThat(cotacaoDe("trocas", troca2)).as("troca das 11:30").isEqualTo(dona);
        assertThat(cotacaoDe("creditos_cliente", t1.creditoId())).as("crédito da troca das 09:30").isEqualTo(bcb);
        assertThat(cotacaoDe("creditos_cliente", t2.creditoId())).as("crédito da troca das 11:30").isEqualTo(dona);

        // A dona lança outra cotação à tarde: nada do que já estava gravado muda.
        UUID maisNova = CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), TARDE, "6.10", "1400");
        assertThat(cotacaoDe("vendas", cedo)).isEqualTo(bcb);
        assertThat(cotacaoDe("vendas", tarde)).isEqualTo(dona);
        assertThat(cotacaoDe("devolucoes", dev2)).isEqualTo(dona);
        assertThat(cotacaoDe("trocas", troca2)).isEqualTo(dona);
        // E o lançamento seguinte já usa a nova.
        UUID depois = vendaEm(TARDE.plusSeconds(60));
        assertThat(cotacaoDe("vendas", depois)).isEqualTo(maisNova);
    }

    @Test
    void relatorioComDuasCotacoesNoMesmoDiaMostraCadaLinhaPelaSuaEComHoraSoQuandoEDaDona() throws Exception {
        UsuarioDeTeste v2 = usuarios.criarVendedor();
        UUID cliente2 = cliente(v2);
        UUID peca2 = peca("100.00", 30);
        UUID maleta2 = maleta(v2, peca2, 10);
        UUID cedo = UUID.randomUUID();
        vendas.vender(v2.vendedorId(), v2.id(), usuarios.aparelhoDe(v2), new PedidoDeVenda(cedo, maleta2, cliente2,
                List.of(new LinhaDoPedido(peca2, 1, null)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal("100.00"), "DINHEIRO")), null), NOVE_E_MEIA, false);
        UUID tarde = UUID.randomUUID();
        vendas.vender(v2.vendedorId(), v2.id(), usuarios.aparelhoDe(v2), new PedidoDeVenda(tarde, maleta2, cliente2,
                List.of(new LinhaDoPedido(peca2, 1, null)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal("100.00"), "DINHEIRO")), null), ONZE_E_MEIA, false);

        var r = corpoDe(ler("/api/dona/relatorios/vendas/vendas?de=" + DIA + "&ate=" + DIA + "&vendedorId=" + v2.vendedorId(), dona())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()));
        var linhaCedo = linhaDaVenda(r, cedo);
        var linhaTarde = linhaDaVenda(r, tarde);
        // Banco Central (5,00 e 0,001): R$ 100 = US$ 20,00 e Gs. 100.000, e só o dia na coluna da cotação.
        assertThat(new BigDecimal(linhaCedo.get("vendidoEmDolar").asString())).isEqualByComparingTo("20.00");
        assertThat(new BigDecimal(linhaCedo.get("vendidoEmGuarani").asString())).isEqualByComparingTo("100000");
        assertThat(linhaCedo.get("dataDaCotacao").asString()).isEqualTo(DIA);
        // Cotação da dona (5,80 e 1 real = Gs. 1.350): R$ 100 = US$ 17,24 e Gs. 135.000, com a hora em que passou a valer.
        assertThat(new BigDecimal(linhaTarde.get("vendidoEmDolar").asString())).isEqualByComparingTo("17.24");
        assertThat(new BigDecimal(linhaTarde.get("vendidoEmGuarani").asString())).isEqualByComparingTo("135000");
        assertThat(linhaTarde.get("dataDaCotacao").asString()).isEqualTo(DIA + "T10:00");
    }

    private static tools.jackson.databind.JsonNode linhaDaVenda(tools.jackson.databind.JsonNode relatorio, UUID vendaId) {
        for (var item : relatorio.get("itens")) {
            if (vendaId.toString().equals(item.get("vendaId").asString())) {
                return item;
            }
        }
        throw new AssertionError("A venda " + vendaId + " não está no relatório");
    }
}
