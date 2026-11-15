package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * O recebimento avulso do vendedor e o estorno dele, pela API da dona (tarefa 7.6; ADR-0012, itens 7, 8, 9, 11 e 16): a
 * distribuição por vencimento, a conta indicada, o boleto pago, o caixa, a idempotência e as permissões.
 */
class RecebimentosDoVendedorTest extends ApoioDoFechamento {

    private static final String RECEBIMENTOS = "/api/dona/recebimentos-vendedor";

    private UsuarioDeTeste vendedor;
    private UUID semana;
    private UUID peca;
    private UUID maleta;
    private UUID cliente;

    @BeforeEach
    void preparar() throws Exception {
        vendedor = usuarios.criarVendedor();
        semana = semana();
        peca = peca("50.00", 30);
        maleta = maletaConferida(vendedor, semana, peca, 20);
        cliente = cliente(vendedor);
    }

    private static LocalDate hoje() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    /** Fecha por SQL um acerto com a venda de R$ 50,00 (líquido 35,00) que o vendedor ficou devendo em contas dos valores dados. */
    private List<UUID> contas(String formaDaPrimeira, String... valores) throws Exception {
        UUID venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto = acertoNoBanco(vendedor, semana());
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        return transacao.execute(s -> {
            List<UUID> ids = new java.util.ArrayList<>();
            for (int i = 0; i < valores.length; i++) {
                ids.add(contaDoAcertoNoBanco(acerto, new BigDecimal(valores[i]), i + 1, valores.length, hoje().plusDays(10L * (i + 1)),
                        i == 0 ? formaDaPrimeira : null));
            }
            fecharComDinheiro(acerto, "0", "0", "35.00", "0", null);
            return ids;
        });
    }

    private org.springframework.test.web.servlet.ResultActions receber(UUID id, String forma, String valor, UUID conta) throws Exception {
        return postar(RECEBIMENTOS, dona(), mapa("id", id, "vendedorId", vendedor.vendedorId(), "forma", forma, "valor", valor, "contaId", conta));
    }

    private String situacao(UUID conta) {
        return jdbc.queryForObject("SELECT status || ':' || valor_pago FROM corona.contas_receber WHERE id = ?", String.class, conta);
    }

    private BigDecimal caixa() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(l.valor), 0) FROM corona.lancamentos_caixa l JOIN corona.recebimentos_vendedor r
                  ON l.origem_tipo = 'RECEBIMENTO_VENDEDOR' AND l.origem_id = r.id WHERE r.vendedor_id = ?""", BigDecimal.class,
                vendedor.vendedorId());
    }

    @Test
    void recebimentoSemContaDistribuiPorVencimentoEEntraNoCaixa() throws Exception {
        List<UUID> c = contas(null, "10.00", "25.00");
        receber(UUID.randomUUID(), "DINHEIRO", "15.00", null).andExpect(status().isCreated());
        assertThat(situacao(c.get(0))).isEqualTo("PAGA:10.00");
        assertThat(situacao(c.get(1))).isEqualTo("PARCIAL:5.00");
        assertThat(caixa()).isEqualByComparingTo("15.00");
        receber(UUID.randomUUID(), "PIX", "20.00", null).andExpect(status().isCreated());
        assertThat(situacao(c.get(1))).isEqualTo("PAGA:25.00");
        assertThat(caixa()).isEqualByComparingTo("35.00");
        // Nada mais em aberto.
        receber(UUID.randomUUID(), "DINHEIRO", "1.00", null).andExpect(status().isConflict());
    }

    @Test
    void recebimentoComContaBaixaSoEssaEAcimaDoSaldoEhRecusado() throws Exception {
        List<UUID> c = contas(null, "10.00", "25.00");
        receber(UUID.randomUUID(), "DINHEIRO", "5.00", c.get(1)).andExpect(status().isCreated());
        assertThat(situacao(c.get(0))).startsWith("ABERTA");
        assertThat(situacao(c.get(1))).isEqualTo("PARCIAL:5.00");
        receber(UUID.randomUUID(), "DINHEIRO", "20.01", c.get(1)).andExpect(status().isConflict());
        receber(UUID.randomUUID(), "DINHEIRO", "30.01", null).andExpect(status().isConflict());
        assertThat(caixa()).isEqualByComparingTo("5.00");
    }

    @Test
    void boletoPagoSoInteiroENaContaDoBoleto() throws Exception {
        List<UUID> c = contas("BOLETO", "20.00", "15.00");
        receber(UUID.randomUUID(), "BOLETO", "10.00", c.get(0)).andExpect(status().isConflict());
        receber(UUID.randomUUID(), "BOLETO", "15.00", c.get(1)).andExpect(status().isConflict());
        receber(UUID.randomUUID(), "BOLETO", "20.00", null).andExpect(status().isConflict());
        UUID recebimento = UUID.randomUUID();
        receber(recebimento, "BOLETO", "20.00", c.get(0)).andExpect(status().isCreated());
        assertThat(situacao(c.get(0))).isEqualTo("PAGA:20.00");
        assertThat(caixa()).isEqualByComparingTo("20.00");
        // O lançamento deste recebimento (e não "o último do banco": outras classes também lançam no caixa).
        assertThat(jdbc.queryForObject("SELECT forma FROM corona.lancamentos_caixa WHERE origem_id = ?", String.class, recebimento))
                .isEqualTo("BOLETO");
    }

    @Test
    void chequeECartaoNaoExistemNoRepasse() throws Exception {
        List<UUID> c = contas(null, "35.00");
        for (String forma : List.of("CHEQUE", "CARTAO", "CREDITO", "")) {
            receber(UUID.randomUUID(), forma, "5.00", null).andExpect(status().isBadRequest());
        }
        receber(UUID.randomUUID(), "DINHEIRO", "0", null).andExpect(status().isBadRequest());
        receber(UUID.randomUUID(), "DINHEIRO", "-3.00", null).andExpect(status().isBadRequest());
        receber(UUID.randomUUID(), "DINHEIRO", "5.005", null).andExpect(status().isBadRequest());
        assertThat(situacao(c.get(0))).startsWith("ABERTA");
        assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE vendedor_id = ?", vendedor.vendedorId())).isZero();
    }

    @Test
    void estornoDeRecebimentoAvulsoReabreAsContasETiraDoCaixa() throws Exception {
        List<UUID> c = contas(null, "10.00", "25.00");
        UUID r = UUID.randomUUID();
        receber(r, "DINHEIRO", "15.00", null).andExpect(status().isCreated());
        UUID estorno = UUID.randomUUID();
        postar(RECEBIMENTOS + "/" + r + "/estorno", dona(), mapa("id", estorno, "motivo", "Lancei errado")).andExpect(status().isCreated());
        assertThat(situacao(c.get(0))).isEqualTo("ABERTA:0.00");
        assertThat(situacao(c.get(1))).isEqualTo("ABERTA:0.00");
        assertThat(caixa()).isEqualByComparingTo("0.00");
        // Reenvio do mesmo estorno: 200, nada novo.
        int antes = contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE origem_id IN (?, ?)", r, estorno);
        postar(RECEBIMENTOS + "/" + r + "/estorno", dona(), mapa("id", estorno, "motivo", "Lancei errado")).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE origem_id IN (?, ?)", r, estorno)).isEqualTo(antes).isEqualTo(2);
        // Outro estorno do mesmo recebimento e estorno de estorno.
        postar(RECEBIMENTOS + "/" + r + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "De novo")).andExpect(status().isConflict());
        postar(RECEBIMENTOS + "/" + estorno + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Desfazer")).andExpect(status().isNotFound());
        // O original nunca mudou.
        assertThat(jdbc.queryForObject("SELECT valor FROM corona.recebimentos_vendedor WHERE id = ?", BigDecimal.class, r)).isEqualByComparingTo("15.00");
        // As contas podem ser pagas de novo.
        receber(UUID.randomUUID(), "DINHEIRO", "35.00", null).andExpect(status().isCreated());
        assertThat(situacao(c.get(1))).isEqualTo("PAGA:25.00");
    }

    @Test
    void estornoPedeMotivoESoADonaEstorna() throws Exception {
        contas(null, "35.00");
        UUID r = UUID.randomUUID();
        receber(r, "DINHEIRO", "5.00", null).andExpect(status().isCreated());
        postar(RECEBIMENTOS + "/" + r + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "  ")).andExpect(status().isBadRequest());
        postar(RECEBIMENTOS + "/" + r + "/estorno", dona(), mapa("id", UUID.randomUUID())).andExpect(status().isBadRequest());
        postar(RECEBIMENTOS + "/" + r + "/estorno", como(vendedor), mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isForbidden());
        postar(RECEBIMENTOS + "/" + UUID.randomUUID() + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isNotFound());
        assertThat(caixa()).isEqualByComparingTo("5.00");
    }

    @Test
    void mesmoIdComOMesmoConteudoDa200EComOutroConteudoDa409() throws Exception {
        List<UUID> c = contas(null, "35.00");
        UUID r = UUID.randomUUID();
        receber(r, "DINHEIRO", "5.00", null).andExpect(status().isCreated());
        receber(r, "DINHEIRO", "5.00", null).andExpect(status().isOk());
        receber(r, "DINHEIRO", "6.00", null).andExpect(status().isConflict());
        receber(r, "PIX", "5.00", null).andExpect(status().isConflict());
        assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE id = ?", r)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.baixas_receber WHERE recebimento_vendedor_id = ?", r)).isEqualTo(1);
        assertThat(situacao(c.get(0))).isEqualTo("PARCIAL:5.00");
        assertThat(caixa()).isEqualByComparingTo("5.00");
    }

    @Test
    void soAAdminRegistraEAContaDeOutroVendedorNaoSeBaixa() throws Exception {
        List<UUID> c = contas(null, "35.00");
        postar(RECEBIMENTOS, como(vendedor), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "forma", "DINHEIRO",
                "valor", "5.00")).andExpect(status().isForbidden());
        UsuarioDeTeste outro = usuarios.criarVendedor();
        postar(RECEBIMENTOS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", outro.vendedorId(), "forma", "DINHEIRO", "valor", "5.00",
                "contaId", c.get(0))).andExpect(status().isNotFound());
        postar(RECEBIMENTOS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", outro.vendedorId(), "forma", "DINHEIRO", "valor", "5.00"))
                .andExpect(status().isConflict());
        assertThat(situacao(c.get(0))).startsWith("ABERTA");
    }

    @Test
    void aListaDaDonaTraz() throws Exception {
        List<UUID> c = contas(null, "35.00");
        UUID r = UUID.randomUUID();
        receber(r, "PIX", "5.00", null).andExpect(status().isCreated());
        JsonNode lista = corpoDe(ler(RECEBIMENTOS + "?vendedorId=" + vendedor.vendedorId(), dona()).andExpect(status().isOk()));
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("forma").asString()).isEqualTo("PIX");
        assertThat(lista.get(0).get("contas").get(0).get("contaId").asString()).isEqualTo(c.get(0).toString());
        ler(RECEBIMENTOS, como(vendedor)).andExpect(status().isForbidden());
    }
}
