package br.com.coronasemijoias.api.configuracoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** A tabela {@code configuracoes} (especificação §9): as chaves da loja (a garantia está em GarantiaTest), com os padrões. */
class ConfiguracoesTest extends TesteDeIntegracao {

    @Autowired
    Configuracoes configuracoes;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void asChavesComOsPadroes() {
        assertThat(jdbc.queryForList("SELECT chave FROM corona.configuracoes ORDER BY chave", String.class))
                .containsExactly("acerto_do_vendedor_visivel", "comissao_meta_guarani", "comissao_percentual_abaixo_da_meta", "comissao_percentual_na_meta",
                        "limite_desconto_percentual", "limite_reembolso_semanal", "percentual_alerta_devolucao",
                        "prazo_arrependimento_dias", "prazo_defeito_dias", "tempo_bloqueio_login_minutos",
                        "tempo_maximo_offline_dias", "tentativas_login_antes_do_bloqueio", "valor_aprovacao_reembolso");
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.LIMITE_DESCONTO_PERCENTUAL)).isZero();
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.TEMPO_MAXIMO_OFFLINE_DIAS)).isEqualTo(10);
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.TENTATIVAS_LOGIN_ANTES_DO_BLOQUEIO)).isEqualTo(5);
        assertThat(configuracoes.inteiro(ChaveDeConfiguracao.TEMPO_BLOQUEIO_LOGIN_MINUTOS)).isEqualTo(15);
    }

    @Test
    void oBancoRecusaValorForaDaFaixaEChaveNovaPelaAplicacao() throws SQLException {
        try (Connection conexao = conectarComoAplicacao()) {
            for (String comando : List.of(
                    "UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'tentativas_login_antes_do_bloqueio'",
                    "UPDATE corona.configuracoes SET valor_inteiro = NULL WHERE chave = 'tempo_maximo_offline_dias'",
                    "UPDATE corona.configuracoes SET valor_inteiro = 2000 WHERE chave = 'tempo_bloqueio_login_minutos'",
                    "INSERT INTO corona.configuracoes (id, chave, tipo, valor_inteiro) VALUES (uuidv7(), 'outra', 'INTEIRO', 1)")) {
                assertThatThrownBy(() -> conexao.createStatement().execute(comando)).as(comando)
                        .isInstanceOf(SQLException.class);
                conexao.rollback();
            }
        }
    }
}
