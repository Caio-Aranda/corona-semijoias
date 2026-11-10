package br.com.coronasemijoias.api.produtos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Produtos, custos e códigos da dona (tarefa 1.5; ADR-0007). */
class ProdutosDaDonaTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private RequestPostProcessor dona() {
        return usuarios.comoDona();
    }

    private static String unico(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> mapa(Object... paresChaveValor) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < paresChaveValor.length; i += 2) {
            corpo.put((String) paresChaveValor[i], paresChaveValor[i + 1]);
        }
        return corpo;
    }

    private ResultActions enviar(String metodo, String caminho, RequestPostProcessor quem, Object corpo) throws Exception {
        MockHttpServletRequestBuilder pedido = switch (metodo) {
            case "POST" -> post(caminho);
            case "PUT" -> put(caminho);
            case "DELETE" -> delete(caminho);
            default -> get(caminho);
        };
        pedido.with(quem);
        if (corpo != null) {
            pedido.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
        }
        return mvc.perform(pedido);
    }

    private JsonNode ler(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private UUID categoria() throws Exception {
        String id = ler(enviar("POST", "/api/dona/categorias", dona(), mapa("nome", unico("Cat")))
                .andExpect(status().isCreated())).get("id").asString();
        return UUID.fromString(id);
    }

    private UUID fornecedor() throws Exception {
        String id = ler(enviar("POST", "/api/dona/fornecedores", dona(), mapa("razaoSocial", unico("Forn")))
                .andExpect(status().isCreated())).get("id").asString();
        return UUID.fromString(id);
    }

    private static Map<String, Object> codigo(String texto, String tipo, boolean principal) {
        return mapa("codigo", texto, "tipo", tipo, "principal", principal);
    }

    private Map<String, Object> corpo(UUID categoria, String nome, String codigoDaLoja) {
        return mapa("nome", nome, "categoriaId", categoria, "precoVenda", "70.00", "precoCusto", "10.00",
                "estoqueMinimo", 2, "codigos", List.of(codigo(codigoDaLoja, "LOJA", true)));
    }

    private JsonNode criar(Map<String, Object> corpo) throws Exception {
        return ler(enviar("POST", "/api/dona/produtos", dona(), corpo).andExpect(status().isCreated()));
    }

    // --- permissão e custo secreto ---

    @Test
    void vendedorRecebe403EmTodasAsRotas() throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        String base = "/api/dona/produtos";
        enviar("GET", base, vendedor, null).andExpect(status().isForbidden());
        enviar("GET", base + "/" + id, vendedor, null).andExpect(status().isForbidden());
        enviar("GET", base + "/por-codigo?codigo=1", vendedor, null).andExpect(status().isForbidden());
        enviar("POST", base, vendedor, mapa()).andExpect(status().isForbidden());
        enviar("POST", base + "/codigos/proximo", vendedor, null).andExpect(status().isForbidden());
        enviar("PUT", base + "/" + id, vendedor, mapa()).andExpect(status().isForbidden());
        enviar("PUT", base + "/" + id + "/codigos", vendedor, mapa()).andExpect(status().isForbidden());
        enviar("POST", base + "/" + id + "/inativar", vendedor, null).andExpect(status().isForbidden());
        enviar("POST", base + "/" + id + "/reativar", vendedor, null).andExpect(status().isForbidden());
        enviar("DELETE", base + "/" + id, vendedor, null).andExpect(status().isForbidden());
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
    }

    @Test
    void ocontratoDoVendedorNaoTemOCusto() throws Exception {
        // O grupo do vendedor (e o do /v3/api-docs em geral) não pode citar custo nem as rotas de produtos da dona.
        String vendedor = mvc.perform(get("/v3/api-docs/vendedor")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(vendedor).doesNotContain("precocusto").doesNotContain("preco_custo").doesNotContain("produtos_custos")
                .doesNotContain("/api/dona/produtos");
    }

    @Test
    void oCustoNaoEstaEmNenhumaTabelaSincronizada() {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        assertThat(registro.soDoServidor()).anyMatch(t -> t.nome().equals("produtos_custos"));
        assertThat(registro.sincronizaveis()).noneMatch(t -> t.nome().equals("produtos_custos"));
        assertThat(registro.sincronizaveis()).filteredOn(t -> t.nome().startsWith("produto"))
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t.colunas()).noneMatch(c -> c.contains("custo")));
    }

    // --- criar, ver, editar ---

    @Test
    void criaComCodigoECustoEMostraNaDona() throws Exception {
        UUID cat = categoria();
        String cod = unico("BR");
        JsonNode p = criar(corpo(cat, unico("Brinco argola"), "  " + cod + "  "));
        assertThat(p.get("version").asInt()).isEqualTo(1);
        assertThat(p.get("precoVenda").asString()).isEqualTo("70.00");
        assertThat(p.get("precoCusto").asString()).isEqualTo("10.00");
        assertThat(p.get("estoqueAtual").asInt()).isZero();
        assertThat(p.get("ativo").asBoolean()).isTrue();
        assertThat(p.get("codigos")).hasSize(1);
        assertThat(p.get("codigos").get(0).get("codigo").asString()).isEqualTo(cod);
        assertThat(p.get("codigos").get(0).get("principal").asBoolean()).isTrue();

        Integer custos = jdbc.queryForObject("SELECT count(*) FROM corona.produtos_custos WHERE produto_id = ?",
                Integer.class, UUID.fromString(p.get("id").asString()));
        assertThat(custos).isEqualTo(1);
        JsonNode visto = ler(enviar("GET", "/api/dona/produtos/" + p.get("id").asString(), dona(), null)
                .andExpect(status().isOk()));
        assertThat(visto.get("precoCusto").asString()).isEqualTo("10.00");
    }

    @Test
    void estoqueAtualNuncaEntraPelaApi() throws Exception {
        Map<String, Object> c = corpo(categoria(), unico("Anel"), unico("AN"));
        c.put("estoqueAtual", 50);
        JsonNode p = criar(c);
        assertThat(p.get("estoqueAtual").asInt()).isZero();
        Map<String, Object> e = mapa("nome", "Anel novo", "categoriaId", c.get("categoriaId"), "precoVenda", "80.00",
                "precoCusto", "11.00", "estoqueMinimo", 1, "version", 1, "estoqueAtual", 99);
        enviar("PUT", "/api/dona/produtos/" + p.get("id").asString(), dona(), e).andExpect(status().isOk())
                .andExpect(jsonPath("$.estoqueAtual").value(0));
    }

    @Test
    void recusaPrecoNegativoEComMaisDeDuasCasas() throws Exception {
        UUID cat = categoria();
        Map<String, Object> negativo = corpo(cat, unico("X"), unico("X"));
        negativo.put("precoVenda", "-1.00");
        enviar("POST", "/api/dona/produtos", dona(), negativo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("precoVenda"));
        Map<String, Object> casas = corpo(cat, unico("X"), unico("X"));
        casas.put("precoCusto", "1.005");
        enviar("POST", "/api/dona/produtos", dona(), casas).andExpect(status().isBadRequest());
        Map<String, Object> minimo = corpo(cat, unico("X"), unico("X"));
        minimo.put("estoqueMinimo", -1);
        enviar("POST", "/api/dona/produtos", dona(), minimo).andExpect(status().isBadRequest());
    }

    @Test
    void categoriaEFornecedorPrecisamExistir() throws Exception {
        Map<String, Object> c = corpo(UUID.randomUUID(), unico("X"), unico("X"));
        enviar("POST", "/api/dona/produtos", dona(), c).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("categoriaId"));
        Map<String, Object> f = corpo(categoria(), unico("X"), unico("X"));
        f.put("fornecedorId", UUID.randomUUID());
        enviar("POST", "/api/dona/produtos", dona(), f).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("fornecedorId"));
    }

    @Test
    void editaComVersaoEGravaAuditoriaDePrecoECusto() throws Exception {
        UUID cat = categoria();
        JsonNode p = criar(corpo(cat, unico("Colar"), unico("CO")));
        String id = p.get("id").asString();
        Map<String, Object> e = mapa("nome", "Colar novo", "categoriaId", cat, "precoVenda", "99.90",
                "precoCusto", "14.20", "estoqueMinimo", 3, "version", 1);
        JsonNode editado = ler(enviar("PUT", "/api/dona/produtos/" + id, dona(), e).andExpect(status().isOk()));
        assertThat(editado.get("version").asInt()).isEqualTo(2);
        assertThat(editado.get("precoVenda").asString()).isEqualTo("99.90");
        assertThat(editado.get("precoCusto").asString()).isEqualTo("14.20");

        List<Map<String, Object>> linhas = jdbc.queryForList("""
                SELECT acao, antes::text AS antes, depois::text AS depois, ator_usuario_id
                FROM corona.auditoria WHERE tabela = 'produtos' AND registro_id = ? ORDER BY ocorrida_em, id""",
                UUID.fromString(id));
        assertThat(linhas).extracting(l -> l.get("acao")).contains("PRECO_VENDA_ALTERADO", "PRECO_CUSTO_ALTERADO");
        Map<String, Object> venda = linhas.stream().filter(l -> l.get("acao").equals("PRECO_VENDA_ALTERADO")).findFirst().orElseThrow();
        assertThat(venda.get("antes").toString()).contains("70.00");
        assertThat(venda.get("depois").toString()).contains("99.90");
        Map<String, Object> custo = linhas.stream().filter(l -> l.get("acao").equals("PRECO_CUSTO_ALTERADO")).findFirst().orElseThrow();
        assertThat(custo.get("antes").toString()).contains("10.00");
        assertThat(custo.get("depois").toString()).contains("14.20");
        assertThat(custo.get("ator_usuario_id")).isNotNull();

        // Mudar só o nome: nada novo na auditoria.
        int antes = linhas.size();
        e.put("version", 2);
        e.put("nome", "Colar outra vez");
        enviar("PUT", "/api/dona/produtos/" + id, dona(), e).andExpect(status().isOk());
        Integer depois = jdbc.queryForObject("SELECT count(*) FROM corona.auditoria WHERE tabela = 'produtos' AND registro_id = ?",
                Integer.class, UUID.fromString(id));
        assertThat(depois).isEqualTo(antes);
    }

    @Test
    void versaoVelhaDa409() throws Exception {
        UUID cat = categoria();
        JsonNode p = criar(corpo(cat, unico("Pulseira"), unico("PU")));
        Map<String, Object> e = mapa("nome", "Pulseira nova", "categoriaId", cat, "precoVenda", "50.00",
                "precoCusto", "5.00", "estoqueMinimo", 0, "version", 1);
        enviar("PUT", "/api/dona/produtos/" + p.get("id").asString(), dona(), e).andExpect(status().isOk());
        enviar("PUT", "/api/dona/produtos/" + p.get("id").asString(), dona(), e).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_ALTERADO"));
    }

    // --- listar e buscar ---

    @Test
    void listaComBuscaFiltrosEPagina() throws Exception {
        UUID cat = categoria();
        UUID forn = fornecedor();
        String marca = "zq" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 3; i++) {
            Map<String, Object> c = corpo(cat, marca + " peça " + i, marca + "-" + i);
            c.put("fornecedorId", forn);
            criar(c);
        }
        criar(corpo(categoria(), marca + " de outra categoria", marca + "-x"));

        enviar("GET", "/api/dona/produtos?busca=" + marca, dona(), null).andExpect(jsonPath("$.total").value(4));
        enviar("GET", "/api/dona/produtos?busca=" + marca + "&categoriaId=" + cat + "&tamanho=2", dona(), null)
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.itens.length()").value(2));
        enviar("GET", "/api/dona/produtos?busca=" + marca + "&fornecedorId=" + forn, dona(), null)
                .andExpect(jsonPath("$.total").value(3));
        // Busca por parte do código, sem diferenciar maiúsculas.
        enviar("GET", "/api/dona/produtos?busca=" + marca.toUpperCase() + "-1", dona(), null)
                .andExpect(jsonPath("$.total").value(1));
        enviar("GET", "/api/dona/produtos?busca=" + marca + "&ativo=false", dona(), null)
                .andExpect(jsonPath("$.total").value(0));
        enviar("GET", "/api/dona/produtos?tamanho=101", dona(), null).andExpect(status().isBadRequest());
    }

    @Test
    void buscaPorReferencia() throws Exception {
        String ref = "REF" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> c = corpo(categoria(), unico("Com ref"), unico("RF"));
        c.put("referencia", ref);
        criar(c);
        enviar("GET", "/api/dona/produtos?busca=" + ref.toLowerCase(), dona(), null).andExpect(jsonPath("$.total").value(1));
    }

    // --- inativar, excluir ---

    @Test
    void inativaReativaEExcluiLiberandoOCodigo() throws Exception {
        UUID cat = categoria();
        String cod = unico("EX");
        JsonNode p = criar(corpo(cat, unico("Excluir"), cod));
        String id = p.get("id").asString();
        enviar("POST", "/api/dona/produtos/" + id + "/inativar", dona(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
        enviar("POST", "/api/dona/produtos/" + id + "/reativar", dona(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true));

        enviar("DELETE", "/api/dona/produtos/" + id, dona(), null).andExpect(status().isNoContent());
        enviar("GET", "/api/dona/produtos/" + id, dona(), null).andExpect(status().isNotFound());
        // O código volta a ficar livre.
        criar(corpo(cat, unico("Outro"), cod.toLowerCase()));
    }

    // --- códigos (ADR-0007) ---

    @Test
    void codigoRepetidoDa409DizendoQualProdutoUsa() throws Exception {
        UUID cat = categoria();
        String cod = unico("DUP");
        String nome = unico("Dono do código");
        criar(corpo(cat, nome, cod));
        String resposta = enviar("POST", "/api/dona/produtos", dona(), corpo(cat, unico("Segundo"), "  " + cod.toLowerCase() + " "))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CODIGO_JA_USADO"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(resposta).contains(nome);
    }

    @Test
    void aceitaEanValidoEPreservaZeros() throws Exception {
        Map<String, Object> c = corpo(categoria(), unico("Com EAN"), unico("L"));
        c.put("codigos", List.of(codigo("0012345678905", "EAN13", false), codigo(unico("L"), "LOJA", true)));
        JsonNode p = criar(c);
        assertThat(p.get("codigos").findValuesAsString("codigo")).contains("0012345678905");
    }

    @Test
    void recusaEanInvalidoMasNaoValidaOutrosTipos() throws Exception {
        UUID cat = categoria();
        Map<String, Object> ruim = corpo(cat, unico("EAN ruim"), unico("L"));
        ruim.put("codigos", List.of(codigo("7891000100104", "EAN13", true)));
        enviar("POST", "/api/dona/produtos", dona(), ruim).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("codigos"));
        // Código do fornecedor com 13 dígitos "errados" vale: o dígito só conta no tipo EAN13.
        Map<String, Object> livre = corpo(cat, unico("Fornecedor 13"), unico("L"));
        livre.put("codigos", List.of(codigo("7891000100105", "FORNECEDOR", true)));
        criar(livre);
        // EAN13 que não tem 13 dígitos não é conferido.
        Map<String, Object> curto = corpo(cat, unico("EAN curto"), unico("L"));
        curto.put("codigos", List.of(codigo("12345" + UUID.randomUUID().toString().substring(0, 4), "EAN13", true)));
        criar(curto);
    }

    @Test
    void recusaCodigoVazioTipoDesconhecidoPrincipalDuploRepetidoESemCodigo() throws Exception {
        UUID cat = categoria();
        Map<String, Object> a = corpo(cat, unico("A"), unico("A"));
        a.put("codigos", List.of(codigo("   ", "LOJA", true)));
        enviar("POST", "/api/dona/produtos", dona(), a).andExpect(status().isBadRequest());
        Map<String, Object> b = corpo(cat, unico("B"), unico("B"));
        b.put("codigos", List.of(codigo(unico("B"), "OUTRO", true)));
        enviar("POST", "/api/dona/produtos", dona(), b).andExpect(status().isBadRequest());
        Map<String, Object> c = corpo(cat, unico("C"), unico("C"));
        c.put("codigos", List.of(codigo(unico("C1"), "LOJA", true), codigo(unico("C2"), "LOJA", true)));
        enviar("POST", "/api/dona/produtos", dona(), c).andExpect(status().isBadRequest());
        Map<String, Object> d = corpo(cat, unico("D"), unico("D"));
        String mesmo = unico("D");
        d.put("codigos", List.of(codigo(mesmo, "LOJA", true), codigo(mesmo.toUpperCase(), "FORNECEDOR", false)));
        enviar("POST", "/api/dona/produtos", dona(), d).andExpect(status().isBadRequest());
        Map<String, Object> sem = corpo(cat, unico("E"), unico("E"));
        sem.put("codigos", List.of());
        enviar("POST", "/api/dona/produtos", dona(), sem).andExpect(status().isBadRequest());
    }

    @Test
    void semPrincipalMarcadoOPrimeiroViraPrincipal() throws Exception {
        Map<String, Object> c = corpo(categoria(), unico("P"), unico("P"));
        c.put("codigos", List.of(codigo(unico("P1"), "LOJA", false), codigo(unico("P2"), "FORNECEDOR", false)));
        JsonNode p = criar(c);
        assertThat(p.get("codigos").get(0).get("principal").asBoolean()).isTrue();
        assertThat(p.get("codigos").get(1).get("principal").asBoolean()).isFalse();
    }

    @Test
    void alteraOsCodigosTrocandoOPrincipalEApagandoUm() throws Exception {
        UUID cat = categoria();
        String antigo = unico("OLD");
        JsonNode p = criar(corpo(cat, unico("Trocar"), antigo));
        String id = p.get("id").asString();
        String idAntigo = p.get("codigos").get(0).get("id").asString();
        String novo = unico("NEW");
        Map<String, Object> pedido = mapa("version", 1, "codigos", List.of(
                mapa("id", idAntigo, "codigo", antigo, "tipo", "LOJA", "principal", false),
                mapa("codigo", novo, "tipo", "FORNECEDOR", "principal", true)));
        JsonNode r = ler(enviar("PUT", "/api/dona/produtos/" + id + "/codigos", dona(), pedido).andExpect(status().isOk()));
        assertThat(r.get("version").asInt()).isEqualTo(2);
        assertThat(r.get("codigos")).hasSize(2);
        assertThat(r.get("codigos").findValuesAsString("codigo")).contains(antigo, novo);

        // Tira o antigo da lista: ele some e o código fica livre para outro produto.
        Map<String, Object> so = mapa("version", 2, "codigos", List.of(mapa("codigo", novo, "tipo", "FORNECEDOR", "principal", true)));
        enviar("PUT", "/api/dona/produtos/" + id + "/codigos", dona(), so).andExpect(status().isOk())
                .andExpect(jsonPath("$.codigos.length()").value(1));
        criar(corpo(cat, unico("Pega o antigo"), antigo));

        // A versão velha é recusada.
        enviar("PUT", "/api/dona/produtos/" + id + "/codigos", dona(), so).andExpect(status().isConflict());
    }

    @Test
    void buscaProdutoPeloCodigoExato() throws Exception {
        String cod = unico("ExAtO");
        JsonNode p = criar(corpo(categoria(), unico("Achar"), cod));
        mvc.perform(get("/api/dona/produtos/por-codigo").param("codigo", "  " + cod.toLowerCase() + " ").with(dona()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(p.get("id").asString()));
        enviar("GET", "/api/dona/produtos/por-codigo?codigo=" + cod.substring(0, 4), dona(), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void geraProximoCodigoDaLojaSemRepetirEmChamadasEmParalelo() throws Exception {
        int chamadas = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futuros = new ArrayList<>();
            for (int i = 0; i < chamadas; i++) {
                Callable<String> t = () -> ler(enviar("POST", "/api/dona/produtos/codigos/proximo", dona(), null)
                        .andExpect(status().isOk())).get("codigo").asString();
                futuros.add(pool.submit(t));
            }
            Set<String> codigos = new HashSet<>();
            for (Future<String> f : futuros) {
                codigos.add(f.get());
            }
            assertThat(codigos).hasSize(chamadas);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void proximoCodigoPulaOQueJaEstaEmUso() throws Exception {
        String primeiro = ler(enviar("POST", "/api/dona/produtos/codigos/proximo", dona(), null)).get("codigo").asString();
        long proximo = Long.parseLong(primeiro) + 1;
        criar(corpo(categoria(), unico("Já usa o próximo"), String.valueOf(proximo)));
        String gerado = ler(enviar("POST", "/api/dona/produtos/codigos/proximo", dona(), null)).get("codigo").asString();
        assertThat(Long.parseLong(gerado)).isGreaterThan(proximo);
    }

    @Test
    void criacoesEmParaleloComOMesmoCodigoDeixamSoUmProduto() throws Exception {
        UUID cat = categoria();
        String cod = unico("RACE");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String nome = unico("Corrida");
                futuros.add(pool.submit(() -> enviar("POST", "/api/dona/produtos", dona(), corpo(cat, nome, cod))
                        .andReturn().getResponse().getStatus()));
            }
            int criados = 0;
            for (Future<Integer> f : futuros) {
                int s = f.get();
                assertThat(s).isIn(201, 409);
                if (s == 201) {
                    criados++;
                }
            }
            assertThat(criados).isEqualTo(1);
            Integer produtos = jdbc.queryForObject(
                    "SELECT count(*) FROM corona.produto_codigos WHERE lower(codigo) = lower(?) AND deleted_at IS NULL",
                    Integer.class, cod);
            assertThat(produtos).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- categoria e fornecedor em uso ---

    @Test
    void categoriaEFornecedorEmUsoNaoSaem() throws Exception {
        UUID cat = categoria();
        UUID forn = fornecedor();
        Map<String, Object> c = corpo(cat, unico("Usa"), unico("US"));
        c.put("fornecedorId", forn);
        JsonNode p = criar(c);
        enviar("DELETE", "/api/dona/categorias/" + cat, dona(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_EM_USO_POR_PRODUTOS"));
        enviar("DELETE", "/api/dona/fornecedores/" + forn, dona(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_EM_USO_POR_PRODUTOS"));
        enviar("DELETE", "/api/dona/produtos/" + p.get("id").asString(), dona(), null).andExpect(status().isNoContent());
        enviar("DELETE", "/api/dona/categorias/" + cat, dona(), null).andExpect(status().isNoContent());
        enviar("DELETE", "/api/dona/fornecedores/" + forn, dona(), null).andExpect(status().isNoContent());
    }

    @Test
    void precoGuardadoEmNumericSemFloat() {
        String tipo = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a
                WHERE a.attrelid = 'corona.produtos'::regclass AND a.attname = 'preco_venda'""", String.class);
        assertThat(tipo).isEqualTo("numeric(14,2)");
        String custo = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a
                WHERE a.attrelid = 'corona.produtos_custos'::regclass AND a.attname = 'preco_custo'""", String.class);
        assertThat(custo).isEqualTo("numeric(14,2)");
    }
}
