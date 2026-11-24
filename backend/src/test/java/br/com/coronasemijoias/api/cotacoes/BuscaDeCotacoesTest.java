package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A busca agendada (ADR-0006, item 3): janela de horário, atraso, feriado, falha, recuperação, nunca
 * parcial e nunca sobrescreve. Cliente HTTP e relógio falsos; o banco é o de verdade.
 */
class BuscaDeCotacoesTest extends TesteDeCotacoes {

    static final LocalDate SEGUNDA = LocalDate.of(2026, 11, 9);
    static final LocalDate TERCA = LocalDate.of(2026, 11, 10);
    static final LocalDate SEXTA_ANTERIOR = LocalDate.of(2026, 11, 6);
    static final LocalDate FINADOS = LocalDate.of(2026, 11, 2);

    /** As 10 tentativas do dia: 16:30, 17:00, ... , 21:00 UTC. */
    static List<String> tentativasDe(String dia) {
        return List.of("16:30", "17:00", "17:30", "18:00", "18:30", "19:00", "19:30", "20:00", "20:30", "21:00")
                .stream().map(hora -> dia + "T" + hora + ":00Z").toList();
    }

    @Test
    void gravaOsDoisMoedasNumaLinhaSoQuandoOArquivoDoDiaExiste() {
        cliente.arquivo(SEGUNDA, arquivoValidoDe(SEGUNDA));

        List<LocalDate> gravados = rodadaAs("2026-11-09T16:30:00Z");

        assertThat(gravados).containsExactly(SEGUNDA);
        Cotacao cotacao = repositorio.vigenteEm(relogio.instant()).orElseThrow();
        assertThat(cotacao.dataCotacao()).isEqualTo(SEGUNDA);
        assertThat(cotacao.taxas().usdVenda()).isEqualTo(new BigDecimal("5.50130000"));
        assertThat(cotacao.taxas().pygVenda()).isEqualTo(new BigDecimal("0.00089500"));
        assertThat(cotacao.taxas().pygParidadeVenda()).isEqualTo(new BigDecimal("6355.00000000"));
        assertThat(cotacao.buscadaEm()).isEqualTo(Instant.parse("2026-11-09T16:30:00Z"));
        assertThat(cotacao.fonte()).isEqualTo("https://www4.bcb.gov.br/Download/fechamento/20261109.csv");
        assertThat(cotacao.id().version()).isEqualTo(7);
        assertThat(linhasNoBanco()).isEqualTo(1);
    }

    @Test
    void cadaPedidoTem20SegundosDeTempoLimite() {
        rodadaAs("2026-11-09T16:30:00Z");
        assertThat(cliente.ultimoTempoLimite()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void foraDoHorarioEDeFimDeSemanaNaoBusca() {
        cliente.arquivo(SEGUNDA, arquivoValidoDe(SEGUNDA));
        cliente.arquivo(LocalDate.of(2026, 11, 7), arquivoValidoDe(LocalDate.of(2026, 11, 7)));

        assertThat(rodadaAs("2026-11-09T16:29:59Z")).isEmpty();
        assertThat(rodadaAs("2026-11-09T21:00:01Z")).isEmpty();
        assertThat(rodadaAs("2026-11-07T17:00:00Z")).as("sábado").isEmpty();
        assertThat(rodadaAs("2026-11-08T17:00:00Z")).as("domingo").isEmpty();

        assertThat(cliente.pedidos()).isEmpty();
        assertThat(linhasNoBanco()).isZero();
    }

    @Test
    void aPrimeiraEAUltimaTentativaDoDiaValem() {
        assertThat(rodadaAs("2026-11-09T16:30:00Z")).isEmpty();
        assertThat(rodadaAs("2026-11-09T21:00:00Z")).isEmpty();
        assertThat(cliente.vezesPedido(SEGUNDA)).isEqualTo(2);
    }

    @Test
    void arquivoAtrasadoNaoDaAvisoEEntraQuandoOBcbPublica() {
        gravarUteisAte(SEXTA_ANTERIOR);
        // 404 em todas as tentativas antes da publicação, sem aviso nenhum.
        for (String instante : List.of("2026-11-09T16:30:00Z", "2026-11-09T17:00:00Z", "2026-11-09T17:30:00Z",
                "2026-11-09T18:00:00Z", "2026-11-09T18:30:00Z")) {
            assertThat(rodadaAs(instante)).isEmpty();
            assertThat(aviso.calcular().aparece()).as("aviso às " + instante).isFalse();
        }
        // O BCB publica; a próxima tentativa grava.
        cliente.arquivo(SEGUNDA, arquivoValidoDe(SEGUNDA));
        assertThat(rodadaAs("2026-11-09T19:00:00Z")).containsExactly(SEGUNDA);
        assertThat(avisoAs("2026-11-09T21:30:00Z").aparece()).isFalse();
        assertThat(repositorio.vigenteEm(relogio.instant()).orElseThrow().dataCotacao()).isEqualTo(SEGUNDA);
    }

    @Test
    void diaGravadoNuncaERelido() {
        cliente.arquivo(SEGUNDA, arquivoValidoDe(SEGUNDA));
        rodadaAs("2026-11-09T16:30:00Z");
        cliente.esquecerPedidos();

        for (String instante : tentativasDe("2026-11-09")) {
            rodadaAs(instante);
        }

        assertThat(cliente.vezesPedido(SEGUNDA)).isZero();
        assertThat(linhasNoBanco()).isEqualTo(1);
    }

    @Test
    void feriadoSemArquivoNaoEFalhaENaoDaAviso() {
        // Finados, segunda 02/11/2026: o BCB responde 404 o dia todo, inclusive na última tentativa.
        gravarUteisAte(LocalDate.of(2026, 10, 30));
        for (String instante : tentativasDe("2026-11-02")) {
            assertThat(rodadaAs(instante)).isEmpty();
        }
        assertThat(cliente.vezesPedido(FINADOS)).isEqualTo(10);

        AvisoDeCotacao.Aviso depoisDaUltima = avisoAs("2026-11-02T21:00:01Z");
        assertThat(depoisDaUltima.aparece()).isFalse();
        assertThat(depoisDaUltima.cotacaoEmUso()).contains(LocalDate.of(2026, 10, 30));
        // Nos dias seguintes também não: a recuperação não corre atrás de feriado.
        gravarCotacaoDe(LocalDate.of(2026, 11, 3));
        gravarCotacaoDe(LocalDate.of(2026, 11, 4));
        cliente.esquecerPedidos();
        rodadaAs("2026-11-04T16:30:00Z");
        assertThat(cliente.vezesPedido(FINADOS)).isZero();
        assertThat(avisoAs("2026-11-04T16:31:00Z").aparece()).isFalse();
    }

    @Test
    void arquivoQueExisteNumDiaMarcadoComoFeriadoEGravado() {
        assertThat(calendario.ehFeriado(FINADOS)).isTrue();
        cliente.arquivo(FINADOS, arquivoValidoDe(FINADOS));

        assertThat(rodadaAs("2026-11-02T17:00:00Z")).containsExactly(FINADOS);

        assertThat(repositorio.vigenteEm(relogio.instant()).orElseThrow().dataCotacao()).isEqualTo(FINADOS);
    }

    @Test
    void falhaDe404NumDiaUtilDaAvisoDepoisDaUltimaTentativa() {
        gravarUteisAte(SEXTA_ANTERIOR);
        for (String instante : tentativasDe("2026-11-09")) {
            assertThat(rodadaAs(instante)).isEmpty();
        }
        assertThat(cliente.vezesPedido(SEGUNDA)).isEqualTo(10);

        assertThat(avisoAs("2026-11-09T21:00:00Z").aparece()).as("na própria última tentativa ainda não").isFalse();
        AvisoDeCotacao.Aviso depois = avisoAs("2026-11-09T21:00:01Z");
        assertThat(depois.aparece()).isTrue();
        assertThat(depois.diasSemCotacao()).containsExactly(SEGUNDA);
        assertThat(depois.cotacaoEmUso()).contains(SEXTA_ANTERIOR);
    }

    @Test
    void falhaDeRedeAteOFimDaAviso() {
        gravarUteisAte(SEXTA_ANTERIOR);
        int antes = linhasNoBanco();
        cliente.redeCaida(SEGUNDA);
        for (String instante : tentativasDe("2026-11-09")) {
            assertThat(rodadaAs(instante)).isEmpty();
        }
        assertThat(linhasNoBanco()).isEqualTo(antes);
        assertThat(avisoAs("2026-11-09T22:00:00Z").diasSemCotacao()).containsExactly(SEGUNDA);
    }

    @Test
    void respostaHttpDiferenteDe200EDe404TambemEFalha() {
        gravarUteisAte(SEXTA_ANTERIOR);
        cliente.status(SEGUNDA, 503);
        for (String instante : tentativasDe("2026-11-09")) {
            assertThat(rodadaAs(instante)).isEmpty();
        }
        assertThat(avisoAs("2026-11-09T22:00:00Z").diasSemCotacao()).containsExactly(SEGUNDA);
    }

    @Test
    void arquivoForaDaRegraAteOFimDaAvisoENaoGravaNada() {
        gravarUteisAte(SEXTA_ANTERIOR);
        cliente.arquivo(SEGUNDA, LeitorDoArquivoDeFechamentoTest.arquivo("coluna-a-mais.csv")
                .replace("06/11/2026", "09/11/2026"));
        for (String instante : tentativasDe("2026-11-09")) {
            assertThat(rodadaAs(instante)).isEmpty();
        }
        assertThat(avisoAs("2026-11-09T21:30:00Z").diasSemCotacao()).containsExactly(SEGUNDA);
    }

    @Test
    void arquivoDeOutroDiaNoEnderecoDeHojeEFalha() {
        // O endereço de 09/11 devolve o arquivo de 06/11: a data dentro das linhas manda.
        cliente.arquivo(SEGUNDA, LeitorDoArquivoDeFechamentoTest.arquivo(ARQUIVO_REAL));
        assertThat(rodadaAs("2026-11-09T17:00:00Z")).isEmpty();
        assertThat(linhasNoBanco()).isZero();
    }

    @Test
    void recuperacaoGravaODiaPerdidoComoLinhaNovaNoDiaSeguinte() {
        gravarUteisAte(SEXTA_ANTERIOR);
        // Segunda inteira com falha passageira (rede), e aviso às 21:30.
        cliente.redeCaida(SEGUNDA);
        for (String instante : tentativasDe("2026-11-09")) {
            rodadaAs(instante);
        }
        assertThat(avisoAs("2026-11-09T21:30:00Z").aparece()).isTrue();

        // Terça: o arquivo de segunda voltou e o de terça ainda não saiu.
        cliente.arquivo(SEGUNDA, arquivoValidoDe(SEGUNDA));
        List<LocalDate> gravados = rodadaAs("2026-11-10T16:30:00Z");

        assertThat(gravados).containsExactly(SEGUNDA);
        assertThat(avisoAs("2026-11-10T16:31:00Z").aparece()).as("o aviso some sozinho").isFalse();
        // Recuperada depois: o lançamento de segunda passa a usar a cotação de segunda.
        assertThat(vigente.vigenteEm(java.time.Instant.parse("2026-11-09T12:00:00Z")).orElseThrow().dataCotacao()).isEqualTo(SEGUNDA);
    }

    @Test
    void recuperacaoNaMesmaRodadaGravaHojeEOsDiasAtrasados() {
        gravarUteisAte(SEXTA_ANTERIOR);
        cliente.arquivo(SEGUNDA, arquivoValidoDe(SEGUNDA));
        cliente.arquivo(TERCA, arquivoValidoDe(TERCA));

        List<LocalDate> gravados = rodadaAs("2026-11-10T16:30:00Z");

        assertThat(gravados).containsExactlyInAnyOrder(SEGUNDA, TERCA);
    }

    @Test
    void recuperacaoOlhaSoOsUltimosDezDiasUteis() {
        // Quarta 25/11/2026: janela de 15/11 a 24/11. Úteis: 16, 17, 18, 19, 23 e 24 (20/11 é feriado).
        rodadaAs("2026-11-25T16:30:00Z");

        assertThat(cliente.pedidos()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 18),
                LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24));
    }

    @Test
    void nuncaGravaCotacaoParcial() {
        // Sem o Guarani, sem o Dólar, ou com o Dólar fora da regra: nada entra.
        String semGuarani = LeitorDoArquivoDeFechamentoTest.arquivo("pyg-ausente.csv").replace("06/11/2026", "09/11/2026");
        cliente.arquivo(SEGUNDA, semGuarani);
        assertThat(rodadaAs("2026-11-09T17:00:00Z")).isEmpty();

        String semDolar = arquivoValidoDe(SEGUNDA).replaceAll("(?m)^.*;220;A;USD;.*\r?\n", "");
        cliente.arquivo(SEGUNDA, semDolar);
        assertThat(rodadaAs("2026-11-09T17:30:00Z")).isEmpty();

        String dolarComZero = LeitorDoArquivoDeFechamentoTest.arquivo("zero.csv").replace("06/11/2026", "09/11/2026");
        cliente.arquivo(SEGUNDA, dolarComZero);
        assertThat(rodadaAs("2026-11-09T18:00:00Z")).isEmpty();

        assertThat(linhasNoBanco()).isZero();
    }

    @Test
    void nuncaSobrescreveODiaJaGravado() {
        gravarCotacaoDe(SEGUNDA);
        BigDecimal antes = repositorio.vigenteEm(relogio.instant()).orElseThrow().taxas().usdVenda();
        // O BCB "republica" o dia com outro número: o sistema mantém o que leu primeiro.
        cliente.arquivo(SEGUNDA, arquivoValidoDe(SEGUNDA).replace("5,50130000", "9,99990000"));

        assertThat(rodadaAs("2026-11-09T17:00:00Z")).isEmpty();
        assertThat(cliente.vezesPedido(SEGUNDA)).isZero();
        TaxasDoDia outras = LeitorDoArquivoDeFechamento.ler(
                SEGUNDA, arquivoValidoDe(SEGUNDA).replace("5,50130000", "9,99990000"));
        assertThat(outras.usdVenda()).isEqualTo(new BigDecimal("9.99990000"));
        assertThat(repositorio.gravar(SEGUNDA, outras, Instant.parse("2026-11-09T18:00:00Z"), "outra")).isFalse();

        assertThat(repositorio.vigenteEm(relogio.instant()).orElseThrow().taxas().usdVenda()).isEqualTo(antes);
        assertThat(linhasNoBanco()).isEqualTo(1);
    }

    @Test
    void umaFalhaNaoImpedeOsOutrosDias() {
        cliente.redeCaida(SEGUNDA);
        cliente.arquivo(TERCA, arquivoValidoDe(TERCA));

        assertThat(rodadaAs("2026-11-10T17:00:00Z")).containsExactly(TERCA);
    }
}
