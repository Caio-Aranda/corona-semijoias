package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * As regras do fechamento do acerto que o PRÓPRIO BANCO garante (tarefa 7.5; ADR-0012, itens 3.6, 5, 6, 8; invariantes 4 a 10
 * do plano), com SQL direto: o serviço pode errar, o banco não deixa gravar errado. Escrito antes da migração V044.
 */
class AcertoFechadoNoBancoTest extends ApoioDoFechamento {

    private UsuarioDeTeste vendedor;
    private UUID semana;
    private UUID peca;
    private UUID maleta;
    private UUID cliente;
    private UUID venda;

    @BeforeEach
    void umaVendaNaMaleta() throws Exception {
        vendedor = usuarios.criarVendedor();
        semana = semana();
        peca = peca("50.00", 20);
        maleta = maletaConferida(vendedor, semana, peca, 10);
        cliente = cliente(vendedor);
        venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
    }

    // --- a configuração da comissão ---

    @Test
    void aConfiguracaoDaComissaoTemOsPadroesEAsFaixas() {
        assertThat(configuracao("comissao_meta_guarani")).isEqualTo(700_000);
        assertThat(configuracao("comissao_percentual_abaixo_da_meta")).isEqualTo(30);
        assertThat(configuracao("comissao_percentual_na_meta")).isEqualTo(40);
        for (int meta : List.of(0, -5, 1_000_000_001)) {
            assertThatThrownBy(() -> jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'comissao_meta_guarani'", meta))
                    .isInstanceOf(DataAccessException.class);
        }
        for (String chave : List.of("comissao_percentual_abaixo_da_meta", "comissao_percentual_na_meta")) {
            for (int percentual : List.of(-1, 101)) {
                assertThatThrownBy(() -> jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = ?", percentual, chave))
                        .isInstanceOf(DataAccessException.class);
            }
        }
        assertThatCode(() -> configurarComissao(1_000_000_000, 0, 100)).doesNotThrowAnyException();
    }

    @Test
    void aMetaAbaixoNuncaPassaDaNaMetaMasAsDuasMudamJuntas() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE corona.configuracoes SET valor_inteiro = 41 WHERE chave = 'comissao_percentual_abaixo_da_meta'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE corona.configuracoes SET valor_inteiro = 29 WHERE chave = 'comissao_percentual_na_meta'"))
                .isInstanceOf(DataAccessException.class);
        // As duas de uma vez, em qualquer ordem, valem se a conta fecha no fim.
        assertThatCode(() -> configurarComissao(700_000, 50, 60)).doesNotThrowAnyException();
        assertThat(configuracao("comissao_percentual_abaixo_da_meta")).isEqualTo(50);
        assertThatCode(() -> configurarComissao(700_000, 10, 20)).doesNotThrowAnyException();
        assertThatCode(() -> configurarComissao(700_000, 20, 20)).doesNotThrowAnyException();
    }

    // --- o acerto fechado ---

    @Test
    void acertadoSemOFechamentoCompletoRecusado() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET status = 'ACERTADO' WHERE id = ?", acerto))
                .isInstanceOf(DataAccessException.class);
        // Tudo, menos a cotação.
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'x', fechado_em = now(),
                       fechado_por = ?, meta_guarani = 700000, percentual_comissao = 30, vendido_para_meta_guarani = 0,
                       vendido_bruto = 0, descontos = 0, devolvido = 0, vendido_liquido = 0, comissao = 0, faltas_cobradas = 0,
                       liquido = 0, recebido_no_acerto = 0, ficou_devendo = 0 WHERE id = ?""", UUID.randomUUID(),
                usuarios.dona().id(), acerto)).isInstanceOf(DataAccessException.class);
        // Os campos do fechamento num acerto que não está fechado.
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET vendido_bruto = 10 WHERE id = ?", acerto))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void asContasDoItem8SaoConferidasPeloBanco() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        // vendido_liquido diferente de bruto - descontos - devolvido.
        assertThatThrownBy(() -> fecharComTotais(acerto, "100", "10", "0", "95", "0", "0", "95", "0", "95"))
                .isInstanceOf(DataAccessException.class);
        // liquido diferente de vendido_liquido - comissao + faltas.
        assertThatThrownBy(() -> fecharComTotais(acerto, "100", "10", "0", "90", "9", "0", "82", "0", "82"))
                .isInstanceOf(DataAccessException.class);
        // liquido diferente de recebido + ficou devendo.
        assertThatThrownBy(() -> fecharComTotais(acerto, "100", "10", "0", "90", "9", "0", "81", "50", "30"))
                .isInstanceOf(DataAccessException.class);
        // Valores que nunca são negativos.
        assertThatThrownBy(() -> fecharComTotais(acerto, "-100", "-110", "0", "10", "0", "0", "10", "0", "10"))
                .isInstanceOf(DataAccessException.class);
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", acerto)).isEqualTo(1);
    }

    @Test
    void acertoAFavorDoVendedorGuardaOSinal() throws Exception {
        UUID acerto = acertoNoBanco(vendedor, semana);
        UUID devolucao = devolucaoDaDona();
        eventoNoBanco(acerto, vendedor, "DEVOLUCAO", devolucao, venda, "-50.00");
        // Só devolução: vendido_liquido -50, líquido -50 (o sinal nunca se esconde).
        assertThatCode(() -> transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "0", "0", "50", "0", "0")))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT liquido FROM corona.acertos WHERE id = ?", java.math.BigDecimal.class, acerto))
                .isEqualByComparingTo("-50.00");
    }

    @Test
    void fechamentoCompletoQueConfereComEventosEComissoesEAceito() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        assertThatCode(() -> transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "50.00", "0", "0", "15.00", "0")))
                .doesNotThrowAnyException();
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'ACERTADO'", acerto)).isEqualTo(1);
    }

    @Test
    void oGatilhoAdiadoRecusaTotaisQueNaoBatemComEventosComissoesEPendencias() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        // Vendido 10 sem nenhum evento.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "10", "0", "0", "0", "0")))
                .isInstanceOf(RuntimeException.class);
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        // Comissão 5 sem nenhuma linha de comissão.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "50", "0", "0", "5", "0")))
                .isInstanceOf(RuntimeException.class);
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        // Bruto e descontos diferentes das vendas dos eventos.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "60", "10", "0", "15", "0")))
                .isInstanceOf(RuntimeException.class);
        // Faltas cobradas 20 sem pendência cobrada neste acerto.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "50", "0", "0", "15", "20")))
                .isInstanceOf(RuntimeException.class);
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", acerto)).isEqualTo(1);
    }

    @Test
    void faltaCobradaNoAcertoEntraNasFaltasCobradas() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        assertThatCode(() -> transacao.executeWithoutResult(s -> {
            pendenciaNoBanco(acerto, vendedor, "FALTA", peca, maleta, 1, "50.00", "COBRADA", acerto, null);
            fecharNoBanco(acerto, "50.00", "0", "0", "15.00", "50.00");
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT liquido FROM corona.acertos WHERE id = ?", java.math.BigDecimal.class, acerto))
                .isEqualByComparingTo("85.00");
    }

    @Test
    void acertoFechadoItensEventosEComissoesNaoMudamMaisNemApagam() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        UUID comissao = comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        jdbc.update("""
                INSERT INTO corona.itens_acerto (id, acerto_id, produto_id, remessa_id, preco_remessa, esperado, contado_boa,
                    contado_defeito, falta, defeito_aprovado) VALUES (uuidv7(), ?, ?, ?, 50.00, 9, 9, 0, 0, 0)""", acerto, peca, maleta);
        transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "50.00", "0", "0", "15.00", "0"));

        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET liquido = 1, ficou_devendo = 1, vendido_liquido = 1 WHERE id = ?", acerto))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET status = 'EM_ACERTO' WHERE id = ?", acerto))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET recebido_no_acerto = 1, ficou_devendo = 34 WHERE id = ?", acerto))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.acertos WHERE id = ?", acerto)).isInstanceOf(DataAccessException.class);
        for (String tabela : List.of("itens_acerto", "acerto_eventos", "comissoes")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE corona." + tabela + " SET version = version + 1 WHERE acerto_id = ?", acerto))
                    .as("UPDATE em " + tabela).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM corona." + tabela + " WHERE acerto_id = ?", acerto))
                    .as("DELETE em " + tabela).isInstanceOf(DataAccessException.class);
        }
        // Nada entra mais num acerto fechado.
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "VENDA", UUID.randomUUID(), venda, "50.00"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null))
                .isInstanceOf(DataAccessException.class);
        assertThat(comissao).isNotNull();
    }

    // --- a maleta acertada ---

    @Test
    void maletaSoVaiParaAcertadaComSaldoZeroEDepoisNaoMudaNemRecebeMovimento() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        ligarNoBanco(maleta, acerto);
        // Saldo 9 (10 enviadas, 1 vendida): recusada.
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", maleta))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (uuidv7(), ?, ?, 'PERDA', -9)",
                maleta, peca);
        assertThatCode(() -> jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", maleta)).doesNotThrowAnyException();
        assertThat(statusDaMaleta(maleta)).isEqualTo("ACERTADA");
        assertThat(jdbc.queryForObject("SELECT acerto_id FROM corona.remessas WHERE id = ?", UUID.class, maleta)).isEqualTo(acerto);

        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET status = 'CONFERIDA' WHERE id = ?", maleta))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET acerto_id = NULL, status_antes_do_acerto = NULL WHERE id = ?", maleta))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (uuidv7(), ?, ?, 'DEVOLUCAO_DONA', -1)""",
                maleta, peca)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (uuidv7(), ?, ?, 'AJUSTE', 1)""",
                maleta, peca)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void maletaNaoVaiParaAcertadaSemPassarPeloAcertoEmAndamento() {
        // Direto de CONFERIDA para ACERTADA, sem acerto: recusado.
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", maleta))
                .isInstanceOf(DataAccessException.class);
    }

    // --- os eventos ---

    @Test
    void cadaEventoEntraEmUmAcertoSo() throws Exception {
        UUID primeiro = acertoNoBanco(vendedor, semana);
        eventoNoBanco(primeiro, vendedor, "VENDA", venda, venda, "50.00");
        desistirNoBanco(primeiro);
        UUID segundo = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> eventoNoBanco(segundo, vendedor, "VENDA", venda, venda, "50.00")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void eventoComValorDiferenteDaOrigemOuDeOutroVendedorOuDeTipoErradoRecusado() throws Exception {
        UUID acerto = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "49.99")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "-50.00")).isInstanceOf(DataAccessException.class);
        // Estorno de venda que não foi estornada.
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "ESTORNO_VENDA", venda, venda, "-50.00"))
                .isInstanceOf(DataAccessException.class);
        // O acerto é de outro vendedor.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID doOutro = acertoNoBanco(outro, semana());
        assertThatThrownBy(() -> eventoNoBanco(doOutro, outro, "VENDA", venda, venda, "50.00")).isInstanceOf(DataAccessException.class);
        // Origem que não existe.
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "VENDA", UUID.randomUUID(), venda, "50.00"))
                .isInstanceOf(DataAccessException.class);
        // Tipo que não existe.
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "TROCA", venda, venda, "50.00")).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00")).doesNotThrowAnyException();
    }

    @Test
    void estornoDeVendaEDevolucaoSoEntramComOValorDaOrigem() throws Exception {
        UUID vendaEstornada = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        postar("/api/dona/vendas/" + vendaEstornada + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "teste"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        UUID devolucao = devolucaoDaDona();
        UUID acerto = acertoNoBanco(vendedor, semana);
        eventoNoBanco(acerto, vendedor, "VENDA", vendaEstornada, vendaEstornada, "50.00");
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "ESTORNO_VENDA", vendaEstornada, vendaEstornada, "-49.00"))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> eventoNoBanco(acerto, vendedor, "ESTORNO_VENDA", vendaEstornada, vendaEstornada, "-50.00"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "DEVOLUCAO", devolucao, venda, "-49.00"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> eventoNoBanco(acerto, vendedor, "DEVOLUCAO", devolucao, venda, "50.00"))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> eventoNoBanco(acerto, vendedor, "DEVOLUCAO", devolucao, venda, "-50.00")).doesNotThrowAnyException();
    }

    @Test
    void devolucaoLigadaAUmAcertoNaoPodeSerCancelada() throws Exception {
        UUID devolucao = devolucaoDaDona();
        UUID acerto = acertoNoBanco(vendedor, semana);
        eventoNoBanco(acerto, vendedor, "DEVOLUCAO", devolucao, venda, "-50.00");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.devolucoes SET situacao = 'CANCELADA', cancelamento_id = ?, cancelada_em = now(), cancelada_por = ?,
                       motivo_cancelamento = 'teste' WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), devolucao))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("acerto");
    }

    // --- as comissões ---

    @Test
    void comissaoSoComUmaLinhaPorVendaEComSinalCerto() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        // COMISSAO negativa e ESTORNO positivo ou sem a linha original.
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "-15.00", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, venda, evento, "ESTORNO", "50.00", "30", "-15.00", null))
                .isInstanceOf(DataAccessException.class);
        UUID comissao = comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null))
                .isInstanceOf(DataAccessException.class);
        assertThat(comissao).isNotNull();
    }

    @Test
    void estornoDeComissaoTemOPercentualDaLinhaOriginalENuncaPassaDaComissao() throws Exception {
        UUID vendaEstornada = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        postar("/api/dona/vendas/" + vendaEstornada + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "teste"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        UUID acerto = acertoNoBanco(vendedor, semana);
        UUID eventoVenda = eventoNoBanco(acerto, vendedor, "VENDA", vendaEstornada, vendaEstornada, "50.00");
        UUID eventoEstorno = eventoNoBanco(acerto, vendedor, "ESTORNO_VENDA", vendaEstornada, vendaEstornada, "-50.00");
        UUID original = comissaoNoBanco(acerto, vendedor, vendaEstornada, eventoVenda, "COMISSAO", "50.00", "40", "20.00", null);
        // Outro percentual que não o da linha original.
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, vendaEstornada, eventoEstorno, "ESTORNO", "50.00", "30", "-15.00", original))
                .isInstanceOf(DataAccessException.class);
        // Mais do que a comissão.
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, vendaEstornada, eventoEstorno, "ESTORNO", "50.00", "40", "-20.01", original))
                .isInstanceOf(DataAccessException.class);
        // Estorno de uma linha que não é a COMISSAO da venda.
        UUID outraVenda = venda;
        UUID eventoOutra = eventoNoBanco(acerto, vendedor, "VENDA", outraVenda, outraVenda, "50.00");
        UUID comissaoOutra = comissaoNoBanco(acerto, vendedor, outraVenda, eventoOutra, "COMISSAO", "50.00", "40", "20.00", null);
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, vendaEstornada, eventoEstorno, "ESTORNO", "50.00", "40", "-20.00", comissaoOutra))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> comissaoNoBanco(acerto, vendedor, vendaEstornada, eventoEstorno, "ESTORNO", "50.00", "40", "-20.00", original))
                .doesNotThrowAnyException();
        // Um estorno por evento.
        assertThatThrownBy(() -> comissaoNoBanco(acerto, vendedor, vendaEstornada, eventoEstorno, "ESTORNO", "50.00", "40", "-0.01", original))
                .isInstanceOf(DataAccessException.class);
    }

    // --- as pendências ---

    @Test
    void pendenciaNasceDecididaEMudaSoPelasTransicoesPermitidas() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        // Nascimentos que não valem.
        assertThatThrownBy(() -> pendenciaNoBanco(acerto, vendedor, "DEFEITO", peca, maleta, 1, "50.00", "ABERTA", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> pendenciaNoBanco(acerto, vendedor, "FALTA", peca, maleta, 1, "50.00", "ENCONTRADA", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> pendenciaNoBanco(acerto, vendedor, "FALTA", peca, maleta, 1, "50.00", "ASSUMIDA_PELA_LOJA", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> pendenciaNoBanco(acerto, vendedor, "SOBRA", peca, maleta, 1, "50.00", "ABERTA", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> pendenciaNoBanco(acerto, vendedor, "FALTA", peca, maleta, 1, "-1.00", "ABERTA", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> pendenciaNoBanco(acerto, vendedor, "DEFEITO", peca, maleta, 1, "50.00", "APROVADA", null, null))
                .doesNotThrowAnyException();

        UUID aberta = pendenciaNoBanco(acerto, vendedor, "FALTA", peca, maleta, 1, "50.00", "ABERTA", null, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.pendencias_acerto SET valor = 1 WHERE id = ?", aberta))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.pendencias_acerto SET quantidade = 2 WHERE id = ?", aberta))
                .isInstanceOf(DataAccessException.class);
        // Assumir sem motivo.
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.pendencias_acerto SET status = 'ASSUMIDA_PELA_LOJA', resolucao_id = ?, resolvida_em = now(), resolvida_por = ?
                 WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), aberta)).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> jdbc.update("""
                UPDATE corona.pendencias_acerto SET status = 'ASSUMIDA_PELA_LOJA', resolucao_id = ?, resolvida_em = now(), resolvida_por = ?,
                       motivo = 'perda da loja' WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), aberta)).doesNotThrowAnyException();
        // O resto é final.
        for (String novo : List.of("ABERTA", "ENCONTRADA", "COBRADA")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE corona.pendencias_acerto SET status = ? WHERE id = ?", novo, aberta))
                    .as("de ASSUMIDA para " + novo).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.pendencias_acerto WHERE id = ?", aberta)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void soAFaltaAbertaPodeSerEncontradaESoUmAcertoDoMesmoVendedorCobra() throws Exception {
        UUID acerto = acertoNoBanco(vendedor, semana);
        UUID falta = pendenciaNoBanco(acerto, vendedor, "FALTA", peca, maleta, 1, "50.00", "ABERTA", null, null);
        UUID naoAceita = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        jdbc.update("""
                INSERT INTO corona.pendencias_acerto (id, acerto_id, vendedor_id, tipo, valor, origem_tipo, origem_id, status)
                VALUES (?, ?, ?, 'DEVOLUCAO_NAO_ACEITA', 20.00, 'DEVOLUCAO', ?, 'ABERTA')""", naoAceita, acerto, vendedor.vendedorId(),
                UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.pendencias_acerto SET status = 'ENCONTRADA', resolucao_id = ?, resolvida_em = now(), resolvida_por = ?,
                       motivo = 'achou' WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), naoAceita))
                .isInstanceOf(DataAccessException.class);
        // Cobrar num acerto de outro vendedor, ou que não está em andamento.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID doOutro = acertoNoBanco(outro, semana());
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.pendencias_acerto SET status = 'COBRADA', cobrada_no_acerto_id = ? WHERE id = ?",
                doOutro, falta)).isInstanceOf(DataAccessException.class);
    }

    // --- o estoque ---

    @Test
    void movimentoDoEstoqueAceitaAOrigemAcerto() {
        UUID acerto = acertoNoBanco(vendedor, semana);
        assertThatCode(() -> estoque.registrar(new Pedido(null, peca, TipoDeMovimento.ENTRADA, 1, null,
                OrigemDoMovimento.valueOf("ACERTO"), acerto), usuarios.dona().id())).doesNotThrowAnyException();
        // Devolve o saldo para a conciliação continuar zerada: a peça entrou "do acerto", sai por ajuste manual.
        estoque.registrar(new Pedido(null, peca, TipoDeMovimento.SAIDA, 1, "teste", OrigemDoMovimento.MANUAL, null), usuarios.dona().id());
    }

    // --- apoio ---

    private int configuracao(String chave) {
        return jdbc.queryForObject("SELECT valor_inteiro FROM corona.configuracoes WHERE chave = ?", Integer.class, chave);
    }

    private void fecharComTotais(UUID acerto, String bruto, String descontos, String devolvido, String vendidoLiquido, String comissao,
            String faltas, String liquido, String recebido, String ficou) {
        jdbc.update("""
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'teste', fechado_em = now(),
                       fechado_por = ?, cotacao_id = ?, meta_guarani = 700000, percentual_comissao = 30, vendido_para_meta_guarani = 0,
                       vendido_bruto = ?::numeric, descontos = ?::numeric, devolvido = ?::numeric, vendido_liquido = ?::numeric,
                       comissao = ?::numeric, faltas_cobradas = ?::numeric, liquido = ?::numeric, recebido_no_acerto = ?::numeric,
                       ficou_devendo = ?::numeric WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), cotacaoDoExemplo, bruto,
                descontos, devolvido, vendidoLiquido, comissao, faltas, liquido, recebido, ficou, acerto);
    }

    /** A dona devolve a peça da venda do teste (peça boa, para a loja), antes do acerto. Devolve o id da devolução. */
    private UUID devolucaoDaDona() throws Exception {
        UUID id = UUID.randomUUID();
        postar("/api/dona/devolucoes", dona(), mapa("id", id, "vendaId", venda, "motivo", "ARREPENDIMENTO", "formaReembolso", "DINHEIRO",
                "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        return id;
    }
}
