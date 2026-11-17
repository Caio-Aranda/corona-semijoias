package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A prévia do fechamento tem de sair com o mesmo JSON antes e depois de ganhar tipo (tarefa 8.11). Uma massa conhecida
 * (o exemplo do ADR-0012, item 18, com uma falta antiga, um defeito, uma conta aberta do vendedor, boleto e restante em 2
 * parcelas), datas e cotação fixas. As respostas de "antes" ficam em {@code src/test/resources/acertos/previa-do-fechamento-antes/},
 * gravadas uma vez com o código intocado ({@code -Dcorona.previa.gravar=true}) e nunca regravadas.
 *
 * <p>Normalização: o id ou o vencimento da massa vira o nome do dado ({@code <maleta-1>}; o servidor só aceita vencimento até 12 meses
 * à frente, então as datas contam a partir de hoje); o id que o servidor gera vira {@code <gerado-N>}
 * na ordem em que aparece (chaves em ordem alfabética); o instante gravado ({@code fechadoEm}) vira {@code <instante>}.
 * Nada mais é tocado. A comparação é por {@link JsonNode}: objetos sem depender da ordem das chaves, listas na ordem.
 * O {@code eventos} da prévia sai de um {@code Map.of}, cuja ordem de chaves muda a cada JVM.
 *
 * <p>Depois da tarefa, a prévia da dona ganha exatamente {@code faltaParaMetaGuarani} e {@code faltaParaMetaReais}; a ficha do
 * acerto fechado e a prévia do vendedor não ganham nada.
 */
class PreviaDoFechamentoMesmoJsonTest extends ApoioDoFechamento {

    private static final String PASTA = "src/test/resources/acertos/previa-do-fechamento-antes";
    private static final List<String> CAMPOS_NOVOS = List.of("faltaParaMetaGuarani", "faltaParaMetaReais");
    private static final Pattern UUID_INTEIRO = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern INSTANTE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");

    private final Map<String, String> nomes = new LinkedHashMap<>();

    private void nome(UUID id, String nome) {
        nomes.put(id.toString(), nome);
    }

    private static final java.time.ZoneId SAO_PAULO = java.time.ZoneId.of("America/Sao_Paulo");

    /** Um vencimento escolhido pelo teste, contado a partir de hoje (o servidor só aceita até 12 meses): vira o nome do dado. */
    private java.time.LocalDate data(int diasAPartirDeHoje, String nome) {
        java.time.LocalDate d = java.time.LocalDate.now(SAO_PAULO).plusDays(diasAPartirDeHoje);
        return data(d, nome);
    }

    private java.time.LocalDate data(java.time.LocalDate d, String nome) {
        assertThat(nomes).as("data repetida").doesNotContainKey(d.toString());
        nomes.put(d.toString(), nome);
        return d;
    }

    private UUID pecaFixa(String nome, String codigo, String preco, int saldo, UUID categoria) throws Exception {
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", nome, "categoriaId", categoria,
                "precoVenda", preco, "precoCusto", "1.00", "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", codigo, "tipo", "LOJA", "principal", true)))).andExpect(status().isCreated()))
                .get("id").asString());
        estoque.registrar(new br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido(null, id,
                br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento.ENTRADA, saldo, null,
                br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento.MANUAL, null), usuarios.dona().id());
        return id;
    }

    /** As respostas que a massa produz, já normalizadas. */
    record Respostas(JsonNode get, JsonNode post, JsonNode desatualizado, JsonNode fechado, JsonNode doVendedor) {
    }

    @Test
    void asRespostasDaPreviaSaemComOMesmoJsonDeAntes() throws Exception {
        Respostas r = montarEPedir();
        if (gravando()) {
            Files.createDirectories(Path.of(PASTA));
            gravar("get.json", r.get());
            gravar("post-com-decisoes.json", r.post());
            gravar("409-desatualizado.json", r.desatualizado());
            gravar("get-fechado.json", r.fechado());
            gravar("previa-do-vendedor.json", r.doVendedor());
            return;
        }
        // A prévia da dona: igual ao de antes, mais (depois da tarefa) só os dois campos novos.
        confere("get.json", r.get(), true);
        confere("post-com-decisoes.json", r.post(), true);
        confere("409-desatualizado.json", r.desatualizado(), true);
        // A ficha do acerto fechado e a prévia do vendedor: idênticas, sem chave nova.
        confere("get-fechado.json", r.fechado(), false);
        confere("previa-do-vendedor.json", r.doVendedor(), false);
    }

    private static boolean gravando() {
        return Boolean.parseBoolean(System.getProperty("corona.previa.gravar", System.getenv().getOrDefault("CORONA_PREVIA_GRAVAR", "false")));
    }

    private void gravar(String arquivo, JsonNode json) throws IOException {
        Files.writeString(Path.of(PASTA, arquivo), json.toPrettyString() + "\n", StandardCharsets.UTF_8);
    }

    private void confere(String arquivo, JsonNode atual, boolean ganhaOsCamposNovos) throws IOException {
        JsonNode antes = json.readTree(Files.readString(Path.of(PASTA, arquivo), StandardCharsets.UTF_8));
        ObjectNode semNovos = (ObjectNode) atual.deepCopy();
        List<String> novos = new ArrayList<>();
        for (String campo : CAMPOS_NOVOS) {
            if (ganhaOsCamposNovos && semNovos.has(campo)) {
                novos.add(campo);
                semNovos.remove(campo);
            }
        }
        if (!semNovos.equals(antes)) {
            // Para ver a diferença: o que veio fica em target/, nunca nos arquivos de antes.
            Files.createDirectories(Path.of("target/previa-atual"));
            Files.writeString(Path.of("target/previa-atual", arquivo), atual.toPrettyString() + "\n", StandardCharsets.UTF_8);
        }
        assertThat(semNovos).as(arquivo).isEqualTo(antes);
        if (ganhaOsCamposNovos) {
            assertThat(novos).as(arquivo + ": campos novos").isIn(List.of(), CAMPOS_NOVOS);
        }
    }

    private Respostas montarEPedir() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        UUID cliente = cliente(vendedor);
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 10 WHERE chave = 'limite_desconto_percentual'");
        nome(cotacaoDoExemplo, "<cotacao>");
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Categoria da previa do fechamento"))
                .andExpect(status().isCreated())).get("id").asString());
        UUID a = pecaFixa("Anel da previa A", "PDFJ-A", "50.00", 60, categoria);
        UUID b = pecaFixa("Anel da previa B", "PDFJ-B", "33.33", 60, categoria);
        UUID c = pecaFixa("Anel da previa C", "PDFJ-C", "20.00", 60, categoria);
        nome(a, "<peca-A>");
        nome(b, "<peca-B>");
        nome(c, "<peca-C>");

        // Acerto de antes, com a meta baixa (40%): vende 1 de C (comissão 8,00), falta 1 de C para cobrar depois (pendência
        // antiga, 20,00) e fica devendo 12,00 em 1 parcela (a conta aberta do vendedor).
        configurarComissao(1, 30, 40);
        UUID m0 = maletaConferida(vendedor, semana(), c, 3);
        nome(m0, "<maleta-0>");
        UUID venda0 = vender(vendedor, m0, cliente, c, 1, "20.00");
        nome(venda0, "<venda-0>");
        UUID acerto0 = iniciar(vendedor, null);
        bipar(acerto0, c, 1, "BOA");
        fechar(acerto0, mapa("id", UUID.randomUUID(), "faltas", List.of(decisaoDeFalta(c, "COBRAR_DEPOIS", null)), "defeitos", List.of(),
                "cobrancas", List.of(), "pendenciasAntigas", List.of(), "liquidoMostrado", "12.00",
                "pagamento", mapa("restante", mapa("parcelas", 1, "primeiroVencimento", data(30, "<vencimento-da-conta-antiga>").toString())))).andExpect(status().isCreated());
        configurarComissao(700_000, 30, 40);
        UUID antiga = jdbc.queryForObject("SELECT id FROM corona.pendencias_acerto WHERE acerto_id = ? AND tipo = 'FALTA'", UUID.class,
                acerto0);
        nome(antiga, "<pendencia-antiga>");

        // Devolução (da dona) da venda de antes: a peça vai para a loja e entra neste acerto.
        UUID devolucao = UUID.fromString(corpoDe(postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", venda0,
                "motivo", "ARREPENDIMENTO", "formaReembolso", "DINHEIRO",
                "itens", List.of(mapa("produtoId", c, "quantidade", 1, "estado", "BOA")))).andExpect(status().isCreated())).get("id").asString());
        nome(devolucao, "<devolucao>");

        // A semana: maleta nova com 10 de A, reposição com 5 de B; vendas com desconto e um estorno.
        UUID m1 = maletaConferida(vendedor, semana(), a, 10);
        UUID m2 = maletaEnviada(vendedor, semana(), "REPOSICAO", b, 5);
        nome(m1, "<maleta-1>");
        nome(m2, "<maleta-2>");
        postar("/api/vendedor/maletas/" + m2 + "/conferencia", como(vendedor), mapa("id", UUID.randomUUID(),
                "itens", List.of(mapa("produtoId", b, "contada", 5)))).andExpect(status().isCreated());
        UUID v1 = UUID.randomUUID();
        postar(VENDAS, como(vendedor), mapa("id", v1, "remessaId", m1, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", a, "quantidade", 2, "desconto", "5.00")),
                "pagamentos", List.of(pagamento("95.00", "DINHEIRO")))).andExpect(status().isCreated());
        UUID v2 = vender(vendedor, m2, cliente, b, 1, "33.33");
        UUID v3 = vender(vendedor, m1, cliente, a, 1, "50.00");
        nome(v1, "<venda-1>");
        nome(v2, "<venda-2>");
        nome(v3, "<venda-3>");
        postar("/api/dona/vendas/" + v3 + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "erro")).andExpect(status().isCreated());

        // A prévia do vendedor (ainda sem acerto), para provar que a conta da meta não mudou na extração.
        JsonNode doVendedor = normalizar(corpoDe(ler("/api/vendedor/comissao/previa", como(vendedor)).andExpect(status().isOk())));

        UUID acerto = iniciar(vendedor, null);
        nome(acerto, "<acerto>");
        bipar(acerto, a, 6, "BOA");
        bipar(acerto, a, 1, "DEFEITO");
        bipar(acerto, b, 4, "BOA");
        bipar(acerto, c, 1, "BOA");

        JsonNode get = normalizar(previa(acerto));

        List<Object> faltas = List.of(decisaoDeFalta(a, "COBRAR_NO_ACERTO", null));
        List<Object> defeitos = List.of(defeitoAprovado(a, 1));
        List<Object> antigas = List.of(mapa("pendenciaId", antiga, "decisao", "COBRAR_NO_ACERTO"));
        Map<String, Object> pedido = mapa("id", UUID.randomUUID(), "faltas", faltas, "defeitos", defeitos, "cobrancas", List.of(),
                "pendenciasAntigas", antigas, "liquidoMostrado", "0.00");
        pedido.put("pagamento", mapa("recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "valor", "40.00")),
                "boleto", mapa("valor", "30.00", "vencimento", data(10, "<vencimento-do-boleto>").toString()),
                "restante", mapa("parcelas", 2, "primeiroVencimento", data(60, "<vencimento-da-parcela-1>").toString())));
        data(java.time.LocalDate.now(SAO_PAULO).plusDays(60).plusMonths(1), "<vencimento-da-parcela-2>");
        JsonNode postCru = corpoDe(postar(ACERTOS_DA_DONA + "/" + acerto + "/fechamento/previa", dona(), pedido).andExpect(status().isOk()));
        String liquido = postCru.get("liquido").asString();
        JsonNode post = normalizar(postCru);

        // Sem as decisões: o servidor recusa e devolve a prévia nova.
        JsonNode desatualizado = normalizar(corpoDe(fechar(acerto, mapa("id", UUID.randomUUID(), "faltas", List.of(), "defeitos", List.of(),
                "cobrancas", List.of(), "pendenciasAntigas", List.of(), "liquidoMostrado", "0.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FECHAMENTO_DESATUALIZADO"))).get("previa"));

        pedido.put("liquidoMostrado", liquido);
        fechar(acerto, pedido).andExpect(status().isCreated());
        JsonNode fechado = normalizar(previa(acerto));
        assertThat(new BigDecimal(liquido)).isEqualByComparingTo("147.83");
        return new Respostas(get, post, desatualizado, fechado, doVendedor);
    }

    // --- normalização ---

    /** Troca os ids pelo nome do dado, os ids gerados por {@code <gerado-N>} e o instante por {@code <instante>}. */
    private JsonNode normalizar(JsonNode original) {
        // A ficha do acerto fechado lista as pendências do acerto sem ordem definida (por id gerado): ordena por tipo e id da peça.
        if (original.has("pendencias") && original.get("pendencias").isArray()) {
            List<JsonNode> lista = new ArrayList<>();
            original.get("pendencias").forEach(lista::add);
            lista.sort(java.util.Comparator.comparing(n -> n.get("tipo").asString() + "|" + n.path("produto").path("id").asString()));
            ObjectNode copia = (ObjectNode) original.deepCopy();
            ArrayNode ordenada = json.createArrayNode();
            lista.forEach(ordenada::add);
            copia.set("pendencias", ordenada);
            original = copia;
        }
        Map<String, String> gerados = new LinkedHashMap<>();
        numerar(original, gerados);
        return trocar(original, gerados);
    }

    /** Percorre com as chaves em ordem alfabética, para a numeração não depender da ordem de serialização. */
    private void numerar(JsonNode no, Map<String, String> gerados) {
        if (no.isObject()) {
            TreeMap<String, JsonNode> ordenado = new TreeMap<>();
            no.properties().forEach(e -> ordenado.put(e.getKey(), e.getValue()));
            ordenado.values().forEach(v -> numerar(v, gerados));
        } else if (no.isArray()) {
            no.forEach(v -> numerar(v, gerados));
        } else if (no.isString() && UUID_INTEIRO.matcher(no.asString()).matches() && !nomes.containsKey(no.asString())
                && !gerados.containsKey(no.asString())) {
            gerados.put(no.asString(), "<gerado-" + (gerados.size() + 1) + ">");
        }
    }

    private JsonNode trocar(JsonNode no, Map<String, String> gerados) {
        if (no.isObject()) {
            ObjectNode novo = json.createObjectNode();
            no.properties().forEach(e -> novo.set(e.getKey(), trocar(e.getValue(), gerados)));
            return novo;
        }
        if (no.isArray()) {
            ArrayNode novo = json.createArrayNode();
            no.forEach(v -> novo.add(trocar(v, gerados)));
            return novo;
        }
        if (no.isString()) {
            String texto = no.asString();
            if (nomes.containsKey(texto)) {
                return json.getNodeFactory().stringNode(nomes.get(texto));
            }
            if (gerados.containsKey(texto)) {
                return json.getNodeFactory().stringNode(gerados.get(texto));
            }
            if (INSTANTE.matcher(texto).matches()) {
                return json.getNodeFactory().stringNode("<instante>");
            }
        }
        return no;
    }
}
