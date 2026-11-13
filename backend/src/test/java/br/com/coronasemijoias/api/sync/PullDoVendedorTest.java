package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O pull do vendedor pela rota (tarefa 5.4; ADR-0013, itens 3 a 6): cada furo conhecido do filtro como teste
 * determinístico, a varredura de marcadores proibidos em toda resposta, os erros da rota, a conferência por resumo e os
 * gatilhos que impedem a pertença de mudar.
 */
class PullDoVendedorTest extends TesteDeIntegracao {

    private static final String PULL = "/api/vendedor/sync/pull";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    ServicoDeEstoque estoque;

    @Autowired
    ServicoDeRemessas remessas;

    @Autowired
    ServicoDeConferencia conferencias;

    @Autowired
    ServicoDeVendas vendas;

    private CenarioDeSync cenario() {
        return new CenarioDeSync(jdbc, usuarios, estoque, remessas, conferencias, vendas);
    }

    /** O que o app guardou: tabela → id → linha recebida (só a de version maior fica). */
    private static final class Copia {

        final Map<String, Map<String, JsonNode>> tabelas = new HashMap<>();
        String cursor;

        JsonNode linha(String tabela, Object id) {
            return tabelas.getOrDefault(tabela, Map.of()).get(id.toString());
        }

        boolean tem(String tabela, Object id) {
            return linha(tabela, id) != null;
        }
    }

    private JsonNode receber(RequestPostProcessor quem, String parametros) throws Exception {
        String corpo = mvc.perform(get(PULL + parametros).with(quem)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        varrer(corpo);
        return json.readTree(corpo);
    }

    /** Recebe até não haver mais nada, aplicando a regra da version. */
    private void receberTudo(RequestPostProcessor quem, Copia copia, int limite) throws Exception {
        JsonNode r;
        do {
            r = receber(quem, "?limite=" + limite + (copia.cursor == null ? "" : "&cursor=" + copia.cursor));
            for (JsonNode l : r.get("linhas")) {
                Map<String, JsonNode> t = copia.tabelas.computeIfAbsent(l.get("tabela").asString(), x -> new HashMap<>());
                JsonNode atual = t.get(l.get("id").asString());
                if (atual == null || l.get("version").asInt() > atual.get("version").asInt()) {
                    t.put(l.get("id").asString(), l);
                }
            }
            copia.cursor = r.get("cursor").asString();
        } while (r.get("temMais").asBoolean());
    }

    /** Nenhum marcador proibido (scripts/marcadores-proibidos.json) nem "custo" em nenhuma resposta (ADR-0013, item 5, prova 2). */
    private void varrer(String corpo) throws Exception {
        Path arquivo = Path.of("../scripts/marcadores-proibidos.json");
        if (!Files.exists(arquivo)) {
            arquivo = Path.of("scripts/marcadores-proibidos.json");
        }
        List<String> marcadores = new ArrayList<>();
        json.readTree(Files.readString(arquivo)).get("marcadores").forEach(m -> marcadores.add(m.asString()));
        marcadores.add("custo");
        marcadores.add("estoque_atual");
        // O tipo de código "FORNECEDOR" pode ir; o fornecedor da peça (fornecedor_id) nunca.
        marcadores.add("fornecedor_id");
        for (String m : marcadores) {
            assertThat(corpo.toLowerCase()).as("marcador proibido na resposta do pull: %s", m).doesNotContain(m.toLowerCase());
        }
    }

    private RequestPostProcessor como(UsuarioDeTeste v) {
        return UsuariosDeTeste.comToken(usuarios.token(v));
    }

    // --- os furos conhecidos (ADR-0013, item 5) ---

    @Test
    void pecaAntigaComCodigoEFotoChegaQuandoEntraNumaMaletaNovaEORascunhoNuncaChega() throws Exception {
        CenarioDeSync c = cenario();
        UsuarioDeTeste v = usuarios.criarVendedor();
        RequestPostProcessor quem = como(v);
        UUID antiga = c.peca(new BigDecimal("40.00"), 10);
        c.acrescentarCodigo(antiga);
        Copia copia = new Copia();
        receberTudo(quem, copia, 50);
        assertThat(copia.tem("produtos", antiga)).isFalse();

        // Rascunho com a peça: nada dele chega (nem a maleta, nem os itens, nem a peça).
        UUID rascunho = c.rascunho(v, Map.of(antiga, 2));
        receberTudo(quem, copia, 7);
        assertThat(copia.tem("remessas", rascunho)).isFalse();
        assertThat(copia.tabelas.getOrDefault("itens_remessa", Map.of())).isEmpty();
        assertThat(copia.tem("produtos", antiga)).isFalse();

        // Enviada: a maleta expande para os itens, e os itens para a peça, os códigos e a foto (gravados antes).
        c.enviar(rascunho);
        receberTudo(quem, copia, 1);
        assertThat(copia.tem("remessas", rascunho)).isTrue();
        assertThat(copia.tabelas.get("itens_remessa")).hasSize(1);
        JsonNode peca = copia.linha("produtos", antiga);
        assertThat(peca.get("campos").get("preco_venda").asString()).isEqualTo("40.00");
        assertThat(jdbc.queryForList("SELECT id FROM corona.produto_codigos WHERE produto_id = ?", UUID.class, antiga))
                .allMatch(id -> copia.tem("produto_codigos", id)).hasSize(2);
        assertThat(jdbc.queryForList("SELECT id FROM corona.produtos_fotos WHERE produto_id = ?", UUID.class, antiga))
                .allMatch(id -> copia.tem("produtos_fotos", id)).isNotEmpty();

        // Troca de foto e código excluído depois: a lápide e a foto nova chegam pelo caminho normal.
        c.trocarFoto(antiga);
        c.excluirUmCodigoSecundario(antiga);
        receberTudo(quem, copia, 3);
        long fotosAtivas = copia.tabelas.get("produtos_fotos").values().stream()
                .filter(l -> l.get("deletedAt").isNull()).count();
        assertThat(fotosAtivas).isEqualTo(1);
        assertThat(copia.tabelas.get("produto_codigos").values().stream().filter(l -> !l.get("deletedAt").isNull()).count())
                .isEqualTo(1);

        // Rascunho excluído nunca aparece.
        UUID outro = c.rascunho(v, Map.of(c.peca(new BigDecimal("5.00"), 3), 1));
        c.excluirRascunho(outro);
        receberTudo(quem, copia, 50);
        assertThat(copia.tem("remessas", outro)).isFalse();
    }

    @Test
    void clientesDaLojaDiretorioParaTodosEOCadastroSoParaQuemAtendeNosDoisSentidos() throws Exception {
        CenarioDeSync c = cenario();
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID cliente = c.cliente(a.vendedorId());
        Copia deA = new Copia();
        Copia deB = new Copia();
        receberTudo(como(a), deA, 200);
        receberTudo(como(b), deB, 200);

        JsonNode emA = deA.linha("clientes", cliente).get("campos");
        assertThat(emA.get("observacoes").asString()).isEqualTo("Só paga em dinheiro");
        assertThat(emA.get("vendedor_responsavel_id").asString()).isEqualTo(a.vendedorId().toString());
        JsonNode emB = deB.linha("clientes", cliente).get("campos");
        assertThat(emB.get("nome").asString()).startsWith("Cliente ");
        assertThat(emB.get("telefone").asString()).isEqualTo("(19) 3000-0001");
        assertThat(emB.get("endereco_cidade").asString()).isEqualTo("Campinas");
        assertThat(emB.get("observacoes").isNull()).isTrue();
        assertThat(emB.get("whatsapp").isNull()).isTrue();
        assertThat(emB.get("vendedor_responsavel_id").isNull()).isTrue();

        // A dona passa o cliente para B: B recebe o cadastro; A fica só com o diretório.
        c.transferir(cliente, b.vendedorId());
        receberTudo(como(a), deA, 200);
        receberTudo(como(b), deB, 200);
        assertThat(deA.linha("clientes", cliente).get("campos").get("observacoes").isNull()).isTrue();
        assertThat(deA.linha("clientes", cliente).get("campos").get("vendedor_responsavel_id").isNull()).isTrue();
        assertThat(deB.linha("clientes", cliente).get("campos").get("observacoes").asString()).isEqualTo("Só paga em dinheiro");
        // E volta para A.
        c.transferir(cliente, a.vendedorId());
        receberTudo(como(a), deA, 200);
        receberTudo(como(b), deB, 200);
        assertThat(deA.linha("clientes", cliente).get("campos").get("whatsapp").asString()).isEqualTo("(19) 3000-0002");
        assertThat(deB.linha("clientes", cliente).get("campos").get("whatsapp").isNull()).isTrue();
    }

    @Test
    void vendasConferenciasEMaletasDeOutroVendedorNuncaChegamEDinheiroVemComoTexto() throws Exception {
        CenarioDeSync c = cenario();
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        usuarios.token(a);
        usuarios.token(b);
        UUID peca = c.peca(new BigDecimal("12.30"), 10);
        UUID deA = c.maleta(a, Map.of(peca, 3));
        UUID deB = c.maleta(b, Map.of(peca, 3));
        c.conferir(a, deA);
        c.conferir(b, deB);
        UUID cliente = c.cliente(null);
        c.vender(a, deA, cliente, peca);
        c.vender(b, deB, cliente, peca);

        Copia copia = new Copia();
        receberTudo(como(a), copia, 4);
        assertThat(copia.tabelas.get("remessas").keySet()).containsExactly(deA.toString());
        assertThat(copia.tabelas.get("vendas")).hasSize(1);
        assertThat(copia.tabelas.get("conferencias")).hasSize(1);
        assertThat(copia.tabelas.get("movimentos_remessa").values())
                .allMatch(m -> m.get("campos").get("remessa_id").asString().equals(deA.toString()));
        JsonNode venda = copia.tabelas.get("vendas").values().iterator().next().get("campos");
        assertThat(venda.get("total").isString()).isTrue();
        assertThat(venda.get("total").asString()).isEqualTo("12.30");
        assertThat(venda.get("vendedor_id").asString()).isEqualTo(a.vendedorId().toString());
        // A peça é dos dois (esteve nas duas maletas): chega para A uma vez só.
        assertThat(copia.tem("produtos", peca)).isTrue();
    }

    // --- a rota ---

    @Test
    void respostaTrazHoraParametrosECursorEOsErrosSaoClaros() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        RequestPostProcessor quem = como(v);
        JsonNode r = receber(quem, "?limite=1");
        assertThat(r.get("horaServidor").asString()).isNotBlank();
        assertThat(r.get("parametros").get("limiteDescontoPercentual").isInt()).isTrue();
        assertThat(r.get("parametros").get("tempoMaximoOfflineDias").asInt()).isPositive();
        assertThat(r.get("parametros").get("situacaoDoAparelho").asString()).isEqualTo("ATIVO");
        assertThat(r.get("linhas").size()).isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ultimo_pull_em IS NOT NULL FROM corona.dispositivos WHERE id = ?", Boolean.class,
                usuarios.aparelhoDe(v))).isTrue();

        mvc.perform(get(PULL + "?cursor=9999:1:1").with(quem)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CURSOR_INVALIDO"));
        mvc.perform(get(PULL + "?cursor=lixo").with(quem)).andExpect(status().isConflict());
        mvc.perform(get(PULL + "?limite=1001").with(quem)).andExpect(status().isBadRequest());
        mvc.perform(get(PULL + "?limite=0").with(quem)).andExpect(status().isBadRequest());
        mvc.perform(get(PULL + "?tabela=produtos_custos").with(quem)).andExpect(status().isBadRequest());
        mvc.perform(get(PULL + "?tabela=teste_sync_a").with(quem)).andExpect(status().isBadRequest());
        mvc.perform(get(PULL).with(usuarios.comoDona())).andExpect(status().isForbidden());
        mvc.perform(get(PULL)).andExpect(status().isUnauthorized());
        // Segundo computador do vendedor, ainda sem liberação: não recebe nada.
        String noSegundo = usuarios.tokenNoAparelho(v, UUID.randomUUID());
        mvc.perform(get(PULL).with(UsuariosDeTeste.comToken(noSegundo))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("APARELHO_NAO_LIBERADO"));
    }

    @Test
    void recargaDeUmaTabelaSoTrazEla() throws Exception {
        CenarioDeSync c = cenario();
        UsuarioDeTeste v = usuarios.criarVendedor();
        c.maleta(v, Map.of(c.peca(new BigDecimal("9.90"), 5), 1));
        JsonNode r;
        String cursor = "";
        List<String> tabelas = new ArrayList<>();
        do {
            r = receber(como(v), "?tabela=remessas&limite=1000" + (cursor.isEmpty() ? "" : "&cursor=" + cursor));
            r.get("linhas").forEach(l -> tabelas.add(l.get("tabela").asString()));
            cursor = r.get("cursor").asString();
        } while (r.get("temMais").asBoolean());
        // A maleta e o que ela expande; nada das tabelas da loja.
        assertThat(tabelas).contains("remessas", "itens_remessa", "produtos").doesNotContain("cotacoes", "categorias", "clientes");
    }

    @Test
    void conferenciaPorResumoBateComOQueOAppGuardou() throws Exception {
        CenarioDeSync c = cenario();
        UsuarioDeTeste v = usuarios.criarVendedor();
        usuarios.token(v);
        UUID peca = c.peca(new BigDecimal("20.00"), 5);
        UUID maleta = c.maleta(v, Map.of(peca, 2));
        c.conferir(v, maleta);
        c.vender(v, maleta, c.cliente(v.vendedorId()), peca);
        c.trocarFoto(peca);
        Copia copia = new Copia();
        receberTudo(como(v), copia, 37);

        String corpo = mvc.perform(get("/api/vendedor/sync/conferencia").with(como(v))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        varrer(corpo);
        JsonNode r = json.readTree(corpo);
        // 23: as 15 de antes, devolucoes e itens_devolucao (tarefa 6.3), autorizacoes_excecao e itens_autorizacao
        // (tarefa 6.4), trocas, creditos_cliente e usos_credito (tarefa 6.5) e creditos_sem_saldo (tarefa 6.7).
        assertThat(r.get("tabelas").size()).isEqualTo(23);
        for (JsonNode t : r.get("tabelas")) {
            Map<String, JsonNode> guardadas = copia.tabelas.getOrDefault(t.get("tabela").asString(), Map.of());
            TreeMap<String, Integer> ordenadas = new TreeMap<>();
            guardadas.forEach((id, l) -> ordenadas.put(id, l.get("version").asInt()));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            ordenadas.forEach((id, versao) -> sha.update((id + ":" + versao + "\n").getBytes(StandardCharsets.UTF_8)));
            assertThat(t.get("quantidade").asLong()).as(t.get("tabela").asString()).isEqualTo(guardadas.size());
            assertThat(t.get("resumo").asString()).as(t.get("tabela").asString()).isEqualTo(HexFormat.of().formatHex(sha.digest()));
        }
        mvc.perform(post("/api/vendedor/sync/conferencia/diferencas").with(como(v)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tabelas\":[\"produtos\"]}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/vendedor/sync/conferencia").with(usuarios.comoDona())).andExpect(status().isForbidden());
    }

    // --- a pertença não muda (V032) ---

    @Test
    void depoisDeEnviadaAMaletaNaoTrocaDeVendedorNemPerdeItemEAVendaNaoTrocaDeVendedor() {
        CenarioDeSync c = cenario();
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        usuarios.token(a);
        UUID peca = c.peca(new BigDecimal("15.00"), 5);
        UUID rascunho = c.rascunho(a, Map.of(peca, 1));
        // No rascunho a dona ainda pode trocar o vendedor.
        jdbc.update("UPDATE corona.remessas SET vendedor_id = ? WHERE id = ?", b.vendedorId(), rascunho);
        jdbc.update("UPDATE corona.remessas SET vendedor_id = ? WHERE id = ?", a.vendedorId(), rascunho);
        c.enviar(rascunho);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET vendedor_id = ? WHERE id = ?", b.vendedorId(), rascunho))
                .hasMessageContaining("já foi enviada");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET enviada_em = NULL WHERE id = ?", rascunho))
                .hasMessageContaining("já foi enviada");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.itens_remessa SET deleted_at = now() WHERE remessa_id = ?", rascunho))
                .hasMessageContaining("não é excluído");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.itens_remessa SET produto_id = ? WHERE remessa_id = ?",
                c.peca(BigDecimal.ONE, 0), rascunho)).hasMessageContaining("não mudam");
        c.conferir(a, rascunho);
        c.vender(a, rascunho, c.cliente(null), peca);
        UUID venda = jdbc.queryForObject("SELECT id FROM corona.vendas WHERE remessa_id = ?", UUID.class, rascunho);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.vendas SET vendedor_id = ? WHERE id = ?", b.vendedorId(), venda))
                .hasMessageContaining("não muda");
        UUID foto = jdbc.queryForObject("SELECT id FROM corona.produtos_fotos WHERE produto_id = ? LIMIT 1", UUID.class, peca);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.produtos_fotos SET produto_id = ? WHERE id = ?",
                c.peca(BigDecimal.ONE, 0), foto)).hasMessageContaining("não muda");
    }
}
