package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.conciliacoes.ConferenciaDaRodada.LinhaDeDiferenca;
import java.sql.Array;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A rodada da conferência diária (tarefa 8.4; ADR-0015, item 6): roda as treze conferências num retrato só do banco (uma transação
 * com isolamento repetível), guarda o resultado e NADA mais. É a única classe que escreve nas duas tabelas da rodada
 * ({@code rodadas_conferencia} e {@code diferencas_conferencia}, só INSERT); nenhuma outra tabela muda e nada é corrigido sozinho.
 *
 * <ul>
 *   <li>Um id de comando grava no máximo uma rodada: o mesmo id de novo devolve a rodada já gravada (200).</li>
 *   <li>Uma rodada de cada vez, por trava consultiva do PostgreSQL: quem chega com outra rodada rodando recebe 409 (a segunda
 *       rodada pedida não espera a primeira; a tela mostra o resultado ao atualizar).</li>
 *   <li>Uma conferência com erro não derruba as outras: volta ao ponto de salvamento, a rodada fica {@code FALHOU} com a chave
 *       guardada (só a chave: o erro vai para o registro técnico, nunca para a tela).</li>
 * </ul>
 *
 * <p>Os métodos de leitura que a rodada chama ({@code ConciliacoesDaFase7.conferir}, {@code ConferenciasDeDevolucaoETroca.conferir} e
 * {@code ServicoDeEstoque.conciliar}) têm {@code noRollbackFor}: sem isso, um erro que passa por eles marcaria a transação de fora
 * como "só desfazer" e a rodada inteira se perderia. Eles só leem, então não há nada a desfazer.
 */
@Service
public class ServicoDaConferenciaDiaria {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDaConferenciaDiaria.class);
    private static final String TRAVA = "corona.conferencia-diaria";

    public enum Origem {
        AUTOMATICA, PEDIDA
    }

    public enum Situacao {
        SEM_DIFERENCA, COM_DIFERENCA, FALHOU
    }

    /** {@code jaExistia}: o id já estava gravado (200); senão, a rodada foi feita agora (201). */
    public record Resultado(UUID id, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final CatalogoDasConferencias catalogo;
    private final Clock relogio;

    ServicoDaConferenciaDiaria(JdbcTemplate jdbc, PlatformTransactionManager gerenteDeTransacao, CatalogoDasConferencias catalogo,
            Clock relogio) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
        this.transacao.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.catalogo = catalogo;
        this.relogio = relogio;
    }

    /**
     * Faz uma rodada. {@code pedidaPor} só vale (e é obrigatório) quando a origem é PEDIDA.
     *
     * @throws ErroDaApi 409 se já há uma rodada rodando
     */
    public Resultado rodar(UUID id, Origem origem, UUID pedidaPor) {
        if (jaGravada(id)) {
            return new Resultado(id, true);
        }
        try {
            transacao.executeWithoutResult(status -> fazer(id, origem, pedidaPor));
            return new Resultado(id, false);
        } catch (DuplicateKeyException repetida) {
            // O mesmo comando chegou duas vezes ao mesmo tempo e a outra gravou primeiro.
            return new Resultado(id, true);
        }
    }

    private boolean jaGravada(UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM corona.rodadas_conferencia WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    private void fazer(UUID id, Origem origem, UUID pedidaPor) {
        Boolean livre = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, TRAVA);
        if (!Boolean.TRUE.equals(livre)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONFERENCIA_EM_ANDAMENTO);
        }
        Instant iniciada = Instant.now(relogio);
        List<String> conferidas = new ArrayList<>();
        List<String> comFalha = new ArrayList<>();
        List<LinhaGuardada> linhas = new ArrayList<>();
        int ponto = 0;
        for (ConferenciaDaRodada conferencia : catalogo.todas()) {
            String salvamento = "conferencia_" + (++ponto);
            jdbc.execute("SAVEPOINT " + salvamento);
            try {
                List<LinhaDeDiferenca> achadas = conferencia.conferir();
                jdbc.execute("RELEASE SAVEPOINT " + salvamento);
                conferidas.add(conferencia.chave());
                for (LinhaDeDiferenca l : achadas) {
                    linhas.add(new LinhaGuardada(conferencia.chave(), l));
                }
            } catch (RuntimeException erro) {
                jdbc.execute("ROLLBACK TO SAVEPOINT " + salvamento);
                comFalha.add(conferencia.chave());
                LOG.error("Conferência diária: a conferência {} deu erro e foi pulada.", conferencia.chave(), erro);
            }
        }
        Situacao situacao = !comFalha.isEmpty() ? Situacao.FALHOU : linhas.isEmpty() ? Situacao.SEM_DIFERENCA : Situacao.COM_DIFERENCA;
        Instant terminada = Instant.now(relogio);
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO corona.rodadas_conferencia (id, origem, pedida_por, iniciada_em, terminada_em, situacao,
                                                            chaves_conferidas, chaves_com_falha, total_diferencas)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""");
            ps.setObject(1, id);
            ps.setString(2, origem.name());
            ps.setObject(3, pedidaPor);
            ps.setObject(4, OffsetDateTime.ofInstant(iniciada, ZoneOffset.UTC));
            ps.setObject(5, OffsetDateTime.ofInstant(terminada.isBefore(iniciada) ? iniciada : terminada, ZoneOffset.UTC));
            ps.setString(6, situacao.name());
            Array c = con.createArrayOf("text", conferidas.toArray());
            ps.setArray(7, c);
            ps.setArray(8, con.createArrayOf("text", comFalha.toArray()));
            ps.setInt(9, linhas.size());
            return ps;
        });
        int ordem = 0;
        for (LinhaGuardada g : linhas) {
            LinhaDeDiferenca l = g.linha();
            jdbc.update("""
                    INSERT INTO corona.diferencas_conferencia (id, rodada_id, chave, item, tipo, referencia, descricao, esperado,
                                                               encontrado, unidade, ordem)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), id, g.chave(), l.item(), l.tipo(), l.referencia(),
                    l.descricao(), l.esperado(), l.encontrado(), l.unidade(), ++ordem);
        }
    }

    private record LinhaGuardada(String chave, LinhaDeDiferenca linha) {
    }
}
