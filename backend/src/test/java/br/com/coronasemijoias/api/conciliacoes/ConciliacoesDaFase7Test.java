package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Diferenca;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * As nove conciliações da Fase 7 (tarefa 7.8) sobre a massa completa: zeradas nas referências da massa (pela rota, como a dona) e,
 * em CADA uma, um teste que estraga o dado de propósito e prova que a diferença aparece, com a referência certa. O estrago é feito
 * numa conexão do dono das tabelas, com os gatilhos desligados e a transação desfeita; a consulta é a MESMA de produção.
 */
class ConciliacoesDaFase7Test extends MassaDaFase7 {

    private static final String ZERO = "00000000-0000-0000-0000-000000000000";

    // --- a massa está zerada ---

    @Test
    void asOnzeConciliacoesVemZeradasNasReferenciasDaMassaECadaUmaConferiuRegistros() throws Exception {
        JsonNode r = corpoDe(ler("/api/dona/conciliacoes", dona()).andExpect(status().isOk()));
        List<String> chaves = new ArrayList<>();
        Set<UUID> meus = idsDaMassa();
        assertThat(r.get("conferencias").size()).isEqualTo(11);
        for (JsonNode c : r.get("conferencias")) {
            chaves.add(c.get("chave").asString());
            assertThat(c.get("conferidos").asLong()).as(c.get("chave").asString() + " conferiu registros").isPositive();
            assertThat(c.get("titulo").asString()).isNotBlank();
            assertThat(c.get("explicacao").asString()).isNotBlank();
            Set<String> daMassa = new HashSet<>();
            for (JsonNode d : c.get("divergencias")) {
                if (!d.get("referencia").isNull() && meus.contains(UUID.fromString(d.get("referencia").asString()))) {
                    daMassa.add(d.get("tipo").asString() + " " + d.get("referencia").asString() + " " + d.get("descricao").asString());
                }
            }
            assertThat(daMassa).as(c.get("chave").asString() + " na massa").isEmpty();
        }
        assertThat(chaves).containsExactly("C1", "C2", "C3", "C4", "C5", "C7", "C8", "C9", "C10", "C11", "C12");
    }

    @Test
    void cadaConciliacaoSozinhaConfereMaisDeUmRegistroEVemZeradaNaMassa() {
        for (String chave : ConciliacoesDaFase7.CHAVES) {
            var conferencia = conciliacoes.conferir(chave).conferencias();
            assertThat(conferencia).as(chave).hasSize(1);
            assertThat(conferencia.get(0).chave()).isEqualTo(chave);
            assertThat(conferencia.get(0).conferidos()).as(chave).isPositive();
            assertThat(daMassa(conferencia.get(0).divergencias())).as(chave + " na massa").isEmpty();
        }
    }

    // --- C1: total menos devolvido = pago líquido mais em aberto ---

    @Test
    void c1AcusaPagamentoPosteriorSemBaixaEVendaEstornadaComParcelaEmAberto() throws Exception {
        var semBaixa = estragarEConferir("C1", Estrago.de(List.of("pagamentos_cliente"), """
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior, sync_seq, sync_xid)
                VALUES ('%s', '%s', 'PAGAMENTO', 'PIX', 20.00, now(), true, nextval('corona.sync_seq_global'), pg_current_xact_id())""".formatted(UUID.randomUUID(), vendaAPrazoAberta)));
        assertThat(tem(semBaixa, "VENDA", vendaAPrazoAberta)).isTrue();

        var estornadaAberta = estragarEConferir("C1", Estrago.de(List.of("vendas"), """
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = '%s', estornada_em = now(), motivo_estorno = 'teste'
                 WHERE id = '%s'""".formatted(UUID.randomUUID(), vendaAPrazoPaga)));
        assertThat(tem(estornadaAberta, "VENDA", vendaAPrazoPaga)).isTrue();
    }

    // --- C2: o acerto fecha ---

    @Test
    void c2AcusaFaltaCobradaAMais() throws Exception {
        var achadas = estragarEConferir("C2", Estrago.de(List.of("acertos"),
                "ALTER TABLE corona.acertos DROP CONSTRAINT IF EXISTS acertos_liquido_confere",
                "UPDATE corona.acertos SET faltas_cobradas = faltas_cobradas + 1 WHERE id = '" + acertoAcima + "'"));
        assertThat(tem(achadas, "ACERTO", acertoAcima)).isTrue();
    }

    @Test
    void c2AcusaRecebimentoDoAcertoComOutroValor() throws Exception {
        UUID recebimento = umId("""
                SELECT id FROM corona.recebimentos_vendedor WHERE vendedor_id = ? AND aplicado_em = 'ACERTO' AND estorna_id IS NULL
                 ORDER BY id LIMIT 1""", x.vendedorId());
        UUID acerto = umId("SELECT acerto_id FROM corona.recebimentos_vendedor WHERE id = ?", recebimento);
        var achadas = estragarEConferir("C2", Estrago.de(List.of("recebimentos_vendedor"),
                "UPDATE corona.recebimentos_vendedor SET valor = valor + 1 WHERE id = '" + recebimento + "'"));
        assertThat(tem(achadas, "ACERTO", acerto)).isTrue();
    }

    @Test
    void c2AcusaContaDoRestanteApagada() throws Exception {
        UUID conta = umId("""
                SELECT id FROM corona.contas_receber WHERE vendedor_id = ? AND origem_tipo = 'ACERTO' AND forma_prevista IS NULL
                 ORDER BY id LIMIT 1""", x.vendedorId());
        UUID acerto = umId("SELECT origem_id FROM corona.contas_receber WHERE id = ?", conta);
        var achadas = estragarEConferir("C2", Estrago.de(List.of("contas_receber", "baixas_receber"),
                "DELETE FROM corona.baixas_receber WHERE conta_id = '" + conta + "'",
                "DELETE FROM corona.contas_receber WHERE id = '" + conta + "'"));
        assertThat(tem(achadas, "ACERTO", acerto)).isTrue();
    }

    // --- C3: a comissão ---

    @Test
    void c3AcusaComissaoApagada() throws Exception {
        var achadas = estragarEConferir("C3", Estrago.de(List.of("comissoes"),
                "DELETE FROM corona.comissoes WHERE venda_id = '" + vendaDe100 + "' AND tipo = 'COMISSAO'"));
        assertThat(tem(achadas, "VENDEDOR", acima.vendedorId())).isTrue();
    }

    @Test
    void c3AcusaPercentualDaLinhaDiferenteDoDoAcerto() throws Exception {
        UUID linha = umId("SELECT id FROM corona.comissoes WHERE venda_id = ? AND tipo = 'COMISSAO'", vendaDe100);
        var achadas = estragarEConferir("C3", Estrago.de(List.of("comissoes"),
                "UPDATE corona.comissoes SET percentual = percentual + 1 WHERE id = '" + linha + "'"));
        assertThat(tem(achadas, "COMISSAO", linha)).isTrue();
    }

    @Test
    void c3AcusaValorDaLinhaComUmCentavoAMais() throws Exception {
        UUID linha = umId("SELECT id FROM corona.comissoes WHERE venda_id = ? AND tipo = 'COMISSAO'", vendaDe100);
        var achadas = estragarEConferir("C3", Estrago.de(List.of("comissoes"),
                "UPDATE corona.comissoes SET valor = valor + 0.01 WHERE id = '" + linha + "'"));
        assertThat(tem(achadas, "COMISSAO", linha)).isTrue();
        assertThat(tem(achadas, "VENDEDOR", acima.vendedorId())).isTrue();
    }

    // --- C4: o caixa ---

    private UUID recebimentoSemEstornoDoX() {
        return umId("""
                SELECT r.id FROM corona.recebimentos_vendedor r WHERE r.vendedor_id = ? AND r.estorna_id IS NULL
                   AND NOT EXISTS (SELECT 1 FROM corona.recebimentos_vendedor e WHERE e.estorna_id = r.id) ORDER BY r.id LIMIT 1""",
                x.vendedorId());
    }

    @Test
    void c4AcusaLancamentoDeRecebimentoApagado() throws Exception {
        UUID recebimento = recebimentoSemEstornoDoX();
        var achadas = estragarEConferir("C4", Estrago.de(List.of("lancamentos_caixa"),
                "DELETE FROM corona.lancamentos_caixa WHERE origem_tipo = 'RECEBIMENTO_VENDEDOR' AND origem_id = '" + recebimento + "'"));
        assertThat(tem(achadas, "RECEBIMENTO", recebimento)).isTrue();
    }

    @Test
    void c4AcusaLancamentoComODobroDoValor() throws Exception {
        UUID recebimento = recebimentoSemEstornoDoX();
        var achadas = estragarEConferir("C4", Estrago.de(List.of("lancamentos_caixa"),
                "UPDATE corona.lancamentos_caixa SET valor = valor * 2 WHERE origem_tipo = 'RECEBIMENTO_VENDEDOR' AND origem_id = '"
                        + recebimento + "'"));
        assertThat(tem(achadas, "RECEBIMENTO", recebimento)).isTrue();
    }

    @Test
    void c4AcusaLancamentoAutomaticoComOrigemQueNaoExiste() throws Exception {
        UUID recebimento = recebimentoSemEstornoDoX();
        UUID lancamento = umId("SELECT id FROM corona.lancamentos_caixa WHERE origem_tipo = 'RECEBIMENTO_VENDEDOR' AND origem_id = ?",
                recebimento);
        var achadas = estragarEConferir("C4", Estrago.de(List.of("lancamentos_caixa"),
                "UPDATE corona.lancamentos_caixa SET origem_id = '" + ZERO + "' WHERE id = '" + lancamento + "'"));
        assertThat(tem(achadas, "LANCAMENTO", lancamento)).isTrue();
    }

    @Test
    void c4AcusaBaixaDeContaAPagarSemASaida() throws Exception {
        UUID baixa = umId("""
                SELECT b.id FROM corona.baixas_pagar b JOIN corona.contas_pagar c ON c.id = b.conta_id
                 WHERE c.compra_id = ? AND b.tipo = 'PAGAMENTO' AND b.valor = 100.00""", compraDasParcelas);
        var achadas = estragarEConferir("C4", Estrago.de(List.of("lancamentos_caixa"),
                "DELETE FROM corona.lancamentos_caixa WHERE origem_tipo = 'PAGAMENTO_FORNECEDOR' AND origem_id = '" + baixa + "'"));
        assertThat(tem(achadas, "PAGAMENTO_A_FORNECEDOR", baixa)).isTrue();
    }

    @Test
    void c4AcusaSaidaComValorPositivo() throws Exception {
        var achadas = estragarEConferir("C4", Estrago.de(List.of("lancamentos_caixa"),
                "ALTER TABLE corona.lancamentos_caixa DROP CONSTRAINT lancamentos_caixa_sinal",
                "UPDATE corona.lancamentos_caixa SET valor = abs(valor) WHERE id = '" + lancamentoManual + "'"));
        assertThat(tem(achadas, "LANCAMENTO", lancamentoManual)).isTrue();
    }

    @Test
    void c4AcusaPagamentoAoVendedorSemSaida() throws Exception {
        UUID acerto = umId("SELECT id FROM corona.acertos WHERE vendedor_id = ? AND pago_ao_vendedor > 0", y.vendedorId());
        var achadas = estragarEConferir("C4", Estrago.de(List.of("lancamentos_caixa"),
                "DELETE FROM corona.lancamentos_caixa WHERE origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND origem_id = '" + acerto + "'"));
        assertThat(tem(achadas, "ACERTO", acerto)).isTrue();
    }

    // --- C5: os atalhos das contas ---

    @Test
    void c5AcusaValorPagoDeContaAReceberSemABaixa() throws Exception {
        UUID conta = umId("""
                SELECT id FROM corona.contas_receber WHERE vendedor_id = ? AND valor_pago + valor_cancelado + 0.01 <= valor
                 ORDER BY id LIMIT 1""", x.vendedorId());
        var achadas = estragarEConferir("C5", Estrago.de(List.of("contas_receber"),
                "ALTER TABLE corona.contas_receber DROP CONSTRAINT IF EXISTS contas_receber_status_confere",
                "UPDATE corona.contas_receber SET valor_pago = valor_pago + 0.01 WHERE id = '" + conta + "'"));
        assertThat(tem(achadas, "CONTA_A_RECEBER", conta)).isTrue();
    }

    @Test
    void c5AcusaValorPagoDeContaAPagarSemABaixa() throws Exception {
        var achadas = estragarEConferir("C5", Estrago.de(List.of("contas_pagar"),
                "ALTER TABLE corona.contas_pagar DROP CONSTRAINT IF EXISTS contas_pagar_status_confere",
                "UPDATE corona.contas_pagar SET valor_pago = 1.00 WHERE id = '" + contaAbertaDaCompra + "'"));
        assertThat(tem(achadas, "CONTA_A_PAGAR", contaAbertaDaCompra)).isTrue();
    }

    @Test
    void c5AcusaStatusPagaComSaldoEmAberto() throws Exception {
        UUID parcela = umId("SELECT id FROM corona.contas_receber WHERE venda_id = ? ORDER BY id LIMIT 1", vendaAPrazoAberta);
        var pagar = estragarEConferir("C5", Estrago.de(List.of("contas_pagar"),
                "ALTER TABLE corona.contas_pagar DROP CONSTRAINT IF EXISTS contas_pagar_status_confere",
                "UPDATE corona.contas_pagar SET status = 'PAGA' WHERE id = '" + contaAbertaDaCompra + "'"));
        assertThat(tem(pagar, "CONTA_A_PAGAR", contaAbertaDaCompra)).isTrue();
        var receber = estragarEConferir("C5", Estrago.de(List.of("contas_receber"),
                "ALTER TABLE corona.contas_receber DROP CONSTRAINT IF EXISTS contas_receber_status_confere",
                "UPDATE corona.contas_receber SET status = 'PAGA' WHERE id = '" + parcela + "'"));
        assertThat(tem(receber, "CONTA_A_RECEBER", parcela)).isTrue();
    }

    // --- C7: o estoque ---

    @Test
    void c7AcusaEstoqueAtualDeUmaPecaComUmaAMais() throws Exception {
        UUID peca = pecas.get(0);
        var achadas = estragarEConferir("C7", Estrago.de(List.of("produtos"),
                "UPDATE corona.produtos SET estoque_atual = estoque_atual + 1 WHERE id = '" + peca + "'"));
        assertThat(tem(achadas, "PRODUTO", peca)).isTrue();
    }

    @Test
    void c7AcusaMovimentoDoEstoqueApagado() throws Exception {
        UUID peca = pecas.get(0);
        var achadas = estragarEConferir("C7", Estrago.de(List.of("movimentacoes_estoque"), """
                DELETE FROM corona.movimentacoes_estoque WHERE id = (SELECT id FROM corona.movimentacoes_estoque
                 WHERE produto_id = '%s' AND origem_tipo = 'MANUAL' ORDER BY id LIMIT 1)""".formatted(peca)));
        assertThat(tem(achadas, "PRODUTO", peca)).isTrue();
    }

    @Test
    void c7AcusaMaletaAbertaComPecaAMais() throws Exception {
        UUID maleta = umId("SELECT id FROM corona.remessas WHERE vendedor_id = ?", z.vendedorId());
        UUID peca = umId("SELECT produto_id FROM corona.itens_remessa WHERE remessa_id = ? LIMIT 1", maleta);
        var achadas = estragarEConferir("C7", Estrago.de(List.of("itens_remessa"),
                "UPDATE corona.itens_remessa SET qtd_enviada = qtd_enviada + 1 WHERE remessa_id = '" + maleta + "'"));
        assertThat(tem(achadas, "PRODUTO", peca)).isTrue();
    }

    // --- C8: a maleta acertada e as faltas ---

    @Test
    void c8AcusaPerdaApagadaNaMaletaAcertadaENaFalta() throws Exception {
        UUID maleta = umId("SELECT id FROM corona.remessas WHERE vendedor_id = ? AND status = 'ACERTADA'", acima.vendedorId());
        UUID falta = umId("SELECT id FROM corona.pendencias_acerto WHERE vendedor_id = ? AND tipo = 'FALTA'", acima.vendedorId());
        var achadas = estragarEConferir("C8", Estrago.de(List.of("movimentos_remessa"), """
                DELETE FROM corona.movimentos_remessa WHERE remessa_id = '%s' AND tipo = 'PERDA'""".formatted(maleta)));
        assertThat(tem(achadas, "MALETA", maleta)).isTrue();
        assertThat(tem(achadas, "PENDENCIA", falta)).isTrue();
    }

    // --- C9: cada evento em um acerto só, e nenhum fica de fora ---

    private UUID eventoDe(String tipo, UUID origem) {
        return umId("SELECT id FROM corona.acerto_eventos WHERE origem_tipo = ? AND origem_id = ?", tipo, origem);
    }

    private List<Diferenca> semOEvento(String tipo, UUID origem) throws Exception {
        UUID evento = eventoDe(tipo, origem);
        return estragarEConferir("C9", Estrago.de(List.of("acerto_eventos", "comissoes"),
                "DELETE FROM corona.comissoes WHERE evento_id = '" + evento + "'",
                "DELETE FROM corona.acerto_eventos WHERE id = '" + evento + "'"));
    }

    @Test
    void c9AcusaEventoDaVendaApagado() throws Exception {
        assertThat(tem(semOEvento("VENDA", vendaDe100), "VENDA", vendaDe100)).isTrue();
    }

    @Test
    void c9AcusaEventoDoEstornoDaVendaApagado() throws Exception {
        assertThat(tem(semOEvento("ESTORNO_VENDA", vendaEstornada), "ESTORNO_VENDA", vendaEstornada)).isTrue();
    }

    @Test
    void c9AcusaEventoDaDevolucaoApagado() throws Exception {
        assertThat(tem(semOEvento("DEVOLUCAO", devolucaoDoAbaixo), "DEVOLUCAO", devolucaoDoAbaixo)).isTrue();
    }

    @Test
    void c9AcusaOMesmoEventoEmDoisAcertos() throws Exception {
        UUID evento = eventoDe("VENDA", vendaDoAbaixo);
        var achadas = estragarEConferir("C9", Estrago.de(List.of("acerto_eventos"),
                "ALTER TABLE corona.acerto_eventos DROP CONSTRAINT acerto_eventos_origem_unica", """
                INSERT INTO corona.acerto_eventos (id, acerto_id, vendedor_id, origem_tipo, origem_id, venda_id, valor, sync_seq, sync_xid)
                SELECT '%s', '%s', vendedor_id, origem_tipo, origem_id, venda_id, valor, nextval('corona.sync_seq_global'),
                       pg_current_xact_id() FROM corona.acerto_eventos WHERE id = '%s'"""
                        .formatted(UUID.randomUUID(), acertoAbaixoSeguinte, evento)));
        assertThat(tem(achadas, "VENDA", vendaDoAbaixo)).isTrue();
    }

    // --- C10: as parcelas da compra ---

    @Test
    void c10AcusaParcelaDeCompraComUmCentavoAMais() throws Exception {
        var achadas = estragarEConferir("C10", Estrago.de(List.of("contas_pagar"),
                "UPDATE corona.contas_pagar SET valor = valor + 0.01 WHERE id = '" + contaAbertaDaCompra + "'"));
        assertThat(tem(achadas, "COMPRA", compraDasParcelas)).isTrue();
    }

    @Test
    void c10ConfereACompraDesfeitaComParcelasCanceladas() {
        var c10 = conciliacoes.conferir("C10").conferencias().get(0);
        assertThat(c10.conferidos()).isPositive();
        assertThat(daMassa(c10.divergencias())).isEmpty();
        assertThat(contar("SELECT count(*) FROM corona.contas_pagar WHERE compra_id = ? AND status = 'CANCELADA'", compraDesfeita)).isPositive();
    }
}
