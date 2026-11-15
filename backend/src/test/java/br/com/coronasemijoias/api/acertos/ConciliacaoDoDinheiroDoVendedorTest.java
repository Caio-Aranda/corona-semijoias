package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.ConciliacoesNosTestes;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * As conciliações do dinheiro do vendedor zeradas com a massa do teste (tarefa 7.6; ADR-0012, item 17): C2 (acerto: líquido menos
 * crédito = recebido menos pago mais ficou devendo, e cada parte confere com os recebimentos, as contas e o caixa), C4 (cada
 * recebimento e cada pagamento ao vendedor com exatamente um lançamento de caixa e o saldo do caixa igual à soma dos
 * lançamentos) e C5 nas contas do nível 2 (os atalhos são a soma das baixas). Cada uma ACUSA a diferença quando o teste estraga um
 * dado de propósito (pelo dono das tabelas, com os gatilhos desligados e a transação desfeita). Desde a 7.8 todas rodam pela consulta
 * de produção (ConciliacoesDaFase7), a mesma da rota.
 */
class ConciliacaoDoDinheiroDoVendedorTest extends ApoioDoFechamento {

    private UsuarioDeTeste x;
    private UsuarioDeTeste y;

    private static LocalDate hoje() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    private UUID acertoDe35(UsuarioDeTeste v, UUID cliente, java.util.Map<String, Object> pagamento) throws Exception {
        UUID peca = peca("50.00", 30);
        UUID maleta = maletaConferida(v, semana(), peca, 4);
        vender(v, maleta, cliente, peca, 1, "50.00");
        UUID acerto = iniciar(v, null);
        bipar(acerto, peca, 3, "BOA");
        var pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00");
        pedido.remove("pagamento");
        if (pagamento != null) {
            pedido.put("pagamento", pagamento);
        }
        fechar(acerto, pedido).andExpect(status().isCreated());
        return acerto;
    }

    private static java.util.Map<String, Object> restante(int parcelas) {
        return mapa("parcelas", parcelas, "primeiroVencimento", hoje().plusDays(30).toString());
    }

    @BeforeEach
    void amassa() throws Exception {
        x = usuarios.criarVendedor();
        y = usuarios.criarVendedor();
        UUID cx = cliente(x);
        // X, acerto 1: R$ 10,00 em dinheiro, R$ 5,00 em Transferência, boleto de R$ 10,00 e o resto (10,00) em uma parcela.
        UUID acerto1 = acertoDe35(x, cx, mapa(
                "recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "valor", "10.00"),
                        mapa("id", UUID.randomUUID(), "forma", "PIX", "valor", "5.00")),
                "boleto", mapa("valor", "10.00", "vencimento", hoje().plusDays(10).toString()), "restante", restante(1)));
        UUID boleto = jdbc.queryForObject("SELECT id FROM corona.contas_receber WHERE origem_id = ? AND forma_prevista = 'BOLETO'", UUID.class, acerto1);
        UUID comum = jdbc.queryForObject("SELECT id FROM corona.contas_receber WHERE origem_id = ? AND forma_prevista IS NULL", UUID.class, acerto1);
        // Um recebimento avulso que é estornado, e o boleto pago.
        UUID avulso = UUID.randomUUID();
        postar("/api/dona/recebimentos-vendedor", dona(), mapa("id", avulso, "vendedorId", x.vendedorId(), "forma", "DINHEIRO", "valor", "4.00",
                "contaId", comum)).andExpect(status().isCreated());
        postar("/api/dona/recebimentos-vendedor/" + avulso + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Lancei errado"))
                .andExpect(status().isCreated());
        postar("/api/dona/recebimentos-vendedor", dona(), mapa("id", UUID.randomUUID(), "vendedorId", x.vendedorId(), "forma", "BOLETO",
                "valor", "10.00", "contaId", boleto)).andExpect(status().isCreated());
        // X, acerto 2: paga tudo em dinheiro e 3,00 da conta antiga; depois o recebimento do acerto é estornado (vira conta nova).
        UUID dinheiroDoAcerto2 = UUID.randomUUID();
        acertoDe35(x, cx, mapa("recebimentos", List.of(mapa("id", dinheiroDoAcerto2, "forma", "DINHEIRO", "valor", "35.00")),
                "contasAntigas", List.of(mapa("id", UUID.randomUUID(), "contaId", comum, "forma", "PIX", "valor", "3.00"))));
        postar("/api/dona/recebimentos-vendedor/" + dinheiroDoAcerto2 + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Foi engano"))
                .andExpect(status().isCreated());

        // Y: acerto a favor pago na hora, depois um acerto a favor como crédito, abatido no seguinte.
        UUID cy = cliente(y);
        UUID peca = peca("50.00", 30);
        UUID maleta = maletaConferida(y, semana(), peca, 3);
        UUID venda = vender(y, maleta, cy, peca, 1, "50.00");
        UUID a0 = iniciar(y, null);
        bipar(a0, peca, 2, "BOA");
        fechar(a0, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "35.00")).andExpect(status().isCreated());
        postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo", "ARREPENDIMENTO", "formaReembolso",
                "DINHEIRO", "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA")))).andExpect(status().isCreated());
        UUID a1 = UUID.randomUUID();
        postar(ACERTOS_DA_DONA, dona(), mapa("id", a1, "vendedorId", y.vendedorId(), "semanaId", semana())).andExpect(status().isCreated());
        bipar(a1, peca, 1, "BOA");
        var pedido = pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "-35.00");
        pedido.put("pagamento", mapa("aFavorDoVendedor", "PAGAR_NA_HORA", "formaDoPagamento", "DINHEIRO"));
        fechar(a1, pedido).andExpect(status().isCreated());
    }

    private java.util.Set<UUID> referencias() {
        return ConciliacoesNosTestes.referenciasDosVendedores(jdbc, List.of(x.vendedorId(), y.vendedorId()));
    }

    private int c(JdbcTemplate comoJdbc, String chave) {
        return ConciliacoesNosTestes.contar(comoJdbc, chave, referencias());
    }

    // --- a massa está zerada ---

    @Test
    void asConciliacoesDoDinheiroVemZeradasComAMassa() throws Exception {
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE vendedor_id IN (?, ?) AND status = 'ACERTADO'", x.vendedorId(), y.vendedorId()))
                .isEqualTo(4);
        assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE vendedor_id = ?", x.vendedorId())).isEqualTo(8);
        for (String chave : List.of("C2", "C4", "C5")) {
            assertThat(c(jdbc, chave)).as(chave).isZero();
        }
    }

    // --- e cada uma acusa a diferença quando o dado é estragado (a consulta é a de produção, a mesma da rota) ---

    private void estragar(List<String> tabelas, List<String> comandos, java.util.function.Consumer<JdbcTemplate> conferencia) throws Exception {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            for (String t : tabelas) {
                s.execute("ALTER TABLE corona." + t + " DISABLE TRIGGER USER");
            }
            try {
                for (String comando : comandos) {
                    s.execute(comando);
                }
                conferencia.accept(ConciliacoesNosTestes.naConexao(con));
            } finally {
                con.rollback();
            }
        }
    }

    @Test
    void c2AcusaRecebidoQueNaoBateComOsRecebimentos() throws Exception {
        estragar(List.of("recebimentos_vendedor"), List.of("""
                UPDATE corona.recebimentos_vendedor SET valor = valor + 1 WHERE id = (SELECT id FROM corona.recebimentos_vendedor
                 WHERE vendedor_id = '""" + x.vendedorId() + "' AND aplicado_em = 'ACERTO' AND estorna_id IS NULL ORDER BY id LIMIT 1)"),
                j -> assertThat(c(j, "C2")).isPositive());
    }

    @Test
    void c2AcusaContaDoRestanteApagadaEPagoAoVendedorSemSaida() throws Exception {
        estragar(List.of("contas_receber", "baixas_receber"), List.of("""
                DELETE FROM corona.baixas_receber WHERE conta_id = (SELECT id FROM corona.contas_receber WHERE vendedor_id = '""" + x.vendedorId()
                + "' AND origem_tipo = 'ACERTO' AND forma_prevista IS NULL ORDER BY id LIMIT 1)", """
                DELETE FROM corona.contas_receber WHERE id = (SELECT id FROM corona.contas_receber WHERE vendedor_id = '""" + x.vendedorId()
                + "' AND origem_tipo = 'ACERTO' AND forma_prevista IS NULL ORDER BY id LIMIT 1)"),
                j -> assertThat(c(j, "C2")).isPositive());
        estragar(List.of("lancamentos_caixa"), List.of("""
                DELETE FROM corona.lancamentos_caixa WHERE origem_tipo = 'ACERTO_A_FAVOR_DO_VENDEDOR'
                   AND origem_id IN (SELECT id FROM corona.acertos WHERE vendedor_id = '""" + y.vendedorId() + "')"),
                j -> assertThat(c(j, "C2")).isPositive());
    }

    @Test
    void c4AcusaLancamentoApagadoELancamentoDeOutroValor() throws Exception {
        estragar(List.of("lancamentos_caixa"), List.of("""
                DELETE FROM corona.lancamentos_caixa WHERE id = (SELECT l.id FROM corona.lancamentos_caixa l JOIN corona.recebimentos_vendedor r
                 ON r.id = l.origem_id WHERE r.vendedor_id = '""" + x.vendedorId() + "' ORDER BY l.id LIMIT 1)"),
                j -> assertThat(c(j, "C4")).isPositive());
        estragar(List.of("lancamentos_caixa"), List.of("""
                UPDATE corona.lancamentos_caixa SET valor = valor * 2 WHERE id = (SELECT l.id FROM corona.lancamentos_caixa l JOIN
                 corona.recebimentos_vendedor r ON r.id = l.origem_id WHERE r.vendedor_id = '""" + x.vendedorId() + "' AND l.valor > 0 ORDER BY l.id LIMIT 1)"),
                j -> assertThat(c(j, "C4")).isPositive());
    }

    @Test
    void c4AcusaLancamentoSemOrigem() throws Exception {
        estragar(List.of("lancamentos_caixa", "recebimentos_vendedor"), List.of("""
                UPDATE corona.lancamentos_caixa SET origem_id = '00000000-0000-7000-8000-000000000001' WHERE id = (SELECT l.id
                  FROM corona.lancamentos_caixa l JOIN corona.recebimentos_vendedor r ON r.id = l.origem_id WHERE r.vendedor_id = '"""
                + x.vendedorId() + "' ORDER BY l.id LIMIT 1)"), j -> assertThat(c(j, "C4")).isPositive());
    }

    @Test
    void c5AcusaAtalhoDaContaQueNaoBateComAsBaixas() throws Exception {
        estragar(List.of("contas_receber"), List.of("""
                UPDATE corona.contas_receber SET valor_pago = valor_pago + 1 WHERE id = (SELECT id FROM corona.contas_receber
                 WHERE vendedor_id = '""" + x.vendedorId() + "' AND forma_prevista IS NULL AND origem_tipo = 'ACERTO' ORDER BY id LIMIT 1)"),
                j -> assertThat(c(j, "C5")).isPositive());
        estragar(List.of("baixas_receber"), List.of("""
                DELETE FROM corona.baixas_receber WHERE id = (SELECT b.id FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id
                 WHERE c.vendedor_id = '""" + x.vendedorId() + "' AND b.valor > 0 "
                + "AND NOT EXISTS (SELECT 1 FROM corona.baixas_receber e WHERE e.estorna_id = b.id) ORDER BY b.id LIMIT 1)"),
                j -> assertThat(c(j, "C5")).isPositive());
    }
}
