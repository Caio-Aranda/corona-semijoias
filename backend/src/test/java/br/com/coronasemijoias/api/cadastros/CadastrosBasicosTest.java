package br.com.coronasemijoias.api.cadastros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Cadastros básicos da dona: permissão, CRUD com busca e página, exclusão lógica e unicidade (tarefa 1.2). */
class CadastrosBasicosTest extends TesteDeIntegracao {

    private static final AtomicInteger NUMERO_DA_SEMANA = new AtomicInteger(1000);

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

    private JsonNode criar(String caminho, Map<String, Object> corpo) throws Exception {
        String resposta = enviar("POST", caminho, dona(), corpo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resposta);
    }

    // --- permissão ---

    @Test
    void vendedorRecebe403EmTodasAsRotasDosCadastros() throws Exception {
        UUID id = UUID.randomUUID();
        for (String recurso : new String[] {"fornecedores", "categorias", "pracas", "semanas", "restricoes", "clientes"}) {
            String base = "/api/dona/" + recurso;
            RequestPostProcessor vendedor = usuarios.comoVendedor();
            enviar("GET", base, vendedor, null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("SEM_PERMISSAO"));
            enviar("GET", base + "/" + id, vendedor, null).andExpect(status().isForbidden());
            enviar("POST", base, vendedor, mapa()).andExpect(status().isForbidden());
            enviar("PUT", base + "/" + id, vendedor, mapa()).andExpect(status().isForbidden());
            enviar("DELETE", base + "/" + id, vendedor, null).andExpect(status().isForbidden());
        }
    }

    @Test
    void semTokenRecebe401() throws Exception {
        mvc.perform(get("/api/dona/clientes")).andExpect(status().isUnauthorized());
    }

    // --- categorias (cadastro simples, serve de modelo para praças) ---

    @Test
    void categoriaCriaListaEditaEExclui() throws Exception {
        String nome = unico("Brincos");
        JsonNode criada = criar("/api/dona/categorias", mapa("nome", "  " + nome + "  "));
        String id = criada.get("id").asString();
        assertThat(criada.get("nome").asString()).isEqualTo(nome);
        assertThat(criada.get("version").asInt()).isEqualTo(1);

        enviar("GET", "/api/dona/categorias?busca=" + nome.toLowerCase(), dona(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].id").value(id));

        String novoNome = unico("Colares");
        enviar("PUT", "/api/dona/categorias/" + id, dona(), mapa("nome", novoNome, "version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value(novoNome))
                .andExpect(jsonPath("$.version").value(2));

        enviar("DELETE", "/api/dona/categorias/" + id, dona(), null).andExpect(status().isNoContent());
        enviar("GET", "/api/dona/categorias/" + id, dona(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_NAO_ENCONTRADO"));
        enviar("GET", "/api/dona/categorias?busca=" + novoNome, dona(), null)
                .andExpect(jsonPath("$.total").value(0));
        // Exclusão lógica: a linha continua no banco, com deleted_at.
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM corona.categorias WHERE id = ?::uuid",
                Boolean.class, id)).isTrue();
        // Excluir de novo: já não existe para a dona.
        enviar("DELETE", "/api/dona/categorias/" + id, dona(), null).andExpect(status().isNotFound());
    }

    @Test
    void nomeRepetidoSemDiferenciarMaiusculasEhRecusadoMasLiberaDepoisDeExcluir() throws Exception {
        String nome = unico("Aneis");
        String id = criar("/api/dona/categorias", mapa("nome", nome)).get("id").asString();

        enviar("POST", "/api/dona/categorias", dona(), mapa("nome", nome.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_JA_EXISTE"));

        enviar("DELETE", "/api/dona/categorias/" + id, dona(), null).andExpect(status().isNoContent());
        criar("/api/dona/categorias", mapa("nome", nome.toUpperCase()));
    }

    @Test
    void editarParaUmNomeQueJaExisteEhRecusado() throws Exception {
        String nomeA = unico("Pulseiras");
        criar("/api/dona/categorias", mapa("nome", nomeA));
        String idB = criar("/api/dona/categorias", mapa("nome", unico("Tornozeleiras"))).get("id").asString();
        enviar("PUT", "/api/dona/categorias/" + idB, dona(), mapa("nome", nomeA, "version", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_JA_EXISTE"));
    }

    @Test
    void editarComVersaoVelhaEhRecusado() throws Exception {
        String id = criar("/api/dona/categorias", mapa("nome", unico("Relogios"))).get("id").asString();
        enviar("PUT", "/api/dona/categorias/" + id, dona(), mapa("nome", unico("Relogios B"), "version", 1))
                .andExpect(status().isOk());
        enviar("PUT", "/api/dona/categorias/" + id, dona(), mapa("nome", unico("Relogios C"), "version", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_ALTERADO"));
    }

    @Test
    void nomeEmBrancoEhRecusadoComOCampo() throws Exception {
        enviar("POST", "/api/dona/categorias", dona(), mapa("nome", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("nome"));
    }

    @Test
    void idDoClienteEhUsadoEOutroPedidoComOMesmoIdEhRecusado() throws Exception {
        UUID id = UUID.randomUUID();
        JsonNode criada = criar("/api/dona/categorias", mapa("id", id.toString(), "nome", unico("Com id")));
        assertThat(criada.get("id").asString()).isEqualTo(id.toString());
        enviar("POST", "/api/dona/categorias", dona(), mapa("id", id.toString(), "nome", unico("Outro")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_JA_EXISTE"));
        enviar("POST", "/api/dona/categorias", dona(), mapa("id", "nao-e-uuid", "nome", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listaPaginada() throws Exception {
        String marca = unico("Pagina");
        for (int i = 1; i <= 5; i++) {
            criar("/api/dona/categorias", mapa("nome", marca + " " + i));
        }
        enviar("GET", "/api/dona/categorias?busca=" + marca + "&pagina=1&tamanho=2", dona(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.tamanho").value(2))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].nome").value(marca + " 3"));
        enviar("GET", "/api/dona/categorias?tamanho=1000", dona(), null).andExpect(status().isBadRequest());
        enviar("GET", "/api/dona/categorias?pagina=-1", dona(), null).andExpect(status().isBadRequest());
    }

    // --- praças ---

    @Test
    void pracaCriaEExclui() throws Exception {
        String nome = unico("Ponta Pora");
        String id = criar("/api/dona/pracas", mapa("nome", nome)).get("id").asString();
        enviar("POST", "/api/dona/pracas", dona(), mapa("nome", nome)).andExpect(status().isConflict());
        enviar("DELETE", "/api/dona/pracas/" + id, dona(), null).andExpect(status().isNoContent());
        criar("/api/dona/pracas", mapa("nome", nome));
    }

    // --- restrições ---

    @Test
    void restricaoGuardaSeBloqueiaAMaleta() throws Exception {
        String codigo = unico("DEVE");
        JsonNode criada = criar("/api/dona/restricoes",
                mapa("codigo", codigo, "descricao", "Está devendo acerto", "bloqueiaRemessa", true));
        assertThat(criada.get("bloqueiaRemessa").asBoolean()).isTrue();
        enviar("POST", "/api/dona/restricoes", dona(), mapa("codigo", codigo.toLowerCase(), "descricao", "Outra"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_JA_EXISTE"));
        enviar("PUT", "/api/dona/restricoes/" + criada.get("id").asString(), dona(),
                mapa("codigo", codigo, "descricao", "Está devendo acerto", "bloqueiaRemessa", false, "version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloqueiaRemessa").value(false));
    }

    // --- fornecedores ---

    @Test
    void fornecedorGuardaCnpjSoComOsCaracteresDoNumero() throws Exception {
        String razao = unico("Folheados Brilho");
        JsonNode criado = criar("/api/dona/fornecedores", mapa(
                "razaoSocial", razao, "cnpj", "11.222.333/0001-81", "contatoNome", "Ana", "telefone", "(67) 99999-0000",
                "endereco", mapa("cidade", "Campo Grande", "uf", "MS")));
        assertThat(criado.get("cnpj").asString()).isEqualTo("11222333000181");
        assertThat(criado.get("endereco").get("cidade").asString()).isEqualTo("Campo Grande");
        assertThat(jdbc.queryForObject("SELECT cnpj FROM corona.fornecedores WHERE id = ?::uuid", String.class,
                criado.get("id").asString())).isEqualTo("11222333000181");

        // A busca acha pelo número do CNPJ digitado de qualquer jeito.
        enviar("GET", "/api/dona/fornecedores?busca=11.222.333/0001", dona(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void fornecedorSemCnpjEhAceitoEVariosSemCnpjConvivem() throws Exception {
        criar("/api/dona/fornecedores", mapa("razaoSocial", unico("Importadora A")));
        criar("/api/dona/fornecedores", mapa("razaoSocial", unico("Importadora B")));
    }

    @Test
    void cnpjComDigitoErradoEhRecusadoEmPortugues() throws Exception {
        enviar("POST", "/api/dona/fornecedores", dona(), mapa("razaoSocial", unico("X"), "cnpj", "11.222.333/0001-82"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos[0].campo").value("cnpj"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("Confira o CNPJ: ele não parece estar certo."));
    }

    @Test
    void cnpjRepetidoEhRecusadoMasLiberaDepoisDeExcluir() throws Exception {
        String id = criar("/api/dona/fornecedores", mapa("razaoSocial", unico("F1"), "cnpj", "45.723.174/0001-10"))
                .get("id").asString();
        enviar("POST", "/api/dona/fornecedores", dona(), mapa("razaoSocial", unico("F2"), "cnpj", "45723174000110"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_JA_EXISTE"));
        enviar("DELETE", "/api/dona/fornecedores/" + id, dona(), null).andExpect(status().isNoContent());
        criar("/api/dona/fornecedores", mapa("razaoSocial", unico("F3"), "cnpj", "45723174000110"));
    }

    // --- semanas ---

    private static Map<String, Object> semana(int numero, LocalDate inicio, LocalDate fim) {
        return mapa("numero", numero, "inicio", inicio.toString(), "fim", fim.toString());
    }

    private static LocalDate inicioDe(int numero) {
        return LocalDate.of(2100, 1, 1).plusDays(numero * 7L);
    }

    @Test
    void semanaComecaAbertaERecusaSobreposicaoDeDatas() throws Exception {
        int n = NUMERO_DA_SEMANA.getAndAdd(10);
        LocalDate inicio = inicioDe(n);
        JsonNode criada = criar("/api/dona/semanas", semana(n, inicio, inicio.plusDays(6)));
        assertThat(criada.get("situacao").asString()).isEqualTo("ABERTA");

        // Começa no último dia da semana anterior: sobrepõe.
        enviar("POST", "/api/dona/semanas", dona(), semana(n + 1, inicio.plusDays(6), inicio.plusDays(12)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SEMANA_SOBREPOSTA"));
        // Dia seguinte: tudo bem.
        criar("/api/dona/semanas", semana(n + 1, inicio.plusDays(7), inicio.plusDays(13)));
    }

    @Test
    void semanaComNumeroRepetidoOuFimAntesDoInicioEhRecusada() throws Exception {
        int n = NUMERO_DA_SEMANA.getAndAdd(10);
        LocalDate inicio = inicioDe(n);
        criar("/api/dona/semanas", semana(n, inicio, inicio.plusDays(6)));
        enviar("POST", "/api/dona/semanas", dona(), semana(n, inicio.plusDays(70), inicio.plusDays(76)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CADASTRO_JA_EXISTE"));
        enviar("POST", "/api/dona/semanas", dona(), semana(n + 1, inicio.plusDays(90), inicio.plusDays(80)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("fim"));
    }

    @Test
    void semanaPodeSerFechadaEExcluidaLiberandoAsDatas() throws Exception {
        int n = NUMERO_DA_SEMANA.getAndAdd(10);
        LocalDate inicio = inicioDe(n);
        String id = criar("/api/dona/semanas", semana(n, inicio, inicio.plusDays(6))).get("id").asString();
        Map<String, Object> edicao = semana(n, inicio, inicio.plusDays(6));
        edicao.put("situacao", "FECHADA");
        edicao.put("version", 1);
        enviar("PUT", "/api/dona/semanas/" + id, dona(), edicao)
                .andExpect(status().isOk()).andExpect(jsonPath("$.situacao").value("FECHADA"));
        enviar("DELETE", "/api/dona/semanas/" + id, dona(), null).andExpect(status().isNoContent());
        criar("/api/dona/semanas", semana(n, inicio, inicio.plusDays(6)));
    }

    @Test
    void situacaoDeSemanaDesconhecidaEhRecusada() throws Exception {
        int n = NUMERO_DA_SEMANA.getAndAdd(10);
        LocalDate inicio = inicioDe(n);
        Map<String, Object> corpo = semana(n, inicio, inicio.plusDays(6));
        corpo.put("situacao", "TALVEZ");
        enviar("POST", "/api/dona/semanas", dona(), corpo).andExpect(status().isBadRequest());
    }

    // --- clientes ---

    @Test
    void clienteGuardaCpfSoComDigitosEAceitaCpfRepetido() throws Exception {
        String nome = unico("Maria Cliente");
        UUID vendedor = usuarios.criarCadastroDeVendedor();
        JsonNode criado = criar("/api/dona/clientes", mapa(
                "nome", nome, "cpf", "529.982.247-25", "telefone", "67 3333-0000", "whatsapp", "67 99999-0000",
                "observacoes", "Paga em dia", "vendedorResponsavelId", vendedor.toString(),
                "endereco", mapa("logradouro", "Rua das Flores", "numero", "10")));
        assertThat(criado.get("cpf").asString()).isEqualTo("52998224725");
        assertThat(criado.get("vendedorResponsavelId").asString()).isEqualTo(vendedor.toString());
        String nomeOutra = unico("Outra Maria");
        // O CPF repetido não é recusado: vai para revisão da dona na sincronização (ESPECIFICACAO §10).
        criar("/api/dona/clientes", mapa("nome", nomeOutra, "cpf", "52998224725"));
        // A busca acha pelo CPF (com ou sem máscara) e pelo nome.
        enviar("GET", "/api/dona/clientes?busca=529.982.247&tamanho=100", dona(), null)
                .andExpect(status().isOk());
        enviar("GET", "/api/dona/clientes?busca=" + nome, dona(), null)
                .andExpect(jsonPath("$.total").value(1));
        enviar("GET", "/api/dona/clientes?busca=52998224725&tamanho=100", dona(), null)
                .andExpect(jsonPath("$.itens[?(@.nome=='" + nomeOutra + "')]").exists());
    }

    @Test
    void clienteSemCpfEhAceitoECpfInvalidoNao() throws Exception {
        criar("/api/dona/clientes", mapa("nome", unico("Sem CPF")));
        enviar("POST", "/api/dona/clientes", dona(), mapa("nome", unico("CPF ruim"), "cpf", "111.111.111-11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("cpf"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("Confira o CPF: ele não parece estar certo."));
    }

    @Test
    void clienteEditadoEExcluidoLogicamente() throws Exception {
        String id = criar("/api/dona/clientes", mapa("nome", unico("Ana"), "cpf", "52998224725")).get("id").asString();
        enviar("PUT", "/api/dona/clientes/" + id, dona(), mapa("nome", unico("Ana Paula"), "version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").doesNotExist())
                .andExpect(jsonPath("$.version").value(2));
        enviar("DELETE", "/api/dona/clientes/" + id, dona(), null).andExpect(status().isNoContent());
        enviar("GET", "/api/dona/clientes/" + id, dona(), null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM corona.clientes WHERE id = ?::uuid AND deleted_at IS NOT NULL",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void clienteNovoGanhaPosicaoNoPullDoSync() throws Exception {
        String id = criar("/api/dona/clientes", mapa("nome", unico("Pull"))).get("id").asString();
        assertThat(jdbc.queryForObject("SELECT sync_seq FROM corona.clientes WHERE id = ?::uuid", Long.class, id))
                .isPositive();
    }
}
