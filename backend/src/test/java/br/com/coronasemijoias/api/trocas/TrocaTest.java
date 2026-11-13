package br.com.coronasemijoias.api.trocas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ValorDaDevolucao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Troca (tarefa 6.5; ADR-0014, itens 8, 9, 11 e 12; plano aprovado no PR #129): o dinheiro passa pelo crédito do cliente
 * (reembolso CREDITO na venda A, pagamento CREDITO na venda B), B mais cara, mais barata e igual, várias peças com
 * centavos, peça com defeito e maleta acertada, tudo ou nada, prazo e autorização, fora do limite da semana, cancelar a
 * troca, idempotência, conciliações, permissões, nada de custo, as segundas travas do banco (com a conferência no fim
 * da transação traduzida para 409) e o pedido malformado. Escritos antes do código.
 */
class TrocaTest extends ApoioDasTrocas {

    private record Cenario(UsuarioDeTeste v, UUID cliente, UUID a, UUID b, UUID maleta, UUID vendaA) {
    }

    /** Vendedor com uma maleta conferida (A e B, 5 de cada) e a venda de uma peça A, paga em dinheiro. */
    private Cenario cenario(String precoA, String precoB) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca(precoA, 10);
        UUID b = peca(precoB, 10);
        UUID maleta = maleta(v, a, 5, b, 5);
        UUID vendaA = vender(v, maleta, cliente, List.of(linha(a, 1)), List.of(pagamento(precoA, "DINHEIRO")));
        return new Cenario(v, cliente, a, b, maleta, vendaA);
    }

    private UUID vendaNova(UUID troca) {
        return jdbc.queryForObject("SELECT venda_nova_id FROM corona.trocas WHERE id = ?", UUID.class, troca);
    }

    private UUID devolucaoDa(UUID troca) {
        return jdbc.queryForObject("SELECT devolucao_id FROM corona.trocas WHERE id = ?", UUID.class, troca);
    }

    // --- 1. B mais cara ---

    @Test
    void bMaisCaraOCreditoPagaAParteDeAEOClientePagaADiferenca() throws Exception {
        Cenario c = cenario("100.00", "150.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of(pagamento("50.00", "DINHEIRO")));
        UUID troca = id(t);
        assertThat(t.get("situacao").asString()).isEqualTo("ATIVA");
        assertThat(dec(t.get("valorDevolvido"))).isEqualByComparingTo("100.00");
        assertThat(dec(t.get("totalDaVendaNova"))).isEqualByComparingTo("150.00");
        assertThat(dec(t.get("diferenca"))).isEqualByComparingTo("50.00");
        assertThat(dec(t.get("creditoEmAberto"))).isEqualByComparingTo("0.00");
        assertThat(t.get("vendaOrigemId").asString()).isEqualTo(c.vendaA().toString());
        assertThat(t.get("voltaram")).hasSize(1);
        assertThat(t.get("sairam")).hasSize(1);
        assertThat(t.get("voltaram").get(0).get("produto").get("id").asString()).isEqualTo(c.a().toString());
        assertThat(t.get("sairam").get(0).get("produto").get("id").asString()).isEqualTo(c.b().toString());

        UUID devolucao = devolucaoDa(troca);
        UUID vendaB = vendaNova(troca);
        UUID credito = UUID.fromString(t.get("creditoId").asString());
        Map<String, Object> d = jdbc.queryForMap(
                "SELECT destino, situacao, valor_devolvido, valor_reembolsado, valor_em_credito FROM corona.devolucoes WHERE id = ?",
                devolucao);
        assertThat(d.get("destino")).isEqualTo("TROCA");
        assertThat(d.get("situacao")).isEqualTo("ACEITA");
        assertThat((BigDecimal) d.get("valor_devolvido")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) d.get("valor_reembolsado")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) d.get("valor_em_credito")).isEqualByComparingTo("100.00");

        // Venda A: o reembolso é em crédito, nenhum dinheiro sai.
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO' AND forma = 'CREDITO'
                   AND valor = -100.00 AND devolucao_id = ? AND credito_id = ?""", c.vendaA(), devolucao, credito)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND forma <> 'CREDITO' AND tipo <> 'PAGAMENTO'",
                c.vendaA())).isZero();

        // O crédito nasce da troca e é usado inteiro na venda B.
        Map<String, Object> cr = credito(credito);
        assertThat(cr.get("origem")).isEqualTo("TROCA");
        assertThat(cr.get("troca_id")).isEqualTo(troca);
        assertThat(cr.get("devolucao_id")).isEqualTo(devolucao);
        assertThat(cr.get("cliente_id")).isEqualTo(c.cliente());
        assertThat(cr.get("vendedor_id")).isEqualTo(c.v().vendedorId());
        assertThat((BigDecimal) cr.get("valor")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) cr.get("valor_utilizado")).isEqualByComparingTo("100.00");
        assertThat(cr.get("situacao")).isEqualTo("UTILIZADO");
        assertThat(somaDosUsos(credito)).isEqualByComparingTo("100.00");

        // Venda B: pagamento CREDITO de 100,00 (o id do app) e 50,00 em dinheiro.
        assertThat(jdbc.queryForObject("SELECT troca_id FROM corona.vendas WHERE id = ?", UUID.class, vendaB)).isEqualTo(troca);
        assertThat(jdbc.queryForObject("SELECT cliente_id FROM corona.vendas WHERE id = ?", UUID.class, vendaB)).isEqualTo(c.cliente());
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO' AND forma = 'CREDITO'
                   AND valor = 100.00 AND credito_id = ? AND id = ?""", vendaB, credito,
                jdbc.queryForObject("SELECT pagamento_id FROM corona.usos_credito WHERE credito_id = ? AND tipo = 'USO'", UUID.class,
                        credito))).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'PAGAMENTO' AND forma = 'DINHEIRO'
                   AND valor = 50.00""", vendaB)).isEqualTo(1);

        // Na maleta: A entra como TROCA_ENTRADA, B sai como TROCA_SAIDA.
        assertThat(contar("""
                SELECT count(*) FROM corona.movimentos_remessa WHERE tipo = 'TROCA_ENTRADA' AND quantidade = 1 AND produto_id = ?
                   AND devolucao_id = ?""", c.a(), devolucao)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.movimentos_remessa WHERE tipo = 'TROCA_SAIDA' AND quantidade = -1 AND produto_id = ?
                   AND venda_id = ?""", c.b(), vendaB)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE venda_id = ? AND tipo = 'VENDA'", vendaB)).isZero();
        assertThat(saldo(c.maleta(), c.a())).isEqualTo(5);
        assertThat(saldo(c.maleta(), c.b())).isEqualTo(4);

        // Nada a receber em nenhuma das duas; a venda B mostra o pagamento em crédito e a troca.
        JsonNode lidaA = corpoDe(ler(VENDAS + "/" + c.vendaA(), como(c.v())).andExpect(status().isOk()));
        assertThat(dec(lidaA.get("aReceber"))).isEqualByComparingTo("0");
        JsonNode lidaB = corpoDe(ler(VENDAS + "/" + vendaB, como(c.v())).andExpect(status().isOk()));
        assertThat(dec(lidaB.get("aReceber"))).isEqualByComparingTo("0");
        assertThat(lidaB.get("trocaId").asString()).isEqualTo(troca.toString());
        assertThat(lidaB.get("pagamentos").toString()).contains("CREDITO").contains(credito.toString());
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'TROCA_REGISTRADA' AND registro_id = ?", troca))
                .isEqualTo(1);
    }

    @Test
    void bMaisCaraComMenosQueADiferencaPagaFicaAPrazo() throws Exception {
        Cenario c = cenario("100.00", "150.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of(pagamento("20.00", "PIX")));
        UUID vendaB = vendaNova(id(t));
        JsonNode lidaB = corpoDe(ler(VENDAS + "/" + vendaB, como(c.v())).andExpect(status().isOk()));
        assertThat(dec(lidaB.get("aReceber"))).isEqualByComparingTo("30.00");
        assertThat(dec(t.get("diferenca"))).isEqualByComparingTo("50.00");
    }

    // --- 2. B mais barata ---

    @Test
    void bMaisBarataASobraFicaDeCreditoEmAbertoSemDinheiro() throws Exception {
        Cenario c = cenario("100.00", "60.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        UUID credito = UUID.fromString(t.get("creditoId").asString());
        assertThat(dec(t.get("diferenca"))).isEqualByComparingTo("-40.00");
        assertThat(dec(t.get("creditoEmAberto"))).isEqualByComparingTo("40.00");
        Map<String, Object> cr = credito(credito);
        assertThat((BigDecimal) cr.get("valor_utilizado")).isEqualByComparingTo("60.00");
        assertThat(cr.get("situacao")).isEqualTo("ABERTO");
        UUID vendaB = vendaNova(id(t));
        assertThat(somaDosPagamentos(vendaB)).isEqualByComparingTo("60.00");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND forma <> 'CREDITO'", vendaB)).isZero();

        // Com B mais barata, mandar pagamento do resto é recusado (não há diferença a pagar).
        Cenario d = cenario("100.00", "60.00");
        Map<String, Integer> antes = contagens();
        JsonNode erro = corpoDe(postar(TROCAS, como(d.v()), pedidoDeTroca(d.vendaA(), "ARREPENDIMENTO",
                List.of(devolvida(d.a(), 1, "BOA")), d.maleta(), List.of(linha(d.b(), 1)), List.of(pagamento("10.00", "DINHEIRO"))))
                .andExpect(status().isBadRequest()));
        assertThat(erro.toString()).contains("não há diferença a pagar");
        assertThat(contagens()).isEqualTo(antes);
    }

    // --- 3. igual ---

    @Test
    void mesmoValorNaoTemDiferencaEOCreditoFicaUsado() throws Exception {
        Cenario c = cenario("80.00", "80.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        assertThat(dec(t.get("diferenca"))).isEqualByComparingTo("0.00");
        assertThat(dec(t.get("creditoEmAberto"))).isEqualByComparingTo("0.00");
        assertThat(credito(UUID.fromString(t.get("creditoId").asString())).get("situacao")).isEqualTo("UTILIZADO");
    }

    // --- 4. várias peças, centavos ---

    @Test
    void variasPecasComDescontoQueNaoDivideExatoValemCentavoACentavo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID anel = peca("10.00", 10);
        UUID brinco = peca("30.00", 10);
        UUID b1 = peca("12.00", 10);
        UUID b2 = peca("7.50", 10);
        UUID b3 = peca("40.00", 10);
        limiteDeDesconto(10);
        UUID maleta = maleta(v, anel, 5, brinco, 5, b1, 5, b2, 5, b3, 5);
        UUID vendaA = vender(v, maleta, cliente, List.of(linhaComDesconto(anel, 3, "0.10"), linha(brinco, 1)),
                List.of(pagamento("59.90", "DINHEIRO")));
        limiteDeDesconto(0);

        BigDecimal esperadoAnel = ValorDaDevolucao.calcular(new BigDecimal("29.90"), 3, 0, BigDecimal.ZERO, 2);
        BigDecimal esperado = esperadoAnel.add(new BigDecimal("30.00"));
        BigDecimal totalB = new BigDecimal("71.50");
        // O cliente paga exatamente a diferença: o servidor a calcula igual, centavo a centavo.
        JsonNode t = trocar(v, pedidoDeTroca(vendaA, "TAMANHO", List.of(devolvida(anel, 2, "BOA"), devolvida(brinco, 1, "BOA")),
                maleta, List.of(linha(b1, 2), linha(b2, 1), linha(b3, 1)),
                List.of(pagamento(totalB.subtract(esperado).toPlainString(), "PIX"))));
        assertThat(dec(t.get("valorDevolvido"))).isEqualByComparingTo(esperado);
        assertThat(dec(t.get("totalDaVendaNova"))).isEqualByComparingTo(totalB);
        assertThat(dec(t.get("diferenca"))).isEqualByComparingTo(totalB.subtract(esperado));
        UUID devolucao = devolucaoDa(id(t));
        assertThat(valor("SELECT sum(valor) FROM corona.itens_devolucao WHERE devolucao_id = ?", devolucao))
                .isEqualByComparingTo(esperado);
        assertThat(valor("SELECT valor FROM corona.creditos_cliente WHERE id = ?", UUID.fromString(t.get("creditoId").asString())))
                .isEqualByComparingTo(esperado);
        assertThat(saldo(maleta, anel)).isEqualTo(4);
        assertThat(saldo(maleta, b1)).isEqualTo(3);
        assertThat(dec(corpoDe(ler(VENDAS + "/" + vendaNova(id(t)), como(v))).get("aReceber"))).isEqualByComparingTo("0");
    }

    // --- 5. peça com defeito, maleta acertada e duas maletas ---

    @Test
    void pecaComDefeitoVaiParaALojaEBSaiDaMaleta() throws Exception {
        Cenario c = cenario("100.00", "100.00");
        JsonNode t = trocar(c.v(), pedidoDeTroca(c.vendaA(), "DEFEITO", List.of(devolvida(c.a(), 1, "DEFEITO")), c.maleta(),
                List.of(linha(c.b(), 1)), List.of()));
        UUID devolucao = devolucaoDa(id(t));
        assertThat(jdbc.queryForObject("SELECT destino_peca FROM corona.itens_devolucao WHERE devolucao_id = ?", String.class,
                devolucao)).isEqualTo("LOJA");
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE devolucao_id = ?", devolucao)).isZero();
        assertThat(saldo(c.maleta(), c.a())).isEqualTo(4);
        assertThat(saldo(c.maleta(), c.b())).isEqualTo(4);
        assertThat(t.get("voltaram").get(0).get("destino").asString()).isEqualTo("LOJA");
    }

    @Test
    void aDeMaletaAcertadaEBDaMaletaNova() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("50.00", 10);
        UUID b = peca("70.00", 10);
        // A maleta vende tudo: acertada sem sobra, ela não deixa diferença na conciliação do banco que os testes dividem.
        UUID antiga = maleta(v, a, 1);
        UUID vendaA = vender(v, antiga, cliente, List.of(linha(a, 1)), List.of(pagamento("50.00", "PIX")));
        jdbc.update("UPDATE corona.remessas SET status = 'ACERTADA' WHERE id = ?", antiga);
        UUID nova = maleta(v, b, 3);

        JsonNode t = trocar(v, vendaA, a, nova, b, List.of(pagamento("20.00", "PIX")));
        UUID devolucao = devolucaoDa(id(t));
        assertThat(jdbc.queryForObject("SELECT destino_peca FROM corona.itens_devolucao WHERE devolucao_id = ?", String.class,
                devolucao)).isEqualTo("LOJA");
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ? AND devolucao_id IS NOT NULL", antiga))
                .isZero();
        assertThat(saldo(nova, b)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT remessa_id FROM corona.vendas WHERE id = ?", UUID.class, vendaNova(id(t))))
                .isEqualTo(nova);
    }

    // --- 6. tudo ou nada ---

    @Test
    void qualquerRecusaNoMeioDesfazTudo() throws Exception {
        // B sem saldo.
        Cenario c = cenario("100.00", "120.00");
        Map<String, Integer> antes = contagens();
        assertThat(codigoDe(postar(TROCAS, como(c.v()), pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), c.maleta(), List.of(linha(c.b(), 6)), List.of()))
                .andExpect(status().isConflict()))).isEqualTo("VENDA_SEM_SALDO");
        assertThat(contagens()).isEqualTo(antes);

        // Desconto de B acima do limite (a dona não liberou desconto).
        assertThat(codigoDe(postar(TROCAS, como(c.v()), pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), c.maleta(), List.of(linhaComDesconto(c.b(), 1, "5.00")), List.of()))
                .andExpect(status().isConflict()))).isEqualTo("DESCONTO_ACIMA_DO_LIMITE");
        assertThat(contagens()).isEqualTo(antes);

        // Maleta de B ainda não conferida.
        UUID naoConferida = maletaEnviada(c.v(), c.b(), 2);
        antes = contagens();
        assertThat(codigoDe(postar(TROCAS, como(c.v()), pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), naoConferida, List.of(linha(c.b(), 1)), List.of()))
                .andExpect(status().isConflict()))).isEqualTo("MALETA_NAO_CONFERIDA");
        assertThat(contagens()).isEqualTo(antes);

        // A fora do prazo, na rota.
        UUID velha = venderEm(c.v(), c.maleta(), c.cliente(), c.a(), 1, "100.00", diasAtras(10));
        antes = contagens();
        assertThat(codigoDe(postar(TROCAS, como(c.v()), pedidoDeTroca(velha, "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), c.maleta(), List.of(linha(c.b(), 1)), List.of()))
                .andExpect(status().isConflict()))).isEqualTo("GARANTIA_VENCIDA");
        assertThat(contagens()).isEqualTo(antes);

        // Venda A a prazo.
        UUID aPrazo = vender(c.v(), c.maleta(), c.cliente(), List.of(linha(c.a(), 1)), List.of(pagamento("30.00", "DINHEIRO")));
        antes = contagens();
        assertThat(codigoDe(postar(TROCAS, como(c.v()), pedidoDeTroca(aPrazo, "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), c.maleta(), List.of(linha(c.b(), 1)), List.of()))
                .andExpect(status().isConflict()))).isEqualTo("VENDA_A_PRAZO_SEM_DEVOLUCAO");
        assertThat(contagens()).isEqualTo(antes);

        // Maleta de A em acerto.
        UUID outra = maleta(c.v(), c.b(), 3);
        jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO' WHERE id = ?", c.maleta());
        antes = contagens();
        assertThat(codigoDe(postar(TROCAS, como(c.v()), pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), outra, List.of(linha(c.b(), 1)), List.of()))
                .andExpect(status().isConflict()))).isEqualTo("MALETA_EM_ACERTO");
        assertThat(contagens()).isEqualTo(antes);
    }

    // --- 7. prazo e autorização ---

    @Test
    void prazoContaEmDiasDeBrasiliaComoNaDevolucao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("40.00", 10);
        UUID b = peca("40.00", 10);
        UUID maleta = maleta(v, a, 5, b, 5);
        LocalDate dia = LocalDate.ofInstant(relogio.instant(), BRASILIA).minusDays(30);
        UUID venda1 = venderEm(v, maleta, cliente, a, 1, "40.00", brasilia(dia + "T10:00:00"));
        UUID venda2 = venderEm(v, maleta, cliente, a, 1, "40.00", brasilia(dia + "T10:00:00"));

        // Último dia do prazo (7), às 23:30 de Brasília (já é o dia seguinte em UTC): passa.
        trocarPeloServico(v, pedidoDoServico(venda1, "ARREPENDIMENTO", List.of(new PecaDoPedido(a, 1, "BOA")), null, maleta,
                List.of(new LinhaDoPedido(b, 1, null)), List.of()), brasilia(dia.plusDays(7) + "T23:30:00"), ModoDaDevolucao.ONLINE);
        // No dia seguinte, 00:30: fora do prazo.
        assertThatThrownBy(() -> trocarPeloServico(v, pedidoDoServico(venda2, "ARREPENDIMENTO", List.of(new PecaDoPedido(a, 1, "BOA")),
                null, maleta, List.of(new LinhaDoPedido(b, 1, null)), List.of()), brasilia(dia.plusDays(8) + "T00:30:00"),
                ModoDaDevolucao.ONLINE)).satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("GARANTIA_VENCIDA"));
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda2)).isZero();
    }

    @Test
    void comAutorizacaoAprovadaPassaForaDoPrazoEAutorizacaoFicaUsada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("40.00", 10);
        UUID b = peca("50.00", 10);
        UUID maleta = maleta(v, a, 5, b, 5);
        UUID vendaA = venderEm(v, maleta, cliente, a, 1, "40.00", diasAtras(10));
        UUID autorizacao = UUID.randomUUID();
        postar("/api/vendedor/autorizacoes", como(v), mapa("id", autorizacao, "vendaId", vendaA, "motivo", "ARREPENDIMENTO",
                "itens", List.of(devolvida(a, 1, "BOA")))).andExpect(status().isCreated());
        postar("/api/dona/autorizacoes/" + autorizacao + "/aprovar", dona(), null).andExpect(status().isOk());

        Map<String, Object> pedido = pedidoDeTroca(vendaA, "ARREPENDIMENTO", List.of(devolvida(a, 1, "BOA")), maleta,
                List.of(linha(b, 1)), List.of(pagamento("10.00", "DINHEIRO")));
        devolucaoDo(pedido).put("autorizacaoId", autorizacao);
        JsonNode t = trocar(v, pedido);
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.autorizacoes_excecao WHERE id = ?", String.class, autorizacao))
                .isEqualTo("USADA");
        assertThat(jdbc.queryForObject("SELECT autorizacao_id FROM corona.devolucoes WHERE id = ?", UUID.class, devolucaoDa(id(t))))
                .isEqualTo(autorizacao);
    }

    @Test
    void peloEnvioForaDoPrazoADevolucaoDaTrocaVaiParaRevisaoEATrocaEGravada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("40.00", 10);
        UUID b = peca("40.00", 10);
        UUID maleta = maleta(v, a, 5, b, 5);
        UUID vendaA = venderEm(v, maleta, cliente, a, 1, "40.00", diasAtras(10));
        ServicoDeTrocas.Resultado r = trocarPeloServico(v, pedidoDoServico(vendaA, "ARREPENDIMENTO",
                List.of(new PecaDoPedido(a, 1, "BOA")), null, maleta, List.of(new LinhaDoPedido(b, 1, null)), List.of()),
                null, ModoDaDevolucao.ENVIO);
        assertThat(r.jaExistia()).isFalse();
        Map<String, Object> d = jdbc.queryForMap("SELECT situacao, revisao_fora_do_prazo FROM corona.devolucoes WHERE id = ?",
                devolucaoDa(r.trocaId()));
        assertThat(d.get("situacao")).isEqualTo("EM_REVISAO");
        assertThat(d.get("revisao_fora_do_prazo")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.trocas WHERE id = ?", String.class, r.trocaId()))
                .isEqualTo("ATIVA");
    }

    // --- 8. sem dinheiro, sem limite ---

    @Test
    void trocaNaoPassaPeloLimiteDaSemanaNemPeloValorDeAprovacao() throws Exception {
        Cenario c = cenario("100.00", "100.00");
        limiteSemanal("10.00");
        valorDeAprovacao("10.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        Map<String, Object> d = jdbc.queryForMap("""
                SELECT situacao, revisao_acima_do_limite_semanal, revisao_acima_do_valor_de_aprovacao
                  FROM corona.devolucoes WHERE id = ?""", devolucaoDa(id(t)));
        assertThat(d.get("situacao")).isEqualTo("ACEITA");
        assertThat(d.get("revisao_acima_do_limite_semanal")).isEqualTo(false);

        // A devolução em dinheiro da mesma semana conta só o dinheiro: 5,00 cabem no limite de 10,00.
        UUID pequena = peca("5.00", 5);
        UUID m = maleta(c.v(), pequena, 2);
        UUID venda = vender(c.v(), m, c.cliente(), List.of(linha(pequena, 1)), List.of(pagamento("5.00", "PIX")));
        JsonNode dev = corpoDe(postar(DEVOLUCOES, como(c.v()), mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo",
                "ARREPENDIMENTO", "formaReembolso", "PIX", "itens", List.of(devolvida(pequena, 1, "BOA"))))
                .andExpect(status().isCreated()));
        assertThat(dev.get("situacao").asString()).isEqualTo("ACEITA");
    }

    // --- 13. cancelar a troca ---

    @Test
    void cancelarTrocaDeixaTudoComoEra() throws Exception {
        Cenario c = cenario("100.00", "150.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of(pagamento("50.00", "DINHEIRO")));
        UUID troca = id(t);
        UUID vendaB = vendaNova(troca);
        UUID devolucao = devolucaoDa(troca);
        UUID credito = UUID.fromString(t.get("creditoId").asString());

        // A venda e a devolução da troca não se desfazem sozinhas.
        assertThat(codigoDe(postar(VENDAS + "/" + vendaB + "/estorno", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isConflict()))).isEqualTo("VENDA_DE_TROCA");
        assertThat(codigoDe(postar("/api/dona/vendas/" + vendaB + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isConflict()))).isEqualTo("VENDA_DE_TROCA");
        assertThat(codigoDe(postar(DEVOLUCOES + "/" + devolucao + "/cancelar", como(c.v()), mapa("id", UUID.randomUUID(),
                "motivo", "x")).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_DE_TROCA");
        assertThat(codigoDe(postar("/api/dona/devolucoes/" + devolucao + "/cancelar", dona(), mapa("id", UUID.randomUUID(),
                "motivo", "x")).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_DE_TROCA");

        UUID cancelamento = UUID.randomUUID();
        JsonNode cancelada = corpoDe(postar(TROCAS + "/" + troca + "/cancelar", como(c.v()), mapa("id", cancelamento,
                "motivo", "o cliente desistiu da troca")).andExpect(status().isCreated()));
        assertThat(cancelada.get("situacao").asString()).isEqualTo("CANCELADA");
        assertThat(cancelada.get("motivoCancelamento").asString()).isEqualTo("o cliente desistiu da troca");
        assertThat(jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, vendaB)).isEqualTo("ESTORNADA");
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.devolucoes WHERE id = ?", String.class, devolucao))
                .isEqualTo("CANCELADA");
        Map<String, Object> cr = credito(credito);
        assertThat(cr.get("situacao")).isEqualTo("CANCELADO");
        assertThat((BigDecimal) cr.get("valor_utilizado")).isEqualByComparingTo("0.00");
        assertThat(somaDosUsos(credito)).isEqualByComparingTo("0.00");
        assertThat(saldo(c.maleta(), c.a())).isEqualTo(4);
        assertThat(saldo(c.maleta(), c.b())).isEqualTo(5);
        assertThat(somaDosPagamentos(c.vendaA())).isEqualByComparingTo("100.00");
        assertThat(somaDosPagamentos(vendaB)).isEqualByComparingTo("0.00");
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'ESTORNO_REEMBOLSO' AND forma = 'CREDITO'
                   AND credito_id = ? AND valor = 100.00""", c.vendaA(), credito)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.movimentos_remessa WHERE devolucao_id = ? AND tipo = 'TROCA_ENTRADA' AND quantidade = -1
                   AND estorna_id IS NOT NULL""", devolucao)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.movimentos_remessa WHERE venda_id = ? AND tipo = 'TROCA_SAIDA' AND quantidade = 1
                   AND estorna_id IS NOT NULL""", vendaB)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'TROCA_CANCELADA' AND registro_id = ?", troca))
                .isEqualTo(1);

        // Reenvio igual: 200 sem gravar nada; outro id: 409.
        postar(TROCAS + "/" + troca + "/cancelar", como(c.v()), mapa("id", cancelamento, "motivo", "o cliente desistiu da troca"))
                .andExpect(status().isOk());
        assertThat(codigoDe(postar(TROCAS + "/" + troca + "/cancelar", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "de novo"))
                .andExpect(status().isConflict()))).isEqualTo("TROCA_JA_CANCELADA");
        assertThat(saldo(c.maleta(), c.a())).isEqualTo(4);
        assertThat(saldo(c.maleta(), c.b())).isEqualTo(5);

        // A venda A, como antes da troca, pode ser trocada (ou devolvida) de novo.
        trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of(pagamento("50.00", "DINHEIRO")));
    }

    @Test
    void vendedorSoCancelaNoDiaDaTrocaEADonaCancelaDepois() throws Exception {
        Cenario c = cenario("100.00", "100.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        UUID troca = id(t);
        Instant amanha = relogio.instant().plus(Duration.ofDays(1));
        assertThatThrownBy(() -> trocas.cancelar(troca, c.v().vendedorId(), c.v().id(), usuarios.aparelhoDe(c.v()),
                UUID.randomUUID(), "engano", amanha)).satisfies(e -> assertThat(codigoDoErro(e)).isEqualTo("CANCELAR_TROCA_SO_DA_DONA"));
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.trocas WHERE id = ?", String.class, troca)).isEqualTo("ATIVA");

        JsonNode pelaDona = corpoDe(postar(TROCAS_DA_DONA + "/" + troca + "/cancelar", dona(), mapa("id", UUID.randomUUID(),
                "motivo", "lançada errada")).andExpect(status().isCreated()));
        assertThat(pelaDona.get("situacao").asString()).isEqualTo("CANCELADA");
        assertThat(saldo(c.maleta(), c.a())).isEqualTo(4);
        assertThat(saldo(c.maleta(), c.b())).isEqualTo(5);
    }

    @Test
    void naoCancelaComASobraJaUsadaNemComAPecaAVendidaDeNovo() throws Exception {
        // A sobra do crédito já usada em outra venda.
        Cenario c = cenario("100.00", "60.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        UUID credito = UUID.fromString(t.get("creditoId").asString());
        vender(c.v(), c.maleta(), c.cliente(), List.of(linha(c.b(), 1)), List.of(pagamentoComCredito("40.00", credito),
                pagamento("20.00", "DINHEIRO")));
        Map<String, Integer> antes = contagens();
        assertThat(codigoDe(postar(TROCAS + "/" + id(t) + "/cancelar", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isConflict()))).isEqualTo("CREDITO_DA_TROCA_JA_USADO");
        assertThat(contagens()).isEqualTo(antes);

        // A peça A que voltou já foi vendida de novo (a maleta tinha só uma).
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("30.00", 5);
        UUID b = peca("30.00", 5);
        UUID maleta = maleta(v, a, 1, b, 1);
        UUID vendaA = vender(v, maleta, cliente, List.of(linha(a, 1)), List.of(pagamento("30.00", "PIX")));
        JsonNode t2 = trocar(v, vendaA, a, maleta, b, List.of());
        vender(v, maleta, cliente, List.of(linha(a, 1)), List.of(pagamento("30.00", "PIX")));
        antes = contagens();
        JsonNode erro = corpoDe(postar(TROCAS + "/" + id(t2) + "/cancelar", como(v), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isConflict()));
        assertThat(erro.get("codigo").asString()).isEqualTo("TROCA_NAO_PODE_SER_CANCELADA");
        assertThat(erro.get("detail").asString()).contains("já foi vendida de novo");
        assertThat(contagens()).isEqualTo(antes);
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.trocas WHERE id = ?", String.class, id(t2))).isEqualTo("ATIVA");
    }

    // --- 14. idempotência ---

    @Test
    void reenvioIgualDa200EDiferenteOuComIdJaUsadoDa409() throws Exception {
        Cenario c = cenario("100.00", "120.00");
        UUID outraB = peca("120.00", 5);
        UUID maleta2 = maleta(c.v(), outraB, 2);
        Map<String, Object> pedido = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(),
                List.of(linha(c.b(), 1)), List.of(pagamento("20.00", "PIX")));
        JsonNode primeira = trocar(c.v(), pedido);
        Map<String, Integer> depois = contagens();
        JsonNode denovo = corpoDe(postar(TROCAS, como(c.v()), pedido).andExpect(status().isOk()));
        assertThat(denovo.get("id").asString()).isEqualTo(primeira.get("id").asString());
        assertThat(contagens()).isEqualTo(depois);

        // Mesmo id com outra peça B: 409.
        Map<String, Object> diferente = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), maleta2,
                List.of(linha(outraB, 1)), List.of(pagamento("20.00", "PIX")));
        diferente.put("id", pedido.get("id"));
        diferente.put("creditoId", pedido.get("creditoId"));
        diferente.put("pagamentoDoCreditoId", pedido.get("pagamentoDoCreditoId"));
        devolucaoDo(diferente).put("id", idDe(devolucaoDo(pedido)));
        vendaDo(diferente).put("id", idDe(vendaDo(pedido)));
        assertThat(codigoDe(postar(TROCAS, como(c.v()), diferente).andExpect(status().isConflict())))
                .isEqualTo("TROCA_JA_REGISTRADA_DIFERENTE");

        // Troca nova com o id de uma devolução ou de uma venda que já existem: 409, nada gravado.
        UUID vendaA2 = vender(c.v(), c.maleta(), c.cliente(), List.of(linha(c.a(), 1)), List.of(pagamento("100.00", "PIX")));
        Map<String, Object> comDevolucaoUsada = pedidoDeTroca(vendaA2, "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")),
                c.maleta(), List.of(linha(c.b(), 1)), List.of(pagamento("20.00", "PIX")));
        devolucaoDo(comDevolucaoUsada).put("id", idDe(devolucaoDo(pedido)));
        Map<String, Integer> antes = contagens();
        assertThat(codigoDe(postar(TROCAS, como(c.v()), comDevolucaoUsada).andExpect(status().isConflict())))
                .isEqualTo("TROCA_JA_REGISTRADA_DIFERENTE");
        Map<String, Object> comVendaUsada = pedidoDeTroca(vendaA2, "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")),
                c.maleta(), List.of(linha(c.b(), 1)), List.of(pagamento("20.00", "PIX")));
        vendaDo(comVendaUsada).put("id", c.vendaA());
        assertThat(codigoDe(postar(TROCAS, como(c.v()), comVendaUsada).andExpect(status().isConflict())))
                .isEqualTo("TROCA_JA_REGISTRADA_DIFERENTE");
        Map<String, Object> comCreditoUsado = pedidoDeTroca(vendaA2, "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")),
                c.maleta(), List.of(linha(c.b(), 1)), List.of(pagamento("20.00", "PIX")));
        comCreditoUsado.put("creditoId", pedido.get("creditoId"));
        assertThat(codigoDe(postar(TROCAS, como(c.v()), comCreditoUsado).andExpect(status().isConflict())))
                .isEqualTo("TROCA_JA_REGISTRADA_DIFERENTE");
        assertThat(contagens()).isEqualTo(antes);
    }

    // --- 15. comissão e eventos do acerto (consulta) ---

    @Test
    void naContaDoVendedorATrocaEUmaDevolucaoDeAEUmaVendaDeB() throws Exception {
        Cenario c = cenario("100.00", "150.00");
        trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of(pagamento("50.00", "DINHEIRO")));
        BigDecimal vendido = valor("SELECT sum(total) FROM corona.vendas WHERE vendedor_id = ? AND status = 'CONCLUIDA'",
                c.v().vendedorId());
        BigDecimal devolvido = valor("""
                SELECT sum(valor_devolvido) FROM corona.devolucoes WHERE vendedor_id = ? AND situacao <> 'CANCELADA'""",
                c.v().vendedorId());
        assertThat(vendido).isEqualByComparingTo("250.00");
        assertThat(devolvido).isEqualByComparingTo("100.00");
        // B - A: o que a troca acrescenta à conta do vendedor (a venda A já estava lá).
        assertThat(vendido.subtract(devolvido).subtract(new BigDecimal("100.00"))).isEqualByComparingTo("50.00");
        assertThat(corpoDe(ler(VENDAS, como(c.v())))).hasSize(2);
        assertThat(corpoDe(ler(DEVOLUCOES, como(c.v())))).hasSize(1);
    }

    // --- 16. conciliações ---

    @Test
    void conciliacoesFechamComATroca() throws Exception {
        Cenario c = cenario("100.00", "60.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        Cenario d = cenario("100.00", "150.00");
        trocar(d.v(), d.vendaA(), d.a(), d.maleta(), d.b(), List.of(pagamento("50.00", "PIX")));

        // C1 por venda: total - devolvido = soma dos pagamentos líquidos (com o crédito), em todas as vendas concluídas.
        for (UsuarioDeTeste v : List.of(c.v(), d.v())) {
            assertThat(contar("""
                    SELECT count(*) FROM corona.vendas v
                     WHERE v.vendedor_id = ? AND v.status = 'CONCLUIDA'
                       AND v.total - coalesce((SELECT sum(x.valor_devolvido) FROM corona.devolucoes x
                                                WHERE x.venda_id = v.id AND x.situacao <> 'CANCELADA'), 0)
                           <> coalesce((SELECT sum(p.valor) FROM corona.pagamentos_cliente p WHERE p.venda_id = v.id), 0)""",
                    v.vendedorId())).isZero();
        }
        // Créditos: valor_utilizado = soma dos usos; não cancelados - usos líquidos = saldos em aberto.
        assertThat(contar("""
                SELECT count(*) FROM corona.creditos_cliente cr
                 WHERE cr.valor_utilizado <> coalesce((SELECT sum(u.valor) FROM corona.usos_credito u WHERE u.credito_id = cr.id), 0)""")).isZero();
        BigDecimal emAberto = valor("""
                SELECT coalesce(sum(valor - valor_utilizado), 0) FROM corona.creditos_cliente
                 WHERE vendedor_id = ? AND situacao = 'ABERTO'""", c.v().vendedorId());
        BigDecimal liquido = valor("""
                SELECT coalesce(sum(cr.valor), 0) - coalesce((SELECT sum(u.valor) FROM corona.usos_credito u
                        JOIN corona.creditos_cliente x ON x.id = u.credito_id WHERE x.vendedor_id = ? AND x.situacao <> 'CANCELADO'), 0)
                  FROM corona.creditos_cliente cr WHERE cr.vendedor_id = ? AND cr.situacao <> 'CANCELADO'""",
                c.v().vendedorId(), c.v().vendedorId());
        assertThat(emAberto).isEqualByComparingTo("40.00").isEqualByComparingTo(liquido);
        assertThat(dec(t.get("creditoEmAberto"))).isEqualByComparingTo(emAberto);

        // Estoque da loja + maletas, com TROCA_ENTRADA e TROCA_SAIDA.
        for (UUID p : List.of(c.a(), c.b(), d.a(), d.b())) {
            assertThat(estoque.conciliar().divergencias()).noneMatch(x -> x.produtoId().equals(p));
            assertThat(estoque.conciliar().diferencasComMaletas()).as("peça %s", p).noneMatch(x -> x.produtoId().equals(p));
            JsonNode relatorio = corpoDe(ler("/api/dona/relatorios/estoque/conciliacao?produtoId=" + p, dona())
                    .andExpect(status().isOk()));
            assertThat(relatorio.get("total").asLong()).as("relatório da peça %s", p).isZero();
        }
        // Maleta do vendedor: B como vendida, A de volta.
        JsonNode maleta = corpoDe(ler("/api/vendedor/maletas/" + c.maleta(), como(c.v())).andExpect(status().isOk()));
        for (JsonNode l : maleta.get("itens")) {
            String produto = l.get("produto").get("id").asString();
            if (produto.equals(c.a().toString())) {
                assertThat(l.get("vendida").asInt()).isZero();
                assertThat(l.get("saldo").asInt()).isEqualTo(5);
            } else if (produto.equals(c.b().toString())) {
                assertThat(l.get("vendida").asInt()).isEqualTo(1);
                assertThat(l.get("saldo").asInt()).isEqualTo(4);
            }
        }
    }

    // --- 18. permissões ---

    @Test
    void vendedorSoTrocaVeECancelaAsDeleENaoUsaARotaDaDona() throws Exception {
        Cenario c = cenario("100.00", "100.00");
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID b2 = peca("100.00", 5);
        UUID maletaDoOutro = maleta(outro, b2, 2);
        Map<String, Integer> antes = contagens();
        assertThat(codigoDe(postar(TROCAS, como(outro), pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), maletaDoOutro, List.of(linha(b2, 1)), List.of()))
                .andExpect(status().isNotFound()))).isEqualTo("VENDA_NAO_ENCONTRADA");
        assertThat(contagens()).isEqualTo(antes);
        // B de uma maleta de outro vendedor: 404, sem dizer de quem é.
        assertThat(codigoDe(postar(TROCAS, como(c.v()), pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO",
                List.of(devolvida(c.a(), 1, "BOA")), maletaDoOutro, List.of(linha(b2, 1)), List.of()))
                .andExpect(status().isNotFound()))).isEqualTo("REMESSA_NAO_ENCONTRADA");
        assertThat(contagens()).isEqualTo(antes);

        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        UUID troca = id(t);
        ler(TROCAS + "/" + troca, como(outro)).andExpect(status().isNotFound());
        assertThat(corpoDe(ler(TROCAS, como(outro)).andExpect(status().isOk()))).isEmpty();
        assertThat(corpoDe(ler(TROCAS, como(c.v())).andExpect(status().isOk()))).hasSize(1);
        assertThat(codigoDe(postar(TROCAS + "/" + troca + "/cancelar", como(outro), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isNotFound()))).isEqualTo("TROCA_NAO_ENCONTRADA");

        ler(TROCAS_DA_DONA, como(c.v())).andExpect(status().isForbidden());
        ler(TROCAS_DA_DONA + "/" + troca, como(c.v())).andExpect(status().isForbidden());
        postar(TROCAS_DA_DONA + "/" + troca + "/cancelar", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isForbidden());
        ler(TROCAS, dona()).andExpect(status().isForbidden());
        ler(TROCAS, UsuariosDeTeste.comToken("invalido")).andExpect(status().isUnauthorized());

        // A dona vê, com filtro de vendedor.
        assertThat(corpoDe(ler(TROCAS_DA_DONA + "?vendedorId=" + c.v().vendedorId(), dona()).andExpect(status().isOk()))).hasSize(1);
        assertThat(corpoDe(ler(TROCAS_DA_DONA + "/" + troca, dona()).andExpect(status().isOk())).get("id").asString())
                .isEqualTo(troca.toString());
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.trocas WHERE id = ?", String.class, troca)).isEqualTo("ATIVA");
    }

    // --- 19. nada de custo ---

    @Test
    void nenhumaRespostaDasRotasDaTrocaTemCustoNemFornecedor() throws Exception {
        Cenario c = cenario("100.00", "60.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of());
        List<String> respostas = List.of(t.toString(),
                corpoDe(ler(TROCAS, como(c.v()))).toString(),
                corpoDe(ler(TROCAS + "/" + id(t), como(c.v()))).toString(),
                corpoDe(ler(TROCAS_DA_DONA, dona())).toString(),
                corpoDe(ler(TROCAS_DA_DONA + "/" + id(t), dona())).toString(),
                corpoDe(ler(VENDAS + "/" + vendaNova(id(t)), como(c.v()))).toString(),
                corpoDe(postar(TROCAS + "/" + id(t) + "/cancelar", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "x")))
                        .toString());
        for (String r : respostas) {
            assertThat(r.toLowerCase()).doesNotContain("custo").doesNotContain("fornecedor");
        }
    }

    // --- 20. imutabilidade e segundas travas no banco ---

    @Test
    void trocaNaoEAlteradaNemApagadaEOBancoConfereAsLigacoes() throws Exception {
        Cenario c = cenario("100.00", "150.00");
        JsonNode t = trocar(c.v(), c.vendaA(), c.a(), c.maleta(), c.b(), List.of(pagamento("50.00", "DINHEIRO")));
        UUID troca = id(t);
        UUID vendaB = vendaNova(troca);
        UUID devolucao = devolucaoDa(troca);

        assertThatThrownBy(() -> jdbc.update("UPDATE corona.trocas SET diferenca = 0 WHERE id = ?", troca))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.trocas SET vendedor_id = ? WHERE id = ?",
                usuarios.criarVendedor().vendedorId(), troca)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.trocas SET deleted_at = now() WHERE id = ?", troca))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.trocas WHERE id = ?", troca)).isInstanceOf(Exception.class);
        // A venda B não troca de troca; a devolução não muda o valor em crédito.
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.vendas SET troca_id = NULL WHERE id = ?", vendaB))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.devolucoes SET valor_em_credito = 0 WHERE id = ?", devolucao))
                .isInstanceOf(Exception.class);
        // A venda e a devolução da troca não são desfeitas sem a troca cancelada (segunda trava do "Cancelar troca").
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = now(), motivo_estorno = 'x'
                 WHERE id = ?""", UUID.randomUUID(), vendaB)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.devolucoes SET situacao = 'CANCELADA', cancelamento_id = ?, cancelada_em = now(), cancelada_por = ?,
                       motivo_cancelamento = 'x' WHERE id = ?""", UUID.randomUUID(), c.v().id(), devolucao))
                .isInstanceOf(Exception.class);
        // Só ATIVA -> CANCELADA, com os dados do cancelamento.
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.trocas SET situacao = 'CANCELADA' WHERE id = ?", troca))
                .isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, vendaB)).isEqualTo("CONCLUIDA");
        // O sinal dos movimentos da troca (V025): TROCA_ENTRADA volta à maleta (positivo) e TROCA_SAIDA sai (negativo).
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id, devolucao_id)
                VALUES (?, ?, ?, 'TROCA_ENTRADA', -1, ?, ?)""", UUID.randomUUID(), c.maleta(), c.a(), c.vendaA(), devolucao))
                .hasMessageContaining("movimentos_remessa_sinal_pelo_tipo");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, venda_id)
                VALUES (?, ?, ?, 'TROCA_SAIDA', 1, ?)""", UUID.randomUUID(), c.maleta(), c.b(), vendaB))
                .hasMessageContaining("movimentos_remessa_sinal_pelo_tipo");

        postar(TROCAS + "/" + troca + "/cancelar", como(c.v()), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.trocas SET situacao = 'ATIVA', cancelamento_id = NULL, cancelada_em = NULL, cancelada_por = NULL,
                       motivo_cancelamento = NULL WHERE id = ?""", troca)).isInstanceOf(Exception.class);
    }

    /** A troca gravada direto no banco com uma devolução que não é de troca: recusada no COMMIT (conferência adiada). */
    @Test
    void oBancoRecusaNoFimDaTransacaoATrocaQueNaoFecha() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("30.00", 5);
        UUID maleta = maleta(v, a, 3);
        UUID venda = vender(v, maleta, cliente, List.of(linha(a, 1)), List.of(pagamento("30.00", "PIX")));
        UUID outra = vender(v, maleta, cliente, List.of(linha(a, 1)), List.of(pagamento("30.00", "PIX")));
        UUID devolucao = UUID.fromString(corpoDe(postar(DEVOLUCOES, como(v), mapa("id", UUID.randomUUID(), "vendaId", venda,
                "motivo", "ARREPENDIMENTO", "formaReembolso", "PIX", "itens", List.of(devolvida(a, 1, "BOA"))))
                .andExpect(status().isCreated())).get("id").asString());

        try (Connection con = conectarComoAplicacao()) {
            try (PreparedStatement s = con.prepareStatement("""
                    INSERT INTO corona.trocas (id, venda_origem_id, devolucao_id, venda_nova_id, cliente_id, vendedor_id, diferenca,
                        trocada_em, registrada_por, situacao)
                    VALUES (?, ?, ?, ?, ?, ?, 0.00, now(), ?, 'ATIVA')""")) {
                s.setObject(1, UUID.randomUUID());
                s.setObject(2, venda);
                s.setObject(3, devolucao);
                s.setObject(4, outra);
                s.setObject(5, cliente);
                s.setObject(6, v.vendedorId());
                s.setObject(7, v.id());
                s.executeUpdate();
            }
            assertThatThrownBy(con::commit).isInstanceOf(SQLException.class);
            con.rollback();
        }
        assertThat(contar("SELECT count(*) FROM corona.trocas WHERE devolucao_id = ?", devolucao)).isZero();

        // Pagamento em crédito sem o crédito: recusado já no INSERT.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor) VALUES (?, ?, 'PAGAMENTO', 'CREDITO', 1.00)""",
                UUID.randomUUID(), venda)).isInstanceOf(Exception.class);
    }

    /**
     * Pedido 2 da orquestradora: se a troca ficar incoerente na conferência do fim da transação (aqui, um gatilho de teste
     * entorta a diferença só desta troca), a resposta é 409 com o código da troca (nunca 500) e nada fica gravado.
     */
    @Test
    void trocaQueNaoFechaNaConferenciaDoFimDaTransacaoDa409ENaoGravaNada() throws Exception {
        Cenario c = cenario("100.00", "150.00");
        Map<String, Object> pedido = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(),
                List.of(linha(c.b(), 1)), List.of(pagamento("50.00", "DINHEIRO")));
        UUID troca = (UUID) pedido.get("id");
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            s.execute("""
                    CREATE FUNCTION corona.teste_entortar_troca() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.id = '%s' THEN
                            NEW.diferenca := NEW.diferenca + 0.01;
                        END IF;
                        RETURN NEW;
                    END;
                    $$""".formatted(troca));
            s.execute("""
                    CREATE TRIGGER teste_entortar_troca BEFORE INSERT ON corona.trocas
                        FOR EACH ROW EXECUTE FUNCTION corona.teste_entortar_troca()""");
            con.commit();
        }
        try {
            Map<String, Integer> antes = contagens();
            JsonNode erro = corpoDe(postar(TROCAS, como(c.v()), pedido).andExpect(status().isConflict()));
            assertThat(erro.get("codigo").asString()).isEqualTo("TROCA_NAO_CONFERE");
            assertThat(erro.toString()).doesNotContain("Exception").doesNotContain("diferenca");
            assertThat(contagens()).isEqualTo(antes);
            assertThat(saldo(c.maleta(), c.a())).isEqualTo(4);
            assertThat(saldo(c.maleta(), c.b())).isEqualTo(5);
        } finally {
            try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
                s.execute("DROP TRIGGER teste_entortar_troca ON corona.trocas");
                s.execute("DROP FUNCTION corona.teste_entortar_troca()");
                con.commit();
            }
        }
        // Sem o gatilho de teste, a mesma troca passa.
        trocar(c.v(), pedido);
    }

    // --- 21. pedido malformado ---

    @Test
    void pedidoMalformadoDa400EmPortuguesSemGravarNada() throws Exception {
        Cenario c = cenario("100.00", "120.00");
        List<Map<String, Object>> ruins = new ArrayList<>();
        ruins.add(pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(), c.maleta(), List.of(linha(c.b(), 1)), List.of()));
        ruins.add(pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(), List.of(), List.of()));
        ruins.add(pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(),
                List.of(linha(c.b(), 1), linha(c.b(), 1)), List.of()));
        ruins.add(pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(),
                List.of(linhaComDesconto(c.b(), 1, "1.005")), List.of()));
        ruins.add(pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(),
                List.of(linha(c.b(), 1)), List.of(pagamento("20.005", "PIX"))));
        ruins.add(pedidoDeTroca(c.vendaA(), "NAO_GOSTOU", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(),
                List.of(linha(c.b(), 1)), List.of()));
        ruins.add(pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "QUEBRADA")), c.maleta(),
                List.of(linha(c.b(), 1)), List.of()));
        Map<String, Object> semDevolucao = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(), c.maleta(), List.of(), List.of());
        semDevolucao.remove("devolucao");
        ruins.add(semDevolucao);
        Map<String, Object> semVenda = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")), c.maleta(),
                List.of(), List.of());
        semVenda.remove("venda");
        ruins.add(semVenda);
        // O crédito da própria troca não é escolhido no resto: ele entra sozinho.
        Map<String, Object> comOCreditoDaTroca = pedidoDeTroca(c.vendaA(), "ARREPENDIMENTO", List.of(devolvida(c.a(), 1, "BOA")),
                c.maleta(), List.of(linha(c.b(), 1)), List.of());
        vendaDo(comOCreditoDaTroca).put("pagamentos", List.of(pagamentoComCredito("20.00", (UUID) comOCreditoDaTroca.get("creditoId"))));
        ruins.add(comOCreditoDaTroca);
        Map<String, Integer> antes = contagens();
        for (Map<String, Object> ruim : ruins) {
            JsonNode erro = corpoDe(postar(TROCAS, como(c.v()), ruim).andExpect(status().isBadRequest()));
            assertThat(erro.get("detail").asString()).as("%s", ruim).isNotBlank();
            assertThat(erro.toString()).as("%s", ruim).doesNotContain("Exception");
        }
        assertThat(contagens()).isEqualTo(antes);
    }
}
