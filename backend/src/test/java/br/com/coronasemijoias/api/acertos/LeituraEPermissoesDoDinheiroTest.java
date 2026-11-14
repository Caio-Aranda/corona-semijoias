package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.caixa.ConsultasDoCaixa;
import br.com.coronasemijoias.api.contasreceber.ConsultasDeContasAReceber;
import br.com.coronasemijoias.api.contasreceber.ConsultasDeRecebimentosDoVendedor;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * A leitura do dinheiro do vendedor e as permissões, validadas na API (tarefa 7.6; ADR-0012, itens 7, 11 e 16): contas dos dois
 * níveis, recebimentos, o caixa só da dona, a ficha do acerto com o dinheiro, 403 cruzado em todas as rotas novas, 401 sem
 * login e nada de caixa, categoria, quem registrou ou custo nas respostas do vendedor.
 */
class LeituraEPermissoesDoDinheiroTest extends ApoioDoFechamento {

    private static final String UM = UUID.randomUUID().toString();
    private static final String RECEBIMENTOS_DA_DONA = "/api/dona/recebimentos-vendedor";

    private record Rota(String metodo, String caminho) {
    }

    private static final List<Rota> DA_DONA = List.of(
            new Rota("POST", RECEBIMENTOS_DA_DONA), new Rota("GET", RECEBIMENTOS_DA_DONA),
            new Rota("POST", RECEBIMENTOS_DA_DONA + "/" + UM + "/estorno"),
            new Rota("GET", "/api/dona/caixa/lancamentos"), new Rota("GET", "/api/dona/contas-receber"));
    private static final List<Rota> DO_VENDEDOR = List.of(
            new Rota("GET", "/api/vendedor/recebimentos"), new Rota("GET", "/api/vendedor/contas-receber"));

    private UsuarioDeTeste vendedor;
    private UUID cliente;

    @BeforeEach
    void umVendedor() throws Exception {
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
    }

    private ResultActions chamar(Rota r, RequestPostProcessor quem) throws Exception {
        var pedido = "GET".equals(r.metodo()) ? get(r.caminho()) : post(r.caminho()).contentType(MediaType.APPLICATION_JSON).content("{}");
        return quem == null ? mvc.perform(pedido) : mvc.perform(pedido.with(quem));
    }

    private static LocalDate hoje() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    /** Fecha um acerto do vendedor em que ele pagou 5,00 e ficou devendo 30,00. Devolve o id do acerto. */
    private UUID acertoComPagamento(UsuarioDeTeste v, UUID clienteDele) throws Exception {
        UUID peca = peca("50.00", 20);
        UUID maleta = maletaConferida(v, semana(), peca, 4);
        vender(v, maleta, clienteDele, peca, 1, "50.00");
        UUID acerto = iniciar(v, null);
        bipar(acerto, peca, 3, "BOA");
        var pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00");
        pedido.put("pagamento", mapa("recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "PIX", "valor", "5.00")),
                "restante", mapa("parcelas", 2, "primeiroVencimento", hoje().plusDays(30).toString())));
        fechar(acerto, pedido).andExpect(status().isCreated());
        return acerto;
    }

    // --- permissões ---

    @Test
    void vendedorEmRotaDaDonaRecebe403EDonaEmRotaDoVendedorRecebe403() throws Exception {
        for (Rota r : DA_DONA) {
            chamar(r, como(vendedor)).andExpect(status().isForbidden());
        }
        for (Rota r : DO_VENDEDOR) {
            chamar(r, dona()).andExpect(status().isForbidden());
        }
    }

    @Test
    void semLoginRecebe401() throws Exception {
        for (Rota r : DA_DONA) {
            chamar(r, null).andExpect(status().isUnauthorized());
        }
        for (Rota r : DO_VENDEDOR) {
            chamar(r, null).andExpect(status().isUnauthorized());
        }
    }

    // --- as contas dos dois níveis ---

    @Test
    void asContasDoVendedorTrazemOsDoisNiveisEACadaUmSoVeAsSuas() throws Exception {
        UUID acerto = acertoComPagamento(vendedor, cliente);
        // Uma venda a prazo dele: conta do nível 1.
        UUID peca = peca("20.00", 10);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 2);
        postar(VENDAS, como(vendedor), mapa("id", UUID.randomUUID(), "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", peca, "quantidade", 1)), "pagamentos", List.of(pagamento("5.00", "DINHEIRO"))))
                .andExpect(status().isCreated());
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID acertoDoOutro = acertoComPagamento(outro, cliente(outro));

        JsonNode minhas = corpoDe(ler("/api/vendedor/contas-receber", como(vendedor)).andExpect(status().isOk()));
        List<String> niveis = new ArrayList<>();
        minhas.forEach(c -> niveis.add(c.get("nivel").asString()));
        assertThat(niveis).containsOnly("CLIENTE", "VENDEDOR");
        assertThat(niveis.stream().filter("VENDEDOR"::equals).count()).isEqualTo(2);
        assertThat(niveis.stream().filter("CLIENTE"::equals).count()).isEqualTo(1);
        // Só o nível 2: duas parcelas de 15,00 do acerto, sem venda nem cliente.
        JsonNode soNivel2 = corpoDe(ler("/api/vendedor/contas-receber?nivel=VENDEDOR", como(vendedor)).andExpect(status().isOk()));
        assertThat(soNivel2).hasSize(2);
        assertThat(new BigDecimal(soNivel2.get(0).get("valor").asString())).isEqualByComparingTo("15.00");
        assertThat(soNivel2.get(0).get("origem").asString()).isEqualTo("ACERTO");
        assertThat(soNivel2.get(0).get("vendaId").isNull()).isTrue();
        ler("/api/vendedor/contas-receber?nivel=QUALQUER", como(vendedor)).andExpect(status().isBadRequest());
        // O outro vendedor não aparece nas contas deste.
        assertThat(jdbc.queryForList("SELECT id FROM corona.contas_receber WHERE origem_id = ?", UUID.class, acertoDoOutro)).hasSize(2);
        List<String> ids = new ArrayList<>();
        minhas.forEach(c -> ids.add(c.get("id").asString()));
        for (UUID idDoOutro : jdbc.queryForList("SELECT id FROM corona.contas_receber WHERE origem_id = ?", UUID.class, acertoDoOutro)) {
            assertThat(ids).doesNotContain(idDoOutro.toString());
        }
        assertThat(acerto).isNotNull();

        // A dona vê os dois níveis e filtra por nível e vendedor.
        JsonNode daDona = corpoDe(ler("/api/dona/contas-receber?nivel=VENDEDOR&vendedorId=" + vendedor.vendedorId(), dona())
                .andExpect(status().isOk()));
        assertThat(daDona).hasSize(2);
        assertThat(daDona.get(0).get("nivel").asString()).isEqualTo("VENDEDOR");
        assertThat(daDona.get(0).get("vendedorNome").asString()).isNotBlank();
        JsonNode soClientes = corpoDe(ler("/api/dona/contas-receber?nivel=CLIENTE&vendedorId=" + vendedor.vendedorId(), dona())
                .andExpect(status().isOk()));
        assertThat(soClientes).hasSize(1);
        assertThat(soClientes.get(0).get("nivel").asString()).isEqualTo("CLIENTE");
    }

    // --- recebimentos ---

    @Test
    void osRecebimentosDoVendedorSaoSoOsDeleESemDadosDaDona() throws Exception {
        acertoComPagamento(vendedor, cliente);
        UsuarioDeTeste outro = usuarios.criarVendedor();
        acertoComPagamento(outro, cliente(outro));
        JsonNode meus = corpoDe(ler("/api/vendedor/recebimentos", como(vendedor)).andExpect(status().isOk()));
        assertThat(meus).hasSize(1);
        assertThat(meus.get(0).get("forma").asString()).isEqualTo("PIX");
        assertThat(new BigDecimal(meus.get(0).get("valor").asString())).isEqualByComparingTo("5.00");
        assertThat(meus.get(0).get("aplicadoEm").asString()).isEqualTo("ACERTO");
        assertThat(meus.toString()).doesNotContain("registrad").doesNotContain("motivo").doesNotContain("caixa").doesNotContain("custo")
                .doesNotContain("categoria");
        // A dona vê os dois vendedores e filtra.
        assertThat(corpoDe(ler(RECEBIMENTOS_DA_DONA, dona()).andExpect(status().isOk())).size()).isGreaterThanOrEqualTo(2);
        assertThat(corpoDe(ler(RECEBIMENTOS_DA_DONA + "?vendedorId=" + outro.vendedorId(), dona()).andExpect(status().isOk()))).hasSize(1);
        ler(RECEBIMENTOS_DA_DONA + "?de=2999-01-02&ate=2999-01-01", dona()).andExpect(status().isBadRequest());
    }

    // --- o caixa ---

    @Test
    void oCaixaEASomaDosLancamentosESoADonaVe() throws Exception {
        acertoComPagamento(vendedor, cliente);
        LocalDate hoje = hoje();
        JsonNode extrato = corpoDe(ler("/api/dona/caixa/lancamentos?de=" + hoje + "&ate=" + hoje, dona()).andExpect(status().isOk()));
        BigDecimal soma = BigDecimal.ZERO;
        for (JsonNode l : extrato.get("lancamentos")) {
            soma = soma.add(new BigDecimal(l.get("valor").asString()));
            assertThat(new BigDecimal(l.get("saldoDepois").asString())).isEqualByComparingTo(
                    new BigDecimal(extrato.get("saldoAnterior").asString()).add(soma));
        }
        assertThat(new BigDecimal(extrato.get("saldoFinal").asString())).isEqualByComparingTo(
                new BigDecimal(extrato.get("saldoAnterior").asString()).add(soma));
        assertThat(new BigDecimal(extrato.get("saldoFinal").asString())).isEqualByComparingTo(
                jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa WHERE data_lancamento <= ?", BigDecimal.class, hoje));
        assertThat(extrato.get("lancamentos").toString()).contains("Repasse do vendedor");
    }

    // --- a ficha do acerto ---

    @Test
    void aFichaDoAcertoFechadoMostraORecebidoOCreditoOPagoEAsContas() throws Exception {
        UUID acerto = acertoComPagamento(vendedor, cliente);
        for (boolean daDona : List.of(true, false)) {
            String caminho = daDona ? ACERTOS_DA_DONA : ACERTOS_DO_VENDEDOR;
            JsonNode f = corpoDe(ler(caminho + "/" + acerto, daDona ? dona() : como(vendedor)).andExpect(status().isOk())).get("fechamento");
            assertThat(new BigDecimal(f.get("recebidoNoAcerto").asString())).isEqualByComparingTo("5.00");
            assertThat(new BigDecimal(f.get("ficouDevendo").asString())).isEqualByComparingTo("30.00");
            assertThat(new BigDecimal(f.get("pagoAoVendedor").asString())).isEqualByComparingTo("0.00");
            assertThat(new BigDecimal(f.get("creditoUsado").asString())).isEqualByComparingTo("0.00");
            assertThat(f.get("recebimentos")).hasSize(1);
            assertThat(f.get("contas")).hasSize(2);
        }
    }

    // --- o vendedor nunca recebe o que é da dona ---

    @Test
    void osTiposDoVendedorNaoTemCaixaCategoriaQuemRegistrouNemCusto() {
        List<Class<?>> tipos = List.of(ConsultasDeRecebimentosDoVendedor.RecebimentoDoVendedor.class, ConsultasDeContasAReceber.Conta.class,
                ConsultasDeAcertos.Fechamento.class, ConsultasDeAcertos.RecebimentoDoAcerto.class, ConsultasDeAcertos.ContaDoAcerto.class);
        for (Class<?> tipo : tipos) {
            for (RecordComponent c : tipo.getRecordComponents()) {
                String nome = c.getName().toLowerCase();
                assertThat(nome).as(tipo.getSimpleName() + "." + c.getName()).doesNotContain("custo").doesNotContain("caixa")
                        .doesNotContain("categoria").doesNotContain("registrad").doesNotContain("conteudo").doesNotContain("motivo");
            }
        }
        // E o caixa nunca é tipo de resposta do vendedor.
        assertThat(ConsultasDoCaixa.Extrato.class.getPackageName()).isEqualTo("br.com.coronasemijoias.api.caixa");
    }
}
