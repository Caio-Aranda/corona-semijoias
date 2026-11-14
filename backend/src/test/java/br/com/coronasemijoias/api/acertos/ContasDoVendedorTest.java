package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.caixa.ServicoDeCaixa;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.BaixaFeita;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.ParcelaDoVendedor;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Parcelamento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * As contas do nível 2 no {@code ServicoDeContasAReceber} (tarefa 7.6; ADR-0012, itens 7, 8 e 9): gerar as parcelas do que o
 * vendedor ficou devendo, baixar pelo recebimento (com conta indicada ou distribuindo por vencimento) e desfazer a baixa. Os
 * recebimentos e o caixa são gravados aqui por SQL e pelo {@code ServicoDeCaixa} (o serviço de recebimentos é do passo seguinte).
 */
class ContasDoVendedorTest extends ApoioDoFechamento {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    @Autowired
    ServicoDeContasAReceber contas;

    @Autowired
    ServicoDeCaixa caixa;

    private UsuarioDeTeste vendedor;
    private UUID semana;
    private UUID peca;
    private UUID maleta;
    private UUID cliente;

    @BeforeEach
    void preparar() throws Exception {
        vendedor = usuarios.criarVendedor();
        semana = semana();
        peca = peca("50.00", 20);
        maleta = maletaConferida(vendedor, semana, peca, 10);
        cliente = cliente(vendedor);
    }

    private static LocalDate hoje() {
        return LocalDate.now(BRASILIA);
    }

    /** Fecha um acerto com a venda de R$ 50,00 (líquido 35,00) gerando as contas do jeito do serviço; devolve as parcelas. */
    private List<ParcelaDoVendedor> fecharComContas(BigDecimal boleto, LocalDate vencimentoDoBoleto, BigDecimal restante,
            Parcelamento parcelamento) throws Exception {
        UUID venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto = acertoNoBanco(vendedor, semana());
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        return transacao.execute(s -> {
            List<ParcelaDoVendedor> geradas = contas.gerarContasDoAcerto(vendedor.vendedorId(), acerto, boleto, vencimentoDoBoleto, restante,
                    parcelamento);
            fecharComDinheiro(acerto, "0", "0", "35.00", "0", null);
            return geradas;
        });
    }

    /** Um recebimento avulso das contas, gravado por SQL, com o lançamento de caixa e as baixas do serviço. */
    private UUID receber(UsuarioDeTeste de, String forma, String valor, UUID contaId) {
        return transacao.execute(s -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, aplicado_em, forma, valor, registrado_por)
                    VALUES (?, ?, 'AVULSO', 'CONTAS', ?, ?::numeric, ?)""", id, de.vendedorId(), forma, valor, usuarios.dona().id());
            caixa.lancarRecebimento(usuarios.dona().id(), id, forma, new BigDecimal(valor));
            contas.baixarPeloRecebimento(de.vendedorId(), id, forma, new BigDecimal(valor), contaId, null);
            return id;
        });
    }

    private static CodigoErro codigo(Throwable t) {
        Throwable atual = t;
        while (atual != null && !(atual instanceof ErroDaApi)) {
            atual = atual.getCause();
        }
        return atual == null ? null : ((ErroDaApi) atual).codigo();
    }

    private String situacao(UUID conta) {
        return jdbc.queryForObject("SELECT status || ':' || valor_pago FROM corona.contas_receber WHERE id = ?", String.class, conta);
    }

    // --- gerar ---

    @Test
    void restanteEmTresParcelasMensaisSomaOValorEOBoletoEhAPrimeiraConta() throws Exception {
        UUID venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto = acertoNoBanco(vendedor, semana());
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        LocalDate primeiro = hoje().plusDays(40);
        List<ParcelaDoVendedor> geradas = transacao.execute(s -> {
            List<ParcelaDoVendedor> g = contas.gerarContasDoAcerto(vendedor.vendedorId(), acerto, null, null,
                    new BigDecimal("35.00"), new Parcelamento(3, primeiro));
            fecharComDinheiro(acerto, "0", "0", "35.00", "0", null);
            return g;
        });
        // 35,00 em 3: as duas primeiras arredondadas (11,67) e a última fecha o total (11,66).
        assertThat(geradas).extracting(ParcelaDoVendedor::valor).extracting(BigDecimal::toPlainString)
                .containsExactly("11.67", "11.67", "11.66");
        assertThat(geradas).extracting(ParcelaDoVendedor::numero).containsExactly(1, 2, 3);
        assertThat(geradas).extracting(ParcelaDoVendedor::vencimento).containsExactly(primeiro, primeiro.plusMonths(1), primeiro.plusMonths(2));
        assertThat(geradas).allSatisfy(p -> assertThat(p.formaPrevista()).isNull());
    }

    @Test
    void boletoViraAPrimeiraContaESeguemAsParcelasDoRestante() throws Exception {
        LocalDate boletoEm = hoje().plusDays(10);
        List<ParcelaDoVendedor> geradas = fecharComContas(new BigDecimal("20.00"), boletoEm, new BigDecimal("15.00"),
                new Parcelamento(2, hoje().plusDays(30)));
        assertThat(geradas).extracting(ParcelaDoVendedor::numero).containsExactly(1, 2, 3);
        assertThat(geradas).extracting(ParcelaDoVendedor::total).containsOnly(3);
        assertThat(geradas.get(0).formaPrevista()).isEqualTo("BOLETO");
        assertThat(geradas.get(0).valor()).isEqualByComparingTo("20.00");
        assertThat(geradas.get(0).vencimento()).isEqualTo(boletoEm);
        assertThat(geradas.stream().map(ParcelaDoVendedor::valor).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("35.00");
    }

    @Test
    void restanteSemDataOuComDataRuimEhRecusado() throws Exception {
        UUID venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto = acertoNoBanco(vendedor, semana());
        eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        BigDecimal valor = new BigDecimal("35.00");
        UUID v = vendedor.vendedorId();
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> contas.gerarContasDoAcerto(v, acerto, null, null, valor, null)))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.DATA_DO_RESTANTE_OBRIGATORIA));
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> contas.gerarContasDoAcerto(v, acerto, null, null, valor,
                new Parcelamento(1, null)))).satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.DATA_DO_RESTANTE_OBRIGATORIA));
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> contas.gerarContasDoAcerto(v, acerto, null, null, valor,
                new Parcelamento(1, hoje().minusDays(1))))).isInstanceOf(ErroDaApi.class);
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> contas.gerarContasDoAcerto(v, acerto, null, null, valor,
                new Parcelamento(25, hoje().plusDays(5))))).isInstanceOf(ErroDaApi.class);
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> contas.gerarContasDoAcerto(v, acerto, null, null, valor,
                new Parcelamento(0, hoje().plusDays(5))))).isInstanceOf(ErroDaApi.class);
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> contas.gerarContasDoAcerto(v, acerto, null, null, valor,
                new Parcelamento(1, hoje().plusMonths(13))))).isInstanceOf(ErroDaApi.class);
        // Sem restante, parcelamento não vale.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> contas.gerarContasDoAcerto(v, acerto, new BigDecimal("35.00"),
                hoje().plusDays(3), BigDecimal.ZERO, new Parcelamento(1, hoje().plusDays(5))))).isInstanceOf(ErroDaApi.class);
        assertThat(contar("SELECT count(*) FROM corona.contas_receber WHERE vendedor_id = ?", v)).isZero();
    }

    // --- baixar ---

    @Test
    void recebimentoSemContaDistribuiPorVencimentoDaMaisAntigaParaAMaisNova() throws Exception {
        List<ParcelaDoVendedor> geradas = fecharComContas(null, null, new BigDecimal("35.00"), new Parcelamento(3, hoje().plusDays(20)));
        // 11,67 + 11,67 + 11,66: um recebimento de 20,00 quita a primeira e parte da segunda.
        receber(vendedor, "DINHEIRO", "20.00", null);
        assertThat(situacao(geradas.get(0).id())).isEqualTo("PAGA:11.67");
        assertThat(situacao(geradas.get(1).id())).isEqualTo("PARCIAL:8.33");
        assertThat(situacao(geradas.get(2).id())).isEqualTo("ABERTA:0.00");
        receber(vendedor, "PIX", "15.00", null);
        assertThat(situacao(geradas.get(1).id())).isEqualTo("PAGA:11.67");
        assertThat(situacao(geradas.get(2).id())).isEqualTo("PAGA:11.66");
    }

    @Test
    void recebimentoComContaBaixaSoEssaContaEOForaDoSaldoEhRecusado() throws Exception {
        List<ParcelaDoVendedor> geradas = fecharComContas(null, null, new BigDecimal("35.00"), new Parcelamento(2, hoje().plusDays(20)));
        UUID segunda = geradas.get(1).id();
        receber(vendedor, "DINHEIRO", "5.00", segunda);
        assertThat(situacao(geradas.get(0).id())).startsWith("ABERTA");
        assertThat(situacao(segunda)).isEqualTo("PARCIAL:5.00");
        // Passa do saldo da conta (17,50 - 5,00 = 12,50).
        assertThatThrownBy(() -> receber(vendedor, "DINHEIRO", "12.51", segunda))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.RECEBIMENTO_DO_VENDEDOR_ACIMA_DO_EM_ABERTO));
        // Conta de outro vendedor: 404.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        assertThatThrownBy(() -> receber(outro, "DINHEIRO", "1.00", segunda))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.CONTA_DO_VENDEDOR_NAO_ENCONTRADA));
        // Vendedor sem conta em aberto.
        assertThatThrownBy(() -> receber(outro, "DINHEIRO", "1.00", null))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.VENDEDOR_SEM_CONTA_EM_ABERTO));
        // Acima de tudo que ele deve.
        assertThatThrownBy(() -> receber(vendedor, "DINHEIRO", "30.01", null))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.RECEBIMENTO_DO_VENDEDOR_ACIMA_DO_EM_ABERTO));
        assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE vendedor_id = ?", vendedor.vendedorId())).isEqualTo(1);
    }

    @Test
    void boletoPagoSoInteiroENaContaDoBoleto() throws Exception {
        List<ParcelaDoVendedor> geradas = fecharComContas(new BigDecimal("20.00"), hoje().plusDays(10), new BigDecimal("15.00"),
                new Parcelamento(1, hoje().plusDays(30)));
        UUID boleto = geradas.get(0).id();
        UUID comum = geradas.get(1).id();
        assertThatThrownBy(() -> receber(vendedor, "BOLETO", "10.00", boleto))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.BOLETO_PAGO_PRECISA_SER_INTEIRO));
        assertThatThrownBy(() -> receber(vendedor, "BOLETO", "15.00", comum))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.BOLETO_PAGO_PRECISA_SER_INTEIRO));
        assertThatThrownBy(() -> receber(vendedor, "BOLETO", "20.00", null))
                .satisfies(e -> assertThat(codigo(e)).isEqualTo(CodigoErro.BOLETO_PAGO_PRECISA_SER_INTEIRO));
        receber(vendedor, "BOLETO", "20.00", boleto);
        assertThat(situacao(boleto)).isEqualTo("PAGA:20.00");
        // Sem distribuição para o boleto: dinheiro sem conta vai para as outras primeiro? Vai por vencimento (o boleto já está pago).
        receber(vendedor, "DINHEIRO", "15.00", null);
        assertThat(situacao(comum)).isEqualTo("PAGA:15.00");
    }

    @Test
    void estornarAsBaixasReabreAsContasEOEstornoDoEstornoEhRecusadoPeloBanco() throws Exception {
        List<ParcelaDoVendedor> geradas = fecharComContas(null, null, new BigDecimal("35.00"), new Parcelamento(2, hoje().plusDays(20)));
        UUID recebimento = receber(vendedor, "DINHEIRO", "20.00", null);
        assertThat(situacao(geradas.get(0).id())).isEqualTo("PAGA:17.50");
        assertThat(situacao(geradas.get(1).id())).isEqualTo("PARCIAL:2.50");
        List<BaixaFeita> desfeitas = transacao.execute(s -> {
            UUID estorno = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, aplicado_em, forma, valor, registrado_por, estorna_id, motivo)
                    VALUES (?, ?, 'AVULSO', 'CONTAS', 'DINHEIRO', -20, ?, ?, 'lancei errado')""", estorno, vendedor.vendedorId(),
                    usuarios.dona().id(), recebimento);
            caixa.estornarRecebimento(usuarios.dona().id(), recebimento, estorno);
            return contas.estornarBaixasDoRecebimento(recebimento, estorno, null);
        });
        assertThat(desfeitas).extracting(BaixaFeita::valor).extracting(BigDecimal::toPlainString).containsExactly("-17.50", "-2.50");
        assertThat(situacao(geradas.get(0).id())).isEqualTo("ABERTA:0.00");
        assertThat(situacao(geradas.get(1).id())).isEqualTo("ABERTA:0.00");
        // O caixa voltou ao que era.
        assertThat(jdbc.queryForObject("SELECT sum(valor) FROM corona.lancamentos_caixa WHERE origem_id IN (SELECT id FROM corona.recebimentos_vendedor WHERE vendedor_id = ?)",
                BigDecimal.class, vendedor.vendedorId())).isEqualByComparingTo("0.00");
    }
}
