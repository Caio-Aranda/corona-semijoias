package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * O fechamento do acerto com o pagamento (tarefa 7.6; ADR-0012, itens 3.6, 7, 8, 9, 11 e 18): recebimentos em dinheiro e
 * Transferência (PIX), boleto como conta, o que ficou devendo em parcelas, conta antiga paga no acerto, acerto a favor do
 * vendedor (crédito ou saída de caixa) e o caixa automático.
 */
class FechamentoComPagamentoTest extends ApoioDoFechamento {

    private UsuarioDeTeste vendedor;
    private UUID cliente;

    @BeforeEach
    void umVendedor() throws Exception {
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 10 WHERE chave = 'limite_desconto_percentual'");
    }

    private static LocalDate hoje() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    private BigDecimal doAcerto(UUID acerto, String coluna) {
        return jdbc.queryForObject("SELECT " + coluna + " FROM corona.acertos WHERE id = ?", BigDecimal.class, acerto);
    }

    private record Cenario(UUID acerto, UUID maleta, UUID peca) {
    }

    /** Uma venda de R$ 50,00 na maleta do vendedor (líquido 35,00 com 30% de comissão) e o acerto pronto para fechar. */
    private Cenario liquidoDe35() throws Exception {
        UUID peca = peca("50.00", 50);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 5);
        vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto = iniciar(vendedor, null);
        bipar(acerto, peca, 4, "BOA");
        return new Cenario(acerto, maleta, peca);
    }

    /** Um acerto de líquido 0,00 (uma falta que fica para cobrar depois): nada a receber nem a pagar. */
    private Cenario liquidoZero() throws Exception {
        UUID peca = peca("50.00", 50);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 5);
        UUID acerto = iniciar(vendedor, null);
        bipar(acerto, peca, 4, "BOA");
        return new Cenario(acerto, maleta, peca);
    }

    private static Map<String, Object> recebimento(String forma, String valor) {
        return mapa("id", UUID.randomUUID(), "forma", forma, "valor", valor);
    }

    private static Map<String, Object> restante(int parcelas, LocalDate primeiro) {
        return mapa("parcelas", parcelas, "primeiroVencimento", primeiro.toString());
    }

    private Map<String, Object> pedido(UUID id, String liquido, Map<String, Object> pagamento) {
        return pedido(id, List.of(), liquido, pagamento);
    }

    private Map<String, Object> pedido(UUID id, List<?> faltas, String liquido, Map<String, Object> pagamento) {
        Map<String, Object> p = mapa("id", id, "faltas", faltas, "defeitos", List.of(), "cobrancas", List.of(), "pendenciasAntigas", List.of(),
                "liquidoMostrado", liquido);
        if (pagamento != null) {
            p.put("pagamento", pagamento);
        }
        return p;
    }

    private List<Map<String, Object>> contasDoAcerto(UUID acerto) {
        return jdbc.queryForList("""
                SELECT parcela, total_parcelas, valor, vencimento, forma_prevista, status FROM corona.contas_receber
                 WHERE devedor_tipo = 'VENDEDOR' AND origem_tipo = 'ACERTO' AND origem_id = ? ORDER BY parcela""", acerto);
    }

    private BigDecimal caixaDoVendedor() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(l.valor), 0) FROM corona.lancamentos_caixa l
                  LEFT JOIN corona.recebimentos_vendedor r ON l.origem_tipo = 'RECEBIMENTO_VENDEDOR' AND l.origem_id = r.id
                  LEFT JOIN corona.acertos a ON l.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND l.origem_id = a.id
                 WHERE r.vendedor_id = ? OR a.vendedor_id = ?""", BigDecimal.class, vendedor.vendedorId(), vendedor.vendedorId());
    }

    private int lancamentosDoVendedor() {
        return contar("""
                SELECT count(*) FROM corona.lancamentos_caixa l
                  LEFT JOIN corona.recebimentos_vendedor r ON l.origem_tipo = 'RECEBIMENTO_VENDEDOR' AND l.origem_id = r.id
                  LEFT JOIN corona.acertos a ON l.origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND l.origem_id = a.id
                 WHERE r.vendedor_id = ? OR a.vendedor_id = ?""", vendedor.vendedorId(), vendedor.vendedorId());
    }

    /** Tudo o que o fechamento do vendedor pode gravar de dinheiro: recebimentos, contas, baixas e lançamentos. */
    private int linhasDeDinheiro() {
        return contar("""
                SELECT (SELECT count(*) FROM corona.recebimentos_vendedor WHERE vendedor_id = ?)
                     + (SELECT count(*) FROM corona.contas_receber WHERE vendedor_id = ?)
                     + (SELECT count(*) FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id WHERE c.vendedor_id = ?)""",
                vendedor.vendedorId(), vendedor.vendedorId(), vendedor.vendedorId())
                + lancamentosDoVendedor();
    }

    /** A conciliação C2 do acerto (ADR-0012, item 17), na ponta do banco. */
    private void c2Confere(UUID acerto) {
        assertThat(contar("""
                SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'ACERTADO'
                   AND liquido - credito_usado = recebido_no_acerto - pago_ao_vendedor + ficou_devendo""", acerto)).isEqualTo(1);
    }

    // --- o exemplo do ADR-0012, item 18 ---

    @Test
    void oExemploDoAdrComDinheiroETransferencia() throws Exception {
        UUID a = peca("50.00", 60);
        UUID b = peca("33.33", 60);
        UUID c = peca("20.00", 60);
        configurarComissao(1, 30, 40);
        UUID m0 = maletaConferida(vendedor, semana(), c, 1);
        UUID venda0 = vender(vendedor, m0, cliente, c, 1, "20.00");
        UUID acerto0 = iniciar(vendedor, null);
        fechar(acerto0, pedido(UUID.randomUUID(), "12.00", mapa("restante", restante(1, hoje().plusDays(30))))).andExpect(status().isCreated());
        configurarComissao(700_000, 30, 40);
        postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", venda0, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "DINHEIRO", "itens", List.of(mapa("produtoId", c, "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isCreated());
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

        // A prévia com o pagamento: nada gravado.
        int linhasAntes = linhasDeDinheiro();
        JsonNode previa = corpoDe(postar(ACERTOS_DA_DONA + "/" + acerto + "/fechamento/previa", dona(),
                pedido(UUID.randomUUID(), List.of(decisaoDeFalta(a, "COBRAR_NO_ACERTO", null)), "127.83", mapa(
                        "recebimentos", List.of(recebimento("DINHEIRO", "60.00"), recebimento("PIX", "50.00")),
                        "restante", restante(1, hoje().plusDays(15))))).andExpect(status().isOk()));
        assertThat(new BigDecimal(previa.get("aPagarNoAcerto").asString())).isEqualByComparingTo("127.83");
        assertThat(new BigDecimal(previa.get("recebidoNoAcerto").asString())).isEqualByComparingTo("110.00");
        assertThat(new BigDecimal(previa.get("ficouDevendo").asString())).isEqualByComparingTo("17.83");
        assertThat(previa.get("parcelasDoRestante").size()).isEqualTo(1);
        // A conta de 12,00 do acerto antigo continua em aberto e aparece na prévia.
        assertThat(previa.get("contasAbertasDoVendedor").size()).isEqualTo(1);
        assertThat(linhasDeDinheiro()).isEqualTo(linhasAntes);

        UUID dinheiro = UUID.randomUUID();
        UUID pix = UUID.randomUUID();
        LocalDate vencimento = hoje().plusDays(15);
        fechar(acerto, pedido(UUID.randomUUID(), List.of(decisaoDeFalta(a, "COBRAR_NO_ACERTO", null)), "127.83", mapa(
                "recebimentos", List.of(mapa("id", dinheiro, "forma", "DINHEIRO", "valor", "60.00"),
                        mapa("id", pix, "forma", "PIX", "valor", "50.00")),
                "restante", restante(1, vencimento)))).andExpect(status().isCreated());

        assertThat(doAcerto(acerto, "liquido")).isEqualByComparingTo("127.83");
        assertThat(doAcerto(acerto, "recebido_no_acerto")).isEqualByComparingTo("110.00");
        assertThat(doAcerto(acerto, "ficou_devendo")).isEqualByComparingTo("17.83");
        assertThat(doAcerto(acerto, "credito_usado")).isEqualByComparingTo("0.00");
        assertThat(doAcerto(acerto, "pago_ao_vendedor")).isEqualByComparingTo("0.00");
        List<Map<String, Object>> contas = contasDoAcerto(acerto);
        assertThat(contas).hasSize(1);
        assertThat((BigDecimal) contas.get(0).get("valor")).isEqualByComparingTo("17.83");
        assertThat(contas.get(0).get("vencimento")).hasToString(vencimento.toString());
        // O caixa: uma entrada por recebimento, no fechamento, na forma de cada um.
        assertThat(contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE origem_id IN (?, ?)", dinheiro, pix)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT valor FROM corona.lancamentos_caixa WHERE origem_id = ?", BigDecimal.class, dinheiro))
                .isEqualByComparingTo("60.00");
        assertThat(jdbc.queryForObject("SELECT forma FROM corona.lancamentos_caixa WHERE origem_id = ?", String.class, pix)).isEqualTo("PIX");
        // O estoque e as maletas continuam como na 7.5.
        assertThat(estoqueAtual(a)).isEqualTo(centralA + 7);
        assertThat(statusDaMaleta(m1)).isEqualTo("ACERTADA");
        assertThat(saldoDaMaleta(m1, a)).isZero();
        c2Confere(acerto);
        // Nada em Dólar nem em Guarani nas tabelas novas (só Real).
        assertThat(contar("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema = 'corona'
                   AND table_name IN ('recebimentos_vendedor', 'lancamentos_caixa', 'categorias_caixa')
                   AND (column_name ILIKE '%usd%' OR column_name ILIKE '%pyg%' OR column_name ILIKE '%guarani%' OR column_name ILIKE '%dolar%')"""))
                .isZero();
    }

    // --- boleto e parcelas ---

    @Test
    void boletoViraContaESoEntraNoCaixaQuandoPago() throws Exception {
        Cenario c = liquidoDe35();
        LocalDate boleto = hoje().plusDays(10);
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "5.00")),
                "boleto", mapa("valor", "20.00", "vencimento", boleto.toString()), "restante", restante(2, hoje().plusDays(40)))))
                .andExpect(status().isCreated());
        List<Map<String, Object>> contas = contasDoAcerto(c.acerto());
        assertThat(contas).hasSize(3);
        assertThat(contas.get(0).get("forma_prevista")).isEqualTo("BOLETO");
        assertThat((BigDecimal) contas.get(0).get("valor")).isEqualByComparingTo("20.00");
        assertThat(contas.get(0).get("vencimento")).hasToString(boleto.toString());
        assertThat((BigDecimal) contas.get(1).get("valor")).isEqualByComparingTo("5.00");
        assertThat((BigDecimal) contas.get(2).get("valor")).isEqualByComparingTo("5.00");
        assertThat(((Number) contas.get(1).get("total_parcelas")).intValue()).isEqualTo(3);
        // No fechamento só o dinheiro entrou no caixa: o boleto ainda não.
        assertThat(lancamentosDoVendedor()).isEqualTo(1);
        assertThat(caixaDoVendedor()).isEqualByComparingTo("5.00");
        c2Confere(c.acerto());
        // O boleto pago: recebimento BOLETO sobre a conta do boleto, entrada de caixa na hora.
        UUID contaDoBoleto = jdbc.queryForObject("""
                SELECT id FROM corona.contas_receber WHERE origem_id = ? AND forma_prevista = 'BOLETO'""", UUID.class, c.acerto());
        postar("/api/dona/recebimentos-vendedor", dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "forma", "BOLETO",
                "valor", "20.00", "contaId", contaDoBoleto)).andExpect(status().isCreated());
        assertThat(caixaDoVendedor()).isEqualByComparingTo("25.00");
        assertThat(jdbc.queryForObject("SELECT status FROM corona.contas_receber WHERE id = ?", String.class, contaDoBoleto)).isEqualTo("PAGA");
    }

    @Test
    void restanteEmTresParcelasMensais() throws Exception {
        Cenario c = liquidoDe35();
        LocalDate primeiro = hoje().plusDays(20);
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("restante", restante(3, primeiro)))).andExpect(status().isCreated());
        List<Map<String, Object>> contas = contasDoAcerto(c.acerto());
        assertThat(contas).extracting(m -> ((BigDecimal) m.get("valor")).toPlainString()).containsExactly("11.67", "11.67", "11.66");
        assertThat(contas).extracting(m -> m.get("vencimento").toString()).containsExactly(primeiro.toString(), primeiro.plusMonths(1).toString(),
                primeiro.plusMonths(2).toString());
        assertThat(lancamentosDoVendedor()).isZero();
        assertThat(doAcerto(c.acerto(), "recebido_no_acerto")).isEqualByComparingTo("0.00");
        assertThat(doAcerto(c.acerto(), "ficou_devendo")).isEqualByComparingTo("35.00");
        c2Confere(c.acerto());
    }

    @Test
    void tudoPagoNaoGeraConta() throws Exception {
        Cenario c = liquidoDe35();
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "20.00"),
                recebimento("PIX", "15.00"))))).andExpect(status().isCreated());
        assertThat(contasDoAcerto(c.acerto())).isEmpty();
        assertThat(doAcerto(c.acerto(), "ficou_devendo")).isEqualByComparingTo("0.00");
        assertThat(doAcerto(c.acerto(), "recebido_no_acerto")).isEqualByComparingTo("35.00");
        assertThat(caixaDoVendedor()).isEqualByComparingTo("35.00");
        c2Confere(c.acerto());
    }

    @Test
    void liquidoZeroNaoTemNadaAReceber() throws Exception {
        Cenario c = liquidoZero();
        // A falta fica para cobrar depois: líquido 0,00.
        List<?> faltas = List.of(decisaoDeFalta(c.peca(), "COBRAR_DEPOIS", null));
        fechar(c.acerto(), pedido(UUID.randomUUID(), faltas, "0.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "1.00")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO"));
        fechar(c.acerto(), pedido(UUID.randomUUID(), faltas, "0.00", null)).andExpect(status().isCreated());
        assertThat(contasDoAcerto(c.acerto())).isEmpty();
        c2Confere(c.acerto());
    }

    // --- recusas: nada é gravado ---

    @Test
    void pagamentoErradoEhRecusadoENadaEhGravado() throws Exception {
        Cenario c = liquidoDe35();
        int antes = linhasDeDinheiro();
        // Sem o bloco: o que ficou devendo precisa de data.
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DATA_DO_RESTANTE_OBRIGATORIA"));
        // Recebimento sem o restante.
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "10.00")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("DATA_DO_RESTANTE_OBRIGATORIA"));
        // Recebido acima do que ele deve.
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "35.01")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO"));
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "20.00")),
                "boleto", mapa("valor", "15.01", "vencimento", hoje().plusDays(5).toString())))).andExpect(status().isConflict());
        // Formas que não existem no acerto: cheque, cartão e boleto como recebimento.
        for (String forma : List.of("CHEQUE", "CARTAO", "BOLETO", "CREDITO")) {
            fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento(forma, "10.00")),
                    "restante", restante(1, hoje().plusDays(5))))).andExpect(status().isBadRequest());
        }
        // Valor com três casas, zero e negativo.
        for (String valor : List.of("10.005", "0", "-5.00")) {
            fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", valor)),
                    "restante", restante(1, hoje().plusDays(5))))).andExpect(status().isBadRequest());
        }
        // Pagar o vendedor quando é ele quem deve.
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("aFavorDoVendedor", "PAGAR_NA_HORA", "formaDoPagamento", "DINHEIRO")))
                .andExpect(status().isConflict());
        // Data no passado e parcelas demais.
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("restante", restante(1, hoje().minusDays(1))))).andExpect(status().isBadRequest());
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("restante", restante(25, hoje().plusDays(1))))).andExpect(status().isBadRequest());
        // O mesmo recebimento duas vezes.
        UUID repetido = UUID.randomUUID();
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(mapa("id", repetido, "forma", "DINHEIRO", "valor", "1.00"),
                mapa("id", repetido, "forma", "PIX", "valor", "1.00")), "restante", restante(1, hoje().plusDays(5))))).andExpect(status().isBadRequest());
        assertThat(linhasDeDinheiro()).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", c.acerto())).isEqualTo(1);
        assertThat(statusDaMaleta(c.maleta())).isEqualTo("EM_ACERTO");
    }

    @Test
    void falhaDepoisDosLancamentosDeCaixaNaoDeixaNadaGravado() throws Exception {
        Cenario c = liquidoDe35();
        int antes = linhasDeDinheiro();
        // A conta antiga que não existe derruba o fechamento inteiro, depois de gravar o recebimento e o lançamento do acerto.
        fechar(c.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "10.00")),
                "restante", restante(1, hoje().plusDays(5)),
                "contasAntigas", List.of(mapa("id", UUID.randomUUID(), "contaId", UUID.randomUUID(), "forma", "DINHEIRO", "valor", "1.00")))))
                .andExpect(status().isNotFound());
        assertThat(linhasDeDinheiro()).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", c.acerto())).isEqualTo(1);
        assertThat(statusDaMaleta(c.maleta())).isEqualTo("EM_ACERTO");
    }

    // --- conta antiga paga no acerto ---

    @Test
    void contaAntigaPagaNoAcertoNaoMudaOsTotais() throws Exception {
        Cenario primeiro = liquidoDe35();
        fechar(primeiro.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("restante", restante(1, hoje().plusDays(10)))))
                .andExpect(status().isCreated());
        UUID contaAntiga = jdbc.queryForObject("SELECT id FROM corona.contas_receber WHERE origem_id = ?", UUID.class, primeiro.acerto());
        Cenario segundo = liquidoDe35();
        UUID pagamentoAntigo = UUID.randomUUID();
        fechar(segundo.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("restante", restante(1, hoje().plusDays(10)),
                "contasAntigas", List.of(mapa("id", pagamentoAntigo, "contaId", contaAntiga, "forma", "PIX", "valor", "10.00")))))
                .andExpect(status().isCreated());
        // Os totais do acerto são só do acerto.
        assertThat(doAcerto(segundo.acerto(), "recebido_no_acerto")).isEqualByComparingTo("0.00");
        assertThat(doAcerto(segundo.acerto(), "ficou_devendo")).isEqualByComparingTo("35.00");
        assertThat(jdbc.queryForObject("SELECT valor_pago FROM corona.contas_receber WHERE id = ?", BigDecimal.class, contaAntiga))
                .isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT aplicado_em || '/' || momento FROM corona.recebimentos_vendedor WHERE id = ?", String.class,
                pagamentoAntigo)).isEqualTo("CONTAS/ACERTO");
        assertThat(caixaDoVendedor()).isEqualByComparingTo("10.00");
        c2Confere(segundo.acerto());
        // Acima do que a conta antiga deve: 409 e nada gravado.
        Cenario terceiro = liquidoDe35();
        int antes = linhasDeDinheiro();
        fechar(terceiro.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("restante", restante(1, hoje().plusDays(10)),
                "contasAntigas", List.of(mapa("id", UUID.randomUUID(), "contaId", contaAntiga, "forma", "DINHEIRO", "valor", "25.01")))))
                .andExpect(status().isConflict());
        assertThat(linhasDeDinheiro()).isEqualTo(antes);
    }

    // --- a favor do vendedor ---

    /** Um acerto fechado e uma devolução depois: o próximo acerto tem líquido -35,00. Devolve o acerto novo, já com a peça contada. */
    private UUID acertoAFavor() throws Exception {
        UUID peca = peca("50.00", 20);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 3);
        UUID venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto0 = iniciar(vendedor, null);
        bipar(acerto0, peca, 2, "BOA");
        fechar(acerto0, pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "35.00")))))
                .andExpect(status().isCreated());
        postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "DINHEIRO", "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA"))))
                .andExpect(status().isCreated());
        UUID acerto = UUID.randomUUID();
        postar(ACERTOS_DA_DONA, dona(), mapa("id", acerto, "vendedorId", vendedor.vendedorId(), "semanaId", semana())).andExpect(status().isCreated());
        bipar(acerto, peca, 1, "BOA");
        return acerto;
    }

    @Test
    void aFavorDoVendedorFicaDeCreditoEAbateNoProximoAcerto() throws Exception {
        UUID acerto = acertoAFavor();
        int lancamentosAntes = lancamentosDoVendedor();
        // O padrão é crédito: nada no caixa.
        fechar(acerto, pedido(UUID.randomUUID(), "-35.00", null)).andExpect(status().isCreated());
        assertThat(doAcerto(acerto, "ficou_devendo")).isEqualByComparingTo("-35.00");
        assertThat(doAcerto(acerto, "pago_ao_vendedor")).isEqualByComparingTo("0.00");
        assertThat(lancamentosDoVendedor()).isEqualTo(lancamentosAntes);
        assertThat(contasDoAcerto(acerto)).isEmpty();
        c2Confere(acerto);

        // O próximo acerto (líquido 35,00) usa o crédito inteiro: nada a pagar.
        Cenario proximo = liquidoDe35();
        JsonNode previa = previa(proximo.acerto());
        assertThat(new BigDecimal(previa.get("creditoDisponivel").asString())).isEqualByComparingTo("35.00");
        assertThat(new BigDecimal(previa.get("aPagarNoAcerto").asString())).isEqualByComparingTo("0.00");
        fechar(proximo.acerto(), pedido(UUID.randomUUID(), "35.00", null)).andExpect(status().isCreated());
        assertThat(doAcerto(proximo.acerto(), "credito_usado")).isEqualByComparingTo("35.00");
        assertThat(doAcerto(proximo.acerto(), "ficou_devendo")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT credito_do_acerto_id FROM corona.acertos WHERE id = ?", UUID.class, proximo.acerto()))
                .isEqualTo(acerto);
        assertThat(contasDoAcerto(proximo.acerto())).isEmpty();
        c2Confere(proximo.acerto());

        // Crédito já usado: o acerto seguinte (líquido 35,00) paga tudo.
        Cenario depois = liquidoDe35();
        assertThat(new BigDecimal(previa(depois.acerto()).get("creditoDisponivel").asString())).isEqualByComparingTo("0.00");
        fechar(depois.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(recebimento("DINHEIRO", "35.00")))))
                .andExpect(status().isCreated());
        assertThat(doAcerto(depois.acerto(), "credito_usado")).isEqualByComparingTo("0.00");
    }

    @Test
    void creditoMaiorQueOProximoLiquidoContinuaComoCredito() throws Exception {
        UUID acerto = acertoAFavor();
        fechar(acerto, pedido(UUID.randomUUID(), "-35.00", null)).andExpect(status().isCreated());
        // Um acerto de líquido 0,00: abate o crédito de 35,00 e sobra 35,00 de crédito.
        Cenario zero = liquidoZero();
        fechar(zero.acerto(), pedido(UUID.randomUUID(), List.of(decisaoDeFalta(zero.peca(), "COBRAR_DEPOIS", null)), "0.00", null))
                .andExpect(status().isCreated());
        assertThat(doAcerto(zero.acerto(), "credito_usado")).isEqualByComparingTo("35.00");
        assertThat(doAcerto(zero.acerto(), "ficou_devendo")).isEqualByComparingTo("-35.00");
        c2Confere(zero.acerto());
        // E o crédito que sobrou ainda abate o acerto seguinte, uma vez só.
        Cenario depois = liquidoDe35();
        fechar(depois.acerto(), pedido(UUID.randomUUID(), "35.00", null)).andExpect(status().isCreated());
        assertThat(doAcerto(depois.acerto(), "credito_usado")).isEqualByComparingTo("35.00");
        assertThat(jdbc.queryForObject("SELECT credito_do_acerto_id FROM corona.acertos WHERE id = ?", UUID.class, depois.acerto()))
                .isEqualTo(zero.acerto());
    }

    @Test
    void aFavorDoVendedorPagoNaHoraSaiDoCaixa() throws Exception {
        UUID acerto = acertoAFavor();
        int lancamentosAntes = lancamentosDoVendedor();
        // Sem a forma do pagamento, ou com recebimento junto: recusado.
        fechar(acerto, pedido(UUID.randomUUID(), "-35.00", mapa("aFavorDoVendedor", "PAGAR_NA_HORA"))).andExpect(status().isBadRequest());
        fechar(acerto, pedido(UUID.randomUUID(), "-35.00", mapa("aFavorDoVendedor", "PAGAR_NA_HORA", "formaDoPagamento", "BOLETO")))
                .andExpect(status().isBadRequest());
        fechar(acerto, pedido(UUID.randomUUID(), "-35.00", mapa("aFavorDoVendedor", "PAGAR_NA_HORA", "formaDoPagamento", "PIX",
                "recebimentos", List.of(recebimento("DINHEIRO", "1.00"))))).andExpect(status().isConflict());
        assertThat(lancamentosDoVendedor()).isEqualTo(lancamentosAntes);
        fechar(acerto, pedido(UUID.randomUUID(), "-35.00", mapa("aFavorDoVendedor", "PAGAR_NA_HORA", "formaDoPagamento", "PIX")))
                .andExpect(status().isCreated());
        assertThat(doAcerto(acerto, "pago_ao_vendedor")).isEqualByComparingTo("35.00");
        assertThat(doAcerto(acerto, "ficou_devendo")).isEqualByComparingTo("0.00");
        assertThat(lancamentosDoVendedor()).isEqualTo(lancamentosAntes + 1);
        Map<String, Object> saida = jdbc.queryForMap("""
                SELECT tipo, valor, forma FROM corona.lancamentos_caixa WHERE origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR' AND origem_id = ?""", acerto);
        assertThat(saida.get("tipo")).isEqualTo("SAIDA");
        assertThat((BigDecimal) saida.get("valor")).isEqualByComparingTo("-35.00");
        assertThat(saida.get("forma")).isEqualTo("PIX");
        c2Confere(acerto);
    }

    // --- idempotência ---

    @Test
    void mesmoIdComOMesmoPagamentoNaoRepeteNadaEOutroPagamentoDa409() throws Exception {
        Cenario c = liquidoDe35();
        UUID id = UUID.randomUUID();
        UUID recebimentoId = UUID.randomUUID();
        Map<String, Object> pagamento = mapa("recebimentos", List.of(mapa("id", recebimentoId, "forma", "DINHEIRO", "valor", "20.00")),
                "restante", restante(1, hoje().plusDays(30)));
        fechar(c.acerto(), pedido(id, "35.00", pagamento)).andExpect(status().isCreated());
        int linhas = linhasDeDinheiro();
        fechar(c.acerto(), pedido(id, "35.00", pagamento)).andExpect(status().isOk());
        assertThat(linhasDeDinheiro()).isEqualTo(linhas);
        // Outro pagamento com o mesmo id de fechamento: 409.
        fechar(c.acerto(), pedido(id, "35.00", mapa("recebimentos", List.of(mapa("id", recebimentoId, "forma", "DINHEIRO", "valor", "25.00")),
                "restante", restante(1, hoje().plusDays(30))))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FECHAMENTO_JA_REGISTRADO_DIFERENTE"));
        assertThat(linhasDeDinheiro()).isEqualTo(linhas);
        // Um id de recebimento já usado em outro fechamento: 409 e nada gravado.
        Cenario outro = liquidoDe35();
        int antes = linhasDeDinheiro();
        fechar(outro.acerto(), pedido(UUID.randomUUID(), "35.00", mapa("recebimentos", List.of(mapa("id", recebimentoId, "forma", "DINHEIRO",
                "valor", "5.00")), "restante", restante(1, hoje().plusDays(30))))).andExpect(status().isConflict());
        assertThat(linhasDeDinheiro()).isEqualTo(antes);
    }

    // --- a prévia mostra o crédito e as contas abertas ---

    @Test
    void aPreviaNaoGravaENaoPedePagamento() throws Exception {
        Cenario c = liquidoDe35();
        int antes = linhasDeDinheiro();
        JsonNode previa = previa(c.acerto());
        assertThat(new BigDecimal(previa.get("aPagarNoAcerto").asString())).isEqualByComparingTo("35.00");
        assertThat(new BigDecimal(previa.get("recebidoNoAcerto").asString())).isEqualByComparingTo("0.00");
        assertThat(previa.get("restanteSemData").asBoolean()).isTrue();
        assertThat(linhasDeDinheiro()).isEqualTo(antes);
    }
}
