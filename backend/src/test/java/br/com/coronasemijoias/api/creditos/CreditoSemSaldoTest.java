package br.com.coronasemijoias.api.creditos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.sync.ApoioDoEnvioDaFase6;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Crédito do cliente acima do saldo pelo envio do computador (tarefa 6.7; ADR-0014, item 9, linha de 13/11/2026; dúvidas
 * S1 e S2; pontos 1 e 2 da aprovação no PR #131): a venda entra, o crédito paga só o saldo que havia, o resto fica a
 * receber e a dona revisa ({@code creditos_sem_saldo}). Na rota online continua 409. A conta C1 (soma dos usos = o usado
 * do crédito; total − devolvido = pagos + a receber) fecha sempre, e a linha de revisão nunca mexe em dinheiro. Escritos
 * antes do código.
 */
class CreditoSemSaldoTest extends ApoioDoEnvioDaFase6 {

    private static final String SEM_SALDO = "/api/dona/creditos-sem-saldo";

    /** Vendedor, cliente, um crédito de 40,00 em aberto e uma maleta conferida com peças de 40,00 e de 25,00. */
    private record Cenario(UsuarioDeTeste v, UUID cliente, UUID credito, UUID maleta, UUID p40, UUID p25) {
    }

    private Cenario cenario() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID credito = creditoDeSobra(v, cliente, "100.00", "60.00");
        UUID p40 = peca("40.00", 10);
        UUID p25 = peca("25.00", 10);
        UUID maleta = maleta(v, p40, 4, p25, 4);
        return new Cenario(v, cliente, credito, maleta, p40, p25);
    }

    private Map<String, Object> vendaComCredito(Cenario c, UUID id, UUID peca, String valor) {
        return vendaPeloEnvio(id, c.maleta(), c.cliente(), List.of(linha(peca, 1)),
                List.of(mapa("id", id, "valor", valor, "forma", "CREDITO", "creditoId", c.credito())), relogio.instant());
    }

    private Map<String, Object> semSaldo(UUID pagamento) {
        return jdbc.queryForMap("""
                SELECT venda_id, credito_id, valor_pedido, valor_usado, situacao, revisada_em, observacao_revisao
                  FROM corona.creditos_sem_saldo WHERE id = ?""", pagamento);
    }

    /** C1: o usado do crédito é a soma dos usos e nunca passa do valor. */
    private void creditoFecha(UUID credito) {
        Map<String, Object> cr = credito(credito);
        assertThat((BigDecimal) cr.get("valor_utilizado")).isEqualByComparingTo(somaDosUsos(credito));
        assertThat((BigDecimal) cr.get("valor_utilizado")).isLessThanOrEqualTo((BigDecimal) cr.get("valor"));
    }

    private JsonNode avisos() throws Exception {
        return corpoDe(ler("/api/dona/avisos", dona()).andExpect(status().isOk()));
    }

    private static List<String> titulosDoTipo(JsonNode avisos, String tipo) {
        List<String> t = new java.util.ArrayList<>();
        for (JsonNode a : avisos.has("avisos") ? avisos.get("avisos") : avisos) {
            if (tipo.equals(a.get("tipo").asString())) {
                t.add(a.get("titulo").asString());
            }
        }
        return t;
    }

    // --- crédito usado duas vezes no mesmo lote ---

    @Test
    void creditoUsadoDuasVezesNoMesmoLoteASegundaVendaEntraSemCreditoEVaiParaARevisao() throws Exception {
        Cenario c = cenario();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        List<Map<String, Object>> lote = List.of(vendaComCredito(c, v1, c.p40(), "40.00"), vendaComCredito(c, v2, c.p40(), "40.00"));
        JsonNode r = enviar(c.v(), lote);
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("ACEITO");

        // A primeira usou tudo; a segunda não tem pagamento em crédito e deve 40,00.
        assertThat(somaDosPagamentos(v1)).isEqualByComparingTo("40.00");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cliente WHERE venda_id = ?", v2)).isZero();
        assertThat(aReceber(v2)).isEqualByComparingTo("40.00");
        Map<String, Object> f = semSaldo(v2);
        assertThat(f.get("venda_id")).isEqualTo(v2);
        assertThat(f.get("credito_id")).isEqualTo(c.credito());
        assertThat((BigDecimal) f.get("valor_pedido")).isEqualByComparingTo("40.00");
        assertThat((BigDecimal) f.get("valor_usado")).isEqualByComparingTo("0.00");
        assertThat(f.get("situacao")).isEqualTo("EM_REVISAO");
        assertThat(credito(c.credito()).get("situacao")).isEqualTo("UTILIZADO");
        assertThat(somaDosUsos(c.credito())).isEqualByComparingTo("100.00");
        creditoFecha(c.credito());
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'CREDITO_SEM_SALDO_NA_VENDA' AND registro_id = ?", v2))
                .isOne();
        assertThat(titulosDoTipo(avisos(), FonteDeAvisosDosCreditos.TIPO_SEM_SALDO)).isNotEmpty();

        // Reenvio do lote: JA_RECEBIDO, sem segunda revisão nem pagamento novo.
        Map<String, Integer> antes = contagens();
        int revisoes = contar("SELECT count(*) FROM corona.creditos_sem_saldo");
        JsonNode de2 = enviar(c.v(), lote);
        // O serviço também reconhece a mesma venda (o pedido de 40,00 em crédito está na revisão, não no pagamento).
        assertThat(vendas.venderPeloEnvio(c.v().vendedorId(), c.v().id(), usuarios.aparelhoDe(c.v()),
                new br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda(v2, c.maleta(), c.cliente(),
                        List.of(new br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido(c.p40(), 1, null)),
                        List.of(new br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido(v2, new BigDecimal("40.00"),
                                "CREDITO", c.credito())), null), null, false).jaExistia()).isTrue();
        assertThat(resultado(de2, 0)).isEqualTo("JA_RECEBIDO");
        assertThat(resultado(de2, 1)).isEqualTo("JA_RECEBIDO");
        assertThat(contagens()).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM corona.creditos_sem_saldo")).isEqualTo(revisoes);
    }

    @Test
    void comSaldoMenorOCreditoPagaOSaldoEORestoFicaAReceber() throws Exception {
        Cenario c = cenario();
        // Usa 15,00 primeiro: sobram 25,00.
        UUID antes = UUID.randomUUID();
        UUID p15 = peca("15.00", 2);
        UUID m15 = maleta(c.v(), p15, 1);
        assertThat(resultado(enviar(c.v(), List.of(vendaPeloEnvio(antes, m15, c.cliente(), List.of(linha(p15, 1)),
                List.of(mapa("id", UUID.randomUUID(), "valor", "15.00", "forma", "CREDITO", "creditoId", c.credito())),
                relogio.instant()))), 0)).isEqualTo("ACEITO");

        UUID venda = UUID.randomUUID();
        JsonNode r = enviar(c.v(), List.of(vendaComCredito(c, venda, c.p40(), "40.00")));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(valor("SELECT valor FROM corona.pagamentos_cliente WHERE id = ?", venda)).isEqualByComparingTo("25.00");
        assertThat(aReceber(venda)).isEqualByComparingTo("15.00");
        Map<String, Object> f = semSaldo(venda);
        assertThat((BigDecimal) f.get("valor_pedido")).isEqualByComparingTo("40.00");
        assertThat((BigDecimal) f.get("valor_usado")).isEqualByComparingTo("25.00");
        assertThat(credito(c.credito()).get("situacao")).isEqualTo("UTILIZADO");
        creditoFecha(c.credito());
        // Conciliação por venda: total − devolvido = pagos + a receber.
        assertThat(valor("SELECT total FROM corona.vendas WHERE id = ?", venda))
                .isEqualByComparingTo(somaDosPagamentos(venda).add(aReceber(venda)));
    }

    @Test
    void creditoTransferidoOuCanceladoAntesDoEnvioPagaZeroEVaiParaARevisao() throws Exception {
        // Transferido pela dona para outro vendedor: o original fica usado, para quem vendeu sem internet.
        Cenario c = cenario();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        postar(CREDITOS_DA_DONA + "/" + c.credito() + "/transferir", dona(), mapa("id", UUID.randomUUID(),
                "vendedorId", outro.vendedorId())).andExpect(status().isCreated());
        UUID venda = UUID.randomUUID();
        assertThat(resultado(enviar(c.v(), List.of(vendaComCredito(c, venda, c.p25(), "25.00"))), 0)).isEqualTo("ACEITO");
        assertThat(aReceber(venda)).isEqualByComparingTo("25.00");
        assertThat((BigDecimal) semSaldo(venda).get("valor_usado")).isEqualByComparingTo("0.00");
        creditoFecha(c.credito());

        // Cancelado: a dona cancelou a troca que gerou o crédito (com a sobra ainda sem uso).
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID a = peca("100.00", 5);
        UUID b = peca("60.00", 5);
        UUID m = maleta(v, a, 2, b, 2);
        UUID vendaA = vender(v, m, cliente, List.of(linha(a, 1)), List.of(pagamento("100.00", "DINHEIRO")));
        JsonNode troca = trocar(v, vendaA, a, m, b, List.of());
        UUID credito = UUID.fromString(troca.get("creditoId").asString());
        postar(TROCAS_DA_DONA + "/" + id(troca) + "/cancelar", dona(), mapa("id", UUID.randomUUID(), "motivo", "Lançada errada"))
                .andExpect(status().is2xxSuccessful());
        assertThat(credito(credito).get("situacao")).isEqualTo("CANCELADO");
        UUID vendaB = UUID.randomUUID();
        assertThat(resultado(enviar(v, List.of(vendaPeloEnvio(vendaB, m, cliente, List.of(linha(b, 1)),
                List.of(mapa("id", vendaB, "valor", "40.00", "forma", "CREDITO", "creditoId", credito)), relogio.instant()))), 0))
                .isEqualTo("ACEITO");
        assertThat(aReceber(vendaB)).isEqualByComparingTo("60.00");
        assertThat((BigDecimal) semSaldo(vendaB).get("valor_usado")).isEqualByComparingTo("0.00");
        assertThat(credito(credito).get("situacao")).isEqualTo("CANCELADO");
        creditoFecha(credito);
    }

    @Test
    void creditoInexistenteDeOutroVendedorOuDeOutroClienteContinuaRecusado() throws Exception {
        Cenario c = cenario();
        UUID inexistente = UUID.randomUUID();
        Map<String, Object> op = vendaPeloEnvio(inexistente, c.maleta(), c.cliente(), List.of(linha(c.p25(), 1)),
                List.of(mapa("id", UUID.randomUUID(), "valor", "25.00", "forma", "CREDITO", "creditoId", UUID.randomUUID())),
                relogio.instant());
        JsonNode r = enviar(c.v(), List.of(op));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("CREDITO_NAO_ENCONTRADO");

        // De outro vendedor: o mesmo 404 (nada revelado).
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID clienteDoOutro = cliente(outro);
        UUID creditoDoOutro = creditoDeSobra(outro, clienteDoOutro, "100.00", "60.00");
        UUID deOutro = UUID.randomUUID();
        r = enviar(c.v(), List.of(vendaPeloEnvio(deOutro, c.maleta(), c.cliente(), List.of(linha(c.p25(), 1)),
                List.of(mapa("id", UUID.randomUUID(), "valor", "25.00", "forma", "CREDITO", "creditoId", creditoDoOutro)),
                relogio.instant())));
        assertThat(codigo(r, 0)).isEqualTo("CREDITO_NAO_ENCONTRADO");

        // De outro cliente: 409 do serviço, RECUSADO.
        UUID outroCliente = cliente(c.v());
        UUID deOutroCliente = UUID.randomUUID();
        r = enviar(c.v(), List.of(vendaPeloEnvio(deOutroCliente, c.maleta(), outroCliente, List.of(linha(c.p25(), 1)),
                List.of(mapa("id", UUID.randomUUID(), "valor", "25.00", "forma", "CREDITO", "creditoId", c.credito())),
                relogio.instant())));
        assertThat(resultado(r, 0)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 0)).isEqualTo("CREDITO_DE_OUTRO_CLIENTE");
        assertThat(contar("SELECT count(*) FROM corona.vendas WHERE id IN (?, ?, ?)", inexistente, deOutro, deOutroCliente)).isZero();
        assertThat(contar("SELECT count(*) FROM corona.creditos_sem_saldo WHERE credito_id = ?", c.credito())).isZero();
        assertThat(credito(c.credito()).get("situacao")).isEqualTo("ABERTO");
    }

    @Test
    void naRotaOnlineAcimaDoSaldoContinua409() throws Exception {
        Cenario c = cenario();
        Map<String, Integer> antes = contagens();
        assertThat(codigoDe(postar(VENDAS, como(c.v()), pedidoDeVenda(UUID.randomUUID(), c.maleta(), c.cliente(),
                List.of(linha(c.p40(), 2)), List.of(pagamentoComCredito("80.00", c.credito()))))
                .andExpect(status().isConflict()))).isEqualTo("CREDITO_ACIMA_DO_SALDO");
        assertThat(contagens()).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM corona.creditos_sem_saldo WHERE credito_id = ?", c.credito())).isZero();
    }

    // --- revisão da dona ---

    @Test
    void manterENaoAceitarSoADonaComObservacaoNoNaoAceitaERepeticao200EAvisoSome() throws Exception {
        Cenario c = cenario();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        enviar(c.v(), List.of(vendaComCredito(c, v1, c.p40(), "40.00"), vendaComCredito(c, v2, c.p25(), "25.00"),
                vendaComCredito(c, v3, c.p25(), "25.00")));
        long antes = jdbc.queryForObject("SELECT count(*) FROM corona.creditos_sem_saldo WHERE situacao = 'EM_REVISAO'", Long.class);
        assertThat(titulosDoTipo(avisos(), FonteDeAvisosDosCreditos.TIPO_SEM_SALDO))
                .containsExactly(antes == 1 ? "Há 1 venda paga com crédito que já tinha acabado."
                        : "Há " + antes + " vendas pagas com crédito que já tinha acabado.");
        assertThat(FonteDeAvisosDosCreditos.aviso(1).orElseThrow().titulo()).isEqualTo("Há 1 venda paga com crédito que já tinha acabado.");
        assertThat(FonteDeAvisosDosCreditos.aviso(2).orElseThrow().titulo()).isEqualTo("Há 2 vendas pagas com crédito que já tinha acabado.");
        assertThat(FonteDeAvisosDosCreditos.aviso(0)).isEmpty();

        JsonNode lista = corpoDe(ler(SEM_SALDO, dona()).andExpect(status().isOk()));
        assertThat(lista.toString()).contains(v2.toString(), v3.toString()).doesNotContain("custo");

        // Só a dona: vendedor 403, sem token 401.
        postar(SEM_SALDO + "/" + v2 + "/manter", como(c.v()), mapa()).andExpect(status().isForbidden());
        mvc.perform(post(SEM_SALDO + "/" + v2 + "/manter").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        ler(SEM_SALDO, como(c.v())).andExpect(status().isForbidden());

        Map<String, Integer> contagens = contagens();
        BigDecimal pagosV2 = somaDosPagamentos(v2);
        JsonNode mantida = corpoDe(postar(SEM_SALDO + "/" + v2 + "/manter", dona(), mapa()).andExpect(status().isOk()));
        assertThat(mantida.get("situacao").asString()).isEqualTo("MANTIDA");
        postar(SEM_SALDO + "/" + v2 + "/manter", dona(), mapa()).andExpect(status().isOk());
        assertThat(codigoDe(postar(SEM_SALDO + "/" + v2 + "/nao-aceitar", dona(), mapa("observacao", "não"))
                .andExpect(status().isConflict()))).isEqualTo("CREDITO_SEM_SALDO_NAO_ESTA_PARA_REVISAO");

        // Não aceitar pede a observação.
        postar(SEM_SALDO + "/" + v3 + "/nao-aceitar", dona(), mapa()).andExpect(status().isBadRequest());
        JsonNode naoAceita = corpoDe(postar(SEM_SALDO + "/" + v3 + "/nao-aceitar", dona(),
                mapa("observacao", "Vendeu sabendo que o crédito tinha acabado")).andExpect(status().isOk()));
        assertThat(naoAceita.get("situacao").asString()).isEqualTo("NAO_ACEITA");
        assertThat(naoAceita.get("observacaoRevisao").asString()).isEqualTo("Vendeu sabendo que o crédito tinha acabado");
        postar(SEM_SALDO + "/" + v3 + "/nao-aceitar", dona(), mapa("observacao", "de novo")).andExpect(status().isOk());
        postar(SEM_SALDO + "/" + UUID.randomUUID() + "/manter", dona(), mapa()).andExpect(status().isNotFound());

        // Nenhuma decisão mexe em dinheiro.
        assertThat(contagens()).isEqualTo(contagens);
        assertThat(somaDosPagamentos(v2)).isEqualByComparingTo(pagosV2);
        assertThat(semSaldo(v2).get("revisada_em")).isNotNull();
        long depois = jdbc.queryForObject("SELECT count(*) FROM corona.creditos_sem_saldo WHERE situacao = 'EM_REVISAO'", Long.class);
        assertThat(depois).isEqualTo(antes - 2);
        if (depois == 0) {
            assertThat(titulosDoTipo(avisos(), FonteDeAvisosDosCreditos.TIPO_SEM_SALDO)).isEmpty();
        }
        creditoFecha(c.credito());
    }

    // --- segundas travas do banco ---

    @Test
    void oBancoRecusaConteudoAlteradoDeleteTransicaoProibidaEPagamentoQueNaoConfere() throws Exception {
        Cenario c = cenario();
        UUID venda = UUID.randomUUID();
        enviar(c.v(), List.of(vendaComCredito(c, UUID.randomUUID(), c.p40(), "40.00"), vendaComCredito(c, venda, c.p25(), "25.00")));
        assertThat(semSaldo(venda).get("situacao")).isEqualTo("EM_REVISAO");

        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            for (String sql : List.of(
                    "UPDATE corona.creditos_sem_saldo SET valor_pedido = 1.00 WHERE id = '%s'",
                    "UPDATE corona.creditos_sem_saldo SET valor_usado = 1.00 WHERE id = '%s'",
                    "UPDATE corona.creditos_sem_saldo SET venda_id = gen_random_uuid() WHERE id = '%s'",
                    "UPDATE corona.creditos_sem_saldo SET deleted_at = now() WHERE id = '%s'",
                    "DELETE FROM corona.creditos_sem_saldo WHERE id = '%s'",
                    "UPDATE corona.creditos_sem_saldo SET situacao = 'MANTIDA' WHERE id = '%s'")) {
                assertThatThrownBy(() -> s.execute(sql.formatted(venda))).as(sql).isInstanceOf(java.sql.SQLException.class);
                con.rollback();
            }
        }
        // Mantida não volta nem passa para "não aceita".
        postar(SEM_SALDO + "/" + venda + "/manter", dona(), mapa()).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_sem_saldo SET situacao = 'EM_REVISAO', revisada_em = NULL,"
                + " revisada_por = NULL WHERE id = ?", venda)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.creditos_sem_saldo SET situacao = 'NAO_ACEITA', observacao_revisao = 'x'"
                + " WHERE id = ?", venda)).isInstanceOf(Exception.class);

        // Linha nova errada: usado >= pedido, pagamento que não confere, venda ou crédito de outro vendedor/cliente.
        UUID outraVenda = vender(c.v(), c.maleta(), c.cliente(), List.of(linha(c.p25(), 1)), List.of(pagamento("25.00", "PIX")));
        UUID pix = jdbc.queryForObject("SELECT id FROM corona.pagamentos_cliente WHERE venda_id = ?", UUID.class, outraVenda);
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID creditoDoOutro = creditoDeSobra(outro, cliente(outro), "100.00", "60.00");
        String inserir = "INSERT INTO corona.creditos_sem_saldo (id, venda_id, credito_id, valor_pedido, valor_usado, registrada_em)"
                + " VALUES (?, ?, ?, ?, ?, now())";
        assertThatThrownBy(() -> jdbc.update(inserir, UUID.randomUUID(), outraVenda, c.credito(), new BigDecimal("10.00"),
                new BigDecimal("10.00"))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(inserir, pix, outraVenda, c.credito(), new BigDecimal("30.00"),
                new BigDecimal("25.00"))).as("pagamento PIX não é o pagamento em crédito").isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(inserir, pix, outraVenda, c.credito(), new BigDecimal("30.00"),
                BigDecimal.ZERO)).as("sem uso não pode existir pagamento com o id").isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(inserir, UUID.randomUUID(), outraVenda, creditoDoOutro, new BigDecimal("10.00"),
                BigDecimal.ZERO)).as("crédito de outro vendedor").isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(inserir, UUID.randomUUID(), outraVenda, c.credito(), BigDecimal.ZERO,
                BigDecimal.ZERO)).as("pedido zero").isInstanceOf(Exception.class);
    }
}
