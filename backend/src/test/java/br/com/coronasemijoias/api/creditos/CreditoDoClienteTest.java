package br.com.coronasemijoias.api.creditos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.sync.FiltroPorVendedor;
import br.com.coronasemijoias.api.sync.LinhaDoPull;
import br.com.coronasemijoias.api.sync.LoteDoPull;
import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import br.com.coronasemijoias.api.sync.TabelaSincronizavel;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Crédito do cliente (tarefa 6.5; ADR-0014, item 9 e a linha datada de 13/11/2026; plano aprovado no PR #129): uso em
 * parte, de novo e até zerar, acima do saldo, de outro cliente e de outro vendedor; dois usos ao mesmo tempo; estorno
 * da venda paga com crédito; devolução da venda paga com crédito (o crédito volta primeiro); transferência pela dona;
 * permissões; nada de custo; as segundas travas do banco; o registro de tabelas, o pull e o envio. Escritos antes do
 * código.
 */
class CreditoDoClienteTest extends ApoioDasTrocas {

    @Autowired
    ServicoDePull pull;

    private static final String PUSH = "/api/vendedor/sync/push";

    // --- 9. uso em parte, de novo e até zerar ---

    @Test
    void creditoUsadoEmParteDeNovoEAteZerar() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
        UUID p15 = peca("15.00", 5);
        UUID p25 = peca("25.00", 5);
        UUID p1 = peca("1.00", 5);
        UUID m = maleta(v, p15, 3, p25, 3, p1, 3);

        JsonNode lido = corpoDe(ler(CREDITOS + "/" + credito, como(v)).andExpect(status().isOk()));
        assertThat(dec(lido.get("saldo"))).isEqualByComparingTo("40.00");
        assertThat(lido.get("situacao").asString()).isEqualTo("ABERTO");
        assertThat(lido.get("origem").asString()).isEqualTo("TROCA");

        vender(v, m, cliente, List.of(linha(p15, 1)), List.of(pagamentoComCredito("15.00", credito)));
        assertThat(dec(corpoDe(ler(CREDITOS + "/" + credito, como(v))).get("saldo"))).isEqualByComparingTo("25.00");

        // 25,01 num crédito com 25,00: acima do saldo, nada gravado.
        Map<String, Integer> antes = contagens();
        assertThat(codigoDe(postar(VENDAS, como(v), pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p25, 1), linha(p1, 1)),
                List.of(pagamentoComCredito("25.01", credito)))).andExpect(status().isConflict()))).isEqualTo("CREDITO_ACIMA_DO_SALDO");
        assertThat(contagens()).isEqualTo(antes);

        // No valor exato passa e o crédito fica usado.
        vender(v, m, cliente, List.of(linha(p25, 1)), List.of(pagamentoComCredito("25.00", credito)));
        Map<String, Object> cr = credito(credito);
        assertThat(cr.get("situacao")).isEqualTo("UTILIZADO");
        assertThat((BigDecimal) cr.get("valor_utilizado")).isEqualByComparingTo("100.00");
        assertThat(somaDosUsos(credito)).isEqualByComparingTo("100.00");

        assertThat(codigoDe(postar(VENDAS, como(v), pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p1, 1)),
                List.of(pagamentoComCredito("1.00", credito)))).andExpect(status().isConflict()))).isEqualTo("CREDITO_SEM_SALDO");
    }

    @Test
    void creditoDeOutroClienteDeOutroVendedorEPedidoErradoSaoRecusados() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID outroCliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
        UUID p = peca("10.00", 10);
        UUID m = maleta(v, p, 5);
        Map<String, Integer> antes = contagens();

        assertThat(codigoDe(postar(VENDAS, como(v), pedidoDeVenda(UUID.randomUUID(), m, outroCliente, List.of(linha(p, 1)),
                List.of(pagamentoComCredito("10.00", credito)))).andExpect(status().isConflict()))).isEqualTo("CREDITO_DE_OUTRO_CLIENTE");

        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID mo = maleta(outro, p, 2);
        assertThat(codigoDe(postar(VENDAS, como(outro), pedidoDeVenda(UUID.randomUUID(), mo, cliente, List.of(linha(p, 1)),
                List.of(pagamentoComCredito("10.00", credito)))).andExpect(status().isNotFound()))).isEqualTo("CREDITO_NAO_ENCONTRADO");
        assertThat(codigoDe(postar(VENDAS, como(v), pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p, 1)),
                List.of(pagamentoComCredito("10.00", UUID.randomUUID())))).andExpect(status().isNotFound())))
                .isEqualTo("CREDITO_NAO_ENCONTRADO");

        // Forma crédito sem o crédito, crédito com outra forma e o mesmo crédito duas vezes: 400.
        postar(VENDAS, como(v), pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p, 1)),
                List.of(pagamento("10.00", "CREDITO")))).andExpect(status().isBadRequest());
        Map<String, Object> pixComCredito = pagamento("10.00", "PIX");
        pixComCredito.put("creditoId", credito);
        postar(VENDAS, como(v), pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p, 1)), List.of(pixComCredito)))
                .andExpect(status().isBadRequest());
        postar(VENDAS, como(v), pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p, 2)),
                List.of(pagamentoComCredito("5.00", credito), pagamentoComCredito("5.00", credito)))).andExpect(status().isBadRequest());
        assertThat(contagens()).isEqualTo(antes);
        assertThat(credito(credito).get("situacao")).isEqualTo("ABERTO");
    }

    @Test
    void reenvioDaVendaComCreditoDa200SemUsarDeNovoEOutroCreditoDa409() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
        UUID outroCredito = creditoDeSobra(v, cliente, "100.00", "60.00");
        UUID p = peca("10.00", 10);
        UUID m = maleta(v, p, 5);
        Map<String, Object> pedido = pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p, 1)),
                List.of(pagamentoComCredito("10.00", credito)));
        postar(VENDAS, como(v), pedido).andExpect(status().isCreated());
        postar(VENDAS, como(v), pedido).andExpect(status().isOk());
        assertThat(somaDosUsos(credito)).isEqualByComparingTo("70.00");
        @SuppressWarnings("unchecked")
        Map<String, Object> pago = (Map<String, Object>) ((List<?>) pedido.get("pagamentos")).get(0);
        pago.put("creditoId", outroCredito);
        assertThat(codigoDe(postar(VENDAS, como(v), pedido).andExpect(status().isConflict()))).isEqualTo("VENDA_JA_REGISTRADA_DIFERENTE");
        assertThat(somaDosUsos(outroCredito)).isEqualByComparingTo("60.00");
    }

    // --- 10. dois usos ao mesmo tempo ---

    @Test
    void transferenciaEVendaComOMesmoCreditoAoMesmoTempoSoUmaPassa() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste w = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID p = peca("40.00", 20);
        UUID m = maleta(v, p, 10);
        RequestPostProcessor quem = como(v);
        RequestPostProcessor aDona = dona();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int rodada = 0; rodada < 3; rodada++) {
                UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
                CountDownLatch largada = new CountDownLatch(1);
                Future<Integer> transferencia = pool.submit(() -> {
                    largada.await();
                    return postar(CREDITOS_DA_DONA + "/" + credito + "/transferir", aDona, mapa("id", UUID.randomUUID(),
                            "vendedorId", w.vendedorId())).andReturn().getResponse().getStatus();
                });
                Future<Integer> venda = pool.submit(() -> {
                    largada.await();
                    return postar(VENDAS, quem, pedidoDeVenda(UUID.randomUUID(), m, cliente, List.of(linha(p, 1)),
                            List.of(pagamentoComCredito("40.00", credito)))).andReturn().getResponse().getStatus();
                });
                largada.countDown();
                int t = transferencia.get(60, TimeUnit.SECONDS);
                int s = venda.get(60, TimeUnit.SECONDS);
                assertThat((t == 201 && s == 409) || (t == 409 && s == 201))
                        .as("rodada %d: só uma passa (transferência %d, venda %d)", rodada, t, s).isTrue();
                Map<String, Object> cr = credito(credito);
                assertThat((BigDecimal) cr.get("valor_utilizado")).isEqualByComparingTo("100.00");
                assertThat(somaDosUsos(credito)).isEqualByComparingTo("100.00");
                assertThat(contar("SELECT count(*) FROM corona.creditos_cliente WHERE credito_origem_id = ?", credito))
                        .isEqualTo(s == 201 ? 0 : 1);
            }
        } finally {
            pool.shutdown();
        }
    }

    // --- 11. estorno da venda paga com crédito ---

    @Test
    void estornoDaVendaPagaComCreditoDevolveOCredito() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
        UUID p = peca("40.00", 10);
        UUID m = maleta(v, p, 5);
        UUID venda = vender(v, m, cliente, List.of(linha(p, 1)), List.of(pagamentoComCredito("30.00", credito),
                pagamento("10.00", "DINHEIRO")));
        assertThat(credito(credito).get("situacao")).isEqualTo("ABERTO");
        assertThat(dec(corpoDe(ler(CREDITOS + "/" + credito, como(v))).get("saldo"))).isEqualByComparingTo("10.00");

        postar(VENDAS + "/" + venda + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "lançada errada"))
                .andExpect(status().isCreated());
        Map<String, Object> cr = credito(credito);
        assertThat(cr.get("situacao")).isEqualTo("ABERTO");
        assertThat((BigDecimal) cr.get("valor_utilizado")).isEqualByComparingTo("60.00");
        assertThat(contar("""
                SELECT count(*) FROM corona.usos_credito WHERE credito_id = ? AND tipo = 'ESTORNO' AND valor = -30.00
                   AND estorna_id IS NOT NULL AND venda_id = ?""", credito, venda)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO' AND forma = 'CREDITO'
                   AND valor = -30.00 AND credito_id = ?""", venda, credito)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ? AND tipo = 'REEMBOLSO' AND forma = 'DINHEIRO'
                   AND valor = -10.00""", venda)).isEqualTo(1);
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("0.00");
        assertThat(dec(corpoDe(ler(CREDITOS + "/" + credito, como(v))).get("saldo"))).isEqualByComparingTo("40.00");

        // O crédito que voltou pode ser usado de novo.
        vender(v, m, cliente, List.of(linha(p, 1)), List.of(pagamentoComCredito("40.00", credito)));
        assertThat(credito(credito).get("situacao")).isEqualTo("UTILIZADO");
    }

    // --- 12. devolução da venda paga com crédito ---

    @Test
    void devolucaoDeVendaPagaComCreditoDevolvePrimeiroOCredito() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "200.00", "20.00");
        UUID p = peca("50.00", 10);
        UUID m = maleta(v, p, 6);
        UUID tudo = vender(v, m, cliente, List.of(linha(p, 2)), List.of(pagamentoComCredito("60.00", credito),
                pagamento("40.00", "DINHEIRO")));
        UUID metade = vender(v, m, cliente, List.of(linha(p, 2)), List.of(pagamentoComCredito("60.00", credito),
                pagamento("40.00", "DINHEIRO")));

        // Devolver tudo: 60,00 voltam como crédito novo (origem devolução) e 40,00 em dinheiro.
        JsonNode d = corpoDe(postar(DEVOLUCOES, como(v), mapa("id", UUID.randomUUID(), "vendaId", tudo, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "PIX", "itens", List.of(devolvida(p, 2, "BOA")))).andExpect(status().isCreated()));
        UUID devolucao = id(d);
        assertThat(dec(d.get("valorReembolsado"))).isEqualByComparingTo("40.00");
        assertThat(dec(d.get("valorEmCredito"))).isEqualByComparingTo("60.00");
        UUID novo = jdbc.queryForObject("SELECT id FROM corona.creditos_cliente WHERE devolucao_id = ?", UUID.class, devolucao);
        Map<String, Object> cr = credito(novo);
        assertThat(cr.get("origem")).isEqualTo("DEVOLUCAO");
        assertThat(cr.get("cliente_id")).isEqualTo(cliente);
        assertThat(cr.get("vendedor_id")).isEqualTo(v.vendedorId());
        assertThat((BigDecimal) cr.get("valor")).isEqualByComparingTo("60.00");
        assertThat(cr.get("situacao")).isEqualTo("ABERTO");
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE devolucao_id = ? AND tipo = 'REEMBOLSO' AND forma = 'CREDITO'
                   AND valor = -60.00 AND credito_id = ?""", devolucao, novo)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE devolucao_id = ? AND tipo = 'REEMBOLSO' AND forma = 'PIX'
                   AND valor = -40.00""", devolucao)).isEqualTo(1);
        // O crédito usado na venda continua usado: o que voltou é um crédito novo.
        assertThat((BigDecimal) credito(credito).get("valor_utilizado")).isEqualByComparingTo("140.00");

        // Devolver metade (50,00): tudo em crédito, nenhum dinheiro.
        JsonNode dm = corpoDe(postar(DEVOLUCOES, como(v), mapa("id", UUID.randomUUID(), "vendaId", metade, "motivo",
                "ARREPENDIMENTO", "formaReembolso", "PIX", "itens", List.of(devolvida(p, 1, "BOA")))).andExpect(status().isCreated()));
        assertThat(dec(dm.get("valorReembolsado"))).isEqualByComparingTo("0.00");
        assertThat(dec(dm.get("valorEmCredito"))).isEqualByComparingTo("50.00");
        UUID daMetade = jdbc.queryForObject("SELECT id FROM corona.creditos_cliente WHERE devolucao_id = ?", UUID.class, id(dm));

        // Cancelar a devolução cancela o crédito novo.
        postar(DEVOLUCOES + "/" + id(dm) + "/cancelar", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        assertThat(credito(daMetade).get("situacao")).isEqualTo("CANCELADO");
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente WHERE devolucao_id = ? AND tipo = 'ESTORNO_REEMBOLSO' AND forma = 'CREDITO'
                   AND valor = 50.00 AND credito_id = ?""", id(dm), daMetade)).isEqualTo(1);
        assertThat(somaDosPagamentos(metade)).isEqualByComparingTo("100.00");

        // Com o crédito novo já usado, a devolução não é cancelada.
        UUID q = peca("60.00", 5);
        UUID m2 = maleta(v, q, 2);
        vender(v, m2, cliente, List.of(linha(q, 1)), List.of(pagamentoComCredito("60.00", novo)));
        Map<String, Integer> antes = contagens();
        assertThat(codigoDe(postar(DEVOLUCOES + "/" + devolucao + "/cancelar", como(v), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isConflict()))).isEqualTo("CREDITO_JA_USADO");
        assertThat(contagens()).isEqualTo(antes);
    }

    // --- 17. transferência pela dona ---

    @Test
    void donaTransfereOSaldoInteiroParaOutroVendedor() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID cliente = cliente(a);
        UUID credito = creditoDeSobra(a, cliente, "100.00", "60.00");
        UUID p = peca("40.00", 10);
        UUID ma = maleta(a, p, 3);
        UUID mb = maleta(b, p, 3);

        UUID transferencia = UUID.randomUUID();
        JsonNode novo = corpoDe(postar(CREDITOS_DA_DONA + "/" + credito + "/transferir", dona(), mapa("id", transferencia,
                "vendedorId", b.vendedorId())).andExpect(status().isCreated()));
        UUID deB = id(novo);
        assertThat(deB).isEqualTo(transferencia);
        assertThat(novo.get("origem").asString()).isEqualTo("TRANSFERENCIA");
        assertThat(dec(novo.get("saldo"))).isEqualByComparingTo("40.00");
        assertThat(novo.get("vendedorId").asString()).isEqualTo(b.vendedorId().toString());
        Map<String, Object> original = credito(credito);
        assertThat(original.get("situacao")).isEqualTo("UTILIZADO");
        assertThat(contar("""
                SELECT count(*) FROM corona.usos_credito WHERE credito_id = ? AND tipo = 'TRANSFERENCIA' AND valor = 40.00
                   AND credito_destino_id = ?""", credito, deB)).isEqualTo(1);
        assertThat(credito(deB).get("credito_origem_id")).isEqualTo(credito);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'CREDITO_TRANSFERIDO' AND registro_id = ?", credito))
                .isEqualTo(1);

        // B usa; A não vê nem usa o crédito novo; o original não tem mais saldo.
        vender(b, mb, cliente, List.of(linha(p, 1)), List.of(pagamentoComCredito("40.00", deB)));
        ler(CREDITOS + "/" + deB, como(a)).andExpect(status().isNotFound());
        assertThat(codigoDe(postar(VENDAS, como(a), pedidoDeVenda(UUID.randomUUID(), ma, cliente, List.of(linha(p, 1)),
                List.of(pagamentoComCredito("40.00", deB)))).andExpect(status().isNotFound()))).isEqualTo("CREDITO_NAO_ENCONTRADO");
        assertThat(codigoDe(postar(VENDAS, como(a), pedidoDeVenda(UUID.randomUUID(), ma, cliente, List.of(linha(p, 1)),
                List.of(pagamentoComCredito("40.00", credito)))).andExpect(status().isConflict()))).isEqualTo("CREDITO_SEM_SALDO");

        // Repetir é 200; o mesmo id com outro destino, 409; outro id sem saldo, 409; para o mesmo vendedor, 400.
        postar(CREDITOS_DA_DONA + "/" + credito + "/transferir", dona(), mapa("id", transferencia, "vendedorId", b.vendedorId()))
                .andExpect(status().isOk());
        UsuarioDeTeste c = usuarios.criarVendedor();
        assertThat(codigoDe(postar(CREDITOS_DA_DONA + "/" + credito + "/transferir", dona(), mapa("id", transferencia,
                "vendedorId", c.vendedorId())).andExpect(status().isConflict()))).isEqualTo("CREDITO_JA_TRANSFERIDO_DIFERENTE");
        assertThat(codigoDe(postar(CREDITOS_DA_DONA + "/" + credito + "/transferir", dona(), mapa("id", UUID.randomUUID(),
                "vendedorId", c.vendedorId())).andExpect(status().isConflict()))).isEqualTo("CREDITO_SEM_SALDO");
        UUID outro = creditoDeSobra(a, cliente, "100.00", "60.00");
        postar(CREDITOS_DA_DONA + "/" + outro + "/transferir", dona(), mapa("id", UUID.randomUUID(), "vendedorId", a.vendedorId()))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE corona.vendedores SET ativo = false WHERE id = ?", c.vendedorId());
        postar(CREDITOS_DA_DONA + "/" + outro + "/transferir", dona(), mapa("id", UUID.randomUUID(), "vendedorId", c.vendedorId()))
                .andExpect(status().isBadRequest());
        assertThat(credito(outro).get("situacao")).isEqualTo("ABERTO");
    }

    // --- 18. permissões e listas ---

    @Test
    void vendedorSoVeOsPropriosCreditosEADonaVeTodosComFiltros() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID cliente = cliente(a);
        UUID outroCliente = cliente(a);
        UUID credito = creditoDeSobra(a, cliente, "100.00", "60.00");
        creditoDeSobra(a, outroCliente, "100.00", "90.00");

        ler(CREDITOS + "/" + credito, como(b)).andExpect(status().isNotFound());
        assertThat(corpoDe(ler(CREDITOS, como(b)).andExpect(status().isOk()))).isEmpty();
        assertThat(corpoDe(ler(CREDITOS, como(a)).andExpect(status().isOk()))).hasSize(2);
        JsonNode doCliente = corpoDe(ler(CREDITOS + "?clienteId=" + cliente, como(a)).andExpect(status().isOk()));
        assertThat(doCliente).hasSize(1);
        assertThat(doCliente.get(0).get("id").asString()).isEqualTo(credito.toString());
        assertThat(doCliente.get(0).get("usos")).hasSize(1);

        ler(CREDITOS_DA_DONA, como(a)).andExpect(status().isForbidden());
        postar(CREDITOS_DA_DONA + "/" + credito + "/transferir", como(a), mapa("id", UUID.randomUUID(), "vendedorId", b.vendedorId()))
                .andExpect(status().isForbidden());
        ler(CREDITOS, dona()).andExpect(status().isForbidden());
        ler(CREDITOS, UsuariosDeTeste.comToken("invalido")).andExpect(status().isUnauthorized());

        assertThat(corpoDe(ler(CREDITOS_DA_DONA + "?vendedorId=" + a.vendedorId(), dona()).andExpect(status().isOk()))).hasSize(2);
        assertThat(corpoDe(ler(CREDITOS_DA_DONA + "?clienteId=" + cliente, dona()).andExpect(status().isOk()))).hasSize(1);
        assertThat(corpoDe(ler(CREDITOS_DA_DONA + "?vendedorId=" + a.vendedorId() + "&situacao=UTILIZADO", dona())
                .andExpect(status().isOk()))).isEmpty();
        ler(CREDITOS_DA_DONA + "?situacao=QUALQUER", dona()).andExpect(status().isBadRequest());
        assertThat(corpoDe(ler(CREDITOS_DA_DONA + "/" + credito, dona()).andExpect(status().isOk())).get("id").asString())
                .isEqualTo(credito.toString());
    }

    // --- 19. nada de custo ---

    @Test
    void nenhumaRespostaDasRotasDoCreditoTemCustoNemFornecedor() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID cliente = cliente(a);
        UUID credito = creditoDeSobra(a, cliente, "100.00", "60.00");
        List<String> respostas = List.of(corpoDe(ler(CREDITOS, como(a))).toString(),
                corpoDe(ler(CREDITOS + "/" + credito, como(a))).toString(),
                corpoDe(ler(CREDITOS_DA_DONA, dona())).toString(),
                corpoDe(ler(CREDITOS_DA_DONA + "/" + credito, dona())).toString(),
                corpoDe(postar(CREDITOS_DA_DONA + "/" + credito + "/transferir", dona(), mapa("id", UUID.randomUUID(),
                        "vendedorId", b.vendedorId()))).toString());
        for (String r : respostas) {
            assertThat(r.toLowerCase()).doesNotContain("custo").doesNotContain("fornecedor");
        }
    }

    // --- 20. segundas travas no banco ---

    @Test
    void creditoEUsosNaoSaoAlteradosNemApagadosEOSaldoTemSegundaTrava() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
        UUID p = peca("50.00", 5);
        UUID m = maleta(v, p, 2);
        UUID venda = vender(v, m, cliente, List.of(linha(p, 1)), List.of(pagamento("50.00", "PIX")));

        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_cliente SET valor = 1000.00 WHERE id = ?", credito))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_cliente SET cliente_id = ? WHERE id = ?", cliente(v), credito))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_cliente SET vendedor_id = ? WHERE id = ?",
                usuarios.criarVendedor().vendedorId(), credito)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_cliente SET valor_utilizado = 0 WHERE id = ?", credito))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_cliente SET deleted_at = now() WHERE id = ?", credito))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.creditos_cliente WHERE id = ?", credito)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.usos_credito SET valor = 1.00 WHERE credito_id = ?", credito))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.usos_credito WHERE credito_id = ?", credito))
                .isInstanceOf(Exception.class);
        assertThat((BigDecimal) credito(credito).get("valor_utilizado")).isEqualByComparingTo("60.00");

        // Uso gravado direto acima do saldo (40,00 em aberto): o gatilho recusa, mesmo com o pagamento em crédito certo.
        SQLException acima = gravarUsoDireto(v, credito, venda, "50.00");
        assertThat((Throwable) acima).isNotNull();
        assertThat(acima.getMessage()).contains("saldo");
        assertThat((Throwable) gravarUsoDireto(v, credito, venda, "40.00")).as("no valor exato passa").isNull();

        // Cancelado não volta a ficar em aberto.
        UUID outro = creditoDeSobra(v, cliente, "100.00", "100.00");
        UUID troca = (UUID) credito(outro).get("troca_id");
        postar(TROCAS + "/" + troca + "/cancelar", como(v), mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isCreated());
        assertThat(credito(outro).get("situacao")).isEqualTo("CANCELADO");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.creditos_cliente SET situacao = 'ABERTO', cancelado_em = NULL, cancelado_por = NULL WHERE id = ?""",
                outro)).isInstanceOf(Exception.class);
    }

    /** Pagamento em crédito e uso gravados direto pelo usuário da aplicação, numa transação desfeita no fim. */
    private SQLException gravarUsoDireto(UsuarioDeTeste v, UUID credito, UUID venda, String valor) throws SQLException {
        try (Connection c = conectarComoAplicacao()) {
            try {
                UUID pagamento = UUID.randomUUID();
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, credito_id)
                        VALUES (?, ?, 'PAGAMENTO', 'CREDITO', ?, ?)""")) {
                    s.setObject(1, pagamento);
                    s.setObject(2, venda);
                    s.setBigDecimal(3, new BigDecimal(valor));
                    s.setObject(4, credito);
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO corona.usos_credito (id, credito_id, tipo, venda_id, pagamento_id, valor, usado_em)
                        VALUES (?, ?, 'USO', ?, ?, ?, now())""")) {
                    s.setObject(1, UUID.randomUUID());
                    s.setObject(2, credito);
                    s.setObject(3, venda);
                    s.setObject(4, pagamento);
                    s.setBigDecimal(5, new BigDecimal(valor));
                    s.executeUpdate();
                }
                return null;
            } catch (SQLException e) {
                return e;
            } finally {
                c.rollback();
            }
        }
    }

    // --- 22. registro de tabelas, pull e envio ---

    @Test
    void asTabelasNovasVaoAoVendedorDesdeATarefa67() throws Exception {
        RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();
        for (String nome : List.of("trocas", "creditos_cliente", "usos_credito")) {
            TabelaSincronizavel t = registro.sincronizavel(nome).orElseThrow();
            assertThat(t.regra().declarada()).as(nome).isTrue();
            // Desde a tarefa 6.7, o vendedor recebe as dele (filtro do vendedor; PullDaFase6Test).
            assertThat(t.regra().condicao()).as(nome).isNotEqualTo("FALSE");
        }
        assertThat(registro.sincronizavel("usos_credito").orElseThrow().imutavel()).isTrue();
        assertThat(registro.sincronizavel("pagamentos_cliente").orElseThrow().colunas()).contains("credito_id");
        assertThat(registro.sincronizavel("vendas").orElseThrow().colunas()).contains("troca_id");
        assertThat(registro.sincronizavel("devolucoes").orElseThrow().colunas()).contains("valor_em_credito");
        // Nada que aponte para o crédito de outro vendedor (dúvida S4 da 6.7).
        assertThat(registro.sincronizavel("creditos_cliente").orElseThrow().colunas()).doesNotContain("credito_origem_id");
        assertThat(registro.sincronizavel("usos_credito").orElseThrow().colunas()).doesNotContain("credito_destino_id");

        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
        List<LinhaDoPull> recebidas = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 100; i++) {
            LoteDoPull lote = pull.puxar(cursor, 500, new FiltroPorVendedor(v.vendedorId()));
            recebidas.addAll(lote.linhas());
            cursor = lote.proximoCursor();
            if (!lote.haMais()) {
                break;
            }
        }
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("creditos_cliente")).extracting(LinhaDoPull::id).contains(credito);
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("trocas")).isNotEmpty();
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("usos_credito")).isNotEmpty();
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("pagamentos_cliente")).isNotEmpty()
                .allSatisfy(l -> assertThat(l.colunas()).containsKeys("credito_id"));
        assertThat(recebidas).filteredOn(l -> l.tabela().equals("vendas")).isNotEmpty()
                .allSatisfy(l -> assertThat(l.colunas()).containsKeys("troca_id"));

        // A venda com crédito pelo envio (formato 1 ampliado, tarefa 6.7) entra e usa o crédito.
        UUID p = peca("10.00", 5);
        UUID m = maleta(v, p, 2);
        UUID id = UUID.randomUUID();
        Map<String, Object> pagamento = mapa("id", UUID.randomUUID(), "valor", "10.00", "forma", "CREDITO", "creditoId", credito);
        Map<String, Object> op = mapa("operacaoId", id, "tipo", "VENDA", "formato", 1, "criadoEmLocal", Instant.now().toString(),
                "dados", mapa("id", id, "remessaId", m, "clienteId", cliente, "itens", List.of(linha(p, 1)),
                        "pagamentos", List.of(pagamento), "vendidaEm", Instant.now().toString()));
        JsonNode r = corpoDe(postar(PUSH, UsuariosDeTeste.comToken(usuarios.token(v)), mapa("horaLocal", Instant.now().toString(),
                "aguardandoDepoisDeste", 0, "operacoes", List.of(op))).andExpect(status().isOk()));
        assertThat(r.get("resultados").get(0).get("resultado").asString()).isEqualTo("ACEITO");
        assertThat(contar("SELECT count(*) FROM corona.vendas WHERE id = ?", id)).isOne();
        assertThat((BigDecimal) credito(credito).get("valor_utilizado")).isEqualByComparingTo("70.00");
    }
}
