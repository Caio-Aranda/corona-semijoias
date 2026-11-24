package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7;
import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Diferenca;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * As conciliações do consignado (ADR-0016, item 17): cada uma acusa um defeito plantado de propósito (pelo dono das tabelas, com
 * os gatilhos de usuário desligados, numa transação desfeita no fim) e passa no caso limpo. Escritos antes do código.
 */
class ConciliacoesDoConsignadoTest extends ApoioDoConsignado {

    private record Caso(UsuarioDeTeste v, UUID cliente, UUID maleta, UUID peca, UUID nota, UUID acerto, UUID recibo, UUID semana) {
    }

    private Caso caso() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, peca, 10);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente, item(peca, maleta, 5, "100.00"));
        UUID acerto = acertar(v, nota, semana, "30", List.of(volta(peca, maleta, 2)), 2, paga("PIX", "100.00"));
        return new Caso(v, cliente, maleta, peca, nota, acerto, reciboDoAcerto(acerto), semana);
    }

    private List<Diferenca> estragar(String chave, List<String> tabelas, String... comandos) throws Exception {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            try {
                for (String t : tabelas) {
                    s.execute("ALTER TABLE corona." + t + " DISABLE TRIGGER USER");
                }
                for (String c : comandos) {
                    s.execute(c);
                }
                var comoDono = new JdbcTemplate(new SingleConnectionDataSource(con, true));
                return new ConciliacoesDoConsignado(comoDono).conferir(chave).conferencias().get(0).divergencias();
            } finally {
                con.rollback();
            }
        }
    }

    private static boolean tem(List<Diferenca> achadas, UUID referencia) {
        return achadas.stream().anyMatch(d -> referencia.equals(d.referencia()));
    }

    @Test
    void casoLimpoPassaEmTodas() throws Exception {
        Caso k = caso();
        var r = new ConciliacoesDoConsignado(jdbc).conferir();
        for (var c : r.conferencias()) {
            assertThat(c.divergencias()).as(c.chave()).isEmpty();
        }
        assertThat(r.tudoCerto()).isTrue();
        assertThat(k.nota()).isNotNull();
    }

    /**
     * As conciliações do estoque (C7, C11 e C12) contam a peça que saiu da maleta na nota e está com o cliente (CONSIGNADO_SAIDA) e
     * a que voltou no acerto da nota (CONSIGNADO_RETORNO), como a C7 já fazia: no servidor de teste de 24/11/2026, a C11 e a C12
     * acusavam toda peça em nota de consignado.
     */
    @Test
    void estoqueCompletoEMaletaContamAsPecasComOCliente() throws Exception {
        Caso k = caso();
        var fase7 = new ConciliacoesDaFase7(jdbc);
        for (String chave : List.of("C7", "C11", "C12")) {
            var achadas = fase7.conferir(chave).conferencias().get(0).divergencias();
            assertThat(tem(achadas, k.peca()) || tem(achadas, k.maleta())).as(chave + ": " + achadas).isFalse();
        }
    }

    @Test
    void k1AcusaSituacaoGravadaDiferenteDaRecalculada() throws Exception {
        Caso k = caso();
        var achadas = estragar("K1", List.of("notas_consignado"),
                "UPDATE corona.notas_consignado SET situacao = 'FECHADO' WHERE id = '" + k.nota() + "'");
        assertThat(tem(achadas, k.nota())).isTrue();
    }

    @Test
    void k2AcusaTotalDoAcertoDiferenteDosItens() throws Exception {
        Caso k = caso();
        var achadas = estragar("K2", List.of("itens_acerto_nota"),
                "UPDATE corona.itens_acerto_nota SET preco_unitario = preco_unitario + 1 WHERE acerto_id = '" + k.acerto() + "'");
        assertThat(tem(achadas, k.acerto())).isTrue();
    }

    @Test
    void k3AcusaAplicacoesAcimaDoBruto() throws Exception {
        Caso k = caso();
        var achadas = estragar("K3", List.of("aplicacoes_recibo"),
                "UPDATE corona.aplicacoes_recibo SET valor = valor + 5000 WHERE acerto_id = '" + k.acerto() + "'");
        assertThat(tem(achadas, k.acerto())).isTrue();
    }

    @Test
    void k4AcusaTotalDoReciboDiferenteDasFormasEAplicacoesAcimaDoTotal() throws Exception {
        Caso k = caso();
        var total = estragar("K4", List.of("recibos_consignado"),
                "UPDATE corona.recibos_consignado SET total = total + 5 WHERE id = '" + k.recibo() + "'");
        assertThat(tem(total, k.recibo())).isTrue();
        var aplicacoes = estragar("K4", List.of("aplicacoes_recibo"),
                "UPDATE corona.aplicacoes_recibo SET valor = valor + 3 WHERE recibo_id = '" + k.recibo() + "'");
        assertThat(tem(aplicacoes, k.recibo())).isTrue();
    }

    @Test
    void k5AcusaMaletaNegativaSemAlertaEPerdoaComAlerta() throws Exception {
        Caso k = caso();
        var achadas = estragar("K5", List.of("movimentos_remessa"),
                "INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, nota_id, sync_seq, sync_xid) VALUES ('"
                        + UUID.randomUUID() + "', '" + k.maleta() + "', '" + k.peca() + "', 'CONSIGNADO_SAIDA', -50, '" + k.nota() + "', nextval('corona.sync_seq_global'), pg_current_xact_id())");
        assertThat(tem(achadas, k.maleta())).isTrue();
        var comAlerta = estragar("K5", List.of("movimentos_remessa"),
                "INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, nota_id, sync_seq, sync_xid) VALUES ('"
                        + UUID.randomUUID() + "', '" + k.maleta() + "', '" + k.peca() + "', 'CONSIGNADO_SAIDA', -50, '" + k.nota() + "', nextval('corona.sync_seq_global'), pg_current_xact_id())",
                "INSERT INTO corona.alertas_consignado (id, tipo, vendedor_id, registro_tabela, registro_id, detalhe) VALUES ('"
                        + UUID.randomUUID() + "', 'PECA_SEM_SALDO_NA_MALETA', '" + k.v().vendedorId() + "', 'notas_consignado', '"
                        + k.nota() + "', '{\"remessa_id\":\"" + k.maleta() + "\",\"produto_id\":\"" + k.peca() + "\"}')");
        assertThat(tem(comAlerta, k.maleta())).isFalse();
    }

    @Test
    void k6AcusaDevolucaoQueNaoBateComODVDoAcerto() throws Exception {
        Caso k = caso();
        var achadas = estragar("K6", List.of("itens_acerto_nota"),
                "UPDATE corona.itens_acerto_nota SET qtd_dv = 0 WHERE acerto_id = '" + k.acerto() + "'");
        assertThat(tem(achadas, k.acerto())).isTrue();
    }

    @Test
    void k7NaoAcusaNadaComUmaSemanaAbertaPorRepresentante() throws Exception {
        Caso k = caso();
        assertThat(new ConciliacoesDoConsignado(jdbc).conferir("K7").conferencias().get(0).divergencias()).isEmpty();
        assertThat(k.semana()).isNotNull();
    }
}
