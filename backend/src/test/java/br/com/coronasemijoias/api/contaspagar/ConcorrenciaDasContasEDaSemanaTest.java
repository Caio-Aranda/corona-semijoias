package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Concorrência das contas a pagar e da semana (tarefa 7.7; ADR-0012, item 13): threads paralelas, com tempo limite de 10 segundos
 * (uma espera em volta estoura o tempo). Fechar a semana contra iniciar acerto e contra lançamento de caixa, dois pagamentos que
 * juntos passam do saldo e desfazer a compra contra pagar a parcela.
 */
class ConcorrenciaDasContasEDaSemanaTest extends ApoioDasContasAPagar {

    private static final AtomicInteger SEQUENCIA = new AtomicInteger(1);
    private static final int RODADAS = 12;

    private UUID semanaDoPassado() throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = LocalDate.of(1980, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar("/api/dona/semanas", mapa("numero", 95_000 + n, "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())).get("id").asString());
    }

    /** Roda as duas tarefas ao mesmo tempo (largada juntas) e devolve o status HTTP de cada uma; falha se passar de 10 segundos. */
    private List<Integer> emParalelo(List<Callable<Integer>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> t : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return t.call();
                }));
            }
            largada.countDown();
            List<Integer> resultados = new ArrayList<>();
            for (Future<Integer> f : futuros) {
                resultados.add(f.get(10, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    private int status(org.springframework.test.web.servlet.ResultActions r) {
        return r.andReturn().getResponse().getStatus();
    }

    @Test
    void fecharSemanaContraIniciarAcertoNuncaDeixaAcertoEmAndamentoEmSemanaFechada() throws Exception {
        for (int i = 0; i < RODADAS; i++) {
            UUID semana = semanaDoPassado();
            UsuarioDeTeste vendedor = usuarios.criarVendedor();
            List<Integer> r = emParalelo(List.of(
                    () -> status(postar("/api/dona/semanas/" + semana + "/fechar", mapa("id", UUID.randomUUID()))),
                    () -> status(postar("/api/dona/acertos", mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana)))));
            assertThat(contar("""
                    SELECT count(*) FROM corona.acertos a JOIN corona.semanas s ON s.id = a.semana_id
                     WHERE s.id = ? AND a.status = 'EM_ACERTO' AND s.situacao = 'FECHADA'""", semana)).as("rodada " + i + ": " + r).isZero();
            // Ou o acerto entrou e o fechar foi recusado, ou o fechar entrou e o acerto foi recusado: nunca os dois.
            assertThat(r.get(0) == 201 && r.get(1) == 201).as("os dois deram certo na rodada " + i).isFalse();
            assertThat(r.get(0) == 409 && r.get(1) == 409).as("os dois recusados na rodada " + i).isFalse();
        }
    }

    @Test
    void fecharSemanaContraLancamentoManualNuncaDeixaLancamentoNovoEmSemanaFechada() throws Exception {
        UUID categoria = UUID.randomUUID();
        postar("/api/dona/caixa/categorias", mapa("id", categoria, "nome", "Cat " + categoria, "tipo", "SAIDA")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        for (int i = 0; i < RODADAS; i++) {
            UUID semana = semanaDoPassado();
            LocalDate dia = jdbc.queryForObject("SELECT inicio FROM corona.semanas WHERE id = ?", LocalDate.class, semana).plusDays(1);
            UUID lancamento = UUID.randomUUID();
            List<Integer> r = emParalelo(List.of(
                    () -> status(postar("/api/dona/semanas/" + semana + "/fechar", mapa("id", UUID.randomUUID()))),
                    () -> status(postar("/api/dona/caixa/lancamentos", mapa("id", lancamento, "tipo", "SAIDA", "valor", "1.00", "forma", "DINHEIRO",
                            "categoriaId", categoria, "data", dia, "descricao", "Concorrência")))));
            assertThat(r.get(0)).as("o fechar sempre fecha (lançamento não impede): " + r).isEqualTo(201);
            assertThat(r.get(1)).as("rodada " + i).isIn(201, 409);
            boolean entrou = contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE id = ?", lancamento) == 1;
            assertThat(entrou).isEqualTo(r.get(1) == 201);
            if (entrou) {
                // Entrou antes do fechamento: o lançamento é do mesmo instante ou anterior ao do fechamento.
                assertThat(contar("""
                        SELECT count(*) FROM corona.lancamentos_caixa l, corona.fechamentos_semana f
                         WHERE l.id = ? AND f.semana_id = ? AND l.lancado_em <= f.fechada_em""", lancamento, semana)).isEqualTo(1);
            }
        }
    }

    @Test
    void doisPagamentosQueJuntosPassamDoSaldoSoUmEntra() throws Exception {
        for (int i = 0; i < RODADAS; i++) {
            UUID conta = contasDe(compra(fornecedor(), "100.00"), 1, LocalDate.of(2027, 6, 10)).get(0);
            List<Integer> r = emParalelo(List.of(
                    () -> status(pagar(conta, UUID.randomUUID(), "60.00", "PIX")),
                    () -> status(pagar(conta, UUID.randomUUID(), "60.00", "DINHEIRO"))));
            assertThat(r).as("rodada " + i).containsExactlyInAnyOrder(201, 422);
            assertThat(situacaoDaConta(conta)).startsWith("PARCIAL:60.00");
            assertThat(contar("SELECT count(*) FROM corona.baixas_pagar WHERE conta_id = ?", conta)).isEqualTo(1);
            assertThat(contar("""
                    SELECT count(*) FROM corona.lancamentos_caixa l JOIN corona.baixas_pagar b ON b.id = l.origem_id
                     WHERE l.origem_tipo = 'PAGAMENTO_FORNECEDOR' AND b.conta_id = ?""", conta)).isEqualTo(1);
        }
    }

    @Test
    void desfazerACompraContraPagarAParcelaTerminaSemEsperaEmVoltaECoerente() throws Exception {
        for (int i = 0; i < RODADAS; i++) {
            UUID compra = compra(fornecedor(), "100.00");
            List<UUID> contas = contasDe(compra, 2, LocalDate.of(2027, 6, 10));
            UUID pagamento = UUID.randomUUID();
            List<Integer> r = emParalelo(List.of(
                    () -> status(postar(COMPRAS + "/" + compra + "/desfazer", mapa("id", UUID.randomUUID(), "motivo", "Concorrência"))),
                    () -> status(pagar(contas.get(0), pagamento, "50.00", "PIX"))));
            assertThat(r.get(0)).as("desfazer na rodada " + i + ": " + r).isEqualTo(201);
            assertThat(r.get(1)).isIn(201, 409);
            for (UUID conta : contas) {
                Map<String, Object> c = jdbc.queryForMap("SELECT valor, valor_pago, valor_cancelado, status FROM corona.contas_pagar WHERE id = ?", conta);
                BigDecimal valor = (BigDecimal) c.get("valor");
                assertThat(((BigDecimal) c.get("valor_pago")).add((BigDecimal) c.get("valor_cancelado"))).isLessThanOrEqualTo(valor);
                // Compra desfeita: nada fica em aberto.
                assertThat(c.get("status")).as("rodada " + i).isIn("PAGA", "CANCELADA");
            }
            // Pagamento que entrou tem a saída de caixa; o que foi recusado não deixou nada.
            assertThat(contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE origem_tipo = 'PAGAMENTO_FORNECEDOR' AND origem_id = ?", pagamento))
                    .isEqualTo(r.get(1) == 201 ? 1 : 0);
        }
    }
}
