package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.ReciboDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.NovaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.NovoPagamento;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Data do acerto editável (tarefa 9.8; ADR-0016, "Ajustes da tarefa 9.8"; ESPECIFICACAO §23.8), com PostgreSQL de verdade.
 * Os dias do teste são de 2020 (muito antes de hoje) para a cotação de cada dia ser a do teste e a ordem dos débitos não
 * depender do dia em que o teste roda. Escritos antes do código.
 */
class DataDoAcertoTest extends ApoioDoConsignado {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final Instant ABRE_A_SEMANA = Instant.parse("2020-03-01T13:00:00Z");
    private static final Instant SAIDA = Instant.parse("2020-03-02T13:00:00Z");
    private static final Instant DIA_12_10H = Instant.parse("2020-03-12T13:00:00Z");
    private static final Instant DIA_12_15H = Instant.parse("2020-03-12T18:00:00Z");

    private record Cena(UsuarioDeTeste v, UUID cliente, UUID semana, UUID peca, UUID maleta) {
    }

    private Cena cena() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("100.00", 40);
        UUID maleta = maletaEnviada(v, peca, 40);
        UUID semana = abrirSemana(v, 1, ABRE_A_SEMANA);
        return new Cena(v, cliente, semana, peca, maleta);
    }

    /** Nota de qtd peças de 100,00, com a saída em 2/3/2020. */
    private UUID notaDe(Cena c, int sequencia, int qtd) {
        NovaNota n = nota(c.semana(), sequencia, c.cliente(), item(c.peca(), c.maleta(), qtd, "100.00"));
        notas.criar(ctxEm(c.v(), SAIDA), n);
        return n.id();
    }

    private UUID acertarEm(Cena c, UUID nota, Instant quando, LocalDate data, Integer sequenciaDoRecibo, String pago) {
        UUID id = UUID.randomUUID();
        ReciboDoAcerto recibo = pago == null ? null : new ReciboDoAcerto(UUID.randomUUID(), sequenciaDoRecibo, List.of(paga("DINHEIRO", pago)));
        acertos.acertar(ctxEm(c.v(), quando), new NovoAcerto(id, nota, c.semana(), BigDecimal.ZERO, List.of(), recibo, null, data));
        return id;
    }

    private Instant instante(String sql, UUID id) {
        return jdbc.queryForObject(sql, Timestamp.class, id).toInstant();
    }

    @Test
    void acertoComDataDePassadoGravaMeioDiaDeBrasiliaECotacaoDesseDia() throws Exception {
        Cena c = cena();
        UUID cotacaoDe10 = CotacoesDaMassa.gravar(jdbc, "2020-03-10", "5.10000000", "0.00091000");
        UUID cotacaoDe12 = CotacoesDaMassa.gravar(jdbc, "2020-03-12", "5.20000000", "0.00092000");
        assertThat(cotacaoDe10).isNotEqualTo(cotacaoDe12);
        UUID nota = notaDe(c, 1, 2);
        UUID acerto = acertarEm(c, nota, DIA_12_10H, LocalDate.of(2020, 3, 10), 1, "50.00");

        Instant esperado = Instant.parse("2020-03-10T15:00:00Z"); // meio-dia em Brasília
        assertThat(instante("SELECT acertado_em FROM corona.acertos_nota WHERE id = ?", acerto)).isEqualTo(esperado);
        assertThat(instante("SELECT registrado_em FROM corona.acertos_nota WHERE id = ?", acerto)).isEqualTo(DIA_12_10H);
        UUID recibo = reciboDoAcerto(acerto);
        assertThat(instante("SELECT pago_em FROM corona.recibos_consignado WHERE id = ?", recibo)).isEqualTo(esperado);
        assertThat(jdbc.queryForObject("SELECT cotacao_id FROM corona.acertos_nota WHERE id = ?", UUID.class, acerto)).isEqualTo(cotacaoDe10);
        assertThat(jdbc.queryForObject("SELECT cotacao_id FROM corona.recibos_consignado WHERE id = ?", UUID.class, recibo))
                .isEqualTo(cotacaoDe10);
    }

    @Test
    void acertoSemDataOuComODiaDoLancamentoUsaOMomentoDoLancamento() throws Exception {
        Cena c = cena();
        UUID cotacaoDe12 = CotacoesDaMassa.gravar(jdbc, "2020-03-12", "5.20000000", "0.00092000");
        UUID n1 = notaDe(c, 1, 1);
        UUID n2 = notaDe(c, 2, 1);
        UUID semData = acertarEm(c, n1, DIA_12_10H, null, null, null);
        UUID hoje = acertarEm(c, n2, DIA_12_15H, LocalDate.of(2020, 3, 12), null, null);
        for (UUID a : List.of(semData, hoje)) {
            assertThat(instante("SELECT acertado_em FROM corona.acertos_nota WHERE id = ?", a))
                    .isEqualTo(instante("SELECT registrado_em FROM corona.acertos_nota WHERE id = ?", a));
            assertThat(jdbc.queryForObject("SELECT cotacao_id FROM corona.acertos_nota WHERE id = ?", UUID.class, a)).isEqualTo(cotacaoDe12);
        }
        assertThat(instante("SELECT acertado_em FROM corona.acertos_nota WHERE id = ?", hoje)).isEqualTo(DIA_12_15H);
    }

    @Test
    void diaSemCotacaoGravaOAcertoSemCotacao() throws Exception {
        Cena c = cena();
        NovaNota antiga = nota(c.semana(), 1, c.cliente(), item(c.peca(), c.maleta(), 1, "100.00"));
        notas.criar(ctxEm(c.v(), Instant.parse("2000-01-05T13:00:00Z")), antiga);
        UUID acerto = acertarEm(c, antiga.id(), DIA_12_10H, LocalDate.of(2000, 1, 6), null, null);
        assertThat(jdbc.queryForObject("SELECT cotacao_id FROM corona.acertos_nota WHERE id = ?", UUID.class, acerto)).isNull();
    }

    @Test
    void dataNoFuturoERecusadaSemGravarNada() throws Exception {
        Cena c = cena();
        UUID nota = notaDe(c, 1, 2);
        LocalDate amanha = LocalDate.now(BRASILIA).plusDays(1);
        // Relógio do computador em dia: o dia seguinte ao de hoje (relógio do servidor) é recusado.
        Throwable futuro = catchThrowable(() -> acertarEm(c, nota, relogio.instant(), amanha, 1, "10.00"));
        assertThat(codigoDoErro(futuro)).isEqualTo("DATA_DO_ACERTO_NO_FUTURO");
        // Depois do dia que o computador declarou (relógio dele atrasado): também recusado.
        Throwable depoisDoDeclarado = catchThrowable(() -> acertarEm(c, nota, DIA_12_10H, LocalDate.of(2020, 3, 13), 1, "10.00"));
        assertThat(codigoDoErro(depoisDoDeclarado)).isEqualTo("DATA_DO_ACERTO_NO_FUTURO");
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", nota)).isEqualTo(0);
        assertThat(contar("SELECT count(*) FROM corona.recibos_consignado WHERE vendedor_id = ?", c.v().vendedorId())).isEqualTo(0);
        assertThat(contar("SELECT count(*) FROM corona.numeros_da_semana WHERE vendedor_id = ? AND tipo = 'RECIBO'", c.v().vendedorId()))
                .isEqualTo(0);
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE nota_id = ? AND acerto_nota_id IS NOT NULL", nota)).isEqualTo(0);
    }

    @Test
    void dataAnteriorAoDiaDaSaidaDaNotaERecusadaMasOMesmoDiaPassa() throws Exception {
        Cena c = cena();
        UUID nota = notaDe(c, 1, 2);
        Throwable antes = catchThrowable(() -> acertarEm(c, nota, DIA_12_10H, LocalDate.of(2020, 3, 1), null, null));
        assertThat(codigoDoErro(antes)).isEqualTo("DATA_DO_ACERTO_ANTES_DA_SAIDA");
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", nota)).isEqualTo(0);
        UUID acerto = acertarEm(c, nota, DIA_12_10H, LocalDate.of(2020, 3, 2), null, null);
        assertThat(instante("SELECT acertado_em FROM corona.acertos_nota WHERE id = ?", acerto)).isEqualTo(Instant.parse("2020-03-02T15:00:00Z"));
    }

    @Test
    void ordemDosDebitosComDataPassadaContinuaPeloLancamento() throws Exception {
        Cena c = cena();
        UUID notaA = notaDe(c, 10, 1); // bruto 100
        UUID notaB = notaDe(c, 12, 2); // bruto 200
        // A lançado às 10h do dia 12, sem pagar: o cliente deve 100.
        UUID a = acertarEm(c, notaA, DIA_12_10H, null, null, null);
        assertThat(saldoDoDebito(a)).isEqualByComparingTo("100.00");
        // B lançado às 15h do dia 12 com data do dia 11 (ANTES de A em acertado_em) e pagando 150: o pago quita A primeiro.
        UUID b = acertarEm(c, notaB, DIA_12_15H, LocalDate.of(2020, 3, 11), 1, "150.00");

        assertThat(instante("SELECT acertado_em FROM corona.acertos_nota WHERE id = ?", b)).isBefore(
                instante("SELECT acertado_em FROM corona.acertos_nota WHERE id = ?", a));
        assertThat(saldoDoDebito(a)).isEqualByComparingTo("0.00");
        assertThat(saldoDoDebito(b)).isEqualByComparingTo("150.00");
        assertThat(situacaoDaNota(notaA)).isEqualTo("FECHADO");
        assertThat(situacaoDaNota(notaB)).isEqualTo("PENDENTE");
        UUID recibo = reciboDoAcerto(b);
        assertThat(jdbc.queryForObject("SELECT valor FROM corona.aplicacoes_recibo WHERE recibo_id = ? AND acerto_id = ?", BigDecimal.class,
                recibo, a)).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT valor FROM corona.aplicacoes_recibo WHERE recibo_id = ? AND acerto_id = ?", BigDecimal.class,
                recibo, b)).isEqualByComparingTo("50.00");

        // Um Lançar pagamento de 150 quita B (o único que sobrou).
        recibos.lancar(ctxEm(c.v(), Instant.parse("2020-03-13T13:00:00Z")),
                new NovoPagamento(UUID.randomUUID(), c.cliente(), c.semana(), 2, List.of(paga("PIX", "150.00")), null, null));
        assertThat(situacaoDaNota(notaB)).isEqualTo("FECHADO");
        assertThat(pendencia(c.cliente())).isEqualByComparingTo("0.00");
    }

    @Test
    void mesmaHoraDeLancamentoDesempataPorSemanaSequenciaEId() throws Exception {
        Cena c = cena();
        UUID notaB = notaDe(c, 2, 1);
        UUID notaA = notaDe(c, 1, 1);
        UUID b = acertarEm(c, notaB, DIA_12_10H, null, null, null);
        UUID a = acertarEm(c, notaA, DIA_12_10H, null, null, null);
        recibos.lancar(ctxEm(c.v(), DIA_12_15H),
                new NovoPagamento(UUID.randomUUID(), c.cliente(), c.semana(), 1, List.of(paga("PIX", "60.00")), null, null));
        // Mesma hora: vale a sequência da nota (1 antes de 2), não a ordem em que chegaram.
        assertThat(saldoDoDebito(a)).isEqualByComparingTo("40.00");
        assertThat(saldoDoDebito(b)).isEqualByComparingTo("100.00");
    }

    @Test
    void acertoComDataReenviadoEInofensivoESoGravaUmaVez() throws Exception {
        Cena c = cena();
        UUID nota = notaDe(c, 1, 2);
        UUID id = UUID.randomUUID();
        NovoAcerto pedido = new NovoAcerto(id, nota, c.semana(), BigDecimal.ZERO, List.of(), null, null, LocalDate.of(2020, 3, 10));
        assertThat(acertos.acertar(ctxEm(c.v(), DIA_12_10H), pedido)).isFalse();
        assertThat(acertos.acertar(ctxEm(c.v(), DIA_12_10H), pedido)).isTrue();
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", nota)).isEqualTo(1);
    }

    @Test
    void registradoEmNaoMudaDepoisDeGravado() throws Exception {
        Cena c = cena();
        UUID nota = notaDe(c, 1, 1);
        UUID acerto = acertarEm(c, nota, DIA_12_10H, null, null, null);
        Throwable erro = catchThrowable(() -> jdbc.update("UPDATE corona.acertos_nota SET registrado_em = now() WHERE id = ?", acerto));
        assertThat(erro).isNotNull();
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE registrado_em IS NULL")).isEqualTo(0);
    }

    @Test
    void estornarERefazerComDataPassadaDeixaORefeitoComoOMaisNovo() throws Exception {
        Cena c = cena();
        UUID notaA = notaDe(c, 1, 1);
        UUID notaB = notaDe(c, 2, 2);
        UUID a = acertarEm(c, notaA, DIA_12_10H, null, null, null); // deve 100
        UUID b = acertarEm(c, notaB, DIA_12_15H, null, 1, "150.00"); // quita A e 50 de B
        acertos.estornar(ctxEm(c.v(), Instant.parse("2020-03-13T13:00:00Z")),
                new ServicoDeAcertosDaNota.EstornoDoAcerto(UUID.randomUUID(), b, "Errei o valor"));
        assertThat(saldoDoDebito(a)).isEqualByComparingTo("100.00");
        // Refeito com a data do dia 11: continua depois de A na ordem (registrado_em é o do lançamento).
        UUID refeito = acertarEm(c, notaB, Instant.parse("2020-03-13T14:00:00Z"), LocalDate.of(2020, 3, 11), 2, "100.00");
        assertThat(saldoDoDebito(a)).isEqualByComparingTo("0.00");
        assertThat(saldoDoDebito(refeito)).isEqualByComparingTo("200.00");
    }
}
