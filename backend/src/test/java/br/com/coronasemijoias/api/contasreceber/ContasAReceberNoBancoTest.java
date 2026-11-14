package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * As regras das contas a receber do cliente que o PRÓPRIO BANCO garante (tarefa 7.3; ADR-0012, item 7; invariantes 2, 5
 * e 7 do plano), com SQL direto: o serviço pode errar, o banco não deixa gravar errado. Escrito antes da migração V041.
 */
class ContasAReceberNoBancoTest extends ApoioDasContas {

    private UsuarioDeTeste vendedor;
    private UUID cliente;
    private UUID venda;

    @BeforeEach
    void umaVendaPaga() throws Exception {
        conciliar = false;
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
        UUID peca = peca("100.00", 5);
        UUID maleta = maleta(vendedor, peca, 5);
        // À vista, sem parcela nenhuma: as linhas de contas_receber deste teste são todas de SQL direto.
        venda = vender(vendedor, maleta, cliente, List.of(linha(peca, 1)), List.of(pagamento("100.00", "DINHEIRO")));
    }

    /**
     * Estes testes gravam de propósito pagamentos e baixas que não fecham com a venda. Como o banco é dividido entre os
     * testes (e as conferências olham todas as vendas concluídas), a venda sai de cena por SQL direto: estornada, sem o
     * serviço (que mexeria nas parcelas de mentira).
     */
    @AfterEach
    void tirarAVendaDeCena() {
        jdbc.update("""
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = now(), motivo_estorno = 'teste do banco'
                 WHERE id = ? AND status = 'CONCLUIDA'""", UUID.randomUUID(), venda);
    }

    // --- ajudantes de SQL ---

    private UUID conta(int parcela, int total, String vencimento, String valor) {
        UUID id = UuidV7.gerar();
        jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                    total_parcelas, vencimento, valor)
                VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, ?, ?, ?::date, ?)""", id, cliente, venda, venda, parcela, total,
                vencimento, v(valor));
        return id;
    }

    private UUID pagamentoPosterior(String valor, String forma) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior)
                VALUES (?, ?, 'PAGAMENTO', ?, ?, now(), true)""", id, venda, forma, v(valor));
        return id;
    }

    private UUID baixa(UUID conta, String tipo, String valor, UUID pagamento, String motivo, UUID estorna) {
        UUID id = UuidV7.gerar();
        jdbc.update("""
                INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, pagamento_cliente_id,
                    motivo_cancelamento, estorna_id)
                VALUES (?, ?, ?, ?, now(), ?, ?, ?)""", id, conta, tipo, v(valor), pagamento, motivo, estorna);
        return id;
    }

    private void atalhos(UUID conta, String pago, String cancelado, String status) {
        jdbc.update("UPDATE corona.contas_receber SET valor_pago = ?, valor_cancelado = ?, status = ? WHERE id = ?",
                v(pago), v(cancelado), status, conta);
    }

    private static void recusa(Runnable comando) {
        assertThatThrownBy(comando::run).isInstanceOf(DataAccessException.class);
    }

    // --- contas_receber: o que entra ---

    @Test
    void parcelaValidaNasceAbertaComOsAtalhosZerados() {
        UUID id = conta(1, 2, "2030-01-31", "60.00");
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT status, valor_pago, valor_cancelado, vencimento FROM corona.contas_receber WHERE id = ?", id);
        assertThat(c.get("status")).isEqualTo("ABERTA");
        assertThat((BigDecimal) c.get("valor_pago")).isEqualByComparingTo("0");
        assertThat((BigDecimal) c.get("valor_cancelado")).isEqualByComparingTo("0");
        assertThat(c.get("vencimento")).hasToString("2030-01-31");
    }

    @Test
    void valorZeroOuNegativoEParcelaForaDaFaixaSaoRecusados() {
        recusa(() -> conta(1, 1, "2030-01-31", "0.00"));
        recusa(() -> conta(1, 1, "2030-01-31", "-1.00"));
        recusa(() -> conta(0, 1, "2030-01-31", "1.00"));
        recusa(() -> conta(3, 2, "2030-01-31", "1.00"));
        recusa(() -> conta(1, 25, "2030-01-31", "1.00"));
    }

    @Test
    void mesmaParcelaDaMesmaVendaNaoRepete() {
        conta(1, 2, "2030-01-31", "30.00");
        recusa(() -> conta(1, 2, "2030-02-28", "30.00"));
    }

    @Test
    void sohAbertaPagaComPagoMaisCanceladoAcimaDoValorOuStatusIncoerenteESaoRecusados() {
        UUID id = UuidV7.gerar();
        String insere = """
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                    total_parcelas, vencimento, valor, valor_pago, valor_cancelado, status)
                VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, 1, 1, '2030-01-31', 50.00, ?, ?, ?)""";
        // pago + cancelado acima do valor
        recusa(() -> jdbc.update(insere, id, cliente, venda, venda, v("30.00"), v("30.00"), "PARCIAL"));
        // status que não combina com os valores
        recusa(() -> jdbc.update(insere, id, cliente, venda, venda, v("0.00"), v("0.00"), "PAGA"));
        recusa(() -> jdbc.update(insere, id, cliente, venda, venda, v("50.00"), v("0.00"), "ABERTA"));
        recusa(() -> jdbc.update(insere, id, cliente, venda, venda, v("10.00"), v("0.00"), "PAGA"));
        recusa(() -> jdbc.update(insere, id, cliente, venda, venda, v("0.00"), v("0.00"), "VENCIDA"));
        // negativos
        recusa(() -> jdbc.update(insere, id, cliente, venda, venda, v("-1.00"), v("0.00"), "ABERTA"));
    }

    @Test
    void parcelaDeVendaEstornadaOuDeOutroClienteOuSemVendaNaoEntra() throws Exception {
        UUID outroCliente = cliente(vendedor);
        recusa(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                    total_parcelas, vencimento, valor)
                VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, 1, 1, '2030-01-31', 10.00)""", UuidV7.gerar(), outroCliente, venda, venda));
        UUID inexistente = UUID.randomUUID();
        recusa(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                    total_parcelas, vencimento, valor)
                VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, 1, 1, '2030-01-31', 10.00)""", UuidV7.gerar(), cliente, inexistente, inexistente));
        // origem e venda têm de ser a mesma
        recusa(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                    total_parcelas, vencimento, valor)
                VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, 1, 1, '2030-01-31', 10.00)""", UuidV7.gerar(), cliente, UUID.randomUUID(), venda));
        // devedor VENDEDOR e origem de acerto só chegam em migração nova (7.5 e 7.6)
        recusa(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, vendedor_id, origem_tipo, origem_id, venda_id, parcela,
                    total_parcelas, vencimento, valor)
                VALUES (?, 'VENDEDOR', ?, 'VENDA', ?, ?, 1, 1, '2030-01-31', 10.00)""", UuidV7.gerar(), vendedor.vendedorId(), venda, venda));
    }

    @Test
    void vendaEstornadaNaoRecebeParcelaNova() throws Exception {
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID clienteDoOutro = cliente(outro);
        UUID peca = peca("50.00", 2);
        UUID maleta = maleta(outro, peca, 2);
        UUID estornada = vender(outro, maleta, clienteDoOutro, List.of(linha(peca, 1)), List.of(pagamento("50.00", "DINHEIRO")));
        postar("/api/vendedor/vendas/" + estornada + "/estorno", como(outro), mapa("id", UUID.randomUUID(), "motivo", "Erro"))
                .andReturn();
        assertThat(jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, estornada))
                .isEqualTo("ESTORNADA");
        recusa(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                    total_parcelas, vencimento, valor)
                VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, 1, 1, '2030-01-31', 10.00)""", UuidV7.gerar(), clienteDoOutro, estornada,
                estornada));
    }

    // --- contas_receber: o que não muda ---

    @Test
    void conteudoDaParcelaNaoMudaENadaEApagado() throws Exception {
        UUID outroCliente = cliente(vendedor);
        UUID id = conta(1, 1, "2030-01-31", "60.00");
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET valor = 61.00 WHERE id = ?", id));
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET vencimento = '2030-02-28' WHERE id = ?", id));
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET cliente_id = ? WHERE id = ?", outroCliente, id));
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET venda_id = ?, origem_id = ? WHERE id = ?", UUID.randomUUID(),
                UUID.randomUUID(), id));
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET parcela = 1, total_parcelas = 2 WHERE id = ?", id));
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET deleted_at = now() WHERE id = ?", id));
        recusa(() -> jdbc.update("DELETE FROM corona.contas_receber WHERE id = ?", id));
        assertThat(jdbc.queryForObject("SELECT valor FROM corona.contas_receber WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("60.00");
    }

    @Test
    void atalhoSoMudaParaOQueAsBaixasSomam() {
        UUID id = conta(1, 1, "2030-01-31", "60.00");
        // sem baixa nenhuma, nenhum atalho muda
        recusa(() -> atalhos(id, "10.00", "0.00", "PARCIAL"));
        recusa(() -> atalhos(id, "0.00", "60.00", "CANCELADA"));
        UUID pag = pagamentoPosterior("25.00", "DINHEIRO");
        baixa(id, "PAGAMENTO", "25.00", pag, null, null);
        // atalho diferente da soma das baixas
        recusa(() -> atalhos(id, "30.00", "0.00", "PARCIAL"));
        recusa(() -> atalhos(id, "60.00", "0.00", "PAGA"));
        // o certo entra
        atalhos(id, "25.00", "0.00", "PARCIAL");
        assertThat(jdbc.queryForObject("SELECT status FROM corona.contas_receber WHERE id = ?", String.class, id))
                .isEqualTo("PARCIAL");
        // cancelar o resto: pago + cancelado = valor
        baixa(id, "CANCELAMENTO", "35.00", null, "ESTORNO_VENDA", null);
        atalhos(id, "25.00", "35.00", "CANCELADA");
        assertThat(jdbc.queryForObject("SELECT status FROM corona.contas_receber WHERE id = ?", String.class, id))
                .isEqualTo("CANCELADA");
    }

    @Test
    void baixaQuePassaDoValorDaParcelaOuDoRecebimentoEhRecusada() {
        UUID id = conta(1, 1, "2030-01-31", "60.00");
        UUID pag = pagamentoPosterior("70.00", "PIX");
        recusa(() -> baixa(id, "PAGAMENTO", "70.00", pag, null, null));
        baixa(id, "PAGAMENTO", "50.00", pag, null, null);
        recusa(() -> baixa(id, "PAGAMENTO", "20.00", pag, null, null));
        recusa(() -> baixa(id, "CANCELAMENTO", "11.00", null, "ESTORNO_VENDA", null));
        baixa(id, "CANCELAMENTO", "10.00", null, "ESTORNO_VENDA", null);
    }

    // --- baixas_receber ---

    @Test
    void baixaNaoMudaENaoEApagada() {
        UUID id = conta(1, 1, "2030-01-31", "60.00");
        UUID pag = pagamentoPosterior("10.00", "PIX");
        UUID baixa = baixa(id, "PAGAMENTO", "10.00", pag, null, null);
        recusa(() -> jdbc.update("UPDATE corona.baixas_receber SET valor = 11.00 WHERE id = ?", baixa));
        recusa(() -> jdbc.update("UPDATE corona.baixas_receber SET deleted_at = now() WHERE id = ?", baixa));
        recusa(() -> jdbc.update("DELETE FROM corona.baixas_receber WHERE id = ?", baixa));
    }

    @Test
    void baixaTemFormaCertaDeTipoValorEPagamento() {
        UUID id = conta(1, 1, "2030-01-31", "60.00");
        UUID pag = pagamentoPosterior("10.00", "PIX");
        // PAGAMENTO sem pagamento do cliente, ou com valor zero ou negativo sem estorno
        recusa(() -> baixa(id, "PAGAMENTO", "10.00", null, null, null));
        recusa(() -> baixa(id, "PAGAMENTO", "0.00", pag, null, null));
        recusa(() -> baixa(id, "PAGAMENTO", "-10.00", pag, null, null));
        // CANCELAMENTO com pagamento, sem motivo, ou com motivo desconhecido
        recusa(() -> baixa(id, "CANCELAMENTO", "10.00", pag, "ESTORNO_VENDA", null));
        recusa(() -> baixa(id, "CANCELAMENTO", "10.00", null, null, null));
        recusa(() -> baixa(id, "CANCELAMENTO", "10.00", null, "QUALQUER", null));
        // tipo desconhecido
        recusa(() -> baixa(id, "ESTORNO", "10.00", pag, null, null));
        // pagamento do cliente que não é posterior (o da hora da venda) não baixa parcela
        UUID daHora = jdbc.queryForObject("SELECT id FROM corona.pagamentos_cliente WHERE venda_id = ? AND NOT posterior",
                UUID.class, venda);
        recusa(() -> baixa(id, "PAGAMENTO", "10.00", daHora, null, null));
    }

    @Test
    void estornoDeBaixaTemMesmoValorComSinalOpostoEUmSo() {
        UUID id = conta(1, 1, "2030-01-31", "60.00");
        UUID outra = conta(2, 2, "2030-02-28", "30.00");
        UUID pag = pagamentoPosterior("10.00", "PIX");
        UUID original = baixa(id, "PAGAMENTO", "10.00", pag, null, null);
        UUID pagEstorno = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior, estorna_id)
                VALUES (?, ?, 'ESTORNO_PAGAMENTO', 'PIX', -10.00, now(), true, ?)""", pagEstorno, venda, pag);
        // estorno positivo, de outro valor, de outra conta
        recusa(() -> baixa(id, "PAGAMENTO", "10.00", pagEstorno, null, original));
        recusa(() -> baixa(id, "PAGAMENTO", "-9.00", pagEstorno, null, original));
        recusa(() -> baixa(outra, "PAGAMENTO", "-10.00", pagEstorno, null, original));
        // o certo entra uma vez só
        baixa(id, "PAGAMENTO", "-10.00", pagEstorno, null, original);
        recusa(() -> baixa(id, "PAGAMENTO", "-10.00", pagEstorno, null, original));
        // estorno de estorno
        UUID estorno = jdbc.queryForObject("SELECT id FROM corona.baixas_receber WHERE estorna_id = ?", UUID.class, original);
        recusa(() -> baixa(id, "PAGAMENTO", "10.00", pag, null, estorno));
    }

    @Test
    void cancelamentoNaoTemEstorno() {
        UUID id = conta(1, 1, "2030-01-31", "60.00");
        UUID cancelamento = baixa(id, "CANCELAMENTO", "60.00", null, "ESTORNO_VENDA", null);
        recusa(() -> baixa(id, "CANCELAMENTO", "-60.00", null, "ESTORNO_VENDA", cancelamento));
    }

    // --- pagamentos_cliente ---

    @Test
    void recebimentoPosteriorSoEmDinheiroOuTransferenciaESoComoPagamento() {
        recusa(() -> pagamentoPosterior("10.00", "CARTAO"));
        recusa(() -> pagamentoPosterior("10.00", "CHEQUE"));
        recusa(() -> jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior)
                VALUES (?, ?, 'REEMBOLSO', 'PIX', -10.00, now(), true)""", UUID.randomUUID(), venda));
        pagamentoPosterior("10.00", "DINHEIRO");
        pagamentoPosterior("10.00", "PIX");
    }

    @Test
    void estornoDePagamentoTemSinalNegativoEEstornaUmPagamentoPosterior() {
        UUID pag = pagamentoPosterior("10.00", "PIX");
        String insere = """
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior, estorna_id)
                VALUES (?, ?, 'ESTORNO_PAGAMENTO', 'PIX', ?, now(), ?, ?)""";
        // positivo, sem estorna_id, sem ser posterior
        recusa(() -> jdbc.update(insere, UUID.randomUUID(), venda, v("10.00"), true, pag));
        recusa(() -> jdbc.update(insere, UUID.randomUUID(), venda, v("-10.00"), true, null));
        recusa(() -> jdbc.update(insere, UUID.randomUUID(), venda, v("-10.00"), false, pag));
        // estornar o pagamento da hora da venda ou um valor diferente
        UUID daHora = jdbc.queryForObject("SELECT id FROM corona.pagamentos_cliente WHERE venda_id = ? AND NOT posterior",
                UUID.class, venda);
        recusa(() -> jdbc.update(insere, UUID.randomUUID(), venda, v("-100.00"), true, daHora));
        recusa(() -> jdbc.update(insere, UUID.randomUUID(), venda, v("-9.00"), true, pag));
        // o certo entra uma vez; a segunda vez o índice único recusa
        jdbc.update(insere, UUID.randomUUID(), venda, v("-10.00"), true, pag);
        recusa(() -> jdbc.update(insere, UUID.randomUUID(), venda, v("-10.00"), true, pag));
    }

    @Test
    void pagamentoDaHoraContinuaComoAntes() {
        Map<String, Object> p = jdbc.queryForMap(
                "SELECT posterior, tipo FROM corona.pagamentos_cliente WHERE venda_id = ?", venda);
        assertThat(p.get("posterior")).isEqualTo(false);
        assertThat(p.get("tipo")).isEqualTo("PAGAMENTO");
    }

    // --- convenções ---

    @Test
    void asTabelasNovasSaoSincronizaveis() {
        for (String tabela : List.of("contas_receber", "baixas_receber")) {
            assertThat(contar("""
                    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'corona' AND table_name = ?
                       AND column_name IN ('sync_seq', 'sync_xid', 'updated_at', 'deleted_at', 'version')""", tabela)).isEqualTo(5);
        }
    }

    // --- migração do que já existe ---

    private static Flyway flyway(PostgreSQLContainer banco, String ate) {
        return Flyway.configure()
                .dataSource(urlJdbc(banco), MIGRACAO_USUARIO, MIGRACAO_SENHA)
                .schemas("corona").defaultSchema("corona").createSchemas(false)
                .locations("classpath:db/migration")
                .target(ate)
                .load();
    }

    @Test
    void vendaAPrazoGravadaAntesViraUmaParcelaNoDiaDaVenda() throws Exception {
        try (PostgreSQLContainer banco = novoContainer()) {
            banco.start();
            flyway(banco, "40").migrate();
            UUID usuario = UUID.randomUUID();
            UUID vend = UUID.randomUUID();
            UUID cli = UUID.randomUUID();
            UUID semana = UUID.randomUUID();
            UUID remessa = UUID.randomUUID();
            UUID aPrazo = UUID.randomUUID();
            UUID aVista = UUID.randomUUID();
            UUID semPagar = UUID.randomUUID();
            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA)) {
                executar(c, "INSERT INTO corona.usuarios (id, nome, login, senha_hash, perfil) VALUES (?, 'Dona', 'dona.mig', "
                        + "'$2a$04$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234', 'ADMIN')", usuario);
                executar(c, "INSERT INTO corona.vendedores (id, nome) VALUES (?, 'Vendedora')", vend);
                executar(c, "INSERT INTO corona.clientes (id, nome) VALUES (?, 'Cliente')", cli);
                executar(c, "INSERT INTO corona.semanas (id, numero, inicio, fim) VALUES (?, 1, '2026-11-02', '2026-11-08')", semana);
                executar(c, "INSERT INTO corona.remessas (id, vendedor_id, semana_id, tipo, status, enviada_em, criada_por) "
                        + "VALUES (?, ?, ?, 'NOVA', 'CONFERIDA', '2026-11-02T12:00:00Z', ?)", remessa, vend, semana, usuario);
                // 23:30 de 09/11 em Brasília já é 10/11 em UTC: o vencimento é o dia de Brasília (09/11).
                venda(c, aPrazo, vend, cli, remessa, "100.00", "2026-11-10T02:30:00Z");
                venda(c, aVista, vend, cli, remessa, "50.00", "2026-11-03T15:00:00Z");
                venda(c, semPagar, vend, cli, remessa, "30.00", "2026-11-04T15:00:00Z");
                pagar(c, aPrazo, "40.00");
                pagar(c, aVista, "50.00");
                c.commit();
            }

            flyway(banco, "latest").migrate();

            try (Connection c = conectar(banco, MIGRACAO_USUARIO, MIGRACAO_SENHA);
                    ResultSet r = c.createStatement().executeQuery("""
                            SELECT venda_id, parcela, total_parcelas, vencimento::text, valor, status, cliente_id, origem_tipo
                              FROM corona.contas_receber ORDER BY valor""")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getObject("venda_id")).isEqualTo(semPagar);
                assertThat(r.getBigDecimal("valor")).isEqualByComparingTo("30.00");
                assertThat(r.getString("vencimento")).isEqualTo("2026-11-04");
                assertThat(r.next()).isTrue();
                assertThat(r.getObject("venda_id")).isEqualTo(aPrazo);
                assertThat(r.getBigDecimal("valor")).isEqualByComparingTo("60.00");
                assertThat(r.getString("vencimento")).isEqualTo("2026-11-09");
                assertThat(r.getInt("parcela")).isEqualTo(1);
                assertThat(r.getInt("total_parcelas")).isEqualTo(1);
                assertThat(r.getString("status")).isEqualTo("ABERTA");
                assertThat(r.getObject("cliente_id")).isEqualTo(cli);
                assertThat(r.getString("origem_tipo")).isEqualTo("VENDA");
                assertThat(r.next()).as("a venda à vista não ganha parcela").isFalse();
            }
        }
    }

    private static void executar(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                p.setObject(i + 1, args[i]);
            }
            p.executeUpdate();
        }
    }

    private static void venda(Connection c, UUID id, UUID vend, UUID cli, UUID remessa, String total, String quando)
            throws Exception {
        executar(c, "INSERT INTO corona.vendas (id, vendedor_id, cliente_id, remessa_id, subtotal, desconto, total, vendida_em) "
                + "VALUES (?, ?, ?, ?, " + total + ", 0, " + total + ", '" + quando + "')", id, vend, cli, remessa);
    }

    private static void pagar(Connection c, UUID venda, String valor) throws Exception {
        executar(c, "INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor) VALUES (?, ?, 'PAGAMENTO', 'DINHEIRO', "
                + valor + ")", UUID.randomUUID(), venda);
    }
}
