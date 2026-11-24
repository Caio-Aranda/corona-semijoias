package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * As tabelas da conferência diária (tarefa 8.4, V047) só recebem INSERT: o próprio banco recusa UPDATE, DELETE e TRUNCATE, até para o
 * dono das tabelas, e os CHECK não deixam gravar uma rodada incoerente. Escrito antes da V047.
 */
class ImutabilidadeDaConferenciaTest extends TesteDeIntegracao {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuariosDeTeste usuarios;

    private UUID rodada(String situacao, String chavesComFalha) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.rodadas_conferencia (id, origem, iniciada_em, terminada_em, situacao, chaves_conferidas, chaves_com_falha,
                                                        total_diferencas)
                VALUES (?, 'AUTOMATICA', now(), now(), ?, ARRAY['C1'], ?::text[], 0)""", id, situacao, chavesComFalha);
        return id;
    }

    private UUID diferenca(UUID rodada) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.diferencas_conferencia (id, rodada_id, chave, descricao, esperado, encontrado, unidade, ordem)
                VALUES (?, ?, 'C11', 'Peça', '11', '10', 'PECAS', 1)""", id, rodada);
        return id;
    }

    @Test
    void asDuasTabelasSoRecebemInsertMesmoParaODonoDasTabelas() throws Exception {
        UUID r = rodada("SEM_DIFERENCA", "{}");
        UUID d = diferenca(r);
        for (String comando : new String[] {
            "UPDATE corona.rodadas_conferencia SET total_diferencas = 5 WHERE id = '" + r + "'",
            "DELETE FROM corona.rodadas_conferencia WHERE id = '" + r + "'",
            "TRUNCATE corona.rodadas_conferencia CASCADE",
            "UPDATE corona.diferencas_conferencia SET descricao = 'outra' WHERE id = '" + d + "'",
            "DELETE FROM corona.diferencas_conferencia WHERE id = '" + d + "'",
            "TRUNCATE corona.diferencas_conferencia"}) {
            assertThatThrownBy(() -> jdbc.update(comando)).as(comando).isInstanceOf(DataAccessException.class);
            try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
                assertThatThrownBy(() -> s.execute(comando)).as("dono: " + comando).isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23001");
                con.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT total_diferencas FROM corona.rodadas_conferencia WHERE id = ?", Integer.class, r)).isZero();
        assertThat(jdbc.queryForObject("SELECT descricao FROM corona.diferencas_conferencia WHERE id = ?", String.class, d)).isEqualTo("Peça");
    }

    @Test
    void osCheckRecusamRodadaIncoerente() {
        UUID dona = usuarios.dona().id();
        // PEDIDA sem quem pediu.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.rodadas_conferencia (id, origem, iniciada_em, terminada_em, situacao, chaves_conferidas, total_diferencas)
                VALUES (?, 'PEDIDA', now(), now(), 'SEM_DIFERENCA', ARRAY['C1'], 0)""", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        // AUTOMATICA com quem pediu.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.rodadas_conferencia (id, origem, pedida_por, iniciada_em, terminada_em, situacao, chaves_conferidas, total_diferencas)
                VALUES (?, 'AUTOMATICA', ?, now(), now(), 'SEM_DIFERENCA', ARRAY['C1'], 0)""", UUID.randomUUID(), dona))
                .isInstanceOf(DataAccessException.class);
        // FALHOU sem chave com falha, e chave com falha sem FALHOU.
        assertThatThrownBy(() -> rodada("FALHOU", "{}")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> rodada("SEM_DIFERENCA", "{C3}")).isInstanceOf(DataAccessException.class);
        // SEM_DIFERENCA com diferenças; terminou antes de começar; situação e unidade fora da lista.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.rodadas_conferencia (id, origem, iniciada_em, terminada_em, situacao, chaves_conferidas, total_diferencas)
                VALUES (?, 'AUTOMATICA', now(), now(), 'SEM_DIFERENCA', ARRAY['C1'], 2)""", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.rodadas_conferencia (id, origem, iniciada_em, terminada_em, situacao, chaves_conferidas, total_diferencas)
                VALUES (?, 'AUTOMATICA', now(), now() - interval '1 second', 'SEM_DIFERENCA', ARRAY['C1'], 0)""", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> rodada("QUASE", "{}")).isInstanceOf(DataAccessException.class);
        UUID r = rodada("FALHOU", "{C3}");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.diferencas_conferencia (id, rodada_id, chave, descricao, unidade, ordem)
                VALUES (?, ?, 'C1', 'x', 'DOLARES', 1)""", UUID.randomUUID(), r)).isInstanceOf(DataAccessException.class);
        // Uma rodada pedida de verdade entra.
        jdbc.update("""
                INSERT INTO corona.rodadas_conferencia (id, origem, pedida_por, iniciada_em, terminada_em, situacao, chaves_conferidas, total_diferencas)
                VALUES (?, 'PEDIDA', ?, now(), now(), 'COM_DIFERENCA', ARRAY['C1'], 1)""", UUID.randomUUID(), dona);
    }
}
