package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Concorrência do dinheiro do vendedor (tarefa 7.6; ADR-0012, item 13): threads de verdade contra o PostgreSQL, cinco rodadas,
 * tempo limite de 10 s contra deadlock. Vendedor, acerto, produtos e contas (por id), sempre nessa ordem: nenhum caminho trava
 * conta antes do vendedor.
 */
class ConcorrenciaDoDinheiroDoVendedorTest extends ApoioDoFechamento {

    private static final int TEMPO_LIMITE_S = 10;
    private static final int RODADAS = 5;
    private static final String RECEBIMENTOS = "/api/dona/recebimentos-vendedor";

    private record Resposta(int status) {
    }

    private List<Resposta> emParalelo(List<Callable<Resposta>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Resposta>> futuros = new ArrayList<>();
            for (Callable<Resposta> t : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return t.call();
                }));
            }
            largada.countDown();
            List<Resposta> respostas = new ArrayList<>();
            for (Future<Resposta> f : futuros) {
                respostas.add(f.get(TEMPO_LIMITE_S, TimeUnit.SECONDS));
            }
            return respostas;
        } finally {
            pool.shutdownNow();
        }
    }

    private Resposta chamar(String caminho, RequestPostProcessor quem, Object corpo) {
        try {
            return new Resposta(postar(caminho, quem, corpo).andReturn().getResponse().getStatus());
        } catch (Exception e) {
            return new Resposta(500);
        }
    }

    private static long quantos(List<Resposta> r, int status) {
        return r.stream().filter(x -> x.status() == status).count();
    }

    private Callable<Resposta> receber(UsuarioDeTeste v, UUID id, String valor, UUID conta) {
        return () -> chamar(RECEBIMENTOS, dona(), mapa("id", id, "vendedorId", v.vendedorId(), "forma", "DINHEIRO", "valor", valor,
                "contaId", conta));
    }

    private Callable<Resposta> estornar(UUID recebimento) {
        return () -> chamar(RECEBIMENTOS + "/" + recebimento + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "teste"));
    }

    /** Um vendedor com um acerto fechado em que ficou devendo R$ 35,00 numa conta só. Devolve a conta. */
    private UUID contaDe35(UsuarioDeTeste v) throws Exception {
        UUID cliente = cliente(v);
        UUID peca = peca("50.00", 10);
        UUID maleta = maletaConferida(v, semana(), peca, 4);
        vender(v, maleta, cliente, peca, 1, "50.00");
        UUID acerto = iniciar(v, null);
        bipar(acerto, peca, 3, "BOA");
        fechar(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        return jdbc.queryForObject("SELECT id FROM corona.contas_receber WHERE origem_id = ?", UUID.class, acerto);
    }

    private BigDecimal pago(UUID conta) {
        return jdbc.queryForObject("SELECT valor_pago FROM corona.contas_receber WHERE id = ?", BigDecimal.class, conta);
    }

    @Test
    void doisRecebimentosQueJuntosPassamDoSaldoDaContaSoUmEntra() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID conta = contaDe35(v);
            List<Resposta> r = emParalelo(List.of(receber(v, UUID.randomUUID(), "20.00", conta), receber(v, UUID.randomUUID(), "20.00", conta)));
            assertThat(quantos(r, 201)).as("rodada " + rodada).isEqualTo(1);
            assertThat(quantos(r, 409)).isEqualTo(1);
            assertThat(pago(conta)).isEqualByComparingTo("20.00");
            assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE vendedor_id = ?", v.vendedorId())).isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM corona.lancamentos_caixa l JOIN corona.recebimentos_vendedor r ON r.id = l.origem_id WHERE r.vendedor_id = ?",
                    v.vendedorId())).isEqualTo(1);
        }
    }

    @Test
    void recebimentoAvulsoContraFechamentoQuePagaAMesmaContaAntiga() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID conta = contaDe35(v);
            // O segundo acerto do vendedor, pronto para fechar pagando 30,00 da conta antiga.
            UUID cliente = cliente(v);
            UUID peca = peca("50.00", 10);
            UUID maleta = maletaConferida(v, semana(), peca, 4);
            vender(v, maleta, cliente, peca, 1, "50.00");
            UUID acerto = iniciar(v, null);
            bipar(acerto, peca, 3, "BOA");
            LocalDate vencimento = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(30);
            Map<String, Object> pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00");
            pedido.put("pagamento", mapa("restante", mapa("parcelas", 1, "primeiroVencimento", vencimento.toString()),
                    "contasAntigas", List.of(mapa("id", UUID.randomUUID(), "contaId", conta, "forma", "DINHEIRO", "valor", "30.00"))));
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar(ACERTOS_DA_DONA + "/" + acerto + "/fechar", dona(), pedido),
                    receber(v, UUID.randomUUID(), "30.00", conta)));
            assertThat(quantos(r, 201)).as("rodada " + rodada).isEqualTo(1);
            assertThat(quantos(r, 409)).isEqualTo(1);
            // Só um dos dois pagou: a conta nunca passa do que valia e os lançamentos batem com os recebimentos.
            assertThat(pago(conta)).isEqualByComparingTo("30.00");
            assertThat(contar("""
                    SELECT count(*) FROM corona.recebimentos_vendedor r WHERE r.vendedor_id = ?
                       AND (SELECT count(*) FROM corona.lancamentos_caixa l WHERE l.origem_id = r.id) <> 1""", v.vendedorId())).isZero();
            String status = jdbc.queryForObject("SELECT status FROM corona.acertos WHERE id = ?", String.class, acerto);
            assertThat(status).isIn("ACERTADO", "EM_ACERTO");
            if (r.get(0).status() == 409) {
                assertThat(status).isEqualTo("EM_ACERTO");
                assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE acerto_id = ?", acerto)).isZero();
            }
        }
    }

    @Test
    void doisEstornosDoMesmoRecebimentoSoUmVale() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID conta = contaDe35(v);
            UUID recebimento = UUID.randomUUID();
            assertThat(receber(v, recebimento, "10.00", conta).call().status()).isEqualTo(201);
            List<Resposta> r = emParalelo(List.of(estornar(recebimento), estornar(recebimento)));
            assertThat(quantos(r, 201)).as("rodada " + rodada).isEqualTo(1);
            assertThat(quantos(r, 409)).isEqualTo(1);
            assertThat(pago(conta)).isEqualByComparingTo("0.00");
            assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE estorna_id = ?", recebimento)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT coalesce(sum(l.valor), 0) FROM corona.lancamentos_caixa l JOIN corona.recebimentos_vendedor r ON r.id = l.origem_id
                     WHERE r.vendedor_id = ?""", BigDecimal.class, v.vendedorId())).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void recebimentoEEstornoDeVendedoresDiferentesEmOrdensCruzadasNaoTravam() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste a = usuarios.criarVendedor();
            UsuarioDeTeste b = usuarios.criarVendedor();
            UUID contaA = contaDe35(a);
            UUID contaB = contaDe35(b);
            UUID recA = UUID.randomUUID();
            UUID recB = UUID.randomUUID();
            assertThat(receber(a, recA, "10.00", contaA).call().status()).isEqualTo(201);
            assertThat(receber(b, recB, "10.00", contaB).call().status()).isEqualTo(201);
            List<Resposta> r = emParalelo(List.of(receber(a, UUID.randomUUID(), "5.00", contaA), estornar(recB),
                    receber(b, UUID.randomUUID(), "5.00", contaB), estornar(recA)));
            assertThat(quantos(r, 201)).as("rodada " + rodada).isEqualTo(4);
            assertThat(pago(contaA)).isEqualByComparingTo("5.00");
            assertThat(pago(contaB)).isEqualByComparingTo("5.00");
        }
    }
}
