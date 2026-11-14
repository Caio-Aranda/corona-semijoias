package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Concorrência do fechamento (tarefa 7.5; ADR-0012, item 13): threads de verdade contra o PostgreSQL, cinco rodadas, tempo limite
 * de 10 s contra deadlock. Mais a falha no meio do fechamento, que não pode deixar nada gravado.
 */
class ConcorrenciaDoFechamentoTest extends ApoioDoFechamento {

    private static final int TEMPO_LIMITE_S = 10;
    private static final int RODADAS = 5;

    @Autowired
    ServicoDeFechamento servicoDeFechamento;

    private record Resposta(int status, JsonNode corpo) {
        String codigo() {
            JsonNode c = corpo == null ? null : corpo.get("codigo");
            return c == null || c.isNull() ? null : c.asString();
        }
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
            var r = postar(caminho, quem, corpo).andReturn().getResponse();
            String texto = r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            return new Resposta(r.getStatus(), texto.isBlank() ? null : json.readTree(texto));
        } catch (Exception e) {
            return new Resposta(500, null);
        }
    }

    private Callable<Resposta> fecharEm(UUID acerto, Map<String, Object> pedido) {
        return () -> chamar(ACERTOS_DA_DONA + "/" + acerto + "/fechar", dona(), pedido);
    }

    private int linhasDoAcerto(UUID acerto) {
        return contar("""
                SELECT (SELECT count(*) FROM corona.acerto_eventos WHERE acerto_id = ?) + (SELECT count(*) FROM corona.itens_acerto WHERE acerto_id = ?)
                     + (SELECT count(*) FROM corona.comissoes WHERE acerto_id = ?) + (SELECT count(*) FROM corona.pendencias_acerto WHERE acerto_id = ?)""",
                acerto, acerto, acerto, acerto);
    }

    private int perdasNoBanco() {
        return contar("SELECT count(*) FROM corona.movimentos_remessa WHERE tipo = 'PERDA'");
    }

    // --- dois fechamentos do mesmo acerto ---

    @Test
    void doisFechamentosDoMesmoAcertoComOMesmoIdDaUmResultadoSo() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID cliente = cliente(v);
            UUID peca = peca("50.00", 10);
            UUID maleta = maletaConferida(v, semana(), peca, 4);
            vender(v, maleta, cliente, peca, 1, "50.00");
            UUID acerto = iniciar(v, null);
            bipar(acerto, peca, 2, "BOA");
            Map<String, Object> pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(peca, "COBRAR_NO_ACERTO", null)),
                    List.of(), "85.00");
            List<Resposta> r = emParalelo(List.of(fecharEm(acerto, pedido), fecharEm(acerto, pedido)));
            assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(200, 201);
            assertThat(contar("SELECT count(*) FROM corona.pendencias_acerto WHERE acerto_id = ? AND tipo = 'FALTA'", acerto)).isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ? AND tipo = 'PERDA'", maleta)).isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM corona.comissoes WHERE acerto_id = ?", acerto)).isEqualTo(1);
        }
    }

    @Test
    void doisFechamentosComIdsDiferentesUmCriaOOutroRecebe409() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID peca = peca("50.00", 10);
            maletaConferida(v, semana(), peca, 3);
            UUID acerto = iniciar(v, null);
            bipar(acerto, peca, 3, "BOA");
            List<Resposta> r = emParalelo(List.of(
                    fecharEm(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00")),
                    fecharEm(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00"))));
            assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(201, 409);
            assertThat(r.stream().filter(x -> x.status() == 409).findFirst().orElseThrow().codigo()).isEqualTo("ACERTO_JA_FECHADO");
        }
    }

    // --- devolução "para a loja" e estorno contra o fechamento ---

    @Test
    void devolucaoParaALojaContraOFechamentoNuncaViraSobraFalsa() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID cliente = cliente(v);
            UUID peca = peca("50.00", 20);
            UUID m0 = maletaConferida(v, semana(), peca, 1);
            UUID venda0 = vender(v, m0, cliente, peca, 1, "50.00");
            UUID acerto0 = iniciar(v, null);
            fechar(acerto0, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00"));
            maletaConferida(v, semana(), peca, 3);
            UUID acerto = iniciar(v, null);
            bipar(acerto, peca, 3, "BOA");
            Map<String, Object> devolucao = mapa("id", UUID.randomUUID(), "vendaId", venda0, "motivo", "ARREPENDIMENTO", "formaReembolso", "DINHEIRO",
                    "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA")));
            // O fechamento se adapta: ou a devolução entrou antes (esperado 4, contado 3: falta 1) ou fica para o próximo acerto.
            Map<String, Object> pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00");
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar("/api/dona/devolucoes", dona(), devolucao),
                    () -> {
                        Resposta primeira = chamar(ACERTOS_DA_DONA + "/" + acerto + "/fechar", dona(), pedido);
                        if (primeira.status() == 409 && "FECHAMENTO_DESATUALIZADO".equals(primeira.codigo())) {
                            // Entrou a falta de 1 (a devolução chegou antes): a dona decide, confere a prévia e fecha de novo.
                            Map<String, Object> decidido = pedidoDeFechamento(UUID.randomUUID(),
                                    List.of(decisaoDeFalta(peca, "LOJA_ASSUME", "teste")), List.of(), "0.00");
                            Resposta previa = chamar(ACERTOS_DA_DONA + "/" + acerto + "/fechamento/previa", dona(), decidido);
                            return chamar(ACERTOS_DA_DONA + "/" + acerto + "/fechar", dona(), pedidoDeFechamento(UUID.randomUUID(),
                                    List.of(decisaoDeFalta(peca, "LOJA_ASSUME", "teste")), List.of(), previa.corpo().get("liquido").asString()));
                        }
                        return primeira;
                    }));
            assertThat(r.get(0).status()).isIn(201, 409);
            assertThat(r.get(1).status()).isEqualTo(201);
            assertThat(r.get(1).codigo()).isNotEqualTo("SOBRA_NO_ACERTO");
        }
    }

    @Test
    void estornoDaDonaContraOFechamentoOuUmOuOutroNuncaOsDoisSemConta() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID cliente = cliente(v);
            UUID peca = peca("50.00", 20);
            UUID maleta = maletaConferida(v, semana(), peca, 3);
            UUID venda = vender(v, maleta, cliente, peca, 1, "50.00");
            UUID acerto = iniciar(v, null);
            bipar(acerto, peca, 2, "BOA");
            Map<String, Object> pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00");
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar("/api/dona/vendas/" + venda + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "erro")),
                    fecharEm(acerto, pedido)));
            int estorno = r.get(0).status();
            int fechamento = r.get(1).status();
            if (fechamento == 201) {
                // O fechamento levou a venda; o estorno depois da maleta acertada é recusado.
                assertThat(estorno).isEqualTo(409);
                assertThat(statusDaMaleta(maleta)).isEqualTo("ACERTADA");
            } else {
                // O estorno entrou antes: a peça voltou à maleta, o esperado mudou e a dona confere de novo.
                assertThat(estorno).isEqualTo(201);
                assertThat(fechamento).isEqualTo(409);
                assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", acerto)).isEqualTo(1);
            }
        }
    }

    // --- dois vendedores devolvendo as mesmas peças ---

    @Test
    void fechamentosDeDoisVendedoresComAsMesmasPecasEmOrdensCruzadasSemEsperaEmVolta() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID a = peca("50.00", 30);
            UUID b = peca("30.00", 30);
            UsuarioDeTeste v1 = usuarios.criarVendedor();
            UsuarioDeTeste v2 = usuarios.criarVendedor();
            // v1 recebe A e B; v2 recebe B e A (ordens de envio diferentes).
            maletaConferida(v1, semana(), a, 3, b, 3);
            maletaConferida(v2, semana(), b, 4, a, 4);
            UUID acerto1 = iniciar(v1, null);
            UUID acerto2 = iniciar(v2, null);
            bipar(acerto1, a, 3, "BOA");
            bipar(acerto1, b, 3, "BOA");
            bipar(acerto2, b, 4, "BOA");
            bipar(acerto2, a, 4, "BOA");
            int antesA = estoqueAtual(a);
            int antesB = estoqueAtual(b);
            List<Resposta> r = emParalelo(List.of(
                    fecharEm(acerto1, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00")),
                    fecharEm(acerto2, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00"))));
            assertThat(r).extracting(Resposta::status).containsExactly(201, 201);
            assertThat(estoqueAtual(a)).isEqualTo(antesA + 7);
            assertThat(estoqueAtual(b)).isEqualTo(antesB + 7);
        }
    }

    // --- resolver pendência contra o fechamento ---

    @Test
    void resolverPendenciaContraFecharOuUmOuOutro() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID peca = peca("50.00", 20);
            maletaConferida(v, semana(), peca, 2);
            UUID primeiro = iniciar(v, null);
            bipar(primeiro, peca, 1, "BOA");
            fechar(primeiro, pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(peca, "COBRAR_DEPOIS", null)), List.of(), "0.00"));
            UUID falta = jdbc.queryForObject("SELECT id FROM corona.pendencias_acerto WHERE acerto_id = ? AND tipo = 'FALTA'", UUID.class, primeiro);
            UUID outra = peca("50.00", 20);
            maletaConferida(v, semana(), outra, 1);
            UUID segundo = iniciar(v, null);
            bipar(segundo, outra, 1, "BOA");
            Map<String, Object> pedido = mapa("id", UUID.randomUUID(), "faltas", List.of(), "defeitos", List.of(), "cobrancas", List.of(),
                    "pendenciasAntigas", List.of(mapa("pendenciaId", falta, "decisao", "COBRAR_NO_ACERTO")), "liquidoMostrado", "50.00");
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar("/api/dona/pendencias-acerto/" + falta + "/resolver", dona(),
                            mapa("id", UUID.randomUUID(), "acao", "LOJA_ASSUME", "motivo", "perda")),
                    fecharEm(segundo, pedido)));
            String status = jdbc.queryForObject("SELECT status FROM corona.pendencias_acerto WHERE id = ?", String.class, falta);
            if (status.equals("COBRADA")) {
                assertThat(r.get(0).status()).isEqualTo(409);
                assertThat(r.get(1).status()).isEqualTo(201);
            } else {
                assertThat(status).isEqualTo("ASSUMIDA_PELA_LOJA");
                assertThat(r.get(0).status()).isEqualTo(201);
                assertThat(r.get(1).status()).isEqualTo(409);
            }
        }
    }

    // --- falha forçada no meio ---

    @Test
    void falhaDepoisDosMovimentosNaoDeixaNadaGravadoEDepoisFechaNormalmente() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("50.00", 20);
        UUID maleta = maletaConferida(v, semana(), peca, 4);
        vender(v, maleta, cliente, peca, 1, "50.00");
        UUID acerto = iniciar(v, null);
        bipar(acerto, peca, 2, "BOA");
        int estoque = estoqueAtual(peca);
        int perdas = perdasNoBanco();
        int movimentosMaleta = contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ?", maleta);
        int movimentosEstoque = contar("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ?", peca);
        int auditorias = contar("SELECT count(*) FROM corona.auditoria WHERE acao IN ('ACERTO_FECHADO', 'PENDENCIA_DECIDIDA')");
        Map<String, Object> pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(peca, "LOJA_ASSUME", "x")), List.of(), "35.00");

        servicoDeFechamento.definirGanchoDeTeste(() -> {
            throw new IllegalStateException("falha de teste no meio do fechamento");
        });
        try {
            assertThat(chamar(ACERTOS_DA_DONA + "/" + acerto + "/fechar", dona(), pedido).status()).isEqualTo(500);
        } finally {
            servicoDeFechamento.definirGanchoDeTeste(null);
        }
        assertThat(linhasDoAcerto(acerto)).isZero();
        assertThat(perdasNoBanco()).isEqualTo(perdas);
        assertThat(estoqueAtual(peca)).isEqualTo(estoque);
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ?", maleta)).isEqualTo(movimentosMaleta);
        assertThat(contar("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ?", peca)).isEqualTo(movimentosEstoque);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao IN ('ACERTO_FECHADO', 'PENDENCIA_DECIDIDA')")).isEqualTo(auditorias);
        assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", acerto)).isEqualTo(1);

        assertThat(chamar(ACERTOS_DA_DONA + "/" + acerto + "/fechar", dona(), pedido).status()).isEqualTo(201);
        assertThat(statusDaMaleta(maleta)).isEqualTo("ACERTADA");
    }
}
