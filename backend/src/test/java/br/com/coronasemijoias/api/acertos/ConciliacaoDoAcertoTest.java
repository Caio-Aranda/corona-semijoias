package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.ConciliacoesNosTestes;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * As conciliações do acerto zeradas com a massa do teste (tarefa 7.5; ADR-0012, item 17): C2, C3, C8 e C9, mais "estoque central
 * + maletas" da Fase 3. Dois vendedores, um acima e outro abaixo da meta, estorno e devolução atrasada. E cada conciliação ACUSA a
 * diferença quando o teste estraga um dado de propósito (pelo dono das tabelas, com os gatilhos desligados e a transação desfeita).
 * Desde a 7.8 todas rodam pela consulta de produção (ConciliacoesDaFase7), a mesma da rota.
 */
class ConciliacaoDoAcertoTest extends ApoioDoFechamento {

    private UsuarioDeTeste acima;
    private UsuarioDeTeste abaixo;

    @BeforeEach
    void amassa() throws Exception {
        acima = usuarios.criarVendedor();
        abaixo = usuarios.criarVendedor();
        UUID peca = peca("50.00", 60);

        // O vendedor "acima": meta baixa (40%), uma venda estornada, uma falta cobrada.
        configurarComissao(1, 30, 40);
        UUID c1 = cliente(acima);
        UUID m1 = maletaConferida(acima, semana(), peca, 5);
        vender(acima, m1, c1, peca, 2, "100.00");
        UUID estornada = vender(acima, m1, c1, peca, 1, "50.00");
        postar("/api/dona/vendas/" + estornada + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "erro"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        UUID acerto1 = iniciar(acima, null);
        bipar(acerto1, peca, 2, "BOA");
        // 5 enviadas, 2 vendidas, 1 estornada e devolvida à maleta: saldo 4; contadas 2; faltam 2.
        fechar(acerto1, pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(peca, "COBRAR_NO_ACERTO", null)), List.of(), "110.00"));

        // O vendedor "abaixo": meta alta (30%), uma venda e uma devolução atrasada (peça para a loja) no acerto seguinte.
        configurarComissao(700_000, 30, 40);
        UUID c2 = cliente(abaixo);
        UUID m2 = maletaConferida(abaixo, semana(), peca, 4);
        UUID venda = vender(abaixo, m2, c2, peca, 2, "100.00");
        UUID acerto2 = iniciar(abaixo, null);
        bipar(acerto2, peca, 2, "BOA");
        fechar(acerto2, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "70.00"));
        postar("/api/dona/devolucoes", dona(), mapa("id", UUID.randomUUID(), "vendaId", venda, "motivo", "ARREPENDIMENTO",
                "formaReembolso", "DINHEIRO", "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        maletaConferida(abaixo, semana(), peca, 2);
        UUID acerto3 = iniciar(abaixo, null);
        bipar(acerto3, peca, 3, "BOA");
        fechar(acerto3, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "-35.00"));
    }

    private java.util.Set<UUID> referencias() {
        return ConciliacoesNosTestes.referenciasDosVendedores(jdbc, List.of(acima.vendedorId(), abaixo.vendedorId()));
    }

    private int c(JdbcTemplate comoJdbc, String chave) {
        return ConciliacoesNosTestes.contar(comoJdbc, chave, referencias());
    }

    // --- a massa está zerada ---

    @Test
    void asConciliacoesDoAcertoVemZeradasComAMassa() throws Exception {
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE vendedor_id IN (?, ?) AND status = 'ACERTADO'", acima.vendedorId(), abaixo.vendedorId()))
                .isEqualTo(3);
        for (String chave : List.of("C2", "C3", "C7", "C8", "C9")) {
            assertThat(c(jdbc, chave)).as(chave).isZero();
        }
        // Um acima da meta (40%) e outro abaixo (30%).
        assertThat(jdbc.queryForObject("SELECT percentual_comissao FROM corona.acertos WHERE vendedor_id = ? ORDER BY fechado_em LIMIT 1",
                BigDecimal.class, acima.vendedorId())).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("SELECT percentual_comissao FROM corona.acertos WHERE vendedor_id = ? ORDER BY fechado_em LIMIT 1",
                BigDecimal.class, abaixo.vendedorId())).isEqualByComparingTo("30");
        assertThat(estoque.conciliar().diferencasComMaletas()).as("estoque central + maletas").isEmpty();
        assertThat(estoque.conciliar().divergencias()).isEmpty();
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
    void c3AcusaComissaoApagada() throws Exception {
        estragar(List.of("comissoes"), List.of("""
                DELETE FROM corona.comissoes WHERE id = (SELECT id FROM corona.comissoes WHERE vendedor_id IN ('""" + acima.vendedorId() + "', '"
                + abaixo.vendedorId() + "') AND tipo = 'COMISSAO' LIMIT 1)"), j -> assertThat(c(j, "C3")).isPositive());
    }

    @Test
    void c8AcusaPerdaApagadaEMaletaComSaldo() throws Exception {
        estragar(List.of("movimentos_remessa"), List.of("""
                DELETE FROM corona.movimentos_remessa WHERE id = (SELECT m.id FROM corona.movimentos_remessa m JOIN corona.remessas r ON r.id = m.remessa_id
                 WHERE r.vendedor_id IN ('""" + acima.vendedorId() + "', '" + abaixo.vendedorId() + "') AND m.tipo = 'PERDA' LIMIT 1)"),
                j -> assertThat(c(j, "C8")).isPositive());
    }

    @Test
    void c9eC2AcusamEventoApagado() throws Exception {
        // O evento da venda de 100,00 do vendedor de cima (sem estorno), com a comissão dele.
        String evento = "(SELECT id FROM corona.acerto_eventos WHERE vendedor_id = '" + acima.vendedorId()
                + "' AND origem_tipo = 'VENDA' AND valor = 100.00 ORDER BY id LIMIT 1)";
        estragar(List.of("acerto_eventos", "comissoes"), List.of("DELETE FROM corona.comissoes WHERE evento_id = " + evento,
                "DELETE FROM corona.acerto_eventos WHERE id = " + evento), j -> {
                    assertThat(c(j, "C9")).isPositive();
                    assertThat(c(j, "C2")).isPositive();
                });
    }
}
