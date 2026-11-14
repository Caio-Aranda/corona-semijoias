package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O pull do acerto (tarefa 7.4): {@code acertos} e {@code leituras_acerto} chegam só ao vendedor dono, com as colunas exatas
 * da lista (nunca os "*_por"); desistir sobe a versão e o acerto volta no pull; a maleta chega com status EM_ACERTO, sem a
 * ligação com o acerto. Escrito antes do registro das tabelas.
 */
class PullDoAcertoTest extends ApoioDoEnvioDaFase6 {

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

    private record Cenario(UsuarioDeTeste v, UUID peca, UUID maleta, UUID semana, UUID acerto, UUID leitura) {
    }

    /** Um vendedor com uma maleta em acerto e uma leitura (da dona) na contagem. */
    private Cenario cenario() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("50.00", 10);
        UUID maleta = maleta(v, peca, 5);
        UUID semana = jdbc.queryForObject("SELECT semana_id FROM corona.remessas WHERE id = ?", UUID.class, maleta);
        UUID acerto = UUID.randomUUID();
        postar("/api/dona/acertos", dona(), mapa("id", acerto, "vendedorId", v.vendedorId(), "semanaId", semana))
                .andExpect(status().isCreated());
        UUID leitura = UUID.randomUUID();
        postar("/api/dona/acertos/" + acerto + "/leituras", dona(), mapa("id", leitura, "produtoId", peca, "quantidade", 2,
                "estadoPeca", "BOA")).andExpect(status().isCreated());
        return new Cenario(v, peca, maleta, semana, acerto, leitura);
    }

    @Test
    void acertosELeiturasChegamSoAoDonoComAsColunasDaLista() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        Map<String, Map<UUID, LinhaDoPull>> copiaA = puxarTudo(a.v());
        Map<String, Map<UUID, LinhaDoPull>> copiaB = puxarTudo(b.v());
        for (String t : List.of("acertos", "leituras_acerto")) {
            Set<String> lista = new HashSet<>(registro.sincronizavel(t).orElseThrow().colunasDoPull());
            for (var copia : List.of(copiaA, copiaB)) {
                assertThat(copia).containsKey(t);
                copia.get(t).values().forEach(l -> assertThat(l.colunas().keySet()).as("colunas de %s", t).isEqualTo(lista));
            }
            assertThat(lista).noneMatch(c -> c.endsWith("_por"));
        }
        assertThat(copiaA.get("acertos").keySet()).containsExactly(a.acerto());
        assertThat(copiaA.get("leituras_acerto").keySet()).containsExactly(a.leitura());
        assertThat(copiaB.get("acertos").keySet()).containsExactly(b.acerto());
        assertThat(copiaA.get("acertos").keySet()).doesNotContainAnyElementsOf(copiaB.get("acertos").keySet());
        assertThat(copiaA.get("leituras_acerto").keySet()).doesNotContainAnyElementsOf(copiaB.get("leituras_acerto").keySet());
        assertThat(copiaA.get("acertos").get(a.acerto()).colunas()).containsEntry("status", "EM_ACERTO")
                .containsEntry("pedido_pelo_vendedor", false).containsEntry("vendedor_id", a.v().vendedorId());
        assertThat(copiaA.get("leituras_acerto").get(a.leitura()).colunas()).containsEntry("quantidade", 2)
                .containsEntry("estado_peca", "BOA").containsEntry("acerto_id", a.acerto());
    }

    @Test
    void desistirSobeAVersaoEOAcertoVoltaNoPull() throws Exception {
        Cenario a = cenario();
        int antes = puxarTudo(a.v()).get("acertos").get(a.acerto()).versao();
        postar("/api/dona/acertos/" + a.acerto() + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "A dona desistiu."))
                .andExpect(status().isCreated());
        LinhaDoPull depois = puxarTudo(a.v()).get("acertos").get(a.acerto());
        assertThat(depois.versao()).isGreaterThan(antes);
        assertThat(depois.colunas()).containsEntry("status", "DESISTIDO").containsEntry("motivo_desistencia", "A dona desistiu.");
        assertThat(depois.colunas().get("desistido_em")).isNotNull();
    }

    @Test
    void aMaletaChegaEmAcertoSemAsColunasDeLigacao() throws Exception {
        Cenario a = cenario();
        LinhaDoPull maleta = puxarTudo(a.v()).get("remessas").get(a.maleta());
        assertThat(maleta.colunas()).containsEntry("status", "EM_ACERTO");
        assertThat(maleta.colunas().keySet()).doesNotContain("acerto_id", "status_antes_do_acerto");
    }

    @Test
    void oPullNaoTemCustoNemOsQuemFez() throws Exception {
        Cenario a = cenario();
        String texto = puxarTudo(a.v()).entrySet().stream().filter(e -> e.getKey().contains("acerto"))
                .map(e -> e.getValue().values().toString().toLowerCase()).reduce("", String::concat);
        assertThat(texto).doesNotContain("custo").doesNotContain("iniciado_por").doesNotContain("lida_por")
                .doesNotContain("desistido_por");
    }

    @AfterAll
    static void tirarAsCotacoesEOsAcertosDaMassa() throws java.sql.SQLException {
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    @Test
    void oFechamentoChegaSoAoDonoSemOsCamposDaDonaENuncaDeOutroVendedor() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        Cenario a = cenario();
        Cenario b = cenario();
        postar("/api/dona/acertos/" + a.acerto() + "/leituras", dona(), mapa("id", UUID.randomUUID(), "produtoId", a.peca(),
                "quantidade", 3, "estadoPeca", "BOA")).andExpect(status().isCreated());
        postar("/api/dona/acertos/" + a.acerto() + "/fechar", dona(), mapa("id", UUID.randomUUID(), "faltas", List.of(),
                "defeitos", List.of(), "cobrancas", List.of(), "pendenciasAntigas", List.of(), "liquidoMostrado", "0.00"))
                .andExpect(status().isCreated());
        Map<String, Map<UUID, LinhaDoPull>> copiaA = puxarTudo(a.v());
        Map<String, Map<UUID, LinhaDoPull>> copiaB = puxarTudo(b.v());
        LinhaDoPull acerto = copiaA.get("acertos").get(a.acerto());
        assertThat(acerto.colunas()).containsEntry("status", "ACERTADO").containsKeys("liquido", "comissao", "vendido_bruto",
                "percentual_comissao", "meta_guarani", "vendido_para_meta_guarani", "cotacao_id", "fechado_em");
        assertThat(acerto.colunas().keySet()).doesNotContain("fechamento_id", "conteudo_do_fechamento", "fechado_por");
        assertThat(copiaA.get("itens_acerto").values()).hasSize(1);
        assertThat(copiaA.get("itens_acerto").values().iterator().next().colunas()).containsEntry("esperado", 5).containsEntry("contado_boa", 5);
        assertThat(copiaB.getOrDefault("itens_acerto", Map.of())).isEmpty();
        assertThat(copiaB.getOrDefault("acerto_eventos", Map.of())).isEmpty();
        assertThat(copiaB.getOrDefault("comissoes", Map.of())).isEmpty();
        assertThat(copiaB.getOrDefault("pendencias_acerto", Map.of())).isEmpty();
        assertThat(copiaB.get("acertos").get(b.acerto()).colunas()).containsEntry("status", "EM_ACERTO");
        String texto = copiaA.entrySet().stream().filter(e -> e.getKey().contains("acerto") || e.getKey().equals("comissoes"))
                .map(e -> e.getValue().values().toString().toLowerCase()).reduce("", String::concat);
        assertThat(texto).doesNotContain("custo").doesNotContain("fechado_por").doesNotContain("conteudo_do_fechamento");
    }
}
