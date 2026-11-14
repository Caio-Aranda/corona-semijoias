package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.trocas.ApoioDaVendaAPrazo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * O que o PRÓPRIO BANCO garante na devolução de venda a prazo (tarefa 6.6; invariantes 2 a 5 do plano), com SQL direto: o
 * serviço pode errar, o banco não deixa gravar errado. Escrito antes da migração V042.
 */
class DevolucaoDeVendaAPrazoNoBancoTest extends ApoioDaVendaAPrazo {

    private APrazo c;
    private UUID devolucao;
    private UUID baixaDaDevolucao;

    @BeforeEach
    void umaDevolucaoDeUmaPeca() throws Exception {
        conciliar = false;
        c = vendaAPrazo(null, 3);
        devolucao = UUID.fromString(devolverAPrazoOk(c, 1).get("id").asString());
        baixaDaDevolucao = jdbc.queryForObject("""
                SELECT b.id FROM corona.baixas_receber b JOIN corona.contas_receber c ON c.id = b.conta_id
                 WHERE c.venda_id = ? AND b.devolucao_id = ?""", UUID.class, c.venda(), devolucao);
    }

    /** Estes testes gravam baixas que não fecham com a venda: ela sai de cena por SQL direto, como nas contas. */
    @AfterEach
    void tirarAVendaDeCena() {
        jdbc.update("""
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = now(), motivo_estorno = 'teste do banco'
                 WHERE id = ? AND status = 'CONCLUIDA'""", UUID.randomUUID(), c.venda());
    }

    private UUID conta(int parcela) {
        return (UUID) parcelas(c.venda()).get(parcela - 1).get("id");
    }

    private void baixa(UUID conta, String valor, UUID devolucaoId, UUID estorna) {
        jdbc.update("""
                INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, motivo_cancelamento, devolucao_id, estorna_id)
                VALUES (?, ?, 'CANCELAMENTO', ?, now(), 'DEVOLUCAO', ?, ?)""", UuidV7.gerar(), conta, new BigDecimal(valor),
                devolucaoId, estorna);
    }

    private static void recusa(Runnable comando) {
        assertThatThrownBy(comando::run).isInstanceOf(DataAccessException.class);
    }

    @Test
    void cancelamentoPorDevolucaoPrecisaDaDevolucaoDaMesmaVenda() throws Exception {
        recusa(() -> baixa(conta(1), "1.00", null, null));
        APrazo outra = vendaAPrazo(null, 3);
        UUID daOutra = UUID.fromString(devolverAPrazoOk(outra, 1).get("id").asString());
        recusa(() -> baixa(conta(1), "1.00", daOutra, null));
    }

    @Test
    void cancelamentoPorDevolucaoNaoPassaDoAbatidoDaDevolucao() {
        // A devolução abateu 33,33 e já o gastou na parcela 3: mais um centavo passa do abatido.
        recusa(() -> baixa(conta(1), "0.01", devolucao, null));
    }

    @Test
    void estornoDoCancelamentoPrecisaDesfazerOOriginalDaMesmaContaPeloMesmoValor() {
        recusa(() -> baixa(conta(3), "-10.00", devolucao, baixaDaDevolucao));
        recusa(() -> baixa(conta(1), "-33.33", devolucao, baixaDaDevolucao));
        baixa(conta(3), "-33.33", devolucao, baixaDaDevolucao);
        // O estorno duplicado e o cancelado negativo depois do estorno.
        recusa(() -> baixa(conta(3), "-33.33", devolucao, baixaDaDevolucao));
        assertThat(contar("SELECT count(*) FROM corona.baixas_receber WHERE estorna_id = ?", baixaDaDevolucao)).isEqualTo(1);
    }

    @Test
    void cancelamentoDeEstornoDaVendaNaoTemEstornoPorDevolucao() {
        UUID estornoDaVenda = UuidV7.gerar();
        jdbc.update("""
                INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, motivo_cancelamento)
                VALUES (?, ?, 'CANCELAMENTO', 5.00, now(), 'ESTORNO_VENDA')""", estornoDaVenda, conta(1));
        recusa(() -> baixa(conta(1), "-5.00", devolucao, estornoDaVenda));
    }

    @Test
    void parcelaParcialSoComPagoOuCanceladoParcialENuncaComOsDoisFechandoOValor() {
        // A parcela 3 está PARCIAL só com cancelamento (33,33 de 33,34): o banco aceita.
        assertThat(situacoes(c.venda()).get(2)).isEqualTo("PARCIAL");
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET status = 'ABERTA' WHERE id = ?", conta(3)));
        recusa(() -> jdbc.update("UPDATE corona.contas_receber SET status = 'PAGA' WHERE id = ?", conta(3)));
    }

    @Test
    void aDevolucaoNaoMudaOAbatidoENemSeApagaBaixa() {
        recusa(() -> jdbc.update("UPDATE corona.devolucoes SET valor_abatido = 1.00 WHERE id = ?", devolucao));
        recusa(() -> jdbc.update("DELETE FROM corona.baixas_receber WHERE id = ?", baixaDaDevolucao));
        recusa(() -> jdbc.update("UPDATE corona.baixas_receber SET valor = 1.00 WHERE id = ?", baixaDaDevolucao));
    }

    @Test
    void aSomaDeDinheiroCreditoEAbatidoEOValorDevolvidoEUmaIgualdadeDoBanco() {
        List<Map<String, Object>> definicoes = jdbc.queryForList("""
                SELECT conname, pg_get_constraintdef(oid) AS definicao FROM pg_constraint
                 WHERE conrelid = 'corona.devolucoes'::regclass AND conname IN ('devolucoes_dinheiro_e_credito_cabem',
                       'devolucoes_troca_em_credito')""");
        assertThat(definicoes).hasSize(2);
        assertThat(definicoes).allSatisfy(d -> assertThat((String) d.get("definicao")).contains("valor_abatido"));
        assertThat(definicoes.stream().filter(d -> d.get("conname").equals("devolucoes_dinheiro_e_credito_cabem"))
                .map(d -> (String) d.get("definicao")).findFirst().orElseThrow()).doesNotContain("<=");
    }
}
