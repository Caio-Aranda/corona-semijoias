package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Os recebimentos do vendedor, as contas do nível 2 e o caixa que o PRÓPRIO BANCO garante (tarefa 7.6; ADR-0012, itens 1, 7, 8, 9,
 * 11 e 17), com SQL direto: o serviço pode errar, o banco não deixa gravar errado. Escrito antes da migração V045.
 */
class RecebimentosECaixaBancoTest extends ApoioDoFechamento {

    private UsuarioDeTeste vendedor;
    private UUID semana;
    private UUID peca;
    private UUID maleta;
    private UUID cliente;
    private UUID venda;

    @BeforeEach
    void umaVendaNaMaleta() throws Exception {
        vendedor = usuarios.criarVendedor();
        semana = semana();
        peca = peca("50.00", 20);
        maleta = maletaConferida(vendedor, semana, peca, 10);
        cliente = cliente(vendedor);
        venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
    }

    // --- o SQL direto ---

    /** Um acerto por vendedor e semana: cada acerto do teste ganha a sua semana. */
    private UUID novaSemana() {
        try {
            return semana();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID categoria(String nome) {
        return jdbc.queryForObject("SELECT id FROM corona.categorias_caixa WHERE nome = ?", UUID.class, nome);
    }

    private UUID recebimento(UsuarioDeTeste de, String momento, UUID acerto, String aplicado, String forma, String valor, UUID estorna,
            String motivo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, acerto_id, aplicado_em, forma, valor, registrado_por,
                    estorna_id, motivo) VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?, ?, ?)""", id, de.vendedorId(), momento, acerto, aplicado,
                forma, valor, usuarios.dona().id(), estorna, motivo);
        return id;
    }

    private UUID lancamento(String tipo, String valor, String forma, String origemTipo, UUID origem, UUID estorna) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, estorna_id,
                    registrado_por) VALUES (?, ?, ?::numeric, ?, ?, ?, ?, current_date, ?, ?)""", id, tipo, valor, forma,
                categoria("ENTRADA".equals(tipo) ? "Repasse do vendedor" : "Pagamento ao vendedor"), origemTipo, origem, estorna,
                usuarios.dona().id());
        return id;
    }

    private UUID baixa(UUID conta, UUID recebimento, String valor, UUID estorna) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, recebimento_vendedor_id, estorna_id)
                VALUES (?, ?, 'PAGAMENTO', ?::numeric, ?, ?)""", id, conta, valor, recebimento, estorna);
        return id;
    }

    private void pagarAConta(UUID conta, BigDecimal pago) {
        String status = pago.signum() == 0 ? "ABERTA" : jdbc.queryForObject(
                "SELECT CASE WHEN valor = ?::numeric THEN 'PAGA' ELSE 'PARCIAL' END FROM corona.contas_receber WHERE id = ?", String.class, pago, conta);
        jdbc.update("UPDATE corona.contas_receber SET valor_pago = ?, status = ? WHERE id = ?", pago, status, conta);
    }

    /** Um acerto fechado por SQL com a venda do teste: líquido 35,00 que o vendedor ficou devendo (uma conta de 35,00). Devolve a conta. */
    private UUID contaDe35() {
        return contaDe35(venda);
    }

    private UUID contaDe35(UUID daVenda) {
        UUID acerto = acertoNoBanco(vendedor, novaSemana());
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", daVenda, daVenda, "50.00");
        comissaoNoBanco(acerto, vendedor, daVenda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        transacao.executeWithoutResult(s -> fecharNoBanco(acerto, "50.00", "0", "0", "15.00", "0"));
        return jdbc.queryForObject("SELECT id FROM corona.contas_receber WHERE origem_id = ?", UUID.class, acerto);
    }

    private UUID contaComBoleto() {
        UUID acerto = acertoNoBanco(vendedor, novaSemana());
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        UUID[] conta = new UUID[1];
        transacao.executeWithoutResult(s -> {
            conta[0] = contaDoAcertoNoBanco(acerto, new BigDecimal("35.00"), 1, 1, LocalDate.now().plusDays(10), "BOLETO");
            jdbc.update("""
                    UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'teste', fechado_em = now(),
                           fechado_por = ?, cotacao_id = ?, meta_guarani = 700000, percentual_comissao = 30, vendido_para_meta_guarani = 0,
                           vendido_bruto = 50, descontos = 0, devolvido = 0, vendido_liquido = 50, comissao = 15, faltas_cobradas = 0,
                           liquido = 35, recebido_no_acerto = 0, ficou_devendo = 35 WHERE id = ?""", UUID.randomUUID(),
                    usuarios.dona().id(), cotacaoDoExemplo, acerto);
        });
        return conta[0];
    }

    // --- as formas e o sinal ---

    @Test
    void chequeECartaoSaoRecusadosNoRepasseENoCaixa() {
        UUID conta = contaDe35();
        for (String forma : List.of("CHEQUE", "CARTAO")) {
            assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", forma, "10.00", null, null))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> lancamento("ENTRADA", "10.00", forma, "RECEBIMENTO_VENDEDOR", UUID.randomUUID(), null))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(conta).isNotNull();
    }

    @Test
    void boletoSoComoRecebimentoAvulsoNasContas() {
        UUID acerto = acertoNoBanco(vendedor, novaSemana());
        // No fechamento o boleto é conta, não recebimento.
        assertThatThrownBy(() -> recebimento(vendedor, "ACERTO", acerto, "ACERTO", "BOLETO", "10.00", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "ACERTO", acerto, "CONTAS", "BOLETO", "10.00", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "ACERTO", "DINHEIRO", "10.00", null, null))
                .isInstanceOf(DataAccessException.class);
        // O acerto do recebimento do momento ACERTO é obrigatório; no avulso, proibido.
        assertThatThrownBy(() -> recebimento(vendedor, "ACERTO", null, "ACERTO", "DINHEIRO", "10.00", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", acerto, "CONTAS", "DINHEIRO", "10.00", null, null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sinalEEstornoDoRecebimento() {
        UUID conta = contaDe35();
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "0", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "-5.00", null, null))
                .isInstanceOf(DataAccessException.class);
        UUID[] original = new UUID[1];
        transacao.executeWithoutResult(s -> {
            original[0] = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", original[0], null);
            baixa(conta, original[0], "10.00", null);
            pagarAConta(conta, new BigDecimal("10.00"));
        });
        // O estorno precisa de motivo, do sinal oposto, da mesma forma e do mesmo valor.
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "-10.00", original[0], null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", original[0], "erro"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "-9.00", original[0], "erro"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "PIX", "-10.00", original[0], "erro"))
                .isInstanceOf(DataAccessException.class);
        // Estorno de outro vendedor.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        assertThatThrownBy(() -> recebimento(outro, "AVULSO", null, "CONTAS", "DINHEIRO", "-10.00", original[0], "erro"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void estornoDeEstornoEDoisEstornosDoMesmoRecebimentoSaoRecusados() {
        UUID conta = contaDe35();
        UUID[] ids = new UUID[2];
        transacao.executeWithoutResult(s -> {
            ids[0] = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", ids[0], null);
            baixa(conta, ids[0], "10.00", null);
            pagarAConta(conta, new BigDecimal("10.00"));
        });
        UUID baixaOriginal = jdbc.queryForObject("SELECT id FROM corona.baixas_receber WHERE recebimento_vendedor_id = ?", UUID.class, ids[0]);
        UUID lancamentoOriginal = jdbc.queryForObject("SELECT id FROM corona.lancamentos_caixa WHERE origem_id = ?", UUID.class, ids[0]);
        transacao.executeWithoutResult(s -> {
            ids[1] = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "-10.00", ids[0], "lancei errado");
            lancamento("ENTRADA", "-10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", ids[1], lancamentoOriginal);
            baixa(conta, ids[1], "-10.00", baixaOriginal);
            pagarAConta(conta, new BigDecimal("0.00"));
        });
        assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE estorna_id = ?", ids[0])).isEqualTo(1);
        // Segundo estorno do mesmo recebimento e estorno do estorno.
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "-10.00", ids[0], "de novo"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", ids[1], "desfaz o estorno"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void recebimentoELancamentoNaoMudamNemSaoApagados() {
        UUID conta = contaDe35();
        UUID[] r = new UUID[1];
        transacao.executeWithoutResult(s -> {
            r[0] = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r[0], null);
            baixa(conta, r[0], "10.00", null);
            pagarAConta(conta, new BigDecimal("10.00"));
        });
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.recebimentos_vendedor SET valor = 11 WHERE id = ?", r[0]))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.recebimentos_vendedor WHERE id = ?", r[0]))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.lancamentos_caixa SET valor = 11 WHERE origem_id = ?", r[0]))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.lancamentos_caixa WHERE origem_id = ?", r[0]))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.categorias_caixa WHERE nome = 'Repasse do vendedor'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void asCategoriasDoSistemaExistemENaoRepetemONome() {
        assertThat(contar("SELECT count(*) FROM corona.categorias_caixa WHERE sistema AND nome IN ('Repasse do vendedor', 'Pagamento ao vendedor')"))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO corona.categorias_caixa (id, nome, tipo) VALUES (?, '  repasse DO vendedor ', 'ENTRADA')",
                UUID.randomUUID())).isInstanceOf(DataAccessException.class);
    }

    // --- as contas do nível 2 ---

    @Test
    void contaDoVendedorTemOrigemEDevedorCoerentes() {
        UUID acerto = acertoNoBanco(vendedor, novaSemana());
        // Origem VENDA para o vendedor e venda_id numa conta do vendedor.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, vendedor_id, origem_tipo, origem_id, venda_id, parcela, total_parcelas,
                    vencimento, valor) VALUES (?, 'VENDEDOR', ?, 'VENDA', ?, ?, 1, 1, current_date, 10)""", UUID.randomUUID(),
                vendedor.vendedorId(), venda, venda)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, vendedor_id, origem_tipo, origem_id, venda_id, parcela, total_parcelas,
                    vencimento, valor) VALUES (?, 'VENDEDOR', ?, 'ACERTO', ?, ?, 1, 1, current_date, 10)""", UUID.randomUUID(),
                vendedor.vendedorId(), acerto, venda)).isInstanceOf(DataAccessException.class);
        // Conta do cliente com origem de acerto.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, parcela, total_parcelas,
                    vencimento, valor) VALUES (?, 'CLIENTE', ?, 'ACERTO', ?, 1, 1, current_date, 10)""", UUID.randomUUID(), cliente, acerto))
                .isInstanceOf(DataAccessException.class);
        // A origem PENDENCIA não existe nesta tarefa.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, vendedor_id, origem_tipo, origem_id, parcela, total_parcelas,
                    vencimento, valor) VALUES (?, 'VENDEDOR', ?, 'PENDENCIA', ?, 1, 1, current_date, 10)""", UUID.randomUUID(),
                vendedor.vendedorId(), UUID.randomUUID())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void formaPrevistaSoNaContaDeAcertoEContaDeAcertoSoNascePeloAcertoEmAndamentoDoMesmoVendedor() throws Exception {
        UUID conta = contaDe35(vender(vendedor, maleta, cliente, peca, 1, "50.00"));
        UUID acerto = acertoNoBanco(vendedor, novaSemana());
        // forma_prevista fora de conta de acerto do vendedor.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela, total_parcelas,
                    vencimento, valor, forma_prevista) VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, 1, 1, current_date, 10, 'BOLETO')""",
                UUID.randomUUID(), cliente, venda, venda)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> contaDoAcertoNoBanco(acerto, new BigDecimal("10.00"), 1, 1, LocalDate.now(), "CHEQUE"))
                .isInstanceOf(DataAccessException.class);
        // Outro vendedor.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.contas_receber (id, devedor_tipo, vendedor_id, origem_tipo, origem_id, parcela, total_parcelas,
                    vencimento, valor) VALUES (?, 'VENDEDOR', ?, 'ACERTO', ?, 1, 1, current_date, 10)""", UUID.randomUUID(),
                outro.vendedorId(), acerto)).isInstanceOf(DataAccessException.class);
        // Acerto que já fechou não ganha conta nova.
        UUID fechado = jdbc.queryForObject("SELECT origem_id FROM corona.contas_receber WHERE id = ?", UUID.class, conta);
        assertThatThrownBy(() -> contaDoAcertoNoBanco(fechado, new BigDecimal("5.00"), 2, 2, LocalDate.now(), null))
                .isInstanceOf(DataAccessException.class);
        // A parcela do vendedor é única por origem.
        assertThatCode(() -> contaDoAcertoNoBanco(acerto, new BigDecimal("5.00"), 1, 2, LocalDate.now(), null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> contaDoAcertoNoBanco(acerto, new BigDecimal("5.00"), 1, 2, LocalDate.now(), null))
                .isInstanceOf(DataAccessException.class);
    }

    // --- as baixas do nível 2 ---

    @Test
    void baixaDoNivel2ConfereORecebimentoEOSaldo() {
        UUID conta = contaDe35();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID acertoDoVendedor = acertoNoBanco(vendedor, novaSemana());
        // Recebimento de outro vendedor.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(outro, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "10.00", null);
        })).isInstanceOf(RuntimeException.class);
        // Recebimento que paga o acerto (aplicado_em = ACERTO) não baixa conta.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "ACERTO", acertoDoVendedor, "ACERTO", "DINHEIRO", "10.00",
                    null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "10.00", null);
        })).isInstanceOf(RuntimeException.class);
        // Baixa acima do valor do recebimento e acima do saldo da conta.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "10.01", null);
        })).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "36.00", null, null);
            lancamento("ENTRADA", "36.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "36.00", null);
        })).isInstanceOf(RuntimeException.class);
        // A baixa do nível 2 nunca é da forma do cliente (os dois ids juntos).
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, recebimento_vendedor_id, pagamento_cliente_id)
                VALUES (?, ?, 'PAGAMENTO', 5, ?, ?)""", UUID.randomUUID(), conta, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThat(contar("SELECT count(*) FROM corona.baixas_receber WHERE conta_id = ?", conta)).isZero();
    }

    @Test
    void boletoPagoSoBaixaAContaDoBoletoPeloValorInteiro() throws Exception {
        UUID conta = contaComBoleto();
        // Boleto parcial.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "BOLETO", "20.00", null, null);
            lancamento("ENTRADA", "20.00", "BOLETO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "20.00", null);
        })).isInstanceOf(RuntimeException.class);
        // Boleto na conta que não é do boleto.
        UUID comum = contaDe35(vender(vendedor, maleta, cliente, peca, 1, "50.00"));
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "BOLETO", "35.00", null, null);
            lancamento("ENTRADA", "35.00", "BOLETO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(comum, r, "35.00", null);
        })).isInstanceOf(RuntimeException.class);
        // Inteiro, na conta do boleto.
        assertThatCode(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "BOLETO", "35.00", null, null);
            lancamento("ENTRADA", "35.00", "BOLETO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "35.00", null);
            pagarAConta(conta, new BigDecimal("35.00"));
        })).doesNotThrowAnyException();
    }

    // --- o COMMIT ---

    @Test
    void oCommitRecusaRecebimentoSemLancamentoOuComLancamentoErrado() {
        UUID conta = contaDe35();
        // Sem lançamento de caixa.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            baixa(conta, r, "10.00", null);
            pagarAConta(conta, new BigDecimal("10.00"));
        })).isInstanceOf(RuntimeException.class);
        // Lançamento de outro valor.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "9.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "10.00", null);
            pagarAConta(conta, new BigDecimal("10.00"));
        })).isInstanceOf(RuntimeException.class);
        // Dois lançamentos do mesmo recebimento: a origem é única.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
        })).isInstanceOf(RuntimeException.class);
        // Lançamento de origem que não existe.
        assertThatThrownBy(() -> lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", UUID.randomUUID(), null))
                .isInstanceOf(DataAccessException.class);
        assertThat(contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE vendedor_id = ?", vendedor.vendedorId())).isZero();
    }

    @Test
    void oCommitRecusaRecebimentoDeContasSemBaixasInteiras() {
        UUID conta = contaDe35();
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "6.00", null);
            pagarAConta(conta, new BigDecimal("6.00"));
        })).isInstanceOf(RuntimeException.class);
        assertThatCode(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "AVULSO", null, "CONTAS", "DINHEIRO", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            baixa(conta, r, "10.00", null);
            pagarAConta(conta, new BigDecimal("10.00"));
        })).doesNotThrowAnyException();
    }

    // --- o acerto fechado com dinheiro ---

    /** Fecha o acerto por SQL com a venda do teste (líquido 35,00) e o dinheiro dado; as contas e os recebimentos o chamador cria antes. */
    private void fecharComDinheiro(UUID acerto, String recebido, String pagoAoVendedor, String ficou, String creditoUsado, UUID creditoDe) {
        jdbc.update("""
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'teste', fechado_em = now(),
                       fechado_por = ?, cotacao_id = ?, meta_guarani = 700000, percentual_comissao = 30, vendido_para_meta_guarani = 0,
                       vendido_bruto = 50, descontos = 0, devolvido = 0, vendido_liquido = 50, comissao = 15, faltas_cobradas = 0,
                       liquido = 35, recebido_no_acerto = ?::numeric, pago_ao_vendedor = ?::numeric, ficou_devendo = ?::numeric,
                       credito_usado = ?::numeric, credito_do_acerto_id = ? WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(),
                cotacaoDoExemplo, recebido, pagoAoVendedor, ficou, creditoUsado, creditoDe, acerto);
    }

    private UUID acertoComVenda() {
        UUID acerto = acertoNoBanco(vendedor, novaSemana());
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        return acerto;
    }

    @Test
    void acertoPagoEmPartesComRecebimentosEContaDoRestanteEAceito() {
        UUID acerto = acertoComVenda();
        assertThatCode(() -> transacao.executeWithoutResult(s -> {
            UUID dinheiro = recebimento(vendedor, "ACERTO", acerto, "ACERTO", "DINHEIRO", "20.00", null, null);
            lancamento("ENTRADA", "20.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", dinheiro, null);
            UUID pix = recebimento(vendedor, "ACERTO", acerto, "ACERTO", "PIX", "10.00", null, null);
            lancamento("ENTRADA", "10.00", "PIX", "RECEBIMENTO_VENDEDOR", pix, null);
            contaDoAcertoNoBanco(acerto, new BigDecimal("5.00"), 1, 1, LocalDate.now().plusDays(30), null);
            fecharComDinheiro(acerto, "30.00", "0", "5.00", "0", null);
        })).doesNotThrowAnyException();
        assertThat(contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE origem_id IN (SELECT id FROM corona.recebimentos_vendedor WHERE acerto_id = ?)",
                acerto)).isEqualTo(2);
    }

    @Test
    void acertoFechadoComDinheiroQueNaoBateEhRecusado() {
        UUID acerto = acertoComVenda();
        // recebido_no_acerto diferente da soma dos recebimentos.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID r = recebimento(vendedor, "ACERTO", acerto, "ACERTO", "DINHEIRO", "20.00", null, null);
            lancamento("ENTRADA", "20.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r, null);
            contaDoAcertoNoBanco(acerto, new BigDecimal("10.00"), 1, 1, LocalDate.now().plusDays(30), null);
            fecharComDinheiro(acerto, "25.00", "0", "10.00", "0", null);
        })).isInstanceOf(RuntimeException.class);
        // Contas do vendedor diferentes de ficou_devendo.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            contaDoAcertoNoBanco(acerto, new BigDecimal("30.00"), 1, 1, LocalDate.now().plusDays(30), null);
            fecharComDinheiro(acerto, "0", "0", "35.00", "0", null);
        })).isInstanceOf(RuntimeException.class);
        // Nenhuma conta com ficou_devendo positivo.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> fecharComDinheiro(acerto, "0", "0", "35.00", "0", null)))
                .isInstanceOf(RuntimeException.class);
        // Recebido e pago ao vendedor juntos.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> fecharComDinheiro(acerto, "35.00", "5.00", "-5.00", "0", null)))
                .isInstanceOf(RuntimeException.class);
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE id = ? AND status = 'EM_ACERTO'", acerto)).isEqualTo(1);
    }

    @Test
    void acertoAFavorPagoNaHoraExigeASaidaDeCaixa() throws Exception {
        UUID acerto = acertoNoBanco(vendedor, novaSemana());
        UUID devolucao = devolucaoDaDona();
        eventoNoBanco(acerto, vendedor, "DEVOLUCAO", devolucao, venda, "-50.00");
        String fechar = """
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'teste', fechado_em = now(),
                       fechado_por = ?, cotacao_id = ?, meta_guarani = 700000, percentual_comissao = 30, vendido_para_meta_guarani = 0,
                       vendido_bruto = 0, descontos = 0, devolvido = 50, vendido_liquido = -50, comissao = 0, faltas_cobradas = 0,
                       liquido = -50, recebido_no_acerto = 0, pago_ao_vendedor = 50, ficou_devendo = 0 WHERE id = ?""";
        // Sem a saída de caixa.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> jdbc.update(fechar, UUID.randomUUID(), usuarios.dona().id(),
                cotacaoDoExemplo, acerto))).isInstanceOf(RuntimeException.class);
        // Com a saída de caixa do valor certo.
        assertThatCode(() -> transacao.executeWithoutResult(s -> {
            lancamento("SAIDA", "-50.00", "DINHEIRO", "ACERTO_A_FAVOR_DO_VENDEDOR", acerto, null);
            jdbc.update(fechar, UUID.randomUUID(), usuarios.dona().id(), cotacaoDoExemplo, acerto);
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT sum(valor) FROM corona.lancamentos_caixa WHERE origem_id = ?", BigDecimal.class, acerto))
                .isEqualByComparingTo("-50.00");
    }

    @Test
    void oCreditoUsadoEhTodoOCreditoDoAcertoAnteriorEUsadoUmaVezSo() throws Exception {
        UUID primeiro = acertoNoBanco(vendedor, novaSemana());
        UUID devolucao = devolucaoDaDona();
        eventoNoBanco(primeiro, vendedor, "DEVOLUCAO", devolucao, venda, "-50.00");
        // A favor do vendedor, como crédito: ficou_devendo negativo.
        transacao.executeWithoutResult(s -> jdbc.update("""
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'teste', fechado_em = now(),
                       fechado_por = ?, cotacao_id = ?, meta_guarani = 700000, percentual_comissao = 30, vendido_para_meta_guarani = 0,
                       vendido_bruto = 0, descontos = 0, devolvido = 50, vendido_liquido = -50, comissao = 0, faltas_cobradas = 0,
                       liquido = -50, recebido_no_acerto = 0, ficou_devendo = -50 WHERE id = ?""", UUID.randomUUID(),
                usuarios.dona().id(), cotacaoDoExemplo, primeiro));
        UUID venda2 = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID segundo = acertoNoBanco(vendedor, novaSemana());
        UUID evento = eventoNoBanco(segundo, vendedor, "VENDA", venda2, venda2, "50.00");
        comissaoNoBanco(segundo, vendedor, venda2, evento, "COMISSAO", "50.00", "30", "15.00", null);
        // Sem abater o crédito (credito_usado 0): o vendedor ficaria com crédito sem uso.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            contaDoAcertoNoBanco(segundo, new BigDecimal("35.00"), 1, 1, LocalDate.now().plusDays(30), null);
            fecharComDinheiro(segundo, "0", "0", "35.00", "0", null);
        })).isInstanceOf(RuntimeException.class);
        // Crédito menor que o do acerto anterior.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> fecharComDinheiro(segundo, "0", "0", "-25.00", "10.00", primeiro)))
                .isInstanceOf(RuntimeException.class);
        // Todo o crédito (50): 35 - 50 = -15, o resto continua como crédito.
        assertThatCode(() -> transacao.executeWithoutResult(s -> fecharComDinheiro(segundo, "0", "0", "-15.00", "50.00", primeiro)))
                .doesNotThrowAnyException();
        // O mesmo crédito não é usado duas vezes.
        UUID venda3 = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID terceiro = acertoNoBanco(vendedor, novaSemana());
        UUID evento3 = eventoNoBanco(terceiro, vendedor, "VENDA", venda3, venda3, "50.00");
        comissaoNoBanco(terceiro, vendedor, venda3, evento3, "COMISSAO", "50.00", "30", "15.00", null);
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            fecharComDinheiro(terceiro, "0", "0", "-15.00", "50.00", primeiro);
        })).isInstanceOf(RuntimeException.class);
        // Usando o crédito certo (o do segundo acerto, 15): 35 - 15 = 20 que ele deve.
        assertThatCode(() -> transacao.executeWithoutResult(s -> {
            contaDoAcertoNoBanco(terceiro, new BigDecimal("20.00"), 1, 1, LocalDate.now().plusDays(30), null);
            fecharComDinheiro(terceiro, "0", "0", "20.00", "15.00", segundo);
        })).doesNotThrowAnyException();
    }

    @Test
    void estornoDeRecebimentoDoAcertoPrecisaVirarContaDoNivel2() {
        UUID acerto = acertoComVenda();
        UUID[] r = new UUID[1];
        transacao.executeWithoutResult(s -> {
            r[0] = recebimento(vendedor, "ACERTO", acerto, "ACERTO", "DINHEIRO", "35.00", null, null);
            lancamento("ENTRADA", "35.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", r[0], null);
            fecharComDinheiro(acerto, "35.00", "0", "0", "0", null);
        });
        UUID lancamentoOriginal = jdbc.queryForObject("SELECT id FROM corona.lancamentos_caixa WHERE origem_id = ?", UUID.class, r[0]);
        // Sem a conta nova, o estorno não fecha.
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID e = recebimento(vendedor, "ACERTO", acerto, "ACERTO", "DINHEIRO", "-35.00", r[0], "erro de digitação");
            lancamento("ENTRADA", "-35.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", e, lancamentoOriginal);
        })).isInstanceOf(RuntimeException.class);
        assertThatCode(() -> transacao.executeWithoutResult(s -> {
            UUID e = recebimento(vendedor, "ACERTO", acerto, "ACERTO", "DINHEIRO", "-35.00", r[0], "erro de digitação");
            lancamento("ENTRADA", "-35.00", "DINHEIRO", "RECEBIMENTO_VENDEDOR", e, lancamentoOriginal);
            jdbc.update("""
                    INSERT INTO corona.contas_receber (id, devedor_tipo, vendedor_id, origem_tipo, origem_id, parcela, total_parcelas,
                        vencimento, valor) VALUES (?, 'VENDEDOR', ?, 'ESTORNO_RECEBIMENTO', ?, 1, 1, current_date, 35.00)""",
                    UUID.randomUUID(), vendedor.vendedorId(), e);
        })).doesNotThrowAnyException();
        // O acerto, que já terminou, não mudou nem pode mudar.
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET recebido_no_acerto = 0, ficou_devendo = 35, credito_usado = 0 WHERE id = ?", acerto))
                .isInstanceOf(DataAccessException.class);
    }

    /** A dona devolve a peça da venda do teste (peça boa, para a loja), antes do acerto. Devolve o id da devolução. */
    private UUID devolucaoDaDona() throws Exception {
        UUID id = UUID.randomUUID();
        postar("/api/dona/devolucoes", dona(), mapa("id", id, "vendaId", venda, "motivo", "ARREPENDIMENTO", "formaReembolso", "DINHEIRO",
                "itens", List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        return id;
    }
}
