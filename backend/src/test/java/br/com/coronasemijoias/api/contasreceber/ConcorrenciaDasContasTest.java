package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Concorrência das contas a receber (tarefa 7.3; ADR-0012, item 13): threads de verdade contra o PostgreSQL, com tempo
 * limite de 10 s contra deadlock. A ordem única de travas (remessa, venda, créditos, parcelas por id) é o que se prova aqui.
 * Escrito antes do código dos passos que ele conferem.
 */
class ConcorrenciaDasContasTest extends ApoioDasContas {

    private static final int TEMPO_LIMITE_S = 10;

    private record Resposta(int status, JsonNode corpo) {
        String codigo() {
            JsonNode c = corpo == null ? null : corpo.get("codigo");
            return c == null || c.isNull() ? null : c.asString();
        }
    }

    /** Roda as tarefas ao mesmo tempo (todas esperam a largada) e devolve as respostas; deadlock estoura o tempo limite. */
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

    private Resposta chamar(String caminho, org.springframework.test.web.servlet.request.RequestPostProcessor quem, Object corpo)
            throws Exception {
        org.springframework.mock.web.MockHttpServletResponse r;
        try {
            r = postar(caminho, quem, corpo).andReturn().getResponse();
        } catch (Exception e) {
            // Uma falha não prevista que o MockMvc deixa passar equivale ao 500 da API.
            return new Resposta(500, null);
        }
        String texto = r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Resposta(r.getStatus(), texto.isBlank() ? null : json.readTree(texto));
    }

    private Callable<Resposta> receberEm(APrazo c, UUID id, String valor) {
        return () -> chamar(RECEBIMENTOS.formatted(c.venda()), como(c.vendedor()), mapa("id", id, "valor", valor, "forma", "PIX"));
    }

    @Test
    void doisRecebimentosQueJuntosPassamDoQueFaltaUmEntraEOOutroRecebe409() throws Exception {
        for (int rodada = 0; rodada < 4; rodada++) {
            APrazo c = vendaAPrazo("100.00", null, 2);
            List<Resposta> r = emParalelo(List.of(receberEm(c, UUID.randomUUID(), "60.00"), receberEm(c, UUID.randomUUID(), "60.00")));
            assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(201, 409);
            assertThat(r.stream().filter(x -> x.status() == 409).findFirst().orElseThrow().codigo())
                    .isEqualTo("RECEBIMENTO_ACIMA_DO_EM_ABERTO");
            BigDecimal soma = jdbc.queryForObject("SELECT sum(valor_pago) FROM corona.contas_receber WHERE venda_id = ?",
                    BigDecimal.class, c.venda());
            assertThat(soma).isEqualByComparingTo("60.00");
            assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND posterior", c.venda())).isEqualTo(1);
        }
    }

    @Test
    void oMesmoRecebimentoDuasVezesAoMesmoTempoGravaUmSoComUmCreatedEUmOk() throws Exception {
        for (int rodada = 0; rodada < 4; rodada++) {
            APrazo c = vendaAPrazo("100.00", null, 2);
            UUID id = UUID.randomUUID();
            List<Resposta> r = emParalelo(List.of(receberEm(c, id, "30.00"), receberEm(c, id, "30.00")));
            assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(201, 200);
            assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ?", id)).isEqualTo(1);
            assertThat(baixas(c.venda())).isEqualTo(1);
            assertThat(pagos(c.venda())).containsExactly("30.00", "0.00");
        }
    }

    @Test
    void recebimentoEEstornoDaVendaAoMesmoTempoNuncaDeixamPagamentoEmVendaEstornada() throws Exception {
        for (int rodada = 0; rodada < 6; rodada++) {
            APrazo c = vendaDeCemEmTres();
            UUID recebimento = UUID.randomUUID();
            List<Resposta> r = emParalelo(List.of(receberEm(c, recebimento, "40.00"),
                    () -> chamar(VENDAS + "/" + c.venda() + "/estorno", como(c.vendedor()),
                            mapa("id", UUID.randomUUID(), "motivo", "Desistiu"))));
            Resposta receb = r.get(0);
            Resposta estorno = r.get(1);
            assertThat(estorno.status()).isEqualTo(201);
            assertThat(receb.status()).isIn(201, 409);
            if (receb.status() == 409) {
                // O estorno entrou primeiro: o recebimento nem foi gravado.
                assertThat(receb.codigo()).isEqualTo("VENDA_JA_ESTORNADA");
                assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ?", recebimento)).isZero();
            } else {
                // O recebimento entrou primeiro: o estorno o reembolsou e cancelou o resto.
                assertThat(jdbc.queryForObject("""
                        SELECT sum(valor) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO'""",
                        BigDecimal.class, c.venda())).isEqualByComparingTo("-40.00");
            }
            // Em nenhum dos dois casos sobra nada em aberto, e a venda está estornada com o líquido zerado.
            assertThat(jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, c.venda())).isEqualTo("ESTORNADA");
            assertThat(jdbc.queryForObject("""
                    SELECT coalesce(sum(valor - valor_pago - valor_cancelado), 0) FROM corona.contas_receber WHERE venda_id = ?""",
                    BigDecimal.class, c.venda())).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void estornoDoRecebimentoEDevolucaoDaMesmaVendaPagaNuncaPassamAsDuas() throws Exception {
        for (int rodada = 0; rodada < 6; rodada++) {
            APrazo c = vendaAPrazo("100.00", null, 2);
            UUID recebimento = receberOk(c, "100.00");
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar(ESTORNO_DO_VENDEDOR.formatted(recebimento), como(c.vendedor()),
                            mapa("id", UUID.randomUUID(), "motivo", "Erro")),
                    () -> chamar("/api/vendedor/devolucoes", como(c.vendedor()), mapa("id", UUID.randomUUID(),
                            "vendaId", c.venda(), "motivo", "ARREPENDIMENTO", "formaReembolso", "PIX",
                            "itens", List.of(mapa("produtoId", c.peca(), "quantidade", 1, "estado", "BOA"))))));
            Resposta estorno = r.get(0);
            Resposta devolucao = r.get(1);
            assertThat(estorno.status() == 201 && devolucao.status() == 201).as("as duas passaram na rodada %d", rodada).isFalse();
            assertThat(estorno.status() == 201 || devolucao.status() == 201).isTrue();
            if (devolucao.status() == 201) {
                assertThat(estorno.codigo()).isEqualTo("VENDA_COM_DEVOLUCAO");
            } else {
                assertThat(devolucao.codigo()).isEqualTo("VENDA_A_PRAZO_SEM_DEVOLUCAO");
            }
            // O dinheiro nunca volta duas vezes: o líquido da venda nunca fica negativo.
            assertThat(jdbc.queryForObject("""
                    SELECT sum(valor) FROM corona.pagamentos_cliente WHERE venda_id = ?
                       AND tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO', 'REEMBOLSO')""", BigDecimal.class, c.venda()).signum())
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void doisEstornosDoMesmoRecebimentoComIdsDiferentesAoMesmoTempoSoUmEntra() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID recebimento = receberOk(c, "40.00");
        List<Resposta> r = emParalelo(List.of(
                () -> chamar(ESTORNO_DO_VENDEDOR.formatted(recebimento), como(c.vendedor()), mapa("id", UUID.randomUUID(), "motivo", "A")),
                () -> chamar(ESTORNO_DO_VENDEDOR.formatted(recebimento), como(c.vendedor()), mapa("id", UUID.randomUUID(), "motivo", "B"))));
        assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(201, 409);
        assertThat(pagos(c.venda())).containsExactly("0.00", "0.00", "0.00");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE estorna_id = ?", recebimento)).isEqualTo(1);
    }

    @Test
    void falhaDepoisDasBaixasEAntesDosAtalhosNaoDeixaNadaGravado() throws Exception {
        APrazo c = vendaAPrazo("100.00", null, 2);
        UUID conta = (UUID) parcelas(c.venda()).get(0).get("id");
        String funcao = "corona.teste_falha_da_conta_" + conta.toString().replace('-', '_');
        try (Connection banco = conectarComoMigracao(); Statement sql = banco.createStatement()) {
            sql.execute("CREATE FUNCTION " + funcao + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'falha forçada pelo teste'; END; $$");
            sql.execute("CREATE TRIGGER teste_falha BEFORE UPDATE ON corona.contas_receber FOR EACH ROW WHEN (OLD.id = '" + conta
                    + "') EXECUTE FUNCTION " + funcao + "()");
            banco.commit();
        }
        try {
            UUID id = UUID.randomUUID();
            Resposta r = emParalelo(List.of(receberEm(c, id, "30.00"))).get(0);
            assertThat(r.status()).isGreaterThanOrEqualTo(500);
            // Nada ficou: nem o pagamento, nem a baixa, nem o atalho.
            assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ?", id)).isZero();
            assertThat(baixas(c.venda())).isZero();
            assertThat(pagos(c.venda())).containsExactly("0.00", "0.00");
        } finally {
            try (Connection banco = conectarComoMigracao(); Statement sql = banco.createStatement()) {
                sql.execute("DROP TRIGGER teste_falha ON corona.contas_receber");
                sql.execute("DROP FUNCTION " + funcao + "()");
                banco.commit();
            }
        }
        // Sem a falha, o mesmo recebimento entra normalmente.
        receberOk(c, "30.00");
        assertThat(pagos(c.venda())).containsExactly("30.00", "0.00");
    }

    @Test
    void muitosRecebimentosPequenosAoMesmoTempoSomamExatamenteSemDeadlock() throws Exception {
        APrazo c = vendaAPrazo("100.00", null, 4);
        List<Callable<Resposta>> tarefas = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tarefas.add(receberEm(c, UUID.randomUUID(), "10.00"));
        }
        List<Resposta> r = emParalelo(tarefas);
        assertThat(r).allSatisfy(x -> assertThat(x.status()).isEqualTo(201));
        assertThat(situacoes(c.venda())).containsOnly("PAGA");
        assertThat(contar("SELECT count(*) FROM corona.baixas_receber b JOIN corona.contas_receber x ON x.id = b.conta_id WHERE x.venda_id = ? AND b.valor > 0",
                c.venda())).isGreaterThanOrEqualTo(10);
        // O 11º já não tem o que receber.
        receber(c, UUID.randomUUID(), "0.01", "PIX").andExpect(status().isConflict());
    }
}
