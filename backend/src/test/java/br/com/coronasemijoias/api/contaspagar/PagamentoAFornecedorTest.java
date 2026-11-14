package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Pagar a fornecedor e estornar o pagamento (tarefa 7.7; ADR-0012, itens 12, 15 e 16; condições C4 e C5): a baixa e a saída de caixa
 * na mesma transação, o pagamento parcial, o limite do saldo, as formas, a data, a idempotência e o estorno.
 */
class PagamentoAFornecedorTest extends ApoioDasContasAPagar {

    private UUID compra;
    private List<UUID> contas;

    @BeforeEach
    void umaCompraEmDuasParcelas() throws Exception {
        compra = compra(fornecedor(), "100.00");
        contas = contasDe(compra, 2, LocalDate.of(2027, 6, 10));
    }

    private static LocalDate hoje() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    private List<Map<String, Object>> lancamentosDe(UUID baixa) {
        return jdbc.queryForList("""
                SELECT l.tipo, l.valor, l.forma, l.data_lancamento, c.nome AS categoria, l.estorna_id FROM corona.lancamentos_caixa l
                  JOIN corona.categorias_caixa c ON c.id = l.categoria_id WHERE l.origem_tipo = 'PAGAMENTO_FORNECEDOR' AND l.origem_id = ?""", baixa);
    }

    @Test
    void pagamentoParcialDepoisOResto() throws Exception {
        UUID conta = contas.get(0);
        UUID p1 = UUID.randomUUID();
        JsonNode r = corpoDe(pagar(conta, p1, "20.00", "PIX").andExpect(status().isCreated()));
        assertThat(r.get("situacao").asString()).isEqualTo("PARCIAL");
        assertThat(r.get("saldo").asString()).isEqualTo("30.00");
        assertThat(situacaoDaConta(conta)).startsWith("PARCIAL:20.00");
        List<Map<String, Object>> caixa = lancamentosDe(p1);
        assertThat(caixa).hasSize(1);
        assertThat((BigDecimal) caixa.get(0).get("valor")).isEqualByComparingTo("-20.00");
        assertThat(caixa.get(0).get("tipo")).isEqualTo("SAIDA");
        assertThat(caixa.get(0).get("forma")).isEqualTo("PIX");
        assertThat(caixa.get(0).get("categoria")).isEqualTo("Pagamento a fornecedor");
        assertThat(caixa.get(0).get("data_lancamento")).isEqualTo(java.sql.Date.valueOf(hoje()));

        UUID p2 = UUID.randomUUID();
        r = corpoDe(pagar(conta, p2, "30.00", "BOLETO").andExpect(status().isCreated()));
        assertThat(r.get("situacao").asString()).isEqualTo("PAGA");
        assertThat(r.get("baixas")).hasSize(2);
        assertThat(situacaoDaConta(conta)).startsWith("PAGA:50.00");
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'PAGAMENTO_FORNECEDOR_REGISTRADO' AND registro_id IN (?, ?)", p1, p2))
                .isEqualTo(2);
        // Paga por inteiro: não aceita mais nada.
        pagar(conta, UUID.randomUUID(), "0.01", "DINHEIRO").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("PAGAMENTO_ACIMA_DO_SALDO"));
    }

    @Test
    void acimaDoSaldoERecusadoComOTextoDoSaldo() throws Exception {
        UUID conta = contas.get(0);
        pagar(conta, UUID.randomUUID(), "50.01", "DINHEIRO").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("PAGAMENTO_ACIMA_DO_SALDO"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("R$ 50,00")));
        assertThat(situacaoDaConta(conta)).startsWith("ABERTA:0.00");
    }

    @Test
    void chequeCartaoValorRuimEDataFuturaSaoRecusados() throws Exception {
        UUID conta = contas.get(0);
        for (String forma : new String[] {"CHEQUE", "CARTAO", "ENTRADA", ""}) {
            pagar(conta, UUID.randomUUID(), "10.00", forma).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("forma"));
        }
        for (String valor : new String[] {"0", "-5.00", "10.005"}) {
            pagar(conta, UUID.randomUUID(), valor, "PIX").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("valor"));
        }
        postar(CONTAS + "/" + conta + "/pagamentos", mapa("id", UUID.randomUUID(), "valor", "10.00", "forma", "PIX", "data", hoje().plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("data"));
        assertThat(contar("SELECT count(*) FROM corona.baixas_pagar WHERE conta_id = ?", conta)).isZero();
    }

    @Test
    void dataNoPassadoEAceita() throws Exception {
        UUID conta = contas.get(0);
        UUID id = UUID.randomUUID();
        postar(CONTAS + "/" + conta + "/pagamentos", mapa("id", id, "valor", "10.00", "forma", "DINHEIRO", "data", "2020-05-05"))
                .andExpect(status().isCreated());
        assertThat(lancamentosDe(id).get(0).get("data_lancamento")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2020, 5, 5)));
    }

    @Test
    void reenvioIgualDa200EOutroConteudoComOMesmoIdDa409() throws Exception {
        UUID conta = contas.get(0);
        UUID id = UUID.randomUUID();
        pagar(conta, id, "10.00", "PIX").andExpect(status().isCreated());
        pagar(conta, id, "10.00", "PIX").andExpect(status().isOk());
        pagar(conta, id, "11.00", "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PAGAMENTO_A_FORNECEDOR_JA_REGISTRADO_DIFERENTE"));
        pagar(contas.get(1), id, "10.00", "PIX").andExpect(status().isConflict());
        assertThat(contar("SELECT count(*) FROM corona.baixas_pagar WHERE id = ?", id)).isEqualTo(1);
        assertThat(lancamentosDe(id)).hasSize(1);
    }

    @Test
    void contaInexistenteDa404() throws Exception {
        pagar(UUID.randomUUID(), UUID.randomUUID(), "10.00", "PIX").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CONTA_A_PAGAR_NAO_ENCONTRADA"));
    }

    @Test
    void estornoVoltaOCaixaEAContaEOSegundoEstornoDa409() throws Exception {
        UUID conta = contas.get(0);
        UUID pagamento = UUID.randomUUID();
        pagar(conta, pagamento, "50.00", "DINHEIRO").andExpect(status().isCreated());
        assertThat(situacaoDaConta(conta)).startsWith("PAGA:50.00");

        UUID estorno = UUID.randomUUID();
        String rota = CONTAS + "/pagamentos/" + pagamento + "/estorno";
        postar(rota, mapa("id", estorno, "motivo", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("motivo"));
        JsonNode r = corpoDe(postar(rota, mapa("id", estorno, "motivo", "Paguei o fornecedor errado")).andExpect(status().isCreated()));
        assertThat(r.get("situacao").asString()).isEqualTo("ABERTA");
        assertThat(r.get("saldo").asString()).isEqualTo("50.00");
        assertThat(situacaoDaConta(conta)).startsWith("ABERTA:0.00");
        List<Map<String, Object>> doEstorno = lancamentosDe(estorno);
        assertThat(doEstorno).hasSize(1);
        assertThat((BigDecimal) doEstorno.get(0).get("valor")).isEqualByComparingTo("50.00");
        assertThat(doEstorno.get(0).get("data_lancamento")).isEqualTo(java.sql.Date.valueOf(hoje()));
        // O original não mudou.
        assertThat((BigDecimal) lancamentosDe(pagamento).get(0).get("valor")).isEqualByComparingTo("-50.00");
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'PAGAMENTO_FORNECEDOR_ESTORNADO' AND registro_id = ?", estorno)).isEqualTo(1);

        // Mesmo id de estorno: 200. Outro id para o mesmo pagamento: 409.
        postar(rota, mapa("id", estorno, "motivo", "Paguei o fornecedor errado")).andExpect(status().isOk());
        postar(rota, mapa("id", UUID.randomUUID(), "motivo", "De novo")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PAGAMENTO_A_FORNECEDOR_JA_ESTORNADO"));
        // Estorno de estorno e pagamento que não existe: 404.
        postar(CONTAS + "/pagamentos/" + estorno + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isNotFound());
        postar(CONTAS + "/pagamentos/" + UUID.randomUUID() + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isNotFound());
        // O estorno usa o id de um pagamento que já existe: 409.
        postar(CONTAS + "/pagamentos/" + pagamento + "/estorno", mapa("id", pagamento, "motivo", "x")).andExpect(status().isConflict());
        // A conta pode ser paga de novo.
        pagar(conta, UUID.randomUUID(), "50.00", "PIX").andExpect(status().isCreated());
    }

    @Test
    void estornoDeParteDaContaDeixaAContaParcial() throws Exception {
        UUID conta = contas.get(0);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        pagar(conta, a, "20.00", "PIX").andExpect(status().isCreated());
        pagar(conta, b, "30.00", "PIX").andExpect(status().isCreated());
        postar(CONTAS + "/pagamentos/" + a + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "Valor errado")).andExpect(status().isCreated());
        assertThat(situacaoDaConta(conta)).startsWith("PARCIAL:30.00");
    }
}
