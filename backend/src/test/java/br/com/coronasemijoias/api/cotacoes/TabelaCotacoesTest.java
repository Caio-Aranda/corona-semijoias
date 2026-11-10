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
                usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
            VALUES (uuidv7(), DATE '2026-11-06', 5.5007, 5.5013, 1, 1, 0.0008656, 0.000895, 6147, 6355, now(), 'teste')""";

    @Test
    void ESincronizavelEImutavelNoRegistro() {
        assertThat(registro.sincronizaveis()).anySatisfy(tabela -> {
            assertThat(tabela.nome()).isEqualTo("cotacoes");
            assertThat(tabela.imutavel()).isTrue();
            assertThat(tabela.colunas()).hasSize(11).contains("usd_venda", "pyg_venda", "data_cotacao");
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
                .hasMessageContaining("cotacoes_data_unica");
    }

    @Test
    void naoAceitaCotacaoParcial() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, buscada_em, fonte)
                VALUES (uuidv7(), DATE '2026-11-06', 5.5007, 5.5013, 1, 1, now(), 'teste')"""))
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
}
