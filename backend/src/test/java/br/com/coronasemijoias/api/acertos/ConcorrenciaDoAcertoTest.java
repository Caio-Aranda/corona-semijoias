package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Concorrência do acerto (tarefa 7.4; ADR-0012, item 13; ajuste 1 da aprovação): threads de verdade contra o PostgreSQL, com
 * tempo limite de 10 s contra deadlock. Venda, devolução, troca e envio de remessa não passam entre o início do acerto e a
 * trava das maletas: ou entram antes (e o acerto leva em conta) ou são recusados; nunca os dois, nunca espera em volta.
 */
class ConcorrenciaDoAcertoTest extends ApoioDoAcerto {

    private static final int TEMPO_LIMITE_S = 10;
    private static final int RODADAS = 5;

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

    private Resposta chamar(String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
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

    private Callable<Resposta> iniciarEm(UsuarioDeTeste v, UUID semana, UUID id) {
        return () -> chamar(ACERTOS_DA_DONA, dona(), mapa("id", id, "vendedorId", v.vendedorId(), "semanaId", semana));
    }

    private Callable<Resposta> leituraEm(UUID acerto, UUID peca, int quantidade, String estado) {
        return () -> chamar(ACERTOS_DA_DONA + "/" + acerto + "/leituras", dona(),
                mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", quantidade, "estadoPeca", estado));
    }

    private int vendasNaMaleta(UUID maleta) {
        return contar("SELECT count(*) FROM corona.vendas WHERE remessa_id = ?", maleta);
    }

    /** Nenhuma venda gravada depois do início numa maleta que ficou em acerto (a única exceção é a do envio, que não é testada aqui). */
    private void nenhumaVendaDepoisDoInicio(UUID maleta) {
        assertThat(contar("""
                SELECT count(*) FROM corona.vendas v JOIN corona.remessas r ON r.id = v.remessa_id
                  JOIN corona.acertos a ON a.id = r.acerto_id
                 WHERE r.id = ? AND v.vendida_em >= a.iniciado_em""", maleta)).isZero();
    }

    @Test
    void venderEIniciarNaMesmaMaletaOuAVendaEntraAntesOuERecusada() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID cliente = cliente(v);
            UUID peca = peca("50.00", 10);
            UUID semana = semana();
            UUID maleta = maletaConferida(v, semana, peca, 5);
            como(v);
            UUID acerto = UUID.randomUUID();
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar(VENDAS, como(v), mapa("id", UUID.randomUUID(), "remessaId", maleta, "clienteId", cliente,
                            "itens", List.of(linha(peca, 1)), "pagamentos", List.of(pagamento("50.00", "DINHEIRO")))),
                    iniciarEm(v, semana, acerto)));
            assertThat(r.get(1).status()).isEqualTo(201);
            assertThat(r.get(0).status()).isIn(201, 409);
            if (r.get(0).status() == 409) {
                assertThat(r.get(0).codigo()).isEqualTo("MALETA_EM_ACERTO");
                assertThat(vendasNaMaleta(maleta)).isZero();
                assertThat(saldoDaMaleta(maleta, peca)).isEqualTo(5);
            } else {
                assertThat(vendasNaMaleta(maleta)).isEqualTo(1);
                assertThat(saldoDaMaleta(maleta, peca)).isEqualTo(4);
            }
            assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
            nenhumaVendaDepoisDoInicio(maleta);
        }
    }

    @Test
    void devolucaoEIniciarNaMesmaMaletaOuAEntraAntesOuERecusada() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID cliente = cliente(v);
            UUID peca = peca("50.00", 10);
            UUID semana = semana();
            UUID maleta = maletaConferida(v, semana, peca, 5);
            UUID venda = vender(v, maleta, cliente, peca, 2, "100.00");
            como(v);
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar("/api/vendedor/devolucoes", como(v), mapa("id", UUID.randomUUID(), "vendaId", venda,
                            "motivo", "ARREPENDIMENTO", "formaReembolso", "PIX",
                            "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA")))),
                    iniciarEm(v, semana, UUID.randomUUID())));
            assertThat(r.get(1).status()).isEqualTo(201);
            assertThat(r.get(0).status()).isIn(201, 409);
            int devolucoes = contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda);
            if (r.get(0).status() == 409) {
                assertThat(r.get(0).codigo()).isEqualTo("MALETA_EM_ACERTO");
                assertThat(devolucoes).isZero();
            } else {
                assertThat(devolucoes).isEqualTo(1);
            }
            assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
        }
    }

    @Test
    void trocaEIniciarOuATrocaEntraAntesOuERecusadaSemDeadlock() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID cliente = cliente(v);
            UUID a = peca("50.00", 10);
            UUID b = peca("50.00", 10);
            UUID semana = semana();
            UUID maletaA = maletaConferida(v, semana, a, 5);
            UUID maletaB = maletaConferida(v, semana(), b, 5);
            UUID venda = vender(v, maletaA, cliente, a, 1, "50.00");
            como(v);
            var pedido = mapa("id", UUID.randomUUID(), "creditoId", UUID.randomUUID(), "pagamentoDoCreditoId", UUID.randomUUID(),
                    "devolucao", mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo", "ARREPENDIMENTO",
                            "itens", List.of(mapa("produtoId", a, "quantidade", 1, "estado", "BOA"))),
                    "venda", mapa("id", UUID.randomUUID(), "remessaId", maletaB, "itens", List.of(linha(b, 1)),
                            "pagamentos", List.of()));
            List<Resposta> r = emParalelo(List.of(() -> chamar("/api/vendedor/trocas", como(v), pedido),
                    iniciarEm(v, semana, UUID.randomUUID())));
            assertThat(r.get(1).status()).isEqualTo(201);
            assertThat(r.get(0).status()).as("troca: %s", r.get(0).corpo()).isIn(201, 409);
            if (r.get(0).status() == 409) {
                assertThat(r.get(0).codigo()).isEqualTo("MALETA_EM_ACERTO");
                assertThat(contar("SELECT count(*) FROM corona.trocas WHERE vendedor_id = ?", v.vendedorId())).isZero();
            } else {
                assertThat(contar("SELECT count(*) FROM corona.trocas WHERE vendedor_id = ?", v.vendedorId())).isEqualTo(1);
            }
            assertThat(statusDaMaleta(maletaA)).isEqualTo("EM_ACERTO");
            assertThat(statusDaMaleta(maletaB)).isEqualTo("EM_ACERTO");
        }
    }

    @Test
    void enviarRemessaEIniciarNuncaDeixamMaletaEnviadaForaDoAcerto() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID peca = peca("50.00", 20);
            UUID semana = semana();
            UUID maleta = maletaConferida(v, semana, peca, 5);
            UUID rascunho = rascunho(v, semana(), "REPOSICAO", peca, 2);
            List<Resposta> r = emParalelo(List.of(() -> chamar(REMESSAS + "/" + rascunho + "/enviar", dona(), null),
                    iniciarEm(v, semana, UUID.randomUUID())));
            assertThat(r.get(1).status()).isEqualTo(201);
            assertThat(r.get(0).status()).isIn(201, 409);
            if (r.get(0).status() == 409) {
                assertThat(r.get(0).codigo()).isEqualTo("VENDEDOR_EM_ACERTO");
                assertThat(statusDaMaleta(rascunho)).isEqualTo("RASCUNHO");
            } else {
                // A remessa entrou antes e foi ligada ao acerto.
                assertThat(statusDaMaleta(rascunho)).isEqualTo("EM_ACERTO");
            }
            assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
            assertThat(contar("SELECT count(*) FROM corona.remessas WHERE vendedor_id = ? AND status IN ('ENVIADA', 'CONFERIDA')",
                    v.vendedorId())).isZero();
        }
    }

    @Test
    void doisIniciarDoMesmoVendedorComIdsDiferentesUmEntraEOOutroRecebe409() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID peca = peca("50.00", 10);
            UUID semana = semana();
            maletaConferida(v, semana, peca, 5);
            List<Resposta> r = emParalelo(List.of(iniciarEm(v, semana, UUID.randomUUID()), iniciarEm(v, semana, UUID.randomUUID())));
            assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(201, 409);
            assertThat(r.stream().filter(x -> x.status() == 409).findFirst().orElseThrow().codigo()).isEqualTo("ACERTO_EM_ANDAMENTO");
            assertThat(contar("SELECT count(*) FROM corona.acertos WHERE vendedor_id = ? AND status = 'EM_ACERTO'", v.vendedorId()))
                    .isEqualTo(1);
        }
    }

    @Test
    void doisIniciarComOMesmoIdGravamUmSoAcertoUmCreatedEUmOk() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID peca = peca("50.00", 10);
            UUID semana = semana();
            UUID maleta = maletaConferida(v, semana, peca, 5);
            UUID id = UUID.randomUUID();
            List<Resposta> r = emParalelo(List.of(iniciarEm(v, semana, id), iniciarEm(v, semana, id)));
            assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(201, 200);
            assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ?", id)).isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM corona.remessas WHERE acerto_id = ?", id)).isEqualTo(1);
            assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
        }
    }

    @Test
    void leituraEDesistirOuALeituraEntraAntesOuERecusada() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID peca = peca("50.00", 10);
            UUID semana = semana();
            UUID maleta = maletaConferida(v, semana, peca, 5);
            UUID acerto = iniciar(v, semana);
            List<Resposta> r = emParalelo(List.of(leituraEm(acerto, peca, 1, "BOA"),
                    () -> chamar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "Teste."))));
            assertThat(r.get(1).status()).isEqualTo(201);
            assertThat(r.get(0).status()).isIn(201, 409);
            int leituras = contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto);
            if (r.get(0).status() == 409) {
                assertThat(r.get(0).codigo()).isEqualTo("ACERTO_NAO_ESTA_EM_ANDAMENTO");
                assertThat(leituras).isZero();
            } else {
                assertThat(leituras).isEqualTo(1);
            }
            assertThat(statusDaMaleta(maleta)).isEqualTo("CONFERIDA");
        }
    }

    @Test
    void duasLeiturasNegativasAoMesmoTempoNuncaDeixamASomaNegativa() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID peca = peca("50.00", 10);
            UUID semana = semana();
            maletaConferida(v, semana, peca, 5);
            UUID acerto = iniciar(v, semana);
            postar(ACERTOS_DA_DONA + "/" + acerto + "/leituras", dona(),
                    mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 3, "estadoPeca", "BOA")).andExpect(status().isCreated());
            List<Resposta> r = emParalelo(List.of(leituraEm(acerto, peca, -2, "BOA"), leituraEm(acerto, peca, -2, "BOA")));
            assertThat(r).extracting(Resposta::status).containsExactlyInAnyOrder(201, 409);
            assertThat(r.stream().filter(x -> x.status() == 409).findFirst().orElseThrow().codigo())
                    .isEqualTo("LEITURA_DEIXA_CONTAGEM_NEGATIVA");
            assertThat(contar("SELECT sum(quantidade) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto)).isEqualTo(1);
        }
    }

    @Test
    void estornoDaDonaELeituraAoMesmoTempoNaMaletaEmAcertoNaoTravamNemFalham() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            UUID cliente = cliente(v);
            UUID peca = peca("50.00", 10);
            UUID semana = semana();
            UUID maleta = maletaConferida(v, semana, peca, 5);
            UUID venda = vender(v, maleta, cliente, peca, 2, "100.00");
            UUID acerto = iniciar(v, semana);
            List<Resposta> r = emParalelo(List.of(
                    () -> chamar("/api/dona/vendas/" + venda + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Sobra.")),
                    leituraEm(acerto, peca, 5, "BOA")));
            assertThat(r).extracting(Resposta::status).containsExactly(201, 201);
            assertThat(saldoDaMaleta(maleta, peca)).isEqualTo(5);
        }
    }
}
