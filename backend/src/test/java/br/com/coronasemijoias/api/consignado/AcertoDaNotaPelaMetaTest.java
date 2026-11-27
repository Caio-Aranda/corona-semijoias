package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.configuracoes.ServicoDoConsignado;
import br.com.coronasemijoias.api.configuracoes.ServicoDoConsignado.Consignado;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.EstornoDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.Visto;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O servidor decide a comissão do acerto da nota pela meta (formatos 1 e 2; ESPECIFICACAO §23.13; plano acerto-45-dias, D1 a D4 e D7).
 * A cotação "em uso" dos testes é a do exemplo do ADR-0012: 1 Gs = R$ 0,00090000, então R$ 630,00 = Gs. 700.000 exatos.
 * Escritos antes do código. PostgreSQL de verdade.
 */
class AcertoDaNotaPelaMetaTest extends ApoioDoConsignado {

    private static final Consignado PADRAO = new Consignado(700_000, 30, 40, 45);

    @Autowired
    ServicoDoConsignado configuracaoDoConsignado;

    @BeforeEach
    @AfterEach
    void configuracaoEPadraoESemCotacao() throws SQLException {
        configuracaoDoConsignado.alterar(PADRAO, usuarios.dona().id());
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    private record Cena(UsuarioDeTeste v, UUID cliente, UUID peca, UUID maleta, UUID semana) {
    }

    /** A peça custa o que a nota vai valer: o servidor usa o preço da maleta, não o do app. */
    private Cena cena(String preco) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca(preco, 5);
        UUID maleta = maletaEnviada(v, peca, 3);
        return new Cena(v, cliente, peca, maleta, abrirSemana(v));
    }

    /** Uma nota de uma peça só, com o preço dado (o VD da nota sem devolução). */
    private UUID notaDe(Cena c, String preco, int sequencia) {
        return criarNota(c.v(), c.semana(), sequencia, c.cliente(), item(c.peca(), c.maleta(), 1, preco));
    }

    /** Acerto formato 2: sem porcentagem, o servidor decide. */
    private UUID acertarPelaMeta(Cena c, UUID nota, Visto visto) {
        UUID id = UUID.randomUUID();
        acertos.acertar(ctx(c.v()), new NovoAcerto(id, nota, c.semana(), null, List.of(), null, visto));
        return id;
    }

    private Map<String, Object> linha(UUID acerto) {
        return jdbc.queryForMap("SELECT * FROM corona.acertos_nota WHERE id = ?", acerto);
    }

    @Test
    void naMetaExataAComissaoEDe40EGravaAMeta() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("630.00");
        UUID acerto = acertarPelaMeta(c, notaDe(c, "630.00", 1), null);
        var r = linha(acerto);
        assertThat(r.get("regra_da_comissao")).isEqualTo("META");
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("40");
        assertThat(r.get("meta_guarani")).isEqualTo(700_000L);
        assertThat(r.get("vd_guarani")).isEqualTo(700_000L);
        assertThat((BigDecimal) r.get("comissao")).isEqualByComparingTo("252.00");
        assertThat((BigDecimal) r.get("bruto")).isEqualByComparingTo("378.00");
    }

    @Test
    void umGuaraniAbaixoDaMetaAComissaoEDe30() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("629.99");
        UUID acerto = acertarPelaMeta(c, notaDe(c, "629.99", 1), null);
        var r = linha(acerto);
        assertThat(r.get("regra_da_comissao")).isEqualTo("META");
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("30");
        assertThat(r.get("vd_guarani")).isEqualTo(699_989L);
        assertThat((BigDecimal) r.get("comissao")).isEqualByComparingTo("189.00");
    }

    @Test
    void ametaEOsPercentuaisVemDaConfiguracaoDoMomento() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        configuracaoDoConsignado.alterar(new Consignado(500_000, 20, 35, 45), usuarios.dona().id());
        Cena c = cena("450.00");
        UUID acerto = acertarPelaMeta(c, notaDe(c, "450.00", 1), null);
        var r = linha(acerto);
        assertThat(r.get("meta_guarani")).isEqualTo(500_000L);
        assertThat(r.get("vd_guarani")).isEqualTo(500_000L);
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("35");
    }

    @Test
    void semCotacaoFicaAbaixoDaMetaComAlerta() throws Exception {
        Cena c = cena("900.00");
        UUID acerto = acertarPelaMeta(c, notaDe(c, "900.00", 1), null);
        var r = linha(acerto);
        assertThat(r.get("regra_da_comissao")).isEqualTo("META");
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("30");
        assertThat(r.get("vd_guarani")).isNull();
        assertThat(r.get("cotacao_id")).isNull();
        assertThat(r.get("meta_guarani")).isEqualTo(700_000L);
        assertThat(contar("SELECT count(*) FROM corona.alertas_consignado WHERE registro_id = ? AND detalhe->>'motivo' = 'SEM_COTACAO'",
                acerto)).isEqualTo(1);
    }

    /**
     * C5 (revisão de 27/11/2026): o formato 1 (Corona Vendas 1.2.0, com porcentagem) também é decidido pela meta; a porcentagem
     * do computador vira só conferência, com alerta quando é diferente.
     */
    @Test
    void formato1NaMetaOServidorDecide40MesmoComOutraPorcentagemEAvisaADona() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("630.00");
        UUID acerto = acertar(c.v(), notaDe(c, "630.00", 1), c.semana(), "10", List.of(), null);
        var r = linha(acerto);
        assertThat(r.get("regra_da_comissao")).isEqualTo("META");
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("40");
        assertThat(r.get("vd_guarani")).isEqualTo(700_000L);
        assertThat((BigDecimal) r.get("comissao")).isEqualByComparingTo("252.00");
        assertThat(contar("SELECT count(*) FROM corona.alertas_consignado WHERE registro_id = ? AND tipo = 'VALOR_DO_ACERTO_RECALCULADO'"
                + " AND detalhe->>'comissao_percentual_informada' = '10.00' AND detalhe->>'comissao_percentual_servidor' = '40.00'",
                acerto)).isEqualTo(1);
    }

    @Test
    void formato1AbaixoDaMetaFicaEm30MesmoQueOComputadorMande40() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("629.99");
        UUID acerto = acertar(c.v(), notaDe(c, "629.99", 1), c.semana(), "40", List.of(), null);
        var r = linha(acerto);
        assertThat(r.get("regra_da_comissao")).isEqualTo("META");
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("30");
        assertThat((BigDecimal) r.get("comissao")).isEqualByComparingTo("189.00");
        assertThat(contar("SELECT count(*) FROM corona.alertas_consignado WHERE registro_id = ? AND tipo = 'VALOR_DO_ACERTO_RECALCULADO'",
                acerto)).isEqualTo(1);
    }

    @Test
    void formato1ComAMesmaPorcentagemDoServidorNaoAvisa() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("630.00");
        UUID acerto = acertar(c.v(), notaDe(c, "630.00", 1), c.semana(), "40", List.of(), null);
        assertThat(linha(acerto).get("regra_da_comissao")).isEqualTo("META");
        assertThat(contar("SELECT count(*) FROM corona.alertas_consignado WHERE registro_id = ?", acerto)).isZero();
    }

    @Test
    void cotacaoDaDonaLancadaDepoisMudaADecisaoEViraAlertaPeloVisto() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("629.99");
        UUID nota = notaDe(c, "629.99", 1);
        // O computador, com a cotação que tinha, viu 30% (R$ 189,00); a dona lançou uma cotação melhor antes de o acerto chegar.
        CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), relogio.instant().minus(Duration.ofHours(1)), "5.00", "1500");
        var visto = new Visto(new BigDecimal("629.99"), new BigDecimal("189.00"), new BigDecimal("440.99"), new BigDecimal("0.00"),
                new BigDecimal("440.99"), new BigDecimal("0.00"), new BigDecimal("440.99"));
        UUID acerto = acertarPelaMeta(c, nota, visto);
        var r = linha(acerto);
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("40");
        assertThat((BigDecimal) r.get("comissao")).isEqualByComparingTo("252.00");
        assertThat(alertas(c.v(), "VALOR_DO_ACERTO_RECALCULADO")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detalhe->>'comissao_percentual_servidor' FROM corona.alertas_consignado WHERE registro_id = ?",
                String.class, acerto)).isEqualTo("40.00");
    }

    @Test
    void vistoIgualAoDoServidorNaoGeraAlerta() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("630.00");
        var visto = new Visto(new BigDecimal("630.00"), new BigDecimal("252.00"), new BigDecimal("378.00"), new BigDecimal("0.00"),
                new BigDecimal("378.00"), new BigDecimal("0.00"), new BigDecimal("378.00"));
        acertarPelaMeta(c, notaDe(c, "630.00", 1), visto);
        assertThat(alertas(c.v(), "VALOR_DO_ACERTO_RECALCULADO")).isZero();
    }

    @Test
    void mesmoAcertoDuasVezesGravaUmSo() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("630.00");
        UUID nota = notaDe(c, "630.00", 1);
        var pedido = new NovoAcerto(UUID.randomUUID(), nota, c.semana(), null, List.of(), null, null);
        assertThat(acertos.acertar(ctx(c.v()), pedido)).isFalse();
        assertThat(acertos.acertar(ctx(c.v()), pedido)).isTrue();
        assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", nota)).isEqualTo(1);
    }

    @Test
    void estornoEAcertoRefeitoComOutraConfiguracaoGuardaCadaUmComASua() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("630.00");
        UUID nota = notaDe(c, "630.00", 1);
        UUID primeiro = acertarPelaMeta(c, nota, null);
        assertThat((BigDecimal) linha(primeiro).get("comissao_percentual")).isEqualByComparingTo("40");

        // Mudar a configuração não muda o acerto feito (D7).
        configuracaoDoConsignado.alterar(new Consignado(1_000_000, 25, 35, 45), usuarios.dona().id());
        var depoisDeMudar = linha(primeiro);
        assertThat(depoisDeMudar.get("meta_guarani")).isEqualTo(700_000L);
        assertThat((BigDecimal) depoisDeMudar.get("comissao_percentual")).isEqualByComparingTo("40");

        acertos.estornar(ctx(c.v()), new EstornoDoAcerto(UUID.randomUUID(), primeiro, "Refazer"));
        UUID segundo = acertarPelaMeta(c, nota, null);
        var r = linha(segundo);
        assertThat(r.get("meta_guarani")).isEqualTo(1_000_000L);
        assertThat(r.get("vd_guarani")).isEqualTo(700_000L);
        assertThat((BigDecimal) r.get("comissao_percentual")).isEqualByComparingTo("25");
        // O estornado continua como foi.
        assertThat(linha(primeiro).get("meta_guarani")).isEqualTo(700_000L);
        assertThat(linha(primeiro).get("estornado_em")).isNotNull();
    }

    @Test
    void oBancoProtegeAsColunasNovasEExigeAMetaComARegraMeta() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cena c = cena("630.00");
        UUID acerto = acertarPelaMeta(c, notaDe(c, "630.00", 1), null);
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            for (String atribuicao : List.of("meta_guarani = 1", "vd_guarani = 1", "regra_da_comissao = 'INFORMADA'")) {
                Throwable t = catchThrowable(() -> s.execute("UPDATE corona.acertos_nota SET " + atribuicao + " WHERE id = '" + acerto + "'"));
                assertThat(t).as(atribuicao).hasMessageContaining("não pode ser alterado");
                con.rollback();
            }
            // Sem o gatilho, o CHECK segura: META sem meta não passa.
            s.execute("ALTER TABLE corona.acertos_nota DISABLE TRIGGER USER");
            Throwable semMeta = catchThrowable(() -> s.execute("UPDATE corona.acertos_nota SET meta_guarani = NULL WHERE id = '" + acerto + "'"));
            assertThat(semMeta).hasMessageContaining("acertos_nota_meta_coerente");
            con.rollback();
            // E INFORMADA com meta também não.
            s.execute("ALTER TABLE corona.acertos_nota DISABLE TRIGGER USER");
            Throwable informadaComMeta = catchThrowable(
                    () -> s.execute("UPDATE corona.acertos_nota SET regra_da_comissao = 'INFORMADA' WHERE id = '" + acerto + "'"));
            assertThat(informadaComMeta).hasMessageContaining("acertos_nota_meta_coerente");
            con.rollback();
        }
    }
}
