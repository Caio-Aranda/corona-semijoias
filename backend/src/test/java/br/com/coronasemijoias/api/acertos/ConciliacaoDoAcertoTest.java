package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * As conciliações do acerto zeradas com a massa do teste (tarefa 7.5; ADR-0012, item 17): C2, C3, C8 e C9, mais "estoque central
 * + maletas" da Fase 3. Dois vendedores, um acima e outro abaixo da meta, estorno e devolução atrasada. E cada conciliação ACUSA a
 * diferença quando o teste estraga um dado de propósito (pelo dono das tabelas, com os gatilhos desligados e a transação desfeita).
 * A consulta formal e a rota das conciliações são da 7.8.
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

    private String vendedores() {
        return "'" + acima.vendedorId() + "', '" + abaixo.vendedorId() + "'";
    }

    // --- as conciliações, como SQL que conta o que está errado ---

    private int c2(Statement s) throws SQLException {
        return unico(s, """
                SELECT count(*) FROM corona.acertos a WHERE a.vendedor_id IN (""" + vendedores() + """
                ) AND a.status = 'ACERTADO' AND (a.vendido_bruto - a.descontos - a.devolvido - a.comissao + a.faltas_cobradas <> a.liquido
                   OR a.liquido - a.credito_usado <> a.recebido_no_acerto - a.pago_ao_vendedor + a.ficou_devendo
                   OR a.vendido_liquido <> (SELECT coalesce(sum(e.valor), 0) FROM corona.acerto_eventos e WHERE e.acerto_id = a.id))""");
    }

    private int c3(Statement s) throws SQLException {
        int somas = unico(s, """
                SELECT count(*) FROM corona.vendedores v WHERE v.id IN (""" + vendedores() + """
                ) AND coalesce((SELECT sum(valor) FROM corona.comissoes WHERE vendedor_id = v.id), 0)
                  <> coalesce((SELECT sum(comissao) FROM corona.acertos WHERE vendedor_id = v.id AND status = 'ACERTADO'), 0)""");
        int linhas = 0;
        try (ResultSet rs = s.executeQuery("""
                SELECT c.base, c.percentual, c.valor, a.percentual_comissao FROM corona.comissoes c JOIN corona.acertos a ON a.id = c.acerto_id
                 WHERE c.tipo = 'COMISSAO' AND c.vendedor_id IN (""" + vendedores() + ")")) {
            while (rs.next()) {
                boolean certo = Dinheiro.aplicarPorcentagem(rs.getBigDecimal(1), rs.getBigDecimal(2)).compareTo(rs.getBigDecimal(3)) == 0
                        && rs.getBigDecimal(2).compareTo(rs.getBigDecimal(4)) == 0;
                if (!certo) {
                    linhas++;
                }
            }
        }
        int estornos = unico(s, """
                SELECT count(*) FROM (SELECT c.venda_id FROM corona.comissoes c WHERE c.vendedor_id IN (""" + vendedores() + """
                ) GROUP BY c.venda_id HAVING sum(c.valor) < 0) x""");
        return somas + linhas + estornos;
    }

    private int c8(Statement s) throws SQLException {
        int maletas = unico(s, """
                SELECT count(*) FROM corona.remessas r WHERE r.vendedor_id IN (""" + vendedores() + """
                ) AND r.status = 'ACERTADA' AND (
                  SELECT coalesce(sum(t.q), 0) FROM (
                    SELECT i.qtd_enviada AS q FROM corona.itens_remessa i WHERE i.remessa_id = r.id AND i.deleted_at IS NULL
                    UNION ALL SELECT m.quantidade FROM corona.movimentos_remessa m WHERE m.remessa_id = r.id) t) <> 0""");
        int faltas = unico(s, """
                SELECT count(*) FROM corona.pendencias_acerto p WHERE p.tipo = 'FALTA' AND p.remessa_id IS NOT NULL AND p.vendedor_id IN (""" + vendedores() + """
                ) AND p.quantidade <> coalesce((SELECT -sum(m.quantidade) FROM corona.movimentos_remessa m
                       WHERE m.remessa_id = p.remessa_id AND m.produto_id = p.produto_id AND m.tipo = 'PERDA'), 0)""");
        return maletas + faltas;
    }

    private int c9(Statement s) throws SQLException {
        int duplicados = unico(s, """
                SELECT count(*) FROM (SELECT origem_tipo, origem_id FROM corona.acerto_eventos GROUP BY 1, 2 HAVING count(*) > 1) x""");
        int soltas = unico(s, """
                SELECT count(*) FROM corona.vendas v JOIN corona.remessas r ON r.id = v.remessa_id
                 WHERE v.vendedor_id IN (""" + vendedores() + """
                ) AND r.status = 'ACERTADA' AND NOT EXISTS (SELECT 1 FROM corona.acerto_eventos e WHERE e.origem_tipo = 'VENDA' AND e.origem_id = v.id)""");
        return duplicados + soltas;
    }

    private static int unico(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // --- a massa está zerada ---

    @Test
    void asConciliacoesDoAcertoVemZeradasComAMassa() throws Exception {
        try (Connection con = conectarComoAplicacao(); Statement s = con.createStatement()) {
            assertThat(contar("SELECT count(*) FROM corona.acertos WHERE vendedor_id IN (?, ?) AND status = 'ACERTADO'", acima.vendedorId(), abaixo.vendedorId()))
                    .isEqualTo(3);
            assertThat(c2(s)).as("C2").isZero();
            assertThat(c3(s)).as("C3").isZero();
            assertThat(c8(s)).as("C8").isZero();
            assertThat(c9(s)).as("C9").isZero();
            // Um acima da meta (40%) e outro abaixo (30%).
            assertThat(jdbc.queryForObject("SELECT percentual_comissao FROM corona.acertos WHERE vendedor_id = ? ORDER BY fechado_em LIMIT 1",
                    BigDecimal.class, acima.vendedorId())).isEqualByComparingTo("40");
            assertThat(jdbc.queryForObject("SELECT percentual_comissao FROM corona.acertos WHERE vendedor_id = ? ORDER BY fechado_em LIMIT 1",
                    BigDecimal.class, abaixo.vendedorId())).isEqualByComparingTo("30");
        }
        assertThat(estoque.conciliar().diferencasComMaletas()).as("estoque central + maletas").isEmpty();
        assertThat(estoque.conciliar().divergencias()).isEmpty();
    }

    // --- e cada uma acusa a diferença quando o dado é estragado ---

    private void estragar(List<String> tabelas, List<String> comandos, java.util.function.Consumer<Statement> conferencia) throws Exception {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            for (String t : tabelas) {
                s.execute("ALTER TABLE corona." + t + " DISABLE TRIGGER USER");
            }
            try {
                for (String c : comandos) {
                    s.execute(c);
                }
                conferencia.accept(s);
            } finally {
                con.rollback();
            }
        }
    }

    private static <T> T semExcecao(java.util.concurrent.Callable<T> c) {
        try {
            return c.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void c3AcusaComissaoApagada() throws Exception {
        estragar(List.of("comissoes"), List.of("""
                DELETE FROM corona.comissoes WHERE id = (SELECT id FROM corona.comissoes WHERE vendedor_id IN (""" + vendedores()
                + ") AND tipo = 'COMISSAO' LIMIT 1)"), s -> assertThat(semExcecao(() -> c3(s))).isPositive());
    }

    @Test
    void c8AcusaPerdaApagadaEMaletaComSaldo() throws Exception {
        estragar(List.of("movimentos_remessa"), List.of("""
                DELETE FROM corona.movimentos_remessa WHERE id = (SELECT m.id FROM corona.movimentos_remessa m JOIN corona.remessas r ON r.id = m.remessa_id
                 WHERE r.vendedor_id IN (""" + vendedores() + ") AND m.tipo = 'PERDA' LIMIT 1)"),
                s -> assertThat(semExcecao(() -> c8(s))).isPositive());
    }

    @Test
    void c9eC2AcusamEventoApagado() throws Exception {
        // O evento da venda de 100,00 do vendedor de cima (sem estorno), com a comissão dele.
        String evento = "(SELECT id FROM corona.acerto_eventos WHERE vendedor_id = '" + acima.vendedorId()
                + "' AND origem_tipo = 'VENDA' AND valor = 100.00 ORDER BY id LIMIT 1)";
        estragar(List.of("acerto_eventos", "comissoes"), List.of("DELETE FROM corona.comissoes WHERE evento_id = " + evento,
                "DELETE FROM corona.acerto_eventos WHERE id = " + evento), s -> {
                    assertThat(semExcecao(() -> c9(s))).isPositive();
                    assertThat(semExcecao(() -> c2(s))).isPositive();
                });
    }
}
