package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.ReciboDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.NovaNota;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Nota, acerto da nota e recibo gravam a cotação VIGENTE NO MOMENTO deles (ADR-0006, item 15; plano da cotação da dona, seção
 * 2.1). No dia 14/04/2020 o Banco Central vale desde 00:00 e a dona lançou a dela às 10:00 (13:00 UTC): o que é das 09:00 fica com
 * o Banco Central; o das 11:00, com a da dona; e nada do que já estava gravado muda quando ela lança outra à tarde.
 */
class CotacaoDoMomentoNoConsignadoTest extends ApoioDoConsignado {

    private static final String DIA = "2020-04-14";
    private static final Instant ABRE_A_SEMANA = Instant.parse("2020-03-01T13:00:00Z");
    private static final Instant NOVE_HORAS = Instant.parse("2020-04-14T12:00:00Z");
    private static final Instant DEZ_HORAS = Instant.parse("2020-04-14T13:00:00Z");
    private static final Instant ONZE_HORAS = Instant.parse("2020-04-14T14:00:00Z");
    private static final Instant TARDE = Instant.parse("2020-04-14T20:00:00Z");

    /** As cotações deste teste são gravadas de verdade (a tabela é imutável): saem no fim, para não vazar para outros testes. */
    @AfterAll
    static void tirarAsCotacoes() throws java.sql.SQLException {
        try (java.sql.Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirarDias(con, List.of(DIA));
        }
    }

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

    private UUID notaEm(Cena c, int sequencia, Instant quando) {
        NovaNota n = nota(c.semana(), sequencia, c.cliente(), item(c.peca(), c.maleta(), 1, "100.00"));
        notas.criar(ctxEm(c.v(), quando), n);
        return n.id();
    }

    private UUID acertarEm(Cena c, UUID nota, Instant quando, int sequenciaDoRecibo) {
        UUID id = UUID.randomUUID();
        ReciboDoAcerto recibo = new ReciboDoAcerto(UUID.randomUUID(), sequenciaDoRecibo, List.of(paga("DINHEIRO", "50.00")));
        acertos.acertar(ctxEm(c.v(), quando), new NovoAcerto(id, nota, c.semana(), BigDecimal.ZERO, List.of(), recibo, null, null));
        return id;
    }

    private UUID cotacaoDe(String tabela, UUID id) {
        return jdbc.queryForObject("SELECT cotacao_id FROM corona." + tabela + " WHERE id = ?", UUID.class, id);
    }

    @Test
    void notaAcertoDaNotaEReciboGravamAVigenteNoMomentoENadaMudaDepois() throws Exception {
        UUID bcb = CotacoesDaMassa.gravar(jdbc, DIA, "5.10000000", "0.00091000");
        UUID dona = CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), DEZ_HORAS, "5.80", "1350");
        assertThat(bcb).isNotEqualTo(dona);
        Cena c = cena();

        // Notas: a das 09:00 fica com o Banco Central; a das 11:00, com a da dona.
        UUID notaCedo = notaEm(c, 1, NOVE_HORAS);
        UUID notaTarde = notaEm(c, 2, ONZE_HORAS);
        assertThat(cotacaoDe("notas_consignado", notaCedo)).as("nota das 09:00").isEqualTo(bcb);
        assertThat(cotacaoDe("notas_consignado", notaTarde)).as("nota das 11:00").isEqualTo(dona);

        // Acerto da nota e o recibo dele, nos mesmos dois horários.
        UUID acertoCedo = acertarEm(c, notaCedo, NOVE_HORAS, 1);
        UUID acertoTarde = acertarEm(c, notaTarde, ONZE_HORAS, 2);
        assertThat(cotacaoDe("acertos_nota", acertoCedo)).as("acerto das 09:00").isEqualTo(bcb);
        assertThat(cotacaoDe("acertos_nota", acertoTarde)).as("acerto das 11:00").isEqualTo(dona);
        assertThat(cotacaoDe("recibos_consignado", reciboDoAcerto(acertoCedo))).as("recibo das 09:00").isEqualTo(bcb);
        assertThat(cotacaoDe("recibos_consignado", reciboDoAcerto(acertoTarde))).as("recibo das 11:00").isEqualTo(dona);

        // A dona lança outra cotação à tarde: nada do que já estava gravado muda.
        UUID maisNova = CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), TARDE, "6.10", "1400");
        assertThat(cotacaoDe("notas_consignado", notaCedo)).isEqualTo(bcb);
        assertThat(cotacaoDe("notas_consignado", notaTarde)).isEqualTo(dona);
        assertThat(cotacaoDe("acertos_nota", acertoTarde)).isEqualTo(dona);
        assertThat(cotacaoDe("recibos_consignado", reciboDoAcerto(acertoTarde))).isEqualTo(dona);

        // E o lançamento seguinte, depois dela, já usa a nova.
        UUID notaDepois = notaEm(c, 3, TARDE.plusSeconds(60));
        assertThat(cotacaoDe("notas_consignado", notaDepois)).isEqualTo(maisNova);
    }
}
