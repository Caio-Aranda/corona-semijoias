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
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    ServicoDoFechamentoDaMaleta fechamento;

    /** Como {@link #estragar}, mas olhando uma conferência da Fase 7 (C11, C12...). */
    private List<Diferenca> estragarNaFase7(String chave, List<String> tabelas, String... comandos) throws Exception {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            try {
                for (String t : tabelas) {
                    s.execute("ALTER TABLE corona." + t + " DISABLE TRIGGER USER");
                }
                for (String c : comandos) {
                    s.execute(c);
                }
                var comoDono = new JdbcTemplate(new SingleConnectionDataSource(con, true));
                return new ConciliacoesDaFase7(comoDono).conferir(chave).conferencias().get(0).divergencias();
            } finally {
                con.rollback();
            }
        }
    }

    private String saidaDaNota(Caso k, int quantidade) {
        return "INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, nota_id, sync_seq, sync_xid) VALUES ('"
                + UUID.randomUUID() + "', '" + k.maleta() + "', '" + k.peca() + "', 'CONSIGNADO_SAIDA', " + quantidade + ", '" + k.nota()
                + "', nextval('corona.sync_seq_global'), pg_current_xact_id())";
    }

    private String alertaSemSaldo(Caso k) {
        return "INSERT INTO corona.alertas_consignado (id, tipo, vendedor_id, registro_tabela, registro_id, detalhe) VALUES ('"
                + UUID.randomUUID() + "', 'PECA_SEM_SALDO_NA_MALETA', '" + k.v().vendedorId() + "', 'notas_consignado', '" + k.nota()
                + "', '{\"remessa_id\":\"" + k.maleta() + "\",\"produto_id\":\"" + k.peca() + "\"}')";
    }

    /**
     * A saída da nota acima do saldo é aceita com alerta (ADR-0016, dúvida D4): a C12 acusa a peça negativa sem alerta e perdoa
     * a que tem alerta, como a K5 (antes acusava as duas).
     */
    @Test
    void c12PerdoaAPecaNegativaComAlertaComoAK5() throws Exception {
        Caso k = caso();
        assertThat(tem(estragarNaFase7("C12", List.of("movimentos_remessa"), saidaDaNota(k, -50)), k.maleta())).isTrue();
        assertThat(tem(estragarNaFase7("C12", List.of("movimentos_remessa"), saidaDaNota(k, -50), alertaSemSaldo(k)), k.maleta()))
                .isFalse();
    }

    /** A maleta FECHADA (leva 9) entra na C12: limpa quando está certa, acusada quando um movimento sobra. */
    @Test
    void c12ConfereAMaletaFechada() throws Exception {
        Caso k = caso();
        fechamento.iniciar(k.maleta(), usuarios.dona().id());
        int naMaleta = jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ?""", Integer.class, k.maleta(), k.peca());
        fechamento.registrarLeituras(k.maleta(),
                List.of(new ServicoDoFechamentoDaMaleta.Leitura(UUID.randomUUID(), k.peca(), naMaleta)), usuarios.dona().id());
        fechamento.concluir(k.maleta(), usuarios.dona().id());
        assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, k.maleta())).isEqualTo("FECHADA");

        var limpa = new ConciliacoesDaFase7(jdbc).conferir("C12").conferencias().get(0).divergencias();
        assertThat(tem(limpa, k.maleta())).as(limpa.toString()).isFalse();
        var estragada = estragarNaFase7("C12", List.of("movimentos_remessa"),
                "INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, sync_seq, sync_xid) VALUES ('"
                        + UUID.randomUUID() + "', '" + k.maleta() + "', '" + k.peca()
                        + "', 'AJUSTE', 1, nextval('corona.sync_seq_global'), pg_current_xact_id())");
        assertThat(tem(estragada, k.maleta())).isTrue();
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

    // --- a meta (§23.13): K8 a K10 ---

    /** Um acerto da regra META (formato 2), com a cotação em uso do exemplo (Gs. 1 = R$ 0,0009): R$ 630,00 = Gs. 700.000. */
    private Caso casoPelaMeta() throws Exception {
        br.com.coronasemijoias.api.testes.CotacoesDaMassa.doExemplo(jdbc);
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("630.00", 20);
        UUID maleta = maletaEnviada(v, peca, 10);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente, item(peca, maleta, 1, "630.00"));
        UUID acerto = UUID.randomUUID();
        acertos.acertar(ctx(v), new ServicoDeAcertosDaNota.NovoAcerto(acerto, nota, semana, null, List.of(), null, null));
        return new Caso(v, cliente, maleta, peca, nota, acerto, null, semana);
    }

    private void tirarAsCotacoes() throws Exception {
        try (Connection con = conectarComoMigracao()) {
            br.com.coronasemijoias.api.testes.CotacoesDaMassa.tirar(con);
        }
    }

    @Test
    void k8k9k10PassamNoAcertoPelaMeta() throws Exception {
        try {
            Caso k = casoPelaMeta();
            for (String chave : List.of("K8", "K9", "K10")) {
                assertThat(new ConciliacoesDoConsignado(jdbc).conferir(chave).conferencias().get(0).divergencias()).as(chave).isEmpty();
            }
            assertThat(k.acerto()).isNotNull();
        } finally {
            tirarAsCotacoes();
        }
    }

    @Test
    void k8AcusaVdEmGuaraniDiferenteDaConversaoPelaCotacaoDoAcerto() throws Exception {
        try {
            Caso k = casoPelaMeta();
            var achadas = estragar("K8", List.of("acertos_nota"),
                    "UPDATE corona.acertos_nota SET vd_guarani = vd_guarani + 1 WHERE id = '" + k.acerto() + "'");
            assertThat(tem(achadas, k.acerto())).isTrue();
        } finally {
            tirarAsCotacoes();
        }
    }

    @Test
    void k9AcusaPercentualQueNaoEODoLadoDaMetaNaConfiguracaoDoMomento() throws Exception {
        try {
            Caso k = casoPelaMeta();
            // Bateu a meta (700.000 de 700.000), então devia ser 40%: 35% não é o de nenhum lado.
            var achadas = estragar("K9", List.of("acertos_nota"),
                    "UPDATE corona.acertos_nota SET comissao_percentual = 35 WHERE id = '" + k.acerto() + "'");
            assertThat(tem(achadas, k.acerto())).isTrue();
        } finally {
            tirarAsCotacoes();
        }
    }

    @Test
    void k10AcusaPorcentagemInformadaEmOperacaoDeFormato2() throws Exception {
        Caso k = caso();
        usuarios.token(k.v());
        String sincronizado = "INSERT INTO corona.sync_log (id, dispositivo_id, usuario_id, vendedor_id, tipo, formato, resumo, resultado, "
                + "recebido_em, dados_esvaziados_em) SELECT '" + k.acerto() + "', d.id, d.usuario_id, '" + k.v().vendedorId() + "', 'ACERTO_NOTA', %d, repeat('a', 64), "
                + "'ACEITO', now(), now() FROM corona.dispositivos d WHERE d.usuario_id = '" + k.v().id() + "' LIMIT 1";
        assertThat(tem(estragar("K10", List.of("sync_log"), sincronizado.formatted(2)), k.acerto())).isTrue();
        // De operação formato 1, e a que não passou pelo sync, a regra INFORMADA continua valendo.
        assertThat(tem(estragar("K10", List.of("sync_log"), sincronizado.formatted(1)), k.acerto())).isFalse();
        assertThat(tem(estragar("K10", List.of("sync_log")), k.acerto())).isFalse();
    }
}
