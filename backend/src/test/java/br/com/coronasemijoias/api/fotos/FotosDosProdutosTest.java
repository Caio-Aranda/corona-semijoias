package br.com.coronasemijoias.api.fotos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fotos de produto da dona (tarefa 1.6; §20): envio, ordem, principal, download e importação em massa. */
class FotosDosProdutosTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/produtos";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    br.com.coronasemijoias.api.arquivos.ArmazenamentoDeArquivos armazenamento;

    @Autowired
    LimpezaDeArquivosDeFotos limpeza;

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

    private JsonNode ler(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pedido,
            Object corpo) throws Exception {
        pedido.with(dona());
        if (corpo != null) {
            pedido.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
        }
        return mvc.perform(pedido);
    }

    private UUID categoria() throws Exception {
        return UUID.fromString(ler(json(post("/api/dona/categorias"), mapa("nome", unico("Cat")))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private UUID fornecedor() throws Exception {
        return UUID.fromString(ler(json(post("/api/dona/fornecedores"), mapa("razaoSocial", unico("Forn")))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private JsonNode produto(UUID categoria, UUID fornecedor, String nome, String codigo) throws Exception {
        Map<String, Object> corpo = mapa("nome", nome, "categoriaId", categoria, "precoVenda", "70.00",
                "precoCusto", "10.00", "codigos",
                List.of(mapa("codigo", codigo, "tipo", "LOJA", "principal", true)));
        if (fornecedor != null) {
            corpo.put("fornecedorId", fornecedor);
        }
        return ler(json(post(BASE), corpo).andExpect(status().isCreated()));
    }

    private UUID produto() throws Exception {
        return UUID.fromString(produto(categoria(), null, unico("Anel"), unico("P")).get("id").asString());
    }

    private ResultActions enviarFoto(UUID produto, String nome, String tipo, byte[] bytes, RequestPostProcessor quem)
            throws Exception {
        return mvc.perform(multipart(BASE + "/" + produto + "/fotos")
                .file(new MockMultipartFile("arquivo", nome, tipo, bytes)).with(quem));
    }

    private JsonNode enviarFotoBoa(UUID produto, int semente) throws Exception {
        return ler(enviarFoto(produto, "foto.jpg", "image/jpeg", ImagensDeTeste.jpeg(900, 600, semente), dona())
                .andExpect(status().is2xxSuccessful()));
    }

    private JsonNode fotosDe(UUID produto) throws Exception {
        return ler(json(get(BASE + "/" + produto + "/fotos"), null).andExpect(status().isOk()));
    }

    private ResultActions importar(String caminho, RequestPostProcessor quem, MockMultipartFile... arquivos) throws Exception {
        MockMultipartHttpServletRequestBuilder pedido = multipart(BASE + "/fotos/importacao/" + caminho);
        for (MockMultipartFile a : arquivos) {
            pedido.file(a);
        }
        return mvc.perform(pedido.with(quem));
    }

    private static MockMultipartFile arquivo(String nome, byte[] bytes) {
        return new MockMultipartFile("arquivos", nome, "application/octet-stream", bytes);
    }

    private int totalDeFotos() {
        return jdbc.queryForObject("SELECT count(*) FROM corona.produtos_fotos", Integer.class);
    }

    // --- permissão ---

    @Test
    void soADonaMexeNasFotos() throws Exception {
        UUID produto = produto();
        JsonNode foto = enviarFotoBoa(produto, 1);
        String id = foto.get("id").asString();
        String hash = foto.get("hash").asString();
        RequestPostProcessor vendedor = usuarios.comoVendedor();

        enviarFoto(produto, "a.jpg", "image/jpeg", ImagensDeTeste.jpeg(100, 100, 3), vendedor).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + produto + "/fotos").with(vendedor)).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/" + produto + "/fotos/" + id).with(vendedor)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + produto + "/fotos/" + id + "/principal").with(vendedor)).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/" + produto + "/fotos/ordem").with(vendedor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[]}")).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/fotos/" + hash + "/media").with(vendedor)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/sem-foto").with(vendedor)).andExpect(status().isForbidden());
        importar("previa", vendedor).andExpect(status().isForbidden());
        importar("confirmar", vendedor, arquivo("x.jpg", ImagensDeTeste.jpeg(100, 100, 4))).andExpect(status().isForbidden());

        mvc.perform(get(BASE + "/fotos/" + hash + "/media")).andExpect(status().isUnauthorized());
        mvc.perform(multipart(BASE + "/" + produto + "/fotos").file(new MockMultipartFile("arquivo", "a.jpg", "image/jpeg",
                ImagensDeTeste.jpeg(100, 100, 5)))).andExpect(status().isUnauthorized());
    }

    // --- envio ---

    @Test
    void enviaAFotoGeraTresTamanhosEMostraNaLeituraDoProduto() throws Exception {
        UUID produto = produto();
        // Antes da foto: a falta nunca é erro, o produto vem com foto vazia.
        mvc.perform(get(BASE + "/" + produto).with(dona())).andExpect(status().isOk())
                .andExpect(jsonPath("$.fotoPrincipal").value((Object) null));

        JsonNode foto = enviarFotoBoa(produto, 10);
        assertThat(foto.get("largura").asInt()).isEqualTo(900);
        assertThat(foto.get("altura").asInt()).isEqualTo(600);
        String hash = foto.get("hash").asString();
        assertThat(hash).hasSize(64);
        assertThat(foto.get("miniaturaUrl").asString()).isEqualTo(BASE + "/fotos/" + hash + "/miniatura");
        assertThat(foto.get("mediaUrl").asString()).isEqualTo(BASE + "/fotos/" + hash + "/media");
        assertThat(foto.get("grandeUrl").asString()).isEqualTo(BASE + "/fotos/" + hash + "/grande");

        for (String leitura : List.of(BASE + "/" + produto, BASE + "?busca=" + produtoNome(produto))) {
            ResultActions r = mvc.perform(get(leitura).with(dona())).andExpect(status().isOk());
            JsonNode corpo = ler(r);
            JsonNode item = corpo.has("itens") ? corpo.get("itens").get(0) : corpo;
            assertThat(item.get("fotoPrincipal").get("hash").asString()).isEqualTo(hash);
            assertThat(item.get("fotoPrincipal").get("miniaturaUrl").asString()).endsWith("/" + hash + "/miniatura");
        }
        String codigo = jdbc.queryForObject(
                "SELECT codigo FROM corona.produto_codigos WHERE produto_id = ? AND deleted_at IS NULL", String.class, produto);
        mvc.perform(get(BASE + "/por-codigo?codigo=" + codigo).with(dona())).andExpect(status().isOk())
                .andExpect(jsonPath("$.fotoPrincipal.hash").value(hash));
        // O produto sincronizável leva o hash da foto principal (o vendedor baixa só se o hash mudar).
        assertThat(jdbc.queryForObject("SELECT foto_principal_hash FROM corona.produtos WHERE id = ?", String.class, produto))
                .isEqualTo(hash);
    }

    private String produtoNome(UUID produto) {
        return jdbc.queryForObject("SELECT right(nome, 8) FROM corona.produtos WHERE id = ?", String.class, produto);
    }

    @Test
    void fotoGuardadaComoArquivoComNomePeloConteudoNuncaNoBanco() throws Exception {
        UUID produto = produto();
        String hash = ler(enviarFoto(produto, "../../Meu Anel Lindo.JPG", "image/jpeg",
                ImagensDeTeste.jpeg(700, 500, 20), dona()).andExpect(status().isCreated())).get("hash").asString();

        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'corona' AND table_name = 'produtos_fotos' AND data_type = 'bytea'""")).isEmpty();
        Map<String, Object> linha = jdbc.queryForMap(
                "SELECT chave_miniatura, chave_media, chave_grande, tamanho_bytes FROM corona.produtos_fotos WHERE arquivo_hash = ?", hash);
        for (String coluna : List.of("chave_miniatura", "chave_media", "chave_grande")) {
            String chave = (String) linha.get(coluna);
            assertThat(chave).doesNotContainIgnoringCase("anel").doesNotContain("..").doesNotContainIgnoringCase("lindo");
            assertThat(Files.exists(PASTA_DE_ARQUIVOS.resolve(chave))).as(coluna).isTrue();
            assertThat(Files.readAllBytes(PASTA_DE_ARQUIVOS.resolve(chave))[0] & 0xFF).isEqualTo(0xFF); // JPEG recodificado
        }
        assertThat(((Number) linha.get("tamanho_bytes")).intValue()).isPositive();
    }

    @Test
    void valeOConteudoENaoONomeNemOTipoInformado() throws Exception {
        UUID produto = produto();
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
        // PDF com nome e tipo de foto: recusado.
        enviarFoto(produto, "bonita.jpg", "image/jpeg", pdf, dona()).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.codigo").value("FOTO_TIPO_NAO_ACEITO"))
                .andExpect(jsonPath("$.detail").value("Esta não é uma foto que o sistema aceite. Envie uma foto nos formatos JPG, PNG ou WebP."));
        // Programa renomeado.
        enviarFoto(produto, "a.png", "image/png", "MZ\u0090\u0000programa".getBytes(StandardCharsets.ISO_8859_1), dona())
                .andExpect(status().isUnsupportedMediaType());
        // Foto de verdade com nome e tipo errados: vale.
        enviarFoto(produto, "nota.pdf", "application/pdf", ImagensDeTeste.png(ImagensDeTeste.imagem(300, 200)), dona())
                .andExpect(status().isCreated());
        // Vazio.
        enviarFoto(produto, "a.jpg", "image/jpeg", new byte[0], dona()).andExpect(status().isBadRequest());
        // Danificada.
        byte[] danificada = new byte[3000];
        danificada[0] = (byte) 0xFF;
        danificada[1] = (byte) 0xD8;
        danificada[2] = (byte) 0xFF;
        for (int i = 3; i < danificada.length; i++) {
            danificada[i] = (byte) (i * 17);
        }
        enviarFoto(produto, "a.jpg", "image/jpeg", danificada, dona()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FOTO_NAO_LIDA"));
        assertThat(fotosDe(produto)).hasSize(1);
    }

    @Test
    void imagemBombaEArquivoGrandeDemaisSaoRecusados() throws Exception {
        UUID produto = produto();
        enviarFoto(produto, "bomba.png", "image/png", ImagensDeTeste.pngQueDeclaraTamanhoEnorme(100_000, 100_000), dona())
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.codigo").value("FOTO_GRANDE_DEMAIS_EM_PONTOS"));
        byte[] grande = new byte[10 * 1024 * 1024 + 1];
        grande[0] = (byte) 0xFF;
        grande[1] = (byte) 0xD8;
        grande[2] = (byte) 0xFF;
        enviarFoto(produto, "a.jpg", "image/jpeg", grande, dona()).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.codigo").value("ARQUIVO_GRANDE_DEMAIS"));
        assertThat(fotosDe(produto)).isEmpty();
    }

    @Test
    void produtoQueNaoExisteDa404() throws Exception {
        enviarFoto(UUID.randomUUID(), "a.jpg", "image/jpeg", ImagensDeTeste.jpeg(100, 100, 1), dona()).andExpect(status().isNotFound());
    }

    @Test
    void mesmaFotoDuasVezesNaMesmaPecaNaoDuplica() throws Exception {
        UUID produto = produto();
        byte[] jpeg = ImagensDeTeste.jpeg(800, 600, 31);
        JsonNode primeira = ler(enviarFoto(produto, "a.jpg", "image/jpeg", jpeg, dona()).andExpect(status().isCreated()));
        JsonNode segunda = ler(enviarFoto(produto, "b.jpg", "image/jpeg", jpeg, dona()).andExpect(status().isOk()));
        assertThat(segunda.get("id").asString()).isEqualTo(primeira.get("id").asString());
        assertThat(fotosDe(produto)).hasSize(1);
        // Em outra peça a mesma foto é permitida e reaproveita os arquivos.
        UUID outro = produto();
        enviarFoto(outro, "a.jpg", "image/jpeg", jpeg, dona()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT chave_grande) FROM corona.produtos_fotos WHERE arquivo_hash = ?",
                Integer.class, primeira.get("hash").asString())).isEqualTo(1);
    }

    @Test
    void fotoNovaSubstituiAAnteriorEUmaSoPorPeca() throws Exception {
        UUID produto = produto();
        JsonNode a = enviarFotoBoa(produto, 41);
        assertThat(fotosDe(produto)).hasSize(1);
        JsonNode b = ler(enviarFoto(produto, "b.jpg", "image/jpeg", ImagensDeTeste.jpeg(300, 300, 42), dona())
                .andExpect(status().isCreated()));
        assertThat(b.get("hash").asString()).isNotEqualTo(a.get("hash").asString());
        JsonNode lista = fotosDe(produto);
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("id").asString()).isEqualTo(b.get("id").asString());
        // A antiga ganhou exclusão lógica (nunca DELETE) e a peça leva o hash da nova.
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM corona.produtos_fotos WHERE id = ?", Boolean.class,
                UUID.fromString(a.get("id").asString()))).isTrue();
        mvc.perform(get(BASE + "/" + produto).with(dona())).andExpect(jsonPath("$.fotoPrincipal.hash").value(b.get("hash").asString()));
        assertThat(jdbc.queryForObject("SELECT foto_principal_hash FROM corona.produtos WHERE id = ?", String.class, produto))
                .isEqualTo(b.get("hash").asString());
        // A foto antiga não baixa mais; a nova baixa.
        mvc.perform(get(BASE + "/fotos/" + a.get("hash").asString() + "/miniatura").with(dona())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/fotos/" + b.get("hash").asString() + "/miniatura").with(dona())).andExpect(status().isOk());
        // As rotas de foto principal e de ordem deixaram de existir.
        json(post(BASE + "/" + produto + "/fotos/" + b.get("id").asString() + "/principal"), null).andExpect(status().is4xxClientError());
        json(put(BASE + "/" + produto + "/fotos/ordem"), mapa("ids", List.of(b.get("id").asString()))).andExpect(status().is4xxClientError());
    }

    @Test
    void obancoRecusaDuasFotosAtivasNaMesmaPeca() throws Exception {
        UUID produto = produto();
        JsonNode a = enviarFotoBoa(produto, 51);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.produtos_fotos (id, produto_id, arquivo_hash, largura, altura, tamanho_bytes,
                    chave_miniatura, chave_media, chave_grande)
                VALUES (?, ?, repeat('a', 64), 10, 10, 10, 'x', 'y', 'z')""", UUID.randomUUID(), produto))
                .hasMessageContaining("produtos_fotos_uma_ativa");
        assertThat(fotosDe(produto)).hasSize(1);
        assertThat(a.get("hash").asString()).isNotBlank();
    }

    @Test
    void enviosEmParaleloDeixamSempreUmaSoFotoAtiva() throws Exception {
        UUID produto = produto();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tarefas = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int semente = 500 + i;
                tarefas.add(() -> enviarFoto(produto, "a.jpg", "image/jpeg", ImagensDeTeste.jpeg(400, 300, semente), dona())
                        .andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(tarefas)) {
                assertThat(f.get()).isIn(200, 201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM corona.produtos_fotos WHERE produto_id = ? AND deleted_at IS NULL", Integer.class, produto)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT foto_principal_hash FROM corona.produtos WHERE id = ?", String.class, produto))
                .isEqualTo(jdbc.queryForObject("SELECT arquivo_hash FROM corona.produtos_fotos WHERE produto_id = ? AND deleted_at IS NULL",
                        String.class, produto));
    }

    // --- exclusão e limpeza dos arquivos ---

    @Test
    void excluirDeixaAPecaSemFotoENuncaEErro() throws Exception {
        UUID produto = produto();
        JsonNode a = enviarFotoBoa(produto, 61);
        json(delete(BASE + "/" + produto + "/fotos/" + a.get("id").asString()), null).andExpect(status().isNoContent());
        assertThat(fotosDe(produto)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM corona.produtos_fotos WHERE id = ?", Boolean.class,
                UUID.fromString(a.get("id").asString()))).isTrue();
        mvc.perform(get(BASE + "/" + produto).with(dona())).andExpect(status().isOk()).andExpect(jsonPath("$.fotoPrincipal").value((Object) null));
        assertThat(jdbc.queryForObject("SELECT foto_principal_hash FROM corona.produtos WHERE id = ?", String.class, produto)).isNull();
        json(delete(BASE + "/" + produto + "/fotos/" + a.get("id").asString()), null).andExpect(status().isNotFound());
        // Foto de outra peça não pode ser mexida por esta rota.
        UUID outro = produto();
        JsonNode b = enviarFotoBoa(outro, 62);
        json(delete(BASE + "/" + produto + "/fotos/" + b.get("id").asString()), null).andExpect(status().isNotFound());
    }

    @Test
    void limpezaTiraDoDiscoOsArquivosDaFotoTrocadaSoDepoisDeUmaSemanaESoSeNinguemMaisUsa() throws Exception {
        UUID produto = produto();
        JsonNode antiga = enviarFotoBoa(produto, 71);
        // A mesma imagem em outra peça reaproveita os arquivos: eles não podem sair do disco.
        UUID outra = produto();
        enviarFotoBoa(outra, 71);
        UUID idAntiga = UUID.fromString(antiga.get("id").asString());
        // Troca a foto da primeira peça.
        enviarFotoBoa(produto, 72);
        String chave = jdbc.queryForObject("SELECT chave_grande FROM corona.produtos_fotos WHERE id = ?", String.class, idAntiga);
        assertThat(armazenamento.existe(chave)).isTrue();
        // Recém trocada: a limpeza não mexe.
        limpeza.limpar();
        assertThat(armazenamento.existe(chave)).isTrue();
        // Passada a carência, mas a outra peça ainda usa os mesmos arquivos: ficam.
        jdbc.update("UPDATE corona.produtos_fotos SET deleted_at = now() - interval '8 days' WHERE id = ?", idAntiga);
        limpeza.limpar();
        assertThat(armazenamento.existe(chave)).isTrue();
        // Troca a foto da outra peça também: agora ninguém usa e, passada a carência, o arquivo sai.
        UUID idDaOutra = jdbc.queryForObject("SELECT id FROM corona.produtos_fotos WHERE produto_id = ? AND deleted_at IS NULL",
                UUID.class, outra);
        enviarFoto(outra, "c.jpg", "image/jpeg", ImagensDeTeste.jpeg(800, 600, 73), dona()).andExpect(status().isCreated());
        jdbc.update("UPDATE corona.produtos_fotos SET deleted_at = now() - interval '8 days', arquivos_removidos_em = NULL WHERE id = ?", idDaOutra);
        jdbc.update("UPDATE corona.produtos_fotos SET arquivos_removidos_em = NULL WHERE id = ?", idAntiga);
        assertThat(limpeza.limpar()).isGreaterThanOrEqualTo(3);
        assertThat(armazenamento.existe(chave)).isFalse();
        assertThat(jdbc.queryForObject("SELECT arquivos_removidos_em IS NOT NULL FROM corona.produtos_fotos WHERE id = ?", Boolean.class,
                idAntiga)).isTrue();
        // A foto atual de cada peça continua lá, e rodar de novo não apaga nada nem dá erro.
        assertThat(limpeza.limpar()).isZero();
        mvc.perform(get(BASE + "/" + produto).with(dona())).andExpect(jsonPath("$.fotoPrincipal.hash").isNotEmpty());
        // Enviar de novo a imagem apagada grava arquivos novos (nunca reaproveita os que saíram do disco).
        enviarFotoBoa(produto, 71);
        String novaChave = jdbc.queryForObject("SELECT chave_grande FROM corona.produtos_fotos WHERE produto_id = ? AND deleted_at IS NULL",
                String.class, produto);
        assertThat(armazenamento.existe(novaChave)).isTrue();
    }

    @Test
    void mudancaNaFotoAparecesNoSyncDoProduto() throws Exception {
        UUID produto = produto();
        long antes = jdbc.queryForObject("SELECT sync_seq FROM corona.produtos WHERE id = ?", Long.class, produto);
        enviarFotoBoa(produto, 61);
        long depois = jdbc.queryForObject("SELECT sync_seq FROM corona.produtos WHERE id = ?", Long.class, produto);
        assertThat(depois).isGreaterThan(antes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.produtos_fotos WHERE produto_id = ? AND sync_seq > 0",
                Integer.class, produto)).isEqualTo(1);
    }

    // --- download ---

    @Test
    void downloadDosTresTamanhosComCacheEtagENosniff() throws Exception {
        UUID produto = produto();
        String hash = enviarFotoBoa(produto, 71).get("hash").asString();
        for (String tamanho : List.of("miniatura", "media", "grande")) {
            var r = mvc.perform(get(BASE + "/fotos/" + hash + "/" + tamanho).with(dona())).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().exists(HttpHeaders.ETAG)).andReturn().getResponse();
            assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).contains("private").contains("immutable").contains("max-age=");
            assertThat(r.getHeader(HttpHeaders.ETAG)).contains(hash);
            assertThat(r.getContentAsByteArray()[0] & 0xFF).isEqualTo(0xFF);
            // Voltou com o mesmo ETag: nada para baixar de novo.
            mvc.perform(get(BASE + "/fotos/" + hash + "/" + tamanho).with(dona())
                    .header(HttpHeaders.IF_NONE_MATCH, r.getHeader(HttpHeaders.ETAG))).andExpect(status().isNotModified());
        }
        long grande = mvc.perform(get(BASE + "/fotos/" + hash + "/grande").with(dona())).andReturn().getResponse().getContentLength();
        long mini = mvc.perform(get(BASE + "/fotos/" + hash + "/miniatura").with(dona())).andReturn().getResponse().getContentLength();
        assertThat(mini).isLessThan(grande);

        mvc.perform(get(BASE + "/fotos/" + hash + "/enorme").with(dona())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/fotos/" + "0".repeat(64) + "/media").with(dona())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/fotos/nao-e-hash/media").with(dona())).andExpect(status().isNotFound());
    }

    @Test
    void fotoExcluidaNaoBaixaMais() throws Exception {
        UUID produto = produto();
        JsonNode foto = enviarFotoBoa(produto, 81);
        mvc.perform(delete(BASE + "/" + produto + "/fotos/" + foto.get("id").asString()).with(dona())).andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/fotos/" + foto.get("hash").asString() + "/media").with(dona())).andExpect(status().isNotFound());
    }

    // --- relatório de produtos sem foto ---

    @Test
    void relatorioDeProdutosSemFoto() throws Exception {
        UUID categoria = categoria();
        UUID outraCategoria = categoria();
        UUID fornecedor = fornecedor();
        String marca = "Brinco" + UUID.randomUUID().toString().substring(0, 8);
        UUID semFoto = UUID.fromString(produto(categoria, fornecedor, marca + " sem", unico("SF")).get("id").asString());
        UUID comFoto = UUID.fromString(produto(categoria, fornecedor, marca + " com", unico("CF")).get("id").asString());
        UUID outraCat = UUID.fromString(produto(outraCategoria, null, marca + " outra", unico("OC")).get("id").asString());
        UUID excluido = UUID.fromString(produto(categoria, fornecedor, marca + " excluido", unico("EX")).get("id").asString());
        UUID inativo = UUID.fromString(produto(categoria, fornecedor, marca + " inativo", unico("IN")).get("id").asString());
        enviarFotoBoa(comFoto, 91);
        mvc.perform(delete(BASE + "/" + excluido).with(dona())).andExpect(status().isNoContent());
        mvc.perform(post(BASE + "/" + inativo + "/inativar").with(dona())).andExpect(status().isOk());

        String busca = "?busca=" + marca;
        JsonNode r = ler(mvc.perform(get(BASE + "/sem-foto" + busca).with(dona())).andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        r.get("itens").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).containsExactlyInAnyOrder(semFoto.toString(), outraCat.toString());
        assertThat(r.get("itens").get(0).has("nome")).isTrue();
        assertThat(r.get("itens").get(0).has("codigoPrincipal")).isTrue();

        // Filtros: categoria e fornecedor.
        r = ler(mvc.perform(get(BASE + "/sem-foto" + busca + "&categoriaId=" + categoria).with(dona())).andExpect(status().isOk()));
        assertThat(r.get("itens")).hasSize(1);
        assertThat(r.get("itens").get(0).get("id").asString()).isEqualTo(semFoto.toString());
        r = ler(mvc.perform(get(BASE + "/sem-foto" + busca + "&fornecedorId=" + fornecedor).with(dona())).andExpect(status().isOk()));
        assertThat(r.get("itens")).hasSize(1);
        // Inativos só aparecem se pedir.
        r = ler(mvc.perform(get(BASE + "/sem-foto" + busca + "&ativo=false").with(dona())).andExpect(status().isOk()));
        assertThat(r.get("itens")).hasSize(1);
        assertThat(r.get("itens").get(0).get("id").asString()).isEqualTo(inativo.toString());
        // Paginação.
        r = ler(mvc.perform(get(BASE + "/sem-foto" + busca + "&tamanho=1&pagina=1").with(dona())).andExpect(status().isOk()));
        assertThat(r.get("itens")).hasSize(1);
        assertThat(r.get("total").asInt()).isEqualTo(2);
        // Ao enviar a foto, sai do relatório.
        enviarFotoBoa(semFoto, 92);
        r = ler(mvc.perform(get(BASE + "/sem-foto" + busca).with(dona())).andExpect(status().isOk()));
        assertThat(r.get("itens")).hasSize(1);
    }

    // --- importação em massa ---

    @Test
    void previaNaoGravaNadaEMostraAssociadasSemProdutoEAmbiguas() throws Exception {
        UUID categoria = categoria();
        String m = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        JsonNode a = produto(categoria, null, unico("Anel A"), "A-" + m + "-1");
        JsonNode b = produto(categoria, null, unico("Anel B"), "A-" + m + "-2");
        JsonNode c = produto(categoria, null, unico("Anel C"), "A-" + m + "-3");
        int antes = totalDeFotos();
        int semFotoAntes = jdbc.queryForObject("""
                SELECT count(*) FROM corona.produtos p WHERE p.deleted_at IS NULL AND p.ativo
                  AND NOT EXISTS (SELECT 1 FROM corona.produtos_fotos f WHERE f.produto_id = p.id AND f.deleted_at IS NULL)""",
                Integer.class);

        Map<String, Object> pedido = mapa("nomes", List.of(
                "a-" + m + "-1.JPG",            // maiúsculas e minúsculas não importam
                "  A-" + m + "-2.jpeg ",       // espaços nas pontas
                "pasta/sub\\A-" + m + "-3.webp", // caminho do navegador é ignorado
                "A-" + m + "-3.png",           // mesmo código do anterior: ambíguos os dois
                "NAOEXISTE-" + m + ".jpg",
                "leiame.txt"));
        JsonNode r = ler(json(post(BASE + "/fotos/importacao/previa"), pedido).andExpect(status().isOk()));

        List<String> associadas = new ArrayList<>();
        r.get("associadas").forEach(i -> associadas.add(i.get("produtoId").asString()));
        assertThat(associadas).containsExactlyInAnyOrder(a.get("id").asString(), b.get("id").asString());
        assertThat(r.get("associadas").get(0).has("nome")).isTrue();
        assertThat(r.get("associadas").get(0).has("produtoNome")).isTrue();

        assertThat(r.get("ambiguas")).hasSize(1);
        assertThat(r.get("ambiguas").get(0).get("nomes")).hasSize(2);
        assertThat(r.get("ambiguas").get(0).get("mensagem").asString()).contains("Há mais de um arquivo");

        List<String> semProduto = new ArrayList<>();
        r.get("semProduto").forEach(i -> semProduto.add(i.get("nome").asString()));
        assertThat(semProduto).containsExactlyInAnyOrder("NAOEXISTE-" + m + ".jpg", "leiame.txt");
        assertThat(r.get("semProduto").get(0).get("mensagem").asString()).startsWith("Nenhuma peça tem o código");

        // A e B ganhariam foto e saem da conta; C está ambíguo e continua sem foto.
        assertThat(r.get("produtosSemFoto").get("total").asInt()).isEqualTo(semFotoAntes - 2);
        assertThat(r.get("produtosSemFoto").get("itens").size()).isLessThanOrEqualTo(200);
        assertThat(c.get("id").asString()).isNotEmpty();

        assertThat(totalDeFotos()).isEqualTo(antes); // nada gravado
        // Sem lista: erro simples.
        json(post(BASE + "/fotos/importacao/previa"), mapa("nomes", List.of())).andExpect(status().isBadRequest());
    }

    @Test
    void confirmarGravaEUmaFalhaNaoDerrubaAsOutras() throws Exception {
        UUID categoria = categoria();
        String m = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        JsonNode p1 = produto(categoria, null, unico("Colar 1"), "C-" + m + "-1");
        JsonNode p2 = produto(categoria, null, unico("Colar 2"), "C-" + m + "-2");
        JsonNode p3 = produto(categoria, null, unico("Colar 3"), "C-" + m + "-3");
        JsonNode p4 = produto(categoria, null, unico("Colar 4"), "C-" + m + "-4");
        JsonNode p5 = produto(categoria, null, unico("Colar 5"), "C-" + m + "-5");
        byte[] danificada = new byte[1500];
        danificada[0] = (byte) 0xFF;
        danificada[1] = (byte) 0xD8;
        danificada[2] = (byte) 0xFF;
        for (int i = 3; i < danificada.length; i++) {
            danificada[i] = (byte) (i * 13);
        }

        JsonNode r = ler(importar("confirmar", dona(),
                arquivo("c-" + m + "-1.JPG", ImagensDeTeste.jpeg(900, 600, 201)),
                arquivo("C-" + m + "-2.png", ImagensDeTeste.png(ImagensDeTeste.imagem(500, 500, 202))),
                arquivo("C-" + m + "-3.jpg", danificada),
                arquivo("C-" + m + "-4.jpg", "%PDF-1.4 isto nao e foto".getBytes(StandardCharsets.US_ASCII)),
                arquivo("SEM-PRODUTO-" + m + ".jpg", ImagensDeTeste.jpeg(100, 100, 203)),
                arquivo("C-" + m + "-5.jpg", ImagensDeTeste.jpeg(100, 100, 204)),
                arquivo("c-" + m + "-5.png", ImagensDeTeste.png(ImagensDeTeste.imagem(100, 100, 205)))).andExpect(status().isOk()));

        Map<String, JsonNode> porNome = new LinkedHashMap<>();
        r.get("resultados").forEach(i -> porNome.put(i.get("nome").asString(), i));
        assertThat(r.get("resultados")).hasSize(7);
        assertThat(porNome.get("c-" + m + "-1.JPG").get("situacao").asString()).isEqualTo("GRAVADA");
        assertThat(porNome.get("C-" + m + "-2.png").get("situacao").asString()).isEqualTo("GRAVADA");
        assertThat(porNome.get("C-" + m + "-3.jpg").get("situacao").asString()).isEqualTo("RECUSADA");
        assertThat(porNome.get("C-" + m + "-3.jpg").get("mensagem").asString()).startsWith("Não foi possível abrir esta foto.");
        assertThat(porNome.get("C-" + m + "-4.jpg").get("situacao").asString()).isEqualTo("RECUSADA");
        assertThat(porNome.get("C-" + m + "-4.jpg").get("mensagem").asString()).contains("JPG, PNG ou WebP");
        assertThat(porNome.get("SEM-PRODUTO-" + m + ".jpg").get("situacao").asString()).isEqualTo("SEM_PRODUTO");
        assertThat(porNome.get("C-" + m + "-5.jpg").get("situacao").asString()).isEqualTo("AMBIGUA");
        assertThat(porNome.get("C-" + m + "-5.jpg").get("mensagem").asString()).contains("mais de um arquivo");
        assertThat(r.get("gravadas").asInt()).isEqualTo(2);
        assertThat(r.get("jaExistiam").asInt()).isEqualTo(0);
        assertThat(r.get("naoGravadas").asInt()).isEqualTo(5);

        assertThat(fotosDe(UUID.fromString(p1.get("id").asString()))).hasSize(1);
        assertThat(fotosDe(UUID.fromString(p2.get("id").asString()))).hasSize(1);
        assertThat(fotosDe(UUID.fromString(p3.get("id").asString()))).isEmpty();
        assertThat(fotosDe(UUID.fromString(p4.get("id").asString()))).isEmpty();
        assertThat(fotosDe(UUID.fromString(p5.get("id").asString()))).isEmpty();
        mvc.perform(get(BASE + "/" + p1.get("id").asString()).with(dona())).andExpect(jsonPath("$.fotoPrincipal.hash").isNotEmpty());

        // Repetir a mesma importação não duplica nada: idempotente pelo conteúdo.
        JsonNode de_novo = ler(importar("confirmar", dona(),
                arquivo("c-" + m + "-1.JPG", ImagensDeTeste.jpeg(900, 600, 201)),
                arquivo("C-" + m + "-2.png", ImagensDeTeste.png(ImagensDeTeste.imagem(500, 500, 202)))).andExpect(status().isOk()));
        assertThat(de_novo.get("gravadas").asInt()).isEqualTo(0);
        assertThat(de_novo.get("jaExistiam").asInt()).isEqualTo(2);
        assertThat(de_novo.get("resultados").get(0).get("situacao").asString()).isEqualTo("JA_EXISTIA");
        assertThat(fotosDe(UUID.fromString(p1.get("id").asString()))).hasSize(1);
    }

    @Test
    void importacaoSubstituiAFotoQueAPecaJaTinha() throws Exception {
        importar("confirmar", dona()).andExpect(status().isBadRequest());
        UUID categoria = categoria();
        String m = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        JsonNode p = produto(categoria, null, unico("Com foto"), "CF-" + m);
        UUID id = UUID.fromString(p.get("id").asString());
        JsonNode antiga = enviarFotoBoa(id, 300);
        JsonNode r = ler(importar("confirmar", dona(), arquivo("cf-" + m + ".jpg", ImagensDeTeste.jpeg(200, 200, 399)))
                .andExpect(status().isOk()));
        assertThat(r.get("resultados").get(0).get("situacao").asString()).isEqualTo("GRAVADA");
        JsonNode lista = fotosDe(id);
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("hash").asString()).isNotEqualTo(antiga.get("hash").asString());
    }

    @Test
    void arquivoEmPastaTemporariaNaoSobraDepoisDoEnvioRecusado() throws Exception {
        UUID produto = produto();
        long antes;
        try (var s = Files.walk(Path.of(PASTA_DE_ARQUIVOS.toString()))) {
            antes = s.filter(Files::isRegularFile).count();
        }
        enviarFoto(produto, "a.jpg", "image/jpeg", "%PDF-1.4 x".getBytes(StandardCharsets.US_ASCII), dona()).andExpect(status().isUnsupportedMediaType());
        long depois;
        try (var s = Files.walk(Path.of(PASTA_DE_ARQUIVOS.toString()))) {
            depois = s.filter(Files::isRegularFile).count();
        }
        assertThat(depois).isEqualTo(antes); // recusado antes de gravar qualquer arquivo
    }
}
