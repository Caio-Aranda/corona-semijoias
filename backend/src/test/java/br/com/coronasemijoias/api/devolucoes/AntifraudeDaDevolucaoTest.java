package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.sync.FiltroPorVendedor;
import br.com.coronasemijoias.api.sync.LinhaDoPull;
import br.com.coronasemijoias.api.sync.LoteDoPull;
import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import br.com.coronasemijoias.api.sync.TabelaSincronizavel;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Limite semanal, valor de aprovação, reembolso maior que o pago, motivos da revisão, revisão da dona (Manter e Não
 * aceitar), taxa de devolução e os avisos (tarefa 6.4; ADR-0014, itens 5, 10 e 12; plano aprovado no PR #126, itens 6 a
 * 12, 16 e 17 dos testes). Escritos antes do código.
 */
class AntifraudeDaDevolucaoTest extends ApoioDasDevolucoes {

    @Autowired
    FonteDeAvisosDasDevolucoes fonte;

    @Autowired
    ServicoDePull pull;

    private static List<PecaDoPedido> uma(UUID produto) {
        return List.of(new PecaDoPedido(produto, 1, "BOA"));
    }

    private String nomeDoVendedor(UsuarioDeTeste v) {
        return jdbc.queryForObject("SELECT nome FROM corona.vendedores WHERE id = ?", String.class, v.vendedorId());
    }

    private List<AvisoDaDona> avisosDe(String tipo) {
        return fonte.avisos().stream().filter(a -> a.tipo().equals(tipo)).toList();
    }

    private List<AvisoDaDona> muitasDevolucoesDe(UsuarioDeTeste v) {
        String nome = nomeDoVendedor(v);
        return avisosDe("MUITAS_DEVOLUCOES").stream().filter(a -> a.titulo().equals("Muitas devoluções de " + nome + ".")).toList();
    }

    private BigDecimal somaDosPagamentos(UUID venda) {
        return jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.pagamentos_cliente WHERE venda_id = ?",
                BigDecimal.class, venda);
    }

    private int saldo(UUID maleta, UUID produto) {
        return jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                Integer.class, maleta, produto);
    }

    // --- 6. limite semanal ---

    @Test
    void limiteSemanalVazioNuncaPoeEmRevisaoENoValorExatoPassa() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sessenta = peca("60.00", 10);
        UUID quarenta = peca("40.00", 10);
        UUID maleta = maleta(v, sessenta, 5, quarenta, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(sessenta, 2), linha(quarenta, 2)), List.of(pagamento("200.00", "PIX")));
        // Sem limite: tudo aceito.
        assertThat(devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(sessenta, 1, "BOA"))).get("situacao").asString())
                .isEqualTo("ACEITA");
        // Outro vendedor devolvendo na mesma semana não conta para este.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID maletaDoOutro = maleta(outro, sessenta, 2);
        UUID vendaDoOutro = vender(outro, maletaDoOutro, cliente(outro), List.of(linha(sessenta, 1)), List.of(pagamento("60.00", "PIX")));
        devolver(outro, vendaDoOutro, "ARREPENDIMENTO", List.of(devolvida(sessenta, 1, "BOA")));

        limiteSemanal("100.00");
        // 60,00 + 40,00 = 100,00: no limite exato, aceita.
        JsonNode noLimite = devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(quarenta, 1, "BOA")));
        assertThat(noLimite.get("situacao").asString()).isEqualTo("ACEITA");
        assertThat(noLimite.get("motivosRevisao")).isEmpty();
    }

    @Test
    void acimaDoLimiteSemanalVaiParaRevisaoNaRotaENoEnvioComReembolsoEMovimento() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sessenta = peca("60.00", 10);
        UUID quarentaEUm = peca("40.01", 10);
        UUID maleta = maleta(v, sessenta, 5, quarentaEUm, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(sessenta, 2), linha(quarentaEUm, 2)),
                List.of(pagamento("200.02", "PIX")));
        limiteSemanal("100.00");
        devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(sessenta, 1, "BOA")));
        int saldoAntes = saldo(maleta, quarentaEUm);
        JsonNode acima = devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(quarentaEUm, 1, "BOA")));
        assertThat(acima.get("situacao").asString()).isEqualTo("EM_REVISAO");
        assertThat(acima.get("motivosRevisao").toString()).isEqualTo("[\"ACIMA_DO_LIMITE_SEMANAL\"]");
        // O dinheiro já saiu e a peça já voltou: movimento e reembolso gravados igual.
        assertThat(dec(acima.get("valorReembolsado"))).isEqualByComparingTo("40.01");
        assertThat(saldo(maleta, quarentaEUm)).isEqualTo(saldoAntes + 1);
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE devolucao_id = ? AND tipo = 'REEMBOLSO'",
                id(acima))).isEqualTo(1);

        // Pelo envio, o mesmo.
        ServicoDeDevolucoes.Resultado peloEnvio = devolverPeloServico(v, venda, "ARREPENDIMENTO", uma(sessenta), null, null,
                ModoDaDevolucao.ENVIO);
        assertThat(motivosGravados(peloEnvio.devolucaoId())).containsExactly("ACIMA_DO_LIMITE_SEMANAL");
        assertThat(situacaoDaDevolucao(peloEnvio.devolucaoId())).isEqualTo("EM_REVISAO");
    }

    @Test
    void devolucaoCanceladaNaoContaNoLimite() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sessenta = peca("60.00", 10);
        UUID maleta = maleta(v, sessenta, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(sessenta, 3)), List.of(pagamento("180.00", "PIX")));
        limiteSemanal("100.00");
        JsonNode cancelada = devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(sessenta, 1, "BOA")));
        postar(DEVOLUCOES + "/" + cancelada.get("id").asString() + "/cancelar", como(v), mapa("id", UUID.randomUUID(),
                "motivo", "engano")).andExpect(status().isCreated());
        assertThat(devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(sessenta, 1, "BOA"))).get("situacao").asString())
                .isEqualTo("ACEITA");
    }

    @Test
    void semanaSemCadastroESegundaADomingoDeBrasilia() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sessenta = peca("60.00", 10);
        UUID maleta = maleta(v, sessenta, 6);
        UUID cliente = cliente(v);
        limiteSemanal("100.00");
        // Semana de segunda 02/03/2026 a domingo 08/03/2026 (nenhuma semana cadastrada nestes dias).
        UUID a = venderEm(v, maleta, cliente, sessenta, 1, "60.00", brasilia("2026-03-07T10:00:00"));
        UUID b = venderEm(v, maleta, cliente, sessenta, 1, "60.00", brasilia("2026-03-07T10:00:00"));
        UUID c = venderEm(v, maleta, cliente, sessenta, 1, "60.00", brasilia("2026-03-07T10:00:00"));
        ServicoDeDevolucoes.Resultado domingo = devolverPeloServico(v, a, "ARREPENDIMENTO", uma(sessenta), null,
                brasilia("2026-03-08T12:00:00"), ModoDaDevolucao.ONLINE);
        // Domingo às 23:30 em Brasília (segunda 02:30 UTC): ainda é a mesma semana.
        ServicoDeDevolucoes.Resultado domingoANoite = devolverPeloServico(v, b, "ARREPENDIMENTO", uma(sessenta), null,
                brasilia("2026-03-08T23:30:00"), ModoDaDevolucao.ONLINE);
        // Segunda: semana nova.
        ServicoDeDevolucoes.Resultado segunda = devolverPeloServico(v, c, "ARREPENDIMENTO", uma(sessenta), null,
                brasilia("2026-03-09T10:00:00"), ModoDaDevolucao.ONLINE);
        assertThat(situacaoDaDevolucao(domingo.devolucaoId())).isEqualTo("ACEITA");
        assertThat(situacaoDaDevolucao(domingoANoite.devolucaoId())).isEqualTo("EM_REVISAO");
        assertThat(motivosGravados(domingoANoite.devolucaoId())).containsExactly("ACIMA_DO_LIMITE_SEMANAL");
        assertThat(situacaoDaDevolucao(segunda.devolucaoId())).isEqualTo("ACEITA");
    }

    @Test
    void semanaCadastradaValeNoLugarDeSegundaADomingo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sessenta = peca("60.00", 10);
        UUID maleta = maleta(v, sessenta, 6);
        UUID cliente = cliente(v);
        limiteSemanal("100.00");
        // Semanas de quarta a terça: 04/02 a 10/02 e 11/02 a 17/02/2026.
        semana(120_001, LocalDate.of(2026, 2, 4), LocalDate.of(2026, 2, 10));
        semana(120_002, LocalDate.of(2026, 2, 11), LocalDate.of(2026, 2, 17));
        UUID a = venderEm(v, maleta, cliente, sessenta, 1, "60.00", brasilia("2026-02-09T10:00:00"));
        UUID b = venderEm(v, maleta, cliente, sessenta, 1, "60.00", brasilia("2026-02-09T10:00:00"));
        // Terça 10/02 e quarta 11/02: de segunda a domingo seria a mesma semana; pelas semanas cadastradas, não.
        ServicoDeDevolucoes.Resultado terca = devolverPeloServico(v, a, "ARREPENDIMENTO", uma(sessenta), null,
                brasilia("2026-02-10T10:00:00"), ModoDaDevolucao.ONLINE);
        ServicoDeDevolucoes.Resultado quarta = devolverPeloServico(v, b, "ARREPENDIMENTO", uma(sessenta), null,
                brasilia("2026-02-11T10:00:00"), ModoDaDevolucao.ONLINE);
        assertThat(situacaoDaDevolucao(terca.devolucaoId())).isEqualTo("ACEITA");
        assertThat(situacaoDaDevolucao(quarta.devolucaoId())).isEqualTo("ACEITA");
    }

    @Test
    void limiteValeMesmoComAutorizacao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sessenta = peca("60.00", 10);
        UUID maleta = maleta(v, sessenta, 5);
        UUID cliente = cliente(v);
        UUID recente = vender(v, maleta, cliente, List.of(linha(sessenta, 1)), List.of(pagamento("60.00", "PIX")));
        UUID antiga = venderEm(v, maleta, cliente, sessenta, 1, "60.00", diasAtras(12));
        limiteSemanal("100.00");
        devolver(v, recente, "ARREPENDIMENTO", List.of(devolvida(sessenta, 1, "BOA")));
        UUID autorizacao = id(pedir(v, antiga, "ARREPENDIMENTO", List.of(devolvida(sessenta, 1, "BOA"))));
        aprovar(autorizacao);
        JsonNode d = corpoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(antiga, "ARREPENDIMENTO", autorizacao,
                List.of(devolvida(sessenta, 1, "BOA")))).andExpect(status().isCreated()));
        assertThat(d.get("situacao").asString()).isEqualTo("EM_REVISAO");
        assertThat(d.get("motivosRevisao").toString()).isEqualTo("[\"ACIMA_DO_LIMITE_SEMANAL\"]");
        assertThat(d.get("autorizacaoId").asString()).isEqualTo(autorizacao.toString());
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("USADA");
    }

    @Test
    void duasDevolucoesDoMesmoVendedorAoMesmoTempoSomamUmaDepoisDaOutra() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID sessenta = peca("60.00", 20);
        UUID maleta = maleta(v, sessenta, 10);
        UUID cliente = cliente(v);
        limiteSemanal("100.00");
        RequestPostProcessor quem = como(v);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int rodada = 0; rodada < 3; rodada++) {
                UsuarioDeTeste r = rodada == 0 ? v : usuarios.criarVendedor();
                RequestPostProcessor token = rodada == 0 ? quem : como(r);
                UUID maletaDaRodada = rodada == 0 ? maleta : maleta(r, sessenta, 2);
                UUID clienteDaRodada = rodada == 0 ? cliente : cliente(r);
                UUID primeira = vender(r, maletaDaRodada, clienteDaRodada, List.of(linha(sessenta, 1)), List.of(pagamento("60.00", "PIX")));
                UUID segunda = vender(r, maletaDaRodada, clienteDaRodada, List.of(linha(sessenta, 1)), List.of(pagamento("60.00", "PIX")));
                CountDownLatch largada = new CountDownLatch(1);
                List<Future<JsonNode>> resultados = new ArrayList<>();
                for (UUID venda : List.of(primeira, segunda)) {
                    resultados.add(pool.submit(() -> {
                        largada.await();
                        return corpoDe(postar(DEVOLUCOES, token, devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                                List.of(devolvida(sessenta, 1, "BOA")))).andExpect(status().isCreated()));
                    }));
                }
                largada.countDown();
                List<String> situacoes = new ArrayList<>();
                for (Future<JsonNode> f : resultados) {
                    situacoes.add(f.get(60, TimeUnit.SECONDS).get("situacao").asString());
                }
                assertThat(situacoes).as("rodada %d", rodada).containsExactlyInAnyOrder("ACEITA", "EM_REVISAO");
            }
        } finally {
            pool.shutdown();
        }
    }

    // --- 7. valor de aprovação ---

    @Test
    void valorDeAprovacaoNaRotaPedeAutorizacaoENoEnvioVaiParaRevisao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cinquenta = peca("50.00", 10);
        UUID cinquentaEUm = peca("50.01", 10);
        UUID maleta = maleta(v, cinquenta, 5, cinquentaEUm, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(cinquenta, 2), linha(cinquentaEUm, 3)),
                List.of(pagamento("250.03", "PIX")));
        // Vazio: nunca.
        devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(cinquentaEUm, 1, "BOA")));
        valorDeAprovacao("50.00");
        assertThat(devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(cinquenta, 1, "BOA"))).get("situacao").asString())
                .isEqualTo("ACEITA");
        int devolucoesAntes = contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda);
        BigDecimal pagamentosAntes = somaDosPagamentos(venda);
        JsonNode erro = corpoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(cinquentaEUm, 1, "BOA")))).andExpect(status().isConflict()));
        assertThat(erro.get("codigo").asString()).isEqualTo("DEVOLUCAO_PRECISA_DE_AUTORIZACAO");
        assertThat(erro.get("detail").asString()).isEqualTo(
                "Esta devolução passa do valor que a dona aprova antes. Peça autorização à dona.");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isEqualTo(devolucoesAntes);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo(pagamentosAntes);

        ServicoDeDevolucoes.Resultado peloEnvio = devolverPeloServico(v, venda, "ARREPENDIMENTO", uma(cinquentaEUm), null, null,
                ModoDaDevolucao.ENVIO);
        assertThat(situacaoDaDevolucao(peloEnvio.devolucaoId())).isEqualTo("EM_REVISAO");
        assertThat(motivosGravados(peloEnvio.devolucaoId())).containsExactly("ACIMA_DO_VALOR_SEM_AUTORIZACAO");

        // Com a autorização "acima do valor" aprovada: aceita.
        JsonNode pedido = pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(cinquentaEUm, 1, "BOA")));
        assertThat(pedido.get("tipo").asString()).isEqualTo("ACIMA_DO_VALOR");
        aprovar(id(pedido));
        JsonNode autorizada = corpoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(venda, "ARREPENDIMENTO", id(pedido),
                List.of(devolvida(cinquentaEUm, 1, "BOA")))).andExpect(status().isCreated()));
        assertThat(autorizada.get("situacao").asString()).isEqualTo("ACEITA");
        assertThat(autorizada.get("dentroGarantia").asBoolean()).isTrue();
    }

    @Test
    void autorizacaoForaDoPrazoTambemDispensaOValor() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID oitenta = peca("80.00", 10);
        UUID maleta = maleta(v, oitenta, 5);
        UUID venda = venderEm(v, maleta, cliente(v), oitenta, 1, "80.00", diasAtras(12));
        valorDeAprovacao("50.00");
        JsonNode pedido = pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(oitenta, 1, "BOA")));
        assertThat(pedido.get("tipo").asString()).isEqualTo("FORA_DO_PRAZO");
        aprovar(id(pedido));
        JsonNode d = corpoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(venda, "ARREPENDIMENTO", id(pedido),
                List.of(devolvida(oitenta, 1, "BOA")))).andExpect(status().isCreated()));
        assertThat(d.get("situacao").asString()).isEqualTo("ACEITA");
    }

    // --- 8. reembolso maior que o pago ---

    @Test
    void reembolsoMaiorQueOPagoVaiParaRevisao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cinquenta = peca("50.00", 10);
        UUID maleta = maleta(v, cinquenta, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(cinquenta, 1)), List.of(pagamento("50.00", "PIX")));
        // Um reembolso avulso (dado torto) gravado direto no banco.
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em)
                VALUES (?, ?, 'REEMBOLSO', 'DINHEIRO', -30.00, now())""", UUID.randomUUID(), venda);
        JsonNode d = devolver(v, venda, "ARREPENDIMENTO", List.of(devolvida(cinquenta, 1, "BOA")));
        assertThat(d.get("situacao").asString()).isEqualTo("EM_REVISAO");
        assertThat(d.get("motivosRevisao").toString()).isEqualTo("[\"REEMBOLSO_MAIOR_QUE_O_PAGO\"]");
    }

    // --- 9. motivos juntos ---

    @Test
    void envioForaDoPrazoEAcimaDoLimiteTemOsDoisMotivosEmOrdemFixa() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cento = peca("120.00", 10);
        UUID maleta = maleta(v, cento, 5);
        UUID venda = venderEm(v, maleta, cliente(v), cento, 1, "120.00", diasAtras(12));
        limiteSemanal("100.00");
        ServicoDeDevolucoes.Resultado r = devolverPeloServico(v, venda, "ARREPENDIMENTO", uma(cento), null, null,
                ModoDaDevolucao.ENVIO);
        assertThat(motivosGravados(r.devolucaoId())).containsExactly("FORA_DO_PRAZO", "ACIMA_DO_LIMITE_SEMANAL");
        JsonNode lida = corpoDe(ler(DEVOLUCOES + "/" + r.devolucaoId(), como(v)).andExpect(status().isOk()));
        assertThat(lida.get("motivosRevisao").toString()).isEqualTo("[\"FORA_DO_PRAZO\",\"ACIMA_DO_LIMITE_SEMANAL\"]");
    }

    // --- 10. revisão da dona ---

    @Test
    void manterENaoAceitarNaoMexemEmNadaESoValemParaRevisao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("30.00", 20);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        UUID vendaA = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID vendaB = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID mantida = devolverPeloServico(v, vendaA, "ARREPENDIMENTO", uma(anel), null, null, ModoDaDevolucao.ENVIO).devolucaoId();
        UUID naoAceita = devolverPeloServico(v, vendaB, "ARREPENDIMENTO", uma(anel), null, null, ModoDaDevolucao.ENVIO).devolucaoId();
        int saldoAntes = saldo(maleta, anel);
        BigDecimal pagamentosA = somaDosPagamentos(vendaA);
        BigDecimal pagamentosB = somaDosPagamentos(vendaB);

        // A lista da dona (padrão: para revisão) tem as duas.
        JsonNode lista = corpoDe(ler(DEVOLUCOES_DA_DONA + "?vendedorId=" + v.vendedorId(), dona()).andExpect(status().isOk()));
        assertThat(lista).hasSize(2);

        JsonNode m = corpoDe(postar(DEVOLUCOES_DA_DONA + "/" + mantida + "/manter", dona(), mapa()).andExpect(status().isOk()));
        assertThat(m.get("situacao").asString()).isEqualTo("MANTIDA");
        assertThat(m.get("revisadaEm").asString()).isNotBlank();
        postar(DEVOLUCOES_DA_DONA + "/" + mantida + "/manter", dona(), mapa()).andExpect(status().isOk());
        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA + "/" + mantida + "/nao-aceitar", dona(), mapa("observacao", "x"))
                .andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_JA_REVISADA");

        postar(DEVOLUCOES_DA_DONA + "/" + naoAceita + "/nao-aceitar", dona(), mapa()).andExpect(status().isBadRequest());
        JsonNode n = corpoDe(postar(DEVOLUCOES_DA_DONA + "/" + naoAceita + "/nao-aceitar", dona(),
                mapa("observacao", "Cliente não tinha nota")).andExpect(status().isOk()));
        assertThat(n.get("situacao").asString()).isEqualTo("NAO_ACEITA");
        assertThat(n.get("observacaoRevisao").asString()).isEqualTo("Cliente não tinha nota");
        assertThat(dec(n.get("pendenciaDoVendedor"))).isEqualByComparingTo("30.00");
        postar(DEVOLUCOES_DA_DONA + "/" + naoAceita + "/nao-aceitar", dona(), mapa("observacao", "Cliente não tinha nota"))
                .andExpect(status().isOk());
        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA + "/" + naoAceita + "/manter", dona(), mapa())
                .andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_JA_REVISADA");

        // Nada mudou na maleta nem no dinheiro.
        assertThat(saldo(maleta, anel)).isEqualTo(saldoAntes);
        assertThat(somaDosPagamentos(vendaA)).isEqualByComparingTo(pagamentosA);
        assertThat(somaDosPagamentos(vendaB)).isEqualByComparingTo(pagamentosB);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE registro_id = ? AND acao = 'DEVOLUCAO_MANTIDA'", mantida))
                .isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE registro_id = ? AND acao = 'DEVOLUCAO_NAO_ACEITA'", naoAceita))
                .isEqualTo(1);

        // Depois da decisão, cancelar = 409 (regra da 6.3).
        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA + "/" + mantida + "/cancelar", dona(), mapa("id", UUID.randomUUID(),
                "motivo", "x")).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_NAO_PODE_SER_CANCELADA");

        // Devolução aceita: não está para revisão.
        UUID vendaC = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("30.00", "PIX")));
        JsonNode aceita = devolver(v, vendaC, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA")));
        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA + "/" + aceita.get("id").asString() + "/manter", dona(), mapa())
                .andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_NAO_ESTA_EM_REVISAO");
        postar(DEVOLUCOES_DA_DONA + "/" + UUID.randomUUID() + "/manter", dona(), mapa()).andExpect(status().isNotFound());

        // O banco recusa: mantida sem quem revisou, motivo da revisão mudado, aceita com motivo.
        UUID vendaD = venderEm(v, maleta, cliente, anel, 1, "30.00", diasAtras(12));
        UUID emRevisao = devolverPeloServico(v, vendaD, "ARREPENDIMENTO", uma(anel), null, null, ModoDaDevolucao.ENVIO).devolucaoId();
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET situacao = 'MANTIDA' WHERE id = ?", emRevisao))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET revisao_acima_do_limite_semanal = true WHERE id = ?",
                emRevisao)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET revisao_fora_do_prazo = true WHERE id = ?",
                UUID.fromString(aceita.get("id").asString()))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET revisada_em = now(), revisada_por = ? WHERE id = ?",
                usuarios.dona().id(), emRevisao)).isInstanceOf(Exception.class);
        assertThat(situacaoDaDevolucao(emRevisao)).isEqualTo("EM_REVISAO");
    }

    // --- 11. taxa de devolução ---

    @Test
    void taxaDeDevolucaoAvisaSoAcimaDoPercentualNasUltimas4Semanas() throws Exception {
        UUID cem = peca("100.00", 200);
        UUID centavo = peca("0.01", 200);

        // A: vendeu 1.000,00 e devolveu 200,00 = 20% exato: sem aviso. Depois mais 0,01 vendido e devolvido: aviso.
        UsuarioDeTeste a = usuarios.criarVendedor();
        UUID maletaA = maleta(a, cem, 10, centavo, 1);
        UUID clienteA = cliente(a);
        UUID vendaA = vender(a, maletaA, clienteA, List.of(linha(cem, 10)), List.of(pagamento("1000.00", "PIX")));
        devolver(a, vendaA, "ARREPENDIMENTO", List.of(devolvida(cem, 2, "BOA")));
        assertThat(muitasDevolucoesDe(a)).isEmpty();
        UUID vendaA2 = vender(a, maletaA, clienteA, List.of(linha(centavo, 1)), List.of(pagamento("0.01", "PIX")));
        devolver(a, vendaA2, "ARREPENDIMENTO", List.of(devolvida(centavo, 1, "BOA")));
        List<AvisoDaDona> deA = muitasDevolucoesDe(a);
        assertThat(deA).hasSize(1);
        String nomeA = nomeDoVendedor(a);
        assertThat(deA.get(0).texto()).isEqualTo("Nas últimas 4 semanas, " + nomeA + " devolveu 20% do que vendeu. "
                + "Confira as devoluções dele.");

        // B: 10%: sem aviso; com o percentual em 5 (pela rota da garantia), aviso na hora.
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID maletaB = maleta(b, cem, 10);
        UUID vendaB = vender(b, maletaB, cliente(b), List.of(linha(cem, 10)), List.of(pagamento("1000.00", "PIX")));
        devolver(b, vendaB, "ARREPENDIMENTO", List.of(devolvida(cem, 1, "BOA")));
        assertThat(muitasDevolucoesDe(b)).isEmpty();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dona/configuracoes/garantia")
                .with(dona()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("prazoArrependimentoDias", 7, "prazoDefeitoDias", 30,
                        "percentualAlertaDevolucao", 5)))).andExpect(status().isOk());
        assertThat(muitasDevolucoesDe(b)).hasSize(1);
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 20 WHERE chave = 'percentual_alerta_devolucao'");

        // C: venda e devolução do 29º dia para trás não contam.
        UsuarioDeTeste c = usuarios.criarVendedor();
        UUID maletaC = maleta(c, cem, 2);
        UUID vendaC = venderEm(c, maletaC, cliente(c), cem, 1, "100.00", diasAtras(29));
        devolverPeloServico(c, vendaC, "ARREPENDIMENTO", uma(cem), null, diasAtras(28), ModoDaDevolucao.ONLINE);
        assertThat(muitasDevolucoesDe(c)).isEmpty();

        // D: no 28º dia (o primeiro da janela) contam.
        UsuarioDeTeste d = usuarios.criarVendedor();
        UUID maletaD = maleta(d, cem, 2);
        UUID vendaD = venderEm(d, maletaD, cliente(d), cem, 1, "100.00", diasAtras(27));
        devolverPeloServico(d, vendaD, "ARREPENDIMENTO", uma(cem), null, diasAtras(27).plusSeconds(3600), ModoDaDevolucao.ONLINE);
        assertThat(muitasDevolucoesDe(d)).hasSize(1);
        assertThat(muitasDevolucoesDe(d).get(0).texto()).contains("devolveu 100% do que vendeu");

        // E: devolução cancelada não conta.
        UsuarioDeTeste e = usuarios.criarVendedor();
        UUID maletaE = maleta(e, cem, 2);
        UUID vendaE = vender(e, maletaE, cliente(e), List.of(linha(cem, 1)), List.of(pagamento("100.00", "PIX")));
        JsonNode cancelada = devolver(e, vendaE, "ARREPENDIMENTO", List.of(devolvida(cem, 1, "BOA")));
        assertThat(muitasDevolucoesDe(e)).hasSize(1);
        postar(DEVOLUCOES + "/" + cancelada.get("id").asString() + "/cancelar", como(e), mapa("id", UUID.randomUUID(),
                "motivo", "engano")).andExpect(status().isCreated());
        assertThat(muitasDevolucoesDe(e)).isEmpty();

        // F: venda estornada não conta como vendido (30 de 130 = 23%; se contasse, 30 de 1.030 = 3%).
        UsuarioDeTeste f = usuarios.criarVendedor();
        UUID trinta = peca("30.00", 10);
        UUID maletaF = maleta(f, cem, 10, trinta, 2);
        UUID clienteF = cliente(f);
        UUID estornada = vender(f, maletaF, clienteF, List.of(linha(cem, 9)), List.of(pagamento("900.00", "PIX")));
        postar(VENDAS + "/" + estornada + "/estorno", como(f), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        vender(f, maletaF, clienteF, List.of(linha(cem, 1)), List.of(pagamento("100.00", "PIX")));
        UUID vendaF2 = vender(f, maletaF, clienteF, List.of(linha(trinta, 1)), List.of(pagamento("30.00", "PIX")));
        devolver(f, vendaF2, "ARREPENDIMENTO", List.of(devolvida(trinta, 1, "BOA")));
        assertThat(muitasDevolucoesDe(f)).hasSize(1);

        // G: devolução e nenhuma venda na janela: aviso.
        UsuarioDeTeste g = usuarios.criarVendedor();
        UUID maletaG = maleta(g, cem, 2);
        UUID vendaG = venderEm(g, maletaG, cliente(g), cem, 1, "100.00", diasAtras(29));
        devolverPeloServico(g, vendaG, "DEFEITO", List.of(new PecaDoPedido(cem, 1, "DEFEITO")), null, diasAtras(0),
                ModoDaDevolucao.ONLINE);
        assertThat(muitasDevolucoesDe(g)).hasSize(1);
        assertThat(muitasDevolucoesDe(g).get(0).texto()).isEqualTo("Nas últimas 4 semanas, " + nomeDoVendedor(g)
                + " teve devoluções e nenhuma venda. Confira as devoluções dele.");

        // H: vendedor desativado não aparece.
        UsuarioDeTeste h = usuarios.criarVendedor();
        UUID maletaH = maleta(h, cem, 2);
        UUID vendaH = vender(h, maletaH, cliente(h), List.of(linha(cem, 1)), List.of(pagamento("100.00", "PIX")));
        devolver(h, vendaH, "ARREPENDIMENTO", List.of(devolvida(cem, 1, "BOA")));
        assertThat(muitasDevolucoesDe(h)).hasSize(1);
        jdbc.update("UPDATE corona.vendedores SET ativo = false WHERE id = ?", h.vendedorId());
        assertThat(muitasDevolucoesDe(h)).isEmpty();

        // A rota dos avisos da dona junta este aviso aos outros.
        JsonNode avisos = corpoDe(ler("/api/dona/avisos", dona()).andExpect(status().isOk()));
        assertThat(avisos.get("avisos")).anyMatch(x -> x.get("titulo").asString().equals("Muitas devoluções de " + nomeA + "."));
    }

    // --- 12. avisos ---

    @Test
    void avisosDePedidosEDeRevisoesContamESomemQuandoADonaDecide() throws Exception {
        assertThat(FonteDeAvisosDasDevolucoes.avisoDosPedidos(0)).isEmpty();
        assertThat(FonteDeAvisosDasDevolucoes.avisoDosPedidos(1).orElseThrow().titulo())
                .isEqualTo("Há 1 pedido de autorização de devolução esperando você.");
        assertThat(FonteDeAvisosDasDevolucoes.avisoDosPedidos(3).orElseThrow().titulo())
                .isEqualTo("Há 3 pedidos de autorização de devolução esperando você.");
        assertThat(FonteDeAvisosDasDevolucoes.avisoDosPedidos(3).orElseThrow().texto())
                .isEqualTo("Abra Devoluções para autorizar e escolha Aprovar ou Negar.");
        assertThat(FonteDeAvisosDasDevolucoes.avisoDasRevisoes(0)).isEmpty();
        assertThat(FonteDeAvisosDasDevolucoes.avisoDasRevisoes(1).orElseThrow().titulo()).isEqualTo("Há 1 devolução para revisar.");
        assertThat(FonteDeAvisosDasDevolucoes.avisoDasRevisoes(2).orElseThrow().titulo()).isEqualTo("Há 2 devoluções para revisar.");
        assertThat(FonteDeAvisosDasDevolucoes.avisoDasRevisoes(2).orElseThrow().texto())
                .isEqualTo("Abra Devoluções para revisar e escolha Manter ou Não aceitar.");

        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("30.00", 20);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        UUID venda = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID outra = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID pedido = id(pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA"))));
        UUID revisao = devolverPeloServico(v, outra, "ARREPENDIMENTO", uma(anel), null, null, ModoDaDevolucao.ENVIO).devolucaoId();
        long pedidas = contar("SELECT count(*) FROM corona.autorizacoes_excecao WHERE situacao = 'PEDIDA'");
        long emRevisao = contar("SELECT count(*) FROM corona.devolucoes WHERE situacao = 'EM_REVISAO'");
        assertThat(avisosDe("DEVOLUCOES_PARA_AUTORIZAR")).containsExactly(FonteDeAvisosDasDevolucoes.avisoDosPedidos(pedidas).orElseThrow());
        assertThat(avisosDe("DEVOLUCOES_PARA_REVISAR")).containsExactly(FonteDeAvisosDasDevolucoes.avisoDasRevisoes(emRevisao).orElseThrow());

        aprovar(pedido);
        postar(DEVOLUCOES_DA_DONA + "/" + revisao + "/manter", dona(), mapa()).andExpect(status().isOk());
        assertThat(avisosDe("DEVOLUCOES_PARA_AUTORIZAR")).isEqualTo(
                FonteDeAvisosDasDevolucoes.avisoDosPedidos(pedidas - 1).stream().toList());
        assertThat(avisosDe("DEVOLUCOES_PARA_REVISAR")).isEqualTo(
                FonteDeAvisosDasDevolucoes.avisoDasRevisoes(emRevisao - 1).stream().toList());
        // Os avisos que já existiam continuam na rota (a fonte da cotação, por exemplo, segue registrada).
        ler("/api/dona/avisos", dona()).andExpect(status().isOk());
    }

    // --- 16. conciliação ---

    @Test
    void conciliacaoFechaComDevolucaoAutorizadaEmRevisaoMantidaENaoAceita() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("30.00", 20);
        UUID maleta = maleta(v, anel, 12);
        UUID cliente = cliente(v);
        UUID vendaA = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID vendaB = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID vendaC = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID vendaD = venderEm(v, maleta, cliente, anel, 2, "60.00", diasAtras(12));
        UUID autorizacao = id(pedir(v, vendaA, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA"))));
        aprovar(autorizacao);
        postar(DEVOLUCOES, como(v), devolucaoAutorizada(vendaA, "ARREPENDIMENTO", autorizacao, List.of(devolvida(anel, 1, "BOA"))))
                .andExpect(status().isCreated());
        devolverPeloServico(v, vendaB, "ARREPENDIMENTO", uma(anel), null, null, ModoDaDevolucao.ENVIO);
        UUID mantida = devolverPeloServico(v, vendaC, "ARREPENDIMENTO", uma(anel), null, null, ModoDaDevolucao.ENVIO).devolucaoId();
        UUID naoAceita = devolverPeloServico(v, vendaD, "ARREPENDIMENTO", uma(anel), null, null, ModoDaDevolucao.ENVIO).devolucaoId();
        postar(DEVOLUCOES_DA_DONA + "/" + mantida + "/manter", dona(), mapa()).andExpect(status().isOk());
        postar(DEVOLUCOES_DA_DONA + "/" + naoAceita + "/nao-aceitar", dona(), mapa("observacao", "não")).andExpect(status().isOk());

        // 12 enviadas - 8 vendidas + 4 boas que voltaram.
        assertThat(saldo(maleta, anel)).isEqualTo(8);
        assertThat(estoque.conciliar().divergencias()).noneMatch(x -> x.produtoId().equals(anel));
        assertThat(estoque.conciliar().diferencasComMaletas()).noneMatch(x -> x.produtoId().equals(anel));
    }

    // --- 17. registro de tabelas e pull ---

    @Test
    void asTabelasNovasEstaoNoRegistroMasNadaDelasVaiAoVendedorAinda() throws Exception {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        for (String nome : List.of("autorizacoes_excecao", "itens_autorizacao")) {
            TabelaSincronizavel t = registro.sincronizavel(nome).orElseThrow();
            assertThat(t.regra().declarada()).as(nome).isTrue();
            assertThat(t.regra().condicao()).as(nome).isEqualTo("FALSE");
        }
        assertThat(registro.sincronizavel("itens_autorizacao").orElseThrow().imutavel()).isTrue();
        assertThat(registro.sincronizavel("autorizacoes_excecao").orElseThrow().colunas())
                .contains("valida_ate", "situacao", "valor_pedido").doesNotContain("pedida_por", "decidida_por");
        assertThat(registro.sincronizavel("devolucoes").orElseThrow().colunas())
                .contains("revisao_fora_do_prazo", "revisao_acima_do_limite_semanal", "revisao_acima_do_valor_de_aprovacao",
                        "revisao_autorizacao_invalida", "revisao_reembolso_maior_que_pago", "revisada_em", "observacao_revisao")
                .doesNotContain("revisada_por");
        assertThat(registro.sincronizavel("devolucoes").orElseThrow().regra().condicao()).isEqualTo("FALSE");

        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("30.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 2, "60.00", diasAtras(12));
        pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA")));

        List<LinhaDoPull> recebidas = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 100; i++) {
            LoteDoPull lote = pull.puxar(cursor, 500, new FiltroPorVendedor(v.vendedorId()));
            recebidas.addAll(lote.linhas());
            cursor = lote.proximoCursor();
            if (!lote.haMais()) {
                break;
            }
        }
        assertThat(recebidas).noneMatch(l -> l.tabela().equals("autorizacoes_excecao") || l.tabela().equals("itens_autorizacao"));
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("vendas")).isNotEmpty();
    }
}
