package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.testes.AcertosDeTeste;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * O envio das operações da tarefa 7.3: a VENDA e a TROCA com o parcelamento opcional (sem ele, o resumo é o mesmo de
 * antes), RECEBIMENTO_CLIENTE e ESTORNO_RECEBIMENTO_CLIENTE pelo mesmo serviço da rota online, com a hora corrigida.
 * PostgreSQL de verdade. Escritos antes do código.
 */
class PushDasContasTest extends ApoioDoEnvioDaFase6 {

    @Autowired
    OperacoesDoVendedor operacoes;

    private record Cenario(UsuarioDeTeste v, UUID cliente, UUID peca, UUID maleta) {
    }

    private Cenario cenario() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("100.00", 10);
        UUID maleta = maleta(v, peca, 8);
        return new Cenario(v, cliente, peca, maleta);
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), BRASILIA);
    }

    private Map<String, Object> vendaAPrazoPeloEnvio(Cenario c, UUID id, Map<String, Object> parcelamento) {
        Map<String, Object> op = vendaPeloEnvio(id, c.maleta(), c.cliente(), List.of(linha(c.peca(), 1)), List.of(),
                relogio.instant().minusSeconds(30));
        if (parcelamento != null) {
            dadosDe(op).put("parcelamento", parcelamento);
        }
        return op;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dadosDe(Map<String, Object> op) {
        return (Map<String, Object>) op.get("dados");
    }

    private static Map<String, Object> recebimentoPeloEnvio(UUID id, UUID venda, String valor, String forma, Instant quando) {
        return op(id, "RECEBIMENTO_CLIENTE", mapa("id", id, "vendaId", venda, "valor", valor, "forma", forma,
                "recebidoEm", quando == null ? null : quando.toString()));
    }

    private static Map<String, Object> estornoPeloEnvio(UUID id, UUID recebimento, Instant quando) {
        return op(id, "ESTORNO_RECEBIMENTO_CLIENTE", mapa("id", id, "recebimentoId", recebimento, "motivo", "Digitei errado",
                "estornadoEm", quando == null ? null : quando.toString()));
    }

    private List<BigDecimal> valoresDasParcelas(UUID venda) {
        return jdbc.queryForList("SELECT valor FROM corona.contas_receber WHERE venda_id = ? ORDER BY parcela", BigDecimal.class, venda);
    }

    private List<String> situacoes(UUID venda) {
        return jdbc.queryForList("SELECT status FROM corona.contas_receber WHERE venda_id = ? ORDER BY parcela", String.class, venda);
    }

    // --- o resumo canônico ---

    @Test
    void vendaSemParcelamentoTemOMesmoResumoDeAntesEComParcelamentoMuda() throws Exception {
        UUID id = UUID.fromString("0190a1b2-0000-7000-8000-000000000011");
        UUID maleta = UUID.fromString("0190a1b2-0000-7000-8000-000000000012");
        UUID cliente = UUID.fromString("0190a1b2-0000-7000-8000-000000000013");
        UUID peca = UUID.fromString("0190a1b2-0000-7000-8000-000000000014");
        Map<String, Object> dados = mapa("id", id, "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", peca, "quantidade", 2, "desconto", "1.5")), "pagamentos", List.of(),
                "totalConferido", "58.50", "vendidaEm", "2026-11-12T15:00:00Z");
        // A forma canônica da tarefa 5.5, escrita à parte: o resumo de uma venda sem parcelamento nunca muda.
        String canonico = "VENDA/1|id=" + id + "|remessaId=" + maleta + "|clienteId=" + cliente + "|itens=[" + peca
                + ":2:150;]|pagamentos=[]|totalConferido=5850|vendidaEm=2026-11-12T15:00:00Z|";
        String esperado = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonico.getBytes(StandardCharsets.UTF_8)));
        assertThat(operacoes.ler(id, "VENDA", 1, json.valueToTree(dados)).resumo()).isEqualTo(esperado);

        Map<String, Object> comParcelas = new LinkedHashMap<>(dados);
        comParcelas.put("parcelamento", mapa("quantidade", 3, "primeiroVencimento", "2026-12-01"));
        String comCanonico = canonico + "parcelamento=3:2026-12-01|";
        String comEsperado = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(comCanonico.getBytes(StandardCharsets.UTF_8)));
        assertThat(operacoes.ler(id, "VENDA", 1, json.valueToTree(comParcelas)).resumo()).isEqualTo(comEsperado).isNotEqualTo(esperado);
        // Uma quantidade diferente dá outro resumo.
        comParcelas.put("parcelamento", mapa("quantidade", 2, "primeiroVencimento", "2026-12-01"));
        assertThat(operacoes.ler(id, "VENDA", 1, json.valueToTree(comParcelas)).resumo()).isNotEqualTo(comEsperado);
    }

    // --- VENDA com parcelamento ---

    @Test
    void vendaComParcelamentoPeloEnvioGeraParcelasReenvioDaJaRecebidoEOutroParcelamentoDaConflito() throws Exception {
        Cenario c = cenario();
        UUID id = UUID.randomUUID();
        Map<String, Object> parcelamento = mapa("quantidade", 3, "primeiroVencimento", hoje().plusDays(5).toString());
        Map<String, Object> envio = vendaAPrazoPeloEnvio(c, id, parcelamento);
        JsonNode r = enviar(c.v(), List.of(envio));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(valoresDasParcelas(id)).hasSize(3);
        assertThat(valoresDasParcelas(id).get(2)).isEqualByComparingTo("33.34");
        // O mesmo envio de novo.
        assertThat(resultado(enviar(c.v(), List.of(envio)), 0)).isEqualTo("JA_RECEBIDO");
        assertThat(valoresDasParcelas(id)).hasSize(3);
        // Outro parcelamento com o mesmo id (e a mesma hora): conflito para a dona, sem mexer nas parcelas.
        Map<String, Object> outro = vendaAPrazoPeloEnvio(c, id, mapa("quantidade", 2,
                "primeiroVencimento", hoje().plusDays(5).toString()));
        dadosDe(outro).put("vendidaEm", dadosDe(envio).get("vendidaEm"));
        assertThat(resultado(enviar(c.v(), List.of(outro)), 0)).isEqualTo("CONFLITO");
        assertThat(valoresDasParcelas(id)).hasSize(3);
    }

    @Test
    void vendaSemParcelamentoPeloEnvioGeraUmaParcelaUmMesDepois() throws Exception {
        Cenario c = cenario();
        UUID id = UUID.randomUUID();
        assertThat(resultado(enviar(c.v(), List.of(vendaAPrazoPeloEnvio(c, id, null))), 0)).isEqualTo("ACEITO");
        assertThat(valoresDasParcelas(id)).hasSize(1);
        assertThat(valoresDasParcelas(id).get(0)).isEqualByComparingTo("100.00");
    }

    @Test
    void vendaPeloEnvioComCreditoSemSaldoGeraParcelaSobreOResto() throws Exception {
        Cenario c = cenario();
        // Crédito de 20,00 que já não tem saldo para os 60,00 pedidos: paga o que tinha e o resto vira parcela.
        UUID credito = creditoDeSobra(c.v(), c.cliente(), "100.00", "80.00");
        UUID id = UUID.randomUUID();
        Map<String, Object> venda = vendaPeloEnvio(id, c.maleta(), c.cliente(), List.of(linha(c.peca(), 1)),
                List.of(mapa("id", UUID.randomUUID(), "valor", "60.00", "forma", "CREDITO", "creditoId", credito)),
                relogio.instant().minusSeconds(30));
        assertThat(resultado(enviar(c.v(), List.of(venda)), 0)).isEqualTo("ACEITO");
        assertThat(contar("SELECT count(*) FROM corona.creditos_sem_saldo WHERE venda_id = ?", id)).isEqualTo(1);
        // 100,00 - 20,00 de crédito = 80,00 a receber, numa parcela.
        assertThat(valoresDasParcelas(id)).hasSize(1);
        assertThat(valoresDasParcelas(id).get(0)).isEqualByComparingTo("80.00");
    }

    // --- RECEBIMENTO_CLIENTE ---

    @Test
    void recebimentoPeloEnvioEntraReenvioDaJaRecebidoEOutroValorDaConflito() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        enviar(c.v(), List.of(vendaAPrazoPeloEnvio(c, venda, mapa("quantidade", 2, "primeiroVencimento", hoje().plusDays(5).toString()))));
        UUID recebimento = UUID.randomUUID();
        Instant quando = relogio.instant().minusSeconds(20);
        assertThat(resultado(enviar(c.v(), List.of(recebimentoPeloEnvio(recebimento, venda, "70.00", "PIX", quando))), 0))
                .isEqualTo("ACEITO");
        assertThat(situacoes(venda)).containsExactly("PAGA", "PARCIAL");
        assertThat(resultado(enviar(c.v(), List.of(recebimentoPeloEnvio(recebimento, venda, "70.00", "PIX", quando))), 0))
                .isEqualTo("JA_RECEBIDO");
        assertThat(contar("SELECT count(*) FROM corona.baixas_receber b JOIN corona.contas_receber x ON x.id = b.conta_id WHERE x.venda_id = ?",
                venda)).isEqualTo(2);
        assertThat(resultado(enviar(c.v(), List.of(recebimentoPeloEnvio(recebimento, venda, "60.00", "PIX", quando))), 0))
                .isEqualTo("CONFLITO");
    }

    @Test
    void recebimentoAcimaDoQueFaltaOuDeVendaEstornadaEhRecusadoComOMotivo() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        enviar(c.v(), List.of(vendaAPrazoPeloEnvio(c, venda, mapa("quantidade", 2, "primeiroVencimento", hoje().plusDays(5).toString()))));
        UUID acima = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(recebimentoPeloEnvio(acima, venda, "100.01", "PIX", relogio.instant().minusSeconds(20))));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("RECEBIMENTO_ACIMA_DO_EM_ABERTO");
        assertThat(situacoes(venda)).containsExactly("ABERTA", "ABERTA");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE id = ?", acima)).isZero();

        postar(VENDAS + "/" + venda + "/estorno", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "Desistiu")).andExpect(status().isCreated());
        JsonNode r2 = enviar(c.v(), List.of(recebimentoPeloEnvio(UUID.randomUUID(), venda, "10.00", "PIX", relogio.instant().minusSeconds(20))));
        assertThat(resultado(r2, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r2, 0)).isEqualTo("VENDA_JA_ESTORNADA");
    }

    @Test
    void recebimentoDeVendaDeOutroVendedorEhRecusadoSemDizerDeQuem() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        UUID venda = UUID.randomUUID();
        enviar(a.v(), List.of(vendaAPrazoPeloEnvio(a, venda, null)));
        JsonNode r = enviar(b.v(), List.of(recebimentoPeloEnvio(UUID.randomUUID(), venda, "10.00", "PIX", relogio.instant().minusSeconds(20))));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("VENDA_NAO_ENCONTRADA");
        assertThat(situacoes(venda)).containsExactly("ABERTA");
    }

    // --- ESTORNO_RECEBIMENTO_CLIENTE ---

    @Test
    void estornoDoRecebimentoPeloEnvioNoMesmoDiaEntraEOutroIdNoJaEstornadoEhRecusado() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        enviar(c.v(), List.of(vendaAPrazoPeloEnvio(c, venda, mapa("quantidade", 2, "primeiroVencimento", hoje().plusDays(5).toString()))));
        UUID recebimento = UUID.randomUUID();
        enviar(c.v(), List.of(recebimentoPeloEnvio(recebimento, venda, "30.00", "DINHEIRO", relogio.instant().minusSeconds(40))));
        UUID estorno = UUID.randomUUID();
        Instant quando = relogio.instant().minusSeconds(10);
        assertThat(resultado(enviar(c.v(), List.of(estornoPeloEnvio(estorno, recebimento, quando))), 0)).isEqualTo("ACEITO");
        assertThat(situacoes(venda)).containsExactly("ABERTA", "ABERTA");
        assertThat(resultado(enviar(c.v(), List.of(estornoPeloEnvio(estorno, recebimento, quando))), 0)).isEqualTo("JA_RECEBIDO");
        JsonNode r = enviar(c.v(), List.of(estornoPeloEnvio(UUID.randomUUID(), recebimento, quando)));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("RECEBIMENTO_JA_ESTORNADO");
    }

    @Test
    void aHoraCorrigidaDecideOMesmoDiaDoEstornoDoVendedor() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        enviar(c.v(), List.of(vendaAPrazoPeloEnvio(c, venda, null)));
        // Um recebimento de dois dias atrás (gravado pelo serviço, com a hora escolhida): hoje o vendedor não estorna mais.
        UUID antigo = UUID.randomUUID();
        var contas = servicoDeContas();
        contas.receber(venda, c.v().vendedorId(), c.v().id(), usuarios.aparelhoDe(c.v()),
                new br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Recebimento(antigo, new BigDecimal("10.00"), "PIX"),
                diasAtras(2));
        JsonNode r = enviar(c.v(), List.of(estornoPeloEnvio(UUID.randomUUID(), antigo, relogio.instant().minusSeconds(10))));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("ESTORNO_DE_RECEBIMENTO_SO_DA_DONA");
        assertThat(situacoes(venda)).containsExactly("PARCIAL");
    }

    @Autowired
    br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber contasDoContexto;

    private br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber servicoDeContas() {
        return contasDoContexto;
    }

    @Test
    void recebimentoNaoOlhaAMaletaMesmoEmAcerto() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        enviar(c.v(), List.of(vendaAPrazoPeloEnvio(c, venda, null)));
        try {
            AcertosDeTeste.emAcerto(jdbc, c.maleta());
            assertThat(resultado(enviar(c.v(), List.of(recebimentoPeloEnvio(UUID.randomUUID(), venda, "10.00", "PIX",
                    relogio.instant().minusSeconds(20)))), 0)).isEqualTo("ACEITO");
        } finally {
            AcertosDeTeste.sairDoAcerto(jdbc, c.maleta());
        }
    }

    // --- dependências ---

    @Test
    void recebimentoDependeDaVendaEOEstornoDoRecebimento() throws Exception {
        UUID venda = UUID.randomUUID();
        UUID recebimento = UUID.randomUUID();
        var r = operacoes.ler(recebimento, "RECEBIMENTO_CLIENTE", 1, json.valueToTree(
                dadosDe(recebimentoPeloEnvio(recebimento, venda, "1.00", "PIX", null))));
        assertThat(operacoes.dependencias(r)).containsExactly(venda);
        UUID estorno = UUID.randomUUID();
        var e = operacoes.ler(estorno, "ESTORNO_RECEBIMENTO_CLIENTE", 1, json.valueToTree(dadosDe(estornoPeloEnvio(estorno, recebimento, null))));
        assertThat(operacoes.dependencias(e)).containsExactly(recebimento);
    }
}
