package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * O fechamento do acerto de ponta a ponta (tarefa 7.5; ADR-0012, itens 3.6, 5, 6, 8, 14 e 18). As vendas vêm pela API; a
 * cotação das vendas é a do exemplo (1 Gs = R$ 0,00090000, a única cadastrada pelo teste: a venda sem cotação gravada usa a mais
 * antiga cadastrada).
 */
class FechamentoDoAcertoTest extends ApoioDoFechamento {

    private UsuarioDeTeste vendedor;
    private UUID cliente;

    @BeforeEach
    void umVendedor() throws Exception {
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 10 WHERE chave = 'limite_desconto_percentual'");
    }

    private static BigDecimal d(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    private BigDecimal doAcerto(UUID acerto, String coluna) {
        return jdbc.queryForObject("SELECT " + coluna + " FROM corona.acertos WHERE id = ?", BigDecimal.class, acerto);
    }

    private int movimentosNaMaleta(UUID maleta, String tipo) {
        return contar("SELECT coalesce(-sum(quantidade), 0) FROM corona.movimentos_remessa WHERE remessa_id = ? AND tipo = ?", maleta, tipo);
    }

    private int totalDeLinhas() {
        return contar("""
                SELECT (SELECT count(*) FROM corona.acerto_eventos) + (SELECT count(*) FROM corona.itens_acerto)
                     + (SELECT count(*) FROM corona.comissoes) + (SELECT count(*) FROM corona.pendencias_acerto)
                     + (SELECT count(*) FROM corona.movimentos_remessa) + (SELECT count(*) FROM corona.movimentacoes_estoque)
                     + (SELECT count(*) FROM corona.auditoria)""");
    }

    /** Uma maleta com A (10), vendas de 2 (uma com desconto) e o fechamento pronto para ser decidido. */
    private record Cenario(UUID acerto, UUID maleta, UUID peca) {
    }

    private Cenario cenarioSimples(int enviadas, int vendidas, String preco) throws Exception {
        UUID peca = peca(preco, 50);
        UUID maleta = maletaConferida(vendedor, semana(), peca, enviadas);
        if (vendidas > 0) {
            vender(vendedor, maleta, cliente, peca, vendidas, new BigDecimal(preco).multiply(BigDecimal.valueOf(vendidas)).toPlainString());
        }
        return new Cenario(iniciar(vendedor, null), maleta, peca);
    }

    // --- o exemplo do ADR-0012, item 18 ---

    @Test
    void oExemploDoAdrFechaComOsMesmosNumeros() throws Exception {
        UUID a = peca("50.00", 60);
        UUID b = peca("33.33", 60);
        UUID c = peca("20.00", 60);

        // Acerto antigo: a venda de 20,00 de C, com a meta baixa (40%): comissão original de 8,00.
        configurarComissao(1, 30, 40);
        UUID m0 = maletaConferida(vendedor, semana(), c, 1);
        UUID venda0 = vender(vendedor, m0, cliente, c, 1, "20.00");
        UUID acerto0 = iniciar(vendedor, null);
        fechar(acerto0, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "12.00")).andExpect(status().isCreated());
        configurarComissao(700_000, 30, 40);
        assertThat(jdbc.queryForObject("SELECT valor FROM corona.comissoes WHERE venda_id = ?", BigDecimal.class, venda0))
                .isEqualByComparingTo("8.00");

        // Devolução (da dona) dessa venda de acerto fechado: a peça vai para a loja e entra no acerto seguinte.
        postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", venda0, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "DINHEIRO", "itens", List.of(mapa("produtoId", c, "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isCreated());

        // A semana: maleta nova com 10 de A, reposição com 5 de B.
        UUID m1 = maletaConferida(vendedor, semana(), a, 10);
        UUID m2 = maletaEnviada(vendedor, semana(), "REPOSICAO", b, 5);
        postar("/api/vendedor/maletas/" + m2 + "/conferencia", como(vendedor), mapa("id", UUID.randomUUID(),
                "itens", List.of(mapa("produtoId", b, "contada", 5)))).andExpect(status().isCreated());
        postar(VENDAS, como(vendedor), mapa("id", UUID.randomUUID(), "remessaId", m1, "clienteId", cliente,
                "itens", List.of(mapa("produtoId", a, "quantidade", 2, "desconto", "5.00")),
                "pagamentos", List.of(pagamento("95.00", "DINHEIRO")))).andExpect(status().isCreated());
        vender(vendedor, m2, cliente, b, 1, "33.33");
        UUID v3 = vender(vendedor, m1, cliente, a, 1, "50.00");
        postar("/api/dona/vendas/" + v3 + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "erro"))
                .andExpect(status().isCreated());

        UUID acerto = iniciar(vendedor, null);
        bipar(acerto, a, 7, "BOA");
        bipar(acerto, b, 4, "BOA");
        bipar(acerto, c, 1, "BOA");
        int centralA = estoqueAtual(a);
        int centralB = estoqueAtual(b);
        int centralC = estoqueAtual(c);

        JsonNode previa = previa(acerto);
        assertThat(d(previa.get("liquido"))).isEqualByComparingTo("127.83");
        assertThat(previa.get("faltas").size()).isEqualTo(1);

        UUID id = UUID.randomUUID();
        JsonNode ficha = corpoDe(fechar(acerto, pedidoDeFechamento(id, List.of(decisaoDeFalta(a, "COBRAR_NO_ACERTO", null)), List.of(),
                "127.83")).andExpect(status().isCreated()));
        assertThat(ficha.get("status").asString()).isEqualTo("ACERTADO");

        assertThat(jdbc.queryForObject("SELECT vendido_para_meta_guarani FROM corona.acertos WHERE id = ?", Long.class, acerto))
                .isEqualTo(142_589L);
        assertThat(doAcerto(acerto, "percentual_comissao")).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT meta_guarani FROM corona.acertos WHERE id = ?", Integer.class, acerto)).isEqualTo(700_000);
        assertThat(doAcerto(acerto, "vendido_bruto")).isEqualByComparingTo("183.33");
        assertThat(doAcerto(acerto, "descontos")).isEqualByComparingTo("5.00");
        assertThat(doAcerto(acerto, "devolvido")).isEqualByComparingTo("70.00");
        assertThat(doAcerto(acerto, "vendido_liquido")).isEqualByComparingTo("108.33");
        assertThat(doAcerto(acerto, "comissao")).isEqualByComparingTo("30.50");
        assertThat(doAcerto(acerto, "faltas_cobradas")).isEqualByComparingTo("50.00");
        assertThat(doAcerto(acerto, "liquido")).isEqualByComparingTo("127.83");
        assertThat(doAcerto(acerto, "recebido_no_acerto")).isEqualByComparingTo("0.00");
        assertThat(doAcerto(acerto, "ficou_devendo")).isEqualByComparingTo("127.83");

        // Estoque: A volta 7 (a falta de 1 é PERDA na maleta), B volta 4, C volta 1 ("para a loja"); maletas zeradas e acertadas.
        assertThat(estoqueAtual(a)).isEqualTo(centralA + 7);
        assertThat(estoqueAtual(b)).isEqualTo(centralB + 4);
        assertThat(estoqueAtual(c)).isEqualTo(centralC + 1);
        assertThat(movimentosNaMaleta(m1, "PERDA")).isEqualTo(1);
        assertThat(saldoDaMaleta(m1, a)).isZero();
        assertThat(saldoDaMaleta(m2, b)).isZero();
        assertThat(statusDaMaleta(m1)).isEqualTo("ACERTADA");
        assertThat(statusDaMaleta(m2)).isEqualTo("ACERTADA");
        assertThat(contar("SELECT count(*) FROM corona.pendencias_acerto WHERE acerto_id = ? AND tipo = 'FALTA' AND status = 'COBRADA'",
                acerto)).isEqualTo(1);
        // O estorno da devolução usou o percentual da linha original (40%) num acerto de 30%.
        assertThat(jdbc.queryForObject("SELECT percentual FROM corona.comissoes WHERE acerto_id = ? AND tipo = 'ESTORNO' AND valor = -8.00",
                BigDecimal.class, acerto)).isEqualByComparingTo("40");
    }

    @Test
    void ocadastroDoVendedorNaoMudaAComissao() throws Exception {
        jdbc.update("UPDATE corona.vendedores SET comissao_percentual = 99 WHERE id = ?", vendedor.vendedorId());
        Cenario c = cenarioSimples(5, 1, "50.00");
        bipar(c.acerto(), c.peca(), 4, "BOA");
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00")).andExpect(status().isCreated());
        assertThat(doAcerto(c.acerto(), "comissao")).isEqualByComparingTo("15.00");
    }

    // --- esperado x contado ---

    @Test
    void sobraImpedeENadaEGravado() throws Exception {
        Cenario c = cenarioSimples(5, 1, "50.00");
        bipar(c.acerto(), c.peca(), 5, "BOA");
        int antes = totalDeLinhas();
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SOBRA_NO_ACERTO"));
        assertThat(totalDeLinhas()).isEqualTo(antes);
        assertThat(statusDaMaleta(c.maleta())).isEqualTo("EM_ACERTO");
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", c.acerto())).isEqualTo(1);
    }

    @Test
    void faltaComAsTresDecisoes() throws Exception {
        for (String decisao : List.of("COBRAR_NO_ACERTO", "COBRAR_DEPOIS", "LOJA_ASSUME")) {
            UsuarioDeTeste v = usuarios.criarVendedor();
            vendedor = v;
            cliente = cliente(v);
            Cenario c = cenarioSimples(4, 0, "50.00");
            bipar(c.acerto(), c.peca(), 3, "BOA");
            String motivo = decisao.equals("LOJA_ASSUME") ? "perda da loja" : null;
            String liquido = decisao.equals("COBRAR_NO_ACERTO") ? "50.00" : "0.00";
            fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(c.peca(), decisao, motivo)), List.of(),
                    liquido)).andExpect(status().isCreated());
            String status = jdbc.queryForObject("SELECT status FROM corona.pendencias_acerto WHERE acerto_id = ? AND tipo = 'FALTA'",
                    String.class, c.acerto());
            assertThat(status).isEqualTo(switch (decisao) {
                case "COBRAR_NO_ACERTO" -> "COBRADA";
                case "COBRAR_DEPOIS" -> "ABERTA";
                default -> "ASSUMIDA_PELA_LOJA";
            });
            assertThat(doAcerto(c.acerto(), "faltas_cobradas")).isEqualByComparingTo(liquido);
            assertThat(movimentosNaMaleta(c.maleta(), "PERDA")).isEqualTo(1);
        }
    }

    @Test
    void lojaAssumeExigeMotivo() throws Exception {
        Cenario c = cenarioSimples(4, 0, "50.00");
        bipar(c.acerto(), c.peca(), 3, "BOA");
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(c.peca(), "LOJA_ASSUME", " ")), List.of(), "0.00"))
                .andExpect(status().isBadRequest());
        assertThat(statusDaMaleta(c.maleta())).isEqualTo("EM_ACERTO");
    }

    @Test
    void defeitoAprovadoNaoAumentaOSaldoVendavelERecusadoVoltaComoBoa() throws Exception {
        Cenario c = cenarioSimples(5, 0, "50.00");
        int antes = estoqueAtual(c.peca());
        bipar(c.acerto(), c.peca(), 3, "BOA");
        bipar(c.acerto(), c.peca(), 2, "DEFEITO");
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(defeitoAprovado(c.peca(), 1)), "0.00"))
                .andExpect(status().isCreated());
        // Voltam 5 ao central; 1 aprovada sai como avaria: o saldo vendável sobe 4.
        assertThat(estoqueAtual(c.peca())).isEqualTo(antes + 4);
        assertThat(contar("SELECT count(*) FROM corona.movimentacoes_estoque WHERE produto_id = ? AND tipo = 'AVARIA' AND origem_tipo = 'ACERTO'",
                c.peca())).isEqualTo(1);
        assertThat(contar("SELECT defeito_aprovado FROM corona.itens_acerto WHERE acerto_id = ? AND produto_id = ?", c.acerto(), c.peca()))
                .isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.pendencias_acerto WHERE acerto_id = ? AND tipo = 'DEFEITO' AND status = 'APROVADA'",
                c.acerto())).isEqualTo(1);
    }

    @Test
    void defeitosPrecisamDeUmaDecisaoPorPecaEAprovadasNaoPassamDoContado() throws Exception {
        Cenario c = cenarioSimples(5, 0, "50.00");
        bipar(c.acerto(), c.peca(), 4, "BOA");
        bipar(c.acerto(), c.peca(), 1, "DEFEITO");
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(defeitoAprovado(c.peca(), 2)), "0.00"))
                .andExpect(status().isBadRequest());
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FECHAMENTO_DESATUALIZADO"));
    }

    @Test
    void acertoSemMaletaSoComEventoAtrasado() throws Exception {
        UUID peca = peca("50.00", 10);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 3);
        vender(vendedor, maleta, cliente, peca, 3, "150.00");
        UUID acerto0 = iniciar(vendedor, null);
        fechar(acerto0, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "105.00")).andExpect(status().isCreated());
        assertThat(doAcerto(acerto0, "liquido")).isEqualByComparingTo("105.00");
        // Sem maleta aberta: um acerto só para os eventos que chegaram depois.
        UUID semana = semana();
        UUID acerto = UUID.randomUUID();
        postar(ACERTOS_DA_DONA, dona(), mapa("id", acerto, "vendedorId", vendedor.vendedorId(), "semanaId", semana)).andExpect(status().isCreated());
        fechar(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00")).andExpect(status().isCreated());
        assertThat(doAcerto(acerto, "vendido_liquido")).isEqualByComparingTo("0.00");
    }

    @Test
    void acertoAFavorDoVendedorTemLiquidoNegativo() throws Exception {
        UUID peca = peca("50.00", 10);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 3);
        UUID venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto0 = iniciar(vendedor, null);
        bipar(acerto0, peca, 2, "BOA");
        fechar(acerto0, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00")).andExpect(status().isCreated());
        postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "DINHEIRO", "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isCreated());
        UUID acerto = UUID.randomUUID();
        postar(ACERTOS_DA_DONA, dona(), mapa("id", acerto, "vendedorId", vendedor.vendedorId(), "semanaId", semana())).andExpect(status().isCreated());
        bipar(acerto, peca, 1, "BOA");
        // Devolvido 50,00; a comissão estornada 15,00: líquido -35,00 (a favor do vendedor).
        fechar(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "-35.00")).andExpect(status().isCreated());
        assertThat(doAcerto(acerto, "liquido")).isEqualByComparingTo("-35.00");
        assertThat(doAcerto(acerto, "ficou_devendo")).isEqualByComparingTo("-35.00");
    }

    // --- decisões desatualizadas ---

    @Test
    void decisoesAMaisOuAMenosOuLiquidoDiferenteDao409SemGravar() throws Exception {
        Cenario c = cenarioSimples(4, 0, "50.00");
        bipar(c.acerto(), c.peca(), 3, "BOA");
        int antes = totalDeLinhas();
        // Falta sem decisão.
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "50.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FECHAMENTO_DESATUALIZADO"));
        // Decisão de peça que não tem falta.
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(c.peca(), "COBRAR_NO_ACERTO", null),
                decisaoDeFalta(UUID.randomUUID(), "COBRAR_NO_ACERTO", null)), List.of(), "50.00")).andExpect(status().isConflict());
        // O líquido que ela viu não é o de agora.
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(c.peca(), "COBRAR_NO_ACERTO", null)), List.of(),
                "49.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FECHAMENTO_DESATUALIZADO"));
        assertThat(totalDeLinhas()).isEqualTo(antes);
        assertThat(statusDaMaleta(c.maleta())).isEqualTo("EM_ACERTO");
    }

    // --- a prévia ---

    @Test
    void aPreviaMostraOMesmoQueOFechamentoGrava() throws Exception {
        Cenario c = cenarioSimples(5, 2, "50.00");
        bipar(c.acerto(), c.peca(), 2, "BOA");
        int antes = totalDeLinhas();
        JsonNode previa = previa(c.acerto());
        assertThat(totalDeLinhas()).isEqualTo(antes);
        UUID decisaoDe = c.peca();
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(decisaoDe, "COBRAR_NO_ACERTO", null)), List.of(),
                previa.get("liquido").asString())).andExpect(status().isCreated());
        for (String campo : List.of("vendidoBruto:vendido_bruto", "descontos:descontos", "devolvido:devolvido",
                "vendidoLiquido:vendido_liquido", "comissao:comissao", "faltasCobradas:faltas_cobradas", "liquido:liquido")) {
            String[] partes = campo.split(":");
            assertThat(d(previa.get(partes[0]))).as(partes[0]).isEqualByComparingTo(doAcerto(c.acerto(), partes[1]));
        }
        assertThat(previa.get("vendidoParaMetaGuarani").asLong()).isEqualTo(
                jdbc.queryForObject("SELECT vendido_para_meta_guarani FROM corona.acertos WHERE id = ?", Long.class, c.acerto()));
    }

    // --- cotação e configuração ---

    @Test
    void aCotacaoGravadaEAMaisRecenteENaoMudaQuandoChegaOutra() throws Exception {
        Cenario c = cenarioSimples(3, 0, "50.00");
        bipar(c.acerto(), c.peca(), 3, "BOA");
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00")).andExpect(status().isCreated());
        UUID maisRecente = jdbc.queryForObject("SELECT id FROM corona.cotacoes ORDER BY data_cotacao DESC LIMIT 1", UUID.class);
        assertThat(jdbc.queryForObject("SELECT cotacao_id FROM corona.acertos WHERE id = ?", UUID.class, c.acerto())).isEqualTo(maisRecente);
        br.com.coronasemijoias.api.testes.CotacoesDaMassa.gravar(jdbc, "2291-06-08", "6.00000000", "0.00100000");
        assertThat(jdbc.queryForObject("SELECT cotacao_id FROM corona.acertos WHERE id = ?", UUID.class, c.acerto())).isEqualTo(maisRecente);
    }

    @Test
    void mudarAConfiguracaoDepoisNaoMudaOFechadoEValeNoProximo() throws Exception {
        Cenario c = cenarioSimples(3, 1, "50.00");
        bipar(c.acerto(), c.peca(), 2, "BOA");
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00")).andExpect(status().isCreated());
        configurarComissao(1, 10, 20);
        assertThat(doAcerto(c.acerto(), "comissao")).isEqualByComparingTo("15.00");
        assertThat(doAcerto(c.acerto(), "percentual_comissao")).isEqualByComparingTo("30");
        UUID peca = peca("50.00", 10);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 2);
        vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID proximo = iniciar(vendedor, null);
        bipar(proximo, peca, 1, "BOA");
        fechar(proximo, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "40.00")).andExpect(status().isCreated());
        assertThat(doAcerto(proximo, "percentual_comissao")).isEqualByComparingTo("20");
        assertThat(doAcerto(proximo, "comissao")).isEqualByComparingTo("10.00");
    }

    // --- idempotência ---

    @Test
    void mesmoIdNaoRepeteNadaOutrasDecisoesEOutroIdDao409() throws Exception {
        Cenario c = cenarioSimples(4, 0, "50.00");
        bipar(c.acerto(), c.peca(), 3, "BOA");
        UUID id = UUID.randomUUID();
        Map<String, Object> pedido = pedidoDeFechamento(id, List.of(decisaoDeFalta(c.peca(), "COBRAR_NO_ACERTO", null)), List.of(), "50.00");
        fechar(c.acerto(), pedido).andExpect(status().isCreated());
        int depois = totalDeLinhas();
        fechar(c.acerto(), pedido).andExpect(status().isOk());
        assertThat(totalDeLinhas()).isEqualTo(depois);
        fechar(c.acerto(), pedidoDeFechamento(id, List.of(decisaoDeFalta(c.peca(), "LOJA_ASSUME", "x")), List.of(), "0.00"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FECHAMENTO_JA_REGISTRADO_DIFERENTE"));
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(c.peca(), "COBRAR_NO_ACERTO", null)), List.of(),
                "50.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ACERTO_JA_FECHADO"));
        assertThat(totalDeLinhas()).isEqualTo(depois);
    }

    @Test
    void acertoDesistidoNaoFecha() throws Exception {
        Cenario c = cenarioSimples(2, 0, "50.00");
        postar(ACERTOS_DA_DONA + "/" + c.acerto() + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "erro"))
                .andExpect(status().isCreated());
        fechar(c.acerto(), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ACERTO_NAO_ESTA_EM_ANDAMENTO"));
    }

    // --- permissões ---

    @Test
    void soAdminFechaEVeAPrevia() throws Exception {
        Cenario c = cenarioSimples(2, 0, "50.00");
        postar(ACERTOS_DA_DONA + "/" + c.acerto() + "/fechar", como(vendedor), pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "0.00"))
                .andExpect(status().isForbidden());
        ler(ACERTOS_DA_DONA + "/" + c.acerto() + "/fechamento", como(vendedor)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ACERTOS_DA_DONA + "/" + c.acerto() + "/fechar")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
}
