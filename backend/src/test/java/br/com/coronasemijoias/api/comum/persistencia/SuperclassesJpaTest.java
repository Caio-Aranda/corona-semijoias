package br.com.coronasemijoias.api.comum.persistencia;

import static br.com.coronasemijoias.api.testes.Sql.executar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Como a edição otimista do Hibernate convive com o gatilho (ADR-0002, item 3): o {@code version}
 * é {@code @Version} + {@code @Generated}. O Java nunca grava a versão nem os outros campos do
 * gatilho; o Hibernate relê o que o gatilho gravou e usa a versão lida no {@code WHERE}.
 */
class SuperclassesJpaTest extends TesteDeIntegracao {

    @Autowired
    EntityManager em;

    @Autowired
    TransactionTemplate transacao;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void insercaoLeDeVoltaOQueOGatilhoGravou() {
        UUID id = UuidV7.gerar();
        EntidadeDeTesteSincronizavel nova = new EntidadeDeTesteSincronizavel(id, "novo");
        assertThat(nova.getVersion()).isNull();

        transacao.executeWithoutResult(estado -> em.persist(nova));

        assertThat(nova.getVersion()).isEqualTo(1);
        assertThat(nova.getCreatedAt()).isNotNull();
        assertThat(nova.getUpdatedAt()).isNotNull();
        assertThat(nova.getSyncSeq()).isNotNull();
        assertThat(nova.getSyncXid()).isNotNull().isPositive();
        assertThat(nova.getOrigemDispositivo()).isNull();
        assertThat(versaoNoBanco("teste_sync_a", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sync_seq FROM teste_sync_a WHERE id = ?", Long.class, id))
                .isEqualTo(nova.getSyncSeq());
        assertThat(jdbc.queryForObject("SELECT sync_xid::text FROM teste_sync_a WHERE id = ?", String.class, id))
                .isEqualTo(Long.toUnsignedString(nova.getSyncXid()));
    }

    @Test
    void edicaoSobeAVersaoNoObjetoENoBanco() {
        UUID id = persistir("antes");
        long seqDaInsercao = jdbc.queryForObject("SELECT sync_seq FROM teste_sync_a WHERE id = ?", Long.class, id);

        EntidadeDeTesteSincronizavel editada = transacao.execute(estado -> {
            EntidadeDeTesteSincronizavel e = em.find(EntidadeDeTesteSincronizavel.class, id);
            e.setTexto("depois");
            return e;
        });

        assertThat(editada.getVersion()).isEqualTo(2);
        assertThat(editada.getSyncSeq()).isGreaterThan(seqDaInsercao);
        assertThat(versaoNoBanco("teste_sync_a", id)).isEqualTo(2);

        // Um objeto desligado (por exemplo, vindo de outra requisição) com a versão certa também grava.
        editada.setTexto("de novo");
        EntidadeDeTesteSincronizavel juntada = transacao.execute(estado -> em.merge(editada));
        assertThat(juntada.getVersion()).isEqualTo(3);
        assertThat(versaoNoBanco("teste_sync_a", id)).isEqualTo(3);
    }

    @Test
    void edicaoComVersaoVelhaFalha() throws Exception {
        UUID id = persistir("original");

        assertThatThrownBy(() -> transacao.executeWithoutResult(estado -> {
                    EntidadeDeTesteSincronizavel lida = em.find(EntidadeDeTesteSincronizavel.class, id);
                    assertThat(lida.getVersion()).isEqualTo(1);
                    // Outra transação grava a mesma linha e confirma antes.
                    try (Connection outra = conectarComoAplicacao()) {
                        executar(outra, "UPDATE teste_sync_a SET texto = 'outra pessoa' WHERE id = ?", id);
                        outra.commit();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    lida.setTexto("eu");
                    em.flush();
                }))
                .isInstanceOfAny(OptimisticLockException.class, ObjectOptimisticLockingFailureException.class);

        assertThat(versaoNoBanco("teste_sync_a", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT texto FROM teste_sync_a WHERE id = ?", String.class, id))
                .isEqualTo("outra pessoa");
    }

    @Test
    void objetoDesligadoComVersaoVelhaFalha() {
        UUID id = persistir("original");
        EntidadeDeTesteSincronizavel velha = transacao.execute(estado -> em.find(EntidadeDeTesteSincronizavel.class, id));
        jdbc.update("UPDATE teste_sync_a SET texto = 'outra pessoa' WHERE id = ?", id);

        velha.setTexto("eu");
        assertThatThrownBy(() -> transacao.execute(estado -> em.merge(velha)))
                .isInstanceOfAny(OptimisticLockException.class, ObjectOptimisticLockingFailureException.class);
        assertThat(versaoNoBanco("teste_sync_a", id)).isEqualTo(2);
    }

    @Test
    void exclusaoLogicaEUmUpdateQueSobeAVersao() {
        UUID id = persistir("vai sair");
        EntidadeDeTesteSincronizavel excluida = transacao.execute(estado -> {
            EntidadeDeTesteSincronizavel e = em.find(EntidadeDeTesteSincronizavel.class, id);
            e.marcarComoExcluida(Instant.now());
            return e;
        });
        assertThat(excluida.estaExcluida()).isTrue();
        assertThat(excluida.getVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM teste_sync_a WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void identificadorForaDaRegraNaoCriaEntidade() {
        assertThatThrownBy(() -> new EntidadeDeTesteSincronizavel(UUID.fromString("c232ab00-9414-11ec-b3c8-9f6bdeced846"), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entidadeDoServidorGeraUuidV7ETemVersao() {
        EntidadeDeTesteDoServidor nova = new EntidadeDeTesteDoServidor("servidor");
        assertThat(nova.getId().version()).isEqualTo(7);
        transacao.executeWithoutResult(estado -> em.persist(nova));
        assertThat(nova.getVersion()).isEqualTo(1);
        assertThat(nova.getCreatedAt()).isNotNull();

        EntidadeDeTesteDoServidor editada = transacao.execute(estado -> {
            EntidadeDeTesteDoServidor e = em.find(EntidadeDeTesteDoServidor.class, nova.getId());
            e.setDescricao("editada");
            return e;
        });
        assertThat(editada.getVersion()).isEqualTo(2);
        assertThat(editada.getCreatedAt()).isEqualTo(nova.getCreatedAt());
        assertThat(versaoNoBanco("teste_servidor", nova.getId())).isEqualTo(2);

        jdbc.update("UPDATE teste_servidor SET descricao = 'outra pessoa' WHERE id = ?", nova.getId());
        editada.setDescricao("velha");
        assertThatThrownBy(() -> transacao.execute(estado -> em.merge(editada)))
                .isInstanceOfAny(OptimisticLockException.class, ObjectOptimisticLockingFailureException.class);
    }

    private UUID persistir(String texto) {
        UUID id = UuidV7.gerar();
        transacao.executeWithoutResult(estado -> em.persist(new EntidadeDeTesteSincronizavel(id, texto)));
        return id;
    }

    private int versaoNoBanco(String tabela, UUID id) {
        return jdbc.queryForObject("SELECT version FROM " + tabela + " WHERE id = ?", Integer.class, id);
    }
}
