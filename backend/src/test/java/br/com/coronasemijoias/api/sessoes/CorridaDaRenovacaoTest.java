package br.com.coronasemijoias.api.sessoes;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Renovacao;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.Renovada;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.SessaoAberta;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Corrida na renovação (ADR-0004, item 2), em PostgreSQL de verdade e com chamadas em paralelo: o mesmo
 * token apresentado ao mesmo tempo nunca cria dois sucessores. A trava da linha da família põe as
 * renovações em fila; o {@code UNIQUE (anterior_id)} é a segunda barreira.
 */
class CorridaDaRenovacaoTest extends TesteDeIntegracao {

    private static final int RODADAS = 20;

    @Autowired
    ServicoDeSessoes sessoes;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JdbcTemplate jdbc;

    /** Apresenta o mesmo token em {@code quantas} chamadas que saem juntas. */
    private List<Renovacao> emParalelo(String token, int quantas) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(quantas);
        try {
            CyclicBarrier largada = new CyclicBarrier(quantas);
            List<Future<Renovacao>> chamadas = new ArrayList<>();
            for (int i = 0; i < quantas; i++) {
                chamadas.add(executor.submit(() -> {
                    largada.await(10, TimeUnit.SECONDS);
                    return sessoes.renovar(token, UsuariosDeTeste.APARELHO);
                }));
            }
            List<Renovacao> resultados = new ArrayList<>();
            for (Future<Renovacao> chamada : chamadas) {
                resultados.add(chamada.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            executor.shutdownNow();
        }
    }

    private SessaoAberta abrir(UsuarioDeTeste vendedor) {
        return sessoes.abrir(vendedor.id(), TipoDeSessao.NORMAL, UsuariosDeTeste.APARELHO);
    }

    private long sucessoresDoPrimeiroToken(SessaoAberta sessao) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM corona.tokens_de_renovacao filho
                  JOIN corona.tokens_de_renovacao pai ON pai.id = filho.anterior_id
                 WHERE pai.sessao_id = ? AND pai.anterior_id IS NULL""", Long.class, sessao.sid());
    }

    @Test
    void duasRenovacoesSimultaneasRecebemOMesmoSucessor() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            SessaoAberta sessao = abrir(vendedor);

            List<Renovacao> resultados = emParalelo(sessao.tokenDeRenovacao(), 2);

            assertThat(resultados).as("rodada %d", rodada).allMatch(Renovada.class::isInstance);
            assertThat(resultados.stream().map(r -> ((Renovada) r).tokenDeRenovacao()).distinct()).hasSize(1);
            assertThat(sucessoresDoPrimeiroToken(sessao)).as("rodada %d", rodada).isEqualTo(1);
        }
    }

    @Test
    void oitoRenovacoesSimultaneasNuncaCriamDoisSucessores() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        for (int rodada = 0; rodada < RODADAS / 2; rodada++) {
            SessaoAberta sessao = abrir(vendedor);

            List<Renovacao> resultados = emParalelo(sessao.tokenDeRenovacao(), 8);

            assertThat(sucessoresDoPrimeiroToken(sessao)).as("rodada %d", rodada).isEqualTo(1);
            List<String> entregues = resultados.stream().filter(Renovada.class::isInstance)
                    .map(r -> ((Renovada) r).tokenDeRenovacao()).distinct().toList();
            assertThat(entregues).as("rodada %d", rodada).hasSize(1);
            // A terceira apresentação em diante é a segunda reapresentação: a família cai, como manda o ADR.
            assertThat(jdbc.queryForObject("SELECT motivo_da_revogacao FROM corona.sessoes WHERE id = ?", String.class,
                    sessao.sid())).isEqualTo("SEGUNDA_REAPRESENTACAO");
        }
    }
}
