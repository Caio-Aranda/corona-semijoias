package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.acertos.EsperadoEContado.Contagem;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.Item;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.PecasParaALoja;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.Resultado;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.SaldoDeMaleta;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Esperado x contado do fechamento (tarefa 7.5; ADR-0012, itens 3.6 e 6), sem banco: sobra impede, a falta cai na maleta mais
 * antiga primeiro, a linha "para a loja" é preenchida antes, o defeito aprovado é por quantidade.
 */
class EsperadoEContadoTest {

    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID VELHA = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID NOVA = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant ANTES = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant DEPOIS = Instant.parse("2026-11-08T12:00:00Z");

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static SaldoDeMaleta saldo(UUID maleta, Instant enviada, UUID produto, int saldo, String preco) {
        return new SaldoDeMaleta(maleta, enviada, produto, saldo, d(preco));
    }

    private static Item linha(Resultado r, UUID produto, UUID maleta) {
        return r.itens().stream().filter(i -> i.produtoId().equals(produto) && java.util.Objects.equals(i.remessaId(), maleta))
                .findFirst().orElseThrow();
    }

    @Test
    void tudoContadoNaoTemFaltaNemSobra() {
        Resultado r = EsperadoEContado.calcular(List.of(saldo(VELHA, ANTES, A, 5, "50.00")), List.of(),
                List.of(new Contagem(A, 5, 0)), Map.of());
        assertThat(r.sobras()).isEmpty();
        Item i = linha(r, A, VELHA);
        assertThat(i.esperado()).isEqualTo(5);
        assertThat(i.contadoBoa()).isEqualTo(5);
        assertThat(i.falta()).isZero();
        assertThat(i.valorDaFalta()).isEqualByComparingTo("0.00");
    }

    @Test
    void faltaVaiParaAMaletaMaisAntigaPrimeiroEValePeloPrecoDaMaletaDelaMesmoComPrecosDiferentes() {
        // A maleta velha (50,00) e a nova (55,00) têm 4 de A cada; contaram 5 de 8: faltam 3, todas as da velha primeiro.
        Resultado r = EsperadoEContado.calcular(
                List.of(saldo(NOVA, DEPOIS, A, 4, "55.00"), saldo(VELHA, ANTES, A, 4, "50.00")), List.of(),
                List.of(new Contagem(A, 5, 0)), Map.of());
        Item velha = linha(r, A, VELHA);
        Item nova = linha(r, A, NOVA);
        // O contado enche a mais nova primeiro (4) e o que sobra vai para a mais velha (1).
        assertThat(nova.contadoBoa()).isEqualTo(4);
        assertThat(nova.falta()).isZero();
        assertThat(velha.contadoBoa()).isEqualTo(1);
        assertThat(velha.falta()).isEqualTo(3);
        assertThat(velha.valorDaFalta()).isEqualByComparingTo("150.00");
        assertThat(nova.valorDaFalta()).isEqualByComparingTo("0.00");
    }

    @Test
    void sobraImpedeEListaAPecaComEsperadoEContado() {
        Resultado r = EsperadoEContado.calcular(List.of(saldo(VELHA, ANTES, A, 2, "50.00")), List.of(),
                List.of(new Contagem(A, 3, 0), new Contagem(B, 1, 0)), Map.of());
        assertThat(r.sobras()).hasSize(2);
        assertThat(r.sobras()).anySatisfy(s -> {
            assertThat(s.produtoId()).isEqualTo(A);
            assertThat(s.esperado()).isEqualTo(2);
            assertThat(s.contado()).isEqualTo(3);
        });
        // Peça que nem era esperada (B) é sobra inteira.
        assertThat(r.sobras()).anySatisfy(s -> {
            assertThat(s.produtoId()).isEqualTo(B);
            assertThat(s.esperado()).isZero();
        });
    }

    @Test
    void pecasParaALojaSaoEsperadasEPreenchidasAntesDasMaletas() {
        // O exemplo do ADR-0012: A tem 8 na maleta e 1 "para a loja" (esperado 9); contaram 8: falta 1 na maleta, não na loja.
        Resultado r = EsperadoEContado.calcular(List.of(saldo(VELHA, ANTES, A, 8, "50.00")),
                List.of(new PecasParaALoja(A, 1, d("20.00"))), List.of(new Contagem(A, 8, 0)), Map.of());
        Item loja = linha(r, A, null);
        Item maleta = linha(r, A, VELHA);
        assertThat(loja.esperado()).isEqualTo(1);
        assertThat(loja.contadoBoa()).isEqualTo(1);
        assertThat(loja.falta()).isZero();
        assertThat(maleta.esperado()).isEqualTo(8);
        assertThat(maleta.contadoBoa()).isEqualTo(7);
        assertThat(maleta.falta()).isEqualTo(1);
        assertThat(maleta.valorDaFalta()).isEqualByComparingTo("50.00");
        assertThat(loja.precoRemessa()).isNull();
    }

    @Test
    void faltaNaLinhaParaALojaValePeloValorPagoPelaPecaDevolvida() {
        Resultado r = EsperadoEContado.calcular(List.of(), List.of(new PecasParaALoja(A, 3, d("90.00"))),
                List.of(new Contagem(A, 1, 0)), Map.of());
        Item loja = linha(r, A, null);
        assertThat(loja.falta()).isEqualTo(2);
        assertThat(loja.valorDaFalta()).isEqualByComparingTo("60.00");
    }

    @Test
    void defeitoAprovadoEmParteEOrestoVoltaComoBoa() {
        Resultado r = EsperadoEContado.calcular(List.of(saldo(VELHA, ANTES, A, 5, "50.00")), List.of(),
                List.of(new Contagem(A, 2, 3)), Map.of(A, 2));
        Item i = linha(r, A, VELHA);
        assertThat(i.contadoBoa()).isEqualTo(2);
        assertThat(i.contadoDefeito()).isEqualTo(3);
        assertThat(i.defeitoAprovado()).isEqualTo(2);
        assertThat(i.falta()).isZero();
    }

    @Test
    void variasMaletasDividemOContadoEOsDefeitosEAprovadosSeguemAOrdemDasLinhas() {
        // Duas maletas com 3 de A cada: contaram 4 (1 boa e 3 com defeito, 2 aprovados). Enche primeiro a mais nova.
        Resultado r = EsperadoEContado.calcular(
                List.of(saldo(VELHA, ANTES, A, 3, "50.00"), saldo(NOVA, DEPOIS, A, 3, "50.00")), List.of(),
                List.of(new Contagem(A, 1, 3)), Map.of(A, 2));
        Item nova = linha(r, A, NOVA);
        Item velha = linha(r, A, VELHA);
        assertThat(nova.contadoBoa() + nova.contadoDefeito()).isEqualTo(3);
        assertThat(velha.contadoBoa() + velha.contadoDefeito()).isEqualTo(1);
        assertThat(nova.falta()).isZero();
        assertThat(velha.falta()).isEqualTo(2);
        assertThat(nova.contadoBoa() + velha.contadoBoa()).isEqualTo(1);
        assertThat(nova.contadoDefeito() + velha.contadoDefeito()).isEqualTo(3);
        assertThat(nova.defeitoAprovado() + velha.defeitoAprovado()).isEqualTo(2);
        assertThat(nova.defeitoAprovado()).isLessThanOrEqualTo(nova.contadoDefeito());
        assertThat(velha.defeitoAprovado()).isLessThanOrEqualTo(velha.contadoDefeito());
    }

    @Test
    void pecaNaoContadaViraFaltaInteiraELinhaSemSaldoNaoGeraItem() {
        Resultado r = EsperadoEContado.calcular(
                List.of(saldo(VELHA, ANTES, A, 4, "50.00"), saldo(VELHA, ANTES, B, 0, "30.00")), List.of(), List.of(), Map.of());
        assertThat(r.itens()).hasSize(1);
        Item i = linha(r, A, VELHA);
        assertThat(i.contadoBoa()).isZero();
        assertThat(i.falta()).isEqualTo(4);
        assertThat(i.valorDaFalta()).isEqualByComparingTo("200.00");
    }

    @Test
    void pecasDiferentesNaoSeMisturam() {
        Resultado r = EsperadoEContado.calcular(
                List.of(saldo(VELHA, ANTES, A, 2, "50.00"), saldo(VELHA, ANTES, B, 3, "30.00")), List.of(),
                List.of(new Contagem(A, 2, 0), new Contagem(B, 1, 0)), Map.of());
        assertThat(linha(r, A, VELHA).falta()).isZero();
        assertThat(linha(r, B, VELHA).falta()).isEqualTo(2);
        assertThat(linha(r, B, VELHA).valorDaFalta()).isEqualByComparingTo("60.00");
        assertThat(r.sobras()).isEmpty();
    }

    @Test
    void esperadoSempreFechaComContadoMaisFalta() {
        Resultado r = EsperadoEContado.calcular(
                List.of(saldo(VELHA, ANTES, A, 6, "10.00"), saldo(NOVA, DEPOIS, A, 7, "10.00")),
                List.of(new PecasParaALoja(A, 2, d("20.00"))), List.of(new Contagem(A, 9, 3)), Map.of(A, 1));
        assertThat(r.itens()).allSatisfy(i -> assertThat(i.esperado()).isEqualTo(i.contadoBoa() + i.contadoDefeito() + i.falta()));
        assertThat(r.itens().stream().mapToInt(Item::esperado).sum()).isEqualTo(15);
        assertThat(r.itens().stream().mapToInt(i -> i.contadoBoa() + i.contadoDefeito()).sum()).isEqualTo(12);
    }
}
