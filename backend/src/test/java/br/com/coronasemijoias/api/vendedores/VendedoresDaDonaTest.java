package br.com.coronasemijoias.api.vendedores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.arquivos.ArmazenamentoDeArquivos;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cadastro completo do vendedor: permissão, regras do limite e da comissão, abas, referências e documentos (tarefa 1.3). */
class VendedoresDaDonaTest extends TesteDeIntegracao {

    private static final String BASE = "/api/dona/vendedores";
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ArmazenamentoDeArquivos armazenamento;

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

    private JsonNode ler(ResultActions resposta) throws Exception {
        return json.readTree(resposta.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode criarVendedor(Object... campos) throws Exception {
        Map<String, Object> corpo = mapa("nome", unico("Vendedora"));
        Map<String, Object> extras = mapa(campos);
        corpo.putAll(extras);
        if (!extras.containsKey("limiteConsignacao") && !extras.containsKey("semLimite")) {
            corpo.put("semLimite", true); // o limite é obrigatório (V026): "Sem limite" de propósito
        }
        return ler(enviar("POST", BASE, dona(), corpo).andExpect(status().isCreated()));
    }

    private UUID criarPraca() throws Exception {
        String resposta = enviar("POST", "/api/dona/pracas", dona(), mapa("nome", unico("Praça"))).andReturn()
                .getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private UUID criarRestricao() throws Exception {
        String resposta = enviar("POST", "/api/dona/restricoes", dona(),
                mapa("codigo", unico("R"), "descricao", "Atraso", "bloqueiaRemessa", true)).andReturn()
                .getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private ResultActions enviarArquivo(String vendedorId, String descricao, String nome, String tipo, byte[] bytes,
            RequestPostProcessor quem) throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", nome, tipo, bytes);
        return mvc.perform(multipart(BASE + "/" + vendedorId + "/documentos").file(arquivo)
                .param("descricao", descricao).with(quem));
    }

    // --- permissão ---

    @Test
    void vendedorRecebe403EmTodasAsRotas() throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        enviar("GET", BASE, vendedor, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
        enviar("POST", BASE, vendedor, mapa("nome", "x")).andExpect(status().isForbidden());
        for (String parte : new String[] {"", "/endereco", "/contato", "/trabalho", "/conjuge", "/mei", "/observacoes",
                "/referencias", "/documentos"}) {
            enviar("GET", BASE + "/" + id + parte, vendedor, null).andExpect(status().isForbidden());
        }
        for (String parte : new String[] {"", "/endereco", "/contato", "/trabalho", "/conjuge", "/mei",
                "/observacoes"}) {
            enviar("PUT", BASE + "/" + id + parte, vendedor, mapa()).andExpect(status().isForbidden());
        }
        enviar("DELETE", BASE + "/" + id, vendedor, null).andExpect(status().isForbidden());
        enviar("POST", BASE + "/" + id + "/inativar", vendedor, null).andExpect(status().isForbidden());
        enviar("POST", BASE + "/" + id + "/reativar", vendedor, null).andExpect(status().isForbidden());
        enviar("POST", BASE + "/" + id + "/referencias", vendedor, mapa("nome", "x")).andExpect(status().isForbidden());
        enviarArquivo(id.toString(), "RG", "rg.pdf", "application/pdf", PDF, vendedor).andExpect(status().isForbidden());
        enviar("GET", BASE + "/" + id + "/documentos/" + UUID.randomUUID() + "/arquivo", vendedor, null)
                .andExpect(status().isForbidden());
        enviar("DELETE", BASE + "/" + id + "/documentos/" + UUID.randomUUID(), vendedor, null)
                .andExpect(status().isForbidden());
    }

    @Test
    void semTokenRecebe401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    // --- cadastro principal ---

    @Test
    void criaGuardaOQueFoiPreenchidoEDaOCodigoSequencial() throws Exception {
        UUID praca = criarPraca();
        UUID restricao = criarRestricao();
        JsonNode primeiro = criarVendedor("cpfCnpj", "529.982.247-25", "limiteConsignacao", "1500.50",
                "comissaoPercentual", "12.50", "pracaId", praca.toString(), "restricaoId", restricao.toString(),
                "sexo", "FEMININO", "estrangeiro", true, "rgIe", "1234567", "rgUf", "MS",
                "telefone1", "67 3333-0000", "whatsapp", "67 99999-0000", "email", "maria@exemplo.com",
                "dataNascimento", "1985-03-20", "naturalidade", "Ponta Porã", "escolaridade", "Médio");
        assertThat(primeiro.get("version").asInt()).isEqualTo(1);
        assertThat(primeiro.get("cpfCnpj").asString()).isEqualTo("52998224725");
        assertThat(primeiro.get("limiteConsignacao").asString()).isEqualTo("1500.50");
        assertThat(primeiro.get("comissaoPercentual").asString()).isEqualTo("12.50");
        assertThat(primeiro.get("pracaId").asString()).isEqualTo(praca.toString());
        assertThat(primeiro.get("restricaoId").asString()).isEqualTo(restricao.toString());
        assertThat(primeiro.get("ativo").asBoolean()).isTrue();
        assertThat(primeiro.get("estrangeiro").asBoolean()).isTrue();
        assertThat(primeiro.get("cadastradoPor").asString()).isEqualTo(usuarios.dona().id().toString());
        assertThat(primeiro.get("dataCadastro").asString()).isNotBlank();

        JsonNode segundo = criarVendedor();
        assertThat(segundo.get("codigo").asInt()).isGreaterThan(primeiro.get("codigo").asInt());
        // "Sem limite" marcado: o limite fica vazio (nunca zero); sem comissão informada: zero, nunca vazio.
        assertThat(segundo.get("limiteConsignacao").isNull()).isTrue();
        assertThat(segundo.get("semLimite").asBoolean()).isTrue();
        assertThat(segundo.get("comissaoPercentual").asString()).isEqualTo("0.00");
    }

    // --- limite de consignação obrigatório (V026) ---

    @Test
    void limiteDeConsignacaoEObrigatorioOuSemLimiteMarcadoDePropositoNuncaOsDoisENuncaZeroComoSemLimite() throws Exception {
        enviar("POST", BASE, dona(), mapa("nome", unico("V"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("limiteConsignacao"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("Informe o limite de consignação em reais ou marque \"Sem limite\"."));
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "limiteConsignacao", "100.00", "semLimite", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("limiteConsignacao"));
        JsonNode zero = criarVendedor("limiteConsignacao", "0.00");
        assertThat(zero.get("limiteConsignacao").asString()).isEqualTo("0.00");
        assertThat(zero.get("semLimite").asBoolean()).isFalse();
        JsonNode sem = criarVendedor("semLimite", true);
        assertThat(sem.get("limiteConsignacao").isNull()).isTrue();
        assertThat(sem.get("semLimite").asBoolean()).isTrue();
        // Editar também exige: tirar o limite sem marcar "Sem limite" é recusado.
        String id = sem.get("id").asString();
        enviar("PUT", BASE + "/" + id, dona(), mapa("nome", unico("V"), "version", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("limiteConsignacao"));
        enviar("PUT", BASE + "/" + id, dona(), mapa("nome", unico("V"), "limiteConsignacao", "300.00", "version", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.semLimite").value(false))
                .andExpect(jsonPath("$.limiteConsignacao").value("300.00"));
    }

    @Test
    void obancoRecusaSemLimiteJuntoComUmValorDeLimite() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO corona.vendedores (id, nome, limite_consignacao, limite_consignacao_ilimitado) "
                + "VALUES (?, 'x', 10, true)", id)).hasMessageContaining("vendedores_limite_ou_sem_limite");
    }

    @Test
    void limiteEComissaoForaDaFaixaSaoRecusadosComOCampo() throws Exception {
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "limiteConsignacao", "-0.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("limiteConsignacao"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("O limite não pode ser menor que zero."));
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "comissaoPercentual", "100.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("comissaoPercentual"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("A comissão deve ficar entre 0 e 100."));
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "comissaoPercentual", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("comissaoPercentual"));
        // Dinheiro só como texto com ponto (ADR-0002): número JSON e três casas são recusados.
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "limiteConsignacao", 100))
                .andExpect(status().isBadRequest());
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "limiteConsignacao", "10.005"))
                .andExpect(status().isBadRequest());
        // Os limites das faixas valem.
        assertThat(criarVendedor("limiteConsignacao", "0.00", "comissaoPercentual", "100").get("comissaoPercentual")
                .asString()).isEqualTo("100.00");
        assertThat(criarVendedor("comissaoPercentual", "0").get("comissaoPercentual").asString()).isEqualTo("0.00");
    }

    @Test
    void oBancoTambemRecusaLimiteNegativoEComissaoForaDaFaixa() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO corona.vendedores (id, nome, limite_consignacao) VALUES (?, 'x', -1)", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO corona.vendedores (id, nome, comissao_percentual) VALUES (?, 'x', 100.01)", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void praçaERestricaoPrecisamExistir() throws Exception {
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "semLimite", true, "pracaId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("pracaId"))
                .andExpect(jsonPath("$.campos[0].mensagem")
                        .value("Esta opção não existe mais. Atualize a tela e escolha de novo."));
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "semLimite", true, "restricaoId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("restricaoId"));
        // Praça excluída também não vale.
        UUID praca = criarPraca();
        enviar("DELETE", "/api/dona/pracas/" + praca, dona(), null).andExpect(status().isNoContent());
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "semLimite", true, "pracaId", praca.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cpfOuCnpjErradoEhRecusadoERepetidoEhConflitoMasLiberaDepoisDeExcluir() throws Exception {
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "semLimite", true, "cpfCnpj", "111.111.111-11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("cpfCnpj"));
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "semLimite", true, "cpfCnpj", "1234"))
                .andExpect(status().isBadRequest());
        // CNPJ válido (14 caracteres) também serve para quem é empresa.
        JsonNode empresa = criarVendedor("cpfCnpj", "11.222.333/0001-81");
        assertThat(empresa.get("cpfCnpj").asString()).isEqualTo("11222333000181");

        JsonNode criado = criarVendedor("cpfCnpj", "390.533.447-05");
        enviar("POST", BASE, dona(), mapa("nome", unico("V"), "semLimite", true, "cpfCnpj", "39053344705"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_JA_EXISTE"));
        enviar("DELETE", BASE + "/" + criado.get("id").asString(), dona(), null).andExpect(status().isNoContent());
        criarVendedor("cpfCnpj", "39053344705");
    }

    @Test
    void buscaPorNomeCodigoCpfETelefoneEFiltroDeSituacao() throws Exception {
        String nome = unico("Zuleica");
        JsonNode v = criarVendedor("nome", nome, "cpfCnpj", "168.995.350-09", "telefone1", "67 98888-1234");
        String id = v.get("id").asString();
        for (String busca : new String[] {nome.toLowerCase(), "168.995.350", "98888-1234", String.valueOf(v.get("codigo").asInt())}) {
            String corpo = enviar("GET", BASE + "?busca=" + busca, dona(), null)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(corpo).as("busca: " + busca).contains(id);
        }
        enviar("POST", BASE + "/" + id + "/inativar", dona(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
        String filtro = "&busca=" + nome;
        enviar("GET", BASE + "?situacao=ATIVOS" + filtro, dona(), null).andExpect(jsonPath("$.total").value(0));
        enviar("GET", BASE + "?situacao=INATIVOS" + filtro, dona(), null).andExpect(jsonPath("$.total").value(1));
        enviar("GET", BASE + "?situacao=TODOS" + filtro, dona(), null).andExpect(jsonPath("$.total").value(1));
        enviar("GET", BASE + "?situacao=QUALQUER", dona(), null).andExpect(status().isBadRequest());
        enviar("POST", BASE + "/" + id + "/reativar", dona(), null).andExpect(jsonPath("$.ativo").value(true));
        enviar("GET", BASE + "?situacao=ATIVOS" + filtro, dona(), null).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void editaComAVersaoQueATelaLeuERecusaVersaoVelha() throws Exception {
        JsonNode v = criarVendedor("limiteConsignacao", "100.00");
        String id = v.get("id").asString();
        Map<String, Object> novo = mapa("nome", unico("Renomeada"), "limiteConsignacao", "2000.00",
                "comissaoPercentual", "15", "version", 1);
        enviar("PUT", BASE + "/" + id, dona(), novo).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.limiteConsignacao").value("2000.00"))
                .andExpect(jsonPath("$.comissaoPercentual").value("15.00"))
                .andExpect(jsonPath("$.codigo").value(v.get("codigo").asInt()));
        enviar("PUT", BASE + "/" + id, dona(), novo).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_ALTERADO"));
        novo.remove("version");
        enviar("PUT", BASE + "/" + id, dona(), novo).andExpect(status().isBadRequest());
        novo.put("version", 2);
        novo.put("limiteConsignacao", "-5.00");
        enviar("PUT", BASE + "/" + id, dona(), novo).andExpect(status().isBadRequest());
    }

    @Test
    void excluirEhLogicoEAlinhaSomeDasListas() throws Exception {
        String id = criarVendedor().get("id").asString();
        enviar("DELETE", BASE + "/" + id, dona(), null).andExpect(status().isNoContent());
        enviar("GET", BASE + "/" + id, dona(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_NAO_ENCONTRADO"));
        enviar("DELETE", BASE + "/" + id, dona(), null).andExpect(status().isNotFound());
        enviar("POST", BASE + "/" + id + "/inativar", dona(), null).andExpect(status().isNotFound());
        enviar("GET", BASE + "/" + id + "/endereco", dona(), null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM corona.vendedores WHERE id = ?::uuid",
                Boolean.class, id)).isTrue();
    }

    // --- abas ---

    @Test
    void abaNaoGravadaVemVaziaComVersaoZeroEGravaDepoisComVersao() throws Exception {
        String id = criarVendedor().get("id").asString();
        enviar("GET", BASE + "/" + id + "/endereco", dona(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.logradouro").doesNotExist());

        Map<String, Object> endereco = mapa("logradouro", "Rua das Flores", "numero", "10", "bairro", "Centro",
                "cep", "79900-000", "cidade", "Ponta Porã", "uf", "MS", "localReferencia", "Perto da praça",
                "residencia", "Alugada", "valorResidencia", "850.00");
        enviar("PUT", BASE + "/" + id + "/endereco", dona(), endereco).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.valorResidencia").value("850.00"))
                .andExpect(jsonPath("$.logradouro").value("Rua das Flores"));
        // Gravar de novo sem a versão que a tela leu: alguém já gravou.
        enviar("PUT", BASE + "/" + id + "/endereco", dona(), endereco).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_ALTERADO"));
        endereco.put("version", 1);
        endereco.put("numero", "20");
        enviar("PUT", BASE + "/" + id + "/endereco", dona(), endereco).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        enviar("GET", BASE + "/" + id + "/endereco", dona(), null).andExpect(jsonPath("$.numero").value("20"));
        endereco.put("version", 2);
        endereco.put("valorResidencia", "-1.00");
        enviar("PUT", BASE + "/" + id + "/endereco", dona(), endereco).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("valorResidencia"));
    }

    @Test
    void todasAsAbasGravamEListam() throws Exception {
        String id = criarVendedor().get("id").asString();
        Map<String, Map<String, Object>> abas = new LinkedHashMap<>();
        abas.put("contato", mapa("nomePai", "José", "nomeMae", "Ana", "contatoNome", "Rosa",
                "contatoParentesco", "Irmã", "contatoTelefone", "67 3000-1111"));
        abas.put("trabalho", mapa("empresa", "Loja X", "funcao", "Caixa", "telefone", "67 3000-2222",
                "endereco", "Av. Brasil, 100", "dataAdmissao", "2020-01-15", "renda", "2500.00"));
        abas.put("conjuge", mapa("nome", "Paulo", "cpf", "529.982.247-25", "rg", "9876", "dataNascimento", "1983-05-01",
                "telefone", "67 3000-3333", "profissao", "Pedreiro", "empresa", "Obra Y", "renda", "3000.00"));
        abas.put("mei", mapa("cnpj", "11.222.333/0001-81", "razaoSocial", "Maria ME", "nomeFantasia", "Maria Joias",
                "dataAbertura", "2022-02-02", "atividade", "Comércio de joias"));
        abas.put("observacoes", mapa("texto", "Boa pagadora."));
        for (var aba : abas.entrySet()) {
            String caminho = BASE + "/" + id + "/" + aba.getKey();
            ResultActions gravada = enviar("PUT", caminho, dona(), aba.getValue()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));
            JsonNode lida = ler(enviar("GET", caminho, dona(), null).andExpect(status().isOk()));
            for (var campo : aba.getValue().entrySet()) {
                String esperado = campo.getValue().toString();
                if (campo.getKey().equals("cpf")) {
                    esperado = "52998224725";
                } else if (campo.getKey().equals("cnpj")) {
                    esperado = "11222333000181";
                }
                assertThat(lida.get(campo.getKey()).asString()).as(aba.getKey() + "." + campo.getKey())
                        .isEqualTo(esperado);
            }
            assertThat(gravada).isNotNull();
        }
    }

    @Test
    void cpfDoConjugeECnpjDoMeiPrecisamEstarCertos() throws Exception {
        String id = criarVendedor().get("id").asString();
        enviar("PUT", BASE + "/" + id + "/conjuge", dona(), mapa("nome", "Paulo", "cpf", "123.456.789-00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("cpf"));
        enviar("PUT", BASE + "/" + id + "/mei", dona(), mapa("cnpj", "11.222.333/0001-82"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("cnpj"));
        enviar("PUT", BASE + "/" + id + "/trabalho", dona(), mapa("renda", "-10.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("renda"));
    }

    @Test
    void abaDeVendedorQueNaoExisteDa404() throws Exception {
        enviar("PUT", BASE + "/" + UUID.randomUUID() + "/contato", dona(), mapa("nomeMae", "Ana"))
                .andExpect(status().isNotFound());
    }

    // --- referências ---

    @Test
    void referenciasCriamEditamEExcluemLogicamente() throws Exception {
        String id = criarVendedor().get("id").asString();
        String caminho = BASE + "/" + id + "/referencias";
        JsonNode ref = ler(enviar("POST", caminho, dona(), mapa("nome", "Vizinha Lúcia", "parentesco", "Vizinha",
                "telefone", "67 3000-4444")).andExpect(status().isCreated()));
        String refId = ref.get("id").asString();
        enviar("POST", caminho, dona(), mapa("nome", "  ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("nome"));
        enviar("GET", caminho, dona(), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        enviar("PUT", caminho + "/" + refId, dona(), mapa("nome", "Lúcia Souza", "version", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.nome").value("Lúcia Souza"));
        enviar("PUT", caminho + "/" + refId, dona(), mapa("nome", "Outra", "version", 1))
                .andExpect(status().isConflict());
        enviar("DELETE", caminho + "/" + refId, dona(), null).andExpect(status().isNoContent());
        enviar("GET", caminho, dona(), null).andExpect(jsonPath("$.length()").value(0));
        enviar("DELETE", caminho + "/" + refId, dona(), null).andExpect(status().isNotFound());
        // A referência é do vendedor da rota: o id dela em outro vendedor não vale.
        String outro = criarVendedor().get("id").asString();
        JsonNode daOutra = ler(enviar("POST", BASE + "/" + outro + "/referencias", dona(), mapa("nome", "Ana"))
                .andExpect(status().isCreated()));
        enviar("DELETE", caminho + "/" + daOutra.get("id").asString(), dona(), null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM corona.vendedores_referencias WHERE id = ?::uuid",
                Boolean.class, refId)).isTrue();
    }

    // --- documentos ---

    @Test
    void documentoSobeFicaNoArmazenamentoEBaixaIgual() throws Exception {
        String id = criarVendedor().get("id").asString();
        JsonNode doc = ler(enviarArquivo(id, "  RG  ", "rg frente.pdf", "application/pdf", PDF, dona())
                .andExpect(status().isCreated()));
        String docId = doc.get("id").asString();
        assertThat(doc.get("descricao").asString()).isEqualTo("RG");
        assertThat(doc.get("nomeOriginal").asString()).isEqualTo("rg frente.pdf");
        assertThat(doc.get("tipoConteudo").asString()).isEqualTo("application/pdf");
        assertThat(doc.get("tamanhoBytes").asLong()).isEqualTo(PDF.length);
        // O caminho do arquivo nunca sai da API.
        assertThat(doc.has("chaveArquivo")).isFalse();

        // O banco guarda os dados do arquivo e só a chave; o conteúdo está no armazenamento.
        String chave = jdbc.queryForObject("SELECT chave_arquivo FROM corona.vendedores_documentos WHERE id = ?::uuid",
                String.class, docId);
        assertThat(armazenamento.existe(chave)).isTrue();
        assertThat(jdbc.queryForObject("SELECT hash_sha256 FROM corona.vendedores_documentos WHERE id = ?::uuid",
                String.class, docId)).hasSize(64);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'corona' AND table_name = 'vendedores_documentos' AND data_type = 'bytea'"""))
                .isEmpty();

        byte[] baixado = mvc.perform(get(BASE + "/" + id + "/documentos/" + docId + "/arquivo").with(dona()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(baixado).isEqualTo(PDF);

        enviar("GET", BASE + "/" + id + "/documentos", dona(), null).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(docId));

        // Excluir é lógico: some da lista e não baixa mais, mas a linha e o arquivo ficam.
        enviar("DELETE", BASE + "/" + id + "/documentos/" + docId, dona(), null).andExpect(status().isNoContent());
        enviar("GET", BASE + "/" + id + "/documentos", dona(), null).andExpect(jsonPath("$.length()").value(0));
        enviar("GET", BASE + "/" + id + "/documentos/" + docId + "/arquivo", dona(), null)
                .andExpect(status().isNotFound());
        assertThat(armazenamento.existe(chave)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM corona.vendedores_documentos WHERE id = ?::uuid",
                Boolean.class, docId)).isTrue();
    }

    @Test
    void documentoDeOutroVendedorNaoBaixaPelaRotaDeste() throws Exception {
        String dono = criarVendedor().get("id").asString();
        String outro = criarVendedor().get("id").asString();
        String docId = ler(enviarArquivo(dono, "RG", "rg.png", "image/png", PNG, dona()).andExpect(status().isCreated()))
                .get("id").asString();
        enviar("GET", BASE + "/" + outro + "/documentos/" + docId + "/arquivo", dona(), null)
                .andExpect(status().isNotFound());
        enviar("DELETE", BASE + "/" + outro + "/documentos/" + docId, dona(), null).andExpect(status().isNotFound());
    }

    @Test
    void arquivoDeTipoNaoAceitoEhRecusadoMesmoComTipoMentiroso() throws Exception {
        String id = criarVendedor().get("id").asString();
        byte[] programa = "MZ\u0090\u0000\u0003 programa".getBytes(StandardCharsets.ISO_8859_1);
        enviarArquivo(id, "RG", "virus.exe", "application/octet-stream", programa, dona())
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.codigo").value("ARQUIVO_TIPO_NAO_ACEITO"));
        // O que vale é o conteúdo: um programa dito "PDF" não passa.
        enviarArquivo(id, "RG", "rg.pdf", "application/pdf", programa, dona())
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.codigo").value("ARQUIVO_TIPO_NAO_ACEITO"));
        enviarArquivo(id, "RG", "texto.txt", "text/plain", "ola".getBytes(StandardCharsets.UTF_8), dona())
                .andExpect(status().isUnsupportedMediaType());
        // Um PDF verdadeiro com o nome e o tipo trocados passa, e o tipo guardado é o do conteúdo.
        JsonNode ok = ler(enviarArquivo(id, "RG", "foto.jpg", "image/jpeg", PDF, dona()).andExpect(status().isCreated()));
        assertThat(ok.get("tipoConteudo").asString()).isEqualTo("application/pdf");
        enviar("GET", BASE + "/" + id + "/documentos", dona(), null).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void arquivoVazioSemDescricaoOuMaiorQue10MbEhRecusado() throws Exception {
        String id = criarVendedor().get("id").asString();
        enviarArquivo(id, "RG", "rg.pdf", "application/pdf", new byte[0], dona())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("arquivo"));
        enviarArquivo(id, "   ", "rg.pdf", "application/pdf", PDF, dona())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("descricao"));

        byte[] dezMbMaisUm = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, dezMbMaisUm, 0, PDF.length);
        enviarArquivo(id, "RG", "grande.pdf", "application/pdf", dezMbMaisUm, dona())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.codigo").value("ARQUIVO_GRANDE_DEMAIS"));
        // Exatamente 10 MB ainda vale.
        byte[] dezMb = Arrays.copyOf(dezMbMaisUm, 10 * 1024 * 1024);
        enviarArquivo(id, "RG", "limite.pdf", "application/pdf", dezMb, dona()).andExpect(status().isCreated());
    }

    @Test
    void documentoDeVendedorQueNaoExisteDa404() throws Exception {
        enviarArquivo(UUID.randomUUID().toString(), "RG", "rg.pdf", "application/pdf", PDF, dona())
                .andExpect(status().isNotFound());
    }

    // --- usuário do vendedor e clientes ---

    @Test
    void usuarioVendedorExigeUmVendedorQueExiste() throws Exception {
        Map<String, Object> sem = mapa("nome", "Pessoa", "login", "novo." + UUID.randomUUID().toString().substring(0, 8),
                "perfil", "VENDEDOR");
        enviar("POST", "/api/dona/usuarios", dona(), sem).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("vendedorId"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("Escolha o vendedor deste acesso."));

        sem.put("vendedorId", UUID.randomUUID().toString());
        enviar("POST", "/api/dona/usuarios", dona(), sem).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("vendedorId"));

        String excluido = criarVendedor().get("id").asString();
        enviar("DELETE", BASE + "/" + excluido, dona(), null).andExpect(status().isNoContent());
        sem.put("vendedorId", excluido);
        enviar("POST", "/api/dona/usuarios", dona(), sem).andExpect(status().isBadRequest());

        String vivo = criarVendedor().get("id").asString();
        sem.put("vendedorId", vivo);
        enviar("POST", "/api/dona/usuarios", dona(), sem).andExpect(status().isCreated())
                .andExpect(jsonPath("$.vendedorId").value(vivo));
    }

    @Test
    void oBancoExigeVendedorNoUsuarioVendedorECadastroQueExiste() {
        String hash = "$2a$04$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234";
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.usuarios (id, nome, login, senha_hash, perfil) VALUES (?, 'x', ?, ?, 'VENDEDOR')""",
                UUID.randomUUID(), "sem.vendedor." + UUID.randomUUID().toString().substring(0, 8), hash))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.usuarios (id, nome, login, senha_hash, perfil, vendedor_id)
                VALUES (?, 'x', ?, ?, 'VENDEDOR', ?)""",
                UUID.randomUUID(), "fantasma." + UUID.randomUUID().toString().substring(0, 8), hash, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void clienteSoAceitaVendedorResponsavelQueExiste() throws Exception {
        enviar("POST", "/api/dona/clientes", dona(), mapa("nome", unico("Cliente"), "vendedorResponsavelId",
                UUID.randomUUID().toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("vendedorResponsavelId"));
        String vendedor = criarVendedor().get("id").asString();
        enviar("POST", "/api/dona/clientes", dona(), mapa("nome", unico("Cliente"), "vendedorResponsavelId", vendedor))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.vendedorResponsavelId").value(vendedor));
    }

    @Test
    void praçaERestricaoEmUsoPorVendedorNaoSaemDoBanco() throws Exception {
        // A chave estrangeira impede apontar para o que não existe; a regra de excluir o que está em uso é da API.
        UUID praca = criarPraca();
        criarVendedor("pracaId", praca.toString());
        enviar("DELETE", "/api/dona/pracas/" + praca, dona(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_EM_USO"));
        UUID restricao = criarRestricao();
        criarVendedor("restricaoId", restricao.toString());
        enviar("DELETE", "/api/dona/restricoes/" + restricao, dona(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_EM_USO"));
    }
}
