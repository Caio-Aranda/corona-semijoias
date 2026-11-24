package br.com.coronasemijoias.api.cotacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** A tabela {@code cotacoes} no banco (ADR-0006, itens 4 e 8): imutável, sem parcial, uma por dia. */
class TabelaCotacoesTest extends TesteDeCotacoes {

    @Autowired
    RegistroDeTabelas registro;

    private static final String INSERT_COMPLETO = """
            INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte, vigente_desde)
            VALUES (uuidv7(), DATE '2026-11-06', 5.5007, 5.5013, 1, 1, 0.0008656, 0.000895, 6147, 6355, now(), 'teste',
                TIMESTAMPTZ '2026-11-06 00:00:00-03')""";

    @Test
    void ESincronizavelEImutavelNoRegistro() {
        assertThat(registro.sincronizaveis()).anySatisfy(tabela -> {
            assertThat(tabela.nome()).isEqualTo("cotacoes");
            assertThat(tabela.imutavel()).isTrue();
            assertThat(tabela.colunas()).hasSize(14).contains("usd_venda", "pyg_venda", "data_cotacao", "origem", "vigente_desde", "guaranis_por_real")
                    .doesNotContain("lancada_por");
        });
    }

    @Test
    void guardaOitoCasasSemArredondar() {
        gravarCotacaoDe(LocalDate.of(2026, 11, 6));

        String texto = jdbc.queryForObject(
                "SELECT pyg_venda::text FROM corona.cotacoes WHERE data_cotacao = DATE '2026-11-06'", String.class);
        assertThat(texto).isEqualTo("0.00089500");
    }

    @Test
    void aMesmaDataNaoEntraDuasVezes() {
        jdbc.update(INSERT_COMPLETO);
        assertThatThrownBy(() -> jdbc.update(INSERT_COMPLETO))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cotacoes_bcb_um_por_dia");
    }

    @Test
    void naoAceitaCotacaoParcial() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, buscada_em, fonte, vigente_desde)
                VALUES (uuidv7(), DATE '2026-11-06', 5.5007, 5.5013, 1, 1, now(), 'teste', now())"""))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pyg_");
    }

    @Test
    void naoAceitaZeroNemCompraMaiorQueVenda() {
        assertThatThrownBy(() -> jdbc.update(INSERT_COMPLETO.replace("0.000895,", "0,")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void naoAceitaCompraMaiorQueVendaNoBanco() {
        assertThatThrownBy(() -> jdbc.update(INSERT_COMPLETO.replace("5.5007, 5.5013", "5.6, 5.5013")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cotacoes_usd_compra_ate_venda");
    }

    @Test
    void recusaAlterarEApagar() {
        gravarCotacaoDe(LocalDate.of(2026, 11, 6));
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.cotacoes SET usd_venda = 9"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void recusaApagarMesmoComOUsuarioDaAplicacao() {
        gravarCotacaoDe(LocalDate.of(2026, 11, 6));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.cotacoes"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void entraNoPullComControleDeSync() {
        repositorio.gravar(LocalDate.of(2026, 11, 6),
                LeitorDoArquivoDeFechamento.ler(LocalDate.of(2026, 11, 6), LeitorDoArquivoDeFechamentoTest.arquivo(ARQUIVO_REAL)),
                Instant.parse("2026-11-06T17:00:00Z"), "teste");

        Integer comControle = jdbc.queryForObject(
                "SELECT count(*) FROM corona.cotacoes WHERE sync_seq IS NOT NULL AND sync_xid IS NOT NULL AND version = 1",
                Integer.class);
        assertThat(comControle).isEqualTo(1);
    }

    // --- V059: a cotação da dona ---

    private static final String INSERT_DA_DONA = """
            INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte,
                origem, vigente_desde, lancada_por, guaranis_por_real)
            VALUES (uuidv7(), DATE '2026-11-23', 5.8, 5.8, 1, 1, 0.00074074, 0.00074074, 7830, 7830, now(),
                'Digitada pela dona', 'DONA', TIMESTAMPTZ '2026-11-23 10:15:00-03', ?, 1350)""";

    @Test
    void oBancoCentralGravaVigenteDesdeAMeiaNoiteDeBrasilia() {
        gravarCotacaoDe(LocalDate.of(2026, 11, 6));

        Instant desde = jdbc.queryForObject(
                "SELECT vigente_desde FROM corona.cotacoes WHERE data_cotacao = DATE '2026-11-06'",
                java.time.OffsetDateTime.class).toInstant();
        assertThat(desde).isEqualTo(Instant.parse("2026-11-06T03:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT origem FROM corona.cotacoes", String.class)).isEqualTo("BANCO_CENTRAL");
    }

    @Test
    void doisDoBancoCentralNoMesmoDiaSaoRecusadosMasDuasDaDonaNoMesmoDiaEntram() {
        gravarCotacaoDe(LocalDate.of(2026, 11, 23));
        assertThat(repositorio.gravar(LocalDate.of(2026, 11, 23), TaxasDoDia.daDona(
                new java.math.BigDecimal("5.5"), new java.math.BigDecimal("1117")), Instant.now(), "outra")).isFalse();

        gravarDaDona("2026-11-23T13:15:00Z", "5.80", "1350");
        gravarDaDona("2026-11-23T17:00:00Z", "5.85", "1360");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.cotacoes WHERE data_cotacao = DATE '2026-11-23'",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void daDonaGuardaAsTaxasEOsGuaranisPorRealComoDigitou() {
        Cotacao c = gravarDaDona("2026-11-23T13:15:00Z", "5.80", "1350");

        assertThat(c.origem()).isEqualTo("DONA");
        assertThat(c.vigenteDesde()).isEqualTo(Instant.parse("2026-11-23T13:15:00Z"));
        assertThat(c.guaranisPorReal()).isEqualByComparingTo("1350");
        assertThat(c.taxas().usdCompra()).isEqualByComparingTo("5.80");
        assertThat(c.taxas().usdVenda()).isEqualByComparingTo("5.80");
        assertThat(c.taxas().pygCompra()).isEqualByComparingTo("0.00074074");
        assertThat(c.taxas().pygVenda()).isEqualByComparingTo("0.00074074");
        assertThat(c.taxas().pygParidadeVenda()).isEqualByComparingTo("7830.00000000");
        assertThat(c.dataCotacao()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(c.lancadaPor()).isNotNull();
    }

    private void recusa(String sql, java.util.UUID quem) {
        assertThatThrownBy(() -> jdbc.update(sql, quem)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void daDonaSemQuemLancouERecusada() {
        recusa(INSERT_DA_DONA, null);
    }

    @Test
    void daDonaComCompraDiferenteDeVendaERecusada() {
        recusa(INSERT_DA_DONA.replace("5.8, 5.8", "5.7, 5.8"), usuarios.criarDona().id());
    }

    @Test
    void daDonaComDiaDiferenteDoDaVigenciaERecusada() {
        recusa(INSERT_DA_DONA.replace("DATE '2026-11-23', 5.8", "DATE '2026-11-22', 5.8"), usuarios.criarDona().id());
    }

    @Test
    void daDonaSemGuaranisPorRealERecusadaEComTudoCertoEntra() {
        recusa(INSERT_DA_DONA.replace("?, 1350", "?, NULL"), usuarios.criarDona().id());
    }

    @Test
    void daDonaCompletaEntra() {
        assertThat(jdbc.update(INSERT_DA_DONA, usuarios.criarDona().id())).isEqualTo(1);
    }

    @Test
    void doBancoCentralNaoPodeTerQuemLancouNemGuaranisPorReal() {
        java.util.UUID dona = usuarios.criarDona().id();
        assertThatThrownBy(() -> jdbc.update(INSERT_DA_DONA.replace("'DONA'", "'BANCO_CENTRAL'"), dona))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cotacoes_origem_completa");
    }

    @Test
    void depoisDaMigracaoAindaRecusaAlterarEApagar() {
        gravarDaDona("2026-11-23T13:15:00Z", "5.80", "1350");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.cotacoes SET usd_venda = 9, usd_compra = 9"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.cotacoes")).isInstanceOf(DataAccessException.class);
    }
}
