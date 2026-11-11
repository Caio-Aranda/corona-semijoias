package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas;
import br.com.coronasemijoias.api.sync.CenarioDeSync.CopiaLocal;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Estresse do pull COM o filtro do vendedor (ADR-0013, item 5, prova 4; tarefa 5.4): escritores em paralelo fazem
 * mudanças de pertença de verdade (peças novas entrando em maletas enviadas, maletas de dois vendedores, troca de foto,
 * código excluído, preço, conferência e venda) enquanto o vendedor puxa sem parar, com lotes aleatórios. No fim, com
 * tudo parado, a cópia do vendedor é igual ao oráculo.
 *
 * <p>Duração: {@code -Dcorona.estresse.duracao} (padrão PT20S, como o estresse da Fase 0; o semanal usa PT10M).
 */
class EstresseDoPullComFiltroTest extends TesteDeIntegracao {

    private static final Logger LOG = LoggerFactory.getLogger(EstresseDoPullComFiltroTest.class);
    private static final int ESCRITORES = 4;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    ServicoDeEstoque estoque;

    @Autowired
    ServicoDeRemessas remessas;

    @Autowired
    ServicoDeConferencia conferencias;

    @Autowired
    ServicoDeVendas vendas;

    @Autowired
    ServicoDePull pull;

    @Test
    void aCopiaDoVendedorFicaIgualAoOraculoComEscritasEmParalelo() throws Exception {
        Duration duracao = Duration.parse(System.getProperty("corona.estresse.duracao", "PT20S"));
        long semente = Long.getLong("corona.estresse.semente", System.nanoTime());
        LOG.info("Estresse do pull com filtro: semente {}, {}, {} escritores", semente, duracao, ESCRITORES);
        CenarioDeSync c = new CenarioDeSync(jdbc, usuarios, estoque, remessas, conferencias, vendas);
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste w = usuarios.criarVendedor();
        usuarios.token(v);
        usuarios.token(w);
        UUID cliente = c.cliente(null);
        List<UUID> pecasIniciais = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pecasIniciais.add(c.peca(new BigDecimal("25.00"), 100_000));
        }

        Instant fim = Instant.now().plus(duracao);
        AtomicBoolean parar = new AtomicBoolean();
        AtomicInteger operacoes = new AtomicInteger();
        AtomicInteger recusadas = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(ESCRITORES + 1);
        List<Future<?>> escritores = new ArrayList<>();
        for (int e = 0; e < ESCRITORES; e++) {
            Random r = new Random(semente + e);
            escritores.add(executor.submit(() -> {
                List<UUID> pecas = new ArrayList<>(pecasIniciais);
                Map<UUID, UsuarioDeTeste> conferidas = new HashMap<>();
                while (Instant.now().isBefore(fim)) {
                    UsuarioDeTeste quem = r.nextBoolean() ? v : w;
                    UUID peca = pecas.get(r.nextInt(pecas.size()));
                    try {
                        switch (r.nextInt(8)) {
                            case 0 -> pecas.add(c.peca(new BigDecimal("12.00"), 100_000));
                            case 1 -> c.mudarPreco(peca);
                            case 2 -> c.trocarFoto(peca);
                            case 3 -> c.acrescentarCodigo(peca);
                            case 4 -> c.excluirUmCodigoSecundario(peca);
                            case 5 -> {
                                // Peça (às vezes recém-criada, às vezes antiga) entrando numa maleta enviada.
                                UUID m = c.maleta(quem, Map.of(peca, 1 + r.nextInt(2), pecas.get(r.nextInt(pecas.size())), 1));
                                if (r.nextBoolean()) {
                                    c.conferir(quem, m);
                                    conferidas.put(m, quem);
                                }
                            }
                            case 6 -> conferidas.entrySet().stream().findAny().ifPresent(m -> {
                                UUID item = jdbc.queryForList("SELECT produto_id FROM corona.itens_remessa WHERE remessa_id = ?",
                                        UUID.class, m.getKey()).get(0);
                                if (c.saldo(m.getKey(), item) > 0) {
                                    c.vender(m.getValue(), m.getKey(), cliente, item);
                                }
                            });
                            default -> c.rascunho(quem, Map.of(peca, 1)); // rascunho nunca vai
                        }
                        operacoes.incrementAndGet();
                    } catch (RuntimeException ex) {
                        recusadas.incrementAndGet(); // corrida esperada entre escritores (ex.: duas fotos principais)
                    }
                }
                return null;
            }));
        }
        CopiaLocal copia = new CopiaLocal();
        Random r = new Random(semente);
        Future<?> leitor = executor.submit(() -> {
            while (!parar.get()) {
                copia.aplicar(pull.puxar(copia.cursor, 1 + r.nextInt(80), new FiltroPorVendedor(v.vendedorId())));
            }
            return null;
        });
        for (Future<?> f : escritores) {
            f.get(duracao.plusMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
        }
        parar.set(true);
        leitor.get(1, TimeUnit.MINUTES);
        executor.shutdown();

        copia.puxarTudo(pull, v.vendedorId(), 1000);
        LOG.info("Estresse do pull com filtro: {} operações, {} recusadas por corrida", operacoes.get(), recusadas.get());
        assertThat(operacoes.get()).isPositive();
        Map<String, Map<UUID, CenarioDeSync.Linha>> esperado = CenarioDeSync.oraculo(jdbc, v.vendedorId());
        Map<String, Map<UUID, CenarioDeSync.Linha>> tem = CenarioDeSync.medidas(copia);
        for (String tabela : esperado.keySet()) {
            assertThat(tem.getOrDefault(tabela, Map.of())).as("semente %d: tabela %s", semente, tabela)
                    .isEqualTo(esperado.get(tabela));
        }
        assertThat(tem.keySet()).as("semente %d", semente).isSubsetOf(esperado.keySet());
    }
}
