package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.sync.FiltroPorVendedor;
import br.com.coronasemijoias.api.sync.LinhaDoPull;
import br.com.coronasemijoias.api.sync.LoteDoPull;
import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O pull do dinheiro do vendedor (tarefa 7.6; ADR-0013, item 4): os recebimentos, as contas dos dois níveis, as baixas e as colunas
 * novas do acerto chegam só ao vendedor dono, com as colunas exatas da lista (nunca quem registrou, o motivo ou o resumo); o caixa e as
 * categorias nunca vão ao aplicativo.
 */
class PullDoDinheiroDoVendedorTest extends ApoioDoFechamento {

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

    private record Cenario(UsuarioDeTeste v, UUID acerto, UUID contaDoBoleto, UUID recebimento) {
    }

    /** Um vendedor com um acerto pago em partes (dinheiro), boleto e restante; depois o boleto pago e um recebimento estornado. */
    private Cenario cenario() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("50.00", 20);
        UUID maleta = maletaConferida(v, semana(), peca, 4);
        vender(v, maleta, cliente, peca, 1, "50.00");
        UUID acerto = iniciar(v, null);
        bipar(acerto, peca, 3, "BOA");
        UUID recebimento = UUID.randomUUID();
        var pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00");
        pedido.put("pagamento", mapa("recebimentos", List.of(mapa("id", recebimento, "forma", "DINHEIRO", "valor", "10.00")),
                "boleto", mapa("valor", "15.00", "vencimento", LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(10).toString()),
                "restante", mapa("parcelas", 1, "primeiroVencimento", LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(30).toString())));
        fechar(acerto, pedido).andExpect(status().isCreated());
        UUID boleto = jdbc.queryForObject("SELECT id FROM corona.contas_receber WHERE origem_id = ? AND forma_prevista = 'BOLETO'", UUID.class, acerto);
        postar("/api/dona/recebimentos-vendedor", dona(), mapa("id", UUID.randomUUID(), "vendedorId", v.vendedorId(), "forma", "BOLETO",
                "valor", "15.00", "contaId", boleto)).andExpect(status().isCreated());
        return new Cenario(v, acerto, boleto, recebimento);
    }

    @Test
    void osRecebimentosAsContasDosDoisNiveisEAsBaixasChegamSoAoDonoComAsColunasDaLista() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        Map<String, Map<UUID, LinhaDoPull>> copiaA = puxarTudo(a.v());
        Map<String, Map<UUID, LinhaDoPull>> copiaB = puxarTudo(b.v());
        for (String t : List.of("recebimentos_vendedor", "contas_receber", "baixas_receber", "acertos")) {
            Set<String> lista = new HashSet<>(registro.sincronizavel(t).orElseThrow().colunasDoPull());
            for (var copia : List.of(copiaA, copiaB)) {
                assertThat(copia).containsKey(t);
                copia.get(t).values().forEach(l -> assertThat(l.colunas().keySet()).as("colunas de %s", t).isEqualTo(lista));
            }
            assertThat(lista).noneMatch(c -> c.endsWith("_por") || c.equals("conteudo") || c.equals("motivo") || c.contains("registrad"));
        }
        // Só os dele: dois recebimentos (dinheiro e boleto pago), duas contas do nível 2 (boleto e restante), três baixas ou menos.
        assertThat(copiaA.get("recebimentos_vendedor")).hasSize(2);
        assertThat(copiaA.get("recebimentos_vendedor").keySet()).doesNotContainAnyElementsOf(copiaB.get("recebimentos_vendedor").keySet());
        assertThat(copiaA.get("recebimentos_vendedor").values()).extracting(l -> l.colunas().get("forma")).containsExactlyInAnyOrder("DINHEIRO", "BOLETO");
        Map<UUID, LinhaDoPull> contasA = copiaA.get("contas_receber");
        assertThat(contasA.values()).filteredOn(l -> "VENDEDOR".equals(l.colunas().get("devedor_tipo"))).hasSize(2);
        assertThat(contasA.values()).allSatisfy(l -> assertThat(l.colunas().get("vendedor_id")).isEqualTo(a.v().vendedorId()));
        assertThat(contasA.get(a.contaDoBoleto()).colunas()).containsEntry("forma_prevista", "BOLETO").containsEntry("status", "PAGA");
        assertThat(contasA.keySet()).doesNotContainAnyElementsOf(copiaB.get("contas_receber").keySet());
        assertThat(copiaA.get("baixas_receber").values()).allSatisfy(l -> assertThat(l.colunas().get("recebimento_vendedor_id")).isNotNull());
        assertThat(copiaA.get("acertos").get(a.acerto()).colunas()).containsKeys("pago_ao_vendedor", "credito_usado", "credito_do_acerto_id")
                .containsEntry("status", "ACERTADO");
    }

    @Test
    void oCaixaEAsCategoriasNuncaVaoAoAplicativo() throws Exception {
        Cenario a = cenario();
        Map<String, Map<UUID, LinhaDoPull>> copia = puxarTudo(a.v());
        assertThat(copia).doesNotContainKeys("lancamentos_caixa", "categorias_caixa");
        assertThat(registro.sincronizavel("lancamentos_caixa")).isEmpty();
        assertThat(registro.sincronizavel("categorias_caixa")).isEmpty();
        assertThat(registro.sincronizaveis().stream().map(t -> t.nome())).doesNotContain("lancamentos_caixa", "categorias_caixa");
        assertThat(copia.toString().toLowerCase()).doesNotContain("repasse do vendedor").doesNotContain("custo");
    }

    @Test
    void oEstornoDeRecebimentoDoAcertoVoltaNoPullComoContaNova() throws Exception {
        Cenario a = cenario();
        postar("/api/dona/recebimentos-vendedor/" + a.recebimento() + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Engano"))
                .andExpect(status().isCreated());
        Map<String, Map<UUID, LinhaDoPull>> copia = puxarTudo(a.v());
        assertThat(copia.get("recebimentos_vendedor")).hasSize(3);
        assertThat(copia.get("contas_receber").values()).filteredOn(l -> "ESTORNO_RECEBIMENTO".equals(l.colunas().get("origem_tipo"))).hasSize(1);
    }
}
