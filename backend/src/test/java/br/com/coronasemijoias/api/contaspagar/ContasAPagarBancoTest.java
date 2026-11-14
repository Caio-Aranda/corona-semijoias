package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * As contas a pagar, o caixa manual e a semana fechada que o PRÓPRIO BANCO garante (tarefa 7.7; ADR-0012, itens 2, 11, 12, 13,
 * 14 e 17; condições C4, C5 e C10), com SQL direto: o serviço pode errar, o banco não deixa gravar errado. Escrito antes da V046.
 */
class ContasAPagarBancoTest extends TesteDeIntegracao {

    private static final AtomicInteger SEQUENCIA = new AtomicInteger(1);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transacao;

    @Autowired
    UsuariosDeTeste usuarios;

    private UUID dona;
    private UUID fornecedor;

    @BeforeEach
    void preparar() {
        dona = usuarios.dona().id();
        fornecedor = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.fornecedores (id, razao_social) VALUES (?, ?)", fornecedor, "Fornecedor " + fornecedor);
    }

    // --- montagem ---

    private UUID compra(String total) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.compras (id, fornecedor_id, data_compra, total, usuario_id) VALUES (?, ?, current_date, ?::numeric, ?)",
                id, fornecedor, total, dona);
        return id;
    }

    /** As parcelas de uma compra, todas numa transação (o gatilho do COMMIT confere a soma). */
    private UUID[] parcelas(UUID compra, String... valores) {
        UUID geracao = UUID.randomUUID();
        UUID[] ids = new UUID[valores.length];
        transacao.executeWithoutResult(s -> {
            for (int i = 0; i < valores.length; i++) {
                ids[i] = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO corona.contas_pagar (id, compra_id, fornecedor_id, geracao_id, parcela, total_parcelas, vencimento, valor,
                            conteudo, criada_por) VALUES (?, ?, ?, ?, ?, ?, current_date + ?, ?::numeric, 'teste', ?)""", ids[i], compra,
                        fornecedor, geracao, i + 1, valores.length, 30 * (i + 1), valores[i], dona);
            }
        });
        return ids;
    }

    private UUID conta(String valor) {
        return parcelas(compra(valor), valor)[0];
    }

    private UUID categoria(String nome) {
        return jdbc.queryForObject("SELECT id FROM corona.categorias_caixa WHERE nome = ?", UUID.class, nome);
    }

    private UUID categoriaNova(String tipo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.categorias_caixa (id, nome, tipo) VALUES (?, ?, ?)", id, "Cat " + id, tipo);
        return id;
    }

    private void baixa(UUID id, UUID conta, String tipo, String valor, String forma, UUID estorna) {
        jdbc.update("""
                INSERT INTO corona.baixas_pagar (id, conta_id, tipo, valor, forma, data_pagamento, motivo, estorna_id, conteudo, registrada_por)
                VALUES (?, ?, ?, ?::numeric, ?, current_date, ?, ?, 'teste', ?)""", id, conta, tipo, valor, forma,
                estorna == null && "PAGAMENTO".equals(tipo) ? null : "teste", estorna, dona);
    }

    private void lancamentoDoPagamento(UUID baixa, String valorComSinal, String forma, UUID estorna) {
        String tipoDoLancamento = "SAIDA";
        jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, estorna_id,
                    registrado_por) VALUES (?, ?, ?::numeric, ?, ?, 'PAGAMENTO_FORNECEDOR', ?, current_date, ?, ?)""", UUID.randomUUID(),
                tipoDoLancamento, valorComSinal, forma, categoria("Pagamento a fornecedor"), baixa, estorna, dona);
    }

    /** Um pagamento completo (baixa, lançamento de caixa e atalhos da conta), numa transação. Devolve a baixa. */
    private UUID pagar(UUID conta, String valor, String forma) {
        UUID baixa = UUID.randomUUID();
        transacao.executeWithoutResult(s -> {
            baixa(baixa, conta, "PAGAMENTO", valor, forma, null);
            lancamentoDoPagamento(baixa, "-" + valor, forma, null);
            jdbc.update("""
                    UPDATE corona.contas_pagar SET valor_pago = valor_pago + ?::numeric,
                           status = CASE WHEN valor_pago + ?::numeric = valor THEN 'PAGA' ELSE 'PARCIAL' END WHERE id = ?""", valor, valor, conta);
        });
        return baixa;
    }

    private UUID lancamentoManual(String tipo, String valorComSinal, UUID categoria) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, registrado_por,
                    descricao) VALUES (?, ?, ?::numeric, 'DINHEIRO', ?, 'MANUAL', ?, current_date, ?, 'teste')""", id, tipo, valorComSinal,
                categoria, id, dona);
        return id;
    }

    private UUID semana(boolean fechada) {
        int n = SEQUENCIA.getAndIncrement();
        UUID id = UUID.randomUUID();
        LocalDate inicio = LocalDate.of(4100, 1, 1).plusDays(10L * n);
        jdbc.update("INSERT INTO corona.semanas (id, numero, inicio, fim) VALUES (?, ?, ?, ?)", id, 800_000 + n, inicio, inicio.plusDays(6));
        if (fechada) {
            fechar(id);
        }
        return id;
    }

    private void fechar(UUID semana) {
        transacao.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO corona.fechamentos_semana (id, semana_id, fechada_por) VALUES (?, ?, ?)", UUID.randomUUID(), semana, dona);
            jdbc.update("UPDATE corona.semanas SET situacao = 'FECHADA' WHERE id = ?", semana);
        });
    }

    private LocalDate inicioDa(UUID semana) {
        return jdbc.queryForObject("SELECT inicio FROM corona.semanas WHERE id = ?", LocalDate.class, semana);
    }

    // --- imutabilidade ---

    @Test
    void baixaEFechamentoNaoSaoAlteradosNemApagados() {
        UUID conta = conta("100.00");
        UUID baixa = pagar(conta, "40.00", "PIX");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.baixas_pagar SET valor = 1 WHERE id = ?", baixa)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.baixas_pagar WHERE id = ?", baixa)).isInstanceOf(DataAccessException.class);
        UUID semana = semana(true);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.fechamentos_semana SET fechada_em = now() WHERE semana_id = ?", semana))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.fechamentos_semana WHERE semana_id = ?", semana))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void conteudoDaContaNaoMudaENaoSeApaga() {
        UUID conta = conta("100.00");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.contas_pagar SET valor = 90 WHERE id = ?", conta)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.contas_pagar SET vencimento = current_date WHERE id = ?", conta))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.contas_pagar SET deleted_at = now() WHERE id = ?", conta)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.contas_pagar WHERE id = ?", conta)).isInstanceOf(DataAccessException.class);
    }

    // --- a conta ---

    @Test
    void valorZeroOuNegativoERecusado() {
        UUID compra = compra("10.00");
        assertThatThrownBy(() -> parcelas(compra, "0.00")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> parcelas(compra, "-1.00")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void somaDasParcelasPrecisaSerOTotalDaCompraNoCommit() {
        UUID compra = compra("100.00");
        assertThatThrownBy(() -> parcelas(compra, "33.33", "33.33", "33.33")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> parcelas(compra, "50.00")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.contas_pagar WHERE compra_id = ?", Integer.class, compra)).isZero();
        assertThatCode(() -> parcelas(compra, "33.33", "33.33", "33.34")).doesNotThrowAnyException();
    }

    @Test
    void parcelaDeOutroFornecedorOuDeCompraDesfeitaERecusada() {
        UUID compra = compra("10.00");
        UUID outro = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.fornecedores (id, razao_social) VALUES (?, ?)", outro, "Outro " + outro);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.contas_pagar (id, compra_id, fornecedor_id, geracao_id, parcela, total_parcelas, vencimento, valor, conteudo,
                    criada_por) VALUES (?, ?, ?, ?, 1, 1, current_date, 10, 'teste', ?)""", UUID.randomUUID(), compra, outro, UUID.randomUUID(), dona))
                .isInstanceOf(DataAccessException.class);
        UUID desfeita = compra("10.00");
        jdbc.update("""
                UPDATE corona.compras SET situacao = 'ESTORNADA', estorno_id = ?, estorno_motivo = 'teste', estornada_em = now(), estornada_por = ?
                 WHERE id = ?""", UUID.randomUUID(), dona, desfeita);
        assertThatThrownBy(() -> parcelas(desfeita, "10.00")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void pagoMaisCanceladoNaoPassaDoValorEOStatusConfere() {
        UUID conta = conta("100.00");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.contas_pagar SET valor_pago = 150, status = 'PAGA' WHERE id = ?", conta))
                .isInstanceOf(DataAccessException.class);
        // Atalho sem baixa que o sustente: recusado (C5).
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.contas_pagar SET valor_pago = 100, status = 'PAGA' WHERE id = ?", conta))
                .isInstanceOf(DataAccessException.class);
    }

    // --- o pagamento e o caixa (C4 e C5) ---

    @Test
    void pagamentoCompletoEAceitoEOSaldoNaoPassa() {
        UUID conta = conta("100.00");
        pagar(conta, "60.00", "DINHEIRO");
        assertThatThrownBy(() -> pagar(conta, "60.01", "PIX")).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> pagar(conta, "40.00", "BOLETO")).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT status FROM corona.contas_pagar WHERE id = ?", String.class, conta)).isEqualTo("PAGA");
    }

    @Test
    void baixaSemLancamentoDeCaixaERecusadaNoCommit() {
        UUID conta = conta("100.00");
        assertThatThrownBy(() -> baixa(UUID.randomUUID(), conta, "PAGAMENTO", "10.00", "PIX", null)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corona.baixas_pagar WHERE conta_id = ?", Integer.class, conta)).isZero();
    }

    @Test
    void lancamentoDePagamentoSemBaixaOuComValorDiferenteERecusado() {
        UUID conta = conta("100.00");
        assertThatThrownBy(() -> lancamentoDoPagamento(UUID.randomUUID(), "-10.00", "PIX", null)).isInstanceOf(DataAccessException.class);
        UUID baixa = UUID.randomUUID();
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            baixa(baixa, conta, "PAGAMENTO", "10.00", "PIX", null);
            lancamentoDoPagamento(baixa, "-9.00", "PIX", null);
        })).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
            UUID outra = UUID.randomUUID();
            baixa(outra, conta, "PAGAMENTO", "10.00", "PIX", null);
            lancamentoDoPagamento(outra, "-10.00", "DINHEIRO", null);
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void chequeECartaoSaoRecusadosNaBaixaENoCaixa() {
        UUID conta = conta("100.00");
        for (String forma : new String[] {"CHEQUE", "CARTAO"}) {
            assertThatThrownBy(() -> baixa(UUID.randomUUID(), conta, "PAGAMENTO", "10.00", forma, null)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> lancamentoDoPagamento(UUID.randomUUID(), "-10.00", forma, null)).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void estornoDoPagamentoEUnicoENaoSeEstornaEstornoNemCancelamento() {
        UUID conta = conta("100.00");
        UUID pagamento = pagar(conta, "40.00", "PIX");
        UUID estorno = UUID.randomUUID();
        transacao.executeWithoutResult(s -> {
            baixa(estorno, conta, "PAGAMENTO", "-40.00", "PIX", pagamento);
            lancamentoDoPagamento(estorno, "40.00", "PIX", jdbc.queryForObject(
                    "SELECT id FROM corona.lancamentos_caixa WHERE origem_tipo = 'PAGAMENTO_FORNECEDOR' AND origem_id = ?", UUID.class, pagamento));
            jdbc.update("UPDATE corona.contas_pagar SET valor_pago = 0, status = 'ABERTA' WHERE id = ?", conta);
        });
        // Segundo estorno do mesmo pagamento.
        assertThatThrownBy(() -> baixa(UUID.randomUUID(), conta, "PAGAMENTO", "-40.00", "PIX", pagamento)).isInstanceOf(DataAccessException.class);
        // Estorno de estorno.
        assertThatThrownBy(() -> baixa(UUID.randomUUID(), conta, "PAGAMENTO", "40.00", "PIX", estorno)).isInstanceOf(DataAccessException.class);
        // Cancelamento não se estorna.
        UUID cancelamento = UUID.randomUUID();
        transacao.executeWithoutResult(s -> {
            baixa(cancelamento, conta, "CANCELAMENTO", "100.00", null, null);
            jdbc.update("UPDATE corona.contas_pagar SET valor_cancelado = 100, status = 'CANCELADA' WHERE id = ?", conta);
        });
        assertThatThrownBy(() -> baixa(UUID.randomUUID(), conta, "PAGAMENTO", "-10.00", "PIX", cancelamento)).isInstanceOf(DataAccessException.class);
        // Cancelamento não tem lançamento de caixa: um lançamento ligado a ele é recusado.
        assertThatThrownBy(() -> lancamentoDoPagamento(cancelamento, "-10.00", "PIX", null)).isInstanceOf(DataAccessException.class);
    }

    // --- o caixa manual ---

    @Test
    void lancamentoManualPrecisaDeCategoriaDoUsuarioDoMesmoTipo() {
        UUID saida = categoriaNova("SAIDA");
        UUID entrada = categoriaNova("ENTRADA");
        assertThatCode(() -> lancamentoManual("SAIDA", "-5.00", saida)).doesNotThrowAnyException();
        assertThatCode(() -> lancamentoManual("ENTRADA", "5.00", entrada)).doesNotThrowAnyException();
        assertThatThrownBy(() -> lancamentoManual("SAIDA", "-5.00", entrada)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> lancamentoManual("ENTRADA", "5.00", saida)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> lancamentoManual("SAIDA", "-5.00", categoria("Pagamento a fornecedor"))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> lancamentoManual("ENTRADA", "5.00", categoria("Repasse do vendedor"))).isInstanceOf(DataAccessException.class);
        // O lançamento manual é a própria origem.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento)
                VALUES (?, 'SAIDA', -1, 'PIX', ?, 'MANUAL', ?, current_date)""", UUID.randomUUID(), saida, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void estornoManualPrecisaDesfazerUmManualDoMesmoValorEDeMotivo() {
        UUID saida = categoriaNova("SAIDA");
        UUID original = lancamentoManual("SAIDA", "-5.00", saida);
        UUID estorno = UUID.randomUUID();
        // Sem motivo: recusado.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, estorna_id)
                VALUES (?, 'SAIDA', 5.00, 'DINHEIRO', ?, 'MANUAL', ?, current_date, ?)""", estorno, saida, estorno, original))
                .isInstanceOf(DataAccessException.class);
        // Valor diferente: recusado.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, estorna_id, motivo)
                VALUES (?, 'SAIDA', 4.00, 'DINHEIRO', ?, 'MANUAL', ?, current_date, ?, 'erro')""", estorno, saida, estorno, original))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, estorna_id, motivo)
                VALUES (?, 'SAIDA', 5.00, 'DINHEIRO', ?, 'MANUAL', ?, current_date, ?, 'erro')""", estorno, saida, estorno, original))
                .doesNotThrowAnyException();
        // Estorno de estorno e segundo estorno: recusados.
        UUID outro = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, estorna_id, motivo)
                VALUES (?, 'SAIDA', -5.00, 'DINHEIRO', ?, 'MANUAL', ?, current_date, ?, 'de novo')""", outro, saida, outro, estorno))
                .isInstanceOf(DataAccessException.class);
        UUID segundo = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento, estorna_id, motivo)
                VALUES (?, 'SAIDA', 5.00, 'DINHEIRO', ?, 'MANUAL', ?, current_date, ?, 'duas vezes')""", segundo, saida, segundo, original))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void categoriaDoSistemaNaoMudaETipoNuncaMuda() {
        UUID sistema = categoria("Pagamento a fornecedor");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.categorias_caixa SET nome = 'Outra' WHERE id = ?", sistema)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.categorias_caixa SET ativo = false WHERE id = ?", sistema)).isInstanceOf(DataAccessException.class);
        UUID minha = categoriaNova("SAIDA");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.categorias_caixa SET tipo = 'ENTRADA' WHERE id = ?", minha)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.categorias_caixa SET sistema = true WHERE id = ?", minha)).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> jdbc.update("UPDATE corona.categorias_caixa SET ativo = false WHERE id = ?", minha)).doesNotThrowAnyException();
        // Inativa: não recebe lançamento manual novo.
        assertThatThrownBy(() -> lancamentoManual("SAIDA", "-5.00", minha)).isInstanceOf(DataAccessException.class);
    }

    // --- a semana fechada ---

    @Test
    void semanaSoFechaComORegistroDoFechamentoENuncaReabre() {
        UUID semana = semana(false);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.semanas SET situacao = 'FECHADA' WHERE id = ?", semana)).isInstanceOf(DataAccessException.class);
        fechar(semana);
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas WHERE id = ?", String.class, semana)).isEqualTo("FECHADA");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.semanas SET situacao = 'ABERTA' WHERE id = ?", semana)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.semanas SET fim = fim + 1 WHERE id = ?", semana)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.semanas SET numero = numero + 1 WHERE id = ?", semana)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.semanas SET deleted_at = now() WHERE id = ?", semana)).isInstanceOf(DataAccessException.class);
        // Nem apagando o fechamento (imutável) nem fechando de novo.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO corona.fechamentos_semana (id, semana_id, fechada_por) VALUES (?, ?, ?)",
                UUID.randomUUID(), semana, dona)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void lancamentoComDataEmSemanaFechadaERecusado() {
        UUID semana = semana(true);
        UUID saida = categoriaNova("SAIDA");
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento)
                VALUES (?, 'SAIDA', -5, 'DINHEIRO', ?, 'MANUAL', ?, ?)""", id, saida, id, inicioDa(semana))).isInstanceOf(DataAccessException.class);
        UUID aberta = semana(false);
        UUID outro = UUID.randomUUID();
        assertThatCode(() -> jdbc.update("""
                INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id, data_lancamento)
                VALUES (?, 'SAIDA', -5, 'DINHEIRO', ?, 'MANUAL', ?, ?)""", outro, saida, outro, inicioDa(aberta))).doesNotThrowAnyException();
    }

    @Test
    void acertoERemessaEmSemanaFechadaSaoRecusadosPeloBanco() {
        UUID semana = semana(true);
        UsuariosDeTeste.UsuarioDeTeste vendedor = usuarios.criarVendedor();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                VALUES (?, ?, ?, 'EM_ACERTO', false, now(), ?)""", UUID.randomUUID(), vendedor.vendedorId(), semana, dona))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.remessas (id, vendedor_id, semana_id, tipo, criada_por) VALUES (?, ?, ?, 'NOVA', ?)""", UUID.randomUUID(),
                vendedor.vendedorId(), semana, dona)).isInstanceOf(DataAccessException.class);
    }
}
