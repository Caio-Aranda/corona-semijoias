package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * O pull das tabelas da Fase 6 (tarefa 6.7; plano aprovado no PR #131, testes 14 a 16 e o ponto 2 da aprovação): cada
 * tabela nova chega ao vendedor dono e nunca ao outro, com as colunas exatas da lista (sem os "*_por" e sem as duas
 * colunas que apontam para crédito de outro vendedor); as colunas novas das tabelas antigas chegam; as decisões da dona
 * chegam como versão nova; o crédito transferido fica com cada um sem id do outro; nenhuma sincronizável sem filtro; a
 * pertença não muda; e o app abaixo da versão mínima (o que não tem a migração local 3) recebe 426 no pull, nunca um
 * lote com tabela que ele não conhece. Escritos antes do código.
 */
class PullDaFase6Test extends ApoioDoEnvioDaFase6 {

    static final List<String> NOVAS = List.of("devolucoes", "itens_devolucao", "autorizacoes_excecao", "itens_autorizacao",
            "trocas", "creditos_cliente", "usos_credito", "creditos_sem_saldo");

    /** O que pertence ao vendedor em cada tabela nova, calculado à parte (sem o filtro do pull). */
    private static final Map<String, String> DO_VENDEDOR = Map.of(
            "devolucoes", "SELECT id FROM corona.devolucoes WHERE vendedor_id = ?",
            "itens_devolucao", "SELECT i.id FROM corona.itens_devolucao i JOIN corona.devolucoes d ON d.id = i.devolucao_id WHERE d.vendedor_id = ?",
            "autorizacoes_excecao", "SELECT id FROM corona.autorizacoes_excecao WHERE vendedor_id = ?",
            "itens_autorizacao", "SELECT i.id FROM corona.itens_autorizacao i JOIN corona.autorizacoes_excecao a ON a.id = i.autorizacao_id WHERE a.vendedor_id = ?",
            "trocas", "SELECT id FROM corona.trocas WHERE vendedor_id = ?",
            "creditos_cliente", "SELECT id FROM corona.creditos_cliente WHERE vendedor_id = ?",
            "usos_credito", "SELECT u.id FROM corona.usos_credito u JOIN corona.creditos_cliente c ON c.id = u.credito_id WHERE c.vendedor_id = ?",
            "creditos_sem_saldo", "SELECT s.id FROM corona.creditos_sem_saldo s JOIN corona.vendas v ON v.id = s.venda_id WHERE v.vendedor_id = ?");

    @Autowired
    ServicoDePull pull;

    @Autowired
    RegistroDeTabelas registro;

    /** A cópia local: tabela → id → a linha de versão maior. */
    private Map<String, Map<UUID, LinhaDoPull>> puxar(UsuarioDeTeste v, Map<String, Map<UUID, LinhaDoPull>> copia, String[] cursor) {
        LoteDoPull lote;
        int voltas = 0;
        do {
            lote = pull.puxar(cursor[0], 200, new FiltroPorVendedor(v.vendedorId()));
            for (LinhaDoPull l : lote.linhas()) {
                Map<UUID, LinhaDoPull> t = copia.computeIfAbsent(l.tabela(), x -> new HashMap<>());
                LinhaDoPull atual = t.get(l.id());
                if (atual == null || l.versao() > atual.versao()) {
                    t.put(l.id(), l);
                }
            }
            cursor[0] = lote.proximoCursor();
        } while (lote.haMais() && ++voltas < 500);
        return copia;
    }

    private Map<String, Map<UUID, LinhaDoPull>> puxarTudo(UsuarioDeTeste v) {
        return puxar(v, new HashMap<>(), new String[1]);
    }

    private Set<UUID> doVendedor(String tabela, UsuarioDeTeste v) {
        return new HashSet<>(jdbc.queryForList(DO_VENDEDOR.get(tabela), UUID.class, v.vendedorId()));
    }

    /** Um vendedor com tudo da Fase 6: devolução, pedido aprovado e negado, troca com sobra, uso do crédito e crédito sem saldo. */
    private UsuarioDeTeste vendedorComTudo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("50.00", 10);
        UUID c = peca("30.00", 10);
        UUID m = maleta(v, a, 5, c, 5);
        UUID venda = vender(v, m, cliente, List.of(linha(a, 2)), List.of(pagamento("100.00", "PIX")));
        postar(DEVOLUCOES, como(v), mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", List.of(devolvida(a, 1, "BOA")))).andExpect(status().isCreated());
        // Dois pedidos (vendas velhas): um aprovado, um negado.
        for (String decisao : List.of("aprovar", "negar")) {
            UUID velha = venderEm(v, m, cliente, a, 1, "50.00", diasAtras(12));
            JsonNode pedido = corpoDe(postar("/api/vendedor/autorizacoes", como(v), mapa("id", UUID.randomUUID(), "vendaId", velha,
                    "motivo", "ARREPENDIMENTO", "itens", List.of(devolvida(a, 1, "BOA")))).andExpect(status().isCreated()));
            postar("/api/dona/autorizacoes/" + id(pedido) + "/" + decisao, dona(), mapa("motivo", "Fora do combinado"))
                    .andExpect(status().isOk());
        }
        // Troca com sobra (A 50,00 por C 30,00): crédito de 20,00; usa 10,00 e depois pede 30,00 (sem saldo) pelo envio.
        UUID vendaA = vender(v, m, cliente, List.of(linha(a, 1)), List.of(pagamento("50.00", "DINHEIRO")));
        JsonNode troca = trocar(v, vendaA, a, m, c, List.of());
        UUID credito = UUID.fromString(troca.get("creditoId").asString());
        vender(v, m, cliente, List.of(linha(c, 1)), List.of(pagamento("20.00", "PIX"), pagamentoComCredito("10.00", credito)));
        UUID semSaldo = UUID.randomUUID();
        assertThat(resultado(enviar(v, List.of(vendaPeloEnvio(semSaldo, m, cliente, List.of(linha(c, 1)),
                List.of(mapa("id", semSaldo, "valor", "30.00", "forma", "CREDITO", "creditoId", credito)), relogio.instant()))), 0))
                .isEqualTo("ACEITO");
        return v;
    }

    // --- 14. cada tabela nova ---

    @Test
    void cadaTabelaNovaChegaAoDonoENuncaAoOutroComAsColunasDaLista() throws Exception {
        UsuarioDeTeste a = vendedorComTudo();
        UsuarioDeTeste b = vendedorComTudo();
        Map<String, Map<UUID, LinhaDoPull>> copiaA = new HashMap<>();
        String[] cursorA = new String[1];
        puxar(a, copiaA, cursorA);
        Map<String, Map<UUID, LinhaDoPull>> copiaB = puxarTudo(b);

        for (String t : NOVAS) {
            Set<UUID> deA = doVendedor(t, a);
            Set<UUID> deB = doVendedor(t, b);
            assertThat(deA).as("o cenário de A tem %s", t).isNotEmpty();
            assertThat(copiaA.getOrDefault(t, Map.of()).keySet()).as("A recebe as dele em %s", t).containsAll(deA)
                    .doesNotContainAnyElementsOf(deB);
            assertThat(copiaB.getOrDefault(t, Map.of()).keySet()).as("B recebe as dele em %s", t).containsAll(deB)
                    .doesNotContainAnyElementsOf(deA);
            Set<String> lista = new HashSet<>(registro.sincronizavel(t).orElseThrow().colunasDoPull());
            copiaA.get(t).values().forEach(l -> assertThat(l.colunas().keySet()).as("colunas de %s", t).isEqualTo(lista));
        }
        for (Map<UUID, LinhaDoPull> linhas : copiaA.values()) {
            linhas.values().forEach(l -> assertThat(l.colunas().keySet()).noneMatch(c -> c.endsWith("_por"))
                    .doesNotContain("credito_origem_id", "credito_destino_id"));
        }
        // As colunas novas das tabelas antigas chegam.
        assertThat(copiaA.get("vendas").values()).allSatisfy(l -> assertThat(l.colunas()).containsKey("troca_id"))
                .anySatisfy(l -> assertThat(l.colunas().get("troca_id")).isNotNull());
        assertThat(copiaA.get("pagamentos_cliente").values())
                .allSatisfy(l -> assertThat(l.colunas()).containsKeys("devolucao_id", "estorna_id", "credito_id"))
                .anySatisfy(l -> assertThat(l.colunas().get("credito_id")).isNotNull())
                .anySatisfy(l -> assertThat(l.colunas().get("devolucao_id")).isNotNull());
        assertThat(copiaA.get("movimentos_remessa").values()).allSatisfy(l -> assertThat(l.colunas()).containsKey("devolucao_id"))
                .anySatisfy(l -> assertThat(l.colunas().get("devolucao_id")).isNotNull());
        // EM_REVISAO, aprovada com valida_ate e negada chegam.
        assertThat(copiaA.get("autorizacoes_excecao").values()).extracting(l -> l.colunas().get("situacao"))
                .contains("APROVADA", "NEGADA");
        assertThat(copiaA.get("autorizacoes_excecao").values()).filteredOn(l -> "APROVADA".equals(l.colunas().get("situacao")))
                .allSatisfy(l -> assertThat(l.colunas().get("valida_ate")).isNotNull());
        assertThat(copiaA.get("creditos_sem_saldo").values()).extracting(l -> l.colunas().get("situacao")).containsOnly("EM_REVISAO");

        // As decisões da dona chegam como versão nova: manter o crédito sem saldo e cancelar a devolução.
        UUID semSaldo = copiaA.get("creditos_sem_saldo").keySet().iterator().next();
        int versaoAntes = copiaA.get("creditos_sem_saldo").get(semSaldo).versao();
        postar("/api/dona/creditos-sem-saldo/" + semSaldo + "/manter", dona(), mapa()).andExpect(status().isOk());
        UUID devolucao = jdbc.queryForObject("""
                SELECT id FROM corona.devolucoes WHERE vendedor_id = ? AND destino = 'REEMBOLSO' AND situacao = 'ACEITA' LIMIT 1""",
                UUID.class, a.vendedorId());
        int versaoDaDevolucao = copiaA.get("devolucoes").get(devolucao).versao();
        postar("/api/dona/devolucoes/" + devolucao + "/cancelar", dona(), mapa("id", UUID.randomUUID(), "motivo", "Errada"))
                .andExpect(status().is2xxSuccessful());
        puxar(a, copiaA, cursorA);
        assertThat(copiaA.get("creditos_sem_saldo").get(semSaldo).versao()).isGreaterThan(versaoAntes);
        assertThat(copiaA.get("creditos_sem_saldo").get(semSaldo).colunas().get("situacao")).isEqualTo("MANTIDA");
        assertThat(copiaA.get("devolucoes").get(devolucao).versao()).isGreaterThan(versaoDaDevolucao);
        assertThat(copiaA.get("devolucoes").get(devolucao).colunas().get("situacao")).isEqualTo("CANCELADA");
        assertThat(copiaA.get("devolucoes").get(devolucao).colunas().get("cancelamento_id")).isNotNull();
    }

    // --- 15. crédito transferido ---

    @Test
    void creditoTransferidoFicaComCadaUmSemIdDoOutro() throws Exception {
        UsuarioDeTeste antigo = usuarios.criarVendedor();
        UsuarioDeTeste novo = usuarios.criarVendedor();
        UUID cliente = cliente(antigo);
        UUID original = creditoDeSobra(antigo, cliente, "100.00", "60.00");
        UUID transferido = UUID.randomUUID();
        postar(CREDITOS_DA_DONA + "/" + original + "/transferir", dona(), mapa("id", transferido, "vendedorId", novo.vendedorId()))
                .andExpect(status().isCreated());
        Map<String, Map<UUID, LinhaDoPull>> doAntigo = puxarTudo(antigo);
        Map<String, Map<UUID, LinhaDoPull>> doNovo = puxarTudo(novo);
        assertThat(doNovo.get("creditos_cliente").keySet()).containsExactly(transferido);
        assertThat(doAntigo.get("creditos_cliente").keySet()).contains(original).doesNotContain(transferido);
        Set<UUID> usosDoOriginal = new HashSet<>(jdbc.queryForList("SELECT id FROM corona.usos_credito WHERE credito_id = ?",
                UUID.class, original));
        assertThat(doAntigo.get("usos_credito").keySet()).containsAll(usosDoOriginal);
        assertThat(doNovo.getOrDefault("usos_credito", Map.of()).keySet()).doesNotContainAnyElementsOf(usosDoOriginal);
        assertThat(OraculoDeDoisVendedoresTest.idsDoOutro(doNovo, Set.of(original))).isEmpty();
        assertThat(OraculoDeDoisVendedoresTest.idsDoOutro(doAntigo, Set.of(transferido))).isEmpty();
    }

    // --- 16. convenções ---

    @Test
    void nenhumaSincronizavelSemFiltroEAPertencaDasTabelasNovasNaoMuda() throws Exception {
        assertThat(RegistroDeTabelas.daAplicacao().sincronizaveis()).noneMatch(t -> "FALSE".equals(t.regra().condicao()));
        assertThat(registro.sincronizavel("creditos_sem_saldo")).isPresent();
        UsuarioDeTeste v = vendedorComTudo();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        for (String sql : List.of(
                "UPDATE corona.devolucoes SET vendedor_id = ? WHERE vendedor_id = ?",
                "UPDATE corona.autorizacoes_excecao SET vendedor_id = ? WHERE vendedor_id = ?",
                "UPDATE corona.trocas SET vendedor_id = ? WHERE vendedor_id = ?",
                "UPDATE corona.creditos_cliente SET vendedor_id = ? WHERE vendedor_id = ?")) {
            assertThatThrownBy(() -> jdbc.update(sql, outro.vendedorId(), v.vendedorId())).as(sql).isInstanceOf(Exception.class);
        }
        UUID semSaldo = doVendedor("creditos_sem_saldo", v).iterator().next();
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_sem_saldo SET venda_id = (SELECT id FROM corona.vendas"
                + " WHERE vendedor_id = ? LIMIT 1) WHERE id = ?", v.vendedorId(), semSaldo)).isInstanceOf(Exception.class);
        for (String t : List.of("itens_devolucao", "itens_autorizacao", "usos_credito")) {
            UUID linha = doVendedor(t, v).iterator().next();
            assertThatThrownBy(() -> jdbc.update("UPDATE corona." + t + " SET updated_at = now() WHERE id = ?", linha))
                    .as("%s é imutável", t).isInstanceOf(Exception.class);
        }
    }

    // --- ponto 2 da aprovação: o app sem a migração local 3 recebe 426, nunca um lote com tabela desconhecida ---

    @Test
    void appAbaixoDaVersaoMinimaRecebe426NoPullEOEmDiaRecebeAsTabelasNovas() throws Exception {
        UsuarioDeTeste v = vendedorComTudo();
        String velho = mvc.perform(get("/api/vendedor/sync/pull?limite=500").with(como(v)).header("X-App-Tipo", "vendas")
                        .header("X-App-Versao", "2.0.0")).andExpect(status().isUpgradeRequired()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        for (String t : NOVAS) {
            assertThat(velho).doesNotContain("\"" + t + "\"");
        }
        assertThat(json.readTree(velho).get("codigo").asString()).isEqualTo("APP_DESATUALIZADO");

        Set<String> tabelas = new HashSet<>();
        String cursor = null;
        for (int i = 0; i < 100; i++) {
            JsonNode r = json.readTree(mvc.perform(get("/api/vendedor/sync/pull?limite=500" + (cursor == null ? "" : "&cursor=" + cursor))
                            .with(como(v)).header("X-App-Tipo", "vendas").header("X-App-Versao", TesteDeIntegracao.VERSAO_MINIMA_VENDAS))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            r.get("linhas").forEach(l -> tabelas.add(l.get("tabela").asString()));
            cursor = r.get("cursor").asString();
            if (!r.get("temMais").asBoolean()) {
                break;
            }
        }
        assertThat(tabelas).containsAll(NOVAS);
    }

}
