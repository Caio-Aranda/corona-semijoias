package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O pull das contas a receber (tarefa 7.3): as duas tabelas novas chegam só ao vendedor dono, com as colunas exatas da
 * lista, o recebimento e o estorno chegam como pagamentos com a coluna {@code posterior}, e o atalho da parcela chega como
 * versão nova. Escritos antes do código.
 */
class PullDasContasTest extends ApoioDoEnvioDaFase6 {

    @Autowired
    ServicoDePull pull;

    @Autowired
    RegistroDeTabelas registro;

    private Map<String, Map<UUID, LinhaDoPull>> puxarTudo(UsuarioDeTeste v) {
        Map<String, Map<UUID, LinhaDoPull>> copia = new HashMap<>();
        String cursor = null;
        LoteDoPull lote;
        int voltas = 0;
        do {
            lote = pull.puxar(cursor, 200, new FiltroPorVendedor(v.vendedorId()));
            for (LinhaDoPull l : lote.linhas()) {
                Map<UUID, LinhaDoPull> t = copia.computeIfAbsent(l.tabela(), x -> new HashMap<>());
                LinhaDoPull atual = t.get(l.id());
                if (atual == null || l.versao() > atual.versao()) {
                    t.put(l.id(), l);
                }
            }
            cursor = lote.proximoCursor();
        } while (lote.haMais() && ++voltas < 500);
        return copia;
    }

    private record Vendedor(UsuarioDeTeste v, UUID venda, UUID recebimento, UUID estorno) {
    }

    /** Um vendedor com uma venda a prazo em 3 parcelas, um recebimento de 50,00 e, se pedido, o estorno dele. */
    private Vendedor vendedorComContas(boolean estornar) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("100.00", 5);
        UUID maleta = maleta(v, peca, 5);
        UUID venda = UUID.randomUUID();
        Map<String, Object> corpo = pedidoDeVenda(venda, maleta, cliente, List.of(linha(peca, 1)), List.of());
        corpo.put("parcelamento", mapa("quantidade", 3, "primeiroVencimento", LocalDate.now().plusDays(5).toString()));
        postar(VENDAS, como(v), corpo).andExpect(status().isCreated());
        UUID recebimento = UUID.randomUUID();
        postar("/api/vendedor/vendas/" + venda + "/recebimentos", como(v), mapa("id", recebimento, "valor", "50.00", "forma", "PIX"))
                .andExpect(status().isCreated());
        UUID estorno = null;
        if (estornar) {
            estorno = UUID.randomUUID();
            postar("/api/vendedor/recebimentos-de-cliente/" + recebimento + "/estorno", como(v),
                    mapa("id", estorno, "motivo", "Digitei errado")).andExpect(status().isCreated());
        }
        return new Vendedor(v, venda, recebimento, estorno);
    }

    private Set<UUID> doBanco(String sql, UUID vendedor) {
        return new HashSet<>(jdbc.queryForList(sql, UUID.class, vendedor));
    }

    @Test
    void asDuasTabelasChegamSoAoDonoComAsColunasDaLista() throws Exception {
        Vendedor a = vendedorComContas(false);
        Vendedor b = vendedorComContas(true);
        Map<String, Map<UUID, LinhaDoPull>> copiaA = puxarTudo(a.v());
        Map<String, Map<UUID, LinhaDoPull>> copiaB = puxarTudo(b.v());
        for (String t : List.of("contas_receber", "baixas_receber")) {
            Set<String> lista = new HashSet<>(registro.sincronizavel(t).orElseThrow().colunasDoPull());
            for (var copia : List.of(copiaA, copiaB)) {
                assertThat(copia).containsKey(t);
                copia.get(t).values().forEach(l -> assertThat(l.colunas().keySet()).as("colunas de %s", t).isEqualTo(lista));
            }
        }
        assertThat(copiaA.get("contas_receber").keySet()).isEqualTo(doBanco("""
                SELECT c.id FROM corona.contas_receber c JOIN corona.vendas v ON v.id = c.venda_id WHERE v.vendedor_id = ?""",
                a.v().vendedorId()));
        assertThat(copiaA.get("baixas_receber").keySet()).isEqualTo(doBanco("""
                SELECT b.id FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id
                  JOIN corona.vendas v ON v.id = c.venda_id WHERE v.vendedor_id = ?""", a.v().vendedorId()));
        assertThat(copiaA.get("contas_receber")).hasSize(3);
        assertThat(copiaA.get("baixas_receber")).hasSize(2);
        assertThat(copiaB.get("baixas_receber")).hasSize(4);
        // Nada de um chega ao outro.
        Set<UUID> deB = copiaB.get("contas_receber").keySet();
        assertThat(copiaA.get("contas_receber").keySet()).doesNotContainAnyElementsOf(deB);
        assertThat(copiaA.get("baixas_receber").keySet()).doesNotContainAnyElementsOf(copiaB.get("baixas_receber").keySet());
    }

    @Test
    void oRecebimentoEOEstornoChegamComoPagamentosPosterioresEOAtalhoDaParcelaChegaAtualizado() throws Exception {
        Vendedor a = vendedorComContas(true);
        Map<String, Map<UUID, LinhaDoPull>> copia = puxarTudo(a.v());
        Map<UUID, LinhaDoPull> pagamentos = copia.get("pagamentos_cliente");
        assertThat(pagamentos.get(a.recebimento()).colunas()).containsEntry("posterior", true).containsEntry("tipo", "PAGAMENTO");
        assertThat(pagamentos.get(a.estorno()).colunas()).containsEntry("posterior", true).containsEntry("tipo", "ESTORNO_PAGAMENTO")
                .containsEntry("estorna_id", a.recebimento());
        assertThat(pagamentos.values()).allSatisfy(l -> assertThat(l.colunas()).containsKey("posterior"));
        // Depois do estorno, todas as parcelas chegam abertas, com o valor pago zerado (a versão mais nova da linha).
        assertThat(copia.get("contas_receber").values()).allSatisfy(l -> {
            assertThat(l.colunas().get("status")).isEqualTo("ABERTA");
            assertThat(l.colunas().get("valor_pago")).hasToString("0.00");
        });
        // E as linhas das parcelas já tiveram mais de uma versão (nasceu, pagou, voltou).
        assertThat(copia.get("contas_receber").values()).anySatisfy(l -> assertThat(l.versao()).isGreaterThan(1));
    }

    @Test
    void estornoDaVendaChegaComAsParcelasCanceladasEAsBaixasDeCancelamento() throws Exception {
        Vendedor a = vendedorComContas(false);
        postar("/api/vendedor/vendas/" + a.venda() + "/estorno", como(a.v()), mapa("id", UUID.randomUUID(), "motivo", "Desistiu"))
                .andExpect(status().isCreated());
        Map<String, Map<UUID, LinhaDoPull>> copia = puxarTudo(a.v());
        assertThat(copia.get("contas_receber").values()).extracting(l -> l.colunas().get("status"))
                .containsExactlyInAnyOrder("PAGA", "CANCELADA", "CANCELADA");
        assertThat(copia.get("baixas_receber").values()).extracting(l -> l.colunas().get("tipo")).contains("CANCELAMENTO");
    }

    @Test
    void asRespostasDoPullNaoTemCustoNemMarcadoresProibidos() throws Exception {
        Vendedor a = vendedorComContas(false);
        String corpo = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/vendedor/sync/pull?limite=500").with(como(a.v())).header("X-App-Tipo", "vendas")
                        .header("X-App-Versao", VERSAO_MINIMA_VENDAS)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
        assertThat(corpo).contains("contas_receber").doesNotContain("custo").doesNotContain("fornecedor");
    }
}
