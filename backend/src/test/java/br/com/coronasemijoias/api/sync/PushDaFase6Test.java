package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * O envio das operações da Fase 6 (tarefa 6.7; plano aprovado no PR #131, testes 1 a 12): cada tipo novo pelo mesmo
 * serviço da rota online, reenvio sem duplicar, dependência pelos dados (inclusive o crédito de uma troca ainda não
 * enviada), fora do prazo pelo servidor vira revisão e nunca recusa, maleta em acerto, a VENDA ampliada sem quebrar o
 * passado, hora corrigida e "mesmo dia", permissões, computador trocado, quarentena e a troca que não fecha. PostgreSQL
 * de verdade. Escritos antes do código.
 */
class PushDaFase6Test extends ApoioDoEnvioDaFase6 {

    @Autowired
    OperacoesDoVendedor operacoes;

    /** Vendedor com cliente, peças A (50,00) e B (80,00) e C (30,00) numa maleta conferida, e uma venda de A à vista. */
    private record Cenario(UsuarioDeTeste v, UUID cliente, UUID a, UUID b, UUID c, UUID maleta, UUID vendaA) {
    }

    private Cenario cenario(Instant vendaEm) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("50.00", 10);
        UUID b = peca("80.00", 10);
        UUID c = peca("30.00", 10);
        UUID maleta = maleta(v, a, 4, b, 4, c, 4);
        UUID vendaA = vendaEm == null ? vender(v, maleta, cliente, List.of(linha(a, 2)), List.of(pagamento("100.00", "DINHEIRO")))
                : venderEm(v, maleta, cliente, a, 2, "100.00", vendaEm);
        return new Cenario(v, cliente, a, b, c, maleta, vendaA);
    }

    private Cenario cenario() throws Exception {
        return cenario(null);
    }

    private static List<Map<String, Object>> umaBoa(UUID peca) {
        return List.of(devolvida(peca, 1, "BOA"));
    }

    // --- fotografias (o que a rota grava, sem ids nem horas) ---

    private Map<String, Object> fotoDaDevolucao(UUID id) {
        Map<String, Object> f = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT situacao, destino, motivo, dentro_garantia, valor_devolvido, valor_reembolsado, valor_em_credito,
                       forma_reembolso, revisao_fora_do_prazo, revisao_acima_do_limite_semanal,
                       revisao_acima_do_valor_de_aprovacao, revisao_autorizacao_invalida, revisao_reembolso_maior_que_pago
                  FROM corona.devolucoes WHERE id = ?""", id));
        f.put("itens", jdbc.queryForList("""
                SELECT quantidade, valor, estado_peca, destino_peca FROM corona.itens_devolucao WHERE devolucao_id = ?
                 ORDER BY estado_peca, valor, quantidade""", id));
        f.put("movimentos", jdbc.queryForList(
                "SELECT tipo, quantidade FROM corona.movimentos_remessa WHERE devolucao_id = ? ORDER BY tipo, quantidade", id));
        f.put("pagamentos", jdbc.queryForList(
                "SELECT tipo, forma, valor FROM corona.pagamentos_cliente WHERE devolucao_id = ? ORDER BY tipo, forma, valor", id));
        f.put("credito", jdbc.queryForList(
                "SELECT origem, valor, valor_utilizado, situacao FROM corona.creditos_cliente WHERE devolucao_id = ?", id));
        return f;
    }

    private Map<String, Object> fotoDaTroca(UUID id) {
        Map<String, Object> t = jdbc.queryForMap("SELECT devolucao_id, venda_nova_id, situacao, diferenca FROM corona.trocas WHERE id = ?", id);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("situacao", t.get("situacao"));
        f.put("diferenca", t.get("diferenca"));
        f.put("devolucao", fotoDaDevolucao((UUID) t.get("devolucao_id")));
        UUID b = (UUID) t.get("venda_nova_id");
        f.put("venda", jdbc.queryForMap("SELECT status, subtotal, desconto, total FROM corona.vendas WHERE id = ?", b));
        f.put("pagamentosDeB", jdbc.queryForList(
                "SELECT tipo, forma, valor FROM corona.pagamentos_cliente WHERE venda_id = ? ORDER BY tipo, forma, valor", b));
        f.put("movimentosDeB", jdbc.queryForList(
                "SELECT tipo, quantidade FROM corona.movimentos_remessa WHERE venda_id = ? ORDER BY tipo, quantidade", b));
        f.put("usos", jdbc.queryForList(
                "SELECT tipo, valor FROM corona.usos_credito WHERE venda_id = ? ORDER BY tipo, valor", b));
        return f;
    }

    private Map<String, Object> fotoDoPedido(UUID id) {
        Map<String, Object> f = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT tipo, situacao, motivo, valor_pedido FROM corona.autorizacoes_excecao WHERE id = ?", id));
        f.put("itens", jdbc.queryForList("SELECT quantidade FROM corona.itens_autorizacao WHERE autorizacao_id = ?", id));
        return f;
    }

    private Instant corrigida(UUID operacao) {
        return ((Timestamp) jdbc.queryForObject("SELECT hora_corrigida FROM corona.sync_log WHERE id = ?", Timestamp.class, operacao))
                .toInstant();
    }

    private Instant instante(String sql, UUID id) {
        return jdbc.queryForObject(sql, OffsetDateTime.class, id).toInstant();
    }

    // --- 1. cada tipo pelo mesmo serviço da rota ---

    @Test
    void cadaTipoNovoPeloEnvioGravaOMesmoQueARotaComAHoraCorrigida() throws Exception {
        Cenario rota = cenario();
        Cenario envio = cenario();

        // Devolução.
        UUID devRota = UUID.randomUUID();
        postar(DEVOLUCOES, como(rota.v()), mapa("id", devRota, "vendaId", rota.vendaA(), "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", umaBoa(rota.a()))).andExpect(status().isCreated());
        UUID devEnvio = UUID.randomUUID();
        Instant quando = relogio.instant().minusSeconds(60);
        JsonNode r = enviar(envio.v(), List.of(devolucaoPeloEnvio(devEnvio, envio.vendaA(), "ARREPENDIMENTO", "PIX",
                umaBoa(envio.a()), null, quando)));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(fotoDaDevolucao(devEnvio)).isEqualTo(fotoDaDevolucao(devRota));
        assertThat(instante("SELECT devolvida_em FROM corona.devolucoes WHERE id = ?", devEnvio)).isEqualTo(corrigida(devEnvio));
        assertThat(saldo(envio.maleta(), envio.a())).isEqualTo(saldo(rota.maleta(), rota.a()));

        // Cancelar a devolução (mesmo dia).
        postar(DEVOLUCOES + "/" + devRota + "/cancelar", como(rota.v()), mapa("id", UUID.randomUUID(), "motivo", "Lançada errada"))
                .andExpect(status().isCreated());
        UUID cancelamento = UUID.randomUUID();
        r = enviar(envio.v(), List.of(cancelarDevolucaoPeloEnvio(cancelamento, devEnvio, relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(fotoDaDevolucao(devEnvio)).isEqualTo(fotoDaDevolucao(devRota));
        assertThat(jdbc.queryForObject("SELECT cancelamento_id FROM corona.devolucoes WHERE id = ?", UUID.class, devEnvio))
                .isEqualTo(cancelamento);

        // Pedido de autorização (venda fora do prazo).
        Cenario velhaRota = cenario(diasAtras(12));
        Cenario velhaEnvio = cenario(diasAtras(12));
        UUID pedRota = id(pedirAutorizacao(velhaRota));
        UUID pedEnvio = UUID.randomUUID();
        r = enviar(velhaEnvio.v(), List.of(pedidoPeloEnvio(pedEnvio, velhaEnvio.vendaA(), "ARREPENDIMENTO", umaBoa(velhaEnvio.a()),
                relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(fotoDoPedido(pedEnvio)).isEqualTo(fotoDoPedido(pedRota));

        // Troca com B mais cara (paga a diferença em dinheiro) e com B mais barata (sobra de crédito).
        for (String[] caso : List.of(new String[] {"b", "30.00"}, new String[] {"c", null})) {
            Cenario tr = cenario();
            Cenario te = cenario();
            List<Map<String, Object>> resto = caso[1] == null ? List.of() : List.of(pagamento(caso[1], "DINHEIRO"));
            UUID pecaR = "b".equals(caso[0]) ? tr.b() : tr.c();
            UUID pecaE = "b".equals(caso[0]) ? te.b() : te.c();
            JsonNode trocaRota = trocar(tr.v(), pedidoDeTroca(tr.vendaA(), "ARREPENDIMENTO", umaBoa(tr.a()), tr.maleta(),
                    List.of(linha(pecaR, 1)), resto));
            Map<String, Object> pedido = pedidoDeTroca(te.vendaA(), "ARREPENDIMENTO", umaBoa(te.a()), te.maleta(),
                    List.of(linha(pecaE, 1)), caso[1] == null ? List.of() : List.of(pagamento(caso[1], "DINHEIRO")));
            UUID trocaEnvio = (UUID) pedido.get("id");
            r = enviar(te.v(), List.of(trocaPeloEnvio(pedido, relogio.instant())));
            assertThat(resultado(r, 0)).as(caso[0]).isEqualTo("ACEITO");
            assertThat(fotoDaTroca(trocaEnvio)).as(caso[0]).isEqualTo(fotoDaTroca(id(trocaRota)));
            for (String campo : List.of("origem", "valor", "valor_utilizado", "situacao")) {
                assertThat(credito((UUID) pedido.get("creditoId")).get(campo)).as(caso[0] + " " + campo)
                        .isEqualTo(credito(UUID.fromString(trocaRota.get("creditoId").asString())).get(campo));
            }
            assertThat(instante("SELECT trocada_em FROM corona.trocas WHERE id = ?", trocaEnvio)).isEqualTo(corrigida(trocaEnvio));

            // Cancelar a troca (mesmo dia).
            postar(TROCAS + "/" + id(trocaRota) + "/cancelar", como(tr.v()), mapa("id", UUID.randomUUID(), "motivo", "Errada"))
                    .andExpect(status().isCreated());
            UUID cancelarTroca = UUID.randomUUID();
            r = enviar(te.v(), List.of(cancelarTrocaPeloEnvio(cancelarTroca, trocaEnvio, relogio.instant())));
            assertThat(resultado(r, 0)).as(caso[0]).isEqualTo("ACEITO");
            assertThat(fotoDaTroca(trocaEnvio)).as(caso[0]).isEqualTo(fotoDaTroca(id(trocaRota)));
        }
    }

    private JsonNode pedirAutorizacao(Cenario c) throws Exception {
        return corpoDe(postar("/api/vendedor/autorizacoes", como(c.v()), mapa("id", UUID.randomUUID(), "vendaId", c.vendaA(),
                "motivo", "ARREPENDIMENTO", "itens", umaBoa(c.a()))).andExpect(status().isCreated()));
    }

    // --- 2. reenvio sem duplicar ---

    @Test
    void reenvioDoLoteDaJaRecebidoJsonEquivalenteTambemEOutraPecaDaConflito() throws Exception {
        Cenario c = cenario();
        Cenario velha = cenario(diasAtras(12));
        UUID dev = UUID.randomUUID();
        Map<String, Object> devolucao = devolucaoPeloEnvio(dev, c.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(c.a()), null,
                relogio.instant());
        UUID ped = UUID.randomUUID();
        Map<String, Object> pedido = pedidoPeloEnvio(ped, velha.vendaA(), "ARREPENDIMENTO", umaBoa(velha.a()), relogio.instant());
        Map<String, Object> pedidoDeTroca = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", umaBoa(c.a()), c.maleta(),
                List.of(linha(c.c(), 1)), List.of());
        Map<String, Object> troca = trocaPeloEnvio(pedidoDeTroca, relogio.instant());
        JsonNode r = enviar(c.v(), List.of(devolucao, troca));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        assertThat(resultado(enviar(velha.v(), List.of(pedido)), 0)).isEqualTo("ACEITO");

        // O mesmo lote de novo (resposta perdida): JA_RECEBIDO e nenhuma contagem muda.
        Map<String, Integer> antes = contagens();
        JsonNode de2 = enviar(c.v(), List.of(devolucao, troca));
        assertThat(resultado(de2, 0)).isEqualTo("JA_RECEBIDO");
        assertThat(resultado(de2, 1)).isEqualTo("JA_RECEBIDO");
        assertThat(resultado(enviar(velha.v(), List.of(pedido)), 0)).isEqualTo("JA_RECEBIDO");
        assertThat(contagens()).isEqualTo(antes);

        // JSON equivalente: campos em outra ordem e "50.0" no lugar de "50.00".
        Map<String, Object> dados = dadosDe(devolucao);
        Map<String, Object> outraOrdem = new LinkedHashMap<>();
        outraOrdem.put("itens", dados.get("itens"));
        outraOrdem.put("devolvidaEm", dados.get("devolvidaEm"));
        outraOrdem.put("autorizacaoId", null);
        outraOrdem.put("formaReembolso", "PIX");
        outraOrdem.put("motivo", "ARREPENDIMENTO");
        outraOrdem.put("vendaId", c.vendaA());
        outraOrdem.put("id", dev);
        assertThat(resultado(enviar(c.v(), List.of(op(dev, "DEVOLUCAO", outraOrdem))), 0)).isEqualTo("JA_RECEBIDO");
        Map<String, Object> comCentavos = new LinkedHashMap<>(dados);
        comCentavos.put("valorConferido", "50.0");
        UUID dev2 = UUID.randomUUID();
        comCentavos.put("id", dev2);
        Map<String, Object> com50 = op(dev2, "DEVOLUCAO", comCentavos);
        Map<String, Object> com5000 = op(dev2, "DEVOLUCAO", new LinkedHashMap<>(comCentavos));
        dadosDe(com5000).put("valorConferido", "50.00");
        assertThat(operacoes.ler(dev2, "DEVOLUCAO", 1, json.valueToTree(dadosDe(com50))).resumo())
                .isEqualTo(operacoes.ler(dev2, "DEVOLUCAO", 1, json.valueToTree(dadosDe(com5000))).resumo());

        // Mesmo id com outra peça: CONFLITO e nada muda.
        Map<String, Object> outraPeca = new LinkedHashMap<>(dados);
        outraPeca.put("itens", umaBoa(c.b()));
        JsonNode conflito = enviar(c.v(), List.of(op(dev, "DEVOLUCAO", outraPeca)));
        assertThat(resultado(conflito, 0)).isEqualTo("CONFLITO");
        assertThat(contagens()).isEqualTo(antes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dadosDe(Map<String, Object> op) {
        return (Map<String, Object>) op.get("dados");
    }

    // --- 3. dependência: devolução de venda ainda não enviada ---

    @Test
    void devolucaoDeVendaAindaNaoEnviadaEDependenciasRecusadas() throws Exception {
        Cenario c = cenario();
        // [VENDA, DEVOLUCAO dela]: as duas entram.
        UUID venda = UUID.randomUUID();
        UUID dev = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(
                vendaPeloEnvio(venda, c.maleta(), c.cliente(), List.of(linha(c.b(), 1)), List.of(pagamento("80.00", "PIX")),
                        relogio.instant().minusSeconds(120)),
                devolucaoPeloEnvio(dev, venda, "ARREPENDIMENTO", "PIX", umaBoa(c.b()), null, relogio.instant().minusSeconds(60))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");

        // [VENDA recusada por saldo, DEVOLUCAO dela]: a devolução depende da venda recusada.
        UUID semSaldo = UUID.randomUUID();
        UUID devDela = UUID.randomUUID();
        r = enviar(c.v(), List.of(
                vendaPeloEnvio(semSaldo, c.maleta(), c.cliente(), List.of(linha(c.c(), 99)), List.of(), relogio.instant()),
                devolucaoPeloEnvio(devDela, semSaldo, "ARREPENDIMENTO", "PIX", umaBoa(c.c()), null, relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(resultado(r, 1)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("DEPENDE_DE_ENVIO_RECUSADO");

        // CANCELAR_DEVOLUCAO de uma devolução recusada (acima do vendido).
        UUID acima = UUID.randomUUID();
        UUID cancelar = UUID.randomUUID();
        r = enviar(c.v(), List.of(
                devolucaoPeloEnvio(acima, c.vendaA(), "ARREPENDIMENTO", "PIX", List.of(devolvida(c.a(), 9, "BOA")), null,
                        relogio.instant()),
                cancelarDevolucaoPeloEnvio(cancelar, acima, relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("DEPENDE_DE_ENVIO_RECUSADO");

        // DEVOLUCAO com a autorização de um pedido recusado (venda de outro vendedor).
        Cenario outro = cenario(diasAtras(12));
        Cenario velha = cenario(diasAtras(12));
        UUID ped = UUID.randomUUID();
        UUID devAutorizada = UUID.randomUUID();
        r = enviar(velha.v(), List.of(
                pedidoPeloEnvio(ped, outro.vendaA(), "ARREPENDIMENTO", umaBoa(outro.a()), relogio.instant()),
                devolucaoPeloEnvio(devAutorizada, velha.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(velha.a()), ped,
                        relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("DEPENDE_DE_ENVIO_RECUSADO");

        // CANCELAR_TROCA de uma troca recusada (peça acima do vendido).
        Map<String, Object> trocaRuim = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 9, "BOA")), c.maleta(),
                List.of(linha(c.c(), 1)), List.of());
        UUID cancelarTroca = UUID.randomUUID();
        r = enviar(c.v(), List.of(trocaPeloEnvio(trocaRuim, relogio.instant()),
                cancelarTrocaPeloEnvio(cancelarTroca, (UUID) trocaRuim.get("id"), relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("DEPENDE_DE_ENVIO_RECUSADO");
    }

    // --- 4. dependência: crédito de troca ainda não enviada ---

    @Test
    void creditoDeTrocaAindaNaoEnviadaPagaAVendaSeguinteEATrocaRecusadaArrastaAVenda() throws Exception {
        Cenario c = cenario();
        // TROCA com sobra (A 50,00 por C 30,00: 20,00 de crédito) e VENDA de C paga com 20,00 desse crédito.
        Map<String, Object> troca = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", umaBoa(c.a()), c.maleta(), List.of(linha(c.c(), 1)),
                List.of());
        UUID credito = (UUID) troca.get("creditoId");
        UUID venda = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(trocaPeloEnvio(troca, relogio.instant().minusSeconds(60)),
                vendaPeloEnvio(venda, c.maleta(), c.cliente(), List.of(linha(c.c(), 1)),
                        List.of(pagamento("10.00", "DINHEIRO"), pagamentoComCredito("20.00", credito)), relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        assertThat(credito(credito).get("situacao")).isEqualTo("UTILIZADO");

        // TROCA recusada (A acima do vendido) e VENDA com o crédito dela: depende.
        Map<String, Object> ruim = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 9, "BOA")), c.maleta(),
                List.of(linha(c.c(), 1)), List.of());
        UUID vendaRuim = UUID.randomUUID();
        r = enviar(c.v(), List.of(trocaPeloEnvio(ruim, relogio.instant()),
                vendaPeloEnvio(vendaRuim, c.maleta(), c.cliente(), List.of(linha(c.c(), 1)),
                        List.of(pagamentoComCredito("20.00", (UUID) ruim.get("creditoId"))), relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(resultado(r, 1)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("DEPENDE_DE_ENVIO_RECUSADO");

        // TROCA com sobra e outra TROCA cujo resto é pago com aquele crédito.
        Cenario d = cenario();
        Map<String, Object> primeira = pedidoDeTroca(d.vendaA(), "ARREPENDIMENTO", umaBoa(d.a()), d.maleta(), List.of(linha(d.c(), 1)),
                List.of());
        UUID sobra = (UUID) primeira.get("creditoId");
        Map<String, Object> segunda = pedidoDeTroca(d.vendaA(), "ARREPENDIMENTO", umaBoa(d.a()), d.maleta(), List.of(linha(d.b(), 1)),
                List.of(pagamentoComCredito("20.00", sobra), pagamento("10.00", "DINHEIRO")));
        r = enviar(d.v(), List.of(trocaPeloEnvio(primeira, relogio.instant().minusSeconds(60)), trocaPeloEnvio(segunda,
                relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");
        assertThat(credito(sobra).get("situacao")).isEqualTo("UTILIZADO");
    }

    // --- 5. fora do prazo pelo servidor vira revisão, nunca recusa ---

    @Test
    void foraDoPrazoPeloServidorViraRevisaoENuncaRecusa() throws Exception {
        // Relógio 3 dias atrasado: o computador achou o 6º dia; o servidor corrige para o 9º.
        Cenario c = cenario(diasAtras(9));
        Instant atrasado = relogio.instant().minus(Duration.ofDays(3));
        UUID dev = UUID.randomUUID();
        JsonNode r = enviar(c.v(), atrasado, List.of(devolucaoPeloEnvio(dev, c.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(c.a()),
                null, atrasado)));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        Map<String, Object> d = fotoDaDevolucao(dev);
        assertThat(d.get("situacao")).isEqualTo("EM_REVISAO");
        assertThat(d.get("revisao_fora_do_prazo")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT hora_ajustada FROM corona.devolucoes WHERE id = ?", Boolean.class, dev)).isTrue();

        // O mesmo na troca: a devolução de A em revisão e a troca gravada.
        Cenario t = cenario(diasAtras(9));
        Map<String, Object> troca = pedidoDeTroca(t.vendaA(), "ARREPENDIMENTO", umaBoa(t.a()), t.maleta(), List.of(linha(t.c(), 1)),
                List.of());
        r = enviar(t.v(), atrasado, List.of(trocaPeloEnvio(troca, atrasado)));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(situacaoDaDevolucao(idDe(devolucaoDo(troca)))).isEqualTo("EM_REVISAO");
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.trocas WHERE id = ?", String.class, troca.get("id"))).isEqualTo("ATIVA");

        // Relógio 2 dias adiantado: o computador achou o 8º dia; o servidor corrige para o 6º, dentro do prazo.
        Cenario e = cenario(diasAtras(6));
        Instant adiantado = relogio.instant().plus(Duration.ofDays(2));
        UUID dentro = UUID.randomUUID();
        r = enviar(e.v(), adiantado, List.of(devolucaoPeloEnvio(dentro, e.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(e.a()), null,
                adiantado)));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(situacaoDaDevolucao(dentro)).isEqualTo("ACEITA");

        // Pedido desnecessário pelo envio: gravado como fora do prazo (P13).
        Cenario p = cenario(diasAtras(2));
        UUID ped = UUID.randomUUID();
        r = enviar(p.v(), List.of(pedidoPeloEnvio(ped, p.vendaA(), "ARREPENDIMENTO", umaBoa(p.a()), relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(fotoDoPedido(ped).get("tipo")).isEqualTo("FORA_DO_PRAZO");

        // Acima do limite da semana e do valor de aprovação, pelo envio: revisão.
        limiteSemanal("10.00");
        Cenario l = cenario();
        UUID acimaDoLimite = UUID.randomUUID();
        assertThat(resultado(enviar(l.v(), List.of(devolucaoPeloEnvio(acimaDoLimite, l.vendaA(), "ARREPENDIMENTO", "PIX",
                umaBoa(l.a()), null, relogio.instant()))), 0)).isEqualTo("ACEITO");
        assertThat(fotoDaDevolucao(acimaDoLimite).get("revisao_acima_do_limite_semanal")).isEqualTo(true);
        limiteSemanal(null);
        valorDeAprovacao("10.00");
        Cenario va = cenario();
        UUID acimaDoValor = UUID.randomUUID();
        assertThat(resultado(enviar(va.v(), List.of(devolucaoPeloEnvio(acimaDoValor, va.vendaA(), "ARREPENDIMENTO", "PIX",
                umaBoa(va.a()), null, relogio.instant()))), 0)).isEqualTo("ACEITO");
        assertThat(fotoDaDevolucao(acimaDoValor).get("revisao_acima_do_valor_de_aprovacao")).isEqualTo(true);
        valorDeAprovacao(null);

        // Autorização vencida no dia corrigido: revisão, sem consumir a autorização.
        Cenario au = cenario(diasAtras(12));
        UUID autorizacao = id(pedirAutorizacao(au));
        jdbc.update("""
                UPDATE corona.autorizacoes_excecao SET situacao = 'APROVADA', decidida_em = now() - interval '9 days',
                       decidida_por = ?, valida_ate = current_date - 2 WHERE id = ?""", usuarios.dona().id(), autorizacao);
        UUID comVencida = UUID.randomUUID();
        assertThat(resultado(enviar(au.v(), List.of(devolucaoPeloEnvio(comVencida, au.vendaA(), "ARREPENDIMENTO", "PIX",
                umaBoa(au.a()), autorizacao, relogio.instant()))), 0)).isEqualTo("ACEITO");
        Map<String, Object> vencida = fotoDaDevolucao(comVencida);
        assertThat(vencida.get("situacao")).isEqualTo("EM_REVISAO");
        assertThat(vencida.get("revisao_autorizacao_invalida")).isEqualTo(true);
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("APROVADA");
    }

    @Test
    void viradaDoDiaPeloHorarioDeBrasiliaNaoPeloUtc() throws Exception {
        // Servidor às 23h45 de Brasília do 7º dia (02h45 UTC do 8º).
        relogio.fixar(Instant.parse("2026-11-21T02:45:00Z"));
        Cenario c = cenario(brasilia("2026-11-13T12:00:00"));
        UUID dev = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(devolucaoPeloEnvio(dev, c.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(c.a()), null,
                brasilia("2026-11-20T23:30:00"))));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(situacaoDaDevolucao(dev)).isEqualTo("ACEITA");
    }

    private String situacaoDaDevolucao(Object id) {
        return jdbc.queryForObject("SELECT situacao FROM corona.devolucoes WHERE id = ?", String.class, id);
    }

    private String situacaoDaAutorizacao(UUID id) {
        return jdbc.queryForObject("SELECT situacao FROM corona.autorizacoes_excecao WHERE id = ?", String.class, id);
    }

    // --- 6. maleta em acerto ---

    @Test
    void maletaEmAcertoRecusaEDepoisDoAcertoTentarDeNovoMandaAPecaParaALoja() throws Exception {
        // A maleta da venda vende tudo: o acerto feito à mão no fim (UPDATE, a Fase 7 ainda não existe) não deixa peça nela
        // sem movimento, e a conciliação do banco que os testes dividem continua vazia. A peça B vem de outra maleta, aberta.
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("50.00", 10);
        UUID pecaB = peca("30.00", 10);
        UUID daVenda = maleta(v, a, 2);
        UUID vendaA = vender(v, daVenda, cliente, List.of(linha(a, 2)), List.of(pagamento("100.00", "DINHEIRO")));
        UUID aberta = maleta(v, pecaB, 1);
        jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO' WHERE id = ?", daVenda);
        Map<String, Integer> antes = contagens();
        UUID dev = UUID.randomUUID();
        Map<String, Object> troca = pedidoDeTroca(vendaA, "ARREPENDIMENTO", umaBoa(a), aberta, List.of(linha(pecaB, 1)), List.of());
        JsonNode r = enviar(v, List.of(devolucaoPeloEnvio(dev, vendaA, "ARREPENDIMENTO", "PIX", umaBoa(a), null, relogio.instant()),
                trocaPeloEnvio(troca, relogio.instant())));
        for (int i = 0; i < 2; i++) {
            assertThat(resultado(r, i)).isEqualTo("RECUSADO");
            assertThat(codigo(r, i)).isEqualTo("MALETA_EM_ACERTO");
        }
        assertThat(contagens()).isEqualTo(antes);
        assertThat(registroDoEnvio(dev).get("com_json")).isEqualTo(true);
        String problemas = corpoDe(ler("/api/dona/envios-com-problema", dona()).andExpect(status().isOk())).toString();
        assertThat(problemas).contains(dev.toString());

        // B de outra maleta em acerto, com A de maleta aberta: também recusada.
        Cenario d = cenario();
        UUID outraB = maleta(d.v(), d.c(), 1);
        jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO' WHERE id = ?", outraB);
        Map<String, Object> trocaB = pedidoDeTroca(d.vendaA(), "ARREPENDIMENTO", umaBoa(d.a()), outraB, List.of(linha(d.c(), 1)),
                List.of());
        assertThat(codigo(enviar(d.v(), List.of(trocaPeloEnvio(trocaB, relogio.instant()))), 0)).isEqualTo("MALETA_EM_ACERTO");

        // Depois do acerto, a dona tenta de novo: entra, e a peça boa vai para a loja (nenhum movimento na maleta acertada).
        assertThat(saldo(daVenda, a)).isZero();
        jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", daVenda);
        mvc.perform(post("/api/dona/envios-com-problema/" + dev + "/tentar-de-novo").with(dona())).andExpect(status().isOk());
        Map<String, Object> f = fotoDaDevolucao(dev);
        assertThat(f.get("movimentos")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(jdbc.queryForObject("SELECT destino_peca FROM corona.itens_devolucao WHERE devolucao_id = ?", String.class, dev))
                .isEqualTo("LOJA");
        assertThat(jdbc.queryForObject("SELECT resolucao FROM corona.sync_log WHERE id = ?", String.class, dev)).isNotNull();
    }

    // --- 8. VENDA ampliada sem quebrar o passado ---

    @Test
    void vendaSemCreditoTemOMesmoResumoDeAntesEComCreditoUsaOCredito() throws Exception {
        UUID id = UUID.fromString("0190a1b2-0000-7000-8000-000000000001");
        UUID maleta = UUID.fromString("0190a1b2-0000-7000-8000-000000000002");
        UUID cliente = UUID.fromString("0190a1b2-0000-7000-8000-000000000003");
        UUID peca = UUID.fromString("0190a1b2-0000-7000-8000-000000000004");
        UUID pagamento = UUID.fromString("0190a1b2-0000-7000-8000-000000000005");
        Map<String, Object> dados = mapa("id", id, "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", peca, "quantidade", 2, "desconto", "1.5")),
                "pagamentos", List.of(mapa("id", pagamento, "valor", "10.0", "forma", "PIX")),
                "totalConferido", "58.50", "vendidaEm", "2026-11-12T15:00:00Z");
        // A forma canônica da tarefa 5.5, escrita à parte: o resumo de uma venda sem crédito nunca muda.
        String canonico = "VENDA/1|id=" + id + "|remessaId=" + maleta + "|clienteId=" + cliente + "|itens=[" + peca
                + ":2:150;]|pagamentos=[" + pagamento + ":1000:PIX;]|totalConferido=5850|vendidaEm=2026-11-12T15:00:00Z|";
        String esperado = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonico.getBytes(StandardCharsets.UTF_8)));
        assertThat(operacoes.ler(id, "VENDA", 1, json.valueToTree(dados)).resumo()).isEqualTo(esperado);

        // Com o crédito, o resumo muda (o crédito entra na forma canônica).
        Map<String, Object> comCredito = new LinkedHashMap<>(dados);
        comCredito.put("pagamentos", List.of(mapa("id", pagamento, "valor", "10.0", "forma", "CREDITO", "creditoId", cliente)));
        assertThat(operacoes.ler(id, "VENDA", 1, json.valueToTree(comCredito)).resumo()).isNotEqualTo(esperado);

        // Pelo envio, a venda com crédito usa o crédito.
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID c = cliente(v);
        UUID credito = creditoDeSobra(v, c, "100.00", "60.00");
        UUID p = peca("30.00", 2);
        UUID m = maleta(v, p, 1);
        UUID venda = UUID.randomUUID();
        assertThat(resultado(enviar(v, List.of(vendaPeloEnvio(venda, m, c, List.of(linha(p, 1)),
                List.of(pagamentoComCredito("30.00", credito)), relogio.instant()))), 0)).isEqualTo("ACEITO");
        assertThat(valor("SELECT coalesce(sum(valor), 0) FROM corona.usos_credito WHERE venda_id = ?", venda))
                .isEqualByComparingTo("30.00");
        assertThat(contar("SELECT count(*) FROM corona.creditos_sem_saldo WHERE venda_id = ?", venda)).isZero();
    }

    // --- 9. hora e "mesmo dia" ---

    @Test
    void horaLimitadaPelaVendaPelaMaletaEPeloRegistroEOMesmoDiaPelaHoraCorrigida() throws Exception {
        // Servidor às 23h30 de Brasília (alguns dias à frente, para nada do banco ficar depois do relógio).
        relogio.fixar(Instant.parse("2026-11-17T02:30:00Z"));
        Cenario c = cenario(brasilia("2026-11-16T20:00:00"));
        // Devolução declarada antes da venda: fica na hora da venda.
        UUID antes = UUID.randomUUID();
        enviar(c.v(), List.of(devolucaoPeloEnvio(antes, c.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(c.a()), null,
                brasilia("2026-11-10T10:00:00"))));
        Instant vendidaEm = instante("SELECT vendida_em FROM corona.vendas WHERE id = ?", c.vendaA());
        assertThat(instante("SELECT devolvida_em FROM corona.devolucoes WHERE id = ?", antes)).isEqualTo(vendidaEm);
        // Cancelamento declarado antes da devolução: fica na hora da devolução; no mesmo dia, passa.
        UUID cancelar = UUID.randomUUID();
        assertThat(resultado(enviar(c.v(), List.of(cancelarDevolucaoPeloEnvio(cancelar, antes, brasilia("2026-11-01T10:00:00")))), 0))
                .isEqualTo("ACEITO");
        assertThat(corrigida(cancelar)).isEqualTo(vendidaEm);

        // Troca limitada pela venda A e pelo envio da maleta B; hora no futuro vira a chegada.
        Map<String, Object> troca = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", umaBoa(c.a()), c.maleta(), List.of(linha(c.c(), 1)),
                List.of());
        enviar(c.v(), List.of(trocaPeloEnvio(troca, brasilia("2026-11-01T10:00:00"))));
        Instant enviada = instante("SELECT enviada_em FROM corona.remessas WHERE id = ?", c.maleta());
        assertThat(corrigida((UUID) troca.get("id"))).isEqualTo(vendidaEm.isAfter(enviada) ? vendidaEm : enviada);
        UUID futura = UUID.randomUUID();
        Cenario f = cenario();
        enviar(f.v(), List.of(devolucaoPeloEnvio(futura, f.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(f.a()), null,
                relogio.instant().plus(Duration.ofDays(3)))));
        assertThat(corrigida(futura)).isEqualTo(relogio.instant().truncatedTo(ChronoUnit.MICROS));

        // Cancelar no dia seguinte (pela hora corrigida): RECUSADO, só a dona.
        UUID outra = UUID.randomUUID();
        enviar(c.v(), List.of(devolucaoPeloEnvio(outra, c.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(c.a()), null, relogio.instant())));
        relogio.fixar(Instant.parse("2026-11-17T03:10:00Z")); // 00h10 em Brasília
        var tokenNovo = UsuariosDeTeste.comToken(usuarios.token(c.v()));
        UUID tarde = UUID.randomUUID();
        JsonNode r = enviar(tokenNovo, relogio.instant(), List.of(cancelarDevolucaoPeloEnvio(tarde, outra, relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("CANCELAR_DEVOLUCAO_SO_DA_DONA");
        UUID tardeTroca = UUID.randomUUID();
        r = enviar(tokenNovo, relogio.instant(), List.of(cancelarTrocaPeloEnvio(tardeTroca, (UUID) troca.get("id"), relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("CANCELAR_TROCA_SO_DA_DONA");
    }

    // --- 10. permissões e barreira ---

    @Test
    void sobreVendaDevolucaoOuTrocaDeOutroVendedorTudoRecusadoSemRevelarNada() throws Exception {
        Cenario dono = cenario();
        Cenario intruso = cenario();
        UUID dev = UUID.randomUUID();
        postar(DEVOLUCOES, como(dono.v()), mapa("id", dev, "vendaId", dono.vendaA(), "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", umaBoa(dono.a()))).andExpect(status().isCreated());
        JsonNode trocaDoDono = trocar(dono.v(), pedidoDeTroca(dono.vendaA(), "ARREPENDIMENTO", umaBoa(dono.a()), dono.maleta(),
                List.of(linha(dono.c(), 1)), List.of()));
        Map<String, Integer> antes = contagens();
        JsonNode r = enviar(intruso.v(), List.of(
                devolucaoPeloEnvio(UUID.randomUUID(), dono.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(dono.a()), null, relogio.instant()),
                pedidoPeloEnvio(UUID.randomUUID(), dono.vendaA(), "ARREPENDIMENTO", umaBoa(dono.a()), relogio.instant()),
                trocaPeloEnvio(pedidoDeTroca(dono.vendaA(), "ARREPENDIMENTO", umaBoa(dono.a()), intruso.maleta(),
                        List.of(linha(intruso.c(), 1)), List.of()), relogio.instant()),
                cancelarDevolucaoPeloEnvio(UUID.randomUUID(), dev, relogio.instant()),
                cancelarTrocaPeloEnvio(UUID.randomUUID(), id(trocaDoDono), relogio.instant())));
        List<String> codigos = List.of("VENDA_NAO_ENCONTRADA", "VENDA_NAO_ENCONTRADA", "VENDA_NAO_ENCONTRADA",
                "DEVOLUCAO_NAO_ENCONTRADA", "TROCA_NAO_ENCONTRADA");
        for (int i = 0; i < codigos.size(); i++) {
            assertThat(resultado(r, i)).as("%d", i).isEqualTo("RECUSADO");
            assertThat(codigo(r, i)).as("%d", i).isEqualTo(codigos.get(i));
            assertThat(r.get(i).toString()).doesNotContain(dono.cliente().toString()).doesNotContain(dono.v().nome());
        }
        assertThat(contagens()).isEqualTo(antes);
    }

    // --- 11. computador trocado, quarentena e formato desconhecido ---

    @Test
    void computadorTrocadoEnviaADevolucaoPelaCorrenteEFormato2EInvalido() throws Exception {
        Cenario c = cenario();
        String noAntigo = usuarios.token(c.v());
        UUID novo = UUID.randomUUID();
        usuarios.tokenNoAparelho(c.v(), novo);
        mvc.perform(post("/api/dona/aparelhos/" + novo + "/liberar").with(dona())).andExpect(status().isOk());
        UUID dev = UUID.randomUUID();
        String corpo = mvc.perform(post(PUSH).with(UsuariosDeTeste.comToken(noAntigo)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mapa("horaLocal", relogio.instant().toString(), "aguardandoDepoisDeste", 0,
                                "operacoes", List.of(devolucaoPeloEnvio(dev, c.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(c.a()),
                                        null, relogio.instant().minusSeconds(600)))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(corpo).get("resultados").get(0).get("resultado").asString()).isEqualTo("ACEITO");
        assertThat(situacaoDaDevolucao(dev)).isEqualTo("ACEITA");

        // Formato 2 de DEVOLUCAO não existe: pedido inválido.
        Cenario d = cenario();
        UUID f2 = UUID.randomUUID();
        Map<String, Object> op2 = devolucaoPeloEnvio(f2, d.vendaA(), "ARREPENDIMENTO", "PIX", umaBoa(d.a()), null, relogio.instant());
        op2.put("formato", 2);
        JsonNode r = enviar(d.v(), List.of(op2));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("PEDIDO_INVALIDO");
    }

    @Test
    void computadorBloqueadoGuardaDevolucaoETrocaSemAplicarEADonaAprovaPeloMesmoServico() throws Exception {
        Cenario c = cenario();
        UUID computador = usuarios.aparelhoDe(c.v());
        JsonNode login = corpoDe(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("login", c.v().login(), "senha", c.v().senha(), "dispositivoId", computador))))
                .andExpect(status().isOk()));
        mvc.perform(post("/api/dona/aparelhos/" + computador + "/bloquear").with(dona())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Roubado\"}")).andExpect(status().isOk());
        UUID dev = UUID.randomUUID();
        Map<String, Object> troca = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", umaBoa(c.a()), c.maleta(), List.of(linha(c.c(), 1)),
                List.of());
        Map<String, Integer> antes = contagens();
        mvc.perform(post("/api/vendedor/sync/quarentena").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("tokenDeRenovacao", login.get("tokenDeRenovacao").asString(),
                        "dispositivoId", computador, "horaLocal", relogio.instant().toString(), "operacoes", List.of(
                                devolucaoPeloEnvio(dev, c.vendaA(), "ARREPENDIMENTO", "PIX", List.of(devolvida(c.a(), 1, "DEFEITO")),
                                        null, relogio.instant().minusSeconds(120)),
                                trocaPeloEnvio(troca, relogio.instant().minusSeconds(60)))))))
                .andExpect(status().isOk());
        assertThat(contagens()).as("guardado sem aplicar").isEqualTo(antes);
        for (UUID envio : List.of(dev, (UUID) troca.get("id"))) {
            mvc.perform(post("/api/dona/quarentena/" + envio + "/aprovar").with(dona())).andExpect(status().isOk());
        }
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE id = ?", dev)).isOne();
        assertThat(contar("SELECT count(*) FROM corona.trocas WHERE id = ?", troca.get("id"))).isOne();
    }

    // --- 12. troca que não fecha no fim da transação ---

    @Test
    void trocaQueNaoFechaPeloEnvioERecusadaSem500ENadaGravado() throws Exception {
        Cenario c = cenario();
        Map<String, Object> pedido = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", umaBoa(c.a()), c.maleta(), List.of(linha(c.b(), 1)),
                List.of(pagamento("30.00", "DINHEIRO")));
        UUID troca = (UUID) pedido.get("id");
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            s.execute("""
                    CREATE FUNCTION corona.teste_entortar_troca_do_envio() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.id = '%s' THEN
                            NEW.diferenca := NEW.diferenca + 0.01;
                        END IF;
                        RETURN NEW;
                    END;
                    $$""".formatted(troca));
            s.execute("""
                    CREATE TRIGGER teste_entortar_troca_do_envio BEFORE INSERT ON corona.trocas
                        FOR EACH ROW EXECUTE FUNCTION corona.teste_entortar_troca_do_envio()""");
            con.commit();
        }
        try {
            Map<String, Integer> antes = contagens();
            JsonNode r = enviar(c.v(), List.of(trocaPeloEnvio(pedido, relogio.instant())));
            assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
            assertThat(codigo(r, 0)).isEqualTo("TROCA_NAO_CONFERE");
            assertThat(contagens()).isEqualTo(antes);
            assertThat(registroDoEnvio(troca).get("resultado")).isEqualTo("RECUSADO");
        } finally {
            try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
                s.execute("DROP TRIGGER teste_entortar_troca_do_envio ON corona.trocas");
                s.execute("DROP FUNCTION corona.teste_entortar_troca_do_envio()");
                con.commit();
            }
        }
        assertThat(saldo(c.maleta(), c.b())).isEqualTo(4);
    }
}
