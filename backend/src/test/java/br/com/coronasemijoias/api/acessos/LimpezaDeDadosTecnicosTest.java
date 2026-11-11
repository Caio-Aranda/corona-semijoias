package br.com.coronasemijoias.api.acessos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Limpeza dos dados técnicos (V028): sessões encerradas, tokens e tentativas de login com mais de 90 dias. */
class LimpezaDeDadosTecnicosTest extends TesteDeIntegracao {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    LimpezaDeDadosTecnicos limpeza;

    private UUID sessao(UUID usuario, String criada, String usada, String revogada) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.sessoes (id, usuario_id, tipo, criada_em, usada_em, revogada_em, motivo_da_revogacao) "
                + "VALUES (?, ?, 'NORMAL', now() - ?::interval, now() - ?::interval, "
                + (revogada == null ? "NULL, NULL" : "now() - ?::interval, 'SAIDA'") + ")",
                revogada == null ? new Object[] {id, usuario, criada, usada} : new Object[] {id, usuario, criada, usada, revogada});
        return id;
    }

    private void token(UUID sessao) {
        byte[] hash = new byte[32];
        new java.util.Random().nextBytes(hash);
        jdbc.update("INSERT INTO corona.tokens_de_renovacao (id, sessao_id, hash, criado_em, vence_em) "
                + "VALUES (?, ?, ?, now() - interval '200 days', now() - interval '170 days')", UUID.randomUUID(), sessao, hash);
    }

    private boolean existeSessao(UUID id) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.sessoes WHERE id = ?)", Boolean.class, id);
    }

    private UUID tentativa(String login, String atras) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.tentativas_login (id, login, ip, resultado, ocorrida_em) "
                + "VALUES (?, ?, '10.0.0.1', 'SENHA_INCORRETA', now() - ?::interval)", id, login, atras);
        return id;
    }

    private boolean existeTentativa(UUID id) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.tentativas_login WHERE id = ?)", Boolean.class, id);
    }

    @Test
    void apagaDeVerdadeSoOQueEncerrouHaMaisDe90DiasEMantemTodoOResto() {
        UUID usuario = usuarios.dona().id();
        UUID revogadaAntiga = sessao(usuario, "200 days", "150 days", "100 days");
        token(revogadaAntiga);
        UUID expiradaAntiga = sessao(usuario, "300 days", "125 days", null); // 30 dias de validade + 90 de retenção
        token(expiradaAntiga);
        UUID revogadaRecente = sessao(usuario, "100 days", "80 days", "89 days");
        UUID expiradaRecente = sessao(usuario, "200 days", "119 days", null);
        UUID aberta = sessao(usuario, "10 days", "1 day", null);
        token(aberta);
        UUID tentativaAntiga = tentativa("limpeza.antiga", "91 days");
        UUID tentativaRecente = tentativa("limpeza.recente", "89 days");

        LimpezaDeDadosTecnicos.Resultado r = limpeza.limpar();

        assertThat(r.sessoes()).isGreaterThanOrEqualTo(2);
        assertThat(r.tokens()).isGreaterThanOrEqualTo(2);
        assertThat(r.tentativas()).isGreaterThanOrEqualTo(1);
        assertThat(existeSessao(revogadaAntiga)).isFalse();
        assertThat(existeSessao(expiradaAntiga)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.tokens_de_renovacao WHERE sessao_id IN (?, ?)", Integer.class,
                revogadaAntiga, expiradaAntiga)).isZero();
        assertThat(existeSessao(revogadaRecente)).isTrue();
        assertThat(existeSessao(expiradaRecente)).isTrue();
        assertThat(existeSessao(aberta)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.tokens_de_renovacao WHERE sessao_id = ?", Integer.class, aberta))
                .isEqualTo(1);
        assertThat(existeTentativa(tentativaAntiga)).isFalse();
        assertThat(existeTentativa(tentativaRecente)).isTrue();
        // Rodar de novo não acha mais nada para apagar do que já saiu.
        assertThat(limpeza.limpar().sessoes()).isZero();
    }

    @Test
    void foraDaLimpezaOBancoContinuaRecusandoDeleteEmTodasAsTabelas() {
        UUID usuario = usuarios.dona().id();
        UUID velha = sessao(usuario, "200 days", "150 days", "100 days");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.sessoes WHERE id = ?", velha))
                .hasMessageContaining("Não é permitido apagar");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.tentativas_login WHERE login = 'nao.existe'"))
                .hasMessageContaining("Não é permitido apagar");
        // Mesmo com o aviso, só as três tabelas técnicas deixam apagar (aqui, a de dados de negócio recusa, por privilégio ou pelo gatilho).
        assertThatThrownBy(() -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SELECT set_config('corona.limpeza_tecnica', 'sim', true)");
                st.execute("DELETE FROM corona.auditoria WHERE false");
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
            return null;
        })).rootCause().hasMessageMatching("(?s).*(permission denied|Não é permitido apagar).*");
        assertThat(existeSessao(velha)).isTrue();
    }
}
