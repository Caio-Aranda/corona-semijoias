package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.EstornoDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.PecaQueEntra;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.PecaQueVolta;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Remarcacao;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Reposicao;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Troca;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.NovoPagamento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O acerto da nota (ADR-0016, itens 8, 10 e 12; ESPECIFICACAO §23.8), com PostgreSQL de verdade. O caso da nota {@code 0000064-21}
 * de ponta a ponta, com a pendência da {@code 0000063-32}. Escritos antes do código.
 */
class ServicoDeAcertosDaNotaTest extends ApoioDoConsignado {

    /** A pendência de 498,10 da nota 0000063-32, a nota 0000064-21 da especificação (DX 26, DV 15) e a maleta delas. */
    private record Caso(UsuarioDeTeste v, UUID cliente, UUID maleta, UUID a, UUID b, UUID c, UUID peca498, UUID s63, UUID s64, UUID s65,
            UUID nota63, UUID acerto63, UUID nota64) {
    }

    private Caso caso() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("200.00", 30);
        UUID b = peca("187.50", 30);
        UUID c = peca("233.75", 30);
        UUID p498 = peca("498.10", 5);
        UUID maleta = maletaEnviada(v, a, 10, b, 8, c, 8, p498, 2);
        Instant t = relogio.instant();
        UUID s63 = abrirSemana(v, 63, t.minusSeconds(20_000));
        // A nota 0000063-32: 1 peça de 498,10, acertada a 0% sem pagar nada (a pendência de antes).
        UUID nota63 = criarNota(v, s63, 32, cliente, item(p498, maleta, 1, "498.10"));
        UUID s64 = abrirSemana(v, 64, t.minusSeconds(15_000));
        UUID acerto63 = acertar(v, nota63, s64, "0", List.of(), null);
        // A nota 0000064-21 da especificação.
        UUID nota64 = criarNota(v, s64, 21, cliente, item(a, maleta, 10, "200.00"), item(b, maleta, 8, "187.50"),
                item(c, maleta, 8, "233.75"));
        UUID s65 = abrirSemana(v, 65, t.minusSeconds(10_000));
        return new Caso(v, cliente, maleta, a, b, c, p498, s63, s64, s65, nota63, acerto63, nota64);
    }

    @Test
    void notaDa64_21ComAPendenciaDa63_32DePontaAPonta() throws Exception {
        Caso k = caso();
        assertThat(situacaoDaNota(k.nota63())).isEqualTo("PENDENTE");
        assertThat(pendencia(k.cliente())).isEqualByComparingTo("498.10");

        UUID acerto = acertar(k.v(), k.nota64(), k.s65(), "30", List.of(volta(k.a(), k.maleta(), 5), volta(k.b(), k.maleta(), 6),
                volta(k.c(), k.maleta(), 4)), 53, paga("DINHEIRO", "1470.00"), paga("PIX", "300.00"));

        var r = jdbc.queryForMap("SELECT * FROM corona.acertos_nota WHERE id = ?", acerto);
        assertThat(r.get("itens_dx")).isEqualTo(26);
        assertThat(r.get("itens_dv")).isEqualTo(15);
        assertThat(r.get("itens_vd")).isEqualTo(11);
        assertThat((BigDecimal) r.get("valor_dx")).isEqualByComparingTo("5370.00");
        assertThat((BigDecimal) r.get("valor_dv")).isEqualByComparingTo("3060.00");
        assertThat((BigDecimal) r.get("valor_vd")).isEqualByComparingTo("2310.00");
        assertThat((BigDecimal) r.get("comissao")).isEqualByComparingTo("693.00");
        assertThat((BigDecimal) r.get("bruto")).isEqualByComparingTo("1617.00");
        assertThat((BigDecimal) r.get("pendencias_anteriores")).isEqualByComparingTo("498.10");
        assertThat((BigDecimal) r.get("total_a_receber")).isEqualByComparingTo("2115.10");
        assertThat((BigDecimal) r.get("total_pago")).isEqualByComparingTo("1770.00");
        assertThat((BigDecimal) r.get("ficou_devendo")).isEqualByComparingTo("345.10");

        // O pago quita primeiro a pendência de antes (63-32 vira Fechado) e depois esta nota (64-21 fica Pendente de 345,10).
        assertThat(situacaoDaNota(k.nota63())).isEqualTo("FECHADO");
        assertThat(situacaoDaNota(k.nota64())).isEqualTo("PENDENTE");
        assertThat(saldoDoDebito(acerto)).isEqualByComparingTo("345.10");
        assertThat(pendencia(k.cliente())).isEqualByComparingTo("345.10");
        // O recibo é da semana do pagamento (65), número 53, com as duas formas.
        UUID recibo = reciboDoAcerto(acerto);
        assertThat(jdbc.queryForObject("SELECT semana_id FROM corona.recibos_consignado WHERE id = ?", UUID.class, recibo)).isEqualTo(k.s65());
        assertThat(jdbc.queryForObject("SELECT sequencia FROM corona.recibos_consignado WHERE id = ?", Integer.class, recibo)).isEqualTo(53);
        assertThat(contar("SELECT count(*) FROM corona.formas_recibo_consignado WHERE recibo_id = ?", recibo)).isEqualTo(2);
        // As devoluções voltaram à maleta: A 10-10+5, B 8-8+6, C 8-8+4.
        assertThat(disponivel(k.maleta(), k.a())).isEqualTo(5);
        assertThat(disponivel(k.maleta(), k.b())).isEqualTo(6);
        assertThat(disponivel(k.maleta(), k.c())).isEqualTo(4);
        assertThat(contar("SELECT count(*) FROM corona.itens_acerto_nota WHERE acerto_id = ?", acerto)).isEqualTo(3);
    }

    @Test
    void servidorRecalculaOQueOAppMandouErradoEGravaAlerta() throws Exception {
        Caso k = caso();
        var visto = new ServicoDeAcertosDaNota.Visto(new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("1.00"),
                new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("1.00"));
        UUID id = UUID.randomUUID();
        acertos.acertar(ctx(k.v()), new NovoAcerto(id, k.nota64(), k.s65(), new BigDecimal("30"),
                List.of(volta(k.a(), k.maleta(), 5), volta(k.b(), k.maleta(), 6), volta(k.c(), k.maleta(), 4)), null, visto));
        assertThat(valor("SELECT valor_vd FROM corona.acertos_nota WHERE id = ?", id)).isEqualByComparingTo("2310.00");
        assertThat(alertas(k.v(), "VALOR_DO_ACERTO_RECALCULADO")).isEqualTo(1);
    }

    @Test
    void devolucaoMaiorQueOLevadoEPecaForaDaNotaSaoRecusadas() throws Exception {
        Caso k = caso();
        Throwable maior = catchThrowable(() -> acertar(k.v(), k.nota64(), k.s65(), "30", List.of(volta(k.a(), k.maleta(), 11)), null));
        assertThat(codigoDoErro(maior)).isEqualTo("DEVOLUCAO_MAIOR_QUE_O_LEVADO");
        Throwable fora = catchThrowable(() -> acertar(k.v(), k.nota64(), k.s65(), "30", List.of(volta(k.peca498(), k.maleta(), 1)), null));
        assertThat(codigoDoErro(fora)).isEqualTo("DEVOLUCAO_MAIOR_QUE_O_LEVADO");
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", k.nota64())).isZero();
    }

    @Test
    void comissaoForaDoLimiteERecusada() throws Exception {
        Caso k = caso();
        assertThat(codigoDoErro(catchThrowable(() -> acertar(k.v(), k.nota64(), k.s65(), "40.01", List.of(), null))))
                .isEqualTo("COMISSAO_FORA_DO_LIMITE");
        assertThat(codigoDoErro(catchThrowable(() -> acertar(k.v(), k.nota64(), k.s65(), "-1", List.of(), null))))
                .isEqualTo("COMISSAO_FORA_DO_LIMITE");
    }

    @Test
    void segundoAcertoDaMesmaNotaERecusadoEMesmoIdEInofensivo() throws Exception {
        Caso k = caso();
        UUID id = UUID.randomUUID();
        NovoAcerto pedido = new NovoAcerto(id, k.nota64(), k.s65(), new BigDecimal("30"), List.of(), null, null);
        assertThat(acertos.acertar(ctx(k.v()), pedido)).isFalse();
        assertThat(acertos.acertar(ctx(k.v()), pedido)).isTrue();
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", k.nota64())).isEqualTo(1);
        Throwable segundo = catchThrowable(() -> acertar(k.v(), k.nota64(), k.s65(), "30", List.of(), null));
        assertThat(codigoDoErro(segundo)).isEqualTo("NOTA_JA_ACERTADA");
        NovoAcerto outraNota = new NovoAcerto(id, k.nota63(), k.s65(), new BigDecimal("30"), List.of(), null, null);
        assertThat(codigoDoErro(catchThrowable(() -> acertos.acertar(ctx(k.v()), outraNota)))).isEqualTo("CONSIGNADO_JA_REGISTRADO_DIFERENTE");
    }

    @Test
    void notaAcertadaNaoRecebeReposicaoTrocaNemRemarcacao() throws Exception {
        Caso k = caso();
        acertar(k.v(), k.nota64(), k.s65(), "30", List.of(), null);
        Reposicao repor = new Reposicao(UUID.randomUUID(), k.nota64(), List.of(item(k.a(), k.maleta(), 1, "200.00")));
        assertThat(codigoDoErro(catchThrowable(() -> notas.reporNaNota(ctx(k.v()), repor)))).isEqualTo("NOTA_JA_ACERTADA");
        Troca troca = new Troca(UUID.randomUUID(), k.nota64(), new PecaQueVolta(UUID.randomUUID(), k.a(), k.maleta(), UUID.randomUUID()),
                new PecaQueEntra(UUID.randomUUID(), k.b(), k.maleta(), new BigDecimal("187.50"), UUID.randomUUID()));
        assertThat(codigoDoErro(catchThrowable(() -> notas.trocar(ctx(k.v()), troca)))).isEqualTo("NOTA_JA_ACERTADA");
        Remarcacao remarcar = new Remarcacao(UUID.randomUUID(), k.nota64(), LocalDate.now().plusDays(30));
        assertThat(codigoDoErro(catchThrowable(() -> notas.remarcar(ctx(k.v()), remarcar)))).isEqualTo("NOTA_JA_ACERTADA");
    }

    @Test
    void notaRemarcadaEDepoisAcertadaViraFechadoQuandoPaga() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("100.00", 5);
        UUID maleta = maletaEnviada(v, peca, 5);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente(v), item(peca, maleta, 2, "100.00"));
        notas.remarcar(ctx(v), new Remarcacao(UUID.randomUUID(), nota, LocalDate.now().plusDays(30)));
        assertThat(situacaoDaNota(nota)).isEqualTo("REMARCADO");
        acertar(v, nota, semana, "0", List.of(), 2, paga("PIX", "200.00"));
        assertThat(situacaoDaNota(nota)).isEqualTo("FECHADO");
    }

    @Test
    void acertoDeNotaDeSemanaJaFechadaFunciona() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("100.00", 5);
        UUID maleta = maletaEnviada(v, peca, 5);
        Instant t = relogio.instant();
        UUID s1 = abrirSemana(v, 1, t.minusSeconds(9000));
        UUID nota = criarNota(v, s1, 1, cliente(v), item(peca, maleta, 2, "100.00"));
        UUID s2 = abrirSemana(v, 2, t.minusSeconds(4000)); // a semana 1 fechou
        UUID acerto = acertar(v, nota, s2, "30", List.of(volta(peca, maleta, 1)), 1, paga("DINHEIRO", "70.00"));
        assertThat(saldoDoDebito(acerto)).isEqualByComparingTo("0.00");
        assertThat(situacaoDaNota(nota)).isEqualTo("FECHADO");
    }

    // --- estorno ---

    @Test
    void estornoDoAcertoDesfazRetornosReciboEAplicacoesEReabreAPendenciaAntiga() throws Exception {
        Caso k = caso();
        UUID acerto = acertar(k.v(), k.nota64(), k.s65(), "30", List.of(volta(k.a(), k.maleta(), 5), volta(k.b(), k.maleta(), 6),
                volta(k.c(), k.maleta(), 4)), 53, paga("DINHEIRO", "1470.00"), paga("PIX", "300.00"));
        UUID recibo = reciboDoAcerto(acerto);
        assertThat(situacaoDaNota(k.nota63())).isEqualTo("FECHADO");

        UUID estorno = UUID.randomUUID();
        EstornoDoAcerto pedido = new EstornoDoAcerto(estorno, acerto, "Digitei a devolução errada");
        assertThat(acertos.estornar(ctx(k.v()), pedido)).isFalse();
        assertThat(acertos.estornar(ctx(k.v()), pedido)).isTrue();

        // As peças voltam a ficar com o cliente (saldo da maleta como antes do acerto).
        assertThat(disponivel(k.maleta(), k.a())).isZero();
        assertThat(disponivel(k.maleta(), k.b())).isZero();
        assertThat(disponivel(k.maleta(), k.c())).isZero();
        // O recibo do acerto foi estornado e o que ele quitou da 63-32 voltou a ser pendência.
        assertThat(jdbc.queryForObject("SELECT estornado_em IS NOT NULL FROM corona.recibos_consignado WHERE id = ?", Boolean.class, recibo)).isTrue();
        assertThat(situacaoDaNota(k.nota63())).isEqualTo("PENDENTE");
        assertThat(situacaoDaNota(k.nota64())).isEqualTo("EM_ABERTO");
        assertThat(pendencia(k.cliente())).isEqualByComparingTo("498.10");
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ? AND estornado_em IS NULL", k.nota64())).isZero();
        // Outro estorno do mesmo acerto, com outro id, é recusado.
        Throwable outro = catchThrowable(() -> acertos.estornar(ctx(k.v()), new EstornoDoAcerto(UUID.randomUUID(), acerto, "de novo")));
        assertThat(codigoDoErro(outro)).isEqualTo("ACERTO_JA_ESTORNADO");

        // Refazer o acerto é possível (nota de volta a Em aberto).
        UUID novo = acertar(k.v(), k.nota64(), k.s65(), "30", List.of(volta(k.a(), k.maleta(), 5), volta(k.b(), k.maleta(), 6),
                volta(k.c(), k.maleta(), 4)), 54, paga("DINHEIRO", "1470.00"), paga("PIX", "300.00"));
        assertThat(saldoDoDebito(novo)).isEqualByComparingTo("345.10");
    }

    @Test
    void estornoDoAcertoLiberaOQueOutroReciboAplicouEOValorEntraNoAcertoRefeito() throws Exception {
        Caso k = caso();
        // 64-21 acertada sem pagar: débito de 1.617,00. A 63-32 (498,10) segue pendente.
        UUID acerto = acertar(k.v(), k.nota64(), k.s65(), "30", List.of(volta(k.a(), k.maleta(), 5), volta(k.b(), k.maleta(), 6),
                volta(k.c(), k.maleta(), 4)), null);
        // Lançar pagamento de 600: 498,10 na 63-32 (mais antiga) e 101,90 na 64-21.
        UUID pagamento = UUID.randomUUID();
        recibos.lancar(ctx(k.v()), new NovoPagamento(pagamento, k.cliente(), k.s65(), 60, List.of(paga("PIX", "600.00")), null, null));
        assertThat(situacaoDaNota(k.nota63())).isEqualTo("FECHADO");
        assertThat(saldoDoDebito(acerto)).isEqualByComparingTo("1515.10");

        acertos.estornar(ctx(k.v()), new EstornoDoAcerto(UUID.randomUUID(), acerto, "Errei"));
        // O que o recibo de 600 aplicou na 64-21 (101,90) voltou ao "a aplicar"; não há débito aberto para recebê-lo.
        assertThat(aAplicar(pagamento)).isEqualByComparingTo("101.90");
        assertThat(pendencia(k.cliente())).isEqualByComparingTo("0.00");
        assertThat(situacaoDaNota(k.nota63())).isEqualTo("FECHADO");

        // Refeito o acerto, o valor liberado entra sozinho no débito novo.
        UUID novo = acertar(k.v(), k.nota64(), k.s65(), "30", List.of(volta(k.a(), k.maleta(), 5), volta(k.b(), k.maleta(), 6),
                volta(k.c(), k.maleta(), 4)), null);
        assertThat(aAplicar(pagamento)).isEqualByComparingTo("0.00");
        assertThat(saldoDoDebito(novo)).isEqualByComparingTo("1515.10");
    }

    @Test
    void estornoDeAcertoDeOutroVendedorEAcertoInexistenteSaoRecusados() throws Exception {
        Caso k = caso();
        UUID acerto = acertar(k.v(), k.nota64(), k.s65(), "30", List.of(), null);
        UsuarioDeTeste outro = usuarios.criarVendedor();
        assertThat(codigoDoErro(catchThrowable(() -> acertos.estornar(ctx(outro), new EstornoDoAcerto(UUID.randomUUID(), acerto, "x")))))
                .isEqualTo("ACERTO_NAO_ENCONTRADO");
        assertThat(codigoDoErro(catchThrowable(() -> acertos.estornar(ctx(k.v()), new EstornoDoAcerto(UUID.randomUUID(), acerto, " ")))))
                .isEqualTo("MOTIVO_OBRIGATORIO");
    }

    @Test
    void pagarAcimaDoDevidoNoAcertoEAceitoComAlertaESobraAAplicar() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("100.00", 5);
        UUID maleta = maletaEnviada(v, peca, 5);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente(v), item(peca, maleta, 1, "100.00"));
        UUID acerto = acertar(v, nota, semana, "0", List.of(), 2, paga("DINHEIRO", "130.00"));
        assertThat(saldoDoDebito(acerto)).isEqualByComparingTo("0.00");
        assertThat(aAplicar(reciboDoAcerto(acerto))).isEqualByComparingTo("30.00");
        assertThat(alertas(v, "PAGAMENTO_ACIMA_DO_DEVIDO")).isEqualTo(1);
    }

    @Test
    void numeroDeReciboJaUsadoPelaNotaERecusado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("100.00", 5);
        UUID maleta = maletaEnviada(v, peca, 5);
        UUID semana = abrirSemana(v);
        UUID cliente = cliente(v);
        UUID nota = criarNota(v, semana, 1, cliente, item(peca, maleta, 1, "100.00"));
        UUID nota2 = criarNota(v, semana, 2, cliente, item(peca, maleta, 1, "100.00"));
        // O número 2 já é da nota 2: o recibo não pode usá-lo.
        Throwable erro = catchThrowable(() -> acertar(v, nota, semana, "0", List.of(), 2, paga("PIX", "100.00")));
        assertThat(codigoDoErro(erro)).isEqualTo("NUMERO_REPETIDO");
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", nota)).isZero();
        assertThat(nota2).isNotNull();
    }
}
