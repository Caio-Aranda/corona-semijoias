package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.EstornoDoRecibo;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.FormaPaga;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.NovoPagamento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lançar pagamento e estorno de recibo (ADR-0016, itens 10 e 12). PostgreSQL de verdade. Escritos antes do código. */
class ServicoDeRecibosTest extends ApoioDoConsignado {

    /** Três notas do mesmo cliente acertadas sem pagar: débitos de 100, 200 e 300 (do mais antigo ao mais novo). */
    private record Tres(UsuarioDeTeste v, UUID cliente, UUID semana, UUID n1, UUID n2, UUID n3, UUID d1, UUID d2, UUID d3) {
    }

    private Tres tres() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("100.00", 9);
        UUID maleta = maletaEnviada(v, peca, 9);
        UUID semana = abrirSemana(v);
        UUID n1 = criarNota(v, semana, 1, cliente, item(peca, maleta, 1, "100.00"));
        UUID d1 = acertar(v, n1, semana, "0", List.of(), null);
        UUID n2 = criarNota(v, semana, 2, cliente, item(peca, maleta, 2, "100.00"));
        UUID d2 = acertar(v, n2, semana, "0", List.of(), null);
        UUID n3 = criarNota(v, semana, 3, cliente, item(peca, maleta, 3, "100.00"));
        UUID d3 = acertar(v, n3, semana, "0", List.of(), null);
        return new Tres(v, cliente, semana, n1, n2, n3, d1, d2, d3);
    }

    @Test
    void lancarPagamentoAbateOMaisAntigoPrimeiro() throws Exception {
        Tres t = tres();
        assertThat(pendencia(t.cliente())).isEqualByComparingTo("600.00");
        UUID recibo = UUID.randomUUID();
        NovoPagamento pedido = new NovoPagamento(recibo, t.cliente(), t.semana(), 4, List.of(paga("DINHEIRO", "350.00")), null, null);
        assertThat(recibos.lancar(ctx(t.v()), pedido)).isFalse();
        assertThat(recibos.lancar(ctx(t.v()), pedido)).isTrue();

        assertThat(saldoDoDebito(t.d1())).isEqualByComparingTo("0.00");
        assertThat(saldoDoDebito(t.d2())).isEqualByComparingTo("0.00");
        assertThat(saldoDoDebito(t.d3())).isEqualByComparingTo("250.00");
        assertThat(situacaoDaNota(t.n1())).isEqualTo("FECHADO");
        assertThat(situacaoDaNota(t.n2())).isEqualTo("FECHADO");
        assertThat(situacaoDaNota(t.n3())).isEqualTo("PENDENTE");
        assertThat(pendencia(t.cliente())).isEqualByComparingTo("250.00");
        assertThat(aAplicar(recibo)).isEqualByComparingTo("0.00");
        assertThat(valor("SELECT coalesce(sum(valor), 0) FROM corona.aplicacoes_recibo WHERE recibo_id = ?", recibo)).isEqualByComparingTo("350.00");
    }

    @Test
    void pagamentoAcimaDoDevidoEAceitoComAlertaESobraAAplicar() throws Exception {
        Tres t = tres();
        UUID recibo = UUID.randomUUID();
        recibos.lancar(ctx(t.v()), new NovoPagamento(recibo, t.cliente(), t.semana(), 4, List.of(paga("PIX", "700.00")), null, null));
        assertThat(pendencia(t.cliente())).isEqualByComparingTo("0.00");
        assertThat(aAplicar(recibo)).isEqualByComparingTo("100.00");
        assertThat(alertas(t.v(), "PAGAMENTO_ACIMA_DO_DEVIDO")).isEqualTo(1);
        assertThat(situacaoDaNota(t.n3())).isEqualTo("FECHADO");
    }

    @Test
    void estornoDoReciboReabreAPendenciaEVoltaANotaAPendente() throws Exception {
        Tres t = tres();
        UUID recibo = UUID.randomUUID();
        recibos.lancar(ctx(t.v()), new NovoPagamento(recibo, t.cliente(), t.semana(), 4, List.of(paga("DINHEIRO", "350.00")), null, null));
        UUID estorno = UUID.randomUUID();
        EstornoDoRecibo pedido = new EstornoDoRecibo(estorno, recibo, "Lancei o valor errado");
        assertThat(recibos.estornar(ctx(t.v()), pedido)).isFalse();
        assertThat(recibos.estornar(ctx(t.v()), pedido)).isTrue();

        assertThat(pendencia(t.cliente())).isEqualByComparingTo("600.00");
        assertThat(situacaoDaNota(t.n1())).isEqualTo("PENDENTE");
        assertThat(situacaoDaNota(t.n2())).isEqualTo("PENDENTE");
        assertThat(situacaoDaNota(t.n3())).isEqualTo("PENDENTE");
        assertThat(aAplicar(recibo)).isEqualByComparingTo("0.00");
        Throwable outro = catchThrowable(() -> recibos.estornar(ctx(t.v()), new EstornoDoRecibo(UUID.randomUUID(), recibo, "de novo")));
        assertThat(codigoDoErro(outro)).isEqualTo("RECIBO_JA_ESTORNADO_DO_CONSIGNADO");
    }

    @Test
    void reciboDeAcertoSoSeEstornaJuntoComOAcerto() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("100.00", 5);
        UUID maleta = maletaEnviada(v, peca, 5);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente(v), item(peca, maleta, 1, "100.00"));
        UUID acerto = acertar(v, nota, semana, "0", List.of(), 2, paga("DINHEIRO", "40.00"));
        UUID recibo = reciboDoAcerto(acerto);
        Throwable erro = catchThrowable(() -> recibos.estornar(ctx(v), new EstornoDoRecibo(UUID.randomUUID(), recibo, "Quero desfazer")));
        assertThat(codigoDoErro(erro)).isEqualTo("RECIBO_DO_ACERTO");
    }

    @Test
    void formaInvalidaValorNaoPositivoEFormaRepetidaSaoRecusados() throws Exception {
        Tres t = tres();
        for (List<FormaPaga> formas : List.of(List.of(paga("CHEQUE", "10.00")), List.of(paga("PIX", "0.00")),
                List.of(paga("PIX", "-5.00")), List.of(paga("PIX", "1.005")), List.of(paga("PIX", "5.00"), paga("PIX", "5.00")),
                List.<FormaPaga>of())) {
            Throwable erro = catchThrowable(() -> recibos.lancar(ctx(t.v()),
                    new NovoPagamento(UUID.randomUUID(), t.cliente(), t.semana(), 4, formas, null, null)));
            assertThat(codigoDoErro(erro)).isEqualTo("PAGAMENTO_INVALIDO");
        }
        assertThat(pendencia(t.cliente())).isEqualByComparingTo("600.00");
        assertThat(new BigDecimal("1")).isNotNull();
    }

    @Test
    void clienteDeOutroVendedorENumeroRepetidoSaoRecusados() throws Exception {
        Tres t = tres();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID semanaDoOutro = abrirSemana(outro);
        Throwable alheio = catchThrowable(() -> recibos.lancar(ctx(outro),
                new NovoPagamento(UUID.randomUUID(), t.cliente(), semanaDoOutro, 1, List.of(paga("PIX", "10.00")), null, null)));
        assertThat(codigoDoErro(alheio)).isEqualTo("CLIENTE_DE_OUTRO_VENDEDOR");
        // O recibo 4 já foi usado (a nota 3 e o recibo 3 não disputam o número, 9.8).
        recibos.lancar(ctx(t.v()), new NovoPagamento(UUID.randomUUID(), t.cliente(), t.semana(), 4, List.of(paga("PIX", "10.00")), null, null));
        Throwable repetido = catchThrowable(() -> recibos.lancar(ctx(t.v()),
                new NovoPagamento(UUID.randomUUID(), t.cliente(), t.semana(), 4, List.of(paga("PIX", "10.00")), null, null)));
        assertThat(codigoDoErro(repetido)).isEqualTo("NUMERO_REPETIDO");
    }

    @Test
    void dataDoPagamentoNoPassadoGravaODiaEscolhidoENuncaOFuturo() throws Exception {
        Tres t = tres();
        java.time.LocalDate ontem = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).minusDays(1);
        UUID recibo = UUID.randomUUID();
        recibos.lancar(ctx(t.v()),
                new NovoPagamento(recibo, t.cliente(), t.semana(), 4, List.of(paga("PIX", "50.00")), null, ontem));
        java.sql.Timestamp pagoEm = jdbc.queryForObject("SELECT pago_em FROM corona.recibos_consignado WHERE id = ?",
                java.sql.Timestamp.class, recibo);
        assertThat(java.time.LocalDate.ofInstant(pagoEm.toInstant(), java.time.ZoneId.of("America/Sao_Paulo"))).isEqualTo(ontem);

        java.time.LocalDate amanha = ontem.plusDays(2);
        Throwable erro = catchThrowable(() -> recibos.lancar(ctx(t.v()),
                new NovoPagamento(UUID.randomUUID(), t.cliente(), t.semana(), 5, List.of(paga("PIX", "10.00")), null, amanha)));
        assertThat(codigoDoErro(erro)).isEqualTo("DATA_DO_PAGAMENTO_NO_FUTURO");
    }
}
