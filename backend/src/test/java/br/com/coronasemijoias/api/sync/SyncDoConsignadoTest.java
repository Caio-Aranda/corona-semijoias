package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * O envio das operações do consignado ao cliente (tarefa 9.1; ADR-0016, item 13): cada tipo pelo push, repetição, mesmo id com
 * outro conteúdo, dependência recusada, o cadastro do cliente no formato 2 (e o formato 1 ainda aceito) e a concorrência.
 * PostgreSQL de verdade. Escritos antes do código.
 */
class SyncDoConsignadoTest extends ApoioDoEnvioDaFase6 {

    @Autowired
    ServicoDeEnvio envio;

    private record Cena(UsuarioDeTeste v, UUID peca, UUID outra, UUID maleta, UUID cliente, UUID semana) {
    }

    private static Map<String, Object> opF(UUID id, String tipo, int formato, Map<String, Object> dados) {
        return mapa("operacaoId", id, "tipo", tipo, "formato", formato, "criadoEmLocal", Instant.now().toString(), "dados", dados);
    }

    private Instant agora() {
        return relogio.instant().minusSeconds(30);
    }

    private static Map<String, Object> clienteV2(UUID id) {
        return mapa("id", id, "nome", "Maria Cliente", "documento", "CI 123456", "documentoTipo", "CI", "telefone", "11999990000",
                "enderecoLogradouro", "Rua A", "enderecoNumero", "10", "enderecoBairro", "Centro", "enderecoCidade", "Foz",
                "enderecoUf", "PR", "comissaoPercentual", "30.00");
    }

    private Cena cena() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("100.00", 20);
        UUID outra = peca("50.00", 20);
        UUID maleta = maletaEnviada(v, peca, 10, outra, 10);
        UUID cliente = UUID.randomUUID();
        UUID semana = UUID.randomUUID();
        JsonNode r = enviar(v, List.of(opF(semana, "SEMANA_NOVA", 1, mapa("id", semana, "numero", 1, "abertaEm", agora().toString())),
                opF(cliente, "CLIENTE_NOVO", 2, clienteV2(cliente))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        return new Cena(v, peca, outra, maleta, cliente, semana);
    }

    /** Como {@link #cena()}, com a peça principal custando o preço dado (o servidor usa o preço da maleta). */
    private Cena cenaComPeca(String preco) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca(preco, 20);
        UUID maleta = maletaEnviada(v, peca, 10);
        UUID cliente = UUID.randomUUID();
        UUID semana = UUID.randomUUID();
        JsonNode r = enviar(v, List.of(opF(semana, "SEMANA_NOVA", 1, mapa("id", semana, "numero", 1, "abertaEm", agora().toString())),
                opF(cliente, "CLIENTE_NOVO", 2, clienteV2(cliente))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        return new Cena(v, peca, peca, maleta, cliente, semana);
    }

    private static Map<String, Object> itemJson(UUID produto, UUID maleta, int q, String preco) {
        return mapa("id", UUID.randomUUID(), "produtoId", produto, "remessaId", maleta, "quantidade", q, "precoUnitario", preco,
                "movimentoId", UUID.randomUUID());
    }

    private Map<String, Object> notaOp(Cena c, UUID id, int seq, List<Map<String, Object>> itens) {
        return opF(id, "NOTA_CONSIGNADO", 1, mapa("id", id, "semanaId", c.semana(), "sequencia", seq, "clienteId", c.cliente(),
                "dataCobranca", "2030-01-10", "saidaEm", agora().toString(), "itens", itens));
    }

    private Map<String, Object> acertoOp(Cena c, UUID id, UUID nota, int seqRecibo, String pago) {
        return opF(id, "ACERTO_NOTA", 1, mapa("id", id, "notaId", nota, "semanaId", c.semana(), "acertadoEm", agora().toString(),
                "comissaoPercentual", "30", "devolvidos", List.of(),
                "recibo", pago == null ? null : mapa("id", UUID.randomUUID(), "sequencia", seqRecibo,
                        "formas", List.of(mapa("forma", "PIX", "valor", pago)))));
    }

    @Test
    void todosOsTiposPeloPushEARepeticaoDaJaRecebidoSemDuplicar() throws Exception {
        Cena c = cena();
        UUID nota = UUID.randomUUID();
        UUID acerto = UUID.randomUUID();
        UUID repos = UUID.randomUUID();
        UUID troca = UUID.randomUUID();
        UUID remarca = UUID.randomUUID();
        UUID pagamento = UUID.randomUUID();
        UUID estornoRecibo = UUID.randomUUID();
        UUID estornoAcerto = UUID.randomUUID();
        List<Map<String, Object>> lote = new ArrayList<>();
        lote.add(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 3, "100.00"))));
        lote.add(opF(repos, "REPOSICAO_NOTA", 1, mapa("id", repos, "notaId", nota, "feitaEm", agora().toString(),
                "itens", List.of(itemJson(c.outra(), c.maleta(), 2, "50.00")))));
        lote.add(opF(troca, "TROCA_NA_NOTA", 1, mapa("id", troca, "notaId", nota, "trocadaEm", agora().toString(),
                "volta", mapa("itemId", UUID.randomUUID(), "produtoId", c.outra(), "remessaId", c.maleta(), "movimentoId", UUID.randomUUID()),
                "entra", mapa("itemId", UUID.randomUUID(), "produtoId", c.peca(), "remessaId", c.maleta(), "precoUnitario", "100.00",
                        "movimentoId", UUID.randomUUID()))));
        lote.add(opF(remarca, "REMARCAR_NOTA", 1, mapa("id", remarca, "notaId", nota, "dataCobranca", "2030-02-01",
                "remarcadaEm", agora().toString())));
        lote.add(acertoOp(c, acerto, nota, 2, "100.00"));
        lote.add(opF(pagamento, "PAGAMENTO_CONSIGNADO", 1, mapa("id", pagamento, "clienteId", c.cliente(), "semanaId", c.semana(),
                "sequencia", 3, "pagoEm", agora().toString(), "formas", List.of(mapa("forma", "DINHEIRO", "valor", "20.00")))));
        lote.add(opF(estornoRecibo, "ESTORNO_RECIBO", 1, mapa("id", estornoRecibo, "reciboId", pagamento, "motivo", "Errei",
                "estornadoEm", agora().toString())));
        lote.add(opF(estornoAcerto, "ESTORNO_ACERTO_NOTA", 1, mapa("id", estornoAcerto, "acertoId", acerto, "motivo", "Errei",
                "estornadoEm", agora().toString())));

        JsonNode r = enviar(c.v(), new ArrayList<>(lote));
        for (int i = 0; i < lote.size(); i++) {
            assertThat(resultado(r, i)).as("operação %d (%s)", i, lote.get(i).get("tipo")).isEqualTo("ACEITO");
        }
        int movimentos = contar("SELECT count(*) FROM corona.movimentos_remessa WHERE nota_id = ?", nota);
        assertThat(situacaoNota(nota)).isEqualTo("REMARCADO");

        JsonNode de = enviar(c.v(), new ArrayList<>(lote));
        for (int i = 0; i < lote.size(); i++) {
            assertThat(resultado(de, i)).as("repetição %d", i).isEqualTo("JA_RECEBIDO");
        }
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE nota_id = ?", nota)).isEqualTo(movimentos);
        assertThat(contar("SELECT count(*) FROM corona.itens_nota_consignado WHERE nota_id = ?", nota)).isEqualTo(4);
    }

    private int alertas(UsuarioDeTeste v, String tipo) {
        return contar("SELECT count(*) FROM corona.alertas_consignado WHERE tipo = ? AND vendedor_id = ?", tipo, v.vendedorId());
    }

    private String situacaoNota(UUID nota) {
        return jdbc.queryForObject("SELECT situacao FROM corona.notas_consignado WHERE id = ?", String.class, nota);
    }

    @Test
    void mesmoIdComOutroConteudoDaConflitoENadaMuda() throws Exception {
        Cena c = cena();
        UUID nota = UUID.randomUUID();
        Map<String, Object> primeira = notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "100.00")));
        assertThat(resultado(enviar(c.v(), List.of(primeira)), 0)).isEqualTo("ACEITO");
        Map<String, Object> outra = notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 5, "100.00")));
        assertThat(resultado(enviar(c.v(), List.of(outra)), 0)).isEqualTo("CONFLITO");
        assertThat(contar("SELECT coalesce(sum(quantidade), 0) FROM corona.itens_nota_consignado WHERE nota_id = ?", nota)).isEqualTo(1);
    }

    @Test
    void pecaForaDaMaletaERecusadaEOQueDependeDelaTambem() throws Exception {
        Cena c = cena();
        UUID estranha = peca("10.00", 5);
        UUID nota = UUID.randomUUID();
        UUID acerto = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(estranha, c.maleta(), 1, "10.00"))),
                acertoOp(c, acerto, nota, 2, null)));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("PECA_FORA_DA_MALETA");
        assertThat(resultado(r, 1)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("DEPENDE_DE_ENVIO_RECUSADO");
    }

    @Test
    void acertoDeNotaInexistenteERecusado() throws Exception {
        Cena c = cena();
        JsonNode r = enviar(c.v(), List.of(acertoOp(c, UUID.randomUUID(), UUID.randomUUID(), 2, null)));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("NOTA_NAO_ENCONTRADA");
    }

    @Test
    void clienteNovoNoFormato1ContinuaAceito() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID id = UUID.randomUUID();
        JsonNode r = enviar(v, List.of(op(id, "CLIENTE_NOVO", mapa("id", id, "nome", "Cliente Antigo"))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
    }

    @Test
    void clienteNovoV2SemEnderecoERecusadoEDocumentoRepetidoAceitoComAlerta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sem = UUID.randomUUID();
        Map<String, Object> semRua = clienteV2(sem);
        semRua.remove("enderecoLogradouro");
        assertThat(resultado(enviar(v, List.of(opF(sem, "CLIENTE_NOVO", 2, semRua))), 0)).isEqualTo("RECUSADO");

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        JsonNode r = enviar(v, List.of(opF(a, "CLIENTE_NOVO", 2, clienteV2(a)), opF(b, "CLIENTE_NOVO", 2, clienteV2(b))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        assertThat(alertas(v, "DOCUMENTO_REPETIDO")).isEqualTo(1);
    }

    @Test
    void alteracaoDeClienteNoFormato2MudaOsCamposNovos() throws Exception {
        Cena c = cena();
        UUID op = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(opF(op, "CLIENTE_ALTERADO", 2, mapa("clienteId", c.cliente(), "campos",
                mapa("comissaoPercentual", mapa("antes", "30.00", "depois", "25"), "enderecoBairro", mapa("antes", "Centro", "depois", "Vila"))))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(valor("SELECT comissao_percentual FROM corona.clientes WHERE id = ?", c.cliente())).isEqualByComparingTo("25");
        assertThat(jdbc.queryForObject("SELECT endereco_bairro FROM corona.clientes WHERE id = ?", String.class, c.cliente())).isEqualTo("Vila");
    }

    // --- concorrência ---

    private <T> List<T> emParalelo(List<Callable<T>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<T>> futuros = new ArrayList<>();
        for (Callable<T> t : tarefas) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return t.call();
            }));
        }
        largada.countDown();
        List<T> resultados = new ArrayList<>();
        for (Future<T> f : futuros) {
            resultados.add(f.get());
        }
        pool.shutdown();
        return resultados;
    }

    private ServicoDeEnvio.OperacaoEnviada enviada(Map<String, Object> op) {
        return json.convertValue(op, ServicoDeEnvio.OperacaoEnviada.class);
    }

    private OperacoesDoVendedor.Contexto contexto(UsuarioDeTeste v) {
        usuarios.token(v);
        UUID aparelho = jdbc.queryForObject("SELECT id FROM corona.dispositivos WHERE usuario_id = ? LIMIT 1", UUID.class, v.id());
        return new OperacoesDoVendedor.Contexto(v.vendedorId(), v.id(), aparelho, relogio.instant(), 0, null, false, false);
    }

    @Test
    void aMesmaOperacaoEmDuasThreadsAplicaUmaVezESoUmaVezEntraOMovimento() throws Exception {
        Cena c = cena();
        UUID nota = UUID.randomUUID();
        ServicoDeEnvio.OperacaoEnviada op = enviada(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 2, "100.00"))));
        List<ServicoDeEnvio.ResultadoDaOperacao> rs = emParalelo(List.of(() -> envio.processar(op, contexto(c.v())),
                () -> envio.processar(op, contexto(c.v()))));
        assertThat(rs).extracting(r -> r.resultado().name()).containsExactlyInAnyOrder("ACEITO", "JA_RECEBIDO");
        assertThat(contar("SELECT count(*) FROM corona.itens_nota_consignado WHERE nota_id = ?", nota)).isEqualTo(1);
        assertThat(saldo(c.maleta(), c.peca())).isEqualTo(8);
    }

    @Test
    void doisPagamentosDoMesmoClienteJuntosNuncaAplicamMaisQueODebito() throws Exception {
        Cena c = cena();
        UUID nota = UUID.randomUUID();
        UUID acerto = UUID.randomUUID();
        assertThat(resultado(enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 3, "100.00"))),
                acertoOp(c, acerto, nota, 2, null))), 1)).isEqualTo("ACEITO");
        // Débito de 210,00 (300 − 30% de comissão); dois pagamentos de 200 ao mesmo tempo.
        List<Callable<ServicoDeEnvio.ResultadoDaOperacao>> tarefas = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            UUID id = UUID.randomUUID();
            int seq = 10 + i;
            ServicoDeEnvio.OperacaoEnviada op = enviada(opF(id, "PAGAMENTO_CONSIGNADO", 1, mapa("id", id, "clienteId", c.cliente(),
                    "semanaId", c.semana(), "sequencia", seq, "pagoEm", agora().toString(),
                    "formas", List.of(mapa("forma", "PIX", "valor", "200.00")))));
            tarefas.add(() -> envio.processar(op, contexto(c.v())));
        }
        List<ServicoDeEnvio.ResultadoDaOperacao> rs = emParalelo(tarefas);
        assertThat(rs).extracting(r -> r.resultado().name()).containsOnly("ACEITO");
        assertThat(valor("SELECT coalesce(sum(valor), 0) FROM corona.aplicacoes_recibo WHERE acerto_id = ?", acerto)).isEqualByComparingTo("210.00");
        assertThat(situacaoNota(nota)).isEqualTo("FECHADO");
        // 400 pagos - 210 devidos = 190 ficam "a aplicar"; nada se perde.
        assertThat(valor("""
                SELECT coalesce(sum(r.total), 0) - coalesce(sum((SELECT sum(a.valor) FROM corona.aplicacoes_recibo a WHERE a.recibo_id = r.id)), 0)
                  FROM corona.recibos_consignado r WHERE r.cliente_id = ?""", c.cliente())).isEqualByComparingTo("190.00");
    }

    // --- acerto da nota, formato 2 (a meta decide; §23.13) ---

    /** Acerto formato 2: sem porcentagem. {@code lixo} acrescenta uma porcentagem que o servidor deve ignorar. */
    private Map<String, Object> acertoOpV2(Cena c, UUID id, UUID nota, String lixo) {
        Map<String, Object> dados = mapa("id", id, "notaId", nota, "semanaId", c.semana(), "acertadoEm", agora().toString(),
                "devolvidos", List.of());
        if (lixo != null) {
            dados.put("comissaoPercentual", lixo);
        }
        return opF(id, "ACERTO_NOTA", 2, dados);
    }

    private void comACotacaoDoExemplo(org.junit.jupiter.api.function.Executable teste) throws Throwable {
        br.com.coronasemijoias.api.testes.CotacoesDaMassa.doExemplo(jdbc);
        try {
            teste.execute();
        } finally {
            try (var con = conectarComoMigracao()) {
                br.com.coronasemijoias.api.testes.CotacoesDaMassa.tirar(con);
            }
        }
    }

    @Test
    void acertoFormato2ENaMetaOServidorDecideIgnoraAPorcentagemDoAppERepeticaoDaJaRecebido() throws Throwable {
        comACotacaoDoExemplo(() -> {
            Cena c = cenaComPeca("630.00");
            UUID nota = UUID.randomUUID();
            UUID acerto = UUID.randomUUID();
            List<Map<String, Object>> lote = List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "630.00"))),
                    acertoOpV2(c, acerto, nota, "10"));
            JsonNode r = enviar(c.v(), new ArrayList<>(lote));
            assertThat(resultado(r, 0)).isEqualTo("ACEITO");
            assertThat(resultado(r, 1)).isEqualTo("ACEITO");
            var linha = jdbc.queryForMap("SELECT * FROM corona.acertos_nota WHERE id = ?", acerto);
            assertThat(linha.get("regra_da_comissao")).isEqualTo("META");
            assertThat((java.math.BigDecimal) linha.get("comissao_percentual")).isEqualByComparingTo("40");
            assertThat(linha.get("vd_guarani")).isEqualTo(700_000L);
            // Repetir não grava de novo.
            JsonNode de = enviar(c.v(), new ArrayList<>(lote));
            assertThat(resultado(de, 1)).isEqualTo("JA_RECEBIDO");
            assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", nota)).isEqualTo(1);
        });
    }

    /** As colunas de uma linha no pull do representante (formato 2, o do Corona Vendas 1.2.0; todas as páginas). */
    private JsonNode colunasNoPull(UsuarioDeTeste v, String tabela, UUID id) throws Exception {
        String cursor = null;
        for (int i = 0; i < 100; i++) {
            JsonNode r = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/vendedor/sync/pull?formato=2&limite=500" + (cursor == null ? "" : "&cursor=" + cursor))
                            .with(como(v)).header("X-App-Tipo", "vendas")
                            .header("X-App-Versao", br.com.coronasemijoias.api.testes.TesteDeIntegracao.VERSAO_MINIMA_VENDAS))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn()
                    .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            for (JsonNode l : r.get("linhas")) {
                if (tabela.equals(l.get("tabela").asString()) && id.toString().equals(l.get("id").asString())) {
                    return l.get("campos");
                }
            }
            cursor = r.get("cursor").asString();
            if (!r.get("temMais").asBoolean()) {
                break;
            }
        }
        throw new AssertionError("A linha " + tabela + " " + id + " não veio no pull.");
    }

    /** Acerto formato 1, como o Corona Vendas 1.2.0 manda (com a porcentagem escolhida no computador). */
    private Map<String, Object> acertoOpV1(Cena c, UUID id, UUID nota, String porcentagem) {
        return opF(id, "ACERTO_NOTA", 1, mapa("id", id, "notaId", nota, "semanaId", c.semana(), "acertadoEm", agora().toString(),
                "comissaoPercentual", porcentagem, "devolvidos", List.of()));
    }

    /** C5 (27/11/2026): o 1.2.0 manda 30%, a nota bateu a meta: o servidor grava 40% e o pull devolve a linha do servidor. */
    @Test
    void acertoFormato1Do120NaMetaOServidorDecide40EOPullLevaAPorcentagemNova() throws Throwable {
        comACotacaoDoExemplo(() -> {
            Cena c = cenaComPeca("630.00");
            UUID nota = UUID.randomUUID();
            UUID acerto = UUID.randomUUID();
            JsonNode r = enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "630.00"))),
                    acertoOpV1(c, acerto, nota, "30")));
            assertThat(resultado(r, 1)).isEqualTo("ACEITO");
            var linha = jdbc.queryForMap("SELECT * FROM corona.acertos_nota WHERE id = ?", acerto);
            assertThat(linha.get("regra_da_comissao")).isEqualTo("META");
            assertThat((java.math.BigDecimal) linha.get("comissao_percentual")).isEqualByComparingTo("40");
            assertThat((java.math.BigDecimal) linha.get("comissao")).isEqualByComparingTo("252.00");
            // O computador recebe a linha do servidor no pull, com a porcentagem e a comissão decididas.
            JsonNode doPull = colunasNoPull(c.v(), "acertos_nota", acerto);
            assertThat(new java.math.BigDecimal(doPull.get("comissao_percentual").asString())).isEqualByComparingTo("40");
            assertThat(new java.math.BigDecimal(doPull.get("comissao").asString())).isEqualByComparingTo("252.00");
        });
    }

    @Test
    void acertoFormato1Do120AbaixoDaMetaFicaEm30MesmoMandando40() throws Throwable {
        comACotacaoDoExemplo(() -> {
            Cena c = cenaComPeca("629.99");
            UUID nota = UUID.randomUUID();
            UUID acerto = UUID.randomUUID();
            JsonNode r = enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "629.99"))),
                    acertoOpV1(c, acerto, nota, "40")));
            assertThat(resultado(r, 1)).isEqualTo("ACEITO");
            assertThat(jdbc.queryForObject("SELECT comissao_percentual FROM corona.acertos_nota WHERE id = ?", java.math.BigDecimal.class,
                    acerto)).isEqualByComparingTo("30");
            assertThat(jdbc.queryForObject("SELECT regra_da_comissao FROM corona.acertos_nota WHERE id = ?", String.class, acerto))
                    .isEqualTo("META");
        });
    }

    @Test
    void acertoFormato2AbaixoDaMetaFicaEm30() throws Throwable {
        comACotacaoDoExemplo(() -> {
            Cena c = cenaComPeca("629.99");
            UUID nota = UUID.randomUUID();
            UUID acerto = UUID.randomUUID();
            JsonNode r = enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "629.99"))),
                    acertoOpV2(c, acerto, nota, null)));
            assertThat(resultado(r, 1)).isEqualTo("ACEITO");
            assertThat(jdbc.queryForObject("SELECT comissao_percentual FROM corona.acertos_nota WHERE id = ?", java.math.BigDecimal.class,
                    acerto)).isEqualByComparingTo("30");
        });
    }

    @Test
    void acertoFormato2DuasVezesEmDuasThreadsUmAceitoEUmJaRecebido() throws Throwable {
        comACotacaoDoExemplo(() -> {
            Cena c = cenaComPeca("630.00");
            UUID nota = UUID.randomUUID();
            UUID acerto = UUID.randomUUID();
            assertThat(resultado(enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "630.00"))))), 0))
                    .isEqualTo("ACEITO");
            ServicoDeEnvio.OperacaoEnviada op = enviada(acertoOpV2(c, acerto, nota, null));
            List<ServicoDeEnvio.ResultadoDaOperacao> rs = emParalelo(List.of(() -> envio.processar(op, contexto(c.v())),
                    () -> envio.processar(op, contexto(c.v()))));
            assertThat(rs).extracting(r -> r.resultado().name()).containsExactlyInAnyOrder("ACEITO", "JA_RECEBIDO");
            assertThat(contar("SELECT count(*) FROM corona.acertos_nota WHERE nota_id = ?", nota)).isEqualTo(1);
        });
    }

    @Test
    void acertoFormato1SemPorcentagemContinuaRecusado() throws Exception {
        Cena c = cena();
        UUID nota = UUID.randomUUID();
        UUID acerto = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "100.00"))),
                opF(acerto, "ACERTO_NOTA", 1, mapa("id", acerto, "notaId", nota, "semanaId", c.semana(), "acertadoEm", agora().toString(),
                        "devolvidos", List.of()))));
        assertThat(resultado(r, 1)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("COMISSAO_FORA_DO_LIMITE");
    }

    @Test
    void acertoFormato1SemCotacaoTambemEDecididoPelaMetaAbaixoDela() throws Exception {
        Cena c = cena();
        UUID nota = UUID.randomUUID();
        UUID acerto = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(notaOp(c, nota, 1, List.of(itemJson(c.peca(), c.maleta(), 1, "100.00"))),
                acertoOp(c, acerto, nota, 2, null)));
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        var linha = jdbc.queryForMap("SELECT regra_da_comissao, meta_guarani, vd_guarani, comissao_percentual FROM corona.acertos_nota WHERE id = ?",
                acerto);
        // C5 (27/11/2026): o formato 1 não grava mais INFORMADA; sem cotação, fica abaixo da meta (30%), com alerta.
        assertThat(linha.get("regra_da_comissao")).isEqualTo("META");
        assertThat(linha.get("meta_guarani")).isEqualTo(700_000L);
        assertThat(linha.get("vd_guarani")).isNull();
        assertThat((java.math.BigDecimal) linha.get("comissao_percentual")).isEqualByComparingTo("30");
    }
}
